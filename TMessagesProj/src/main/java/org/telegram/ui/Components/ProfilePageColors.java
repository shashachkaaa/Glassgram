package org.telegram.ui.Components;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

/**
 * The colors of a profile: the theme's (or the theme of the chat it was opened from) with the
 * profile color of the peer over the whole page, not only over the header. The page under the
 * cards goes on in the header's color, the cards are a shade of it: darker in a dark theme,
 * a pale tint in a light one. Without a profile color it is the theme as it is.
 */
public final class ProfilePageColors implements Theme.CompactModeResourcesProvider {

    @Nullable
    private final Theme.ResourcesProvider base;
    private int pageColor, cardColor;
    private float progress;

    public ProfilePageColors(@Nullable Theme.ResourcesProvider base) {
        this.base = base;
    }

    @Nullable
    public Theme.ResourcesProvider getBase() {
        return base;
    }

    /**
     * @param color    the profile color at the edge of the header, where it meets the page
     * @param progress 0 for the theme's own colors, 1 for the profile's
     * @return whether the colors changed
     */
    public boolean setProfileColor(int color, float progress) {
        progress = Utilities.clamp01(progress);
        int page = 0, card = 0;
        if (progress > 0f) {
            page = color | 0xFF000000;
            final int themeCard = baseColor(Theme.key_windowBackgroundWhite) | 0xFF000000;
            final boolean dark = ColorUtils.calculateLuminance(themeCard) < .5f;
            card = dark ? ColorUtils.blendARGB(page, Color.BLACK, .2f) : ColorUtils.blendARGB(page, Color.WHITE, .78f);
        }
        if (page == pageColor && card == cardColor && progress == this.progress) {
            return false;
        }
        pageColor = page;
        cardColor = card;
        this.progress = progress;
        return true;
    }

    public boolean isTinted() {
        return progress > 0f;
    }

    private int baseColor(int key) {
        return base != null ? base.getColor(key) : Theme.getColor(key);
    }

    private int tint(int key, int color) {
        if (progress <= 0f) {
            return color;
        }
        final int target;
        if (key == Theme.key_windowBackgroundGray) {
            target = pageColor;
        } else if (key == Theme.key_windowBackgroundWhite) {
            target = cardColor;
        } else if (key == Theme.key_graySection) {
            target = ColorUtils.blendARGB(cardColor, pageColor, .5f);
        } else {
            return color;
        }
        return ColorUtils.blendARGB(color, ColorUtils.setAlphaComponent(target, Color.alpha(color)), progress);
    }

    @Override
    public int getColor(int key) {
        return tint(key, baseColor(key));
    }

    @Override
    public int getColorOrDefault(int key) {
        return tint(key, base != null ? base.getColorOrDefault(key) : Theme.getColor(key));
    }

    @Override
    public int getCurrentColor(int key) {
        return tint(key, base != null ? base.getCurrentColor(key) : Theme.getColor(key));
    }

    @Override
    public void setAnimatedColor(int key, int color) {
        if (base != null) {
            base.setAnimatedColor(key, color);
        }
    }

    @Override
    public Drawable getDrawable(String drawableKey) {
        return base != null ? base.getDrawable(drawableKey) : null;
    }

    @Override
    public Paint getPaint(String paintKey) {
        return base != null ? base.getPaint(paintKey) : Theme.getThemePaint(paintKey);
    }

    @Override
    public boolean hasGradientService() {
        return base != null && base.hasGradientService();
    }

    @Override
    public boolean isDark() {
        return base != null ? base.isDark() : Theme.isCurrentThemeDark();
    }

    @Override
    public void applyServiceShaderMatrix(int w, int h, float translationX, float translationY) {
        if (base != null) {
            base.applyServiceShaderMatrix(w, h, translationX, translationY);
        } else {
            Theme.applyServiceShaderMatrix(w, h, translationX, translationY);
        }
    }

    @Override
    public ColorFilter getAnimatedEmojiColorFilter() {
        return base != null ? base.getAnimatedEmojiColorFilter() : Theme.chat_animatedEmojiTextColorFilter;
    }

    @Override
    public boolean isCompactMode() {
        return Theme.isCompactMode(base);
    }
}
