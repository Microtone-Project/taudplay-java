/*
 * Per-voice filters + Taud voice FX — port of filter.js (taudplay-c filter.c).
 *  - IT/tracker path: all-pole 2-pole resonant LPF, no feedforward terms.
 *  - filterSfMode path: FluidSynth's RBJ biquad (cents→Hz, −3.01 dB Q offset,
 *    1/√Q gain normalisation).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Filter {
    private Filter() {}

    /** The rate a voice's filter runs at: the engine's, or its rack's for an
     *  FM operator past the first (voice.filterOversample). */
    private static double filterRate(Engine e, Voice v) {
        return v.filterOversample > 1 ? (double) e.rate * v.filterOversample : (double) e.rate;
    }

    /** RBJ cookbook low-pass, normalised to a0; SF2 §2.01 p.59 1/√Q gain-norm. */
    private static void sfBiquad(Engine e, Voice v, double fres, double qLin) {
        final double pi = 3.141592653589793;
        double omega = (2.0 * pi * fres) / filterRate(e, v);
        double sinC = JsMath.sin(omega);
        double cosC = JsMath.cos(omega);
        double alpha = sinC / (2.0 * qLin);
        double a0inv = 1.0 / (1.0 + alpha);
        double gain = a0inv / Math.sqrt(qLin);
        v.filterBqB1 = (1.0 - cosC) * gain;
        v.filterBqB02 = v.filterBqB1 * 0.5;
        v.filterBqA1 = -2.0 * cosC * a0inv;
        v.filterBqA2 = (1.0 - alpha) * a0inv;
    }

    /** Forget a SoundFont-mode glide: the filter is off, or in IT mode. */
    private static void stopSfGlide(Voice v) {
        v.sfCents = Double.NaN;
        v.sfCentsLeft = 0;
        v.sfQLeft = 0;
    }

    /** One frame of a SoundFont-mode filter glide, after every channel of the
     *  frame has been filtered (filter.js advanceFilterGlide). */
    static void advanceFilterGlide(Engine e, Voice v) {
        if (v.sfCentsLeft == 0 && v.sfQLeft == 0) return;
        if (v.sfCentsLeft > 0) {
            v.sfCents += v.sfCentsIncr;
            v.sfCentsLeft--;
        }
        if (v.sfQLeft > 0) {
            v.sfQLeft--;
            v.sfQ = v.sfQLeft == 0 ? v.sfQAim : v.sfQ + v.sfQIncr;
        }
        if (v.sfCentsLeft == 0 && v.sfQLeft == 0) {
            v.sfCents = v.sfCentsAim;
            sfBiquad(e, v, v.sfHzAim, v.sfQAim);
            return;
        }
        double hz = v.sfCentsLeft == 0
            ? v.sfHzAim
            : dmin(dmax(8.176 * JsMath.pow(2.0, v.sfCents / 1200.0), 5.0), 0.45 * filterRate(e, v));
        sfBiquad(e, v, hz, v.sfQ);
    }

    static void refreshVoiceFilter(Engine e, Voice v) {
        final double pi = 3.141592653589793;
        int cut = v.currentCutoff;
        int res = v.currentResonance;
        double rate = filterRate(e, v);
        double nyquist = rate * 0.5 - 1.0;
        if (cut == v.filterCutoffCached && res == v.filterResonanceCached) return;
        v.filterCutoffCached = cut;
        v.filterResonanceCached = res;

        if (v.filterSfMode) {
            if (cut >= 0xffff) { v.filterActive = false; stopSfGlide(v); return; }
            double fres = dmin(dmax(8.176 * JsMath.pow(2.0, cut / 1200.0), 5.0), 0.45 * rate);
            int qcb = res >= 0xffff ? 0 : res;
            double qDb = dmin(dmax(qcb / 10.0, 0.0), 96.0) - 3.01;
            double qLin = dmax(JsMath.pow(10.0, qDb / 20.0), 0.001);
            /* The coefficients follow a new cutoff / Q a sample at a time
             * (advanceFilterGlide), as FluidSynth's do; a voice's first
             * refresh, and one that turns the biquad on, lands at once. The
             * glide runs in the cutoff's own cents (no logarithm) and is
             * clamped as Hz at every step. */
            boolean fresh = Double.isNaN(v.sfCents) || !v.filterActive || !v.filterIsBiquad;
            v.sfHzAim = fres;
            v.sfCentsAim = cut;
            v.filterIsBiquad = true;
            v.filterActive = true;
            if (fresh) {
                v.sfCents = cut;
                v.sfQ = qLin;
                v.sfQAim = qLin;
                v.sfCentsLeft = 0;
                v.sfQLeft = 0;
                sfBiquad(e, v, fres, qLin);
                return;
            }
            if (qLin != v.sfQAim) {
                v.sfQAim = qLin;
                v.sfQIncr = (qLin - v.sfQ) / e.sfFilterGlideSamples;
                v.sfQLeft = e.sfFilterGlideSamples;
            }
            double diff = cut - v.sfCents;
            if (Math.abs(diff) > 1.0) {
                double count = e.sfFilterGlideSamples * dmin(dmax(v.sfQ, 1.0), 5.0);
                v.sfCentsIncr = diff / count;
                v.sfCentsLeft = (int) JsMath.round(count);
            } else {
                v.sfCents = cut;
                v.sfCentsLeft = 0;
            }
            if (v.sfCentsLeft == 0 && v.sfQLeft == 0) sfBiquad(e, v, fres, qLin);
            return;
        }

        stopSfGlide(v);
        if (iclamp(cut, 0, 255) >= 255) { v.filterActive = false; return; }
        double itCutoff = iclamp(cut, 0, 254) * 0.5;
        double itResonance = res >= 255 ? 0.0 : iclamp(res, 0, 254) * 0.5;
        double frequency = dmin(110.0 * JsMath.pow(2.0, itCutoff / 24.0 + 0.25), nyquist);
        double dmpfac = JsMath.pow(10.0, (-itResonance * (24.0 / 128.0)) / 20.0);

        double r = rate / (2.0 * pi * frequency);
        double d = dmpfac * r + dmpfac - 1.0;
        double ee = r * r;
        double denom = 1.0 + d + ee;

        v.filterA0 = 1.0 / denom;
        v.filterB0 = (d + ee + ee) / denom;
        v.filterB1 = -ee / denom;
        v.filterIsBiquad = false;
        v.filterActive = true;
    }

    /** The voice's own delay line (mono, or the first channel of a pair). */
    static double applyVoiceFilterSelf(Voice v, double x0) {
        if (!v.filterActive) return x0;
        double y0;
        if (v.filterIsBiquad) {
            y0 = v.filterBqB02 * (x0 + v.filterX2) +
                 v.filterBqB1 * v.filterX1 -
                 v.filterBqA1 * v.filterY1 -
                 v.filterBqA2 * v.filterY2;
            v.filterX2 = v.filterX1;
            v.filterX1 = x0;
            v.filterY2 = v.filterY1;
            v.filterY1 = y0;
            return y0;
        }
        double y1c = dmin(dmax(v.filterY1, -2.0), 2.0);
        double y2c = dmin(dmax(v.filterY2, -2.0), 2.0);
        y0 = v.filterA0 * x0 + v.filterB0 * y1c + v.filterB1 * y2c;
        v.filterY2 = v.filterY1;
        v.filterY1 = y0;
        return y0;
    }

    /** A separate delay line (a stereo pair's second channel). */
    static double applyVoiceFilter(Voice v, double x0, Voice.ChannelState st) {
        if (!v.filterActive) return x0;
        double y0;
        if (v.filterIsBiquad) {
            y0 = v.filterBqB02 * (x0 + st.filterX2) +
                 v.filterBqB1 * st.filterX1 -
                 v.filterBqA1 * st.filterY1 -
                 v.filterBqA2 * st.filterY2;
            st.filterX2 = st.filterX1;
            st.filterX1 = x0;
            st.filterY2 = st.filterY1;
            st.filterY1 = y0;
            return y0;
        }
        double y1c = dmin(dmax(st.filterY1, -2.0), 2.0);
        double y2c = dmin(dmax(st.filterY2, -2.0), 2.0);
        y0 = v.filterA0 * x0 + v.filterB0 * y1c + v.filterB1 * y2c;
        st.filterY2 = st.filterY1;
        st.filterY1 = y0;
        return y0;
    }

    /** Shared clipper for effects 8/9: 0 clamp, 1 fold (triangle), 2 wrap (sawtooth). */
    private static double clipSample(double x, int mode) {
        switch (mode & 3) {
        case 1: {
            double v = x;
            while (v > 1.0) v = 2.0 - v;
            while (v < -1.0) v = -2.0 - v;
            return v;
        }
        case 2: {
            double v = (x + 1.0) % 2.0;
            if (v < 0.0) v += 2.0;
            return v - 1.0;
        }
        default:
            return dmin(dmax(x, -1.0), 1.0);
        }
    }

    /** Overdrive (9) → shared clipper → bitcrusher (8), on the voice's own
     *  crusher state (st == null) or a stereo pair's second channel's. */
    static double applyTaudVoiceFx(Voice v, double sample, Voice.ChannelState st) {
        double s = sample;
        boolean overdriveOn = v.overdriveAmp > 0;
        boolean depthQuantises = v.bitcrusherDepth >= 1 && v.bitcrusherDepth <= 7;
        boolean skipActive = v.bitcrusherSkip > 0;
        boolean crushActive = depthQuantises || skipActive;

        if (overdriveOn) {
            s *= (16 + v.overdriveAmp) / 16.0;
            s = clipSample(s, v.clipMode);
        }
        if (crushActive) {
            int counter = st == null ? v.bitcrusherCounter : st.bitcrusherCounter;
            if (counter == 0) {
                if (depthQuantises) {
                    int levels = (1 << v.bitcrusherDepth) - 1;
                    double clipped = dmin(dmax(clipSample(s, v.clipMode), -1.0), 1.0);
                    double q = dmin(dmax(Math.floor((clipped + 1.0) * 0.5 * levels + 0.5), 0.0), levels);
                    s = (q / levels) * 2.0 - 1.0;
                }
                if (st == null) v.bitcrusherHeld = s; else st.bitcrusherHeld = s;
            } else {
                s = st == null ? v.bitcrusherHeld : st.bitcrusherHeld;
            }
            if (skipActive) counter = (counter + 1) % (v.bitcrusherSkip + 1);
            else counter = 0;
            if (st == null) v.bitcrusherCounter = counter; else st.bitcrusherCounter = counter;
        }
        return s;
    }
}
