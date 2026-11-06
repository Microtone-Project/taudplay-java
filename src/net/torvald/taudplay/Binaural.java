/*
 * Binaural monitoring — port of binaural.js (taudplay-c binaural.c).
 *
 * The bus channels ARE an ambisonic scene (SN3D/ACN); the monitor pair is
 * that scene decoded through the SADIE HRIRs, one convolution per channel,
 * summed. The right ear is free: mirroring the listener flips the sign of
 * every harmonic with m < 0, so L = Σ(m≥0) + Σ(m<0), R = Σ(m≥0) − Σ(m<0).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Binaural {
    private static final int HRIR_CHANNELS = 16;
    private static final int HRIR_LENGTH = 256;
    private static final int HRIR_RATE = 48000;
    private static final int BIN_ORDER = 3;
    private static final int BIN_SH_COUNT = 16;
    private static final int BIN_FRONT_AZIMUTH = 128;
    private static final int BIN_RESAMP_HALF = 24;
    private static final double BIN_RESAMP_BETA = 8.0;

    boolean sphere;
    int numChannels;
    final int[] acn = new int[16];
    final int[] mirror = new int[16];
    int taps;
    double[] hrir;              /* numChannels × taps */
    double[] hist;              /* numChannels × taps × 2 */
    int histPos;
    private final double[] sh = new double[BIN_SH_COUNT];

    private static int channelList(boolean sphere, int[] out) {
        int n = 0;
        for (int l = 0; l <= BIN_ORDER; l++) {
            for (int m = -l; m <= l; m++) {
                if (sphere || (l - Math.abs(m)) % 2 == 0) out[n++] = l * l + l + m;
            }
        }
        return n;
    }

    private static void mirrorSigns(int[] acn, int n, int[] out) {
        for (int i = 0; i < n; i++) {
            int k = acn[i];
            int l = (int) Math.floor(Math.sqrt((double) k));
            out[i] = k - (l * l + l) >= 0 ? 1 : -1;
        }
    }

    private static double besselI0(double x) {
        double sum = 1.0, term = 1.0, half = x * 0.5;
        for (int k = 1; k < 24; k++) {
            term *= (half / k) * (half / k);
            sum += term;
            if (term < sum * 1e-17) break;
        }
        return sum;
    }

    /** Rate-convert the whole set; taps scaled by 1/ratio (impulse responses).
     *  Returns the new set; its length per channel goes into lenOut[0]. */
    private static double[] resample(double[] src, int srcLen, int channels, int rate, int[] lenOut) {
        double ratio = (double) rate / HRIR_RATE;
        double cutoff = ratio < 1.0 ? ratio : 1.0;
        double half = Math.ceil(BIN_RESAMP_HALF / cutoff);
        int dstLen = ((int) Math.ceil(srcLen * ratio) + 3) & ~3;
        double norm = besselI0(BIN_RESAMP_BETA);
        double scale = 1.0 / ratio;
        double[] out = new double[channels * dstLen];
        for (int n = 0; n < dstLen; n++) {
            double t = n / ratio;
            int lo = (int) dmax(0, Math.ceil(t - half));
            int hi = (int) dmin(srcLen - 1, Math.floor(t + half));
            for (int i = lo; i <= hi; i++) {
                double d = t - i;
                double u = cutoff * d;
                double sinc = Math.abs(u) < 1e-9 ? 1.0 : JsMath.sin(3.141592653589793 * u) / (3.141592653589793 * u);
                double x = d / half;
                double w = besselI0(BIN_RESAMP_BETA * Math.sqrt(1.0 - x * x)) / norm;
                double g = cutoff * sinc * w * scale;
                for (int c = 0; c < channels; c++) out[c * dstLen + n] += src[c * srcLen + i] * g;
            }
        }
        lenOut[0] = dstLen;
        return out;
    }

    /** Level contract: a source dead ahead leaves the head with the power the
     *  pan law gives it. */
    private static double calibration(double[] hrir, int len) {
        double[] sh = new double[BIN_SH_COUNT];
        int[] all = new int[BIN_SH_COUNT];
        int[] mirror = new int[BIN_SH_COUNT];
        double energy = 0.0;
        Spatial.encodeSn3d(BIN_FRONT_AZIMUTH, 0.0, BIN_ORDER, sh);
        for (int k = 0; k < BIN_SH_COUNT; k++) all[k] = k;
        mirrorSigns(all, BIN_SH_COUNT, mirror);
        for (int n = 0; n < len; n++) {
            double p = 0.0, q = 0.0;
            for (int k = 0; k < BIN_SH_COUNT; k++) {
                double v = sh[k] * hrir[k * len + n];
                if (mirror[k] > 0) p += v; else q += v;
            }
            energy += (p + q) * (p + q) + (p - q) * (p - q);
        }
        return 1.0 / Math.sqrt(energy);
    }

    Binaural(boolean sphere, int rate) {
        double[] raw = new double[HRIR_CHANNELS * HRIR_LENGTH];
        for (int i = 0; i < raw.length; i++) raw[i] = HrirSadie.HRIR_SADIE[i] / 32768.0;
        double[] hrirAll;
        int t;
        if (rate == HRIR_RATE) {
            hrirAll = raw;
            t = HRIR_LENGTH;
        } else {
            int[] len = new int[1];
            hrirAll = resample(raw, HRIR_LENGTH, HRIR_CHANNELS, rate, len);
            t = len[0];
        }
        double gain = calibration(hrirAll, t);
        for (int i = 0; i < HRIR_CHANNELS * t; i++) hrirAll[i] *= gain;

        this.sphere = sphere;
        this.numChannels = channelList(sphere, acn);
        this.taps = t;
        this.hrir = new double[numChannels * t];
        this.hist = new double[numChannels * t * 2];
        for (int c = 0; c < numChannels; c++) {
            System.arraycopy(hrirAll, acn[c] * t, hrir, c * t, t);
        }
        mirrorSigns(acn, numChannels, mirror);
        histPos = 0;
    }

    void reset() {
        java.util.Arrays.fill(hist, 0);
        histPos = 0;
    }

    static void channelGains(Binaural b, double az, double el, double[] out, int off) {
        double[] sh = b.sh;
        Spatial.encodeSn3d(az, el, BIN_ORDER, sh);
        for (int c = 0; c < b.numChannels; c++) out[off + c] = sh[b.acn[c]];
    }

    /** Decode one frame to two ears. Called once per frame, in frame order. */
    static void monitorStereo(Binaural b, double[] data, int frames, int n, double[] out) {
        int nc = b.numChannels, taps = b.taps, pos = b.histPos;
        double[] hrir = b.hrir;
        double[] hist = b.hist;
        double p = 0.0, q = 0.0;

        for (int c = 0; c < nc; c++) {
            int base = c * taps * 2;
            double x = data[c * frames + n];
            int hb = c * taps;
            int head = base + pos + taps;
            double a0 = 0.0, a1 = 0.0, a2 = 0.0, a3 = 0.0;
            hist[base + pos] = x;
            hist[base + pos + taps] = x;
            for (int i = 0; i < taps; i += 4) {
                a0 += hrir[hb + i] * hist[head - i];
                a1 += hrir[hb + i + 1] * hist[head - i - 1];
                a2 += hrir[hb + i + 2] * hist[head - i - 2];
                a3 += hrir[hb + i + 3] * hist[head - i - 3];
            }
            double acc = (a0 + a1) + (a2 + a3);
            if (b.mirror[c] > 0) p += acc; else q += acc;
        }
        b.histPos = pos + 1 == taps ? 0 : pos + 1;
        out[0] = p + q;
        out[1] = p - q;
    }
}
