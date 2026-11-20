package org.telegram.ui;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;

import com.chaquo.python.Python;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.plugins.PluginInfo;
import org.telegram.messenger.plugins.PluginsController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.telegram.messenger.LocaleController.getString;

public class GlassgramRepositoriesActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final org.telegram.messenger.DispatchQueue queue =
        new org.telegram.messenger.DispatchQueue("pluginRepositories");
    private final String source;
    private final ArrayList<String> sources = new ArrayList<>();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ArrayList<Entry> shown = new ArrayList<>();
    private final ArrayDeque<TLRPC.Message> documents = new ArrayDeque<>();
    private TLRPC.Message pending;
    private int offset, examined;
    private long peer;
    private String status = "";

    public GlassgramRepositoriesActivity() { this(null); }

    private GlassgramRepositoriesActivity(String source) { this.source = source; }

    private static class Entry {
        String id, name, version;
        int date;
        File file;
        JSONObject row;
    }

    private SharedPreferences prefs() {
        return MessagesController.getMainSettings(currentAccount);
    }

    @Override public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
        getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
        if (source != null) refresh(false);
        return true;
    }

    @Override public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
        getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
        super.onFragmentDestroy();
    }

    @Override protected CharSequence getTitle() { return getString(R.string.GlassgramRepositories); }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (source == null) {
            sources.clear();
            sources.addAll(prefs().getStringSet("pluginRepositories", java.util.Collections.emptySet()));
            java.util.Collections.sort(sources);
            items.add(UItem.asButton(1, R.drawable.msg_add, getString(R.string.Add)));
            for (int i = 0; i < sources.size(); i++) items.add(UItem.asButton(100 + i, sources.get(i)));
        } else {
            items.add(UItem.asButton(1, source));
            shown.clear();
            shown.addAll(entries.values());
            for (int i = 0; i < shown.size(); i++) {
                Entry e = shown.get(i);
                PluginInfo installed = PluginsController.getPlugin(e.id);
                String version = installed == null || org.telegram.messenger.GlassgramRemoteConfig.compareVersions(e.version, installed.version) <= 0 ? e.version : installed.version + " → " + e.version;
                items.add(UItem.asButton(100 + i, e.name, version));
            }
            items.add(UItem.asShadow(status));
        }
    }

    private void update() {
        if (!isFinished && listView != null) listView.adapter.update(true);
    }

    private void save(String value, boolean add) {
        Set<String> values = new LinkedHashSet<>(prefs().getStringSet("pluginRepositories", java.util.Collections.emptySet()));
        if (add) values.add(value); else values.remove(value);
        prefs().edit().putStringSet("pluginRepositories", values).apply();
        update();
    }

    @Override protected boolean onLongClick(UItem item, View v, int position, float x, float y) {
        if (source != null || item.id < 100) return false;
        String value = sources.get(item.id - 100);
        showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(value)
            .setPositiveButton(getString(R.string.Delete), (d, w) -> save(value, false))
            .setNegativeButton(getString(R.string.Cancel), null).create());
        return true;
    }

    @Override protected void onClick(UItem item, View v, int position, float x, float y) {
        if (item.id == 1 && source == null) {
            EditText input = new EditText(getParentActivity());
            input.setSingleLine();
            input.setHint("https://github.com/… / @channel");
            showDialog(new AlertDialog.Builder(getParentActivity()).setTitle(getTitle()).setView(input)
                .setPositiveButton(getString(R.string.Add), (d, w) -> {
                    String value = input.getText().toString().trim();
                    android.net.Uri uri = android.net.Uri.parse(value);
                    if ("t.me".equalsIgnoreCase(uri.getHost())) {
                        String git = uri.getQueryParameter("git"), channel = uri.getQueryParameter("c");
                        if ("/exteraPluginRepo".equalsIgnoreCase(uri.getPath()) && git != null) value = git;
                        else value = "@" + (channel == null ? uri.getLastPathSegment() : channel);
                    }
                    if (value.matches("@[A-Za-z0-9_]{1,32}") || value.startsWith("https://")) {
                        save(value, true);
                        presentFragment(new GlassgramRepositoriesActivity(value));
                    } else {
                        showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(getString(R.string.GlassgramRepoInvalid)).setPositiveButton(getString(R.string.OK), null).create());
                    }
                }).setNegativeButton(getString(R.string.Cancel), null).create());
        } else if (item.id == 1) {
            if (!busy) refresh(true);
        } else if (item.id >= 100 && source == null) {
            presentFragment(new GlassgramRepositoriesActivity(sources.get(item.id - 100)));
        } else if (item.id >= 100) {
            Entry e = shown.get(item.id - 100);
            if (e.file != null) GlassgramPluginInstaller.showInstallDialog(this, e.file);
            else background(() -> {
                String path = Python.getInstance().getModule("_glassgram_repositories").callAttr("download",
                    e.row.toString(), ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath()).toString();
                AndroidUtilities.runOnUIThread(() -> {
                    File file = new File(path);
                    if (!isFinished) GlassgramPluginInstaller.preview(this, file, true);
                    else file.delete();
                });
            });
        }
    }

    private interface Job { void run() throws Exception; }
    private void background(Job job) {
        queue.postRunnable(() -> {
            try { job.run(); }
            catch (Exception e) { AndroidUtilities.runOnUIThread(() -> finish(e.toString())); }
        });
    }

    private boolean busy;
    private void finish(String message) {
        busy = false;
        status = message;
        update();
    }

    private void refresh(boolean force) {
        busy = true;
        entries.clear();
        status = getString(R.string.Loading);
        update();
        if (source.startsWith("@")) {
            getMessagesController().getUserNameResolver().resolve(source.substring(1), id -> AndroidUtilities.runOnUIThread(() -> {
                if (isFinished) return;
                if (id == null || id >= 0) { finish(getString(R.string.GlassgramRepoInvalid)); return; }
                peer = id;
                examined = offset = 0;
                page();
            }));
            return;
        }
        background(() -> {
            JSONArray rows = new JSONArray(Python.getInstance().getModule("_glassgram_repositories").callAttr("entries", source, force).toString());
            ArrayList<Entry> result = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                Entry e = new Entry();
                e.row = rows.getJSONObject(i);
                e.id = e.row.getString("id");
                e.name = e.row.getString("name");
                e.version = e.row.getString("version");
                result.add(e);
            }
            AndroidUtilities.runOnUIThread(() -> {
                for (Entry e : result) entries.put(e.id, e);
                finish("");
            });
        });
    }

    private void page() {
        TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
        req.peer = getMessagesController().getInputPeer(peer);
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterDocument();
        req.limit = Math.min(100, 2000 - examined);
        req.offset_id = offset;
        int request = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isFinished) return;
            if (!(response instanceof TLRPC.messages_Messages)) { finish(error == null ? getString(R.string.GlassgramRepoInvalid) : error.text); return; }
            ArrayList<TLRPC.Message> messages = ((TLRPC.messages_Messages) response).messages;
            if (messages.isEmpty()) { finish(""); return; }
            int next = messages.get(messages.size() - 1).id;
            examined += messages.size();
            if (next == offset) { finish(""); return; }
            offset = next;
            for (TLRPC.Message m : messages) {
                TLRPC.Document doc = MessageObject.getDocument(m);
                if (doc == null) continue;
                String n = FileLoader.getDocumentFileName(doc).toLowerCase(java.util.Locale.ROOT);
                if (doc.size > 0 && ((n.endsWith(".plugin") && doc.size <= 2 * 1024 * 1024)
                        || (n.matches(".*\\.(elyx|eaf)(\\.zip)?") && doc.size <= 10 * 1024 * 1024))) documents.add(m);
            }
            advance();
        }));
        getConnectionsManager().bindRequestToGuid(request, classGuid);
    }

    private void advance() {
        if (isFinished) return;
        pending = documents.poll();
        if (pending == null) {
            if (examined < 2000) page(); else finish("");
            return;
        }
        TLRPC.Document doc = MessageObject.getDocument(pending);
        File file = getFileLoader().getPathToAttach(doc, true);
        if (file.isFile() && file.length() == doc.size) read(file);
        else getFileLoader().loadFile(doc, pending, FileLoader.PRIORITY_LOW, 1);
    }

    private void read(File file) {
        TLRPC.Message message = pending;
        pending = null;
        PluginsController.pluginsQueue.postRunnable(() -> {
            String[] meta = null;
            try {
                com.chaquo.python.PyObject metadata = PluginsController.getEngine().callAttr("_read_meta", file.getAbsolutePath());
                java.util.Map<com.chaquo.python.PyObject, com.chaquo.python.PyObject> values = metadata.asMap();
                meta = new String[] {values.get(com.chaquo.python.PyObject.fromJava("id")).toString(), values.get(com.chaquo.python.PyObject.fromJava("name")).toString(), values.get(com.chaquo.python.PyObject.fromJava("version")).toString()};
            } catch (Exception ignored) {}
            final String[] info = meta;
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished) return;
                if (info != null) {
                    Entry old = entries.get(info[0]);
                    int date = Math.max(message.date, message.edit_date);
                    if (old == null || date > old.date) {
                        Entry e = new Entry();
                        e.id = info[0]; e.name = info[1]; e.version = info[2]; e.date = date; e.file = file;
                        entries.put(e.id, e);
                        update();
                    }
                }
                advance();
            });
        });
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (pending == null || !FileLoader.getAttachFileName(MessageObject.getDocument(pending)).equals(args[0])) return;
        if (id == NotificationCenter.fileLoaded) read((File) args[1]);
        else advance();
    }
}
