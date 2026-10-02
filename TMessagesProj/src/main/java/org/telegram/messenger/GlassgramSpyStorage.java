package org.telegram.messenger;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    public static void saveEdit(MessageObject oldMessage, MessageObject newMessage) {
        if (!GlassgramConfig.spySaveEditsHistory || oldMessage == null) {
            return;
        }
        if (!GlassgramConfig.spySaveInBotDialogs && isBotDialog(oldMessage.getDialogId())) {
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("type", "edit");
            o.put("account", oldMessage.currentAccount);
            o.put("dialog_id", oldMessage.getDialogId());
            o.put("message_id", oldMessage.getId());
            o.put("saved_at", System.currentTimeMillis());
            o.put("old_text", oldMessage.messageOwner != null && oldMessage.messageOwner.message != null ? oldMessage.messageOwner.message : "");
            o.put("new_text", newMessage != null && newMessage.messageOwner != null && newMessage.messageOwner.message != null ? newMessage.messageOwner.message : "");
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

    private static boolean isBotDialog(long dialogId) {
        if (dialogId <= 0) {
            return false;
        }
        TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId);
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
        synchronized (LOCK) {
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
