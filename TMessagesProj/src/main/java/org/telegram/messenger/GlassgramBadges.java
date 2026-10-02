package org.telegram.messenger;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CombinedDrawable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;

/**
 * Glassgram badges: custom marks next to the names of channels, chats, users and bots that the
 * Glassgram API hands out. The list is a JSON file (see glassgram-api/README.md) that is
 * downloaded every few hours and kept in a cache file between runs.
 */
public final class GlassgramBadges {

    /** The badges API. A static file in this repository; point it to your own server any time. */
    public static final String API_URL = "https://raw.githubusercontent.com/shashachkaaa/Telegram/master/glassgram-api/badges.json";

    private static final long REFRESH_INTERVAL = 3 * 60 * 60 * 1000L;
    private static final String CACHE_FILE = "glassgram_badges.json";

    public static final class Badge {
        public final String id;
        public final String icon;
        public final int color;
        public final HashMap<String, String> texts;

        Badge(String id, String icon, int color, HashMap<String, String> texts) {
            this.id = id;
            this.icon = icon;
            this.color = color;
            this.texts = texts;
        }

        /** The text shown when the badge is tapped, in the app language when the API has it. */
        public String getText() {
            String lang = LocaleController.getInstance().getCurrentLocaleInfo() != null
                    ? LocaleController.getInstance().getCurrentLocaleInfo().getLangCode() : Locale.getDefault().getLanguage();
            if (lang != null) {
                String text = texts.get(lang.toLowerCase(Locale.ROOT));
                if (text == null && lang.length() > 2) {
                    text = texts.get(lang.substring(0, 2).toLowerCase(Locale.ROOT));
                }
                if (text != null) {
                    return text;
                }
            }
            String text = texts.get("en");
            if (text == null && !texts.isEmpty()) {
                text = texts.values().iterator().next();
            }
            return text;
        }

        public int getIconResId() {
            switch (icon == null ? "" : icon) {
                case "check":
                    return R.drawable.glassgram_badge_check;
                case "star":
                    return R.drawable.glassgram_badge_star;
                case "heart":
                    return R.drawable.glassgram_badge_heart;
                case "bolt":
                    return R.drawable.glassgram_badge_bolt;
                case "crown":
                    return R.drawable.glassgram_badge_crown;
                case "code":
                    return R.drawable.glassgram_badge_code;
                case "shield":
                    return R.drawable.glassgram_badge_shield;
                case "arrow":
                default:
                    return R.drawable.glassgram_badge_arrow;
            }
        }

        /** The badge drawn at sizePx: the colored rosette with the white glyph on it. */
        public Drawable createDrawable(Context context, int sizePx) {
            Drawable shape = ContextCompat.getDrawable(context, R.drawable.glassgram_badge_shape).mutate();
            shape.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            Drawable glyph = ContextCompat.getDrawable(context, getIconResId()).mutate();
            CombinedDrawable drawable = new CombinedDrawable(shape, glyph);
            drawable.setCustomSize(sizePx, sizePx);
            drawable.setIconSize(sizePx, sizePx);
            drawable.setBounds(0, 0, sizePx, sizePx);
            return drawable;
        }
    }

    private static volatile HashMap<Long, Badge> byPeer = new HashMap<>();
    private static volatile HashMap<String, Badge> byUsername = new HashMap<>();
    private static boolean loadedCache;
    private static long lastFetch;
    private static boolean fetching;

    private GlassgramBadges() {
    }

    /** Loads the cached list and refreshes it from the API when it is older than a few hours. */
    public static void init() {
        Utilities.globalQueue.postRunnable(() -> {
            if (!loadedCache) {
                loadedCache = true;
                String cached = readFile(cacheFile());
                if (cached != null) {
                    apply(cached, false);
                }
            }
            refresh(false);
        });
    }

    public static void refresh(boolean force) {
        Utilities.globalQueue.postRunnable(() -> {
            long now = System.currentTimeMillis();
            if (fetching || !force && now - lastFetch < REFRESH_INTERVAL) {
                return;
            }
            fetching = true;
            lastFetch = now;
            Utilities.externalNetworkQueue.postRunnable(() -> {
                String json = download(API_URL);
                Utilities.globalQueue.postRunnable(() -> {
                    fetching = false;
                    if (json != null && apply(json, true)) {
                        writeFile(cacheFile(), json);
                    }
                });
            });
        });
    }

    private static File cacheFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), CACHE_FILE);
    }

    private static boolean apply(String json, boolean notify) {
        try {
            JSONObject root = new JSONObject(json);
            HashMap<String, Badge> badges = new HashMap<>();
            JSONObject badgesJson = root.optJSONObject("badges");
            if (badgesJson != null) {
                for (Iterator<String> it = badgesJson.keys(); it.hasNext(); ) {
                    String id = it.next();
                    JSONObject b = badgesJson.optJSONObject(id);
                    if (b == null) {
                        continue;
                    }
                    HashMap<String, String> texts = new HashMap<>();
                    Object text = b.opt("text");
                    if (text instanceof JSONObject) {
                        JSONObject textJson = (JSONObject) text;
                        for (Iterator<String> langs = textJson.keys(); langs.hasNext(); ) {
                            String lang = langs.next();
                            texts.put(lang.toLowerCase(Locale.ROOT), textJson.optString(lang));
                        }
                    } else if (text instanceof String) {
                        texts.put("en", (String) text);
                    }
                    int color;
                    try {
                        color = Color.parseColor(b.optString("color", "#2AABEE"));
                    } catch (IllegalArgumentException e) {
                        color = 0xFF2AABEE;
                    }
                    badges.put(id, new Badge(id, b.optString("icon", "arrow"), color, texts));
                }
            }
            HashMap<Long, Badge> peers = new HashMap<>();
            JSONObject peersJson = root.optJSONObject("peers");
            if (peersJson != null) {
                for (Iterator<String> it = peersJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Badge badge = badges.get(peersJson.optString(key));
                    Long dialogId = parseDialogId(key);
                    if (badge != null && dialogId != null) {
                        peers.put(dialogId, badge);
                    }
                }
            }
            HashMap<String, Badge> usernames = new HashMap<>();
            JSONObject usernamesJson = root.optJSONObject("usernames");
            if (usernamesJson != null) {
                for (Iterator<String> it = usernamesJson.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Badge badge = badges.get(usernamesJson.optString(key));
                    if (badge != null) {
                        usernames.put(normalizeUsername(key), badge);
                    }
                }
            }
            byPeer = peers;
            byUsername = usernames;
            if (notify) {
                AndroidUtilities.runOnUIThread(() -> {
                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (UserConfig.getInstance(a).isClientActivated()) {
                            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
                        }
                    }
                });
            }
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Telegram dialog id for an id written either the app way or the Bot API way (-100…). */
    private static Long parseDialogId(String key) {
        try {
            String trimmed = key.trim();
            if (trimmed.startsWith("-100") && trimmed.length() > 4) {
                return -Long.parseLong(trimmed.substring(4));
            }
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizeUsername(String username) {
        String result = username.trim();
        if (result.startsWith("@")) {
            result = result.substring(1);
        }
        return result.toLowerCase(Locale.ROOT);
    }

    public static Badge get(TLObject peer) {
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            Badge badge = byPeer.get(user.id);
            return badge != null ? badge : byUsernames(UserObject.getPublicUsername(user));
        } else if (peer instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) peer;
            Badge badge = byPeer.get(-chat.id);
            return badge != null ? badge : byUsernames(ChatObject.getPublicUsername(chat));
        }
        return null;
    }

    public static Badge get(int account, long dialogId) {
        if (byPeer.isEmpty() && byUsername.isEmpty()) {
            return null;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            return user != null ? get(user) : byPeer.get(dialogId);
        } else if (DialogObject.isChatDialog(dialogId)) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            return chat != null ? get(chat) : byPeer.get(dialogId);
        }
        return null;
    }

    private static Badge byUsernames(String username) {
        if (TextUtils.isEmpty(username)) {
            return null;
        }
        return byUsername.get(normalizeUsername(username));
    }

    /** The name followed by the peer's badge, when it has one. */
    public static CharSequence withBadge(CharSequence name, TLObject peer, int sizeDp) {
        return withBadge(name, get(peer), sizeDp);
    }

    public static CharSequence withBadge(CharSequence name, Badge badge, int sizeDp) {
        if (badge == null || name == null) {
            return name;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(name);
        builder.append("  ");
        ColoredImageSpan span = new ColoredImageSpan(badge.createDrawable(ApplicationLoader.applicationContext, AndroidUtilities.dp(sizeDp)), ColoredImageSpan.ALIGN_CENTER);
        span.recolorDrawable = false;
        span.setSize(AndroidUtilities.dp(sizeDp));
        builder.setSpan(span, builder.length() - 1, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    private static String download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Glassgram/" + BuildVars.BUILD_VERSION_STRING);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    if (out.size() > 4 * 1024 * 1024) {
                        return null;
                    }
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readFile(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File file, String text) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
