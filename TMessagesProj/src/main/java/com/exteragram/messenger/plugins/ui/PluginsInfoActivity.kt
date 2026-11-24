package com.exteragram.messenger.plugins.ui

import android.view.View
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.GlassgramPluginEngineActivity

/**
 * glassGram: exteraGram's screen of the plugin engine's settings, which the SDK's ElyxCore adds its
 * own rows to (it hooks fillItems and onClick, so they are declared here). It is glassGram's.
 */
class PluginsInfoActivity : GlassgramPluginEngineActivity() {

    enum class PreferenceItem {
        DEVELOPER_MODE,
        COMPACT_VIEW,
        SAFE_MODE,
        SDK_AUTO_UPDATE,
        SDK_BETA_VERSIONS,
        CHECK_SDK_UPDATES,
        RESTORE_SDK_FROM_APK,
        DOCUMENTATION,
        TRUSTED_PLUGINS,
        PLUGINS_DISABLE_ART_OPTS,
        SDK_HEADER,
        UNKNOWN_SOURCES,
        STATIC_ANALYSIS,
        SENSITIVE_REQUESTS,
        CHECK_ANALYZER_UPDATES;

        fun getId(): Int = ordinal + 1
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        super.fillItems(items, adapter)
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        super.onClick(item, view, position, x, y)
    }
}
