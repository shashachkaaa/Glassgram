package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConversationStats;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;

public class GlassgramConversationStatsActivity extends BaseFragment {
    private static final int[] TITLES = {
            R.string.GlassgramConversationMessages, R.string.GlassgramConversationReplies,
            R.string.GlassgramConversationStarts, R.string.GlassgramConversationUnanswered,
            R.string.GlassgramConversationActivity, R.string.GlassgramConversationSummary
    };
    private static final int[] COLORS = {0xffbbf4ed, 0xffd7f5c3, 0xfff9d6e9, 0xffe8ddfa, 0xffffeab0, 0xffd5e9ff};

    private static final int TEXT = 0xff172127;
    private static final int SECONDARY_TEXT = 0xff344750;

    private static final int[] ACCENTS = {0xff006e68, 0xff426725, 0xff943765, 0xff63468c, 0xff815719, 0xff235f86};
    private static final int[] ANIMATIONS = {
            R.raw.utyan_empty, R.raw.utyan_call, R.raw.utyan_newborn,
            R.raw.utyan_empty2, R.raw.utyan_schedule, R.raw.utyan_saved_messages
    };

    private GlassgramConversationStats.Result stats;
    private int peakHour;
    private final RLottieImageView[] ducks = new RLottieImageView[TITLES.length];
    private RLottieImageView playingDuck;
    private final View[] indicators = new View[TITLES.length];
    private FrameLayout screen;
    private ViewPager pager;
    private LinearLayout pageIndicators;
    private TextView status;
    private LinearLayout statePanel;
    private ProgressBar loading;
    private String coverage;
    private int page;
    private GlassgramConversationStats.Request request;

    public GlassgramConversationStatsActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        actionBar.setAddToContainer(false);
        stopDuck();
        Arrays.fill(ducks, null);
        screen = new FrameLayout(context);
        screen.setBackgroundColor(COLORS[page]);
        fragmentView = screen;

        pager = new ViewPager(context);
        pager.setPageTransformer(false, (view, position) -> {
            float distance = Math.min(1f, Math.abs(position));
            view.setAlpha(1f - distance * .16f);
        });
        screen.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                page = position;
                updatePage();
            }
        });

        statePanel = new LinearLayout(context);
        statePanel.setOrientation(LinearLayout.VERTICAL);
        statePanel.setGravity(Gravity.CENTER);
        statePanel.setPadding(dp(22), dp(22), dp(22), dp(22));
        statePanel.setBackground(rounded(0xcfffffff, 26));
        screen.addView(statePanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER, 30, 58, 30, 48));
        loading = new ProgressBar(context);
        loading.setIndeterminateTintList(ColorStateList.valueOf(ACCENTS[0]));
        loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        statePanel.addView(loading, LayoutHelper.createLinear(28, 28));
        status = label(context, 16, TEXT);
        status.setText(getString(R.string.GlassgramConversationLoading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statePanel.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(TEXT);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(rounded(0x66ffffff, 24));
        back.setContentDescription(getString(R.string.AccDescrGoBack));
        back.setOnClickListener(v -> finishFragment());
        screen.addView(back, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.START, 12, 4, 12, 0));

        TextView heading = label(context, 15, TEXT);
        heading.setText(getString(R.string.GlassgramConversationStats));
        heading.setTypeface(AndroidUtilities.bold());
        ViewCompat.setAccessibilityHeading(heading, true);
        screen.addView(heading, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56,
                Gravity.TOP, 56, 0, 56, 0));

        pageIndicators = new LinearLayout(context);
        pageIndicators.setGravity(Gravity.CENTER);
        pageIndicators.setBackground(rounded(0x88ffffff, 22));
        pageIndicators.setVisibility(View.GONE);
        screen.addView(pageIndicators, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 42, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));
        for (int i = 0; i < indicators.length; i++) {
            final int index = i;
            FrameLayout target = new FrameLayout(context);
            target.setContentDescription(formatString(R.string.GlassgramConversationPage, i + 1, TITLES.length, getString(TITLES[i])));
            target.setOnClickListener(v -> pager.setCurrentItem(index, true));
            View indicator = new View(context);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            indicators[i] = indicator;
            target.addView(indicator, LayoutHelper.createFrame(8, 8, Gravity.CENTER));
            pageIndicators.addView(target, LayoutHelper.createLinear(40, 42));
        }

        if (request != null) {
            request.cancel();
        }
        request = GlassgramConversationStats.load(getCurrentAccount(), getUserConfig().getClientUserId(), this::showResult);
        return screen;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (screen != null) {
            screen.setPadding(left, top, right, bottom);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDuck();
    }

    @Override
    public void onPause() {
        stopDuck();
        super.onPause();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean isLightStatusBar() {
        return true;
    }

    @Override
    public int getNavigationBarColor() {
        return COLORS[page];
    }

    @Override
    public boolean canBeginSlide() {
        return false;
    }

    @Override
    public void onFragmentDestroy() {
        stopDuck();
        if (request != null) {
            request.cancel();
        }
        super.onFragmentDestroy();
    }

    private void showResult(GlassgramConversationStats.Result stats) {
        loading.setVisibility(View.GONE);
        if (stats == null) {
            status.setText(getString(R.string.GlassgramConversationError));
            return;
        }
        if (stats.chats == 0) {
            status.setText(getString(R.string.GlassgramConversationEmpty));
            return;
        }
        this.stats = stats;
        peakHour = 0;
        for (int hour = 1; hour < stats.hours.length; hour++) {
            if (stats.hours[hour] > stats.hours[peakHour]) {
                peakHour = hour;
            }
        }
        coverage = formatString(R.string.GlassgramConversationCoverage, LocaleController.formatShortDate(stats.oldest));
        statePanel.setVisibility(View.GONE);
        pageIndicators.setVisibility(View.VISIBLE);
        pager.setAdapter(new PagerAdapter() {
            @Override
            public int getCount() {
                return TITLES.length;
            }

            @Override
            public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
                return view == object;
            }

            @NonNull
            @Override
            public Object instantiateItem(@NonNull ViewGroup container, int position) {
                View slide = createSlide(container.getContext(), position);
                container.addView(slide);
                if (position == page) {
                    updateDuck();
                }
                return slide;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                if (ducks[position] == playingDuck) {
                    stopDuck();
                }
                ducks[position] = null;
                container.removeView((View) object);
            }
        });
        pager.setCurrentItem(page, false);
        updatePage();
    }

    private void updatePage() {
        updateDuck();
        screen.setBackgroundColor(COLORS[page]);
        for (int i = 0; i < indicators.length; i++) {
            indicators[i].setBackground(rounded(i == page ? ACCENTS[page] : 0x50344750, 8));
            indicators[i].animate().scaleX(i == page ? 2.5f : 1f).setDuration(200).start();
            ((View) indicators[i].getParent()).setSelected(i == page);
        }
    }

    private void updateDuck() {
        RLottieImageView selected = isPaused() ? null : ducks[page];
        if (selected == playingDuck) {
            return;
        }
        stopDuck();
        playingDuck = selected;
        if (playingDuck != null) {
            playingDuck.playAnimation();
        }
    }

    private void stopDuck() {
        if (playingDuck != null) {
            playingDuck.stopAnimation();
            playingDuck = null;
        }
    }

    private View createSlide(Context context, int index) {
        ScrollView slide = new ScrollView(context);
        slide.setFillViewport(true);
        slide.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {COLORS[index], androidx.core.graphics.ColorUtils.blendARGB(COLORS[index], 0xffffffff, .72f)}));
        slide.setPadding(dp(22), dp(65), dp(22), dp(58));
        slide.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(0, dp(10), 0, dp(12));
        slide.addView(column, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout illustration = new FrameLayout(context);
        illustration.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xcaffffff, 0x33ffffff}));
        ((GradientDrawable) illustration.getBackground()).setCornerRadius(dp(90));
        column.addView(illustration, LayoutHelper.createLinear(170, 152));

        RLottieImageView duck = new RLottieImageView(context);
        duck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        duck.setAutoRepeat(true);
        duck.setAnimation(ANIMATIONS[index], 148, 148);
        ducks[index] = duck;
        illustration.addView(duck, LayoutHelper.createFrame(148, 148, Gravity.CENTER));

        TextView title = label(context, 28, TEXT);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(TITLES[index]));
        ViewCompat.setAccessibilityHeading(title, true);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 14));

        switch (index) {
            case 0:
                comparison(column, index, number(stats.sent), R.string.GlassgramConversationSent,
                        number(stats.received), R.string.GlassgramConversationReceived);
                ratio(column, index, stats.sent, stats.received);
                note(column, formatString(R.string.GlassgramConversationChatTotal, number(stats.chats)));
                note(column, coverage);
                break;
            case 1:
                comparison(column, index, replyTime(stats.yourMedianMinutes()), R.string.GlassgramConversationYou,
                        replyTime(stats.theirMedianMinutes()), R.string.GlassgramConversationOthers);
                note(column, getString(R.string.GlassgramConversationReplyNote));
                break;
            case 2:
                comparison(column, index, number(stats.startedByYou), R.string.GlassgramConversationYou,
                        number(stats.startedByOthers), R.string.GlassgramConversationOthers);
                ratio(column, index, stats.startedByYou, stats.startedByOthers);
                note(column, getString(R.string.GlassgramConversationStartNote));
                break;
            case 3:
                comparison(column, index, number(stats.outgoingUnanswered), R.string.GlassgramConversationYourQuestions,
                        number(stats.incomingUnanswered), R.string.GlassgramConversationTheirQuestions);
                note(column, getString(R.string.GlassgramConversationQuestionNote));
                break;
            case 4:
                if (stats.sent == 0) {
                    note(column, getString(R.string.GlassgramConversationNoOutgoing));
                } else {
                    TextView peak = label(context, 25, ACCENTS[index]);
                    peak.setTypeface(AndroidUtilities.bold());
                    peak.setText(formatHour(peakHour) + " – " + formatHour((peakHour + 1) % 24));
                    column.addView(peak, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                    histogram(column, index);
                }
                break;
            case 5:
                int summary = stats.startedByYou > stats.startedByOthers ? R.string.GlassgramConversationSummaryYou
                        : stats.startedByYou < stats.startedByOthers ? R.string.GlassgramConversationSummaryOthers
                        : stats.startedByYou > 0 ? R.string.GlassgramConversationSummaryEqual : R.string.GlassgramConversationSummaryNoStarts;
                TextView takeaway = label(context, 18, TEXT);
                takeaway.setText(getString(summary));
                takeaway.setBackground(rounded(0xd9ffffff, 20));
                takeaway.setPadding(dp(18), dp(14), dp(18), dp(14));
                column.addView(takeaway, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                if (stats.sent > 0) {
                    note(column, formatString(R.string.GlassgramConversationPeak, formatHour(peakHour), formatHour((peakHour + 1) % 24)));
                }
                break;
        }
        return slide;
    }

    private void comparison(LinearLayout column, int index, CharSequence first, int firstLabel,
                            CharSequence second, int secondLabel) {
        LinearLayout row = new LinearLayout(column.getContext());
        column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addMetric(row, first, firstLabel, ACCENTS[index]);
        addMetric(row, second, secondLabel, TEXT);
    }

    private static void addMetric(LinearLayout row, CharSequence number, int caption, int color) {
        Context context = row.getContext();
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setMinimumHeight(dp(100));
        tile.setPadding(dp(6), dp(12), dp(6), dp(12));
        tile.setBackground(rounded(0xd9ffffff, 20));
        row.addView(tile, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 4, 0, 4, 0));
        TextView value = label(context, number.length() > 12 ? 18 : 25, color);
        value.setTypeface(AndroidUtilities.bold());
        value.setText(number);
        tile.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView name = label(context, 13, SECONDARY_TEXT);
        name.setText(getString(caption));
        tile.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
    }

    private static void ratio(LinearLayout column, int index, int first, int second) {
        long total = (long) first + second;
        if (total == 0) {
            return;
        }
        LinearLayout line = new LinearLayout(column.getContext());
        line.setBackground(rounded(0x66ffffff, 8));
        line.setClipToOutline(true);
        column.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 8, 12, 8, 0));
        for (int i = 0; i < 2; i++) {
            int count = i == 0 ? first : second;
            if (count != 0) {
                View part = new View(line.getContext());
                part.setBackgroundColor(i == 0 ? ACCENTS[index] : 0x66344750);
                line.addView(part, new LinearLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT, count / (float) total));
            }
        }
    }

    private void histogram(LinearLayout column, int index) {
        Context context = column.getContext();
        View histogram = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float step = (getWidth() - dp(24)) / 24f;
                float bottom = getHeight() - dp(12);
                float availableHeight = bottom - dp(12);
                for (int hour = 0; hour < stats.hours.length; hour++) {
                    float left = dp(12) + hour * step;
                    float height = Math.max(dp(2), availableHeight * stats.hours[hour] / stats.hours[peakHour]);
                    paint.setColor(hour == peakHour ? ACCENTS[index] : 0x60815719);
                    canvas.drawRoundRect(left, bottom - height, left + step * .68f, bottom, dp(2), dp(2), paint);
                }
            }
        };
        histogram.setBackground(rounded(0xd9ffffff, 20));
        histogram.setContentDescription(formatString(R.string.GlassgramConversationPeak, formatHour(peakHour), formatHour((peakHour + 1) % 24)));
        column.addView(histogram, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 90, 0, 12, 0, 0));
        LinearLayout axis = new LinearLayout(context);
        axis.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        column.addView(axis, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 5, 12, 0));
        for (int hour = 0; hour <= 24; hour += 6) {
            TextView tick = label(context, 11, SECONDARY_TEXT);
            tick.setText(formatHour(hour));
            tick.setGravity(hour == 0 ? Gravity.LEFT : hour == 24 ? Gravity.RIGHT : Gravity.CENTER);
            axis.addView(tick, new LinearLayout.LayoutParams(0, LayoutHelper.WRAP_CONTENT, hour == 0 || hour == 24 ? .5f : 1f));
        }
    }

    private static void note(LinearLayout column, CharSequence text) {
        TextView note = label(column.getContext(), 14, SECONDARY_TEXT);
        note.setText(text);
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 12, 6, 0));
    }

    private static String number(int value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
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

    private static TextView label(Context context, int size, int color) {
        TextView view = new TextView(context);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }
}
