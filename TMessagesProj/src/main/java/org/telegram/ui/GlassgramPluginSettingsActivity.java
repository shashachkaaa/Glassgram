package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import com.chaquo.python.PyObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginItemFactory;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * A plugin's page: what it is, the switch, its load error, and the settings rows from its
 * create_settings() (or one of its sub-pages).
 */
public class GlassgramPluginSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENABLED = 1;
    private static final int ID_ERROR = 2;
    private static final int ID_DELETE = 3;
    private static final int ID_SETTING = 1000;

    private final String pluginId;
    private final CharSequence subTitle;
    private final PyObject subFactory;

    private final ArrayList<PyObject> settingRows = new ArrayList<>();

    public GlassgramPluginSettingsActivity(String pluginId) {
        this(pluginId, null, null);
    }

    private GlassgramPluginSettingsActivity(String pluginId, CharSequence subTitle, PyObject subFactory) {
        this.pluginId = pluginId;
        this.subTitle = subTitle;
        this.subFactory = subFactory;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.glassgramPluginsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.glassgramPluginSettingsReload);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.glassgramPluginsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.glassgramPluginSettingsReload);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView == null) {
            return;
        }
        if (id == NotificationCenter.glassgramPluginsUpdated
                || id == NotificationCenter.glassgramPluginSettingsReload && args.length > 0 && pluginId.equals(args[0])) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        if (subTitle != null) {
            return subTitle;
        }
        PluginInfo plugin = PluginsController.getPlugin(pluginId);
        return plugin != null ? plugin.name : getString(R.string.GlassgramPlugins);
    }

    private static String str(PyObject object, String key) {
        try {
            PyObject value = object.get(key);
            return value == null ? null : value.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean bool(PyObject object, String key) {
        try {
            PyObject value = object.get(key);
            return value != null && value.toBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    private static PyObject attr(PyObject object, String key) {
        try {
            return object.get(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private Object settingValue(PyObject row) {
        PyObject engine = PluginsController.getEngine();
        if (engine == null) {
            return null;
        }
        PyObject value = engine.callAttr("get_setting_value", pluginId, str(row, "key"), attr(row, "default"));
        if (value == null) {
            return null;
        }
        String type = str(row, "type");
        if ("switch".equals(type)) {
            return value.toBoolean();
        } else if ("selector".equals(type)) {
            try {
                return value.toInt();
            } catch (Throwable e) {
                return 0;
            }
        }
        return value.toString();
    }

    private List<PyObject> loadRows() {
        PyObject engine = PluginsController.getEngine();
        if (engine == null) {
            return new ArrayList<>();
        }
        try {
            PyObject rows = subFactory != null
                    ? engine.callAttr("create_sub_settings", pluginId, subFactory)
                    : engine.callAttr("get_settings", pluginId);
            return rows == null ? new ArrayList<>() : rows.asList();
        } catch (Throwable e) {
            FileLog.e(e);
            return new ArrayList<>();
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        settingRows.clear();
        PluginInfo plugin = PluginsController.getPlugin(pluginId);
        if (plugin == null) {
            items.add(UItem.asShadow(getString(R.string.GlassgramPluginsEmpty)));
            return;
        }
        if (subFactory == null) {
            items.add(UItem.asHeader(formatString(R.string.GlassgramPluginVersion, plugin.version)
                    + (TextUtils.isEmpty(plugin.author) ? "" : " · " + plugin.author)));
            items.add(UItem.asCheck(ID_ENABLED, getString(R.string.GlassgramPluginEnabled)).setChecked(plugin.enabled));
            items.add(UItem.asShadow(TextUtils.isEmpty(plugin.description) ? null : AndroidUtilities.replaceTags(plugin.description)));
            if (plugin.error != null) {
                items.add(UItem.asHeader(getString(R.string.GlassgramPluginLoadError)));
                items.add(UItem.asButton(ID_ERROR, R.drawable.msg_info, errorLine(plugin.error)).red());
                items.add(UItem.asShadow(getString(R.string.GlassgramPluginLoadErrorInfo)));
            }
        }
        if (plugin.enabled && plugin.error == null) {
            List<PyObject> rows = loadRows();
            if (rows.isEmpty() && subFactory == null && !plugin.hasSettings) {
                // Nothing to show besides the switch
            } else {
                boolean needsShadow = false;
                for (PyObject row : rows) {
                    UItem item = toItem(row);
                    if (item == null) {
                        continue;
                    }
                    items.add(item);
                    needsShadow = item.viewType != UniversalAdapter.VIEW_TYPE_SHADOW;
                }
                if (needsShadow) {
                    items.add(UItem.asShadow(null));
                }
            }
        }
        if (subFactory == null) {
            items.add(UItem.asButton(ID_DELETE, R.drawable.msg_delete, getString(R.string.GlassgramPluginDelete)).red());
            items.add(UItem.asShadow(null));
        }
    }

    private static String errorLine(String error) {
        String trimmed = error.trim();
        int lastBreak = trimmed.lastIndexOf('\n');
        return lastBreak >= 0 ? trimmed.substring(lastBreak + 1) : trimmed;
    }

    private UItem toItem(PyObject row) {
        String type = str(row, "type");
        if (type == null) {
            return null;
        }
        final int id = ID_SETTING + settingRows.size();
        final int icon = PluginsController.getDrawableId(str(row, "icon"));
        UItem item;
        switch (type) {
            case "header":
                item = UItem.asHeader(str(row, "text"));
                break;
            case "divider":
                item = UItem.asShadow(str(row, "text"));
                break;
            case "switch": {
                Object value = settingValue(row);
                boolean checked = value instanceof Boolean && (Boolean) value;
                String subtext = str(row, "subtext");
                item = TextUtils.isEmpty(subtext) ? UItem.asCheck(id, str(row, "text")) : UItem.asButtonCheck(id, str(row, "text"), subtext);
                item.setChecked(checked);
                break;
            }
            case "selector": {
                Object value = settingValue(row);
                List<PyObject> choices = attr(row, "items") == null ? new ArrayList<>() : attr(row, "items").asList();
                int index = value instanceof Integer ? (Integer) value : 0;
                String current = index >= 0 && index < choices.size() ? choices.get(index).toString() : "";
                item = UItem.asButton(id, icon, str(row, "text"), current);
                break;
            }
            case "input": {
                Object value = settingValue(row);
                item = UItem.asButton(id, icon, str(row, "text"), value == null ? "" : value.toString());
                break;
            }
            case "edit_text": {
                Object value = settingValue(row);
                String text = value == null ? "" : value.toString();
                item = UItem.asButton(id, TextUtils.isEmpty(text) ? str(row, "hint") : text.replace('\n', ' '));
                break;
            }
            case "text": {
                String subtext = str(row, "subtext");
                item = TextUtils.isEmpty(subtext) ? UItem.asButton(id, icon, str(row, "text")) : UItem.asButton(id, icon, str(row, "text"), subtext);
                if (bool(row, "red")) {
                    item.red();
                } else if (bool(row, "accent")) {
                    item.accent = true;
                }
                break;
            }
            case "custom": {
                PyObject custom = attr(row, "item");
                PyObject view = attr(row, "view");
                PyObject factory = attr(row, "factory");
                try {
                    if (custom != null) {
                        item = custom.toJava(UItem.class);
                    } else if (view != null) {
                        item = UItem.asCustom(view.toJava(View.class));
                    } else if (factory != null) {
                        item = PluginItemFactory.asItem(factory.toJava(PluginItemFactory.Delegate.class), attr(row, "factory_args"));
                    } else {
                        return null;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    return null;
                }
                if (item.id == 0) {
                    item.id = id;
                }
                break;
            }
            default:
                return null;
        }
        if (item.id == id) {
            settingRows.add(row);
        } else if (!"header".equals(type) && !"divider".equals(type)) {
            settingRows.add(row);
            item.id = id;
        }
        return item;
    }

    private PyObject rowFor(UItem item) {
        int index = item.id - ID_SETTING;
        return index >= 0 && index < settingRows.size() ? settingRows.get(index) : null;
    }

    private void changed(PyObject row, Object value) {
        PyObject engine = PluginsController.getEngine();
        if (engine == null) {
            return;
        }
        try {
            engine.callAttr("setting_changed", pluginId, row, value);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void call(PyObject fn, Object... args) {
        PyObject engine = PluginsController.getEngine();
        if (engine == null || fn == null) {
            return;
        }
        Object[] callArgs = new Object[args.length + 2];
        callArgs[0] = pluginId;
        callArgs[1] = fn;
        System.arraycopy(args, 0, callArgs, 2, args.length);
        try {
            engine.callAttr("call", callArgs);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLED) {
            PluginInfo plugin = PluginsController.getPlugin(pluginId);
            if (plugin == null) {
                return;
            }
            boolean value = !plugin.enabled;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            PluginsController.setPluginEnabled(pluginId, value, null);
            return;
        } else if (item.id == ID_ERROR) {
            PluginInfo plugin = PluginsController.getPlugin(pluginId);
            if (plugin != null && plugin.error != null) {
                AndroidUtilities.addToClipboard(plugin.error);
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
            }
            return;
        } else if (item.id == ID_DELETE) {
            confirmDelete();
            return;
        }
        PyObject row = rowFor(item);
        if (row == null) {
            return;
        }
        String type = str(row, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "switch": {
                Object current = settingValue(row);
                boolean value = !(current instanceof Boolean && (Boolean) current);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                } else if (view instanceof NotificationsCheckCell) {
                    ((NotificationsCheckCell) view).setChecked(value);
                }
                changed(row, value);
                break;
            }
            case "selector":
                showSelector(row);
                break;
            case "input":
                showInput(row, str(row, "text"), false, 0);
                break;
            case "edit_text": {
                PyObject maxLength = attr(row, "max_length");
                int max = 0;
                try {
                    max = maxLength == null ? 0 : maxLength.toInt();
                } catch (Throwable ignore) {
                }
                showInput(row, str(row, "hint"), bool(row, "multiline"), max);
                break;
            }
            case "text":
            case "custom": {
                if ("custom".equals(type) && item.object instanceof PluginItemFactory.Delegate) {
                    PluginItemFactory.onClick(PluginsController.getPlugin(pluginId), item, view);
                }
                call(attr(row, "on_click"), view);
                PyObject sub = attr(row, "create_sub_fragment");
                if (sub != null) {
                    String title = str(row, "text");
                    presentFragment(new GlassgramPluginSettingsActivity(pluginId, title != null ? title : getTitle(), sub));
                }
                break;
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        PyObject row = rowFor(item);
        if (row == null) {
            return false;
        }
        if (item.object instanceof PluginItemFactory.Delegate && PluginItemFactory.onLongClick(PluginsController.getPlugin(pluginId), item, view)) {
            return true;
        }
        PyObject onLongClick = attr(row, "on_long_click");
        if (onLongClick == null) {
            return false;
        }
        call(onLongClick, view);
        return true;
    }

    private void showSelector(PyObject row) {
        if (getParentActivity() == null) {
            return;
        }
        PyObject itemsAttr = attr(row, "items");
        List<PyObject> choices = itemsAttr == null ? new ArrayList<>() : itemsAttr.asList();
        Object value = settingValue(row);
        int current = value instanceof Integer ? (Integer) value : -1;
        CharSequence[] names = new CharSequence[choices.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i == current ? "✓ " : "") + choices.get(i).toString();
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(str(row, "text"));
        builder.setItems(names, (dialog, which) -> {
            changed(row, which);
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showInput(PyObject row, CharSequence title, boolean multiline, int maxLength) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        Object value = settingValue(row);
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(str(row, "hint") != null ? str(row, "hint") : "");
        editText.setText(value == null ? "" : value.toString());
        if (multiline) {
            editText.setSingleLine(false);
            editText.setMaxLines(8);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            editText.setSingleLine(true);
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        if (maxLength > 0) {
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        }
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setPadding(0, dp(8), 0, dp(8));

        FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        String subtext = str(row, "subtext");
        if (!TextUtils.isEmpty(subtext)) {
            builder.setMessage(subtext);
        }
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            String mask = str(row, "mask");
            String text = editText.getText().toString();
            if (!TextUtils.isEmpty(mask)) {
                try {
                    if (!text.matches(mask)) {
                        BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramPluginBadValue)).show();
                        return;
                    }
                } catch (Throwable ignore) {
                }
            }
            changed(row, text);
            if (listView != null) {
                listView.adapter.update(true);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
        editText.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(editText), 200);
    }

    private void confirmDelete() {
        PluginInfo plugin = PluginsController.getPlugin(pluginId);
        if (plugin == null || getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.GlassgramPluginDelete));
        builder.setMessage(formatString(R.string.GlassgramPluginDeleteText, plugin.name));
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> PluginsController.uninstallPlugin(pluginId, this::finishFragment));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }
}
