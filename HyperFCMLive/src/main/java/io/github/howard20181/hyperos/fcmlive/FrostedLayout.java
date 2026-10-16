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

/** Samples only the list behind the header. Header text and controls stay sharp. */
public final class FrostedLayout extends FrameLayout {
    private final Paint tint = new Paint();
    private HeaderBlur blur;

    public FrostedLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child.getId() == R.id.header) {
            View list = findViewById(R.id.app_list);
            int save = canvas.save();
            canvas.clipRect(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            if (Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated()
                    && list != null && list.getWidth() > 0) {
                if (blur == null) blur = new HeaderBlur(20 * getResources().getDisplayMetrics().density);
                blur.draw(canvas, list, child.getBottom(), getContext().getColor(R.color.background));
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
        private final RenderNode node = new RenderNode("FCM header backdrop");
        private final int overscan;

        HeaderBlur(float radius) {
            overscan = (int) Math.ceil(radius * 2);
            node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }

        void draw(Canvas canvas, View source, int headerBottom, int background) {
            // Include pixels below the header to avoid a hard blur edge when scrolling.
            int height = Math.min(source.getHeight(), headerBottom - source.getTop() + overscan);
            node.setPosition(source.getLeft(), source.getTop(), source.getRight(), source.getTop() + height);
            Canvas recording = node.beginRecording(source.getWidth(), height);
            recording.drawColor(background);
            source.draw(recording);
            node.endRecording();
            canvas.drawRenderNode(node);
        }

        void release() { node.discardDisplayList(); }
    }
}
