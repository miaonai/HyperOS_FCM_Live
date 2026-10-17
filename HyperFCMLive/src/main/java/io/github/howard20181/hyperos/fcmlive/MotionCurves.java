package io.github.howard20181.hyperos.fcmlive;

import android.animation.TimeInterpolator;
import android.view.animation.PathInterpolator;

/** Shared, bounded motion; system animator duration settings still apply. */
final class MotionCurves {
    private MotionCurves() {}
    // Critical damping with stiffness 400, matching the scope page's medium-low placement spring.
    static final TimeInterpolator PLACEMENT = fraction -> {
        double time = fraction * 5.2;
        return (float) ((1 - (1 + time) * Math.exp(-time)) / (1 - 6.2 * Math.exp(-5.2)));
    };
    static final TimeInterpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);
}
