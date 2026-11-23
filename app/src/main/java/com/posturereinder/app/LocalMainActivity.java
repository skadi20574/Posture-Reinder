package com.posturereinder.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LocalMainActivity extends Activity {

    private TextView tvSub;

    private static final int REQ_PERM = 9;

    private TextView statusText;
    private TextView scoreText;
    private TextView detailText;
    private TextView hintText;
    private Button btnStart;
    private Button btnCal;
    private Button btnVoice;
    private Button btnSettings;
    private Button btnReset;

    private LocalPrefs prefs;
    private Settings settings;
    private boolean receiverOn;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String alert = intent.getStringExtra(LocalService.EXTRA_ALERT);
            if (alert != null) {
                Toast.makeText(LocalMainActivity.this, alert, Toast.LENGTH_SHORT).show();
                return;
            }
            applyState(intent.getStringExtra(LocalService.EXTRA_STATE),
                    intent.getIntExtra(LocalService.EXTRA_SCORE, -1),
                    intent.getStringExtra(LocalService.EXTRA_MSG),
                    intent.getBooleanExtra(LocalService.EXTRA_RUNNING, false),
                    intent.getStringExtra(LocalService.EXTRA_LABEL),
                    intent.getStringExtra(LocalService.EXTRA_ATTENTION),
                    intent.getIntExtra(LocalService.EXTRA_FOCUS, 0));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local);
        prefs = new LocalPrefs(this);
        settings = new Settings(this);

        statusText = findViewById(R.id.statusText);
        scoreText = findViewById(R.id.scoreText);
        tvSub = findViewById(R.id.tvSub);
        detailText = findViewById(R.id.detailText);
        hintText = findViewById(R.id.hintText);
        btnStart = findViewById(R.id.btnStart);
        btnCal = findViewById(R.id.btnCal);
        btnVoice = findViewById(R.id.btnVoice);
        btnSettings = findViewById(R.id.btnSettings);
        btnReset = findViewById(R.id.btnReset);

        updateVoiceLabel();

        btnStart.setOnClickListener(v -> {
            if (serviceRunning()) {
                startService(new Intent(this, LocalService.class)
                        .setAction(LocalService.ACTION_STOP));
                prefs.setRunning(false);
                applyState("IDLE", -1, "已停止", false, "—", "—", 0);
            } else {
                ensurePermissionsThenStart();
            }
        });

        btnCal.setOnClickListener(v -> {
            if (!serviceRunning()) {
                Toast.makeText(this, "请先点「开始监控」", Toast.LENGTH_SHORT).show();
                return;
            }
            startService(new Intent(this, LocalService.class)
                    .setAction(LocalService.ACTION_CALIBRATE));
        });

        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.btnStats).setOnClickListener(v ->
                startActivity(new Intent(this, StatsActivity.class)));

        btnVoice.setOnClickListener(v -> {
            settings.voice = !settings.voice;
            settings.save();
            prefs.setVoice(settings.voice);
            updateVoiceLabel();
            sendBroadcast(new Intent(LocalService.ACTION_RELOAD).setPackage(getPackageName()));
        });

        btnReset.setOnClickListener(v -> {
            prefs.clearBaseline(settings.cameraMode);
            scoreText.setText("—");
            hintText.setText("已清除校准数据，请重新校准");
            Toast.makeText(this, "已清除校准", Toast.LENGTH_SHORT).show();
        });

        boolean running = serviceRunning();
        prefs.setRunning(running);
        applyState("IDLE", -1, running ? "正在运行" : null, running, "—", "—", 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        settings.reload();
        prefs.setVoice(settings.voice);
        updateVoiceLabel();
        applyTheme();
        if (tvSub != null) {
            tvSub.setText(settings.cameraMode == Vision.MODE_SIDE
                    ? "POSTURE · GUARD · 侧面" : "POSTURE · GUARD · 正面");
        }
    }

    private void applyTheme() {
        android.view.View root = findViewById(R.id.root);
        GlowView glow = findViewById(R.id.glow);
        if (settings.theme == 1) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable(
                            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0xFF0B0C0E, 0xFF102024, 0xFF0B0C0E});
            if (root != null) root.setBackground(g);
            if (glow != null) glow.setActive(true);
        } else {
            if (root != null) root.setBackgroundColor(getColor(R.color.plate));
            if (glow != null) glow.setActive(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(LocalService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        receiverOn = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (receiverOn) {
            unregisterReceiver(receiver);
            receiverOn = false;
        }
    }

    private void updateVoiceLabel() {
        btnVoice.setText(settings.voice ? "语音：开" : "语音：关");
    }

    private boolean serviceRunning() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (android.app.ActivityManager.RunningServiceInfo si : am.getRunningServices(50)) {
                if (LocalService.class.getName().equals(si.service.getClassName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void ensurePermissionsThenStart() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            startWatching();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startWatching();
        } else {
            Toast.makeText(this, "没有摄像头权限，无法监控", Toast.LENGTH_LONG).show();
            applyState("ERROR", -1, "缺少摄像头权限", false, "—", "—", 0);
        }
    }

    private void startWatching() {
        Intent i = new Intent(this, LocalService.class).setAction(LocalService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        prefs.setRunning(true);
        applyState("IDLE", -1, "正在启动…", true, "—", "—", 0);
    }

    private void applyState(String state, int score, String msg, boolean running,
                            String label, String attention, int focusSec) {
        String text;
        int color;
        switch (state == null ? "" : state) {
            case "GOOD":
                text = "坐姿端正";
                color = getColor(R.color.good);
                break;
            case "BAD":
                text = "需要调整";
                color = getColor(R.color.bad);
                break;
            case "AWAY":
                text = "离开座位";
                color = getColor(R.color.fg3);
                break;
            case "WATCH":
                text = "识别中";
                color = getColor(R.color.fg2);
                break;
            case "CALIB":
                text = "未校准";
                color = getColor(R.color.warn);
                if (msg == null) msg = "点「校准 10 秒」做一次校准";
                break;
            case "CALIBRATING":
                text = "校准中";
                color = getColor(R.color.warn);
                break;
            case "READY":
                text = "运行中";
                color = getColor(R.color.accent);
                break;
            case "ERROR":
                text = "出错了";
                color = getColor(R.color.bad);
                break;
            default:
                text = running ? "运行中" : "未启动";
                color = getColor(R.color.fg3);
                break;
        }
        statusText.setText(text);
        statusText.setTextColor(color);

        String posture = label == null ? "—" : label;
        String baseTime = prefs.baselineTime(settings.cameraMode);
        if ("未校准".equals(baseTime)) {
            scoreText.setText("未校准");
        } else {
            scoreText.setText((score >= 0 ? "评分 " + score : "—") + " · " + posture + " · 校准 " + baseTime);
        }

        StringBuilder detail = new StringBuilder();
        detail.append("注视 ").append(attention == null ? "—" : attention);
        if (focusSec > 0) {
            detail.append(String.format(Locale.CHINA, " · 连续专注 %d 分 %02d 秒",
                    focusSec / 60, focusSec % 60));
        }
        detailText.setText(detail.toString());

        if (msg != null) hintText.setText(msg);
        btnStart.setText(running ? "停止监控" : "开始监控");
    }
}
