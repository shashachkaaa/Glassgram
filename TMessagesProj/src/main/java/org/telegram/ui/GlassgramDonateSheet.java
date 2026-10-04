package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramBadges;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.GlassgramBadgeDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Opened by tapping a supporter's badge: how to support Glassgram through its bot and get the
 * same badge, like exteraGram's "Support Development" sheet.
 */
public class GlassgramDonateSheet extends BottomSheet {

    public static void show(BaseFragment fragment, GlassgramBadges.Badge badge) {
        if (fragment == null || fragment.getParentActivity() == null || badge == null) {
            return;
        }
        fragment.showDialog(new GlassgramDonateSheet(fragment, badge));
    }

    private GlassgramDonateSheet(BaseFragment fragment, GlassgramBadges.Badge badge) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        final Context context = getContext();
        final Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));
        final String bot = badge.donateBot;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(24), dp(22), dp(10));

        // The badge itself, large, in the accent color with its sparkles
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        GlassgramBadgeDrawable icon = new GlassgramBadgeDrawable(badge.getIconResId(), dp(88));
        icon.setColor(accent);
        ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageDrawable(icon);
        layout.addView(iconView, LayoutHelper.createLinear(150, 150, Gravity.CENTER_HORIZONTAL, 0, -14, 0, -14));

        TextView title = new TextView(context);
        title.setText(getString(R.string.GlassgramDonateTitle));
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.GlassgramDonateSubtitle));
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitle.setGravity(Gravity.CENTER);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 18));

        layout.addView(step(context, resourcesProvider, R.drawable.msg_bot,
            getString(R.string.GlassgramDonateStep1),
            LocaleController.formatString(R.string.GlassgramDonateStep1Text, bot)));
        layout.addView(step(context, resourcesProvider, R.drawable.star_small_outline,
            getString(R.string.GlassgramDonateStep2),
            getString(R.string.GlassgramDonateStep2Text)));
        layout.addView(step(context, resourcesProvider, R.drawable.msg_premium_badge,
            getString(R.string.GlassgramDonateStep3),
            getString(R.string.GlassgramDonateStep3Text)));

        ButtonWithCounterView openBot = new ButtonWithCounterView(context, resourcesProvider);
        openBot.setText(LocaleController.formatString(R.string.GlassgramDonateOpenBot, bot), false);
        openBot.setOnClickListener(v -> {
            dismiss();
            org.telegram.messenger.browser.Browser.openUrl(fragment.getParentActivity(), "https://t.me/" + bot + "?start=donate");
        });
        layout.addView(openBot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 0));

        ButtonWithCounterView close = new ButtonWithCounterView(context, false, resourcesProvider);
        close.setText(getString(R.string.Close), false);
        close.setOnClickListener(v -> dismiss());
        layout.addView(close, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));

        setCustomView(layout);
    }

    private static LinearLayout step(Context context, Theme.ResourcesProvider resourcesProvider, int iconRes, String title, String text) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(iconRes);
        icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider), PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createLinear(28, 28, Gravity.TOP, 0, 2, 16, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        texts.addView(titleView);

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, resourcesProvider));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        return row;
    }
}
