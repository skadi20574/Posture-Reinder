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

    private static String key(int mode) {
        return mode == Vision.MODE_SIDE ? "baseline_side" : "baseline_front";
    }

    /** Baseline for one camera placement; front and side calibrations are kept apart. */
    public Vision.Baseline baseline(int mode) {
        String raw = sp.getString(key(mode), null);
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            Vision.Baseline b = new Vision.Baseline();
            b.mode = o.optInt("mode", mode);
            b.neckFwd = o.optDouble("neckFwd", 0);
            b.headHeight = o.optDouble("headHeight", 0);
            b.shY = o.optDouble("shY", 0);
            b.shTilt = o.optDouble("shTilt", 0);
            b.lean = o.optDouble("lean", 0);
            b.torso = o.optDouble("torso", 0);
            b.hasTorso = o.optBoolean("hasTorso", false);
            b.sw = o.optDouble("sw", 0);
            b.imgH = o.optDouble("imgH", 0);
            b.sNeck = o.optDouble("sNeck", 0);
            b.sShdrop = o.optDouble("sShdrop", 0);
            b.sHead = o.optDouble("sHead", 0);
            b.sTorso = o.optDouble("sTorso", 0);
            b.sTilt = o.optDouble("sTilt", 0);
            b.sLean = o.optDouble("sLean", 0);
            b.sDist = o.optDouble("sDist", 0);
            b.headW = o.optDouble("headW", 0);
            b.sHeadW = o.optDouble("sHeadW", 0);
            b.fwdHead = o.optDouble("fwdHead", 0);
            b.trunk = o.optDouble("trunk", 0);
            b.spine = o.optDouble("spine", 0);
            b.headDrop = o.optDouble("headDrop", 0);
            b.scaleRef = o.optDouble("scaleRef", 0);
            b.sFwdHead = o.optDouble("sFwdHead", 0);
            b.sTrunk = o.optDouble("sTrunk", 0);
            b.sSpine = o.optDouble("sSpine", 0);
            b.sHeadDrop = o.optDouble("sHeadDrop", 0);
            b.sSideDist = o.optDouble("sSideDist", 0);
            b.samples = o.optInt("samples", 0);
            b.createdAt = o.optLong("createdAt", 0);
            return b.valid() ? b : null;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setBaseline(int mode, Vision.Baseline b) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", b.mode);
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
            o.put("headW", b.headW);
            o.put("sHeadW", b.sHeadW);
            o.put("fwdHead", b.fwdHead);
            o.put("trunk", b.trunk);
            o.put("spine", b.spine);
            o.put("headDrop", b.headDrop);
            o.put("scaleRef", b.scaleRef);
            o.put("sFwdHead", b.sFwdHead);
            o.put("sTrunk", b.sTrunk);
            o.put("sSpine", b.sSpine);
            o.put("sHeadDrop", b.sHeadDrop);
            o.put("sSideDist", b.sSideDist);
            o.put("samples", b.samples);
            o.put("createdAt", b.createdAt);
            sp.edit().putString(key(mode), o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void clearBaseline(int mode) {
        sp.edit().remove(key(mode)).apply();
    }

    public String baselineTime(int mode) {
        Vision.Baseline b = baseline(mode);
        if (b == null) return "未校准";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(b.createdAt));
    }
}
