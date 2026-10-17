package io.github.howard20181.hyperos.fcmlive;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import java.util.HashMap;
import java.util.Map;

/** Package-keyed placement animation, like InstallerX's Miuix scope list. */
public final class AnimatedAppList extends ListView {
    private ViewTreeObserver.OnPreDrawListener pending;

    public AnimatedAppList(Context context, AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
    }

    @Override protected int getChildDrawingOrder(int count, int drawingPosition) {
        return count - 1 - drawingPosition;
    }

    void changeItems(Runnable change) {
        Map<String, Float> previous = new HashMap<>();
        if (isLaidOut() && ValueAnimator.areAnimatorsEnabled()) {
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                String key = AppListAdapter.boundPackage(row);
                if (key != null) previous.put(key, row.getTop() + row.getTranslationY());
            }
        }
        int first = getFirstVisiblePosition();
        int top = getChildCount() == 0 ? getPaddingTop() : getChildAt(0).getTop();
        stopItemAnimations();
        change.run();
        if (previous.isEmpty()) return;
        // Keep the viewport fixed, rather than letting ListView follow the old first item's ID.
        setSelectionFromTop(Math.min(first, Math.max(0, getCount() - 1)), top - getPaddingTop());
        pending = () -> {
            getViewTreeObserver().removeOnPreDrawListener(pending);
            pending = null;
            for (int i = 0; i < getChildCount(); i++) {
                View row = getChildAt(i);
                Float oldTop = previous.get(AppListAdapter.boundPackage(row));
                if (oldTop == null || Math.abs(oldTop - row.getTop()) < 1) continue;
                row.setTranslationY(oldTop - row.getTop());
                row.animate().translationY(0).setDuration(260)
                        .setInterpolator(MotionCurves.PLACEMENT)
                        .setUpdateListener(ignored -> getRootView().invalidate()).start();
            }
            return true;
        };
        getViewTreeObserver().addOnPreDrawListener(pending);
    }

    void stopItemAnimations() {
        if (pending != null) {
            if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(pending);
            pending = null;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View row = getChildAt(i);
            row.animate().cancel();
            row.setTranslationY(0);
        }
    }

    @Override protected void onDetachedFromWindow() {
        stopItemAnimations();
        super.onDetachedFromWindow();
    }
}
