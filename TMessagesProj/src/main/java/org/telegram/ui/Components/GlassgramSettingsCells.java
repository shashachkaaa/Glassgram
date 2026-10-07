package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;

import java.util.Arrays;

/**
 * Cells of Glassgram's settings in exteraGram's layout: section titles above rounded cards, a sticker size
 * slider and a sticker shape picker. The pieces that are pressed react like the app's Liquid Glass buttons.
 */
public final class GlassgramSettingsCells {

    private GlassgramSettingsCells() {
    }

    /** A section title above its card, outside it: bold, in the text color. */
    public static final class Header extends UItem.UItemFactory<TextView> {
        static { setup(new Header()); }

        @Override
        public TextView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            final TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setPadding(dp(24), dp(20), dp(24), dp(10));
            textView.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return textView;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((TextView) view).setText(item.text);
        }

        @Override
        public boolean isShadow() {
            return true;
        }

        @Override
        public boolean isClickable() {
            return false;
        }

        public static UItem of(CharSequence text) {
            final UItem item = UItem.ofFactory(Header.class);
            item.text = text;
            return item;
        }
    }

    /** A switch with an example under its title, like "1.23K → 1,234". */
    public static final class ExampleCheck extends UItem.UItemFactory<TextCheckCell> {
        static { setup(new ExampleCheck()); }

        @Override
        public TextCheckCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new TextCheckCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            final TextCheckCell cell = (TextCheckCell) view;
            if (cell.itemId == item.id) {
                cell.setChecked(item.checked);
            }
            cell.setTextAndValueAndCheck(String.valueOf(item.text), String.valueOf(item.subtext), item.checked, false, divider);
            cell.itemId = item.id;
        }

        public static UItem of(int id, CharSequence text, CharSequence example, boolean checked) {
            final UItem item = UItem.ofFactory(ExampleCheck.class);
            item.id = id;
            item.text = text;
            item.subtext = example;
            item.checked = checked;
            return item;
        }
    }

    /** "Sticker Size [12]", Small and Large at the ends, and the slider. */
    public static class SizeSlider extends FrameLayout {

        private final TextView valueView;
        private final SeekBarView seekBar;
        private final int min, max;
        private int value;

        public SizeSlider(Context context, CharSequence title, CharSequence minText, CharSequence maxText,
                          int min, int value, int max, Utilities.Callback<Integer> onChange) {
            super(context);
            this.min = min;
            this.max = max;
            this.value = value;

            final LinearLayout titleRow = new LinearLayout(context);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            final TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleRow.addView(titleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            // The value in a small pill next to the title
            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueView.setTypeface(AndroidUtilities.bold());
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            valueView.setGravity(Gravity.CENTER);
            valueView.setPadding(dp(7), 0, dp(7), 0);
            final GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(6));
            pill.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.1f));
            valueView.setBackground(pill);
            titleRow.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 22, 0, 8, 0, 0, 0));
            addView(titleRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 16, 21, 0));

            addView(endLabel(context, minText, Gravity.LEFT), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 50, 21, 0));
            addView(endLabel(context, maxText, Gravity.RIGHT), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 50, 21, 0));

            seekBar = new SeekBarView(context) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return super.onTouchEvent(event);
                }
            };
            seekBar.setReportChanges(true);
            seekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
                @Override
                public void onSeekBarDrag(boolean stop, float progress) {
                    final int newValue = Math.round(SizeSlider.this.min + (SizeSlider.this.max - SizeSlider.this.min) * progress);
                    if (newValue != SizeSlider.this.value) {
                        SizeSlider.this.value = newValue;
                        AndroidUtilities.vibrateCursor(seekBar);
                        updateValue();
                        onChange.run(newValue);
                    }
                }

                @Override
                public int getStepsCount() {
                    return SizeSlider.this.max - SizeSlider.this.min;
                }

                @Override
                public CharSequence getContentDescription() {
                    return title + " " + SizeSlider.this.value;
                }
            });
            seekBar.setProgress((value - min) / (float) (max - min), false);
            addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.TOP, 6, 76, 6, 0));
            updateValue();
        }

        private static TextView endLabel(Context context, CharSequence text, int gravity) {
            final TextView textView = new TextView(context);
            textView.setText(text);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTypeface(AndroidUtilities.bold());
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setGravity(gravity);
            return textView;
        }

        private void updateValue() {
            valueView.setText(String.valueOf(value));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(124), MeasureSpec.EXACTLY));
        }
    }

    /** Three tiles to pick a shape from, the chosen one framed in the accent color. */
    public static class ShapePicker extends LinearLayout {

        public static final int SHAPE_SQUARE = 0, SHAPE_ROUNDED = 1, SHAPE_MESSAGE = 2;

        private final Option[] options;
        private int selected;

        public ShapePicker(Context context, CharSequence[] names, int selected, Utilities.Callback<Integer> onSelect) {
            super(context);
            this.selected = selected;
            setOrientation(HORIZONTAL);
            setPadding(dp(14), dp(14), dp(14), dp(12));
            options = new Option[names.length];
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                options[i] = new Option(context, i, names[i]);
                options[i].setOnClickListener(v -> {
                    if (this.selected != index) {
                        this.selected = index;
                        for (Option option : options) {
                            option.invalidateAll();
                        }
                        onSelect.run(index);
                    }
                });
                addView(options[i], LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i == 0 ? 0 : 6, 0, i == names.length - 1 ? 0 : 6, 0));
            }
        }

        private class Option extends LinearLayout {

            private final Tile tile;
            private final TextView label;

            Option(Context context, int shape, CharSequence name) {
                super(context);
                setOrientation(VERTICAL);
                setGravity(Gravity.CENTER_HORIZONTAL);
                tile = new Tile(context, shape);
                addView(tile, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                label = new TextView(context);
                label.setText(name);
                label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
                tile.setOnClickListener(v -> performClick());
                invalidateAll();
            }

            void invalidateAll() {
                final boolean chosen = selected == tile.shape;
                label.setTextColor(Theme.getColor(chosen ? Theme.key_windowBackgroundWhiteValueText : Theme.key_windowBackgroundWhiteGrayText));
                label.setTypeface(chosen ? AndroidUtilities.bold() : null);
                tile.invalidate();
            }
        }

        private class Tile extends View {

            final int shape;
            private final LiquidPressEffect press;
            private final AnimatedFloat chosen;
            private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final RectF rect = new RectF();
            private final float[] radii = new float[8];

            Tile(Context context, int shape) {
                super(context);
                this.shape = shape;
                press = new LiquidPressEffect(this);
                chosen = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
                chosen.set(selected == shape, true);
                stroke.setStyle(Paint.Style.STROKE);
                setClickable(true);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                setMeasuredDimension(width, Math.round(width * 0.92f));
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                press.onTouchEvent(event);
                return super.dispatchTouchEvent(event);
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                canvas.save();
                press.transform(canvas, getWidth(), getHeight());
                super.draw(canvas);
                canvas.restore();
            }

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                final float t = chosen.set(selected == shape);
                final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
                final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteValueText);
                final float outer = dp(16);

                // The frame
                final float strokeWidth = AndroidUtilities.lerp(AndroidUtilities.dpf2(1), AndroidUtilities.dpf2(2.33f), t);
                rect.set(strokeWidth / 2f, strokeWidth / 2f, getWidth() - strokeWidth / 2f, getHeight() - strokeWidth / 2f);
                fill.setColor(Theme.multAlpha(text, 0.04f));
                canvas.drawRoundRect(rect, outer, outer, fill);
                stroke.setStrokeWidth(strokeWidth);
                stroke.setColor(ColorUtils.blendARGB(Theme.multAlpha(text, 0.1f), accent, t));
                canvas.drawRoundRect(rect, outer, outer, stroke);
                press.drawGlow(canvas, getWidth(), getHeight(), outer);

                // The shape: what a sticker is clipped to
                final float inset = dp(12);
                rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
                final float big;
                switch (shape) {
                    case SHAPE_ROUNDED: big = dp(10); break;
                    case SHAPE_MESSAGE: big = dp(20); break;
                    default: big = dp(2);
                }
                Arrays.fill(radii, big);
                if (shape == SHAPE_MESSAGE) {
                    // the small corner where an outgoing bubble has its tail
                    radii[4] = radii[5] = dp(6);
                }
                path.rewind();
                path.addRoundRect(rect, radii, Path.Direction.CW);
                fill.setColor(ColorUtils.blendARGB(Theme.multAlpha(text, 0.16f), Theme.multAlpha(accent, 0.3f), t * 0.5f));
                canvas.drawPath(path, fill);
            }
        }
    }
}
