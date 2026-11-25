/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

 Modified for glassGram, 2026: the sheet is glassGram's (GlassgramPluginInstaller.showInstallSheet);
 only PluginInstallParams is exteraGram's, without its list of trusted channels.

*/

package com.exteragram.messenger.plugins.ui.components

import android.graphics.drawable.Drawable
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.utils.chats.ChatUtils
import org.telegram.messenger.MessageObject
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.GlassgramPluginInstaller

class InstallPluginBottomSheet(
    private val fragment: BaseFragment,
    private val validationResult: PluginsController.PluginValidationResult,
    private val params: PluginInstallParams,
) {

    fun show() {
        val plugin = validationResult.plugin ?: return
        GlassgramPluginInstaller.showInstallSheet(fragment, plugin, params)
    }

    class PluginInstallParams(
        @JvmField val filePath: String,
        source: MessageObject?,
    ) {
        @JvmOverloads
        constructor(filePath: String, @Suppress("UNUSED_PARAMETER") trusted: Boolean = false) : this(filePath, null as MessageObject?)

        /** exteraGram trusts the files of its own channels; glassGram vouches by its catalog instead. */
        @JvmField
        val trusted: Boolean = false

        /** The chat message the file came from, if it did. */
        @JvmField
        val message: MessageObject? = source

        @JvmField
        var iconDrawable: Drawable? = null

        @JvmField
        var requiredPlugins: List<RequiredPluginInfo>? = null
        @JvmField
        var bundledPackages: List<String>? = null

        @JvmField
        var blockedTitle: String? = null
        @JvmField
        var blockedSubtitle: String? = null

        // Preserve existing Java callers while exposing the fields to ElyxCore.
        fun getFilePath() = filePath
        fun getTrusted() = trusted
        fun getMessage() = message
        fun getIconDrawable() = iconDrawable
        fun setIconDrawable(value: Drawable?) { iconDrawable = value }
        fun getRequiredPlugins() = requiredPlugins
        fun setRequiredPlugins(value: List<RequiredPluginInfo>?) { requiredPlugins = value }
        fun getBundledPackages() = bundledPackages
        fun setBundledPackages(value: List<String>?) { bundledPackages = value }
        fun getBlockedTitle() = blockedTitle
        fun setBlockedTitle(value: String?) { blockedTitle = value }
        fun getBlockedSubtitle() = blockedSubtitle
        fun setBlockedSubtitle(value: String?) { blockedSubtitle = value }

        companion object {
            @JvmStatic
            @JvmOverloads
            fun of(messageObject: MessageObject, path: String? = null): PluginInstallParams =
                PluginInstallParams(path ?: ChatUtils.getInstance().getPathToMessage(messageObject), messageObject)

            @JvmStatic
            fun untrusted(filePath: String): PluginInstallParams = PluginInstallParams(filePath)
        }
    }
}
