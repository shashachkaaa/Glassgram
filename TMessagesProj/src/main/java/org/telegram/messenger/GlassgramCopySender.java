package org.telegram.messenger;

import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Sends a copy of messages that Telegram would refuse to forward: media with a self-destruct
 * timer, or messages from chats with forwarding restricted. The text is sent again and the
 * media is uploaded again from the local files, downloading them first when needed.
 *
 * Copies from protected channels and groups: an album goes as an album, the original captions
 * of media are left out, and the copy is signed with the source name.
 */
public final class GlassgramCopySender implements NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final ArrayList<MessageObject> messages;
    private final long peer;
    private final boolean notify;
    private final File[] files;
    private final ArrayList<String> waitingFileNames = new ArrayList<>();
    private boolean finished;

    private GlassgramCopySender(int account, ArrayList<MessageObject> messages, long peer, boolean notify) {
        this.account = account;
        this.messages = messages;
        this.peer = peer;
        this.notify = notify;
        this.files = new File[messages.size()];
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

    /** A copy from a protected chat (not self-destructing media): album rules and the signature apply. */
    private static boolean isProtectedCopy(MessageObject message) {
        return !isSelfDestructing(message);
    }

    /**
     * Sends copies of the messages, in order. Album items from protected chats are collected
     * back into one album.
     */
    public static void send(int account, ArrayList<MessageObject> messages, long peer, boolean notify) {
        LinkedHashMap<Long, ArrayList<MessageObject>> groups = new LinkedHashMap<>();
        long single = Long.MIN_VALUE;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            long groupId = message.getGroupId();
            long key = groupId != 0 && isProtectedCopy(message) && isAlbumMedia(message) ? groupId : single++;
            ArrayList<MessageObject> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(message);
        }
        for (ArrayList<MessageObject> group : groups.values()) {
            new GlassgramCopySender(account, group, peer, notify).start();
        }
    }

    private static boolean hasMedia(MessageObject message) {
        TLRPC.MessageMedia media = message.messageOwner.media;
        return media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage);
    }

    private static boolean isVisualMedia(MessageObject message) {
        return message.isPhoto() || message.isVideo();
    }

    private static boolean isAlbumMedia(MessageObject message) {
        return isVisualMedia(message) || isFileMedia(message);
    }

    private static boolean isFileMedia(MessageObject message) {
        return message.getDocument() != null && !message.isAnyKindOfSticker() && !isVisualMedia(message);
    }

    private static boolean isCopyable(MessageObject message) {
        return message.isPhoto() || message.isVideo() || message.isGif() || message.isRoundVideo() || message.isVoice() || message.isMusic() || message.getDocument() != null && !message.isAnyKindOfSticker();
    }

    private void start() {
        if (messages.size() == 1 && !hasMedia(messages.get(0))) {
            sendText(messages.get(0));
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (!hasMedia(message) || !isCopyable(message)) {
                toast(R.string.GlassgramCopyUnsupported);
                return;
            }
        }
        boolean downloading = false;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            files[i] = localFile(message);
            if (files[i] != null) {
                continue;
            }
            String fileName = message.getFileName();
            if (TextUtils.isEmpty(fileName)) {
                toast(R.string.GlassgramCopyUnsupported);
                return;
            }
            if (!downloading) {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
                downloading = true;
            }
            waitingFileNames.add(fileName);
            if (message.isPhoto()) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, AndroidUtilities.getPhotoSize());
                FileLoader.getInstance(account).loadFile(ImageLocation.getForPhoto(size, message.messageOwner.media.photo), message, null, FileLoader.PRIORITY_HIGH, 0);
            } else {
                FileLoader.getInstance(account).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
            }
        }
        if (downloading) {
            toast(R.string.GlassgramCopyDownloading);
        } else {
            sendMedia();
        }
    }

    private File localFile(MessageObject message) {
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
        if (finished || args.length == 0 || !(args[0] instanceof String) || !waitingFileNames.remove(args[0])) {
            return;
        }
        if (id == NotificationCenter.fileLoadFailed) {
            stopWaiting();
            toast(R.string.GlassgramCopyFailed);
            return;
        }
        if (!waitingFileNames.isEmpty()) {
            return;
        }
        stopWaiting();
        for (int i = 0; i < messages.size(); i++) {
            if (files[i] == null) {
                files[i] = localFile(messages.get(i));
                if (files[i] == null) {
                    toast(R.string.GlassgramCopyFailed);
                    return;
                }
            }
        }
        sendMedia();
    }

    private void stopWaiting() {
        finished = true;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.fileLoadFailed);
    }

    /** "— Channel name (@username)" for copies from protected channels and groups. */
    private String signature(MessageObject message) {
        long dialogId = message.getDialogId();
        if (dialogId >= 0 || !isProtectedCopy(message)) {
            return null;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null || TextUtils.isEmpty(chat.title)) {
            return null;
        }
        String username = ChatObject.getPublicUsername(chat);
        return "— " + chat.title + (TextUtils.isEmpty(username) ? "" : " (@" + username + ")");
    }

    private void sendText(MessageObject message) {
        String text = message.messageOwner.message;
        String signature = signature(message);
        if (signature != null) {
            text = TextUtils.isEmpty(text) ? signature : text + "\n\n" + signature;
        }
        if (TextUtils.isEmpty(text)) {
            toast(R.string.GlassgramCopyUnsupported);
            return;
        }
        ArrayList<TLRPC.MessageEntity> entities = message.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(message.messageOwner.entities);
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(
            text, peer, null, null, null, true, entities, null, null, notify, 0, 0, null, false));
    }

    private void sendMedia() {
        MessageObject first = messages.get(0);
        boolean protectedCopy = isProtectedCopy(first);
        boolean allVisual = true;
        boolean allFiles = true;
        for (int i = 0; i < messages.size(); i++) {
            allVisual &= isVisualMedia(messages.get(i));
            allFiles &= isFileMedia(messages.get(i));
        }
        boolean album = messages.size() > 1 && (allVisual || allFiles);
        ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
            info.path = files[i].getAbsolutePath();
            info.isVideo = message.isVideo() || message.isRoundVideo();
            if (protectedCopy) {
                // The original captions stay behind; one signature goes on the first item
                info.caption = i == 0 ? signature(message) : null;
                info.entities = new ArrayList<>();
            } else {
                info.caption = message.messageOwner.message;
                info.entities = message.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(message.messageOwner.entities);
            }
            media.add(info);
        }
        final boolean asDocument = album ? allFiles : !(first.isPhoto() || first.isVideo() || first.isRoundVideo());
        AndroidUtilities.runOnUIThread(() -> {
            if (album || media.size() == 1) {
                SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), media, peer,
                    null, null, null, null, asDocument, album, null, notify, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
            } else {
                // Mixed items that cannot share an album go one by one
                for (int i = 0; i < media.size(); i++) {
                    MessageObject message = messages.get(i);
                    ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>();
                    one.add(media.get(i));
                    boolean document = !(message.isPhoto() || message.isVideo() || message.isRoundVideo());
                    SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), one, peer,
                        null, null, null, null, document, false, null, notify, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
                }
            }
        });
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
