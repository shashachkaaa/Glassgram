/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

 Modified for glassGram, 2026: only RequiredPluginInfo, which the SDK's ElyxCore fills in; the
 cell that shows it is exteraGram's install sheet's.

*/

package com.exteragram.messenger.plugins.ui.components

import org.telegram.messenger.LocaleController
import org.telegram.messenger.R

class RequiredPluginInfo(@JvmField val id: String) {
    @JvmField
    var name: String? = null

    @JvmField
    var requiredVersion: String? = null

    @JvmField
    var currentVersion: String? = null

    @JvmField
    var downloadUrl: String? = null

    @JvmField
    var state: Int = STATE_MISSING

    val isSatisfied: Boolean
        get() = state == STATE_SATISFIED

    fun title(): CharSequence = name ?: id

    fun subtitle(): CharSequence = when (state) {
        STATE_DISABLED -> LocaleController.getString(R.string.PluginRequiredDisabled)
        STATE_OUTDATED -> LocaleController.formatString(
            R.string.PluginRequiredOutdated,
            currentVersion ?: "",
            requiredVersion ?: ""
        )

        STATE_SATISFIED -> LocaleController.formatString(
            R.string.PluginVersionValue,
            currentVersion ?: ""
        )

        else -> if (requiredVersion.isNullOrEmpty()) {
            LocaleController.getString(R.string.PluginRequiredMissing)
        } else {
            LocaleController.formatString(R.string.PluginRequiredVersion, requiredVersion)
        }
    }

    fun actionText(): CharSequence = when (state) {
        STATE_DISABLED -> LocaleController.getString(R.string.Enable)
        STATE_OUTDATED -> LocaleController.getString(R.string.PluginRequiredUpdate)
        STATE_SATISFIED -> LocaleController.getString(R.string.PluginRequiredInstalled)
        else -> LocaleController.getString(R.string.BotDownloadFileDownload)
    }

    fun contentKey(): String = "$state|$name|$currentVersion|$requiredVersion"

    companion object {
        const val STATE_MISSING = 0
        const val STATE_DISABLED = 1
        const val STATE_OUTDATED = 2
        const val STATE_SATISFIED = 3
    }
}
