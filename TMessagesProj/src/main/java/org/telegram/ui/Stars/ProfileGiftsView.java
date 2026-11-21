package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
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

import org.telegram.messenger.AndroidUtilities;
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
import org.telegram.ui.Components.LiquidPressEffect;
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

    public void setMusicPulse(float pulse) {
        if (musicPulse != pulse) {
            musicPulse = pulse;
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
        public final int color;
        public final String slug;
        private StarsReactionsSheet.Particles particles;

        public int position = -1;

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            color = backdrop.center_color | 0xFF000000;
            slug = gift.slug;
            initParticles();
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            slug = status.slug;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 6);
            final float gsz = dp(36);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public AnimatedEmojiDrawable emojiDrawable;
        public AnimatedFloat animatedFloat;

        private final ProfileHeaderGlass.Piece rearPiece = new ProfileHeaderGlass.Piece();
        private final ProfileHeaderGlass.Piece frontPiece = new ProfileHeaderGlass.Piece();
        private final LiquidPressEffect press = new LiquidPressEffect(ProfileGiftsView.this);
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
                    hasGlass, glass, inFront ? frontPiece : rearPiece, press);
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
                                boolean glassEnabled, ProfileHeaderGlass glass, ProfileHeaderGlass.Piece layer,
                                LiquidPressEffect effect) {
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
                lensBounds.inset(-r * .12f, -r * .12f);
                glass.draw(canvas, layer, effect, lensBounds, lensBounds.width() / 2, 1f, alpha, 0f,
                        lowerGifts.isEmpty() ? null : this::drawLowerGifts);
            } else {
                bubblePaint.setColor(Theme.multAlpha(0x30FFFFFF, alpha));
                canvas.drawPath(bubbleOutline, bubblePaint);
            }
            canvas.restore();
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(dpf2(.7f));
            rimPaint.setColor(Theme.multAlpha(0x52FFFFFF, alpha));
            canvas.drawPath(bubbleOutline, rimPaint);
            canvas.restore();
        }

        private void drawArtwork(Canvas canvas, float alpha, float gradientAlpha, boolean advanceParticles) {
            final float gsz = dp(45);
            if (advanceParticles) particles.process();
            particles.draw(canvas, color, alpha);
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha));
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
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(0.0f);
                if (isAttachedToWindow()) {
                    g.emojiDrawable.addView(this);
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
        boolean hasVisibleGift = false;
        for (Gift gift : gifts) {
            if (gift.alpha > 0f && gift.inFront == front) {
                hasVisibleGift = true;
                break;
            }
        }
        if (!hasVisibleGift) return;
        final int save = canvas.save();
        clipGiftLayer(canvas);
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
            movingGift.press.onTouch(MotionEvent.ACTION_CANCEL, 0, 0);
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
        movingGift.press.onTouch(action, x - movingGift.bounds.left, y - movingGift.bounds.top);
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
