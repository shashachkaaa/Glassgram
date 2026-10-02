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
        final ArrayList<Integer> toSend = new ArrayList<>(ids);

        // Marked first, so the deletion coming back from the server leaves them in place
        GlassgramSpyStorage.markDeleted(account, channelId, toSend);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.glassgramMessagesMarkedDeleted, toSend, channelId);

        if (channelId != 0) {
            TLRPC.TL_channels_deleteMessages req = new TLRPC.TL_channels_deleteMessages();
            req.id = toSend;
            req.channel = controller.getInputChannel(channelId);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewChannelDifferenceParams(res.pts, res.pts_count, channelId);
                }
            });
        } else {
            TLRPC.TL_messages_deleteMessages req = new TLRPC.TL_messages_deleteMessages();
            req.id = toSend;
            req.revoke = true;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            });
        }
    }
}
