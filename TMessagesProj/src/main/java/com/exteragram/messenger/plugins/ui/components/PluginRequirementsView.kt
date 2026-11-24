package com.exteragram.messenger.plugins.ui.components

import android.content.Context
import android.widget.LinearLayout

/**
 * glassGram: exteraGram's list of the plugins a plugin needs, in its install sheet. ElyxCore
 * imports it; glassGram's install sheet doesn't list them, so it holds nothing.
 */
class PluginRequirementsView(context: Context) : LinearLayout(context)
