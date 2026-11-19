package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.multAlpha;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

public class ProfileMusicView extends View {

    private final Theme.ResourcesProvider resourcesProvider;

    private Text author, title;
    private ProfileMusicView trailingView;


    // The pills have separate rows but share the header and its transitions.
    public void setTrailingView(ProfileMusicView view) {
        trailingView = view;

        if (glassHeader != null) {
            view.setGlassHeader(glassHeader, glassPhoto);
        }
        invalidate();
        view.invalidate();
    }

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);


    private final RectF rect = new RectF();

    public void getButtonBoundsOnScreen(RectF bounds) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(rect);
        bounds.offset(location[0], location[1]);
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final ButtonBounce bounce = new ButtonBounce(this);

    public ProfileMusicView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;




        setColor(null);
        setText("", "");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                dp(37),
                MeasureSpec.EXACTLY
            )
        );
    }

    protected int textColor = Color.WHITE;
    protected boolean hasProfileColor;
    private float parentExpanded;
    private int buttonColor;
    private boolean hasColorById;
    private RadialGradient radialGradient;
    private float radialGradientWidth;
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    public void setColor(MessagesController.PeerColor peerColor) {
        hasProfileColor = peerColor != null;
        if (trailingView != null) trailingView.setColor(peerColor);
        invalidate();
    }

    // Same color as ProfileActionsView buttons.
    public void setButtonColor(int color, boolean hasColorById) {
        if (trailingView != null) trailingView.setButtonColor(color, hasColorById);
        if (buttonColor == color && this.hasColorById == hasColorById) return;
        buttonColor = color;
        this.hasColorById = hasColorById;
        radialGradient = null;
        backgroundPaint.setColor(color);
        checkTextColor();
    }

    private boolean isButtonColorLight() {
        return AndroidUtilities.computePerceivedBrightness(buttonColor) > 0.72f;
    }

    private void checkTextColor() {
        final boolean useBlackText = parentExpanded < 0.8f && isButtonColorLight();
        textColor = useBlackText ? Color.BLACK : Color.WHITE;

        iconPaint.setColor(textColor);
        invalidate();
    }

    public void setParentExpanded(float expanded) {
        if (trailingView != null) trailingView.setParentExpanded(expanded);
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkTextColor();
            invalidate();
        }
    }



    public void setMusicDocument(TLRPC.Document document) {
        CharSequence title = getTitle(document);
        CharSequence artist = getAuthor(document);
        setText(TextUtils.isEmpty(title) ? getString(R.string.AudioUnknownTitle) : title,
                " – " + (TextUtils.isEmpty(artist) ? getString(R.string.AudioUnknownArtist) : artist));
    }

    public static CharSequence getTitle(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                String title = attribute.title;
                if (title == null || title.length() == 0) {
                    title = FileLoader.getDocumentFileName(document);
                }
                return title;
            }
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }
//        return getString(R.string.AudioUnknownTitle);
        return null;
    }

    public static CharSequence getAuthor(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (!attribute.voice) {
                    return attribute.performer;
                }
            }
        }
        return null;
    }

    public void setText(CharSequence author, CharSequence title) {
        this.author = new Text(author, 11, AndroidUtilities.bold());
        this.title = new Text(title, 11);

        setContentDescription(getString(R.string.AccDescrProfileMusic) + " " + author + title);
    }

    /** The pill's glass, like the action buttons'; without it the pill is filled with their color. */
    @Nullable
    private BlurredBackgroundDrawable buttonsGlass;
    private float buttonsGlassRadius;

    /** The pill on glass, see {@link ProfileButtonsGlass#actionsFactory}. */
    public void setGlassFactory(@Nullable BlurredBackgroundDrawableViewFactory factory) {
        if (trailingView != null) trailingView.setGlassFactory(factory);
        buttonsGlass = factory != null ? factory.create(this, ProfileButtonsGlass.NO_COLORS) : null;
        buttonsGlassRadius = 0;
        invalidate();
    }

    private float currentHeight;

    // The pill is Liquid Glass made of the header under it, as the action buttons above it
    private ProfileHeaderGlass glass;
    private final ProfileHeaderGlass.Piece glassPiece = new ProfileHeaderGlass.Piece();
    private View glassHeader, glassPhoto;

    /** The header drawn under the pill, a sibling of its parent, for its glass; the pill beside it gets it too. */
    public void setGlassHeader(View header, View photo) {
        if (glass == null) {
            glass = new ProfileHeaderGlass(this);
        }
        glassHeader = header;
        glassPhoto = photo;
        glass.setPhoto(photo);
        glass.setHeader(header);
        if (trailingView != null) {
            trailingView.setGlassHeader(header, photo);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (glass != null) {
            glass.watch(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (glass != null) {
            glass.watch(false);
        }
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (trailingView != null) trailingView.setAlpha(alpha);
    }

    protected boolean isOnProfilePhoto() {
        return parentExpanded >= 0.8f;
    }

    public void updatePosition(float y, float newHeight) {

        currentHeight = newHeight;
        setTranslationY(y);
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final float alpha = Utilities.clamp01((currentHeight) / dp(21));
        if (alpha <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bounce.setPressed(rect.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && bounce.isPressed()) {
            if (!rect.contains(event.getX(), event.getY())) {
                bounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bounce.setPressed(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (bounce.isPressed()) {
                performClick();
            }
            bounce.setPressed(false);
        }
        return bounce.isPressed();
    }

    protected float getContentWidth(float maxWidth) {
        final float textWidth = Math.max(0, maxWidth - dp(16) - dp(17));
        this.author.ellipsize(-1);
        this.title.ellipsize(-1);
        this.author.ellipsize(Math.max(textWidth * 0.65f, textWidth - this.title.getWidth()));
        this.title.ellipsize(Math.max(0, textWidth - this.author.getWidth()));
        return dp(17) + this.author.getWidth() + this.title.getWidth();
    }

    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;

        iconPaint.setColor(textColor);
        iconPaint.setAlpha(Math.round(255 * alpha));
        for (int i = 0; i < 3; i++) {
            float x = dp(1 + 3 * i);
            float h = dp(5 - i);
            canvas.drawRoundRect(x, cy - h, x + dp(2), cy + h, dp(1), dp(1), iconPaint);
        }
        canvas.translate(dp(16.6f), 0);
        this.author.draw(canvas, 0, cy, textColor, alpha);
        canvas.translate(this.author.getWidth(), 0);
        this.title.draw(canvas, 0, cy, textColor, 0.85f * alpha);
    }

    private float getPillWidth(float maxWidth) {
        return dp(16) + getContentWidth(maxWidth);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (this.author == null || this.title == null) return;

        final float alpha = Utilities.clamp01((currentHeight) / dp(21));
        final float scale = bounce.getScale(0.02f);
        if (alpha <= 0) {
            if (buttonsGlass != null) {
                buttonsGlass.setAlpha(0);
            }
            return;
        }

        final int padding = dp(12);
        final int available = Math.max(0, getWidth() - padding * 2);
        final int maxWidth = this instanceof ProfileBalanceView ? available
                : Math.min(available, Math.round(getWidth() * 0.76f));

        final float containerWidth = getPillWidth(maxWidth);
        final float left = (getWidth() - containerWidth) / 2f;
        final float halfHeight = dp(this instanceof ProfileBalanceView ? 10 : 12) * alpha;
        rect.set(left, getHeight() / 2f - halfHeight, left + containerWidth, getHeight() / 2f + halfHeight);
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), getHeight() / 2f);

        // glassGram: the library's glass made of the header, as the action buttons above it
        final boolean glassDrawn = glass != null && glass.begin(canvas);
        if (glassDrawn) {
            glass.draw(canvas, glassPiece, rect, rect.height() / 2f, textColor == Color.WHITE ? 1f : 0f, alpha, parentExpanded);
        } else if (buttonsGlass != null) {
            final float radius = rect.height() / 2f;
            if (buttonsGlassRadius != radius) {
                buttonsGlassRadius = radius;
                buttonsGlass.setRadius(radius);
            }
            buttonsGlass.setBounds((int) rect.left, (int) rect.top, (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom));
            buttonsGlass.setAlpha((int) (0xFF * alpha));
            buttonsGlass.draw(canvas);
        } else {
            final float bgAlpha = hasColorById ? 0.1f : 1f;
            backgroundPaint.setShadowLayer(0, 0, 0, 0);
            int wasAlpha = backgroundPaint.getAlpha();
            backgroundPaint.setAlpha((int) (wasAlpha * alpha * bgAlpha));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, backgroundPaint);
            backgroundPaint.setAlpha(wasAlpha);
            if (hasColorById && buttonColor != 0) {
                if (radialGradient == null || radialGradientWidth != rect.width()) {
                    radialGradientWidth = rect.width();
                    radialGradient = new RadialGradient(
                        rect.width() / 2f, dp(17) / 2f, rect.width() * 0.65f,
                        Theme.multAlpha(buttonColor, 0.8f), buttonColor,
                        Shader.TileMode.CLAMP
                    );
                    shaderPaint.setShader(radialGradient);
                }
                matrix.setTranslate(rect.left, rect.top);
                radialGradient.setLocalMatrix(matrix);
                shaderPaint.setAlpha((int) (0xFF * alpha));
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, shaderPaint);
            }
        }

        clipPath.rewind();
        clipPath.addRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);
        canvas.translate(rect.left + dp(8), 0);
        drawContent(canvas, alpha);

        canvas.restore();

        canvas.restore();
    }
}
