package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramLocalDialogs;
import org.telegram.messenger.GlassgramSpyMedia;
import org.telegram.messenger.GlassgramChatLock;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** Local history only: no ChatActivity, network requests, composer or simulated membership. */
public final class GlassgramLocalDialogActivity extends UniversalFragment {
    private final GlassgramLocalDialogs.Entry entry;
    private final ArrayList<GlassgramLocalDialogs.Row> rows = new ArrayList<>();
    private boolean loading, more = true;
    private static final int LOAD = 1, DELETE = 2, FILE = 1000;

    public GlassgramLocalDialogActivity(int account, GlassgramLocalDialogs.Entry entry) {
        this.currentAccount = account;
        this.entry = entry;
    }

    private boolean allowed() {
        return !GlassgramConfig.STORE_BUILD && GlassgramConfig.spySaveDeletedMessages
                && entry.self == UserConfig.getInstance(currentAccount).getClientUserId()
                && !GlassgramChatLock.isLocked(currentAccount,entry.did)
                && !GlassgramChatLock.isHidden(currentAccount,entry.did);
    }

    @Override public boolean onFragmentCreate() {
        if (!allowed() || !super.onFragmentCreate()) return false;
        load();
        return true;
    }

    @Override public void onResume() {
        super.onResume();
        if (!allowed()) finishFragment();
    }

    private void load() {
        if (loading || !more || !allowed()) return;
        loading = true;
        final int before = rows.isEmpty() ? Integer.MAX_VALUE : rows.get(rows.size()-1).mid;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<GlassgramLocalDialogs.Row> page = GlassgramLocalDialogs.read(currentAccount,entry,before);
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || !allowed()) return;
                loading = false;
                rows.addAll(page);
                more = page.size() == 100;
                if (listView != null) listView.adapter.update(false);
            });
        });
    }

    @Override protected CharSequence getTitle() { return entry.title; }

    @Override protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!allowed()) return;
        items.add(UItem.asShadow(LocaleController.getString(R.string.GlassgramLocalDialogInfo)));
        items.add(UItem.asButton(DELETE, R.drawable.msg_delete, LocaleController.getString(R.string.GlassgramLocalDialogRemove)).red());
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (int i = 0; i < rows.size(); i++) {
            GlassgramLocalDialogs.Row row = rows.get(i);
            items.add(UItem.asHeader((row.sender == null ? "" : row.sender + " · ") + date.format(new Date(row.date * 1000L))));
            if (row.body != null && !row.body.isEmpty()) items.add(UItem.asShadow(row.body));
            if (row.attachment != null && !row.attachment.isEmpty()) {
                String name = row.filename == null || row.filename.isEmpty() ? row.attachment : row.filename;
                items.add(UItem.asButton(FILE + i, name,
                        LocaleController.getString(R.string.GlassgramLocalDialogOpenFile)));
            }
        }
        if (more && !loading) items.add(UItem.asButton(LOAD, LocaleController.getString(R.string.GlassgramLocalDialogEarlier)));
    }

    @Override protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!allowed() || getParentActivity() == null) return;
        if (item.id == LOAD) {
            load();
        } else if (item.id == DELETE) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.GlassgramLocalDialogRemove));
            builder.setMessage(LocaleController.getString(R.string.GlassgramLocalDialogRemoveInfo));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.setPositiveButton(LocaleController.getString(R.string.Delete),
                    (d,w) -> GlassgramLocalDialogs.remove(currentAccount,entry,() -> {
                        if (!isFinished) finishFragment();
                    }));
            showDialog(builder.create());
        } else if (item.id >= FILE && item.id - FILE < rows.size()) {
            GlassgramLocalDialogs.Row row = rows.get(item.id-FILE);
            File file = GlassgramSpyMedia.find(currentAccount,row.attachment);
            if (file != null && file.isFile()) {
                AndroidUtilities.openForView(file,row.filename,row.mime,getParentActivity(),getResourceProvider(),false);
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setMessage(LocaleController.getString(R.string.GlassgramLocalDialogUnavailable));
                builder.setPositiveButton(LocaleController.getString(R.string.OK),null);
                showDialog(builder.create());
            }
        }
    }

    @Override protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
