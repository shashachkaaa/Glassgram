package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.os.SystemClock;
import org.telegram.ui.Components.ProfileHeaderGlass;
import org.telegram.ui.Components.LiquidPressEffect;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

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
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;
    private final Theme.ResourcesProvider resourcesProvider;

    public final ProfileHeaderGlass bubbleGlass = new ProfileHeaderGlass(this);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bubbleOutline = new Path();
    private final RectF lensBounds = new RectF();
    private boolean drawGlass;
    private final FrameLayout dragHost;
    private DragLens dragLens;
    private boolean active = true;
    public void setActive(boolean f) {
        this.active = f;
        if (!f) resetDrag();
    }

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider, FrameLayout dragHost) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;

        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;
        this.dragHost = dragHost;

        this.resourcesProvider = resourcesProvider;
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
        bubbleGlass.watch(true);

        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.addView(this);
        }

        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        bubbleGlass.watch(false);
        // The host may be traversing its children to detach them.
        if (dragLens != null) {
            final View detachedLens = dragLens;
            dragLens = null;
            detachedLens.setVisibility(GONE);
            AndroidUtilities.runOnUIThread(() -> dragHost.removeView(detachedLens), 1);
        }
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

        private final ProfileHeaderGlass.Piece piece = new ProfileHeaderGlass.Piece();
        private final LiquidPressEffect press = new LiquidPressEffect(ProfileGiftsView.this);
        public final RectF bounds = new RectF();
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        private Orbit orbit;

        public void copy(Gift b) {
            orbit = b.orbit;
            gradient = b.gradient;
            emojiDrawable = b.emojiDrawable;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float gradientAlpha
        ) {
            if (alpha <= 0.0f) return;
            final float gsz = dp(42) * ascale;

            bounds.set(cx - gsz / 2, cy - gsz / 2, cx + gsz / 2, cy + gsz / 2);
            if (this == movingGift && dragging) return;
            canvas.save();
            double vx = orbit.vx * orbitRadiusX, vy = orbit.vy * orbitRadiusY;
            float stretch = (float) Math.min(.18, Math.hypot(vx, vy) / dpf2(1800));
            drawBubble(canvas, bounds, alpha, (float) Math.toDegrees(Math.atan2(vy, vx)), stretch,
                    drawGlass, bubbleGlass, piece, press);
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            drawArtwork(canvas, alpha, gradientAlpha);
            canvas.restore();
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
            float normal = (float) Math.toDegrees(orbit.impactAngle);
            float compression = 1f - (float) orbit.impact;
            canvas.rotate(normal, cx, cy);
            canvas.scale(compression, 1f / compression, cx, cy);
            canvas.rotate(angle - normal, cx, cy);
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
            if (glassEnabled) {
                lensBounds.set(box);
                lensBounds.inset(-r * .12f, -r * .12f);
                glass.draw(canvas, layer, effect, lensBounds, lensBounds.width() / 2, 1f, alpha, 0f);
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

        private void drawArtwork(Canvas canvas, float alpha, float gradientAlpha) {
            final float gsz = dp(45);
            particles.process();
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

    // Positions are relative to the avatar, measured in horizontal and vertical orbit radii.
    private static final class Orbit {
        private static final int[] SLOTS = {3, 4, 2, 0, 5, 1};
        double x, y, vx, vy, direction = 1;
        double impact, impactAngle;
        long epoch;

        Orbit(int position) {
            double phase = SLOTS[Math.floorMod(position, SLOTS.length)] * Math.PI / 3;
            x = Math.cos(phase);
            y = Math.sin(phase);
            vx = -.17 * y;
            vy = .17 * x;
        }

        void launch(long now, double startX, double startY, double pullX, double pullY) {
            x = startX;
            y = startY;
            double gain = Math.min(12, 27 / Math.max(.001, Math.hypot(pullX, pullY)));
            vx = -gain * pullX;
            vy = -gain * pullY;
            epoch = now;
        }

        void advance(double dt) {
            double r = Math.max(.01, Math.hypot(x, y));
            double nx = x / r, ny = y / r;
            double radial = vx * nx + vy * ny;
            double tangent = vy * nx - vx * ny;
            if (Math.abs(tangent) > .12) direction = Math.signum(tangent);
            // A wide excursion is pulled back gradually. Keep tangential momentum through the turn.
            double ar = -5.5 * (r - 1) - 1.55 * radial - Math.min(2, tangent * tangent / r);
            double at = .78 * (.17 * direction - tangent);
            vx += (ar * nx - at * ny) * dt;
            vy += (ar * ny + at * nx) * dt;
            x += vx * dt;
            y += vy * dt;
            impact *= Math.exp(-10 * dt);
        }

        static void collide(Orbit a, Orbit b, double dx, double dy, double contact,
                            double rx, double ry, boolean heldA, boolean heldB, double dt) {
            double distance = Math.hypot(dx, dy);
            if (distance >= contact || heldA && heldB) return;
            double nx = distance > .001 ? dx / distance : 1;
            double ny = distance > .001 ? dy / distance : 0;
            double share = heldA || heldB ? 1 : .5;
            // Let the films overlap slightly and recover over time, even if a dragged
            // bubble grows into its neighbour while the finger is stationary.
            double correction = (contact - distance) * share * -Math.expm1(-35 * dt);
            if (!heldA) { a.x -= correction * nx / rx; a.y -= correction * ny / ry; }
            if (!heldB) { b.x += correction * nx / rx; b.y += correction * ny / ry; }
            double speedA = a.vx * rx * nx + a.vy * ry * ny;
            double speedB = b.vx * rx * nx + b.vy * ry * ny;
            double closing = speedA - speedB;
            if (closing <= 0) return;

            // Limit the speed carried from one film to the next in diameters per
            // second. Preserve sliding along the contact and motion away from it.
            double limit = contact * 2;
            double drift = heldA || heldB ? 0 : Math.max(0, speedB) + Math.min(0, speedA);
            double incomingA = Math.min(speedA, drift + limit);
            double incomingB = Math.max(speedB, drift - limit);
            double rebound = .14 * Math.max(0, incomingA - incomingB);
            double sharedSpeed = (incomingA + incomingB) * .5;
            if (!heldA) {
                double next = heldB ? incomingB - rebound : sharedSpeed - rebound * .5;
                double change = Math.min(0, next - speedA);
                a.vx += change * nx / rx;
                a.vy += change * ny / ry;
            }
            if (!heldB) {
                double next = heldA ? incomingA + rebound : sharedSpeed + rebound * .5;
                double change = Math.max(0, next - speedB);
                b.vx += change * nx / rx;
                b.vy += change * ny / ry;
            }
            double squash = Math.min(.085, closing / (contact * 40));
            if (squash >= a.impact) {
                a.impact = squash;
                a.impactAngle = Math.atan2(ny, nx);
            }
            if (squash >= b.impact) {
                b.impact = squash;
                b.impactAngle = Math.atan2(ny, nx);
            }
        }
    }

    private float orbitCenterX, orbitCenterY, orbitRadiusX, orbitRadiusY;
    private long lastPhysicsFrame;
    private float lastDragX, lastDragY;

    private void advanceGifts(long now) {
        for (int i = 0; i < gifts.size(); i++) {
            Gift gift = gifts.get(i);
            if (gift.orbit == null) gift.orbit = new Orbit(gift.position);
        }
        double elapsed = Math.min(.064, (now - lastPhysicsFrame) * .001);
        lastPhysicsFrame = now;
        if (elapsed <= 0) return;
        int steps = (int) Math.ceil(elapsed * 240);
        double dt = elapsed / steps;
        double rx = orbitRadiusX, ry = orbitRadiusY;
        Gift held = released ? null : movingGift;
        if (held != null) {
            held.orbit.impact *= Math.exp(-10 * elapsed);
            held.orbit.vx = Math.max(-27, Math.min(27, (dragX - lastDragX) / (elapsed * rx)));
            held.orbit.vy = Math.max(-27, Math.min(27, (dragY - lastDragY) / (elapsed * ry)));
        }
        double radius = dpf2(21);
        for (int step = 1; step <= steps; step++) {
            double handX = lastDragX + (dragX - lastDragX) * step / steps;
            double handY = lastDragY + (dragY - lastDragY) * step / steps;
            for (int i = 0; i < gifts.size(); i++) {
                Gift gift = gifts.get(i);
                Orbit body = gift.orbit;
                if (gift == held) continue;
                body.advance(dt);
                // Keep a fast shot in reach at the edges of the visible profile.
                double cx = orbitCenterX + body.x * rx, cy = orbitCenterY + body.y * ry;
                if (cx < radius && body.vx < 0 || cx > getWidth() - radius && body.vx > 0) body.vx *= -.78;
                if (cy < radius && body.vy < 0 || cy > dragHost.getHeight() - radius && body.vy > 0) body.vy *= -.78;
            }
            for (int i = 0; i < gifts.size(); i++) {
                Gift a = gifts.get(i);
                for (int j = i + 1; j < gifts.size(); j++) {
                    Gift b = gifts.get(j);
                    double dx = (b.orbit.x - a.orbit.x) * rx;
                    double dy = (b.orbit.y - a.orbit.y) * ry;
                    int hand = b == held ? 1 : a == held ? -1 : 0;
                    double contact = radius * 2;
                    if (dragLens != null && (a == movingGift || b == movingGift)) contact += radius * 1.15 * dragLens.lift;
                    Orbit.collide(a.orbit, b.orbit, dx + hand * handX, dy + hand * handY,
                            contact, rx, ry, a == held, b == held, dt);
                }
            }
        }
        lastDragX = dragX;
        lastDragY = dragY;
    }

    private final TimeInterpolator giftCollapseXInterpolator = new DecelerateInterpolator();
    private final TimeInterpolator giftCollapseYInterpolator = new LinearInterpolator();

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (gifts.isEmpty() || expandProgress >= 1.0f || collapseProgress <= 0f) return;

        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = (avatarContainer.getWidth()) * avatarContainer.getScaleX();
        final float realH = (avatarContainer.getHeight()) * avatarContainer.getScaleY();

        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);


        canvas.save();
        if (collapseProgress < 1f || expandProgress > 0f) canvas.clipRect(0, 0, getWidth(), expandY);
        drawGlass = bubbleGlass.begin(canvas);

        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        final float orbitX = Math.min(acx - dp(22), aw * 0.92f);
        final float orbitY = ah * 0.57f;
        orbitCenterX = acx;
        orbitCenterY = acy;
        orbitRadiusX = Math.max(1f, orbitX);
        orbitRadiusY = Math.max(1f, orbitY);
        advanceGifts(getDrawingTime());
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            final float alpha = enter * (1.0f - expandProgress) * (1.0f - actionBarProgress) * (closedAlpha);

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

            gift.draw(canvas, gx, gy, scale, 0, alpha, 1f);
        }

        canvas.restore();
        if (active && closedAlpha > 0f && actionBarProgress < 1f && isShown()) postInvalidateOnAnimation();
    }

    public Gift getGiftUnder(float x, float y) {
        for (int i = 0; i < gifts.size(); ++i) {
            if (gifts.get(i).bounds.contains(x, y))
                return gifts.get(i);
        }
        return null;
    }

    // A small sibling above the profile can sample the avatar, text and rows under the moving lens.
    private final class DragLens extends View {
        private final ProfileHeaderGlass glass = new ProfileHeaderGlass(this);
        private final ProfileHeaderGlass.Piece piece = new ProfileHeaderGlass.Piece();
        private final LiquidPressEffect lensPress = new LiquidPressEffect(this);
        private final RectF surface = new RectF();
        private final int[] sourceLocation = new int[2], hostLocation = new int[2];
        private float lift, stretch, angle;
        private long lastFrame;
        private float previousX, previousY;

        DragLens() {
            super(ProfileGiftsView.this.getContext());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void place(float x, float y, float sizeProgress) {
            final float cx = movingGift.bounds.centerX() + x;
            final float cy = movingGift.bounds.centerY() + y;
            ProfileGiftsView.this.getLocationInWindow(sourceLocation);
            dragHost.getLocationInWindow(hostLocation);
            final float half = dp(208) / 2f;
            setX(sourceLocation[0] - hostLocation[0] + cx - half);
            setY(sourceLocation[1] - hostLocation[1] + cy - half);
            long now = SystemClock.uptimeMillis();
            float elapsed = Math.max(1, now - lastFrame);
            float dx = cx - previousX, dy = cy - previousY;
            float speed = (float) Math.hypot(dx, dy) / elapsed;
            if (speed > dpf2(.06f)) angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            float blend = 1f - (float) Math.exp(-elapsed / 52f);
            stretch += (Math.min(.42f, speed / dpf2(3)) - stretch) * blend;
            lift += (sizeProgress - lift) * blend;
            lastFrame = now;
            previousX = cx;
            previousY = cy;
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (movingGift == null || !dragging) return;
            float r = dpf2(21) * (1f + 1.15f * lift);
            float c = getWidth() * .5f;
            surface.set(c - r, c - r, c + r, c + r);
            boolean hasGlass = glass.begin(canvas);
            movingGift.drawBubble(canvas, surface, 1f, angle, stretch, hasGlass, glass, piece, lensPress);
            canvas.save();
            canvas.translate(c, c);
            float artworkScale = 1f + .63f * lift;
            canvas.scale(artworkScale, artworkScale);
            movingGift.drawArtwork(canvas, 1f, .5f);
            canvas.restore();
        }
    }

    private final Runnable updateLens = new Runnable() {
        @Override public void run() {
            if (!dragging || movingGift == null) return;
            if (released) {
                float age = (SystemClock.uptimeMillis() - movingGift.orbit.epoch) * .001f;
                if (age >= .9f || expandProgress >= 1f || collapseProgress <= 0f) {
                    resetDrag();
                    return;
                }
                dragLens.place(0, 0, Math.max(0, 1f - age / .72f));
            } else {
                dragLens.place(dragX, dragY, 1f);
            }
            postOnAnimation(this);
        }
    };

    private Gift movingGift;
    private float downX, downY, dragX, dragY;
    private boolean dragging;
    private boolean released;

    private void resetDrag() {
        if (dragging && !released && movingGift != null && movingGift.orbit != null) {
            movingGift.orbit.vx = movingGift.orbit.vy = 0;
        }
        released = false;
        removeCallbacks(updateLens);
        if (dragLens != null) {
            dragHost.removeView(dragLens);
            dragLens = null;
        }
        if (movingGift != null) {
            movingGift.bounce.setPressed(false);
            movingGift.press.onTouch(MotionEvent.ACTION_CANCEL, 0, 0);
        }
        movingGift = null;
        dragging = false;
        dragX = dragY = 0;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!active || expandProgress >= 1f || collapseProgress <= 0f || (collapseProgress < 1f && y > expandY)) return false;
            Gift hit = getGiftUnder(x, y);
            if (hit == null) return false;
            resetDrag();
            movingGift = hit;
            lastDragX = lastDragY = 0;
            downX = x;
            downY = y;
            movingGift.bounce.setPressed(true);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (movingGift == null || released) return false;
        movingGift.press.onTouch(action, x - movingGift.bounds.left, y - movingGift.bounds.top);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (!dragging && dx * dx + dy * dy > slop * slop) {
                dragging = true;
                dragLens = new DragLens();
                dragHost.addView(dragLens, new FrameLayout.LayoutParams(dp(208), dp(208),
                        android.view.Gravity.TOP | android.view.Gravity.LEFT));
                dragLens.previousX = movingGift.bounds.centerX();
                dragLens.previousY = movingGift.bounds.centerY();
                dragLens.lastFrame = SystemClock.uptimeMillis();
                postOnAnimation(updateLens);
            }
            if (dragging) {
                dragX = dx;
                dragY = dy;
                dragLens.place(dx, dy, 1f);
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Gift gift = movingGift;
            getParent().requestDisallowInterceptTouchEvent(false);
            gift.bounce.setPressed(false);
            if (!dragging) {
                resetDrag();
                if (action == MotionEvent.ACTION_UP) onGiftClick(gift);
            } else if (action == MotionEvent.ACTION_CANCEL) {
                resetDrag();
            } else {
                dragX = x - downX;
                dragY = y - downY;
                double startX = (gift.bounds.centerX() + dragX - orbitCenterX) / orbitRadiusX;
                double startY = (gift.bounds.centerY() + dragY - orbitCenterY) / orbitRadiusY;
                gift.orbit.launch(SystemClock.uptimeMillis(), startX, startY,
                        dragX / orbitRadiusX, dragY / orbitRadiusY);
                gift.bounds.offset(dragX, dragY);
                released = true;
                invalidate();
            }
        }
        return true;
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
