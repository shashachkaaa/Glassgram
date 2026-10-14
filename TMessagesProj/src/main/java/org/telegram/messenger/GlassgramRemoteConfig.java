package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * glassGram: rules for devices from glassGram's server (GET /config). A rule turns some of the
 * options off on the phones it matches, so a feature broken on a model is fixed without a release.
 * The app downloads every rule and matches them against its own phone: nothing about the phone is
 * sent for it. The rules are kept, so they hold from the start, before the network.
 * <p>
 * A rule changes the option's field in GlassgramConfig, not the stored choice: when the rule goes,
 * the person's own choice comes back. While it holds, the settings don't turn the option on.
 */
public final class GlassgramRemoteConfig {

    /** The options a rule may turn off: GlassgramConfig's fields, as the server allows them. */
    public static final String[] FEATURES = {
            "roundFps60", "roundStabilization", "roundHorizon", "roundWideCamera", "roundKeepZoom", "roundHighQuality",
            "glassHeader", "glassPanels", "splitInput", "iosTransitions", "uploadBoost", "downloadBoost", "pluginsEngine"};

    private static final String PREFS = "glassgram_remote";
    /** How often the app's opening asks again; every start of the app asks once at any rate. */
    public static final long REFRESH = 10 * 60 * 1000L;
    /** The settings ask again when opened after this. */
    public static final long REFRESH_SETTINGS = 60 * 1000L;

    /** The options turned off now, with the note of the rule that did it. */
    private static HashMap<String, String> forced = new HashMap<>();
    /** The person's own values of the options turned off, to give back when the rule goes. */
    private static final HashMap<String, Object> ownValues = new HashMap<>();
    private static boolean loading;
    /** When the rules were last downloaded in this run of the app: 0, so each start asks. */
    private static long loadedAt;

    private GlassgramRemoteConfig() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a rule turned this option off on this phone. */
    public static synchronized boolean isForced(String feature) {
        return feature != null && forced.containsKey(feature);
    }

    /** Why: the note of the rule that turned the option off, or an empty text. */
    public static synchronized String note(String feature) {
        final String note = forced.get(feature);
        return note != null ? note : "";
    }

    /** The kept rules, applied as GlassgramConfig loads its fields from the stored choices. */
    static synchronized void applyKept() {
        forced = new HashMap<>();
        ownValues.clear();
        try {
            final String kept = prefs().getString("rules", null);
            if (kept != null) {
                apply(new JSONArray(kept));
            }
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    /** Downloads the rules when the app opens: at its start, then every REFRESH. */
    public static void refresh() {
        refresh(REFRESH, null);
    }

    /**
     * Downloads the rules unless they were downloaded within maxAge (0: now, as after the team
     * changed one); onChanged runs when what is turned off here changed.
     */
    public static void refresh(long maxAge, Runnable onChanged) {
        if (loading || loadedAt != 0 && System.currentTimeMillis() - loadedAt < maxAge) {
            return;
        }
        loading = true;
        GlassgramApi.request("GET", "/config", null, null, response -> {
            loading = false;
            final JSONArray rules = response.json.optJSONArray("rules");
            if (!response.ok() || rules == null) {
                return;
            }
            loadedAt = System.currentTimeMillis();
            prefs().edit().putString("rules", rules.toString()).apply();
            if (apply(rules) && onChanged != null) {
                onChanged.run();
            }
        });
    }

    /**
     * Turns off what the rules matching this phone say, and gives back what no rule holds now;
     * whether what is turned off, or why, changed.
     */
    private static synchronized boolean apply(JSONArray rules) {
        final HashMap<String, String> now = new HashMap<>();
        for (int i = 0; i < rules.length(); i++) {
            final JSONObject rule = rules.optJSONObject(i);
            if (rule == null || !matches(rule.optJSONObject("match"))) {
                continue;
            }
            final JSONArray off = rule.optJSONArray("off");
            for (int j = 0; off != null && j < off.length(); j++) {
                final String feature = off.optString(j);
                if (isFeature(feature) && !now.containsKey(feature)) {
                    now.put(feature, rule.optString("note", ""));
                }
            }
        }
        for (String feature : FEATURES) {
            final boolean was = forced.containsKey(feature);
            final boolean is = now.containsKey(feature);
            if (is && !was) {
                ownValues.put(feature, get(feature));
                set(feature, offValue(feature));
            } else if (!is && was) {
                final Object own = ownValues.remove(feature);
                if (own != null) {
                    set(feature, own);
                }
            }
        }
        final boolean changed = !now.equals(forced);
        forced = now;
        return changed;
    }

    private static boolean isFeature(String feature) {
        for (String f : FEATURES) {
            if (f.equals(feature)) {
                return true;
            }
        }
        return false;
    }

    private static Object offValue(String feature) {
        return "downloadBoost".equals(feature) ? (Object) GlassgramConfig.BOOST_OFF : (Object) Boolean.FALSE;
    }

    private static Object get(String feature) {
        try {
            return GlassgramConfig.class.getField(feature).get(null);
        } catch (Exception e) {
            FileLog.e(e, false);
            return null;
        }
    }

    private static void set(String feature, Object value) {
        try {
            final Field field = GlassgramConfig.class.getField(feature);
            if (field.getType() == boolean.class && value instanceof Boolean || field.getType() == int.class && value instanceof Integer) {
                field.set(null, value);
            }
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    // --- Matching ----------------------------------------------------------------------------------

    /** Whether a rule's match holds for this phone; the server counts installs the same way. */
    static boolean matches(JSONObject match) {
        if (match == null) {
            return true;
        }
        final String soc = Build.VERSION.SDK_INT >= 31 ? (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim() : Build.HARDWARE;
        return text(match, "manufacturer", Build.MANUFACTURER)
                && text(match, "brand", Build.BRAND)
                && text(match, "model", Build.MODEL)
                && text(match, "device", Build.DEVICE)
                && text(match, "soc", soc)
                && text(match, "edition", GlassgramConfig.STORE_BUILD ? "rustore" : "full")
                && (!match.has("sdk_min") || Build.VERSION.SDK_INT >= match.optInt("sdk_min"))
                && (!match.has("sdk_max") || Build.VERSION.SDK_INT <= match.optInt("sdk_max"))
                && (!match.has("version_min") || compareVersions(BuildVars.GLASSGRAM_VERSION, match.optString("version_min")) >= 0)
                && (!match.has("version_max") || compareVersions(BuildVars.GLASSGRAM_VERSION, match.optString("version_max")) <= 0);
    }

    /** A text with * and ? and alternatives split by |, in any case. */
    private static boolean text(JSONObject match, String key, String value) {
        final String pattern = match.optString(key, "");
        if (TextUtils.isEmpty(pattern)) {
            return true;
        }
        final String subject = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String part : pattern.split("\\|")) {
            part = part.trim().toLowerCase(Locale.ROOT);
            if (!part.isEmpty() && glob(part).matcher(subject).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String glob) {
        final StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            final char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /** Versions compared by their numbers, as the server does: 1.8 is before 1.8.1, and 1.10 after 1.9. */
    public static int compareVersions(String a, String b) {
        final int[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i] != y[i]) {
                return x[i] < y[i] ? -1 : 1;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    private static int[] numbers(String version) {
        final Matcher matcher = Pattern.compile("\\d+").matcher(version == null ? "" : version);
        final int[] result = new int[4];
        int count = 0;
        while (count < 4 && matcher.find()) {
            try {
                result[count++] = Integer.parseInt(matcher.group());
            } catch (NumberFormatException e) {
                result[count++] = Integer.MAX_VALUE;
            }
        }
        final int[] trimmed = new int[count];
        System.arraycopy(result, 0, trimmed, 0, count);
        return trimmed;
    }
}
