package io.github.howard20181.hyperos.fcmlive;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.CompoundButton;

/** A capsule with explicit, mirrored endpoints, independent of OEM Switch drawable metrics. */
public final class CapsuleSwitch extends CompoundButton {
    static final long SETTLE_DURATION_MS = 350;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator colors = new ArgbEvaluator();
    private float position;
    private ValueAnimator animator;

    public CapsuleSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        setButtonDrawable(null);
        setBackground(null);
        setPadding(0, 0, 0, 0);
        setMinWidth(0);
        setMinHeight(0);
        setFocusable(true);
        setClickable(true);
        position = isChecked() ? 1f : 0f;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize(Math.round(dp(49)), widthSpec),
                resolveSize(Math.round(dp(48)), heightSpec));
    }

    @Override public CharSequence getAccessibilityClassName() { return "android.widget.Switch"; }

    @Override public void setChecked(boolean checked) {
        boolean changed = checked != isChecked();
        super.setChecked(checked);
        // CompoundButton's constructor and change listener can both call back into this method.
        if (paint == null || !changed) return;
        if (animator != null) animator.cancel();
        float target = isChecked() ? 1f : 0f;
        if (!isAttachedToWindow() || !isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            position = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(SETTLE_DURATION_MS);
        // Miuix upstream Switch.kt uses dampingRatio=0.7 and stiffness=987 for translation.
        // https://github.com/compose-miuix-ui/miuix/blob/main/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/Switch.kt
        // Evaluate its unit-mass spring response; normalize the end so it settles exactly.
        animator.setInterpolator(fraction -> spring(fraction) / spring(1f));
        animator.addUpdateListener(value -> {
            position = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private static float spring(float fraction) {
        double omega = Math.sqrt(987);
        double damping = 0.7;
        double damped = omega * Math.sqrt(1 - damping * damping);
        double time = fraction * SETTLE_DURATION_MS / 1000.0;
        return (float) (1 - Math.exp(-damping * omega * time)
                * (Math.cos(damped * time) + damping * omega / damped * Math.sin(damped * time)));
    }

    @Override public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        position = isChecked() ? 1f : 0f;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        jumpDrawablesToCurrentState();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = Math.min(dp(49), getWidth());
        float height = Math.min(dp(28), getHeight());
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        float radius = height / 2f;
        float progress = Math.max(0, Math.min(1, position));
        paint.setColor((int) colors.evaluate(progress, getContext().getColor(R.color.switch_off),
                getContext().getColor(R.color.accent)));
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, paint);
        float visualProgress = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1 - progress : progress;
        // Miuix proportions: 49x28dp track, 20dp thumb; both endpoints share the cap centers.
        float center = left + radius + (width - height) * visualProgress;
        paint.setColor(getContext().getColor(R.color.switch_thumb));
        canvas.drawCircle(center, top + radius, Math.min(dp(10), radius), paint);
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
