package org.telegram.messenger;

import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * glassGram: the app's font. The chosen family (bundled in assets/fonts/gg_*, static weights of
 * Google Fonts families under the OFL) becomes the process's default typeface, so every text that
 * names no font of its own takes it, and Telegram's bold (Roboto Medium) and italic take its
 * weights. The default typeface can only be set at start, through Android's hidden Typeface.setDefault;
 * a change takes a restart. "system" leaves everything as Telegram has it.
 *
 * The user's own fonts (a .ttf or .otf file) are copied to files/glassgram_fonts and named
 * "file:" + the file's name. A variable font gives its own weights; a static one is drawn bolder
 * by Android for the bold.
 */
public final class GlassgramFonts {

    public static final String SYSTEM = "system";
    private static final String CUSTOM = "file:";
    private static final long MAX_CUSTOM_SIZE = 30 * 1024 * 1024;

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

    /** The bundled fonts, then the user's own. */
    public static String[] ids() {
        final ArrayList<String> ids = new ArrayList<>(Arrays.asList(IDS));
        ids.addAll(customIds());
        return ids.toArray(new String[0]);
    }

    /** The font's name, or null for the system's. */
    public static String name(String id) {
        if (isCustom(id)) {
            final String file = id.substring(CUSTOM.length());
            final int dot = file.lastIndexOf('.');
            return dot > 0 ? file.substring(0, dot) : file;
        }
        return NAMES[Math.max(0, index(id))];
    }

    public static boolean isCustom(String id) {
        return id != null && id.startsWith(CUSTOM);
    }

    // --- The user's own fonts ---------------------------------------------------------------------

    private static File customDir() {
        return new File(ApplicationLoader.getFilesDirFixed(), "glassgram_fonts");
    }

    private static File customFile(String id) {
        final String name = id.substring(CUSTOM.length());
        if (name.isEmpty() || name.contains("/") || name.startsWith(".")) {
            return null;
        }
        return new File(customDir(), name);
    }

    /** The user's fonts, by name. */
    public static ArrayList<String> customIds() {
        final ArrayList<String> ids = new ArrayList<>();
        final File[] files = customDir().listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : files) {
                if (file.isFile() && !file.getName().startsWith(".")) {
                    ids.add(CUSTOM + file.getName());
                }
            }
        }
        return ids;
    }

    /**
     * Copies a font file the user picked into the app, after checking that Android can draw with
     * it: its id, or null when it is no font (or too big).
     */
    public static String importFont(Uri uri) {
        final File dir = customDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        String name = displayName(uri);
        if (name == null || name.trim().isEmpty()) {
            name = "font.ttf";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        final String lower = name.toLowerCase();
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            name += ".ttf";
        }
        final File temp = new File(dir, ".import");
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                return null;
            }
            final byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_CUSTOM_SIZE) {
                    throw new IllegalArgumentException("too big");
                }
                out.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            temp.delete();
            return null;
        }
        if (!isFont(temp)) {
            temp.delete();
            return null;
        }
        // a name of its own, the same file again replacing nothing
        final int dot = name.lastIndexOf('.');
        final String base = name.substring(0, dot), extension = name.substring(dot);
        File file = new File(dir, name);
        for (int i = 2; file.exists(); i++) {
            file = new File(dir, base + " " + i + extension);
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            return null;
        }
        return CUSTOM + file.getName();
    }

    /** Deletes one of the user's fonts; the font in use stays until the next start. */
    public static void deleteFont(String id) {
        final File file = isCustom(id) ? customFile(id) : null;
        if (file != null) {
            file.delete();
        }
        synchronized (cache) {
            cache.keySet().removeIf(key -> key.startsWith(id + "@"));
        }
    }

    private static String displayName(Uri uri) {
        try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        final String path = uri.getLastPathSegment();
        return path == null ? null : path.substring(path.lastIndexOf('/') + 1);
    }

    /** Whether Android reads the file as a font (TrueType or OpenType, with its tables). */
    private static boolean isFont(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            final int tag = f.readInt();
            // 0x00010000 TrueType, 'OTTO' OpenType (CFF), 'true' Apple TrueType
            if (tag != 0x00010000 && tag != 0x4F54544F && tag != 0x74727565) {
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
        try {
            // a file Android can't read gives the default font back
            final Typeface t = Typeface.createFromFile(file);
            return t != null && t != Typeface.DEFAULT && t != systemDefault;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Whether the font varies by weight ('wght' in its 'fvar' table), so its bold is its own. */
    private static boolean hasWeightAxis(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            f.seek(4);
            final int tables = f.readUnsignedShort();
            for (int i = 0; i < tables && i < 512; i++) {
                f.seek(12 + 16L * i);
                final int tag = f.readInt();
                if (tag != 0x66766172) { // 'fvar'
                    continue;
                }
                f.skipBytes(4);
                final long offset = f.readInt() & 0xFFFFFFFFL;
                f.seek(offset + 4);
                final int axesOffset = f.readUnsignedShort();
                f.skipBytes(2);
                final int axes = f.readUnsignedShort();
                final int axisSize = f.readUnsignedShort();
                for (int a = 0; a < axes && a < 64; a++) {
                    f.seek(offset + axesOffset + (long) a * axisSize);
                    if (f.readInt() == 0x77676874) { // 'wght'
                        return true;
                    }
                }
                return false;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    private static Typeface loadCustom(String id, int weight) {
        final File file = customFile(id);
        if (file == null || !file.isFile()) {
            return null;
        }
        final String key = id + "@" + weight;
        synchronized (cache) {
            Typeface t = cache.get(key);
            if (t != null) {
                return t;
            }
            try {
                if (Build.VERSION.SDK_INT >= 26 && hasWeightAxis(file)) {
                    t = new Typeface.Builder(file).setFontVariationSettings("'wght' " + weight)
                            .setWeight(weight).setItalic(false).setFallback("sans-serif").build();
                } else {
                    final Typeface base;
                    if (Build.VERSION.SDK_INT >= 26) {
                        base = new Typeface.Builder(file).setWeight(400).setItalic(false).setFallback("sans-serif").build();
                    } else {
                        base = Typeface.createFromFile(file);
                    }
                    // a static face is made bolder by Android itself
                    if (weight >= 600) {
                        t = Build.VERSION.SDK_INT >= 28 ? Typeface.create(base, weight, false) : Typeface.create(base, Typeface.BOLD);
                    } else {
                        t = base;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
            cache.put(key, t);
            return t;
        }
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
        if (isCustom(id)) {
            return loadCustom(id, 400);
        }
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
        final String id = GlassgramConfig.font;
        final boolean custom = isCustom(id);
        final int i = index(id);
        if (!custom && i == 0) {
            return;
        }
        try {
            final Typeface r, m, b;
            if (custom) {
                r = loadCustom(id, 400);
                m = loadCustom(id, 600);
                b = loadCustom(id, 700);
            } else {
                r = load(IDS[i], 400);
                m = load(IDS[i], MEDIUM[i]);
                b = load(IDS[i], 700);
            }
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
