package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.HashMap;
import java.util.Map;

public class ChatActivityEnterViewAnimatedIconView extends RLottieImageView {
    private State currentState;
    private TransitState animatingState;
    private final int sizeDp;
    // glassGram: the stickers icon is iOS's round sticker with a peeled edge, not Android's square
    // one; it swaps with the other icons by a fade and a scale, as on iOS
    private Drawable stickerDrawable;
    private Drawable swapFrom;
    private float swapProgress = 1f;
    private ValueAnimator swapAnimator;

    private final Map<TransitState, RLottieDrawable> stateMap = new HashMap<TransitState, RLottieDrawable>() {
        @Nullable
        @Override
        public RLottieDrawable get(@Nullable Object key) {
            RLottieDrawable obj = super.get(key);
            if (obj == null) {
                TransitState state = (TransitState) key;
                int res = state.resource;
                RLottieDrawable rLottieDrawable = new RLottieDrawable(res, AndroidUtilities.dp(sizeDp), AndroidUtilities.dp(sizeDp));
                put(state, rLottieDrawable);
                return rLottieDrawable;
            }
            return obj;
        }
    };

    public ChatActivityEnterViewAnimatedIconView(Context context) {
        this(context, 32);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp) {
        super(context);
        this.sizeDp = sizeDp;
    }

    public void setState(State state, boolean animate) {
        if (animate && state == currentState) {
            return;
        }
        State fromState = currentState;
        currentState = state;
        if (state == State.STICKER || fromState == State.STICKER) {
            swapIcon(state, animate && fromState != null);
        } else if (!animate || fromState == null || getState(fromState, currentState) == null) {
            cancelSwap();
            RLottieDrawable drawable = stateMap.get(getAnyState(currentState));
            drawable.stop();

            drawable.setProgress(state == State.VOICE ? 0.5f : 0, false);
            setAnimation(drawable);
        } else {
            TransitState transitState = getState(fromState, currentState);
            if (transitState == animatingState) {
                return;
            }
            cancelSwap();

            animatingState = transitState;
            RLottieDrawable drawable = stateMap.get(transitState);
            drawable.stop();
            if (transitState == TransitState.VIDEO_TO_VOICE) {
                drawable.setCustomEndFrame(30);
                drawable.setProgress(0, false);
            } else if (transitState == TransitState.VOICE_TO_VIDEO) {
                drawable.setCustomEndFrame(60);
                drawable.setProgress(0.5f, false);
            } else {
                drawable.setProgress(0, false);
            }
            drawable.setAutoRepeat(0);
            drawable.setOnAnimationEndListener(() -> animatingState = null);
            setAnimation(drawable);
            AndroidUtilities.runOnUIThread(drawable::start);
        }

        switch (state) {
            case VOICE:
                setContentDescription(LocaleController.getString(R.string.AccDescrVoiceMessage));
                break;
            case VIDEO:
                setContentDescription(LocaleController.getString(R.string.AccDescrVideoMessage));
                break;
        }
    }

    private void swapIcon(State state, boolean animate) {
        final Drawable from = animate ? getDrawable() : null;
        cancelSwap();
        animatingState = null;
        if (state == State.STICKER) {
            if (stickerDrawable == null) {
                stickerDrawable = getContext().getResources().getDrawable(R.drawable.input_sticker_ios).mutate();
            }
            clearAnimationDrawable();
            setImageDrawable(stickerDrawable);
        } else {
            RLottieDrawable drawable = stateMap.get(getAnyState(state));
            drawable.stop();
            drawable.setProgress(state == State.VOICE ? 0.5f : 0, false);
            setAnimation(drawable);
        }
        if (from == null || from == getDrawable()) {
            return;
        }
        swapFrom = from;
        swapProgress = 0f;
        swapAnimator = ValueAnimator.ofFloat(0f, 1f);
        swapAnimator.addUpdateListener(a -> {
            swapProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        swapAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (swapAnimator == animation) {
                    swapAnimator = null;
                    swapFrom = null;
                    swapProgress = 1f;
                    invalidate();
                }
            }
        });
        swapAnimator.setDuration(220);
        swapAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        swapAnimator.start();
    }

    private void cancelSwap() {
        if (swapAnimator != null) {
            final ValueAnimator animator = swapAnimator;
            swapAnimator = null;
            animator.cancel();
        }
        swapFrom = null;
        swapProgress = 1f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Drawable drawable = getDrawable();
        if (swapFrom == null || swapProgress >= 1f || drawable == null) {
            super.onDraw(canvas);
            return;
        }
        final float cx = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight()) / 2f;
        final float cy = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;

        // the old icon shrinks and fades out, drawn with the matrix the new one has (the icons are one size)
        canvas.save();
        float scale = AndroidUtilities.lerp(1f, 0.4f, swapProgress);
        canvas.scale(scale, scale, cx, cy);
        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.concat(getImageMatrix());
        swapFrom.setAlpha((int) (0xFF * (1f - swapProgress)));
        swapFrom.draw(canvas);
        swapFrom.setAlpha(0xFF);
        canvas.restore();

        canvas.save();
        scale = AndroidUtilities.lerp(0.4f, 1f, swapProgress);
        canvas.scale(scale, scale, cx, cy);
        drawable.setAlpha((int) (0xFF * swapProgress));
        super.onDraw(canvas);
        drawable.setAlpha(0xFF);
        canvas.restore();
    }

    public State getCurrentState() {
        return currentState;
    }

    private TransitState getAnyState(State from) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from) {
                return transitState;
            }
        }
        return null;
    }

    private TransitState getState(State from, State to) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from && transitState.secondState == to) {
                return transitState;
            }
        }
        return null;
    }

    private enum TransitState {
        VOICE_TO_VIDEO(State.VOICE, State.VIDEO, R.raw.voice_and_video),
        STICKER_TO_KEYBOARD(State.STICKER, State.KEYBOARD, R.raw.sticker_to_keyboard),
        SMILE_TO_KEYBOARD(State.SMILE, State.KEYBOARD, R.raw.smile_to_keyboard),
        VIDEO_TO_VOICE(State.VIDEO, State.VOICE, R.raw.voice_and_video),
        KEYBOARD_TO_STICKER(State.KEYBOARD, State.STICKER, R.raw.keyboard_to_sticker),
        KEYBOARD_TO_GIF(State.KEYBOARD, State.GIF, R.raw.keyboard_to_gif),
        KEYBOARD_TO_SMILE(State.KEYBOARD, State.SMILE, R.raw.keyboard_to_smile),
        GIF_TO_KEYBOARD(State.GIF, State.KEYBOARD, R.raw.gif_to_keyboard),
        GIF_TO_SMILE(State.GIF, State.SMILE, R.raw.gif_to_smile),
        SMILE_TO_GIF(State.SMILE, State.GIF, R.raw.smile_to_gif),
        SMILE_TO_STICKER(State.SMILE, State.STICKER, R.raw.smile_to_sticker),
        STICKER_TO_SMILE(State.STICKER, State.SMILE, R.raw.sticker_to_smile);

        final State firstState, secondState;
        final int resource;

        TransitState(State firstState, State secondState, int resource) {
            this.firstState = firstState;
            this.secondState = secondState;
            this.resource = resource;
        }
    }

    public enum State {
        VOICE,
        VIDEO,
        STICKER,
        KEYBOARD,
        SMILE,
        GIF
    }
}
