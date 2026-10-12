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
        NOX("NoxIcon", R.drawable.glassgram_icon_pick_bg_light, R.drawable.glassgram_icon_pick_fg_light, R.string.GlassgramIconLight),
        // The icon pack of branding/gen_icon_pack.py
        OCEAN("GlassgramOceanIcon", R.drawable.glassgram_icon_bg_ocean, R.drawable.glassgram_icon_fg_ocean, R.string.GlassgramIconOcean),
        SUNSET("GlassgramSunsetIcon", R.drawable.glassgram_icon_bg_sunset, R.drawable.glassgram_icon_fg_sunset, R.string.GlassgramIconSunset),
        AURORA("GlassgramAuroraIcon", R.drawable.glassgram_icon_bg_aurora, R.drawable.glassgram_icon_fg_aurora, R.string.GlassgramIconAurora),
        AMETHYST("GlassgramAmethystIcon", R.drawable.glassgram_icon_bg_amethyst, R.drawable.glassgram_icon_fg_amethyst, R.string.GlassgramIconAmethyst),
        MINT("GlassgramMintIcon", R.drawable.glassgram_icon_bg_mint, R.drawable.glassgram_icon_fg_mint, R.string.GlassgramIconMint),
        RUBY("GlassgramRubyIcon", R.drawable.glassgram_icon_bg_ruby, R.drawable.glassgram_icon_fg_ruby, R.string.GlassgramIconRuby),
        CORAL("GlassgramCoralIcon", R.drawable.glassgram_icon_bg_coral, R.drawable.glassgram_icon_fg_coral, R.string.GlassgramIconCoral),
        CITRUS("GlassgramCitrusIcon", R.drawable.glassgram_icon_bg_citrus, R.drawable.glassgram_icon_fg_citrus, R.string.GlassgramIconCitrus),
        GOLD("GlassgramGoldIcon", R.drawable.glassgram_icon_bg_gold, R.drawable.glassgram_icon_fg_gold, R.string.GlassgramIconGold),
        GRAPHITE("GlassgramGraphiteIcon", R.drawable.glassgram_icon_bg_graphite, R.drawable.glassgram_icon_fg_graphite, R.string.GlassgramIconGraphite),
        BLACK("GlassgramBlackIcon", R.drawable.glassgram_icon_bg_black, R.drawable.glassgram_icon_fg_black, R.string.GlassgramIconBlack),
        PASTEL("GlassgramPastelIcon", R.drawable.glassgram_icon_bg_pastel, R.drawable.glassgram_icon_fg_pastel, R.string.GlassgramIconPastel),
        LIQUID("GlassgramLiquidIcon", R.drawable.glassgram_icon_bg_liquid, R.drawable.glassgram_icon_fg_liquid, R.string.GlassgramIconLiquid),
        HOLO("GlassgramHoloIcon", R.drawable.glassgram_icon_bg_holo, R.drawable.glassgram_icon_fg_holo, R.string.GlassgramIconHolo),
        SPACE("GlassgramSpaceIcon", R.drawable.glassgram_icon_bg_space, R.drawable.glassgram_icon_fg_space, R.string.GlassgramIconSpace),
        NEBULA("GlassgramNebulaIcon", R.drawable.glassgram_icon_bg_nebula, R.drawable.glassgram_icon_fg_nebula, R.string.GlassgramIconNebula),
        PLANET("GlassgramPlanetIcon", R.drawable.glassgram_icon_bg_planet, R.drawable.glassgram_icon_fg_planet, R.string.GlassgramIconPlanet),
        MIDNIGHT("GlassgramMidnightIcon", R.drawable.glassgram_icon_bg_midnight, R.drawable.glassgram_icon_fg_midnight, R.string.GlassgramIconMidnight),
        NEON("GlassgramNeonIcon", R.drawable.glassgram_icon_bg_neon, R.drawable.glassgram_icon_fg_neon, R.string.GlassgramIconNeon),
        CODE("GlassgramCodeIcon", R.drawable.glassgram_icon_bg_code, R.drawable.glassgram_icon_fg_code, R.string.GlassgramIconCode),
        LAVA("GlassgramLavaIcon", R.drawable.glassgram_icon_bg_lava, R.drawable.glassgram_icon_fg_lava, R.string.GlassgramIconLava),
        ICE("GlassgramIceIcon", R.drawable.glassgram_icon_bg_ice, R.drawable.glassgram_icon_fg_ice, R.string.GlassgramIconIce),
        BUBBLES("GlassgramBubblesIcon", R.drawable.glassgram_icon_bg_bubbles, R.drawable.glassgram_icon_fg_bubbles, R.string.GlassgramIconBubbles),
        FOREST("GlassgramForestIcon", R.drawable.glassgram_icon_bg_forest, R.drawable.glassgram_icon_fg_forest, R.string.GlassgramIconForest),
        SAKURA("GlassgramSakuraIcon", R.drawable.glassgram_icon_bg_sakura, R.drawable.glassgram_icon_fg_sakura, R.string.GlassgramIconSakura),
        RAINBOW("GlassgramRainbowIcon", R.drawable.glassgram_icon_bg_rainbow, R.drawable.glassgram_icon_fg_rainbow, R.string.GlassgramIconRainbow),
        PIXEL("GlassgramPixelIcon", R.drawable.glassgram_icon_bg_pixel, R.drawable.glassgram_icon_fg_pixel, R.string.GlassgramIconPixel),
        SKETCH("GlassgramSketchIcon", R.drawable.glassgram_icon_bg_sketch, R.drawable.glassgram_icon_fg_sketch, R.string.GlassgramIconSketch);

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
