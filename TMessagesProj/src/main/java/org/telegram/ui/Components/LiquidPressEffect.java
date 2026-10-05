package org.telegram.ui.Components;

import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.AndroidUtilities;

/**
 * Press feedback of the Kyant0/AndroidLiquidGlass catalog's LiquidButton for a plain View:
 * the surface swells a little and leans towards the finger (its `layerBlock`), and a light
 * spot follows the finger (its `InteractiveHighlight`). Both springs use the catalog's
 * spring(0.5f, 300f).
 * <p>
 * Call {@link #onTouchEvent} from {@code dispatchTouchEvent}, wrap {@code draw} in
 * {@link #transform} / {@code restore}, and call {@link #drawGlow} after the background.
 */
public class LiquidPressEffect {

    private static final float DAMPING = 0.5f;
    private static final float STIFFNESS = 300f;

    private final View view;
    private final FloatValueHolder press = new FloatValueHolder(0f);
    private final FloatValueHolder offsetX = new FloatValueHolder(0f);
    private final FloatValueHolder offsetY = new FloatValueHolder(0f);
    private final SpringAnimation pressAnimation;
    private final SpringAnimation offsetXAnimation;
    private final SpringAnimation offsetYAnimation;

    private float startX, startY, touchX, touchY;

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    public LiquidPressEffect(View view) {
        this.view = view;
        pressAnimation = spring(press, 0.001f);
        offsetXAnimation = spring(offsetX, 0.5f);
        offsetYAnimation = spring(offsetY, 0.5f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            glowPaint.setBlendMode(BlendMode.PLUS);
        } else {
            glowPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }
    }

    private SpringAnimation spring(FloatValueHolder holder, float threshold) {
        SpringAnimation animation = new SpringAnimation(holder);
        animation.setSpring(new SpringForce().setDampingRatio(DAMPING).setStiffness(STIFFNESS));
        animation.setMinimumVisibleChange(threshold);
        animation.addUpdateListener((a, value, velocity) -> view.invalidate());
        animation.addEndListener((a, canceled, value, velocity) -> restoreClipping());
        return animation;
    }

    // The swelling surface goes past its view; these ancestors stop clipping it meanwhile
    private int unclipLevels;
    private final java.util.ArrayList<android.view.ViewGroup> unclipped = new java.util.ArrayList<>();

    /** Lets the surface swell past its view: the given number of ancestors stop clipping while it is pressed. */
    public void setUnclipParents(int levels) {
        unclipLevels = levels;
    }

    private void unclipParents() {
        if (unclipLevels <= 0 || !unclipped.isEmpty()) {
            return;
        }
        android.view.ViewParent parent = view.getParent();
        for (int i = 0; i < unclipLevels && parent instanceof android.view.ViewGroup; i++) {
            final android.view.ViewGroup group = (android.view.ViewGroup) parent;
            if (group.getClipChildren()) {
                group.setClipChildren(false);
                unclipped.add(group);
            }
            parent = group.getParent();
        }
    }

    private void restoreClipping() {
        if (unclipped.isEmpty() || pressAnimation.isRunning() || offsetXAnimation.isRunning() || offsetYAnimation.isRunning() || isActive() && press.getValue() > 0.01f) {
            return;
        }
        for (android.view.ViewGroup group : unclipped) {
            group.setClipChildren(true);
            group.invalidate();
        }
        unclipped.clear();
    }

    public float getPressProgress() {
        return press.getValue();
    }

    /** Whether the surface is pressed or still springing back, so it has to be drawn transformed. */
    public boolean isActive() {
        return press.getValue() != 0f || offsetX.getValue() != 0f || offsetY.getValue() != 0f;
    }

    public void onTouchEvent(MotionEvent event) {
        if (!view.isEnabled() || !view.isClickable()) {
            return;
        }
        onTouch(event.getActionMasked(), event.getX(), event.getY());
    }

    /**
     * Feeds a touch at x, y of a surface that is only a part of the view (the glass pieces of
     * the action bar); the view's own clickable state does not matter.
     */
    public void onTouch(int action, float x, float y) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                unclipParents();
                startX = touchX = x;
                startY = touchY = y;
                offsetXAnimation.cancel();
                offsetYAnimation.cancel();
                offsetX.setValue(0);
                offsetY.setValue(0);
                pressAnimation.animateToFinalPosition(1f);
                break;
            case MotionEvent.ACTION_MOVE:
                touchX = x;
                touchY = y;
                offsetXAnimation.cancel();
                offsetYAnimation.cancel();
                offsetX.setValue(touchX - startX);
                offsetY.setValue(touchY - startY);
                view.invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressAnimation.animateToFinalPosition(0f);
                offsetXAnimation.animateToFinalPosition(0f);
                offsetYAnimation.animateToFinalPosition(0f);
                break;
        }
    }

    // The current press transform: translation and scale about the surface's center
    private float curTx, curTy, curScaleX = 1f, curScaleY = 1f;

    /** Works out the press transform for a surface of this size; false when there is none. */
    private boolean compute(float width, float height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        final float progress = press.getValue();
        final float ox = offsetX.getValue(), oy = offsetY.getValue();
        if (progress == 0f && ox == 0f && oy == 0f) {
            return false;
        }
        // The catalog swells its small buttons by 4dp of their height in both directions; a wide
        // button would grow by tens of dp sideways that way, so each side gets its own 4dp
        final float swell = AndroidUtilities.dpf2(4) * progress;
        final float scaleBaseX = 1f + swell / width;
        final float scaleBaseY = 1f + swell / height;

        final float maxOffset = Math.min(width, height);
        final float initialDerivative = 0.05f;
        curTx = maxOffset * (float) Math.tanh(initialDerivative * ox / maxOffset);
        curTy = maxOffset * (float) Math.tanh(initialDerivative * oy / maxOffset);

        // The drag stretch too is 4dp at most along each side, whatever the surface's size
        final float maxStretch = AndroidUtilities.dpf2(4);
        final float maxDimension = Math.max(width, height);
        final double angle = Math.atan2(oy, ox);
        // The stretch grows with the drag up to one size of the surface, then holds
        curScaleX = scaleBaseX + maxStretch / width * Math.min(1f, Math.abs((float) Math.cos(angle) * ox / maxDimension));
        curScaleY = scaleBaseY + maxStretch / height * Math.min(1f, Math.abs((float) Math.sin(angle) * oy / maxDimension));
        return true;
    }

    /** Applies the catalog's LiquidButton layerBlock: swell by 4dp and stretch towards the drag. */
    public void transform(Canvas canvas, float width, float height) {
        if (!compute(width, height)) {
            return;
        }
        canvas.translate(curTx, curTy);
        canvas.scale(curScaleX, curScaleY, width / 2f, height / 2f);
    }

    /**
     * Where the surface (left, top, right, bottom, in its parent) is with the press transform
     * applied. Glass is drawn at that place rather than through {@link #transform}, so it blurs
     * what is under it now instead of stretching the picture taken where it was.
     */
    public void mapRect(float left, float top, float right, float bottom, android.graphics.RectF out) {
        out.set(left, top, right, bottom);
        final float width = right - left, height = bottom - top;
        if (!compute(width, height)) {
            return;
        }
        final float cx = (left + right) / 2f + curTx, cy = (top + bottom) / 2f + curTy;
        final float hw = width * curScaleX / 2f, hh = height * curScaleY / 2f;
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /**
     * How much the corner radii of glass drawn at {@link #mapRect} grow with it. A canvas scale
     * stretched the corners along with the surface; new bounds keep the old radius, and a circle
     * or a capsule whose half size outgrows it turns into a rounded square.
     */
    public static float radiusScale(float width, float height, android.graphics.RectF moved) {
        if (width <= 0 || height <= 0) {
            return 1f;
        }
        return Math.max(1f, Math.max(moved.width() / width, moved.height() / height));
    }

    /** The catalog's InteractiveHighlight: a faint wash and a light spot under the finger. */
    public void drawGlow(Canvas canvas, float width, float height, float radius) {
        final float progress = press.getValue();
        if (progress <= 0f || width <= 0 || height <= 0) {
            return;
        }
        canvas.save();
        clip.rewind();
        clip.addRoundRect(0, 0, width, height, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);

        glowPaint.setShader(null);
        glowPaint.setColor(Color.argb((int) (255 * 0.08f * Math.min(progress, 1f)), 255, 255, 255));
        canvas.drawRect(0, 0, width, height, glowPaint);

        final float spot = Math.min(width, height) * 1.5f;
        final float x = Math.max(0, Math.min(width, touchX));
        final float y = Math.max(0, Math.min(height, touchY));
        final int color = Color.argb((int) (255 * 0.15f * Math.min(progress, 1f)), 255, 255, 255);
        glowPaint.setShader(new RadialGradient(x, y, spot, new int[]{color, color, 0}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, glowPaint);
        glowPaint.setShader(null);
        canvas.restore();
    }
}
