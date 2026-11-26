package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

import androidx.collection.LongSparseArray;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final class EditLocation {
        final long offset;
        final int length;

        EditLocation(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    // Membership can be read by the UI without waiting for journal operations.
    private static volatile ConcurrentHashMap<String, ArrayList<EditLocation>> editIndex;

    private static String editKey(long selfId, long dialogId, int messageId) {
        return selfId + "_" + dialogId + "_" + messageId;
    }

    private static void indexEdit(ConcurrentHashMap<String, ArrayList<EditLocation>> index,
                                  JSONObject object, long offset, int length) {
        if (!"edit".equals(object.optString("type"))) {
            return;
        }
        // Account slots in older exports use a negative namespace distinct from Telegram user IDs.
        long owner = object.has("self_id") ? object.optLong("self_id") : ~object.optInt("account", -1);
        if (owner == 0) {
            return;
        }
        String key = editKey(owner, object.optLong("dialog_id"), object.optInt("message_id"));
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new EditLocation(offset, length));
    }

    public static void preload() {
        if (!GlassgramConfig.STORE_BUILD) {
            Events.queue.postRunnable(() -> {
                getDeletedIds();
                synchronized (LOCK) {
                    ensureEditIndex();
                }
            });
        }
    }

    /** Must run under LOCK on a background queue. */
    private static void ensureEditIndex() {
        if (editIndex != null) {
            return;
        }
        ConcurrentHashMap<String, ArrayList<EditLocation>> index = new ConcurrentHashMap<>();
        File log = file();
        if (log.isFile()) {
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(log))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                long offset = 0;
                int next;
                do {
                    next = input.read();
                    if (next >= 0 && next != '\n') {
                        line.write(next);
                    } else {
                        int length = line.size();
                        if (length > 0) {
                            try {
                                String json = line.toString("UTF-8");
                                if (json.contains("\"edit\"")) {
                                    indexEdit(index, new JSONObject(json), offset, length);
                                }
                            } catch (org.json.JSONException ignored) {
                                // An incomplete record can remain after a crash.
                            }
                            line.reset();
                        }
                        offset += length + 1L;
                    }
                } while (next >= 0);
            } catch (IOException e) {
                FileLog.e(e);
                return;
            }
        }
        editIndex = index;
    }

    public static boolean hasEditHistory(MessageObject message) {
        ConcurrentHashMap<String, ArrayList<EditLocation>> index = editIndex;
        if (message == null || GlassgramConfig.STORE_BUILD || index == null || index.isEmpty()) {
            return false;
        }
        long selfId = UserConfig.getInstance(message.currentAccount).getClientUserId();
        long dialogId = message.getDialogId();
        int messageId = message.getId();
        return index.containsKey(editKey(selfId, dialogId, messageId))
                || index.containsKey(editKey(~message.currentAccount, dialogId, messageId));
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

    /** Reads only the positions for the requested message, oldest first. */
    public static ArrayList<EditVersion> loadEditHistory(int account, long dialogId, int messageId) {
        ArrayList<EditVersion> result = new ArrayList<>();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        synchronized (LOCK) {
            ensureEditIndex();
            if (editIndex == null) {
                return result;
            }
            ArrayList<EditLocation> positions = new ArrayList<>();
            ArrayList<EditLocation> current = editIndex.get(editKey(selfId, dialogId, messageId));
            ArrayList<EditLocation> legacy = editIndex.get(editKey(~account, dialogId, messageId));
            if (current != null) {
                positions.addAll(current);
            }
            if (legacy != null) {
                positions.addAll(legacy);
                positions.sort(java.util.Comparator.comparingLong(entry -> entry.offset));
            }
            if (positions.isEmpty()) {
                return result;
            }
            try (RandomAccessFile journal = new RandomAccessFile(file(), "r")) {
                for (EditLocation position : positions) {
                    journal.seek(position.offset);
                    byte[] bytes = new byte[position.length];
                    journal.readFully(bytes);
                    JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
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
            }
        }
        return result;
    }

    // Messages deleted by the other side that are kept in the chat. Telegram ids of
    // non-channel messages are unique per account, channel ones per channel, so a mark
    // is "<own user id>_<channel id or 0>_<message id>".
    private static final String DELETED_FILE_NAME = "glassgram_deleted_ids.txt";
    private static final Object DELETED_LOCK = new Object();
    private static volatile Set<String> deletedIds;
    private static final HashSet<String> pendingDeletion = new HashSet<>();
    // A normal deletion initiated on this client must win over a racing server update.
    private static final HashSet<String> localDiscard = new HashSet<>();

    private static File deletedFile() {
        return new File(file().getParentFile(), DELETED_FILE_NAME);
    }

    private static String deletedKey(int account, long channelId, int messageId) {
        return UserConfig.getInstance(account).getClientUserId() + "_" + channelId + "_" + messageId;
    }

    private static Set<String> loadDeletedIds() {
        if (deletedIds != null) {
            return deletedIds;
        }
        Set<String> set = Collections.newSetFromMap(new ConcurrentHashMap<>());
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

    private static Set<String> getDeletedIds() {
        Set<String> ids = deletedIds;
        if (ids != null) {
            return ids;
        }
        synchronized (DELETED_LOCK) {
            return loadDeletedIds();
        }
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
            Set<String> set = loadDeletedIds();
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

    static void setLocalDiscard(int account, long channelId, ArrayList<Integer> ids, boolean discard) {
        synchronized (DELETED_LOCK) {
            for (Integer id : ids) {
                if (id == null || id <= 0) continue;
                String key = deletedKey(account, channelId, id);
                if (discard) {
                    localDiscard.add(key);
                    pendingDeletion.remove(key);
                } else {
                    localDiscard.remove(key);
                }
            }
        }
    }

    private static boolean isLocalDiscard(int account, long channelId, int messageId) {
        synchronized (DELETED_LOCK) {
            return localDiscard.contains(deletedKey(account, channelId, messageId));
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
        return getDeletedIds().contains(deletedKey(account, channelId, messageId));
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
                    if (isLocalDiscard(account, -dialogKey, id)) {
                        continue;
                    }
                    boolean explicit = isMarkedDeleted(account, -dialogKey, id) || isPendingDeletion(account, -dialogKey, id);
                    // The out flag identifies the author, not the person who deleted the message.
                    if (!explicit && !GlassgramConfig.spySaveInBotDialogs
                            && isBotDialogOnStorageQueue(account, storage, dialogId)) {
                        continue;
                    }
                    NativeByteBuffer data = cursor.byteBufferValue(3);
                    if (data == null) {
                        continue;
                    }
                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (message != null && !message.noforwards
                                && !MessageObject.isEphemeral(message) && !MessageObject.isSecretMedia(message)
                                && !DialogObject.isEncryptedDialog(dialogId)
                                && !GlassgramSpyMedia.isProtected(account, dialogId)) {
                            TLRPC.MessageMedia media = MessageObject.getMedia(message);
                            if (media == null || media.ttl_seconds == 0) {
                                message.dialog_id = dialogId;
                                messages.add(message);
                            }
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
                if (isLocalDiscard(account, -dialogKey, message.id)) continue;
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

    static boolean isBotDialogOnStorageQueue(int account, MessagesStorage storage, long dialogId) {
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
        return isMarkedDeleted(message.currentAccount, channelId, message.getId());
    }

    static boolean isBotDialog(int account, long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
        return user != null && user.bot;
    }

    private static void append(JSONObject object) {
        synchronized (LOCK) {
            File journal = file();
            boolean indexedEdit = editIndex != null && "edit".equals(object.optString("type"));
            long offset = indexedEdit ? journal.length() : 0;
            try (FileOutputStream out = new FileOutputStream(journal, true)) {
                byte[] bytes = (object + "\n").getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                if (indexedEdit) {
                    indexEdit(editIndex, object, offset, bytes.length - 1);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public static long size() {
        File f = file();
        return f.length() + deletedFile().length() + GlassgramSpyMedia.size() + GlassgramLocalDialogs.size();
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
            deletedIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
            pendingDeletion.clear();
        }
        synchronized (LOCK) {
            editIndex = new ConcurrentHashMap<>();
            File f = file();
            if (f.exists() && !f.delete()) {
                try {
                    new FileOutputStream(f, false).close();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
        GlassgramLocalDialogs.clear();
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

    public static void importFromUri(Uri uri, Utilities.Callback<Boolean> done) {
        // Keep clearing and importing ordered even if a document provider is slow.
        Events.queue.postRunnable(() -> {
            boolean imported = importFromUri(uri);
            AndroidUtilities.runOnUIThread(() -> done.run(imported));
        });
    }

    private static boolean importFromUri(Uri uri) {
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            return in != null && importFrom(in);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean exportTo(OutputStream out) throws Exception {
        final FileInputStream snapshot;
        final long length;
        final ArrayList<String> marks;
        synchronized (DELETED_LOCK) {
            synchronized (LOCK) {
                File src = file();
                length = src.length();
                snapshot = length > 0 ? new FileInputStream(src) : null;
                marks = new ArrayList<>(loadDeletedIds());
            }
        }
        if (snapshot != null) {
            try (FileInputStream in = snapshot) {
                byte[] buffer = new byte[8192];
                long remaining = length;
                while (remaining > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) {
                        throw new IOException("Spy journal changed during export");
                    }
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
            }
        }
        for (String key : marks) {
            JSONObject mark = new JSONObject().put("type", "deleted_mark").put("key", key);
            out.write((mark + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static boolean importFrom(InputStream in) throws Exception {
        File journal = file();
        File incoming = File.createTempFile("spy-import-", ".tmp", journal.getParentFile());
        HashSet<String> marks = new HashSet<>();
        ConcurrentHashMap<String, ArrayList<EditLocation>> index = new ConcurrentHashMap<>();
        try {
            boolean readRecord = false;
            long offset = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 FileOutputStream out = new FileOutputStream(incoming)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    JSONObject object = new JSONObject(line);
                    if ("deleted_mark".equals(object.getString("type"))) {
                        String key = object.getString("key");
                        if (!key.matches("[1-9][0-9]*_[0-9]+_[1-9][0-9]*")) {
                            throw new IOException("Invalid Spy deletion marker");
                        }
                        marks.add(key);
                    } else {
                        byte[] bytes = (object + "\n").getBytes(StandardCharsets.UTF_8);
                        out.write(bytes);
                        indexEdit(index, object, offset, bytes.length - 1);
                        offset += bytes.length;
                    }
                    readRecord = true;
                }
                if (!readRecord) {
                    return false;
                }
                out.getFD().sync();
            }
            synchronized (DELETED_LOCK) {
                synchronized (LOCK) {
                    // Both paths are in the same directory. Readers of the old journal keep their descriptor.
                    if (!incoming.renameTo(journal)) {
                        throw new IOException("Could not replace Spy journal");
                    }
                    editIndex = index;
                }
                if (!marks.isEmpty()) {
                    Set<String> existing = loadDeletedIds();
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
            }
            return true;
        } finally {
            incoming.delete();
        }
    }
}
