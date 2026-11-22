package com.posturereinder.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Posture metrics, personal baseline, scoring and the alert state machine.
 * Pure Java port of the server-side vision.py so that everything can run on
 * the phone without any network.
 */
public final class Vision {

    public static final int NOSE = 0;
    public static final int L_EAR = 7, R_EAR = 8;
    public static final int L_SHOULDER = 11, R_SHOULDER = 12;
    public static final int L_HIP = 23, R_HIP = 24;

    public static final double MIN_SW_RATIO = 0.035;
    public static final double MIN_SW_PX = 12.0;

    public static final String[] ORDER = {"neck", "shdrop", "head", "torso", "tilt", "lean", "dist"};
    public static final String[] LABEL = {"头前伸", "坐高下沉", "头部高度", "躯干前倾", "肩线倾斜", "身体侧偏", "靠近屏幕"};
    public static final double[] WEIGHT = {1.2, 1.3, 0.8, 1.0, 0.6, 0.6, 0.4};

    static final double K_ENTER = 2.5;
    static final double K_SPAN = 2.0;

    static final Map<String, Double> FLOOR = new HashMap<>();

    static {
        FLOOR.put("neck", 0.030);
        FLOOR.put("shdrop", 0.025);
        FLOOR.put("head", 0.030);
        FLOOR.put("torso", 3.0);
        FLOOR.put("tilt", 0.020);
        FLOOR.put("lean", 0.030);
        FLOOR.put("dist", 0.030);
    }

    public static final Map<String, String[]> ALERT = new HashMap<>();

    static {
        ALERT.put("neck", new String[]{"head_up", "头抬高一点，别低头。"});
        ALERT.put("head", new String[]{"sit_up", "坐直一点，背挺起来。"});
        ALERT.put("shdrop", new String[]{"sit_up", "坐直一点，背挺起来。"});
        ALERT.put("torso", new String[]{"sit_up", "坐直一点，背挺起来。"});
        ALERT.put("tilt", new String[]{"shoulders", "肩膀放平，放松一点。"});
        ALERT.put("lean", new String[]{"center", "身子往中间坐一坐。"});
        ALERT.put("dist", new String[]{"far", "离书远一点，让眼睛休息一下。"});
    }

    private Vision() {
    }

    // ------------------------------------------------------------------ frame

    public static final class Frame {
        public double neckFwd;
        public double headHeight;
        public double shY;
        public double shTilt;
        public double lean;
        public double torso;
        public boolean hasTorso;
        public double sw;
        public double imgH;
        public double headW;   // ear span in pixels, 0 when unavailable
    }

    /** @param nx normalised x [0,1], @param ny normalised y, @param nv visibility */
    public static Frame measure(double[] nx, double[] ny, double[] nv, int w, int h) {
        if (nx == null || nx.length < 33) return null;
        double[] xs = new double[33];
        double[] ys = new double[33];
        double[] vs = new double[33];
        for (int i = 0; i < 33; i++) {
            xs[i] = nx[i] * w;
            ys[i] = ny[i] * h;
            vs[i] = nv[i];
        }

        if (vs[L_SHOULDER] < 0.5 || vs[R_SHOULDER] < 0.5) return null;
        double sw = Math.hypot(xs[L_SHOULDER] - xs[R_SHOULDER], ys[L_SHOULDER] - ys[R_SHOULDER]);
        if (sw < Math.max(MIN_SW_PX, w * MIN_SW_RATIO)) return null;

        double shX = (xs[L_SHOULDER] + xs[R_SHOULDER]) / 2.0;
        double shY = (ys[L_SHOULDER] + ys[R_SHOULDER]) / 2.0;

        double earX;
        double earY;
        boolean leOk = vs[L_EAR] >= 0.5;
        boolean reOk = vs[R_EAR] >= 0.5;
        if (leOk && reOk) {
            earX = (xs[L_EAR] + xs[R_EAR]) / 2.0;
            earY = (ys[L_EAR] + ys[R_EAR]) / 2.0;
        } else if (leOk) {
            earX = xs[L_EAR];
            earY = ys[L_EAR];
        } else if (reOk) {
            earX = xs[R_EAR];
            earY = ys[R_EAR];
        } else {
            earX = xs[NOSE];
            earY = ys[NOSE] - 0.15 * sw;
        }
        if (earY > shY) {
            earX = xs[NOSE];
            earY = Math.min(ys[NOSE], shY - 0.1 * sw);
        }

        List<Double> offs = new ArrayList<>();
        if (leOk) offs.add(Math.abs(xs[L_EAR] - xs[L_SHOULDER]));
        if (reOk) offs.add(Math.abs(xs[R_EAR] - xs[R_SHOULDER]));
        double neckFwd;
        if (!offs.isEmpty()) {
            double sum = 0;
            for (double o : offs) sum += o;
            neckFwd = sum / offs.size() / sw;
        } else {
            neckFwd = Math.abs(earX - shX) / sw;
        }

        Frame f = new Frame();
        f.neckFwd = neckFwd;
        f.headHeight = (shY - earY) / sw;
        f.shY = shY / h;
        f.shTilt = Math.abs(ys[L_SHOULDER] - ys[R_SHOULDER]) / sw;
        f.lean = Math.abs(xs[NOSE] - shX) / sw;
        f.sw = sw;
        f.imgH = h;
        if (leOk && reOk) {
            f.headW = Math.hypot(xs[L_EAR] - xs[R_EAR], ys[L_EAR] - ys[R_EAR]);
        }

        if (vs[L_HIP] >= 0.45 && vs[R_HIP] >= 0.45) {
            double hipX = (xs[L_HIP] + xs[R_HIP]) / 2.0;
            double hipY = (ys[L_HIP] + ys[R_HIP]) / 2.0;
            double dy = shY - hipY;
            double dx = shX - hipX;
            if (Math.abs(dy) > 1e-3) {
                f.torso = Math.toDegrees(Math.atan2(Math.abs(dx), Math.abs(dy)));
                f.hasTorso = true;
            }
        }
        return f;
    }

    // --------------------------------------------------------------- baseline

    public static final class Baseline {
        public double neckFwd, headHeight, shY, shTilt, lean, torso, sw, imgH;
        public boolean hasTorso;
        public double sNeck, sShdrop, sHead, sTorso, sTilt, sLean, sDist;
        public double headW, sHeadW;
        public int samples;
        public long createdAt;

        public boolean valid() {
            return sw > 0;
        }
    }

    public static Baseline fit(List<Frame> frames) {
        if (frames == null || frames.size() < 15) {
            throw new IllegalArgumentException("有效样本太少，请确认画面里能看到双肩后重新校准");
        }
        List<Double> neck = new ArrayList<>();
        List<Double> head = new ArrayList<>();
        List<Double> shy = new ArrayList<>();
        List<Double> tilt = new ArrayList<>();
        List<Double> lean = new ArrayList<>();
        List<Double> sws = new ArrayList<>();
        List<Double> torso = new ArrayList<>();
        List<Double> hs = new ArrayList<>();
        for (Frame f : frames) {
            neck.add(f.neckFwd);
            head.add(f.headHeight);
            shy.add(f.shY);
            tilt.add(f.shTilt);
            lean.add(f.lean);
            sws.add(f.sw);
            hs.add(f.imgH);
            if (f.hasTorso) torso.add(f.torso);
        }
        double medSw = median(sws);
        double imgH = median(hs);

        Baseline b = new Baseline();
        b.neckFwd = median(neck);
        b.headHeight = median(head);
        b.shY = median(shy);
        b.shTilt = median(tilt);
        b.lean = median(lean);
        b.sw = medSw;
        b.imgH = imgH;
        b.samples = frames.size();
        b.createdAt = System.currentTimeMillis();
        if (torso.size() >= 8) {
            b.torso = median(torso);
            b.hasTorso = true;
        }
        b.sNeck = Math.max(1.4826 * mad(neck), FLOOR.get("neck"));
        b.sHead = Math.max(1.4826 * mad(head), FLOOR.get("head"));
        b.sShdrop = Math.max(1.4826 * mad(shy) * imgH / medSw, FLOOR.get("shdrop"));
        b.sTilt = Math.max(1.4826 * mad(tilt), FLOOR.get("tilt"));
        b.sLean = Math.max(1.4826 * mad(lean), FLOOR.get("lean"));
        b.sDist = Math.max(1.4826 * mad(sws) / medSw, FLOOR.get("dist"));
        b.sTorso = (torso.size() >= 8)
                ? Math.max(1.4826 * mad(torso), FLOOR.get("torso"))
                : FLOOR.get("torso");

        List<Double> heads = new ArrayList<>();
        for (Frame f : frames) if (f.headW > 1.0) heads.add(f.headW);
        if (heads.size() >= 8) {
            b.headW = median(heads);
            b.sHeadW = Math.max(1.4826 * mad(heads) / Math.max(1.0, b.headW), 0.030);
        } else {
            b.headW = 0;
            b.sHeadW = 0.05;
        }
        return b;
    }

    public static Map<String, Double> deviations(Frame f, Baseline b) {
        Map<String, Double> out = new HashMap<>();
        if (f == null || b == null || !b.valid()) return out;
        out.put("neck", (f.neckFwd - b.neckFwd) / b.sNeck);
        out.put("head", (b.headHeight - f.headHeight) / b.sHead);
        out.put("shdrop", (f.shY - b.shY) * f.imgH / f.sw / b.sShdrop);
        out.put("tilt", (f.shTilt - b.shTilt) / b.sTilt);
        out.put("lean", (f.lean - b.lean) / b.sLean);
        out.put("dist", (f.sw / b.sw - 1.0) / b.sDist);
        if (b.hasTorso && f.hasTorso) {
            out.put("torso", (f.torso - b.torso) / b.sTorso);
        }
        return out;
    }

    // ------------------------------------------------------------------ score

    public static final class Metric {
        public String key;
        public String label;
        public double z;
        public double bad;
        public double weight;
    }

    public static final class Reading {
        public boolean present;
        public int score = 100;
        public double level;
        public String dominant;
        public double headRatio = 1.0;
        public List<Metric> metrics = new ArrayList<>();
    }

    public static Reading evaluate(Frame f, Baseline b, double sensitivity) {
        Reading r = new Reading();
        if (f == null) {
            r.present = false;
            return r;
        }
        r.present = true;
        if (b.headW > 0 && f.headW > 1.0) {
            r.headRatio = f.headW / b.headW;
        }
        Map<String, Double> devs = deviations(f, b);
        if (devs.isEmpty()) return r;

        double k0 = K_ENTER * sensitivity;
        double totalW = 0, acc = 0, maxBad = 0;
        String dominant = null;
        double dominantBad = -1;

        for (String key : ORDER) {
            Double zv = devs.get(key);
            if (zv == null) continue;
            double z = zv;
            double bad = clamp((z - k0) / K_SPAN, 0, 1);
            double w = WEIGHT[indexOf(key)];
            totalW += w;
            acc += w * bad;
            if (bad > maxBad) maxBad = bad;
            if (bad > dominantBad) {
                dominantBad = bad;
                dominant = key;
            }
            Metric m = new Metric();
            m.key = key;
            m.label = LABEL[indexOf(key)];
            m.z = z;
            m.bad = bad;
            m.weight = w;
            r.metrics.add(m);
        }
        double avg = totalW > 0 ? acc / totalW : 0;
        r.level = Math.max(avg, 0.70 * maxBad);
        int score = (int) Math.round(100 * (1.0 - clamp(r.level / 0.75, 0, 1)));
        if (maxBad < 0.02) score = 100;
        r.score = Math.max(0, Math.min(100, score));
        r.dominant = dominantBad > 0 ? dominant : null;
        return r;
    }

    private static int indexOf(String key) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(key)) return i;
        return 0;
    }

    /** Human readable posture class, driven by the same z-scores as the score. */
    public static String postureLabel(Reading r, double headRatio) {
        if (r == null || !r.present) return "无人";
        // head size is the reliable front/back discriminator: closer = bigger
        if (headRatio > 1.07) return "前倾";
        if (headRatio < 0.93) return "后仰";
        if (r.score >= 80) return "端正";
        Map<String, Double> z = new HashMap<>();
        for (Metric m : r.metrics) z.put(m.key, m.z);
        double dist = z.containsKey("dist") ? z.get("dist") : 0;
        double drop = z.containsKey("shdrop") ? z.get("shdrop") : 0;
        double lean = z.containsKey("lean") ? z.get("lean") : 0;
        double tilt = z.containsKey("tilt") ? z.get("tilt") : 0;
        double neck = z.containsKey("neck") ? z.get("neck") : 0;
        double head = z.containsKey("head") ? z.get("head") : 0;
        if (dist > 2.2) return "前倾";
        if (dist < -2.2) return "后仰";
        if (drop > 2.5) return "前倾";
        if (lean > 2.0) return "侧倾";
        if (tilt > 2.0) return "歪肩";
        if (neck > 2.0) return "头前伸";
        if (head > 2.0) return "低头";
        return "需调整";
    }

    public static String postureLabel(Reading r) {
        return postureLabel(r, 1.0);
    }

    // ------------------------------------------------------------ state machine

    public static final class Payload {
        public String state;
        public int score;
        public String alertKey;
        public String alertText;
        public int sittingSec;
        public int goodSec;
        public int badSec;
        public int alerts;
    }

    public static final class StateMachine {
        public String state = "AWAY";
        private Long badSince, goodSince, lastSeen;
        public double sittingSec, goodSec, badSec, awaySec;
        private long lastTick = 0;
        private long lastAlertAt = 0;
        public int alerts = 0;
        private int streakAlerts = 0;
        private boolean restAlerted = false;

        public double dwellSec = 6, cooldownSec = 120, awayResetSec = 120,
                restEveryMin = 45;
        public int maxStreakAlerts = 3;

        public void reset() {
            state = "AWAY";
            badSince = goodSince = lastSeen = null;
            sittingSec = goodSec = badSec = awaySec = 0;
            lastTick = 0;
            lastAlertAt = 0;
            alerts = 0;
            streakAlerts = 0;
            restAlerted = false;
        }

        public Payload update(Reading r, long now) {
            if (lastTick == 0) lastTick = now;
            double dt = Math.max(0, Math.min((now - lastTick) / 1000.0, 5.0));
            lastTick = now;

            if ("GOOD".equals(state)) goodSec += dt;
            else if ("BAD".equals(state)) badSec += dt;
            else if ("AWAY".equals(state)) awaySec += dt;

            Payload p = new Payload();
            p.state = state;
            p.score = r.score;

            if (!r.present) {
                if (!"AWAY".equals(state)) {
                    state = "AWAY";
                    badSince = null;
                    goodSince = null;
                }
                if (lastSeen != null && now - lastSeen >= awayResetSec * 1000) {
                    sittingSec = 0;
                    restAlerted = false;
                    streakAlerts = 0;
                }
                p.state = state;
                p.sittingSec = (int) sittingSec;
                p.goodSec = (int) goodSec;
                p.badSec = (int) badSec;
                p.alerts = alerts;
                return p;
            }

            lastSeen = now;
            sittingSec += dt;

            if (r.level >= 0.45) {
                goodSince = null;
                if (badSince == null) badSince = now;
                if ("AWAY".equals(state)) state = "WATCH";
                if (now - badSince >= dwellSec * 1000) {
                    boolean entering = !"BAD".equals(state);
                    state = "BAD";
                    if (entering) streakAlerts = 0;
                    if ((entering || now - lastAlertAt >= cooldownSec * 1000)
                            && (maxStreakAlerts <= 0 || streakAlerts < maxStreakAlerts)) {
                        String key = r.dominant != null ? r.dominant : "shdrop";
                        String[] al = ALERT.get(key);
                        if (al == null) al = new String[]{"sit_up", "坐直一点，背挺起来。"};
                        lastAlertAt = now;
                        alerts++;
                        streakAlerts++;
                        p.alertKey = al[0];
                        p.alertText = al[1];
                    }
                }
            } else if (r.level <= 0.22) {
                badSince = null;
                if (goodSince == null) goodSince = now;
                long need = "BAD".equals(state) ? 1500 : 1000;
                if (now - goodSince >= need) {
                    state = "GOOD";
                    streakAlerts = 0;
                }
            } else {
                badSince = null;
                if ("AWAY".equals(state)) state = "WATCH";
            }

            if (sittingSec >= restEveryMin * 60 && !restAlerted
                    && now - lastAlertAt >= cooldownSec * 1000) {
                restAlerted = true;
                lastAlertAt = now;
                alerts++;
                p.alertKey = "rest";
                p.alertText = "坐了很久啦，起来活动一下。";
            }

            p.state = state;
            p.sittingSec = (int) sittingSec;
            p.goodSec = (int) goodSec;
            p.badSec = (int) badSec;
            p.alerts = alerts;
            return p;
        }
    }

    // ------------------------------------------------------------------ maths

    public static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> copy = new ArrayList<>(values);
        Collections.sort(copy);
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }

    public static double mad(List<Double> values) {
        if (values.isEmpty()) return 0;
        double med = median(values);
        List<Double> dev = new ArrayList<>();
        for (double v : values) dev.add(Math.abs(v - med));
        return median(dev);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
