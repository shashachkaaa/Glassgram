package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * glassGram: the plugin catalog, the plugins glassGram's team vouches for (GET /plugins), and the
 * new versions of the plugins installed from it. The screen is GlassgramPluginCatalogActivity.
 */
public final class GlassgramPluginCatalog {

    public static final class Entry {
        public final String id;
        public final String name;
        public final String author;
        public final String description;
        public final String version;
        public final String sha256;
        public final int size;

        Entry(JSONObject json) {
            id = json.optString("id");
            name = json.optString("name");
            author = json.optString("author");
            description = json.optString("description");
            version = json.optString("version");
            sha256 = json.optString("sha256");
            size = json.optInt("size");
        }
    }

    private static final String PREFS = "glassgram_catalog";
    private static final long REFRESH = 30 * 60 * 1000L;
    private static final int MAX_SIZE = 1024 * 1024;
    private static final DispatchQueue queue = new DispatchQueue("glassgramCatalog");

    private static ArrayList<Entry> entries;
    private static long loadedAt;
    private static boolean loading;
    private static final ArrayList<Utilities.Callback<String>> waiting = new ArrayList<>();

    private GlassgramPluginCatalog() {
    }

    /** The catalog as last loaded, or null. */
    public static ArrayList<Entry> entries() {
        return entries;
    }

    /**
     * Loads the catalog, unless it was lately and not forced; the callback gets null when it is
     * loaded, or what went wrong.
     */
    public static void load(boolean force, Utilities.Callback<String> done) {
        if (!force && entries != null && System.currentTimeMillis() - loadedAt < REFRESH) {
            if (done != null) {
                done.run(null);
            }
            return;
        }
        if (done != null) {
            waiting.add(done);
        }
        if (loading) {
            return;
        }
        loading = true;
        GlassgramApi.request("GET", "/plugins", null, null, response -> {
            loading = false;
            final JSONArray list = response.json.optJSONArray("plugins");
            String error = null;
            if (response.ok() && list != null) {
                final ArrayList<Entry> loaded = new ArrayList<>();
                for (int i = 0; i < list.length(); i++) {
                    final JSONObject json = list.optJSONObject(i);
                    if (json != null && !json.optString("id").isEmpty()) {
                        loaded.add(new Entry(json));
                    }
                }
                entries = loaded;
                loadedAt = System.currentTimeMillis();
            } else {
                error = response.message();
            }
            final ArrayList<Utilities.Callback<String>> callbacks = new ArrayList<>(waiting);
            waiting.clear();
            for (Utilities.Callback<String> callback : callbacks) {
                callback.run(error);
            }
        });
    }

    /** The catalog's entry of a plugin, if any. */
    public static Entry find(String id) {
        if (entries == null || id == null) {
            return null;
        }
        for (Entry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** The catalog's entry when it is a newer version of an installed plugin, or null. */
    public static Entry updateFor(PluginInfo installed) {
        final Entry entry = installed != null ? find(installed.id) : null;
        return entry != null && GlassgramRemoteConfig.compareVersions(entry.version, installed.version) > 0 ? entry : null;
    }

    /** The installed plugins the catalog has newer versions of. */
    public static List<Entry> updates() {
        final ArrayList<Entry> result = new ArrayList<>();
        if (entries == null || !PluginsController.isStarted()) {
            return result;
        }
        for (PluginInfo plugin : PluginsController.getPlugins()) {
            final Entry update = updateFor(plugin);
            if (update != null) {
                result.add(update);
            }
        }
        return result;
    }

    /** Downloads a plugin of the catalog to the cache, checked against its SHA-256; the callback gets the file or null. */
    public static void download(Entry entry, Utilities.Callback<File> done) {
        queue.postRunnable(() -> {
            File file = null;
            final byte[] data = GlassgramApi.download("/plugins/" + entry.id + "/file", MAX_SIZE);
            if (data != null && Utilities.bytesToHex(Utilities.computeSHA256(data)).equalsIgnoreCase(entry.sha256)) {
                final File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "catalog_" + entry.id.replaceAll("[^A-Za-z0-9_.-]", "_") + ".plugin");
                try (FileOutputStream stream = new FileOutputStream(out)) {
                    stream.write(data);
                    file = out;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final File result = file;
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    /** When the app opens: tells once of new versions of installed plugins. */
    public static void checkOnOpen(Activity activity) {
        if (activity == null || !GlassgramConfig.pluginsEngine || !PluginsController.isStarted()) {
            return;
        }
        load(false, error -> {
            final List<Entry> updates = updates();
            if (error != null || updates.isEmpty()) {
                return;
            }
            // each new version is told of once
            final SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final Set<String> told = new HashSet<>(prefs.getStringSet("told", new HashSet<>()));
            boolean fresh = false;
            for (Entry update : updates) {
                fresh |= told.add(update.id + "@" + update.version);
            }
            if (!fresh) {
                return;
            }
            prefs.edit().putStringSet("told", told).apply();
            org.telegram.ui.GlassgramPluginCatalogActivity.tellOfUpdates(updates.size());
        });
    }
}
