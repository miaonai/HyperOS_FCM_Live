package io.github.howard20181.hyperos.fcmlive;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EdgeEffect;
import android.widget.FrameLayout;
import android.widget.ListView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

/** EdgeEffect mutates its recording canvas's RenderNode, not the icon View's scale. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h892dp-mdpi", shadows = HeaderOverscrollTest.StretchEdgeShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeaderOverscrollTest {
    @Test public void alternatingOverscrollKeepsStretchOnTheFrameworkOwnedListNode() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrostedLayout root = new FrostedLayout(activity, null);
            ListView list = new ListView(activity);
            list.setId(R.id.app_list);
            list.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
            list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1,
                    new String[] {"One", "Two", "Three"}));
            root.addView(list, new FrameLayout.LayoutParams(-1, -1));
            View header = new View(activity);
            header.setId(R.id.header);
            root.addView(header, new FrameLayout.LayoutParams(-1, 88));
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(412, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(892, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 412, 892);
            // Robolectric's window is not GPU-backed; enable the actual framework display-list
            // branch while using its native RecordingCanvas. No private APIs in production code.
            Object attachInfo = ReflectionHelpers.getField(root, "mAttachInfo");
            assertNotNull(attachInfo);
            ReflectionHelpers.setField(attachInfo, "mHardwareAccelerated", true);
            RenderNode listNode = ReflectionHelpers.getField(list, "mRenderNode");
            TrackingEdge top = new TrackingEdge(activity);
            TrackingEdge bottom = new TrackingEdge(activity);
            ReflectionHelpers.setField(list, "mEdgeGlowTop", top);
            ReflectionHelpers.setField(list, "mEdgeGlowBottom", bottom);
            RenderNode frame = new RenderNode("overscroll regression frame");
            frame.setPosition(0, 0, 412, 892);
            try {
                draw(root, frame);
                for (int cycle = 0; cycle < 8; cycle++) {
                    TrackingEdge active = cycle % 2 == 0 ? top : bottom;
                    active.onPullDistance(0.2f, 0.5f);
                    assertTrue("Pulling beyond either edge must produce feedback", active.getDistance() > 0);
                    int before = active.draws;
                    list.invalidate();
                    draw(root, frame);
                    assertTrue("Actual ListView edge must be drawn every time", active.draws > before);
                    assertSame("Stretch must belong to the ListView node cleared by View.updateDisplayListIfDirty",
                            listNode, active.owner);
                    active.onRelease();
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16));
                }
                for (int frameIndex = 0; frameIndex < 200 && (!top.isFinished() || !bottom.isFinished()); frameIndex++) {
                    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(16));
                    list.invalidate();
                    draw(root, frame);
                }
                assertTrue("Top spring must settle after release", top.isFinished());
                assertTrue("Bottom spring must settle after release", bottom.isFinished());
                assertEquals(0f, top.getDistance(), 0f);
                assertEquals(0f, bottom.getDistance(), 0f);
            } finally { frame.discardDisplayList(); }
        }
    }

    private static void draw(View root, RenderNode frame) {
        Canvas canvas = frame.beginRecording(412, 892);
        try { root.draw(canvas); } finally { frame.endRecording(); }
    }

    private static final class TrackingEdge extends EdgeEffect {
        RenderNode owner;
        int draws;
        TrackingEdge(Activity activity) { super(activity); }
        @Override public boolean draw(Canvas canvas) {
            owner = ReflectionHelpers.getField(canvas, "mNode");
            draws++;
            return super.draw(canvas);
        }
    }

    // Robolectric disables EdgeEffect on API 31+ by default. Exercise Android's real stretch
    // code instead; the test explicitly advances time and draws until both springs settle.
    @Implements(EdgeEffect.class)
    public static class StretchEdgeShadow {
        @Implementation protected int getCurrentEdgeEffectBehavior() {
            return android.animation.ValueAnimator.areAnimatorsEnabled() ? 1 : -1;
        }
    }
}
