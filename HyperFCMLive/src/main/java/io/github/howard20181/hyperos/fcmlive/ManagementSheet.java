package io.github.howard20181.hyperos.fcmlive;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bottom-attached modal surface, with no dim layer and a single enter/exit animation owner. */
final class ManagementSheet extends AlertDialog {
    private boolean closing;
    private boolean dragging;
    private float downX;
    private float downY;
    private boolean scrollingContent;
    private ViewTreeObserver.OnPreDrawListener entrance;

    ManagementSheet(Activity activity) {
        super(activity);
        Window window = getWindow();
        if (window == null) return;
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        window.setDimAmount(0);
        if (Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(0);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(0);
            window.setAttributes(attributes);
        }
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        GradientDrawable background = new GradientDrawable();
        background.setColor(activity.getColor(R.color.surface));
        background.setCornerRadius(dp(28));
        window.setBackgroundDrawable(background);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        int available = activity.findViewById(R.id.root).getWidth();
        if (available <= 0) available = activity.getResources().getDisplayMetrics().widthPixels;
        attributes.width = Math.min(dp(420), Math.max(dp(48), available - dp(24)));
        attributes.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        // Floating windows already exclude system navigation; keep the outside margin inside it.
        attributes.y = dp(12);
        window.setAttributes(attributes);
    }

    @Override protected void onStart() {
        super.onStart();
        styleControls();
        View panel = getWindow().getDecorView();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        // Hide before the first frame; dimensions and background are fixed before show().
        panel.setAlpha(0);
        entrance = () -> {
            panel.getViewTreeObserver().removeOnPreDrawListener(entrance);
            entrance = null;
            if (closing) return true;
            panel.setTranslationY(panel.getHeight() + dp(12));
            panel.setAlpha(1);
            panel.animate().translationY(0).setDuration(300)
                    .setInterpolator(MotionCurves.PLACEMENT).start();
            return true;
        };
        panel.getViewTreeObserver().addOnPreDrawListener(entrance);
    }

    private void styleControls() {
        int titleId = getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : getWindow().findViewById(titleId);
        if (title != null) title.setGravity(Gravity.CENTER);

        Button positive = getButton(BUTTON_POSITIVE);
        Button negative = getButton(BUTTON_NEGATIVE);
        int accent = getContext().getColor(R.color.accent);
        int primaryText = getContext().getColor(R.color.text_primary);
        int neutral = blend(getContext().getColor(R.color.surface), primaryText, 0.08f);
        if (positive != null) styleButton(positive, accent, Color.WHITE, 0x30FFFFFF);
        if (negative != null) styleButton(negative, neutral, primaryText, 0x18000000);

        View buttonParent = positive != null ? (View) positive.getParent()
                : negative != null ? (View) negative.getParent() : null;
        if (buttonParent instanceof LinearLayout row) {
            row.setOrientation(LinearLayout.HORIZONTAL);
            // The platform alert button bar includes an invisible weighted spacer. Remove it
            // so the retained AlertDialog buttons can share the row evenly.
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child != positive && child != negative) child.setVisibility(View.GONE);
            }
        }
        if (negative != null) sizeButton(negative);
        if (positive != null) sizeButton(positive);
    }

    private void styleButton(Button button, int fill, int text, int ripple) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(16));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        button.setTextColor(text);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setElevation(0);
        button.setStateListAnimator(null);
    }

    private void sizeButton(Button button) {
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams linearParams) {
            linearParams.width = 0;
            linearParams.weight = 1;
            linearParams.setMargins(dp(6), dp(8), dp(6), dp(12));
            button.setLayoutParams(linearParams);
        }
    }

    private int blend(int base, int overlay, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                Math.round(Color.red(base) * inverse + Color.red(overlay) * amount),
                Math.round(Color.green(base) * inverse + Color.green(overlay) * amount),
                Math.round(Color.blue(base) * inverse + Color.blue(overlay) * amount));
    }

    @Override public void dismiss() {
        if (closing || !isShowing()) return;
        closing = true;
        View panel = getWindow().getDecorView();
        removeEntrance(panel);
        panel.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled() || !panel.isLaidOut()) {
            super.dismiss();
            return;
        }
        panel.animate().translationY(panel.getHeight() + dp(12)).setDuration(220)
                .setInterpolator(MotionCurves.PLACEMENT).withEndAction(() -> super.dismiss()).start();
    }

    void dismissImmediately() {
        closing = true;
        View panel = getWindow().getDecorView();
        removeEntrance(panel);
        panel.animate().withEndAction(null).cancel();
        super.dismiss();
    }

    private void removeEntrance(View panel) {
        if (entrance != null) {
            if (panel.getViewTreeObserver().isAlive()) panel.getViewTreeObserver().removeOnPreDrawListener(entrance);
            entrance = null;
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        View panel = getWindow().getDecorView();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                downX = event.getRawX();
                downY = event.getRawY();
                dragging = false;
                // Keep one owner for the whole gesture; reaching the scroll edge must not jump the sheet.
                scrollingContent = canScrollUp(panel);
            }
            case MotionEvent.ACTION_MOVE -> {
                float delta = event.getRawY() - downY;
                if (!dragging && delta > dp(12) && delta > Math.abs(event.getRawX() - downX)
                        && !scrollingContent) {
                    dragging = true;
                    panel.animate().cancel();
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                if (dragging) {
                    panel.setTranslationY(Math.max(0, delta));
                    return true;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false;
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            && panel.getTranslationY() > Math.min(dp(96), panel.getHeight() * 0.25f)) dismiss();
                    else panel.animate().translationY(0).setDuration(180)
                            .setInterpolator(MotionCurves.PLACEMENT).start();
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean canScrollUp(View view) {
        if (view instanceof ScrollView && view.canScrollVertically(-1)) {
            // MotionEvent raw coordinates are screen-based, while getGlobalVisibleRect is root-based.
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            if (downX >= location[0] && downX < location[0] + view.getWidth()
                    && downY >= location[1] && downY < location[1] + view.getHeight()) return true;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) if (canScrollUp(group.getChildAt(i))) return true;
        }
        return false;
    }

    private int dp(float value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
