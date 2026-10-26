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
