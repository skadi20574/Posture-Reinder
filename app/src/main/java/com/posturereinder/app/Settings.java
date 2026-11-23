package com.posturereinder.app;

import android.content.Context;
import android.content.SharedPreferences;

/** All tunables of the offline app. */
public final class Settings {

    public static final int DWELL_DEFAULT = 6;
    public static final int COOLDOWN_DEFAULT = 120;
    public static final int REPEAT_DEFAULT = 3;
    public static final int INTERVAL_DEFAULT = 280;
    public static final int FOCUS_REST_DEFAULT = 40;

    private final SharedPreferences sp;

    public int dwellSec = DWELL_DEFAULT;
    public int cooldownSec = COOLDOWN_DEFAULT;
    public int maxRepeat = REPEAT_DEFAULT;
    public int intervalMs = INTERVAL_DEFAULT;
    public int focusRestMin = FOCUS_REST_DEFAULT;
    public float sensitivity = 1.0f;
    public float blinkThreshold = 0.65f;
    public float attentionSens = 1.0f;
    public int cameraMode = Vision.MODE_FRONT;
    public int theme = 0;
    public boolean voice = true;
    public boolean attention = true;

    public Settings(Context c) {
        sp = c.getSharedPreferences("posture_local", Context.MODE_PRIVATE);
        reload();
    }

    public void reload() {
        dwellSec = sp.getInt("dwell", DWELL_DEFAULT);
        cooldownSec = sp.getInt("cooldown", COOLDOWN_DEFAULT);
        maxRepeat = sp.getInt("repeat", REPEAT_DEFAULT);
        intervalMs = sp.getInt("interval", INTERVAL_DEFAULT);
        focusRestMin = sp.getInt("focusrest", FOCUS_REST_DEFAULT);
        sensitivity = sp.getFloat("sensitivity", 1.0f);
        blinkThreshold = sp.getFloat("blink", 0.65f);
        attentionSens = sp.getFloat("attsens", 1.0f);
        cameraMode = sp.getInt("cameraMode", Vision.MODE_FRONT);
        theme = sp.getInt("theme", 0);
        voice = sp.getBoolean("voice", true);
        attention = sp.getBoolean("attention", true);
    }

    public void save() {
        sp.edit()
                .putInt("dwell", dwellSec)
                .putInt("cooldown", cooldownSec)
                .putInt("repeat", maxRepeat)
                .putInt("interval", intervalMs)
                .putInt("focusrest", focusRestMin)
                .putFloat("sensitivity", sensitivity)
                .putFloat("blink", blinkThreshold)
                .putFloat("attsens", attentionSens)
                .putInt("cameraMode", cameraMode)
                .putInt("theme", theme)
                .putBoolean("voice", voice)
                .putBoolean("attention", attention)
                .apply();
    }

    public void reset() {
        dwellSec = DWELL_DEFAULT;
        cooldownSec = COOLDOWN_DEFAULT;
        maxRepeat = REPEAT_DEFAULT;
        intervalMs = INTERVAL_DEFAULT;
        focusRestMin = FOCUS_REST_DEFAULT;
        sensitivity = 1.0f;
        blinkThreshold = 0.65f;
        attentionSens = 1.0f;
        cameraMode = Vision.MODE_FRONT;
        theme = 0;
        voice = true;
        attention = true;
        save();
    }

    public int fps() {
        return Math.max(1, Math.round(1000f / Math.max(120, intervalMs)));
    }
}
