package com.posturereinder.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/** Live camera frame with pose/face boxes, eye markers and a data caption. */
public class DebugView extends View {

    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();

    private Bitmap bitmap;
    private List<NormalizedLandmark> pose;
    private List<NormalizedLandmark> face;
    private String caption = "";

    public DebugView(Context c, AttributeSet a) {
        super(c, a);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(3f);
        box.setColor(0xFF5FD3C4);
        eye.setColor(0xFFFFD166);
        text.setColor(Color.WHITE);
        text.setTextSize(28f);
        text.setShadowLayer(4f, 0f, 2f, Color.BLACK);
        framePaint.setFilterBitmap(true);
    }

    public void update(Bitmap bmp, List<NormalizedLandmark> pose,
                       List<NormalizedLandmark> face, String caption) {
        this.bitmap = bmp;
        this.pose = pose;
        this.face = face;
        this.caption = caption == null ? "" : caption;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (bitmap == null || bitmap.isRecycled()) return;

        float scale = Math.min(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        float dw = bitmap.getWidth() * scale, dh = bitmap.getHeight() * scale;
        float ox = (w - dw) / 2f, oy = (h - dh) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(ox, oy);
        canvas.drawBitmap(bitmap, matrix, framePaint);

        if (pose != null && pose.size() >= 33) {
            drawBox(canvas, pose, ox, oy, dw, dh, 0xFF5FD3C4, "BODY");
            drawSkeleton(canvas, pose, ox, oy, dw, dh);
        }
        if (face != null && face.size() > 100) {
            drawBox(canvas, face, ox, oy, dw, dh, 0xFF7EE0D0, "FACE");
            if (face.size() > 473) {
                drawEye(canvas, face.get(468), ox, oy, dw, dh);
                drawEye(canvas, face.get(473), ox, oy, dw, dh);
            }
        }
        if (!caption.isEmpty()) {
            String[] lines = caption.split("\n");
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], 16f, 40f + i * 34f, text);
            }
        }
    }

    private void drawBox(Canvas c, List<NormalizedLandmark> pts, float ox, float oy,
                         float dw, float dh, int color, String label) {
        float minX = 1f, minY = 1f, maxX = 0f, maxY = 0f;
        boolean any = false;
        for (NormalizedLandmark p : pts) {
            if (p.visibility().isPresent() && p.visibility().get() < 0.3f) continue;
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            any = true;
        }
        if (!any) return;
        box.setColor(color);
        rect.set(ox + minX * dw, oy + minY * dh, ox + maxX * dw, oy + maxY * dh);
        c.drawRect(rect, box);
        text.setColor(color);
        text.setTextSize(24f);
        c.drawText(label, rect.left + 4, rect.top - 8, text);
        text.setColor(Color.WHITE);
        text.setTextSize(28f);
    }

    private void drawEye(Canvas c, NormalizedLandmark p, float ox, float oy, float dw, float dh) {
        float x = ox + p.x() * dw, y = oy + p.y() * dh;
        c.drawCircle(x, y, 7f, eye);
    }

    private void drawSkeleton(Canvas c, List<NormalizedLandmark> pts, float ox, float oy,
                              float dw, float dh) {
        int[][] bones = {{11, 12}, {11, 13}, {12, 14}, {11, 23}, {12, 24}, {23, 24},
                {11, 7}, {12, 8}, {7, 0}, {8, 0}};
        box.setColor(0xCC5FD3C4);
        for (int[] b : bones) {
            NormalizedLandmark a = pts.get(b[0]), d = pts.get(b[1]);
            if ((a.visibility().isPresent() && a.visibility().get() < 0.3f)
                    || (d.visibility().isPresent() && d.visibility().get() < 0.3f)) continue;
            c.drawLine(ox + a.x() * dw, oy + a.y() * dh,
                    ox + d.x() * dw, oy + d.y() * dh, box);
        }
    }
}
