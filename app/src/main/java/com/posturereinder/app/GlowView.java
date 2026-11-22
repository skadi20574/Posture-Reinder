package com.posturereinder.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** Very light floating glow blobs used by the "elegant" theme. */
public class GlowView extends View {

    private static final int N = 5;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RadialGradient[] shaders = new RadialGradient[N];
    private final float[] phase = new float[N];
    private final float[] speed = new float[N];
    private final float[] radius = new float[N];
    private boolean active;
    private long t0 = System.currentTimeMillis();

    public GlowView(Context c, AttributeSet a) {
        super(c, a);
        int[] colors = {0x2A5FD3C4, 0x1E8EE8D8, 0x1A4A9EE0, 0x225FD3C4, 0x148EE8D8};
        for (int i = 0; i < N; i++) {
            phase[i] = (float) (i * 1.7);
            speed[i] = 0.10f + i * 0.03f;
        }
    }

    public void setActive(boolean on) {
        active = on;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int[] colors = {0x2A5FD3C4, 0x1E8EE8D8, 0x1A4A9EE0, 0x225FD3C4, 0x148EE8D8};
        for (int i = 0; i < N; i++) {
            radius[i] = Math.max(w, h) * (0.28f + 0.06f * i);
            shaders[i] = new RadialGradient(0, 0, radius[i], colors[i], Color.TRANSPARENT,
                    Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active) return;
        float t = (System.currentTimeMillis() - t0) / 1000f;
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < N; i++) {
            if (shaders[i] == null) continue;
            float a = phase[i] + t * speed[i];
            float x = w * (0.5f + 0.34f * (float) Math.sin(a));
            float y = h * (0.5f + 0.30f * (float) Math.cos(a * 1.3f + i));
            paint.setShader(shaders[i]);
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(0, 0, radius[i], paint);
            canvas.restore();
        }
        postInvalidateOnAnimation();
    }
}
