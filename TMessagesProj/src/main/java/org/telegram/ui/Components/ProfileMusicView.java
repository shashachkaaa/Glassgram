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
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

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


    // The pills share the header and its transitions; in a row, side by side, see setInRow.
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

    private float drawnAlpha;

    /**
     * The pill as it was last drawn, in this view's coordinates, for the light of the profile's
     * gifts falling on it; returns its alpha, 0 when it is not drawn.
     */
    public float getDrawnPill(RectF out) {
        if (drawnAlpha <= 0f || getVisibility() != VISIBLE) return 0f;
        out.set(rect);
        return drawnAlpha * getAlpha();
    }

    /** The pill as it is laid out, in this view's coordinates, drawn or not. */
    public void getPillRect(RectF out) {
        out.set(rect);
    }

    private float menuHide;

    /** How far the pill is gone into the music's panel, 0 to 1, see {@link ProfileMusicMenu}. */
    public void setMenuHide(float hide) {
        if (menuHide == hide) return;
        menuHide = hide;
        invalidate();
    }

    public void getButtonBoundsOnScreen(RectF bounds) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(rect);
        bounds.offset(location[0], location[1]);
    }

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final LiquidPressEffect press = new LiquidPressEffect(this);
    private boolean clickEligible;
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
        progressColor = peerColor != null ? progressColorOf(peerColor.getBgColor1(Theme.isCurrentThemeDark())) : 0;
        invalidate();
    }

    /**
     * As on iOS, how far the track has played fills the pill in the profile's color, full and
     * bright (on a green profile a fresh green), the text white over it; 0 without a profile
     * color: a light veil.
     */
    private int progressColor;

    private static int progressColorOf(int color) {
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], .6f);
        hsv[2] = .69f;
        return Color.HSVToColor(hsv);
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



    /* As on iOS, the music and the balance side by side in one row when both are there: the music
       on the left, shortened as far as it has to be, the balance on the right, both as tall; the
       two together in the middle when there is room to spare. The row is laid out by the music
       pill as it draws, before the balance, which draws after it. */

    private static final int ROW_SIDE = 16, ROW_GAP = 8;
    private boolean inRow;
    /** Where in this view the pill lies in the row; NaN alone, in the middle of the view. */
    private float slotLeft = Float.NaN, slotRight = Float.NaN;

    /** Whether this pill and the one beside it (see setTrailingView) are in one row. */
    public void setInRow(boolean inRow) {
        if (trailingView != null) trailingView.setInRow(inRow);
        if (this.inRow == inRow) return;
        this.inRow = inRow;
        if (!inRow) setSlot(Float.NaN, Float.NaN);
        invalidate();
    }

    private void setSlot(float left, float right) {
        if (Float.compare(slotLeft, left) == 0 && Float.compare(slotRight, right) == 0) return;
        slotLeft = left;
        slotRight = right;
        invalidate();
    }

    /** The pill's width with all of its text shown. */
    public float getNaturalPillWidth() {
        if (author == null || title == null) return 0;
        return getPillWidth(dp(2000));
    }

    /** Whether the music and the balance beside it fit in one row of this width, the music not too short. */
    public static boolean fitInRow(int width, ProfileMusicView balance) {
        return width - dp(ROW_SIDE * 2 + ROW_GAP) - balance.getNaturalPillWidth() >= dp(120);
    }

    private void layoutRow() {
        final ProfileMusicView beside = trailingView;
        if (!inRow || beside == null || beside.getVisibility() != VISIBLE) {
            setSlot(Float.NaN, Float.NaN);
            return;
        }
        final float width = getWidth(), side = dp(ROW_SIDE), gap = dp(ROW_GAP);
        final float besideWidth = beside.getNaturalPillWidth();
        final float ownWidth = getNaturalPillWidth();
        if (ownWidth + gap + besideWidth <= width - 2 * side) {
            final float start = (width - ownWidth - gap - besideWidth) / 2f;
            setSlot(start, start + ownWidth);
            beside.setSlot(start + ownWidth + gap, start + ownWidth + gap + besideWidth);
        } else {
            setSlot(side, width - side - gap - besideWidth);
            beside.setSlot(width - side - besideWidth, width - side);
        }
    }

    private TLRPC.Document displayedDocument;
    private float playbackProgress;
    private final AnimatedFloat pillWidth = new AnimatedFloat(this, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setPlaybackProgress(float progress) {
        progress = Utilities.clamp01(progress);
        if (playbackProgress != progress) {
            playbackProgress = progress;
            invalidate();
        }
    }

    public void setMusicDocument(TLRPC.Document document) {
        if (displayedDocument == document && document != null) return;
        displayedDocument = document;
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
        removeCallbacks(startSeeking);
        seeking = false;
    }

    /** Off the screen the playing bars stand still: they drew the pill and its glass anew every frame. */
    private boolean animationsPaused;

    public void setAnimationsPaused(boolean paused) {
        if (animationsPaused == paused) return;
        animationsPaused = paused;
        if (!paused) invalidate();
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

    /* As on iOS, the track is wound on by holding the pill and moving the finger along it: the
       pill swells a little, the fill of how far it has played follows the finger, from where it
       was, and the track goes on from there when the finger lifts. */

    public interface SeekListener {
        /** Whether there is a track playing (or paused) to wind on. */
        boolean canSeek();

        void onSeek(float progress);
    }

    /** Held this long, ms, without moving, the pill winds the track on. */
    private static final long SEEK_HOLD = 300;
    private SeekListener seekListener;
    private boolean seeking;
    private float seekFrom, seekProgress, seekX, downX, downY, lastX, lastY;
    private final AnimatedFloat seekSwell = new AnimatedFloat(this, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setSeekListener(SeekListener listener) {
        seekListener = listener;
    }

    public boolean isSeeking() {
        return seeking;
    }

    private final Runnable startSeeking = () -> {
        if (!bounce.isPressed() || seekListener == null || !seekListener.canSeek()) return;
        seeking = true;
        clickEligible = false;
        seekFrom = seekProgress = playbackProgress;
        seekX = lastX;
        bounce.setPressed(false);
        press.onTouch(MotionEvent.ACTION_CANCEL, lastX - rect.left, lastY - rect.top);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        invalidate();
    };

    private void finishSeeking(boolean seek) {
        if (!seeking) return;
        seeking = false;
        if (seek && seekListener != null) {
            playbackProgress = seekProgress;
            seekListener.onSeek(seekProgress);
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final float x = event.getX(), y = event.getY();
        final boolean inside = rect.contains(x, y);
        lastX = x;
        lastY = y;
        if (action == MotionEvent.ACTION_DOWN) {
            if (currentHeight <= 0 || !inside) return false;
            clickEligible = true;
            bounce.setPressed(true);
            downX = x;
            downY = y;
            if (press.isLibraryButton()) {
                getParent().requestDisallowInterceptTouchEvent(true);
                press.onTouch(action, x - rect.left, y - rect.top);
            }
            removeCallbacks(startSeeking);
            if (seekListener != null) postDelayed(startSeeking, SEEK_HOLD);
            return true;
        }
        if (seeking) {
            if (action == MotionEvent.ACTION_MOVE) {
                seekProgress = Utilities.clamp01(seekFrom + (x - seekX) / Math.max(1f, rect.width()));
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishSeeking(action == MotionEvent.ACTION_UP);
            }
            return true;
        }
        if (!bounce.isPressed()) return false;
        press.onTouch(action, x - rect.left, y - rect.top);
        if (action == MotionEvent.ACTION_MOVE) {
            clickEligible &= inside;
            // moved before it held: not a hold
            final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) removeCallbacks(startSeeking);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            removeCallbacks(startSeeking);
            bounce.setPressed(false);
            if (action == MotionEvent.ACTION_UP && clickEligible && inside && currentHeight > 0) {
                performClick();
            }
        }
        return true;
    }

    protected float getContentWidth(float maxWidth) {
        final float textWidth = Math.max(0, maxWidth - dp(16) - dp(17));
        this.author.ellipsize(-1);
        this.title.ellipsize(-1);
        this.author.ellipsize(Math.max(textWidth * 0.65f, textWidth - this.title.getWidth()));
        this.title.ellipsize(Math.max(0, textWidth - this.author.getWidth()));
        return dp(17) + this.author.getWidth() + this.title.getWidth();
    }

    private boolean playing;

    public void setPlaying(boolean playing) {
        if (this.playing == playing) return;
        this.playing = playing;
        invalidate();
    }

    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;

        iconPaint.setColor(textColor);
        iconPaint.setAlpha(Math.round(255 * alpha));
        final double phase = playing ? (getDrawingTime() % 1200L) * (2 * Math.PI / 1200.0) : 0;
        for (int i = 0; i < 3; i++) {
            float x = dp(1 + 3 * i);
            float h = dp(4);
            if (playing) {
                h += dp(2) * (float) Math.sin(phase + i * 2 * Math.PI / 3);
            }
            canvas.drawRoundRect(x, cy - h, x + dp(2), cy + h, dp(1), dp(1), iconPaint);
        }
        if (playing && !animationsPaused && getAlpha() > 0f) postInvalidateOnAnimation();
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
        drawnAlpha = 0f;
        if (this.author == null || this.title == null) return;

        final float sizeAlpha = Utilities.clamp01((currentHeight) / dp(21));
        // while the music's panel is out of it, the pill is gone into the panel's glass
        final float alpha = sizeAlpha * (1f - menuHide);
        // held to wind the track on, it swells a little, as on iOS
        final float scale = (press.isLibraryButton() ? 1f : bounce.getScale(0.02f)) * (1f + .045f * seekSwell.set(seeking));
        drawnAlpha = alpha;
        if (sizeAlpha <= 0) {
            if (buttonsGlass != null) {
                buttonsGlass.setAlpha(0);
            }
            return;
        }

        if (!(this instanceof ProfileBalanceView)) layoutRow();
        final boolean row = inRow && !Float.isNaN(slotLeft) && slotRight > slotLeft;
        final int padding = dp(12);
        final float available = row ? slotRight - slotLeft : Math.max(0, getWidth() - padding * 2);
        final float maxWidth = row || this instanceof ProfileBalanceView ? available
                : Math.min(available, Math.round(getWidth() * 0.76f));

        final float targetWidth = getPillWidth(maxWidth);
        final float containerWidth = this instanceof ProfileBalanceView ? targetWidth : Math.min(maxWidth, pillWidth.set(targetWidth));
        // in the row the music keeps to the left of its place and the balance to the right
        final float left = !row ? (getWidth() - containerWidth) / 2f
                : this instanceof ProfileBalanceView ? slotRight - containerWidth : slotLeft;
        final float halfHeight = dp(this instanceof ProfileBalanceView && !row ? 10 : 12) * sizeAlpha;
        rect.set(left, getHeight() / 2f - halfHeight, left + containerWidth, getHeight() / 2f + halfHeight);
        if (alpha <= 0f) {
            if (buttonsGlass != null) {
                buttonsGlass.setAlpha(0);
            }
            return;
        }
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), getHeight() / 2f);
        if (menuHide > 0f) {
            // going into the music's panel it draws in to its middle, coming back it grows out of it, as on iOS
            canvas.scale(1f - .7f * menuHide, 1f - .25f * menuHide, rect.centerX(), getHeight() / 2f);
        }

        // glassGram: the library's glass made of the header, as the action buttons above it
        final boolean glassDrawn = glass != null && glass.begin(canvas);
        if (glassDrawn) {
            glass.draw(canvas, glassPiece, press, rect, rect.height() / 2f, textColor == Color.WHITE ? 1f : 0f, alpha, parentExpanded);
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

        if (glassDrawn && press.isLibraryButton()) {
            canvas.translate(rect.left, rect.top);
            press.transform(canvas, rect.width(), rect.height());
            canvas.translate(-rect.left, -rect.top);
        }
        clipPath.rewind();
        clipPath.addRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);
        final float progress = seeking ? seekProgress : playbackProgress;
        if (progress > 0f) {
            if (progressColor != 0 && !isOnProfilePhoto()) {
                progressPaint.setColor(progressColor);
                progressPaint.setAlpha(Math.round(0xFF * alpha));
            } else {
                progressPaint.setColor(textColor);
                progressPaint.setAlpha(Math.round((seeking ? 90 : 52) * alpha));
            }
            canvas.drawRect(rect.left, rect.top, rect.left + rect.width() * progress,
                    rect.bottom, progressPaint);
        }
        canvas.translate(rect.left + dp(8), 0);
        drawContent(canvas, alpha);

        canvas.restore();

        canvas.restore();
    }
}
