package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Chats behind glassGram's chat PIN: locked ones open only after it (or a fingerprint), hidden ones
 * are left out of the chat lists and found in their own list, opened with it. All of it is this
 * phone's own: Telegram's servers know nothing of it.
 * <p>
 * The PIN (its salted hash) and the lists are kept apart from glassGram's other settings, so a
 * settings backup carries none of them. A chat is named by its account's user id and its dialog
 * id, so it stays the same chat when accounts are added or removed.
 */
public final class GlassgramChatLock {

    private static final String PREFS = "glassgram_chat_lock";
    /** Away from the app this long, unlocked chats lock again. */
    private static final long RELOCK_AFTER_MS = 60_000;

    private static volatile Set<String> locked;
    private static volatile Set<String> hidden;
    private static final HashSet<String> unlocked = new HashSet<>();
    private static boolean hiddenOpen;
    private static long pausedAt;

    private GlassgramChatLock() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // The PIN

    public static boolean hasPin() {
        return prefs().contains("pinHash");
    }

    public static void setPin(String pin) {
        final byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        final String saltHex = Utilities.bytesToHex(salt);
        prefs().edit().putString("pinSalt", saltHex).putString("pinHash", hash(saltHex, pin)).apply();
    }

    public static boolean checkPin(String pin) {
        final SharedPreferences p = prefs();
        final String salt = p.getString("pinSalt", null);
        final String expected = p.getString("pinHash", null);
        return salt != null && expected != null && pin != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), hash(salt, pin).getBytes(StandardCharsets.US_ASCII));
    }

    /** Forgets the PIN, and with it every lock and every hidden chat: nothing could open them. */
    public static synchronized void removePin() {
        prefs().edit().clear().apply();
        locked = Collections.emptySet();
        hidden = Collections.emptySet();
        unlocked.clear();
        hiddenOpen = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramChatsHiddenChanged);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.dialogsNeedReload);
            }
        }
    }

    public static boolean useFingerprint() {
        return prefs().getBoolean("fingerprint", true);
    }

    public static void setUseFingerprint(boolean value) {
        prefs().edit().putBoolean("fingerprint", value).apply();
    }

    private static String hash(String salt, String pin) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            // stretched: a 4-digit PIN would otherwise fall to a quick guess of its hash
            byte[] value = pin.getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20000; i++) {
                digest.update(value);
                value = digest.digest();
                digest.update(salt.getBytes(StandardCharsets.US_ASCII));
            }
            return Utilities.bytesToHex(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // Which chats

    public static String key(int account, long dialogId) {
        return UserConfig.getInstance(account).getClientUserId() + ":" + dialogId;
    }

    private static Set<String> lockedSet() {
        Set<String> set = locked;
        if (set == null) {
            locked = set = Collections.unmodifiableSet(new HashSet<>(prefs().getStringSet("locked", Collections.emptySet())));
        }
        return set;
    }

    private static Set<String> hiddenSet() {
        Set<String> set = hidden;
        if (set == null) {
            hidden = set = Collections.unmodifiableSet(new HashSet<>(prefs().getStringSet("hidden", Collections.emptySet())));
        }
        return set;
    }

    public static boolean isLocked(int account, long dialogId) {
        final Set<String> set = lockedSet();
        return !set.isEmpty() && dialogId != 0 && set.contains(key(account, dialogId));
    }

    public static boolean isHidden(int account, long dialogId) {
        final Set<String> set = hiddenSet();
        return !set.isEmpty() && dialogId != 0 && set.contains(key(account, dialogId));
    }

    /** Whether the account hides any chat: the chat lists are left as they are when not. */
    public static boolean hasHidden(int account) {
        final Set<String> set = hiddenSet();
        if (set.isEmpty()) {
            return false;
        }
        final String prefix = UserConfig.getInstance(account).getClientUserId() + ":";
        for (String key : set) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void setLocked(int account, long dialogId, boolean value) {
        final HashSet<String> set = new HashSet<>(lockedSet());
        if (value ? set.add(key(account, dialogId)) : set.remove(key(account, dialogId))) {
            prefs().edit().putStringSet("locked", new HashSet<>(set)).apply();
            locked = Collections.unmodifiableSet(set);
        }
    }

    public static synchronized void setHidden(int account, long dialogId, boolean value) {
        final HashSet<String> set = new HashSet<>(hiddenSet());
        if (value ? set.add(key(account, dialogId)) : set.remove(key(account, dialogId))) {
            prefs().edit().putStringSet("hidden", new HashSet<>(set)).apply();
            hidden = Collections.unmodifiableSet(set);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.glassgramChatsHiddenChanged);
            // the chat lists, and search's recent chats and hints, are made again without it, or with it
            final NotificationCenter center = NotificationCenter.getInstance(account);
            center.postNotificationName(NotificationCenter.dialogsNeedReload);
            center.postNotificationName(NotificationCenter.needReloadRecentDialogsSearch);
            center.postNotificationName(NotificationCenter.reloadHints);
        }
    }

    /** The chats suggested in search and in the share sheet (top peers), hidden ones left out. */
    public static ArrayList<TLRPC.TL_topPeer> visibleHints(int account) {
        final ArrayList<TLRPC.TL_topPeer> hints = MediaDataController.getInstance(account).hints;
        if (!hasHidden(account)) {
            return hints;
        }
        final ArrayList<TLRPC.TL_topPeer> shown = new ArrayList<>(hints.size());
        for (int i = 0, n = hints.size(); i < n; i++) {
            final TLRPC.TL_topPeer peer = hints.get(i);
            if (peer == null || peer.peer == null || !isHidden(account, DialogObject.getPeerDialogId(peer.peer))) {
                shown.add(peer);
            }
        }
        return shown;
    }

    public static ArrayList<Long> lockedDialogs(int account) {
        return dialogs(lockedSet(), account);
    }

    public static ArrayList<Long> hiddenDialogs(int account) {
        return dialogs(hiddenSet(), account);
    }

    private static ArrayList<Long> dialogs(Set<String> set, int account) {
        final ArrayList<Long> result = new ArrayList<>();
        final String prefix = UserConfig.getInstance(account).getClientUserId() + ":";
        for (String key : set) {
            if (key.startsWith(prefix)) {
                try {
                    result.add(Long.parseLong(key.substring(prefix.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return result;
    }

    // Opened for now

    /** Whether opening the chat asks for the PIN now. */
    public static synchronized boolean needsUnlock(int account, long dialogId) {
        return isLocked(account, dialogId) && hasPin() && !unlocked.contains(key(account, dialogId));
    }

    public static synchronized void markUnlocked(int account, long dialogId) {
        unlocked.add(key(account, dialogId));
    }

    /** Whether the hidden chats were opened with the PIN and may be shown now. */
    public static synchronized boolean isHiddenOpen() {
        return hiddenOpen;
    }

    public static synchronized void setHiddenOpen(boolean value) {
        hiddenOpen = value;
    }

    /** The app went to the background. */
    public static synchronized void onAppPaused() {
        pausedAt = SystemClock.elapsedRealtime();
    }

    /** Back in the app: after long enough away, what was opened with the PIN is shut again. */
    public static synchronized void onAppResumed() {
        if (pausedAt != 0 && SystemClock.elapsedRealtime() - pausedAt >= RELOCK_AFTER_MS) {
            unlocked.clear();
            hiddenOpen = false;
        }
        pausedAt = 0;
    }

    public static boolean isValidPin(String pin) {
        return !TextUtils.isEmpty(pin) && pin.length() >= 4 && pin.length() <= 16 && TextUtils.isDigitsOnly(pin);
    }
}
