package org.telegram.messenger;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * glassGram: its settings saved to a file and restored from one, for a new phone or after
 * reinstalling. The file has every stored choice of Settings → glassGram, by its type; restoring
 * replaces them all. The spy's saved messages have their own export.
 */
public final class GlassgramBackup {

    private static final int FORMAT = 1;
    private static final int MAX_SIZE = 1024 * 1024;

    private GlassgramBackup() {
    }

    /** Writes the settings to a file the person picked; the number of settings, or -1. */
    public static int export(Uri uri) {
        final JSONObject booleans = new JSONObject(), ints = new JSONObject(), longs = new JSONObject(),
                floats = new JSONObject(), strings = new JSONObject(), sets = new JSONObject();
        int count = 0;
        try {
            for (Map.Entry<String, ?> entry : GlassgramConfig.preferences().getAll().entrySet()) {
                final Object value = entry.getValue();
                if (value instanceof Boolean) {
                    booleans.put(entry.getKey(), value);
                } else if (value instanceof Integer) {
                    ints.put(entry.getKey(), value);
                } else if (value instanceof Long) {
                    longs.put(entry.getKey(), value);
                } else if (value instanceof Float) {
                    floats.put(entry.getKey(), ((Float) value).doubleValue());
                } else if (value instanceof String) {
                    strings.put(entry.getKey(), value);
                } else if (value instanceof Set) {
                    final JSONArray array = new JSONArray();
                    for (Object item : (Set<?>) value) {
                        array.put(String.valueOf(item));
                    }
                    sets.put(entry.getKey(), array);
                } else {
                    continue;
                }
                count++;
            }
            final JSONObject backup = new JSONObject();
            backup.put("glassgram_backup", FORMAT);
            backup.put("version", BuildVars.GLASSGRAM_VERSION);
            backup.put("created_at", System.currentTimeMillis() / 1000);
            backup.put("booleans", booleans);
            backup.put("ints", ints);
            backup.put("longs", longs);
            backup.put("floats", floats);
            backup.put("strings", strings);
            backup.put("string_sets", sets);
            try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    return -1;
                }
                out.write(backup.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return count;
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        }
    }

    /** Replaces the settings with a file's; the number of settings restored, or -1 when it isn't a glassGram backup. */
    public static int restore(Uri uri) {
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    return -1;
                }
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > MAX_SIZE) {
                        return -1;
                    }
                }
            }
            final JSONObject backup = new JSONObject(buffer.toString("UTF-8"));
            if (backup.optInt("glassgram_backup") < 1) {
                return -1;
            }
            final SharedPreferences.Editor editor = GlassgramConfig.preferences().edit().clear();
            int count = 0;
            count += put(backup.optJSONObject("booleans"), (key, value) -> {
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value); else return false;
                return true;
            });
            count += put(backup.optJSONObject("ints"), (key, value) -> {
                if (value instanceof Number) editor.putInt(key, ((Number) value).intValue()); else return false;
                return true;
            });
            count += put(backup.optJSONObject("longs"), (key, value) -> {
                if (value instanceof Number) editor.putLong(key, ((Number) value).longValue()); else return false;
                return true;
            });
            count += put(backup.optJSONObject("floats"), (key, value) -> {
                if (value instanceof Number) editor.putFloat(key, ((Number) value).floatValue()); else return false;
                return true;
            });
            count += put(backup.optJSONObject("strings"), (key, value) -> {
                if (value instanceof String) editor.putString(key, (String) value); else return false;
                return true;
            });
            count += put(backup.optJSONObject("string_sets"), (key, value) -> {
                if (!(value instanceof JSONArray)) return false;
                final Set<String> set = new HashSet<>();
                for (int i = 0; i < ((JSONArray) value).length(); i++) {
                    set.add(((JSONArray) value).optString(i));
                }
                editor.putStringSet(key, set);
                return true;
            });
            editor.commit();
            GlassgramConfig.reload();
            return count;
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        }
    }

    private interface Setter {
        boolean put(String key, Object value);
    }

    private static int put(JSONObject values, Setter setter) {
        if (values == null) {
            return 0;
        }
        int count = 0;
        final Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            if (setter.put(key, values.opt(key))) {
                count++;
            }
        }
        return count;
    }
}
