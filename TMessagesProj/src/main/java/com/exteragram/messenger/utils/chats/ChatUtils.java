package com.exteragram.messenger.utils.chats;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;

/** glassGram: exteraGram's ChatUtils, the part plugins made for exteraGram use. */
public class ChatUtils {

    private static final ChatUtils[] instances = new ChatUtils[UserConfig.MAX_ACCOUNT_COUNT];

    private final int account;

    public ChatUtils(int account) {
        this.account = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int account) {
        synchronized (instances) {
            if (instances[account] == null) {
                instances[account] = new ChatUtils(account);
            }
            return instances[account];
        }
    }

    public MessagesController getMessagesController() {
        return MessagesController.getInstance(account);
    }

    /** The channel or group a username stands for, or null. */
    public void resolveChannel(String username, Utilities.Callback<TLRPC.Chat> callback) {
        getMessagesController().getUserNameResolver().resolve(username, peerId -> {
            if (peerId != null && peerId < 0) {
                callback.run(getMessagesController().getChat(-peerId));
            } else {
                callback.run(null);
            }
        });
    }

    // From exteraGram's ChatUtils (GPL v3 or later, ADDITIONAL_TERMS.md): the file a message's
    // media was saved to, or null; its plugin engine installs a plugin sent in a chat from there.
    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }

        FileLoader fileLoader = FileLoader.getInstance(messageObject.currentAccount);
        String path;

        if (messageObject.messageOwner != null && !TextUtils.isEmpty(messageObject.messageOwner.attachPath)) {
            path = messageObject.messageOwner.attachPath;
            if (fileExists(path)) {
                return path;
            }
        }

        if (messageObject.messageOwner != null) {
            path = fileLoader.getPathToMessage(messageObject.messageOwner).toString();
            if (fileExists(path)) {
                return path;
            }
        }

        if (messageObject.getDocument() != null) {
            path = fileLoader.getPathToAttach(messageObject.getDocument(), false).toString();
            if (fileExists(path)) {
                return path;
            }

            path = fileLoader.getPathToAttach(messageObject.getDocument(), true).toString();
            if (fileExists(path)) {
                return path;
            }
        }

        if (messageObject.cachedQuality != null && messageObject.cachedQuality.isCached() && messageObject.cachedQuality.uri != null) {
            path = messageObject.cachedQuality.uri.getPath();
            if (fileExists(path)) {
                return path;
            }
        }

        if (messageObject.qualityToSave != null) {
            path = fileLoader.getPathToAttach(messageObject.qualityToSave, null, false, true).toString();
            if (fileExists(path)) {
                return path;
            }
        }

        return null;
    }

    private static boolean fileExists(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && file.isFile();
    }
}
