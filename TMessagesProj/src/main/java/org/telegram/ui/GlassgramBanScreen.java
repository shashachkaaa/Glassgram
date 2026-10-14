package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramApi;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * glassGram: an account banned from glassGram by its team closes the whole app while it is signed
 * in here: what is left is to sign it out, or to use the official Telegram. The list of banned
 * accounts comes from glassGram's server (GlassgramApi.loadBans) and is kept between starts.
 */
public final class GlassgramBanScreen {

    private static AlertDialog shown;

    private GlassgramBanScreen() {
    }

    /** When the app opens: asks for the list again if due, and closes the app if an account here is banned. */
    public static void checkOnOpen(Activity activity) {
        show(activity);
        GlassgramApi.loadBans(GlassgramApi.BANS_REFRESH, () -> show(activity));
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
