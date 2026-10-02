package org.telegram.messenger.plugins;

import android.text.TextUtils;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/** Java side of the client_utils and ui.bulletin helpers of the plugin SDK. */
public final class PluginsBridge {

    public static final int MEDIA_PHOTO = 0;
    public static final int MEDIA_VIDEO = 1;
    public static final int MEDIA_DOCUMENT = 2;
    public static final int MEDIA_AUDIO = 3;

    private PluginsBridge() {
    }

    /** A message object standing for message id in peer, enough to reply to it. */
    public static MessageObject replyMessageObject(int account, long peer, int messageId) {
        TLRPC.Message message = new TLRPC.TL_message();
        message.id = messageId;
        message.dialog_id = peer;
        message.peer_id = MessagesController.getInstance(account).getPeer(peer);
        message.message = "";
        message.media = new TLRPC.TL_messageMediaEmpty();
        return new MessageObject(account, message, false, false);
    }

    public static void sendMedia(int account, long peer, String path, String caption, ArrayList<TLRPC.MessageEntity> entities, int kind, boolean highQuality, MessageObject replyTo, boolean notify, int scheduleDate, boolean spoiler) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = path;
        info.caption = TextUtils.isEmpty(caption) ? null : caption;
        info.entities = entities;
        info.isVideo = kind == MEDIA_VIDEO;
        info.highQuality = highQuality;
        info.hasMediaSpoilers = spoiler;
        final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        media.add(info);
        final boolean asDocument = kind == MEDIA_DOCUMENT || kind == MEDIA_AUDIO;
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, peer,
            replyTo, replyTo, null, null, asDocument, false, null, notify, scheduleDate, 0, 0, false, null, null, 0, false, 0, 0, null));
    }

    public static void editMessage(int account, MessageObject messageObject, String text, ArrayList<TLRPC.MessageEntity> entities, String path, boolean spoiler) {
        if (messageObject == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!TextUtils.isEmpty(path)) {
                SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                info.path = path;
                info.caption = text != null ? text : messageObject.messageOwner.message;
                info.entities = text != null ? entities : messageObject.messageOwner.entities;
                info.hasMediaSpoilers = spoiler;
                String lower = path.toLowerCase();
                info.isVideo = lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".mkv");
                boolean photo = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".heic");
                ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
                media.add(info);
                SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, messageObject.getDialogId(),
                    null, null, null, null, !photo && !info.isVideo, false, messageObject, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
            } else if (text != null) {
                SendMessagesHelper.getInstance(account).editMessage(messageObject, text, false, null, entities, 0, 0);
            }
        });
    }

    public static void showBulletin(BaseFragment fragment, String kind, String text, String subtitle, int iconResId, String button, Runnable onButton, Runnable onAction, int duration, int amount, String fileType) {
        AndroidUtilities.runOnUIThread(() -> {
            BulletinFactory factory = fragment != null ? BulletinFactory.of(fragment) : BulletinFactory.global();
            Bulletin bulletin;
            switch (kind) {
                case "error":
                    bulletin = factory.createErrorBulletin(text);
                    break;
                case "success":
                    bulletin = factory.createSuccessBulletin(text);
                    break;
                case "simple":
                    bulletin = factory.createSimpleBulletin(iconResId != 0 ? iconResId : R.raw.info, text);
                    break;
                case "two_line":
                    bulletin = factory.createSimpleBulletin(iconResId != 0 ? iconResId : R.raw.info, text, subtitle);
                    break;
                case "button":
                    bulletin = factory.createSimpleBulletin(iconResId != 0 ? iconResId : R.raw.info, text, button, duration > 0 ? duration : Bulletin.DURATION_LONG, onButton);
                    break;
                case "undo":
                    bulletin = subtitle != null ? factory.createUndoBulletin(text, subtitle, onButton, onAction) : factory.createUndoBulletin(text, onButton, onAction);
                    break;
                case "copied":
                    bulletin = factory.createCopyBulletin(text != null ? text : LocaleController.getString(R.string.TextCopied));
                    break;
                case "link":
                    bulletin = factory.createCopyLinkBulletin(amount != 0);
                    break;
                case "gallery":
                case "downloads":
                    BulletinFactory.FileType type;
                    try {
                        type = BulletinFactory.FileType.valueOf(fileType != null ? fileType : "UNKNOWN");
                    } catch (Exception e) {
                        type = BulletinFactory.FileType.UNKNOWN;
                    }
                    bulletin = factory.createDownloadBulletin(type, Math.max(1, amount), null);
                    break;
                case "info":
                default:
                    bulletin = factory.createSimpleBulletin(R.raw.info, text);
                    break;
            }
            if (bulletin != null) {
                if (duration > 0 && !"button".equals(kind)) {
                    bulletin.setDuration(duration);
                }
                bulletin.show();
            }
        });
    }
}
