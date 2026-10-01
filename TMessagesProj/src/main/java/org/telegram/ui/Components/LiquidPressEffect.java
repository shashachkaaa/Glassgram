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
        return animation;
    }

    public float getPressProgress() {
        return press.getValue();
    }

    public void onTouchEvent(MotionEvent event) {
        if (!view.isEnabled() || !view.isClickable()) {
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = touchX = event.getX();
                startY = touchY = event.getY();
                offsetXAnimation.cancel();
                offsetYAnimation.cancel();
                offsetX.setValue(0);
                offsetY.setValue(0);
                pressAnimation.animateToFinalPosition(1f);
                break;
            case MotionEvent.ACTION_MOVE:
                touchX = event.getX();
                touchY = event.getY();
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

    /** Applies the catalog's LiquidButton layerBlock: swell by 4dp and stretch towards the drag. */
    public void transform(Canvas canvas, float width, float height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        final float progress = press.getValue();
        final float ox = offsetX.getValue(), oy = offsetY.getValue();
        if (progress == 0f && ox == 0f && oy == 0f) {
            return;
        }
        final float scale = AndroidUtilities.lerp(1f, 1f + AndroidUtilities.dpf2(4) / height, progress);

        final float maxOffset = Math.min(width, height);
        final float initialDerivative = 0.05f;
        final float tx = maxOffset * (float) Math.tanh(initialDerivative * ox / maxOffset);
        final float ty = maxOffset * (float) Math.tanh(initialDerivative * oy / maxOffset);

        final float maxDragScale = AndroidUtilities.dpf2(4) / height;
        final float maxDimension = Math.max(width, height);
        final double angle = Math.atan2(oy, ox);
        final float scaleX = scale + maxDragScale * Math.abs((float) Math.cos(angle) * ox / maxDimension) * Math.min(width / height, 1f);
        final float scaleY = scale + maxDragScale * Math.abs((float) Math.sin(angle) * oy / maxDimension) * Math.min(height / width, 1f);

        canvas.translate(tx, ty);
        canvas.scale(scaleX, scaleY, width / 2f, height / 2f);
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
