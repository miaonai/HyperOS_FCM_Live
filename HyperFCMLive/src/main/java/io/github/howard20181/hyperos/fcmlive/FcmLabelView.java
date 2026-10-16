package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.widget.TextView;

/** Keeps the FCM badge immediately after the name, reserving its space on narrow screens. */
public final class FcmLabelView extends TextView {
    private String appName = "";
    private boolean containsFcm;

    public FcmLabelView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setAppName(String name, boolean fcm) {
        appName = name;
        containsFcm = fcm;
        setContentDescription(fcm ? name + ", " + getContext().getString(R.string.contains_fcm) : name);
        updateLabel(getWidth());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // ListView suppresses layout requests made from onSizeChanged. Build the
        // inline label before TextView measures, including the first recycled bind.
        updateLabel(MeasureSpec.getSize(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void updateLabel(int width) {
        int available = Math.max(0, width - getCompoundPaddingLeft() - getCompoundPaddingRight());
        Badge badge = new Badge();
        float badgeWidth = containsFcm ? badge.width() : 0;
        CharSequence name = TextUtils.ellipsize(appName, getPaint(),
                Math.max(0, available - badgeWidth), TextUtils.TruncateAt.END);
        SpannableStringBuilder text = new SpannableStringBuilder(name);
        if (containsFcm) {
            int start = text.length();
            text.append('\uFFFC');
            text.setSpan(badge, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!TextUtils.equals(getText(), text)) setText(text);
    }

    private final class Badge extends ReplacementSpan {
        private final String text = getContext().getString(R.string.contains_fcm);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float unit = getResources().getDisplayMetrics().density;

        Badge() {
            paint.setTextSize(getTextSize() * 0.625f);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }

        float width() { return paint.measureText(text) + 18 * unit; }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            return (int) Math.ceil(width());
        }

        @Override
        public void draw(Canvas canvas, CharSequence value, int start, int end, float x,
                         int top, int y, int bottom, Paint original) {
            float center = y + (original.ascent() + original.descent()) / 2;
            float halfHeight = (paint.descent() - paint.ascent()) / 2 + 3 * unit;
            paint.setColor(getContext().getColor(R.color.fcm_background));
            canvas.drawRoundRect(x + 6 * unit, center - halfHeight, x + width(),
                    center + halfHeight, 5 * unit, 5 * unit, paint);
            paint.setColor(getContext().getColor(R.color.fcm_text));
            canvas.drawText(text, x + 12 * unit, center - (paint.ascent() + paint.descent()) / 2, paint);
        }
    }
}
