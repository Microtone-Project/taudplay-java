/*
 * Master-bus mastering chain — port of mastering.js, the TruePeakProbe of
 * analysis.js and the `sMst` block decoder of mastering-section.js
 * (taudplay-c mastering.c). The biquad, the compressor and the limiter are
 * shared, at any channel count up to TE_MAX_BUS_CH, with the instrument
 * chains (Imix), as mastering.js exports them to imix.js.
 *
 *     trim → high-pass → 4-band EQ → compressor → width → limiter → gain
 *
 * Coefficients in binary64 from binary32 parameters; per-sample maths in
 * binary64; the mix narrows to binary32 once, after this, as it always did.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Mastering {
    private Mastering() {}

    private static final double PI = 3.141592653589793;
    private static final double LIMITER_LOOKAHEAD_MS = 1.0;

    static double dbToGain(double db) { return JsMath.pow(10.0, db / 20); }

    /* ── parameters ── */

    static final class EqBand {
        boolean on;
        int type;
        float freq, gainDb, q;

        void copyFrom(EqBand o) { on = o.on; type = o.type; freq = o.freq; gainDb = o.gainDb; q = o.q; }
    }

    /** The mastering chain's parameters (TaudMastering), every value binary32. */
    static final class Params {
        boolean on;
        float trimDb;
        boolean hpOn; float hpFreq; int hpSlope;
        boolean eqOn;
        final EqBand[] eq = { new EqBand(), new EqBand(), new EqBand(), new EqBand() };
        boolean compOn; int compDetector;
        float compThreshDb, compRatio, compAttackMs, compReleaseMs, compKneeDb, compMakeupDb;
        boolean widthOn; float width;
        boolean limOn, limTruePeak; float limCeilingDb, limReleaseMs;
        float outGainDb;

        Params() { setDefault(); }

        void setDefault() {
            on = false;
            trimDb = 0;
            hpOn = false; hpFreq = 20; hpSlope = HP_SLOPE_12;
            eqOn = false;
            eq[0].on = true; eq[0].type = EQ_LOW_SHELF;  eq[0].freq = 100;  eq[0].gainDb = 0; eq[0].q = 0.707f;
            eq[1].on = true; eq[1].type = EQ_PEAKING;    eq[1].freq = 400;  eq[1].gainDb = 0; eq[1].q = 1.0f;
            eq[2].on = true; eq[2].type = EQ_PEAKING;    eq[2].freq = 2500; eq[2].gainDb = 0; eq[2].q = 1.0f;
            eq[3].on = true; eq[3].type = EQ_HIGH_SHELF; eq[3].freq = 8000; eq[3].gainDb = 0; eq[3].q = 0.707f;
            compOn = false; compDetector = COMP_PEAK;
            compThreshDb = -18; compRatio = 2; compAttackMs = 20; compReleaseMs = 200;
            compKneeDb = 6; compMakeupDb = 0;
            widthOn = false; width = 1;
            limOn = false; limTruePeak = false; limCeilingDb = -1; limReleaseMs = 100;
            outGainDb = 0;
        }

        void copyFrom(Params o) {
            on = o.on; trimDb = o.trimDb;
            hpOn = o.hpOn; hpFreq = o.hpFreq; hpSlope = o.hpSlope;
            eqOn = o.eqOn;
            for (int i = 0; i < EQ_BANDS; i++) eq[i].copyFrom(o.eq[i]);
            compOn = o.compOn; compDetector = o.compDetector;
            compThreshDb = o.compThreshDb; compRatio = o.compRatio; compAttackMs = o.compAttackMs;
            compReleaseMs = o.compReleaseMs; compKneeDb = o.compKneeDb; compMakeupDb = o.compMakeupDb;
            widthOn = o.widthOn; width = o.width;
            limOn = o.limOn; limTruePeak = o.limTruePeak; limCeilingDb = o.limCeilingDb; limReleaseMs = o.limReleaseMs;
            outGainDb = o.outGainDb;
        }
    }

    private static float clampRange(double v, double lo, double hi, double fallback) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return (float) fallback;
        return (float) (v < lo ? lo : v > hi ? hi : v);
    }

    static boolean engaged(Params p) {
        if (p == null || !p.on) return false;
        return p.trimDb != 0 || p.outGainDb != 0 ||
            p.hpOn || p.eqOn || p.compOn || p.limOn || (p.widthOn && p.width != 1);
    }

    static float rdF32(byte[] b, int o) {
        int u = (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
        return Float.intBitsToFloat(u);
    }

    private static final int MASTERING_BLOCK_SIZE = 160;
    private static final int EQ_BASE = 20;
    private static final int EQ_STRIDE = 16;
    private static final int COMP_BASE = 84;
    private static final int WIDTH_BASE = 112;
    private static final int LIM_BASE = 120;
    private static final int OUT_GAIN = 132;

    /** Decode one version-1 parameter block (normalised as the JS does). */
    static boolean parseBlock(byte[] b, int at, int len, Params p) {
        if (len < MASTERING_BLOCK_SIZE) return false;
        if ((b[at] & 0xff) != 1) return false;
        Params d = new Params();
        for (int i = 0; i < EQ_BANDS; i++) {
            int o = at + EQ_BASE + i * EQ_STRIDE;
            int type = b[o + 1] & 0xff;
            type = type == EQ_LOW_SHELF || type == EQ_HIGH_SHELF ? type : EQ_PEAKING;
            p.eq[i].on = (b[o] & 1) != 0;
            p.eq[i].type = i == 0 || i == EQ_BANDS - 1 ? type : EQ_PEAKING;
            p.eq[i].freq = clampRange(rdF32(b, o + 4), 20, 20000, d.eq[i].freq);
            p.eq[i].gainDb = clampRange(rdF32(b, o + 8), -18, 18, d.eq[i].gainDb);
            p.eq[i].q = clampRange(rdF32(b, o + 12), 0.1, 12, d.eq[i].q);
        }
        p.on = (b[at + 1] & 1) != 0;
        p.trimDb = clampRange(rdF32(b, at + 4), -24, 24, d.trimDb);
        p.hpOn = (b[at + 8] & 1) != 0;
        p.hpSlope = (b[at + 8] & 2) != 0 ? HP_SLOPE_24 : HP_SLOPE_12;
        p.hpFreq = clampRange(rdF32(b, at + 12), 10, 500, d.hpFreq);
        p.eqOn = (b[at + 16] & 1) != 0;
        p.compOn = (b[at + COMP_BASE] & 1) != 0;
        p.compDetector = (b[at + COMP_BASE] & 2) != 0 ? COMP_RMS : COMP_PEAK;
        p.compThreshDb = clampRange(rdF32(b, at + COMP_BASE + 4), -60, 0, d.compThreshDb);
        p.compRatio = clampRange(rdF32(b, at + COMP_BASE + 8), 1, 20, d.compRatio);
        p.compAttackMs = clampRange(rdF32(b, at + COMP_BASE + 12), 0.1, 300, d.compAttackMs);
        p.compReleaseMs = clampRange(rdF32(b, at + COMP_BASE + 16), 5, 3000, d.compReleaseMs);
        p.compKneeDb = clampRange(rdF32(b, at + COMP_BASE + 20), 0, 24, d.compKneeDb);
        p.compMakeupDb = clampRange(rdF32(b, at + COMP_BASE + 24), -12, 24, d.compMakeupDb);
        p.widthOn = (b[at + WIDTH_BASE] & 1) != 0;
        p.width = clampRange(rdF32(b, at + WIDTH_BASE + 4), 0, 2, d.width);
        p.limOn = (b[at + LIM_BASE] & 1) != 0;
        p.limTruePeak = (b[at + LIM_BASE] & 2) != 0;
        p.limCeilingDb = clampRange(rdF32(b, at + LIM_BASE + 4), -24, 0, d.limCeilingDb);
        p.limReleaseMs = clampRange(rdF32(b, at + LIM_BASE + 8), 1, 1000, d.limReleaseMs);
        p.outGainDb = clampRange(rdF32(b, at + OUT_GAIN), -24, 24, d.outGainDb);
        return true;
    }

    /* ── biquad (RBJ, Direct Form I, normalised to a0) ── */

    /** An RBJ section, Direct Form I: one set of coefficients, a delay line
     *  per channel. */
    static final class Biquad {
        double b0 = 1, b1, b2, a1, a2;
        final double[] x1 = new double[TE_MAX_BUS_CH], x2 = new double[TE_MAX_BUS_CH];
        final double[] y1 = new double[TE_MAX_BUS_CH], y2 = new double[TE_MAX_BUS_CH];

        double run(int c, double x) {
            double y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c];
            x2[c] = x1[c]; x1[c] = x;
            y2[c] = y1[c]; y1[c] = y;
            return y;
        }

        void init() {
            b0 = 1; b1 = b2 = a1 = a2 = 0;
            reset();
        }

        void reset() {
            java.util.Arrays.fill(x1, 0); java.util.Arrays.fill(x2, 0);
            java.util.Arrays.fill(y1, 0); java.util.Arrays.fill(y2, 0);
        }

        private void set(double b0, double b1, double b2, double a0, double a1, double a2) {
            double inv = 1 / a0;
            this.b0 = b0 * inv; this.b1 = b1 * inv; this.b2 = b2 * inv;
            this.a1 = a1 * inv; this.a2 = a2 * inv;
        }

        void highPass(double freq, double q, double rate) {
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha);
        }

        void lowPass(double freq, double q, double rate) {
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha);
        }

        /** Constant 0 dB peak gain (a dynamic EQ's sensor). */
        void bandPass(double freq, double q, double rate) {
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set(alpha, 0, -alpha, 1 + alpha, -2 * cw, 1 - alpha);
        }

        void notch(double freq, double q, double rate) {
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set(1, -2 * cw, 1, 1 + alpha, -2 * cw, 1 - alpha);
        }

        /** At Q = 1/√2, what a Linkwitz–Riley 4th-order low-pass and high-pass
         *  at the same frequency sum to (a multiband split's phase matching). */
        void allPass(double freq, double q, double rate) {
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set(1 - alpha, -2 * cw, 1 + alpha, 1 + alpha, -2 * cw, 1 - alpha);
        }

        void peaking(double freq, double gainDb, double q, double rate) {
            double A = JsMath.pow(10.0, gainDb / 40);
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            set(1 + alpha * A, -2 * cw, 1 - alpha * A, 1 + alpha / A, -2 * cw, 1 - alpha / A);
        }

        void lowShelf(double freq, double gainDb, double q, double rate) {
            double A = JsMath.pow(10.0, gainDb / 40);
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            double tsa = 2 * Math.sqrt(A) * alpha;
            set(A * ((A + 1) - (A - 1) * cw + tsa),
                2 * A * ((A - 1) - (A + 1) * cw),
                A * ((A + 1) - (A - 1) * cw - tsa),
                (A + 1) + (A - 1) * cw + tsa,
                -2 * ((A - 1) + (A + 1) * cw),
                (A + 1) + (A - 1) * cw - tsa);
        }

        void highShelf(double freq, double gainDb, double q, double rate) {
            double A = JsMath.pow(10.0, gainDb / 40);
            double w = (2 * PI * dmin(freq, rate * 0.49)) / rate;
            double cw = JsMath.cos(w), sw = JsMath.sin(w);
            double alpha = sw / (2 * q);
            double tsa = 2 * Math.sqrt(A) * alpha;
            set(A * ((A + 1) + (A - 1) * cw + tsa),
                -2 * A * ((A - 1) + (A + 1) * cw),
                A * ((A + 1) + (A - 1) * cw - tsa),
                (A + 1) - (A - 1) * cw + tsa,
                2 * ((A - 1) - (A + 1) * cw),
                (A + 1) - (A - 1) * cw - tsa);
        }
    }

    /* ── sliding-window minimum (monotonic deque) ── */

    private static final int SLIDING_REBASE = 0x20000000;

    static final class SlidingMin {
        int width, cap;
        double[] val;
        int[] at;
        int head, tail;
        int n;

        SlidingMin(int width) {
            this.width = width;
            this.cap = width + 1;
            this.val = new double[cap];
            this.at = new int[cap];
        }

        void reset(double fill) {
            n = 0;
            val[0] = fill; at[0] = 0;
            head = 0; tail = 1;
        }

        double push(double v) {
            int i = n++;
            while (tail != head) {
                int prev = (tail - 1 + cap) % cap;
                if (val[prev] >= v) tail = prev; else break;
            }
            val[tail] = v; at[tail] = i;
            tail = (tail + 1) % cap;
            while (at[head] <= i - width) head = (head + 1) % cap;
            if (n >= SLIDING_REBASE) {
                for (int k = head; k != tail; k = (k + 1) % cap) at[k] -= SLIDING_REBASE;
                n -= SLIDING_REBASE;
            }
            return val[head];
        }
    }

    /* ── true-peak probe (4× polyphase, 8 taps a phase) ── */

    static double[] tpCoef() {
        double[] h = new double[TP_PHASES * TP_TAPS];
        int n = TP_PHASES * TP_TAPS;
        double centre = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            double t = (i - centre) / TP_PHASES;
            double sinc = t == 0 ? 1.0 : JsMath.sin(PI * t) / (PI * t);
            h[i] = sinc * (0.5 - 0.5 * JsMath.cos((2 * PI * i) / (n - 1)));
        }
        for (int p = 0; p < TP_PHASES; p++) {
            double s = 0.0;
            for (int k = 0; k < TP_TAPS; k++) s += h[k * TP_PHASES + p];
            if (s != 0) for (int k = 0; k < TP_TAPS; k++) h[k * TP_PHASES + p] /= s;
        }
        return h;
    }

    static double tpPush(double[] h, double[] coef, double v) {
        for (int k = TP_TAPS - 1; k > 0; k--) h[k] = h[k - 1];
        h[0] = v;
        double hi = v < 0 ? -v : v;
        for (int p = 0; p < TP_PHASES; p++) {
            double acc = 0.0;
            for (int k = 0; k < TP_TAPS; k++) acc += h[k] * coef[k * TP_PHASES + p];
            double a = acc < 0 ? -acc : acc;
            if (a > hi) hi = a;
        }
        return hi;
    }

    /* ── stages ── */

    static double onePole(double ms, double rate) {
        double n = (ms / 1000) * rate;
        return n <= 0 ? 1 : 1 - JsMath.exp(-1 / n);
    }

    static final class Compressor {
        double grDb, levelDb, rms, rmsCoef, attackCoef, releaseCoef, thresh, ratio, knee, makeup;
        boolean peakMode;

        void configure(int detector, double threshDb, double ratio, double attackMs,
                       double releaseMs, double kneeDb, double makeupDb, double rate) {
            peakMode = detector != COMP_RMS;
            thresh = threshDb;
            this.ratio = ratio;
            knee = kneeDb;
            makeup = dbToGain(makeupDb);
            attackCoef = onePole(attackMs, rate);
            releaseCoef = onePole(releaseMs, rate);
            rmsCoef = onePole(10, rate);
        }

        void reset() { grDb = 0; rms = 0; levelDb = -120; }

        double advance(double level) {
            double levelDb = level > 1e-6 ? 20 * JsMath.log10(level) : -120;
            double over = levelDb - thresh;
            double half = knee * 0.5;
            double targetDb;
            this.levelDb = levelDb;
            if (knee > 0 && over > -half && over < half) {
                double t = over + half;
                targetDb = -((1 - 1 / ratio) * t * t) / (2 * knee);
            } else if (over > 0) {
                targetDb = -over * (1 - 1 / ratio);
            } else {
                targetDb = 0;
            }
            double coef = targetDb < grDb ? attackCoef : releaseCoef;
            grDb += (targetDb - grDb) * coef;
            return dbToGain(grDb) * makeup;
        }

        double step(double l, double r) {
            double level;
            if (peakMode) {
                double al = l < 0 ? -l : l;
                double ar = r < 0 ? -r : r;
                level = al > ar ? al : ar;
            } else {
                double ms = (l * l + r * r) * 0.5;
                rms += (ms - rms) * rmsCoef;
                level = Math.sqrt(rms);
            }
            return advance(level);
        }

        /** The same detector over `nch` planar channels of a channel-major
         *  buffer (stepPlanar): the peak over every channel of the frame, or
         *  the mean square of all of them. */
        double stepPlanar(double[] data, int n, int stride, int nch) {
            double level;
            if (peakMode) {
                level = 0;
                for (int ch = 0; ch < nch; ch++) {
                    double x = data[ch * stride + n];
                    double a = x < 0 ? -x : x;
                    if (a > level) level = a;
                }
            } else {
                double sum = 0;
                for (int ch = 0; ch < nch; ch++) { double x = data[ch * stride + n]; sum += x * x; }
                rms += (sum / nch - rms) * rmsCoef;
                level = Math.sqrt(rms);
            }
            return advance(level);
        }
    }

    /** The look-ahead limiter: `nch` channels, channel-major, 2D deep each. */
    static final class Limiter {
        int nch;
        int d, dstride, dpos;
        double[] delay;
        SlidingMin min;
        double[] avg;
        int avgLen;
        double avgSum;
        int apos;
        double env, releaseCoef, ceiling;
        boolean truePeak;
        final double[][] probes = new double[TE_MAX_BUS_CH][TP_TAPS];
        final double[] tpCoef = Mastering.tpCoef();
        double grDb;

        Limiter(int nch) { this.nch = nch; }

        void reset() {
            if (d == 0) return;
            java.util.Arrays.fill(delay, 0);
            dpos = 0;
            min.reset(1.0);
            for (int i = 0; i < avgLen; i++) avg[i] = 1.0;
            avgSum = avgLen;
            apos = 0;
            env = 1;
            for (double[] p : probes) java.util.Arrays.fill(p, 0);
            grDb = 0;
        }

        boolean configure(double ceilingDb, boolean truePeak, double releaseMs, double rate) {
            int d = imax(1, (int) JsMath.round((LIMITER_LOOKAHEAD_MS / 1000) * rate));
            boolean geometryChanged = d != this.d;
            if (geometryChanged) {
                this.d = d;
                dstride = 2 * d;
                delay = new double[nch * dstride];
                min = new SlidingMin(2 * d + 1);
                avgLen = d + 1;
                avg = new double[avgLen];
            }
            ceiling = dbToGain(ceilingDb);
            this.truePeak = truePeak;
            releaseCoef = onePole(releaseMs, rate);
            if (geometryChanged) reset();
            return true;
        }

        double advance(double peak) {
            double need = peak > ceiling ? ceiling / peak : 1;
            double m = min.push(need);
            int w = avgLen;
            env = m < env ? m : env + (m - env) * releaseCoef;
            avgSum += env - avg[apos];
            avg[apos] = env;
            apos = apos + 1 == w ? 0 : apos + 1;
            double gain = avgSum / w;
            if (gain < 1) {
                double db = 20 * JsMath.log10(gain);
                if (db < grDb) grDb = db;
            }
            return gain;
        }

        /** One frame in place over `nch` planar channels of a channel-major
         *  buffer (stepPlanar): the peak across every channel, then the
         *  delayed frame out. */
        void stepPlanar(double[] data, int n, int stride) {
            int s = dstride, i = dpos;
            double peak = 0;
            for (int ch = 0; ch < nch; ch++) {
                double x = data[ch * stride + n];
                double a = truePeak ? tpPush(probes[ch], tpCoef, x) : (x < 0 ? -x : x);
                if (a > peak) peak = a;
            }
            double gain = advance(peak);
            for (int ch = 0; ch < nch; ch++) {
                int j = ch * s + i;
                int k = ch * stride + n;
                double held = delay[j];
                delay[j] = data[k];
                data[k] = held * gain;
            }
            dpos = i + 1 == s ? 0 : i + 1;
        }
    }

    /* ── the chain ── */

    static final class Chain {
        final double rate;
        final Params params = new Params();
        final Biquad hp1 = new Biquad(), hp2 = new Biquad();
        boolean hpTwo;
        final Biquad[] eq = { new Biquad(), new Biquad(), new Biquad(), new Biquad() };
        final int[] eqLive = new int[EQ_BANDS];
        int eqLiveCount;
        final Compressor comp = new Compressor();
        final Limiter lim = new Limiter(2);
        double trimGain, outGain, width;
        boolean engaged;

        Chain(Params p, int rate) {
            this.rate = rate;
            setParams(p != null ? p : params);
        }

        void setParams(Params pp) {
            Params p = new Params();
            p.copyFrom(pp);
            params.copyFrom(p);
            trimGain = dbToGain(p.trimDb);
            outGain = dbToGain(p.outGainDb);
            width = p.width;
            if (p.hpOn) {
                hpTwo = p.hpSlope == HP_SLOPE_24;
                if (hpTwo) {
                    hp1.highPass(p.hpFreq, 0.5411961001461969, rate);
                    hp2.highPass(p.hpFreq, 1.3065629648763766, rate);
                } else {
                    hp1.highPass(p.hpFreq, 0.7071067811865476, rate);
                }
            }
            if (p.eqOn) {
                eqLiveCount = 0;
                for (int i = 0; i < EQ_BANDS; i++) {
                    EqBand b = p.eq[i];
                    if (!b.on) continue;
                    if (b.type == EQ_LOW_SHELF) eq[i].lowShelf(b.freq, b.gainDb, b.q, rate);
                    else if (b.type == EQ_HIGH_SHELF) eq[i].highShelf(b.freq, b.gainDb, b.q, rate);
                    else eq[i].peaking(b.freq, b.gainDb, b.q, rate);
                    eqLive[eqLiveCount++] = i;
                }
            }
            if (p.compOn) comp.configure(p.compDetector, p.compThreshDb, p.compRatio, p.compAttackMs,
                                         p.compReleaseMs, p.compKneeDb, p.compMakeupDb, rate);
            if (p.limOn) lim.configure(p.limCeilingDb, p.limTruePeak, p.limReleaseMs, rate);
            engaged = Mastering.engaged(p);
        }

        void reset() {
            hp1.reset();
            hp2.reset();
            for (int i = 0; i < EQ_BANDS; i++) eq[i].reset();
            comp.reset();
            lim.reset();
        }

        void process(float[] left, float[] right, int frames) {
            Params p = params;
            if (!engaged) return;
            double trim = trimGain;
            double out = outGain;
            boolean doHp = p.hpOn, doEq = p.eqOn, doComp = p.compOn;
            boolean doWidth = p.widthOn && width != 1;
            boolean doLim = p.limOn && lim.d != 0;
            double w = width;
            lim.grDb = 0;

            for (int n = 0; n < frames; n++) {
                double l = left[n] * trim;
                double r = right[n] * trim;
                if (doHp) {
                    l = hp1.run(0, l);
                    if (hpTwo) l = hp2.run(0, l);
                    r = hp1.run(1, r);
                    if (hpTwo) r = hp2.run(1, r);
                }
                if (doEq) {
                    for (int i = 0; i < eqLiveCount; i++) l = eq[eqLive[i]].run(0, l);
                    for (int i = 0; i < eqLiveCount; i++) r = eq[eqLive[i]].run(1, r);
                }
                if (doComp) {
                    double g = comp.step(l, r);
                    l *= g; r *= g;
                }
                if (doWidth) {
                    double m = (l + r) * 0.5;
                    double s = (l - r) * 0.5 * w;
                    l = m + s; r = m - s;
                }
                if (doLim) {
                    Limiter L = lim;
                    double pl = L.truePeak ? tpPush(L.probes[0], L.tpCoef, l) : (l < 0 ? -l : l);
                    double pr = L.truePeak ? tpPush(L.probes[1], L.tpCoef, r) : (r < 0 ? -r : r);
                    double gain = L.advance(pl > pr ? pl : pr);
                    int s = L.dstride, di = L.dpos;
                    double dl = L.delay[di];
                    double dr = L.delay[s + di];
                    L.delay[di] = l;
                    L.delay[s + di] = r;
                    L.dpos = di + 1 == s ? 0 : di + 1;
                    l = dl * gain;
                    r = dr * gain;
                }
                left[n] = (float) (l * out);
                right[n] = (float) (r * out);
            }
        }
    }
}
