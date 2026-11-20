package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramRemoteConfig;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

public class GlassgramPluginEngineActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int ENABLE = 1, COMPACT = 2, DEVELOP = 3, LOGS = 4;
    private static final int DOCUMENTATION = 5, REPOSITORIES = 6, CHANNEL = 7, CATALOG = 8;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

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
        if (id == NotificationCenter.glassgramPluginsUpdated) {
            refresh();
        }
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        View root = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return root;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramPluginsEngine);
    }

    static String engineStatus() {
        if (!PluginsController.isSupported()) {
            return getString(R.string.GlassgramPluginsUnsupported);
        }
        if (GlassgramConfig.pluginsEngine && PluginsController.isStarting()) {
            return getString(R.string.GlassgramPluginsStarting);
        }
        if (GlassgramConfig.pluginsEngine && PluginsController.startError != null) {
            return formatString(R.string.GlassgramPluginsStartFailed, PluginsController.startError);
        }
        return null;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.Settings)));
        items.add(UItem.asCheck(ENABLE, getString(R.string.GlassgramPluginsEnable)).setChecked(GlassgramConfig.pluginsEngine));
        items.add(UItem.asCheck(COMPACT, getString(R.string.GlassgramPluginCompact)).setChecked(
            getContext() != null && GlassgramPluginsActivity.displayPreferences(getContext()).getBoolean(GlassgramPluginsActivity.COMPACT, false)));
        String status = engineStatus();
        items.add(UItem.asShadow(status == null ? getString(R.string.GlassgramPluginsInfo) : status));
        if (PluginsController.isSupported()) {
            items.add(UItem.asHeader(getString(R.string.GlassgramPluginDeveloper)));
            UItem developer = UItem.asCheck(DEVELOP, getString(R.string.GlassgramDevServer)).setChecked(PluginsController.devServer);
            developer.enabled = PluginsController.isStarted() && GlassgramConfig.pluginsEngine;
            items.add(developer);
            items.add(UItem.asButton(LOGS, R.drawable.msg_log, getString(R.string.GlassgramPluginsLogs)));
            items.add(UItem.asShadow(PluginsController.devServer && getParentActivity() != null
                ? "adb forward tcp:42690 localabstract:" + getParentActivity().getPackageName() + ".plugins" : null));
        }
        items.add(UItem.asHeader(getString(R.string.GlassgramPluginResources)));
        items.add(UItem.asButton(DOCUMENTATION, R.drawable.msg_info, getString(R.string.GlassgramPluginDocs)));
        if (PluginsController.isSupported()) {
            items.add(UItem.asButton(CATALOG, R.drawable.msg_download, getString(R.string.GlassgramCatalog)));
            items.add(UItem.asButton(REPOSITORIES, R.drawable.msg_download, getString(R.string.GlassgramRepositories)));
        }
        items.add(UItem.asButton(CHANNEL, R.drawable.msg_channel, getString(R.string.GlassgramPluginsBrowse), "@exteraPlugins"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENABLE:
                toggleEngine(this);
                refresh();
                break;
            case COMPACT:
                android.content.SharedPreferences prefs = GlassgramPluginsActivity.displayPreferences(getContext());
                prefs.edit().putBoolean(GlassgramPluginsActivity.COMPACT, !prefs.getBoolean(GlassgramPluginsActivity.COMPACT, false)).apply();
                refresh();
                break;
            case DEVELOP:
                toggleDevServer();
                break;
            case LOGS:
                showLogs();
                break;
            case DOCUMENTATION:
                Browser.openUrl(getParentActivity(), "https://plugins.exteragram.app/");
                break;
            case CATALOG:
                presentFragment(new GlassgramPluginCatalogActivity());
                break;
            case REPOSITORIES:
                presentFragment(new GlassgramRepositoriesActivity());
                break;
            case CHANNEL:
                Browser.openUrl(getParentActivity(), "https://t.me/exteraPlugins");
                break;
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    static void toggleEngine(BaseFragment fragment) {
        if (!PluginsController.isSupported()) {
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.GlassgramPluginsUnsupported)).show();
        } else if (GlassgramRemoteConfig.isForced("pluginsEngine")) {
            GlassgramSettingsActivity.showForcedOff(fragment, "pluginsEngine");
        } else {
            PluginsController.setEngineEnabled(!GlassgramConfig.pluginsEngine);
        }
    }

    private void refresh() {
        if (listView != null && !isFinished) {
            listView.adapter.update(true);
        }
    }

    private void toggleDevServer() {
        if (getParentActivity() == null) return;
        boolean enable = !PluginsController.devServer;
        Runnable change = () -> PluginsController.setDevServer(enable, error -> {
            if (isFinished) return;
            if (error != null) BulletinFactory.of(this).createErrorBulletin(error).show();
            refresh();
        });
        if (enable) {
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.GlassgramDevServer))
                .setMessage(getString(R.string.GlassgramDevServerInfo))
                .setPositiveButton(getString(R.string.OK), (d, w) -> change.run())
                .setNegativeButton(getString(R.string.Cancel), null).create());
        } else {
            change.run();
        }
    }

    private void showLogs() {
        if (getParentActivity() == null) return;
        String logs = PluginsController.getLogs();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.GlassgramPluginsLogs));
        builder.setMessage(TextUtils.isEmpty(logs) ? getString(R.string.GlassgramPluginsLogsEmpty)
            : logs.length() > 4000 ? "…" + logs.substring(logs.length() - 4000) : logs);
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
}
