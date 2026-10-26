package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

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
}
