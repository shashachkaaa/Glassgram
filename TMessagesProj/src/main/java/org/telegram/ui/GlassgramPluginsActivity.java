package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Intent;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

/** Glassgram Preferences > Plugins: the engine switch and the installed plugins. */
public class GlassgramPluginsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ENGINE = 1;
    private static final int ID_INSTALL = 2;
    private static final int ID_BROWSE = 3;
    private static final int ID_LOGS = 4;
    private static final int ID_PLUGIN = 1000;

    private static final int REQUEST_PICK_PLUGIN = 9101;

    private final ArrayList<PluginInfo> shownPlugins = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.glassgramPluginsUpdated);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.glassgramPluginsUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.glassgramPluginsUpdated && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramPlugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.GlassgramPluginsEngine)));
        items.add(UItem.asCheck(ID_ENGINE, getString(R.string.GlassgramPluginsEnable)).setChecked(GlassgramConfig.pluginsEngine));
        String info = getString(R.string.GlassgramPluginsInfo);
        if (!PluginsController.isSupported()) {
            info = getString(R.string.GlassgramPluginsUnsupported);
        } else if (GlassgramConfig.pluginsEngine && PluginsController.isStarting()) {
            info = getString(R.string.GlassgramPluginsStarting);
        } else if (GlassgramConfig.pluginsEngine && PluginsController.startError != null) {
            info = formatString(R.string.GlassgramPluginsStartFailed, PluginsController.startError);
        }
        items.add(UItem.asShadow(info));

        shownPlugins.clear();
        if (GlassgramConfig.pluginsEngine && PluginsController.isStarted()) {
            List<PluginInfo> plugins = PluginsController.getPlugins();
            items.add(UItem.asHeader(getString(R.string.GlassgramPluginsInstalled)));
            for (int i = 0; i < plugins.size(); i++) {
                PluginInfo plugin = plugins.get(i);
                shownPlugins.add(plugin);
                items.add(UItem.asButtonCheck(ID_PLUGIN + i, plugin.name + " " + plugin.version, subtitle(plugin)).setChecked(plugin.enabled));
            }
            items.add(UItem.asButton(ID_INSTALL, R.drawable.msg_add, getString(R.string.GlassgramPluginsInstallFile)));
            items.add(UItem.asShadow(plugins.isEmpty() ? getString(R.string.GlassgramPluginsEmpty) : null));
            items.add(UItem.asButton(ID_BROWSE, R.drawable.msg_channel, getString(R.string.GlassgramPluginsBrowse), "@exteraPlugins"));
            items.add(UItem.asButton(ID_LOGS, R.drawable.msg_log, getString(R.string.GlassgramPluginsLogs)));
            items.add(UItem.asShadow(null));
        }
    }

    private static CharSequence subtitle(PluginInfo plugin) {
        if (plugin.error != null) {
            return getString(R.string.GlassgramPluginLoadError);
        }
        if (!TextUtils.isEmpty(plugin.author)) {
            return plugin.author;
        }
        return plugin.description;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENGINE) {
            if (!PluginsController.isSupported()) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramPluginsUnsupported)).show();
                return;
            }
            boolean value = !GlassgramConfig.pluginsEngine;
            PluginsController.setEngineEnabled(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            listView.adapter.update(true);
        } else if (item.id == ID_INSTALL) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(intent, REQUEST_PICK_PLUGIN);
            } catch (Exception e) {
                BulletinFactory.of(this).createErrorBulletin(e.toString()).show();
            }
        } else if (item.id == ID_BROWSE) {
            org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://t.me/exteraPlugins");
        } else if (item.id == ID_LOGS) {
            showLogs();
        } else if (item.id >= ID_PLUGIN && item.id - ID_PLUGIN < shownPlugins.size()) {
            PluginInfo plugin = shownPlugins.get(item.id - ID_PLUGIN);
            boolean onSwitch = LocaleController.isRTL ? x < dp(76) : x > view.getMeasuredWidth() - dp(76);
            if (onSwitch) {
                boolean value = !plugin.enabled;
                if (view instanceof NotificationsCheckCell) {
                    ((NotificationsCheckCell) view).setChecked(value);
                }
                PluginsController.setPluginEnabled(plugin.id, value, null);
            } else {
                presentFragment(new GlassgramPluginSettingsActivity(plugin.id));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void showLogs() {
        if (getParentActivity() == null) {
            return;
        }
        String logs = PluginsController.getLogs();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.GlassgramPluginsLogs));
        builder.setMessage(TextUtils.isEmpty(logs) ? getString(R.string.GlassgramPluginsLogsEmpty) : tail(logs));
        if (!TextUtils.isEmpty(logs)) {
            builder.setPositiveButton(getString(R.string.Copy), (d, w) -> {
                AndroidUtilities.addToClipboard(logs);
                BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
            });
            builder.setNeutralButton(getString(R.string.GlassgramPluginsLogsClear), (d, w) -> PluginsController.clearLogs());
        }
        builder.setNegativeButton(getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private static String tail(String text) {
        final int max = 4000;
        return text.length() > max ? "…" + text.substring(text.length() - max) : text;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PLUGIN && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            GlassgramPluginInstaller.installFromUri(this, data.getData());
        }
    }
}
