package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * What a locked chat shows in its place until the PIN or a fingerprint is given
 * (GlassgramChatLock): the chat's name, and a button asking for them.
 */
@SuppressLint("ViewConstructor")
public class GlassgramChatLockView extends FrameLayout {

    public GlassgramChatLockView(Context context, Theme.ResourcesProvider resourcesProvider, CharSequence chatName, Runnable onUnlock, Runnable onBack) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        setClickable(true);

        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        back.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setContentDescription(getString(R.string.AccDescrGoBack));
        back.setOnClickListener(v -> onBack.run());
        addView(back, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 4, 0, 0, 0));
        ((LayoutParams) back.getLayoutParams()).topMargin = AndroidUtilities.statusBarHeight + dp(4);

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        final RLottieImageView icon = new RLottieImageView(context);
        icon.setAnimation(R.raw.passcode_lock, 96, 96);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon, resourcesProvider), PorterDuff.Mode.SRC_IN));
        icon.playAnimation();
        column.addView(icon, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        title.setGravity(Gravity.CENTER);
        title.setText(getString(R.string.GlassgramChatLockTitle));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        if (!TextUtils.isEmpty(chatName)) {
            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(chatName);
            column.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
        }

        final ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider);
        button.setText(getString(R.string.GlassgramChatLockOpen), false);
        button.setOnClickListener(v -> onUnlock.run());
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // nothing of the chat behind it is reached
        super.onTouchEvent(event);
        return true;
    }
}
