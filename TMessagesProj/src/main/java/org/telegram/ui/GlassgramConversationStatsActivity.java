package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.GlassgramConversationStats;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.RLottieNative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

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
    private ImageView shareButton;
    private ShareExport shareExport;
    private GlassgramConversationStats.Request request;

    public GlassgramConversationStatsActivity(int account) {
        setCurrentAccount(account);
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        actionBar.setAddToContainer(false);
        stopDuck();
        shareExport = null;
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

        shareButton = new ImageView(context);
        shareButton.setImageResource(R.drawable.msg_shareout);
        shareButton.setColorFilter(TEXT);
        shareButton.setScaleType(ImageView.ScaleType.CENTER);
        shareButton.setBackground(rounded(0x66ffffff, 24));
        shareButton.setContentDescription(getString(R.string.GlassgramConversationShare));
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareStats());
        screen.addView(shareButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.END, 12, 4, 12, 0));

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
        shareExport = null;
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
        shareButton.setVisibility(View.VISIBLE);
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
        slide.setBackground(slideBackground(index));
        slide.setPadding(dp(22), dp(65), dp(22), dp(58));
        slide.setVerticalScrollBarEnabled(false);

        slide.addView(createSlideContent(context, index, null),
                new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return slide;
    }

    private static GradientDrawable slideBackground(int index) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {COLORS[index], androidx.core.graphics.ColorUtils.blendARGB(COLORS[index], 0xffffffff, .72f)});
    }

    private LinearLayout createSlideContent(Context context, int index, Bitmap duckFrame) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(0, dp(10), 0, dp(12));

        FrameLayout illustration = new FrameLayout(context);
        illustration.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xcaffffff, 0x33ffffff}));
        ((GradientDrawable) illustration.getBackground()).setCornerRadius(dp(90));
        column.addView(illustration, LayoutHelper.createLinear(170, 152));

        ImageView illustrationImage;
        if (duckFrame != null) {
            illustrationImage = new ImageView(context);
            illustrationImage.setImageBitmap(duckFrame);
            illustrationImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            RLottieImageView duck = new RLottieImageView(context);
            duck.setAutoRepeat(true);
            duck.setAnimation(ANIMATIONS[index], 148, 148);
            ducks[index] = duck;
            illustrationImage = duck;
        }
        illustrationImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        illustration.addView(illustrationImage, LayoutHelper.createFrame(148, 148, Gravity.CENTER));

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
        return column;
    }

    private void shareStats() {
        Activity activity = getParentActivity();
        if (stats == null || activity == null || shareExport != null) {
            return;
        }
        ShareExport job = new ShareExport(new File(activity.getCacheDir(), "conversation-stats"));
        shareExport = job;
        shareButton.setEnabled(false);
        shareButton.setAlpha(.45f);
        shareButton.setContentDescription(getString(R.string.GlassgramConversationSharePreparing));
        job.renderNext();
    }

    private Bitmap renderShareCard(Context context, int index, Bitmap duckFrame) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(22), dp(28), dp(22), dp(24));
        card.setMinimumHeight(dp(470));
        card.setBackground(slideBackground(index));
        card.addView(createSlideContent(context, index, duckFrame),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout signature = new LinearLayout(context);
        signature.setGravity(Gravity.CENTER);
        signature.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        signature.setPadding(dp(10), dp(6), dp(12), dp(6));
        signature.setBackground(rounded(0xcfffffff, 18));
        ImageView emblem = new ImageView(context);
        emblem.setImageResource(R.mipmap.ic_launcher);
        signature.addView(emblem, LayoutHelper.createLinear(22, 22));
        TextView brand = label(context, 14, TEXT);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setText("glassGram");
        signature.addView(brand, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        card.addView(signature, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        int width = dp(360);
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = card.getMeasuredHeight();
        card.layout(0, 0, width, height);
        float scale = 1080f / width;
        Bitmap bitmap = Bitmap.createBitmap(1080, (int) Math.ceil(height * scale), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        card.draw(canvas);
        return bitmap;
    }

    private final class ShareExport {
        private final File root;
        private final File directory;
        private final ArrayList<Uri> images = new ArrayList<>();

        ShareExport(File root) {
            this.root = root;
            directory = new File(root, UUID.randomUUID().toString());
        }

        void renderNext() {
            int index = images.size();
            if (!Utilities.globalQueue.postRunnable(() -> {
                Bitmap duck = null;
                try {
                    if (index == 0) {
                        if (!directory.mkdirs()) {
                            throw new IOException("Could not create statistics share directory");
                        }
                        File[] exports = root.listFiles();
                        if (exports != null) {
                            long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
                            for (File export : exports) {
                                if (export.lastModified() < cutoff) {
                                    deleteImages(export);
                                }
                            }
                        }
                    }
                    RLottieNative animation = RLottieNative.createFromRawJson(AndroidUtilities.readRes(ANIMATIONS[index]));
                    if (animation == null) {
                        throw new IOException("Could not load statistics illustration");
                    }
                    try {
                        duck = Bitmap.createBitmap(444, 444, Bitmap.Config.ARGB_8888);
                        if (animation.getFrame(animation.getFrameCount() / 3, duck, true) < 0) {
                            throw new IOException("Could not render statistics illustration");
                        }
                    } finally {
                        animation.recycle();
                    }
                    Bitmap frame = duck;
                    AndroidUtilities.runOnUIThread(() -> renderCard(index, frame));
                } catch (Exception e) {
                    if (duck != null) {
                        duck.recycle();
                    }
                    fail(e);
                }
            })) {
                fail(new IOException("Could not queue statistics image"));
            }
        }

        private void renderCard(int index, Bitmap duck) {
            Activity activity = getParentActivity();
            if (shareExport != this || activity == null || isPaused()) {
                duck.recycle();
                cancel();
                return;
            }
            Bitmap card;
            try {
                card = renderShareCard(activity, index, duck);
            } catch (Exception e) {
                fail(e);
                return;
            } finally {
                duck.recycle();
            }
            if (!Utilities.globalQueue.postRunnable(() -> {
                File file = new File(directory, "glassGram-" + (index + 1) + ".png");
                try (FileOutputStream stream = new FileOutputStream(file)) {
                    if (!card.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IOException("Could not write statistics image");
                    }
                } catch (Exception e) {
                    fail(e);
                    return;
                } finally {
                    card.recycle();
                }
                AndroidUtilities.runOnUIThread(() -> addImage(file));
            })) {
                card.recycle();
                fail(new IOException("Could not queue statistics image write"));
            }
        }

        private void addImage(File file) {
            Activity activity = getParentActivity();
            if (shareExport != this || activity == null || isPaused()) {
                cancel();
                return;
            }
            try {
                images.add(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file));
                if (images.size() < TITLES.length) {
                    renderNext();
                    return;
                }
                Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                send.setType("image/png");
                send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, images);
                ClipData clip = ClipData.newUri(activity.getContentResolver(), "glassGram", images.get(0));
                for (int i = 1; i < images.size(); i++) {
                    clip.addItem(new ClipData.Item(images.get(i)));
                }
                send.setClipData(clip);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(send, getString(R.string.GlassgramConversationShare)));
                resetButton();
            } catch (Exception e) {
                fail(e);
            }
        }

        private void fail(Exception error) {
            FileLog.e(error);
            AndroidUtilities.runOnUIThread(() -> {
                if (shareExport == this && getParentActivity() != null && !isPaused()) {
                    showDialog(new AlertDialog.Builder(getParentActivity())
                            .setMessage(getString(R.string.GlassgramConversationShareError))
                            .setPositiveButton(getString(R.string.OK), null)
                            .create());
                }
                cancel();
            });
        }

        private void cancel() {
            resetButton();
            Utilities.globalQueue.postRunnable(() -> deleteImages(directory));
        }

        private void resetButton() {
            if (shareExport == this) {
                shareExport = null;
                shareButton.setEnabled(true);
                shareButton.setAlpha(1f);
                shareButton.setContentDescription(getString(R.string.GlassgramConversationShare));
            }
        }
    }

    private static void deleteImages(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        directory.delete();
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
