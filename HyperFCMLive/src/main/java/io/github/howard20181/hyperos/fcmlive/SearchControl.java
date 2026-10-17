package io.github.howard20181.hyperos.fcmlive;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SearchView;

import java.util.function.Consumer;

/** One persistent magnifier connects the compact action to the rounded input field. */
public final class SearchControl extends FrameLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF surface = new RectF();
    private final ImageButton magnifier;
    private final ImageButton close;
    private final EditText input;
    private SearchView.OnQueryTextListener queryListener;
    private Consumer<Boolean> expansionListener;
    private ValueAnimator morph;
    private View title;
    private float progress;
    private boolean iconified = true;

    public SearchControl(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        input = new EditText(context);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(context.getColor(R.color.text_primary));
        input.setHintTextColor(context.getColor(R.color.text_secondary));
        input.setHint(R.string.search_hint);
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setVisibility(INVISIBLE);
        addView(input);
        magnifier = button(R.drawable.ic_search, R.string.search_hint);
        magnifier.setOnClickListener(view -> {
            if (iconified) setIconified(false); else focusInput();
        });
        close = button(R.drawable.ic_close, android.R.string.cancel);
        close.setVisibility(INVISIBLE);
        close.setOnClickListener(view -> {
            if (input.length() > 0) setQuery("", false); else setIconified(true);
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (queryListener != null) queryListener.onQueryTextChange(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH && queryListener != null) {
                return queryListener.onQueryTextSubmit(input.getText().toString());
            }
            return false;
        });
    }

    private ImageButton button(int icon, int description) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(icon);
        button.setBackgroundResource(R.drawable.bg_icon_button);
        button.setContentDescription(getContext().getString(description));
        addView(button, new LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
        return button;
    }

    void setTitleView(View view) { title = view; applyProgress(); }
    void setOnExpansionChangedListener(Consumer<Boolean> listener) { expansionListener = listener; }
    public void setOnQueryTextListener(SearchView.OnQueryTextListener listener) { queryListener = listener; }
    public CharSequence getQuery() { return input.getText(); }
    public boolean isIconified() { return iconified; }

    public void setQuery(CharSequence query, boolean submit) {
        input.setText(query);
        input.setSelection(input.length());
        if (submit && queryListener != null) queryListener.onQueryTextSubmit(query.toString());
    }

    public void setIconified(boolean value) {
        if (iconified == value) return;
        iconified = value;
        if (morph != null) { morph.cancel(); morph = null; }
        if (value) {
            clearFocus();
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        if (expansionListener != null) expansionListener.accept(!value);
        input.setVisibility(VISIBLE);
        close.setVisibility(VISIBLE);
        float destination = value ? 0 : 1;
        if (!isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
            progress = destination;
            applyProgress();
            if (!value && isLaidOut()) focusInput();
            return;
        }
        morph = ValueAnimator.ofFloat(progress, destination);
        morph.setDuration(Math.max(1, Math.round(280 * Math.abs(destination - progress))));
        morph.setInterpolator(MotionCurves.PLACEMENT);
        morph.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            applyProgress();
        });
        morph.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled && !iconified) focusInput();
            }
        });
        morph.start();
    }

    private void focusInput() {
        if (iconified || !isAttachedToWindow()) return;
        input.requestFocus();
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public void clearFocus() { super.clearFocus(); if (input != null) input.clearFocus(); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, heightSpec);
        input.measure(MeasureSpec.makeMeasureSpec(Math.max(0, getMeasuredWidth() - dp(96)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int width = r - l;
        input.layout(dp(48), 0, width - dp(48), b - t);
        magnifier.layout(rtl ? width - dp(48) : 0, 0, rtl ? width : dp(48), b - t);
        close.layout(rtl ? 0 : width - dp(48), 0, rtl ? dp(48) : width, b - t);
        applyProgress();
    }

    private void applyProgress() {
        float offset = Math.max(0, getWidth() - dp(48)) * (1 - progress);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        magnifier.setTranslationX(rtl ? -offset : offset);
        input.setTranslationX(rtl ? -offset : offset);
        input.setAlpha(progress);
        close.setAlpha(progress);
        int controlsVisibility = progress == 0 ? INVISIBLE : VISIBLE;
        input.setVisibility(controlsVisibility);
        close.setVisibility(controlsVisibility);
        if (title != null) {
            title.setAlpha(Math.max(0, 1 - progress * 2));
            title.setVisibility(progress == 1 ? GONE : VISIBLE);
            title.setImportantForAccessibility(iconified ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float offset = Math.max(0, getWidth() - dp(48)) * (1 - progress);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        surface.set(rtl ? 0 : offset, 0, rtl ? getWidth() - offset : getWidth(), getHeight());
        paint.setColor(getContext().getColor(R.color.search_surface));
        paint.setAlpha(Math.round(255 * progress));
        canvas.drawRoundRect(surface, getHeight() / 2f, getHeight() / 2f, paint);
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        int save = canvas.save();
        if (child == input) canvas.clipRect(dp(48), 0, getWidth() - dp(48), getHeight());
        boolean result = super.drawChild(canvas, child, time);
        canvas.restoreToCount(save);
        return result;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (iconified && progress == 0) {
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            if (rtl ? event.getX() > dp(48) : event.getX() < getWidth() - dp(48)) return false;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override protected void onDetachedFromWindow() {
        if (morph != null) { morph.cancel(); morph = null; }
        progress = iconified ? 0 : 1;
        applyProgress();
        super.onDetachedFromWindow();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
