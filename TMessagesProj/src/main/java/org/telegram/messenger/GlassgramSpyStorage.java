package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

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

    public static void saveMessage(MessageObject message, String reason) {
        if (message == null || !GlassgramConfig.spySaveDeletedMessages) {
            return;
        }
        if (!GlassgramConfig.spySaveInBotDialogs && isBotDialog(message.getDialogId())) {
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("type", reason == null ? "deleted" : reason);
            o.put("account", message.currentAccount);
            o.put("dialog_id", message.getDialogId());
            o.put("message_id", message.getId());
            o.put("date", message.messageOwner != null ? message.messageOwner.date : 0);
            o.put("saved_at", System.currentTimeMillis());
            o.put("text", message.messageOwner != null && message.messageOwner.message != null ? message.messageOwner.message : "");
            if (message.messageOwner != null) {
                o.put("out", message.messageOwner.out);
                o.put("attach_path", message.messageOwner.attachPath == null ? "" : message.messageOwner.attachPath);
            }
            append(o);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void saveRead(long dialogId, int maxMessageId, long timestamp) {
        if (!GlassgramConfig.spySaveReadDate) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", "read");
            o.put("dialog_id", dialogId);
            o.put("message_id", maxMessageId);
            o.put("saved_at", timestamp);
            append(o);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void saveLastSeen(long userId, long timestamp) {
        if (!GlassgramConfig.spySaveLastSeenDate) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", "last_seen");
            o.put("user_id", userId);
            o.put("saved_at", timestamp);
            append(o);
        } catch (Exception e) {
            FileLog.e(e);
        }
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
        if (message == null) {
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
                    append(o);
                    synchronized (LOCK) {
                        if (editIndex != null) {
                            editIndex.add(editKey(selfId, dialogId, edited.getId()));
                        }
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

    public static boolean isMarkedDeleted(int account, long channelId, int messageId) {
        synchronized (DELETED_LOCK) {
            HashSet<String> set = loadDeletedIds();
            return !set.isEmpty() && set.contains(deletedKey(account, channelId, messageId));
        }
    }

    /**
     * Which of the deleted message ids are our own outgoing messages, read from the local database.
     * key is the update's key: 0 for private chats and basic groups, -channelId for a channel.
     * Waits for the storage queue, like MessagesStorage.getDialogReadMax.
     */
    public static HashSet<Integer> findOwnMessageIds(int account, long key, ArrayList<Integer> ids) {
        HashSet<Integer> own = new HashSet<>();
        if (ids == null || ids.isEmpty()) {
            return own;
        }
        MessagesStorage storage = MessagesStorage.getInstance(account);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                String idList = TextUtils.join(",", ids);
                String query = key != 0
                    ? String.format(Locale.US, "SELECT mid FROM messages_v2 WHERE uid = %d AND mid IN (%s) AND out = 1", key, idList)
                    : String.format(Locale.US, "SELECT mid FROM messages_v2 WHERE mid IN (%s) AND out = 1 AND is_channel = 0", idList);
                cursor = storage.getDatabase().queryFinalized(query);
                while (cursor.next()) {
                    own.add(cursor.intValue(0));
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
                latch.countDown();
            }
        });
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            FileLog.e(e);
        }
        synchronized (own) {
            return own;
        }
    }

    public static boolean isMarkedDeleted(MessageObject message) {
        if (message == null || message.messageOwner == null || message.getId() <= 0 || message.scheduled) {
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

    private static boolean isBotDialog(long dialogId) {
        return isBotDialog(UserConfig.selectedAccount, dialogId);
    }

    private static boolean isBotDialog(int account, long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    private static void append(JSONObject object) {
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file(), true);
                out.write((object.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Exception ignore) {}
                }
            }
        }
    }

    public static long size() {
        File f = file();
        return f.exists() ? f.length() : 0;
    }

    public static void clear() {
        synchronized (DELETED_LOCK) {
            File d = deletedFile();
            if (d.exists()) {
                d.delete();
            }
            deletedIds = new HashSet<>();
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
    }

    public static boolean exportToUri(Uri uri) {
        synchronized (LOCK) {
            try {
                File src = file();
                if (!src.exists()) return false;
                InputStream in = new FileInputStream(src);
                OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(uri);
                if (out == null) return false;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                in.close();
                out.close();
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    public static boolean importFromUri(Uri uri) {
        synchronized (LOCK) {
            try {
                InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                if (in == null) return false;
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                ArrayList<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject object = new JSONObject(line);
                    object.getString("type");
                    lines.add(object.toString());
                }
                reader.close();
                FileOutputStream out = new FileOutputStream(file(), false);
                for (String value : lines) out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
                out.close();
                editIndex = null;
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    public static boolean exportTo(File target) {
        synchronized (LOCK) {
            try {
                File src = file();
                if (!src.exists()) {
                    return false;
                }
                FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(target);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                }
                in.close();
                out.close();
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    public static boolean importFrom(File source) {
        synchronized (LOCK) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
                ArrayList<String> lines = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject object = new JSONObject(line);
                    object.getString("type");
                    lines.add(object.toString());
                }
                reader.close();
                File dst = file();
                FileOutputStream out = new FileOutputStream(dst, false);
                for (String value : lines) {
                    out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
                }
                out.close();
                return true;
            } catch (Exception e) {
                FileLog.e(e);
                return false;
            }
        }
    }
}
