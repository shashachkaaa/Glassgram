package org.telegram.ui.Components;

import android.view.animation.Interpolator;

/**
 * glassGram: a damped spring squeezed into an animator's 0..1, so iOS-like springs keep the
 * animator API (durations, listeners, cancel). Below 1 the damping ratio overshoots a little
 * before settling; the spring has settled by the end of the animation.
 */
public class GlassgramSpringInterpolator implements Interpolator {

    private final float damping;
    private final float omega;
    private final float omegaD;

    public GlassgramSpringInterpolator(float dampingRatio) {
        damping = Math.max(0.05f, Math.min(0.99f, dampingRatio));
        // The envelope e^(-damping * omega * t) is down to 0.1% at t = 1
        omega = 6.9f / damping;
        omegaD = omega * (float) Math.sqrt(1 - damping * damping);
    }

    @Override
    public float getInterpolation(float t) {
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        final float decay = (float) Math.exp(-damping * omega * t);
        return 1 - decay * ((float) Math.cos(omegaD * t) + damping * omega / omegaD * (float) Math.sin(omegaD * t));
    }
}
