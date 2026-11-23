package com.posturereinder.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;

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
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device posture + attention coach. Everything (pose model, face model,
 * scoring, voice) runs locally; nothing leaves the phone.
 */
public class LocalService extends Service implements LifecycleOwner {

    public static final String ACTION_START = "com.posturereinder.app.START";
    public static final String ACTION_STOP = "com.posturereinder.app.STOP";
    public static final String ACTION_CALIBRATE = "com.posturereinder.app.CALIBRATE";
    public static final String ACTION_RELOAD = "com.posturereinder.app.RELOAD";
    public static final String ACTION_STATUS = "com.posturereinder.app.STATUS";

    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_SCORE = "score";
    public static final String EXTRA_MSG = "msg";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_ALERT = "alert";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_ATTENTION = "attention";
    public static final String EXTRA_FOCUS = "focus";

    private static final String TAG = "PostureLocal";
    private static final String CHANNEL = "posture_local";
    private static final int NOTIF_ID = 43;
    private static final String POSE_MODEL = "pose_landmarker_full.task";
    private static final String FACE_MODEL = "face_landmarker.task";

    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();

    private LocalPrefs prefs;
    private Settings settings;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer player;
    private ProcessCameraProvider provider;
    private PoseLandmarker landmarker;
    private FaceLandmarker faceLandmarker;

    private Vision.Baseline baseline;
    private final Vision.StateMachine machine = new Vision.StateMachine();

    private volatile boolean running;
    private volatile boolean calibrating;
    private volatile boolean busy;
    private volatile boolean cameraBound;
    private volatile long lastFrameAt;
    private volatile long lastFrameMs;
    private volatile long calibStart;
    private final List<Vision.Frame> calibFrames = new ArrayList<>();

    private volatile String lastState = "IDLE";
    private volatile int lastScore = -1;
    private volatile String lastLabel = "—";
    private volatile long lastNotifyAt;
    private volatile long lastBroadcastAt;
    private volatile String lastBroadcastKey = "";

    private volatile int pendingW, pendingH;
    private volatile Attention attention;
    private volatile String attentionText = "—";
    private volatile double focusSec;
    private volatile long lastPoseAt;
    private volatile long lastFocusAt;
    private volatile long lastRestAt;
    private volatile long lastSettingsAt;
    private volatile long lastReloadAt;

    private StatsDb db;
    private long sessionStart;
    private double statGood, statBad, statFwd, statBack, statClosed, statDistracted,
            statAway, statOccluded;
    private int statAlerts, statScreenOn;
    private int statFwdN, statBackN, statClosedN, statDistractedN;
    private String prevLabel = "";
    private boolean closedActive, distractedActive;
    private boolean sessionSaved = true;
    private volatile boolean frameOccluded;
    private volatile double headEma = 1.0;
    private volatile long distractedSince, closedSince, occludedSince;
    private volatile long lastAuxAlertAt;
    private volatile String lastAuxKey;
    private volatile String prevStateForPraise = "";
    private volatile long lastPraiseAt;
    private static final java.util.Random RND = new java.util.Random();

    /** Fallback when the repository ships without recorded clips. */
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private final java.util.Set<String> audioFiles = new java.util.HashSet<>();

    private static String speakText(String key) {
        switch (key) {
            case "sit_up": return "坐直一点，背挺起来。";
            case "sit2": return "背挺直，坐正一点。";
            case "head_up": return "头抬高一点，别低头。";
            case "shoulders": return "肩膀放平，放松一点。";
            case "center": return "身子往中间坐一坐。";
            case "far": return "离书远一点，让眼睛休息一下。";
            case "near": return "离书太近了，抬头看看远处。";
            case "rest": return "坐了很久啦，起来活动一下。";
            case "good": return "很好，就这样保持。";
            case "focus": return "看前面，专心写作业哦。";
            case "distract2": return "别走神，继续写吧。";
            case "eyes": return "眼睛累了吧，闭眼休息一下。";
            case "eyes2": return "眼睛累了就闭会儿眼吧。";
            case "away": return "回到座位继续写吧。";
            case "covered": return "摄像头好像被挡住了，检查一下。";
            case "praise": return "这一会儿坐得很好，继续保持。";
            case "back": return "坐得有点靠后了，往前坐一点。";
            default: return null;
        }
    }

    private static String variant(String key) {
        switch (key) {
            case "sit_up": return RND.nextBoolean() ? "sit_up" : "sit2";
            case "far": return RND.nextBoolean() ? "far" : "near";
            case "focus": return RND.nextBoolean() ? "focus" : "distract2";
            case "eyes": return RND.nextBoolean() ? "eyes" : "eyes2";
            default: return key;
        }
    }

    private final android.content.BroadcastReceiver screenReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    String a = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a) && running) statScreenOn++;
                }
            };

    private final android.content.BroadcastReceiver reloadReceiver =
            new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    if (ACTION_STOP.equals(intent.getAction())) {
                        stopWatching();
                        stopSelf();
                        return;
                    }
                    int before = settings.cameraMode;
                    settings.reload();
                    prefs.setVoice(settings.voice);
                    if (before != settings.cameraMode) {
                        baseline = prefs.baseline(settings.cameraMode);
                        synchronized (machine) {
                            machine.reset();
                        }
                        lastBroadcastKey = "";
                        headEma = 1.0;
                    }
                }
            };

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new LocalPrefs(this);
        settings = new Settings(this);
        db = new StatsDb(this);
        baseline = prefs.baseline(settings.cameraMode);

        try {
            String[] names = getAssets().list("audio");
            if (names != null) java.util.Collections.addAll(audioFiles, names);
        } catch (Exception ignored) {
        }
        tts = new TextToSpeech(getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) {
                Log.w(TAG, "tts init failed: " + status);
                return;
            }
            int r = tts.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                r = tts.setLanguage(java.util.Locale.CHINA);
            }
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA
                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
            Log.i(TAG, "tts ready=" + ttsReady + " lang=" + r);
        });
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "posture:local");
        wakeLock.setReferenceCounted(false);
        startForegroundCompat(buildNotification("待启动", -1));
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reloadReceiver, reloadFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reloadReceiver, reloadFilter());
        }
        android.content.IntentFilter sf = new android.content.IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, sf, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, sf);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopWatching();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_CALIBRATE.equals(action)) {
            startCalibration();
            return START_STICKY;
        }
        if (ACTION_RELOAD.equals(action)) {
            settings.reload();
            return START_STICKY;
        }
        startWatching();
        return START_STICKY;
    }

    // ------------------------------------------------------------------ start

    private void startWatching() {
        if (running && cameraBound) return;
        running = true;
        prefs.setRunning(true);
        lastBroadcastKey = "";
        resetSessionStats();
        if (!wakeLock.isHeld()) wakeLock.acquire();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        bindCamera();
        modelExecutor.execute(this::ensureModels);
        updateNotification("准备中", -1);
        broadcast("IDLE", -1, baseline != null ? "正在启动…" : "还没有校准，建议先校准", true);
    }

    private void stopWatching() {
        if (!running && sessionSaved) return;
        running = false;
        calibrating = false;
        lastBroadcastKey = "";
        saveSession();
        prefs.setRunning(false);
        if (provider != null) {
            provider.unbindAll();
            cameraBound = false;
        }
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        broadcast("IDLE", -1, "已停止", false);
        updateNotification("已停止", -1);
    }

    private static android.content.IntentFilter reloadFilter() {
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(ACTION_RELOAD);
        f.addAction(ACTION_STOP);
        return f;
    }

    private void ensureModels() {
        if (landmarker == null) {
            try {
                BaseOptions base = BaseOptions.builder()
                        .setModelAssetPath(POSE_MODEL)
                        .setDelegate(Delegate.CPU)
                        .build();
                PoseLandmarker.PoseLandmarkerOptions opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumPoses(1)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener(this::onPoseResult)
                        .setErrorListener(e -> Log.e(TAG, "pose error", e))
                        .build();
                landmarker = PoseLandmarker.createFromOptions(this, opts);
            } catch (Exception e) {
                Log.e(TAG, "pose init failed", e);
                broadcast("ERROR", -1, "姿态模型加载失败：" + e.getMessage(), running);
            }
        }
        if (faceLandmarker == null) {
            try {
                BaseOptions base = BaseOptions.builder()
                        .setModelAssetPath(FACE_MODEL)
                        .setDelegate(Delegate.CPU)
                        .build();
                FaceLandmarker.FaceLandmarkerOptions opts = FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumFaces(1)
                        .setOutputFaceBlendshapes(true)
                        .setMinFaceDetectionConfidence(0.5f)
                        .setMinFacePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setResultListener(this::onFaceResult)
                        .setErrorListener(e -> Log.w(TAG, "face error", e))
                        .build();
                faceLandmarker = FaceLandmarker.createFromOptions(this, opts);
            } catch (Exception e) {
                Log.w(TAG, "face init failed", e);
            }
        }
        broadcast(lastState, lastScore, "本机模型已加载", running);
    }

    // ------------------------------------------------------------------ camera

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyze);

                CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                if (!provider.hasCamera(selector)) selector = CameraSelector.DEFAULT_BACK_CAMERA;

                provider.unbindAll();
                provider.bindToLifecycle(this, selector, analysis);
                cameraBound = true;
                broadcast(lastState, lastScore, "摄像头已启动", true);
            } catch (Exception e) {
                Log.e(TAG, "bind camera failed", e);
                broadcast("ERROR", -1, "摄像头启动失败：" + e.getMessage(), false);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyze(ImageProxy image) {
        try {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastReloadAt > 1000) {
                lastReloadAt = now;
                settings.reload();
            }
            int interval = Math.max(120, settings.intervalMs);
            if (busy || now - lastFrameAt < interval) return;
            if (landmarker == null) return;
            lastFrameAt = now;

            byte[] jpeg = Yuv.toJpeg(image, 62);
            if (jpeg == null) return;
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) return;

            int rot = image.getImageInfo().getRotationDegrees();
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                bmp = rotated;
            }
            if (bmp.getWidth() > 640) {
                int w = 640;
                int h = Math.max(1, (int) (bmp.getHeight() * 640.0 / bmp.getWidth()));
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            MPImage mpImage = new BitmapImageBuilder(bmp).build();
            pendingW = bmp.getWidth();
            pendingH = bmp.getHeight();
            frameOccluded = looksOccluded(bmp);
            busy = true;
            long ts = SystemClock.uptimeMillis();
            landmarker.detectAsync(mpImage, ts);
            FaceLandmarker fl = faceLandmarker;
            if (fl != null && settings.attention && settings.cameraMode == Vision.MODE_FRONT) {
                fl.detectAsync(mpImage, ts);
            }
        } catch (Exception e) {
            Log.w(TAG, "analyze failed", e);
            busy = false;
        } finally {
            image.close();
        }
    }

    // ------------------------------------------------------------------ results

    private void onFaceResult(FaceLandmarkerResult result, MPImage input) {
        try {
            Attention a = Attention.from(result, System.currentTimeMillis(),
                    settings.blinkThreshold, settings.attentionSens);
            attention = a;
            if (!a.face) {
                attentionText = "未检测到人脸";
            } else if (a.distracted) {
                attentionText = "分心";
            } else {
                attentionText = "专注";
            }
        } catch (Exception e) {
            Log.w(TAG, "face result failed", e);
        }
    }

    private void onPoseResult(PoseLandmarkerResult result, MPImage input) {
        busy = false;
        int w = pendingW;
        int h = pendingH;
        try {
            Vision.Frame frame = null;
            if (result != null && result.landmarks() != null && !result.landmarks().isEmpty()) {
                List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> l =
                        result.landmarks().get(0);
                if (l != null && l.size() >= 33) {
                    double[] nx = new double[33];
                    double[] ny = new double[33];
                    double[] nv = new double[33];
                    for (int i = 0; i < 33; i++) {
                        com.google.mediapipe.tasks.components.containers.NormalizedLandmark p = l.get(i);
                        nx[i] = p.x();
                        ny[i] = p.y();
                        nv[i] = p.visibility().isPresent() ? p.visibility().get() : 0f;
                    }
                    frame = settings.cameraMode == Vision.MODE_SIDE
                            ? Vision.measureSide(nx, ny, nv, w, h)
                            : Vision.measure(nx, ny, nv, w, h);
                }
            }

            if (calibrating) {
                handleCalibration(frame);
                return;
            }

            if (baseline == null) {
                lastState = "CALIB";
                lastScore = -1;
                lastLabel = "未校准";
                updateNotification("未校准", -1);
                broadcast("CALIB", -1, "还没有校准，点「校准 10 秒」做一次", running);
                return;
            }

            Vision.Reading reading = frame == null
                    ? absentReading()
                    : Vision.evaluate(frame, baseline, settings.sensitivity);

            double ratio = reading.present ? reading.headRatio : 1.0;
            if (reading.present && ratio > 0.5 && ratio < 2.0) {
                headEma = headEma * 0.8 + ratio * 0.2;
            }
            String label = Vision.postureLabel(reading, headEma, settings.cameraMode);

            long nowMs = System.currentTimeMillis();
            double dt = lastPoseAt == 0 ? 0 : Math.min((nowMs - lastPoseAt) / 1000.0, 5.0);
            lastPoseAt = nowMs;

            machine.dwellSec = settings.dwellSec;
            machine.cooldownSec = settings.cooldownSec;
            machine.maxStreakAlerts = settings.maxRepeat;

            Vision.Payload payload;
            synchronized (machine) {
                payload = machine.update(reading, nowMs);
            }
            lastState = payload.state;
            lastScore = reading.present ? reading.score : -1;
            lastLabel = label;

            updateFocus(reading, dt, nowMs);
            recordStats(reading, label, dt, nowMs);
            if (payload.alertKey != null) statAlerts++;

            String msg = !reading.present
                    ? (frameOccluded ? "摄像头被挡住了" : "没有看到人")
                    : null;
            updateNotification(label + " · " + attentionText, lastScore);
            broadcast(payload.state, lastScore, msg, true);

            String alertKey = payload.alertKey;
            String alertText = payload.alertText;
            if ("后仰".equals(label)) {
                // leaning back is recorded but never nagged
                alertKey = null;
                alertText = null;
            }
            if (restDue && alertKey == null) {
                alertKey = "rest";
                alertText = "专注很久啦，起来活动一下。";
            }
            String aux = auxAlert(nowMs);
            if (aux != null) {
                alertKey = aux;
                alertText = auxText(aux);
            }
            if (alertKey != null && settings.voice) {
                alertKey = variant(alertKey);
                playVoice(alertKey);
                Intent it = new Intent(ACTION_STATUS);
                it.setPackage(getPackageName());
                it.putExtra(EXTRA_ALERT, alertText);
                sendBroadcast(it);
            } else if (alertKey == null && "GOOD".equals(payload.state)
                    && "BAD".equals(prevStateForPraise) && settings.voice
                    && nowMs - lastPraiseAt > 10 * 60 * 1000L) {
                lastPraiseAt = nowMs;
                playVoice("praise");
                Intent it = new Intent(ACTION_STATUS);
                it.setPackage(getPackageName());
                it.putExtra(EXTRA_ALERT, "这一会儿坐得很好，继续保持。");
                sendBroadcast(it);
            }
            prevStateForPraise = payload.state;
        } catch (Exception e) {
            Log.w(TAG, "pose result failed", e);
        }
    }

    private volatile boolean restDue;
    private volatile String restKey;

    private void updateFocus(Vision.Reading reading, double dt, long now) {
        restDue = false;
        if (settings.cameraMode == Vision.MODE_SIDE) {
            attentionText = "侧视：仅坐姿";
            focusSec = 0;
            return;
        }
        if (!settings.attention) {
            attentionText = "专注检测关闭";
            focusSec = 0;
            return;
        }
        Attention a = attention;
        boolean fresh = a != null && now - a.at < 2500;
        boolean focused = fresh && a.face && !a.distracted;
        if (focused) {
            focusSec += dt;
            lastFocusAt = now;
        } else if (now - lastFocusAt > 10000) {
            focusSec = 0;
        }
        if (focusSec >= settings.focusRestMin * 60.0 && now - lastRestAt > 5 * 60 * 1000L) {
            lastRestAt = now;
            focusSec = 0;
            restDue = true;
        }
    }

    // ------------------------------------------------------------------ stats

    private void resetSessionStats() {
        sessionStart = System.currentTimeMillis();
        statGood = statBad = statFwd = statBack = statClosed = 0;
        statDistracted = statAway = statOccluded = 0;
        statAlerts = 0;
        statScreenOn = 0;
        statFwdN = statBackN = statClosedN = statDistractedN = 0;
        prevLabel = "";
        closedActive = distractedActive = false;
        sessionSaved = false;
        lastAuxAlertAt = 0;
        distractedSince = closedSince = occludedSince = 0;
        headEma = 1.0;
    }

    private void recordStats(Vision.Reading reading, String label, double dt, long now) {
        if (sessionSaved || dt <= 0) return;
        if (frameOccluded) {
            statOccluded += dt;
            if (occludedSince == 0) occludedSince = now;
        } else {
            occludedSince = 0;
            if (!reading.present) {
                statAway += dt;
            } else if ("前倾".equals(label)) {
                statFwd += dt;
                if (!"前倾".equals(prevLabel)) statFwdN++;
            } else if ("后仰".equals(label)) {
                statBack += dt;
                if (!"后仰".equals(prevLabel)) statBackN++;
            } else if ("端正".equals(label)) {
                statGood += dt;
            } else {
                statBad += dt;
            }
        }
        if (reading.present) prevLabel = label;
        Attention a = attention;
        boolean fresh = a != null && now - a.at < 2500 && a.face;
        if (fresh) {
            if (a.blink > settings.blinkThreshold) {
                statClosed += dt;
                if (!closedActive) {
                    closedActive = true;
                    statClosedN++;
                }
                if (closedSince == 0) closedSince = now;
            } else {
                closedActive = false;
                closedSince = 0;
            }
            if (a.distracted && a.blink <= settings.blinkThreshold) {
                statDistracted += dt;
                if (!distractedActive) {
                    distractedActive = true;
                    statDistractedN++;
                }
                if (distractedSince == 0) distractedSince = now;
            } else {
                distractedActive = false;
                distractedSince = 0;
            }
        } else {
            closedActive = false;
            distractedActive = false;
            closedSince = 0;
            distractedSince = 0;
        }
    }

    private String auxAlert(long now) {
        long cooldown = 3 * 60 * 1000L;
        if (now - lastAuxAlertAt < cooldown) return null;
        if (occludedSince > 0 && now - occludedSince > 20000) {
            lastAuxAlertAt = now;
            lastAuxKey = "covered";
            return "covered";
        }
        if (closedSince > 0 && now - closedSince > 10000) {
            lastAuxAlertAt = now;
            lastAuxKey = "eyes";
            return "eyes";
        }
        if (distractedSince > 0 && now - distractedSince > 30000) {
            lastAuxAlertAt = now;
            lastAuxKey = "focus";
            return "focus";
        }
        return null;
    }

    private static String auxText(String key) {
        switch (key) {
            case "covered": return "摄像头好像被挡住了，检查一下。";
            case "eyes": return "眼睛累了吧，闭眼休息一下。";
            case "focus": return "看前面，专心写作业哦。";
            default: return "";
        }
    }

    private void saveSession() {
        if (sessionSaved) return;
        sessionSaved = true;
        if (sessionStart == 0) return;
        try {
            StatsDb.Row r = new StatsDb.Row();
            r.start = sessionStart;
            r.end = System.currentTimeMillis();
            r.good = (int) statGood;
            r.bad = (int) statBad;
            r.fwd = (int) statFwd;
            r.back = (int) statBack;
            r.closed = (int) statClosed;
            r.distracted = (int) statDistracted;
            r.away = (int) statAway;
            r.occluded = (int) statOccluded;
            r.fwdN = statFwdN;
            r.backN = statBackN;
            r.closedN = statClosedN;
            r.distractedN = statDistractedN;
            r.alerts = statAlerts;
            r.screenOn = statScreenOn;
            r.score = computeScore();
            db.insert(r);
        } catch (Exception e) {
            Log.w(TAG, "save session failed", e);
        }
    }

    private int computeScore() {
        double active = statGood + statBad + statFwd;
        double base = active > 0 ? 100.0 * statGood / active : 100.0;
        return (int) Math.max(0, Math.min(100, Math.round(base - Math.min(15, statAlerts * 1.0))));
    }

    private static boolean looksOccluded(Bitmap b) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(b, 16, 16, false);
            int[] px = new int[256];
            small.getPixels(px, 0, 16, 0, 0, 16, 16);
            if (small != b) small.recycle();
            double sum = 0, sum2 = 0;
            for (int v : px) {
                int r = (v >> 16) & 0xff, g = (v >> 8) & 0xff, bl = v & 0xff;
                double l = 0.299 * r + 0.587 * g + 0.114 * bl;
                sum += l;
                sum2 += l * l;
            }
            double mean = sum / 256.0;
            double var = sum2 / 256.0 - mean * mean;
            return mean < 30 || Math.sqrt(Math.max(0, var)) < 7;
        } catch (Exception e) {
            return false;
        }
    }

    private static Vision.Reading absentReading() {
        Vision.Reading r = new Vision.Reading();
        r.present = false;
        return r;
    }

    // ------------------------------------------------------------------ calibration

    private void startCalibration() {
        if (!running) {
            broadcast("IDLE", -1, "请先点「开始监控」", false);
            return;
        }
        modelExecutor.execute(() -> {
            ensureModels();
            synchronized (machine) {
                calibFrames.clear();
                calibStart = SystemClock.elapsedRealtime();
                calibrating = true;
            }
            broadcast("CALIBRATING", -1, "校准中：保持端正坐姿 10 秒", true);
        });
    }

    private void handleCalibration(Vision.Frame frame) {
        long elapsed = SystemClock.elapsedRealtime() - calibStart;
        if (frame != null) {
            synchronized (machine) {
                calibFrames.add(frame);
            }
        }
        if (elapsed >= 10000) {
            List<Vision.Frame> copy;
            synchronized (machine) {
                calibrating = false;
                copy = new ArrayList<>(calibFrames);
                calibFrames.clear();
            }
            try {
                Vision.Baseline b = settings.cameraMode == Vision.MODE_SIDE
                        ? Vision.fitSide(copy)
                        : Vision.fit(copy);
                baseline = b;
                prefs.setBaseline(settings.cameraMode, b);
                broadcast("READY", -1, "校准完成 ✔", true);
                updateNotification("校准完成", -1);
            } catch (Exception e) {
                broadcast("READY", -1, "校准失败：" + e.getMessage(), true);
            }
        } else {
            broadcast("CALIBRATING", -1, "校准中… " + ((10000 - elapsed) / 1000 + 1) + " 秒", true);
        }
    }

    // ------------------------------------------------------------------ voice

    private void playVoice(String key) {
        if (audioFiles.contains(key + ".mp3")) {
            playAsset("audio/" + key + ".mp3");
            return;
        }
        String text = speakText(key);
        if (text != null && ttsReady && tts != null) {
            try {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "posture");
                return;
            } catch (Exception e) {
                Log.w(TAG, "tts speak failed", e);
            }
        }
        playAsset("audio/chime.wav");
    }

    private void playAsset(String path) {
        try {
            if (player != null) {
                player.release();
                player = null;
            }
            AssetFileDescriptor afd = getAssets().openFd(path);
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnCompletionListener(m -> {
                m.release();
                if (player == m) player = null;
            });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(TAG, "voice failed", e);
        }
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "坐姿守护",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(String text, int score) {
        Intent open = new Intent(this, LocalMainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = score >= 0 ? text + " · " + score + " 分" : text;
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("坐姿守护")
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text, int score) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifyAt < 1500) return;
        lastNotifyAt = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(text, score));
    }

    private static String stateText(String s) {
        switch (s) {
            case "GOOD": return "坐姿端正";
            case "BAD": return "需要调整";
            case "AWAY": return "离开座位";
            case "WATCH": return "识别中";
            case "CALIB": return "未校准";
            case "CALIBRATING": return "校准中";
            default: return s;
        }
    }

    private void broadcast(String state, int score, String msg, boolean isRunning) {
        long now = SystemClock.elapsedRealtime();
        String key = state + "|" + msg;
        if (key.equals(lastBroadcastKey) && now - lastBroadcastAt < 1200) return;
        lastBroadcastKey = key;
        lastBroadcastAt = now;
        Intent it = new Intent(ACTION_STATUS);
        it.setPackage(getPackageName());
        it.putExtra(EXTRA_STATE, state);
        it.putExtra(EXTRA_SCORE, score);
        it.putExtra(EXTRA_MSG, msg);
        it.putExtra(EXTRA_RUNNING, isRunning);
        it.putExtra(EXTRA_LABEL, lastLabel);
        it.putExtra(EXTRA_ATTENTION, attentionText);
        it.putExtra(EXTRA_FOCUS, (int) focusSec);
        sendBroadcast(it);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(reloadReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        stopWatching();
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        if (landmarker != null) {
            try { landmarker.close(); } catch (Exception ignored) { }
            landmarker = null;
        }
        if (faceLandmarker != null) {
            try { faceLandmarker.close(); } catch (Exception ignored) { }
            faceLandmarker = null;
        }
        cameraExecutor.shutdown();
        modelExecutor.shutdown();
        if (player != null) {
            player.release();
            player = null;
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
            ttsReady = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
