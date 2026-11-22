package com.posturereinder.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

public class SettingsActivity extends Activity {

    private static final int[] INTERVALS = {1000, 800, 650, 500, 400, 320, 250, 200};
    private static final int REQ_APK = 21;

    private Settings settings;
    private boolean loading = true;

    private SeekBar sbDwell, sbCooldown, sbRepeat, sbFps, sbFocus, sbBlink;
    private TextView tvDwell, tvCooldown, tvRepeat, tvFps, tvFocus, tvBlink, dataInfo;
    private Switch swAttention, swVoice;
    private Button sensStrict, sensNormal, sensLoose;
    private Button attStrict, attNormal, attLoose;

    private Button[] tabs;
    private View[] pages;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settings = new Settings(this);

        sbDwell = findViewById(R.id.sbDwell);
        sbCooldown = findViewById(R.id.sbCooldown);
        sbRepeat = findViewById(R.id.sbRepeat);
        sbFps = findViewById(R.id.sbFps);
        sbFocus = findViewById(R.id.sbFocus);
        sbBlink = findViewById(R.id.sbBlink);
        tvDwell = findViewById(R.id.tvDwell);
        tvCooldown = findViewById(R.id.tvCooldown);
        tvRepeat = findViewById(R.id.tvRepeat);
        tvFps = findViewById(R.id.tvFps);
        tvFocus = findViewById(R.id.tvFocus);
        tvBlink = findViewById(R.id.tvBlink);
        dataInfo = findViewById(R.id.dataInfo);
        swAttention = findViewById(R.id.swAttention);
        swVoice = findViewById(R.id.swVoice);
        sensStrict = findViewById(R.id.sensStrict);
        sensNormal = findViewById(R.id.sensNormal);
        sensLoose = findViewById(R.id.sensLoose);
        attStrict = findViewById(R.id.attStrict);
        attNormal = findViewById(R.id.attNormal);
        attLoose = findViewById(R.id.attLoose);

        tabs = new Button[]{findViewById(R.id.tabRemind), findViewById(R.id.tabMonitor),
                findViewById(R.id.tabFocus), findViewById(R.id.tabUi), findViewById(R.id.tabData)};
        pages = new View[]{findViewById(R.id.pageRemind), findViewById(R.id.pageMonitor),
                findViewById(R.id.pageFocus), findViewById(R.id.pageUi), findViewById(R.id.pageData)};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(v -> selectTab(idx));
        }

        sbDwell.setProgress(settings.dwellSec - 2);
        sbCooldown.setProgress(settings.cooldownSec / 5 - 1);
        sbRepeat.setProgress(settings.maxRepeat <= 0 ? 20 : settings.maxRepeat - 1);
        sbFps.setProgress(nearestInterval(settings.intervalMs));
        sbFocus.setProgress(settings.focusRestMin - 10);
        sbBlink.setProgress(Math.round(settings.blinkThreshold * 100) - 30);
        swAttention.setChecked(settings.attention);
        swVoice.setChecked(settings.voice);
        paintSensitivity();
        paintAttention();
        paintTheme();
        paintValues();
        selectTab(0);
        loading = false;

        sbDwell.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.dwellSec = p + 2;
                commit();
            }
        });
        sbCooldown.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.cooldownSec = (p + 1) * 5;
                commit();
            }
        });
        sbRepeat.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.maxRepeat = p >= 20 ? 0 : p + 1;
                commit();
            }
        });
        sbFps.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.intervalMs = INTERVALS[Math.max(0, Math.min(INTERVALS.length - 1, p))];
                commit();
            }
        });
        sbFocus.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.focusRestMin = p + 10;
                commit();
            }
        });
        sbBlink.setOnSeekBarChangeListener(new Simple() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                settings.blinkThreshold = (30 + p) / 100f;
                commit();
            }
        });
        swAttention.setOnCheckedChangeListener((b, v) -> {
            settings.attention = v;
            commit();
        });
        swVoice.setOnCheckedChangeListener((b, v) -> {
            settings.voice = v;
            commit();
        });
        sensStrict.setOnClickListener(v -> setSensitivity(0.8));
        sensNormal.setOnClickListener(v -> setSensitivity(1.0));
        sensLoose.setOnClickListener(v -> setSensitivity(1.3));
        attStrict.setOnClickListener(v -> setAttention(0.8));
        attNormal.setOnClickListener(v -> setAttention(1.0));
        attLoose.setOnClickListener(v -> setAttention(1.3));
        findViewById(R.id.themeMinimal).setOnClickListener(v -> chooseTheme(0));
        findViewById(R.id.themeElegant).setOnClickListener(v -> chooseTheme(1));
        findViewById(R.id.btnDebugPreview).setOnClickListener(v ->
                startActivity(new Intent(this, DebugActivity.class)));
        findViewById(R.id.btnStatsPage).setOnClickListener(v ->
                startActivity(new Intent(this, StatsActivity.class)));
        findViewById(R.id.btnInstallApk).setOnClickListener(v -> pickApk());
        findViewById(R.id.btnDefault).setOnClickListener(v -> resetDefaults());
    }

    // ------------------------------------------------------------------ tabs

    private void selectTab(int idx) {
        int accent = getColor(R.color.accent);
        int dim = getColor(R.color.fg3);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == idx ? accent : dim);
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
        if (idx == 4) refreshDataInfo();
    }

    private void refreshDataInfo() {
        try {
            StatsDb db = new StatsDb(this);
            int n = db.recent(100000).size();
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            dataInfo.setText(String.format(Locale.CHINA,
                    "版本 %s · 本机共有 %d 条监控记录%n记录只保存在本机，导出后可换机导入。", ver, n));
        } catch (Exception e) {
            dataInfo.setText("版本信息读取失败");
        }
    }

    // ------------------------------------------------------------------ actions

    private void setSensitivity(double v) {
        settings.sensitivity = (float) v;
        paintSensitivity();
        commit();
    }

    private void setAttention(double v) {
        settings.attentionSens = (float) v;
        paintAttention();
        commit();
    }

    private void chooseTheme(int t) {
        settings.theme = t;
        settings.save();
        paintTheme();
        notifyService();
    }

    private void resetDefaults() {
        settings.reset();
        settings.reload();
        sbDwell.setProgress(settings.dwellSec - 2);
        sbCooldown.setProgress(settings.cooldownSec / 5 - 1);
        sbRepeat.setProgress(settings.maxRepeat <= 0 ? 20 : settings.maxRepeat - 1);
        sbFps.setProgress(nearestInterval(settings.intervalMs));
        sbFocus.setProgress(settings.focusRestMin - 10);
        sbBlink.setProgress(Math.round(settings.blinkThreshold * 100) - 30);
        swAttention.setChecked(settings.attention);
        swVoice.setChecked(settings.voice);
        paintSensitivity();
        paintAttention();
        paintTheme();
        paintValues();
        notifyService();
        Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
    }

    private void commit() {
        if (loading) return;
        settings.save();
        paintValues();
        notifyService();
    }

    private void notifyService() {
        sendBroadcast(new Intent(LocalService.ACTION_RELOAD).setPackage(getPackageName()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        notifyService();
    }

    private void paintValues() {
        tvDwell.setText(settings.dwellSec + " 秒");
        tvCooldown.setText(settings.cooldownSec + " 秒");
        tvRepeat.setText(settings.maxRepeat <= 0 ? "∞ 次" : settings.maxRepeat + " 次");
        tvFps.setText(String.format(Locale.CHINA, "%.1f 帧/秒", 1000.0 / settings.intervalMs));
        tvFocus.setText(settings.focusRestMin + " 分钟");
        tvBlink.setText(Math.round(settings.blinkThreshold * 100) + "%");
    }

    private void paintSensitivity() {
        int accent = getColor(R.color.accent);
        int dim = getColor(R.color.fg3);
        boolean strict = Math.abs(settings.sensitivity - 0.8f) < 0.01;
        boolean normal = Math.abs(settings.sensitivity - 1.0f) < 0.01;
        boolean loose = Math.abs(settings.sensitivity - 1.3f) < 0.01;
        sensStrict.setTextColor(strict ? accent : dim);
        sensNormal.setTextColor(normal ? accent : dim);
        sensLoose.setTextColor(loose ? accent : dim);
    }

    private void paintAttention() {
        int accent = getColor(R.color.accent);
        int dim = getColor(R.color.fg3);
        boolean strict = Math.abs(settings.attentionSens - 0.8f) < 0.01;
        boolean normal = Math.abs(settings.attentionSens - 1.0f) < 0.01;
        boolean loose = Math.abs(settings.attentionSens - 1.3f) < 0.01;
        attStrict.setTextColor(strict ? accent : dim);
        attNormal.setTextColor(normal ? accent : dim);
        attLoose.setTextColor(loose ? accent : dim);
    }

    private void paintTheme() {
        int accent = getColor(R.color.accent);
        int dim = getColor(R.color.fg3);
        ((Button) findViewById(R.id.themeMinimal)).setTextColor(settings.theme == 0 ? accent : dim);
        ((Button) findViewById(R.id.themeElegant)).setTextColor(settings.theme == 1 ? accent : dim);
    }

    private static int nearestInterval(int ms) {
        int best = 0;
        int diff = Integer.MAX_VALUE;
        for (int i = 0; i < INTERVALS.length; i++) {
            int d = Math.abs(INTERVALS[i] - ms);
            if (d < diff) {
                diff = d;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ apk update

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_APK);
        } catch (Exception e) {
            Toast.makeText(this, "没有文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_APK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            File out = new File(getCacheDir(), "update.apk");
            try (InputStream is = getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(out)) {
                if (is == null) throw new IllegalStateException("无法读取文件");
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            Uri apkUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", out);
            Intent it = new Intent(Intent.ACTION_VIEW);
            it.setDataAndType(apkUri, "application/vnd.android.package-archive");
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(this, "安装失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private abstract static class Simple implements SeekBar.OnSeekBarChangeListener {
        public void onStartTrackingTouch(SeekBar seekBar) { }

        public void onStopTrackingTouch(SeekBar seekBar) { }
    }
}
