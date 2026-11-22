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

            // Clear in the middle, the glass thickens towards the rim into a band of the
            // backdrop's color, as a ball of tinted glass seen edge on, and catches light at it.
            filmPaint.setShader(new RadialGradient(0, 0, 1f,
                    new int[]{
                            Theme.multAlpha(color, 0f),
                            Theme.multAlpha(color, .04f),
                            Theme.multAlpha(ColorUtils.blendARGB(color, edgeColor, .4f), .26f),
                            Theme.multAlpha(color, .62f),
                            Theme.multAlpha(lightColor, .82f)
                    },
                    new float[]{0f, .5f, .76f, .92f, 1f}, Shader.TileMode.CLAMP));
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
        private final Matrix filmMatrix = new Matrix();
        /** Unit radius around (0, 0): the light, strongest at the bubble, fading out. */
        private RadialGradient lightShader;
        /** The same in the gift's saturated color, for the rims it shines on. */
        private RadialGradient glintShader;
        /** The glow just around the bubble's rim, see {@link #drawHalos}. */
        private RadialGradient haloShader;
        private PatternParticles patterns = new PatternParticles();

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
            patterns = b.patterns;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
        }

        private void draw(Canvas canvas, ProfileHeaderGlass glass, boolean hasGlass, boolean advanceParticles) {
            canvas.save();
            double vx = orbit.vx * orbitRadiusX, vy = orbit.vy * orbitRadiusY;
            float stretch = (float) Math.min(.25, Math.hypot(vx, vy) / dpf2(950));
            drawBubble(canvas, bounds, alpha, (float) Math.toDegrees(Math.atan2(vy, vx)), stretch,
                    hasGlass, glass, inFront ? frontPiece : rearPiece);
            canvas.translate(bounds.centerX(), bounds.centerY());
            final float scale = artworkScale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            drawArtwork(canvas, alpha, 1f - .5f * (float) orbit.lift, advanceParticles);
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
            float tx = cx + r * .15f * wave, bx = cx - r * .12f * ripple;
            float ly = cy + r * .16f * ripple, ry = cy - r * .13f * wave;
            float k = r * .55f;
            bubbleOutline.rewind();
            bubbleOutline.moveTo(tx, top);
            bubbleOutline.cubicTo(tx + k, top, right, ry - k, right, ry);
            bubbleOutline.cubicTo(right, ry + k, bx + k, bottom, bx, bottom);
            bubbleOutline.cubicTo(bx - k, bottom, left, ly + k, left, ly);
            bubbleOutline.cubicTo(left, ly - k, tx - k, top, tx, top);
            bubbleOutline.close();
            canvas.save();
            canvas.clipPath(bubbleOutline);
            if (glassEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lensBounds.set(box);
                lensBounds.inset(-r * .04f, -r * .04f);
                // A ball of glass tinted with the gift's backdrop: what is behind it (the name,
                // the avatar's rim, the buttons) shows through it magnified and bent
                glass.drawSphere(canvas, layer, lensBounds,
                        ColorUtils.setAlphaComponent(ColorUtils.blendARGB(color, edgeColor, .35f), 0x40), alpha, 1.18f,
                        lowerGifts.isEmpty() ? null : this::drawLowerGifts);
            } else {
                bubblePaint.setColor(Theme.multAlpha(ColorUtils.blendARGB(color, edgeColor, .35f), .25f * alpha));
                canvas.drawPath(bubbleOutline, bubblePaint);
            }
            filmMatrix.setScale(r * 1.03f, r * 1.03f);
            filmMatrix.postTranslate(cx, cy);
            filmPaint.getShader().setLocalMatrix(filmMatrix);
            filmPaint.setAlpha((int) (0xFF * alpha));
            canvas.drawPath(bubbleOutline, filmPaint);
            if (musicPulse > 0f && lightShader != null) {
                // the bubble flashes from inside on the beat
                filmMatrix.setScale(r * 1.1f, r * 1.1f);
                filmMatrix.postTranslate(cx, cy);
                lightShader.setLocalMatrix(filmMatrix);
                flashPaint.setShader(lightShader);
                flashPaint.setAlpha((int) (0xFF * Utilities.clamp01(.45f * musicPulse * alpha)));
                canvas.drawPath(bubbleOutline, flashPaint);
                flashPaint.setShader(null);
            }
            if (patternDrawable != null) {
                // pale and see-through, as the pattern is pressed into the glass
                patterns.draw(canvas, patternDrawable, ColorUtils.blendARGB(patternColor, Color.WHITE, .6f), cx, cy, r, alpha, musicPulse);
            }
            drawSpecular(canvas, cx, cy, r, alpha);
            canvas.restore();
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(dpf2(1f + .6f * musicPulse));
            rimPaint.setColor(Theme.multAlpha(lightColor, Math.min(1f, .85f + .15f * musicPulse) * alpha));
            canvas.drawPath(bubbleOutline, rimPaint);
            canvas.restore();
        }

        private void drawArtwork(Canvas canvas, float alpha, float gradientAlpha, boolean advanceParticles) {
            final float gsz = dp(45);
            if (patternDrawable == null) {
                // No pattern to show (an old status): the stars of the gift's color instead
                if (advanceParticles) particles.process();
                particles.draw(canvas, color, alpha);
            }
            if (gradientPaint != null) {
                // only a hint of the glow behind the gift: the bubble is clear around it
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha * .3f));
                canvas.drawRect(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f, gradientPaint);
            }
            if (emojiDrawable != null) {
                final int sz = dp(24);
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

    private final Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix specularMatrix = new Matrix();

    {
        specularPaint.setShader(new RadialGradient(0, 0, 1f,
                new int[]{0x99FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
    }

    private final Paint reflexPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF reflexOval = new RectF();

    {
        reflexPaint.setStyle(Paint.Style.STROKE);
        reflexPaint.setStrokeCap(Paint.Cap.ROUND);
        reflexPaint.setColor(Color.WHITE);
    }

    /**
     * The window reflected in the glass, up and to the left, and the light coming through the
     * ball caught again on its inner side, a thin bright crescent down and to the right.
     */
    private void drawSpecular(Canvas canvas, float cx, float cy, float r, float alpha) {
        final float x = cx - r * .36f, y = cy - r * .46f;
        specularMatrix.setScale(r * .44f, r * .25f);
        specularMatrix.postRotate(-36f);
        specularMatrix.postTranslate(x, y);
        specularPaint.getShader().setLocalMatrix(specularMatrix);
        specularPaint.setAlpha((int) (0xFF * alpha));
        canvas.drawCircle(x, y, r * .46f, specularPaint);

        final float inset = r * .14f;
        reflexOval.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset);
        reflexPaint.setStrokeWidth(r * .07f);
        reflexPaint.setAlpha((int) (0x5C * alpha));
        canvas.drawArc(reflexOval, 18f, 64f, false, reflexPaint);
    }

    /**
     * The symbols of the gift's pattern drifting inside its bubble, fading in and out. They follow
     * the clock, not the frames: the rear and the front layer, and the captures, draw the same.
     */
    private static final class PatternParticles {
        private static final int COUNT = 9;
        private static final java.util.Random RANDOM = new java.util.Random();

        private final float[] x = new float[COUNT], y = new float[COUNT];
        private final float[] vx = new float[COUNT], vy = new float[COUNT];
        private final float[] size = new float[COUNT], rotation = new float[COUNT];
        private final long[] start = new long[COUNT], life = new long[COUNT];
        private boolean initialized;
        private ColorFilter filter;
        private int filterColor;

        /** Positions are in bubble radii from its center, speeds in radii per second. */
        private void spawn(int i, long now, boolean initial) {
            final double angle = RANDOM.nextDouble() * 2 * Math.PI;
            // Around the gift, towards the rim, not over it
            final double distance = .42 + .44 * Math.sqrt(RANDOM.nextDouble());
            x[i] = (float) (Math.cos(angle) * distance);
            y[i] = (float) (Math.sin(angle) * distance);
            final double heading = angle + (RANDOM.nextBoolean() ? 1 : -1) * Math.PI / 2 + (RANDOM.nextDouble() - .5) * .9;
            final double speed = .035 + .05 * RANDOM.nextDouble();
            vx[i] = (float) (Math.cos(heading) * speed);
            vy[i] = (float) (Math.sin(heading) * speed);
            size[i] = .2f + .11f * RANDOM.nextFloat();
            rotation[i] = (RANDOM.nextFloat() - .5f) * 50f;
            life[i] = 2600 + RANDOM.nextInt(2400);
            start[i] = initial ? now - RANDOM.nextInt((int) life[i]) : now + RANDOM.nextInt(450);
        }

        /** pulse: the music's beat, 0..1; the symbols are thrown out a little and flare with it. */
        void draw(Canvas canvas, AnimatedEmojiDrawable drawable, int color, float cx, float cy, float r, float alpha, float pulse) {
            final long now = SystemClock.uptimeMillis();
            if (!initialized) {
                initialized = true;
                for (int i = 0; i < COUNT; i++) spawn(i, now, true);
            }
            final boolean animate = LiteMode.isEnabled(LiteMode.FLAG_PARTICLES);
            if (filter == null || filterColor != color) {
                filterColor = color;
                filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
            final int sz = dp(12);
            drawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
            drawable.setColorFilter(filter);
            for (int i = 0; i < COUNT; i++) {
                float px = x[i], py = y[i], fade = .85f;
                if (animate) {
                    long age = now - start[i];
                    if (age >= life[i]) {
                        spawn(i, now, false);
                        age = now - start[i];
                    }
                    if (age < 0) continue;
                    final float seconds = age / 1000f;
                    px += vx[i] * seconds;
                    py += vy[i] * seconds;
                    fade = (float) Math.sin(Math.PI * age / life[i]);
                }
                final int a = (int) (0xFF * Math.min(1f, alpha * fade * (.62f + .3f * pulse)));
                if (a <= 0) continue;
                final float push = 1f + .14f * pulse;
                canvas.save();
                canvas.translate(cx + px * r * push, cy + py * r * push);
                canvas.rotate(rotation[i]);
                final float scale = size[i] * r / sz * (1f + .3f * pulse);
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

        drawOrder.clear();
        for (Gift gift : gifts) {
            if (gift.orbit == null) gift.orbit = new Orbit(gift.position);
            drawOrder.add(gift);
        }
        // In each avatar layer the faster orbit passes in front. Keep the order
        // stable while bubbles overlap, even as their screen-space speeds change.
        drawOrder.sort((a, b) -> Double.compare(a.orbit.path.speed, b.orbit.path.speed));
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

    private static final class OrbitPath {
        final double width, height, slope, speed, size;

        OrbitPath(double width, double height, double slope, double speed, double size) {
            this.width = width;
            this.height = height;
            this.slope = slope;
            this.speed = speed;
            this.size = size;
        }
    }

    // Positions share the same coordinate system, so the differently shaped orbits can intersect.
    // Each gift has a slot going round its path. The bubble rests on it, is pushed off it by the
    // other bubbles and the avatar, and is held on a spring to it: pulled away by the finger and
    // let go, it springs back.
    private static final class Orbit {
        static final int SETTLED = 0, HELD = 1, RETURNING = 2;
        private static final int[] SLOTS = {3, 4, 2, 0, 5, 1};
        private static final OrbitPath[] PATHS = {
                new OrbitPath(.98, .72, -.13, .34, .94),
                new OrbitPath(.76, .91, .12, .28, .82),
                new OrbitPath(.83, .94, -.11, .46, 1.12),
                new OrbitPath(.94, .63, .15, .36, 1.05),
                new OrbitPath(.66, .87, -.16, .50, .89),
                new OrbitPath(.88, .77, .11, .41, 1.16),
        };
        final OrbitPath path;
        /** The slot's angle on its path. */
        double phase;
        /** The bubble, in orbit radii from the orbit's center, and its speed in radii per second. */
        double x, y, vx, vy;
        double lift;
        /** How much a contact flattens the bubble, and the direction it is pressed in. */
        double squash, squashAngle;
        int state = SETTLED;

        Orbit(int position) {
            int slot = Math.floorMod(position, SLOTS.length);
            path = PATHS[slot];
            phase = SLOTS[slot] * Math.PI / 3;
            x = slotX();
            y = slotY();
            vx = slotVx(path.speed);
            vy = slotVy(path.speed);
        }

        double slotX() {
            return path.width * Math.cos(phase);
        }

        double slotY() {
            return path.height * Math.sin(phase) + path.slope * slotX();
        }

        /** The slot's speed while its angle changes at rate radians per second. */
        double slotVx(double rate) {
            return -path.width * Math.sin(phase) * rate;
        }

        double slotVy(double rate) {
            return path.height * Math.cos(phase) * rate + path.slope * slotVx(rate);
        }

        double perspectiveScale() {
            double depth = Math.max(-1, Math.min(1, y));
            return path.size * (1 + .12 * depth);
        }

        double radiusScale() {
            return perspectiveScale() * (1 + .85 * lift);
        }

        /** Held, or springing back: over everything, the avatar included. */
        boolean isLifted() {
            return state != SETTLED;
        }

        boolean isInFront() {
            return isLifted() || y >= 0;
        }

        void press(double amount, double nx, double ny) {
            if (amount > squash) {
                squash = amount;
                squashAngle = Math.atan2(ny, nx);
            }
        }
    }

    /** How firmly a bubble keeps to its slot, rad/s. */
    private static final double SETTLE_SPRING = 8;
    /**
     * The spring a bubble let go of springs back on: fast, and a little past its slot before it
     * settles, as on iOS. It keeps a part of the finger's speed.
     */
    private static final double RETURN_SPRING = 13, RETURN_DAMPING = .55, RELEASE_SPEED = .35;
    private static final float MAX_RELEASE_SPEED = 1400;
    /** A spun orbit slows down to the paths' own pace. */
    private static final double SPIN_DECAY = .9, SPIN_RESUME = .5, MAX_SPIN = 6;

    private float orbitCenterX, orbitCenterY, orbitRadiusX, orbitRadiusY;
    private float avatarCenterX, avatarCenterY, avatarRadius;
    private long lastPhysicsFrame;
    /** The held bubble's center and speed, in this view's pixels. */
    private float heldX, heldY, heldVx, heldVy;

    /** The orbit held by the finger, which turns it: the slots follow it and do not move on their own. */
    private boolean orbitHeld;
    /** Radians per second added to every slot: the finger's while it holds the orbit, decaying after. */
    private double spinRate;
    /** How far the paths' own pace came back since the orbit was let go. */
    private double spinResume = 1;

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
            body.x = (heldX - orbitCenterX) / rx;
            body.y = (heldY - orbitCenterY) / ry;
            body.vx = heldVx / rx;
            body.vy = heldVy / ry;
        }
        for (int step = 0; step < steps; step++) {
            if (!orbitHeld) {
                spinRate *= Math.exp(-dt / SPIN_DECAY);
                spinResume += (1 - spinResume) * -Math.expm1(-dt / SPIN_RESUME);
            }
            for (int i = 0; i < gifts.size(); i++) {
                Orbit body = gifts.get(i).orbit;
                // The slots go on while their bubbles are away; the finger turns them itself
                double rate = orbitHeld ? 0 : body.path.speed * spinResume + spinRate;
                if (!orbitHeld) body.phase += rate * dt;
                body.squash *= Math.exp(-9 * dt);
                if (body.state == Orbit.HELD) {
                    body.lift += (1 - body.lift) * -Math.expm1(-18 * dt);
                } else {
                    // A spring onto the moving slot: critically damped on the orbit, springy for
                    // a bubble let go of, which overshoots its slot a little
                    boolean returning = body.state == Orbit.RETURNING;
                    double w = returning ? RETURN_SPRING : SETTLE_SPRING;
                    double c = 2 * w * (returning ? RETURN_DAMPING : 1);
                    double ax = w * w * (body.slotX() - body.x) + c * (body.slotVx(rate) - body.vx);
                    double ay = w * w * (body.slotY() - body.y) + c * (body.slotVy(rate) - body.vy);
                    body.vx += ax * dt;
                    body.vy += ay * dt;
                    body.x += body.vx * dt;
                    body.y += body.vy * dt;
                    body.lift *= Math.exp(-(returning ? 4 : 5.2) * dt);
                    if (returning && hasReturned(body, rx, ry)) body.state = Orbit.SETTLED;
                }
            }
            collide(rx, ry, -Math.expm1(-30 * dt));
        }
    }

    /** Back on its slot. A slot behind the avatar is taken only clear of it: the bubble does not pop behind it. */
    private boolean hasReturned(Orbit body, double rx, double ry) {
        double dx = (body.slotX() - body.x) * rx, dy = (body.slotY() - body.y) * ry;
        double near = dpf2(3);
        if (dx * dx + dy * dy > near * near || body.lift > .25) return false;
        double rate = body.path.speed * spinResume + spinRate;
        double dvx = (body.slotVx(rate) - body.vx) * rx, dvy = (body.slotVy(rate) - body.vy) * ry;
        if (Math.hypot(dvx, dvy) > dpf2(40)) return false;
        if (body.y >= 0 || avatarRadius <= 0) return true;
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
     * Soft contacts: the films overlap a little, are pushed apart over a few frames and flatten
     * against each other. Bubbles at different depths pass each other; lifted ones are over the
     * front ones. The bubbles on the orbit in front of the avatar press against it.
     */
    private void collide(double rx, double ry, double settle) {
        final double base = dpf2(BUBBLE_DIAMETER / 2);
        for (int i = 0; i < gifts.size(); i++) {
            Orbit a = gifts.get(i).orbit;
            double ra = base * a.radiusScale();
            if (a.state == Orbit.SETTLED && a.y >= 0 && avatarRadius > 0) {
                // it sinks a little into the avatar's rim, flattened against it, and no further
                double dx = orbitCenterX + a.x * rx - avatarCenterX, dy = orbitCenterY + a.y * ry - avatarCenterY;
                double d = Math.hypot(dx, dy);
                double solid = avatarRadius * .9 + ra * .7, touch = avatarRadius + ra * .95;
                if (d < touch && d > .001) {
                    double nx = dx / d, ny = dy / d;
                    a.press(Math.min(.16, (touch - d) / ra * .45), nx, ny);
                    if (d < solid) {
                        double depth = solid - d;
                        a.x += depth * settle * nx / rx;
                        a.y += depth * settle * ny / ry;
                        double vn = a.vx * rx * nx + a.vy * ry * ny;
                        if (vn < 0) {
                            a.vx -= 1.15 * vn * nx / rx;
                            a.vy -= 1.15 * vn * ny / ry;
                        }
                    }
                }
            }
            for (int j = i + 1; j < gifts.size(); j++) {
                Orbit b = gifts.get(j).orbit;
                if (a.isInFront() != b.isInFront()) continue;
                double rb = base * b.radiusScale();
                double dx = (b.x - a.x) * rx, dy = (b.y - a.y) * ry;
                // the films flatten against each other as they touch, and overlap a little before they push
                double d = Math.hypot(dx, dy), touch = (ra + rb) * .97, contact = (ra + rb) * .8;
                if (d >= touch) continue;
                double nx = d > .001 ? dx / d : 1, ny = d > .001 ? dy / d : 0;
                double press = Math.min(.14, (touch - d) / (ra + rb) * 1.1);
                a.press(press, nx, ny);
                b.press(press, nx, ny);
                if (d >= contact) continue;
                double ia = inverseMass(a), ib = inverseMass(b), sum = ia + ib;
                if (sum <= 0) continue;
                double depth = contact - d;
                double move = depth * settle / sum;
                a.x -= move * ia * nx / rx;
                a.y -= move * ia * ny / ry;
                b.x += move * ib * nx / rx;
                b.y += move * ib * ny / ry;
                double vn = (b.vx - a.vx) * rx * nx + (b.vy - a.vy) * ry * ny;
                if (vn < 0) {
                    // a little bounce, most of it taken by the lighter bubble
                    double impulse = -1.2 * vn / sum;
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

        final float orbitX = Math.min(Math.min(acx, getWidth() - acx) - dp(23), aw * 2.1f);
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
            gift.artworkScale = scale * (float) (gift.orbit.perspectiveScale() * (1 + .55 * gift.orbit.lift));
            // Room for the stretched rim and the glow surrounding the gift.
            float extent = Math.max(1.48f * rim, dpf2(26) * gift.artworkScale) + dpf2(1);
            gift.visualBounds.set(gx - extent, gy - extent, gx + extent, gy + extent);
        }
        // Held and springing back bubbles go over the others, the most lifted on top
        renderOrder.clear();
        renderOrder.addAll(drawOrder);
        renderOrder.sort((a, b) -> Double.compare(a.orbit.isLifted() ? 1 + a.orbit.lift : 0, b.orbit.isLifted() ? 1 + b.orbit.lift : 0));
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
     * The bubble's own glow: its rim shines like neon, and only just around it, as on iOS. A wide
     * glow over the header made the bubbles look blurred.
     */
    private void drawHalos(Canvas canvas) {
        final float strength = lightStrength();
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.haloShader == null) continue;
            final float cx = gift.bounds.centerX(), cy = gift.bounds.centerY();
            // the glow flares wider on the beat
            final float radius = gift.bounds.width() / 2f * HALO_EXTENT * (1f + .25f * musicPulse);
            lightMatrix.setScale(radius, radius);
            lightMatrix.postTranslate(cx, cy);
            gift.haloShader.setLocalMatrix(lightMatrix);
            haloPaint.setShader(gift.haloShader);
            haloPaint.setAlpha((int) (0xFF * Utilities.clamp01(.8f * strength * gift.alpha)));
            canvas.drawCircle(cx, cy, radius, haloPaint);
        }
        haloPaint.setShader(null);
    }

    /**
     * The light on the shapes near the bubbles, as on iOS: a bright rim in the bubble's color along
     * a wide arc facing it, and on the round ones (the avatar, the buttons) a soft band of it inside
     * the edge. It falls off with the distance from the bubble's surface and reaches about a bubble
     * and a half, so the avatar's rim takes the colors of the bubbles around it.
     */
    private void drawLight(Canvas canvas) {
        if (litCount == 0) return;
        final float strength = lightStrength();
        final float rimWidth = dpf2(1.6f);
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.glintShader == null) continue;
            final float bx = gift.bounds.centerX(), by = gift.bounds.centerY();
            final float br = gift.bounds.width() / 2f;
            final float reach = br * 2.8f * (1f + .3f * musicPulse);
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
                final float intensity = near * (.55f + .45f * near) * gift.alpha * litShapes[i + 5] * strength;
                if (intensity <= .01f) continue;
                if (!shaderSet) {
                    shaderSet = true;
                    // the light's own falloff from the bubble makes the arc facing it the brightest
                    final float lightRadius = br + reach * 1.35f;
                    lightMatrix.setScale(lightRadius, lightRadius);
                    lightMatrix.postTranslate(bx, by);
                    gift.glintShader.setLocalMatrix(lightMatrix);
                    lightPaint.setShader(gift.glintShader);
                }
                final boolean round = radius >= Math.min(r - l, b - t) / 2f - .5f && Math.abs((r - l) - (b - t)) < 1f;
                litRect.set(l, t, r, b);
                lightPaint.setStyle(Paint.Style.FILL);
                lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.06f * intensity)));
                canvas.drawRoundRect(litRect, radius, radius, lightPaint);
                lightPaint.setStyle(Paint.Style.STROKE);
                if (round) {
                    // a soft band inside the edge: strokes going in, fainter
                    final float cx = litRect.centerX(), cy = litRect.centerY(), rr = radius;
                    lightPaint.setStrokeWidth(rr * .16f);
                    lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.16f * intensity)));
                    canvas.drawCircle(cx, cy, rr * .86f, lightPaint);
                    lightPaint.setStrokeWidth(rr * .08f);
                    lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.3f * intensity)));
                    canvas.drawCircle(cx, cy, rr * .93f, lightPaint);
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
            // let go (or the gesture taken away): it springs back to its slot
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
     * A bubble is picked up and pulled away from its slot, as far as the finger goes; let go, it
     * springs back to it with a part of the finger's speed, past it a little, and settles.
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
        double speed = Math.hypot(vx, vy) * RELEASE_SPEED;
        final double scale = speed > 0 ? Math.min(speed, dpf2(MAX_RELEASE_SPEED)) / Math.hypot(vx, vy) : 0;
        body.state = Orbit.RETURNING;
        body.vx = vx * scale / rx;
        body.vy = vy * scale / ry;
    }

    /* The orbit turned by a sideways drag around the avatar, off the bubbles. The profile feeds
       its touches here before its views see them: a tap on the avatar, a vertical drag of the
       list and a swipe back from the screen's edge stay theirs. */

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

    private double orbitAngleAt(float x, float y) {
        return Math.atan2((y - orbitCenterY) / orbitRadiusY, (x - orbitCenterX) / orbitRadiusX);
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
                invalidate();
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean candidate = orbitCandidate;
            orbitCandidate = false;
            if (orbitHeld) {
                orbitHeld = false;
                // it spins on as fast as the finger went, and slows down to the paths' own pace;
                // a finger that stopped before it was lifted leaves the orbit where it is
                if (action == MotionEvent.ACTION_CANCEL || event.getEventTime() - orbitLastTime > 80) spinRate = 0;
                spinRate = Math.max(-MAX_SPIN, Math.min(MAX_SPIN, spinRate));
                spinResume = 0;
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
        for (Gift gift : gifts) {
            gift.orbit.phase += delta;
        }
        final double seconds = Math.max(.001, (time - orbitLastTime) / 1000.0);
        orbitLastTime = time;
        // the finger's pace, smoothed over a few events, is the spin it leaves the orbit with
        spinRate += (delta / seconds - spinRate) * Math.min(1, seconds / .06);
        invalidate();
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
