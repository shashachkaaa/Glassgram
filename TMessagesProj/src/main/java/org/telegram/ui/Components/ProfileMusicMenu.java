package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * glassGram: the profile's music as on iOS. A tap on the music pill splits a drop of its glass
 * off: the pill draws in to it and lets it go, the drop falls a little, swells and spreads into a
 * panel of dark glass as wide as the pill and a bit wider, its top where the pill was, listing the
 * tracks (the one playing ticked) and, under a line, a way to the whole playlist. Closed, the panel
 * draws back into a drop of clear glass that flows back up into the pill, which comes back from it.
 * <p>
 * It lies over the whole profile while it is there and takes all the touches: a track picked plays,
 * a tap off the panel closes it, a drag along it scrolls the list.
 */
public class ProfileMusicMenu extends View {

    public interface Delegate {
        int getTrackCount();

        MessageObject getTrack(int index);

        /** Whether this is the track playing, or paused: it is ticked. */
        boolean isCurrentTrack(MessageObject track);

        void onTrackClick(MessageObject track);

        void onShowPlaylist();

        /** Near the end of the list: the next of it, if there is more. */
        void loadMore();
    }

    private static final long OPEN_MS = 400, CLOSE_MS = 290;
    /**
     * The way open, as on iOS: to DROP the pill swells down into a big drop of frosted glass under
     * its middle, the panel's list showing blurred through it; to SPREAD the drop spreads into the
     * panel, dark, the list in it at once, a little past its size and back; to 1 the list settles
     * as the glass's bending at the edges eases. Closing goes back from SPREAD: the list and the
     * dark go at once as the panel draws in to the drop, which flows back up into the pill.
     */
    private static final float DROP = .35f, SPREAD = .72f;
    /** Closing, the part of its time the panel takes to draw in to the drop; the rest, the drop going into the pill. */
    private static final float CLOSE_SPREAD_TIME = .35f;
    private static final float PANEL_RADIUS_DP = 26, ROW_DP = 55, PAD_TOP_DP = 6, DIVIDER_DP = 9, FOOTER_DP = 52, PAD_BOTTOM_DP = 4;
    private static final int NONE = -1, FOOTER = -2;

    private final Theme.ResourcesProvider resourcesProvider;
    private final ProfileHeaderGlass glass;
    private final ProfileHeaderGlass.Piece piece = new ProfileHeaderGlass.Piece();
    private ProfileMusicView anchor;
    private Delegate delegate;

    private float progress;
    private boolean open, recorded, hasGlass;
    /** Which way it is going: closing, the panel draws in without going past its size first. */
    private boolean closing;
    private ValueAnimator animator;

    private final RectF pill = new RectF(), panel = new RectF(), body = new RectF(), source = new RectF();
    private final RectF glassRect = new RectF(), shapeBounds = new RectF();
    private final Path shape = new Path(), piecePath = new Path(), bridge = new Path(), bridgeEdges = new Path();
    private float bodyRadius, glassRadius, lens, contentAlpha, blurredAlpha, contentScale, blur;
    private int tint;
    private final RectF drop = new RectF();

    private final DecelerateInterpolator dropEase = new DecelerateInterpolator(1f);
    private final OvershootInterpolator spreadEase = new OvershootInterpolator(1.1f);
    /**
     * The list is laid in the glass while it spreads and settles, seen through a lens of its own
     * that bends it hard at the edges, as through glass curving away there, and eases off.
     */
    private final ProfileHeaderGlass.Piece listPiece = new ProfileHeaderGlass.Piece();
    private float listLensHeight, listLensAmount;
    private final org.telegram.liquidglass.GlassLayer.Backdrop lensedList = canvas -> {
        canvas.save();
        if (contentScale != 1f) canvas.scale(contentScale, contentScale, panel.centerX(), panel.top + Math.min(panel.height(), contentHeight()) / 2f);
        drawContent(canvas, contentAlpha);
        canvas.restore();
    };
    private final DecelerateInterpolator settleEase = new DecelerateInterpolator(1.5f);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** The list drawn under the glass, blurred through the drop, see {@link #layout}. */
    private final org.telegram.liquidglass.GlassLayer.Backdrop blurredList = canvas -> drawContent(canvas, blurredAlpha);

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check = new Path();
    private final Drawable folderIcon;
    private int folderColor;
    private Text footerText;
    private float footerWidth;
    /** Whether the page is dark, for this draw. */
    private boolean dark;

    private static final class Row {
        long id;
        float width;
        Text title, subtitle;
    }

    private final ArrayList<Row> rows = new ArrayList<>();

    private float scrollY, scrollFrom, downX, downY;
    private boolean dragging;
    private int pressed = NONE;

    public ProfileMusicMenu(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        glass = new ProfileHeaderGlass(this);
        rimPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setStrokeWidth(dpf2(2f));
        check.moveTo(0, dpf2(5.5f));
        check.lineTo(dpf2(4.6f), dpf2(10f));
        check.lineTo(dpf2(13.5f), 0);
        folderIcon = context.getResources().getDrawable(R.drawable.msg_folders).mutate();
        titlePaint.setTextSize(dp(15.5f));
        subtitlePaint.setTextSize(dp(13f));
        setVisibility(GONE);
    }

    /** The header and the page under it, for the glass: the panel goes down over the cards. */
    public void setBackdrop(View header, View page) {
        glass.setHeader(header);
        glass.setUnder(page);
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public boolean isOpen() {
        return open;
    }

    public void show(ProfileMusicView anchor) {
        if (open || anchor == null || delegate == null) return;
        this.anchor = anchor;
        open = true;
        recorded = false;
        pressed = NONE;
        scrollY = 0;
        rows.clear();
        setVisibility(VISIBLE);
        animateTo(1f);
    }

    public void dismiss() {
        if (!open) return;
        open = false;
        pressed = NONE;
        dragging = false;
        animateTo(0f);
    }

    /** The list grew or changed: the panel follows it. */
    public void onListChanged() {
        if (getVisibility() == VISIBLE) invalidate();
    }

    private void animateTo(float target) {
        if (animator != null) {
            final ValueAnimator was = animator;
            animator = null;
            was.cancel();
        }
        closing = target < progress;
        // closing goes back from where the panel stands spread, not through its settling
        final float from = closing ? Math.min(progress, SPREAD) : progress;
        progress = from;
        final ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        // Closing, as on iOS: about a third of the time the panel draws in to the drop (the list
        // and the dark going at once), the rest the drop draws in steadily into the pill
        final float spreadPart = closing ? Math.max(0f, from - DROP) : 0f;
        final long duration = closing
                ? Math.round(CLOSE_MS * (spreadPart > 0f ? CLOSE_SPREAD_TIME + (1f - CLOSE_SPREAD_TIME) * Math.min(from, DROP) / DROP
                        : (1f - CLOSE_SPREAD_TIME) * from / DROP))
                : Math.round(OPEN_MS * (target - from));
        next.setDuration(Math.max(1, duration));
        next.setInterpolator(new LinearInterpolator());
        next.addUpdateListener(a -> {
            final float time = (float) a.getAnimatedValue();
            if (!closing) {
                progress = lerp(from, target, time);
            } else if (spreadPart > 0f) {
                final float split = CLOSE_SPREAD_TIME / (CLOSE_SPREAD_TIME + (1f - CLOSE_SPREAD_TIME) * Math.min(from, DROP) / DROP);
                progress = time < split ? from - spreadPart * time / split : Math.min(from, DROP) * (1f - (time - split) / (1f - split));
            } else {
                progress = from * (1f - time);
            }
            updateAnchor();
            invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != animation) return;
                animator = null;
                progress = target;
                updateAnchor();
                if (progress <= 0f) {
                    setVisibility(GONE);
                    anchor = null;
                }
                invalidate();
            }
        });
        animator = next;
        next.start();
    }

    /** The pill gives its glass to the drop: hidden while the drop is out of it. */
    private void updateAnchor() {
        if (anchor != null) anchor.setMenuHide(smoothstep(0f, .1f, progress));
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        final float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    /** The panel's own color, darker than the page's on a dark one, lighter on a light one; sets {@link #dark}. */
    private int panelColor() {
        final int base = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider) | 0xFF000000;
        dark = ColorUtils.calculateLuminance(base) < .5f;
        return dark ? ColorUtils.blendARGB(base, Color.BLACK, .35f) : ColorUtils.blendARGB(base, Color.WHITE, .25f);
    }

    private float contentHeight() {
        final int count = delegate != null ? delegate.getTrackCount() : 0;
        return dp(PAD_TOP_DP) + count * dp(ROW_DP) + dp(DIVIDER_DP) + dp(FOOTER_DP) + dp(PAD_BOTTOM_DP);
    }

    private float maxScroll() {
        return Math.max(0f, contentHeight() - panel.height());
    }

    /** Lays the glass out for how far it is open, 0 to 1; false when there is nothing to draw. */
    private boolean layout(float p, int color) {
        if (anchor == null) return false;
        anchor.getPillRect(pill);
        if (pill.isEmpty()) return false;
        pill.offset(anchor.getX() - getX(), anchor.getY() - getY());
        final float width = getWidth();
        final float panelWidth = Math.max(pill.width(), Math.min(width * .66f, width - pill.left - dp(12)));
        final float top = pill.top - dp(2);
        final float maxHeight = getHeight() - top - dp(24) - AndroidUtilities.navigationBarHeight;
        final float panelHeight = Math.max(Math.min(dp(120), maxHeight), Math.min(contentHeight(), maxHeight));
        panel.set(pill.left, top, pill.left + panelWidth, top + panelHeight);
        scrollY = Math.max(0f, Math.min(scrollY, maxScroll()));

        // The drop: a rounded slab of frosted glass hanging under the middle of the pill, nearly half
        // the screen across opening, as on iOS, and a smaller one closing, that the panel draws in to
        final float cx = pill.centerX();
        final float dropW = Math.max(pill.height() * 2f, Math.min(width * (closing ? .34f : .46f), panelWidth));
        final float dropH = Math.min(dropW * (closing ? 1.18f : 1.48f), Math.max(panelHeight, dropW));
        final float dropTop = pill.top + pill.height() * .6f;
        drop.set(cx - dropW / 2f, dropTop, cx + dropW / 2f, dropTop + dropH);
        final float dropRadius = dropW * .42f;
        final int panelTint = ColorUtils.setAlphaComponent(color, (int) (0xFF * (dark ? .88f : .82f)));
        // Frosted, but clear: what is under it shows through, a little blurred, a little lighter,
        // as through iOS's drop
        final int veil = dark ? 0x14FFFFFF : 0x40FFFFFF;

        shape.rewind();
        if (p < DROP) {
            // opening it swells fast and slows; closing it draws in steadily
            final float t = closing ? p / DROP : dropEase.getInterpolation(p / DROP);
            // The pill draws in to its middle as fast as the drop swells out of it, never wider
            // than the drop, and is gone into it by the time the drop is half grown
            final float w = lerp(pill.height(), dropW, t), h = lerp(pill.height(), dropH, t);
            final float vanish = 1f - smoothstep(.25f, .45f, t);
            final float sh = pill.height() * (1f - .25f * t) * vanish;
            final float sw = Math.min(lerp(pill.width(), w, smoothstep(0f, .3f, t)), Math.max(w, sh)) * vanish;
            source.set(cx - sw / 2f, pill.centerY() - sh / 2f, cx + sw / 2f, pill.centerY() + sh / 2f);
            final float dropY = lerp(pill.top, dropTop, t);
            body.set(cx - w / 2f, dropY, cx + w / 2f, dropY + h);
            bodyRadius = Math.min(w / 2f, lerp(w / 2f, dropRadius, t));
            if (!source.isEmpty()) shape.addRoundRect(source, sh / 2f, sh / 2f, Path.Direction.CW);
            piecePath.rewind();
            piecePath.addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW);
            shape.op(piecePath, Path.Op.UNION);
            // a neck between the pill and the drop hanging from it, as the drop flows back up into it
            if (!source.isEmpty() && GlassMerge.metaball(bridge, bridgeEdges, cx, pill.centerY(), sh / 2f, cx, body.top + bodyRadius, bodyRadius, .5f)) {
                shape.op(bridge, Path.Op.UNION);
            }
            if (source.isEmpty()) {
                // the drop alone: its glass is its own shape, bent a little at its edge
                glassRect.set(body);
                glassRadius = bodyRadius;
                lens = dp(10) * smoothstep(.45f, .75f, t);
            } else {
                shape.computeBounds(shapeBounds, true);
                glassRect.set(shapeBounds);
                glassRadius = 0f;
                lens = 0f;
            }
            tint = veil;
            blur = lerp(dp(3), dp(7), t);
            blurredAlpha = .3f * smoothstep(.4f, 1f, t);
            contentAlpha = 0f;
            contentScale = 1f;
            listLensHeight = listLensAmount = 0f;
        } else if (p < SPREAD) {
            // the drop spreads into the panel: dark at once, the list in it, a little past it and back
            final float u = (p - DROP) / (SPREAD - DROP);
            final float e = closing ? smoothstep(0f, 1f, u) : spreadEase.getInterpolation(u);
            body.set(lerp(drop.left, panel.left, e), lerp(drop.top, panel.top, e),
                    lerp(drop.right, panel.right, e), lerp(drop.bottom, panel.bottom, e));
            bodyRadius = Math.min(lerp(dropRadius, dp(PANEL_RADIUS_DP), Math.min(1f, e)), Math.min(body.width(), body.height()) / 2f);
            shape.addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW);
            glassRect.set(body);
            glassRadius = bodyRadius;
            // bent hard at its edges as it spreads; closing, the bending comes back as it draws in
            lens = closing ? dp(7) + dp(9) * (1f - u) : dp(16) * smoothstep(0f, .5f, u);
            tint = ColorUtils.blendARGB(veil, panelTint, closing ? smoothstep(.35f, 1f, u) : smoothstep(0f, .45f, u));
            blur = lerp(dp(7), dp(10), u);
            blurredAlpha = .3f * (1f - smoothstep(0f, .4f, u));
            // the list shows in it from the start, through the curving edge of the glass
            contentAlpha = closing ? smoothstep(.55f, 1f, u) : smoothstep(0f, .4f, u);
            contentScale = closing ? 1f + .04f * (1f - u) : lerp(1.06f, 1.04f, u);
            listLensHeight = dp(30);
            listLensAmount = closing ? dp(20) * (1f - u) : dp(20);
        } else {
            // spread: the list settles as the bending at the edges eases
            final float settle = settleEase.getInterpolation((p - SPREAD) / (1f - SPREAD));
            body.set(panel);
            bodyRadius = dp(PANEL_RADIUS_DP);
            shape.addRoundRect(body, bodyRadius, bodyRadius, Path.Direction.CW);
            glassRect.set(body);
            glassRadius = bodyRadius;
            lens = lerp(dp(16), dp(7), settle);
            tint = panelTint;
            blur = dp(10);
            blurredAlpha = 0f;
            contentAlpha = 1f;
            contentScale = lerp(1.04f, 1f, settle);
            // the list straightens as the edge's bending eases
            listLensHeight = lerp(dp(30), dp(14), settle);
            listLensAmount = dp(20) * (1f - settle);
        }
        return true;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (progress <= 0f) return;
        final int color = panelColor();
        if (!layout(progress, color)) {
            // the pill is gone (the header folded away): the panel goes with it
            if (open) post(this::dismiss);
            return;
        }
        if (animator != null || !recorded) {
            // what is under the glass, while it moves; still, it keeps what it took last
            hasGlass = glass.begin(canvas);
            recorded = true;
        }
        canvas.save();
        canvas.clipPath(shape);
        if (hasGlass) {
            // the list under the glass too, blurred, while it is a drop and while it settles
            glass.drawPanel(canvas, piece, glassRect, glassRadius, tint, 1f, blur, lens, blurredAlpha > .01f ? blurredList : null);
        } else {
            fillPaint.setColor(ColorUtils.setAlphaComponent(color, (int) (0xFF * .96f)));
            canvas.drawPath(shape, fillPaint);
        }
        if (contentAlpha > 0f) {
            canvas.clipRect(body);
            // through the lens while it bends it; once it has settled, plainly
            if (!(hasGlass && listLensAmount >= .5f && glass.drawLensed(canvas, listPiece, glassRect, glassRadius, listLensHeight, listLensAmount, lensedList))) {
                lensedList.draw(canvas);
            }
        }
        canvas.restore();
        // the glass's light rim, brighter while it is a drop
        rimPaint.setStrokeWidth(dpf2(1.1f));
        rimPaint.setColor(Theme.multAlpha(Color.WHITE, lerp(.3f, dark ? .13f : .5f, smoothstep(DROP, SPREAD, progress))));
        canvas.drawPath(shape, rimPaint);
    }

    private void drawContent(Canvas canvas, float alpha) {
        if (delegate == null) return;
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        final int count = delegate.getTrackCount();
        final float left = panel.left, right = panel.right;
        final float rowHeight = dp(ROW_DP);
        float y = panel.top + dp(PAD_TOP_DP) - scrollY;
        while (rows.size() < count) rows.add(new Row());
        for (int i = 0; i < count; i++, y += rowHeight) {
            if (y + rowHeight < panel.top || y > panel.bottom) continue;
            final MessageObject track = delegate.getTrack(i);
            if (track == null) continue;
            final boolean current = delegate.isCurrentTrack(track);
            if (pressed == i) drawPressed(canvas, left, y, right, y + rowHeight, textColor, alpha);
            // the track playing is ticked in a column of its own before it, as on iOS
            final float x = left + (current ? dp(56) : dp(18));
            final Row row = rows.get(i);
            final float textWidth = right - dp(16) - x;
            if (row.title == null || row.id != track.getId() || row.width != textWidth) {
                // cut with "…" once, as iOS does, not faded out in a layer of its own every draw
                row.id = track.getId();
                row.width = textWidth;
                final CharSequence title = track.getMusicTitle();
                final CharSequence author = track.getMusicAuthor();
                row.title = new Text(TextUtils.ellipsize(TextUtils.isEmpty(title) ? LocaleController.getString(R.string.AudioUnknownTitle) : title,
                        titlePaint, Math.max(0f, textWidth), TextUtils.TruncateAt.END), 15.5f);
                row.subtitle = new Text(TextUtils.ellipsize(TextUtils.isEmpty(author) ? LocaleController.getString(R.string.AudioUnknownArtist) : author,
                        subtitlePaint, Math.max(0f, textWidth), TextUtils.TruncateAt.END), 13f);
            }
            if (current) {
                checkPaint.setColor(Theme.multAlpha(textColor, alpha));
                canvas.save();
                canvas.translate(left + dp(24), y + rowHeight / 2f - dpf2(5));
                canvas.drawPath(check, checkPaint);
                canvas.restore();
            }
            row.title.draw(canvas, x, y + dp(19), textColor, alpha);
            row.subtitle.draw(canvas, x, y + dp(37), grayColor, alpha);
        }
        // the line, then the whole playlist
        linePaint.setStrokeWidth(Math.max(1f, dpf2(.66f)));
        linePaint.setColor(Theme.multAlpha(textColor, .12f * alpha));
        final float lineY = y + dp(DIVIDER_DP) / 2f;
        canvas.drawLine(left + dp(18), lineY, right - dp(18), lineY, linePaint);
        y += dp(DIVIDER_DP);
        if (pressed == FOOTER) drawPressed(canvas, left, y, right, y + dp(FOOTER_DP), textColor, alpha);
        final float cy = y + dp(FOOTER_DP) / 2f;
        final int size = dp(22);
        if (folderColor != textColor) {
            folderColor = textColor;
            folderIcon.setColorFilter(new PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN));
        }
        folderIcon.setAlpha((int) (0xFF * alpha));
        folderIcon.setBounds((int) (left + dp(17)), (int) (cy - size / 2f), (int) (left + dp(17)) + size, (int) (cy - size / 2f) + size);
        folderIcon.draw(canvas);
        final float footerX = left + dp(17 + 22 + 16);
        if (footerText == null || footerWidth != right - dp(16) - footerX) {
            footerWidth = right - dp(16) - footerX;
            footerText = new Text(TextUtils.ellipsize(LocaleController.getString(R.string.GlassgramProfileShowPlaylist),
                    titlePaint, Math.max(0f, footerWidth), TextUtils.TruncateAt.END), 15.5f);
        }
        footerText.draw(canvas, footerX, cy, textColor, alpha);
        if (delegate != null && scrollY >= maxScroll() - rowHeight * 2) delegate.loadMore();
    }

    private final RectF pressedRect = new RectF();

    private void drawPressed(Canvas canvas, float left, float top, float right, float bottom, int color, float alpha) {
        pressedRect.set(left + dp(6), top + dp(2), right - dp(6), bottom - dp(2));
        fillPaint.setColor(Theme.multAlpha(color, .09f * alpha));
        canvas.drawRoundRect(pressedRect, dp(16), dp(16), fillPaint);
    }

    /** The row under (x, y): a track's index, FOOTER, or NONE off them and off the panel. */
    private int hit(float x, float y) {
        if (!panel.contains(x, y) || delegate == null) return NONE;
        final float inside = y - panel.top - dp(PAD_TOP_DP) + scrollY;
        final int count = delegate.getTrackCount();
        if (inside >= 0 && inside < count * dp(ROW_DP)) return (int) (inside / dp(ROW_DP));
        final float footerTop = count * dp(ROW_DP) + dp(DIVIDER_DP);
        if (inside >= footerTop && inside < footerTop + dp(FOOTER_DP)) return FOOTER;
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getVisibility() != VISIBLE || progress <= 0f) return false;
        final float x = event.getX(), y = event.getY();
        final boolean settled = open && animator == null;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                dragging = false;
                scrollFrom = scrollY;
                pressed = settled ? hit(x, y) : NONE;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(y - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                    dragging = true;
                    pressed = NONE;
                    invalidate();
                }
                if (dragging && settled && panel.contains(downX, downY)) {
                    scrollY = Math.max(0f, Math.min(maxScroll(), scrollFrom - (y - downY)));
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP: {
                final int was = pressed;
                pressed = NONE;
                if (!dragging) {
                    final int at = settled ? hit(x, y) : NONE;
                    if (at != NONE && at == was) {
                        click(at);
                    } else if (!panel.contains(x, y) || !settled) {
                        dismiss();
                    }
                }
                dragging = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressed = NONE;
                dragging = false;
                invalidate();
                return true;
        }
        return true;
    }

    private void click(int at) {
        if (delegate == null) return;
        if (at == FOOTER) {
            delegate.onShowPlaylist();
        } else if (at >= 0 && at < delegate.getTrackCount()) {
            final MessageObject track = delegate.getTrack(at);
            if (track != null) delegate.onTrackClick(track);
        }
        dismiss();
    }
}
