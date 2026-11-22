package com.posturereinder.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public final class LocalPrefs {
    private final SharedPreferences sp;

    public LocalPrefs(Context c) {
        sp = c.getSharedPreferences("posture_local", Context.MODE_PRIVATE);
    }

    public double sensitivity() {
        return sp.getFloat("sensitivity", 1.0f);
    }

    public void setSensitivity(double v) {
        sp.edit().putFloat("sensitivity", (float) v).apply();
    }

    public boolean voice() {
        return sp.getBoolean("voice", true);
    }

    public void setVoice(boolean v) {
        sp.edit().putBoolean("voice", v).apply();
    }

    public boolean running() {
        return sp.getBoolean("running", false);
    }

    public void setRunning(boolean v) {
        sp.edit().putBoolean("running", v).apply();
    }

    public Vision.Baseline baseline() {
        String raw = sp.getString("baseline", null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Vision.Baseline b = new Vision.Baseline();
            b.neckFwd = o.getDouble("neckFwd");
            b.headHeight = o.getDouble("headHeight");
            b.shY = o.getDouble("shY");
            b.shTilt = o.getDouble("shTilt");
            b.lean = o.getDouble("lean");
            b.torso = o.optDouble("torso", 0);
            b.hasTorso = o.optBoolean("hasTorso", false);
            b.sw = o.getDouble("sw");
            b.imgH = o.getDouble("imgH");
            b.sNeck = o.getDouble("sNeck");
            b.sShdrop = o.getDouble("sShdrop");
            b.sHead = o.getDouble("sHead");
            b.sTorso = o.getDouble("sTorso");
            b.sTilt = o.getDouble("sTilt");
            b.sLean = o.getDouble("sLean");
            b.sDist = o.getDouble("sDist");
            b.samples = o.optInt("samples", 0);
            b.createdAt = o.optLong("createdAt", 0);
            return b.valid() ? b : null;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setBaseline(Vision.Baseline b) {
        try {
            JSONObject o = new JSONObject();
            o.put("neckFwd", b.neckFwd);
            o.put("headHeight", b.headHeight);
            o.put("shY", b.shY);
            o.put("shTilt", b.shTilt);
            o.put("lean", b.lean);
            o.put("torso", b.torso);
            o.put("hasTorso", b.hasTorso);
            o.put("sw", b.sw);
            o.put("imgH", b.imgH);
            o.put("sNeck", b.sNeck);
            o.put("sShdrop", b.sShdrop);
            o.put("sHead", b.sHead);
            o.put("sTorso", b.sTorso);
            o.put("sTilt", b.sTilt);
            o.put("sLean", b.sLean);
            o.put("sDist", b.sDist);
            o.put("samples", b.samples);
            o.put("createdAt", b.createdAt);
            sp.edit().putString("baseline", o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void clearBaseline() {
        sp.edit().remove("baseline").apply();
    }

    public String baselineTime() {
        Vision.Baseline b = baseline();
        if (b == null) return "未校准";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(b.createdAt));
    }
}
