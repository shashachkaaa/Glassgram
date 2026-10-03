package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;


@SuppressLint("ViewConstructor")
public class ChatActivityBlurredRoundPageDownButton extends FrameLayout {
    private final Theme.ResourcesProvider resourcesProvider;

    private ChatActivityBlurredRoundButton buttonView;
    private CounterView counterView;

    public ChatActivityBlurredRoundPageDownButton(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    public void addButtonView(ChatActivityBlurredRoundButton button, int size) {
        this.buttonView = button;
        addView(button, LayoutHelper.createFrame(size, size, Gravity.BOTTOM));
        button.setIconPadding(dp(2));
    }


    private boolean reversedCounter;

    public void reverseCounter() {
        reversedCounter = true;
        if (counterView != null) {
            counterView.setReverse(true);
        }
    }

    public void setCount(int count, boolean animated) {
        if (counterView == null) {
            counterView = new CounterView(getContext(), resourcesProvider);
            counterView.setReverse(reversedCounter);
            addView(counterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.TOP));
        }

        counterView.setCount(count, animated);
    }





    // This view takes the clicks; the glass button inside stretches under the finger
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (buttonView != null && isEnabled() && isClickable()) {
            buttonView.onParentTouch(ev.getActionMasked(), ev.getX() - buttonView.getLeft(), ev.getY() - buttonView.getTop());
        }
        return super.dispatchTouchEvent(ev);
    }

    public void showLoading(boolean loading, boolean animated) {
        buttonView.showLoading(loading, animated);
    }

    @Override
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, false);
    }

    public void setEnabled(boolean enabled, boolean animated) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled, animated);
    }

    public void reverseIconByY() {
        buttonView.reverseIconByY();
    }

    public void updateColors() {
        if (buttonView != null) {
            buttonView.updateColors();
            invalidate();
        }
    }

    public static ChatActivityBlurredRoundPageDownButton create(
        Context context,
        int size, int iconSize,
        Theme.ResourcesProvider resourcesProvider,
        BlurredBackgroundDrawableViewFactory factory,
        BlurredBackgroundColorProvider colorProvider,
        @DrawableRes int res
    ) {
        ChatActivityBlurredRoundPageDownButton button = new ChatActivityBlurredRoundPageDownButton(context, resourcesProvider);
        button.addButtonView(ChatActivityBlurredRoundButton.create(context, factory, colorProvider, resourcesProvider, res, iconSize), size);

        return button;
    }
}
