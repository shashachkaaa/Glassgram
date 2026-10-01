/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Cells;

import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.SizeNotifierFrameLayout;

public abstract class BaseCell extends ViewGroup implements SizeNotifierFrameLayout.IViewWithInvalidateCallback {

    private final class CheckForTap implements Runnable {
        public void run() {
            if (pendingCheckForLongPress == null) {
                pendingCheckForLongPress = new CheckForLongPress();
            }
            pendingCheckForLongPress.currentPressCount = ++pressCount;
            postDelayed(pendingCheckForLongPress, ViewConfiguration.getLongPressTimeout() - ViewConfiguration.getTapTimeout());
        }
    }

    class CheckForLongPress implements Runnable {
        public int currentPressCount;

        public void run() {
            if (checkingForLongPress && getParent() != null && currentPressCount == pressCount) {
                checkingForLongPress = false;
                if (onLongPress()) {
                    try {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    } catch (Exception ignore) {}
                    MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                    onTouchEvent(event);
                    event.recycle();
                }
            }
        }
    }

    private boolean checkingForLongPress = false;
    private CheckForLongPress pendingCheckForLongPress = null;
    private int pressCount = 0;
    private CheckForTap pendingCheckForTap = null;

    public BaseCell(Context context) {
        super(context);
        setWillNotDraw(false);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
    }

    public static void setDrawableBounds(Drawable drawable, int x, int y) {
        setDrawableBounds(drawable, x, y, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    }

    public static void setDrawableBounds(Drawable drawable, float x, float y) {
        setDrawableBounds(drawable, (int) x, (int) y, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
    }

    public static float setDrawableBounds(Drawable drawable, float x, float y, float h) {
        float w = drawable.getIntrinsicWidth() * h / drawable.getIntrinsicHeight();
        setDrawableBounds(drawable, (int) x, (int) y, (int) w, (int) h);
        return w;
    }

    public static void setDrawableBounds(Drawable drawable, int x, int y, int w, int h) {
        if (drawable != null) {
            drawable.setBounds(x, y, x + w, y + h);
        }
    }

    public static void setDrawableBounds(Drawable drawable, float x, float y, int w, int h) {
        if (drawable != null) {
            drawable.setBounds((int) x, (int) y, (int) x + w, (int) y + h);
        }
    }

    protected void startCheckLongPress() {
        if (checkingForLongPress) {
            return;
        }
        checkingForLongPress = true;
        if (pendingCheckForTap == null) {
            pendingCheckForTap = new CheckForTap();
        }
        postDelayed(pendingCheckForTap, ViewConfiguration.getTapTimeout());
    }

    protected void cancelCheckLongPress() {
        checkingForLongPress = false;
        if (pendingCheckForLongPress != null) {
            removeCallbacks(pendingCheckForLongPress);
        }
        if (pendingCheckForTap != null) {
            removeCallbacks(pendingCheckForTap);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected boolean onLongPress() {
        return true;
    }

    public int getBoundsLeft() {
        return 0;
    }

    public int getBoundsRight() {
        return getWidth();
    }

    protected Runnable invalidateCallback;
    @Override
    public void listenInvalidate(Runnable callback) {
        invalidateCallback = callback;
    }

    public void invalidateLite() {
        super.invalidate();
    }
    @Override
    public void invalidate() {
        if (invalidateCallback != null) {
            invalidateCallback.run();
        }
        super.invalidate();
    }

    /**
     * Every ripple in the app goes through here. The spreading Material wave is replaced by
     * the press feedback of the Kyant0/AndroidLiquidGlass catalog's InteractiveHighlight: the
     * surface lights up evenly, with a soft spot under the finger, and fades out on release.
     */
    public static class RippleDrawableSafe extends RippleDrawable {

        private static final long PRESS_IN_DURATION = 120;
        private static final long PRESS_OUT_DURATION = 320;

        private ColorStateList pressColor;
        private final Drawable maskDrawable;
        private final Drawable contentDrawable;

        private boolean liquidPressed;
        private float pressAlpha;
        private ValueAnimator pressAnimator;
        private float hotspotX = Float.NaN, hotspotY = Float.NaN;

        private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect = new RectF();
        private static final int[] PRESSED_STATE = {android.R.attr.state_enabled, android.R.attr.state_pressed};

        public RippleDrawableSafe(@NonNull ColorStateList color, @Nullable Drawable content, @Nullable Drawable mask) {
            super(color, content, mask);
            pressColor = color;
            maskDrawable = mask;
            contentDrawable = content;
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        @Override
        public void setColor(ColorStateList color) {
            super.setColor(color);
            pressColor = color;
            invalidateSelf();
        }

        @Override
        public void setHotspot(float x, float y) {
            super.setHotspot(x, y);
            hotspotX = x;
            hotspotY = y;
        }

        @Override
        protected boolean onStateChange(int[] stateSet) {
            boolean pressed = false;
            int count = 0;
            for (int s : stateSet) {
                if (s == android.R.attr.state_pressed) {
                    pressed = true;
                } else {
                    count++;
                }
            }
            int[] withoutPressed = stateSet;
            if (pressed) {
                withoutPressed = new int[count];
                int i = 0;
                for (int s : stateSet) {
                    if (s != android.R.attr.state_pressed) {
                        withoutPressed[i++] = s;
                    }
                }
            }
            boolean changed = super.onStateChange(withoutPressed);
            if (pressed != liquidPressed) {
                liquidPressed = pressed;
                animatePress(pressed);
                changed = true;
            }
            return changed;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        public void jumpToCurrentState() {
            super.jumpToCurrentState();
            if (pressAnimator != null) {
                pressAnimator.cancel();
                pressAnimator = null;
            }
            pressAlpha = liquidPressed ? 1f : 0f;
        }

        private void animatePress(boolean pressed) {
            if (pressAnimator != null) {
                pressAnimator.cancel();
            }
            pressAnimator = ValueAnimator.ofFloat(pressAlpha, pressed ? 1f : 0f);
            pressAnimator.setDuration(pressed ? PRESS_IN_DURATION : PRESS_OUT_DURATION);
            pressAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            pressAnimator.addUpdateListener(a -> {
                pressAlpha = (float) a.getAnimatedValue();
                invalidateSelf();
            });
            pressAnimator.start();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final int save = canvas.save();
            try {
                super.draw(canvas);
                drawPress(canvas);
            } catch (Exception e) {
                FileLog.e("probably forgot to put setCallback", e);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        private void drawPress(Canvas canvas) {
            if (pressAlpha <= 0f || pressColor == null) {
                return;
            }
            final Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            final int color = pressColor.getColorForState(PRESSED_STATE, pressColor.getDefaultColor());
            final int alpha = Color.alpha(color);
            if (alpha == 0) {
                return;
            }
            final float cx = Float.isNaN(hotspotX) ? b.exactCenterX() : Math.max(b.left, Math.min(b.right, hotspotX));
            final float cy = Float.isNaN(hotspotY) ? b.exactCenterY() : Math.max(b.top, Math.min(b.bottom, hotspotY));

            final Drawable mask = maskDrawable != null ? maskDrawable : contentDrawable;
            if (mask == null) {
                // Unbounded (round icon buttons): a soft disc instead of the spreading wave
                float radius = Math.max(b.width(), b.height()) / 2f;
                if (Build.VERSION.SDK_INT >= 23 && getRadius() > 0) {
                    radius = getRadius();
                }
                washPaint.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), radius,
                    new int[]{ColorUtils.setAlphaComponent(color, (int) (alpha * pressAlpha)), ColorUtils.setAlphaComponent(color, (int) (alpha * pressAlpha * 0.7f)), 0},
                    new float[]{0f, 0.75f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, washPaint);
                washPaint.setShader(null);
                return;
            }

            tmpRect.set(b);
            final int layer = canvas.saveLayer(tmpRect, null);
            // InteractiveHighlight: an even wash...
            washPaint.setShader(null);
            washPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (alpha * 0.6f * pressAlpha)));
            canvas.drawRect(b, washPaint);
            // ...and a light spot under the finger
            final float spot = Math.min(b.width(), b.height()) * 1.5f;
            final int spotColor = ColorUtils.setAlphaComponent(color, (int) (Math.min(255, alpha * 1.2f) * pressAlpha));
            washPaint.setShader(new RadialGradient(cx, cy, spot, new int[]{spotColor, spotColor, 0}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b, washPaint);
            washPaint.setShader(null);
            // Keep it inside the ripple's mask (rounded corners, circles)
            canvas.saveLayer(tmpRect, maskPaint);
            mask.draw(canvas);
            canvas.restoreToCount(layer);
        }
    }
}
