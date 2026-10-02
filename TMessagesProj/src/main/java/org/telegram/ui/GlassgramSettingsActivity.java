package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Toast;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramSpyStorage;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Settings → Glassgram: the fork's own options, in General, Appearance and Chats pages.
 */
public class GlassgramSettingsActivity extends UniversalFragment {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_GENERAL = 1;
    public static final int PAGE_APPEARANCE = 2;
    public static final int PAGE_CHATS = 3;
    public static final int PAGE_GHOST = 4;
    public static final int PAGE_SPY = 5;
    public static final int PAGE_CUSTOMIZATION = 6;
    public static final int PAGE_FILTERS = 7;

    private static final int ID_GENERAL = 1;
    private static final int ID_APPEARANCE = 2;
    private static final int ID_CHATS = 3;
    private static final int ID_GHOST = 4;
    private static final int ID_SPY = 5;
    private static final int ID_CUSTOMIZATION = 6;
    private static final int ID_FILTERS = 7;

    private static final int ID_GHOST_MODE = 40;
    private static final int ID_GHOST_READ = 41;
    private static final int ID_GHOST_STORIES = 42;
    private static final int ID_GHOST_ONLINE = 43;
    private static final int ID_GHOST_TYPING = 44;
    private static final int ID_GHOST_OFFLINE = 45;
    private static final int ID_GHOST_READ_ON_INTERACT = 46;

    private static final int ID_SPY_DELETED = 50;
    private static final int ID_SPY_EDITS = 51;
    private static final int ID_SPY_BOTS = 52;
    private static final int ID_SPY_READ_DATE = 53;
    private static final int ID_SPY_LAST_SEEN = 54;
    private static final int ID_SPY_ATTACHMENTS = 55;
    private static final int ID_SPY_LIMIT = 56;
    private static final int ID_SPY_EXPORT = 57;
    private static final int ID_SPY_IMPORT = 58;
    private static final int ID_SPY_CLEAR = 59;

    private static final int ID_CUSTOM_TRANSLUCENT = 60;
    private static final int ID_CUSTOM_TRASH = 61;
    private static final int ID_CUSTOM_ADS = 62;
    private static final int ID_CUSTOM_GHOST_STATUS = 63;

    private static final int ID_FILTER_ENABLE = 70;
    private static final int ID_FILTER_SHARED = 71;
    private static final int ID_FILTER_BLOCKED = 72;
    private static final int ID_FILTER_SHADOW = 73;
    private static final int ID_FILTER_SHARED_LIST = 74;

    private static final int REQUEST_SPY_EXPORT = 9001;
    private static final int REQUEST_SPY_IMPORT = 9002;

    private static final int ID_NUMBER_ROUNDING = 10;
    private static final int ID_SECONDS = 11;
    private static final int ID_RELATIVE_LAST_SEEN = 12;
    private static final int ID_HIDE_PHONE = 13;
    private static final int ID_SHOW_ID = 14;
    private static final int ID_YANDEX_MAPS = 15;
    private static final int ID_DOWNLOAD_BOOST = 16;
    private static final int ID_UPLOAD_BOOST = 17;

    private static final int ID_TITLE_TEXT = 20;
    private static final int ID_HIDE_STORIES = 21;
    private static final int ID_HIDE_FAB = 22;

    private static final int ID_HIDE_STICKER_TIME = 30;
    private static final int ID_NO_GREETING = 31;
    private static final int ID_HIDE_KEYBOARD = 32;
    private static final int ID_HIDE_SEND_AS = 33;
    private static final int ID_RECENT_STICKERS = 34;
    private static final int ID_ALWAYS_HD = 35;

    private final int page;

    public GlassgramSettingsActivity() {
        this(PAGE_MAIN);
    }

    public GlassgramSettingsActivity(int page) {
        this.page = page;
        GlassgramConfig.load();
    }

    @Override
    protected CharSequence getTitle() {
        switch (page) {
            case PAGE_GENERAL:
                return getString(R.string.GlassgramGeneral);
            case PAGE_APPEARANCE:
                return getString(R.string.GlassgramAppearance);
            case PAGE_CHATS:
                return getString(R.string.GlassgramChats);
            case PAGE_GHOST:
                return getString(R.string.GlassgramGhostMode);
            case PAGE_SPY:
                return "Spy";
            case PAGE_CUSTOMIZATION:
                return "Customization";
            case PAGE_FILTERS:
                return "Message Filters";
            default:
                return getString(R.string.GlassgramPreferences);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (page) {
            case PAGE_GENERAL:
                items.add(UItem.asHeader(getString(R.string.GlassgramGeneral)));
                items.add(UItem.asCheck(ID_NUMBER_ROUNDING, getString(R.string.GlassgramDisableNumberRounding)).setChecked(GlassgramConfig.disableNumberRounding));
                items.add(UItem.asCheck(ID_SECONDS, getString(R.string.GlassgramTimeWithSeconds)).setChecked(GlassgramConfig.formatTimeWithSeconds));
                items.add(UItem.asShadow(getString(R.string.GlassgramGeneralInfo)));
                items.add(UItem.asHeader(getString(R.string.GlassgramMaps)));
                items.add(UItem.asCheck(ID_YANDEX_MAPS, getString(R.string.GlassgramUseYandexMaps)).setChecked(GlassgramConfig.useYandexMaps));
                items.add(UItem.asShadow(getString(R.string.GlassgramUseYandexMapsInfo)));
                items.add(UItem.asHeader(getString(R.string.GlassgramDownloadBoost)));
                items.add(UItem.asButton(ID_DOWNLOAD_BOOST, getString(R.string.GlassgramDownloadBoost), boostNames()[GlassgramConfig.downloadBoost]));
                items.add(UItem.asCheck(ID_UPLOAD_BOOST, getString(R.string.GlassgramUploadBoost)).setChecked(GlassgramConfig.uploadBoost));
                items.add(UItem.asShadow(getString(R.string.GlassgramBoostInfo)));
                items.add(UItem.asHeader(getString(R.string.GlassgramProfile)));
                items.add(UItem.asCheck(ID_RELATIVE_LAST_SEEN, getString(R.string.GlassgramRelativeLastSeen)).setChecked(GlassgramConfig.relativeLastSeen));
                items.add(UItem.asCheck(ID_HIDE_PHONE, getString(R.string.GlassgramHidePhone)).setChecked(GlassgramConfig.hidePhoneNumber));
                items.add(UItem.asButton(ID_SHOW_ID, getString(R.string.GlassgramShowId), showIdNames()[GlassgramConfig.showId]));
                items.add(UItem.asShadow(getString(R.string.GlassgramProfileInfo)));
                break;
            case PAGE_APPEARANCE:
                items.add(UItem.asHeader(getString(R.string.GlassgramChatList)));
                items.add(UItem.asButton(ID_TITLE_TEXT, getString(R.string.GlassgramTitleText), GlassgramConfig.getTitle()));
                items.add(UItem.asCheck(ID_HIDE_STORIES, getString(R.string.GlassgramHideStories)).setChecked(GlassgramConfig.hideStories));
                items.add(UItem.asCheck(ID_HIDE_FAB, getString(R.string.GlassgramHideFloatingButton)).setChecked(GlassgramConfig.hideFloatingButton));
                items.add(UItem.asShadow(getString(R.string.GlassgramChatListInfo)));
                break;
            case PAGE_CHATS:
                items.add(UItem.asHeader(getString(R.string.GlassgramStickers)));
                items.add(UItem.asCheck(ID_HIDE_STICKER_TIME, getString(R.string.GlassgramHideStickerTime)).setChecked(GlassgramConfig.hideTimeOnStickers));
                items.add(UItem.asCheck(ID_RECENT_STICKERS, getString(R.string.GlassgramUnlimitedRecentStickers)).setChecked(GlassgramConfig.unlimitedRecentStickers));
                items.add(UItem.asCheck(ID_NO_GREETING, getString(R.string.GlassgramDisableGreeting)).setChecked(GlassgramConfig.disableGreetingSticker));
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.GlassgramChats)));
                items.add(UItem.asCheck(ID_HIDE_KEYBOARD, getString(R.string.GlassgramHideKeyboardOnScroll)).setChecked(GlassgramConfig.hideKeyboardOnScroll));
                items.add(UItem.asCheck(ID_HIDE_SEND_AS, getString(R.string.GlassgramHideSendAs)).setChecked(GlassgramConfig.hideSendAsButton));
                items.add(UItem.asShadow(getString(R.string.GlassgramHideSendAsInfo)));
                items.add(UItem.asHeader(getString(R.string.GlassgramPhotos)));
                items.add(UItem.asCheck(ID_ALWAYS_HD, getString(R.string.GlassgramAlwaysSendHD)).setChecked(org.telegram.messenger.SharedConfig.photoHighQualityDefault));
                items.add(UItem.asShadow(getString(R.string.GlassgramAlwaysSendHDInfo)));
                break;
            case PAGE_GHOST:
                items.add(UItem.asCheck(ID_GHOST_MODE, getString(R.string.GlassgramGhostMode)).setChecked(GlassgramConfig.ghostMode));
                items.add(UItem.asShadow(getString(R.string.GlassgramGhostModeInfo)));
                items.add(UItem.asHeader(getString(R.string.GlassgramGhostIncludes)));
                items.add(UItem.asCheck(ID_GHOST_READ, getString(R.string.GlassgramGhostNoRead)).setChecked(GlassgramConfig.ghostNoReadMessages));
                items.add(UItem.asCheck(ID_GHOST_STORIES, getString(R.string.GlassgramGhostNoStories)).setChecked(GlassgramConfig.ghostNoReadStories));
                items.add(UItem.asCheck(ID_GHOST_ONLINE, getString(R.string.GlassgramGhostNoOnline)).setChecked(GlassgramConfig.ghostNoOnline));
                items.add(UItem.asCheck(ID_GHOST_TYPING, getString(R.string.GlassgramGhostNoTyping)).setChecked(GlassgramConfig.ghostNoTyping));
                items.add(UItem.asCheck(ID_GHOST_OFFLINE, getString(R.string.GlassgramGhostAutoOffline)).setChecked(GlassgramConfig.ghostAutoOffline));
                items.add(UItem.asShadow(null));
                items.add(UItem.asCheck(ID_GHOST_READ_ON_INTERACT, getString(R.string.GlassgramGhostReadOnInteract)).setChecked(GlassgramConfig.ghostReadOnInteract));
                items.add(UItem.asShadow(getString(R.string.GlassgramGhostReadOnInteractInfo)));
                break;
            case PAGE_SPY:
                items.add(UItem.asHeader("Spy"));
                items.add(UItem.asCheck(ID_SPY_DELETED, "Save Deleted Messages").setChecked(GlassgramConfig.spySaveDeletedMessages));
                items.add(UItem.asCheck(ID_SPY_EDITS, "Save Edits History").setChecked(GlassgramConfig.spySaveEditsHistory));
                items.add(UItem.asCheck(ID_SPY_BOTS, "Save in Bot Dialogs").setChecked(GlassgramConfig.spySaveInBotDialogs));
                items.add(UItem.asCheck(ID_SPY_READ_DATE, "Save Read Date").setChecked(GlassgramConfig.spySaveReadDate));
                items.add(UItem.asCheck(ID_SPY_LAST_SEEN, "Save Last Seen Date").setChecked(GlassgramConfig.spySaveLastSeenDate));
                items.add(UItem.asCheck(ID_SPY_ATTACHMENTS, "Save Attachments").setChecked(GlassgramConfig.spySaveAttachments));
                items.add(UItem.asButton(ID_SPY_LIMIT, "Max folder size", new String[]{"300 MB","1 GB","2 GB","5 GB","16 GB","No limit"}[Math.max(0, Math.min(5, GlassgramConfig.spyMaxFolderSize))]));
                items.add(UItem.asButton(ID_SPY_EXPORT, "Export Database"));
                items.add(UItem.asButton(ID_SPY_IMPORT, "Import Database"));
                items.add(UItem.asButton(ID_SPY_CLEAR, "Clear"));
                items.add(UItem.asShadow("Local-only Spy storage: " + GlassgramSpyStorage.size() / 1024 + " KB"));
                break;
            case PAGE_CUSTOMIZATION:
                items.add(UItem.asHeader("Customization"));
                items.add(UItem.asCheck(ID_CUSTOM_TRANSLUCENT, "Translucent Deleted Messages").setChecked(GlassgramConfig.spyTranslucentDeleted));
                items.add(UItem.asCheck(ID_CUSTOM_TRASH, "Deleted Mark with trash icon").setChecked(GlassgramConfig.spyDeletedTrashMark));
                items.add(UItem.asCheck(ID_CUSTOM_ADS, "Disable Ads").setChecked(GlassgramConfig.spyDisableAds));
                items.add(UItem.asCheck(ID_CUSTOM_GHOST_STATUS, "Display Ghost Mode Status").setChecked(GlassgramConfig.spyDisplayGhostStatus));
                items.add(UItem.asShadow("Navigation and Pill Stack are kept compatible with the current Glassgram UI."));
                break;
            case PAGE_FILTERS:
                items.add(UItem.asHeader("Message Filters"));
                items.add(UItem.asCheck(ID_FILTER_ENABLE, "Enable Filters").setChecked(GlassgramConfig.spyEnableFilters));
                items.add(UItem.asCheck(ID_FILTER_SHARED, "Enable Shared Filters in Chats").setChecked(GlassgramConfig.spyEnableSharedFilters));
                items.add(UItem.asCheck(ID_FILTER_BLOCKED, "Hide from Blocked Users").setChecked(GlassgramConfig.spyHideBlockedUsers));
                items.add(UItem.asButton(ID_FILTER_SHARED_LIST, "Shared Filters"));
                items.add(UItem.asCheck(ID_FILTER_SHADOW, "Shadow Ban").setChecked(GlassgramConfig.spyShadowBan));
                items.add(UItem.asShadow("Filters are local-only and do not change Telegram server-side block status."));
                break;
            default:
                items.add(UItem.asHeader(getString(R.string.GlassgramCategories)));
                items.add(UItem.asButton(ID_GHOST, R.drawable.msg_secret, getString(R.string.GlassgramGhostMode), GlassgramConfig.ghostMode ? getString(R.string.GlassgramOn) : getString(R.string.GlassgramOff)));
                items.add(UItem.asButton(ID_GENERAL, R.drawable.msg_settings, getString(R.string.GlassgramGeneral)));
                items.add(UItem.asButton(ID_APPEARANCE, R.drawable.msg_palette, getString(R.string.GlassgramAppearance)));
                items.add(UItem.asButton(ID_CHATS, R.drawable.msg_discussion, getString(R.string.GlassgramChats)));
                items.add(UItem.asButton(ID_SPY, R.drawable.msg_secret, "Spy"));
                items.add(UItem.asButton(ID_CUSTOMIZATION, R.drawable.msg_palette, "Customization"));
                items.add(UItem.asButton(ID_FILTERS, R.drawable.msg_settings, "Message Filters"));
                items.add(UItem.asShadow(getString(R.string.GlassgramPreferencesInfo)));
                break;
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_GENERAL:
                presentFragment(new GlassgramSettingsActivity(PAGE_GENERAL));
                return;
            case ID_APPEARANCE:
                presentFragment(new GlassgramSettingsActivity(PAGE_APPEARANCE));
                return;
            case ID_CHATS:
                presentFragment(new GlassgramSettingsActivity(PAGE_CHATS));
                return;
            case ID_GHOST:
                presentFragment(new GlassgramSettingsActivity(PAGE_GHOST));
                return;
            case ID_SPY:
                presentFragment(new GlassgramSettingsActivity(PAGE_SPY));
                return;
            case ID_CUSTOMIZATION:
                presentFragment(new GlassgramSettingsActivity(PAGE_CUSTOMIZATION));
                return;
            case ID_FILTERS:
                presentFragment(new GlassgramSettingsActivity(PAGE_FILTERS));
                return;
            case ID_SPY_LIMIT:
                showChoice("Max folder size", new String[]{"300 MB","1 GB","2 GB","5 GB","16 GB","No limit"}, GlassgramConfig.spyMaxFolderSize, which -> {
                    GlassgramConfig.spyMaxFolderSize = which;
                    GlassgramConfig.putInt("spyMaxFolderSize", which);
                });
                return;
            case ID_SPY_EXPORT:
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE, "glassgram_spy.jsonl"), REQUEST_SPY_EXPORT);
                return;
            case ID_SPY_IMPORT:
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), REQUEST_SPY_IMPORT);
                return;
            case ID_SPY_CLEAR:
                new AlertDialog.Builder(getParentActivity()).setTitle("Clear Spy database?")
                        .setMessage("Only Glassgram Spy data will be deleted.")
                        .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                            GlassgramSpyStorage.clear();
                            if (listView != null) listView.adapter.update(true);
                        }).setNegativeButton(getString(R.string.Cancel), null).show();
                return;
            case ID_TITLE_TEXT:
                showTitleTextDialog();
                return;
            case ID_DOWNLOAD_BOOST:
                showChoice(getString(R.string.GlassgramDownloadBoost), boostNames(), GlassgramConfig.downloadBoost, which -> {
                    GlassgramConfig.downloadBoost = which;
                    GlassgramConfig.putInt("downloadBoost", which);
                });
                return;
            case ID_SHOW_ID:
                showChoice(getString(R.string.GlassgramShowId), showIdNames(), GlassgramConfig.showId, which -> {
                    GlassgramConfig.showId = which;
                    GlassgramConfig.putInt("showId", which);
                });
                return;
        }
        final boolean value;
        switch (item.id) {
            case ID_NUMBER_ROUNDING:
                value = GlassgramConfig.disableNumberRounding = !GlassgramConfig.disableNumberRounding;
                GlassgramConfig.putBoolean("disableNumberRounding", value);
                break;
            case ID_SECONDS:
                value = GlassgramConfig.formatTimeWithSeconds = !GlassgramConfig.formatTimeWithSeconds;
                GlassgramConfig.putBoolean("formatTimeWithSeconds", value);
                LocaleController.getInstance().recreateFormatters();
                break;
            case ID_RELATIVE_LAST_SEEN:
                value = GlassgramConfig.relativeLastSeen = !GlassgramConfig.relativeLastSeen;
                GlassgramConfig.putBoolean("relativeLastSeen", value);
                break;
            case ID_HIDE_PHONE:
                value = GlassgramConfig.hidePhoneNumber = !GlassgramConfig.hidePhoneNumber;
                GlassgramConfig.putBoolean("hidePhoneNumber", value);
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                break;
            case ID_GHOST_MODE:
                value = GlassgramConfig.ghostMode = !GlassgramConfig.ghostMode;
                GlassgramConfig.putBoolean("ghostMode", value);
                if (value && GlassgramConfig.ghostNoOnline) {
                    for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                        if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                            MessagesController.getInstance(a).glassgramGoOffline();
                        }
                    }
                }
                break;
            case ID_GHOST_READ:
                value = GlassgramConfig.ghostNoReadMessages = !GlassgramConfig.ghostNoReadMessages;
                GlassgramConfig.putBoolean("ghostNoReadMessages", value);
                break;
            case ID_GHOST_STORIES:
                value = GlassgramConfig.ghostNoReadStories = !GlassgramConfig.ghostNoReadStories;
                GlassgramConfig.putBoolean("ghostNoReadStories", value);
                break;
            case ID_GHOST_ONLINE:
                value = GlassgramConfig.ghostNoOnline = !GlassgramConfig.ghostNoOnline;
                GlassgramConfig.putBoolean("ghostNoOnline", value);
                break;
            case ID_GHOST_TYPING:
                value = GlassgramConfig.ghostNoTyping = !GlassgramConfig.ghostNoTyping;
                GlassgramConfig.putBoolean("ghostNoTyping", value);
                break;
            case ID_GHOST_OFFLINE:
                value = GlassgramConfig.ghostAutoOffline = !GlassgramConfig.ghostAutoOffline;
                GlassgramConfig.putBoolean("ghostAutoOffline", value);
                break;
            case ID_GHOST_READ_ON_INTERACT:
                value = GlassgramConfig.ghostReadOnInteract = !GlassgramConfig.ghostReadOnInteract;
                GlassgramConfig.putBoolean("ghostReadOnInteract", value);
                break;
            case ID_YANDEX_MAPS:
                value = GlassgramConfig.useYandexMaps = !GlassgramConfig.useYandexMaps;
                GlassgramConfig.putBoolean("useYandexMaps", value);
                break;
            case ID_UPLOAD_BOOST:
                value = GlassgramConfig.uploadBoost = !GlassgramConfig.uploadBoost;
                GlassgramConfig.putBoolean("uploadBoost", value);
                break;
            case ID_HIDE_STORIES:
                value = GlassgramConfig.hideStories = !GlassgramConfig.hideStories;
                GlassgramConfig.putBoolean("hideStories", value);
                getNotificationCenter().postNotificationName(NotificationCenter.storiesUpdated);
                break;
            case ID_HIDE_FAB:
                value = GlassgramConfig.hideFloatingButton = !GlassgramConfig.hideFloatingButton;
                GlassgramConfig.putBoolean("hideFloatingButton", value);
                break;
            case ID_HIDE_STICKER_TIME:
                value = GlassgramConfig.hideTimeOnStickers = !GlassgramConfig.hideTimeOnStickers;
                GlassgramConfig.putBoolean("hideTimeOnStickers", value);
                break;
            case ID_NO_GREETING:
                value = GlassgramConfig.disableGreetingSticker = !GlassgramConfig.disableGreetingSticker;
                GlassgramConfig.putBoolean("disableGreetingSticker", value);
                break;
            case ID_HIDE_KEYBOARD:
                value = GlassgramConfig.hideKeyboardOnScroll = !GlassgramConfig.hideKeyboardOnScroll;
                GlassgramConfig.putBoolean("hideKeyboardOnScroll", value);
                break;
            case ID_HIDE_SEND_AS:
                value = GlassgramConfig.hideSendAsButton = !GlassgramConfig.hideSendAsButton;
                GlassgramConfig.putBoolean("hideSendAsButton", value);
                break;
            case ID_RECENT_STICKERS:
                value = GlassgramConfig.unlimitedRecentStickers = !GlassgramConfig.unlimitedRecentStickers;
                GlassgramConfig.putBoolean("unlimitedRecentStickers", value);
                break;
            case ID_SPY_DELETED:
                value = GlassgramConfig.spySaveDeletedMessages = !GlassgramConfig.spySaveDeletedMessages;
                GlassgramConfig.putBoolean("spySaveDeletedMessages", value); break;
            case ID_SPY_EDITS:
                value = GlassgramConfig.spySaveEditsHistory = !GlassgramConfig.spySaveEditsHistory;
                GlassgramConfig.putBoolean("spySaveEditsHistory", value); break;
            case ID_SPY_BOTS:
                value = GlassgramConfig.spySaveInBotDialogs = !GlassgramConfig.spySaveInBotDialogs;
                GlassgramConfig.putBoolean("spySaveInBotDialogs", value); break;
            case ID_SPY_READ_DATE:
                value = GlassgramConfig.spySaveReadDate = !GlassgramConfig.spySaveReadDate;
                GlassgramConfig.putBoolean("spySaveReadDate", value); break;
            case ID_SPY_LAST_SEEN:
                value = GlassgramConfig.spySaveLastSeenDate = !GlassgramConfig.spySaveLastSeenDate;
                GlassgramConfig.putBoolean("spySaveLastSeenDate", value); break;
            case ID_SPY_ATTACHMENTS:
                value = GlassgramConfig.spySaveAttachments = !GlassgramConfig.spySaveAttachments;
                GlassgramConfig.putBoolean("spySaveAttachments", value); break;
            case ID_CUSTOM_TRANSLUCENT:
                value = GlassgramConfig.spyTranslucentDeleted = !GlassgramConfig.spyTranslucentDeleted;
                GlassgramConfig.putBoolean("spyTranslucentDeleted", value); break;
            case ID_CUSTOM_TRASH:
                value = GlassgramConfig.spyDeletedTrashMark = !GlassgramConfig.spyDeletedTrashMark;
                GlassgramConfig.putBoolean("spyDeletedTrashMark", value); break;
            case ID_CUSTOM_ADS:
                value = GlassgramConfig.spyDisableAds = !GlassgramConfig.spyDisableAds;
                GlassgramConfig.putBoolean("spyDisableAds", value); break;
            case ID_CUSTOM_GHOST_STATUS:
                value = GlassgramConfig.spyDisplayGhostStatus = !GlassgramConfig.spyDisplayGhostStatus;
                GlassgramConfig.putBoolean("spyDisplayGhostStatus", value); break;
            case ID_FILTER_ENABLE:
                value = GlassgramConfig.spyEnableFilters = !GlassgramConfig.spyEnableFilters;
                GlassgramConfig.putBoolean("spyEnableFilters", value); break;
            case ID_FILTER_SHARED:
                value = GlassgramConfig.spyEnableSharedFilters = !GlassgramConfig.spyEnableSharedFilters;
                GlassgramConfig.putBoolean("spyEnableSharedFilters", value); break;
            case ID_FILTER_BLOCKED:
                value = GlassgramConfig.spyHideBlockedUsers = !GlassgramConfig.spyHideBlockedUsers;
                GlassgramConfig.putBoolean("spyHideBlockedUsers", value); break;
            case ID_FILTER_SHADOW:
                value = GlassgramConfig.spyShadowBan = !GlassgramConfig.spyShadowBan;
                GlassgramConfig.putBoolean("spyShadowBan", value); break;
            case ID_ALWAYS_HD:
                // Telegram's own default for the HD toggle in the photo viewer
                value = org.telegram.messenger.SharedConfig.photoHighQualityDefault = !org.telegram.messenger.SharedConfig.photoHighQualityDefault;
                org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", android.app.Activity.MODE_PRIVATE)
                    .edit().putBoolean("photoHighQualityDefault", value).apply();
                break;
            default:
                return;
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        // Screens below show the change when they are rebuilt
        if (parentLayout != null) {
            parentLayout.rebuildAllFragmentViews(false, false);
        }
    }

    private static String[] boostNames() {
        return new String[] { getString(R.string.GlassgramBoostOff), getString(R.string.GlassgramBoostFast), getString(R.string.GlassgramBoostUltra) };
    }

    private static String[] showIdNames() {
        return new String[] { getString(R.string.GlassgramShowIdOff), "Telegram API", "Bot API" };
    }

    private void showChoice(CharSequence title, String[] names, int current, org.telegram.messenger.Utilities.Callback<Integer> onChoose) {
        if (getParentActivity() == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) {
            items[i] = (i == current ? "✓ " : "") + names[i];
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(title);
        builder.setItems(items, (dialog, which) -> {
            onChoose.run(which);
            if (listView != null) {
                listView.adapter.update(true);
            }
            if (parentLayout != null) {
                parentLayout.rebuildAllFragmentViews(false, false);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
    }

    private void showTitleTextDialog() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        editText.setHint(getString(R.string.AppName));
        editText.setText(GlassgramConfig.titleText);
        editText.setSingleLine(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        editText.setPadding(0, dp(8), 0, dp(8));

        final FrameLayout container = new FrameLayout(context);
        container.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 4, 24, 0));

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(getString(R.string.GlassgramTitleText));
        builder.setMessage(getString(R.string.GlassgramTitleTextInfo));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            GlassgramConfig.setTitleText(editText.getText().toString());
            if (listView != null) {
                listView.adapter.update(true);
            }
            if (parentLayout != null) {
                parentLayout.rebuildAllFragmentViews(false, false);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
        editText.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(editText), 200);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        boolean ok = false;
        if (requestCode == REQUEST_SPY_EXPORT) {
            ok = GlassgramSpyStorage.exportToUri(data.getData());
        } else if (requestCode == REQUEST_SPY_IMPORT) {
            ok = GlassgramSpyStorage.importFromUri(data.getData());
        }
        Toast.makeText(getParentActivity(), ok ? "Spy database completed" : "Spy database operation failed", Toast.LENGTH_SHORT).show();
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
