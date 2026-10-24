package com.exteragram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.plugins.PluginsController;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * glassGram: exteraGram's settings class, for plugins made for exteraGram that import it
 * ({@code from com.exteragram.messenger import ExteraConfig}, as zwylib does): its plugin
 * settings as exteraGram has them, taken from glassGram's plugin engine, and its own
 * preferences, kept in "exteraconfig" as in exteraGram.
 */
public final class ExteraConfig {

    private static final Object sync = new Object();
    private static boolean configLoaded;

    public static SharedPreferences preferences;
    public static SharedPreferences.Editor editor;
    public static boolean pluginsEngine;
    public static boolean pluginsSafeMode;
    public static boolean pluginsDevMode;
    public static boolean pluginsCompactView;
    public static boolean pluginsDisableArtOpts;
    public static boolean pluginsPySdkAutoUpdate;
    public static boolean pluginsPySdkBetaVersions;
    public static long sdkUpdateScheduleTimestamp;
    public static Set<String> pinnedPlugins = Collections.emptySet();

    static {
        loadConfig();
    }

    private ExteraConfig() {
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            preferences = context.getSharedPreferences("exteraconfig", Context.MODE_PRIVATE);
            editor = preferences.edit();
            GlassgramConfig.load();
            // what glassGram's engine is, not a setting of its own
            pluginsEngine = PluginsController.isSupported() && GlassgramConfig.pluginsEngine;
            pluginsSafeMode = false;
            pluginsDevMode = preferences.getBoolean("pluginsDevMode", false);
            pluginsCompactView = preferences.getBoolean("pluginsCompactView", false);
            pluginsDisableArtOpts = preferences.getBoolean("pluginsDisableArtOpts", false);
            pluginsPySdkAutoUpdate = preferences.getBoolean("pluginsPySdkAutoUpdate", false);
            pluginsPySdkBetaVersions = preferences.getBoolean("pluginsPySdkBetaVersions", false);
            sdkUpdateScheduleTimestamp = preferences.getLong("sdkUpdateScheduleTimestamp", 0L);
            pinnedPlugins = new HashSet<>(preferences.getStringSet("pinnedPlugins", Collections.emptySet()));
            configLoaded = true;
        }
    }

    public static void reloadConfig() {
        synchronized (sync) {
            configLoaded = false;
        }
        loadConfig();
    }
}
