package org.telegram.messenger;

import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * glassGram: the app's font. The chosen family (bundled in assets/fonts/gg_*, static weights of
 * Google Fonts families under the OFL) becomes the process's default typeface, so every text that
 * names no font of its own takes it, and Telegram's bold (Roboto Medium) and italic take its
 * weights. The default typeface can only be set at start, through Android's hidden Typeface.setDefault;
 * a change takes a restart. "system" leaves everything as Telegram has it.
 */
public final class GlassgramFonts {

    public static final String SYSTEM = "system";

    private static final String[] IDS = { SYSTEM, "roboto", "inter", "golostext", "onest", "manrope", "nunito", "jetbrainsmono" };
    private static final String[] NAMES = { null, "Roboto", "Inter", "Golos", "Onest", "Manrope", "Nunito", "JetBrains Mono" };
    /** The weight that stands for Telegram's bold (Roboto Medium): heavier in the faces whose 500 looks light. */
    private static final int[] MEDIUM = { 0, 500, 600, 600, 600, 600, 700, 600 };

    private static final HashMap<String, Typeface> cache = new HashMap<>();
    private static boolean applied;
    private static Typeface systemDefault;
    private static Typeface regular, medium, bold, italic, mediumItalic;

    private GlassgramFonts() {
    }

    public static String[] ids() {
        return IDS.clone();
    }

    /** The font's name, or null for the system's. */
    public static String name(String id) {
        return NAMES[Math.max(0, index(id))];
    }

    public static int index(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    /** The regular weight of a font, for showing it: the system's own default for "system". */
    public static Typeface preview(String id) {
        final int i = index(id);
        if (i == 0) {
            return systemDefault != null ? systemDefault : Typeface.DEFAULT;
        }
        return load(IDS[i], 400);
    }

    /** Telegram's bold in the chosen font, or null for Telegram's own. */
    public static Typeface medium() {
        return medium;
    }

    /** The chosen font in place of one of Telegram's Roboto files, or null to keep that file. */
    public static Typeface replacement(String assetPath) {
        if (regular == null || assetPath == null) {
            return null;
        }
        switch (assetPath) {
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM:
                return medium;
            case AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM_ITALIC:
                return mediumItalic;
            case AndroidUtilities.TYPEFACE_ROBOTO_EXTRA_BOLD:
                return bold;
            case "fonts/ritalic.ttf":
                return italic;
            default:
                return null;
        }
    }

    /** At the app's start, before any text is drawn: makes the chosen font the default. */
    public static void apply() {
        if (applied) {
            return;
        }
        applied = true;
        systemDefault = Typeface.DEFAULT;
        final int i = index(GlassgramConfig.font);
        if (i == 0) {
            return;
        }
        try {
            final Typeface r = load(IDS[i], 400), m = load(IDS[i], MEDIUM[i]), b = load(IDS[i], 700);
            if (r == null || m == null || b == null) {
                return;
            }
            regular = r;
            medium = m;
            bold = b;
            italic = Typeface.create(r, Typeface.ITALIC);
            mediumItalic = Typeface.create(m, Typeface.ITALIC);
            setDefaults(Typeface.create(b, Typeface.ITALIC));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static Typeface load(String id, int weight) {
        final String path = "fonts/gg_" + id + "_" + weight + ".ttf";
        synchronized (cache) {
            Typeface t = cache.get(path);
            if (t == null) {
                try {
                    final AssetManager assets = ApplicationLoader.applicationContext.getAssets();
                    if (Build.VERSION.SDK_INT >= 26) {
                        // built before the default is replaced, it falls back to the system's fonts
                        // for what it lacks (emoji, other scripts)
                        t = new Typeface.Builder(assets, path).setWeight(weight).setItalic(false).setFallback("sans-serif").build();
                    } else {
                        t = Typeface.createFromAsset(assets, path);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    return null;
                }
                cache.put(path, t);
            }
            return t;
        }
    }

    @SuppressWarnings("unchecked")
    private static void setDefaults(Typeface boldItalic) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/graphics/Typeface;");
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        // what paints and texts without a typeface of their own are drawn with
        try {
            final Method setDefault = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, regular);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        setStatic("DEFAULT", regular);
        setStatic("DEFAULT_BOLD", bold);
        setStatic("SANS_SERIF", regular);
        // Typeface.defaultFromStyle, and so texts given only a style
        setStatic("sDefaults", new Typeface[] { regular, bold, italic, boldItalic });
        // Typeface.create("sans-serif"...) and the fonts named so in styles
        try {
            final Field field = Typeface.class.getDeclaredField("sSystemFontMap");
            field.setAccessible(true);
            final Map<String, Typeface> map = (Map<String, Typeface>) field.get(null);
            if (map != null) {
                map.put("sans-serif", regular);
                map.put("sans-serif-medium", medium);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void setStatic(String name, Object value) {
        try {
            final Field field = Typeface.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
