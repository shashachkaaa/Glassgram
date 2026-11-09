package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

/**
 * glassGram: what a plugin can do, as its code reads before it is installed (_glassgram_analysis.py),
 * shown as exteraGram shows it: in the install sheet a summary (the worst found) with a chip for each
 * of the device, the app and the account, and a report with every finding, each with the names it
 * uses (requests, hooked methods, hosts) and the lines of code where it does it.
 */
public final class GlassgramPluginAnalysis {

    static final int NONE = 0, INFO = 1, WARNING = 2, DANGER = 3;
    private static final String[] GROUPS = {"device", "app", "account"};

    static final class Item {
        String id, group;
        int level;
        final ArrayList<String> names = new ArrayList<>();
        final ArrayList<String> notes = new ArrayList<>();
        final ArrayList<int[]> lineNumbers = new ArrayList<>();
        final ArrayList<String> lines = new ArrayList<>();
        int more;
    }

    int level;
    final int[] groupLevels = new int[GROUPS.length];
    final ArrayList<Item> items = new ArrayList<>();

    private GlassgramPluginAnalysis() {
    }

    /** The analysis the engine gave (JSON), or null without one. */
    static GlassgramPluginAnalysis parse(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final GlassgramPluginAnalysis analysis = new GlassgramPluginAnalysis();
            analysis.level = levelOf(root.optString("level"));
            final JSONArray groups = root.optJSONArray("groups");
            for (int i = 0; groups != null && i < groups.length(); i++) {
                final JSONArray pair = groups.getJSONArray(i);
                final int index = indexOf(pair.getString(0));
                if (index >= 0) {
                    analysis.groupLevels[index] = levelOf(pair.getString(1));
                }
            }
            final JSONArray list = root.optJSONArray("items");
            for (int i = 0; list != null && i < list.length(); i++) {
                final JSONObject object = list.getJSONObject(i);
                final Item item = new Item();
                item.id = object.optString("id");
                item.group = object.optString("group");
                item.level = levelOf(object.optString("level"));
                item.more = object.optInt("more");
                final JSONArray names = object.optJSONArray("items");
                for (int j = 0; names != null && j < names.length(); j++) {
                    item.names.add(names.getString(j));
                }
                final JSONArray notes = object.optJSONArray("notes");
                for (int j = 0; notes != null && j < notes.length(); j++) {
                    item.notes.add(notes.getString(j));
                }
                final JSONArray lines = object.optJSONArray("lines");
                for (int j = 0; lines != null && j < lines.length(); j++) {
                    final JSONArray line = lines.getJSONArray(j);
                    item.lineNumbers.add(new int[]{line.getInt(0)});
                    item.lines.add(line.getString(1));
                }
                if (title(item.id) != 0) {
                    analysis.items.add(item);
                }
            }
            return analysis;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    boolean isDanger() {
        return level >= DANGER;
    }

    private static int levelOf(String level) {
        switch (level) {
            case "danger": return DANGER;
            case "warning": return WARNING;
            case "info": return INFO;
            default: return NONE;
        }
    }

    private static int indexOf(String group) {
        for (int i = 0; i < GROUPS.length; i++) {
            if (GROUPS[i].equals(group)) {
                return i;
            }
        }
        return -1;
    }

    /* Words */

    private static int title(String id) {
        switch (id) {
            case "network": return R.string.GlassgramPluginKindNetwork;
            case "files": return R.string.GlassgramPluginKindFiles;
            case "files_delete": return R.string.GlassgramPluginKindFilesDelete;
            case "shell": return R.string.GlassgramPluginKindShell;
            case "native": return R.string.GlassgramPluginKindNative;
            case "other_apps": return R.string.GlassgramPluginKindOtherApps;
            case "clipboard": return R.string.GlassgramPluginKindClipboard;
            case "device_data": return R.string.GlassgramPluginKindDeviceData;
            case "restart": return R.string.GlassgramPluginKindRestart;
            case "settings": return R.string.GlassgramPluginKindSettings;
            case "hooks": return R.string.GlassgramPluginKindHooks;
            case "request_hooks": return R.string.GlassgramPluginKindRequestHooks;
            case "message_hooks": return R.string.GlassgramPluginKindMessageHooks;
            case "plugins": return R.string.GlassgramPluginKindPlugins;
            case "dynamic_code": return R.string.GlassgramPluginKindDynamicCode;
            case "blob": return R.string.GlassgramPluginKindBlob;
            case "hidden_code": return R.string.GlassgramPluginKindHiddenCode;
            case "remote_code": return R.string.GlassgramPluginKindRemoteCode;
            case "bytecode": return R.string.GlassgramPluginKindBytecode;
            case "known": return R.string.GlassgramPluginKindKnown;
            case "anti_review": return R.string.GlassgramPluginKindAntiReview;
            case "requests": return R.string.GlassgramPluginKindRequests;
            case "messages": return R.string.GlassgramPluginKindMessages;
            case "database": return R.string.GlassgramPluginKindDatabase;
            case "stars": return R.string.GlassgramPluginKindStars;
            case "gifts": return R.string.GlassgramPluginKindGifts;
            case "chat_admin": return R.string.GlassgramPluginKindChatAdmin;
            case "security": return R.string.GlassgramPluginKindSecurity;
            case "session": return R.string.GlassgramPluginKindSession;
            case "syntax": return R.string.GlassgramPluginKindSyntax;
            default: return 0;
        }
    }

    private static int subtitle(String id) {
        switch (id) {
            case "network": return R.string.GlassgramPluginKindNetworkInfo;
            case "files": return R.string.GlassgramPluginKindFilesInfo;
            case "files_delete": return R.string.GlassgramPluginKindFilesDeleteInfo;
            case "shell": return R.string.GlassgramPluginKindShellInfo;
            case "native": return R.string.GlassgramPluginKindNativeInfo;
            case "other_apps": return R.string.GlassgramPluginKindOtherAppsInfo;
            case "clipboard": return R.string.GlassgramPluginKindClipboardInfo;
            case "device_data": return R.string.GlassgramPluginKindDeviceDataInfo;
            case "restart": return R.string.GlassgramPluginKindRestartInfo;
            case "settings": return R.string.GlassgramPluginKindSettingsInfo;
            case "hooks": return R.string.GlassgramPluginKindHooksInfo;
            case "request_hooks": return R.string.GlassgramPluginKindRequestHooksInfo;
            case "message_hooks": return R.string.GlassgramPluginKindMessageHooksInfo;
            case "plugins": return R.string.GlassgramPluginKindPluginsInfo;
            case "dynamic_code": return R.string.GlassgramPluginKindDynamicCodeInfo;
            case "blob": return R.string.GlassgramPluginKindBlobInfo;
            case "hidden_code": return R.string.GlassgramPluginKindHiddenCodeInfo;
            case "remote_code": return R.string.GlassgramPluginKindRemoteCodeInfo;
            case "bytecode": return R.string.GlassgramPluginKindBytecodeInfo;
            case "known": return R.string.GlassgramPluginKindKnownInfo;
            case "anti_review": return R.string.GlassgramPluginKindAntiReviewInfo;
            case "requests": return R.string.GlassgramPluginKindRequestsInfo;
            case "messages": return R.string.GlassgramPluginKindMessagesInfo;
            case "database": return R.string.GlassgramPluginKindDatabaseInfo;
            case "stars": return R.string.GlassgramPluginKindStarsInfo;
            case "gifts": return R.string.GlassgramPluginKindGiftsInfo;
            case "chat_admin": return R.string.GlassgramPluginKindChatAdminInfo;
            case "security": return R.string.GlassgramPluginKindSecurityInfo;
            case "session": return R.string.GlassgramPluginKindSessionInfo;
            case "syntax": return R.string.GlassgramPluginKindSyntaxInfo;
            default: return 0;
        }
    }

    private static String note(String code) {
        if ("hooks_unresolved".equals(code)) {
            return getString(R.string.GlassgramPluginNoteHooksUnresolved);
        }
        return null;
    }

    private static int groupName(int index) {
        return index == 0 ? R.string.GlassgramPluginGroupDevice : index == 1 ? R.string.GlassgramPluginGroupApp : R.string.GlassgramPluginGroupAccount;
    }

    private static int groupIcon(String group) {
        switch (group) {
            case "device": return R.drawable.msg2_devices;
            case "account": return R.drawable.msg_openprofile;
            default: return R.drawable.device_phone_android;
        }
    }

    private static int color(int level, Theme.ResourcesProvider resourcesProvider) {
        switch (level) {
            case DANGER: return Theme.getColor(Theme.key_text_RedBold, resourcesProvider);
            case WARNING: return Theme.getColor(Theme.key_color_yellow, resourcesProvider);
            case NONE: return Theme.getColor(Theme.key_color_green, resourcesProvider);
            default: return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        }
    }

    private static int text(Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
    }

    private static int gray(Theme.ResourcesProvider resourcesProvider) {
        return Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
    }

    /** A card's background: the sheet's, a little lifted. */
    private static int card(Theme.ResourcesProvider resourcesProvider) {
        return Theme.multAlpha(text(resourcesProvider), 0.05f);
    }

    /* The install sheet */

    static View createTrustedBadge(Context context, Theme.ResourcesProvider resourcesProvider) {
        final int green = Theme.getColor(Theme.key_color_green, resourcesProvider);
        final LinearLayout badge = new LinearLayout(context);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        badge.setPadding(dp(12), dp(6), dp(16), dp(6));
        badge.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(green, 0.15f)));
        final ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_policy);
        icon.setColorFilter(new PorterDuffColorFilter(green, PorterDuff.Mode.SRC_IN));
        badge.addView(icon, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
        final TextView label = new TextView(context);
        label.setText(getString(R.string.GlassgramPluginTrusted));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(green);
        badge.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        return badge;
    }

    static View createDescriptionCard(Context context, String description, Theme.ResourcesProvider resourcesProvider) {
        final TextView text = new TextView(context);
        text.setText(AndroidUtilities.replaceTags(description));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(text(resourcesProvider));
        text.setLineSpacing(dp(2), 1f);
        text.setPadding(dp(16), dp(14), dp(16), dp(14));
        text.setBackground(Theme.createRoundRectDrawable(dp(16), card(resourcesProvider)));
        return text;
    }

    /** The worst found, and a chip for each part (the device, the app, the account); a tap opens the report. */
    View createSummaryCard(Context context, Runnable openReport, Theme.ResourcesProvider resourcesProvider) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(dp(16), card(resourcesProvider)));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(14), dp(10), dp(14));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 16, 16));
        final int summaryColor = color(level >= WARNING ? level : NONE, resourcesProvider);
        row.addView(iconBox(context, level >= WARNING ? R.drawable.msg_report : R.drawable.msg_policy, summaryColor),
                LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(context, getString(level >= DANGER ? R.string.GlassgramPluginSummaryDanger
                : level >= WARNING ? R.string.GlassgramPluginSummaryWarning : R.string.GlassgramPluginSummaryNone), 17, true,
                level >= DANGER ? summaryColor : text(resourcesProvider)));
        texts.addView(label(context, getString(level >= DANGER ? R.string.GlassgramPluginSummaryDangerInfo
                : level >= WARNING ? R.string.GlassgramPluginSummaryWarningInfo : R.string.GlassgramPluginSummaryNoneInfo), 14, false, gray(resourcesProvider)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        final ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(new PorterDuffColorFilter(text(resourcesProvider), PorterDuff.Mode.SRC_IN));
        if (LocaleController.isRTL) {
            arrow.setScaleX(-1);
        }
        row.addView(arrow, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> openReport.run());
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        boolean anyGroup = false;
        final LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(14), dp(12), dp(14), dp(14));
        for (int i = 0; i < GROUPS.length; i++) {
            if (groupLevels[i] == NONE) {
                continue;
            }
            anyGroup = true;
            final int levelColor = groupLevels[i] >= WARNING ? color(groupLevels[i], resourcesProvider) : text(resourcesProvider);
            final LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), 0, dp(12), 0);
            chip.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(levelColor, groupLevels[i] >= WARNING ? 0.15f : 0.08f)));
            final ImageView icon = new ImageView(context);
            icon.setImageResource(groupIcon(GROUPS[i]));
            icon.setColorFilter(new PorterDuffColorFilter(levelColor, PorterDuff.Mode.SRC_IN));
            chip.addView(icon, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            chip.addView(label(context, getString(groupName(i)), 15, false, levelColor));
            chip.setOnClickListener(v -> openReport.run());
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        }
        if (anyGroup) {
            final View divider = new View(context);
            divider.setBackgroundColor(Theme.multAlpha(text(resourcesProvider), 0.08f));
            card.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 14, 0, 14, 0));
            final android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(chips);
            card.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return card;
    }

    /* The report */

    void showReport(BaseFragment fragment, String pluginName) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(activity, false, resourcesProvider);
        sheet.fixNavigationBar();

        final LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(8), dp(16), dp(16));

        final FrameLayout top = new FrameLayout(activity);
        final LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.addView(label(activity, getString(R.string.GlassgramPluginReport), 20, true, text(resourcesProvider)));
        if (!TextUtils.isEmpty(pluginName)) {
            heading.addView(label(activity, pluginName, 14, false, gray(resourcesProvider)), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        top.addView(heading, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, LocaleController.isRTL ? 52 : 0, 0, LocaleController.isRTL ? 0 : 52, 0));
        final ImageView back = new ImageView(activity);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.msg_close);
        back.setColorFilter(new PorterDuffColorFilter(text(resourcesProvider), PorterDuff.Mode.SRC_IN));
        back.setBackground(Theme.createCircleDrawable(dp(44), Theme.multAlpha(text(resourcesProvider), 0.06f)));
        back.setContentDescription(getString(R.string.Close));
        back.setOnClickListener(v -> sheet.dismiss());
        top.addView(back, LayoutHelper.createFrame(44, 44, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
        layout.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));

        final int[] sections = {DANGER, WARNING, INFO};
        final int[] sectionNames = {R.string.GlassgramPluginReportDanger, R.string.GlassgramPluginReportWarning, R.string.GlassgramPluginReportOther};
        for (int s = 0; s < sections.length; s++) {
            LinearLayout sectionCard = null;
            for (Item item : items) {
                if (item.level != sections[s]) {
                    continue;
                }
                if (sectionCard == null) {
                    final TextView header = label(activity, getString(sectionNames[s]), 16, true,
                            sections[s] == DANGER ? color(DANGER, resourcesProvider) : text(resourcesProvider));
                    layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 16, 4, 10));
                    sectionCard = new LinearLayout(activity);
                    sectionCard.setOrientation(LinearLayout.VERTICAL);
                    sectionCard.setBackground(Theme.createRoundRectDrawable(dp(16), card(resourcesProvider)));
                    layout.addView(sectionCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                } else {
                    final View divider = new View(activity);
                    divider.setBackgroundColor(Theme.multAlpha(text(resourcesProvider), 0.08f));
                    sectionCard.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, LocaleController.isRTL ? 14 : 72, 0, LocaleController.isRTL ? 72 : 14, 0));
                }
                sectionCard.addView(createItem(activity, item, resourcesProvider), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        final TextView footer = label(activity, getString(R.string.GlassgramPluginReportFooter), 14, false, gray(resourcesProvider));
        layout.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 18, 4, 4));

        final ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(layout, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        sheet.setCustomView(scroll);
        // over the install sheet, which stays under it (showDialog would close it)
        sheet.show();
    }

    /** A finding: its icon, what it is, and opened, the names it uses, notes and its lines of code. */
    private static View createItem(Context context, Item item, Theme.ResourcesProvider resourcesProvider) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 16, 16));
        final int levelColor = item.level >= WARNING ? color(item.level, resourcesProvider) : text(resourcesProvider);
        row.addView(iconBox(context, groupIcon(item.group), levelColor), LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(label(context, getString(title(item.id)), 17, true, text(resourcesProvider)));
        texts.addView(label(context, getString(subtitle(item.id)), 14, false, gray(resourcesProvider)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        final LinearLayout details = createDetails(context, item, resourcesProvider);
        if (details != null) {
            final ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.arrow_more);
            arrow.setColorFilter(new PorterDuffColorFilter(text(resourcesProvider), PorterDuff.Mode.SRC_IN));
            row.addView(arrow, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));
            details.setVisibility(View.GONE);
            row.setOnClickListener(v -> {
                final boolean open = details.getVisibility() != View.VISIBLE;
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                arrow.animate().rotation(open ? 180 : 0).setDuration(200).start();
            });
        }
        box.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (details != null) {
            box.addView(details, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return box;
    }

    private static LinearLayout createDetails(Context context, Item item, Theme.ResourcesProvider resourcesProvider) {
        if (item.names.isEmpty() && item.notes.isEmpty() && item.lines.isEmpty()) {
            return null;
        }
        final LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(14), 0, dp(14), dp(14));
        final int boxColor = Theme.multAlpha(text(resourcesProvider), 0.05f);

        if (!item.names.isEmpty() || !item.notes.isEmpty()) {
            final LinearLayout names = new LinearLayout(context);
            names.setOrientation(LinearLayout.VERTICAL);
            names.setPadding(dp(14), dp(10), dp(14), dp(10));
            names.setBackground(Theme.createRoundRectDrawable(dp(12), boxColor));
            for (String name : item.names) {
                final TextView line = label(context, name, 15, false, text(resourcesProvider));
                line.setTypeface(Typeface.MONOSPACE);
                names.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 3));
            }
            for (String code : item.notes) {
                final String text = note(code);
                if (text != null) {
                    names.addView(label(context, text, 15, false, text(resourcesProvider)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 2));
                }
            }
            details.addView(names, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        }

        if (!item.lines.isEmpty()) {
            final LinearLayout code = new LinearLayout(context);
            code.setOrientation(LinearLayout.VERTICAL);
            code.setPadding(dp(12), dp(10), dp(14), dp(10));
            code.setBackground(Theme.createRoundRectDrawable(dp(12), boxColor));
            for (int i = 0; i < item.lines.size(); i++) {
                final LinearLayout line = new LinearLayout(context);
                line.setOrientation(LinearLayout.HORIZONTAL);
                final TextView number = label(context, String.format(Locale.US, "%d", item.lineNumbers.get(i)[0]), 14, false, gray(resourcesProvider));
                number.setTypeface(Typeface.MONOSPACE);
                number.setGravity(Gravity.END);
                line.addView(number, LayoutHelper.createLinear(44, LayoutHelper.WRAP_CONTENT, 0, 0, 10, 0));
                final TextView text = label(context, item.lines.get(i), 14, false, text(resourcesProvider));
                text.setTypeface(Typeface.MONOSPACE);
                text.setMaxLines(4);
                text.setEllipsize(TruncateAt.END);
                line.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                code.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 3));
            }
            if (item.more > 0) {
                code.addView(label(context, formatString(R.string.GlassgramPluginReportMoreLines, item.more), 14, false, gray(resourcesProvider)),
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 54, 4, 0, 0));
            }
            details.addView(code, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        return details;
    }

    private static View iconBox(Context context, int icon, int color) {
        final ImageView box = new ImageView(context);
        box.setScaleType(ImageView.ScaleType.CENTER);
        box.setImageResource(icon);
        box.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        box.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.multAlpha(color, 0.15f)));
        return box;
    }

    private static TextView label(Context context, CharSequence value, int size, boolean bold, int color) {
        final TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(AndroidUtilities.bold());
        }
        view.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        return view;
    }
}
