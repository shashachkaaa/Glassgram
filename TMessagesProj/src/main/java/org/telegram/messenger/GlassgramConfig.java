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
    public static boolean hideFloatingButton;

    // Chats
    public static boolean hideTimeOnStickers;
    public static boolean disableGreetingSticker;
    public static boolean hideKeyboardOnScroll;
    public static boolean hideSendAsButton;
    public static boolean unlimitedRecentStickers;

    /** Recent stickers kept when the limit is lifted; Telegram's own is 20-30. */
    public static final int UNLIMITED_RECENT_STICKERS = 200;

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
        hideFloatingButton = p.getBoolean("hideFloatingButton", false);
        hideTimeOnStickers = p.getBoolean("hideTimeOnStickers", false);
        disableGreetingSticker = p.getBoolean("disableGreetingSticker", false);
        hideKeyboardOnScroll = p.getBoolean("hideKeyboardOnScroll", false);
        hideSendAsButton = p.getBoolean("hideSendAsButton", false);
        unlimitedRecentStickers = p.getBoolean("unlimitedRecentStickers", false);
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
