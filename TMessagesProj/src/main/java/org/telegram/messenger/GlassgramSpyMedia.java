package org.telegram.messenger;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.PriorityQueue;

/** Keeps Spy attachments outside Telegram's evictable media cache. */
public final class GlassgramSpyMedia {

    private static final class Archive {
        static final DispatchQueue2 queue = new DispatchQueue2("glassgramSpyMedia");
        static final HashMap<File, Long> pending = new HashMap<>();
        static final PriorityQueue<ArchivedFile> oldest = new PriorityQueue<>(Comparator.comparingLong(entry -> entry.modified));
    }

    /** A normal media parent for reference refresh, without gallery or download-list side effects. */
    static final class ArchiveMessage extends MessageObject {
        ArchiveMessage(int account, TLRPC.Message message) {
            super(account, message, false, false);
        }
    }

    private static volatile boolean started;
    private static volatile long size;
    private static boolean indexed;

    private static final class ArchivedFile {
        final File file;
        final long bytes;
        final long modified;

        ArchivedFile(File file, long bytes) {
            this.file = file;
            this.bytes = bytes;
            modified = file.lastModified();
        }
    }

    private GlassgramSpyMedia() {
    }

    private static File directory() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "glassgram_spy/media");
    }

    private static File path(long selfId, String name) {
        if (selfId <= 0 || name == null || name.isEmpty() || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.contains(String.valueOf(Integer.MIN_VALUE))) {
            return null;
        }
        return new File(directory(), selfId + "_" + name);
    }

    public static File find(int account, String name) {
        if (GlassgramConfig.STORE_BUILD || ApplicationLoader.applicationContext == null) {
            return null;
        }
        File file = path(UserConfig.getInstance(account).getClientUserId(), name);
        return file != null && file.isFile() ? file : null;
    }

    // A newly received photo can lose its file reference immediately after deletion or replacement.
    // Fetch the chat and viewer sizes while the message can still refresh that reference.
    static void prefetchPhotos(int account, ArrayList<TLRPC.Message> messages) {
        if (!GlassgramConfig.spySaveAttachments
                || !(GlassgramConfig.spySaveDeletedMessages || GlassgramConfig.spySaveEditsHistory)) {
            return;
        }
        for (TLRPC.Message message : messages) {
            TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (!message.out && media instanceof TLRPC.TL_messageMediaPhoto) {
                capture(account, message);
            }
        }
    }

    static void capture(int account, TLRPC.Message message) {
        if (GlassgramConfig.STORE_BUILD || !GlassgramConfig.spySaveAttachments || message == null
                || message.id <= 0 || message.noforwards || MessageObject.isEphemeral(message)
                || MessageObject.isSecretMedia(message)) {
            return;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message);
        if (media == null || media.ttl_seconds != 0) {
            return;
        }
        long dialogId = MessageObject.getDialogId(message);
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)
                || isProtected(account, dialogId)
                || !GlassgramConfig.spySaveInBotDialogs && GlassgramSpyStorage.isBotDialog(account, dialogId)) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        started = true;
        Archive.queue.postRunnable(() -> {
            if (selfId != UserConfig.getInstance(account).getClientUserId() || !GlassgramConfig.spySaveAttachments) {
                return;
            }
            MessageObject parent = new ArchiveMessage(account, message);
            if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
                ArrayList<TLRPC.PhotoSize> sizes = new ArrayList<>(media.photo.sizes.size());
                for (TLRPC.PhotoSize photoSize : media.photo.sizes) {
                    if (photoSize != null && photoSize.size > 0 && photoSize.location != null
                            && !(photoSize instanceof TLRPC.TL_photoCachedSize)) {
                        sizes.add(photoSize);
                    }
                }
                TLRPC.PhotoSize chat = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize(), false, null, true);
                TLRPC.PhotoSize viewer = FileLoader.getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
                preservePhoto(parent, selfId, media.photo, chat);
                if (viewer != chat) {
                    preservePhoto(parent, selfId, media.photo, viewer);
                }
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document instanceof TLRPC.TL_document
                    && media.document.key == null && !parent.isAnyKindOfSticker()) {
                preserve(parent, selfId, media.document, null, media.document.size);
            }
        });
    }

    private static void preservePhoto(MessageObject parent, long selfId, TLRPC.Photo photo, TLRPC.PhotoSize size) {
        if (size != null) {
            ImageLocation location = ImageLocation.getForPhoto(size, photo);
            if (location != null && !location.isEncrypted()) {
                preserve(parent, selfId, size, location, size.size);
            }
        }
    }

    private static boolean isProtected(int account, long dialogId) {
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.UserFull user = controller.getUserFull(dialogId);
            return user != null && (user.noforwards_peer_enabled || user.noforwards_my_enabled);
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat != null && chat.migrated_to != null) {
            TLRPC.Chat migrated = controller.getChat(chat.migrated_to.channel_id);
            if (migrated != null && migrated.noforwards) {
                return true;
            }
        }
        return chat != null && chat.noforwards;
    }

    private static void preserve(MessageObject parent, long selfId, TLObject attachment, ImageLocation photo, long bytes) {
        String name = FileLoader.getAttachFileName(attachment);
        File target = path(selfId, name);
        if (target == null || bytes <= 0 || bytes > limit() || target.isFile()
                || Archive.pending.containsKey(target)) {
            return;
        }
        FileLoader loader = FileLoader.getInstance(parent.currentAccount);
        File source = loader.getPathToAttach(attachment, null, false);
        if (source.length() != bytes) {
            source = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), name);
        }
        if (source.isFile() && source.length() == bytes) {
            copy(source, target, bytes);
            return;
        }
        Archive.pending.put(target, bytes);
        // An existing download can deliver the same bytes without lowering its priority.
        if (!loader.isLoadingFile(name)) {
            if (photo != null) {
                loader.loadFile(photo, parent, "jpg", FileLoader.PRIORITY_LOW, 1);
            } else {
                loader.loadFile((TLRPC.Document) attachment, parent, FileLoader.PRIORITY_LOW, 1);
            }
        }
    }

    static void onDownloadFinished(long selfId, String name, File source) {
        if (GlassgramConfig.STORE_BUILD || !started) {
            return;
        }
        File target = path(selfId, name);
        if (target != null) {
            Archive.queue.postRunnable(() -> {
                Long bytes = Archive.pending.remove(target);
                if (bytes != null && source != null && source.isFile() && source.length() == bytes) {
                    copy(source, target, bytes);
                }
            });
        }
    }

    private static long limit() {
        final long mb = 1024L * 1024;
        switch (GlassgramConfig.spyMaxFolderSize) {
            case GlassgramConfig.SPY_LIMIT_300_MB: return 300 * mb;
            case GlassgramConfig.SPY_LIMIT_2_GB: return 2 * 1024 * mb;
            case GlassgramConfig.SPY_LIMIT_5_GB: return 5 * 1024 * mb;
            case GlassgramConfig.SPY_LIMIT_16_GB: return 16 * 1024 * mb;
            case GlassgramConfig.SPY_LIMIT_UNLIMITED: return Long.MAX_VALUE;
            default: return 1024 * mb;
        }
    }

    private static File[] files() {
        File[] files = directory().listFiles(File::isFile);
        return files == null ? new File[0] : files;
    }

    private static void index() {
        if (indexed) {
            return;
        }
        for (File file : files()) {
            if (file.getName().endsWith(".part") && file.delete()) {
                continue;
            }
            addArchivedFile(file, file.length());
        }
        indexed = true;
    }

    private static void addArchivedFile(File file, long bytes) {
        Archive.oldest.add(new ArchivedFile(file, bytes));
        size += bytes;
    }

    private static void trim(long budget) {
        index();
        ArrayList<ArchivedFile> failed = null;
        while (size > budget && !Archive.oldest.isEmpty()) {
            ArchivedFile entry = Archive.oldest.remove();
            if (entry.file.delete() || !entry.file.exists()) {
                size -= entry.bytes;
            } else {
                if (failed == null) {
                    failed = new ArrayList<>();
                }
                failed.add(entry);
            }
        }
        if (failed != null) {
            Archive.oldest.addAll(failed);
        }
    }

    private static void copy(File source, File target, long bytes) {
        long budget = limit();
        if (target.isFile() || !GlassgramConfig.spySaveAttachments || bytes > budget) {
            return;
        }
        trim(budget - bytes);
        if (size > budget - bytes) {
            return;
        }
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        File temporary = new File(dir, target.getName() + ".part");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            if (temporary.length() != bytes || !temporary.renameTo(target)) {
                throw new IOException("Could not keep Spy attachment");
            }
            addArchivedFile(target, bytes);
        } catch (IOException e) {
            FileLog.e(e);
        } finally {
            temporary.delete();
        }
    }

    public static void refreshSize(Runnable done) {
        Archive.queue.postRunnable(() -> {
            trim(limit());
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    static long size() {
        return size;
    }

    static void clear(Runnable done) {
        Archive.queue.postRunnable(() -> {
            Archive.pending.clear();
            trim(0);
            AndroidUtilities.runOnUIThread(done);
        });
    }
}
