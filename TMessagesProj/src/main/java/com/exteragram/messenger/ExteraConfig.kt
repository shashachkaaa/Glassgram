/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

 Modified for glassGram, 2026: only the plugin engine's settings, as fields (@JvmField) so that
 Java and plugins read them directly; whether the engine is on is glassGram's own setting
 (GlassgramConfig.pluginsEngine).

*/

@file:JvmName("ExteraConfig")

package com.exteragram.messenger

import android.app.Activity
import android.content.SharedPreferences
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.GlassgramConfig

@JvmField
val preferences: SharedPreferences =
    ApplicationLoader.applicationContext.getSharedPreferences("exteraconfig", Activity.MODE_PRIVATE)

@JvmField
val editor: SharedPreferences.Editor = preferences.edit()

@JvmField
var sdkUpdateScheduleTimestamp = 0L

@JvmField
var pluginsEngine = false

@JvmField
var pluginsDevMode = false

@JvmField
var pluginsSafeMode = false

@JvmField
var pluginsCompactView = false

@JvmField
var pluginsPySdkAutoUpdate = false

@JvmField
var pluginsPySdkBetaVersions = false

@JvmField
var pluginsDisableArtOpts = false

@JvmField
var pinnedPlugins: Set<String> = emptySet()

private var loaded = false

fun loadConfig() {
    synchronized(preferences) {
        if (loaded) {
            return
        }
        GlassgramConfig.load()
        pluginsEngine = GlassgramConfig.pluginsEngine
        pluginsDevMode = preferences.getBoolean("pluginsDevMode", false)
        pluginsSafeMode = preferences.getBoolean("pluginsSafeMode", false)
        pluginsCompactView = preferences.getBoolean("pluginsCompactView", false)
        pluginsPySdkAutoUpdate = preferences.getBoolean("pluginsPySdkAutoUpdate", false)
        pluginsPySdkBetaVersions = preferences.getBoolean("pluginsPySdkBetaVersions", false)
        pluginsDisableArtOpts = preferences.getBoolean("pluginsDisableArtOpts", false)
        sdkUpdateScheduleTimestamp = preferences.getLong("sdkUpdateScheduleTimestamp", 0L)
        pinnedPlugins = HashSet(preferences.getStringSet("pinnedPlugins", emptySet()) ?: emptySet())
        loaded = true
    }
}

fun reloadConfig() {
    synchronized(preferences) {
        loaded = false
    }
    loadConfig()
}

/** glassGram: saves a setting of the plugin engine and keeps its field (pluginsDevMode, ...). */
fun setPluginsSetting(key: String, value: Boolean) {
    when (key) {
        "pluginsDevMode" -> pluginsDevMode = value
        "pluginsSafeMode" -> pluginsSafeMode = value
        "pluginsCompactView" -> pluginsCompactView = value
        "pluginsPySdkAutoUpdate" -> pluginsPySdkAutoUpdate = value
        "pluginsPySdkBetaVersions" -> pluginsPySdkBetaVersions = value
        "pluginsDisableArtOpts" -> pluginsDisableArtOpts = value
        else -> throw IllegalArgumentException(key)
    }
    editor.putBoolean(key, value).apply()
}
