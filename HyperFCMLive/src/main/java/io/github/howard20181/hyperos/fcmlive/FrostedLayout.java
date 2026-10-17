package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** One list recording feeds both the sharp viewport and the unified frosted header. */
public final class FrostedLayout extends FrameLayout {
    private final Paint tint = new Paint();
    private HeaderBlur blur;

    public FrostedLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        View header = findViewById(R.id.header);
        boolean hardwareBlur = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated();
        if (child.getId() == R.id.app_list && hardwareBlur && child.getWidth() > 0 && header != null) {
            if (blur == null) blur = new HeaderBlur(25 * getResources().getDisplayMetrics().density);
            Canvas recording = blur.begin(child, getContext().getColor(R.color.background));
            boolean more;
            try {
                // EdgeEffect stretches the RecordingCanvas's owner node. Going through the
                // framework keeps it on the ListView's node, where updateDisplayListIfDirty()
                // clears it before each recording. child.draw(recording) bypasses that reset
                // and can leave our shared backdrop permanently stretched after a fast fling.
                more = super.drawChild(recording, child, drawingTime);
            } finally {
                blur.end(child, header.getBottom());
            }
            int save = canvas.save();
            // The header receives only the blurred image, never sharp content underneath it.
            canvas.clipRect(child.getLeft(), header.getBottom(), child.getRight(), child.getBottom());
            blur.drawSharp(canvas);
            canvas.restoreToCount(save);
            return more;
        }
        if (child.getId() == R.id.header) {
            int save = canvas.save();
            canvas.clipRect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            if (hardwareBlur && blur != null) {
                blur.drawBlurred(canvas);
                tint.setColor(getContext().getColor(R.color.header_tint));
            } else {
                tint.setColor(getContext().getColor(R.color.background));
            }
            canvas.drawRect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom(), tint);
            canvas.restoreToCount(save);
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 31 && blur != null) blur.release();
        blur = null;
        super.onDetachedFromWindow();
    }

    @androidx.annotation.RequiresApi(31)
    private static final class HeaderBlur {
        private final RenderNode content = new RenderNode("FCM shared list frame");
        private final RenderNode backdrop = new RenderNode("FCM unified header backdrop");
        private final int overscan;

        HeaderBlur(float radius) {
            overscan = (int) Math.ceil(radius * 3);
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        Canvas begin(View source, int background) {
            // Use the real viewport dimensions. Drawing a scrollable View into a shorter canvas
            // can change its clipping/culling relative to the visible list during placement.
            content.setPosition(source.getLeft(), source.getTop(), source.getRight(), source.getBottom());
            Canvas recording = content.beginRecording(source.getWidth(), source.getHeight());
            recording.drawColor(background);
            // drawChild applies the View's position, scrolling and render-node transforms.
            // This recording is positioned at the View's origin, so undo only that origin.
            recording.translate(-source.getLeft(), -source.getTop());
            return recording;
        }

        void end(View source, int headerBottom) {
            content.endRecording();
            // Limit the blur pass to the header plus its kernel margin, while sharing the full
            // viewport recording. No second View draw and no full-screen offscreen blur texture.
            int height = Math.min(source.getHeight(), headerBottom - source.getTop() + overscan);
            backdrop.setPosition(source.getLeft(), source.getTop(), source.getRight(), source.getTop() + height);
            Canvas blurred = backdrop.beginRecording(source.getWidth(), height);
            blurred.translate(-source.getLeft(), -source.getTop());
            blurred.drawRenderNode(content);
            backdrop.endRecording();
        }

        void drawSharp(Canvas canvas) { canvas.drawRenderNode(content); }
        void drawBlurred(Canvas canvas) { canvas.drawRenderNode(backdrop); }

        void release() { content.discardDisplayList(); backdrop.discardDisplayList(); }
    }
}
