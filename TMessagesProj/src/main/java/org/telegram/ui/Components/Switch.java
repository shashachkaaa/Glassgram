/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.StateSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import androidx.annotation.Keep;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.liquidglass.LiquidToggleView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.BaseCell;

import me.vkryl.android.animator.BoolAnimator;

public class Switch extends FrameLayout {
    private final BoolAnimator animatorIconVisibility = new BoolAnimator(this, CubicBezierInterpolator.EASE_OUT_QUINT, 380L, true);

    private RectF rectF;

    private float progress;
    private ObjectAnimator checkAnimator;
    private ObjectAnimator iconAnimator;

    private boolean attachedToWindow;
    private boolean isChecked;
    private Paint paint;
    private Paint paint2;

    private int drawIconType;
    private float iconProgress = 1.0f;

    private OnCheckedChangeListener onCheckedChangeListener;

    private int trackColorKey = Theme.key_fill_RedNormal;
    private int trackCheckedColorKey = Theme.key_switch2TrackChecked;
    private int thumbColorKey = Theme.key_windowBackgroundWhite;
    private int thumbCheckedColorKey = Theme.key_windowBackgroundWhite;

    private Drawable iconDrawable;
    private int lastIconColor;

    private boolean drawRipple;
    private RippleDrawable rippleDrawable;
    private Paint ripplePaint;
    private int[] pressedState = new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed};
    private int colorSet;

    private boolean bitmapsCreated;
    private Bitmap[] overlayBitmap;
    private Canvas[] overlayCanvas;
    private Bitmap overlayMaskBitmap;
    private Canvas overlayMaskCanvas;
    private float overlayCx;
    private float overlayCy;
    private float overlayRad;
    private Paint overlayEraserPaint;
    private Paint overlayMaskPaint;

    private Theme.ResourcesProvider resourcesProvider;

    private int overrideColorProgress;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(Switch view, boolean isChecked);
    }

    public Switch(Context context) {
        this(context, null);
    }

    public Switch(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rectF = new RectF();

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeCap(Paint.Cap.ROUND);
        paint2.setStrokeWidth(AndroidUtilities.dp(2));

        setHapticFeedbackEnabled(true);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        if (LiquidToggleView.isSupported() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            // The toggle itself is the library's: LiquidToggle from the Kyant0/AndroidLiquidGlass catalog
            toggleView = new LiquidToggleView(context);
            toggleView.setListener(this::onToggleRequested);
            final int overflow = LiquidToggleView.OVERFLOW_DP;
            addView(toggleView, LayoutHelper.createFrame(LiquidToggleView.WIDTH_DP + 2 * overflow, LiquidToggleView.HEIGHT_DP + 2 * overflow, android.view.Gravity.CENTER));
        }
    }

    private LiquidToggleView toggleView;

    private void onToggleRequested(boolean checked) {
        if (checked != isChecked) {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            performOwnerClick();
        }
        if (isChecked != checked) {
            // The owner kept the old state: the thumb goes back
            toggleView.resync();
        }
    }

    private void updateToggleColors() {
        if (toggleView == null) {
            return;
        }
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        final boolean dark = ColorUtils.calculateLuminance(background | 0xFF000000) < 0.5f;
        // The catalog's track: gray at 20% (light) or 36% (dark), the accent when on
        toggleView.setColors(
            processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider)),
            dark ? 0x5C787880 : 0x33787878
        );
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Sized as the plain View it used to be; the toggle overflows it, centered
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec));
        if (toggleView != null) {
            final int overflow = LiquidToggleView.OVERFLOW_DP;
            toggleView.measure(
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(LiquidToggleView.WIDTH_DP + 2 * overflow), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(LiquidToggleView.HEIGHT_DP + 2 * overflow), MeasureSpec.EXACTLY)
            );
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (toggleView != null) {
            final int w = toggleView.getMeasuredWidth(), h = toggleView.getMeasuredHeight();
            final int x = (getMeasuredWidth() - w) / 2, y = (getMeasuredHeight() - h) / 2;
            toggleView.layout(x, y, x + w, y + h);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (toggleView != null) {
            if (!isEnabled() || overrideColorProgress != 0) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
                // The toggle can be dragged; keep the list from scrolling meanwhile
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return super.dispatchTouchEvent(event);
        }
        return super.dispatchTouchEvent(event);
    }

    @Keep
    public void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        invalidate();
    }

    @Keep
    public float getProgress() {
        return progress;
    }

    @Keep
    public void setIconProgress(float value) {
        if (iconProgress == value) {
            return;
        }
        iconProgress = value;
        invalidate();
    }

    @Keep
    public float getIconProgress() {
        return iconProgress;
    }

    private void cancelCheckAnimator() {
        if (checkAnimator != null) {
            checkAnimator.cancel();
            checkAnimator = null;
        }
    }

    private void cancelIconAnimator() {
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
    }

    public void setDrawIconType(int type) {
        drawIconType = type;
    }

    public void setDrawRipple(boolean value) {
        if (Build.VERSION.SDK_INT < 21 || value == drawRipple) {
            return;
        }
        drawRipple = value;

        if (rippleDrawable == null) {
            ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ripplePaint.setColor(0xffffffff);
            Drawable maskDrawable;
            if (Build.VERSION.SDK_INT >= 23) {
                maskDrawable = null;
            } else {
                maskDrawable = new Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        android.graphics.Rect bounds = getBounds();
                        canvas.drawCircle(bounds.centerX(), bounds.centerY(), AndroidUtilities.dp(18), ripplePaint);
                    }

                    @Override
                    public void setAlpha(int alpha) {

                    }

                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {

                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.UNKNOWN;
                    }
                };
            }
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{0}
            );
            rippleDrawable = new BaseCell.RippleDrawableSafe(colorStateList, null, maskDrawable);
            if (Build.VERSION.SDK_INT >= 23) {
                rippleDrawable.setRadius(AndroidUtilities.dp(18));
            }
            rippleDrawable.setCallback(this);
        }
        if (isChecked && colorSet != 2 || !isChecked && colorSet != 1) {
            int color = Theme.getColor(isChecked ? Theme.key_switchTrackBlueSelectorChecked : Theme.key_switchTrackBlueSelector, resourcesProvider);
            color = processColor(color);
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{color}
            );
            rippleDrawable.setColor(colorStateList);
            colorSet = isChecked ? 2 : 1;
        }
        if (Build.VERSION.SDK_INT >= 28 && value) {
            rippleDrawable.setHotspot(isChecked ? 0 : AndroidUtilities.dp(100), AndroidUtilities.dp(18));
        }
        rippleDrawable.setState(value ? pressedState : StateSet.NOTHING);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || rippleDrawable != null && who == rippleDrawable;
    }

    protected int processColor(int color) {
        return color;
    }

    public void setColors(int track, int trackChecked, int thumb, int thumbChecked) {
        trackColorKey = track;
        trackCheckedColorKey = trackChecked;
        thumbColorKey = thumb;
        thumbCheckedColorKey = thumbChecked;
        updateToggleColors();
    }

    private void animateToCheckedState(boolean newCheckedState) {
        checkAnimator = ObjectAnimator.ofFloat(this, "progress", newCheckedState ? 1 : 0);
        checkAnimator.setDuration(LIQUID_TOGGLE_DURATION);
        checkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                checkAnimator = null;
            }
        });
        checkAnimator.start();
    }

    private void animateIcon(boolean newCheckedState) {
        iconAnimator = ObjectAnimator.ofFloat(this, "iconProgress", newCheckedState ? 1 : 0);
        iconAnimator.setDuration(200);
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                iconAnimator = null;
            }
        });
        iconAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
        updateToggleColors();
        // The glass thumb grows 1.5x while it moves, like the library's LiquidToggle,
        // and needs room around the switch's own bounds.
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).setClipChildren(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        onCheckedChangeListener = listener;
    }

    public void setChecked(boolean checked, boolean animated) {
        setChecked(checked, drawIconType, animated);
    }

    public void setChecked(boolean checked, int iconType, boolean animated) {
        if (toggleView != null) {
            toggleView.setChecked(checked, animated);
        }
        if (checked != isChecked) {
            isChecked = checked;
            if (attachedToWindow && animated) {
                animateToCheckedState(checked);
            } else {
                cancelCheckAnimator();
                setProgress(checked ? 1.0f : 0.0f);
            }
            if (onCheckedChangeListener != null) {
                onCheckedChangeListener.onCheckedChanged(this, checked);
            }
        }
        setDrawIconType(iconType, animated);
    }

    public void setIcon(int icon) {
        if (icon != 0) {
            iconDrawable = getResources().getDrawable(icon).mutate();
            if (iconDrawable != null) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = Theme.getColor(isChecked ? trackCheckedColorKey : trackColorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            }
        } else {
            iconDrawable = null;
        }
        invalidate();
    }

    public void setIconVisible(boolean visible, boolean animated) {
        animatorIconVisibility.setValue(visible, animated);
    }

    public void setDrawIconType(int iconType, boolean animated) {
        if (drawIconType != iconType) {
            drawIconType = iconType;
            if (attachedToWindow && animated) {
                animateIcon(iconType == 0);
            } else {
                cancelIconAnimator();
                setIconProgress(iconType == 0 ? 1.0f : 0.0f);
            }
        }
    }

    public boolean hasIcon() {
        return iconDrawable != null;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setOverrideColor(int override) {
        if (overrideColorProgress == override) {
            return;
        }
        if (overlayBitmap == null) {
            try {
                overlayBitmap = new Bitmap[2];
                overlayCanvas = new Canvas[2];
                for (int a = 0; a < 2; a++) {
                    overlayBitmap[a] = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                    overlayCanvas[a] = new Canvas(overlayBitmap[a]);
                }
                overlayMaskBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                overlayMaskCanvas = new Canvas(overlayMaskBitmap);

                overlayEraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayEraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

                overlayMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                bitmapsCreated = true;
            } catch (Throwable e) {
                return;
            }
        }
        if (!bitmapsCreated) {
            return;
        }
        overrideColorProgress = override;
        overlayCx = 0;
        overlayCy = 0;
        overlayRad = 0;
        invalidate();
    }

    public void setOverrideColorProgress(float cx, float cy, float rad) {
        overlayCx = cx;
        overlayCy = cy;
        overlayRad = rad;
        invalidate();
    }

    // Without liquid glass (or below Android 12) the toggle keeps the catalog's shape, drawn
    // plainly: a capsule track and a white capsule thumb, no glass.
    private static final long LIQUID_TOGGLE_DURATION = 360;
    private static final float LIQUID_PRESSED_SCALE = 1.5f;

    private final Paint liquidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint liquidShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF liquidRect = new RectF();

    private float liquidTrackWidth, liquidTrackHeight, liquidThumbLeft, liquidThumbTop;
    private float liquidPress;
    private int liquidTrackColor, liquidBackgroundColor;


    // Proportions of the catalog's 64x28 track with a 40x24 thumb, a bit smaller to fit Telegram's rows
    private static final float LIQUID_TRACK_WIDTH = 54f;
    private static final float LIQUID_TRACK_HEIGHT = 26f;
    private static final float LIQUID_PADDING = 2f;

    private float liquidThumbWidth() {
        return liquidTrackWidth * 40f / 64f;
    }

    private float liquidThumbHeight() {
        return liquidTrackHeight - 2 * AndroidUtilities.dpf2(LIQUID_PADDING);
    }

    private float liquidTravel() {
        final float trackWidth = AndroidUtilities.dpf2(LIQUID_TRACK_WIDTH);
        return trackWidth - trackWidth * 40f / 64f - 2 * AndroidUtilities.dpf2(LIQUID_PADDING);
    }

    /* Touch: the thumb can be dragged or the switch tapped, like the catalog's LiquidToggle */

    private final FloatValueHolder touchPress = new FloatValueHolder(0f);
    private final SpringAnimation touchPressAnimation = new SpringAnimation(touchPress);
    {
        // DampedDragAnimation's pressProgress spring(1f, 1000f)
        touchPressAnimation.setSpring(new SpringForce().setDampingRatio(1f).setStiffness(1000f));
        touchPressAnimation.setMinimumVisibleChange(0.001f);
        touchPressAnimation.addUpdateListener((a, value, velocity) -> invalidate());
    }
    private float touchStartX, touchStartProgress;
    private boolean touchDown, touchDragging;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || overrideColorProgress != 0) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true;
                touchDragging = false;
                touchStartX = event.getX();
                touchStartProgress = progress;
                touchPressAnimation.animateToFinalPosition(1f);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!touchDown) {
                    break;
                }
                final float dx = event.getX() - touchStartX;
                if (!touchDragging && Math.abs(dx) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    touchDragging = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    cancelCheckAnimator();
                }
                if (touchDragging) {
                    final float travel = liquidTravel();
                    setProgress(Math.max(0f, Math.min(1f, touchStartProgress + (travel > 0 ? dx / travel : 0))));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!touchDown) {
                    break;
                }
                touchDown = false;
                touchPressAnimation.animateToFinalPosition(0f);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    final boolean target = touchDragging ? progress >= 0.5f : !isChecked;
                    if (target != isChecked) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        performOwnerClick();
                    }
                }
                touchDragging = false;
                // Settle on whatever the owner decided
                if (progress != (isChecked ? 1f : 0f) && checkAnimator == null) {
                    animateToCheckedState(isChecked);
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Toggles through whoever owns the switch, so the setting is saved exactly as when the row
     * is tapped: the switch's own click listener, a clickable ancestor, or the list's item click.
     */
    private void performOwnerClick() {
        if (hasOnClickListeners()) {
            performClick();
            return;
        }
        View child = this;
        ViewParent parent = getParent();
        while (parent instanceof View) {
            final View parentView = (View) parent;
            if (parentView instanceof RecyclerListView) {
                final RecyclerListView list = (RecyclerListView) parentView;
                final int position = list.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION) {
                    if (list.getOnItemClickListener() != null) {
                        list.getOnItemClickListener().onItemClick(child, position);
                        return;
                    }
                    if (list.getOnItemClickListenerExtended() != null) {
                        list.getOnItemClickListenerExtended().onItemClick(child, position, child.getWidth() / 2f, child.getHeight() / 2f);
                        return;
                    }
                }
                break;
            }
            if (parentView.hasOnClickListeners()) {
                parentView.performClick();
                return;
            }
            child = parentView;
            parent = parentView.getParent();
        }
        setChecked(!isChecked, true);
    }

    private void drawLiquid(Canvas canvas) {
        final float trackWidth = liquidTrackWidth = AndroidUtilities.dpf2(LIQUID_TRACK_WIDTH);
        final float trackHeight = liquidTrackHeight = AndroidUtilities.dpf2(LIQUID_TRACK_HEIGHT);
        final float trackLeft = (getMeasuredWidth() - trackWidth) / 2f;
        final float trackTop = (getMeasuredHeight() - trackHeight) / 2f;
        final float padding = AndroidUtilities.dpf2(LIQUID_PADDING);
        final float thumbWidth = liquidThumbWidth();
        final float thumbHeight = liquidThumbHeight();
        final float travel = trackWidth - thumbWidth - 2 * padding;

        // The lens shows while the thumb is held or dragged, as in the catalog; when the row is
        // tapped instead, the thumb is "pressed" for the length of its move.
        final float movePress = checkAnimator != null ? (float) Math.sin(Math.PI * progress) : 0f;
        final float press = liquidPress = Math.max(movePress, Math.max(0f, Math.min(1f, touchPress.getValue())));

        final int trackColor = liquidTrackColor = ColorUtils.blendARGB(
            processColor(Theme.getColor(trackColorKey, resourcesProvider)),
            processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider)),
            progress);
        // The catalog's thumb is always white (onDrawSurface: Color.White)
        final int thumbColor = Color.WHITE;
        liquidBackgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);

        int color1 = processColor(Theme.getColor(trackColorKey, resourcesProvider));
        int color2 = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
        if (iconDrawable != null && lastIconColor != (isChecked ? color2 : color1)) {
            iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
        }

        liquidPaint.setColor(trackColor);
        liquidRect.set(trackLeft, trackTop, trackLeft + trackWidth, trackTop + trackHeight);
        canvas.drawRoundRect(liquidRect, trackHeight / 2f, trackHeight / 2f, liquidPaint);

        liquidThumbLeft = padding + travel * progress;
        liquidThumbTop = padding;
        final float thumbLeft = trackLeft + liquidThumbLeft;
        final float thumbTop = trackTop + liquidThumbTop;
        final float cx = thumbLeft + thumbWidth / 2f;
        final float cy = thumbTop + thumbHeight / 2f;

        if (rippleDrawable != null) {
            rippleDrawable.setBounds((int) cx - AndroidUtilities.dp(18), (int) cy - AndroidUtilities.dp(18), (int) cx + AndroidUtilities.dp(18), (int) cy + AndroidUtilities.dp(18));
            rippleDrawable.draw(canvas);
        }

        canvas.save();
        final float scale = AndroidUtilities.lerp(1f, LIQUID_PRESSED_SCALE, press);
        canvas.scale(scale, scale, cx, cy);

        // Shadow(radius = 4.dp, color = Black 5%)
        liquidShadowPaint.setColor(liquidBackgroundColor);
        liquidShadowPaint.setShadowLayer(AndroidUtilities.dpf2(4), 0, AndroidUtilities.dpf2(4) / 6f, 0x0D000000);
        liquidRect.set(thumbLeft, thumbTop, thumbLeft + thumbWidth, thumbTop + thumbHeight);
        canvas.drawRoundRect(liquidRect, thumbHeight / 2f, thumbHeight / 2f, liquidShadowPaint);

        liquidPaint.setColor(thumbColor);
        canvas.drawRoundRect(liquidRect, thumbHeight / 2f, thumbHeight / 2f, liquidPaint);

        drawThumbIcon(canvas, (int) cx, (int) cy, thumbColor);
        canvas.restore();
    }

    private void drawThumbIcon(Canvas canvas, int tx, int ty, int thumbColor) {
        if (iconDrawable != null) {
            final float factor = animatorIconVisibility.getFloatValue();
            if (factor > 0) {
                final boolean needScale = factor < 1;
                if (needScale) {
                    canvas.save();
                    canvas.scale(factor, factor, tx, ty);
                }
                iconDrawable.setBounds(tx - iconDrawable.getIntrinsicWidth() / 2, ty - iconDrawable.getIntrinsicHeight() / 2, tx + iconDrawable.getIntrinsicWidth() / 2, ty + iconDrawable.getIntrinsicHeight() / 2);
                iconDrawable.draw(canvas);
                if (needScale) {
                    canvas.restore();
                }
            }
        } else if (drawIconType == 1) {
            tx -= AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress;
            ty -= AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress;
            int startX2 = (int) AndroidUtilities.dpf2(4.6f) + tx;
            int startY2 = (int) (AndroidUtilities.dpf2(9.5f) + ty);
            int endX2 = startX2 + AndroidUtilities.dp(2);
            int endY2 = startY2 + AndroidUtilities.dp(2);

            int startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
            int startY = (int) AndroidUtilities.dpf2(5.4f) + ty;
            int endX = startX + AndroidUtilities.dp(7);
            int endY = startY + AndroidUtilities.dp(7);

            startX = (int) (startX + (startX2 - startX) * progress);
            startY = (int) (startY + (startY2 - startY) * progress);
            endX = (int) (endX + (endX2 - endX) * progress);
            endY = (int) (endY + (endY2 - endY) * progress);
            paint2.setColor(liquidTrackColor);
            canvas.drawLine(startX, startY, endX, endY, paint2);

            startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
            startY = (int) AndroidUtilities.dpf2(12.5f) + ty;
            endX = startX + AndroidUtilities.dp(7);
            endY = startY - AndroidUtilities.dp(7);
            canvas.drawLine(startX, startY, endX, endY, paint2);
        } else if (drawIconType == 2 || iconAnimator != null) {
            paint2.setColor(liquidTrackColor);
            paint2.setAlpha((int) (255 * (1.0f - iconProgress)));
            canvas.drawLine(tx, ty, tx, ty - AndroidUtilities.dp(5), paint2);
            canvas.save();
            canvas.rotate(-90 * iconProgress, tx, ty);
            canvas.drawLine(tx, ty, tx + AndroidUtilities.dp(4), ty, paint2);
            canvas.restore();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (toggleView != null) {
            toggleView.setVisibility(overrideColorProgress == 0 ? VISIBLE : INVISIBLE);
            if (overrideColorProgress == 0) {
                updateToggleColors();
                return;
            }
        }
        if (overrideColorProgress == 0) {
            drawLiquid(canvas);
            return;
        }

        int width = AndroidUtilities.dp(31);
        int thumb = AndroidUtilities.dp(20);
        int x = (getMeasuredWidth() - width) / 2;
        float y = (getMeasuredHeight() - AndroidUtilities.dpf2(14)) / 2;
        int tx = x + AndroidUtilities.dp(7) + (int) (AndroidUtilities.dp(17) * progress);
        int ty = getMeasuredHeight() / 2;


        int color1;
        int color2;
        float colorProgress;
        int r1;
        int r2;
        int g1;
        int g2;
        int b1;
        int b2;
        int a1;
        int a2;
        int red;
        int green;
        int blue;
        int alpha;
        int color;

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[0];

            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(0, 0, overlayMaskBitmap.getWidth(), overlayMaskBitmap.getHeight(), paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, overlayEraserPaint);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = processColor(Theme.getColor(trackColorKey, resourcesProvider));
            color2 = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
            if (a == 0 && iconDrawable != null && lastIconColor != (isChecked ? color2 : color1)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
            }

            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            color = ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
            paint.setColor(color);
            paint2.setColor(color);

            rectF.set(x, y, x + width, y + AndroidUtilities.dpf2(14));
            canvasToDraw.drawRoundRect(rectF, AndroidUtilities.dpf2(7), AndroidUtilities.dpf2(7), paint);
            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dpf2(10), paint);

            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds(tx - AndroidUtilities.dp(18), ty - AndroidUtilities.dp(18), tx + AndroidUtilities.dp(18), ty + AndroidUtilities.dp(18));
                rippleDrawable.draw(canvasToDraw);
            } else if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], 0, 0, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[1];

            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = Theme.getColor(thumbColorKey, resourcesProvider);
            color2 = processColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            paint.setColor(((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff));

            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dp(8), paint);

            if (a == 0) {
                if (iconDrawable != null) {
                    final float factor = animatorIconVisibility.getFloatValue();
                    if (factor > 0) {
                        final boolean needScale = factor < 1;
                        if (needScale) {
                            canvas.save();
                            canvas.scale(factor, factor, tx, ty);
                        }
                        iconDrawable.setBounds(tx - iconDrawable.getIntrinsicWidth() / 2, ty - iconDrawable.getIntrinsicHeight() / 2, tx + iconDrawable.getIntrinsicWidth() / 2, ty + iconDrawable.getIntrinsicHeight() / 2);
                        iconDrawable.draw(canvasToDraw);
                        if (needScale) {
                            canvas.restore();
                        }
                    }
                } else if (drawIconType == 1) {
                    tx -= AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress;
                    ty -= AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress;
                    int startX2 = (int) AndroidUtilities.dpf2(4.6f) + tx;
                    int startY2 = (int) (AndroidUtilities.dpf2(9.5f) + ty);
                    int endX2 = startX2 + AndroidUtilities.dp(2);
                    int endY2 = startY2 + AndroidUtilities.dp(2);

                    int startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    int startY = (int) AndroidUtilities.dpf2(5.4f) + ty;
                    int endX = startX + AndroidUtilities.dp(7);
                    int endY = startY + AndroidUtilities.dp(7);

                    startX = (int) (startX + (startX2 - startX) * progress);
                    startY = (int) (startY + (startY2 - startY) * progress);
                    endX = (int) (endX + (endX2 - endX) * progress);
                    endY = (int) (endY + (endY2 - endY) * progress);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);

                    startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    startY = (int) AndroidUtilities.dpf2(12.5f) + ty;
                    endX = startX + AndroidUtilities.dp(7);
                    endY = startY - AndroidUtilities.dp(7);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);
                } else if (drawIconType == 2 || iconAnimator != null) {
                    paint2.setAlpha((int) (255 * (1.0f - iconProgress)));
                    canvasToDraw.drawLine(tx, ty, tx, ty - AndroidUtilities.dp(5), paint2);
                    canvasToDraw.save();
                    canvasToDraw.rotate(-90 * iconProgress, tx, ty);
                    canvasToDraw.drawLine(tx, ty, tx + AndroidUtilities.dp(4), ty, paint2);
                    canvasToDraw.restore();
                }
            }
            if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], 0, 0, null);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(isChecked);
        //info.setContentDescription(isChecked ? LocaleController.getString(R.string.NotificationsOn) : LocaleController.getString(R.string.NotificationsOff));
    }
}
