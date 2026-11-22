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
        if (!f) resetDrag();
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
        private float baseX, baseY, alpha, artworkScale;
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
    private static final class Orbit {
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
        double x, y, vx, vy, direction = 1;
        double lift;

        Orbit(int position) {
            int slot = Math.floorMod(position, SLOTS.length);
            path = PATHS[slot];
            double phase = SLOTS[slot] * Math.PI / 3;
            double cos = Math.cos(phase), sin = Math.sin(phase);
            x = path.width * cos;
            y = path.height * sin + path.slope * x;
            vx = -path.speed * path.width * sin;
            vy = path.speed * path.height * cos + path.slope * vx;
        }

        double perspectiveScale() {
            double depth = Math.max(-1, Math.min(1, y));
            return path.size * (1 + .12 * depth);
        }

        double radiusScale() {
            return perspectiveScale() * (1 + .85 * lift);
        }

        boolean isInFront() {
            return y >= 0;
        }

        void launch(double startX, double startY, double pullX, double pullY,
                    double rx, double ry, double diameter) {
            x = startX;
            y = startY;
            // Measure pull in visible bubble diameters, equally in every direction.
            double pull = Math.hypot(pullX, pullY);
            double diameters = pull / diameter;
            double gain = 2 + 2 * Math.min(1, diameters / 3);
            double speed = 7 * diameter * Math.tanh(diameters * gain / 7);
            double impulse = pull > 0 ? speed / pull : 0;
            vx = -impulse * pullX / rx;
            vy = -impulse * pullY / ry;
        }

        void stopDrag() {
            vx = vy = 0;
        }

        void advance(double dt) {
            double localX = x / path.width, localY = (y - path.slope * x) / path.height;
            double localVx = vx / path.width, localVy = (vy - path.slope * vx) / path.height;
            double r = Math.max(.01, Math.hypot(localX, localY));
            double nx = localX / r, ny = localY / r;
            double radial = localVx * nx + localVy * ny;
            double tangent = localVy * nx - localVx * ny;
            if (Math.abs(tangent) > .12) direction = Math.signum(tangent);
            double ar = -9 * (r - 1) - 3.6 * radial - tangent * tangent / Math.max(.65, r);
            double at = .85 * (path.speed * direction - tangent) + direction * 1.6 * Math.abs(radial);
            double ax = (ar * nx - at * ny) * path.width;
            vx += ax * dt;
            vy += ((ar * ny + at * nx) * path.height + path.slope * ax) * dt;
            x += vx * dt;
            y += vy * dt;
            lift *= Math.exp(-5.2 * dt);
        }
    }

    private float orbitCenterX, orbitCenterY, orbitRadiusX, orbitRadiusY;
    private long lastPhysicsFrame;
    private float lastDragX, lastDragY;

    private void advanceGifts(long now) {
        double elapsed = lastPhysicsFrame == 0 ? 0 : Math.min(.064, (now - lastPhysicsFrame) * .001);
        lastPhysicsFrame = now;
        if (elapsed <= 0) return;
        int steps = (int) Math.ceil(elapsed * 240);
        double dt = elapsed / steps;
        double rx = orbitRadiusX, ry = orbitRadiusY;
        Gift held = movingGift;
        if (held != null) {
            held.orbit.vx = Math.max(-27, Math.min(27, (dragX - lastDragX) / (elapsed * rx)));
            held.orbit.vy = Math.max(-27, Math.min(27, (dragY - lastDragY) / (elapsed * ry)));
        }
        double radius = dpf2(BUBBLE_DIAMETER / 2);
        double top = Math.max(0, orbitCenterY - 1.55 * ry);
        double bottom = Math.min(getHeight(), orbitCenterY + 1.65 * ry);
        for (int step = 0; step < steps; step++) {
            for (int i = 0; i < gifts.size(); i++) {
                Gift gift = gifts.get(i);
                Orbit body = gift.orbit;
                if (gift == held) {
                    if (dragging) body.lift += (1 - body.lift) * -Math.expm1(-18 * dt);
                    continue;
                }
                body.advance(dt);
                // Ease out only the outward component as the rim approaches the
                // viewport. An oblique shot can keep travelling along the edge.
                double cx = orbitCenterX + body.x * rx, cy = orbitCenterY + body.y * ry;
                double rim = radius * body.radiusScale();
                body.vx = limitAtEdge(body.vx, cx, getWidth(), rim, rx);
                body.vy = limitAtEdge(body.vy, cy - top, bottom - top, rim, ry);
            }
        }
        lastDragX = dragX;
        lastDragY = dragY;
    }

    private static double limitAtEdge(double velocity, double center, double extent, double radius, double orbitRadius) {
        double room = velocity > 0 ? extent - radius - center : center - radius;
        double maxSpeed = 6 * Math.max(0, room) / orbitRadius;
        return Math.copySign(Math.min(Math.abs(velocity), maxSpeed), velocity);
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

            gift.baseX = gx;
            gift.baseY = gy;
            boolean held = dragging && gift == movingGift;
            gift.inFront = held || gift.orbit.isInFront();
            if (held) {
                gx += dragX;
                gy += dragY;
            }
            scale *= 1f + .12f * musicPulse;
            float rim = dpf2(BUBBLE_DIAMETER / 2) * scale * (float) gift.orbit.radiusScale();
            gift.bounds.set(gx - rim, gy - rim, gx + rim, gy + rim);
            gift.artworkScale = scale * (float) (gift.orbit.perspectiveScale() * (1 + .55 * gift.orbit.lift));
            // Room for the stretched rim and the glow surrounding the gift.
            float extent = Math.max(1.48f * rim, dpf2(26) * gift.artworkScale) + dpf2(1);
            gift.visualBounds.set(gx - extent, gy - extent, gx + extent, gy + extent);
        }
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

    /** The glow around every bubble on the header, under the avatar and the buttons. */
    private void drawHalos(Canvas canvas) {
        final float strength = lightStrength();
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.lightShader == null) continue;
            final float cx = gift.bounds.centerX(), cy = gift.bounds.centerY();
            // the halo flares wider on the beat, faster than the bubble swells
            final float radius = gift.bounds.width() * 1.15f * (1f + .35f * musicPulse);
            lightMatrix.setScale(radius, radius);
            lightMatrix.postTranslate(cx, cy);
            gift.lightShader.setLocalMatrix(lightMatrix);
            haloPaint.setShader(gift.lightShader);
            haloPaint.setAlpha((int) (0xFF * Utilities.clamp01(.36f * strength * gift.alpha)));
            canvas.drawCircle(cx, cy, radius, haloPaint);
        }
        haloPaint.setShader(null);
    }

    /**
     * The light on the shapes near the bubbles: a glint along the rim facing the bubble and a
     * little of its color spilt over the glass. The light falls off with the distance from the
     * bubble's surface, so a button lights up as a bubble flies past it.
     */
    private void drawLight(Canvas canvas) {
        if (litCount == 0) return;
        final float strength = lightStrength();
        final float rimWidth = dpf2(1.3f);
        lightPaint.setStrokeWidth(rimWidth);
        for (Gift gift : drawOrder) {
            if (gift.alpha <= 0f || gift.glintShader == null) continue;
            final float bx = gift.bounds.centerX(), by = gift.bounds.centerY();
            final float br = gift.bounds.width() / 2f;
            final float reach = br * 2.4f * (1f + .3f * musicPulse);
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
                final float intensity = near * near * gift.alpha * litShapes[i + 5] * strength;
                if (intensity <= .01f) continue;
                if (!shaderSet) {
                    shaderSet = true;
                    // the light's own falloff makes the rim brightest on the side facing the bubble
                    final float lightRadius = br + reach * 1.25f;
                    lightMatrix.setScale(lightRadius, lightRadius);
                    lightMatrix.postTranslate(bx, by);
                    gift.glintShader.setLocalMatrix(lightMatrix);
                    lightPaint.setShader(gift.glintShader);
                }
                litRect.set(l, t, r, b);
                lightPaint.setStyle(Paint.Style.FILL);
                lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.1f * intensity)));
                canvas.drawRoundRect(litRect, radius, radius, lightPaint);
                litRect.inset(rimWidth / 2f, rimWidth / 2f);
                lightPaint.setStyle(Paint.Style.STROKE);
                lightPaint.setAlpha((int) (0xFF * Utilities.clamp01(.9f * intensity)));
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
        for (Gift gift : drawOrder) {
            if (gift.inFront != front) continue;
            gift.lowerGifts.clear();
            gift.sampledByOtherGift = false;
        }
        for (int i = 1; i < drawOrder.size(); i++) {
            Gift upper = drawOrder.get(i);
            if (upper.alpha <= 0f || upper.inFront != front) continue;
            for (int j = 0; j < i; j++) {
                Gift lower = drawOrder.get(j);
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
            for (Gift gift : drawOrder) {
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
        for (int i = drawOrder.size() - 1; i >= 0; i--) {
            Gift gift = drawOrder.get(i);
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
    private float downX, downY, dragX, dragY;
    private boolean dragging;

    private void resetDrag() {
        if (movingGift != null) {
            if (dragging) movingGift.orbit.stopDrag();
            movingGift.bounce.setPressed(false);
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        movingGift = null;
        dragging = false;
        dragX = dragY = lastDragX = lastDragY = 0;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return onGiftTouch(event, false);
    }

    private boolean onGiftTouch(MotionEvent event, boolean front) {
        float x = event.getX(), y = event.getY();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!active || !areGiftsVisible() || ((collapseProgress < 1f || expandProgress > 0f) && y > expandY)) return false;
            Gift hit = getGiftUnder(x, y, front);
            if (hit == null) return false;
            resetDrag();
            movingGift = hit;
            downX = x;
            downY = y;
            movingGift.bounce.setPressed(true);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (movingGift == null) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            dragging |= dx * dx + dy * dy > slop * slop;
            if (dragging) {
                dragX = dx;
                dragY = dy;
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP) {
            final Gift gift = movingGift;
            final boolean wasDragging = dragging;
            if (wasDragging) {
                dragX = x - downX;
                dragY = y - downY;
                double startX = (gift.baseX + dragX - orbitCenterX) / orbitRadiusX;
                double startY = (gift.baseY + dragY - orbitCenterY) / orbitRadiusY;
                gift.orbit.launch(startX, startY, dragX, dragY, orbitRadiusX, orbitRadiusY,
                        dpf2(BUBBLE_DIAMETER) * gift.orbit.path.size);
                dragging = false;
            }
            resetDrag();
            if (!wasDragging) onGiftClick(gift);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            resetDrag();
        }
        return true;
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
