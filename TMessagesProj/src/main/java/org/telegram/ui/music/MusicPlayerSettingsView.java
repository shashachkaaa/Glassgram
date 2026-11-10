package org.telegram.ui.music;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

/**
 * glassGram's music player: its settings, over the player. The cover's colors for the player and
 * for the mini player, online lyrics, and the way back to Telegram's own player.
 */
public class MusicPlayerSettingsView extends FrameLayout {

    private final Runnable onChanged;
    private final LinearLayout list;
    private Row coverFull, coverMini, onlineLyrics, classic;

    public MusicPlayerSettingsView(Context context, Runnable onChanged, Runnable onBack) {
        super(context);
        this.onChanged = onChanged;
        setBackgroundColor(0xff141319);
        setClickable(true);

        final FrameLayout header = new FrameLayout(context);
        final ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setColorFilter(0xffffffff);
        back.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x24ffffff, 1));
        back.setOnClickListener(v -> onBack.run());
        back.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        header.addView(back, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.GlassgramMusicSettings));
        title.setTextColor(0xffffffff);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 68, 0, 16, 0));
        addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP));

        final ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scroll.addView(list, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 56, 0, 0));

        section(context, LocaleController.getString(R.string.GlassgramMusicAppearance));
        final Group appearance = group(context);
        coverFull = row(context, appearance, LocaleController.getString(R.string.GlassgramMusicCoverColorsFull), LocaleController.getString(R.string.GlassgramMusicCoverColorsFullInfo), () -> {
            GlassgramConfig.musicCoverColorsFull = !GlassgramConfig.musicCoverColorsFull;
            GlassgramConfig.putBoolean("musicCoverColorsFull", GlassgramConfig.musicCoverColorsFull);
            return GlassgramConfig.musicCoverColorsFull;
        });
        coverMini = row(context, appearance, LocaleController.getString(R.string.GlassgramMusicCoverColorsMini), LocaleController.getString(R.string.GlassgramMusicCoverColorsMiniInfo), () -> {
            GlassgramConfig.musicCoverColorsMini = !GlassgramConfig.musicCoverColorsMini;
            GlassgramConfig.putBoolean("musicCoverColorsMini", GlassgramConfig.musicCoverColorsMini);
            return GlassgramConfig.musicCoverColorsMini;
        });

        section(context, LocaleController.getString(R.string.GlassgramMusicLyrics));
        final Group lyrics = group(context);
        onlineLyrics = row(context, lyrics, LocaleController.getString(R.string.GlassgramMusicOnlineLyrics), LocaleController.getString(R.string.GlassgramMusicOnlineLyricsInfo), () -> {
            GlassgramConfig.musicOnlineLyrics = !GlassgramConfig.musicOnlineLyrics;
            GlassgramConfig.putBoolean("musicOnlineLyrics", GlassgramConfig.musicOnlineLyrics);
            return GlassgramConfig.musicOnlineLyrics;
        });

        section(context, LocaleController.getString(R.string.GlassgramMusicPlayerSection));
        final Group player = group(context);
        classic = row(context, player, LocaleController.getString(R.string.GlassgramMusicClassic), LocaleController.getString(R.string.GlassgramMusicClassicInfo), () -> {
            GlassgramConfig.musicPlayer = !GlassgramConfig.musicPlayer;
            GlassgramConfig.putBoolean("musicPlayer", GlassgramConfig.musicPlayer);
            return !GlassgramConfig.musicPlayer;
        });
        update();
    }

    public void update() {
        coverFull.setChecked(GlassgramConfig.musicCoverColorsFull);
        coverMini.setChecked(GlassgramConfig.musicCoverColorsMini);
        onlineLyrics.setChecked(GlassgramConfig.musicOnlineLyrics);
        classic.setChecked(!GlassgramConfig.musicPlayer);
    }

    private void section(Context context, String text) {
        final TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(0x99ffffff);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setTypeface(AndroidUtilities.bold());
        list.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 12, 18, 12, 8));
    }

    /** A rounded card of rows, as iOS groups its settings. */
    private static class Group extends LinearLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Group(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            paint.setColor(ROW_COLOR);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, AndroidUtilities.dp(20), AndroidUtilities.dp(20), paint);
        }
    }

    private Group group(Context context) {
        final Group g = new Group(context);
        g.setClipToOutline(true);
        g.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(20));
            }
        });
        list.addView(g, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return g;
    }

    private interface Action {
        boolean toggle();
    }

    private Row row(Context context, Group group, String title, String info, Action toggle) {
        final Row r = new Row(context, title, info);
        r.setOnClickListener(v -> {
            r.setChecked(toggle.toggle());
            onChanged.run();
        });
        group.addView(r, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return r;
    }

    // the card's color, solid: the background (0x141319) with 8% of white over it
    private static final int ROW_COLOR = 0xff26252b;

    private static class Row extends LinearLayout {
        private final org.telegram.ui.Components.Switch check;

        Row(Context context, String title, String info) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            setBackground(org.telegram.ui.ActionBar.Theme.createSelectorWithBackgroundDrawable(ROW_COLOR, 0x14ffffff));
            setClipChildren(false);
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            final TextView t = new TextView(context);
            t.setText(title);
            t.setTextColor(0xffffffff);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            texts.addView(t);
            if (!TextUtils.isEmpty(info)) {
                final TextView i = new TextView(context);
                i.setText(info);
                i.setTextColor(0x8cffffff);
                i.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                texts.addView(i, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            // the client's switch: the library's LiquidToggle (Kyant0/AndroidLiquidGlass) where Liquid Glass is on
            check = new org.telegram.ui.Components.Switch(context, new org.telegram.ui.Stories.DarkThemeResourceProvider());
            // The library's toggle is wider than the Switch (64dp, its swelling thumb further): room
            // for it on both sides, as the settings' cells give it, and the row does not cut it at
            // its padding
            addView(check, LayoutHelper.createLinear(37, 40, Gravity.CENTER_VERTICAL, 26, 0, 12, 0));
            setClipToPadding(false);
        }

        void setChecked(boolean checked) {
            check.setChecked(checked, isAttachedToWindow());
        }
    }
}
