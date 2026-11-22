package com.posturereinder.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Tiny bar chart: one bar per bucket, value 0..100. */
public class ChartView extends View {

    private float[] values = new float[0];
    private String[] labels = new String[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ChartView(Context c, AttributeSet a) {
        super(c, a);
        bar.setColor(0xFF5FD3C4);
        text.setColor(0xFF6B7278);
        text.setTextSize(sp(9));
        text.setTextAlign(Paint.Align.CENTER);
        grid.setColor(0xFF2B2F34);
        grid.setStrokeWidth(1);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    public void setData(float[] values, String[] labels) {
        this.values = values == null ? new float[0] : values;
        this.labels = labels == null ? new String[0] : labels;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float padBottom = sp(18);
        float chartH = h - padBottom - sp(6);
        for (int i = 1; i <= 3; i++) {
            float y = chartH * i / 4f;
            canvas.drawLine(0, y, w, y, grid);
        }
        if (values.length == 0) return;

        float gap = Math.max(2f, w * 0.02f / values.length);
        float bw = (w - gap * (values.length + 1)) / values.length;
        for (int i = 0; i < values.length; i++) {
            float v = Math.max(0, Math.min(100, values[i]));
            float bh = chartH * v / 100f;
            float left = gap + i * (bw + gap);
            rect.set(left, chartH - bh, left + bw, chartH);
            int alpha = 90 + (int) (v * 1.2f);
            bar.setColor(Color.argb(Math.min(255, alpha), 95, 211, 196));
            canvas.drawRoundRect(rect, bw * 0.25f, bw * 0.25f, bar);

            if (i < labels.length) {
                canvas.drawText(labels[i], left + bw / 2f, h - sp(4), text);
            }
        }
    }
}
