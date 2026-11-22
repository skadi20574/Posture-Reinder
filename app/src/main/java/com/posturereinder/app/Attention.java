package com.posturereinder.app;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.List;
import java.util.Optional;

/** Turns a face-landmarker result into a coarse attention state. */
public final class Attention {

    public boolean face;
    public double yaw;       // -0.5 .. 0.5, + = turned to the person's right
    public double pitch;     // + = head down
    public double gazeH;     // + = eyes to the person's right
    public double gazeV;     // + = eyes up
    public double blink;     // 0..1
    public boolean distracted;
    public long at;

    public static Attention none() {
        Attention a = new Attention();
        a.face = false;
        a.distracted = true;
        a.at = System.currentTimeMillis();
        return a;
    }

    public static Attention from(FaceLandmarkerResult r, long now) {
        return from(r, now, 0.65, 1.0);
    }

    public static Attention from(FaceLandmarkerResult r, long now,
                                 double blinkThreshold, double sens) {
        Attention a = new Attention();
        a.at = now;
        if (r == null) return a;

        List<List<NormalizedLandmark>> faces = r.faceLandmarks();
        if (faces == null || faces.isEmpty() || faces.get(0).size() < 468) return a;

        List<NormalizedLandmark> l = faces.get(0);
        a.face = true;

        double noseX = l.get(1).x(), noseY = l.get(1).y();
        double leX = l.get(33).x(), reX = l.get(263).x();
        double eyeY = (l.get(33).y() + l.get(263).y()) / 2.0;
        double eyeW = Math.abs(reX - leX);
        if (eyeW > 1e-4) {
            a.yaw = (noseX - (leX + reX) / 2.0) / eyeW;
            a.pitch = (noseY - eyeY) / eyeW;
        }

        double outL = 0, inL = 0, outR = 0, inR = 0;
        double upL = 0, upR = 0, dnL = 0, dnR = 0, blL = 0, blR = 0;
        Optional<List<List<Category>>> bs = r.faceBlendshapes();
        if (bs.isPresent() && !bs.get().isEmpty() && !bs.get().get(0).isEmpty()) {
            for (Category c : bs.get().get(0)) {
                String n = c.categoryName();
                double s = c.score();
                switch (n) {
                    case "eyeLookOutLeft": outL = s; break;
                    case "eyeLookInLeft": inL = s; break;
                    case "eyeLookOutRight": outR = s; break;
                    case "eyeLookInRight": inR = s; break;
                    case "eyeLookUpLeft": upL = s; break;
                    case "eyeLookUpRight": upR = s; break;
                    case "eyeLookDownLeft": dnL = s; break;
                    case "eyeLookDownRight": dnR = s; break;
                    case "eyeBlinkLeft": blL = s; break;
                    case "eyeBlinkRight": blR = s; break;
                    default: break;
                }
            }
        }
        // looking to the person's right: right eye looks "out", left eye looks "in"
        a.gazeH = ((outR + inL) - (outL + inR)) / 2.0;
        a.gazeV = ((upL + upR) - (dnL + dnR)) / 2.0;
        a.blink = Math.max(blL, blR);

        boolean turned = Math.abs(a.yaw) > 0.28 * sens;
        boolean side = Math.abs(a.gazeH) > 0.45 * sens;
        boolean closed = a.blink > blinkThreshold;
        a.distracted = turned || side || closed;
        return a;
    }
}
