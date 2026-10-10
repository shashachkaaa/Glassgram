package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Glassgram's own preferences (Settings → Glassgram), read straight from static fields by the
 * code they change.
 */
public class GlassgramConfig {

    private static final String PREFS = "glassgram_config";

    // General
    public static boolean disableNumberRounding;
    public static boolean formatTimeWithSeconds;
    public static boolean relativeLastSeen;
    public static boolean hidePhoneNumber;
    public static int showId;
    public static boolean useYandexMaps;
    public static int downloadBoost;
    public static boolean uploadBoost;

    // Names and messages without "Zalgo" stacks of combining marks
    public static boolean filterZalgo;
    // Saved files go to Pictures/<saveFolder>, Download/<saveFolder>...; empty: Glassgram
    public static String saveFolder = "";
    // The data center next to the id in profiles
    public static boolean showDc;

    public static final int SHOW_ID_OFF = 0, SHOW_ID_TELEGRAM_API = 1, SHOW_ID_BOT_API = 2;
    public static final int BOOST_OFF = 0, BOOST_FAST = 1, BOOST_ULTRA = 2;

    // Ghost mode: the master switch and what it covers
    public static boolean ghostMode;
    public static boolean ghostNoReadMessages = true;
    public static boolean ghostNoReadStories = true;
    public static boolean ghostNoOnline = true;
    public static boolean ghostNoTyping = true;
    public static boolean ghostAutoOffline = true;
    public static boolean ghostReadOnInteract = true;

    public static boolean ghostNoReadMessages() { return ghostMode && ghostNoReadMessages; }
    public static boolean ghostNoReadStories() { return ghostMode && ghostNoReadStories; }
    public static boolean ghostNoOnline() { return ghostMode && ghostNoOnline; }
    public static boolean ghostNoTyping() { return ghostMode && ghostNoTyping; }
    public static boolean ghostAutoOffline() { return ghostMode && ghostAutoOffline; }

    // Appearance
    public static String titleText = "";
    public static boolean hideStories;
    public static boolean headerBadge = true;
    public static boolean glassHeader = true;
    /** The message input split into glass pieces: attach circle, field, send circle (and the bots' menu). */
    public static boolean splitInput = true;
    public static boolean hideFloatingButton;

    // Chats
    public static boolean hideTimeOnStickers;
    public static boolean disableGreetingSticker;
    public static boolean hideKeyboardOnScroll;
    public static boolean hideSendAsButton;
    public static boolean unlimitedRecentStickers;
    // Stickers: the size (12 is Telegram's own, 4..20) and the shape
    public static final int STICKER_SIZE_DEFAULT = 12, STICKER_SIZE_MIN = 4, STICKER_SIZE_MAX = 20;
    public static final int STICKER_SHAPE_DEFAULT = 0, STICKER_SHAPE_ROUNDED = 1, STICKER_SHAPE_MESSAGE = 2;
    public static int stickerSize = STICKER_SIZE_DEFAULT;
    public static int stickerShape;

    // Reactions hidden from the interface, by chat kind
    public static boolean hideReactions;
    public static boolean hideReactionsChannels = true;
    public static boolean hideReactionsGroups = true;
    public static boolean hideReactionsPrivate = true;

    // Messages
    public static boolean removeMessageTail;
    public static boolean editedIcon;
    public static boolean showOnlineIndicator;
    public static boolean showForwardCount;
    public static boolean hideShareButton;
    public static boolean showPollResults;
    public static boolean commaAfterMention;

    // Videos
    public static int doubleTapSeekSeconds = 10;
    public static boolean preferOriginalQuality;
    public static boolean volumeUnmute;
    public static boolean autoPause;
    public static boolean autoPauseVideo = true;
    public static boolean autoPauseVoice;
    public static boolean autoPauseRound;

    /** Whether reactions are hidden in this chat: channels, groups, or private chats and bots. */
    public static boolean hidesReactions(boolean channel, boolean group) {
        return hideReactions && (channel ? hideReactionsChannels : group ? hideReactionsGroups : hideReactionsPrivate);
    }

    /** The sticker size multiplier: 1 for Telegram's own size. */
    public static float stickerScale() {
        return stickerSize / (float) STICKER_SIZE_DEFAULT;
    }

    /** Recent stickers kept when the limit is lifted; Telegram's own is 20-30. */
    public static final int UNLIMITED_RECENT_STICKERS = 200;



    // Spy
    public static boolean spySaveDeletedMessages;
    public static boolean spySaveEditsHistory;
    public static boolean spySaveInBotDialogs;
    public static boolean spySaveReadDate;
    public static boolean spySaveLastSeenDate;
    public static boolean spySaveAttachments;
    public static int spyMaxFolderSize = 1; // 1 GB
    public static boolean spyEnableFilters;
    public static boolean spyEnableSharedFilters;
    public static boolean spyHideBlockedUsers;
    public static boolean spyShadowBan;
    public static boolean spyTranslucentDeleted;
    public static boolean spyDeletedTrashMark;
    public static int spyDeletedMarkColor; // 0 = the time color
    public static boolean spyDisableAds;
    public static boolean spyDisplayGhostStatus;
    public static boolean spyDisableSelfDestruct;
    public static boolean ignoreContentProtection;
    public static boolean pluginsEngine;
    public static boolean pluginsEnableAfterInstall = true;

    public static final int SPY_LIMIT_300_MB = 0;
    public static final int SPY_LIMIT_1_GB = 1;
    public static final int SPY_LIMIT_2_GB = 2;
    public static final int SPY_LIMIT_5_GB = 3;
    public static final int SPY_LIMIT_16_GB = 4;
    public static final int SPY_LIMIT_UNLIMITED = 5;

    /**
     * The RuStore edition: the features that change how Telegram itself works (ghost mode, the
     * spy, keeping self-destructing and protected content, hiding ads) are off and not offered.
     */
    public static final boolean STORE_BUILD = BuildVars.GLASSGRAM_STORE;

    private static boolean loaded;

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        final SharedPreferences p = prefs();
        disableNumberRounding = p.getBoolean("disableNumberRounding", false);
        formatTimeWithSeconds = p.getBoolean("formatTimeWithSeconds", false);
        relativeLastSeen = p.getBoolean("relativeLastSeen", false);
        hidePhoneNumber = p.getBoolean("hidePhoneNumber", false);
        showId = p.getInt("showId", SHOW_ID_OFF);
        useYandexMaps = p.getBoolean("useYandexMaps", false);
        downloadBoost = p.getInt("downloadBoost", BOOST_OFF);
        uploadBoost = p.getBoolean("uploadBoost", false);
        titleText = p.getString("titleText", "");
        ghostMode = p.getBoolean("ghostMode", false);
        ghostNoReadMessages = p.getBoolean("ghostNoReadMessages", true);
        ghostNoReadStories = p.getBoolean("ghostNoReadStories", true);
        ghostNoOnline = p.getBoolean("ghostNoOnline", true);
        ghostNoTyping = p.getBoolean("ghostNoTyping", true);
        ghostAutoOffline = p.getBoolean("ghostAutoOffline", true);
        ghostReadOnInteract = p.getBoolean("ghostReadOnInteract", true);
        hideStories = p.getBoolean("hideStories", false);
        headerBadge = p.getBoolean("headerBadge", true);
        glassHeader = p.getBoolean("glassHeader", true);
        splitInput = p.getBoolean("splitInput", true);
        hideFloatingButton = p.getBoolean("hideFloatingButton", false);
        hideTimeOnStickers = p.getBoolean("hideTimeOnStickers", false);
        disableGreetingSticker = p.getBoolean("disableGreetingSticker", false);
        hideKeyboardOnScroll = p.getBoolean("hideKeyboardOnScroll", false);
        hideSendAsButton = p.getBoolean("hideSendAsButton", false);
        unlimitedRecentStickers = p.getBoolean("unlimitedRecentStickers", false);
        spySaveDeletedMessages = p.getBoolean("spySaveDeletedMessages", false);
        spySaveEditsHistory = p.getBoolean("spySaveEditsHistory", false);
        spySaveInBotDialogs = p.getBoolean("spySaveInBotDialogs", false);
        spySaveReadDate = p.getBoolean("spySaveReadDate", false);
        spySaveLastSeenDate = p.getBoolean("spySaveLastSeenDate", false);
        spySaveAttachments = p.getBoolean("spySaveAttachments", false);
        spyMaxFolderSize = p.getInt("spyMaxFolderSize", SPY_LIMIT_1_GB);
        spyEnableFilters = p.getBoolean("spyEnableFilters", false);
        spyEnableSharedFilters = p.getBoolean("spyEnableSharedFilters", false);
        spyHideBlockedUsers = p.getBoolean("spyHideBlockedUsers", false);
        spyShadowBan = p.getBoolean("spyShadowBan", false);
        spyTranslucentDeleted = p.getBoolean("spyTranslucentDeleted", true);
        spyDeletedTrashMark = p.getBoolean("spyDeletedTrashMark", true);
        spyDeletedMarkColor = p.getInt("spyDeletedMarkColor", 0);
        spyDisableAds = p.getBoolean("spyDisableAds", false);
        spyDisplayGhostStatus = p.getBoolean("spyDisplayGhostStatus", false);
        spyDisableSelfDestruct = p.getBoolean("spyDisableSelfDestruct", false);
        ignoreContentProtection = p.getBoolean("ignoreContentProtection", false);
        pluginsEngine = p.getBoolean("pluginsEngine", false);
        pluginsEnableAfterInstall = p.getBoolean("pluginsEnableAfterInstall", true);
        filterZalgo = p.getBoolean("filterZalgo", false);
        saveFolder = p.getString("saveFolder", "");
        showDc = p.getBoolean("showDc", false);
        stickerSize = Math.max(STICKER_SIZE_MIN, Math.min(STICKER_SIZE_MAX, p.getInt("stickerSize", STICKER_SIZE_DEFAULT)));
        stickerShape = p.getInt("stickerShape", STICKER_SHAPE_DEFAULT);
        hideReactions = p.getBoolean("hideReactions", false);
        hideReactionsChannels = p.getBoolean("hideReactionsChannels", true);
        hideReactionsGroups = p.getBoolean("hideReactionsGroups", true);
        hideReactionsPrivate = p.getBoolean("hideReactionsPrivate", true);
        removeMessageTail = p.getBoolean("removeMessageTail", false);
        editedIcon = p.getBoolean("editedIcon", false);
        showOnlineIndicator = p.getBoolean("showOnlineIndicator", false);
        showForwardCount = p.getBoolean("showForwardCount", false);
        hideShareButton = p.getBoolean("hideShareButton", false);
        showPollResults = p.getBoolean("showPollResults", false);
        commaAfterMention = p.getBoolean("commaAfterMention", false);
        doubleTapSeekSeconds = p.getInt("doubleTapSeekSeconds", 10);
        preferOriginalQuality = p.getBoolean("preferOriginalQuality", false);
        volumeUnmute = p.getBoolean("volumeUnmute", false);
        autoPause = p.getBoolean("autoPause", false);
        autoPauseVideo = p.getBoolean("autoPauseVideo", true);
        autoPauseVoice = p.getBoolean("autoPauseVoice", false);
        autoPauseRound = p.getBoolean("autoPauseRound", false);
        if (STORE_BUILD) {
            ghostMode = false;
            spySaveDeletedMessages = false;
            spySaveEditsHistory = false;
            spySaveInBotDialogs = false;
            spySaveReadDate = false;
            spySaveLastSeenDate = false;
            spySaveAttachments = false;
            spyTranslucentDeleted = false;
            spyDeletedTrashMark = false;
            spyDisableAds = false;
            spyDisplayGhostStatus = false;
            spyDisableSelfDestruct = false;
            ignoreContentProtection = false;
        }
        loaded = true;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
    }

    public static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
    }

    public static void putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
    }

    public static void setTitleText(String text) {
        titleText = text == null ? "" : text.trim();
        prefs().edit().putString("titleText", titleText).apply();
    }

    /** The chats list title: the custom one, or the app's name. */
    public static String getTitle() {
        load();
        return titleText != null && !titleText.isEmpty() ? titleText : LocaleController.getString(R.string.AppName);
    }

    static {
        if (ApplicationLoader.applicationContext != null) {
            load();
        }
    }
}
