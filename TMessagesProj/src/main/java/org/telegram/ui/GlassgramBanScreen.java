package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.GlassgramApi;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * glassGram: an account banned from glassGram by its team closes the whole app while it is signed
 * in here: what is left is to sign it out, or to use the official Telegram. The list of banned
 * accounts comes from glassGram's server (GlassgramApi.loadBans) and is kept between starts.
 */
public final class GlassgramBanScreen {

    /** How often the list is asked for again while the app is open. */
    private static final long RECHECK = 2 * 60 * 1000L;

    private static AlertDialog shown;
    private static WeakReference<Activity> current;
    private static boolean watching;
    private static final boolean[] listening = new boolean[UserConfig.MAX_ACCOUNT_COUNT];

    private static final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            final Activity activity = current != null ? current.get() : null;
            if (activity == null || activity.isFinishing()) {
                watching = false;
                return;
            }
            if (!ApplicationLoader.mainInterfacePaused) {
                GlassgramApi.loadBans(GlassgramApi.BANS_REFRESH, () -> show(activity));
            }
            AndroidUtilities.runOnUIThread(this, RECHECK);
        }
    };

    /** The bot telling an account of its ban: the list is asked for at once, not at the next check. */
    private static final NotificationCenter.NotificationCenterDelegate fromBot = (id, account, args) -> {
        if (id != NotificationCenter.didReceiveNewMessages || args.length < 2 || !(args[1] instanceof ArrayList)) {
            return;
        }
        final TLRPC.User user = MessagesController.getInstance(account).getUser((Long) args[0]);
        if (user == null || !user.bot || !GlassgramApi.BOT_USERNAME.equalsIgnoreCase(UserObject.getPublicUsername(user))) {
            return;
        }
        for (Object message : (ArrayList<?>) args[1]) {
            if (message instanceof MessageObject && !((MessageObject) message).isOut()) {
                AndroidUtilities.runOnUIThread(() -> {
                    final Activity activity = current != null ? current.get() : null;
                    GlassgramApi.loadBans(0, () -> show(activity));
                }, 1000);
                return;
            }
        }
    };

    private GlassgramBanScreen() {
    }

    /**
     * When the app opens: asks for the list again if it is older than a little while, and closes the
     * app if an account here is banned; while it is open, asks again every few minutes, and at once
     * when glassGram's bot writes to an account here (it tells of a ban as it is made).
     */
    public static void checkOnOpen(Activity activity) {
        current = new WeakReference<>(activity);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!listening[a] && UserConfig.getInstance(a).isClientActivated()) {
                listening[a] = true;
                NotificationCenter.getInstance(a).addObserver(fromBot, NotificationCenter.didReceiveNewMessages);
            }
        }
        show(activity);
        GlassgramApi.loadBans(GlassgramApi.BANS_REFRESH, () -> show(activity));
        if (!watching) {
            watching = true;
            AndroidUtilities.runOnUIThread(recheck, RECHECK);
        }
    }

    private static void show(Activity activity) {
        final GlassgramApi.Ban ban = GlassgramApi.findBan();
        if (ban == null) {
            if (shown != null) {
                shown.dismiss();
                shown = null;
            }
            return;
        }
        if (activity == null || activity.isFinishing() || shown != null && shown.isShowing()) {
            return;
        }
        final TLRPC.User user = MessagesController.getInstance(ban.account).getUser(ban.userId);
        String who = user != null ? UserObject.getUserName(user) : String.valueOf(ban.userId);
        if (user != null && !TextUtils.isEmpty(user.username)) {
            who += " (@" + user.username + ")";
        }
        final String text = TextUtils.isEmpty(ban.reason)
                ? formatString(R.string.GlassgramBannedText, who)
                : formatString(R.string.GlassgramBannedTextReason, who, ban.reason);
        shown = new AlertDialog.Builder(activity)
                .setTitle(getString(R.string.GlassgramBannedTitle))
                .setMessage(AndroidUtilities.replaceTags(text))
                .setPositiveButton(getString(R.string.GlassgramBannedSignOut), (d, w) -> {
                    shown = null;
                    if (UserConfig.getInstance(ban.account).isClientActivated()) {
                        MessagesController.getInstance(ban.account).performLogout(1);
                    }
                    // another banned account, or the same until it is out
                    AndroidUtilities.runOnUIThread(() -> show(activity), 1500);
                })
                .setNegativeButton(getString(R.string.GlassgramBannedTelegram), (d, w) -> {
                    shown = null;
                    org.telegram.messenger.browser.Browser.openUrl(activity, "https://play.google.com/store/apps/details?id=org.telegram.messenger");
                    // the app stays closed when the person comes back
                    AndroidUtilities.runOnUIThread(() -> show(activity), 1500);
                })
                .create();
        shown.setCancelable(false);
        shown.setCanceledOnTouchOutside(false);
        shown.show();
    }
}
