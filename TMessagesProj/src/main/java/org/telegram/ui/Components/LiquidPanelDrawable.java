package org.telegram.ui.Components;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;

import org.telegram.liquidglass.BackdropGlass;
import org.telegram.liquidglass.GlassLayer;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;

import java.util.function.IntSupplier;

/**
 * Background of menus, dialogs and sheets as Liquid Glass, like ward's {@code glassPanel}:
 * the app window behind is blurred (6dp), refracted by a wide depth lens without dispersion,
 * toned and outlined with the library's highlight.
 * <p>
 * These live in their own windows, so the backdrop is the activity's window, recorded from
 * its decor view and placed by screen coordinates (ward does the same for the same reason).
 * A panel that lives inside the activity window itself would record itself, which is a loop;
 * it gets an opaque fill instead. Menus and dialogs show it live (see watchLive); sheets, which
 * are large, a picture of it (see requestSnapshot).
 * <p>
 * Wraps the old 9-patch only for its padding, so layouts built around it do not move.
 */
public class LiquidPanelDrawable extends Drawable {

    private final Drawable original;
    private final Rect padding = new Rect();
    private float topRadius, bottomRadius;
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
        if (h == null || !h.isAttachedToWindow()) {
            // The host left its window: stop watching, or the window's observer keeps this panel,
            // its host and what that holds (an in-window menu holds its screen) for good.
            // The next draw watches again.
            if (observedTree != null && observedTree.isAlive()) {
                observedTree.removeOnPreDrawListener(this.positionWatcher);
            }
            observedTree = null;
            observedHost = null;
        } else {
            layoutLocationOnScreen(h, checkLocation);
            if (checkLocation[0] != drawnLocation[0] || checkLocation[1] != drawnLocation[1] || scaleChanged(h)) {
                invalidateSelf();
                h.invalidate();
            }
        }
        return true;
    };

    /*
     * Where a view is on screen by its layout and translations, without the scales of it and its
     * parents. The photo viewer zooms the sheet behind it in every frame of its opening and closing;
     * with the scale, getLocationOnScreen moved in each of them and the glass was made anew in every
     * frame, though the glass zooms with the sheet and the screen behind is zoomed alike.
     */
    private final RectF layoutRect = new RectF();

    private void layoutLocationOnScreen(View v, int[] out) {
        final View root = v.getRootView();
        if (v == root || !org.telegram.ui.Components.chat.ViewPositionWatcher.computeRectInParent(v, root, layoutRect)) {
            v.getLocationOnScreen(out);
            return;
        }
        root.getLocationOnScreen(out);
        out[0] += Math.round(layoutRect.left);
        out[1] += Math.round(layoutRect.top);
    }

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
        // glassGram's own switch (Appearance → Glass menus and windows), which the team's rules may also turn off
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)
                && org.telegram.messenger.GlassgramConfig.glassPanels;
    }

    /**
     * A sheet's background as Liquid Glass: the old {@code sheet_shadow_round} 9-patch (kept for its
     * padding) with round top corners. A color set later through a color filter or tint, as sheets
     * did for the 9-patch, takes over from the supplier.
     */
    public static LiquidPanelDrawable sheet(Context context, IntSupplier color) {
        return new LiquidPanelDrawable(context.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate(), AndroidUtilities.dp(16), 0, color);
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

    /** For panels whose corners change, like a sheet that loses them at the top of the screen. */
    public void setRadius(float topRadius, float bottomRadius) {
        if (this.topRadius != topRadius || this.bottomRadius != bottomRadius) {
            this.topRadius = topRadius;
            this.bottomRadius = bottomRadius;
            invalidateSelf();
        }
    }

    /** The view drawing this drawable, when it is drawn by hand rather than as a background. */
    public void setHost(View host) {
        this.host = host;
    }

    /*
     * Menus and dialogs grow and shrink by their own scale as they open and close. Their glass
     * places the screen behind by where the panel really is on screen, scales included, so the
     * blurred screen stays put under them instead of a still picture of it stretching with them.
     * Sheets keep ignoring scales: the photo viewer zooms them together with the screen behind.
     */
    private boolean followScale;
    private final Matrix screenMatrix = new Matrix();
    private final Matrix backdropMatrix = new Matrix();
    private final Matrix glassMatrix = new Matrix();
    private final Matrix drawnScreenMatrix = new Matrix();
    private final Matrix checkScreenMatrix = new Matrix();

    public void setFollowScale(boolean follow) {
        followScale = follow;
    }

    // a method, as the watcher above may not read the fields declared here
    private boolean scaleChanged(View h) {
        if (!followScale) {
            return false;
        }
        checkScreenMatrix.reset();
        h.transformMatrixToGlobal(checkScreenMatrix);
        return !checkScreenMatrix.equals(drawnScreenMatrix);
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

        final int baseColor = hasFilterColor ? filterColor : color.getAsInt();
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
            // The glass is made of a picture of it (see requestSnapshot), or, where that fails, of
            // a texture of it, so the chat with its own glass isn't redrawn whole for the panel;
            // a live panel draws only what lies under it (see updateLiveNode)
            ((RenderNode) decorNode).setUseCompositingLayer(snapshotFailed && !isLive(), null);
        }
        final RenderNode node = (RenderNode) decorNode;
        decor.getLocationOnScreen(decorLocation);
        layoutLocationOnScreen(host, hostLocation);
        // Recording the screen and the windows under the panel costs the UI thread a whole frame of
        // their drawing; a panel sliding or fading over it doesn't need it anew in every frame
        final long now = android.os.SystemClock.uptimeMillis();
        final boolean resized = node.getWidth() != decor.getWidth() || node.getHeight() != decor.getHeight();
        // A menu or a dialog growing or shrinking as it opens or closes moves too: the screen behind
        // is recorded again once it stands still, not in the middle of that, where recording it (and
        // drawing its animations once more, out of their frame) made what moves behind it stutter
        final boolean moved = hostLocation[0] != drawnLocation[0] || hostLocation[1] != drawnLocation[1] || scaleChanged(host);
        // drawn again after a while unseen, as under the photo viewer: that frame records the whole sheet anew already
        final boolean afterGap = now - lastGlassDrawAt > BACKDROP_REFRESH_MS;
        lastGlassDrawAt = now;
        final boolean stale = now - backdropRecordedAt >= BACKDROP_REFRESH_MS;
        if (isLive()) {
            // The recording holds the windows' views themselves, so it shows them as they are now:
            // recorded again only when the windows behind are no longer the same views
            final int signature = decorSignature(decor);
            if (!backdropRecorded || resized || !node.hasDisplayList() || signature != liveDecorSignature) {
                liveDecorSignature = signature;
                backdropRecorded = true;
                backdropRecordedAt = now;
                recordDecor(node, activity, decor, host);
                backdropVersion++;
            }
            watchLive(decor, host);
        } else if (!backdropRecorded || resized || snapshot == null && !node.hasDisplayList()
                || stale && (snapshot == null || !moved && (!afterGap || refreshRequested))) {
            refreshRequested = false;
            backdropRecorded = true;
            backdropRecordedAt = now;
            recordDecor(node, activity, decor, host);
            if (resized) {
                dropSnapshot();
            }
            // Until its picture comes the glass is made of the live recording; after that the old
            // picture stays until the new one is ready
            if (!requestSnapshot(host, decor.getWidth(), decor.getHeight()) || snapshot == null) {
                backdropVersion++;
            }
        } else if (stale && !refreshPosted) {
            // Moving, or just shown again: the old picture stays, and a new one is taken once the
            // panel stands still, in a frame of its own
            refreshPosted = true;
            snapshotHandler.postDelayed(refreshBackdrop, BACKDROP_REFRESH_MS);
        }

        drawnLocation[0] = hostLocation[0];
        drawnLocation[1] = hostLocation[1];
        watchPosition(host);
        backdropDx = decorLocation[0] - (hostLocation[0] + rect.left);
        backdropDy = decorLocation[1] - (hostLocation[1] + rect.top);
        boolean scaled = false;
        if (followScale) {
            screenMatrix.reset();
            host.transformMatrixToGlobal(screenMatrix);
            drawnScreenMatrix.set(screenMatrix);
            // From the decor's points to the panel's own: where the panel draws what is on screen under it
            if (screenMatrix.invert(backdropMatrix)) {
                backdropMatrix.preTranslate(decorLocation[0], decorLocation[1]);
                backdropMatrix.postTranslate(-rect.left, -rect.top);
                scaled = true;
            }
        }
        if (scaled != backdropScaled) {
            backdropScaled = scaled;
            glassVersion = -1;
        }
        if (isLive()) {
            updateLiveNode(node);
        }

        // ward's glassPanel: the window is denser than in-screen glass so text stays readable
        // The panel's alpha goes on the finished glass below, so fading doesn't redo it
        final int tint = ColorUtils.setAlphaComponent(baseColor, (int) (255 * (dark ? 0.62f : 0.68f)));

        // The finished glass is kept as a texture: the blur and the lens are worked out again only
        // when the panel moved, the backdrop was recorded anew, or its shape or color changed, not
        // whenever the sheet's content redraws (a gallery scrolling in it, a photo opening over it)
        if (glassNode == null) {
            glassNode = new RenderNode("LiquidPanelGlass");
            ((RenderNode) glassNode).setUseCompositingLayer(true, null);
        }
        final RenderNode gn = (RenderNode) glassNode;
        final int w = (int) Math.ceil(rect.width()), h = (int) Math.ceil(rect.height());
        // (and when HWUI dropped its display list while the window was hidden: it would draw nothing)
        if (glassVersion != backdropVersion || !gn.hasDisplayList() || gn.getWidth() != w || gn.getHeight() != h || glassRadius != radius
                || glassTint != tint || glassDark != dark || glassDx != backdropDx || glassDy != backdropDy
                || backdropScaled && !glassMatrix.equals(backdropMatrix)) {
            glassVersion = backdropVersion;
            glassMatrix.set(backdropMatrix);
            glassRadius = radius;
            glassTint = tint;
            glassDark = dark;
            glassDx = backdropDx;
            glassDy = backdropDy;
            gn.setPosition(0, 0, w, h);
            // The picture of the screen comes blurred already (see requestSnapshot): a menu or a
            // dialog following its scale makes its glass anew in each frame of its opening and
            // closing, and blurring the screen in each of them held up every window's frames
            final float blur = snapshot != null || isLive() && liveNode != null ? 0f : AndroidUtilities.dpf2(BackdropGlass.PanelBlurRadius);
            final RecordingCanvas gc = gn.beginRecording(w, h);
            try {
                ((GlassLayer) glass).draw(gc, rect.width(), rect.height(), radius, AndroidUtilities.density,
                    dark ? BackdropGlass.COLOR_CONTROLS_DARK : BackdropGlass.COLOR_CONTROLS_LIGHT,
                    blur,
                    AndroidUtilities.dpf2(24), AndroidUtilities.dpf2(24),
                    true, false, dark,
                    backdrop, tint, dark ? 0.7f : 1f, 1f);
            } finally {
                gn.endRecording();
            }
        }
        gn.setAlpha(alpha / 255f);
        canvas.save();
        canvas.translate(rect.left, rect.top);
        canvas.drawRenderNode(gn);
        canvas.restore();
        return true;
    }

    private Object glassNode;
    private boolean backdropScaled;
    private int backdropVersion, glassVersion = -1;
    private float glassRadius = -1, glassDx = Float.NaN, glassDy = Float.NaN;
    private int glassTint;
    private boolean glassDark;

    // How old the recorded backdrop may get before a redraw of the panel records it again
    private static final long BACKDROP_REFRESH_MS = 250;
    private boolean backdropRecorded;
    private long backdropRecordedAt;
    private long lastGlassDrawAt;
    private boolean refreshPosted;
    // the delayed redraw that takes the new picture: a gap of its own must not put it off again
    private boolean refreshRequested;
    private final Runnable refreshBackdrop = () -> {
        refreshPosted = false;
        refreshRequested = true;
        final View h = host();
        if (h != null && h.isAttachedToWindow()) {
            h.invalidate();
        }
    };

    /** Records the activity's window and the windows between it and the panel into [node]. */
    private void recordDecor(RenderNode node, Activity activity, View decor, View host) {
        node.setPosition(0, 0, decor.getWidth(), decor.getHeight());
        node.setClipToBounds(false);
        // The windows drawn here use the shared scratch rects too; a sheet that set one before drawing
        // its panel and used it after (to clip its content) got the windows' rect and showed nothing
        savedRectTmp.set(AndroidUtilities.rectTmp);
        savedRectTmp2.set(AndroidUtilities.rectTmp2);
        final RecordingCanvas rc = node.beginRecording(decor.getWidth(), decor.getHeight());
        try {
            decor.draw(rc);
            drawWindowsBelow(rc, activity, decor, host.getRootView());
        } finally {
            node.endRecording();
            AndroidUtilities.rectTmp.set(savedRectTmp);
            AndroidUtilities.rectTmp2.set(savedRectTmp2);
        }
    }

    /*
     * Menus and dialogs show the screen behind them as it is: a chat scrolling under a menu opened
     * over it, a sticker, a video or a GIF playing there, instead of a picture of the moment they
     * opened. The recorded window holds its views themselves, so what is drawn of it is always
     * their latest drawing; only what lies under the panel is drawn, at half size, and blurred
     * there, and the panel is drawn again whenever the window behind draws. Sheets, as large as
     * half the screen, keep the picture (see requestSnapshot).
     */
    private static final int LIVE_SCALE = 2;
    private static final int LIVE_MARGIN_DP = 48;
    private Object liveNode;
    private final RectF liveArea = new RectF();
    private final RectF liveDrawnArea = new RectF();
    private boolean liveDirty;
    private int liveDecorSignature;
    private View liveHost;
    private ViewTreeObserver liveTree;
    private boolean liveDetachWatched;
    private final ViewTreeObserver.OnDrawListener liveWatcher = this::onLiveWindowDraw;
    private final Runnable unwatchLive = this::unwatchLive;

    private boolean isLive() {
        // the panels that follow their scale are the menus and the dialogs
        return followScale;
    }

    private void onLiveWindowDraw() {
        final View h = liveHost;
        if (h == null || !h.isAttachedToWindow()) {
            // not from inside the window's drawing, which may not lose a listener
            snapshotHandler.post(unwatchLive);
            return;
        }
        liveDirty = true;
        h.postInvalidateOnAnimation();
    }

    private void watchLive(View decor, View host) {
        liveHost = host;
        if (!liveDetachWatched) {
            liveDetachWatched = true;
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    // recorded anew when shown again; the window behind no longer keeps the panel
                    backdropRecorded = false;
                    snapshotHandler.post(unwatchLive);
                }
            });
        }
        final ViewTreeObserver tree = decor.getViewTreeObserver();
        if (liveTree == tree && tree.isAlive()) {
            return;
        }
        unwatchLive();
        liveTree = tree;
        tree.addOnDrawListener(liveWatcher);
    }

    private void unwatchLive() {
        if (liveTree != null && liveTree.isAlive()) {
            liveTree.removeOnDrawListener(liveWatcher);
        }
        liveTree = null;
    }

    /** Which views the windows behind are: the recording of them holds those, not later ones. */
    private int decorSignature(View decor) {
        int signature = 17;
        if (decor instanceof android.view.ViewGroup) {
            final android.view.ViewGroup group = (android.view.ViewGroup) decor;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View child = group.getChildAt(i);
                signature = 31 * signature + System.identityHashCode(child);
                signature = 31 * signature + child.getVisibility();
            }
        }
        final List<View> views = AndroidUtilities.allGlobalViews();
        return 31 * signature + (views == null ? 0 : views.size());
    }

    /**
     * What lies under the panel, from the recorded window, at half size and blurred: drawn again
     * in each frame the window behind drew (its views drawn anew), so the glass is made anew too.
     */
    private void updateLiveNode(RenderNode decorNode) {
        final float margin = AndroidUtilities.dp(LIVE_MARGIN_DP);
        // The panel's place in the window behind, by its layout: a menu or a dialog scaled as it
        // opens shows what is under it from the same place
        liveArea.set(-backdropDx - margin, -backdropDy - margin, -backdropDx + rect.width() + margin, -backdropDy + rect.height() + margin);
        if (liveNode == null) {
            liveNode = new RenderNode("LiquidPanelLive");
        }
        final RenderNode ln = (RenderNode) liveNode;
        final boolean moved = !liveArea.equals(liveDrawnArea);
        if (!moved && !liveDirty && ln.hasDisplayList()) {
            return;
        }
        liveDirty = false;
        final int w = Math.max(1, (int) Math.ceil(liveArea.width() / LIVE_SCALE));
        final int h = Math.max(1, (int) Math.ceil(liveArea.height() / LIVE_SCALE));
        if (moved || !ln.hasDisplayList()) {
            liveDrawnArea.set(liveArea);
            ln.setPosition(0, 0, w, h);
            final float blur = AndroidUtilities.dpf2(BackdropGlass.PanelBlurRadius) / LIVE_SCALE;
            ln.setRenderEffect(blur > 0 ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
            // the glass draws it from another place
            backdropVersion++;
        }
        // Recorded again even when only the window behind changed: its views were drawn anew in
        // their own window's frame, and this one would not see it
        final RecordingCanvas c = ln.beginRecording(w, h);
        try {
            c.scale(1f / LIVE_SCALE, 1f / LIVE_SCALE);
            c.translate(-liveDrawnArea.left, -liveDrawnArea.top);
            c.drawRenderNode(decorNode);
        } finally {
            ln.endRecording();
        }
    }

    private final ArrayList<View> windowsBelow = new ArrayList<>();
    private final int[] windowLocation = new int[2];
    private final RectF savedRectTmp = new RectF();
    private final Rect savedRectTmp2 = new Rect();

    /*
     * The activity's other windows between its own and this panel's, drawn over it as they are on
     * screen: the story viewer is a window of its own, and a menu opened from a story showed the
     * profile behind the viewer instead of the story.
     */
    private void drawWindowsBelow(Canvas canvas, Activity activity, View decor, View root) {
        final List<View> views = AndroidUtilities.allGlobalViews();
        if (views == null) {
            return;
        }
        windowsBelow.clear();
        boolean afterDecor = false, foundRoot = false;
        for (int i = 0; i < views.size(); i++) {
            final View view = views.get(i);
            if (view == root) {
                foundRoot = true;
                break;
            }
            if (view == decor) {
                afterDecor = true;
            } else if (afterDecor && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
                && view.getWidth() > 0 && view.getHeight() > 0 && view.getAlpha() > 0
                && !(view instanceof PipRoundVideoView.PipFrameLayout)
                && AndroidUtilities.findActivity(view.getContext()) == activity) {
                windowsBelow.add(view);
            }
        }
        if (!foundRoot) {
            windowsBelow.clear();
            return;
        }
        for (int i = 0; i < windowsBelow.size(); i++) {
            final View view = windowsBelow.get(i);
            if (view.getLayoutParams() instanceof WindowManager.LayoutParams) {
                final WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                if ((params.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                    canvas.drawColor(Color.argb((int) (0xFF * params.dimAmount), 0, 0, 0));
                }
            }
            view.getLocationOnScreen(windowLocation);
            canvas.save();
            canvas.translate(windowLocation[0] - decorLocation[0], windowLocation[1] - decorLocation[1]);
            try {
                view.draw(canvas);
            } catch (Exception ignore) {
                // A window that cannot be drawn here is left out of the glass
            }
            canvas.restore();
        }
        windowsBelow.clear();
    }

    // a method, as the fields it reads are declared below and a lambda here may not read them
    private final GlassLayer.Backdrop backdrop = this::drawBackdrop;

    private void drawBackdrop(Canvas c) {
        if (backdropScaled) {
            c.concat(backdropMatrix);
        } else {
            c.translate(backdropDx, backdropDy);
        }
        if (isLive() && liveNode != null) {
            c.translate(liveDrawnArea.left, liveDrawnArea.top);
            c.scale(LIVE_SCALE, LIVE_SCALE);
            c.drawRenderNode((RenderNode) liveNode);
        } else if (snapshot != null) {
            c.scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE);
            c.drawBitmap(snapshot, 0, 0, snapshotPaint);
        } else {
            c.drawRenderNode((RenderNode) decorNode);
        }
    }

    /*
     * The backdrop as a picture. The recorded decor holds the live views of the windows behind, so
     * glass made of it was made anew whenever anything there changed (a video or a round message
     * playing in the chat): the whole screen rendered, blurred and refracted again for each of its
     * frames, on the render thread every window shares, and the sheet and anything opening over it
     * stuttered. It is rendered into a half-size buffer when it is recorded, blurred there, and the
     * glass is made of that, so it is worked out again only when a new picture is taken, and then
     * without blurring it again.
     */
    private static final int SNAPSHOT_SCALE = 2;
    private final Paint snapshotPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private HardwareRenderer snapshotRenderer;
    private ImageReader snapshotReader;
    private RenderNode snapshotRoot;
    private RenderNode snapshotBlurNode;
    private Image snapshotImage;
    private Bitmap snapshot;
    private View snapshotHost;
    private boolean snapshotFailed;

    private final View.OnAttachStateChangeListener snapshotDetach = new View.OnAttachStateChangeListener() {
        @Override
        public void onViewAttachedToWindow(@NonNull View v) {
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull View v) {
            releaseSnapshot();
            backdropRecorded = false;
        }
    };

    /** Renders the recorded backdrop into the picture; false when it can't, and the live recording is used. */
    private boolean requestSnapshot(View host, int width, int height) {
        if (snapshotFailed || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            final int w = Math.max(1, width / SNAPSHOT_SCALE), h = Math.max(1, height / SNAPSHOT_SCALE);
            if (snapshotReader == null || snapshotReader.getWidth() != w || snapshotReader.getHeight() != h) {
                releaseSnapshot();
                // three: the one drawn, one waiting and one being rendered
                snapshotReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                snapshotReader.setOnImageAvailableListener(this::onSnapshot, snapshotHandler);
                snapshotRoot = new RenderNode("LiquidPanelSnapshot");
                snapshotBlurNode = new RenderNode("LiquidPanelSnapshotBlur");
                snapshotRenderer = new HardwareRenderer();
                snapshotRenderer.setOpaque(false);
                snapshotRenderer.setSurface(snapshotReader.getSurface());
                snapshotRenderer.setContentRoot(snapshotRoot);
            }
            if (snapshotHost != host) {
                if (snapshotHost != null) {
                    snapshotHost.removeOnAttachStateChangeListener(snapshotDetach);
                }
                snapshotHost = host;
                host.addOnAttachStateChangeListener(snapshotDetach);
            }
            // Blurred once here, at the picture's half size, as the glass would blur it: the glass
            // made of the picture is then only refracted and toned in each frame
            final float blur = AndroidUtilities.dpf2(BackdropGlass.PanelBlurRadius) / SNAPSHOT_SCALE;
            snapshotBlurNode.setPosition(0, 0, w, h);
            snapshotBlurNode.setRenderEffect(blur > 0 ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
            final RecordingCanvas bc = snapshotBlurNode.beginRecording(w, h);
            try {
                bc.scale(1f / SNAPSHOT_SCALE, 1f / SNAPSHOT_SCALE);
                bc.drawRenderNode((RenderNode) decorNode);
            } finally {
                snapshotBlurNode.endRecording();
            }
            snapshotRoot.setPosition(0, 0, w, h);
            final RecordingCanvas c = snapshotRoot.beginRecording(w, h);
            try {
                c.drawRenderNode(snapshotBlurNode);
            } finally {
                snapshotRoot.endRecording();
            }
            // rendered after the frame being drawn now, not from inside its drawing
            if (!snapshotPending) {
                snapshotPending = true;
                snapshotHandler.post(renderSnapshot);
            }
            return true;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            snapshotFailed();
            return false;
        }
    }

    private void snapshotFailed() {
        snapshotFailed = true;
        releaseSnapshot();
        if (decorNode != null && !isLive()) {
            ((RenderNode) decorNode).setUseCompositingLayer(true, null);
        }
    }

    private final Handler snapshotHandler = new Handler(Looper.getMainLooper());
    private boolean snapshotPending;
    private final Runnable renderSnapshot = () -> {
        snapshotPending = false;
        if (snapshotRenderer == null) {
            return;
        }
        try {
            snapshotRenderer.createRenderRequest().syncAndDraw();
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            snapshotFailed();
            backdropVersion++;
            invalidateSelf();
        }
    };

    private void onSnapshot(ImageReader reader) {
        if (reader != snapshotReader) {
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            final HardwareBuffer buffer = image.getHardwareBuffer();
            final Bitmap bitmap = buffer == null ? null : Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
            if (buffer != null) {
                buffer.close();
            }
            if (bitmap == null) {
                image.close();
                return;
            }
            // the old buffer goes back to be rendered into only when the next picture is asked for,
            // by when the glass no longer draws it
            if (snapshotImage != null) {
                snapshotImage.close();
            }
            snapshotImage = image;
            snapshot = bitmap;
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            if (image != null && image != snapshotImage) {
                image.close();
            }
            return;
        }
        backdropVersion++;
        invalidateSelf();
        final View h = host();
        if (h != null) {
            h.invalidate();
        }
    }

    private void dropSnapshot() {
        snapshot = null;
        if (snapshotImage != null) {
            snapshotImage.close();
            snapshotImage = null;
        }
    }

    private void releaseSnapshot() {
        dropSnapshot();
        snapshotHandler.removeCallbacks(renderSnapshot);
        snapshotPending = false;
        snapshotHandler.removeCallbacks(refreshBackdrop);
        refreshPosted = false;
        if (snapshotRenderer != null) {
            snapshotRenderer.destroy();
            snapshotRenderer = null;
        }
        if (snapshotReader != null) {
            snapshotReader.close();
            snapshotReader = null;
        }
        snapshotRoot = null;
        snapshotBlurNode = null;
        if (snapshotHost != null) {
            snapshotHost.removeOnAttachStateChangeListener(snapshotDetach);
            snapshotHost = null;
        }
    }

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
    public void setColorFilter(int color, @NonNull PorterDuff.Mode mode) {
        setFilterColor(color);
    }

    @Override
    public void setTint(int tintColor) {
        setFilterColor(tintColor);
    }

    // Sheets colored the 9-patch with a filter; the glass takes that color as its own
    private boolean hasFilterColor;
    private int filterColor;
    private static java.lang.reflect.Method porterDuffGetColor;
    private static boolean porterDuffGetColorFailed;

    private void setFilterColor(int color) {
        if (!hasFilterColor || filterColor != color) {
            hasFilterColor = true;
            filterColor = color;
            invalidateSelf();
        }
    }

    private static Integer colorOf(ColorFilter filter) {
        if (Build.VERSION.SDK_INT >= 29 && filter instanceof android.graphics.BlendModeColorFilter) {
            return ((android.graphics.BlendModeColorFilter) filter).getColor();
        }
        if (filter instanceof PorterDuffColorFilter && !porterDuffGetColorFailed) {
            try {
                if (porterDuffGetColor == null) {
                    porterDuffGetColor = PorterDuffColorFilter.class.getDeclaredMethod("getColor");
                    porterDuffGetColor.setAccessible(true);
                }
                return (Integer) porterDuffGetColor.invoke(filter);
            } catch (Throwable e) {
                porterDuffGetColorFailed = true;
            }
        }
        return null;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        final Integer filterColor = colorFilter == null ? null : colorOf(colorFilter);
        if (filterColor != null) {
            setFilterColor(filterColor);
            return;
        }
        // The color comes from the supplier; callers used the filter only to tint the 9-patch
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
