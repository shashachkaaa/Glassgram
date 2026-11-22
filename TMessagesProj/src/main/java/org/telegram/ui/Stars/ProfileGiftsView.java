package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ProfileHeaderGlass;
import org.telegram.ui.Components.ViewBehindBackdrop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final float BUBBLE_DIAMETER = 42f;

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileHeaderGlass rearGlass = new ProfileHeaderGlass(this);
    private final FrontLayer frontLayer;
    private final ProfileHeaderGlass frontGlass;
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubbleOutline = new Path();
    private final RectF lensBounds = new RectF();
    private boolean active = true;
    public void setActive(boolean f) {
        this.active = f;
        if (!f) {
            resetDrag();
            orbitHeld = orbitCandidate = false;
        }
    }

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, View header) {
        super(context);
        this.currentAccount = currentAccount;
        this.dialogId = dialogId;
        this.avatarContainer = avatarContainer;
        frontLayer = new FrontLayer(context);
        frontGlass = new ProfileHeaderGlass(frontLayer);
        rearGlass.setHeader(header);
        frontGlass.setHeader(header);
    }

    public View getFrontLayer() {
        return frontLayer;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (frontLayer != null) frontLayer.invalidate();
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (frontLayer != null) frontLayer.setAlpha(alpha);
    }

    private float musicPulse;
    private float avatarPulseScale = 1f;

    /**
     * The beat of the profile's music, 0..1: the bubbles swell, flash from inside, their halos
     * flare and widen, their light reaches further. avatarScale is how much the avatar swells
     * on it, so that the light falls on the avatar's rim as it is drawn.
     */
    private float musicDim;

    /** How far the lights went down for the profile's music, 0..1: the bubbles glow stronger over the dark. */
    public void setMusicDim(float dim) {
        if (musicDim != dim) {
            musicDim = dim;
            invalidate();
        }
    }

    public void setMusicPulse(float pulse, float avatarScale) {
        if (musicPulse != pulse || avatarPulseScale != avatarScale) {
            musicPulse = pulse;
            avatarPulseScale = avatarScale;
            invalidate();
        }
    }

    public float expandProgress, collapseProgress;
    public boolean isOpening;

    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    public void setCollapseProgress(float progress, boolean isOpening) {
        this.isOpening = isOpening;
        float newProgress = Utilities.clamp01((progress - 0.3f) / 0.7f);
        if (this.collapseProgress != newProgress) {
            this.collapseProgress = newProgress;
            invalidate();
        }
    }

    private float actionBarProgress;

    public void setActionBarActionMode(float progress) {
//        if (Theme.isCurrentThemeDark()) {
//            return;
//        }
        actionBarProgress = progress;
        invalidate();
    }


    private float left, right, cy;
    private float expandY, maxExpandY;
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated, int maxExpandY) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        this.maxExpandY = maxExpandY + cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float y) {
        this.expandY = y;
        invalidate();
    }

    private float progressToInsets = 1f;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        rearGlass.watch(true);

        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.addView(this);
            if (gift.patternDrawable != null) gift.patternDrawable.addView(this);
        }

        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        rearGlass.watch(false);
        lastPhysicsFrame = 0;
        resetDrag();
        orbitHeld = orbitCandidate = false;

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.removeView(this);
            if (gift.patternDrawable != null) gift.patternDrawable.removeView(this);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            if ((long) args[0] == dialogId) {
                update();
            }
        }
    }

    public final class Gift {

        public final long id;
        public final TLRPC.Document document;
        public final long documentId;
        /** The colors of the gift's backdrop: the bubble is made of them. */
        public final int color, edgeColor, patternColor;
        /** The light the bubble gives off: its color, bright. */
        public final int lightColor;
        /** The gift's pattern, the symbols drifting inside the bubble. */
        public final TLRPC.Document patternDocument;
        public final long patternDocumentId;
        public final String slug;
        private StarsReactionsSheet.Particles particles;

        public int position = -1;

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            final TL_stars.starGiftAttributePattern pattern = findAttribute(gift.attributes, TL_stars.starGiftAttributePattern.class);
            color = backdrop != null ? backdrop.center_color | 0xFF000000 : DEFAULT_BUBBLE_COLOR;
            edgeColor = backdrop != null ? backdrop.edge_color | 0xFF000000 : color;
            patternColor = backdrop != null ? backdrop.pattern_color | 0xFF000000 : Color.WHITE;
            patternDocument = pattern != null ? pattern.document : null;
            patternDocumentId = patternDocument != null ? patternDocument.id : 0;
            lightColor = lightColorOf(color);
            slug = gift.slug;
            initParticles();
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            edgeColor = status.edge_color | 0xFF000000;
            patternColor = status.pattern_color | 0xFF000000;
            patternDocument = null;
            patternDocumentId = status.pattern_document_id;
            lightColor = lightColorOf(color);
            slug = status.slug;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 6);
            final float gsz = dp(36);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
            sparkles = new Sparkles(id);

            // A soap bubble at rest: clear, with a breath of the backdrop's color that gathers
            // towards the rim, where the film is seen edge on.
            final int tint = ColorUtils.blendARGB(color, edgeColor, .25f);
            filmPaint.setShader(new RadialGradient(0, 0, 1f,
                    new int[]{
                            Theme.multAlpha(tint, 0f),
                            Theme.multAlpha(tint, .06f),
                            Theme.multAlpha(tint, .18f),
                            Theme.multAlpha(tint, .32f),
                            Theme.multAlpha(color, .44f)
                    },
                    new float[]{0f, .55f, .8f, .93f, 1f}, Shader.TileMode.CLAMP));
            // Picked up, it fills with its color like a ball of colored glass, clear only around
            // the gift; drawn over the film by how far the bubble is lifted.
            liftFilmPaint.setShader(new RadialGradient(0, 0, 1f,
                    new int[]{
                            Theme.multAlpha(color, .10f),
                            Theme.multAlpha(color, .34f),
                            Theme.multAlpha(color, .62f),
                            Theme.multAlpha(ColorUtils.blendARGB(color, Color.WHITE, .15f), .8f)
                    },
                    new float[]{0f, .45f, .75f, 1f}, Shader.TileMode.CLAMP));
            rimColor = ColorUtils.blendARGB(color, Color.WHITE, .55f);
            lightShader = new RadialGradient(0, 0, 1f,
                    new int[]{lightColor, Theme.multAlpha(lightColor, .55f), Theme.multAlpha(lightColor, .16f), Theme.multAlpha(lightColor, 0f)},
                    new float[]{0f, .3f, .62f, 1f}, Shader.TileMode.CLAMP);
            // The glint on the rims around keeps the gift's own color: added to the header it
            // went white over a mid-tone one
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            hsv[2] = Math.max(hsv[2], .92f);
            final int glint = Color.HSVToColor(hsv);
            glintShader = new RadialGradient(0, 0, 1f,
                    new int[]{glint, Theme.multAlpha(glint, .6f), Theme.multAlpha(glint, .2f), Theme.multAlpha(glint, 0f)},
                    new float[]{0f, .3f, .62f, 1f}, Shader.TileMode.CLAMP);
            // A ring at the rim: inside, the bubble covers it; outside it fades out at once
            final float rim = 1f / HALO_EXTENT;
            haloShader = new RadialGradient(0, 0, 1f,
                    new int[]{Theme.multAlpha(glint, 0f), Theme.multAlpha(glint, 0f), Theme.multAlpha(glint, .55f), Theme.multAlpha(glint, .18f), Theme.multAlpha(glint, 0f)},
                    new float[]{0f, rim * .9f, rim, rim + (1f - rim) * .35f, 1f}, Shader.TileMode.CLAMP);
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public AnimatedEmojiDrawable emojiDrawable;
        public AnimatedEmojiDrawable patternDrawable;
        public AnimatedFloat animatedFloat;

        private final Paint filmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint liftFilmPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix filmMatrix = new Matrix();
        /** The thin light line of the film's edge: the backdrop's color, washed out. */
        private int rimColor;
        /** Unit radius around (0, 0): the light, strongest at the bubble, fading out. */
        private RadialGradient lightShader;
        /** The same in the gift's saturated color, for the rims it shines on. */
        private RadialGradient glintShader;
        /** The glow just around the bubble's rim, see {@link #drawHalos}. */
        private RadialGradient haloShader;
        private Sparkles sparkles;

        private final ProfileHeaderGlass.Piece rearPiece = new ProfileHeaderGlass.Piece();
        private final ProfileHeaderGlass.Piece frontPiece = new ProfileHeaderGlass.Piece();
        public final RectF bounds = new RectF();
        private final RectF visualBounds = new RectF();
        private final ArrayList<Gift> lowerGifts = new ArrayList<>();
        private boolean sampledByOtherGift;
        private Object renderedGift;
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        private Orbit orbit;
        private float alpha, artworkScale;
        private boolean inFront;

        public void copy(Gift b) {
            orbit = b.orbit;
            gradient = b.gradient;
            emojiDrawable = b.emojiDrawable;
            patternDrawable = b.patternDrawable;
            sparkles = b.sparkles;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
        }

        private void draw(Canvas canvas, ProfileHeaderGlass glass, boolean hasGlass, boolean advanceParticles) {
            canvas.save();
            // The film stretches with how fast the bubble goes, smoothed: the finger's speed
            // jumps from one touch event to the next, the bubble must not twitch with it
            double vx = orbit.stretchVx, vy = orbit.stretchVy;
            float stretch = (float) (MAX_STRETCH * Math.tanh(Math.hypot(vx, vy) / dpf2(STRETCH_SPEED)));
            drawBubble(canvas, bounds, alpha, (float) Math.toDegrees(Math.atan2(vy, vx)), stretch,
                    hasGlass, glass, inFront ? frontPiece : rearPiece);
            canvas.translate(bounds.centerX(), bounds.centerY());
            final float scale = artworkScale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            drawArtwork(canvas, alpha, advanceParticles);
            canvas.restore();
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private void recordAndDraw(Canvas canvas, ProfileHeaderGlass glass) {
            if (renderedGift == null) renderedGift = new RenderNode("ProfileGift");
            final RenderNode node = (RenderNode) renderedGift;
            final int left = (int) Math.floor(visualBounds.left), top = (int) Math.floor(visualBounds.top);
            node.setPosition(left, top, (int) Math.ceil(visualBounds.right), (int) Math.ceil(visualBounds.bottom));
            final RecordingCanvas recording = node.beginRecording();
            try {
                recording.translate(-left, -top);
                clipGiftLayer(recording);
                draw(recording, glass, true, true);
            } finally {
                node.endRecording();
            }
            canvas.drawRenderNode(node);
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private void drawLowerGifts(Canvas backdrop) {
            for (Gift gift : lowerGifts) {
                final RenderNode node = (RenderNode) gift.renderedGift;
                if (node != null && node.hasDisplayList()) backdrop.drawRenderNode(node);
            }
        }

        private void drawBubble(Canvas canvas, RectF box, float alpha, float angle, float stretch,
                                boolean glassEnabled, ProfileHeaderGlass glass, ProfileHeaderGlass.Piece layer) {
            float cx = box.centerX(), cy = box.centerY(), r = box.width() * .5f;
            double time = SystemClock.uptimeMillis() * .0032 + (id % 337);
            float ripple = (float) Math.sin(time);
            float wave = (float) Math.sin(time * 1.61);
            float scale = 1f + stretch + .055f * ripple;
            canvas.save();
            // Flattened where it presses against another bubble or the avatar
            final float squash = (float) orbit.squash;
            if (squash > .002f) {
                final float normal = (float) Math.toDegrees(orbit.squashAngle);
                canvas.rotate(normal, cx, cy);
                canvas.scale(1f - squash, 1f + squash * .6f, cx, cy);
                canvas.rotate(-normal, cx, cy);
            }
            canvas.rotate(angle, cx, cy);
            canvas.scale(scale, 1f / scale, cx, cy);
            canvas.rotate(-angle, cx, cy);

            // Four smooth lobes change curvature as the film wobbles. Clip both the
            // refracted backdrop and the rim to the same outline, including in flight.
            float left = cx - r, right = cx + r, top = cy - r, bottom = cy + r;
            float tx = cx + r * .17f * wave, bx = cx - r * .14f * ripple;
            float ly = cy + r * .18f * ripple, ry = cy - r * .15f * wave;
            float k = r * .55f;
            bubbleOutline.rewind();
            bubbleOutline.moveTo(tx, top);
            bubbleOutline.cubicTo(tx + k, top, right, ry - k, right, ry);
            bubbleOutline.cubicTo(right, ry + k, bx + k, bottom, bx, bottom);
            bubbleOutline.cubicTo(bx - k, bottom, left, ly + k, left, ly);
            bubbleOutline.cubicTo(left, ly - k, tx - k, top, tx, top);
            bubbleOutline.close();
            final float lift = (float) orbit.lift;
            final int tint = ColorUtils.blendARGB(color, edgeColor, .25f);
            canvas.save();
            canvas.clipPath(bubbleOutline);
            if (glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lensBounds.set(box);
                lensBounds.inset(-r * .14f, -r * .14f);
                // A ball of clear glass: what is behind it (the name, the avatar's rim, the
                // buttons) shows through it sharp, a little bigger and bent towards its edge
                glass.drawSphere(canvas, layer, lensBounds,
                        ColorUtils.setAlphaComponent(tint, (int) (0x1C + 0x30 * Math.min(1f, lift))), alpha, 1.22f,
                        lowerGifts.isEmpty() ? null : this::drawLowerGifts);
            } else {
                bubblePaint.setColor(Theme.multAlpha(tint, (.14f + .2f * lift) * alpha));
                canvas.drawPath(bubbleOutline, bubblePaint);
            }
            filmMatrix.setScale(r * 1.03f, r * 1.03f);
            filmMatrix.postTranslate(cx, cy);
            filmPaint.getShader().setLocalMatrix(filmMatrix);
            filmPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawPath(bubbleOutline, filmPaint);
            if (lift > .01f) {
                liftFilmPaint.getShader().setLocalMatrix(filmMatrix);
                liftFilmPaint.setAlpha((int) (0xFF * alpha * Math.min(1f, lift)));
                canvas.drawPath(bubbleOutline, liftFilmPaint);
            }
            if (musicPulse > 0f && lightShader != null) {
                // the bubble flashes from inside on the beat
                filmMatrix.setScale(r * 1.1f, r * 1.1f);
                filmMatrix.postTranslate(cx, cy);
                lightShader.setLocalMatrix(filmMatrix);
                flashPaint.setShader(lightShader);
                flashPaint.setAlpha((int) (0xFF * Utilities.clamp01(.4f * musicPulse * alpha)));
                canvas.drawPath(bubbleOutline, flashPaint);
                flashPaint.setShader(null);
            }
            if (patternDrawable != null) {
                // the gift's pattern as sparkles afloat in the bubble, nearly white
                sparkles.draw(canvas, patternDrawable, ColorUtils.blendARGB(patternColor, Color.WHITE, .85f), cx, cy, r, alpha, musicPulse);
            }
            drawSheen(canvas, cx, cy, r, alpha);
            canvas.restore();
            // The film's edge: a thin line of its color, and the light it catches up and to the
            // left and, fainter, down and to the right
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(dpf2(1.1f + .5f * musicPulse));
            rimPaint.setColor(Theme.multAlpha(rimColor, Math.min(1f, .4f + .4f * lift + .25f * musicPulse) * alpha));
            canvas.drawPath(bubbleOutline, rimPaint);
            rimLightMatrix.setScale(r, r);
            rimLightMatrix.postTranslate(cx, cy);
            rimLightPaint.getShader().setLocalMatrix(rimLightMatrix);
            rimLightPaint.setStrokeWidth(dpf2(1f));
            rimLightPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawPath(bubbleOutline, rimLightPaint);
            canvas.restore();
        }

        private void drawArtwork(Canvas canvas, float alpha, boolean advanceParticles) {
            if (patternDrawable == null) {
                // No pattern to show (an old status): the stars of the gift's color instead
                if (advanceParticles) particles.process();
                particles.draw(canvas, color, alpha);
            }
            if (emojiDrawable != null) {
                // the gift floats in its bubble, it is not pinned to its middle
                final double time = SystemClock.uptimeMillis() * .001 + (id % 89);
                final int sz = dp(24);
                canvas.translate(dpf2(1.1f) * (float) Math.sin(time * 1.3), dpf2(1.3f) * (float) Math.cos(time * 1.05));
                emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                emojiDrawable.setAlpha((int) (0xFF * alpha));
                emojiDrawable.draw(canvas);
            }
        }
    }

    private static final int DEFAULT_BUBBLE_COLOR = 0xFF8E8E93;
    /** How far the glow around a bubble reaches, in its radii. */
    private static final float HALO_EXTENT = 1.32f;

    /** The color of the light a bubble of this color gives off: its hue, bright and a little washed out. */
    private static int lightColorOf(int color) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], .7f);
        hsv[2] = Math.max(hsv[2], .95f);
        return Color.HSVToColor(hsv);
    }

    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix sheenMatrix = new Matrix();
    private final Paint rimLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix rimLightMatrix = new Matrix();

    {
        // Unit radius around (0, 0): white, fading out
        sheenPaint.setShader(new RadialGradient(0, 0, 1f,
                new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x00FFFFFF}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        sheenPaint.setStyle(Paint.Style.STROKE);
        // Across the bubble from its upper left to its lower right, in its radii: the edge
        // catches the light at both ends, more at the first
        rimLightPaint.setShader(new LinearGradient(-.72f, -.72f, .72f, .72f,
                new int[]{0xE6FFFFFF, 0x1CFFFFFF, 0x14FFFFFF, 0x8CFFFFFF}, new float[]{0f, .38f, .7f, 1f}, Shader.TileMode.CLAMP));
        rimLightPaint.setStyle(Paint.Style.STROKE);
    }

    /**
     * The light on the film: no hard glints, a soft sheen that hugs the rim up and to the left,
     * a fainter one opposite, as a soap bubble has them. Each is a band along the inside of the
     * rim lit from a point on it, wider and fainter towards the middle.
     */
    private void drawSheen(Canvas canvas, float cx, float cy, float r, float alpha) {
        drawSheen(canvas, cx, cy, r, -.72f, -.68f, 1.25f, .5f * alpha);
        drawSheen(canvas, cx, cy, r, .64f, .76f, .95f, .26f * alpha);
    }

    private void drawSheen(Canvas canvas, float cx, float cy, float r, float nx, float ny, float reach, float alpha) {
        sheenMatrix.setScale(r * reach, r * reach);
        sheenMatrix.postTranslate(cx + nx * r, cy + ny * r);
        sheenPaint.getShader().setLocalMatrix(sheenMatrix);
        sheenPaint.setStrokeWidth(r * .26f);
        sheenPaint.setAlpha((int) (0xFF * alpha * .45f));
        canvas.drawCircle(cx, cy, r * .84f, sheenPaint);
        sheenPaint.setStrokeWidth(r * .12f);
        sheenPaint.setAlpha((int) (0xFF * alpha * .55f));
        canvas.drawCircle(cx, cy, r * .92f, sheenPaint);
    }

    /**
     * The gift's pattern as sparkles afloat in its bubble: small, nearly white, on a ball turning
     * slowly inside it, brighter and bigger on its near side, twinkling. They follow the clock,
     * not the frames: the rear and the front layer, and the captures, draw the same.
     */
    private static final class Sparkles {
        private static final int COUNT = 13;

        private final float[] x = new float[COUNT], y = new float[COUNT], z = new float[COUNT];
        private final float[] size = new float[COUNT], rotation = new float[COUNT];
        private final float[] twinkleSpeed = new float[COUNT], twinklePhase = new float[COUNT];
        private final float speed, tiltCos, tiltSin, startAngle;
        private ColorFilter filter;
        private int filterColor;

        Sparkles(long seed) {
            final java.util.Random random = new java.util.Random(seed * 31 + 7);
            for (int i = 0; i < COUNT; i++) {
                // evenly over the ball
                final double h = 2 * random.nextDouble() - 1, a = random.nextDouble() * 2 * Math.PI;
                final double w = Math.sqrt(1 - h * h);
                x[i] = (float) (w * Math.cos(a));
                y[i] = (float) h;
                z[i] = (float) (w * Math.sin(a));
                size[i] = .075f + .075f * random.nextFloat();
                rotation[i] = (random.nextFloat() - .5f) * 70f;
                twinkleSpeed[i] = 1.1f + 1.6f * random.nextFloat();
                twinklePhase[i] = random.nextFloat() * 6.28f;
            }
            speed = (.22f + .16f * random.nextFloat()) * (random.nextBoolean() ? 1 : -1);
            final double tilt = (random.nextDouble() - .5) * 1.1;
            tiltCos = (float) Math.cos(tilt);
            tiltSin = (float) Math.sin(tilt);
            startAngle = random.nextFloat() * 6.28f;
        }

        /** pulse: the music's beat, 0..1; the sparkles are thrown out a little and flare with it. */
        void draw(Canvas canvas, AnimatedEmojiDrawable drawable, int color, float cx, float cy, float r, float alpha, float pulse) {
            final double time = LiteMode.isEnabled(LiteMode.FLAG_PARTICLES) ? SystemClock.uptimeMillis() * .001 : 0;
            if (filter == null || filterColor != color) {
                filterColor = color;
                filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
            final double angle = startAngle + time * speed;
            final float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            final int sz = dp(12);
            final float spread = r * .8f * (1f + .12f * pulse);
            drawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
            drawable.setColorFilter(filter);
            for (int i = 0; i < COUNT; i++) {
                // turned around the ball's axis, the axis tilted towards the eye
                final float tx = x[i] * cos + z[i] * sin, tz = z[i] * cos - x[i] * sin;
                final float py = y[i] * tiltCos - tz * tiltSin, near = (y[i] * tiltSin + tz * tiltCos + 1f) * .5f;
                final float twinkle = .72f + .28f * (float) Math.sin(time * twinkleSpeed[i] + twinklePhase[i]);
                final int a = (int) (0xFF * Math.min(1f, alpha * (.3f + .6f * near) * twinkle * (1f + .35f * pulse)));
                if (a <= 4) continue;
                canvas.save();
                canvas.translate(cx + tx * spread, cy + py * spread);
                canvas.rotate(rotation[i]);
                final float scale = size[i] * (.75f + .5f * near) * r / sz * (1f + .3f * pulse);
                canvas.scale(scale, scale);
                drawable.setAlpha(a);
                drawable.draw(canvas);
                canvas.restore();
            }
            drawable.setColorFilter(null);
            drawable.setAlpha(0xFF);
        }
    }

    private StarsController.GiftsList list;

    public final ArrayList<Gift> oldGifts = new ArrayList<>();
    public final ArrayList<Gift> gifts = new ArrayList<>();
    private final ArrayList<Gift> drawOrder = new ArrayList<>();
    private final ArrayList<Gift> renderOrder = new ArrayList<>();
    public final HashSet<Long> giftIds = new HashSet<>();
    public int maxCount;

    public void update() {
        if (!MessagesController.getInstance(currentAccount).enableGiftsInProfile) {
            return;
        }

        maxCount = MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit;
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        final TLRPC.EmojiStatus emojiStatus;
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            emojiStatus = user == null ? null : user.emoji_status;
        } else {
            final TLRPC.User chat = MessagesController.getInstance(currentAccount).getUser(-dialogId);
            emojiStatus = chat == null ? null : chat.emoji_status;
        }
        if (emojiStatus instanceof TLRPC.TL_emojiStatusCollectible) {
            giftIds.add(((TLRPC.TL_emojiStatusCollectible) emojiStatus).collectible_id);
        }
        list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        if (list != null) {
            for (int i = 0; i < list.gifts.size(); i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.pinned_to_top && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    if (!giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
        }

        boolean changed = false;
        if (gifts.size() != oldGifts.size()) {
            changed = true;
        } else for (int i = 0; i < gifts.size(); i++) {
            if (!gifts.get(i).equals(oldGifts.get(i))) {
                changed = true;
                break;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            Gift oldGift = null;
            for (int j = 0; j < oldGifts.size(); ++j) {
                if (oldGifts.get(j).id == g.id) {
                    oldGift = oldGifts.get(j);
                    break;
                }
            }

            if (oldGift != null) {
                g.copy(oldGift);
            } else {
                g.gradient = new RadialGradient(0, 0, dp(22.5f), new int[]{g.color, Theme.multAlpha(g.color, 0.0f)}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                g.gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.gradientPaint.setShader(g.gradient);
                if (g.document != null) {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.document);
                } else {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.documentId);
                }
                if (g.patternDocument != null) {
                    g.patternDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC, g.patternDocument);
                } else if (g.patternDocumentId != 0) {
                    g.patternDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC, g.patternDocumentId);
                }
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(0.0f);
                if (isAttachedToWindow()) {
                    g.emojiDrawable.addView(this);
                    if (g.patternDrawable != null) g.patternDrawable.addView(this);
                }
            }
        }

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            positions.add(i);
        }

        for (int i = 0; i < oldGifts.size(); i++) {
            final Gift g = oldGifts.get(i);
            Gift newGift = null;
            for (int j = 0; j < gifts.size(); ++j) {
                if (gifts.get(j).id == g.id) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift == null) {
                g.emojiDrawable.removeView(this);
                g.emojiDrawable = null;
                if (g.patternDrawable != null) {
                    g.patternDrawable.removeView(this);
                    g.patternDrawable = null;
                }
                g.gradient = null;
            } else {
                positions.remove((Object) g.position);
            }
        }

        if (!positions.isEmpty()) {
            BagRandomizer<Integer> bagRandomizer = new BagRandomizer<>(positions);
            for (int i = 0; i < gifts.size(); ++i) {
                final Gift g = gifts.get(i);
                if (g.position == -1) {
                    g.position = bagRandomizer.next();
                }
            }
        }

        // A new gift starts on its own orbit around the avatar, spread out from the others
        drawOrder.clear();
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            if (gift.orbit == null) gift.orbit = new Orbit(gift.id, 2 * Math.PI * i / gifts.size() + .3, physicsTime);
            drawOrder.add(gift);
        }
        renderOrder.clear();
        renderOrder.addAll(drawOrder);

        if (movingGift != null) {
            Gift replacement = null;
            for (Gift gift : gifts) {
                if (gift.id == movingGift.id) replacement = gift;
            }
            if (replacement == null) resetDrag();
            else if (movingGift != replacement) {
                replacement.bounds.set(movingGift.bounds);
                movingGift = replacement;
            }
        }
        if (changed) invalidate();
    }

    /*
     * The gifts fly free around the avatar, as on iOS: each on its own orbit in a disk around it,
     * seen from above its near edge (its far side, at the top, behind the avatar, its near side
     * in front of it), at its own speed, drifting a little in and out at its own pace. Close
     * neighbours along the ring push each other on, so they spread around it without being
     * placed, and the ones in front of the avatar press against it.
     *
     * The disk's coordinates: its middle ring is the unit circle. On the screen, in orbit radii,
     * it is an ellipse, wide and flat, its right end a little up.
     */
    private static final double RING_WIDTH = .98, RING_HEIGHT = .95, RING_SLOPE = -.38;
    private static final double DEPTH_SCALE = .29, LIFT_SCALE = 1.05;
    /** Each bubble's own speed along its orbit, in ring radii per second, give or take a sixth. */
    private static final double ORBIT_SPEED = .32;
    /**
     * How fast a bubble gets back to its own speed after a spin, takes the finger's, and drops
     * the speed along the orbit it was let go with, 1/s.
     */
    private static final double PACE_DRIVE = 2, SPIN_DRIVE = 7, RETURN_PACE = 14;
    /** How firmly a bubble keeps to its drifting orbit radius: it is pushed off it, not flung. */
    private static final double DRIFT_SPRING = 9, DRIFT_DAMPING = 4.5;
    /** The spring a bubble let go of springs back to its orbit on, rad/s, and its damping. */
    private static final double RETURN_SPRING = 12, RETURN_DAMPING = .75;
    /** The fastest a free bubble moves across its orbit and along it, ring radii per second. */
    private static final double MAX_ACROSS = 1.2, MAX_ALONG = 3.5;
    /** Bubbles closer than this on the disk drift apart. */
    private static final double SPACING = .42, SPACING_PUSH = 6;
    /**
     * Neighbours closer along the ring than an even share of it push each other on, rad/s² for
     * each radian closer: gently, so the gaps between them breathe but they never bunch.
     */
    private static final double EVEN_PUSH = .3;
    /** The most a bubble's orbit drifts out of the ring, for keeping it on the screen. */
    private static final double MAX_DRIFT = 1.13;
    private static final double RELEASE_SPEED = .2;
    /** The film stretches at most by this, softly, half of it at this speed, dp/s, and eases into it, s. */
    private static final double MAX_STRETCH = .18, STRETCH_EASE = .12;
    private static final float STRETCH_SPEED = 1300;
    /**
     * Thrown, by a flick at least this fast, dp/s, it slides round its orbit the way the flick
     * went along it, at most this fast, ring radii a second, a fraction of the flick, and slows
     * down to its own pace in about this long, s: a quick flick takes it a third of the way round.
     */
    private static final float THROW_SPEED = 600;
    private static final double MAX_THROW_ALONG = 3.5, THROW_ALONG = .3, THROW_SLOWDOWN = .7;
    private static final float MAX_RELEASE_SPEED = 700;
    private static final double MAX_SPIN = 3;
    /** A bubble knocked by another takes at most this speed from it, dp/s, and barely bounces. */
    private static final float MAX_KNOCK = 400;
    private static final double BOUNCE = .1;

    /** Seconds of the gifts' own time: their drifting follows it. */
    private double physicsTime;
    private double[] ringAngles = new double[0];

    private static final class Orbit {
        static final int FREE = 0, HELD = 1, RETURNING = 2, FLYING = 3;
        /** The bubble, in orbit radii from the orbit's center, and its speed in radii per second. */
        double x, y, vx, vy;
        double lift;
        /** How much a contact flattens the bubble, and the direction it is pressed in. */
        double squash, squashAngle;
        /** Its speed on the screen, px/s, smoothed, for how much the film stretches and where to. */
        double stretchVx, stretchVy;
        int state = FREE;
        /** How long it has been flying since it was thrown, seconds. */
        double flightTime;
        /** Springing back to the far side of its orbit, and already gone behind the avatar. */
        boolean sunk;
        /** The radius its orbit drifts around, how far and how fast, and its own speed. */
        private final double base, swing, pace, phase, speed;

        Orbit(long id, double angle, double time) {
            final java.util.Random random = new java.util.Random(id * 7919 + 17);
            base = .98 + .04 * random.nextDouble();
            swing = .06 + .05 * random.nextDouble();
            pace = .35 + .25 * random.nextDouble();
            phase = random.nextDouble() * 2 * Math.PI;
            speed = ORBIT_SPEED * (.84 + .32 * random.nextDouble());
            final double r = radiusAt(time);
            final double u = r * Math.cos(angle), v = r * Math.sin(angle);
            setDisk(u, v, -speed * Math.sin(angle), speed * Math.cos(angle));
        }

        /** The radius the bubble's orbit has drifted to at this time. */
        double radiusAt(double time) {
            return base + swing * Math.sin(pace * time + phase);
        }

        double u() {
            return x / RING_WIDTH;
        }

        double v() {
            return (y - RING_SLOPE * x) / RING_HEIGHT;
        }

        double vu() {
            return vx / RING_WIDTH;
        }

        double vv() {
            return (vy - RING_SLOPE * vx) / RING_HEIGHT;
        }

        void setDisk(double u, double v, double vu, double vv) {
            x = RING_WIDTH * u;
            y = RING_HEIGHT * v + RING_SLOPE * x;
            vx = RING_WIDTH * vu;
            vy = RING_HEIGHT * vv + RING_SLOPE * vx;
        }

        /** -1 at the disk's far side, behind the avatar, 1 at its near side. */
        double depth() {
            return Math.max(-1, Math.min(1, v()));
        }

        /** A lifted bubble is as near as the near side of the ring, wherever the finger takes it. */
        double perspectiveScale() {
            final double held = Math.min(1, lift);
            return 1 + DEPTH_SCALE * (depth() * (1 - held) + .7 * held);
        }

        double radiusScale() {
            return perspectiveScale() * (1 + LIFT_SCALE * lift);
        }

        /**
         * Held, springing back, or just thrown: over everything, the avatar included. A thrown
         * bubble slides round its orbit, and once its lift is gone, goes behind the avatar or
         * in front of it as its place on the orbit has it.
         */
        boolean isLifted() {
            return state == HELD || state == RETURNING || state == FLYING && lift >= .5;
        }

        /** Thrown or springing back: it flies over the others, it does not push them. */
        boolean isPassing() {
            return state == FLYING || state == RETURNING;
        }

        boolean isInFront() {
            return !sunk && (isLifted() || v() >= 0);
        }

        void press(double amount, double nx, double ny) {
            if (amount > squash) {
                squash = amount;
                squashAngle = Math.atan2(ny, nx);
            }
        }
    }

    private float orbitCenterX, orbitCenterY, orbitRadiusX, orbitRadiusY;
    private float avatarCenterX, avatarCenterY, avatarRadius;
    private long lastPhysicsFrame;
    /** The held bubble's center and speed, in this view's pixels. */
    private float heldX, heldY, heldVx, heldVy;

    /** The orbit held by the finger: the bubbles take its angular speed, even none. */
    private boolean orbitHeld;
    /** The finger's angular speed around the avatar on the disk, rad/s, while it holds the orbit. */
    private double spinRate;
    private long orbitMoveTime;

    private void advanceGifts(long now) {
        double elapsed = lastPhysicsFrame == 0 ? 0 : Math.min(.064, (now - lastPhysicsFrame) * .001);
        lastPhysicsFrame = now;
        if (elapsed <= 0) return;
        int steps = (int) Math.ceil(elapsed * 240);
        double dt = elapsed / steps;
        double rx = orbitRadiusX, ry = orbitRadiusY;
        Gift held = dragging ? movingGift : null;
        if (held != null) {
            Orbit body = held.orbit;
            body.state = Orbit.HELD;
            body.sunk = false;
            body.x = (heldX - orbitCenterX) / rx;
            body.y = (heldY - orbitCenterY) / ry;
            body.vx = heldVx / rx;
            body.vy = heldVy / ry;
        }
        // A finger holding the orbit still stops it
        if (orbitHeld && SystemClock.uptimeMillis() - orbitMoveTime > 50) {
            spinRate *= Math.exp(-elapsed / .06);
        }
        final int count = gifts.size();
        if (ringAngles.length < count) ringAngles = new double[count];
        final double even = count > 0 ? 2 * Math.PI / count : 0;
        for (int step = 0; step < steps; step++) {
            physicsTime += dt;
            for (int i = 0; i < count; i++) {
                final Orbit body = gifts.get(i).orbit;
                ringAngles[i] = Math.atan2(body.v(), body.u());
            }
            for (int i = 0; i < count; i++) {
                Orbit body = gifts.get(i).orbit;
                body.squash *= Math.exp(-9 * dt);
                final double ease = -Math.expm1(-dt / STRETCH_EASE);
                body.stretchVx += (body.vx * rx - body.stretchVx) * ease;
                body.stretchVy += (body.vy * ry - body.stretchVy) * ease;
                if (body.state == Orbit.HELD) {
                    body.lift += (1 - body.lift) * -Math.expm1(-18 * dt);
                    continue;
                }
                final double u = body.u(), v = body.v(), vu = body.vu(), vv = body.vv();
                final double r = Math.max(.05, Math.hypot(u, v));
                final double nu = u / r, nv = v / r, tu = -nv, tv = nu;
                final double vr = vu * nu + vv * nv, vt = vu * tu + vv * tv;
                final double target = body.radiusAt(physicsTime);
                final boolean returning = body.state == Orbit.RETURNING;
                final boolean thrown = body.state == Orbit.FLYING;
                // Held on its orbit at any speed, its radius following its own drift; one let go
                // of, or thrown, springs back to it
                double ar = -vt * vt / r;
                if (returning || thrown) {
                    ar += -RETURN_SPRING * RETURN_SPRING * (r - target) - 2 * RETURN_DAMPING * RETURN_SPRING * vr;
                } else {
                    ar += -DRIFT_SPRING * (r - target) - DRIFT_DAMPING * vr;
                }
                // let go, it drops the finger's speed along the orbit at once and goes back to
                // where it was, it does not whirl round the avatar; thrown, it slides round its
                // orbit and slows down to its own pace
                double at;
                if (thrown) {
                    at = (body.speed - vt) / THROW_SLOWDOWN;
                } else if (orbitHeld && !returning) {
                    at = SPIN_DRIVE * (spinRate * r - vt);
                } else {
                    at = (returning ? RETURN_PACE : PACE_DRIVE) * (body.speed - vt);
                }
                if (count > 1 && !body.isPassing()) {
                    for (int j = 0; j < count; j++) {
                        if (j == i || gifts.get(j).orbit.isPassing()) continue;
                        final double d = Math.atan2(Math.sin(ringAngles[i] - ringAngles[j]), Math.cos(ringAngles[i] - ringAngles[j]));
                        if (Math.abs(d) < even) at += EVEN_PUSH * Math.copySign(even - Math.abs(d), d) * r;
                    }
                }
                double au = ar * nu + at * tu, av = ar * nv + at * tv;
                // the others close by on the disk push it away a little
                for (int j = 0; j < count && !body.isPassing(); j++) {
                    if (j == i) continue;
                    final Orbit other = gifts.get(j).orbit;
                    if (other.isPassing()) continue;
                    final double du = u - other.u(), dv = v - other.v(), d = Math.hypot(du, dv);
                    if (d < SPACING && d > 1e-3) {
                        au += SPACING_PUSH * (SPACING - d) * du / d;
                        av += SPACING_PUSH * (SPACING - d) * dv / d;
                    }
                }
                double nvu = vu + au * dt, nvv = vv + av * dt;
                if (!returning) {
                    // however hard it is knocked, a free bubble does not fly off
                    final double across = Math.max(-MAX_ACROSS, Math.min(MAX_ACROSS, nvu * nu + nvv * nv));
                    final double along = Math.max(-MAX_ALONG, Math.min(MAX_ALONG, nvu * tu + nvv * tv));
                    nvu = across * nu + along * tu;
                    nvv = across * nv + along * tv;
                }
                body.setDisk(u + nvu * dt, v + nvv * dt, nvu, nvv);
                body.lift *= Math.exp(-(returning ? 4 : thrown ? 3 : 5.2) * dt);
                if (thrown && Math.abs(vt - body.speed) < .25 && Math.abs(r - target) < .12) {
                    body.state = Orbit.FREE;
                    body.sunk = false;
                }
                if (returning) {
                    // On its way to the far side it goes behind the avatar as soon as it is clear
                    // of it, not with a jump once it is there
                    if (!body.sunk && v < 0 && body.lift < .6 && isClearOfAvatar(body, rx, ry)) {
                        body.sunk = true;
                    }
                    if (Math.abs(r - target) < .12 && Math.abs(vr) < .4 && body.lift < .3) {
                        body.state = Orbit.FREE;
                        body.sunk = false;
                    }
                }
            }
            collide(rx, ry, -Math.expm1(-30 * dt));
            for (int i = 0; i < count; i++) {
                keepOnScreen(gifts.get(i).orbit, rx, ry);
            }
        }
    }

    /**
     * No bubble leaves the screen, the held one included: at an edge it stops, flattened against
     * it, and keeps only its speed along it.
     */
    private void keepOnScreen(Orbit body, double rx, double ry) {
        final int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || rx <= 0 || ry <= 0) return;
        // as big as it is drawn: swelling on the beat and wobbling
        final double r = dpf2(BUBBLE_DIAMETER / 2) * body.radiusScale() * (1 + .12 * musicPulse) * 1.06 + dpf2(2);
        final double px = orbitCenterX + body.x * rx, py = orbitCenterY + body.y * ry;
        final double left = r, right = width - r, top = r, bottom = height - r;
        if (left < right) {
            if (px < left) {
                body.x = (left - orbitCenterX) / rx;
                if (body.vx < 0) body.vx = 0;
                body.press(Math.min(.14, (left - px) / r * .5 + .04), -1, 0);
            } else if (px > right) {
                body.x = (right - orbitCenterX) / rx;
                if (body.vx > 0) body.vx = 0;
                body.press(Math.min(.14, (px - right) / r * .5 + .04), 1, 0);
            }
        }
        if (top < bottom) {
            if (py < top) {
                body.y = (top - orbitCenterY) / ry;
                if (body.vy < 0) body.vy = 0;
                body.press(Math.min(.14, (top - py) / r * .5 + .04), 0, -1);
            } else if (py > bottom) {
                body.y = (bottom - orbitCenterY) / ry;
                if (body.vy > 0) body.vy = 0;
                body.press(Math.min(.14, (py - bottom) / r * .5 + .04), 0, 1);
            }
        }
    }

    private boolean isClearOfAvatar(Orbit body, double rx, double ry) {
        if (avatarRadius <= 0) return true;
        double ax = orbitCenterX + body.x * rx - avatarCenterX, ay = orbitCenterY + body.y * ry - avatarCenterY;
        return Math.hypot(ax, ay) > avatarRadius + dpf2(BUBBLE_DIAMETER / 2) * body.radiusScale();
    }

    /**
     * How much a bubble gives way in a contact: the held one not at all, it pushes the others out
     * of its way; one springing back little.
     */
    private static double inverseMass(Orbit body) {
        switch (body.state) {
            case Orbit.HELD:
                return 0;
            case Orbit.RETURNING:
                return .15;
            default:
                return 1;
        }
    }

    /**
     * Soft contacts, as on iOS. Bubbles may lie over each other and over the avatar: they are
     * pushed apart only once they overlap deep, over a few frames, and what flattens them is the
     * blow, how fast they meet, not the touch. Bubbles at different depths pass each other;
     * lifted ones are over the front ones. Only the bubbles in front of the avatar meet it.
     */
    private void collide(double rx, double ry, double settle) {
        final double base = dpf2(BUBBLE_DIAMETER / 2);
        final double blow = dpf2(1300);
        for (int i = 0; i < gifts.size(); i++) {
            Orbit a = gifts.get(i).orbit;
            double ra = base * a.radiusScale();
            if (a.state != Orbit.HELD && a.state != Orbit.FLYING && a.isInFront() && avatarRadius > 0) {
                double dx = orbitCenterX + a.x * rx - avatarCenterX, dy = orbitCenterY + a.y * ry - avatarCenterY;
                double d = Math.hypot(dx, dy);
                if (d < avatarRadius + ra * .5 && d > .001) {
                    double nx = dx / d, ny = dy / d;
                    // landing on the avatar's edge flattens the bubble against it
                    double closing = -(a.vx * rx * nx + a.vy * ry * ny);
                    if (closing > 0) a.press(Math.min(.2, closing / blow), nx, ny);
                    // at rest on the ring's near side it lies half over the avatar; pushed
                    // further in, it is held back
                    double solid = avatarRadius * .62 + ra * .2;
                    if (a.state == Orbit.FREE && d < solid) {
                        double depth = solid - d;
                        a.x += depth * settle * nx / rx;
                        a.y += depth * settle * ny / ry;
                        if (closing > 0) {
                            a.vx += 1.15 * closing * nx / rx;
                            a.vy += 1.15 * closing * ny / ry;
                        }
                        a.press(Math.min(.14, depth / ra), nx, ny);
                    }
                }
            }
            for (int j = i + 1; j < gifts.size(); j++) {
                Orbit b = gifts.get(j).orbit;
                // a thrown or springing back bubble flies over the others
                if (a.isInFront() != b.isInFront() || a.isPassing() || b.isPassing()) continue;
                double rb = base * b.radiusScale();
                double dx = (b.x - a.x) * rx, dy = (b.y - a.y) * ry;
                double d = Math.hypot(dx, dy), touch = (ra + rb) * .92, contact = (ra + rb) * .7;
                if (d >= touch) continue;
                double nx = d > .001 ? dx / d : 1, ny = d > .001 ? dy / d : 0;
                double vn = (b.vx - a.vx) * rx * nx + (b.vy - a.vy) * ry * ny;
                if (vn < 0) {
                    double press = Math.min(.16, -vn / blow);
                    a.press(press, nx, ny);
                    b.press(press, nx, ny);
                    vn = Math.max(vn, -dpf2(MAX_KNOCK));
                }
                if (d >= contact) continue;
                double ia = inverseMass(a), ib = inverseMass(b), sum = ia + ib;
                if (sum <= 0) continue;
                double depth = contact - d;
                double press = Math.min(.1, depth / (ra + rb) * 1.2);
                a.press(press, nx, ny);
                b.press(press, nx, ny);
                double move = depth * settle / sum;
                a.x -= move * ia * nx / rx;
                a.y -= move * ia * ny / ry;
                b.x += move * ib * nx / rx;
                b.y += move * ib * ny / ry;
                if (vn < 0) {
                    // pushed aside, barely bouncing, most of it taken by the lighter bubble
                    double impulse = -(1 + BOUNCE) * vn / sum;
                    a.vx -= impulse * ia * nx / rx;
                    a.vy -= impulse * ia * ny / ry;
                    b.vx += impulse * ib * nx / rx;
                    b.vy += impulse * ib * ny / ry;
                }
            }
        }
    }

    private final TimeInterpolator giftCollapseXInterpolator = new DecelerateInterpolator();
    private final TimeInterpolator giftCollapseYInterpolator = new LinearInterpolator();

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (!areGiftsVisible()) return;
        if (!ViewBehindBackdrop.isAnyDrawing()) {
            prepareFrame(getDrawingTime());
            if (active && actionBarProgress < 1f && isShown()) {
                postInvalidateOnAnimation();
                frontLayer.postInvalidateOnAnimation();
            }
        }
        drawLayer(canvas, false);
    }

    private boolean areGiftsVisible() {
        return !gifts.isEmpty() && expandProgress < 1f && collapseProgress > 0f
                && expandY > AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
    }

    // Both layers draw the same frame. Capturing the rear in the front glass must
    // not advance the physics or tick the artwork's particles again.
    private void prepareFrame(long time) {
        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = (avatarContainer.getWidth()) * avatarContainer.getScaleX();
        final float realH = (avatarContainer.getHeight()) * avatarContainer.getScaleY();

        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);
        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        // The ring and the bubbles drifting out of it, at their size on its sides, stay on the screen
        final float orbitX = Math.min((float) ((Math.min(acx, getWidth() - acx) - dpf2(28)) / (RING_WIDTH * MAX_DRIFT)), aw * 2.1f);
        final float orbitY = ah * .57f;
        orbitCenterX = acx;
        orbitCenterY = acy;
        orbitRadiusX = Math.max(1f, orbitX);
        orbitRadiusY = Math.max(1f, orbitY);
        avatarCenterX = realCX;
        avatarCenterY = realCY;
        avatarRadius = Math.min(realW, realH) / 2f * avatarPulseScale;
        advanceGifts(time);
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            gift.alpha = enter * (1.0f - expandProgress) * (1.0f - actionBarProgress) * (closedAlpha);

            float gx = acx + orbitRadiusX * (float) gift.orbit.x;
            float gy = acy + orbitRadiusY * (float) gift.orbit.y;
            float delayValue = gift.position % 3 == 0 ? 1.6f : gift.position % 3 == 1 ? 0f : .9f;

            final float collapseProgressWithEnter;
            if (isOpening || enter >= 1f) {
                collapseProgressWithEnter = collapseProgress;
            } else {
                collapseProgressWithEnter = Math.min(enter, collapseProgress);
            }

            final float delayFraction = 0.2f;
            final float maxDelayFraction = 1.6f * delayFraction;
            final float intervalFraction = 1f - maxDelayFraction;

            float delay = delayValue * delayFraction;
            float collapse = collapseProgressWithEnter >= 1f - delay ? 1f
                    : Utilities.clamp01((collapseProgressWithEnter - maxDelayFraction + delay) / intervalFraction);
            if (collapse < 1f) {
                gx = AndroidUtilities.lerp(realCX, gx, giftCollapseXInterpolator.getInterpolation(collapse));
                gy = AndroidUtilities.lerp(realCY, gy, giftCollapseYInterpolator.getInterpolation(collapse));
                scale = AndroidUtilities.lerp(scale / 2f, scale, collapse);
            }

            gift.inFront = gift.orbit.isInFront();
            scale *= 1f + .12f * musicPulse;
            float rim = dpf2(BUBBLE_DIAMETER / 2) * scale * (float) gift.orbit.radiusScale();
            gift.bounds.set(gx - rim, gy - rim, gx + rim, gy + rim);
            gift.artworkScale = scale * (float) (gift.orbit.perspectiveScale() * (1 + .95 * gift.orbit.lift));
            // Room for the stretched rim and the glow surrounding the gift.
            float extent = Math.max(1.48f * rim, dpf2(26) * gift.artworkScale) + dpf2(1);
            gift.visualBounds.set(gx - extent, gy - extent, gx + extent, gy + extent);
        }
        // The nearer bubbles go over the farther ones; held and springing back ones over them
        // all, the most lifted on top
        renderOrder.clear();
        renderOrder.addAll(drawOrder);
        renderOrder.sort((a, b) -> Double.compare(a.orbit.isLifted() ? 2 + a.orbit.lift : a.orbit.depth(), b.orbit.isLifted() ? 2 + b.orbit.lift : b.orbit.depth()));
        collectLitShapes(realX, realY, realW, realH);
    }

    /* The light of the bubbles: each glows in the color of its gift, on the header around it and
       on the rims of the buttons and of the avatar it flies close to */

    /** Collects the shapes over the header the bubbles' light falls on, see {@link #addLitShape}. */
    public interface LitShapes {
        void collect(ProfileGiftsView gifts);
    }

    private static final int MAX_LIT_SHAPES = 16;
    private static final int LIT_FIELDS = 6;
    private final float[] litShapes = new float[MAX_LIT_SHAPES * LIT_FIELDS];
    private int litCount;
    private LitShapes litShapesSource;
    private final int[] ownLocation = new int[2], otherLocation = new int[2];
    private final RectF litRect = new RectF();
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix lightMatrix = new Matrix();

    {
        // the glow is screened over the header: added, it went white over a mid-tone header
        haloPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        flashPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public void setLitShapes(LitShapes source) {
        litShapesSource = source;
        invalidate();
    }

    /** A rounded shape of another view in that view's coordinates, lit as long as it is drawn with alpha. */
    public void addLitShape(View owner, RectF rect, float radius, float alpha) {
        if (litCount >= MAX_LIT_SHAPES || alpha <= .01f || rect.isEmpty() || owner == null) return;
        float dx, dy;
        if (owner.getParent() == getParent()) {
            dx = owner.getX() - getX();
            dy = owner.getY() - getY();
        } else {
            owner.getLocationInWindow(otherLocation);
            getLocationInWindow(ownLocation);
            dx = otherLocation[0] - ownLocation[0];
            dy = otherLocation[1] - ownLocation[1];
        }
        final int i = litCount++ * LIT_FIELDS;
        litShapes[i] = rect.left + dx;
        litShapes[i + 1] = rect.top + dy;
        litShapes[i + 2] = rect.right + dx;
        litShapes[i + 3] = rect.bottom + dy;
        litShapes[i + 4] = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        litShapes[i + 5] = Utilities.clamp01(alpha);
    }

    private void collectLitShapes(float avatarX, float avatarY, float avatarW, float avatarH) {
        litCount = 0;
        if (avatarContainer.getVisibility() == VISIBLE && avatarContainer.getAlpha() > 0f && avatarW > 0) {
            // the photo swells around its center on the beat
            final float growX = avatarW * (avatarPulseScale - 1f) / 2f, growY = avatarH * (avatarPulseScale - 1f) / 2f;
            final int i = litCount++ * LIT_FIELDS;
            litShapes[i] = avatarX - growX;
            litShapes[i + 1] = avatarY - growY;
            litShapes[i + 2] = avatarX + avatarW + growX;
            litShapes[i + 3] = avatarY + avatarH + growY;
            litShapes[i + 4] = Math.min(avatarW + 2 * growX, avatarH + 2 * growY) / 2f;
            litShapes[i + 5] = avatarContainer.getAlpha();
        }
        if (litShapesSource != null) litShapesSource.collect(this);
    }

    private float lightStrength() {
        return (1f + .6f * musicPulse) * (1f + .35f * musicDim);
    }

    /**
     * The bubble's own glow, just around its rim. At rest it is a breath of its color, a soap
     * bubble does not shine; picked up, and on the music's beat, it lights up.
     */
    private void drawHalos(Canvas canvas) {
        final float dim = 1f + .35f * musicDim;
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.haloShader == null) continue;
            final float lift = (float) Math.min(1, gift.orbit.lift);
            final float glow = (.12f + .55f * lift + .5f * musicPulse) * dim * gift.alpha;
            if (glow <= .01f) continue;
            final float cx = gift.bounds.centerX(), cy = gift.bounds.centerY();
            // the glow flares wider on the beat
            final float radius = gift.bounds.width() / 2f * HALO_EXTENT * (1f + .25f * musicPulse);
            lightMatrix.setScale(radius, radius);
            lightMatrix.postTranslate(cx, cy);
            gift.haloShader.setLocalMatrix(lightMatrix);
            haloPaint.setShader(gift.haloShader);
            haloPaint.setAlpha((int) (0xFF * Utilities.clamp01(glow)));
            canvas.drawCircle(cx, cy, radius, haloPaint);
        }
        haloPaint.setShader(null);
    }

    /**
     * The light on the shapes near the bubbles, as on iOS: a thin bright rim in the bubble's color
     * on the side facing it, and on the round ones (the avatar, the buttons) a glow of it fading
     * inwards from that rim. It falls off with the distance from the bubble's surface and reaches
     * about its own width, so the avatar's rim takes the colors of the bubbles around it, each on
     * its own side; a bubble picked up shines stronger and further.
     */
    private void drawLight(Canvas canvas) {
        if (litCount == 0) return;
        final float strength = lightStrength();
        final float rimWidth = dpf2(1.3f);
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.glintShader == null) continue;
            final float lift = (float) Math.min(1, gift.orbit.lift);
            final float bx = gift.bounds.centerX(), by = gift.bounds.centerY();
            final float br = gift.bounds.width() / 2f;
            final float reach = br * 2.2f * (1f + .3f * musicPulse);
            final float power = gift.alpha * strength * (.62f + .6f * lift);
            boolean shaderSet = false;
            for (int s = 0; s < litCount; s++) {
                final int i = s * LIT_FIELDS;
                final float l = litShapes[i], t = litShapes[i + 1], r = litShapes[i + 2], b = litShapes[i + 3];
                final float radius = litShapes[i + 4];
                final float dx = Math.max(0, Math.max(l - bx, bx - r));
                final float dy = Math.max(0, Math.max(t - by, by - b));
                final float gap = Math.max(0, (float) Math.hypot(dx, dy) - br);
                if (gap >= reach) continue;
                final float near = 1f - gap / reach;
                final float intensity = near * (.55f + .45f * near) * litShapes[i + 5] * power;
                if (intensity <= .01f) continue;
                if (!shaderSet) {
                    shaderSet = true;
                    // the light's own falloff from the bubble keeps it to the side facing it
                    final float lightRadius = br + reach;
                    lightMatrix.setScale(lightRadius, lightRadius);
                    lightMatrix.postTranslate(bx, by);
                    gift.glintShader.setLocalMatrix(lightMatrix);
                    lightPaint.setShader(gift.glintShader);
                }
                final boolean round = radius >= Math.min(r - l, b - t) / 2f - .5f && Math.abs((r - l) - (b - t)) < 1f;
                litRect.set(l, t, r, b);
                lightPaint.setStyle(Paint.Style.STROKE);
                if (round) {
                    // the glow inside the rim: thin rings going in, each fainter
                    final float cx = litRect.centerX(), cy = litRect.centerY();
                    final float step = radius * .045f;
                    lightPaint.setStrokeWidth(step + 1f);
                    for (int ring = 0; ring < 5; ring++) {
                        final float fade = 1f - ring / 5f;
                        lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.3f * fade * fade * intensity)));
                        canvas.drawCircle(cx, cy, radius - step * (ring + .5f), lightPaint);
                    }
                } else {
                    lightPaint.setStyle(Paint.Style.FILL);
                    lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.07f * intensity)));
                    canvas.drawRoundRect(litRect, radius, radius, lightPaint);
                    lightPaint.setStyle(Paint.Style.STROKE);
                }
                lightPaint.setStrokeWidth(rimWidth);
                lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(intensity)));
                litRect.inset(rimWidth / 2f, rimWidth / 2f);
                final float rimRadius = Math.max(0, radius - rimWidth / 2f);
                canvas.drawRoundRect(litRect, rimRadius, rimRadius, lightPaint);
            }
        }
        lightPaint.setShader(null);
    }

    private void clipGiftLayer(Canvas canvas) {
        if (collapseProgress < 1f || expandProgress > 0f) canvas.clipRect(0, 0, getWidth(), expandY);
    }

    private void findOverlappingGifts(boolean front) {
        for (Gift gift : renderOrder) {
            if (gift.inFront != front) continue;
            gift.lowerGifts.clear();
            gift.sampledByOtherGift = false;
        }
        for (int i = 1; i < renderOrder.size(); i++) {
            Gift upper = renderOrder.get(i);
            if (upper.alpha <= 0f || upper.inFront != front) continue;
            for (int j = 0; j < i; j++) {
                Gift lower = renderOrder.get(j);
                if (lower.alpha > 0f && lower.inFront == front && RectF.intersects(upper.visualBounds, lower.visualBounds)) {
                    upper.lowerGifts.add(lower);
                    lower.sampledByOtherGift = true;
                }
            }
        }
    }

    private void drawLayer(Canvas canvas, boolean front) {
        if (!areGiftsVisible()) return;
        boolean hasAnyGift = false, hasVisibleGift = false;
        for (Gift gift : gifts) {
            if (gift.alpha > 0f) {
                hasAnyGift = true;
                if (gift.inFront == front) {
                    hasVisibleGift = true;
                    break;
                }
            }
        }
        if (!hasAnyGift) return;
        final int save = canvas.save();
        clipGiftLayer(canvas);
        // The halos go on the header under everything; the light on the avatar and the buttons
        // over them, under the bubbles in front of them
        if (front) {
            drawLight(canvas);
        } else {
            drawHalos(canvas);
        }
        if (hasVisibleGift) {
            ProfileHeaderGlass glass = front ? frontGlass : rearGlass;
            boolean capturing = ViewBehindBackdrop.isAnyDrawing();
            boolean hasGlass = !capturing && glass.begin(canvas);
            if (hasGlass) findOverlappingGifts(front);
            for (Gift gift : renderOrder) {
                if (gift.alpha > 0f && gift.inFront == front) {
                    // Each node refers only to nodes already drawn below it in this pass.
                    if (hasGlass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && gift.sampledByOtherGift) {
                        gift.recordAndDraw(canvas, glass);
                    } else {
                        gift.draw(canvas, glass, hasGlass, !capturing);
                    }
                }
            }
        }
        canvas.restoreToCount(save);
    }

    private Gift getGiftUnder(float x, float y, boolean front) {
        for (int i = renderOrder.size() - 1; i >= 0; i--) {
            Gift gift = renderOrder.get(i);
            if (gift.alpha > 0f && gift.inFront == front && gift.bounds.contains(x, y)) return gift;
        }
        return null;
    }

    private final class FrontLayer extends View {
        FrontLayer(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            frontGlass.watch(true);
        }

        @Override
        protected void onDetachedFromWindow() {
            frontGlass.watch(false);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            drawLayer(canvas, true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return onGiftTouch(event, true);
        }
    }

    private Gift movingGift;
    private float downX, downY, grabX, grabY;
    private boolean dragging;
    private VelocityTracker velocityTracker;

    private void resetDrag() {
        if (movingGift != null) {
            // let go (or the gesture taken away): it springs back to its orbit
            if (movingGift.orbit.state == Orbit.HELD) movingGift.orbit.state = Orbit.RETURNING;
            movingGift.bounce.setPressed(false);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        movingGift = null;
        dragging = false;
        heldVx = heldVy = 0;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return onGiftTouch(event, false);
    }

    /**
     * A bubble is picked up and pulled away from its orbit, as far as the finger goes. Let go, it
     * springs back to it with a part of the finger's speed, a little past it, and flies on; thrown
     * by a flick, it slides round its orbit the way of the flick, over the others, slowing down.
     */
    private boolean onGiftTouch(MotionEvent event, boolean front) {
        float x = event.getX(), y = event.getY();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!active || orbitHeld || !areGiftsVisible() || ((collapseProgress < 1f || expandProgress > 0f) && y > expandY)) return false;
            Gift hit = getGiftUnder(x, y, front);
            if (hit == null) return false;
            resetDrag();
            movingGift = hit;
            downX = x;
            downY = y;
            // where on the bubble it was taken: it stays under the finger there
            grabX = x - hit.bounds.centerX();
            grabY = y - hit.bounds.centerY();
            velocityTracker = VelocityTracker.obtain();
            movingGift.bounce.setPressed(true);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (movingGift == null) return false;
        if (velocityTracker != null) velocityTracker.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (!dragging && dx * dx + dy * dy > slop * slop) {
                dragging = true;
                movingGift.orbit.state = Orbit.HELD;
            }
            if (dragging) {
                heldX = x - grabX;
                heldY = y - grabY;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    heldVx = velocityTracker.getXVelocity();
                    heldVy = velocityTracker.getYVelocity();
                }
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            final Gift gift = movingGift;
            final boolean wasDragging = dragging;
            if (wasDragging) {
                float vx = 0, vy = 0;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    vx = velocityTracker.getXVelocity();
                    vy = velocityTracker.getYVelocity();
                }
                releaseGift(gift, x - grabX, y - grabY, vx, vy);
                dragging = false;
            }
            resetDrag();
            if (!wasDragging) onGiftClick(gift);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            resetDrag();
        }
        return true;
    }

    private void releaseGift(Gift gift, float x, float y, float vx, float vy) {
        final Orbit body = gift.orbit;
        final double rx = orbitRadiusX, ry = orbitRadiusY;
        body.x = (x - orbitCenterX) / rx;
        body.y = (y - orbitCenterY) / ry;
        final double fingerSpeed = Math.hypot(vx, vy);
        if (fingerSpeed >= dpf2(THROW_SPEED)) {
            // Along the orbit where it is let go: the flick's part along the ring's tangent there
            final double u = body.u(), v = body.v(), r = Math.max(.05, Math.hypot(u, v));
            final double tu = -v / r, tv = u / r;
            // the flick on the disk, in ring radii a second
            final double fu = vx / rx / RING_WIDTH, fv = (vy / ry - RING_SLOPE * vx / rx) / RING_HEIGHT;
            final double along = Math.max(-MAX_THROW_ALONG, Math.min(MAX_THROW_ALONG, (fu * tu + fv * tv) * THROW_ALONG));
            body.state = Orbit.FLYING;
            body.flightTime = 0;
            body.setDisk(u, v, along * tu, along * tv);
            return;
        }
        double speed = fingerSpeed * RELEASE_SPEED;
        final double scale = speed > 0 ? Math.min(speed, dpf2(MAX_RELEASE_SPEED)) / fingerSpeed : 0;
        body.state = Orbit.RETURNING;
        body.vx = vx * scale / rx;
        body.vy = vy * scale / ry;
    }

    /* The orbit stirred by a sideways drag around the avatar, off the bubbles: the bubbles take
       the finger's angular speed, each on its own orbit, keep it when it lets go and slow back to
       their own pace; a finger held still stops them. The profile feeds its touches here before
       its views see them: a tap on the avatar, a vertical drag of the list and a swipe back from
       the screen's edge stay theirs. */

    private boolean orbitCandidate;
    private float orbitDownX, orbitDownY;
    private double orbitLastAngle;
    private long orbitLastTime;

    public boolean isTurningOrbit() {
        return orbitHeld;
    }

    private boolean isInOrbitArea(float x, float y) {
        if (!active || !areGiftsVisible() || expandProgress > 0f || collapseProgress < 1f || movingGift != null) return false;
        if (x < dp(20) || y > expandY) return false;
        final double d = Math.hypot((x - orbitCenterX) / orbitRadiusX, (y - orbitCenterY) / orbitRadiusY);
        return d > .2 && d < 1.8 && getGiftUnder(x, y, true) == null && getGiftUnder(x, y, false) == null;
    }

    /** The angle of a point around the avatar on the disk the bubbles fly on. */
    private double orbitAngleAt(float x, float y) {
        final double sx = (x - orbitCenterX) / orbitRadiusX, sy = (y - orbitCenterY) / orbitRadiusY;
        final double u = sx / RING_WIDTH, v = (sy - RING_SLOPE * sx) / RING_HEIGHT;
        return Math.atan2(v, u);
    }

    /**
     * A touch of the profile, in this view's coordinates. Returns true once a sideways drag around
     * the avatar holds the orbit, and for the rest of that gesture: the caller cancels it for the
     * other views then.
     */
    public boolean onOrbitTouch(MotionEvent event, float x, float y) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            orbitCandidate = isInOrbitArea(x, y);
            if (orbitCandidate) {
                orbitDownX = x;
                orbitDownY = y;
                // the swipe back and the tabs must not take a drag that may turn the orbit
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (orbitHeld) {
                turnOrbit(x, y, event.getEventTime());
                return true;
            }
            if (!orbitCandidate) return false;
            final float dx = x - orbitDownX, dy = y - orbitDownY;
            final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) {
                // a vertical drag: the list's, and the others may take what follows
                orbitCandidate = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return false;
            }
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                orbitCandidate = false;
                orbitHeld = true;
                spinRate = 0;
                orbitLastAngle = orbitAngleAt(x, y);
                orbitLastTime = event.getEventTime();
                orbitMoveTime = SystemClock.uptimeMillis();
                invalidate();
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean candidate = orbitCandidate;
            orbitCandidate = false;
            if (orbitHeld) {
                // let go, the bubbles fly on as fast as the finger took them and slow back to
                // their own pace
                orbitHeld = false;
                spinRate = 0;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            }
            if (candidate && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return false;
    }

    private void turnOrbit(float x, float y, long time) {
        // Too close to the center the angle jumps: the orbit waits for the finger to come out
        if (Math.hypot((x - orbitCenterX) / orbitRadiusX, (y - orbitCenterY) / orbitRadiusY) < .15) return;
        final double angle = orbitAngleAt(x, y);
        double delta = angle - orbitLastAngle;
        delta = Math.atan2(Math.sin(delta), Math.cos(delta));
        orbitLastAngle = angle;
        final double seconds = Math.max(.001, (time - orbitLastTime) / 1000.0);
        orbitLastTime = time;
        orbitMoveTime = SystemClock.uptimeMillis();
        // the finger's angular speed, smoothed over a few events, is what the bubbles take
        spinRate += (delta / seconds - spinRate) * Math.min(1, seconds / .06);
        spinRate = Math.max(-MAX_SPIN, Math.min(MAX_SPIN, spinRate));
        invalidate();
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
