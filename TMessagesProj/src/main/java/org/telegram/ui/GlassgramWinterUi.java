package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramWinter;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FireworksOverlay;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ShareAlert;

/** glassGram: the winter look's screens (GlassgramWinter): the New Year's greeting and the winter icon. */
public final class GlassgramWinterUi {

    private GlassgramWinterUi() {
    }

    /**
     * Confetti over the screen, then a card that greets with the new year, to share. Once a year,
     * on the first open after midnight on January 1 (marked here).
     */
    public static void showNewYear(BaseFragment fragment, FrameLayout overlay) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final int year = GlassgramWinter.greetingYearShown();
        GlassgramWinter.markGreeted();
        if (overlay != null && LiteMode.isEnabled(LiteMode.FLAG_PARTICLES)) {
            final FireworksOverlay fireworks = new FireworksOverlay(activity);
            overlay.addView(fireworks, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            fireworks.start();
            AndroidUtilities.runOnUIThread(() -> AndroidUtilities.removeFromParent(fireworks), 7000);
        }
        // the confetti first, the card as it falls
        AndroidUtilities.runOnUIThread(() -> {
            if (fragment.getParentActivity() == null || fragment.isRemovingFromStack() || fragment.isFinished) {
                return;
            }
            final AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
            // the winter night of the winter icon behind the gift
            builder.setTopAnimation(R.raw.gift, 96, false, 0xFF1D3A7A);
            builder.setTitle(formatString(R.string.GlassgramNewYearTitle, year));
            builder.setMessage(getString(R.string.GlassgramNewYearText));
            builder.setPositiveButton(getString(R.string.GlassgramNewYearThanks), null);
            builder.setNeutralButton(getString(R.string.GlassgramNewYearShare), (dialog, which) -> share(fragment, year));
            fragment.showDialog(builder.create());
        }, overlay != null ? 1300 : 0);
    }

    private static void share(BaseFragment fragment, int year) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        final String text = formatString(R.string.GlassgramNewYearShareText, year);
        fragment.showDialog(ShareAlert.createShareAlert(fragment.getParentActivity(), null, text, false, text, false));
    }

    /** Once, as the winter comes: a bulletin that puts on the winter icon. */
    public static void offerIcon(BaseFragment fragment) {
        GlassgramWinter.markIconOffered();
        if (isWinterIcon()) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.GlassgramWinterIconOffer),
                getString(R.string.GlassgramWinterIconSet), () -> setWinterIcon(true)).show();
    }

    /** Once, in the winter: a bulletin that offers the New Year hat for one's avatar, unless one has it. */
    public static void offerHat(BaseFragment fragment) {
        GlassgramWinter.markHatOffered();
        if (org.telegram.messenger.GlassgramBadges.getHat(fragment.getUserConfig().getClientUserId()) != null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.gift, getString(R.string.GlassgramWinterHatOffer),
                getString(R.string.GlassgramWinterHatBuy), () -> buyHat(fragment)).show();
    }

    /** The bot that sells the hat, opened on it: it is paid there and worn on the next open of the app. */
    public static void buyHat(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        org.telegram.messenger.browser.Browser.openUrl(fragment.getParentActivity(),
                "https://t.me/" + org.telegram.messenger.GlassgramBadges.getShopBot() + "?start=hat");
    }

    public static boolean isWinterIcon() {
        try {
            return LauncherIconController.isEnabled(LauncherIconController.LauncherIcon.WINTER);
        } catch (Exception e) {
            return false;
        }
    }

    /** Puts on the winter icon, keeping the one before it; or gives that one back. */
    public static void setWinterIcon(boolean winter) {
        try {
            if (winter) {
                for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
                    if (icon != LauncherIconController.LauncherIcon.WINTER && LauncherIconController.isEnabled(icon)) {
                        GlassgramWinter.setIconBeforeWinter(icon.key);
                        break;
                    }
                }
                LauncherIconController.setIcon(LauncherIconController.LauncherIcon.WINTER);
            } else {
                LauncherIconController.LauncherIcon before = LauncherIconController.LauncherIcon.DEFAULT;
                final String key = GlassgramWinter.iconBeforeWinter();
                for (LauncherIconController.LauncherIcon icon : LauncherIconController.LauncherIcon.values()) {
                    if (icon.key.equals(key) && icon != LauncherIconController.LauncherIcon.WINTER) {
                        before = icon;
                    }
                }
                LauncherIconController.setIcon(before);
            }
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }
}
