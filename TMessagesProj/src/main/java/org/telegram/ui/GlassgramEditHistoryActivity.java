package org.telegram.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.GlassgramSpyStorage;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.GlassgramWallpaperLayout;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Glassgram Spy: earlier texts of an edited message, shown as chat bubbles on the chat wallpaper.
 */
public class GlassgramEditHistoryActivity extends BaseFragment {

    private final MessageObject message;
    private LinearLayout listLayout;
    private ScrollView scrollView;

    public GlassgramEditHistoryActivity(MessageObject message) {
        this.message = message;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getDialogTitle());
        actionBar.setSubtitle(LocaleController.getString(org.telegram.messenger.R.string.GlassgramEditHistory));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        GlassgramWallpaperLayout root = new GlassgramWallpaperLayout(context);

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setGravity(Gravity.BOTTOM);
        listLayout.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(12));
        scrollView.addView(listLayout, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        fragmentView = root;

        final int account = currentAccount;
        final long dialogId = message.getDialogId();
        final int messageId = message.getId();
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<GlassgramSpyStorage.EditVersion> versions = GlassgramSpyStorage.loadEditHistory(account, dialogId, messageId);
            AndroidUtilities.runOnUIThread(() -> showVersions(context, versions));
        });
        return fragmentView;
    }

    private CharSequence getDialogTitle() {
        long dialogId = message.getDialogId();
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getMessagesController().getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
        } else {
            TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
            if (chat != null) {
                return chat.title;
            }
        }
        return LocaleController.getString(org.telegram.messenger.R.string.GlassgramEditHistory);
    }

    private void showVersions(Context context, ArrayList<GlassgramSpyStorage.EditVersion> versions) {
        if (listLayout == null || getParentActivity() == null) {
            return;
        }
        listLayout.removeAllViews();
        if (versions.isEmpty()) {
            ChatActionCell empty = new ChatActionCell(context);
            empty.setCustomText(LocaleController.getString(org.telegram.messenger.R.string.GlassgramEditHistoryEmpty));
            listLayout.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }
        long dialogId = message.getDialogId();
        TLRPC.Chat chat = DialogObject.isChatDialog(dialogId) ? getMessagesController().getChat(-dialogId) : null;
        boolean isGroup = chat != null && (!ChatObject.isChannel(chat) || chat.megagroup);
        int lastDay = -1;
        for (int i = 0; i < versions.size(); i++) {
            GlassgramSpyStorage.EditVersion version = versions.get(i);
            int day = dayOf(version.date);
            if (day != lastDay) {
                lastDay = day;
                ChatActionCell dateCell = new ChatActionCell(context);
                dateCell.setCustomDate(version.date, false, false);
                listLayout.addView(dateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            MessageObject versionObject = createVersionObject(version);
            ChatMessageCell cell = new ChatMessageCell(context, currentAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
            });
            cell.isChat = isGroup;
            cell.setFullyDraw(true);
            cell.setMessageObject(versionObject, null, false, false, false);
            listLayout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private static int dayOf(int date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(date * 1000L);
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }

    private MessageObject createVersionObject(GlassgramSpyStorage.EditVersion version) {
        TLRPC.Message original = message.messageOwner;
        TLRPC.Message msg = new TLRPC.TL_message();
        msg.id = original.id;
        msg.message = version.text;
        msg.date = version.date;
        msg.dialog_id = message.getDialogId();
        msg.out = original.out;
        msg.from_id = original.from_id;
        msg.peer_id = original.peer_id;
        msg.post = original.post;
        msg.media = version.media != null ? version.media : new TLRPC.TL_messageMediaEmpty();
        msg.flags = original.from_id != null ? TLRPC.MESSAGE_FLAG_HAS_FROM_ID : 0;
        if (version.media != null) {
            msg.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        }
        MessageObject object = new MessageObject(currentAccount, msg, true, false);
        object.eventId = 1;
        object.resetLayout();
        return object;
    }
}
