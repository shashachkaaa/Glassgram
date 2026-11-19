package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import androidx.collection.LongSparseArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Local-only storage for Glassgram Spy.
 *
 * The Spy store deliberately lives outside Telegram's message database. It records only
 * information that was already visible to this client and never sends anything to Telegram.
 */
public final class GlassgramSpyStorage {

    private static final String FILE_NAME = "glassgram_spy.jsonl";
    private static final Object LOCK = new Object();

    private GlassgramSpyStorage() {
    }

    private static File file() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "glassgram_spy");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, FILE_NAME);
    }

    private static final class Events {
        private static final DispatchQueue2 queue = new DispatchQueue2("glassgramSpyEvents");
    }

    private static void saveMessage(int account, TLRPC.Message message) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "deleted");
            o.put("account", account);
            o.put("self_id", UserConfig.getInstance(account).getClientUserId());
            o.put("dialog_id", MessageObject.getDialogId(message));
            o.put("message_id", message.id);
            o.put("date", message.date);
            o.put("saved_at", System.currentTimeMillis());
            o.put("text", message.message);
            o.put("out", message.out);
            Events.queue.postRunnable(() -> append(o));
            GlassgramSpyMedia.capture(account, message);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void saveRead(int account, long dialogId, int maxMessageId, boolean outgoing, long timestamp) {
        if (!GlassgramConfig.spySaveReadDate || !GlassgramConfig.spySaveInBotDialogs && isBotDialog(account, dialogId)) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        Events.queue.postRunnable(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("type", "read");
                o.put("account", account);
                o.put("self_id", selfId);
                o.put("dialog_id", dialogId);
                o.put("message_id", maxMessageId);
                o.put("outgoing", outgoing);
                o.put("saved_at", timestamp);
                append(o);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    public static void saveLastSeen(int account, long userId, long timestamp) {
        if (!GlassgramConfig.spySaveLastSeenDate || !GlassgramConfig.spySaveInBotDialogs && isBotDialog(account, userId)) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        Events.queue.postRunnable(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("type", "last_seen");
                o.put("account", account);
                o.put("self_id", selfId);
                o.put("user_id", userId);
                o.put("saved_at", timestamp);
                append(o);
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    /** One earlier text of an edited message. date is when that text became current. */
    public static final class EditVersion {
        public final String text;
        public final int date;
        /** The photo or file of that version, or null for text only. */
        public final TLRPC.MessageMedia media;

        EditVersion(String text, int date, TLRPC.MessageMedia media) {
            this.text = text;
            this.date = date;
            this.media = media;
        }
    }

    private static boolean isRealMedia(TLRPC.MessageMedia media) {
        return media != null && !(media instanceof TLRPC.TL_messageMediaEmpty) && !(media instanceof TLRPC.TL_messageMediaWebPage);
    }

    /** Photo or document id, so a replaced photo counts as a change. */
    private static long mediaId(TLRPC.MessageMedia media) {
        if (!isRealMedia(media)) {
            return 0;
        }
        if (media.photo != null) {
            return media.photo.id;
        }
        if (media.document != null) {
            return media.document.id;
        }
        return media.getClass().getName().hashCode();
    }

    private static String serializeMedia(TLRPC.MessageMedia media) {
        try {
            SerializedData data = new SerializedData(media.getObjectSize());
            media.serializeToStream(data);
            String encoded = android.util.Base64.encodeToString(data.toByteArray(), android.util.Base64.NO_WRAP);
            data.cleanup();
            return encoded;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.MessageMedia deserializeMedia(String encoded) {
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }
        try {
            SerializedData data = new SerializedData(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP));
            TLRPC.MessageMedia media = TLRPC.MessageMedia.TLdeserialize(data, false);
            data.cleanup();
            return media;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // Messages with saved earlier versions: "<own user id>_<dialog id>_<message id>"
    private static HashSet<String> editIndex;

    private static String editKey(long selfId, long dialogId, int messageId) {
        return selfId + "_" + dialogId + "_" + messageId;
    }

    /** Whether earlier versions of this message were saved. Reads the store once, then keeps an index. */
    public static boolean hasEditHistory(MessageObject message) {
        // The RuStore edition shows nothing the spy kept (installed over the full app)
        if (message == null || GlassgramConfig.STORE_BUILD) {
            return false;
        }
        long selfId = UserConfig.getInstance(message.currentAccount).getClientUserId();
        synchronized (LOCK) {
            if (editIndex == null) {
                HashSet<String> index = new HashSet<>();
                File f = file();
                if (f.exists()) {
                    BufferedReader reader = null;
                    try {
                        reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.indexOf("\"edit\"") < 0) {
                                continue;
                            }
                            try {
                                JSONObject o = new JSONObject(line);
                                if ("edit".equals(o.optString("type")) && o.has("self_id")) {
                                    index.add(editKey(o.optLong("self_id"), o.optLong("dialog_id"), o.optInt("message_id")));
                                }
                            } catch (Exception ignore) {
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    } finally {
                        if (reader != null) {
                            try { reader.close(); } catch (Exception ignore) {}
                        }
                    }
                }
                editIndex = index;
            }
            return editIndex.contains(editKey(selfId, message.getDialogId(), message.getId()));
        }
    }

    /**
     * Reads the stored texts of messages that are about to be overwritten by an edit and
     * records each changed text. Must run on the storage queue before the edit is written.
     */
    public static void saveEditsFromDatabase(int account, LongSparseArray<ArrayList<MessageObject>> edits) {
        if (!GlassgramConfig.spySaveEditsHistory || edits == null) {
            return;
        }
        SQLiteDatabase database = MessagesStorage.getInstance(account).getDatabase();
        if (database == null) {
            return;
        }
        long selfId = UserConfig.getInstance(account).getClientUserId();
        for (int a = 0, size = edits.size(); a < size; a++) {
            long dialogId = edits.keyAt(a);
            if (!GlassgramConfig.spySaveInBotDialogs && isBotDialog(account, dialogId)) {
                continue;
            }
            ArrayList<MessageObject> objects = edits.valueAt(a);
            for (int b = 0; b < objects.size(); b++) {
                MessageObject edited = objects.get(b);
                if (edited == null || edited.messageOwner == null) {
                    continue;
                }
                SQLiteCursor cursor = null;
                try {
                    cursor = database.queryFinalized(String.format(Locale.US, "SELECT data FROM messages_v2 WHERE mid = %d AND uid = %d LIMIT 1", edited.getId(), dialogId));
                    if (!cursor.next()) {
                        continue;
                    }
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) {
                        continue;
                    }
                    TLRPC.Message old = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                    data.reuse();
                    if (old == null) {
                        continue;
                    }
                    String oldText = old.message == null ? "" : old.message;
                    String newText = edited.messageOwner.message == null ? "" : edited.messageOwner.message;
                    // Edit updates also arrive for reactions and views; only a changed text,
                    // photo or file counts.
                    if (oldText.equals(newText) && mediaId(old.media) == mediaId(edited.messageOwner.media)) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("type", "edit");
                    o.put("account", account);
                    o.put("self_id", selfId);
                    o.put("dialog_id", dialogId);
                    o.put("message_id", edited.getId());
                    o.put("saved_at", System.currentTimeMillis());
                    o.put("old_text", oldText);
                    o.put("old_date", old.edit_date != 0 ? old.edit_date : old.date);
                    o.put("new_text", newText);
                    if (isRealMedia(old.media)) {
                        String media = serializeMedia(old.media);
                        if (media != null) {
                            o.put("old_media", media);
                        }
                    }
                    synchronized (LOCK) {
                        if (append(o) && editIndex != null) {
                            editIndex.add(editKey(selfId, dialogId, edited.getId()));
                        }
                    }
                    if (isRealMedia(old.media) && mediaId(old.media) != mediaId(edited.messageOwner.media)) {
                        GlassgramSpyMedia.capture(account, old);
                        GlassgramSpyMedia.capture(account, edited.messageOwner);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }
            }
        }
    }

    /** Earlier texts of a message, oldest first. Reads the whole store, so call it off the UI thread. */
    public static ArrayList<EditVersion> loadEditHistory(int account, long dialogId, int messageId) {
        ArrayList<EditVersion> result = new ArrayList<>();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        synchronized (LOCK) {
            File f = file();
            if (!f.exists()) {
                return result;
            }
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.indexOf("\"edit\"") < 0) {
                        continue;
                    }
                    JSONObject o;
                    try {
                        o = new JSONObject(line);
                    } catch (Exception ignore) {
                        continue;
                    }
                    if (!"edit".equals(o.optString("type")) || o.optLong("dialog_id") != dialogId || o.optInt("message_id") != messageId) {
                        continue;
                    }
                    if (o.has("self_id") ? o.optLong("self_id") != selfId : o.optInt("account", account) != account) {
                        continue;
                    }
                    String text = o.optString("old_text", "");
                    int date = o.optInt("old_date", (int) (o.optLong("saved_at") / 1000));
                    TLRPC.MessageMedia media = deserializeMedia(o.optString("old_media", null));
                    if (!result.isEmpty()) {
                        EditVersion last = result.get(result.size() - 1);
                        if (last.text.equals(text) && mediaId(last.media) == mediaId(media)) {
                            continue;
                        }
                    }
                    result.add(new EditVersion(text, date, media));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignore) {}
                }
            }
        }
        return result;
    }

    // Messages deleted by the other side that are kept in the chat. Telegram ids of
    // non-channel messages are unique per account, channel ones per channel, so a mark
    // is "<own user id>_<channel id or 0>_<message id>".
    private static final String DELETED_FILE_NAME = "glassgram_deleted_ids.txt";
    private static final Object DELETED_LOCK = new Object();
    private static HashSet<String> deletedIds;
    private static final HashSet<String> pendingDeletion = new HashSet<>();

    private static File deletedFile() {
        return new File(file().getParentFile(), DELETED_FILE_NAME);
    }

    private static String deletedKey(int account, long channelId, int messageId) {
        return UserConfig.getInstance(account).getClientUserId() + "_" + channelId + "_" + messageId;
    }

    private static HashSet<String> loadDeletedIds() {
        if (deletedIds != null) {
            return deletedIds;
        }
        HashSet<String> set = new HashSet<>();
        File f = deletedFile();
        if (f.exists()) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        set.add(line);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (Exception ignore) {}
                }
            }
        }
        deletedIds = set;
        return set;
    }

    /**
     * Remembers that these messages were deleted on the server while keeping them in the chat.
     * channelId is 0 for private chats and basic groups.
     */
    public static void markDeleted(int account, long channelId, ArrayList<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        synchronized (DELETED_LOCK) {
            HashSet<String> set = loadDeletedIds();
            StringBuilder lines = new StringBuilder();
            for (int i = 0; i < messageIds.size(); i++) {
                String key = deletedKey(account, channelId, messageIds.get(i));
                if (set.add(key)) {
                    lines.append(key).append('\n');
                }
            }
            if (lines.length() == 0) {
                return;
            }
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(deletedFile(), true);
                out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Exception ignore) {}
                }
            }
        }
    }

    static void setPendingDeletion(int account, long channelId, ArrayList<Integer> ids, boolean pending) {
        synchronized (DELETED_LOCK) {
            for (int id : ids) {
                String key = deletedKey(account, channelId, id);
                if (pending) {
                    pendingDeletion.add(key);
                } else {
                    pendingDeletion.remove(key);
                }
            }
        }
    }

    private static boolean isPendingDeletion(int account, long channelId, int messageId) {
        synchronized (DELETED_LOCK) {
            return pendingDeletion.contains(deletedKey(account, channelId, messageId));
        }
    }

    public static boolean isMarkedDeleted(int account, long channelId, int messageId) {
        if (GlassgramConfig.STORE_BUILD) {
            return false;
        }
        synchronized (DELETED_LOCK) {
            HashSet<String> set = loadDeletedIds();
            return !set.isEmpty() && set.contains(deletedKey(account, channelId, messageId));
        }
    }

    /** Looks up all retained messages, including ones whose chat is closed. */
    public static HashSet<Integer> retainDeletedMessages(int account, long dialogKey, ArrayList<Integer> ids) {
        HashSet<Integer> retained = new HashSet<>();
        if (ids == null || ids.isEmpty()) {
            return retained;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        long selfId = UserConfig.getInstance(account).getClientUserId();
        FutureTask<ArrayList<TLRPC.Message>> query = new FutureTask<>(() -> {
            ArrayList<TLRPC.Message> messages = new ArrayList<>();
            String idList = TextUtils.join(",", ids);
            String selection = dialogKey == 0 ? "is_channel = 0" : "uid = " + dialogKey;
            SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT mid, uid, out, data FROM messages_v2 WHERE "
                    + selection + " AND mid IN (" + idList + ")");
            try {
                while (cursor.next()) {
                    int id = cursor.intValue(0);
                    long dialogId = cursor.longValue(1);
                    boolean explicit = isMarkedDeleted(account, -dialogKey, id) || isPendingDeletion(account, -dialogKey, id);
                    if (!explicit && (cursor.intValue(2) != 0
                            || !GlassgramConfig.spySaveInBotDialogs && isBotDialogOnStorageQueue(account, storage, dialogId))) {
                        continue;
                    }
                    NativeByteBuffer data = cursor.byteBufferValue(3);
                    if (data == null) {
                        continue;
                    }
                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (message != null) {
                            message.dialog_id = dialogId;
                            messages.add(message);
                        }
                    } finally {
                        data.reuse();
                    }
                }
            } finally {
                cursor.dispose();
            }
            return messages;
        });
        if (Thread.currentThread() == storage.getStorageQueue()) {
            query.run();
        } else {
            storage.getStorageQueue().postRunnable(query);
        }
        try {
            ArrayList<TLRPC.Message> messages = query.get(3, TimeUnit.SECONDS);
            if (selfId != UserConfig.getInstance(account).getClientUserId()) {
                return retained;
            }
            for (TLRPC.Message message : messages) {
                retained.add(message.id);
                if (!isMarkedDeleted(account, -dialogKey, message.id)) {
                    saveMessage(account, message);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            query.cancel(false);
        } catch (Exception e) {
            query.cancel(false);
            FileLog.e(e);
        }
        return retained;
    }

    private static boolean isBotDialogOnStorageQueue(int account, MessagesStorage storage, long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        if (user == null) {
            user = storage.getUser(dialogId);
        }
        return user != null && user.bot;
    }

    public static boolean isMarkedDeleted(MessageObject message) {
        if (GlassgramConfig.STORE_BUILD || message == null || message.messageOwner == null || message.getId() <= 0 || message.scheduled) {
            return false;
        }
        long channelId = 0;
        if (message.messageOwner.peer_id != null && message.messageOwner.peer_id.channel_id != 0) {
            channelId = message.messageOwner.peer_id.channel_id;
        }
        synchronized (DELETED_LOCK) {
            HashSet<String> set = loadDeletedIds();
            return !set.isEmpty() && set.contains(deletedKey(message.currentAccount, channelId, message.getId()));
        }
    }

    static boolean isBotDialog(int account, long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    private static boolean append(JSONObject object) {
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(file(), true)) {
                out.write((object.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    public static long size() {
        File f = file();
        return f.length() + deletedFile().length() + GlassgramSpyMedia.size();
    }

    public static void clear(Runnable done) {
        Events.queue.postRunnable(() -> clearOnQueue(done));
    }

    private static void clearOnQueue(Runnable done) {
        synchronized (DELETED_LOCK) {
            File d = deletedFile();
            if (d.exists()) {
                d.delete();
            }
            deletedIds = new HashSet<>();
            pendingDeletion.clear();
        }
        synchronized (LOCK) {
            editIndex = new HashSet<>();
            File f = file();
            if (f.exists() && !f.delete()) {
                try {
                    new FileOutputStream(f, false).close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        GlassgramSpyMedia.clear(done);
    }

    public static boolean exportToUri(Uri uri) {
        try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri, "wt")) {
            return out != null && exportTo(out);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean importFromUri(Uri uri) {
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            return in != null && importFrom(in);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean exportTo(File target) {
        try (OutputStream out = new FileOutputStream(target)) {
            return exportTo(out);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean exportTo(OutputStream out) throws Exception {
        synchronized (DELETED_LOCK) {
            synchronized (LOCK) {
                File src = file();
                if (src.isFile()) {
                    try (InputStream in = new FileInputStream(src)) {
                        byte[] buffer = new byte[8192];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            out.write(buffer, 0, n);
                        }
                    }
                }
                for (String key : loadDeletedIds()) {
                    JSONObject mark = new JSONObject().put("type", "deleted_mark").put("key", key);
                    out.write((mark + "\n").getBytes(StandardCharsets.UTF_8));
                }
                return true;
            }
        }
    }

    public static boolean importFrom(File source) {
        try (InputStream in = new FileInputStream(source)) {
            return importFrom(in);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean importFrom(InputStream in) throws Exception {
        // Parse before replacing the archive. AtomicFile leaves the previous file intact on failure.
        synchronized (DELETED_LOCK) {
            synchronized (LOCK) {
                AtomicFile journal = new AtomicFile(file());
                FileOutputStream out = journal.startWrite();
                HashSet<String> marks = new HashSet<>();
                boolean readRecord = false;
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }
                        JSONObject object = new JSONObject(line);
                        if ("deleted_mark".equals(object.getString("type"))) {
                            String key = object.getString("key");
                            if (!key.matches("[1-9][0-9]*_[0-9]+_[1-9][0-9]*")) {
                                throw new java.io.IOException("Invalid Spy deletion marker");
                            }
                            marks.add(key);
                        } else {
                            out.write((object + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                        readRecord = true;
                    }
                    if (!readRecord) {
                        return false;
                    }
                    journal.finishWrite(out);
                    out = null;
                    editIndex = null;
                } finally {
                    if (out != null) {
                        journal.failWrite(out);
                    }
                }
                if (!marks.isEmpty()) {
                    HashSet<String> existing = loadDeletedIds();
                    marks.removeAll(existing);
                    if (!marks.isEmpty()) {
                        try (FileOutputStream deleted = new FileOutputStream(deletedFile(), true)) {
                            for (String key : marks) {
                                deleted.write((key + "\n").getBytes(StandardCharsets.UTF_8));
                            }
                        }
                        existing.addAll(marks);
                    }
                }
                return true;
            }
        }
    }
}
