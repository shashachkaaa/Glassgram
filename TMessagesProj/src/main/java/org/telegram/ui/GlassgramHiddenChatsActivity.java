package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.GlassgramChatLock;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * The account's hidden chats (GlassgramChatLock), opened with the chat PIN: tapped, a chat
 * opens; held, it goes back to the chat lists.
 */
public class GlassgramHiddenChatsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    private final ArrayList<Long> dialogs = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.glassgramChatsHiddenChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.glassgramChatsHiddenChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.glassgramChatsHiddenChanged && listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramHiddenChats);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        dialogs.clear();
        dialogs.addAll(GlassgramChatLock.hiddenDialogs(currentAccount));
        if (dialogs.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.GlassgramHiddenChatsEmpty)));
            return;
        }
        for (int i = 0; i < dialogs.size(); i++) {
            final UItem item = UItem.asFilterChat(true, dialogs.get(i));
            item.id = i + 1;
            items.add(item);
        }
        items.add(UItem.asShadow(getString(R.string.GlassgramHiddenChatsInfo)));
    }

    private long dialogOf(UItem item) {
        final int index = item.id - 1;
        return index >= 0 && index < dialogs.size() ? dialogs.get(index) : 0;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        final long dialogId = dialogOf(item);
        if (dialogId == 0) {
            return;
        }
        // the PIN was given to come here
        GlassgramChatLock.markUnlocked(currentAccount, dialogId);
        presentFragment(ChatActivity.of(dialogId));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final long dialogId = dialogOf(item);
        if (dialogId == 0 || getParentActivity() == null) {
            return false;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.GlassgramShowChat))
                .setMessage(getString(R.string.GlassgramShowChatInfo))
                .setPositiveButton(getString(R.string.GlassgramShowChatButton), (d, w) -> GlassgramChatLock.setHidden(currentAccount, dialogId, false))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
        return true;
    }
}
