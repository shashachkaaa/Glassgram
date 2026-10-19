package org.telegram.ui.Components;

import android.view.View;
import android.view.ViewTreeObserver;

/**
 * glassGram: a pre-draw listener that runs only while its view is in a window.
 * <p>
 * A listener added to {@code view.getViewTreeObserver()} goes to the window's observer, which lives
 * as long as the activity. Never removed, it keeps the view and all its listener holds (a whole
 * screen, a chat with its messages) for good: every profile or rebuilt screen added one more.
 * Here it is added when the view is attached and removed when it is detached.
 */
public final class GlassgramPreDraw {

    private GlassgramPreDraw() {
    }

    public static void whileAttached(View view, ViewTreeObserver.OnPreDrawListener listener) {
        final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
            private ViewTreeObserver tree;

            @Override
            public void onViewAttachedToWindow(View v) {
                stop();
                tree = v.getViewTreeObserver();
                tree.addOnPreDrawListener(listener);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stop();
            }

            private void stop() {
                if (tree != null && tree.isAlive()) {
                    tree.removeOnPreDrawListener(listener);
                }
                tree = null;
            }
        };
        view.addOnAttachStateChangeListener(attach);
        if (view.isAttachedToWindow()) {
            attach.onViewAttachedToWindow(view);
        }
    }
}
