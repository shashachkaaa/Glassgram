package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConversationStats;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.GlassgramPreferencesFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Locale;

public class GlassgramConversationStatsActivity extends GlassgramPreferencesFragment {
    private static final int[] PERIODS = {7, 30, 90};
    private static final int[] TITLES = {
            R.string.GlassgramConversationMessages, R.string.GlassgramConversationReplies,
            R.string.GlassgramConversationStarts, R.string.GlassgramConversationUnanswered,
            R.string.GlassgramConversationActivity, R.string.GlassgramConversationSummary
    };
    private static final int[] COLORS = {0xffbbf4ed, 0xffd7f5c3, 0xfff9d6e9, 0xffe8ddfa, 0xffffeab0, 0xffd5e9ff};

    private final TextView[] periodButtons = new TextView[PERIODS.length];
    private final TextView[] values = new TextView[TITLES.length];
    private LinearLayout screen;
    private LinearLayout cards;
    private TextView status;
    private int days = 30;
    private GlassgramConversationStats.Request request;

    public GlassgramConversationStatsActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.GlassgramConversationStats);
    }

    @Override
    protected boolean usePreferencesCards() {
        return false;
    }

    @Override
    public View createView(Context context) {
        screen = column(context);
        screen.setPadding(dp(16), dp(12), dp(16), dp(20));

        LinearLayout periods = new LinearLayout(context);
        screen.addView(periods, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (int i = 0; i < PERIODS.length; i++) {
            int period = PERIODS[i];
            TextView button = label(context, 14, Color.BLACK);
            button.setText(formatString(R.string.GlassgramConversationPeriod, period));
            button.setGravity(Gravity.CENTER);
            button.setTypeface(AndroidUtilities.bold());
            button.setMinHeight(dp(48));
            button.setPadding(dp(4), dp(8), dp(4), dp(8));
            periods.addView(button, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 3, 0, 3, 0));
            button.setOnClickListener(v -> {
                if (days != period) {
                    days = period;
                    load();
                }
            });
            periodButtons[i] = button;
        }

        status = label(context, 14, getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        screen.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 14, 4, 14));
        cards = column(context);
        screen.addView(cards, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (int i = 0; i < TITLES.length; i++) {
            addCard(context, i);
        }

        View view = super.createView(context);
        load();
        return view;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (screen != null) {
            items.add(UItem.asCustomShadow(screen, LayoutHelper.WRAP_CONTENT));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        // The period buttons handle their own clicks.
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        if (request != null) {
            request.cancel();
        }
        super.onFragmentDestroy();
    }

    private void load() {
        if (request != null) {
            request.cancel();
        }
        for (int i = 0; i < PERIODS.length; i++) {
            boolean selected = PERIODS[i] == days;
            TextView button = periodButtons[i];
            button.setSelected(selected);
            button.setTextColor(selected ? Color.BLACK : getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            button.setBackground(capsule(selected ? COLORS[0] : getThemedColor(Theme.key_windowBackgroundWhite), 22));
        }
        cards.setVisibility(View.GONE);
        status.setText(getString(R.string.GlassgramConversationLoading));
        request = GlassgramConversationStats.load(getCurrentAccount(), getUserConfig().getClientUserId(), days, this::showResult);
    }

    private void showResult(GlassgramConversationStats.Result stats) {
        if (stats == null) {
            status.setText(getString(R.string.GlassgramConversationError));
            return;
        }
        if (stats.chats == 0) {
            status.setText(getString(R.string.GlassgramConversationEmpty));
            return;
        }
        String coverage = formatString(R.string.GlassgramConversationCoverage, LocaleController.formatShortDate(stats.oldest));
        if (stats.limited) {
            coverage += "\n" + getString(R.string.GlassgramConversationLimit);
        }
        status.setText(coverage);
        values[0].setText(formatString(R.string.GlassgramConversationCounts, stats.sent, stats.received, stats.chats));
        values[1].setText(formatString(R.string.GlassgramConversationReplyTimes,
                replyTime(stats.yourMedianMinutes()), replyTime(stats.theirMedianMinutes())));
        values[2].setText(formatString(R.string.GlassgramConversationStartsCount, stats.startedByYou, stats.startedByOthers));
        values[3].setText(formatString(R.string.GlassgramConversationUnansweredCount, stats.outgoingUnanswered, stats.incomingUnanswered));
        int peak = 0;
        for (int hour = 1; hour < stats.hours.length; hour++) {
            if (stats.hours[hour] > stats.hours[peak]) {
                peak = hour;
            }
        }
        values[4].setText(stats.sent == 0 ? getString(R.string.GlassgramConversationNoOutgoing)
                : formatString(R.string.GlassgramConversationPeak, formatHour(peak), formatHour((peak + 1) % 24)));
        int summary = stats.startedByYou > stats.startedByOthers ? R.string.GlassgramConversationSummaryYou
                : stats.startedByYou < stats.startedByOthers ? R.string.GlassgramConversationSummaryOthers
                : stats.startedByYou > 0 ? R.string.GlassgramConversationSummaryEqual : R.string.GlassgramConversationSummaryNoStarts;
        values[5].setText(getString(summary));
        cards.setVisibility(View.VISIBLE);
    }

    private static CharSequence replyTime(int minutes) {
        if (minutes < 0) {
            return getString(R.string.GlassgramConversationNotEnough);
        }
        if (minutes == 0) {
            return getString(R.string.GlassgramConversationUnderMinute);
        }
        return formatString(R.string.GlassgramConversationMinutes, minutes);
    }

    private static String formatHour(int hour) {
        return String.format(Locale.getDefault(), "%02d:00", hour);
    }

    private void addCard(Context context, int index) {
        LinearLayout card = column(context);
        card.setBackground(capsule(COLORS[index], 24));
        card.setPadding(dp(18), dp(12), dp(18), dp(16));
        cards.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        RLottieImageView duck = new RLottieImageView(context);
        duck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        duck.setAnimation(index % 2 == 0 ? R.raw.utyan_empty : R.raw.utyan_empty2, 66, 66);
        duck.setProgress(.3f + .08f * index);
        card.addView(duck, LayoutHelper.createLinear(66, 66, Gravity.END));

        TextView title = label(context, 19, 0xff172127);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(TITLES[index]));
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView value = label(context, 15, 0xff26353d);
        values[index] = value;
        card.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
    }

    private static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static TextView label(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static GradientDrawable capsule(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(radius));
        drawable.setColor(color);
        return drawable;
    }
}
