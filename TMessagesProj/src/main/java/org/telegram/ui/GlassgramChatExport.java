package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * glassGram: a chat's history exported as Telegram Desktop does it, into one .zip: result.json
 * (Desktop's machine-readable format) and/or messages.html (pages of 1000 messages), with the
 * chosen media in photos/, video_files/, voice_messages/, round_video_messages/, stickers/,
 * animations/ and files/. Messages come from the server (messages.getHistory, or getReplies for a
 * topic), media through FileLoader. Chats that protect their content, and secret chats, are not
 * exported, as on Desktop.
 */
public final class GlassgramChatExport {

    public static final int REQUEST_SAVE = 9301;

    private static final int FORMAT_HTML = 0, FORMAT_JSON = 1, FORMAT_BOTH = 2;
    private static final long[] SIZE_LIMITS = { 8L << 20, 50L << 20, 500L << 20, 4000L << 20 };
    private static final String NOT_INCLUDED = "(File not included. Change data exporting settings to download.)";
    private static final String TOO_BIG = "(File exceeds maximum size. Change data exporting settings to download.)";
    private static final int MESSAGES_PER_HTML = 1000;

    private static final class Options {
        int format = FORMAT_HTML;
        boolean photos = true, videos = false, voice = true, stickers = false, files = false;
        int sizeLimit = 0;
    }

    private static Options pending;
    private static Exporter running;

    private GlassgramChatExport() {
    }

    /** Whether the chat can be exported: not secret, not protecting its content, not a saved-messages part. */
    public static boolean canExport(ChatActivity fragment) {
        if (fragment.getCurrentEncryptedChat() != null || fragment.getChatMode() != 0) {
            return false;
        }
        final MessagesController controller = fragment.getMessagesController();
        final TLRPC.Chat chat = fragment.getCurrentChat();
        if (chat != null) {
            return !controller.isChatNoForwards(chat);
        }
        final TLRPC.User user = fragment.getCurrentUser();
        return user != null && !controller.isUserNoForwards(user.id);
    }

    /** The export's settings: format, media, size limit; then where to save the archive. */
    public static void showOptions(ChatActivity fragment) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        if (running != null) {
            Toast.makeText(activity, getString(R.string.GlassgramExportRunning), Toast.LENGTH_SHORT).show();
            return;
        }
        final Theme.ResourcesProvider resources = fragment.getResourceProvider();
        final Options options = new Options();
        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        final int[] formats = { R.string.GlassgramExportHtml, R.string.GlassgramExportJson, R.string.GlassgramExportBoth };
        final RadioColorCell[] formatCells = new RadioColorCell[formats.length];
        for (int i = 0; i < formats.length; i++) {
            final int index = i;
            final RadioColorCell cell = new RadioColorCell(activity, resources);
            cell.setPadding(dp(4), 0, dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, resources), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, resources));
            cell.setTextAndValue(getString(formats[i]), i == options.format);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resources), 2));
            cell.setOnClickListener(v -> {
                options.format = index;
                for (int j = 0; j < formatCells.length; j++) {
                    formatCells[j].setChecked(j == index, true);
                }
            });
            formatCells[i] = cell;
            layout.addView(cell);
        }

        final int[] media = { R.string.GlassgramExportPhotos, R.string.GlassgramExportVideos, R.string.GlassgramExportVoice,
                R.string.GlassgramExportStickers, R.string.GlassgramExportFiles };
        final boolean[] checked = { options.photos, options.videos, options.voice, options.stickers, options.files };
        for (int i = 0; i < media.length; i++) {
            final int index = i;
            final CheckBoxCell cell = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, resources);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resources), 2));
            cell.setText(getString(media[i]), "", checked[i], false);
            cell.setOnClickListener(v -> {
                checked[index] = !checked[index];
                cell.setChecked(checked[index], true);
            });
            layout.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }

        final String[] limits = { "8 MB", "50 MB", "500 MB", "4 GB" };
        final CheckBoxCell sizeCell = new CheckBoxCell(activity, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT, resources);
        sizeCell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resources), 2));
        sizeCell.setText(getString(R.string.GlassgramExportSizeLimit), limits[options.sizeLimit], false, false);
        sizeCell.getCheckBoxView().setVisibility(android.view.View.GONE);
        sizeCell.setOnClickListener(v -> {
            options.sizeLimit = (options.sizeLimit + 1) % limits.length;
            sizeCell.setText(getString(R.string.GlassgramExportSizeLimit), limits[options.sizeLimit], false, false);
        });
        layout.addView(sizeCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        new AlertDialog.Builder(activity, resources)
                .setTitle(getString(R.string.GlassgramExportTitle))
                .setView(layout)
                .setPositiveButton(getString(R.string.GlassgramExportStart), (d, w) -> {
                    options.photos = checked[0];
                    options.videos = checked[1];
                    options.voice = checked[2];
                    options.stickers = checked[3];
                    options.files = checked[4];
                    pending = options;
                    final String name = "ChatExport_" + safeName(chatName(fragment)) + "_"
                            + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip";
                    try {
                        fragment.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                                .putExtra(Intent.EXTRA_TITLE, name), REQUEST_SAVE);
                    } catch (Exception e) {
                        FileLog.e(e);
                        pending = null;
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** The archive's place, chosen: the export starts. */
    public static boolean onActivityResult(ChatActivity fragment, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SAVE) {
            return false;
        }
        final Options options = pending;
        pending = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || options == null || running != null) {
            return true;
        }
        final Exporter exporter = new Exporter(fragment, options, data.getData());
        running = exporter;
        exporter.start();
        return true;
    }

    private static String chatName(ChatActivity fragment) {
        final TLRPC.Chat chat = fragment.getCurrentChat();
        if (chat != null) {
            return chat.title;
        }
        final TLRPC.User user = fragment.getCurrentUser();
        if (user != null && UserObject.isUserSelf(user)) {
            return getString(R.string.SavedMessages);
        }
        return user != null ? UserObject.getUserName(user) : "chat";
    }

    private static String safeName(String name) {
        final String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return s.isEmpty() ? "chat" : s.length() > 60 ? s.substring(0, 60) : s;
    }

    // --- The export ------------------------------------------------------------------------------

    private static final class Exporter extends Thread {
        private final int account;
        private final Options options;
        private final Uri target;
        private final long dialogId;
        private final long topicId;
        private final TLRPC.Chat chat;
        private final TLRPC.User user;
        private final String name;
        private final ChatActivity fragment;
        private volatile boolean cancelled;
        private AlertDialog progressDialog;

        private final HashMap<Long, TLRPC.User> users = new HashMap<>();
        private final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        private final HashSet<String> mediaNames = new HashSet<>();
        private ZipOutputStream zip;
        private File workDir;
        private int total, done, files, skipped;

        Exporter(ChatActivity fragment, Options options, Uri target) {
            super("glassgram-export");
            this.fragment = fragment;
            this.account = fragment.getCurrentAccount();
            this.options = options;
            this.target = target;
            this.dialogId = fragment.getDialogId();
            this.topicId = fragment.isTopic ? fragment.getTopicId() : 0;
            this.chat = fragment.getCurrentChat();
            this.user = fragment.getCurrentUser();
            this.name = chatName(fragment);
            showProgress();
        }

        private void showProgress() {
            final Activity activity = fragment.getParentActivity();
            if (activity == null) {
                return;
            }
            progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_LOADING, fragment.getResourceProvider());
            progressDialog.setTitle(getString(R.string.GlassgramExportTitle));
            progressDialog.setMessage(getString(R.string.GlassgramExportPreparing));
            progressDialog.setCanceledOnTouchOutside(false);
            progressDialog.setCancelable(false);
            progressDialog.setNegativeButton(getString(R.string.Cancel), (d, w) -> cancelled = true);
            progressDialog.show();
        }

        private void progress() {
            final int percent = total > 0 ? Math.min(100, done * 100 / total) : 0;
            final String text = formatString(R.string.GlassgramExportProgress, done, Math.max(total, done), files);
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.setProgress(percent);
                    progressDialog.setMessage(text);
                }
            });
        }

        @Override
        public void run() {
            boolean ok = false;
            String error = null;
            workDir = new File(ApplicationLoader.applicationContext.getCacheDir(), "glassgram_export_" + System.currentTimeMillis());
            try (OutputStream out = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(target)) {
                if (out == null) {
                    throw new IllegalStateException("no output");
                }
                workDir.mkdirs();
                zip = new ZipOutputStream(out);
                final ArrayList<File> pages = fetchAll();
                if (!cancelled) {
                    writeResults(pages);
                    zip.finish();
                    ok = true;
                }
                zip.close();
            } catch (Throwable e) {
                FileLog.e(e);
                error = e.getMessage();
            } finally {
                deleteDir(workDir);
            }
            if (!ok) {
                // a half-written archive is no export
                try {
                    android.provider.DocumentsContract.deleteDocument(ApplicationLoader.applicationContext.getContentResolver(), target);
                } catch (Throwable ignore) {
                }
            }
            final boolean success = ok;
            final String reason = error;
            AndroidUtilities.runOnUIThread(() -> finish(success, reason));
        }

        private void finish(boolean success, String error) {
            running = null;
            if (progressDialog != null) {
                try {
                    progressDialog.dismiss();
                } catch (Exception ignore) {
                }
            }
            final String text = cancelled ? getString(R.string.GlassgramExportCancelled)
                    : success ? formatString(R.string.GlassgramExportDone, done, files, skipped)
                    : formatString(R.string.GlassgramExportFailed, error == null ? "?" : error);
            if (fragment.getParentActivity() != null && !fragment.isFinished) {
                if (success && !cancelled) {
                    BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, text).show();
                } else {
                    BulletinFactory.of(fragment).createErrorBulletin(text).show();
                }
            } else {
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
            }
        }

        // --- Messages, newest first, a page of JSON lines per request ---------------------------

        private ArrayList<File> fetchAll() throws Exception {
            final ArrayList<File> pages = new ArrayList<>();
            final TLRPC.InputPeer peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            int offsetId = 0;
            boolean first = true;
            while (!cancelled) {
                final TLObject request = historyRequest(peer, offsetId, first ? 1 : 100);
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) send(request);
                if (first) {
                    total = res instanceof TLRPC.TL_messages_messages ? res.messages.size() : res.count;
                    first = false;
                    if (res.messages.isEmpty()) {
                        break;
                    }
                    // the first answer only counts; the pages start again from the newest
                    continue;
                }
                if (res.messages.isEmpty()) {
                    break;
                }
                for (TLRPC.User u : res.users) {
                    users.put(u.id, u);
                }
                for (TLRPC.Chat c : res.chats) {
                    chats.put(c.id, c);
                }
                final File page = new File(workDir, "page_" + pages.size() + ".jsonl");
                try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(page), StandardCharsets.UTF_8))) {
                    for (TLRPC.Message message : res.messages) {
                        if (cancelled) {
                            break;
                        }
                        if (message instanceof TLRPC.TL_messageEmpty) {
                            continue;
                        }
                        w.write(messageJson(message).toString());
                        w.write('\n');
                        done++;
                        offsetId = message.id;
                    }
                }
                pages.add(page);
                progress();
                if (res.messages.size() < 100 && !(res instanceof TLRPC.TL_messages_messagesSlice || res instanceof TLRPC.TL_messages_channelMessages)) {
                    break;
                }
                Thread.sleep(300);
            }
            return pages;
        }

        private TLObject historyRequest(TLRPC.InputPeer peer, int offsetId, int limit) {
            if (topicId != 0) {
                final TLRPC.TL_messages_getReplies req = new TLRPC.TL_messages_getReplies();
                req.peer = peer;
                req.msg_id = (int) topicId;
                req.offset_id = offsetId;
                req.limit = limit;
                return req;
            }
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_id = offsetId;
            req.limit = limit;
            return req;
        }

        /** A request answered in this thread, waiting out flood limits. */
        private TLObject send(TLObject request) throws Exception {
            for (int attempt = 0; attempt < 10 && !cancelled; attempt++) {
                final CountDownLatch latch = new CountDownLatch(1);
                final TLObject[] response = new TLObject[1];
                final TLRPC.TL_error[] error = new TLRPC.TL_error[1];
                ConnectionsManager.getInstance(account).sendRequest(request, (r, e) -> {
                    response[0] = r;
                    error[0] = e;
                    latch.countDown();
                });
                while (!latch.await(500, TimeUnit.MILLISECONDS)) {
                    if (cancelled) {
                        throw new InterruptedException("cancelled");
                    }
                }
                if (error[0] == null) {
                    return response[0];
                }
                final String text = error[0].text == null ? "" : error[0].text;
                if (text.startsWith("FLOOD_WAIT_")) {
                    int seconds = 5;
                    try {
                        seconds = Integer.parseInt(text.substring("FLOOD_WAIT_".length()));
                    } catch (NumberFormatException ignore) {
                    }
                    for (int i = 0; i < seconds * 2 && !cancelled; i++) {
                        Thread.sleep(500);
                    }
                    continue;
                }
                throw new IllegalStateException(text);
            }
            throw new InterruptedException("cancelled");
        }

        // --- One message, as Telegram Desktop writes it -----------------------------------------

        private JSONObject messageJson(TLRPC.Message message) throws JSONException {
            final JSONObject json = new JSONObject();
            json.put("id", message.id);
            final boolean service = message instanceof TLRPC.TL_messageService;
            json.put("type", service ? "service" : "message");
            json.put("date", isoDate(message.date));
            json.put("date_unixtime", String.valueOf(message.date));
            if (message.edit_date != 0 && !message.edit_hide) {
                json.put("edited", isoDate(message.edit_date));
                json.put("edited_unixtime", String.valueOf(message.edit_date));
            }
            final long fromId = message.from_id != null ? MessageObject.getPeerId(message.from_id) : dialogId;
            final String from = peerName(fromId);
            if (service) {
                json.put("actor", from == null ? JSONObject.NULL : from);
                json.put("actor_id", peerTag(fromId));
                json.put("action", actionName(message.action));
                if (message.action instanceof TLRPC.TL_messageActionChatEditTitle || message.action instanceof TLRPC.TL_messageActionChannelCreate
                        || message.action instanceof TLRPC.TL_messageActionChatCreate) {
                    json.put("title", message.action.title);
                }
                final MessageObject object = new MessageObject(account, message, (java.util.AbstractMap<Long, TLRPC.User>) users, (java.util.AbstractMap<Long, TLRPC.Chat>) chats, false, false);
                putText(json, object.messageText == null ? "" : object.messageText.toString(), null);
                return json;
            }
            json.put("from", from == null ? JSONObject.NULL : from);
            json.put("from_id", peerTag(fromId));
            if (!TextUtils.isEmpty(message.post_author)) {
                json.put("author", message.post_author);
            }
            if (message.fwd_from != null) {
                final String forwarded = message.fwd_from.from_id != null ? peerName(MessageObject.getPeerId(message.fwd_from.from_id)) : message.fwd_from.from_name;
                json.put("forwarded_from", forwarded == null ? JSONObject.NULL : forwarded);
            }
            if (message.reply_to != null && message.reply_to.reply_to_msg_id != 0 && (topicId == 0 || message.reply_to.reply_to_msg_id != topicId)) {
                json.put("reply_to_message_id", message.reply_to.reply_to_msg_id);
            }
            if (message.via_bot_id != 0) {
                final TLRPC.User bot = users.get(message.via_bot_id);
                if (bot != null && !TextUtils.isEmpty(UserObject.getPublicUsername(bot))) {
                    json.put("via_bot", "@" + UserObject.getPublicUsername(bot));
                }
            }
            putMedia(json, message);
            putText(json, message.message == null ? "" : message.message, message.entities);
            return json;
        }

        private String peerName(long peerId) {
            if (peerId > 0) {
                TLRPC.User u = users.get(peerId);
                if (u == null) {
                    u = MessagesController.getInstance(account).getUser(peerId);
                }
                return u == null ? null : UserObject.isDeleted(u) ? getString(R.string.HiddenName) : UserObject.getUserName(u);
            }
            TLRPC.Chat c = chats.get(-peerId);
            if (c == null) {
                c = MessagesController.getInstance(account).getChat(-peerId);
            }
            return c == null ? null : c.title;
        }

        private static String peerTag(long peerId) {
            return peerId > 0 ? "user" + peerId : "channel" + (-peerId);
        }

        private static String actionName(TLRPC.MessageAction action) {
            if (action == null) {
                return "unknown";
            }
            if (action instanceof TLRPC.TL_messageActionChatCreate) return "create_group";
            if (action instanceof TLRPC.TL_messageActionChannelCreate) return "create_channel";
            if (action instanceof TLRPC.TL_messageActionChatAddUser) return "invite_members";
            if (action instanceof TLRPC.TL_messageActionChatDeleteUser) return "remove_members";
            if (action instanceof TLRPC.TL_messageActionChatJoinedByLink) return "join_group_by_link";
            if (action instanceof TLRPC.TL_messageActionChatEditTitle) return "edit_group_title";
            if (action instanceof TLRPC.TL_messageActionChatEditPhoto) return "edit_group_photo";
            if (action instanceof TLRPC.TL_messageActionChatDeletePhoto) return "delete_group_photo";
            if (action instanceof TLRPC.TL_messageActionPinMessage) return "pin_message";
            if (action instanceof TLRPC.TL_messageActionPhoneCall) return "phone_call";
            if (action instanceof TLRPC.TL_messageActionHistoryClear) return "clear_history";
            if (action instanceof TLRPC.TL_messageActionScreenshotTaken) return "take_screenshot";
            // the rest by its type: TL_messageActionGroupCall -> group_call
            String type = action.getClass().getSimpleName().replaceFirst("^TL_messageAction", "").replaceAll("_layer\\d+$", "");
            return type.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.US);
        }

        // --- Text and its entities ------------------------------------------------------------

        private static void putText(JSONObject json, String text, ArrayList<TLRPC.MessageEntity> entities) throws JSONException {
            final JSONArray parts = new JSONArray();
            final JSONArray textEntities = new JSONArray();
            int at = 0;
            if (entities != null && !entities.isEmpty()) {
                final ArrayList<TLRPC.MessageEntity> sorted = new ArrayList<>(entities);
                Collections.sort(sorted, (a, b) -> Integer.compare(a.offset, b.offset));
                for (TLRPC.MessageEntity entity : sorted) {
                    final int start = Math.max(entity.offset, 0), end = Math.min(entity.offset + entity.length, text.length());
                    if (start < at || start >= end) {
                        continue; // nested in the previous one, or out of the text
                    }
                    if (start > at) {
                        parts.put(text.substring(at, start));
                        textEntities.put(entityJson("plain", text.substring(at, start)));
                    }
                    final JSONObject e = entityJson(entityType(entity), text.substring(start, end));
                    if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                        e.put("href", entity.url);
                    } else if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                        e.put("user_id", ((TLRPC.TL_messageEntityMentionName) entity).user_id);
                    } else if (entity instanceof TLRPC.TL_inputMessageEntityMentionName && ((TLRPC.TL_inputMessageEntityMentionName) entity).user_id != null) {
                        e.put("user_id", ((TLRPC.TL_inputMessageEntityMentionName) entity).user_id.user_id);
                    } else if (entity instanceof TLRPC.TL_messageEntityPre) {
                        e.put("language", entity.language == null ? "" : entity.language);
                    } else if (entity instanceof TLRPC.TL_messageEntityCustomEmoji) {
                        e.put("document_id", String.valueOf(((TLRPC.TL_messageEntityCustomEmoji) entity).document_id));
                    } else if (entity instanceof TLRPC.TL_messageEntityBlockquote) {
                        e.put("collapsed", entity.collapsed);
                    }
                    parts.put(e);
                    textEntities.put(e);
                    at = end;
                }
            }
            if (at < text.length()) {
                parts.put(text.substring(at));
                textEntities.put(entityJson("plain", text.substring(at)));
            }
            // plain text stays a string, as on Desktop
            if (parts.length() == 0) {
                json.put("text", "");
            } else if (parts.length() == 1 && parts.opt(0) instanceof String) {
                json.put("text", parts.getString(0));
            } else {
                json.put("text", parts);
            }
            json.put("text_entities", textEntities);
        }

        private static JSONObject entityJson(String type, String text) throws JSONException {
            final JSONObject e = new JSONObject();
            e.put("type", type);
            e.put("text", text);
            return e;
        }

        private static String entityType(TLRPC.MessageEntity e) {
            if (e instanceof TLRPC.TL_messageEntityBold) return "bold";
            if (e instanceof TLRPC.TL_messageEntityItalic) return "italic";
            if (e instanceof TLRPC.TL_messageEntityUnderline) return "underline";
            if (e instanceof TLRPC.TL_messageEntityStrike) return "strikethrough";
            if (e instanceof TLRPC.TL_messageEntityCode) return "code";
            if (e instanceof TLRPC.TL_messageEntityPre) return "pre";
            if (e instanceof TLRPC.TL_messageEntityTextUrl) return "text_link";
            if (e instanceof TLRPC.TL_messageEntityUrl) return "link";
            if (e instanceof TLRPC.TL_messageEntityMention) return "mention";
            if (e instanceof TLRPC.TL_messageEntityMentionName || e instanceof TLRPC.TL_inputMessageEntityMentionName) return "mention_name";
            if (e instanceof TLRPC.TL_messageEntityHashtag) return "hashtag";
            if (e instanceof TLRPC.TL_messageEntityCashtag) return "cashtag";
            if (e instanceof TLRPC.TL_messageEntityBotCommand) return "bot_command";
            if (e instanceof TLRPC.TL_messageEntityEmail) return "email";
            if (e instanceof TLRPC.TL_messageEntityPhone) return "phone";
            if (e instanceof TLRPC.TL_messageEntitySpoiler) return "spoiler";
            if (e instanceof TLRPC.TL_messageEntityBlockquote) return "blockquote";
            if (e instanceof TLRPC.TL_messageEntityCustomEmoji) return "custom_emoji";
            if (e instanceof TLRPC.TL_messageEntityBankCard) return "bank_card";
            return "unknown";
        }

        // --- Media ----------------------------------------------------------------------------

        private void putMedia(JSONObject json, TLRPC.Message message) throws JSONException {
            final TLRPC.MessageMedia media = MessageObject.getMedia(message);
            if (media == null || media instanceof TLRPC.TL_messageMediaEmpty) {
                return;
            }
            final boolean protectedContent = message.noforwards;
            if (media instanceof TLRPC.TL_messageMediaPhoto && media.photo != null) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize());
                if (size != null) {
                    json.put("width", size.w);
                    json.put("height", size.h);
                    json.put("photo_file_size", size.size);
                }
                final String path = options.photos && !protectedContent && size != null && !(size instanceof TLRPC.TL_photoStrippedSize)
                        ? saveMedia(message, size, media.photo, "photos", "photo_" + message.id + "@" + fileDate(message.date) + ".jpg", size.size)
                        : NOT_INCLUDED;
                json.put("photo", path);
            } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
                final TLRPC.Document document = media.document;
                final String kind = documentKind(document, media);
                if (kind != null) {
                    json.put("media_type", kind);
                }
                final String fileName = FileLoader.getDocumentFileName(document);
                if (!TextUtils.isEmpty(fileName)) {
                    json.put("file_name", fileName);
                }
                json.put("file_size", document.size);
                if (!TextUtils.isEmpty(document.mime_type)) {
                    json.put("mime_type", document.mime_type);
                }
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                        json.put("duration_seconds", (int) attribute.duration);
                        json.put("width", attribute.w);
                        json.put("height", attribute.h);
                    } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        json.put("duration_seconds", (int) attribute.duration);
                        if (!TextUtils.isEmpty(attribute.performer)) json.put("performer", attribute.performer);
                        if (!TextUtils.isEmpty(attribute.title)) json.put("title", attribute.title);
                    } else if (attribute instanceof TLRPC.TL_documentAttributeSticker && !TextUtils.isEmpty(attribute.alt)) {
                        json.put("sticker_emoji", attribute.alt);
                    } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                        json.put("width", attribute.w);
                        json.put("height", attribute.h);
                    }
                }
                final String folder;
                final boolean wanted;
                if ("sticker".equals(kind)) {
                    folder = "stickers";
                    wanted = options.stickers;
                } else if ("animation".equals(kind)) {
                    folder = "animations";
                    wanted = options.stickers;
                } else if ("video_file".equals(kind)) {
                    folder = "video_files";
                    wanted = options.videos;
                } else if ("video_message".equals(kind)) {
                    folder = "round_video_messages";
                    wanted = options.voice;
                } else if ("voice_message".equals(kind)) {
                    folder = "voice_messages";
                    wanted = options.voice;
                } else {
                    folder = "files";
                    wanted = options.files;
                }
                String file = TextUtils.isEmpty(fileName) ? null : safeFileName(fileName);
                if (file == null) {
                    file = folder.replaceAll("s$", "") + "_" + message.id + "@" + fileDate(message.date) + extension(document);
                }
                final String path = wanted && !protectedContent ? saveMedia(message, document, null, folder, file, document.size) : NOT_INCLUDED;
                json.put("file", path);
            } else if (media instanceof TLRPC.TL_messageMediaContact) {
                final JSONObject contact = new JSONObject();
                contact.put("first_name", media.first_name == null ? "" : media.first_name);
                contact.put("last_name", media.last_name == null ? "" : media.last_name);
                contact.put("phone_number", media.phone_number == null ? "" : media.phone_number);
                json.put("contact_information", contact);
            } else if (media.geo instanceof TLRPC.TL_geoPoint) {
                final JSONObject location = new JSONObject();
                location.put("latitude", media.geo.lat);
                location.put("longitude", media.geo._long);
                json.put("location_information", location);
                if (!TextUtils.isEmpty(media.title)) json.put("place_name", media.title);
                if (!TextUtils.isEmpty(media.address)) json.put("address", media.address);
            } else if (media instanceof TLRPC.TL_messageMediaPoll && ((TLRPC.TL_messageMediaPoll) media).poll != null) {
                final TLRPC.TL_messageMediaPoll pollMedia = (TLRPC.TL_messageMediaPoll) media;
                final JSONObject poll = new JSONObject();
                poll.put("question", pollMedia.poll.question == null ? "" : pollMedia.poll.question.text);
                poll.put("closed", pollMedia.poll.closed);
                poll.put("total_voters", pollMedia.results != null ? pollMedia.results.total_voters : 0);
                final JSONArray answers = new JSONArray();
                for (TLRPC.PollAnswer answer : pollMedia.poll.answers) {
                    final JSONObject a = new JSONObject();
                    a.put("text", answer.text == null ? "" : answer.text.text);
                    int voters = 0;
                    boolean chosen = false;
                    if (pollMedia.results != null && pollMedia.results.results != null) {
                        for (TLRPC.PollAnswerVoters v : pollMedia.results.results) {
                            if (java.util.Arrays.equals(v.option, answer.option)) {
                                voters = v.voters;
                                chosen = v.chosen;
                            }
                        }
                    }
                    a.put("voters", voters);
                    a.put("chosen", chosen);
                    answers.put(a);
                }
                poll.put("answers", answers);
                json.put("poll", poll);
            }
        }

        private static String documentKind(TLRPC.Document document, TLRPC.MessageMedia media) {
            if (MessageObject.isStickerDocument(document) || MessageObject.isAnimatedStickerDocument(document, true)) return "sticker";
            if (MessageObject.isRoundVideoDocument(document)) return "video_message";
            if (MessageObject.isVoiceDocument(document)) return "voice_message";
            if (MessageObject.isGifDocument(document)) return "animation";
            if (MessageObject.isVideoDocument(document)) return "video_file";
            if (MessageObject.isMusicDocument(document)) return "audio_file";
            return null;
        }

        private static String extension(TLRPC.Document document) {
            final String ext = FileLoader.getDocumentExtension(document);
            return TextUtils.isEmpty(ext) ? "" : "." + ext;
        }

        private static String safeFileName(String name) {
            final String s = name.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
            return s.isEmpty() || s.startsWith(".") ? null : s;
        }

        /**
         * A file of the message in the archive, downloaded first if this phone doesn't have it:
         * its path in the archive, or the text Desktop writes in its place.
         */
        private String saveMedia(TLRPC.Message message, TLObject attach, TLRPC.Photo photo, String folder, String fileName, long size) {
            if (size > SIZE_LIMITS[options.sizeLimit]) {
                skipped++;
                return TOO_BIG;
            }
            final File file = download(message, attach, photo);
            if (file == null || !file.exists()) {
                skipped++;
                return NOT_INCLUDED;
            }
            String entry = folder + "/" + fileName;
            for (int i = 2; mediaNames.contains(entry); i++) {
                final int dot = fileName.lastIndexOf('.');
                entry = folder + "/" + (dot > 0 ? fileName.substring(0, dot) + " (" + i + ")" + fileName.substring(dot) : fileName + " (" + i + ")");
            }
            try (InputStream in = new FileInputStream(file)) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry(entry));
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    zip.write(buffer, 0, read);
                }
                zip.closeEntry();
            } catch (Exception e) {
                FileLog.e(e);
                skipped++;
                return NOT_INCLUDED;
            }
            mediaNames.add(entry);
            files++;
            progress();
            return entry;
        }

        private File download(TLRPC.Message message, TLObject attach, TLRPC.Photo photo) {
            final FileLoader loader = FileLoader.getInstance(account);
            File file = loader.getPathToMessage(message);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
            file = loader.getPathToAttach(attach, true);
            if (file != null && file.exists() && file.length() > 0) {
                return file;
            }
            final String key = FileLoader.getAttachFileName(attach);
            if (TextUtils.isEmpty(key)) {
                return null;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final File[] result = new File[1];
            final MessageObject parent = new MessageObject(account, message, false, false);
            final NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
                if (args.length > 0 && key.equals(args[0])) {
                    if (id == NotificationCenter.fileLoaded && args.length > 1 && args[1] instanceof File) {
                        result[0] = (File) args[1];
                    }
                    latch.countDown();
                }
            };
            AndroidUtilities.runOnUIThread(() -> {
                final NotificationCenter center = NotificationCenter.getInstance(account);
                center.addObserver(observer, NotificationCenter.fileLoaded);
                center.addObserver(observer, NotificationCenter.fileLoadFailed);
                if (attach instanceof TLRPC.Document) {
                    loader.loadFile((TLRPC.Document) attach, parent, FileLoader.PRIORITY_NORMAL, 1);
                } else if (attach instanceof TLRPC.PhotoSize) {
                    loader.loadFile(ImageLocation.getForPhoto((TLRPC.PhotoSize) attach, photo), parent, "jpg", FileLoader.PRIORITY_NORMAL, 1);
                }
            });
            try {
                // a large file is given its time; a stuck one, not forever
                for (int i = 0; i < 20 * 60 * 2 && !cancelled; i++) {
                    if (latch.await(500, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
            } catch (InterruptedException ignore) {
            } finally {
                AndroidUtilities.runOnUIThread(() -> {
                    final NotificationCenter center = NotificationCenter.getInstance(account);
                    center.removeObserver(observer, NotificationCenter.fileLoaded);
                    center.removeObserver(observer, NotificationCenter.fileLoadFailed);
                    if (result[0] == null) {
                        if (attach instanceof TLRPC.Document) {
                            loader.cancelLoadFile((TLRPC.Document) attach);
                        } else if (attach instanceof TLRPC.PhotoSize) {
                            loader.cancelLoadFile((TLRPC.PhotoSize) attach);
                        }
                    }
                });
            }
            if (result[0] != null) {
                return result[0];
            }
            file = loader.getPathToAttach(attach, true);
            return file != null && file.exists() && file.length() > 0 ? file : null;
        }

        // --- The archive's texts, oldest message first ------------------------------------------

        private void writeResults(ArrayList<File> pages) throws Exception {
            // first pass: where each message's HTML page is, for the replies' links
            final HashMap<Integer, Integer> pageOf = new HashMap<>();
            int index = 0;
            for (int p = pages.size() - 1; p >= 0; p--) {
                final ArrayList<String> lines = readLines(pages.get(p));
                for (int i = lines.size() - 1; i >= 0; i--) {
                    final int id = new JSONObject(lines.get(i)).optInt("id");
                    pageOf.put(id, index / MESSAGES_PER_HTML);
                    index++;
                }
            }
            final int htmlPages = Math.max(1, (index + MESSAGES_PER_HTML - 1) / MESSAGES_PER_HTML);
            final boolean json = options.format != FORMAT_HTML, html = options.format != FORMAT_JSON;

            final File jsonFile = new File(workDir, "result.json");
            BufferedWriter jsonOut = null;
            BufferedWriter htmlOut = null;
            final ArrayList<File> htmlFiles = new ArrayList<>();
            try {
                if (json) {
                    jsonOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jsonFile), StandardCharsets.UTF_8));
                    final JSONObject head = new JSONObject();
                    head.put("name", name);
                    head.put("type", chatType());
                    head.put("id", chat != null ? chat.id : user != null ? user.id : dialogId);
                    final String headText = head.toString(1);
                    jsonOut.write(headText.substring(0, headText.length() - 2));
                    jsonOut.write(",\n \"messages\": [");
                }
                int written = 0, page = -1;
                String lastDay = null;
                for (int p = pages.size() - 1; p >= 0 && !cancelled; p--) {
                    final ArrayList<String> lines = readLines(pages.get(p));
                    for (int i = lines.size() - 1; i >= 0; i--) {
                        final JSONObject message = new JSONObject(lines.get(i));
                        if (json) {
                            jsonOut.write(written == 0 ? "\n  " : ",\n  ");
                            jsonOut.write(message.toString(1).replace("\n", "\n  "));
                        }
                        if (html) {
                            final int messagePage = written / MESSAGES_PER_HTML;
                            if (messagePage != page) {
                                if (htmlOut != null) {
                                    htmlOut.write(htmlFooter(page, htmlPages));
                                    htmlOut.close();
                                }
                                page = messagePage;
                                final File f = new File(workDir, htmlName(page));
                                htmlFiles.add(f);
                                htmlOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
                                htmlOut.write(htmlHeader(page));
                                lastDay = null;
                            }
                            // a heading for each day, in the phone's time zone
                            final Date date = new Date(Long.parseLong(message.optString("date_unixtime", "0")) * 1000);
                            final String day = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(date);
                            if (!day.equals(lastDay)) {
                                htmlOut.write("<div class=\"day\">" + escape(day) + "</div>\n");
                                lastDay = day;
                            }
                            htmlOut.write(messageHtml(message, pageOf, page));
                        }
                        written++;
                    }
                }
                if (json) {
                    jsonOut.write("\n ]\n}\n");
                    jsonOut.close();
                    jsonOut = null;
                    addFile(jsonFile, "result.json");
                }
                if (html) {
                    if (htmlOut == null) {
                        final File f = new File(workDir, htmlName(0));
                        htmlFiles.add(f);
                        htmlOut = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
                        htmlOut.write(htmlHeader(0));
                        page = 0;
                    }
                    htmlOut.write(htmlFooter(page, htmlPages));
                    htmlOut.close();
                    htmlOut = null;
                    for (File f : htmlFiles) {
                        addFile(f, f.getName());
                    }
                }
            } finally {
                if (jsonOut != null) jsonOut.close();
                if (htmlOut != null) htmlOut.close();
            }
        }

        private void addFile(File file, String entry) throws Exception {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(new ZipEntry(entry));
            try (InputStream in = new FileInputStream(file)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }

        private String chatType() {
            if (user != null) {
                if (UserObject.isUserSelf(user)) return "saved_messages";
                return user.bot ? "bot_chat" : "personal_chat";
            }
            if (chat == null) return "personal_chat";
            final boolean isPublic = ChatObject.isPublic(chat);
            if (ChatObject.isChannelAndNotMegaGroup(chat)) return isPublic ? "public_channel" : "private_channel";
            if (ChatObject.isChannel(chat)) return isPublic ? "public_supergroup" : "private_supergroup";
            return "private_group";
        }

        private static ArrayList<String> readLines(File file) throws Exception {
            final ArrayList<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
            return lines;
        }

        // --- HTML -----------------------------------------------------------------------------

        private static String htmlName(int page) {
            return page == 0 ? "messages.html" : "messages" + (page + 1) + ".html";
        }

        private String htmlHeader(int page) {
            return "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                    + "<title>" + escape(name) + "</title><style>"
                    + "body{margin:0;background:#e7ebf0;font:15px -apple-system,Roboto,'Segoe UI',sans-serif;color:#000}"
                    + "@media(prefers-color-scheme:dark){body{background:#0e1621;color:#f5f5f5}.message{background:#182533!important}.day{background:#182533!important;color:#aaa!important}a{color:#6ab3f3}}"
                    + ".header{position:sticky;top:0;background:#517da2;color:#fff;padding:14px 16px;font-weight:600;font-size:17px}"
                    + ".history{max-width:720px;margin:0 auto;padding:8px 12px 24px}"
                    + ".message{background:#fff;border-radius:12px;padding:8px 12px;margin:6px 0;overflow-wrap:anywhere}"
                    + ".service{text-align:center;color:#666;font-size:13px;margin:8px 0}"
                    + ".from{font-weight:600;color:#3a6d99;margin-bottom:2px}.fwd,.reply{font-size:13px;color:#3a6d99;border-left:2px solid #3a6d99;padding-left:6px;margin:2px 0 4px}"
                    + ".time{float:right;font-size:12px;color:#999;margin-left:8px}.text{white-space:pre-wrap}"
                    + ".media img{max-width:100%;max-height:420px;border-radius:8px;display:block;margin:4px 0}.media video{max-width:100%;border-radius:8px}"
                    + ".day{text-align:center;margin:12px auto;font-size:13px;color:#555;background:#d4dde6;border-radius:12px;padding:3px 10px;width:max-content}"
                    + ".spoiler{background:#999;color:transparent}.spoiler:hover{color:inherit;background:none}code,pre{background:rgba(0,0,0,.06);border-radius:4px;padding:0 3px}"
                    + ".pages{text-align:center;padding:12px}.pages a{margin:0 6px}.missing{color:#999;font-style:italic}"
                    + "</style></head><body><div class=\"header\">" + escape(name) + "</div><div class=\"history\">\n";
        }

        private static String htmlFooter(int page, int pages) {
            final StringBuilder sb = new StringBuilder("<div class=\"pages\">");
            if (page > 0) sb.append("<a href=\"").append(htmlName(page - 1)).append("\">&larr;</a>");
            if (pages > 1) sb.append(page + 1).append(" / ").append(pages);
            if (page < pages - 1) sb.append("<a href=\"").append(htmlName(page + 1)).append("\">&rarr;</a>");
            sb.append("</div></div></body></html>\n");
            return sb.toString();
        }

        private String messageHtml(JSONObject m, HashMap<Integer, Integer> pageOf, int page) {
            final int id = m.optInt("id");
            final String time = m.optString("date", "");
            final String shortTime = time.length() >= 16 ? time.substring(11, 16) : time;
            if ("service".equals(m.optString("type"))) {
                return "<div class=\"service\" id=\"message" + id + "\">" + textHtml(m) + "</div>\n";
            }
            final StringBuilder sb = new StringBuilder("<div class=\"message\" id=\"message").append(id).append("\">");
            sb.append("<span class=\"time\" title=\"").append(escape(time.replace('T', ' '))).append("\">").append(escape(shortTime)).append("</span>");
            if (!m.isNull("from") && !(chat != null && ChatObject.isChannelAndNotMegaGroup(chat))) {
                sb.append("<div class=\"from\">").append(escape(m.optString("from"))).append("</div>");
            }
            if (m.has("forwarded_from")) {
                sb.append("<div class=\"fwd\">").append(escape(getString(R.string.ForwardedMessage))).append(": ")
                        .append(escape(m.isNull("forwarded_from") ? "?" : m.optString("forwarded_from"))).append("</div>");
            }
            if (m.has("reply_to_message_id")) {
                final int reply = m.optInt("reply_to_message_id");
                final Integer replyPage = pageOf.get(reply);
                final String href = (replyPage == null || replyPage == page ? "" : htmlName(replyPage)) + "#message" + reply;
                sb.append("<div class=\"reply\"><a href=\"").append(href).append("\">").append(escape(getString(R.string.GlassgramExportInReply))).append("</a></div>");
            }
            final String photo = m.optString("photo", null);
            final String file = m.optString("file", null);
            if (photo != null || file != null) {
                sb.append("<div class=\"media\">");
                final String path = photo != null ? photo : file;
                if (path.startsWith("(")) {
                    sb.append("<div class=\"missing\">").append(escape(m.optString("file_name", m.optString("media_type", getString(R.string.AttachPhoto))))).append(" — ").append(escape(path)).append("</div>");
                } else if (photo != null || "sticker".equals(m.optString("media_type")) && path.endsWith(".webp")) {
                    sb.append("<a href=\"").append(escape(path)).append("\"><img src=\"").append(escape(path)).append("\" loading=\"lazy\"></a>");
                } else if ("video_file".equals(m.optString("media_type")) || "video_message".equals(m.optString("media_type")) || "animation".equals(m.optString("media_type"))) {
                    sb.append("<video controls preload=\"none\" src=\"").append(escape(path)).append("\"></video>");
                } else if ("voice_message".equals(m.optString("media_type")) || "audio_file".equals(m.optString("media_type"))) {
                    sb.append("<audio controls preload=\"none\" src=\"").append(escape(path)).append("\"></audio>");
                } else {
                    sb.append("📎 <a href=\"").append(escape(path)).append("\">").append(escape(m.optString("file_name", path))).append("</a>");
                }
                sb.append("</div>");
            }
            final JSONObject contact = m.optJSONObject("contact_information");
            if (contact != null) {
                sb.append("<div>👤 ").append(escape((contact.optString("first_name") + " " + contact.optString("last_name")).trim()))
                        .append(" ").append(escape(contact.optString("phone_number"))).append("</div>");
            }
            final JSONObject location = m.optJSONObject("location_information");
            if (location != null) {
                sb.append("<div>📍 <a href=\"https://maps.google.com/maps?q=").append(location.optDouble("latitude")).append(",")
                        .append(location.optDouble("longitude")).append("\">").append(escape(m.optString("place_name", getString(R.string.AttachLocation)))).append("</a></div>");
            }
            final JSONObject poll = m.optJSONObject("poll");
            if (poll != null) {
                sb.append("<div>📊 <b>").append(escape(poll.optString("question"))).append("</b>");
                final JSONArray answers = poll.optJSONArray("answers");
                for (int i = 0; answers != null && i < answers.length(); i++) {
                    final JSONObject a = answers.optJSONObject(i);
                    sb.append("<br>• ").append(escape(a.optString("text"))).append(" — ").append(a.optInt("voters"));
                }
                sb.append("</div>");
            }
            sb.append("<div class=\"text\">").append(textHtml(m)).append("</div>");
            if (m.has("edited")) {
                sb.append("<div class=\"time\">").append(escape(getString(R.string.EditedMessage))).append("</div><div style=\"clear:both\"></div>");
            }
            sb.append("</div>\n");
            return sb.toString();
        }

        private static String textHtml(JSONObject m) {
            final JSONArray entities = m.optJSONArray("text_entities");
            if (entities == null) {
                return escape(m.optString("text"));
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < entities.length(); i++) {
                final JSONObject e = entities.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                final String text = escape(e.optString("text"));
                switch (e.optString("type")) {
                    case "bold": sb.append("<b>").append(text).append("</b>"); break;
                    case "italic": sb.append("<i>").append(text).append("</i>"); break;
                    case "underline": sb.append("<u>").append(text).append("</u>"); break;
                    case "strikethrough": sb.append("<s>").append(text).append("</s>"); break;
                    case "code": sb.append("<code>").append(text).append("</code>"); break;
                    case "pre": sb.append("<pre>").append(text).append("</pre>"); break;
                    case "spoiler": sb.append("<span class=\"spoiler\">").append(text).append("</span>"); break;
                    case "blockquote": sb.append("<blockquote>").append(text).append("</blockquote>"); break;
                    case "text_link": sb.append("<a href=\"").append(escape(e.optString("href"))).append("\">").append(text).append("</a>"); break;
                    case "link": sb.append("<a href=\"").append(e.optString("text").contains("://") ? "" : "https://").append(text).append("\">").append(text).append("</a>"); break;
                    case "email": sb.append("<a href=\"mailto:").append(text).append("\">").append(text).append("</a>"); break;
                    case "mention": sb.append("<a href=\"https://t.me/").append(escape(e.optString("text").replaceFirst("^@", ""))).append("\">").append(text).append("</a>"); break;
                    default: sb.append(text);
                }
            }
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }

        private static String isoDate(int unix) {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(unix * 1000L));
        }

        private static String fileDate(int unix) {
            return new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(new Date(unix * 1000L));
        }

        private static void deleteDir(File dir) {
            final File[] children = dir == null ? null : dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    child.delete();
                }
            }
            if (dir != null) {
                dir.delete();
            }
        }
    }
}
