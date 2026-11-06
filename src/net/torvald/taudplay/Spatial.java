/*
 * Surround and ambisonics core — port of spatial.js (taudplay-c spatial.c):
 * the parts playback uses — the stereo fold renderer, the per-voice position
 * model, the Z slide, and the object bus the binaural monitor decodes.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Spatial {
    private Spatial() {}

    private static final double PI = 3.141592653589793;
    private static final double AZ_TO_RAD = (2 * PI) / AZIMUTH_TURN;
    private static final double EL_TO_RAD = PI / 2 / ELEVATION_QUARTER;
    private static final double AZ_PER_DEG = AZIMUTH_TURN / 360.0;
    private static final int AZ_FRONT = 128;

    static double wrapAzimuth(double a) {
        double r = a % AZIMUTH_TURN;
        return r < 0 ? r + AZIMUTH_TURN : r;
    }

    static double foldAzimuthToPan(double az) {
        double a = wrapAzimuth(az);
        double p = a <= 256 ? a : AZIMUTH_TURN - a;
        return p > 255 ? 255 : p;
    }

    static void directionFromAngles(double az, double el, double[] out) {
        double th = (AZ_FRONT - az) * AZ_TO_RAD;
        double ph = el * EL_TO_RAD;
        double cph = JsMath.cos(ph);
        out[0] = cph * JsMath.cos(th);
        out[1] = cph * JsMath.sin(th);
        out[2] = JsMath.sin(ph);
    }

    static void anglesFromDirection(double x, double y, double z, double[] out) {
        out[0] = wrapAzimuth(AZ_FRONT - JsMath.atan2(y, x) / AZ_TO_RAD);
        out[1] = JsMath.asin(dclamp(z, -1.0, 1.0)) / EL_TO_RAD;
    }

    /** ITU placement of a sample's channels as azimuth offsets (stereo = ±30°). */
    private static double sampleChannelLayout(int chans, int k) {
        if (chans == 2) return (k == 0 ? -30 : 30) * AZ_PER_DEG;
        return 0 * AZ_PER_DEG;
    }

    static void sampleChannelAngles(double az, double el, double localAz, double[] out) {
        if (localAz == 0) { out[0] = az; out[1] = el; return; }
        if (el == 0) { out[0] = az + localAz; out[1] = 0; return; }
        double psi = -localAz * AZ_TO_RAD;
        double th = (AZ_FRONT - az) * AZ_TO_RAD;
        double ph = el * EL_TO_RAD;
        double cpsi = JsMath.cos(psi), spsi = JsMath.sin(psi);
        double cph = JsMath.cos(ph), sph = JsMath.sin(ph);
        double cth = JsMath.cos(th), sth = JsMath.sin(th);
        anglesFromDirection(cpsi * cph * cth - spsi * sth,
                            cpsi * cph * sth + spsi * cth,
                            cpsi * sph, out);
    }

    static void anglesFromSpatialArg(int arg, double[] out) {
        int ee = (arg >> 8) & 0xff;
        out[0] = (arg & 0xff) * 2;
        out[1] = ee >= 0x80 ? ee - 256 : ee;
    }

    /** Math.hypot(a, b, c) exactly as V8 computes it: scale by the largest
     *  magnitude, Kahan-sum the squares, un-scale. */
    private static double jsHypot3(double a, double b, double c) {
        double[] v = { Math.abs(a), Math.abs(b), Math.abs(c) };
        double max = 0, sum = 0, comp = 0;
        for (int i = 0; i < 3; i++) if (v[i] > max) max = v[i];
        if (max == 0) return 0;
        for (int i = 0; i < 3; i++) {
            double n = v[i] / max;
            double summand = n * n - comp;
            double prelim = sum + summand;
            comp = (prelim - sum) - summand;
            sum = prelim;
        }
        return Math.sqrt(sum) * max;
    }

    /** One tick of a Z slide: rotate toward the target along the great circle. */
    static void stepTowardTarget(double az, double el, double tgtAz, double tgtEl, double stepUnits, double[] out) {
        double[] a = new double[3], b = new double[3];
        double vx, vy, vz;
        directionFromAngles(az, el, a);
        directionFromAngles(tgtAz, tgtEl, b);
        double dot = dclamp(a[0] * b[0] + a[1] * b[1] + a[2] * b[2], -1.0, 1.0);
        double omega = JsMath.acos(dot);
        double step = stepUnits * AZ_TO_RAD;
        if (!(omega > 1e-12) || step <= 0.0) { out[0] = az; out[1] = el; return; }
        if (step >= omega) { out[0] = wrapAzimuth(tgtAz); out[1] = tgtEl; return; }

        if (omega > PI - 1e-9) {
            double kx = a[2] * a[0];
            double ky = a[2] * a[1];
            double kz = a[2] * a[2] - 1.0;
            double len = jsHypot3(kx, ky, kz);
            if (len < 1e-9) {
                double th = (AZ_FRONT - az) * AZ_TO_RAD;
                double hx = JsMath.cos(th), hy = JsMath.sin(th);
                kx = -a[2] * hy;
                ky = a[2] * hx;
                kz = a[0] * hy - a[1] * hx;
                len = jsHypot3(kx, ky, kz);
            }
            kx /= len; ky /= len; kz /= len;
            double c = JsMath.cos(step), s = JsMath.sin(step);
            vx = a[0] * c + (ky * a[2] - kz * a[1]) * s;
            vy = a[1] * c + (kz * a[0] - kx * a[2]) * s;
            vz = a[2] * c + (kx * a[1] - ky * a[0]) * s;
        } else {
            double sinOmega = JsMath.sin(omega);
            double c0 = JsMath.sin(omega - step) / sinOmega;
            double c1 = JsMath.sin(step) / sinOmega;
            vx = a[0] * c0 + b[0] * c1;
            vy = a[1] * c0 + b[1] * c1;
            vz = a[2] * c0 + b[2] * c1;
        }
        anglesFromDirection(vx, vy, vz, out);
    }

    private static final double SQRT3 = Math.sqrt(3.0);
    private static final double SQRT15 = Math.sqrt(15.0);
    private static final double SQRT5_8 = Math.sqrt(5.0 / 8.0);
    private static final double SQRT3_8 = Math.sqrt(3.0 / 8.0);

    /** Real spherical harmonics up to `order` (≤ 3), SN3D, ACN order. */
    static void encodeSn3d(double az, double el, int order, double[] out) {
        double th = (AZ_FRONT - az) * AZ_TO_RAD;
        double ph = el * EL_TO_RAD;
        double cph = JsMath.cos(ph);
        double x = cph * JsMath.cos(th);
        double y = cph * JsMath.sin(th);
        double z = JsMath.sin(ph);

        out[0] = 1.0;
        if (order < 1) return;
        out[1] = y;
        out[2] = z;
        out[3] = x;
        if (order < 2) return;
        out[4] = SQRT3 * x * y;
        out[5] = SQRT3 * y * z;
        out[6] = (3.0 * z * z - 1.0) * 0.5;
        out[7] = SQRT3 * x * z;
        out[8] = SQRT3 * (x * x - y * y) * 0.5;
        if (order < 3) return;
        out[9] = SQRT5_8 * y * (3.0 * x * x - y * y);
        out[10] = SQRT15 * x * y * z;
        out[11] = SQRT3_8 * y * (5.0 * z * z - 1.0);
        out[12] = z * (5.0 * z * z - 3.0) * 0.5;
        out[13] = SQRT3_8 * x * (5.0 * z * z - 1.0);
        out[14] = SQRT15 * z * (x * x - y * y) * 0.5;
        out[15] = SQRT5_8 * x * (x * x - 3.0 * y * y);
    }

    /* ── bus ── */

    private static void stereoChannelGains(double az, double el, double[] out, int off) {
        double p = foldAzimuthToPan(az);
        double pan = el == 0 ? p : 128 + (p - 128) * JsMath.cos(el * EL_TO_RAD);
        out[off] = JsMath.cos((PI * pan) / 512.0);
        out[off + 1] = JsMath.sin((PI * pan) / 512.0);
    }

    private static void busChannelGains(SpatialBus bus, double az, double el, double[] out, int off) {
        if (bus.kind == 1) Binaural.channelGains(bus.bin, az, el, out, off);
        else stereoChannelGains(az, el, out, off);
    }

    static void busAddSource(SpatialBus bus, int n, double value, double[] gains, int off, double ramp) {
        int nc = bus.numChannels;
        double[] data = bus.data;
        for (int c = 0; c < nc; c++) data[c * TRACKER_CHUNK + n] += (value * gains[off + c]) * ramp;
    }

    static void busStereoAt(SpatialBus bus, int n, double[] out) {
        if (bus.kind == 1) {
            Binaural.monitorStereo(bus.bin, bus.data, TRACKER_CHUNK, n, out);
        } else {
            out[0] = bus.data[n];
            out[1] = bus.data[TRACKER_CHUNK + n];
        }
    }

    /* ── per-voice spatial state ── */

    private static double mirrorPanByte(double az) { return JsMath.round(foldAzimuthToPan(az)); }

    static void applyPanSet(Engine e, Voice v, double pan) {
        v.channelPanSet = true;
        if (e.tsSurroundModel == SURROUND_STEREO) {
            v.channelPan = JsMath.toInt32(pan) & 0xff;
        } else {
            v.panAzimuth = wrapAzimuth(pan);
            v.channelPan = (int) mirrorPanByte(v.panAzimuth);
        }
        v.rowPan = iclamp(v.channelPan >>> 2, 0, 63);
    }

    static void applyPanSlide(Engine e, Voice v, double delta) {
        v.channelPanSet = true;
        if (e.tsSurroundModel == SURROUND_STEREO) {
            v.channelPan = delta < 0 ? (int) dmax(v.channelPan + delta, 0)
                                     : (int) dmin(v.channelPan + delta, 0xff);
        } else {
            v.panAzimuth = wrapAzimuth(v.panAzimuth + delta);
            v.channelPan = (int) mirrorPanByte(v.panAzimuth);
        }
        v.rowPan = iclamp(v.channelPan >>> 2, 0, 63);
    }

    static void applyElevation(Engine e, Voice v, double el) {
        v.panElevation = e.tsSurroundModel == SURROUND_SPATIAL ? el : 0.0;
    }

    static double boundNotePan(Engine e, double off) {
        if (e.tsSurroundModel == SURROUND_STEREO) return dclamp(off, -0xff, 0xff);
        return wrapAzimuth(off + AZIMUTH_TURN / 2) - AZIMUTH_TURN / 2;
    }

    static void applyNotePanSet(Engine e, Voice v, double pan) {
        v.notePan = boundNotePan(e, pan - AZ_FRONT);
    }

    static void applyNotePanSlide(Engine e, Voice v, double delta) {
        v.notePan = boundNotePan(e, v.notePan + delta);
    }

    static void applyNoteElevation(Engine e, Voice v, double el) {
        v.noteElevation = e.tsSurroundModel == SURROUND_SPATIAL ? el : 0.0;
    }

    private static double envPanRaw(Voice v) {
        double r = JsMath.round(v.envPan * 255.0);
        return r < 0 ? 0 : r > 255 ? 255 : r;
    }

    static double voiceAzimuth(Voice v) {
        if (v.hasPanEnv && v.panEnvOn) {
            return wrapAzimuth(v.panAzimuth + v.notePan + envPanRaw(v) - 128 + v.randomPanBias +
                               v.panbrelloOffset);
        }
        return wrapAzimuth(v.panAzimuth + v.notePan + v.randomPanBias + v.panbrelloOffset);
    }

    static double voicePanByte(Voice v) {
        double pan;
        if (v.hasPanEnv && v.panEnvOn) {
            pan = v.channelPan + v.notePan + envPanRaw(v) - 128 + v.randomPanBias + v.panbrelloOffset;
        } else {
            pan = v.channelPan + v.notePan + v.randomPanBias + v.panbrelloOffset;
        }
        return pan < 0 ? 0 : pan > 255 ? 255 : pan;
    }

    static double voiceElevation(Voice v) { return v.panElevation + v.noteElevation; }

    static boolean isSoundingChild(Engine e, Voice bg, int vi) {
        if (!bg.isLayerChild || bg.sourceChannel != vi) return false;
        return !bg.fmOperator || bg.fmParent == e.voices[vi];
    }

    private static double displayWeight(Voice v) {
        double env = v.hasVolEnv && v.volEnvOn ? v.envVolMix : 1.0;
        return env * v.fadeoutVolume * v.currentMixVolume * v.layerMixGain * ((255 - v.fader) / 255.0);
    }

    /** The stereo pan the meters show for lane `vi`: the mix-weighted mean of
     *  the foreground voice and its layer children. */
    static double displayPanByte(Engine e, int vi, Voice v) {
        double sum = 0.0, wsum = 0.0;
        double w = displayWeight(v);
        sum += voicePanByte(v) * w;
        wsum += w;
        if (v.metaForeground) {
            for (int i = 0; i < e.bgCount; i++) {
                Voice bg = e.bg[i];
                if (bg.active && isSoundingChild(e, bg, vi)) {
                    w = displayWeight(bg);
                    sum += voicePanByte(bg) * w;
                    wsum += w;
                }
            }
        }
        return wsum > 1e-9 ? sum / wsum : voicePanByte(v);
    }

    /** Renderer gains for every channel of `v`, cached until the source moves. */
    static double[] spatialVoiceGains(Engine e, Voice v) {
        SpatialBus bus = e.spatial;
        Voice.SpatialCache sc = v.spatial;
        int nc = bus.numChannels;
        int chans = v.activeChanCount == 2 ? 2 : 1;
        double az = v.currentPan;
        double el = voiceElevation(v);
        if (!sc.valid || sc.az != az || sc.el != el || sc.chans != chans || sc.rendererGen != e.rendererGen) {
            double[] ang = e.angBox;
            for (int k = 0; k < chans; k++) {
                sampleChannelAngles(az, el, sampleChannelLayout(chans, k), ang);
                busChannelGains(bus, ang[0], ang[1], sc.gains, k * nc);
            }
            sc.valid = true;
            sc.az = az; sc.el = el; sc.chans = chans; sc.rendererGen = e.rendererGen;
        }
        return sc.gains;
    }
}
