package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotInlineKeyboard;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.Text;
import org.telegram.ui.LinkManager;

import java.util.Arrays;

class BotButton {
    public final Runnable invalidateRunnable;

    public boolean isSeparator;
    public float x;
    public int y;
    public float width;
    public int height;
    public int positionFlags;
    public Text title;
    @Nullable
    public TL_keyboard.KeyboardInlineButton button;
    @Nullable
    public BotInlineKeyboard.ButtonCustom buttonCustom;
    public BotInlineKeyboard.Button buttonImpl;
    public TLRPC.TL_reactionCount reaction;
    public int angle;
    public float progressAlpha;
    public long lastUpdateTime;
    public boolean isInviteButton;
    public boolean isLocked;

    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] radii = new float[8];

    // glassGram: the iOS loading look, a band of light sweeping across the button while the bot answers
    private static final long SHIMMER_PERIOD = 1100;
    private Paint shimmerFill, shimmerStroke;
    private LinearGradient shimmerGradient;
    private final Matrix shimmerMatrix = new Matrix();
    private long shimmerStart;
    private float shimmerAlpha;
    private long shimmerAlphaTime;
    public Drawable selectorDrawable;
    public Drawable iconDrawable;
    public AnimatedEmojiDrawable animatedEmojiDrawable;

    public boolean pressed;
    public float pressT;
    public ValueAnimator pressAnimator;

    public BotButton(Runnable invalidateRunnable) {
        this.invalidateRunnable = invalidateRunnable;
    }

    public boolean draw(
        Canvas canvas,
        RectF rect,
        boolean drawProgress,
        boolean drawBuyCard,
        Theme.ResourcesProvider resourcesProvider
    ) {
        boolean invalidate = false;
        final float s = getPressScale();

        canvas.save();
        if (s != 1) {
            canvas.scale(s, s, rect.centerX(), rect.centerY());
        }
        Arrays.fill(radii, dp(Math.min(6.75f, SharedConfig.bubbleRadius)));
        if (hasPositionFlag(MessageObject.POSITION_FLAG_LEFT | MessageObject.POSITION_FLAG_BOTTOM)) {
            radii[6] = radii[7] = dp(SharedConfig.bubbleRadius);
        }
        if (hasPositionFlag(MessageObject.POSITION_FLAG_RIGHT | MessageObject.POSITION_FLAG_BOTTOM)) {
            radii[4] = radii[5] = dp(SharedConfig.bubbleRadius);
        }

        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, Theme.getThemePaint(Theme.key_paint_chatActionBackground, resourcesProvider));

        final BotInlineKeyboard.BackgroundColor bgColor =
            buttonImpl != null ? buttonImpl.getColor() :
            BotInlineKeyboard.BackgroundColor.NONE;

        if (bgColor != BotInlineKeyboard.BackgroundColor.NONE) {
            switch (bgColor) {
                case DANGER:
                    paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_botKeyboard_button_danger, resourcesProvider), 0.7f));
                    break;
                case SUCCESS:
                    paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_botKeyboard_button_success, resourcesProvider), 0.7f));
                    break;
                case PRIMARY:
                    paint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_botKeyboard_button_primary, resourcesProvider), 0.7f));
                    break;
            }
            canvas.drawPath(path, paint);
        }
        final boolean hasGradientService = resourcesProvider != null ? resourcesProvider.hasGradientService() : Theme.hasGradientService();
        if (hasGradientService && (bgColor == BotInlineKeyboard.BackgroundColor.NONE || resourcesProvider != null && resourcesProvider.isDark())) {
            canvas.drawPath(path, Theme.chat_actionBackgroundGradientDarkenPaint);
        }

//        boolean drawProgress = (button instanceof TLRPC.TL_keyboardButtonCallback_layer228 || button instanceof TLRPC.TL_keyboardButtonGame || button instanceof TLRPC.TL_keyboardButtonBuy_layer228 || button instanceof TLRPC.TL_keyboardButtonUrlAuth_layer228) && SendMessagesHelper.getInstance(currentAccount).isSendingCallback(currentMessageObject, button)
//                || button instanceof TLRPC.TL_keyboardButtonRequestGeoLocation && SendMessagesHelper.getInstance(currentAccount).isSendingCurrentLocation(currentMessageObject, button)
//                || button instanceof TLRPC.TL_keyboardButtonUrl && delegate != null && delegate.isProgressLoading(this, ChatActivity.PROGRESS_BOT_BUTTON) && delegate.getProgressLoadingBotButtonUrl(this) == button.url;
//
//        if (button.buttonCustom != null && currentMessageObject != null) {
//            if (button.buttonCustom.id == BotInlineKeyboard.ButtonCustom.SUGGESTION_ACCEPT) {
//                drawProgress |= MessagesController.getInstance(currentAccount).isSendingSuggestedMessageApproval(currentMessageObject.getDialogId(), currentMessageObject.getId(), true);
//            } else if (button.buttonCustom.id == BotInlineKeyboard.ButtonCustom.SUGGESTION_DECLINE) {
//                drawProgress |= MessagesController.getInstance(currentAccount).isSendingSuggestedMessageApproval(currentMessageObject.getDialogId(), currentMessageObject.getId(), false);
//            }
//        }

        canvas.save();
        canvas.clipPath(path);
        if (drawShimmer(canvas, rect, drawProgress)) {
            invalidate = true;
        }
        if (selectorDrawable != null) {
            selectorDrawable.setBounds((int) rect.left, (int) rect.top, (int) rect.right, (int) rect.bottom);
            selectorDrawable.setAlpha(0xFF);
            selectorDrawable.draw(canvas);
        }
        canvas.restore();

        canvas.save();
        int iconWithMarginPx = iconDrawable != null || animatedEmojiDrawable != null ? dp(24 + 2) : 0;
        float titleX = rect.left + (rect.width() - (title.getWidth() + (iconDrawable != null ? dp(4): 0)) - iconWithMarginPx) / 2f;
        if (animatedEmojiDrawable != null) {
            final int top = (int) (rect.centerY() - dp(20) / 2f);
            animatedEmojiDrawable.setBounds(
                (int) titleX,
                top,
                (int) (titleX) + dp(20),
                top + dp(20)
            );
            animatedEmojiDrawable.setAlpha(isLocked ? 128 : 255);
            animatedEmojiDrawable.draw(canvas);
            titleX += iconWithMarginPx;
        } else if (iconDrawable != null) {
            final int top = (int) (rect.centerY() - dp(24) / 2f);
            iconDrawable.setBounds(
                    (int) titleX,
                    top,
                    (int) (titleX) + dp(24),
                    top + dp(24)
            );
            iconDrawable.setAlpha(isLocked ? 128 : 255);
            iconDrawable.draw(canvas);
            titleX += iconWithMarginPx;
        }
        title.ellipsize(Math.max(1, rect.width() - dp(15) - iconWithMarginPx));
        title.draw(canvas, titleX, rect.centerY(), isLocked ? 0.5f: 1f);
        canvas.restore();

        final TL_keyboard.TL_inlineButtonTypeUrl buttonTypeUrl = TLKeyboardHelper.getType(button, TL_keyboard.TL_inlineButtonTypeUrl.class);
        if (buttonCustom != null) {
            if (isLocked) {
                final Drawable drawable = Theme.getThemeDrawable(Theme.key_drawable_botLock, resourcesProvider);
                final int x = (int) rect.right - dp(3) - drawable.getIntrinsicWidth();
                BaseCell.setDrawableBounds(drawable, x, rect.top + dp(3));
                drawable.draw(canvas);
            }

        } else if (TLKeyboardHelper.isButtonWebView(button)) {
            final Drawable drawable = Theme.getThemeDrawable(Theme.key_drawable_botWebView, resourcesProvider);
            final int x = (int) rect.right - dp(3) - drawable.getIntrinsicWidth();
            BaseCell.setDrawableBounds(drawable, x, rect.top + dp(3));
            drawable.draw(canvas);
        } else if (buttonTypeUrl != null) {
            final Drawable drawable;
            if (LinkManager.isWebAppLink(buttonTypeUrl.url)) {
                drawable = Theme.getThemeDrawable(Theme.key_drawable_botWebView, resourcesProvider);
            } else if (isInviteButton) {
                drawable = Theme.getThemeDrawable(Theme.key_drawable_botInvite, resourcesProvider);
            } else {
                drawable = Theme.getThemeDrawable(Theme.key_drawable_botLink, resourcesProvider);
            }
            int x = (int) rect.right - dp(3) - drawable.getIntrinsicWidth();
            BaseCell.setDrawableBounds(drawable, x, rect.top + dp(3));
            drawable.draw(canvas);
        } else if (TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeSwitchInline.class) || TLKeyboardHelper.isType(button, TL_keyboard.TL_buttonTypeRequestPeer.class)) {
            final Drawable drawable = Theme.getThemeDrawable(Theme.key_drawable_botInline, resourcesProvider);
            final int x = (int) rect.right - dp(3) - drawable.getIntrinsicWidth();
            BaseCell.setDrawableBounds(drawable, x, rect.top + dp(3));
            drawable.draw(canvas);
        } else if (TLKeyboardHelper.isType(button, TL_keyboard.TL_inlineButtonTypeBuy.class) && drawBuyCard) {
            final int x = (int) rect.right - dp(5) - Theme.chat_botCardDrawable.getIntrinsicWidth();
            BaseCell.setDrawableBounds(Theme.chat_botCardDrawable, x, rect.top + dp(4));
            Theme.chat_botCardDrawable.draw(canvas);
        }

        canvas.restore();

        return invalidate;
    }

    /** glassGram: the loading band, fading in and out with the loading; true while it moves. */
    private boolean drawShimmer(Canvas canvas, RectF rect, boolean loading) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = shimmerAlphaTime == 0 ? 0 : Math.min(100, now - shimmerAlphaTime) / 160f;
        shimmerAlphaTime = now;
        if (loading && shimmerAlpha == 0) {
            shimmerStart = now;
        }
        shimmerAlpha = Utilities.clamp(shimmerAlpha + (loading ? dt : -dt), 1f, 0f);
        if (!loading && shimmerAlpha <= 0) {
            shimmerAlphaTime = 0;
            return false;
        }
        if (shimmerFill == null) {
            shimmerGradient = new LinearGradient(0, 0, 1, 0, new int[] { 0x00FFFFFF, 0xFFFFFFFF, 0x00FFFFFF }, new float[] { 0f, 0.5f, 1f }, Shader.TileMode.CLAMP);
            shimmerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            shimmerFill.setShader(shimmerGradient);
            shimmerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            shimmerStroke.setShader(shimmerGradient);
            shimmerStroke.setStyle(Paint.Style.STROKE);
            shimmerStroke.setStrokeWidth(AndroidUtilities.dpf2(1.33f));
        }
        final float band = Math.max(rect.width() * 0.55f, dp(90));
        final float phase = ((now - shimmerStart) % SHIMMER_PERIOD) / (float) SHIMMER_PERIOD;
        final float left = AndroidUtilities.lerp(rect.left - band, rect.right, phase);
        shimmerMatrix.reset();
        shimmerMatrix.postScale(band, 1);
        shimmerMatrix.postTranslate(left, 0);
        shimmerGradient.setLocalMatrix(shimmerMatrix);
        shimmerFill.setAlpha((int) (0x1C * shimmerAlpha));
        canvas.drawPath(path, shimmerFill);
        shimmerStroke.setAlpha((int) (0x60 * shimmerAlpha));
        canvas.drawPath(path, shimmerStroke);
        return true;
    }

    public void setPressed(boolean pressed) {
        if (this.pressed != pressed) {
            this.pressed = pressed;
            invalidateRunnable.run();
            if (pressed) {
                if (pressAnimator != null) {
                    pressAnimator.removeAllListeners();
                    pressAnimator.cancel();
                }
            }
            if (!pressed && pressT != 0) {
                pressAnimator = ValueAnimator.ofFloat(pressT, 0);
                pressAnimator.addUpdateListener(animation -> {
                    pressT = (float) animation.getAnimatedValue();
                    invalidateRunnable.run();
                });
                pressAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        pressAnimator = null;
                    }
                });
                pressAnimator.setInterpolator(new OvershootInterpolator(2.0f));
                pressAnimator.setDuration(350);
                pressAnimator.start();
            }
        }
    }

    public boolean hasPositionFlag(int flag) {
        return (positionFlags & flag) == flag;
    }

    public float getPressScale() {
        if (pressed && pressT != 1f) {
            pressT += (float) Math.min(40, 1000f / AndroidUtilities.screenRefreshRate) / 100f;
            pressT = Utilities.clamp(pressT, 1f, 0);
            invalidateRunnable.run();
        }
        return 0.96f + 0.04f * (1f - pressT);
    }
}
