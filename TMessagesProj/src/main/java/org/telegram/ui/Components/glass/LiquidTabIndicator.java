package org.telegram.ui.Components.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.liquidglass.BackdropGlass;
import org.telegram.liquidglass.GlassLayer;
import org.telegram.messenger.AndroidUtilities;

/**
 * Selected-tab indicator after LiquidBottomTabs from the Kyant0/AndroidLiquidGlass catalog:
 * a capsule above the tabs that rides to the selected tab on a spring, follows the finger
 * while dragged, squashes with its velocity, and turns into a glass lens while it moves.
 * Through the lens the tabs are seen tinted with the accent color, so the tab under the
 * capsule always reads as selected, exactly like the catalog's exported tabs backdrop.
 * <p>
 * The host calls {@link #draw} after drawing its tabs.
 */
@RequiresApi(api = Build.VERSION_CODES.S)
public class LiquidTabIndicator {

    /** The catalog's DampedDragAnimation pressedScale for a 56dp tall indicator. */
    private static final float PRESSED_SCALE = 78f / 56f;

    public interface TabsDrawer {
        /** Draws the tabs (not the indicator) in host coordinates. */
        void drawTabs(Canvas canvas);
    }

    private final View host;

    private final FloatValueHolder centerX = new FloatValueHolder(0);
    private final FloatValueHolder width = new FloatValueHolder(0);
    private final FloatValueHolder press = new FloatValueHolder(0);
    private final SpringAnimation centerXAnimation;
    private final SpringAnimation widthAnimation;
    private final SpringAnimation pressAnimation;

    private boolean hasPosition;
    private boolean dragging;
    private float targetCenterX, targetWidth;

    private float lastCenterX;
    private long lastFrameTime;
    private float velocity;

    private final GlassLayer glass = new GlassLayer();
    private final RenderNode tabsNode = new RenderNode("LiquidTabs");
    private int tabsTint;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float drawLeft, drawTop;
    private Runnable backgroundDrawer;

    private final GlassLayer.Backdrop backdrop = c -> {
        // rememberCombinedBackdrop(backdrop, tabsBackdrop): the panel behind, then the tinted tabs
        c.translate(-drawLeft, -drawTop);
        if (backgroundDrawer != null) {
            backgroundDrawerCanvas = c;
            backgroundDrawer.run();
            backgroundDrawerCanvas = null;
        }
        c.drawRenderNode(tabsNode);
    };
    private Canvas backgroundDrawerCanvas;

    public LiquidTabIndicator(View host) {
        this.host = host;
        // The catalog animates the indicator with spring(1f, 1000f); a softer spring keeps the
        // lens visible for the length of the move.
        centerXAnimation = spring(centerX, 0.75f, 600f, 0.5f);
        widthAnimation = spring(width, 0.75f, 600f, 0.5f);
        pressAnimation = spring(press, 0.5f, 300f, 0.001f);
        centerXAnimation.addEndListener((a, canceled, value, v) -> {
            if (!dragging) {
                pressAnimation.animateToFinalPosition(0f);
            }
        });
    }

    private SpringAnimation spring(FloatValueHolder holder, float damping, float stiffness, float threshold) {
        SpringAnimation animation = new SpringAnimation(holder);
        animation.setSpring(new SpringForce().setDampingRatio(damping).setStiffness(stiffness));
        animation.setMinimumVisibleChange(threshold);
        animation.addUpdateListener((a, value, v) -> host.invalidate());
        return animation;
    }

    /** Moves the indicator to a tab; the first call places it without animation. */
    public void setTarget(float centerX, float width, boolean animated) {
        if (dragging) {
            return;
        }
        if (!hasPosition || !animated) {
            hasPosition = true;
            centerXAnimation.cancel();
            widthAnimation.cancel();
            this.centerX.setValue(centerX);
            this.width.setValue(width);
            targetCenterX = centerX;
            targetWidth = width;
            host.invalidate();
            return;
        }
        if (Math.abs(targetCenterX - centerX) > AndroidUtilities.dp(8)) {
            pressAnimation.animateToFinalPosition(1f);
        }
        targetCenterX = centerX;
        targetWidth = width;
        centerXAnimation.animateToFinalPosition(centerX);
        widthAnimation.animateToFinalPosition(width);
    }

    private static final Object DRAG_KEY = new Object();
    private Object lastKey;

    /**
     * Follows the tab identified by {@code key}: a new tab is reached on the spring (with the
     * lens), while the same tab moving on its own (scroll, relayout) is followed directly.
     */
    public void follow(Object key, float centerX, float width) {
        if (dragging) {
            return;
        }
        if (key != lastKey) {
            final boolean animated = lastKey != null;
            lastKey = key;
            setTarget(centerX, width, animated);
        } else if (centerXAnimation.isRunning()) {
            targetCenterX = centerX;
            targetWidth = width;
            centerXAnimation.animateToFinalPosition(centerX);
            widthAnimation.animateToFinalPosition(width);
        } else {
            setTarget(centerX, width, false);
        }
    }

    /** Puts the indicator under the finger and keeps it pressed until {@link #endDrag}. */
    public void drag(float centerX, float width) {
        if (!dragging) {
            dragging = true;
            // After the drag the indicator springs to whatever tab ends up selected
            lastKey = DRAG_KEY;
            pressAnimation.animateToFinalPosition(1f);
        }
        hasPosition = true;
        centerXAnimation.cancel();
        widthAnimation.cancel();
        this.centerX.setValue(centerX);
        this.width.setValue(width);
        targetCenterX = centerX;
        targetWidth = width;
        host.invalidate();
    }

    public void endDrag() {
        if (dragging) {
            dragging = false;
            pressAnimation.animateToFinalPosition(0f);
        }
    }

    /**
     * @param top        top of the indicator in host coordinates
     * @param height     indicator height
     * @param accent     color the tabs are tinted with inside the indicator
     * @param background draws the panel behind the tabs into {@link #getBackgroundCanvas()}, or null
     * @param tabs       draws the tabs
     */
    public void draw(Canvas canvas, ViewGroup parent, float top, float height, int accent, int surfaceBase,
                     Runnable background, TabsDrawer tabs) {
        if (!hasPosition || !canvas.isHardwareAccelerated()) {
            return;
        }
        final float cx = centerX.getValue();
        final float w = width.getValue();
        if (w <= 0 || height <= 0) {
            return;
        }

        final long now = SystemClock.uptimeMillis();
        if (lastFrameTime != 0 && now > lastFrameTime) {
            final float v = (cx - lastCenterX) / ((now - lastFrameTime) / 1000f);
            velocity = AndroidUtilities.lerp(velocity, v, 0.5f);
        }
        if (now - lastFrameTime > 100) {
            velocity = 0;
        }
        lastFrameTime = now;
        lastCenterX = cx;

        final float p = Math.max(0f, Math.min(1f, press.getValue()));
        final boolean dark = ColorUtils.calculateLuminance(surfaceBase | 0xFF000000) < 0.5f;

        // Tabs tinted with the accent color: ColorFilter.tint(accentColor) on the catalog's hidden row
        final int width = parent.getWidth(), heightPx = parent.getHeight();
        tabsNode.setPosition(0, 0, width, heightPx);
        if (tabsTint != accent) {
            tabsTint = accent;
            tabsNode.setRenderEffect(RenderEffect.createColorFilterEffect(
                new android.graphics.PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)));
        }
        RecordingCanvas rc = tabsNode.beginRecording(width, heightPx);
        tabs.drawTabs(rc);
        tabsNode.endRecording();

        float scale = AndroidUtilities.lerp(1f, PRESSED_SCALE, p);
        float scaleX = scale, scaleY = scale;
        final float v = (velocity / Math.max(w, 1f)) / 10f;
        scaleX /= 1f - Math.max(-0.2f, Math.min(0.2f, v * 0.75f));
        scaleY *= 1f - Math.max(-0.2f, Math.min(0.2f, v * 0.25f));

        final float left = cx - w / 2f;
        drawLeft = left;
        drawTop = top;
        this.backgroundDrawer = background;

        canvas.save();
        canvas.scale(scaleX, scaleY, cx, top + height / 2f);

        // Shadow(alpha = progress)
        if (p > 0f) {
            final float radius = AndroidUtilities.dpf2(24);
            shadowPaint.setColor(surfaceBase | 0xFF000000);
            shadowPaint.setShadowLayer(radius, 0, radius / 6f, Color.argb((int) (255 * 0.1f * p), 0, 0, 0));
            rect.set(left, top, left + w, top + height);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, shadowPaint);
        }

        // onDrawSurface: (light ? black : white) 10% fading out while pressed, plus black 3% while pressed
        final int rest = dark ? Color.WHITE : Color.BLACK;
        final int surface = ColorUtils.compositeColors(
            Color.argb((int) (255 * 0.03f * p), 0, 0, 0),
            Color.argb((int) (255 * 0.1f * (1f - p)), Color.red(rest), Color.green(rest), Color.blue(rest)));

        canvas.translate(left, top);
        glass.draw(canvas, w, height, height / 2f, AndroidUtilities.density,
            BackdropGlass.COLOR_NONE, 0f,
            AndroidUtilities.dpf2(10) * p,
            AndroidUtilities.dpf2(14) * p,
            false, true, dark,
            backdrop, surface, p, 1f);
        canvas.restore();
    }

    /** The canvas the background runnable passed to {@link #draw} must draw into. */
    public Canvas getBackgroundCanvas() {
        return backgroundDrawerCanvas;
    }

    public static boolean isSupported() {
        return GlassLayer.isSupported();
    }
}
