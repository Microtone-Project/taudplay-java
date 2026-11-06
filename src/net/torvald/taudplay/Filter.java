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

    static void refreshVoiceFilter(Engine e, Voice v) {
        final double pi = 3.141592653589793;
        int cut = v.currentCutoff;
        int res = v.currentResonance;
        double rate = e.rate;
        double nyquist = rate * 0.5 - 1.0;
        if (cut == v.filterCutoffCached && res == v.filterResonanceCached) return;
        v.filterCutoffCached = cut;
        v.filterResonanceCached = res;

        if (v.filterSfMode) {
            if (cut >= 0xffff) { v.filterActive = false; return; }
            double fres = dmin(dmax(8.176 * JsMath.pow(2.0, cut / 1200.0), 5.0), 0.45 * rate);
            int qcb = res >= 0xffff ? 0 : res;
            double qDb = dmin(dmax(qcb / 10.0, 0.0), 96.0) - 3.01;
            double qLin = dmax(JsMath.pow(10.0, qDb / 20.0), 0.001);
            double omega = (2.0 * pi * fres) / rate;
            double sinC = JsMath.sin(omega);
            double cosC = JsMath.cos(omega);
            double alpha = sinC / (2.0 * qLin);
            double a0inv = 1.0 / (1.0 + alpha);
            double gain = a0inv / Math.sqrt(qLin);
            v.filterBqB1 = (1.0 - cosC) * gain;
            v.filterBqB02 = v.filterBqB1 * 0.5;
            v.filterBqA1 = -2.0 * cosC * a0inv;
            v.filterBqA2 = (1.0 - alpha) * a0inv;
            v.filterIsBiquad = true;
            v.filterActive = true;
            return;
        }

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
