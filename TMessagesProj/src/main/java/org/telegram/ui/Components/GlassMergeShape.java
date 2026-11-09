package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.telegram.liquidglass.LiquidButtonView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

/**
 * glassGram: two glass buttons flowing into one, as iOS's liquid pieces do (Android 13+).
 * <p>
 * The pieces and the bridge between them are one shape, the smooth union of the two capsules,
 * growing with how far they have come together: one outline with one rim, the pieces' own edges
 * where they meet gone into it, and their colors poured into each other over a band as wide as
 * the merge is strong. The glass is the library's LiquidButton's (Kyant0/AndroidLiquidGlass): the
 * same vibrancy, blur, lens and highlight, by the library's own formulas, over what the buttons'
 * glass is made of. It is drawn over the two buttons near where they meet and fades out further
 * along them, where they are their own LiquidButtons again.
 */
@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
final class GlassMergeShape {

    /** Whether the merged shape can be drawn here: the library's buttons are, on a hardware canvas. */
    static boolean isSupported(Canvas canvas) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated()
            && LiquidButtonView.isSupported() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
    }

    // The catalog's LiquidButton: vibrancy(), blur(2dp), lens(12dp, 24dp), the default highlight
    private static final float BLUR_DP = 2f;
    private static final float REFRACTION_HEIGHT_DP = 12f;
    private static final float REFRACTION_AMOUNT_DP = 24f;
    private static final float RIM_DP = 1.5f;
    /** What the lens and the blur read past the shape. */
    private static final float MARGIN_DP = REFRACTION_AMOUNT_DP + BLUR_DP * 3;

    private static final String SHADER =
        "uniform shader content;\n" +
        "uniform float4 rectA;\n" +
        "uniform float radiusA;\n" +
        "uniform float4 rectB;\n" +
        "uniform float radiusB;\n" +
        "uniform float k;\n" +
        "uniform float blendWidth;\n" +
        "layout(color) uniform half4 tintA;\n" +
        "layout(color) uniform half4 tintB;\n" +
        "layout(color) uniform half4 surfaceA;\n" +
        "layout(color) uniform half4 surfaceB;\n" +
        "uniform float refractionHeight;\n" +
        "uniform float refractionAmount;\n" +
        "uniform float rimWidth;\n" +
        "uniform float3 zone;\n" +
        "uniform float2 inner;\n" +
        "uniform float alpha;\n" +
        // the library's rounded rect, as in its shaders
        "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
        "    float outside = length(max(cornerCoord, 0.0)) - radius;\n" +
        "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n" +
        "    return outside + inside;\n" +
        "}\n" +
        "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n" +
        "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n" +
        "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n" +
        "        return sign(coord) * normalize(max(cornerCoord, 0.0001));\n" +
        "    } else {\n" +
        "        float gradX = step(cornerCoord.y, cornerCoord.x);\n" +
        "        return sign(coord) * float2(gradX, 1.0 - gradX);\n" +
        "    }\n" +
        "}\n" +
        "float circleMap(float x) {\n" +
        "    return 1.0 - sqrt(1.0 - x * x);\n" +
        "}\n" +
        "half3 tinted(half3 c, half4 tint) {\n" +
        // the catalog's tint: the tint's hue over the glass (BlendMode.Hue), then the tint at 75%
        "    if (tint.a <= 0.0) return c;\n" +
        "    half3 w = half3(0.2126, 0.7152, 0.0722);\n" +
        "    half3 hue = clamp(tint.rgb * (dot(c, w) / max(dot(tint.rgb, w), 0.001)), 0.0, 1.0);\n" +
        "    c = mix(c, hue, tint.a);\n" +
        "    return mix(c, tint.rgb, 0.75 * tint.a);\n" +
        "}\n" +
        "half4 mixColor(half4 a, half4 b, half t) {\n" +
        "    half aa = mix(a.a, b.a, t);\n" +
        "    half3 rgb = mix(a.rgb * a.a, b.rgb * b.a, t) / max(aa, 0.001);\n" +
        "    return half4(rgb, aa);\n" +
        "}\n" +
        "half4 main(float2 coord) {\n" +
        "    float2 cA = (rectA.xy + rectA.zw) * 0.5, hA = (rectA.zw - rectA.xy) * 0.5;\n" +
        "    float2 cB = (rectB.xy + rectB.zw) * 0.5, hB = (rectB.zw - rectB.xy) * 0.5;\n" +
        "    float rA = min(radiusA, min(hA.x, hA.y)), rB = min(radiusB, min(hB.x, hB.y));\n" +
        "    float dA = sdRoundedRect(coord - cA, hA, rA);\n" +
        "    float dB = sdRoundedRect(coord - cB, hB, rB);\n" +
        // the smooth union: A's weight h
        "    float h = clamp(0.5 + 0.5 * (dB - dA) / k, 0.0, 1.0);\n" +
        "    float d = mix(dB, dA, h) - k * h * (1.0 - h);\n" +
        "    float cover = clamp(0.5 - d, 0.0, 1.0);\n" +
        "    if (cover <= 0.0) return half4(0.0);\n" +
        // near where they meet the merged glass is the glass; along the pieces it fades into them
        "    float mask = 1.0 - smoothstep(zone.y, zone.z, abs(coord.x - zone.x));\n" +
        // a piece whose contents are drawn already keeps them: only its edge goes into the merge
        "    float band = rimWidth * 2.5;\n" +
        "    float keepA = (1.0 - inner.x) * (1.0 - smoothstep(-band - 1.5, -band, dA));\n" +
        "    float keepB = (1.0 - inner.y) * (1.0 - smoothstep(-band - 1.5, -band, dB));\n" +
        "    mask *= 1.0 - max(keepA, keepB);\n" +
        "    if (mask <= 0.0) return half4(0.0);\n" +
        "    float2 gA = gradSdRoundedRect(coord - cA, hA, min(rA * 1.5, min(hA.x, hA.y)));\n" +
        "    float2 gB = gradSdRoundedRect(coord - cB, hB, min(rB * 1.5, min(hB.x, hB.y)));\n" +
        "    float2 g = mix(gB, gA, h);\n" +
        "    g = length(g) > 0.0001 ? normalize(g) : float2(0.0, 1.0);\n" +
        // the library's lens, along the merged outline
        "    float sd = min(d, 0.0);\n" +
        "    float2 sc = coord;\n" +
        "    if (-sd < refractionHeight) {\n" +
        "        sc = coord + circleMap(1.0 - -sd / refractionHeight) * refractionAmount * g;\n" +
        "    }\n" +
        "    half4 c = content.eval(sc);\n" +
        // the two buttons' colors, poured into each other
        "    half t = half(smoothstep(-blendWidth, blendWidth, dA - dB));\n" +
        "    half3 rgb = tinted(c.rgb, mixColor(tintA, tintB, t));\n" +
        "    half4 surface = mixColor(surfaceA, surfaceB, t);\n" +
        "    rgb = mix(rgb, surface.rgb, surface.a);\n" +
        // the library's default highlight: white at half, lit at 45 degrees, along the outline
        "    half rim = half(1.0 - smoothstep(0.0, rimWidth, -d));\n" +
        "    half light = half(abs(dot(g, float2(0.70710678, 0.70710678))));\n" +
        "    rgb = min(rgb + 0.5 * rim * light, 1.0);\n" +
        "    return half4(rgb, 1.0) * half(cover * mask * alpha);\n" +
        "}\n";

    private static RuntimeShader sharedShader;
    private static boolean shaderFailed;
    private static ColorMatrixColorFilter vibrancy;

    private final RenderNode node = new RenderNode("GlassMerge");
    private RenderEffect blurEffect;
    private float blurRadius = -1;

    /** The shader, compiled once for all the merges; null if this Android can't compile it (the bridge is drawn then). */
    private static RuntimeShader shader() {
        if (sharedShader == null && !shaderFailed) {
            try {
                sharedShader = new RuntimeShader(SHADER);
            } catch (Throwable e) {
                shaderFailed = true;
                org.telegram.messenger.FileLog.e(e);
            }
        }
        return sharedShader;
    }

    private static ColorMatrixColorFilter vibrancy() {
        if (vibrancy == null) {
            // the catalog's vibrancy(): the colors under the glass stronger
            final ColorMatrix matrix = new ColorMatrix();
            matrix.setSaturation(1.5f);
            vibrancy = new ColorMatrixColorFilter(matrix);
        }
        return vibrancy;
    }

    /**
     * Draws the merged glass of {@code a} and {@code b} (left before right, capsules, in the canvas'
     * coordinates) as far as they have merged ({@code strength}, 0 to 1, their {@code gap} between them),
     * made of what {@code glass} is made of. A piece whose {@code inner} is false has its contents drawn
     * already: the merge goes only over its edge. False when there is nothing to draw it from.
     */
    boolean draw(Canvas canvas, RectF a, RectF b, float gap, float strength, BlurredBackgroundDrawable glass,
                 int tintA, int tintB, int surfaceA, int surfaceB, boolean innerA, boolean innerB) {
        final RuntimeShader shader = shader();
        // the glass picks its source for the settings as they are now
        final boolean hasDisplayList = !glass.shouldHaveDisplayList() || glass.hasDisplayList();
        final GlassSource source = glass.getUnwrappedSource();
        if (shader == null || source == null) {
            return false;
        }
        if (source instanceof GlassSourceRenderNode) {
            final GlassSourceRenderNode renderNode = (GlassSourceRenderNode) source;
            if (renderNode.inRecording() || renderNode.isRecordingCanvas(canvas)) {
                // drawn into the very picture it is made of
                return true;
            }
        }
        final float margin = AndroidUtilities.dpf2(MARGIN_DP);
        final float left = Math.min(a.left, b.left) - margin, top = Math.min(a.top, b.top) - margin;
        final float right = Math.max(a.right, b.right) + margin, bottom = Math.max(a.bottom, b.bottom) + margin;
        final int width = (int) Math.ceil(right - left), height = (int) Math.ceil(bottom - top);
        if (width <= 0 || height <= 0) {
            return false;
        }

        // The glass keeps the capture of what is under it there, as a drawn one would
        final int out = AndroidUtilities.dp(4);
        glass.setBounds(Math.round(Math.min(a.left, b.left)) - out, Math.round(Math.min(a.top, b.top)) - out,
            Math.round(Math.max(a.right, b.right)) + out, Math.round(Math.max(a.bottom, b.bottom)) + out);
        glass.setAlpha(255);
        if (!hasDisplayList) {
            glass.updateDisplayList();
        }

        // What is under the glass, as the buttons have it (LiquidPressEffect's source)
        final float sx = left + glass.getSourceOffsetX(), sy = top + glass.getSourceOffsetY();
        node.setPosition(0, 0, width, height);
        final Canvas c = node.beginRecording(width, height);
        c.translate(-sx, -sy);
        source.draw(c, sx, sy, sx + width, sy + height);
        node.endRecording();

        try {
            setUniforms(shader, a, b, left, top, gap, strength, tintA, tintB, surfaceA, surfaceB, innerA, innerB);
        } catch (Throwable e) {
            // an Android whose shaders take these uniforms otherwise: the bridge, from now on
            shaderFailed = true;
            sharedShader = null;
            org.telegram.messenger.FileLog.e(e);
            return false;
        }

        final float blur = AndroidUtilities.dpf2(BLUR_DP);
        if (blurEffect == null || blurRadius != blur) {
            blurRadius = blur;
            blurEffect = RenderEffect.createBlurEffect(blur, blur,
                RenderEffect.createColorFilterEffect(vibrancy()), Shader.TileMode.CLAMP);
        }
        node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"), blurEffect));

        canvas.save();
        canvas.translate(left, top);
        canvas.drawRenderNode(node);
        canvas.restore();
        return true;
    }

    private static void setUniforms(RuntimeShader shader, RectF a, RectF b, float left, float top, float gap, float strength,
                                    int tintA, int tintB, int surfaceA, int surfaceB, boolean innerA, boolean innerB) {
        final float minRadius = Math.min(a.height(), b.height()) / 2f;
        final float distance = (b.centerX() - a.centerX());
        shader.setFloatUniform("rectA", a.left - left, a.top - top, a.right - left, a.bottom - top);
        shader.setFloatUniform("radiusA", a.height() / 2f);
        shader.setFloatUniform("rectB", b.left - left, b.top - top, b.right - left, b.bottom - top);
        shader.setFloatUniform("radiusB", b.height() / 2f);
        // The union's bridge: just across the gap as they start to join, a wide fillet once they touch
        shader.setFloatUniform("k", Math.max(1f, Math.max(gap, 0f) * 1.1f + strength * minRadius * 0.9f));
        // The colors mix over a band as wide as the merge is strong: all over the two once fully merged
        shader.setFloatUniform("blendWidth", Math.max(AndroidUtilities.dpf2(2), strength * distance));
        shader.setColorUniform("tintA", tintA);
        shader.setColorUniform("tintB", tintB);
        shader.setColorUniform("surfaceA", surfaceA);
        shader.setColorUniform("surfaceB", surfaceB);
        shader.setFloatUniform("refractionHeight", AndroidUtilities.dpf2(REFRACTION_HEIGHT_DP));
        shader.setFloatUniform("refractionAmount", AndroidUtilities.dpf2(REFRACTION_AMOUNT_DP));
        shader.setFloatUniform("rimWidth", AndroidUtilities.dpf2(RIM_DP));
        final float contactX = (a.right + b.left) / 2f - left;
        final float zoneInner = Math.max(gap, 0f) / 2f + minRadius * (0.6f + strength);
        shader.setFloatUniform("zone", contactX, zoneInner, zoneInner + minRadius * 0.5f);
        shader.setFloatUniform("inner", innerA ? 1f : 0f, innerB ? 1f : 0f);
        shader.setFloatUniform("alpha", Math.min(1f, strength * 4f));
    }
}
