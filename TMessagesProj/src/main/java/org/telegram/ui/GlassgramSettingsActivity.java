package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.GlassgramSpyStorage;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.GlassgramWallpaperLayout;
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
    private static final int ID_SOURCE_CODE = 8;

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
    private static final int ID_CUSTOM_SELF_DESTRUCT = 64;
    private static final int ID_CONTENT_PROTECTION = 65;

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
    private boolean ghostExpanded;

    private GlassgramWallpaperLayout previewView;
    private ChatMessageCell previewCell;
    private MessageObject previewMessage;
    private View colorsView;
    private View aboutHeader;

    // 0 keeps the time color.
    private static final int[] MARK_COLORS = {0, 0xFFFF0000, 0xFFDC2B2B, 0xFFDB2777, 0xFFC026D3, 0xFF9333EA, 0xFF4F46E5, 0xFF2563EB};

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
                return getString(R.string.GlassgramSpy);
            case PAGE_CUSTOMIZATION:
                return getString(R.string.GlassgramCustomization);
            case PAGE_FILTERS:
                return getString(R.string.GlassgramMessageFilters);
            default:
                // The header below the action bar carries the name
                return "";
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
            case PAGE_GHOST: {
                int enabled = ghostOptionsEnabled();
                items.add(UItem.asHeader(getString(R.string.GlassgramGhostEssentials)));
                items.add(UItem.asExpandableSwitch(ID_GHOST_MODE, getString(R.string.GlassgramGhostMode), enabled + "/5")
                        .setChecked(GlassgramConfig.ghostMode)
                        .setCollapsed(!ghostExpanded)
                        .setClickCallback(v -> toggleGhostMode()));
                if (ghostExpanded) {
                    items.add(UItem.asRoundCheckbox(ID_GHOST_READ, getString(R.string.GlassgramGhostNoRead)).setChecked(GlassgramConfig.ghostNoReadMessages).setPad(1));
                    items.add(UItem.asRoundCheckbox(ID_GHOST_STORIES, getString(R.string.GlassgramGhostNoStories)).setChecked(GlassgramConfig.ghostNoReadStories).setPad(1));
                    items.add(UItem.asRoundCheckbox(ID_GHOST_ONLINE, getString(R.string.GlassgramGhostNoOnline)).setChecked(GlassgramConfig.ghostNoOnline).setPad(1));
                    items.add(UItem.asRoundCheckbox(ID_GHOST_TYPING, getString(R.string.GlassgramGhostNoTyping)).setChecked(GlassgramConfig.ghostNoTyping).setPad(1));
                    items.add(UItem.asRoundCheckbox(ID_GHOST_OFFLINE, getString(R.string.GlassgramGhostAutoOffline)).setChecked(GlassgramConfig.ghostAutoOffline).setPad(1));
                }
                items.add(UItem.asCheck(ID_GHOST_READ_ON_INTERACT, getString(R.string.GlassgramGhostReadOnInteract)).setChecked(GlassgramConfig.ghostReadOnInteract));
                items.add(UItem.asShadow(getString(R.string.GlassgramGhostInfo) + "\n\n" + getString(R.string.GlassgramGhostReadOnInteractInfo2)));
                break;
            }
            case PAGE_SPY:
                items.add(UItem.asHeader(getString(R.string.GlassgramSpyEssentials)));
                items.add(UItem.asCheck(ID_SPY_DELETED, getString(R.string.GlassgramSpySaveDeleted)).setChecked(GlassgramConfig.spySaveDeletedMessages));
                items.add(UItem.asCheck(ID_SPY_EDITS, getString(R.string.GlassgramSpySaveEdits)).setChecked(GlassgramConfig.spySaveEditsHistory));
                items.add(UItem.asShadow(getString(R.string.GlassgramSpyEssentialsInfo)));
                items.add(UItem.asCheck(ID_SPY_BOTS, getString(R.string.GlassgramSpySaveBots)).setChecked(GlassgramConfig.spySaveInBotDialogs));
                items.add(UItem.asShadow(getString(R.string.GlassgramSpySaveBotsInfo)));
                items.add(UItem.asCheck(ID_SPY_READ_DATE, getString(R.string.GlassgramSpySaveReadDate)).setChecked(GlassgramConfig.spySaveReadDate));
                items.add(UItem.asShadow(getString(R.string.GlassgramSpySaveReadDateInfo)));
                items.add(UItem.asCheck(ID_SPY_LAST_SEEN, getString(R.string.GlassgramSpySaveLastSeen)).setChecked(GlassgramConfig.spySaveLastSeenDate));
                items.add(UItem.asShadow(AndroidUtilities.replaceTags(getString(R.string.GlassgramSpySaveLastSeenInfo))));
                items.add(UItem.asButtonCheck(ID_SPY_ATTACHMENTS, getString(R.string.GlassgramSpySaveAttachments), getString(R.string.GlassgramSpySaveAttachmentsSub)).setChecked(GlassgramConfig.spySaveAttachments));
                items.add(UItem.asCheck(ID_CUSTOM_SELF_DESTRUCT, getString(R.string.GlassgramDisableSelfDestruct)).setChecked(GlassgramConfig.spyDisableSelfDestruct));
                items.add(UItem.asCheck(ID_CONTENT_PROTECTION, getString(R.string.GlassgramIgnoreContentProtection)).setChecked(GlassgramConfig.ignoreContentProtection));
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.GlassgramSpyMaxFolderSize)));
                items.add(UItem.asSlideView(folderSizeNames(), Math.max(0, Math.min(5, GlassgramConfig.spyMaxFolderSize)), which -> {
                    GlassgramConfig.spyMaxFolderSize = which;
                    GlassgramConfig.putInt("spyMaxFolderSize", which);
                }));
                items.add(UItem.asShadow(getString(R.string.GlassgramSpyMaxFolderSizeInfo)));
                items.add(UItem.asButton(ID_SPY_EXPORT, R.drawable.msg_unarchive, getString(R.string.GlassgramSpyExport)));
                items.add(UItem.asButton(ID_SPY_IMPORT, R.drawable.msg_archive, getString(R.string.GlassgramSpyImport)));
                items.add(UItem.asShadow(null));
                items.add(UItem.asButton(ID_SPY_CLEAR, R.drawable.msg_clearcache, getString(R.string.GlassgramSpyClear)).red());
                items.add(UItem.asShadow(LocaleController.formatString(R.string.GlassgramSpyStorageInfo, AndroidUtilities.formatFileSize(GlassgramSpyStorage.size()))));
                break;
            case PAGE_CUSTOMIZATION:
                items.add(UItem.asHeader(getString(R.string.GlassgramCustomization)));
                items.add(UItem.asCustom(getPreviewView()));
                items.add(UItem.asCheck(ID_CUSTOM_TRANSLUCENT, getString(R.string.GlassgramTranslucentDeleted)).setChecked(GlassgramConfig.spyTranslucentDeleted));
                items.add(UItem.asCheck(ID_CUSTOM_TRASH, getString(R.string.GlassgramDeletedMark)).setChecked(GlassgramConfig.spyDeletedTrashMark));
                if (GlassgramConfig.spyDeletedTrashMark) {
                    items.add(UItem.asCustom(getColorsView()));
                }
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.GlassgramUsefulFeatures)));
                items.add(UItem.asCheck(ID_CUSTOM_ADS, getString(R.string.GlassgramDisableAds)).setChecked(GlassgramConfig.spyDisableAds));
                items.add(UItem.asCheck(ID_CUSTOM_GHOST_STATUS, getString(R.string.GlassgramDisplayGhostStatus)).setChecked(GlassgramConfig.spyDisplayGhostStatus));
                items.add(UItem.asShadow(null));
                break;
            case PAGE_FILTERS:
                items.add(UItem.asHeader(getString(R.string.GlassgramFiltersEssentials)));
                items.add(UItem.asCheck(ID_FILTER_ENABLE, getString(R.string.GlassgramFiltersEnable)).setChecked(GlassgramConfig.spyEnableFilters));
                items.add(UItem.asCheck(ID_FILTER_SHARED, getString(R.string.GlassgramFiltersShared)).setChecked(GlassgramConfig.spyEnableSharedFilters));
                items.add(UItem.asCheck(ID_FILTER_BLOCKED, getString(R.string.GlassgramFiltersBlocked)).setChecked(GlassgramConfig.spyHideBlockedUsers));
                items.add(UItem.asShadow(null));
                items.add(UItem.asButton(ID_FILTER_SHARED_LIST, R.drawable.msg_settings, getString(R.string.GlassgramFiltersSharedList)));
                items.add(UItem.asCheck(ID_FILTER_SHADOW, getString(R.string.GlassgramFiltersShadowBan)).setChecked(GlassgramConfig.spyShadowBan));
                items.add(UItem.asShadow(getString(R.string.GlassgramFiltersInfo)));
                break;
            default:
                items.add(UItem.asCustomShadow(getAboutHeader()));
                items.add(UItem.asHeader(getString(R.string.GlassgramCategories)));
                items.add(UItem.asButton(ID_GENERAL, R.drawable.msg_settings, getString(R.string.GlassgramGeneral)));
                items.add(UItem.asButton(ID_APPEARANCE, R.drawable.msg_palette, getString(R.string.GlassgramAppearance)));
                items.add(UItem.asButton(ID_CHATS, R.drawable.msg_discussion, getString(R.string.GlassgramChats)));
                items.add(UItem.asButton(ID_CUSTOMIZATION, R.drawable.msg_colors, getString(R.string.GlassgramCustomization)));
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.GlassgramPrivacySection)));
                items.add(UItem.asButton(ID_GHOST, R.drawable.msg_stories_stealth, getString(R.string.GlassgramGhostMode)));
                items.add(UItem.asButton(ID_SPY, R.drawable.msg_stories_views, getString(R.string.GlassgramSpy)));
                items.add(UItem.asButton(ID_FILTERS, R.drawable.msg_block, getString(R.string.GlassgramMessageFilters)));
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.GlassgramLinks)));
                items.add(UItem.asButton(ID_SOURCE_CODE, R.drawable.msg_link, getString(R.string.GlassgramSourceCode), "GitHub"));
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
            case ID_SOURCE_CODE:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://github.com/shashachkaaa/Telegram");
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
                new AlertDialog.Builder(getParentActivity()).setTitle(getString(R.string.GlassgramSpyClearTitle))
                        .setMessage(getString(R.string.GlassgramSpyClearText))
                        .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                            GlassgramSpyStorage.clear();
                            if (listView != null) listView.adapter.update(true);
                        }).setNegativeButton(getString(R.string.Cancel), null).show();
                return;
            case ID_GHOST_MODE:
                ghostExpanded = !ghostExpanded;
                if (listView != null) listView.adapter.update(true);
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
            case ID_CUSTOM_SELF_DESTRUCT:
                value = GlassgramConfig.spyDisableSelfDestruct = !GlassgramConfig.spyDisableSelfDestruct;
                GlassgramConfig.putBoolean("spyDisableSelfDestruct", value); break;
            case ID_CONTENT_PROTECTION:
                value = GlassgramConfig.ignoreContentProtection = !GlassgramConfig.ignoreContentProtection;
                GlassgramConfig.putBoolean("ignoreContentProtection", value); break;
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
        if (view instanceof CheckBoxCell) {
            ((CheckBoxCell) view).setChecked(value, true);
        }
        if (page == PAGE_GHOST || item.id == ID_CUSTOM_TRASH) {
            // The Ghost Mode counter and the mark colors row follow these switches
            if (listView != null) listView.adapter.update(true);
        }
        if (item.id == ID_CUSTOM_TRANSLUCENT || item.id == ID_CUSTOM_TRASH) {
            updatePreview();
        }
        // Screens below show the change when they are rebuilt
        if (parentLayout != null) {
            parentLayout.rebuildAllFragmentViews(false, false);
        }
    }

    private static int ghostOptionsEnabled() {
        return (GlassgramConfig.ghostNoReadMessages ? 1 : 0) + (GlassgramConfig.ghostNoReadStories ? 1 : 0)
                + (GlassgramConfig.ghostNoOnline ? 1 : 0) + (GlassgramConfig.ghostNoTyping ? 1 : 0)
                + (GlassgramConfig.ghostAutoOffline ? 1 : 0);
    }

    private void toggleGhostMode() {
        boolean value = GlassgramConfig.ghostMode = !GlassgramConfig.ghostMode;
        GlassgramConfig.putBoolean("ghostMode", value);
        if (value && GlassgramConfig.ghostNoOnline) {
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                    MessagesController.getInstance(a).glassgramGoOffline();
                }
            }
        }
        if (listView != null) listView.adapter.update(true);
        if (parentLayout != null) {
            parentLayout.rebuildAllFragmentViews(false, false);
        }
    }

    private static String[] folderSizeNames() {
        return new String[]{"300 MB", "1 GB", "2 GB", "5 GB", "16 GB", getString(R.string.GlassgramSpyNoLimit)};
    }

    /** A deleted incoming message on the chat wallpaper, drawn with the current mark settings. */
    private View getPreviewView() {
        if (previewView != null) {
            return previewView;
        }
        Context context = getContext();
        previewView = new GlassgramWallpaperLayout(context) {
            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                return true;
            }
        };
        previewView.setPadding(0, dp(12), 0, dp(12));

        TLRPC.Message message = new TLRPC.TL_message();
        message.message = getString(R.string.GlassgramPreviewText);
        message.date = (int) (System.currentTimeMillis() / 1000) - 60;
        message.dialog_id = 1;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.from_id = new TLRPC.TL_peerUser();
        message.id = 1;
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = false;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = getUserConfig().getClientUserId();
        previewMessage = new MessageObject(currentAccount, message, true, false);
        previewMessage.eventId = 1;
        previewMessage.resetLayout();

        previewCell = new ChatMessageCell(context, currentAccount);
        previewCell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() {
        });
        previewCell.isChat = false;
        previewCell.glassgramForceDeleted = true;
        previewCell.setFullyDraw(true);
        previewCell.setMessageObject(previewMessage, null, false, false, false);
        previewView.addView(previewCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return previewView;
    }

    private void updatePreview() {
        if (previewCell == null || previewMessage == null) {
            return;
        }
        previewMessage.forceUpdate = true;
        previewCell.setMessageObject(previewMessage, null, false, false, false);
        previewMessage.forceUpdate = false;
        previewCell.requestLayout();
        previewCell.invalidate();
    }

    /** A row of colors for the trash mark; the first one keeps the time color. */
    private View getColorsView() {
        if (colorsView != null) {
            return colorsView;
        }
        colorsView = new View(getContext()) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
            }

            private float step() {
                return (getMeasuredWidth() - dp(32)) / (float) MARK_COLORS.length;
            }

            @Override
            protected void onDraw(Canvas canvas) {
                float step = step();
                float radius = Math.min(step / 2f - dp(4), dp(19));
                float cy = getMeasuredHeight() / 2f;
                for (int i = 0; i < MARK_COLORS.length; i++) {
                    float cx = dp(16) + step * i + step / 2f;
                    int color = MARK_COLORS[i] == 0 ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : MARK_COLORS[i];
                    boolean selected = GlassgramConfig.spyDeletedMarkColor == MARK_COLORS[i];
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(color);
                    canvas.drawCircle(cx, cy, selected ? radius - dp(6) : radius, paint);
                    if (selected) {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(dp(2.5f));
                        canvas.drawCircle(cx, cy, radius - dp(1.25f), paint);
                    }
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    int index = (int) ((event.getX() - dp(16)) / step());
                    if (index >= 0 && index < MARK_COLORS.length) {
                        GlassgramConfig.spyDeletedMarkColor = MARK_COLORS[index];
                        GlassgramConfig.putInt("spyDeletedMarkColor", MARK_COLORS[index]);
                        invalidate();
                        updatePreview();
                    }
                    return true;
                }
                return super.onTouchEvent(event);
            }
        };
        return colorsView;
    }

    /** App icon, name and version at the top of the main page. */
    private View getAboutHeader() {
        if (aboutHeader != null) {
            return aboutHeader;
        }
        Context context = getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(0, dp(8), 0, dp(20));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try {
            icon.setImageDrawable(context.getPackageManager().getApplicationIcon(context.getPackageName()));
        } catch (Exception e) {
            icon.setImageResource(R.mipmap.ic_launcher);
        }
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(26));
            }
        });
        layout.addView(icon, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL));

        TextView name = new TextView(context);
        name.setText(getString(R.string.AppName));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setGravity(Gravity.CENTER);
        layout.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView version = new TextView(context);
        version.setText(getVersionText(context));
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        version.setTypeface(AndroidUtilities.bold());
        version.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        version.setGravity(Gravity.CENTER);
        layout.addView(version, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

        aboutHeader = layout;
        return aboutHeader;
    }

    private static String getVersionText(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            return org.telegram.messenger.BuildVars.BUILD_VERSION_STRING;
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
        Toast.makeText(getParentActivity(), getString(ok ? R.string.GlassgramSpyDone : R.string.GlassgramSpyFailed), Toast.LENGTH_SHORT).show();
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
