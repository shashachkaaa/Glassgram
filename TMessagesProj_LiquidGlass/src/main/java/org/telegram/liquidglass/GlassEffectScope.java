package org.telegram.liquidglass;

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
