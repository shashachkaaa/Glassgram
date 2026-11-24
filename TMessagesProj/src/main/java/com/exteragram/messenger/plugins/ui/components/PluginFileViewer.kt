package com.exteragram.messenger.plugins.ui.components

import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.GlassgramPluginInstaller
import java.io.File

/**
 * glassGram: where exteraGram's engine shows a plugin's code. exteraGram has its own viewer;
 * glassGram opens the code in an app that reads text (GlassgramPluginInstaller.openSource).
 */
object PluginFileViewer {

    @JvmStatic
    @JvmOverloads
    fun open(fragment: BaseFragment?, file: File?, fileName: String? = null): Boolean {
        if (fragment == null || file == null || !file.isFile) {
            return false
        }
        GlassgramPluginInstaller.openSource(fragment, file, fileName ?: file.name)
        return true
    }

    @JvmStatic
    @JvmOverloads
    fun openExternally(fragment: BaseFragment?, file: File?, fileName: String? = null): Boolean {
        return open(fragment, file, fileName)
    }
}
