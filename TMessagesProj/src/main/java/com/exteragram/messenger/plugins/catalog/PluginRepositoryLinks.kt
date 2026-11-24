package com.exteragram.messenger.plugins.catalog

import org.telegram.messenger.browser.Browser
import org.telegram.ui.ActionBar.BaseFragment

/**
 * glassGram: where exteraGram's engine sends a plugin's repository link. exteraGram opens it in its
 * plugin catalog; glassGram opens the channel or the git page itself.
 */
object PluginRepositoryLinks {

    @JvmStatic
    fun open(fragment: BaseFragment, username: String) {
        val context = fragment.parentActivity ?: return
        Browser.openUrl(context, "https://t.me/" + username.removePrefix("@"))
    }

    @JvmStatic
    fun openGit(fragment: BaseFragment, url: String) {
        val context = fragment.parentActivity ?: return
        Browser.openUrl(context, url)
    }
}
