package org.telegram.ui.Components;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.liquidglass.BackdropGlass;
import org.telegram.liquidglass.GlassLayer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;

import java.util.function.IntSupplier;

/**
 * Background of menus, dialogs and sheets as Liquid Glass, like ward's {@code glassPanel}:
 * the app window behind is blurred (6dp), refracted by a wide depth lens without dispersion,
 * toned and outlined with the library's highlight.
 * <p>
 * These live in their own windows, so the backdrop is the activity's window, recorded from
 * its decor view and placed by screen coordinates (ward does the same for the same reason).
 * A panel that lives inside the activity window itself would record itself, which is a loop;
 * it gets an opaque fill instead.
 * <p>
 * Wraps the old 9-patch only for its padding, so layouts built around it do not move.
 */
public class LiquidPanelDrawable extends Drawable {

    private final Drawable original;
    private final Rect padding = new Rect();
    private final float topRadius, bottomRadius;
    private final IntSupplier color;
    private View host;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private int alpha = 255;

    private Object glass;
    private Object decorNode;
    private final int[] hostLocation = new int[2];
    private final int[] decorLocation = new int[2];
    private View drawingDecor;
    private float backdropDx, backdropDy;

    // The recorded backdrop is placed for where the panel was when it was drawn. Sheets slide
    // in by translation, which does not redraw their background, so the panel watches its
    // position every frame and redraws when it moved.
    private ViewTreeObserver observedTree;
    private View observedHost;
    private final int[] drawnLocation = new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
    private final int[] checkLocation = new int[2];
    private final ViewTreeObserver.OnPreDrawListener positionWatcher = () -> {
        final View h = observedHost;
        if (h != null && h.isAttachedToWindow()) {
            h.getLocationOnScreen(checkLocation);
            if (checkLocation[0] != drawnLocation[0] || checkLocation[1] != drawnLocation[1]) {
                invalidateSelf();
                h.invalidate();
            }
        }
        return true;
    };

    private void watchPosition(View h) {
        final ViewTreeObserver tree = h.getViewTreeObserver();
        if (observedHost == h && observedTree == tree && tree.isAlive()) {
            return;
        }
        if (observedTree != null && observedTree.isAlive()) {
            observedTree.removeOnPreDrawListener(positionWatcher);
        }
        observedHost = h;
        observedTree = tree;
        tree.addOnPreDrawListener(positionWatcher);
    }

    /** Whether panels are glass at all; when not, they keep the opaque fill. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
    }

    public static Drawable wrap(Drawable original, float topRadius, float bottomRadius, IntSupplier color) {
        return new LiquidPanelDrawable(original, topRadius, bottomRadius, color);
    }

    public LiquidPanelDrawable(Drawable original, float topRadius, float bottomRadius, IntSupplier color) {
        this.original = original;
        this.topRadius = topRadius;
        this.bottomRadius = bottomRadius;
        this.color = color;
        if (original != null) {
            original.getPadding(padding);
        }
    }

    /** The view drawing this drawable, when it is drawn by hand rather than as a background. */
    public void setHost(View host) {
        this.host = host;
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        padding.set(this.padding);
        return true;
    }

    private View host() {
        if (host != null) {
            return host;
        }
        final Callback callback = getCallback();
        return callback instanceof View ? (View) callback : null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect b = getBounds();
        rect.set(b.left + padding.left, b.top + padding.top, b.right - padding.right, b.bottom - padding.bottom);
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        final float maxRadius = Math.min(rect.width(), rect.height()) / 2f;
        final float tr = Math.min(topRadius, maxRadius), br = Math.min(bottomRadius, maxRadius);
        radii[0] = radii[1] = radii[2] = radii[3] = tr;
        radii[4] = radii[5] = radii[6] = radii[7] = br;
        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);

        final int baseColor = color.getAsInt();
        final boolean dark = ColorUtils.calculateLuminance(baseColor | 0xFF000000) < 0.5f;

        // Shadow around the panel only, so it never shows through the glass
        final float shadow = AndroidUtilities.dpf2(12);
        shadowPaint.setColor(baseColor | 0xFF000000);
        shadowPaint.setShadowLayer(shadow, 0, shadow / 4f, Color.argb((int) (0x40 * alpha / 255f), 0, 0, 0));
        canvas.save();
        if (Build.VERSION.SDK_INT >= 26) {
            canvas.clipOutPath(path);
        }
        canvas.drawPath(path, shadowPaint);
        canvas.restore();

        if (!drawGlass(canvas, baseColor, dark, tr)) {
            fillPaint.setColor(ColorUtils.setAlphaComponent(baseColor, (int) (Color.alpha(baseColor) * alpha / 255f)));
            canvas.drawPath(path, fillPaint);
        }
    }

    private boolean drawGlass(Canvas canvas, int baseColor, boolean dark, float radius) {
        if (!isSupported() || !canvas.isHardwareAccelerated()) {
            return false;
        }
        final View host = host();
        if (host == null || !host.isAttachedToWindow()) {
            return false;
        }
        final Activity activity = AndroidUtilities.findActivity(host.getContext());
        if (activity == null || activity.getWindow() == null) {
            return false;
        }
        final View decor = activity.getWindow().getDecorView();
        if (decor == null || decor.getWidth() <= 0 || decor == host.getRootView()) {
            // Inside the activity window the backdrop would contain this panel: a loop
            return false;
        }

        if (glass == null) {
            glass = new GlassLayer();
            decorNode = new RenderNode("LiquidPanelBackdrop");
        }
        final RenderNode node = (RenderNode) decorNode;
        node.setPosition(0, 0, decor.getWidth(), decor.getHeight());
        final RecordingCanvas rc = node.beginRecording(decor.getWidth(), decor.getHeight());
        decor.draw(rc);
        node.endRecording();

        host.getLocationOnScreen(hostLocation);
        drawnLocation[0] = hostLocation[0];
        drawnLocation[1] = hostLocation[1];
        watchPosition(host);
        decor.getLocationOnScreen(decorLocation);
        backdropDx = decorLocation[0] - (hostLocation[0] + rect.left);
        backdropDy = decorLocation[1] - (hostLocation[1] + rect.top);

        // ward's glassPanel: the window is denser than in-screen glass so text stays readable
        final int tint = ColorUtils.setAlphaComponent(baseColor, (int) (255 * (dark ? 0.62f : 0.68f) * alpha / 255f));

        canvas.save();
        canvas.translate(rect.left, rect.top);
        ((GlassLayer) glass).draw(canvas, rect.width(), rect.height(), radius, AndroidUtilities.density,
            dark ? BackdropGlass.COLOR_CONTROLS_DARK : BackdropGlass.COLOR_CONTROLS_LIGHT,
            AndroidUtilities.dpf2(BackdropGlass.PanelBlurRadius),
            AndroidUtilities.dpf2(24), AndroidUtilities.dpf2(24),
            true, false, dark,
            backdrop, tint, (dark ? 0.7f : 1f) * alpha / 255f, 1f);
        canvas.restore();
        return true;
    }

    private final GlassLayer.Backdrop backdrop = c -> {
        c.translate(backdropDx, backdropDy);
        c.drawRenderNode((RenderNode) decorNode);
    };

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // The color comes from the supplier; callers used the filter only to tint the 9-patch
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
