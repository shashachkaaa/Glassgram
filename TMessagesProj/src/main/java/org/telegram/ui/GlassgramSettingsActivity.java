package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
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

    private static final int ID_GENERAL = 1;
    private static final int ID_APPEARANCE = 2;
    private static final int ID_CHATS = 3;

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
            default:
                items.add(UItem.asHeader(getString(R.string.GlassgramCategories)));
                items.add(UItem.asButton(ID_GENERAL, R.drawable.msg_settings, getString(R.string.GlassgramGeneral)));
                items.add(UItem.asButton(ID_APPEARANCE, R.drawable.msg_palette, getString(R.string.GlassgramAppearance)));
                items.add(UItem.asButton(ID_CHATS, R.drawable.msg_discussion, getString(R.string.GlassgramChats)));
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
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
