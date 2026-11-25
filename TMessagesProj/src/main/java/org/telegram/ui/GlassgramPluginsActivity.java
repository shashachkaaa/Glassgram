package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramPluginCatalog;
import org.telegram.messenger.GlassgramRemoteConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.GlassgramHeaderButton;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class GlassgramPluginsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final int ID_ENGINE = 1;
    private static final int ID_ENGINE_OPTIONS = 2;
    private static final int ID_INSTALL = 3;
    private static final int REQUEST_PICK_PLUGIN = 9101;
    static final String COMPACT = "compact";

    private String query = "";
    private boolean searching;
    private GlassgramHeaderButton catalogButton;
    private final Map<String, Boolean> pendingToggles = new HashMap<>();

    static SharedPreferences displayPreferences(Context context) {
        return context.getSharedPreferences("glassgram_plugin_list", Context.MODE_PRIVATE);
    }

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
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated);
        GlassgramPluginCatalog.load(false, error -> refresh());
        GlassgramRemoteConfig.refresh(GlassgramRemoteConfig.REFRESH_SETTINGS, this::refresh);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsUpdated) {
            refreshPluginState();
        }
    }

    @Override
    public View createView(Context context) {
        searching = false;
        query = "";
        catalogButton = null;
        setHasOwnBackground(true);
        View root = super.createView(context);
        ThemeBind.backgroundColor(contentView, Theme.key_windowBackgroundGray);
        listView.adapter.setApplyBackground(false);

        simpleActionBar.createMenu().addItem(0, R.drawable.outline_header_search)
            .setIsSearchField(true)
            .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchExpand() {
                    searching = true;
                    filter("");
                }

                @Override
                public void onSearchCollapse() {
                    searching = false;
                    filter("");
                }

                @Override
                public void onTextChanged(EditText editText) {
                    filter(editText.getText().toString());
                }
            }).setSearchFieldHint(getString(R.string.Search));
        simpleActionBar.createMenu().addItem(ID_ENGINE_OPTIONS, R.drawable.msg_settings)
            .setContentDescription(getString(R.string.GlassgramPluginsEngine));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_ENGINE_OPTIONS) {
                    // exteraGram's class for it, which ElyxCore adds its settings to
                    presentFragment(new com.exteragram.messenger.plugins.ui.PluginsInfoActivity());
                }
            }
        });

        if (PluginsController.isSupported()) {
            catalogButton = new GlassgramHeaderButton(context, getBaseSimpleGlass().factory,
                BlurredBackgroundProviderImpl.simpleActionBar(getResourceProvider()));
            catalogButton.setOnClickListener(v -> presentFragment(new GlassgramPluginCatalogActivity()));
            contentView.addView(catalogButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 46,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));
            updateCatalog();
        }
        return root;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (catalogButton != null) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) catalogButton.getLayoutParams();
            if (params.bottomMargin != dp(12) + bottom) {
                params.bottomMargin = dp(12) + bottom;
                catalogButton.setLayoutParams(params);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void filter(String text) {
        query = text.trim().toLowerCase(Locale.ROOT);
        refresh();
        listView.scrollToPosition(0);
    }

    private void refresh() {
        if (listView != null && !isFinished) {
            listView.adapter.update(!searching);
            updateCatalog();
        }
    }

    // Plugin state changes should preserve existing holders and their running animations,
    // even when the search field is open.
    private void refreshPluginState() {
        if (listView != null && !isFinished) {
            listView.adapter.update(true);
            updateCatalog();
        }
    }

    boolean displayedEnabled(PluginInfo plugin) {
        Boolean pending = pendingToggles.get(plugin.id);
        return pending != null ? pending : plugin.enabled;
    }

    private static final class CompactPluginItem extends UItem {
        CompactPluginItem(PluginInfo plugin, String name, String subtitle, boolean checked) {
            super(UniversalAdapter.VIEW_TYPE_TEXT_CHECK, false);
            this.object = plugin;
            this.text = name;
            this.subtext = subtitle;
            this.checked = checked;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CompactPluginItem
                && ((PluginInfo) object).id.equals(((PluginInfo) ((CompactPluginItem) other).object).id);
        }

        @Override
        public int hashCode() {
            return ((PluginInfo) object).id.hashCode();
        }

        @Override
        protected boolean contentsEquals(AdapterWithDiffUtils.Item other) {
            if (!equals(other)) return false;
            CompactPluginItem row = (CompactPluginItem) other;
            return checked == row.checked
                && android.text.TextUtils.equals(text, row.text)
                && android.text.TextUtils.equals(subtext, row.subtext);
        }
    }

    private void updateCatalog() {
        if (catalogButton == null) {
            return;
        }
        final int updates = GlassgramPluginCatalog.updates().size();
        final String text = getString(R.string.GlassgramCatalog) + (updates > 0 ? " · " + updates : "");
        if (!text.equals(catalogButton.getText())) {
            catalogButton.setText(text, catalogButton.isLaidOut());
        }
        catalogButton.setContentColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        catalogButton.setVisibility(searching ? View.GONE : View.VISIBLE);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramPlugins);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!searching) {
            String status = GlassgramPluginEngineActivity.engineStatus();
            items.add(UItem.asCheck(ID_ENGINE, getString(R.string.GlassgramPluginsEnable))
                .setChecked(GlassgramConfig.pluginsEngine));
            if (status != null) {
                items.add(UItem.asShadow(status));
            }
            if (PluginsController.isSupported()) {
                items.add(UItem.asButton(ID_INSTALL, R.drawable.msg_add, getString(R.string.GlassgramPluginsInstallFile)));
            }
        }
        if (getContext() == null || !GlassgramConfig.pluginsEngine || !PluginsController.isStarted()) {
            if (!searching && PluginsController.isSupported()) items.add(UItem.asSpace(74));
            return;
        }

        SharedPreferences prefs = displayPreferences(getContext());
        final Set<String> pinned = prefs.getStringSet("pinned", Collections.emptySet());
        final boolean compact = prefs.getBoolean(COMPACT, false);
        ArrayList<PluginInfo> visible = new ArrayList<>(PluginsController.getPlugins());
        visible.removeIf(plugin -> !matches(plugin));
        visible.sort((a, b) -> Boolean.compare(pinned.contains(b.id), pinned.contains(a.id)));

        if (!searching && !visible.isEmpty()) {
            items.add(UItem.asHeader(getString(R.string.GlassgramPluginsInstalled)));
        }
        for (PluginInfo plugin : visible) {
            if (compact) {
                String name = (pinned.contains(plugin.id) ? "📌 " : "") + plugin.name + " " + plugin.version;
                String subtitle = plugin.error != null ? getString(R.string.GlassgramPluginLoadError) : plugin.author;
                items.add(new CompactPluginItem(plugin, name, subtitle, displayedEnabled(plugin)));
            } else {
                items.add(GlassgramPluginCard.Factory.of(this, plugin, pinned.contains(plugin.id)));
            }
        }
        if (visible.isEmpty()) {
            items.add(UItem.asShadow(getString(query.isEmpty() ? R.string.GlassgramPluginsEmpty : R.string.GlassgramPluginsNoMatches)));
        }
        if (!searching) items.add(UItem.asSpace(74));
    }

    private boolean matches(PluginInfo plugin) {
        if (query.isEmpty()) {
            return true;
        }
        String searchable = plugin.name + "\n" + plugin.id + "\n" + plugin.author + "\n" + plugin.description + "\n" + plugin.version;
        return searchable.toLowerCase(Locale.ROOT).contains(query);
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.object instanceof PluginInfo) {
            PluginInfo plugin = (PluginInfo) item.object;
            boolean onSwitch = LocaleController.isRTL ? x < dp(76) : x > view.getWidth() - dp(76);
            if (onSwitch && view instanceof NotificationsCheckCell) {
                togglePlugin(plugin, enabled -> ((NotificationsCheckCell) view).setChecked(enabled));
            } else {
                openPlugin(plugin);
            }
        } else if (item.id == ID_ENGINE) {
            GlassgramPluginEngineActivity.toggleEngine(this);
            refresh();
        } else if (item.id == ID_INSTALL) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_PICK_PLUGIN);
            } catch (Exception e) {
                BulletinFactory.of(this).createErrorBulletin(e.toString()).show();
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof PluginInfo)) {
            return false;
        }
        PluginInfo plugin = (PluginInfo) item.object;
        boolean pinned = displayPreferences(getContext()).getStringSet("pinned", Collections.emptySet()).contains(plugin.id);
        ItemOptions.makeOptions(this, view)
            .add(R.drawable.msg_shareout, getString(R.string.ShareFile), () -> sharePlugin(plugin))
            .add(R.drawable.msg_openin, getString(R.string.GlassgramPluginSource), () -> openSource(plugin))
            .add(pinned ? R.drawable.msg_unpin : R.drawable.msg_pin,
                getString(pinned ? R.string.GlassgramPluginUnpin : R.string.GlassgramPluginPin), () -> togglePin(plugin))
            .add(R.drawable.msg_delete, getString(R.string.GlassgramPluginDelete), true, () -> deletePlugin(plugin))
            .show();
        return true;
    }

    void openPlugin(PluginInfo plugin) {
        final com.exteragram.messenger.plugins.Plugin extera = PluginsController.getExteraPlugin(plugin.id);
        if (extera != null) {
            presentFragment(new com.exteragram.messenger.plugins.ui.PluginSettingsActivity(extera));
        }
    }

    void togglePlugin(PluginInfo plugin, Utilities.Callback<Boolean> display) {
        PluginInfo current = PluginsController.getPlugin(plugin.id);
        if (current == null || pendingToggles.containsKey(plugin.id)) {
            return;
        }
        final boolean desired = !current.enabled;
        pendingToggles.put(plugin.id, desired);
        display.run(desired);
        // Record the optimistic state in the adapter as well as in the visible switch.
        // Intermediate engine notifications must not restore the old state.
        refreshPluginState();
        PluginsController.setPluginEnabled(plugin.id, desired, () -> {
            pendingToggles.remove(plugin.id);
            // The engine's actual state is authoritative, including a failed enable.
            refreshPluginState();
        });
    }

    void sharePlugin(PluginInfo plugin) {
        if (plugin.path == null) {
            PluginsController.sharePlugin(plugin.id);
        } else {
            GlassgramPluginInstaller.sharePlugin(this, plugin);
        }
    }

    void openSource(PluginInfo plugin) {
        if (plugin.path == null) {
            PluginsController.openPluginSource(plugin.id);
        } else {
            GlassgramPluginInstaller.openSource(this, new File(plugin.path), plugin.name);
        }
    }

    void togglePin(PluginInfo plugin) {
        SharedPreferences prefs = displayPreferences(getContext());
        Set<String> pinned = new HashSet<>(prefs.getStringSet("pinned", Collections.emptySet()));
        if (!pinned.remove(plugin.id)) pinned.add(plugin.id);
        prefs.edit().putStringSet("pinned", pinned).apply();
        refresh();
    }

    void deletePlugin(PluginInfo plugin) {
        com.exteragram.messenger.plugins.ui.PluginSettingsActivity.confirmDelete(this, plugin.id, plugin.name, () -> {
            if (PluginsController.getPlugin(plugin.id) == null && getContext() != null) {
                SharedPreferences prefs = displayPreferences(getContext());
                Set<String> pinned = new HashSet<>(prefs.getStringSet("pinned", Collections.emptySet()));
                if (pinned.remove(plugin.id)) prefs.edit().putStringSet("pinned", pinned).apply();
            }
            refresh();
        });
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PLUGIN && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            GlassgramPluginInstaller.installFromUri(this, data.getData());
        }
    }
}
