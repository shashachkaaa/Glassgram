package org.telegram.liquidglass;

import android.graphics.RuntimeShader;

import androidx.annotation.NonNull;
import androidx.compose.ui.graphics.RectangleShapeKt;
import androidx.compose.ui.graphics.Shape;
import androidx.compose.ui.graphics.drawscope.DrawScope;

import com.kyant.backdrop.BackdropEffectScope;
import com.kyant.backdrop.BackdropEffectScopeImpl;
import com.kyant.backdrop.RuntimeShaderCache;

/**
 * Effect scope of Kyant0/AndroidLiquidGlass used outside of Compose.
 * <p>
 * The library only creates its {@link BackdropEffectScope} inside the {@code drawBackdrop}
 * modifier. Telegram's glass is drawn by plain {@code RenderNode}s, so we keep our own scope
 * and feed it to the very same effect functions ({@code colorControls}, {@code blur},
 * {@code lens}) and highlight shaders. The scope is written in Java because the library's
 * base implementation is Kotlin-internal; Kotlin code only ever sees it through
 * {@link #asScope} and {@link #asShaderCache}.
 */
final class GlassEffectScope extends BackdropEffectScopeImpl {

    private Shape shape = RectangleShapeKt.getRectangleShape();

    /*
     * One compiled shader of each kind (the lens, the highlights) for all the glass of the app.
     * The library keeps a cache per scope, and every glass has its own scope: a screen with a
     * dozen pieces of glass (a chat's header, input, buttons) parsed the same shaders a dozen
     * times as it opened. Sharing them is safe: an effect made from a shader keeps the uniforms
     * it was made with (Skia copies them on the next write), and a highlight's paint takes its
     * shader as it is when drawn. All of it happens on the main thread.
     */
    private static final java.util.HashMap<String, RuntimeShader> sharedShaders = new java.util.HashMap<>();

    @NonNull
    @Override
    public RuntimeShader obtainRuntimeShader(@NonNull String key, @NonNull String string) {
        RuntimeShader shader = sharedShaders.get(string);
        if (shader == null) {
            shader = new RuntimeShader(string);
            sharedShaders.put(string, shader);
        }
        return shader;
    }

    @Override
    public Shape getShape() {
        return shape;
    }

    static Object create() {
        return new GlassEffectScope();
    }

    static BackdropEffectScope asScope(Object scope) {
        return (GlassEffectScope) scope;
    }

    static RuntimeShaderCache asShaderCache(Object scope) {
        return (GlassEffectScope) scope;
    }

    /** Takes size, density and layout direction from {@code drawScope}. */
    static void update(Object scope, DrawScope drawScope) {
        ((GlassEffectScope) scope).update(drawScope);
    }

    static void setShape(Object scope, Shape shape) {
        ((GlassEffectScope) scope).shape = shape;
    }

    static void reset(Object scope) {
        GlassEffectScope s = (GlassEffectScope) scope;
        s.setPadding(0f);
        s.setRenderEffect(null);
    }
}
