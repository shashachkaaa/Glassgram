package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.GlassgramUpdates;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

/** A theme-tinted Kyant LiquidButton above the main tabs, independent of fragment lifetime. */
public final class GlassgramUpdateButton extends FrameLayout {
    private final BlurredBackgroundDrawable glass;
    private final LiquidPressEffect press;
    private final LiquidButtonBackground fallback;
    private final TextView label;
    private int tint;

    public GlassgramUpdateButton(Context context, BlurredBackgroundDrawableViewFactory factory,
                                 BlurredBackgroundColorProvider colors) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(true);
        setFocusable(true);
        glass = factory.create(this, colors);
        glass.setPadding(dp(6));
        press = new LiquidPressEffect(this);
        press.setUnclipParents(2);
        fallback = new LiquidButtonBackground(0);
        label = new TextView(context);
        label.setTextSize(15);
        label.setTypeface(AndroidUtilities.bold());
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(12), 0, dp(12), 0);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setVisibility(GONE);
    }

    public void setColors(int tint, int textColor) {
        this.tint = tint;
        fallback.setColor(tint);
        label.setTextColor(textColor);
        glass.updateColors();
        invalidate();
    }

    /** Returns true if the persistent row has appeared or disappeared. */
    public boolean refresh() {
        final int state = GlassgramUpdates.getState();
        final int visibility = state == GlassgramUpdates.HIDDEN ? GONE : VISIBLE;
        final boolean changed = getVisibility() != visibility;
        if (changed) {
            setVisibility(visibility);
        }
        if (state == GlassgramUpdates.HIDDEN) {
            return changed;
        }
        final int percent = GlassgramUpdates.getProgress();
        final String progress = percent >= 0 ? percent + "%" : AndroidUtilities.formatFileSize(GlassgramUpdates.getDownloadedBytes());
        final String text;
        switch (state) {
            case GlassgramUpdates.DOWNLOADING:
                text = LocaleController.formatString(R.string.GlassgramUpdateBarDownloading, progress);
                break;
            case GlassgramUpdates.PAUSING:
                text = LocaleController.formatString(R.string.GlassgramUpdateBarPausing, progress);
                break;
            case GlassgramUpdates.PAUSED:
                text = LocaleController.formatString(R.string.GlassgramUpdateBarPaused, progress);
                break;
            case GlassgramUpdates.READY:
                text = LocaleController.getString(R.string.GlassgramUpdateBarInstall);
                break;
            case GlassgramUpdates.FAILED:
                text = LocaleController.getString(R.string.GlassgramUpdateBarRetry);
                break;
            default:
                final long size = GlassgramUpdates.getTotalBytes();
                text = LocaleController.getString(R.string.GlassgramUpdateBarAvailable)
                        + (size > 0 ? "  ·  " + AndroidUtilities.formatFileSize(size) : "");
                break;
        }
        if (!TextUtils.equals(label.getText(), text)) {
            label.setText(text);
            setContentDescription(text);
        }
        return changed;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The main tabs are limited to 328dp of glass, with 8dp external margins.
        super.onMeasure(MeasureSpec.makeMeasureSpec(Math.min(dp(328), MeasureSpec.getSize(widthMeasureSpec)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(GlassgramUpdates.BAR_HEIGHT_DP), MeasureSpec.EXACTLY));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        press.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final int w = getWidth(), h = getHeight(), p = dp(6);
        if (w <= 0 || h <= 0) {
            return;
        }
        glass.setBounds(-p, -p, w + p, h + p);
        glass.setRadius(h / 2f);
        // LiquidPressEffect delegates to the catalog's LiquidButtonView (Kyant Backdrop).
        // On devices without the required shaders, retain the same themed capsule.
        final boolean liquid = press.drawGlass(canvas, 0, 0, w, h, h / 2f, glass, 255, tint);
        canvas.save();
        if (press.isActive()) {
            press.transform(canvas, w, h);
        }
        if (!liquid) {
            fallback.setBounds(0, 0, w, h);
            fallback.draw(canvas);
            if (press.isActive()) {
                press.drawGlow(canvas, w, h, h / 2f);
            }
        }
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
