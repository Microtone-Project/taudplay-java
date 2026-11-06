/*
 * Kaiser-windowed-sinc resampling — port of resampler.js's
 * resampleInterleaved (β = 8, 512 phases, 8…24 half-taps, cutoff following
 * the ratio, each phase row DC-normalised, adjacent rows blended); taudplay-c
 * resample.c.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Resample {
    private Resample() {}

    private static final double RESAMP_BETA = 8.0;
    private static final int RESAMP_PHASES = 512;

    private static double besselI0(double x) {
        double s = 1.0, t = 1.0;
        int k = 1;
        for (;;) {
            t *= (x * x) / (4.0 * k * k);
            s += t;
            if (t < 1e-12 * s) return s;
            k++;
        }
    }

    private static int halfWidth(double ratio) {
        int h = (int) js_round(12.0 / dmin(1.0, ratio));
        return imax(8, imin(24, h));
    }

    /** `frames` frames of `channels` interleaved samples at srcRate → dstRate. */
    static float[] interleaved(float[] in, int frames, int channels, int srcRate, int dstRate) {
        double ratio = (double) dstRate / srcRate;
        int hw = halfWidth(ratio), nTaps = 2 * hw, history = hw - 1;
        double cutoff = 0.5 * dmin(1.0, ratio);
        double invI0 = 1.0 / besselI0(RESAMP_BETA);
        double step = (double) srcRate / dstRate;
        int dstFrames = (int) floor((double) frames * dstRate / srcRate);
        double[] rows = new double[(RESAMP_PHASES + 1) * nTaps];
        double[] acc = new double[channels];
        float[] out = new float[dstFrames * channels];
        long last = (long) frames - 1;

        for (int p = 0; p <= RESAMP_PHASES; p++) {
            double frac = (double) p / RESAMP_PHASES, s = 0.0;
            int row = p * nTaps;
            for (int k = 0; k < nTaps; k++) {
                double x = (k - (hw - 1)) - frac;
                double a = 2.0 * cutoff * x;
                double sinc = a == 0.0 ? 1.0 : js_sin(3.141592653589793 * a) / (3.141592653589793 * a);
                double r = x / hw;
                double win = besselI0(RESAMP_BETA * sqrt(dmax(0.0, 1.0 - r * r))) * invI0;
                rows[row + k] = sinc * win;
                s += rows[row + k];
            }
            double inv = s != 0 ? 1.0 / s : 1.0;
            for (int k = 0; k < nTaps; k++) rows[row + k] *= inv;
        }

        for (int n = 0; n < dstFrames; n++) {
            double pos = n * step;
            double i0 = floor(pos);
            double fp = (pos - i0) * RESAMP_PHASES;
            int ph = (int) fp;
            double g = fp - ph;
            int row = ph * nTaps;
            int nxt = (ph + 1) * nTaps;
            long base = (long) i0 - history;
            for (int c = 0; c < channels; c++) acc[c] = 0.0;
            for (int t = 0; t < nTaps; t++) {
                long idx = base + t;
                double w = rows[row + t] + (rows[nxt + t] - rows[row + t]) * g;
                if (idx < 0) idx = 0;
                else if (idx > last) idx = last;
                int o = (int) idx * channels;
                for (int c = 0; c < channels; c++) acc[c] += in[o + c] * w;
            }
            for (int c = 0; c < channels; c++) out[n * channels + c] = (float) acc[c];
        }
        return out;
    }
}
