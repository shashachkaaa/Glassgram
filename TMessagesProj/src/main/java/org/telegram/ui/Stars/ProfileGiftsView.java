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
import android.graphics.drawable.Drawable;
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
import org.telegram.messenger.GlassgramBadges;
import org.telegram.messenger.GlassgramProfileGift;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ProfileHeaderGlass;
import org.telegram.ui.Components.ViewBehindBackdrop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private static final float BUBBLE_DIAMETER = 44f;
    /** The gift inside its bubble, dp: some two fifths of it across, as on iOS. */
    private static final int ARTWORK_SIZE = 21;
    private Drawable testerDuckArtwork;

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
    /** The profile is off the screen (covered, its tab away, the app in the background): nothing moves, nothing is drawn anew. */
    private boolean paused;

    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        // back on the screen, the gifts go on from where they were, they do not jump the time away
        lastPhysicsFrame = 0;
        if (!paused) invalidate();
    }

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
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.glassgramProfileGiftLoaded);

        for (Gift gift : gifts) {
            if (gift.emojiDrawable != null) gift.emojiDrawable.addView(this);
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
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.glassgramProfileGiftLoaded);

        for (Gift gift : gifts) {
            if (gift.emojiDrawable != null) gift.emojiDrawable.removeView(this);
            if (gift.patternDrawable != null) gift.patternDrawable.removeView(this);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded && (long) args[0] == dialogId) {
            update();
        } else if ((id == NotificationCenter.glassgramProfileGiftLoaded && !profileGifts.isEmpty())
                || (id == NotificationCenter.updateInterfaces
                && ((Integer) args[0] & MessagesController.UPDATE_MASK_NAME) != 0
                && !profileGifts.equals(GlassgramBadges.getProfileGifts(dialogId)))) {
            update();
        }
    }

    public final class Gift {
        private final GlassgramProfileGift profileGift;

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
            this(gift, null);
        }

        private Gift(TL_stars.TL_starGiftUnique gift, GlassgramProfileGift profileGift) {
            this.profileGift = profileGift;
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
            profileGift = null;
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

            // A soap bubble at rest, as on iOS: a ball of the backdrop's color, light and full,
            // thin in the middle and thick in a wide band along the rim, where the film is seen
            // edge on: three quarters of what is behind it there is the film's color (a lilac,
            // an amber, a milky white one), easing off just at the edge, which has no white line.
            final int tint = filmColorOf(ColorUtils.blendARGB(color, edgeColor, .5f));
            filmPaint.setShader(new RadialGradient(0, 0, 1f,
                    new int[]{
                            Theme.multAlpha(tint, .04f),
                            Theme.multAlpha(tint, .12f),
                            Theme.multAlpha(tint, .3f),
                            Theme.multAlpha(tint, .6f),
                            Theme.multAlpha(tint, .74f),
                            Theme.multAlpha(tint, .68f),
                            Theme.multAlpha(tint, .5f)
                    },
                    new float[]{0f, .35f, .6f, .8f, .9f, .97f, 1f}, Shader.TileMode.CLAMP));
            // Picked up, it fills with its color like a ball of colored glass, clear only around
            // the gift; drawn over the film by how far the bubble is lifted.
            liftFilmPaint.setShader(new RadialGradient(0, 0, 1f,
                    new int[]{
                            Theme.multAlpha(tint, .16f),
                            Theme.multAlpha(tint, .38f),
                            Theme.multAlpha(tint, .64f),
                            Theme.multAlpha(ColorUtils.blendARGB(tint, Color.WHITE, .15f), .82f)
                    },
                    new float[]{0f, .45f, .75f, 1f}, Shader.TileMode.CLAMP));
            filmColor = tint;
            // the edge is the film's own color, a shade lighter, not a white line
            rimColor = ColorUtils.blendARGB(tint, Color.WHITE, .25f);
            lightShader = new RadialGradient(0, 0, 1f,
                    new int[]{lightColor, Theme.multAlpha(lightColor, .55f), Theme.multAlpha(lightColor, .16f), Theme.multAlpha(lightColor, 0f)},
                    new float[]{0f, .3f, .62f, 1f}, Shader.TileMode.CLAMP);
            // The glint on the rims around, the avatar's above all, is the film's own color, as
            // on iOS: a lilac bubble lights the avatar lilac. Added to the header it went white
            // over a mid-tone one
            final float[] hsv = new float[3];
            Color.colorToHSV(tint, hsv);
            hsv[2] = Math.max(hsv[2], .92f);
            final int glint = Color.HSVToColor(hsv);
            glintShader = new RadialGradient(0, 0, 1f,
                    new int[]{glint, Theme.multAlpha(glint, .6f), Theme.multAlpha(glint, .2f), Theme.multAlpha(glint, 0f)},
                    new float[]{0f, .3f, .62f, 1f}, Shader.TileMode.CLAMP);
            glowColor = glint;
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id && (profileGift == null
                    || profileGift.equals(b.profileGift) && document == b.document);
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
        /** The film's own color: the backdrop's, light and full, see {@link #filmColorOf}. */
        private int filmColor;
        /** Unit radius around (0, 0): the light, strongest at the bubble, fading out. */
        private RadialGradient lightShader;
        /** The same in the gift's saturated color, for the rims it shines on. */
        private RadialGradient glintShader;
        /** The color of the glow just around the bubble's rim, see {@link #drawGlow}. */
        private int glowColor;
        private Sparkles sparkles;

        private final ProfileHeaderGlass.Piece rearPiece = new ProfileHeaderGlass.Piece();
        private final ProfileHeaderGlass.Piece frontPiece = new ProfileHeaderGlass.Piece();
        public final RectF bounds = new RectF();
        private final RectF visualBounds = new RectF();
        private final ArrayList<Gift> lowerGifts = new ArrayList<>();
        private boolean sampledByOtherGift;
        private Object renderedGift;

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
            final float scale = artworkScale;
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
            final float held = Math.min(1f, lift);
            final int tint = filmColor;
            drawGlow(canvas, r, lift, alpha);
            canvas.save();
            canvas.clipPath(bubbleOutline);
            if (glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Its size in steps of 2 px, a little over the bubble: the glass's effect is
                // built anew only when it changes, not for every hair the bubble grows or shrinks
                // by every frame as it goes round nearer and further
                final float lensSize = Math.max(2f, 2f * Math.round(box.width() * 1.14f / 2f));
                lensBounds.set(cx - lensSize / 2f, cy - lensSize / 2f, cx + lensSize / 2f, cy + lensSize / 2f);
                // A ball of clear glass: what is behind it (the name, the avatar's rim, the
                // buttons) shows through it sharp, a little bigger and bent towards its edge.
                // Picked up, it is a thick ball of colored glass: what is behind it shows
                // through blurred and bigger, as on iOS
                glass.drawSphere(canvas, layer, lensBounds,
                        ColorUtils.setAlphaComponent(tint, (int) (0x10 + 0x3C * held)), alpha, 1.22f + .1f * held,
                        dpf2(LIFT_BLUR) * held, lowerGifts.isEmpty() ? null : this::drawLowerGifts);
            } else {
                bubblePaint.setColor(Theme.multAlpha(tint, (.1f + .24f * lift) * alpha));
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
            rimPaint.setColor(Theme.multAlpha(rimColor, Math.min(1f, .24f + .4f * lift + .25f * musicPulse) * alpha));
            canvas.drawPath(bubbleOutline, rimPaint);
            rimLightMatrix.setScale(r, r);
            rimLightMatrix.postTranslate(cx, cy);
            rimLightPaint.getShader().setLocalMatrix(rimLightMatrix);
            rimLightPaint.setStrokeWidth(dpf2(1f));
            // the light the edge catches: a glint, not a white outline
            rimLightPaint.setAlpha((int) (0xFF * alpha * (.35f + .3f * Math.min(1f, lift))));
            canvas.drawPath(bubbleOutline, rimLightPaint);
            if (org.telegram.messenger.GlassgramWinter.isActive()) {
                // in the winter, a tree's bauble: its cap on the film's top, wobbling with it
                drawOrnamentCap(canvas, tx, top, r, alpha);
            }
            canvas.restore();
        }

        /**
         * The bubble's own glow, along its outline as it wobbles, stretches and flattens: strokes
         * of it fading outwards, their inner halves under the glass drawn over them. At rest it is
         * a breath of its color, a soap bubble does not shine; picked up, and on the music's beat,
         * it lights up.
         */
        private void drawGlow(Canvas canvas, float r, float lift, float alpha) {
            final float glow = (.12f + .55f * Math.min(1f, lift) + .5f * musicPulse) * (1f + .35f * musicDim) * alpha;
            if (glow <= .01f) return;
            final float spread = 1f + .25f * musicPulse;
            haloPaint.setShader(null);
            haloPaint.setStyle(Paint.Style.STROKE);
            haloPaint.setStrokeJoin(Paint.Join.ROUND);
            for (int i = 0; i < GLOW_WIDTHS.length; i++) {
                haloPaint.setStrokeWidth(r * GLOW_WIDTHS[i] * spread);
                haloPaint.setColor(Theme.multAlpha(glowColor, Utilities.clamp01(GLOW_ALPHAS[i] * glow)));
                canvas.drawPath(bubbleOutline, haloPaint);
            }
        }

        private void drawArtwork(Canvas canvas, float alpha, boolean advanceParticles) {
            if (patternDrawable == null) {
                // No pattern to show (an old status): the stars of the gift's color instead
                if (advanceParticles) particles.process();
                particles.draw(canvas, color, alpha);
            }
            final Drawable artwork = profileGift != null && profileGift.isDuck() ? testerDuckArtwork : emojiDrawable;
            if (artwork != null) {
                // the gift floats in its bubble, it is not pinned to its middle
                final double time = SystemClock.uptimeMillis() * .001 + (id % 89);
                final int sz = dp(profileGift != null && profileGift.isDuck() ? 27 : ARTWORK_SIZE);
                canvas.translate(dpf2(1.1f) * (float) Math.sin(time * 1.3), dpf2(1.3f) * (float) Math.cos(time * 1.05));
                artwork.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                artwork.setAlpha((int) (0xFF * alpha));
                artwork.draw(canvas);
            }
        }
    }

    private static final int DEFAULT_BUBBLE_COLOR = 0xFF8E8E93;
    /** The glow around a bubble: stroke widths in its radii, the widest faintest; half of each is outside. */
    private static final float[] GLOW_WIDTHS = {.7f, .54f, .4f, .27f, .15f};
    private static final float[] GLOW_ALPHAS = {.08f, .1f, .13f, .17f, .24f};

    /**
     * The color of a bubble's film: its backdrop's hue, a little fuller and light, so that over
     * any header the rim reads as lilac, gold or cyan; a grey or silver backdrop gives a milky one.
     */
    private static int filmColorOf(int color) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // on iOS a lilac film is some 0.45 saturated, an amber one 0.6, a light blue one 0.35,
        // all of them bright
        hsv[1] = Math.min(Math.max(hsv[1], hsv[1] * 1.35f), Math.max(hsv[1], .62f));
        hsv[2] = Math.max(hsv[2], .93f);
        return Color.HSVToColor(hsv);
    }

    /** The color of the light a bubble of this color gives off: its hue, bright and a little washed out. */
    private static int lightColorOf(int color) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(hsv[1], .7f);
        hsv[2] = Math.max(hsv[2], .95f);
        return Color.HSVToColor(hsv);
    }

    // The winter's baubles (GlassgramWinter): a gold cap, its ring, and the thread it hangs by
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix capMatrix = new Matrix();
    private final RectF capRect = new RectF();
    private android.graphics.LinearGradient capShader;

    private void drawOrnamentCap(Canvas canvas, float x, float top, float r, float alpha) {
        if (capShader == null) {
            capShader = new android.graphics.LinearGradient(0, 0, 1, 0,
                    new int[]{0xFF7A5A1E, 0xFFC9A04A, 0xFFF8E6AE, 0xFFC9A04A, 0xFF6E5018},
                    new float[]{0f, .3f, .5f, .72f, 1f}, Shader.TileMode.CLAMP);
            capPaint.setShader(capShader);
        }
        final float w = r * .42f, h = r * .2f;
        final float capTop = top - h, ringR = r * .085f, ringY = capTop - ringR * .9f;
        // the thread, fading upwards
        capLinePaint.setStyle(Paint.Style.STROKE);
        capLinePaint.setStrokeCap(Paint.Cap.ROUND);
        capLinePaint.setStrokeWidth(dpf2(.8f));
        final float threadTop = ringY - ringR, threadLength = r * .95f;
        for (int i = 0; i < 4; i++) {
            capLinePaint.setColor(Theme.multAlpha(0xFFE9DDB8, alpha * (.55f - .13f * i)));
            canvas.drawLine(x, threadTop - threadLength * i / 4f, x, threadTop - threadLength * (i + 1) / 4f, capLinePaint);
        }
        // the ring
        capLinePaint.setStrokeWidth(dpf2(1f));
        capLinePaint.setColor(Theme.multAlpha(0xFFD9B45E, alpha));
        canvas.drawCircle(x, ringY, ringR, capLinePaint);
        // the cap, lit from the middle, its ribs darker
        capMatrix.setScale(w, 1f);
        capMatrix.postTranslate(x - w / 2f, 0);
        capShader.setLocalMatrix(capMatrix);
        capPaint.setAlpha((int) (0xFF * alpha));
        capRect.set(x - w / 2f, capTop, x + w / 2f, top + r * .07f);
        canvas.drawRoundRect(capRect, r * .05f, r * .05f, capPaint);
        capLinePaint.setStrokeWidth(dpf2(.6f));
        capLinePaint.setColor(Theme.multAlpha(0xFF5C4413, alpha * .55f));
        for (int i = 1; i <= 2; i++) {
            final float y = capTop + (capRect.height()) * i / 3f;
            canvas.drawLine(capRect.left + r * .02f, y, capRect.right - r * .02f, y, capLinePaint);
        }
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
        // Along the outline itself, clipped to its inside: the band hugs the film as it wobbles
        sheenMatrix.setScale(r * reach, r * reach);
        sheenMatrix.postTranslate(cx + nx * r, cy + ny * r);
        sheenPaint.getShader().setLocalMatrix(sheenMatrix);
        sheenPaint.setStrokeJoin(Paint.Join.ROUND);
        sheenPaint.setStrokeWidth(r * .5f);
        sheenPaint.setAlpha((int) (0xFF * alpha * .25f));
        canvas.drawPath(bubbleOutline, sheenPaint);
        sheenPaint.setStrokeWidth(r * .22f);
        sheenPaint.setAlpha((int) (0xFF * alpha * .3f));
        canvas.drawPath(bubbleOutline, sheenPaint);
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

    private List<GlassgramProfileGift> profileGifts = java.util.Collections.emptyList();

    public void update() {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        final boolean showTelegramGifts = controller.enableGiftsInProfile;
        profileGifts = GlassgramBadges.getProfileGifts(dialogId);
        maxCount = showTelegramGifts ? controller.stargiftsPinnedToTopLimit : 0;
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        if (showTelegramGifts) {
            final TLRPC.EmojiStatus emojiStatus;
            if (dialogId >= 0) {
                final TLRPC.User user = controller.getUser(dialogId);
                emojiStatus = user == null ? null : user.emoji_status;
            } else {
                final TLRPC.User chat = controller.getUser(-dialogId);
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
        }
        for (GlassgramProfileGift gift : profileGifts) {
            if (!gift.pinned) continue;
            TL_stars.TL_starGiftUnique original = gift.getOriginal(currentAccount);
            if (!gift.isDuck() && original == null) continue;
            Gift display = new Gift(gift.appearance(original), gift);
            if (giftIds.add(display.id)) {
                gifts.add(display);
                maxCount++;
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
                if (g.equals(oldGifts.get(j))) {
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
                if (g.profileGift != null && g.profileGift.isDuck()) {
                    if (testerDuckArtwork == null) {
                        testerDuckArtwork = getContext().getDrawable(R.drawable.glassgram_tester_duck).mutate();
                    }
                } else if (g.document != null) {
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
                    if (g.emojiDrawable != null) g.emojiDrawable.addView(this);
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
                if (g.equals(gifts.get(j))) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift == null) {
                if (g.emojiDrawable != null) {
                    g.emojiDrawable.removeView(this);
                    g.emojiDrawable = null;
                }
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

        // A new gift starts on its own orbit around the avatar, at a place of its own, not spread
        // evenly with the others: some close together, some apart, as on iOS. Where round it, how
        // fast, how its orbit drifts, new each time the profile is opened
        drawOrder.clear();
        for (int i = 0; i < gifts.size(); i++) {
            final Gift gift = gifts.get(i);
            if (gift.orbit == null) {
                gift.orbit = new Orbit(orbitRandom, freeAngle(gifts.size()), physicsTime);
            }
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
    /**
     * The near side of the disk is this much bigger, the far side this much smaller: in front of
     * the avatar a bubble is some three fifths of it across, behind it a bit over a quarter, as on
     * iOS. Picked up, it is as big as the avatar.
     */
    private static final double DEPTH_SCALE = .35, LIFT_SCALE = 1;
    /** Picked up, how much it blurs what is behind it, dp. */
    private static final float LIFT_BLUR = 5;
    /** Each bubble's own speed along its orbit, in ring radii per second, give or take a sixth. */
    private static final double ORBIT_SPEED = .32;
    /** How fast a bubble gets back to its own speed after a spin, and takes the finger's, 1/s. */
    private static final double PACE_DRIVE = 2, SPIN_DRIVE = 7;
    /** How firmly a bubble keeps to its drifting orbit radius: it is pushed off it, not flung. */
    private static final double DRIFT_SPRING = 9, DRIFT_DAMPING = 4.5;
    /**
     * Let go of, a bubble flies on as fast as the finger took it, wherever that is, and its own
     * spring takes it back to its orbit: rad/s, and its damping ratio, which is also how fast it
     * loses the finger's speed every way (some 14/s). Just let go of far off its orbit, over the
     * cards, it is back in a quarter of a second, a little past it and back, as on iOS; flicked,
     * it flies on a good way first, slowing down.
     */
    private static final double RETURN_SPRING = 11, RETURN_DAMPING = .62;
    /** How fast a bubble let go of loses its lift (its size and its color), 1/s: in a sixth of a second. */
    private static final double RETURN_DEFLATE = 14;
    /** The fastest a free bubble moves across its orbit and along it, ring radii per second. */
    private static final double MAX_ACROSS = 1.2, MAX_ALONG = 3.5;
    /**
     * Bubbles closer than this on the disk drift apart: only so as not to lie over each other.
     * They are not spread evenly round the ring: each keeps its own place, as on iOS, and a spin
     * of the orbit leaves them as they were.
     */
    private static final double SPACING = .42, SPACING_PUSH = 6;
    /** The most a bubble's orbit drifts out of the ring, for keeping it on the screen. */
    private static final double MAX_DRIFT = 1.13;
    /**
     * The film stretches along the way it goes at most by this (some 1.7 times as long as it is
     * wide, as a flung bubble on iOS), softly, three quarters of it at this speed, dp/s, and eases
     * into it and out of it, s: it stays drawn out a moment after it stops.
     */
    private static final double MAX_STRETCH = .3, STRETCH_EASE = .12;
    private static final float STRETCH_SPEED = 1400;
    /** The finger's speed at its last move counts for the flick if it lifted within this, ms. */
    private static final long FLICK_WINDOW = 40;
    /** The fastest a bubble let go of flies off, dp/s. */
    private static final float MAX_RELEASE_SPEED = 5000;
    private static final double MAX_SPIN = 3;
    /** A bubble knocked by another takes at most this speed from it, dp/s, and barely bounces. */
    private static final float MAX_KNOCK = 400;
    private static final double BOUNCE = .1;

    /** Seconds of the gifts' own time: their drifting follows it. */
    private double physicsTime;
    private final java.util.Random orbitRandom = new java.util.Random();
    /**
     * A place round the avatar for a new gift: at random, only not on top of another one, at
     * least this much of an even share of the ring away from each of them.
     */
    private double freeAngle(int count) {
        final double minGap = Math.min(.6, 2 * Math.PI / Math.max(1, count) * MIN_SHARE);
        double angle = orbitRandom.nextDouble() * 2 * Math.PI;
        for (int attempt = 0; attempt < 48; attempt++) {
            boolean free = true;
            for (Gift gift : gifts) {
                if (gift.orbit == null) continue;
                final double other = Math.atan2(gift.orbit.v(), gift.orbit.u());
                if (Math.abs(Math.atan2(Math.sin(angle - other), Math.cos(angle - other))) < minGap) {
                    free = false;
                    break;
                }
            }
            if (free) break;
            angle = orbitRandom.nextDouble() * 2 * Math.PI;
        }
        return angle;
    }

    private static final double MIN_SHARE = .45;

    private static final class Orbit {
        static final int FREE = 0, HELD = 1, RETURNING = 2;
        /** The bubble, in orbit radii from the orbit's center, and its speed in radii per second. */
        double x, y, vx, vy;
        double lift;
        /** How much a contact flattens the bubble, and the direction it is pressed in. */
        double squash, squashAngle;
        /** Its speed on the screen, px/s, smoothed, for how much the film stretches and where to. */
        double stretchVx, stretchVy;
        int state = FREE;
        /** Springing back to the far side of its orbit, and already gone behind the avatar. */
        boolean sunk;
        /** The radius its orbit drifts around, how far and how fast, and its own speed. */
        private final double base, swing, pace, phase, speed;
        /** Its speed goes up and down by this part of it, at this pace: a train of them breaks up. */
        private final double surge, surgePace, surgePhase;
        /** How late it comes out of the avatar when the header opens, 0 to 1.6. */
        final float enterDelay;

        Orbit(java.util.Random random, double angle, double time) {
            // each at its own distance and pace, some a good deal faster than others, as on iOS
            base = .93 + .14 * random.nextDouble();
            swing = .05 + .09 * random.nextDouble();
            pace = .3 + .35 * random.nextDouble();
            phase = random.nextDouble() * 2 * Math.PI;
            speed = ORBIT_SPEED * (.6 + .8 * random.nextDouble());
            surge = .15 + .2 * random.nextDouble();
            surgePace = .15 + .25 * random.nextDouble();
            surgePhase = random.nextDouble() * 2 * Math.PI;
            enterDelay = 1.6f * random.nextFloat();
            final double r = radiusAt(time);
            final double u = r * Math.cos(angle), v = r * Math.sin(angle);
            final double now = speedAt(time);
            setDisk(u, v, -now * Math.sin(angle), now * Math.cos(angle));
        }

        /** Its own speed along its orbit at this time, ring radii per second. */
        double speedAt(double time) {
            return speed * (1 + surge * Math.sin(surgePace * time + surgePhase));
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

        /** Held, or let go of and flying back: over everything, the avatar included. */
        boolean isLifted() {
            return state == HELD || state == RETURNING;
        }

        /**
         * Held, or flying back: it goes over the others, it does not push them, as on iOS; they
         * keep on their way under it.
         */
        boolean isPassing() {
            return state == HELD || state == RETURNING;
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
        // Held from the moment it is touched: it swells under the finger where it is, then goes
        // wherever the finger takes it, over the profile's cards too, as on iOS
        Gift held = holding ? movingGift : null;
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
        for (int step = 0; step < steps; step++) {
            physicsTime += dt;
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
                final double pace = body.speedAt(physicsTime);
                final boolean returning = body.state == Orbit.RETURNING;
                // Held on its orbit at any speed, its radius following its own drift; one let go
                // of is not on its orbit, it flies straight on and its spring pulls it back to it
                // from wherever it is
                double ar = returning ? 0 : -vt * vt / r;
                if (returning) {
                    ar += -RETURN_SPRING * RETURN_SPRING * (r - target) - 2 * RETURN_DAMPING * RETURN_SPRING * vr;
                } else {
                    ar += -DRIFT_SPRING * (r - target) - DRIFT_DAMPING * vr;
                }
                // Let go of, it loses the finger's speed every way alike, as fast as its spring
                // takes it in: flung, it flies on in a straight line and slows down, it does not
                // whirl round the avatar, and then goes on round at its own pace
                double at;
                if (returning) {
                    at = 2 * RETURN_DAMPING * RETURN_SPRING * (pace - vt);
                } else if (orbitHeld) {
                    at = SPIN_DRIVE * (spinRate * r - vt);
                } else {
                    at = PACE_DRIVE * (pace - vt);
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
                    // however hard it is knocked, a free bubble does not fly off; one let go of
                    // flies back from wherever it is as fast as its spring takes it
                    final double across = Math.max(-MAX_ACROSS, Math.min(MAX_ACROSS, nvu * nu + nvv * nv));
                    final double along = Math.max(-MAX_ALONG, Math.min(MAX_ALONG, nvu * tu + nvv * tv));
                    nvu = across * nu + along * tu;
                    nvv = across * nv + along * tv;
                }
                body.setDisk(u + nvu * dt, v + nvv * dt, nvu, nvv);
                // let go of, it shrinks back and loses its color at once, on its way
                body.lift *= Math.exp(-(returning ? RETURN_DEFLATE : 5.2) * dt);
                if (returning) {
                    // On its way to the far side it goes behind the avatar as soon as it is clear
                    // of it, not with a jump once it is there
                    if (!body.sunk && v < 0 && body.lift < .6 && isClearOfAvatar(body, rx, ry)) {
                        body.sunk = true;
                    }
                    if (Math.abs(r - target) < .12 && Math.abs(vr) < .4 && Math.abs(vt - pace) < .5 && body.lift < .3) {
                        body.state = Orbit.FREE;
                        body.sunk = false;
                    }
                }
            }
            collide(rx, ry, -Math.expm1(-30 * dt));
            // the held one goes wherever the finger does, even half off the screen; let go of
            // there, it comes back on as it shrinks, it does not jump
            final double cushion = -Math.expm1(-CUSHION * dt);
            for (int i = 0; i < count; i++) {
                final Orbit body = gifts.get(i).orbit;
                if (body.state != Orbit.HELD) keepOnScreen(body, rx, ry, body.state == Orbit.RETURNING ? cushion : 1);
            }
        }
    }

    /** How fast a bubble let go of over an edge of the screen comes back on, 1/s. */
    private static final double CUSHION = 22;

    /**
     * No free bubble leaves the screen: at an edge it stops, flattened against it, and keeps only
     * its speed along it. One let go of flies back as big as it is at rest: at an edge it stops
     * and is eased back on, by this part of the way each step, not flattened.
     */
    private void keepOnScreen(Orbit body, double rx, double ry, double ease) {
        final int width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0 || rx <= 0 || ry <= 0) return;
        final boolean soft = ease < 1;
        // as big as it is drawn: swelling on the beat and wobbling
        final double r = dpf2(BUBBLE_DIAMETER / 2) * (soft ? body.perspectiveScale() : body.radiusScale()) * (1 + .12 * musicPulse) * 1.06 + dpf2(2);
        final double px = orbitCenterX + body.x * rx, py = orbitCenterY + body.y * ry;
        final double left = r, right = width - r, top = r, bottom = height - r;
        if (left < right) {
            if (px < left) {
                body.x += ((left - orbitCenterX) / rx - body.x) * ease;
                if (body.vx < 0) body.vx = 0;
                if (!soft) body.press(Math.min(.14, (left - px) / r * .5 + .04), -1, 0);
            } else if (px > right) {
                body.x += ((right - orbitCenterX) / rx - body.x) * ease;
                if (body.vx > 0) body.vx = 0;
                if (!soft) body.press(Math.min(.14, (px - right) / r * .5 + .04), 1, 0);
            }
        }
        if (top < bottom) {
            if (py < top) {
                body.y += ((top - orbitCenterY) / ry - body.y) * ease;
                if (body.vy < 0) body.vy = 0;
                if (!soft) body.press(Math.min(.14, (top - py) / r * .5 + .04), 0, -1);
            } else if (py > bottom) {
                body.y += ((bottom - orbitCenterY) / ry - body.y) * ease;
                if (body.vy > 0) body.vy = 0;
                if (!soft) body.press(Math.min(.14, (py - bottom) / r * .5 + .04), 0, 1);
            }
        }
    }

    private boolean isClearOfAvatar(Orbit body, double rx, double ry) {
        if (avatarRadius <= 0) return true;
        double ax = orbitCenterX + body.x * rx - avatarCenterX, ay = orbitCenterY + body.y * ry - avatarCenterY;
        return Math.hypot(ax, ay) > avatarRadius + dpf2(BUBBLE_DIAMETER / 2) * body.radiusScale();
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
            // held or flying back, it goes over the avatar too, it does not land on it
            if (!a.isPassing() && a.isInFront() && avatarRadius > 0) {
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
                // only free bubbles meet, and they give way alike: the held one and the ones
                // flying back go over them
                final double ia = 1, ib = 1, sum = ia + ib;
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
            if (active && !paused && actionBarProgress < 1f && isShown()) {
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
        // Flat, as on iOS: the bubbles in front come over the avatar's lower edge, under the
        // name rather than over it, and the ones at the back are half hidden behind its top
        final float orbitY = ah * .5f;
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
            // each comes out of the avatar at its own moment
            float delayValue = gift.orbit.enterDelay;

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
            // Room for the stretched, flattened rim and the glow along it: drawn in the gift's
            // own layer, it must not be cut off at its edges
            float extent = Math.max(2.05f * rim, dpf2(26) * gift.artworkScale) + dpf2(1);
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
    /** The light washing over the photo from its rim: screened, it lights a dark photo up and leaves a light one be. */
    private final Paint washPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The first lit shape is the avatar's: lit much more, see {@link #drawLight}. */
    private boolean avatarLit;
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix lightMatrix = new Matrix();

    {
        // the glow is screened over the header: added, it went white over a mid-tone header
        haloPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        washPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        washPaint.setStyle(Paint.Style.STROKE);
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
        avatarLit = false;
        if (avatarContainer.getVisibility() == VISIBLE && avatarContainer.getAlpha() > 0f && avatarW > 0) {
            avatarLit = true;
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
     * The light on the shapes near the bubbles, as on iOS: a thin bright rim in the bubble's color
     * on the side facing it, and on the round ones (the buttons) a glow of it fading inwards from
     * that rim. It falls off with the distance from the bubble's surface and reaches about its own
     * width; a bubble picked up shines stronger and further.
     * <p>
     * The avatar is lit as a ball of glass is on iOS: its rim burns bright in the colors of the
     * bubbles around it, each on its own side along a long arc, and their light washes in over the
     * photo from there, a third of the way to its middle: a dark photo goes lilac or gold on the
     * side of a lilac or a golden bubble.
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
            final float power = gift.alpha * strength * (.62f + .6f * lift);
            boolean shaderSet = false;
            for (int s = 0; s < litCount; s++) {
                final int i = s * LIT_FIELDS;
                final float l = litShapes[i], t = litShapes[i + 1], r = litShapes[i + 2], b = litShapes[i + 3];
                final float radius = litShapes[i + 4];
                final boolean avatar = s == 0 && avatarLit;
                // the avatar catches the light from further off: all of the bubbles round it
                // tint its rim
                final float reach = br * (avatar ? AVATAR_LIGHT_REACH : 2.2f) * (1f + .3f * musicPulse);
                final float dx = Math.max(0, Math.max(l - bx, bx - r));
                final float dy = Math.max(0, Math.max(t - by, by - b));
                final float gap = Math.max(0, (float) Math.hypot(dx, dy) - br);
                if (gap >= reach) continue;
                final float near = 1f - gap / reach;
                final float intensity = near * (.55f + .45f * near) * litShapes[i + 5] * power;
                if (intensity <= .01f) continue;
                // the light's own falloff from the bubble keeps it to the side facing it
                final float lightRadius = br + reach;
                if (!shaderSet || avatar) {
                    shaderSet = !avatar;
                    lightMatrix.setScale(lightRadius, lightRadius);
                    lightMatrix.postTranslate(bx, by);
                    gift.glintShader.setLocalMatrix(lightMatrix);
                    lightPaint.setShader(gift.glintShader);
                }
                final boolean round = radius >= Math.min(r - l, b - t) / 2f - .5f && Math.abs((r - l) - (b - t)) < 1f;
                litRect.set(l, t, r, b);
                lightPaint.setStyle(Paint.Style.STROKE);
                if (avatar) {
                    drawAvatarLight(canvas, gift, litRect.centerX(), litRect.centerY(), radius, intensity);
                    // the rim, bright: on iOS it burns near white in the bubble's color
                    final float avatarRim = dpf2(1.7f);
                    lightPaint.setShader(gift.glintShader);
                    lightPaint.setStrokeWidth(avatarRim);
                    lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(1.5f * intensity)));
                    canvas.drawCircle(litRect.centerX(), litRect.centerY(), Math.max(0, radius - avatarRim / 2f), lightPaint);
                    // and a thin glow just outside it
                    lightPaint.setStrokeWidth(avatarRim * 2.5f);
                    lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.35f * intensity)));
                    canvas.drawCircle(litRect.centerX(), litRect.centerY(), radius + avatarRim, lightPaint);
                    // the buttons that come after take the gift's shader set afresh
                    shaderSet = false;
                    continue;
                }
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

    /** How far a bubble lights the avatar, in its radii from its surface. */
    private static final float AVATAR_LIGHT_REACH = 2.8f;
    /** How far in from its rim the light washes over the photo, in the avatar's radius, and in how many rings. */
    private static final float AVATAR_WASH_DEPTH = .36f;
    private static final int AVATAR_WASH_RINGS = 12;

    /**
     * The bubble's light washing in over the photo from its rim: rings going in, each fainter,
     * screened over the photo, in the bubble's own light, whose falloff from the bubble keeps it to
     * the side facing it and fading along the rim.
     */
    private void drawAvatarLight(Canvas canvas, Gift gift, float cx, float cy, float radius, float intensity) {
        if (gift.glintShader == null || intensity <= .01f) return;
        final float step = radius * AVATAR_WASH_DEPTH / AVATAR_WASH_RINGS;
        washPaint.setShader(gift.glintShader);
        washPaint.setStrokeWidth(step + 1f);
        for (int ring = 0; ring < AVATAR_WASH_RINGS; ring++) {
            final float fade = 1f - (float) ring / AVATAR_WASH_RINGS;
            final int a = (int) (0xFF * Utilities.clamp01(.8f * fade * fade * intensity));
            if (a <= 1) continue;
            washPaint.setAlpha(a);
            canvas.drawCircle(cx, cy, radius - step * (ring + .5f), washPaint);
        }
        washPaint.setShader(null);
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
        // The light on the avatar and the buttons goes over them, under the bubbles in front of them
        if (front) {
            drawLight(canvas);
        }
        if (hasVisibleGift) {
            ProfileHeaderGlass glass = front ? frontGlass : rearGlass;
            // A bubble taken down over the profile's cards bends and blurs them as it does the
            // header: they go into its glass then, and only then
            glass.setUnder(isAnyGiftOverPage(front) ? pageBehind : null);
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

    /** The profile's list under the header, a sibling of this view's parent drawn before the header. */
    private View pageBehind;

    public void setPageBehind(View page) {
        pageBehind = page;
    }

    private boolean isAnyGiftOverPage(boolean front) {
        if (pageBehind == null) return false;
        for (Gift gift : gifts) {
            if (gift.alpha > 0f && gift.inFront == front && gift.visualBounds.bottom > expandY) return true;
        }
        return false;
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
    private long lastMoveTime;
    /** A bubble is under the finger: held where it is, swelling, from the moment it is touched. */
    private boolean holding;
    /** The finger has gone past the touch slop: it is a drag, not a tap. */
    private boolean dragging;
    private VelocityTracker velocityTracker;

    private void resetDrag() {
        if (movingGift != null) {
            // let go (or the gesture taken away): it flies back to its orbit from where it is
            if (movingGift.orbit.state == Orbit.HELD) movingGift.orbit.state = Orbit.RETURNING;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        movingGift = null;
        holding = false;
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
     * As on iOS: a bubble touched is picked up at once, it swells where it is into a ball of
     * colored glass as big as the avatar, over everything, and goes wherever the finger takes it,
     * down over the profile's cards too, drawn out the way it goes. Let go, it flies on as fast as
     * the finger went, slowing down, shrinks back and loses its color on the way, and its spring
     * takes it back to its orbit. A tap opens the gift.
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
            heldX = hit.bounds.centerX();
            heldY = hit.bounds.centerY();
            heldVx = heldVy = 0;
            holding = true;
            hit.orbit.state = Orbit.HELD;
            hit.orbit.sunk = false;
            velocityTracker = VelocityTracker.obtain();
            getParent().requestDisallowInterceptTouchEvent(true);
            invalidate();
        }
        if (movingGift == null) return false;
        if (velocityTracker != null) velocityTracker.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (!dragging && dx * dx + dy * dy > slop * slop) {
                dragging = true;
            }
            if (dragging) {
                heldX = x - grabX;
                heldY = y - grabY;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    heldVx = velocityTracker.getXVelocity();
                    heldVy = velocityTracker.getYVelocity();
                }
                lastMoveTime = event.getEventTime();
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            final Gift gift = movingGift;
            final boolean wasDragging = dragging;
            float vx = 0, vy = 0;
            if (wasDragging) {
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    vx = velocityTracker.getXVelocity();
                    vy = velocityTracker.getYVelocity();
                }
                // A flick's last instant often slows down as the finger lifts: its speed just
                // before counts too, if it was just before
                if (event.getEventTime() - lastMoveTime <= FLICK_WINDOW && Math.hypot(heldVx, heldVy) > Math.hypot(vx, vy)) {
                    vx = heldVx;
                    vy = heldVy;
                }
                releaseGift(gift, x - grabX, y - grabY, vx, vy);
            } else {
                // tapped: it shrinks back where it is
                releaseGift(gift, heldX, heldY, 0, 0);
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
        // it flies on as fast as the finger went, see RETURN_SPRING
        final double fingerSpeed = Math.hypot(vx, vy);
        final double scale = fingerSpeed > 0 ? Math.min(fingerSpeed, dpf2(MAX_RELEASE_SPEED)) / fingerSpeed : 0;
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

    public interface ProfileGiftClickListener {
        void onGiftClick(GlassgramProfileGift gift);
    }

    private ProfileGiftClickListener clickListener;

    public void setProfileGiftClickListener(ProfileGiftClickListener listener) {
        clickListener = listener;
    }

    public void onGiftClick(Gift gift) {
        if (gift.profileGift != null) {
            if (clickListener != null) clickListener.onGiftClick(gift.profileGift);
            return;
        }
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
