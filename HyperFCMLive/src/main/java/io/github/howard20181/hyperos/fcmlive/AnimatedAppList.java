package io.github.howard20181.hyperos.fcmlive;

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;

/** Package-keyed placement animation, like InstallerX's Miuix scope list. */
public final class AnimatedAppList extends ListView {
    private ViewTreeObserver.OnPreDrawListener pending;
    private final Set<String> revealed = new HashSet<>();
    private ValueAnimator placementLifetime;

    public AnimatedAppList(Context context, AttributeSet attrs) {
        super(context, attrs);
        setChildrenDrawingOrderEnabled(true);
    }

    @Override protected int getChildDrawingOrder(int count, int drawingPosition) {
        if (revealed.isEmpty()) return count - 1 - drawingPosition;
        // Newly admitted rows are exposed as the old rows move away, never painted over them.
        // Within each group keep the original index order: selected rows still move above others.
        int position = 0;
        for (int group = 0; group < 2; group++) {
            for (int i = count - 1; i >= 0; i--) {
                if (revealed.contains(AppListAdapter.boundPackage(getChildAt(i))) == (group == 0)) {
                    if (position++ == drawingPosition) return i;
                }
            }
        }
        return count - 1 - drawingPosition;
    }

    void changeItems(Runnable change) {
        Map<String, Float> previous = new HashMap<>();
        Set<String> stillRevealing = new HashSet<>(revealed);
        Set<String> previousKeys = new HashSet<>();
        if (getAdapter() instanceof AppListAdapter adapter) {
            for (int i = 0; i < adapter.getCount(); i++) previousKeys.add(adapter.getItem(i).packageName);
        }
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
        // In touch mode ListView may read the selected row's ID while syncing a data change.
        // An empty result has no valid anchor, so leave its layout to the data-set notification.
        if (getCount() == 0 || previous.isEmpty()) return;
        if (getAdapter() instanceof AppListAdapter adapter) {
            for (int i = 0; i < adapter.getCount(); i++) {
                String key = adapter.getItem(i).packageName;
                if (!previousKeys.contains(key)) revealed.add(key);
            }
        }
        if (!revealed.isEmpty()) revealed.addAll(stillRevealing);
        // Keep the viewport fixed, rather than letting ListView follow the old first item's ID.
        setSelectionFromTop(Math.min(first, getCount() - 1), top - getPaddingTop());
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
            placementLifetime = ValueAnimator.ofFloat(0, 1);
            placementLifetime.setDuration(260);
            placementLifetime.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    revealed.clear();
                    invalidate();
                }
            });
            placementLifetime.start();
            return true;
        };
        getViewTreeObserver().addOnPreDrawListener(pending);
    }

    void stopItemAnimations() {
        if (placementLifetime != null) { placementLifetime.cancel(); placementLifetime = null; }
        revealed.clear();
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
