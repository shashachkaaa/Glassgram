package com.exteragram.messenger.plugins.ui.components

import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.messenger.plugins.PluginsController
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Stories.recorder.ButtonWithCounterView

/**
 * glassGram: what exteraGram's engine shows after plugins crashed the app: safe mode is on, no
 * plugin loads until it is turned off. As exteraGram's, without its sticker.
 */
class SafeModeBottomSheet(fragment: BaseFragment) :
    BottomSheet(fragment.parentActivity, false, fragment.resourceProvider) {

    init {
        val activity = fragment.parentActivity
        fixNavigationBar()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.msg_warning)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(getThemedColor(Theme.key_text_RedBold))
        }
        layout.addView(icon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0))
        val title = TextView(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LocaleController.getString(R.string.PluginsSafeMode)
        }
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 40f, 16f, 40f, 0f))
        val description = TextView(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText))
            text = LocaleController.getString(R.string.PluginsSafeModeInfo)
        }
        layout.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21f, 8f, 21f, 0f))
        val button = ButtonWithCounterView(activity, true, resourcesProvider).apply {
            setRoundRadius(8)
            setText(LocaleController.getString(R.string.Disable), false)
            setOnClickListener {
                dismiss()
                PluginsController.restart(false)
            }
        }
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16f, 28f, 16f, 16f))
        setCustomView(layout)
    }
}
