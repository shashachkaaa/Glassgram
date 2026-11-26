package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.util.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only local snapshots, not synthetic Telegram dialogs or memberships. */
public final class GlassgramLocalDialogs {
    private static final String DB = "glassgram_local_dialogs.db";
    private static final Object LOCK = new Object();
    private static final ConcurrentHashMap<Long, ArrayList<Entry>> entries = new ConcurrentHashMap<>();
    private static final java.util.Set<Long> loading = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static Helper helper;
    private static volatile int generation;

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context context) { super(context, DB, null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE dialogs (self INTEGER NOT NULL, did INTEGER NOT NULL, title TEXT NOT NULL, date INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(self,did))");
            db.execSQL("CREATE TABLE messages (self INTEGER NOT NULL, did INTEGER NOT NULL, mid INTEGER NOT NULL, date INTEGER NOT NULL, sender TEXT, body TEXT, attachment TEXT, filename TEXT, mime TEXT, PRIMARY KEY(self,did,mid))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    private static SQLiteDatabase db() {
        if (helper == null) helper = new Helper(ApplicationLoader.applicationContext);
        return helper.getWritableDatabase();
    }

    public static final class Entry {
        public final long self, did;
        public final String title;
        public final int date, count;
        Entry(long self, long did, String title, int date, int count) {
            this.self = self; this.did = did; this.title = title; this.date = date; this.count = count;
        }
    }

    public static final class Row {
        public final int mid, date;
        public final String sender, body, attachment, filename, mime;
        Row(Cursor c) {
            mid = c.getInt(0); date = c.getInt(1); sender = c.getString(2); body = c.getString(3);
            attachment = c.getString(4); filename = c.getString(5); mime = c.getString(6);
        }
    }

    private static boolean enabled() {
        return !GlassgramConfig.STORE_BUILD && GlassgramConfig.spySaveDeletedMessages;
    }

    public static ArrayList<Entry> getEntries(int account) {
        if (!enabled()) return new ArrayList<>();
        final long self = UserConfig.getInstance(account).getClientUserId();
        if (self == 0) return new ArrayList<>();
        ArrayList<Entry> result = entries.get(self);
        if (result != null) return new ArrayList<>(result);
        if (loading.add(self)) {
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    synchronized (LOCK) { reload(self); }
                } catch (Exception e) { FileLog.e(e); }
                finally { loading.remove(self); }
                changed(account, self);
            });
        }
        return new ArrayList<>();
    }

    private static void reload(long self) {
        ArrayList<Entry> list = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT did,title,date,count FROM dialogs WHERE self=? ORDER BY date DESC,did", new String[]{Long.toString(self)})) {
            while (c.moveToNext()) list.add(new Entry(self,c.getLong(0),c.getString(1),c.getInt(2),c.getInt(3)));
        }
        entries.put(self,list);
    }

    private static void changed(int account, long self) {
        AndroidUtilities.runOnUIThread(() -> {
            if (self == UserConfig.getInstance(account).getClientUserId()) {
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload, true);
            }
        });
    }

    /** Capture only cached messages before Telegram removes a dialog or truncates its history. */
    public static void beforeRemoval(int account, long did, int maxId) {
        if (!enabled() || did == 0 || DialogObject.isEncryptedDialog(did)) return;
        long self = UserConfig.getInstance(account).getClientUserId();
        int epoch = generation;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> snapshot(account, self, did, maxId, epoch));
    }

    /** For private/basic group/channel deletion batches, snapshot when all known messages are gone. */
    public static void beforeBatch(int account, long key, ArrayList<Integer> ids) {
        if (!enabled() || ids == null || ids.isEmpty()) return;
        long self = UserConfig.getInstance(account).getClientUserId();
        int epoch = generation;
        HashSet<Integer> removed = new HashSet<>(ids);
        removed.remove(null);
        if (removed.isEmpty()) return;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            if (!enabled() || self != UserConfig.getInstance(account).getClientUserId() || epoch != generation) return;
            HashSet<Long> candidates = new HashSet<>();
            SQLiteCursor cursor = null;
            try {
                String selection = key == 0 ? "is_channel = 0" : "uid = " + key;
                cursor = storage.getDatabase().queryFinalized("SELECT DISTINCT uid FROM messages_v2 WHERE " + selection
                        + " AND mid IN (" + android.text.TextUtils.join(",", removed) + ")");
                while (cursor.next()) candidates.add(cursor.longValue(0));
                cursor.dispose(); cursor = null;
                for (long did : candidates) {
                    if (did == self || did == 0 || DialogObject.isEncryptedDialog(did)) continue;
                    boolean all = true, any = false;
                    cursor = storage.getDatabase().queryFinalized("SELECT mid,is_channel FROM messages_v2 WHERE uid = " + did + " AND mid > 0");
                    while (cursor.next()) {
                        any = true;
                        int id = cursor.intValue(0);
                        long channel = cursor.intValue(1) != 0 ? -did : 0;
                        if (!removed.contains(id) && !GlassgramSpyStorage.isMarkedDeleted(account, channel, id)) {
                            all = false; break;
                        }
                    }
                    cursor.dispose(); cursor = null;
                    if (all && any) snapshot(account,self,did,0,epoch);
                }
            } catch (Exception e) { FileLog.e(e); }
            finally { if (cursor != null) cursor.dispose(); }
        });
    }

    private static String title(int account, long did) {
        MessagesController controller = MessagesController.getInstance(account);
        if (did > 0) {
            TLRPC.User user = controller.getUser(did);
            if (user != null) return UserObject.getUserName(user);
        } else {
            TLRPC.Chat chat = controller.getChat(-did);
            if (chat != null && chat.title != null) return chat.title;
        }
        return Long.toString(did);
    }

    private static void snapshot(int account, long self, long did, int maxId, int epoch) {
        if (!enabled() || self <= 0 || self == did || epoch != generation
                || self != UserConfig.getInstance(account).getClientUserId()
                || GlassgramSpyMedia.isProtected(account,did)) return;
        MessagesStorage storage = MessagesStorage.getInstance(account);
        if (!GlassgramConfig.spySaveInBotDialogs && GlassgramSpyStorage.isBotDialogOnStorageQueue(account,storage,did)) return;
        boolean wrote = false;
        synchronized (LOCK) {
            if (epoch != generation || !enabled()) return;
            SQLiteCursor cursor = null;
            SQLiteDatabase local = null;
            SQLiteStatement insert = null;
            try {
                local = db();
                local.beginTransaction();
                insert = local.compileStatement("INSERT OR REPLACE INTO messages "
                        + "(self,did,mid,date,sender,body,attachment,filename,mime) VALUES (?,?,?,?,?,?,?,?,?)");
                insert.bindLong(1, self);
                insert.bindLong(2, did);
                LongSparseArray<String> senderNames = new LongSparseArray<>();
                String fromYou = LocaleController.getString(R.string.FromYou);
                cursor = storage.getDatabase().queryFinalized("SELECT data FROM messages_v2 WHERE uid = " + did
                        + " AND mid > 0" + (maxId > 0 ? " AND mid <= " + maxId : ""));
                while (cursor.next()) {
                    if (self != UserConfig.getInstance(account).getClientUserId() || epoch != generation) return;
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) continue;
                    TLRPC.Message m;
                    try { m = TLRPC.Message.TLdeserialize(data,data.readInt32(false),false); }
                    finally { data.reuse(); }
                    if (m == null || m.noforwards || MessageObject.isEphemeral(m) || MessageObject.isSecretMedia(m)
                            || m instanceof TLRPC.TL_messageService) continue;
                    m.dialog_id = did;
                    TLRPC.MessageMedia media = MessageObject.getMedia(m);
                    if (media != null && media.ttl_seconds != 0) continue;
                    String sender = fromYou;
                    if (!m.out) {
                        long peer = m.from_id != null ? MessageObject.getPeerId(m.from_id) : did;
                        sender = senderNames.get(peer);
                        if (sender == null) {
                            sender = title(account, peer);
                            senderNames.put(peer, sender);
                        }
                    }
                    String attachment = null;
                    String filename = null;
                    String mime = null;
                    if (GlassgramConfig.spySaveAttachments && media != null) {
                        if (media.document instanceof TLRPC.TL_document && media.document.key == null) {
                            attachment = FileLoader.getAttachFileName(media.document);
                            filename = FileLoader.getDocumentFileName(media.document);
                            mime = media.document.mime_type;
                        } else if (media.photo != null) {
                            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes,AndroidUtilities.getPhotoSize(true),false,null,true);
                            if (size != null) {
                                attachment = FileLoader.getAttachFileName(size);
                                filename = "photo.jpg";
                                mime = "image/jpeg";
                            }
                        }
                        // Only copy existing bytes when access to a conversation has been removed.
                        GlassgramSpyMedia.captureLocal(account,m);
                    }
                    insert.bindLong(3, m.id);
                    insert.bindLong(4, m.date);
                    bindText(insert, 5, sender);
                    insert.bindString(6, m.message == null ? "" : m.message);
                    bindText(insert, 7, attachment);
                    bindText(insert, 8, filename);
                    bindText(insert, 9, mime);
                    insert.executeInsert();
                    wrote = true;
                }
                cursor.dispose(); cursor = null;
                if (wrote) {
                    try (Cursor count = local.rawQuery("SELECT COUNT(*),MAX(date) FROM messages WHERE self=? AND did=?",new String[]{Long.toString(self),Long.toString(did)})) {
                        if (count.moveToFirst()) {
                            ContentValues entry = new ContentValues();
                            entry.put("self",self); entry.put("did",did); entry.put("title",title(account,did));
                            entry.put("date",count.getInt(1)); entry.put("count",count.getInt(0));
                            local.insertWithOnConflict("dialogs",null,entry,SQLiteDatabase.CONFLICT_REPLACE);
                        }
                    }
                }
                local.setTransactionSuccessful();
            } catch (Exception e) { wrote = false; FileLog.e(e); }
            finally {
                if (cursor != null) cursor.dispose();
                if (insert != null) insert.close();
                if (local != null && local.inTransaction()) local.endTransaction();
            }
            if (wrote) {
                try { reload(self); } catch (Exception e) { FileLog.e(e); entries.remove(self); }
            }
        }
        if (wrote) changed(account,self);
    }

    private static void bindText(SQLiteStatement statement, int position, String value) {
        if (value == null) {
            statement.bindNull(position);
        } else {
            statement.bindString(position, value);
        }
    }

    public static ArrayList<Row> read(int account, Entry entry, int before) {
        ArrayList<Row> result = new ArrayList<>();
        if (!enabled() || entry.self != UserConfig.getInstance(account).getClientUserId()) return result;
        synchronized (LOCK) {
            try (Cursor c = db().rawQuery("SELECT mid,date,sender,body,attachment,filename,mime FROM messages WHERE self=? AND did=? AND mid<? ORDER BY mid DESC LIMIT 100",
                    new String[]{Long.toString(entry.self),Long.toString(entry.did),Integer.toString(before)})) {
                while (c.moveToNext()) result.add(new Row(c));
            } catch (Exception e) { FileLog.e(e); }
        }
        return result;
    }

    public static void remove(int account, Entry entry, Runnable done) {
        Utilities.globalQueue.postRunnable(() -> {
            if (entry.self != UserConfig.getInstance(account).getClientUserId()) return;
            synchronized (LOCK) {
                try {
                    String[] args = {Long.toString(entry.self),Long.toString(entry.did)};
                    db().delete("messages","self=? AND did=?",args);
                    db().delete("dialogs","self=? AND did=?",args);
                    reload(entry.self);
                } catch (Exception e) { FileLog.e(e); }
            }
            changed(account,entry.self);
            AndroidUtilities.runOnUIThread(done);
        });
    }

    static long size() {
        if (ApplicationLoader.applicationContext == null) return 0;
        File f = ApplicationLoader.applicationContext.getDatabasePath(DB);
        return f.length();
    }

    static void clear() {
        synchronized (LOCK) {
            generation++;
            try {
                if (helper != null) { helper.close(); helper = null; }
                ApplicationLoader.applicationContext.deleteDatabase(DB);
            } catch (Exception e) { FileLog.e(e); }
            entries.clear();
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) changed(a,UserConfig.getInstance(a).getClientUserId());
    }

    private GlassgramLocalDialogs() {}
}
