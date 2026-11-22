package com.posturereinder.app;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** YUV_420_888 -> NV21 -> JPEG. */
public final class Yuv {

    public static byte[] toJpeg(ImageProxy image, int quality) {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] nv21 = toNv21(image, w, h);
        if (nv21 == null) return null;
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        if (!yuv.compressToJpeg(new Rect(0, 0, w, h), quality, out)) return null;
        return out.toByteArray();
    }

    private static byte[] toNv21(ImageProxy image, int w, int h) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes.length < 3) return null;
        ImageProxy.PlaneProxy yp = planes[0];
        ImageProxy.PlaneProxy up = planes[1];
        ImageProxy.PlaneProxy vp = planes[2];

        byte[] out = new byte[w * h * 3 / 2];
        int pos = 0;

        ByteBuffer yb = yp.getBuffer();
        int yRowStride = yp.getRowStride();
        if (yRowStride == w) {
            yb.get(out, 0, w * h);
            pos = w * h;
        } else {
            byte[] row = new byte[yRowStride];
            for (int r = 0; r < h; r++) {
                yb.position(r * yRowStride);
                int n = Math.min(yRowStride, yb.remaining());
                yb.get(row, 0, n);
                System.arraycopy(row, 0, out, pos, w);
                pos += w;
            }
        }

        ByteBuffer ub = up.getBuffer();
        ByteBuffer vb = vp.getBuffer();
        int uvRowStride = up.getRowStride();
        int uvPixelStride = up.getPixelStride();
        if (uvRowStride <= 0 || uvPixelStride <= 0) return null;

        for (int r = 0; r < h / 2; r++) {
            int rowStart = r * uvRowStride;
            for (int c = 0; c < w / 2; c++) {
                int idx = rowStart + c * uvPixelStride;
                if (idx >= vb.limit() || idx >= ub.limit()) break;
                out[pos++] = vb.get(idx);
                out[pos++] = ub.get(idx);
            }
        }
        if (pos != out.length) {
            byte[] trimmed = new byte[pos];
            System.arraycopy(out, 0, trimmed, 0, pos);
            return trimmed;
        }
        return out;
    }
}
