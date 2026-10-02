package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    public enum LauncherIcon {
        // Glassgram variants; the alias names stay so the chosen icon survives updates.
        DEFAULT("DefaultIcon", R.drawable.glassgram_icon_pick_bg_dark, R.drawable.glassgram_icon_pick_fg_dark, R.string.GlassgramIconDark),
        VINTAGE("VintageIcon", R.drawable.glassgram_icon_pick_bg_violet, R.drawable.glassgram_icon_pick_fg_violet, R.string.GlassgramIconViolet),
        AQUA("AquaIcon", R.drawable.glassgram_icon_pick_bg_teal, R.drawable.glassgram_icon_pick_fg_teal, R.string.GlassgramIconTeal),
        PREMIUM("PremiumIcon", R.drawable.glassgram_icon_pick_bg_orange, R.drawable.glassgram_icon_pick_fg_orange, R.string.GlassgramIconOrange),
        TURBO("TurboIcon", R.drawable.glassgram_icon_pick_bg_pink, R.drawable.glassgram_icon_pick_fg_pink, R.string.GlassgramIconPink),
        NOX("NoxIcon", R.drawable.glassgram_icon_pick_bg_light, R.drawable.glassgram_icon_pick_fg_light, R.string.GlassgramIconLight);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
