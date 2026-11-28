package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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

    private final CharSequence[] values = new CharSequence[TITLES.length];
    private final View[] indicators = new View[TITLES.length];
    private FrameLayout screen;
    private ViewPager pager;
    private LinearLayout pageIndicators;
    private TextView status;
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
        screen = new FrameLayout(context);
        screen.setBackgroundColor(COLORS[page]);
        fragmentView = screen;
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            var bars = AndroidUtilities.getDefaultWindowInsets(insets, false);
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        pager = new ViewPager(context);
        screen.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                page = position;
                updatePage();
            }
        });

        status = label(context, 16, TEXT);
        status.setText(getString(R.string.GlassgramConversationLoading));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        screen.addView(status, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER, 28, 64, 28, 54));

        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(TEXT);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(getString(R.string.AccDescrGoBack));
        back.setOnClickListener(v -> finishFragment());
        screen.addView(back, LayoutHelper.createFrame(56, 56, Gravity.TOP | Gravity.START));

        TextView heading = label(context, 15, TEXT);
        heading.setText(getString(R.string.GlassgramConversationStats));
        heading.setTypeface(AndroidUtilities.bold());
        screen.addView(heading, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56,
                Gravity.TOP, 56, 0, 56, 0));

        pageIndicators = new LinearLayout(context);
        pageIndicators.setGravity(Gravity.CENTER);
        pageIndicators.setVisibility(View.GONE);
        screen.addView(pageIndicators, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        for (int i = 0; i < indicators.length; i++) {
            final int index = i;
            FrameLayout target = new FrameLayout(context);
            target.setContentDescription(formatString(R.string.GlassgramConversationPage, i + 1, TITLES.length, getString(TITLES[i])));
            target.setOnClickListener(v -> pager.setCurrentItem(index, true));
            View indicator = new View(context);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            indicators[i] = indicator;
            target.addView(indicator, LayoutHelper.createFrame(8, 8, Gravity.CENTER));
            pageIndicators.addView(target, LayoutHelper.createLinear(32, 48));
        }

        if (request != null) {
            request.cancel();
        }
        request = GlassgramConversationStats.load(getCurrentAccount(), getUserConfig().getClientUserId(), this::showResult);
        return screen;
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
        if (request != null) {
            request.cancel();
        }
        super.onFragmentDestroy();
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
        coverage = formatString(R.string.GlassgramConversationCoverage, LocaleController.formatShortDate(stats.oldest));
        values[0] = formatString(R.string.GlassgramConversationCounts, stats.sent, stats.received, stats.chats);
        values[1] = formatString(R.string.GlassgramConversationReplyTimes,
                replyTime(stats.yourMedianMinutes()), replyTime(stats.theirMedianMinutes()));
        values[2] = formatString(R.string.GlassgramConversationStartsCount, stats.startedByYou, stats.startedByOthers);
        values[3] = formatString(R.string.GlassgramConversationUnansweredCount, stats.outgoingUnanswered, stats.incomingUnanswered);
        int peak = 0;
        for (int hour = 1; hour < stats.hours.length; hour++) {
            if (stats.hours[hour] > stats.hours[peak]) {
                peak = hour;
            }
        }
        values[4] = stats.sent == 0 ? getString(R.string.GlassgramConversationNoOutgoing)
                : formatString(R.string.GlassgramConversationPeak, formatHour(peak), formatHour((peak + 1) % 24));
        int summary = stats.startedByYou > stats.startedByOthers ? R.string.GlassgramConversationSummaryYou
                : stats.startedByYou < stats.startedByOthers ? R.string.GlassgramConversationSummaryOthers
                : stats.startedByYou > 0 ? R.string.GlassgramConversationSummaryEqual : R.string.GlassgramConversationSummaryNoStarts;
        values[5] = getString(summary);
        status.setVisibility(View.GONE);
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
                return slide;
            }

            @Override
            public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
                container.removeView((View) object);
            }
        });
        pager.setCurrentItem(page, false);
        updatePage();
    }

    private void updatePage() {
        screen.setBackgroundColor(COLORS[page]);
        for (int i = 0; i < indicators.length; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(i == page ? TEXT : 0x50344750);
            indicators[i].setBackground(dot);
            ((View) indicators[i].getParent()).setSelected(i == page);
        }
    }

    private View createSlide(Context context, int index) {
        ScrollView slide = new ScrollView(context);
        slide.setFillViewport(true);
        slide.setBackgroundColor(COLORS[index]);
        slide.setPadding(dp(24), dp(56), dp(24), dp(48));
        slide.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(0, dp(20), 0, dp(20));
        slide.addView(column, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        RLottieImageView duck = new RLottieImageView(context);
        duck.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        duck.setAnimation(index % 2 == 0 ? R.raw.utyan_empty : R.raw.utyan_empty2, 156, 156);
        duck.setProgress(.3f + .08f * index);
        column.addView(duck, LayoutHelper.createLinear(156, 156));

        TextView title = label(context, 28, TEXT);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(TITLES[index]));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 10));

        TextView value = label(context, 18, TEXT);
        value.setText(values[index]);
        value.setLineSpacing(dp(5), 1);
        column.addView(value, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        if (index == 0) {
            TextView source = label(context, 14, SECONDARY_TEXT);
            source.setText(coverage);
            column.addView(source, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));
        }
        return slide;
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
