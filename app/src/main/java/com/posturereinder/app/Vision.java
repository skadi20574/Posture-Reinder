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

    public static final int MODE_FRONT = 0;
    public static final int MODE_SIDE = 1;

    public static final String[] ORDER = {"neck", "shdrop", "head", "torso", "tilt", "lean", "dist"};
    public static final String[] LABEL = {"头前伸", "坐高下沉", "头部高度", "躯干前倾", "肩线倾斜", "身体侧偏", "靠近屏幕"};
    public static final double[] WEIGHT = {1.2, 1.3, 0.8, 1.0, 0.6, 0.6, 0.4};

    /** Side view: the camera sits beside the child, shoulders are foreshortened. */
    public static final String[] SIDE_ORDER = {"fwdhead", "trunk", "headdrop", "dist"};
    public static final String[] SIDE_LABEL = {"头前伸", "躯干前倾", "头部下沉", "靠近屏幕"};
    public static final double[] SIDE_WEIGHT = {1.2, 1.4, 0.9, 0.3};

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
        FLOOR.put("fwdhead", 0.025);
        FLOOR.put("trunk", 2.5);
        FLOOR.put("spine", 0.030);
        FLOOR.put("headdrop", 0.025);
        FLOOR.put("sidedist", 0.030);
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
        ALERT.put("fwdhead", new String[]{"head_up", "头抬高一点，别低头。"});
        ALERT.put("trunk", new String[]{"sit_up", "坐直一点，背挺起来。"});
        ALERT.put("spine", new String[]{"sit_up", "坐直一点，背挺起来。"});
        ALERT.put("headdrop", new String[]{"head_up", "头抬高一点，别低头。"});
        ALERT.put("sidedist", new String[]{"far", "离书远一点，让眼睛休息一下。"});
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

        // side view
        public boolean side;
        public double fwdHead;    // ear ahead of shoulder, normalised by scale
        public double trunk;      // torso inclination in degrees, + forward
        public double spine;      // torso length / head depth (shorter = slouch)
        public double headDrop;   // shoulder to ear vertical, normalised
        public double torsoLen;   // shoulder to hip in pixels, 0 when hips hidden
        public double headDepth;  // nose to ear in pixels
        public double scaleRef;   // normalisation scale used by this frame
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

    /**
     * Side view measurement. The child is seen in profile, so shoulder width is
     * useless; everything is normalised by torso length (or a head-size proxy
     * when the hips are out of frame). Facing direction comes from the nose,
     * which makes left and right camera placement behave the same.
     */
    public static Frame measureSide(double[] nx, double[] ny, double[] nv, int w, int h) {
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

        boolean leOk = vs[L_EAR] >= 0.5;
        boolean reOk = vs[R_EAR] >= 0.5;
        if (!leOk && !reOk) return null;
        double earX = leOk ? xs[L_EAR] : xs[R_EAR];
        double earY = leOk ? ys[L_EAR] : ys[R_EAR];
        double shX = (xs[L_SHOULDER] + xs[R_SHOULDER]) / 2.0;
        double shY = (ys[L_SHOULDER] + ys[R_SHOULDER]) / 2.0;

        double noseX = xs[NOSE];
        double noseY = ys[NOSE];
        double dir = (noseX >= earX) ? 1.0 : -1.0;
        double headDepth = Math.hypot(noseX - earX, noseY - earY);
        if (headDepth < 6) headDepth = 6;

        boolean hasHips = vs[L_HIP] >= 0.45 && vs[R_HIP] >= 0.45;
        double hipX = 0, hipY = 0, torsoLen = 0;
        if (hasHips) {
            hipX = (xs[L_HIP] + xs[R_HIP]) / 2.0;
            hipY = (ys[L_HIP] + ys[R_HIP]) / 2.0;
            torsoLen = Math.hypot(shX - hipX, shY - hipY);
        }
        boolean hipsUsable = hasHips && torsoLen > 12;
        double scale = hipsUsable ? torsoLen : 2.2 * headDepth;
        if (scale < 12) return null;

        Frame f = new Frame();
        f.side = true;
        f.sw = hipsUsable ? torsoLen : 0;
        f.imgH = h;
        f.torsoLen = torsoLen;
        f.headDepth = headDepth;
        f.hasTorso = hipsUsable;
        f.scaleRef = scale;
        f.fwdHead = ((earX - shX) * dir) / scale;
        f.headDrop = (shY - earY) / scale;
        f.spine = hipsUsable ? torsoLen / headDepth : 0;
        if (hipsUsable) {
            double dy = Math.max(1.0, hipY - shY);
            f.trunk = Math.toDegrees(Math.atan2((shX - hipX) * dir, dy));
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

        public int mode = MODE_FRONT;
        public double fwdHead, trunk, spine, headDrop, scaleRef, headDepth;
        public double sFwdHead, sTrunk, sSpine, sHeadDrop, sSideDist;

        public boolean valid() {
            return mode == MODE_SIDE ? scaleRef > 0 : sw > 0;
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

    /** Baseline for the side view. Trunk metrics need the hips in frame. */
    public static Baseline fitSide(List<Frame> frames) {
        List<Frame> ok = new ArrayList<>();
        if (frames != null) {
            for (Frame f : frames) if (f != null && f.side) ok.add(f);
        }
        if (ok.size() < 15) {
            throw new IllegalArgumentException("有效样本太少，请确认侧面能看到头和肩膀后重新校准");
        }
        List<Double> fwd = new ArrayList<>();
        List<Double> drop = new ArrayList<>();
        List<Double> scale = new ArrayList<>();
        List<Double> spine = new ArrayList<>();
        List<Double> trunk = new ArrayList<>();
        for (Frame f : ok) {
            fwd.add(f.fwdHead);
            drop.add(f.headDrop);
            scale.add(f.scaleRef);
            if (f.hasTorso) {
                spine.add(f.spine);
                trunk.add(f.trunk);
            }
        }
        Baseline b = new Baseline();
        b.mode = MODE_SIDE;
        b.fwdHead = median(fwd);
        b.headDrop = median(drop);
        b.scaleRef = median(scale);
        b.hasTorso = spine.size() >= 8;
        if (b.hasTorso) {
            b.spine = median(spine);
            b.trunk = median(trunk);
        }
        b.sFwdHead = Math.max(1.4826 * mad(fwd), FLOOR.get("fwdhead"));
        b.sHeadDrop = Math.max(1.4826 * mad(drop), FLOOR.get("headdrop"));
        b.sSideDist = Math.max(1.4826 * mad(scale) / Math.max(1.0, b.scaleRef),
                FLOOR.get("sidedist"));
        b.sSpine = b.hasTorso ? Math.max(1.4826 * mad(spine), FLOOR.get("spine"))
                : FLOOR.get("spine");
        b.sTrunk = b.hasTorso ? Math.max(1.4826 * mad(trunk), FLOOR.get("trunk"))
                : FLOOR.get("trunk");
        b.samples = ok.size();
        b.createdAt = System.currentTimeMillis();
        return b;
    }

    public static Map<String, Double> deviations(Frame f, Baseline b) {
        Map<String, Double> out = new HashMap<>();
        if (f == null || b == null || !b.valid()) return out;
        if (b.mode == MODE_SIDE) {
            out.put("fwdhead", (f.fwdHead - b.fwdHead) / b.sFwdHead);
            out.put("headdrop", (b.headDrop - f.headDrop) / b.sHeadDrop);
            out.put("dist", (f.scaleRef / b.scaleRef - 1.0) / b.sSideDist);
            if (b.hasTorso && f.hasTorso) {
                out.put("trunk", (f.trunk - b.trunk) / b.sTrunk);
                out.put("spine", (b.spine - f.spine) / b.sSpine);
            }
            return out;
        }
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
        if (b.mode != MODE_SIDE && b.headW > 0 && f.headW > 1.0) {
            r.headRatio = f.headW / b.headW;
        }
        Map<String, Double> devs = deviations(f, b);
        if (devs.isEmpty()) return r;

        String[] order = b.mode == MODE_SIDE ? SIDE_ORDER : ORDER;
        String[] labels = b.mode == MODE_SIDE ? SIDE_LABEL : LABEL;
        double[] weights = b.mode == MODE_SIDE ? SIDE_WEIGHT : WEIGHT;

        double k0 = K_ENTER * sensitivity;
        double totalW = 0, acc = 0, maxBad = 0;
        String dominant = null;
        double dominantBad = -1;

        for (int i = 0; i < order.length; i++) {
            String key = order[i];
            Double zv = devs.get(key);
            if (zv == null) continue;
            double z = zv;
            double bad = clamp((z - k0) / K_SPAN, 0, 1);
            double w = weights[i];
            totalW += w;
            acc += w * bad;
            if (bad > maxBad) maxBad = bad;
            if (bad > dominantBad) {
                dominantBad = bad;
                dominant = key;
            }
            Metric m = new Metric();
            m.key = key;
            m.label = labels[i];
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

    public static String postureLabel(Reading r, double headRatio, int mode) {
        if (r == null || !r.present) return "无人";
        if (mode == MODE_SIDE) return sideLabel(r);
        return postureLabel(r, headRatio);
    }

    /** Side view classes: the profile shows trunk lean and head position directly. */
    private static String sideLabel(Reading r) {
        Map<String, Double> z = new HashMap<>();
        for (Metric m : r.metrics) z.put(m.key, m.z);
        double trunk = z.containsKey("trunk") ? z.get("trunk") : 0;
        double fwd = z.containsKey("fwdhead") ? z.get("fwdhead") : 0;
        double drop = z.containsKey("headdrop") ? z.get("headdrop") : 0;
        if (trunk > 2.0) return "前倾";
        if (trunk < -2.0) return "后仰";
        if (fwd > 2.0 && drop > 2.0) return "驼背";
        if (fwd > 2.0) return "头前伸";
        if (drop > 2.0) return "低头";
        if (r.score >= 80) return "端正";
        return "需调整";
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
