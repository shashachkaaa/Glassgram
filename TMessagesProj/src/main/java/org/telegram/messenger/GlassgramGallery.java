package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

/** Saves ordinary message photos selected by the user, including photos in Telegram's cache. */
public final class GlassgramGallery {
    private static final long DOWNLOAD_TIMEOUT = 60_000;
    private static final Pattern PHOTO_FILENAME = Pattern.compile("-?[0-9]+_-?[0-9]+[.]jpg");

    private static final class Worker {
        static final DispatchQueue2 queue = new DispatchQueue2("glassgramGallery");
    }

    private GlassgramGallery() {
    }

    public static boolean canSaveMessagePhoto(MessageObject message) {
        if (message == null || message.messageOwner == null || message.messageOwner.noforwards
                || DialogObject.isEncryptedDialog(message.getDialogId()) || message.isLivePhoto()
                || message.hasRevealedExtendedMedia() || message.needDrawBluredPreview()) {
            return false;
        }
        TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
        return media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null
                && media.ttl_seconds == 0 && !media.photo.sizes.isEmpty();
    }

    public static void saveMessagePhotos(List<MessageObject> photos, Utilities.Callback<Integer> done) {
        if (photos.isEmpty()) {
            done.run(0);
            return;
        }
        final int[] remaining = {photos.size()};
        final int[] saved = {0};
        for (MessageObject photo : photos) {
            saveMessagePhoto(photo, uri -> {
                if (uri != null) {
                    saved[0]++;
                }
                if (--remaining[0] == 0) {
                    done.run(saved[0]);
                }
            });
        }
    }

    public static void saveMessagePhoto(MessageObject message, Utilities.Callback<Uri> done) {
        if (!canSaveMessagePhoto(message)) {
            done.run(null);
            return;
        }

        TLRPC.Photo photo = MessageObject.getMedia(message.messageOwner).photo;
        TLRPC.PhotoSize full = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
        TLRPC.PhotoSize chat = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(), false, null, true);
        Worker.queue.postRunnable(() -> {
            FileLoader loader = FileLoader.getInstance(message.currentAccount);
            File source = findPhoto(loader, message.currentAccount, full);
            if (source == null && chat != full) {
                source = findPhoto(loader, message.currentAccount, chat);
            }
            if (source == null && !TextUtils.isEmpty(message.messageOwner.attachPath)) {
                File attached = new File(message.messageOwner.attachPath);
                if (attached.isFile() && attached.length() > 0 && !AndroidUtilities.isInternalUri(Uri.fromFile(attached))) {
                    source = attached;
                }
            }
            if (source != null) {
                save(source, done);
            } else {
                AndroidUtilities.runOnUIThread(() -> download(message, photo, full != null ? full : chat, done));
            }
        });
    }

    private static File findPhoto(FileLoader loader, int account, TLRPC.PhotoSize size) {
        if (size == null) {
            return null;
        }
        String name = FileLoader.getAttachFileName(size);
        if (TextUtils.isEmpty(name)) {
            return null;
        }
        File standard = loader.getPathToAttach(size);
        if (isUsablePhoto(standard, account, name)) {
            return standard;
        }
        File cached = loader.getPathToAttach(size, true);
        return isUsablePhoto(cached, account, name) ? cached : null;
    }

    /** Internal files must be the exact photo named by this message in a known media cache. */
    private static boolean isUsablePhoto(File file, int account, String name) {
        if (file == null || !file.isFile() || file.length() == 0) {
            return false;
        }
        if (!AndroidUtilities.isInternalUri(Uri.fromFile(file))) {
            return true;
        }
        if (!PHOTO_FILENAME.matcher(name).matches()) {
            return false;
        }
        try {
            Context context = ApplicationLoader.applicationContext;
            File cache = context.getCacheDir();
            File files = context.getFilesDir();
            File canonical = file.getCanonicalFile();
            if (cache != null && canonical.equals(new File(cache.getCanonicalFile(), name))) {
                return true;
            }
            if (files == null) {
                return false;
            }
            File root = files.getCanonicalFile();
            if (canonical.equals(new File(root, "cache/" + name))) {
                return true;
            }
            long selfId = UserConfig.getInstance(account).getClientUserId();
            return selfId > 0 && canonical.equals(new File(root, "glassgram_spy/media/" + selfId + "_" + name));
        } catch (IOException e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void download(MessageObject message, TLRPC.Photo photo, TLRPC.PhotoSize size, Utilities.Callback<Uri> done) {
        if (size == null || size.location == null) {
            done.run(null);
            return;
        }
        String name = FileLoader.getAttachFileName(size);
        ImageLocation location = ImageLocation.getForPhoto(size, photo);
        if (TextUtils.isEmpty(name) || location == null || location.isEncrypted()) {
            done.run(null);
            return;
        }
        NotificationCenter center = NotificationCenter.getInstance(message.currentAccount);
        class DownloadWaiter implements NotificationCenter.NotificationCenterDelegate, Runnable {
            private boolean finished;

            @Override
            public void didReceivedNotification(int id, int account, Object... args) {
                if (args.length == 0 || !name.equals(args[0]) || finished) {
                    return;
                }
                stop();
                File file = id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File
                        ? (File) args[1] : null;
                if (file == null) {
                    done.run(null);
                } else {
                    Worker.queue.postRunnable(() -> {
                        if (isUsablePhoto(file, message.currentAccount, name)) {
                            save(file, done);
                        } else {
                            AndroidUtilities.runOnUIThread(() -> done.run(null));
                        }
                    });
                }
            }

            @Override
            public void run() {
                if (!finished) {
                    stop();
                    done.run(null);
                }
            }

            private void stop() {
                finished = true;
                center.removeObserver(this, NotificationCenter.fileLoaded);
                center.removeObserver(this, NotificationCenter.fileLoadFailed);
                AndroidUtilities.cancelRunOnUIThread(this);
            }
        }

        DownloadWaiter waiter = new DownloadWaiter();
        center.addObserver(waiter, NotificationCenter.fileLoaded);
        center.addObserver(waiter, NotificationCenter.fileLoadFailed);
        AndroidUtilities.runOnUIThread(waiter, DOWNLOAD_TIMEOUT);
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        if (!loader.isLoadingFile(name)) {
            loader.loadFile(location, message, "jpg", FileLoader.PRIORITY_HIGH, 1);
        }
    }

    /** Called on a worker after the source file has been checked against the message. */
    private static void save(File source, Utilities.Callback<Uri> done) {
        Uri uri = null;
        if (Build.VERSION.SDK_INT >= 29) {
            uri = MediaController.saveFileInternal(0, source, null);
        } else {
            File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), AndroidUtilities.getSaveFolderName());
            File target = new File(directory, AndroidUtilities.generateFileName(0, FileLoader.getFileExtension(source)));
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create gallery folder");
                }
                if (AndroidUtilities.copyFile(source, target) && target.length() > 0 && target.length() == source.length()) {
                    AndroidUtilities.addMediaToGallery(target);
                    uri = Uri.fromFile(target);
                }
            } catch (IOException | SecurityException e) {
                FileLog.e(e);
            } finally {
                if (uri == null) {
                    target.delete();
                }
            }
        }
        Uri result = uri;
        AndroidUtilities.runOnUIThread(() -> done.run(result));
    }
}
