package com.exteragram.messenger.utils;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * glassGram: exteraGram's AppUtils, the part plugins made for exteraGram use (zwylib logs Java
 * objects as JSON with its Gson).
 */
public final class AppUtils {

    private static Gson gson;

    private AppUtils() {
    }

    /**
     * As exteraGram's: whether version a is, to version b, what op says ("<", "<=", "==", "!=",
     * ">=", ">"), by their numbers ("12.10.6" is after "12.2.0"). Plugins' Java code (NoForwardLimit's
     * dex) asks it of BuildVars.BUILD_VERSION_STRING.
     */
    public static boolean compareVersions(String op, String a, String b) {
        final int c = compare(a, b);
        if (op == null) {
            return false;
        }
        switch (op.trim()) {
            case "<": return c < 0;
            case "<=": return c <= 0;
            case "=":
            case "==": return c == 0;
            case "!=": return c != 0;
            case ">=": return c >= 0;
            case ">": return c > 0;
            default: return false;
        }
    }

    private static int compare(String a, String b) {
        final String[] x = numbers(a), y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final long p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return p < q ? -1 : 1;
            }
        }
        return 0;
    }

    private static String[] numbers(String version) {
        final String digits = version == null ? "" : version.replaceAll("[^0-9]+", " ").trim();
        return digits.isEmpty() ? new String[0] : digits.split(" ");
    }

    private static long parse(String number) {
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** As exteraGram's: to the system log and the app's, with the error's trace when there is one. */
    public static void log(String message) {
        log(message, null);
    }

    public static void log(String message, Throwable error) {
        if (error != null) {
            android.util.Log.e("exteraGram", String.valueOf(message), error);
            org.telegram.messenger.FileLog.e(String.valueOf(message), error);
        } else {
            android.util.Log.i("exteraGram", String.valueOf(message));
            org.telegram.messenger.FileLog.d(String.valueOf(message));
        }
    }

    public static synchronized Gson getGson() {
        if (gson == null) {
            gson = new GsonBuilder()
                    .setPrettyPrinting()
                    .serializeSpecialFloatingPointValues()
                    // as exteraGram's: Android's own classes hold views and contexts, not data
                    .addSerializationExclusionStrategy(new ExclusionStrategy() {
                        @Override
                        public boolean shouldSkipField(FieldAttributes attributes) {
                            final Package pkg = attributes.getDeclaringClass().getPackage();
                            if (pkg == null) {
                                return false;
                            }
                            final String name = pkg.getName();
                            return name.startsWith("android.") || name.startsWith("androidx.");
                        }

                        @Override
                        public boolean shouldSkipClass(Class<?> clazz) {
                            return false;
                        }
                    })
                    .create();
        }
        return gson;
    }
}
