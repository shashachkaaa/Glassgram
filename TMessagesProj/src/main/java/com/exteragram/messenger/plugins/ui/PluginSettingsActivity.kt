/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

 Modified for glassGram, 2026: on Telegram's UniversalFragment instead of exteraGram's preferences
 screen, without translating the settings; a plugin's own page also shows what it is, its switch,
 its load error and a button to delete it, as glassGram's plugin page did; text input in a dialog.

*/

package com.exteragram.messenger.plugins.ui

import android.content.Context
import android.content.DialogInterface
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.chaquo.python.PyObject
import com.exteragram.messenger.plugins.Plugin
import com.exteragram.messenger.plugins.PluginsConstants
import com.exteragram.messenger.plugins.PluginsController
import com.exteragram.messenger.plugins.PythonPluginsEngine
import com.exteragram.messenger.plugins.models.CustomSetting
import com.exteragram.messenger.plugins.models.DividerSetting
import com.exteragram.messenger.plugins.models.EditTextSetting
import com.exteragram.messenger.plugins.models.HeaderSetting
import com.exteragram.messenger.plugins.models.InputSetting
import com.exteragram.messenger.plugins.models.SelectorSetting
import com.exteragram.messenger.plugins.models.SettingItem
import com.exteragram.messenger.plugins.models.SwitchSetting
import com.exteragram.messenger.plugins.models.TextSetting
import com.exteragram.messenger.preferences.utils.SettingsRegistry
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import org.telegram.messenger.LocaleController.formatString
import org.telegram.messenger.LocaleController.getString
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBarMenuItem
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.EditTextBoldCursor
import org.telegram.ui.Components.ItemOptions
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter
import org.telegram.ui.Components.UniversalFragment
import org.telegram.ui.GlassgramPluginInstaller
import java.util.Objects
import org.telegram.messenger.plugins.PluginsController as GlassgramPlugins

class PluginSettingsActivity : UniversalFragment, NotificationCenter.NotificationCenterDelegate {

    private lateinit var plugin: Plugin
    private var customTitle: String? = null
    private var settingItems: List<SettingItem>? = null
    private var createSubFragmentCallback: PyObject? = null
    private var targetSettingName: String? = null
    private var resetItem: ActionBarMenuItem? = null
    private var targetSettingItemId: Int? = null
    private var settingsLinkPrefix: String? = null

    private var pluginHeader: View? = null
    private var pluginHeaderKey: String? = null

    constructor() : super()

    @JvmOverloads
    constructor(
        plugin: Plugin,
        targetSettingName: String? = null
    ) : this() {
        this.plugin = plugin
        this.targetSettingName = targetSettingName
    }

    @JvmOverloads
    constructor(
        plugin: Plugin,
        title: String?,
        settingsList: List<SettingItem>?,
        createSubFragmentCallback: PyObject?,
        targetSettingName: String? = null
    ) : this() {
        this.plugin = plugin
        this.customTitle = title
        this.settingItems = settingsList
        this.createSubFragmentCallback = createSubFragmentCallback
        this.targetSettingName = targetSettingName
    }

    fun setSettingsLinkPrefix(settingsLinkPrefix: String?): PluginSettingsActivity = apply {
        this.settingsLinkPrefix = settingsLinkPrefix
    }

    /** The plugin's own page, not one of its sub-pages. */
    private val isMainPage: Boolean
        get() = customTitle == null && settingItems == null && createSubFragmentCallback == null

    override fun getTitle(): CharSequence = customTitle ?: plugin.getName()

    override fun onFragmentCreate(): Boolean {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered)
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated)
        return super.onFragmentCreate()
    }

    override fun onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered)
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated)
        super.onFragmentDestroy()
    }

    private fun update() {
        if (listView != null && !isFinished) {
            listView.adapter.update(true)
        }
    }

    override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
        when (id) {
            NotificationCenter.pluginsUpdated -> {
                val current = PluginsController.getInstance().plugins[plugin.getId()]
                if (current == null) {
                    if (isMainPage) finishFragment()
                    return
                }
                plugin = current
                update()
            }

            NotificationCenter.pluginSettingsRegistered -> {
                val updatedPluginId = args.firstOrNull() as? String
                if (updatedPluginId != null && plugin.getId() != updatedPluginId) return

                val callback = createSubFragmentCallback
                if (callback != null) {
                    PluginsController.runOnPluginsQueue {
                        if (!PluginsController.getInstance().isPluginActive(plugin)) return@runOnPluginsQueue
                        val subSettingsList = try {
                            val pySubDefinitions = callback.call() ?: return@runOnPluginsQueue
                            val engine = PluginsController.getEngines()[PluginsConstants.PYTHON] as? PythonPluginsEngine
                                ?: return@runOnPluginsQueue
                            engine.parsePySettingDefinitions(pySubDefinitions.asList())
                        } catch (_: Exception) {
                            return@runOnPluginsQueue
                        }

                        AndroidUtilities.runOnUIThread {
                            settingItems = subSettingsList
                            update()
                        }
                    }
                } else {
                    update()
                    resetItem?.let {
                        AndroidUtilities.updateViewVisibilityAnimated(
                            it,
                            PluginsController.getInstance().hasPluginSettingsPreferences(plugin.getId()),
                            0.5f,
                            true
                        )
                    }
                }
            }

            NotificationCenter.pluginSettingsUnregistered -> {
                val unregisteredPluginId = args.firstOrNull() as? String ?: return
                if (plugin.getId() == unregisteredPluginId) {
                    if (isMainPage) {
                        update()
                    } else if (!PluginsController.getInstance().hasPluginSettings(plugin.getId())) {
                        finishFragment()
                    }
                }
            }
        }
    }

    override fun createView(context: Context): View {
        val contentView = super.createView(context)

        if (createSubFragmentCallback == null) {
            resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset).apply {
                contentDescription = getString(R.string.Reset)
                AndroidUtilities.updateViewVisibilityAnimated(
                    this,
                    PluginsController.getInstance().hasPluginSettingsPreferences(plugin.getId()),
                    0.5f,
                    false
                )
                tag = null
                setOnClickListener { confirmReset(it) }
            }
        }

        checkTargetSetting()
        return contentView
    }

    private fun confirmReset(item: View) {
        val dialog = AlertDialog.Builder(context, resourceProvider).apply {
            setTitle(getString(R.string.ResetSettings))
            setMessage(AndroidUtilities.replaceTags(formatString(R.string.ResetPluginSettingsInfo, plugin.getName())))
            setPositiveButton(getString(R.string.Reset)) { _, _ ->
                fragmentView?.findFocus()?.clearFocus()
                AndroidUtilities.updateViewVisibilityAnimated(item, false, 0.5f, true)
                PluginsController.runOnPluginsQueue {
                    PluginsController.getInstance().clearPluginSettingsPreferences(plugin.getId())
                    PluginsController.getInstance().loadPluginSettings(plugin.getId())
                    AndroidUtilities.runOnUIThread {
                        BulletinFactory.of(this@PluginSettingsActivity).createSimpleBulletin(
                            R.raw.info,
                            formatString(R.string.ResetPluginSettings, plugin.getName())
                        ).show()
                    }
                }
            }
            setNegativeButton(getString(R.string.Cancel), null)
        }.create()

        showDialog(dialog)
        (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)
            ?.setTextColor(Theme.getColor(Theme.key_text_RedBold))
    }

    fun checkTargetSetting() {
        targetSettingItemId?.let { id ->
            listView?.let { list ->
                val position = list.findPositionByItemId(id)
                if (position >= 0) list.smoothScrollToPosition(position)
            }
            targetSettingItemId = null
        }
    }

    private fun header(): View {
        val key = "${plugin.getName()}\n${plugin.getVersion()}\n${plugin.getAuthor()}\n${plugin.getIcon()}\n${plugin.getDescription()}"
        val current = pluginHeader
        if (current != null && key == pluginHeaderKey) {
            return current
        }
        pluginHeaderKey = key
        val header = GlassgramPluginInstaller.createPluginHeader(
            context, currentAccount, plugin.getName(), plugin.getVersion(), plugin.getAuthor(),
            plugin.getDescription(), plugin.getIcon(), resourceProvider
        )
        return FrameLayout(context).apply {
            setPadding(dp(16f), dp(16f), dp(16f), dp(20f))
            addView(header)
            pluginHeader = this
        }
    }

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        if (isMainPage) {
            items.add(UItem.asCustomShadow(header()))
            items.add(UItem.asCheck(ID_ENABLED, getString(R.string.GlassgramPluginEnabled)).setChecked(plugin.isEnabled() || plugin.hasError()))
            items.add(UItem.asShadow(null))
            val error = plugin.getError()
            if (error != null) {
                items.add(UItem.asHeader(getString(R.string.GlassgramPluginLoadError)))
                items.add(UItem.asButton(ID_ERROR, R.drawable.msg_info, errorLine(error)).red())
                items.add(UItem.asShadow(getString(R.string.GlassgramPluginLoadErrorInfo)))
            }
        }

        val javaSettings = if (isMainPage && !plugin.isEnabled()) null else settingItems
            ?: PluginsController.getInstance().getPluginSettingsList(plugin.getId())
        var found = false
        var needsShadow = false
        for (settingDef in javaSettings.orEmpty()) {
            val item = createItem(settingDef) ?: continue
            item.id = getStableId(settingDef)
            val linkAlias = (item.settingItem as? SettingItem)?.linkAlias
            if (!targetSettingName.isNullOrEmpty() && linkAlias == targetSettingName) {
                targetSettingItemId = item.id
                targetSettingName = null
                found = true
            }
            items.add(item)
            needsShadow = item.viewType != UniversalAdapter.VIEW_TYPE_SHADOW
        }
        if (needsShadow) {
            items.add(UItem.asShadow(null))
        }

        if (!found && !targetSettingName.isNullOrEmpty() && !javaSettings.isNullOrEmpty()) {
            SettingsRegistry.getInstance().onSettingNotFound(this)
            targetSettingName = null
        }

        if (isMainPage) {
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, getString(R.string.GlassgramPluginDelete)).red())
            items.add(UItem.asShadow(null))
        }
    }

    private fun createItem(settingDef: SettingItem): UItem? {
        val iconResId = settingDef.icon
            ?.takeIf { it.isNotEmpty() }
            ?.let { iconName ->
                val context = ApplicationLoader.applicationContext
                context.resources.getIdentifier(iconName, "drawable", context.packageName)
            } ?: 0
        return try {
            when (settingDef) {
                is HeaderSetting -> settingDef.text?.let { text ->
                    UItem.asHeader(text).apply { settingItem = settingDef }
                }

                is SwitchSetting -> {
                    val key = settingDef.key
                    val text = settingDef.text
                    if (key != null && text != null) {
                        val currentValue = PluginsController.getInstance()
                            .getPluginSettingBoolean(plugin.getId(), key, settingDef.defaultValue)
                        val subtext = settingDef.subtext
                        (if (subtext.isNullOrEmpty()) UItem.asCheck(0, text) else UItem.asButtonCheck(0, text, subtext).wrapText()).apply {
                            setChecked(currentValue)
                            if (iconResId != 0) this.iconResId = iconResId
                            object2 = key
                            settingItem = settingDef
                        }
                    } else null
                }

                is InputSetting -> {
                    val key = settingDef.key
                    val text = settingDef.text
                    if (key != null && text != null) {
                        val currentValue = PluginsController.getInstance()
                            .getPluginSettingString(plugin.getId(), key, settingDef.defaultValue)
                        UItem.asButton(0, iconResId, text, currentValue.orEmpty()).apply {
                            object2 = key
                            settingItem = settingDef
                        }
                    } else null
                }

                is EditTextSetting -> {
                    val key = settingDef.key
                    if (key != null && settingDef.hint != null) {
                        val value = PluginsController.getInstance()
                            .getPluginSettingString(plugin.getId(), key, settingDef.defaultValue)
                        UItem.asButton(0, if (value.isNullOrEmpty()) settingDef.hint else value.replace('\n', ' ')).apply {
                            object2 = key
                            settingItem = settingDef
                        }
                    } else null
                }

                is SelectorSetting -> {
                    val key = settingDef.key
                    val text = settingDef.text
                    if (key != null && text != null && settingDef.items.isNotEmpty()) {
                        var currentValue = PluginsController.getInstance().getPluginSettingInt(plugin.getId(), key, settingDef.defaultValue)
                        if (currentValue !in settingDef.items.indices) {
                            currentValue = settingDef.defaultValue.coerceIn(settingDef.items.indices)
                            PluginsController.getInstance().setPluginSetting(plugin.getId(), key, currentValue)
                        }
                        UItem.asButton(0, iconResId, text, settingDef.items[currentValue]).apply {
                            texts = settingDef.items
                            intValue = currentValue
                            object2 = key
                            settingItem = settingDef
                        }
                    } else null
                }

                is TextSetting -> {
                    val subtext = settingDef.subtext
                    (if (subtext.isNullOrEmpty()) UItem.asButton(0, iconResId, settingDef.text)
                    else UItem.asButton(0, iconResId, settingDef.text, subtext)).apply {
                        settingItem = settingDef
                        accent = settingDef.accent
                        if (settingDef.red) red()
                    }
                }

                is DividerSetting -> UItem.asShadow(settingDef.text?.let { AndroidUtilities.replaceTags(it) })

                is CustomSetting -> {
                    val custom = settingDef.item ?: settingDef.factory?.create(plugin, settingDef, settingDef.factoryArgs)
                    custom?.apply { settingItem = settingDef }
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e("PluginSettings", "Error creating item", e)
            null
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when (item.id) {
            ID_ENABLED -> {
                val value = !(plugin.isEnabled() || plugin.hasError())
                (view as? TextCheckCell)?.isChecked = value
                GlassgramPlugins.setPluginEnabled(plugin.getId(), value, null)
                return
            }

            ID_ERROR -> {
                val error = plugin.getError() ?: return
                AndroidUtilities.addToClipboard(Log.getStackTraceString(error).ifEmpty { error.toString() })
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show()
                return
            }

            ID_DELETE -> {
                confirmDelete(this, plugin.getId(), plugin.getName()) { finishFragment() }
                return
            }
        }

        if (!PluginsController.getInstance().isPluginActive(plugin)) return
        val settingItem = item.settingItem as? SettingItem ?: return

        when (settingItem) {
            is TextSetting -> {
                val createSubFragmentCallback = settingItem.createSubFragmentCallback
                if (createSubFragmentCallback != null) {
                    openSubFragmentNative(item, createSubFragmentCallback)
                    return
                }

                settingItem.onClickCallback?.let { callback ->
                    try {
                        callback.call(view)
                    } catch (_: Exception) {
                    }
                }
                return
            }

            is CustomSetting -> {
                val createSubFragmentCallback = settingItem.createSubFragmentCallback
                if (createSubFragmentCallback != null) {
                    openSubFragmentNative(item, createSubFragmentCallback)
                    return
                }

                try {
                    val factory = settingItem.factory
                    factory?.onClick(plugin, item, view)
                        ?: settingItem.onClickCallback?.call(view)
                } catch (_: Exception) {
                }
                return
            }

            else -> {}
        }

        val key = item.object2 as? String ?: return

        when (settingItem) {
            is SwitchSetting -> {
                val newValue = !item.checked
                when (view) {
                    is TextCheckCell -> view.isChecked = newValue
                    is NotificationsCheckCell -> view.isChecked = newValue
                }
                item.setChecked(newValue)
                PluginsController.runOnPluginsQueue {
                    PluginsController.getInstance().setPluginSettingAndTriggerOnChange(
                        plugin.getId(), key, newValue, settingItem.onChangeCallback
                    )
                }
            }

            is SelectorSetting -> showSelectorDialog(item, key, settingItem)
            is InputSetting -> showInputDialog(item, key, settingItem.text, settingItem.subtext, null, settingItem.defaultValue,
                false, 0, null, settingItem.onChangeCallback)
            is EditTextSetting -> showInputDialog(item, key, settingItem.hint, null, settingItem.hint, settingItem.defaultValue,
                settingItem.multiline, settingItem.maxLength, settingItem.mask, settingItem.onChangeCallback)

            else -> {}
        }
    }

    override fun onLongClick(item: UItem, view: View, position: Int, x: Float, y: Float): Boolean {
        if (!PluginsController.getInstance().isPluginActive(plugin)) return false
        val settingItem = item.settingItem as? SettingItem ?: return false

        settingItem.onLongClickCallback?.let { callback ->
            try {
                callback.call(view)
            } catch (_: Exception) {
            }
            return true
        }

        if (!settingItem.linkAlias.isNullOrEmpty()) {
            settingItem.getLink(plugin.getId(), settingsLinkPrefix)?.let { link ->
                ItemOptions.makeOptions(this, view)
                    .add(R.drawable.msg_copy, getString(R.string.CopyLink)) {
                        AndroidUtilities.addToClipboard(link)
                        BulletinFactory.of(this).createCopyLinkBulletin().show()
                    }
                    .show()
                return true
            }
        }

        if (settingItem is CustomSetting) {
            settingItem.factory?.let { factory ->
                try {
                    factory.onLongClick(plugin, item, view)
                } catch (_: Exception) {
                }
                return true
            }
        }

        return false
    }

    private fun showInputDialog(
        item: UItem, key: String, title: CharSequence?, subtext: String?, hint: String?, defaultValue: String?,
        multiline: Boolean, maxLength: Int, mask: String?, onChangeCallback: PyObject?
    ) {
        val context = parentActivity ?: return
        val editText = EditTextBoldCursor(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourceProvider))
            setHint(hint ?: getString(R.string.EnterValue))
            setText(PluginsController.getInstance().getPluginSettingString(plugin.getId(), key, defaultValue))
            if (multiline) {
                isSingleLine = false
                maxLines = 8
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (maxLength > 0) {
                filters = arrayOf(InputFilter.LengthFilter(maxLength))
            }
            setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, resourceProvider))
            setLineColors(
                Theme.getColor(Theme.key_dialogInputField, resourceProvider),
                Theme.getColor(Theme.key_dialogInputFieldActivated, resourceProvider),
                Theme.getColor(Theme.key_text_RedBold, resourceProvider)
            )
            setPadding(0, dp(8f), 0, dp(8f))
        }
        val container = FrameLayout(context)
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT.toFloat(), 0, 24f, 4f, 24f, 0f))

        val builder = AlertDialog.Builder(context, resourceProvider)
        builder.setTitle(title)
        if (!subtext.isNullOrEmpty()) {
            builder.setMessage(subtext)
        }
        builder.setView(container)
        builder.setPositiveButton(getString(R.string.Save)) { _, _ ->
            val text = editText.text?.toString().orEmpty()
            if (!mask.isNullOrEmpty()) {
                val matches = try {
                    text.matches(Regex(mask))
                } catch (_: Exception) {
                    true
                }
                if (!matches) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramPluginBadValue)).show()
                    return@setPositiveButton
                }
            }
            PluginsController.runOnPluginsQueue {
                PluginsController.getInstance()
                    .setPluginSettingAndTriggerOnChange(plugin.getId(), key, text, onChangeCallback)
                AndroidUtilities.runOnUIThread { update() }
            }
        }
        builder.setNegativeButton(getString(R.string.Cancel), null)
        showDialog(builder.create())
        editText.requestFocus()
        AndroidUtilities.runOnUIThread({ AndroidUtilities.showKeyboard(editText) }, 200)
    }

    private fun showSelectorDialog(item: UItem, key: String, selectorSetting: SelectorSetting) {
        val activity = parentActivity ?: return
        val currentValue = PluginsController.getInstance()
            .getPluginSettingInt(plugin.getId(), key, selectorSetting.defaultValue)
        val names = selectorSetting.items.mapIndexed { i, name -> (if (i == currentValue) "✓ " else "") + name }.toTypedArray<CharSequence>()
        AlertDialog.Builder(activity, resourceProvider).apply {
            setTitle(item.text)
            setItems(names) { _, which ->
                PluginsController.runOnPluginsQueue {
                    PluginsController.getInstance()
                        .setPluginSettingAndTriggerOnChange(plugin.getId(), key, which, selectorSetting.onChangeCallback)
                    AndroidUtilities.runOnUIThread { update() }
                }
            }
            setNegativeButton(getString(R.string.Cancel), null)
            showDialog(create())
        }
    }

    private fun getStableId(item: SettingItem): Int = when (item) {
        is SwitchSetting -> Objects.hash("switch", item.key)
        is InputSetting -> Objects.hash("input", item.key)
        is EditTextSetting -> Objects.hash("edit", item.key)
        is SelectorSetting -> Objects.hash("selector", item.key)
        is HeaderSetting -> Objects.hash("header", item.text)
        is DividerSetting -> Objects.hash("divider", item.text)
        is TextSetting -> Objects.hash("text", item.text)
        is CustomSetting -> Objects.hash(
            "custom",
            item.item?.id ?: item.factory?.hashCode(),
            item.factoryArgs?.hashCode()
        )

        else -> item.hashCode()
    }.let { if (it in ID_DELETE..ID_ENABLED) it + 4 else it }

    private fun openSubFragmentNative(item: UItem, callback: PyObject) {
        PluginsController.runOnPluginsQueue {
            if (!PluginsController.getInstance().isPluginActive(plugin))
                return@runOnPluginsQueue

            val subSettingsList = try {
                val pySubDefinitions = callback.call() ?: return@runOnPluginsQueue
                val engine = PluginsController.getEngines()[PluginsConstants.PYTHON] as? PythonPluginsEngine
                    ?: return@runOnPluginsQueue
                engine.parsePySettingDefinitions(pySubDefinitions.asList())
            } catch (e: Exception) {
                FileLog.e("Error opening subfragment", e)
                return@runOnPluginsQueue
            }

            AndroidUtilities.runOnUIThread {
                if (subSettingsList.isNotEmpty()) {
                    val itemSetting = item.settingItem as? SettingItem
                    val originalText = (itemSetting as? TextSetting)?.text ?: item.text
                    val title = originalText?.toString()?.takeIf { it.isNotEmpty() }
                        ?: customTitle
                        ?: plugin.getName()

                    val subPrefix = if (settingsLinkPrefix.isNullOrEmpty()) {
                        itemSetting?.linkAlias.orEmpty()
                    } else {
                        "$settingsLinkPrefix:${itemSetting?.linkAlias.orEmpty()}"
                    }

                    presentFragment(
                        PluginSettingsActivity(plugin, title, subSettingsList, callback)
                            .setSettingsLinkPrefix(subPrefix)
                    )
                }
            }
        }
    }

    companion object {
        private const val ID_ENABLED = -1001
        private const val ID_ERROR = -1002
        private const val ID_DELETE = -1003

        private fun errorLine(error: Throwable): String {
            val text = (error.message ?: error.toString()).trim()
            val lastBreak = text.lastIndexOf('\n')
            return if (lastBreak >= 0) text.substring(lastBreak + 1) else text
        }

        /** Asks, then deletes the plugin; done runs when it is gone. */
        @JvmStatic
        fun confirmDelete(fragment: BaseFragment, pluginId: String, name: String?, done: Runnable?) {
            val activity = fragment.parentActivity ?: return
            val dialog = AlertDialog.Builder(activity, fragment.resourceProvider)
                .setTitle(getString(R.string.GlassgramPluginDelete))
                .setMessage(formatString(R.string.GlassgramPluginDeleteText, name ?: pluginId))
                .setPositiveButton(getString(R.string.Delete)) { _, _ -> GlassgramPlugins.uninstallPlugin(pluginId, done) }
                .setNegativeButton(getString(R.string.Cancel), null)
                .create()
            fragment.showDialog(dialog)
            (dialog.getButton(DialogInterface.BUTTON_POSITIVE) as? TextView)
                ?.setTextColor(Theme.getColor(Theme.key_text_RedBold, fragment.resourceProvider))
        }
    }
}
