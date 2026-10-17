package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/** Anchored, scrollable menu with Sui-style trailing selection marks. */
final class ManagementMenu {
    private final Context context;
    private final LinearLayout items;
    private final MenuWindow popup;
    private final ScrollView scroll;

    ManagementMenu(Context context) {
        this.context = context;
        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(8), dp(8), dp(8), dp(8));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(items);
        popup = new MenuWindow(scroll);
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.surface));
        background.setCornerRadius(dp(24));
        scroll.setBackground(background);
        scroll.setClipToOutline(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(6));
        popup.setAnimationStyle(0);
        popup.setEnterTransition(null);
        popup.setExitTransition(null);
        if (Build.VERSION.SDK_INT >= 29) popup.setIsLaidOutInScreen(true);
        popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
    }

    void toggle(int id, int label, boolean checked, Runnable action) {
        LinearLayout row = row(id, label, true, action);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.ic_menu_check);
        mark.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView title = (TextView) row.getChildAt(0);
        title.setTextColor(context.getColor(checked ? R.color.accent : R.color.text_primary));
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.setContentDescription(context.getString(label));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.CheckBox");
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }

    void action(int id, int label, boolean enabled, Runnable action) {
        row(id, label, enabled, action);
    }

    private LinearLayout row(int id, int label, boolean enabled, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setId(id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(14), dp(6), dp(14), dp(6));
        row.setBackgroundResource(R.drawable.bg_app_row);
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(14);
        title.setTextColor(context.getColor(R.color.text_primary));
        title.setPadding(0, 0, dp(12), 0);
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1 : 0.4f);
        row.setFocusable(true);
        row.setOnClickListener(view -> popup.dismissThen(action));
        items.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    @android.annotation.SuppressLint("RtlHardcoded") // x is already an absolute, RTL-aware screen coordinate.
    void show(View anchor) {
        int availableWidth = Math.max(1, anchor.getRootView().getWidth() - dp(32));
        items.measure(View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = items.getMeasuredWidth();
        items.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.setWidth(width);
        popup.setHeight(Math.min(items.getMeasuredHeight(), (int) (anchor.getRootView().getHeight() * 0.72f)));
        Rect bounds = new Rect();
        anchor.getWindowVisibleDisplayFrame(bounds);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? location[0] : location[0] + anchor.getWidth() - width;
        x = Math.max(bounds.left + dp(16), Math.min(x, bounds.right - width - dp(16)));
        int y = Math.max(bounds.top + dp(8), Math.min(location[1] + anchor.getHeight() + dp(6),
                bounds.bottom - popup.getHeight() - dp(8)));
        if (Build.VERSION.SDK_INT < 29) {
            int[] windowLocation = new int[2];
            anchor.getLocationInWindow(windowLocation);
            x -= location[0] - windowLocation[0];
            y -= location[1] - windowLocation[1];
        }
        // A fixed screen position avoids drop-down anchor scrolling/flipping during IME dismissal.
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        scroll.setAlpha(animate ? 0 : 1);
        scroll.setTranslationY(animate ? -dp(8) : 0);
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
        if (animate) scroll.animate().alpha(1).translationY(0).setDuration(180)
                .setInterpolator(MotionCurves.PLACEMENT).start();
    }

    void dismiss() { popup.dismissImmediately(); }

    /** Keep the surface attached until exit completes, including outside-tap and Back dismissal. */
    private final class MenuWindow extends PopupWindow {
        private boolean closing;
        private Runnable afterDismiss;

        MenuWindow(View content) {
            super(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        }

        void dismissThen(Runnable action) {
            if (closing) return;
            afterDismiss = action;
            dismiss();
        }

        @Override public void dismiss() {
            if (closing || !isShowing()) return;
            closing = true;
            scroll.animate().cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) { finishDismiss(); return; }
            scroll.animate().alpha(0).translationY(-dp(6)).setDuration(120)
                    .setInterpolator(MotionCurves.EXIT).withEndAction(this::finishDismiss).start();
        }

        private void finishDismiss() {
            super.dismiss();
            Runnable action = afterDismiss;
            afterDismiss = null;
            if (action != null) action.run();
        }

        void dismissImmediately() {
            afterDismiss = null;
            scroll.animate().withEndAction(null).cancel();
            super.dismiss();
        }
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
