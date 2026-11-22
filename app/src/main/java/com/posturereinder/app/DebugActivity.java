package com.posturereinder.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live debugging view: camera + boxes + the numbers behind the score. */
public class DebugActivity extends Activity implements LifecycleOwner {

    private static final String TAG = "PostureDebug";

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService cameraExec = Executors.newSingleThreadExecutor();
    private final ExecutorService modelExec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private DebugView view;
    private TextView data;
    private Settings settings;
    private LocalPrefs prefs;
    private Vision.Baseline baseline;

    private PoseLandmarker pose;
    private FaceLandmarker face;
    private ProcessCameraProvider provider;

    private volatile List<NormalizedLandmark> lastPose;
    private volatile List<NormalizedLandmark> lastFace;
    private volatile Attention attention;
    private volatile double headEma = 1.0;
    private volatile String label = "—";
    private volatile int score = -1;
    private volatile double[] zs = new double[0];
    private volatile Bitmap bitmap;
    private volatile boolean busy;
    private volatile int pw, ph;
    private volatile long lastDraw;
    private volatile int frames;
    private volatile float fps;
    private volatile double cpuPct;
    private volatile long memUsedMb, memTotalMb;
    private long lastCpuMs;
    private int lastFrameCount;
    private long lastStatsAt;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            sampleStats();
            updatePanel();
            main.postDelayed(this, 1000);
        }
    };

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);
        view = findViewById(R.id.debugView);
        data = findViewById(R.id.debugData);
        settings = new Settings(this);
        prefs = new LocalPrefs(this);
        baseline = prefs.baseline();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);

        // the monitoring service owns the camera, stop it first
        sendBroadcast(new Intent(LocalService.ACTION_STOP).setPackage(getPackageName()));
        main.postDelayed(() -> {
            modelExec.execute(this::initModels);
            bindCamera();
        }, 700);
        lastStatsAt = System.currentTimeMillis();
        main.postDelayed(statsTick, 1000);
    }

    private void sampleStats() {
        try {
            long now = System.currentTimeMillis();
            long dtMs = Math.max(1, now - lastStatsAt);
            long cpuMs = android.os.Process.getElapsedCpuTime();
            if (lastCpuMs > 0) {
                cpuPct = 100.0 * (cpuMs - lastCpuMs) / dtMs;
            }
            lastCpuMs = cpuMs;
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            memTotalMb = mi.totalMem / (1024 * 1024);
            memUsedMb = (mi.totalMem - mi.availMem) / (1024 * 1024);
            int df = frames - lastFrameCount;
            fps = df * 1000f / dtMs;
            lastFrameCount = frames;
            lastStatsAt = now;
        } catch (Exception ignored) {
        }
    }

    private void initModels() {
        try {
            BaseOptions b1 = BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task")
                    .setDelegate(Delegate.CPU).build();
            pose = PoseLandmarker.createFromOptions(this,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                            .setBaseOptions(b1)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumPoses(1)
                            .setResultListener(this::onPose)
                            .setErrorListener(e -> Log.w(TAG, "pose", e))
                            .build());
        } catch (Exception e) {
            Log.e(TAG, "pose init", e);
        }
        try {
            BaseOptions b2 = BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                    .setDelegate(Delegate.CPU).build();
            face = FaceLandmarker.createFromOptions(this,
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(b2)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setNumFaces(1)
                            .setOutputFaceBlendshapes(true)
                            .setResultListener(this::onFace)
                            .setErrorListener(e -> Log.w(TAG, "face", e))
                            .build());
        } catch (Exception e) {
            Log.e(TAG, "face init", e);
        }
        main.post(() -> data.setText("模型就绪，等待画面…"));
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                ImageAnalysis ia = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                ia.setAnalyzer(cameraExec, this::analyze);
                CameraSelector cs = CameraSelector.DEFAULT_FRONT_CAMERA;
                if (!provider.hasCamera(cs)) cs = CameraSelector.DEFAULT_BACK_CAMERA;
                provider.unbindAll();
                provider.bindToLifecycle(this, cs, ia);
            } catch (Exception e) {
                Log.e(TAG, "bind", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            if (busy || pose == null) return;
            byte[] jpeg = Yuv.toJpeg(image, 62);
            if (jpeg == null) return;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) return;
            int rot = image.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                bmp = r;
            }
            if (bmp.getWidth() > 640) {
                int w = 640;
                int h = Math.max(1, (int) (bmp.getHeight() * 640.0 / bmp.getWidth()));
                Bitmap s = Bitmap.createScaledBitmap(bmp, w, h, true);
                if (s != bmp) bmp.recycle();
                bmp = s;
            }
            MPImage img = new BitmapImageBuilder(bmp).build();
            bitmap = bmp;
            pw = bmp.getWidth();
            ph = bmp.getHeight();
            frames++;
            busy = true;
            long ts = SystemClock.uptimeMillis();
            pose.detectAsync(img, ts);
            if (face != null) face.detectAsync(img, ts);
        } catch (Exception e) {
            busy = false;
            Log.w(TAG, "analyze", e);
        } finally {
            image.close();
        }
    }

    private void onPose(PoseLandmarkerResult r, MPImage input) {
        busy = false;
        List<NormalizedLandmark> pts = null;
        if (r != null && r.landmarks() != null && !r.landmarks().isEmpty()) {
            pts = r.landmarks().get(0);
        }
        lastPose = pts;
        if (pts != null && pts.size() >= 33) {
            double[] nx = new double[33], ny = new double[33], nv = new double[33];
            for (int i = 0; i < 33; i++) {
                NormalizedLandmark p = pts.get(i);
                nx[i] = p.x();
                ny[i] = p.y();
                nv[i] = p.visibility().isPresent() ? p.visibility().get() : 0f;
            }
            Vision.Frame f = Vision.measure(nx, ny, nv, pw, ph);
            if (f != null && baseline != null) {
                Vision.Reading rd = Vision.evaluate(f, baseline, settings.sensitivity);
                if (rd.headRatio > 0.5 && rd.headRatio < 2) headEma = headEma * 0.8 + rd.headRatio * 0.2;
                label = Vision.postureLabel(rd, headEma);
                score = rd.score;
                zs = new double[Vision.ORDER.length];
                for (Vision.Metric m : rd.metrics) {
                    for (int i = 0; i < Vision.ORDER.length; i++) {
                        if (Vision.ORDER[i].equals(m.key)) zs[i] = m.z;
                    }
                }
            } else if (f != null) {
                label = "未校准";
            }
        } else {
            label = "无人";
        }
        draw();
        updatePanel();
    }

    private void onFace(FaceLandmarkerResult r, MPImage input) {
        lastFace = (r != null && r.faceLandmarks() != null && !r.faceLandmarks().isEmpty())
                ? r.faceLandmarks().get(0) : null;
        attention = Attention.from(r, System.currentTimeMillis(),
                settings.blinkThreshold, settings.attentionSens);
        draw();
    }

    private void draw() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDraw < 80) return;
        lastDraw = now;
        Bitmap b = bitmap;
        main.post(() -> view.update(b, lastPose, lastFace, ""));
    }

    private void updatePanel() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.CHINA, "姿态：%s   评分：%s   头比：%.2f%n",
                label, score >= 0 ? String.valueOf(score) : "—", headEma));
        Attention a = attention;
        if (a != null && a.face) {
            sb.append(String.format(Locale.CHINA,
                    "转头：%+.2f   眼珠横：%+.2f   眼珠纵：%+.2f   闭眼：%.2f%n",
                    a.yaw, a.gazeH, a.gazeV, a.blink));
        } else {
            sb.append("人脸：未检测到\n");
        }
        if (zs.length == Vision.ORDER.length) {
            StringBuilder z = new StringBuilder("z: ");
            for (int i = 0; i < zs.length; i++) {
                z.append(String.format(Locale.CHINA, "%s%+.1f ", Vision.ORDER[i], zs[i]));
            }
            sb.append(z).append("\n");
        }
        sb.append("阈值：闭眼 ").append(Math.round(settings.blinkThreshold * 100))
                .append("% · 分心灵敏度 ").append(settings.attentionSens).append("\n");
        sb.append("识别模式：MediaPipe Tasks 0.10.21 · CPU / XNNPACK\n");
        sb.append(String.format(Locale.CHINA,
                "App CPU %.0f%%（单核占比）· 内存 %d/%d MB · 分析 %.1f 帧/秒",
                cpuPct, memUsedMb, memTotalMb, fps));
        main.post(() -> data.setText(sb.toString()));
    }

    @Override
    protected void onDestroy() {
        if (provider != null) provider.unbindAll();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (pose != null) { try { pose.close(); } catch (Exception ignored) { } }
        if (face != null) { try { face.close(); } catch (Exception ignored) { } }
        cameraExec.shutdown();
        modelExec.shutdown();
        super.onDestroy();
    }
}
