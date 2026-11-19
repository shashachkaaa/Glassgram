package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * "Keep for me" in the delete dialog: the messages are deleted on Telegram for everyone, but stay
 * in this chat on this device, marked as deleted like messages others deleted.
 */
public final class GlassgramKeepDeleted {

    private GlassgramKeepDeleted() {
    }

    public static void delete(int account, ArrayList<Integer> ids, long dialogId) {
        if (ids == null || ids.isEmpty() || DialogObject.isEncryptedDialog(dialogId)) {
            return;
        }
        MessagesController controller = MessagesController.getInstance(account);
        TLRPC.Chat chat = dialogId < 0 ? controller.getChat(-dialogId) : null;
        final long channelId = chat != null && ChatObject.isChannel(chat) ? chat.id : 0;
        final long selfId = UserConfig.getInstance(account).getClientUserId();
        final ArrayList<Integer> toSend = new ArrayList<>();
        for (Integer id : ids) {
            if (id != null && id > 0) {
                toSend.add(id);
            }
        }
        if (toSend.isEmpty()) {
            return;
        }
        // An update may arrive before the RPC response. Keep the rows, but show the mark only on success.
        GlassgramSpyStorage.setPendingDeletion(account, channelId, toSend, true);

        if (channelId != 0) {
            TLRPC.TL_channels_deleteMessages req = new TLRPC.TL_channels_deleteMessages();
            req.id = toSend;
            req.channel = controller.getInputChannel(channelId);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (complete(account, selfId, channelId, toSend,
                        error == null && response instanceof TLRPC.TL_messages_affectedMessages)) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewChannelDifferenceParams(res.pts, res.pts_count, channelId);
                }
            });
        } else {
            TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
            req.id = toSend;
            req.revoke = true;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (complete(account, selfId, channelId, toSend,
                        error == null && response instanceof TLRPC.TL_messages_affectedMessages)) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            });
        }
    }

    private static boolean complete(int account, long selfId, long channelId, ArrayList<Integer> ids, boolean success) {
        if (selfId != UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        if (success) {
            GlassgramSpyStorage.retainDeletedMessages(account, -channelId, ids);
            GlassgramSpyStorage.markDeleted(account, channelId, ids);
            AndroidUtilities.runOnUIThread(() -> {
                if (selfId == UserConfig.getInstance(account).getClientUserId()) {
                    NotificationCenter.getInstance(account).postNotificationName(
                            NotificationCenter.glassgramMessagesMarkedDeleted, ids, channelId);
                }
            });
        } else {
            AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(
                    ApplicationLoader.applicationContext, LocaleController.getString(R.string.ErrorOccurred),
                    android.widget.Toast.LENGTH_SHORT).show());
        }
        GlassgramSpyStorage.setPendingDeletion(account, channelId, ids, false);
        return success;
    }
}
