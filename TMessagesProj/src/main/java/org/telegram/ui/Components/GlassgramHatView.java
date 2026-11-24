package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * glassGram: the New Year hat bought for an avatar in the bot (GlassgramBadges.getHat), worn in the
 * winter look (GlassgramWinter). A Santa hat sits on the top left of the avatar's circle, its fur
 * brim bowed around the head, its tip flopping to the right with a pompom, and its shadow on the
 * photo. It is drawn at any size from the circle's radius.
 * <p>
 * The view lies over the avatar's screen and follows it wherever the avatar goes (scrolled, opened
 * from a chat): before every frame it asks its Source where the avatar is, and draws again only
 * when that changed. It takes no touches.
 */
public class GlassgramHatView extends View {

    /** Where the avatar is: fills out (the circle's center x, y and its radius, in this view) and returns the hat's alpha, 0 for none. */
    public interface Source {
        float place(float[] out);
    }

    private final Source source;
    private final float[] place = new float[3];
    private float drawnX, drawnY, drawnR, drawnAlpha;
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        checkMoved();
        return true;
    };

    public GlassgramHatView(Context context, Source source) {
        super(context);
        this.source = source;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    private void checkMoved() {
        final float alpha = source.place(place);
        if (Math.abs(alpha - drawnAlpha) > .005f || alpha > 0f && (Math.abs(place[0] - drawnX) > .3f
                || Math.abs(place[1] - drawnY) > .3f || Math.abs(place[2] - drawnR) > .3f)) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float alpha = Math.max(0f, Math.min(1f, source.place(place)));
        drawnX = place[0];
        drawnY = place[1];
        drawnR = place[2];
        drawnAlpha = alpha;
        if (alpha > .01f && drawnR > 1f) {
            draw(canvas, drawnX, drawnY, drawnR, alpha);
        }
    }

    // --- The hat ---------------------------------------------------------------------------------
    // Drawn in its own frame: the avatar's radius is 1, the middle of the brim is at (0, 0), y down

    private static final float BOW = .10f, BRIM_HALF = .78f, BRIM_HALF_HEIGHT = .15f;
    // seated on the circle's top left, along it
    private static final double SEAT_ANGLE = Math.toRadians(-110);
    private static final float SEAT_DISTANCE = .80f, TILT = -18f;

    private static Path body, fur;
    private static Paint shadowPaint, bodyPaint, foldPaint, lightPaint, brimShadePaint, pompomPaint, furPaint, tuftPaint;
    private static final Path tufts = new Path();

    private static float brimY(float x) {
        final float t = x / BRIM_HALF;
        return -BOW * (1f - t * t);
    }

    private static void prepare() {
        if (body != null) {
            return;
        }
        body = new Path();
        body.moveTo(-.62f, brimY(-.62f) - .02f);
        // up the left side to the top, over, flopping to the right, under the flop and down the right side
        body.cubicTo(-.6f, -.6f, -.32f, -1.02f, .08f, -1.06f);
        body.cubicTo(.48f, -1.09f, .76f, -.9f, .88f, -.52f);
        body.cubicTo(.8f, -.64f, .62f, -.72f, .46f, -.68f);
        body.cubicTo(.5f, -.44f, .56f, -.22f, .62f, brimY(.62f) - .02f);
        // and along the brim's bowed top
        body.quadTo(0f, -BOW * 2f - .02f, -.62f, brimY(-.62f) - .02f);
        body.close();

        // the fur of the brim: one bumpy shape of overlapping tufts
        fur = new Path();
        final float[] k = {1f, .86f, .95f, .8f, 1f, .9f, .84f, .97f, .88f, 1f, .82f, .93f, .87f, .98f, 1f};
        final int n = 14;
        final float h = BRIM_HALF_HEIGHT;
        for (int i = 0; i <= n; i++) {
            final float x = -BRIM_HALF + h + (2 * BRIM_HALF - 2 * h) * i / n, y = brimY(x);
            fur.addCircle(x, y - h * .25f, h * k[i] * .95f, Path.Direction.CW);
            fur.addCircle(x + .02f, y + h * .25f, h * .9f * k[i], Path.Direction.CW);
            if (i % 3 == 1) {
                tufts.addCircle(x, y - h * .55f, h * .28f, Path.Direction.CW);
            }
        }

        shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RadialGradient shadow = new RadialGradient(0f, 0f, 1f, new int[]{0x55000000, 0x40000000, 0x00000000}, new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP);
        final Matrix m = new Matrix();
        m.setScale(.8f, .24f);
        m.postTranslate(0f, .13f);
        shadow.setLocalMatrix(m);
        shadowPaint.setShader(shadow);

        bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setShader(new LinearGradient(-.6f, -1f, .6f, 0f, new int[]{0xFFF0473F, 0xFFD32F2F, 0xFF9F1C1C}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        // the shade under the flop
        foldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        foldPaint.setShader(new RadialGradient(.62f, -.64f, .46f, 0x8C460000, 0x00460000, Shader.TileMode.CLAMP));
        // a soft light down the left
        lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lightPaint.setShader(new LinearGradient(-.62f, 0f, -.12f, 0f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        // the brim's shadow on the body
        brimShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        brimShadePaint.setShader(new LinearGradient(0f, -.36f, 0f, -.1f, 0x003C0000, 0x593C0000, Shader.TileMode.CLAMP));

        pompomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pompomPaint.setShader(new RadialGradient(.86f, -.56f, .21f, new int[]{0xFFFFFFFF, 0xFFF2F5F9, 0xFFC5D0DD}, new float[]{0f, .65f, 1f}, Shader.TileMode.CLAMP));
        furPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        furPaint.setShader(new LinearGradient(0f, -h - BOW, 0f, h, new int[]{0xFFFFFFFF, 0xFFF4F7FA, 0xFFC9D3DF}, new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        tuftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tuftPaint.setColor(0xE6FFFFFF);
    }

    /** Draws the hat on the avatar's circle (center cx, cy, radius r) at this alpha. */
    public static void draw(Canvas canvas, float cx, float cy, float r, float alpha) {
        prepare();
        final float bx = cx + (float) Math.cos(SEAT_ANGLE) * r * SEAT_DISTANCE;
        final float by = cy + (float) Math.sin(SEAT_ANGLE) * r * SEAT_DISTANCE;
        final int saved;
        if (alpha < 1f) {
            // one layer: the parts lying over each other mustn't show through as it fades
            saved = canvas.saveLayerAlpha(bx - r * 1.4f, by - r * 1.4f, bx + r * 1.4f, by + r * .7f, (int) (255 * alpha));
        } else {
            saved = canvas.save();
        }
        canvas.translate(bx, by);
        canvas.rotate(TILT);
        canvas.scale(r, r);

        canvas.drawOval(-.8f, -.11f, .8f, .37f, shadowPaint);

        canvas.drawPath(body, bodyPaint);
        canvas.save();
        canvas.clipPath(body);
        canvas.drawRect(-1f, -1.2f, 1f, .2f, foldPaint);
        canvas.drawRect(-1f, -1.2f, 1f, .2f, lightPaint);
        canvas.drawRect(-1f, -.4f, 1f, 0f, brimShadePaint);
        canvas.restore();

        canvas.drawCircle(.9f, -.5f, .17f, pompomPaint);
        canvas.drawPath(fur, furPaint);
        canvas.drawPath(tufts, tuftPaint);

        canvas.restoreToCount(saved);
    }
}
