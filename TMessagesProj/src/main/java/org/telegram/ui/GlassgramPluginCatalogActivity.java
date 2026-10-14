package org.telegram.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramApi;
import org.telegram.messenger.GlassgramPluginCatalog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * glassGram Preferences > Plugins > Catalog: the plugins glassGram's team vouches for, installed in
 * one tap, with the new versions of installed ones first. The team adds and removes them here.
 */
public class GlassgramPluginCatalogActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_RETRY = 1;
    private static final int ID_ADD = 2;
    private static final int ID_ENTRY = 1000;

    private static final int REQUEST_PICK_PLUGIN = 9111;

    private final ArrayList<GlassgramPluginCatalog.Entry> shown = new ArrayList<>();
    private boolean loading;
    private String error;

    /** Tells of new versions of installed plugins, where the person is now. */
    public static void tellOfUpdates(int count) {
        final BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_download,
                formatString(R.string.GlassgramCatalogUpdatesToast, count), getString(R.string.GlassgramCatalogOpen),
                () -> fragment.presentFragment(new GlassgramPluginCatalogActivity())).show();
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.glassgramPluginsUpdated);
        load(false);
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
            update();
        }
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void load(boolean force) {
        loading = true;
        error = null;
        update();
        GlassgramPluginCatalog.load(force, problem -> {
            loading = false;
            error = problem;
            update();
        });
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramCatalog);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown.clear();
        final ArrayList<GlassgramPluginCatalog.Entry> entries = GlassgramPluginCatalog.entries();
        if (entries == null) {
            items.add(UItem.asHeader(getString(R.string.GlassgramCatalog)));
            final UItem row = UItem.asButton(ID_RETRY, getString(loading ? R.string.Loading : R.string.GlassgramApiRetry), loading ? null : error);
            items.add(loading ? row.locked() : row);
            items.add(UItem.asShadow(getString(R.string.GlassgramCatalogInfo)));
        } else {
            final List<GlassgramPluginCatalog.Entry> updates = GlassgramPluginCatalog.updates();
            if (!updates.isEmpty()) {
                items.add(UItem.asHeader(getString(R.string.GlassgramCatalogUpdates)));
                for (GlassgramPluginCatalog.Entry entry : updates) {
                    final PluginInfo installed = PluginsController.getPlugin(entry.id);
                    items.add(UItem.asButton(ID_ENTRY + shown.size(), R.drawable.msg_download, entry.name,
                            (installed != null ? installed.version : "?") + " → " + entry.version));
                    shown.add(entry);
                }
                items.add(UItem.asShadow(null));
            }
            items.add(UItem.asHeader(getString(R.string.GlassgramCatalog)));
            for (GlassgramPluginCatalog.Entry entry : entries) {
                final PluginInfo installed = PluginsController.isStarted() ? PluginsController.getPlugin(entry.id) : null;
                final CharSequence state;
                if (installed == null) {
                    state = entry.version;
                } else if (GlassgramPluginCatalog.updateFor(installed) != null) {
                    state = getString(R.string.GlassgramCatalogUpdate);
                } else {
                    state = getString(R.string.GlassgramCatalogInstalled);
                }
                final String title = TextUtils.isEmpty(entry.author) ? entry.name : entry.name + " · " + entry.author;
                items.add(UItem.asButton(ID_ENTRY + shown.size(), title, state));
                shown.add(entry);
            }
            items.add(UItem.asShadow(getString(entries.isEmpty() ? R.string.GlassgramCatalogEmpty : R.string.GlassgramCatalogInfo)));
        }
        if (GlassgramApi.can(currentAccount, GlassgramApi.RIGHT_PLUGINS)) {
            items.add(UItem.asHeader(getString(R.string.GlassgramApiTools)));
            items.add(UItem.asButton(ID_ADD, R.drawable.msg_add, getString(R.string.GlassgramCatalogAdd)));
            items.add(UItem.asShadow(getString(R.string.GlassgramCatalogAddInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            load(true);
        } else if (item.id == ID_ADD) {
            try {
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_PICK_PLUGIN);
            } catch (Exception e) {
                BulletinFactory.of(this).createErrorBulletin(e.toString()).show();
            }
        } else if (item.id >= ID_ENTRY && item.id - ID_ENTRY < shown.size()) {
            install(shown.get(item.id - ID_ENTRY));
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        if (item.id < ID_ENTRY || item.id - ID_ENTRY >= shown.size() || !GlassgramApi.can(currentAccount, GlassgramApi.RIGHT_PLUGINS)) {
            return false;
        }
        final GlassgramPluginCatalog.Entry entry = shown.get(item.id - ID_ENTRY);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(entry.name)
                .setMessage(formatString(R.string.GlassgramCatalogRemoveConfirm, entry.name))
                .setPositiveButton(getString(R.string.GlassgramCatalogRemove), (d, w) -> {
                    final AlertDialog progress = GlassgramApiUi.progress(this);
                    GlassgramApi.call(currentAccount, "DELETE", "/plugins/" + Uri.encode(entry.id), null, response -> {
                        progress.dismiss();
                        if (response.ok()) {
                            GlassgramApiUi.showDone(this, formatString(R.string.GlassgramCatalogRemoved, entry.name));
                            load(true);
                        } else {
                            GlassgramApiUi.showError(this, response);
                        }
                    });
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
        return true;
    }

    /** Downloads the plugin and asks to install it, as a plugin file sent in a chat. */
    private void install(GlassgramPluginCatalog.Entry entry) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = GlassgramApiUi.progress(this);
        GlassgramPluginCatalog.download(entry, file -> {
            progress.dismiss();
            if (file == null) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramCatalogDownloadFailed)).show();
                return;
            }
            GlassgramPluginInstaller.showInstallDialog(this, file);
        });
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_PLUGIN && resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            addToCatalog(data.getData());
        }
    }

    /** The team: reads a plugin file's own metadata and puts it in the catalog. */
    private void addToCatalog(Uri uri) {
        if (!PluginsController.isSupported()) {
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramPluginsUnsupported)).show();
            return;
        }
        if (!PluginsController.isStarted()) {
            // the metadata is read by the engine, on the same queue it starts on
            PluginsController.setEngineEnabled(true);
        }
        PluginsController.pluginsQueue.postRunnable(() -> {
            final File copy = new File(ApplicationLoader.applicationContext.getCacheDir(), "catalog_upload.plugin");
            byte[] bytes = null;
            String[] meta = null;
            try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(copy)) {
                if (in == null) {
                    throw new IllegalStateException("No input");
                }
                final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                final byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > 1024 * 1024) {
                        throw new IllegalStateException("Larger than 1 MB");
                    }
                }
                bytes = buffer.toByteArray();
                out.write(bytes);
                out.close();
                meta = PluginsController.readPluginFile(copy);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final byte[] file = bytes;
            final String[] info = meta;
            AndroidUtilities.runOnUIThread(() -> {
                if (file == null || info == null || TextUtils.isEmpty(info[0])) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.GlassgramPluginBadFile)).show();
                    return;
                }
                confirmAdd(info, file);
            });
        });
    }

    private void confirmAdd(String[] meta, byte[] file) {
        if (getParentActivity() == null) {
            return;
        }
        final String id = meta[0], name = meta[1], version = meta[2], author = meta[3], description = meta[4], icon = meta[5];
        final GlassgramPluginCatalog.Entry current = GlassgramPluginCatalog.find(id);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.GlassgramCatalogAdd))
                .setMessage(current != null
                        ? formatString(R.string.GlassgramCatalogReplaceConfirm, name, current.version, version)
                        : formatString(R.string.GlassgramCatalogAddConfirm, name, version, author))
                .setPositiveButton(getString(R.string.GlassgramCatalogAddButton), (d, w) -> {
                    final AlertDialog progress = GlassgramApiUi.progress(this);
                    GlassgramApi.call(currentAccount, "PUT", "/plugins/" + Uri.encode(id), GlassgramApi.json(
                            "name", TextUtils.isEmpty(name) ? id : name, "author", author, "version", version,
                            "description", description, "icon", icon, "file", Base64.encodeToString(file, Base64.NO_WRAP)), response -> {
                        progress.dismiss();
                        if (response.ok()) {
                            GlassgramApiUi.showDone(this, formatString(R.string.GlassgramCatalogAdded, name));
                            load(true);
                        } else {
                            GlassgramApiUi.showError(this, response);
                        }
                    });
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
