package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.os.SystemClock;
import org.telegram.ui.Components.ProfileHeaderGlass;
import org.telegram.ui.Components.LiquidPressEffect;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
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

        public void copy(Gift b) {
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
            if (drawGlass) {
                bubbleGlass.draw(canvas, piece, press, bounds, gsz / 2, 1f, alpha, 0f);
            } else {
                bubblePaint.setColor(Theme.multAlpha(0x30FFFFFF, alpha));
                canvas.drawOval(bounds, bubblePaint);
            }
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            drawArtwork(canvas, alpha, gradientAlpha);
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
        canvas.clipRect(0, 0, getWidth(), expandY);
        drawGlass = bubbleGlass.begin(canvas);

        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        final float orbitX = Math.min(acx - dp(22), aw * 0.92f);
        final float orbitY = ah * 0.57f;
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            final float alpha = enter * (1.0f - expandProgress) * (1.0f - actionBarProgress) * (closedAlpha);

            float gx, gy;
            float delayValue;

            switch (gift.position) {
                case 0: // left
                    gx = acx - orbitX;
                    gy = acy;
                    delayValue = 1.6f;
                    break;
                case 1: // top left
                    gx = acx - orbitX * .64f;
                    gy = acy - orbitY;
                    delayValue = 0;
                    break;
                case 2: // bottom left
                    gx = acx - orbitX * .64f;
                    gy = acy + orbitY;
                    delayValue = 0.9f;
                    break;
                case 3: // right
                    gx = acx + orbitX;
                    gy = acy;
                    delayValue = 1.6f;
                    break;
                case 4: // top right
                    gx = acx + orbitX * .64f;
                    gy = acy - orbitY;
                    delayValue = 0.9f;
                    break;
                default: // bottom right
                    gx = acx + orbitX * .64f;
                    gy = acy + orbitY;
                    delayValue = 0;
                    break;
            }

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
            final float half = dp(160) / 2f;
            setX(sourceLocation[0] - hostLocation[0] + cx - half);
            setY(sourceLocation[1] - hostLocation[1] + cy - half);
            long now = SystemClock.uptimeMillis();
            float elapsed = Math.max(1, now - lastFrame);
            float dx = x - previousX, dy = y - previousY;
            float speed = (float) Math.hypot(dx, dy) / elapsed;
            if (speed > dpf2(.06f)) angle = (float) Math.toDegrees(Math.atan2(dy, dx));
            float blend = 1f - (float) Math.exp(-elapsed / 52f);
            stretch += (Math.min(.42f, speed / dpf2(3)) - stretch) * blend;
            lift += (sizeProgress - lift) * blend;
            lastFrame = now;
            previousX = x;
            previousY = y;
            invalidate();
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            if (movingGift == null || !dragging) return;
            float r = dpf2(21) * (1f + 1.15f * lift);
            float c = getWidth() * .5f;
            surface.set(c - r, c - r, c + r, c + r);
            boolean hasGlass = glass.begin(canvas);
            canvas.save();
            canvas.rotate(angle, c, c);
            canvas.scale(1f + stretch, 1f / (1f + stretch), c, c);
            canvas.rotate(-angle, c, c);
            if (hasGlass) {
                glass.draw(canvas, piece, lensPress, surface, r, 1f, 1f, 0f);
            } else {
                bubblePaint.setColor(0x38FFFFFF);
                canvas.drawOval(surface, bubblePaint);
            }
            canvas.restore();
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
            if (!dragging || movingGift == null || returnAnimation != null) return;
            dragLens.place(dragX, dragY, 1f);
            postOnAnimation(this);
        }
    };

    private Gift movingGift;
    private float downX, downY, dragX, dragY;
    private boolean dragging;
    private ValueAnimator returnAnimation;

    private void resetDrag() {
        if (returnAnimation != null) {
            returnAnimation.removeAllListeners();
            returnAnimation.removeAllUpdateListeners();
            returnAnimation.cancel();
        }
        returnAnimation = null;
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
            if (!active || expandProgress >= 1f || collapseProgress <= 0f || y > expandY) return false;
            Gift hit = getGiftUnder(x, y);
            if (hit == null) return false;
            resetDrag();
            movingGift = hit;
            downX = x;
            downY = y;
            movingGift.bounce.setPressed(true);
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (movingGift == null || returnAnimation != null) return false;
        movingGift.press.onTouch(action, x - movingGift.bounds.left, y - movingGift.bounds.top);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = x - downX, dy = y - downY;
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (!dragging && dx * dx + dy * dy > slop * slop) {
                dragging = true;
                dragLens = new DragLens();
                dragHost.addView(dragLens, new FrameLayout.LayoutParams(dp(160), dp(160),
                        android.view.Gravity.TOP | android.view.Gravity.LEFT));
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
            } else {
                final float dx = dragX, dy = dragY;
                returnAnimation = ValueAnimator.ofFloat(1f, 0f);
                returnAnimation.setDuration(420);
                returnAnimation.setInterpolator(new android.view.animation.OvershootInterpolator(.75f));
                returnAnimation.addUpdateListener(a -> {
                    float fraction = (float) a.getAnimatedValue();
                    dragX = dx * fraction;
                    dragY = dy * fraction;
                    dragLens.place(dragX, dragY, Math.max(0f, fraction));
                    invalidate();
                });
                returnAnimation.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator animation) {
                        returnAnimation = null;
                        resetDrag();
                    }
                });
                returnAnimation.start();
            }
        }
        return true;
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
