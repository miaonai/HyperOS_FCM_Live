package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
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
    private final PopupWindow popup;

    ManagementMenu(Context context) {
        this.context = context;
        items = new LinearLayout(context);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(items);
        popup = new PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(R.color.surface));
        background.setCornerRadius(dp(24));
        popup.setBackgroundDrawable(background);
        popup.setElevation(dp(12));
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
        row.setOnClickListener(view -> { popup.dismiss(); action.run(); });
        items.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    void show(View anchor) {
        int availableWidth = Math.max(1, anchor.getRootView().getWidth() - dp(32));
        items.measure(View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = items.getMeasuredWidth();
        items.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.setWidth(width);
        popup.setHeight(Math.min(items.getMeasuredHeight(), (int) (anchor.getRootView().getHeight() * 0.72f)));
        popup.showAsDropDown(anchor, 0, dp(6), Gravity.END);
    }

    void dismiss() { popup.dismiss(); }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
