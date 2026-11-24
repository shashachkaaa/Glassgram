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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
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
 * that changed. Gallery frames refresh with the photo; other backdrop changes are throttled.
 */
public class ProfileHeaderGlass {

    private static final long REFRESH_MS = 32;

    // A glass view drawn inside another one's backdrop (or the bar's) does not record what is under
    // it again there, it uses what it recorded last. It still draws its glass: the view's own
    // picture can be recorded in there (the bar records the header in every frame over a photo,
    // and the gallery redraws the buttons from inside it), and that picture is what stays on screen
    private static int recordingDepth;

    private final View view;
    private final ViewBehindBackdrop behind;
    private View header;
    private View under;
    private View photo;
    private Object backdropNode;
    private boolean active, refreshPosted;
    /**
     * Set by the watcher before a frame when what is under the view was drawn anew since it was
     * recorded (a sibling's or the header's picture changed in place): recorded again at most
     * every REFRESH_MS, see {@link #needsRecording}.
     */
    private boolean backdropChanged = true;
    /** Whether the page under the header went into the last recording. */
    private boolean recordedUnder;
    private long recordedAt;
    private long recordedPhotoGeneration;
    private final float[] headerState = new float[10];
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shadowClip = new Path();
    private ViewTreeObserver tree;

    private final ViewTreeObserver.OnPreDrawListener watcher = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            if (!active || !view.isShown()) {
                return true;
            }
            if (photoGeneration() != recordedPhotoGeneration) {
                // The live avatar already requested this frame. Refresh the glass in the same traversal.
                backdropChanged = true;
                view.invalidate();
            } else if (!refreshPosted && isStale()) {
                backdropChanged = true;
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

    /**
     * The page under the header, a sibling of the view's parent drawn before the header (the
     * profile's list, its cards), with its parent's background: for glass taken down over it.
     * Null when no piece is there: recording the page costs.
     */
    public void setUnder(@Nullable View under) {
        this.under = under;
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
        refreshPosted = false;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && active && !isNested() && needsRecording()) {
            record();
        }
        return active;
    }

    /**
     * Whether what is under the view has to be recorded again for this draw. A view drawn anew
     * for itself (a pill's playing bars, the gifts flying, a button pressed) over a still header
     * keeps what it recorded: recording the header and everything under the view every frame
     * cost the most. Anything under it moved (a scroll, the view itself, the photo) is recorded at
     * once, or the glass would show it out of place; a picture under it drawn anew in place, at
     * most every REFRESH_MS, and so the page under it while it is wanted. Without the watcher (a
     * view that asks for its glass only while it moves), every draw records, as before.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private boolean needsRecording() {
        if (backdropNode == null || tree == null || (under != null) != recordedUnder) {
            return true;
        }
        final RenderNode node = (RenderNode) backdropNode;
        if (node.getWidth() != view.getWidth() || node.getHeight() != view.getHeight()) {
            return true;
        }
        if (photoGeneration() != recordedPhotoGeneration || behind.isMoved() || isHeaderMoved()) {
            return true;
        }
        // the page under the header is not watched: it is taken again as often, while it is wanted
        return (backdropChanged || under != null) && SystemClock.uptimeMillis() - recordedAt >= REFRESH_MS;
    }

    private static boolean isNested() {
        return recordingDepth > 0 || ViewBehindBackdrop.isAnyDrawing();
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void record() {
        refreshPosted = false;
        backdropChanged = false;
        recordedUnder = under != null;
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
            recordedPhotoGeneration = photoGeneration();
        }
    }

    // What is under the view, with (0, 0) at its top-left corner
    private void drawBehind(Canvas canvas) {
        drawUnder(canvas);
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
        if (view.getParent() instanceof View) {
            // where the header is drawn from depends on where the view's parent is
            headerState[8] = ((View) view.getParent()).getX();
            headerState[9] = ((View) view.getParent()).getY();
        }
    }

    // The page under the header, see setUnder: its parent's background, then the page itself
    private void drawUnder(Canvas canvas) {
        final View u = under;
        if (u == null || u.getVisibility() != View.VISIBLE || u.getAlpha() <= 0f || !(view.getParent() instanceof View)) {
            return;
        }
        final View parent = (View) view.getParent();
        final int restore = canvas.save();
        canvas.translate(-(parent.getX() + view.getX()), -(parent.getY() + view.getY()));
        if (u.getParent() instanceof View) {
            final Drawable background = ((View) u.getParent()).getBackground();
            if (background != null) {
                background.draw(canvas);
            }
        }
        canvas.translate(u.getLeft(), u.getTop());
        canvas.concat(u.getMatrix());
        if (u.getAlpha() < 1f) {
            canvas.saveLayerAlpha(0, 0, u.getWidth(), u.getHeight(), (int) (255 * u.getAlpha()));
        }
        canvas.translate(-u.getScrollX(), -u.getScrollY());
        u.draw(canvas);
        canvas.restoreToCount(restore);
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

    private long photoGeneration() {
        final View p = photo;
        if (p instanceof ProfileGalleryBlurView && p.getVisibility() == View.VISIBLE && p.getAlpha() > 0f) {
            return ((ProfileGalleryBlurView) p).getContentGeneration();
        }
        return -1;
    }

    private boolean isStale() {
        if (behind.isStale()) {
            return true;
        }
        final View h = header;
        if (h != null && h.isDirty()) {
            return true;
        }
        return isHeaderMoved();
    }

    /** Whether the header, or the view's parent it is drawn against, moved since the recording. */
    private boolean isHeaderMoved() {
        final float[] s = headerState;
        if (view.getParent() instanceof View) {
            final View parent = (View) view.getParent();
            if (s[8] != parent.getX() || s[9] != parent.getY()) {
                return true;
            }
        }
        final View h = header;
        if (h == null) {
            return false;
        }
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
    public void draw(Canvas canvas, Piece piece, LiquidPressEffect press, RectF rect, float radius, float white, float alpha, float photo) {
        draw(canvas, piece, press, rect, radius, white, alpha, photo, null);
    }

    /** Extra layers already drawn behind this piece, in the view's coordinates. */
    public void draw(Canvas canvas, Piece piece, LiquidPressEffect press, RectF rect, float radius, float white, float alpha,
                     float photo, @Nullable GlassLayer.Backdrop behindPiece) {
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

        final RenderNode node = (RenderNode) backdropNode;
        final float left = rect.left, top = rect.top;
        final GlassLayer.Backdrop source = backdrop -> {
            backdrop.translate(-left, -top);
            backdrop.drawRenderNode(node);
            if (behindPiece != null) behindPiece.draw(backdrop);
        };

        // During a sibling's capture use the static glass path. Replaying an interactive
        // button into the node used as its own backdrop would create a display-list cycle.
        if (node != null && !isNested()) {
            if (press.drawGlass(canvas, left, top, rect.right, rect.bottom, radius, source, tint, 0, Math.round(alpha * 255))) {
                return;
            }
        }

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

        if (node == null) {
            tintPaint.setColor(ColorUtils.setAlphaComponent(tint, (int) (Color.alpha(tint) * alpha)));
            canvas.drawRoundRect(rect, radius, radius, tintPaint);
            return;
        }

        if (piece.layer == null) {
            piece.layer = new GlassLayer();
        }
        final float density = AndroidUtilities.density;
        final int restore = alpha < 1f
            ? canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha))
            : canvas.save();
        canvas.translate(left, top);
        ((GlassLayer) piece.layer).draw(canvas, rect.width(), rect.height(), radius, density,
            dark || photo >= 0.5f ? BackdropGlass.COLOR_CONTROLS_DARK : BackdropGlass.COLOR_CONTROLS_LIGHT,
            // Over the photo the mirror under the glass is blurred more: sharp, it showed what the
            // gallery's blur around hides, like the edge of a round picture on a white ground
            AndroidUtilities.lerp(veil ? 4f : BackdropGlass.BlurRadius, 10f, photo) * density,
            Math.max(1, Math.min(dp(12), Math.min(rect.width(), rect.height()) / 4)), dpf2(24),
            false, true, dark, source,
            tint, AndroidUtilities.lerp(veil ? 0.8f : 1f, 1f, photo), AndroidUtilities.lerp(1f, 1.8f, photo));
        canvas.restoreToCount(restore);
    }

    /**
     * A glass bubble: clear, the whole of it one lens. What is under it shows through sharp, a
     * little bigger, and more and more stretched towards the edge, as through a drop of water, its
     * glass tinted with a color of its own (the gifts take the color of their backdrop). Drawn
     * without the press effect's button, whose lens bends only a band at the edge: the bubble
     * wobbles and stretches by itself.
     *
     * @param magnify how much bigger what is under the bubble shows through its middle
     */
    public void drawSphere(Canvas canvas, Piece piece, RectF rect, int tint, float alpha, float magnify,
                           @Nullable GlassLayer.Backdrop behindPiece) {
        drawSphere(canvas, piece, rect, tint, alpha, magnify, 0f, behindPiece);
    }

    /**
     * A panel of glass, as iOS's menus: what is under it blurred, bent at its edges by a lens of
     * this depth (0 for none), its own color over it. A radius of 0 gives a plain rectangle, for
     * the caller to clip to a shape of its own.
     */
    public void drawPanel(Canvas canvas, Piece piece, RectF rect, float radius, int tint, float alpha, float blur, float lens) {
        drawPanel(canvas, piece, rect, radius, tint, alpha, blur, lens, null);
    }

    /** As above, with more drawn under the glass, in the view's coordinates: blurred and bent with what is under it. */
    public void drawPanel(Canvas canvas, Piece piece, RectF rect, float radius, int tint, float alpha, float blur, float lens,
                          @Nullable GlassLayer.Backdrop behindPiece) {
        if (!active || rect.isEmpty() || alpha <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        final float width = rect.width(), height = rect.height();
        final RenderNode node = (RenderNode) backdropNode;
        if (node == null) {
            tintPaint.setColor(ColorUtils.setAlphaComponent(tint, (int) (Color.alpha(tint) * alpha)));
            canvas.drawRoundRect(rect, radius, radius, tintPaint);
            return;
        }
        final float left = rect.left, top = rect.top;
        final GlassLayer.Backdrop source = backdrop -> {
            backdrop.translate(-left, -top);
            backdrop.drawRenderNode(node);
            if (behindPiece != null) behindPiece.draw(backdrop);
        };
        if (piece.layer == null) {
            piece.layer = new GlassLayer();
        }
        final float depth = Math.max(0f, Math.min(lens, Math.min(width, height) / 2f));
        final int restore = alpha < 1f
            ? canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha))
            : canvas.save();
        canvas.translate(left, top);
        ((GlassLayer) piece.layer).draw(canvas, width, height, radius, AndroidUtilities.density,
            BackdropGlass.COLOR_NONE,
            blur < .5f ? 0f : Math.round(blur * 2f) / 2f, depth, depth * .6f,
            false, false, false, source,
            tint, depth > 0f ? .6f : 0f, 1f);
        canvas.restoreToCount(restore);
    }

    /**
     * What a piece of glass holds, seen through its lens alone, nothing under it: content laid in
     * the glass, bent at its edges as through thick glass curving away there (the text of iOS's
     * menus as they spread). No blur, no color, no rim; draws nothing when the glass is off.
     *
     * @return whether it drew: if not, the caller draws the content plainly
     */
    public boolean drawLensed(Canvas canvas, Piece piece, RectF rect, float radius, float lensHeight, float lensAmount,
                              GlassLayer.Backdrop content) {
        if (!active || rect.isEmpty() || content == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        final float width = rect.width(), height = rect.height();
        final float left = rect.left, top = rect.top;
        final GlassLayer.Backdrop source = backdrop -> {
            backdrop.translate(-left, -top);
            content.draw(backdrop);
        };
        if (piece.layer == null) {
            piece.layer = new GlassLayer();
        }
        // in steps of half a pixel: the effect is built anew only when they change by that
        final float bandHeight = Math.round(Math.max(0f, Math.min(lensHeight, Math.min(width, height) / 2f)) * 2f) / 2f;
        final float amount = Math.round(Math.max(0f, lensAmount) * 2f) / 2f;
        final int restore = canvas.save();
        canvas.translate(left, top);
        ((GlassLayer) piece.layer).draw(canvas, width, height, radius, AndroidUtilities.density,
            BackdropGlass.COLOR_NONE, 0f, bandHeight, amount,
            true, false, false, source,
            0, 0f, 1f);
        canvas.restoreToCount(restore);
        return true;
    }

    /**
     * @param blur px: a thick ball of glass blurs what is behind it; 0 for a clear one, through
     *             which text stays text
     */
    public void drawSphere(Canvas canvas, Piece piece, RectF rect, int tint, float alpha, float magnify, float blur,
                           @Nullable GlassLayer.Backdrop behindPiece) {
        if (!active || rect.isEmpty() || alpha <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        final float width = rect.width(), height = rect.height();
        final float radius = Math.min(width, height) / 2f;
        final RenderNode node = (RenderNode) backdropNode;
        if (node == null) {
            tintPaint.setColor(ColorUtils.setAlphaComponent(tint, (int) (Color.alpha(tint) * alpha)));
            canvas.drawRoundRect(rect, radius, radius, tintPaint);
            return;
        }
        final float left = rect.left, top = rect.top;
        final GlassLayer.Backdrop source = backdrop -> {
            backdrop.scale(magnify, magnify, width / 2f, height / 2f);
            backdrop.translate(-left, -top);
            backdrop.drawRenderNode(node);
            if (behindPiece != null) behindPiece.draw(backdrop);
        };
        if (piece.layer == null) {
            piece.layer = new GlassLayer();
        }
        final int restore = alpha < 1f
            ? canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, (int) (255 * alpha))
            : canvas.save();
        canvas.translate(left, top);
        // The lens reaches the middle and bends less than its own depth: the picture stays the
        // right way round up to the very edge. No color controls, no rainbow fringe, and, unless
        // asked for, no blur: text under the bubble stays text. The blur goes in steps of half a
        // pixel, the glass's effect is not built anew for every frame it changes by a hair
        ((GlassLayer) piece.layer).draw(canvas, width, height, radius, AndroidUtilities.density,
            BackdropGlass.COLOR_NONE,
            blur < .5f ? 0f : Math.round(blur * 2f) / 2f, radius, radius * .3f,
            false, false, false, source,
            tint, 0f, 1f);
        canvas.restoreToCount(restore);
    }
}
