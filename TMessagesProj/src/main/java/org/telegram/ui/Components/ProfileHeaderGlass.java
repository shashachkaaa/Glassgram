package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import org.telegram.liquidglass.BackdropGlass;
import org.telegram.liquidglass.GlassLayer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

/**
 * Liquid Glass for the pieces drawn over the profile's header (the action buttons, the music
 * pill): glass made of what is under them, the header's color and pattern or the photo, blurred
 * and refracted at the edges like the bar's buttons above them.
 * <p>
 * What is under the view is recorded once per draw for all its pieces: the header, a sibling of
 * the view's parent, then the view's own siblings drawn before it. The view is redrawn when only
 * that changed (the gallery swiped, the header animating), at most every {@link #REFRESH_MS}.
 */
public class ProfileHeaderGlass {

    private static final long REFRESH_MS = 32;

    // Glass views drawn inside another one's backdrop (or the bar's) don't make glass there: it
    // would record the same pieces again, and it is under nothing that shows them
    private static int recordingDepth;

    private final View view;
    private final ViewBehindBackdrop behind;
    private View header;
    private View photo;
    private Object backdropNode;
    private boolean active, refreshPosted;
    private long recordedAt;
    private final float[] headerState = new float[8];
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shadowClip = new Path();
    private ViewTreeObserver tree;

    private final ViewTreeObserver.OnPreDrawListener watcher = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (active && !refreshPosted && isStale()) {
                final long wait = REFRESH_MS - (SystemClock.uptimeMillis() - recordedAt);
                if (wait <= 0) {
                    view.invalidate();
                } else {
                    refreshPosted = true;
                    view.postInvalidateDelayed(wait);
                }
            }
            return true;
        }
    };

    /** One piece of glass; each needs its own, it records its own backdrop. */
    public static class Piece {
        private Object layer;
    }

    public ProfileHeaderGlass(View view) {
        this.view = view;
        this.behind = new ViewBehindBackdrop(view);
    }

    /**
     * The gallery's blur under the photo, a sibling of the view drawn before it. Under the view it
     * is a heavy blur of the photo's mirrored bottom, smooth, with nothing for the lens to bend,
     * and the glass looked like a plain veil; the glass is made of the same mirror, sharp.
     */
    public void setPhoto(View photo) {
        this.photo = photo;
        view.invalidate();
    }

    /** The header drawn under the view, a sibling of its parent. */
    public void setHeader(View header) {
        this.header = header;
        watch(view.isAttachedToWindow());
        view.invalidate();
    }

    /** Called from the view's onAttachedToWindow and onDetachedFromWindow. */
    public void watch(boolean watch) {
        if (tree != null && tree.isAlive()) {
            tree.removeOnPreDrawListener(watcher);
        }
        tree = null;
        if (watch) {
            tree = view.getViewTreeObserver();
            tree.addOnPreDrawListener(watcher);
        }
    }

    /**
     * Whether the view's pieces are glass in this draw; if so, what is under the view is recorded
     * for them. Call once per draw, before {@link #draw}.
     */
    public boolean begin(Canvas canvas) {
        active = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) && canvas.isHardwareAccelerated();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && active && !isNested()) {
            record();
        }
        return active;
    }

    private static boolean isNested() {
        return recordingDepth > 0 || ViewBehindBackdrop.isAnyDrawing();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void record() {
        refreshPosted = false;
        recordedAt = SystemClock.uptimeMillis();
        final int w = view.getWidth(), h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (backdropNode == null) {
            backdropNode = new RenderNode("ProfileHeaderGlass");
        }
        final RenderNode node = (RenderNode) backdropNode;
        node.setPosition(0, 0, w, h);
        final RecordingCanvas recording = node.beginRecording(w, h);
        recordingDepth++;
        try {
            drawBehind(recording);
        } finally {
            recordingDepth--;
            node.endRecording();
        }
    }

    // What is under the view, with (0, 0) at its top-left corner
    private void drawBehind(Canvas canvas) {
        final View h = header;
        if (h != null && h.getVisibility() == View.VISIBLE && h.getAlpha() > 0f && view.getParent() instanceof View) {
            final View parent = (View) view.getParent();
            final int restore = canvas.save();
            canvas.translate(-(parent.getX() + view.getX()), -(parent.getY() + view.getY()));
            canvas.translate(h.getLeft(), h.getTop());
            canvas.concat(h.getMatrix());
            if (h.getAlpha() < 1f) {
                canvas.saveLayerAlpha(0, 0, h.getWidth(), h.getHeight(), (int) (255 * h.getAlpha()));
            }
            canvas.translate(-h.getScrollX(), -h.getScrollY());
            h.draw(canvas);
            canvas.restoreToCount(restore);
        }
        AndroidUtilities.rectTmp.set(0, 0, view.getWidth(), view.getHeight());
        behind.setArea(AndroidUtilities.rectTmp);
        behind.draw(canvas);
        drawMirroredPhoto(canvas);
        if (h != null) {
            final float[] s = headerState;
            s[0] = h.getLeft();
            s[1] = h.getTop();
            s[2] = h.getRight();
            s[3] = h.getBottom();
            s[4] = h.getTranslationX();
            s[5] = h.getTranslationY();
            s[6] = h.getAlpha();
            s[7] = h.getVisibility();
        }
    }

    private void drawMirroredPhoto(Canvas canvas) {
        final View p = photo;
        if (!(p instanceof ProfileGalleryBlurView) || p.getParent() != view.getParent() || p.getVisibility() != View.VISIBLE || p.getAlpha() <= 0f) {
            return;
        }
        final int restore = canvas.save();
        canvas.translate(p.getX() - view.getX(), p.getY() - view.getY());
        if (p.getAlpha() < 1f) {
            canvas.saveLayerAlpha(0, 0, p.getWidth(), p.getHeight(), (int) (255 * p.getAlpha()));
        }
        ((ProfileGalleryBlurView) p).drawSharpMirror(canvas);
        canvas.restoreToCount(restore);
    }

    private boolean isStale() {
        if (behind.isStale()) {
            return true;
        }
        final View h = header;
        if (h == null) {
            return false;
        }
        if (h.isDirty()) {
            return true;
        }
        final float[] s = headerState;
        return s[0] != h.getLeft() || s[1] != h.getTop() || s[2] != h.getRight() || s[3] != h.getBottom()
            || s[4] != h.getTranslationX() || s[5] != h.getTranslationY() || s[6] != h.getAlpha() || s[7] != h.getVisibility();
    }

    /**
     * Draws a piece of glass at rect, in the view's coordinates.
     *
     * @param white 1 when the content on it is white (over a colored header or a photo): a light
     *              veil that keeps the header's color; 0 under dark content: frosted white, as in iOS
     * @param photo 1 over the expanded photo: the glass is a little darker there, for the white
     *              content on it over a light photo, and shows by a brighter, wider rim
     */
    public void draw(Canvas canvas, Piece piece, RectF rect, float radius, float white, float alpha, float photo) {
        if (!active || rect.isEmpty() || alpha <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        radius = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        final boolean veil = white >= 0.5f;
        final int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        final boolean dark = ColorUtils.calculateLuminance(surface | 0xFF000000) < 0.5f;
        final int frosted = ColorUtils.setAlphaComponent(surface, (int) (255 * BackdropGlass.tintAlpha(dark)));
        final int tint = ColorUtils.blendARGB(
            ColorUtils.blendARGB(frosted, ColorUtils.setAlphaComponent(Color.WHITE, 0x2E), white),
            ColorUtils.setAlphaComponent(Color.BLACK, 0x24), photo);

        if (white < 1f) {
            // A soft shadow around the frosted glass, outside it so it does not darken it
            final float shadow = dpf2(10);
            shadowPaint.setColor(Color.BLACK);
            shadowPaint.setShadowLayer(shadow, 0, shadow / 5f, ColorUtils.setAlphaComponent(Color.BLACK, (int) (0x18 * (1f - white) * alpha)));
            shadowClip.rewind();
            shadowClip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipOutPath(shadowClip);
            canvas.drawRoundRect(rect, radius, radius, shadowPaint);
            canvas.restore();
        }

        if (isNested() || backdropNode == null) {
            tintPaint.setColor(ColorUtils.setAlphaComponent(tint, (int) (Color.alpha(tint) * alpha)));
            canvas.drawRoundRect(rect, radius, radius, tintPaint);
            return;
        }

        if (piece.layer == null) {
            piece.layer = new GlassLayer();
        }
        final RenderNode node = (RenderNode) backdropNode;
        final float left = rect.left, top = rect.top;
        final float density = AndroidUtilities.density;
        final int restore = alpha < 1f
            ? canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha))
            : canvas.save();
        canvas.translate(left, top);
        ((GlassLayer) piece.layer).draw(canvas, rect.width(), rect.height(), radius, density,
            dark || photo >= 0.5f ? BackdropGlass.COLOR_CONTROLS_DARK : BackdropGlass.COLOR_CONTROLS_LIGHT,
            (veil ? 4f : BackdropGlass.BlurRadius) * density,
            Math.max(1, Math.min(dp(12), Math.min(rect.width(), rect.height()) / 4)), dpf2(24),
            false, true, dark,
            backdrop -> {
                backdrop.translate(-left, -top);
                backdrop.drawRenderNode(node);
            },
            tint, AndroidUtilities.lerp(veil ? 0.8f : 1f, 1f, photo), AndroidUtilities.lerp(1f, 1.8f, photo));
        canvas.restoreToCount(restore);
    }
}
