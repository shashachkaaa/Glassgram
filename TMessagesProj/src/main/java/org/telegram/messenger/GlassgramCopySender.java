package org.telegram.messenger;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

/**
 * Sends a copy of a message that Telegram would refuse to forward: media with a self-destruct
 * timer, or messages from chats with forwarding restricted. The text is sent again and the
 * media is uploaded again from the local file, downloading it first when needed. Copies from
 * protected channels and groups are signed with the source name.
 */
public final class GlassgramCopySender implements NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final MessageObject message;
    private final long peer;
    private final boolean notify;
    private String waitingFileName;

    private GlassgramCopySender(int account, MessageObject message, long peer, boolean notify) {
        this.account = account;
        this.message = message;
        this.peer = peer;
        this.notify = notify;
    }

    /** Whether forwarding this message has to be done as a copy. */
    public static boolean needsCopy(int account, MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (GlassgramConfig.spyDisableSelfDestruct && isSelfDestructing(message)) {
            return true;
        }
        if (!GlassgramConfig.ignoreContentProtection) {
            return false;
        }
        if (isSelfDestructing(message) || message.messageOwner.noforwards) {
            return true;
        }
        return isPeerProtected(account, message.getDialogId());
    }

    public static boolean isSelfDestructing(MessageObject message) {
        TLRPC.Message owner = message.messageOwner;
        return message.isSecretMedia() || owner.ttl > 0 || owner.media != null && owner.media.ttl_seconds > 0;
    }

    /** The chat's own setting, read directly: isPeerNoForwards answers false when protection is ignored. */
    private static boolean isPeerProtected(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId < 0) {
            TLRPC.Chat chat = controller.getChat(-dialogId);
            if (chat == null) {
                return false;
            }
            if (chat.migrated_to != null) {
                TLRPC.Chat migratedTo = controller.getChat(chat.migrated_to.channel_id);
                if (migratedTo != null) {
                    return migratedTo.noforwards;
                }
            }
            return chat.noforwards;
        }
        TLRPC.UserFull userFull = controller.getUserFull(dialogId);
        return userFull != null && (userFull.noforwards_peer_enabled || userFull.noforwards_my_enabled);
    }

    public static void send(int account, MessageObject message, long peer, boolean notify) {
        new GlassgramCopySender(account, message, peer, notify).start();
    }

    private void start() {
        TLRPC.Message owner = message.messageOwner;
        boolean hasMedia = owner.media != null && !(owner.media instanceof TLRPC.TL_messageMediaEmpty) && !(owner.media instanceof TLRPC.TL_messageMediaWebPage);
        if (!hasMedia) {
            sendText();
            return;
        }
        if (!(message.isPhoto() || message.isVideo() || message.isGif() || message.isRoundVideo() || message.isVoice() || message.isMusic() || message.getDocument() != null && !message.isAnyKindOfSticker())) {
            toast(R.string.GlassgramCopyUnsupported);
            return;
        }
        File file = localFile();
        if (file != null) {
            sendMedia(file);
            return;
        }
        waitingFileName = message.getFileName();
        if (TextUtils.isEmpty(waitingFileName)) {
            toast(R.string.GlassgramCopyUnsupported);
            return;
        }
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
        if (message.isPhoto()) {
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
            TLRPC.Photo photo = owner.media.photo;
            FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, photo), message, null, FileLoader.PRIORITY_HIGH, 0);
        } else {
            FileLoader.getInstance(account).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
        }
        toast(R.string.GlassgramCopyDownloading);
    }

    private File localFile() {
        TLRPC.Message owner = message.messageOwner;
        if (!TextUtils.isEmpty(owner.attachPath)) {
            File f = new File(owner.attachPath);
            if (f.exists() && f.length() > 0) {
                return f;
            }
        }
        File f = FileLoader.getInstance(account).getPathToMessage(owner);
        if (f != null && f.exists() && f.length() > 0) {
            return f;
        }
        return null;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (args.length == 0 || !(args[0] instanceof String) || !args[0].equals(waitingFileName)) {
            return;
        }
        NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.fileLoadFailed);
        File file = id == NotificationCenter.fileLoaded ? localFile() : null;
        if (file == null) {
            toast(R.string.GlassgramCopyFailed);
            return;
        }
        sendMedia(file);
    }

    private String signedText(String text) {
        String signature = signature();
        if (signature == null) {
            return text == null ? "" : text;
        }
        return TextUtils.isEmpty(text) ? signature : text + "\n\n" + signature;
    }

    /** "— Channel name (@username)" for copies from protected channels and groups. */
    private String signature() {
        long dialogId = message.getDialogId();
        if (dialogId >= 0 || isSelfDestructing(message)) {
            return null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null || TextUtils.isEmpty(chat.title)) {
            return null;
        }
        String username = ChatObject.getPublicUsername(chat);
        return "— " + chat.title + (TextUtils.isEmpty(username) ? "" : " (@" + username + ")");
    }

    private ArrayList<TLRPC.MessageEntity> entities() {
        return message.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(message.messageOwner.entities);
    }

    private void sendText() {
        String text = signedText(message.messageOwner.message);
        if (TextUtils.isEmpty(text)) {
            toast(R.string.GlassgramCopyUnsupported);
            return;
        }
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
            text, peer, null, null, null, true, entities(), null, null, notify, 0, 0, null, false));
    }

    private void sendMedia(File file) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = file.getAbsolutePath();
        info.caption = signedText(message.messageOwner.message);
        info.entities = entities();
        info.isVideo = message.isVideo() || message.isRoundVideo();
        boolean asDocument = !(message.isPhoto() || message.isVideo() || message.isRoundVideo());
        ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        media.add(info);
        AndroidUtilities.runOnUIThread(() -> SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, peer,
            null, null, null, null, asDocument, false, null, notify, 0, 0, 0, false, null, null, 0, false, 0, 0, null));
    }

    private static void toast(int res) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(res), Toast.LENGTH_SHORT).show();
            } catch (Exception ignore) {
            }
        });
    }
}
