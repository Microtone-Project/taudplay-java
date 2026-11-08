/*
 * Per-instrument mixing chains — port of imix.js (the equipment, the chain,
 * the buses and the routing) and imix-section.js (the `IMix` section's
 * decoder); taudplay-c imix.c. TAUD_ENGINE_SPEC.md §12.4,
 * TAUD_FILE_FORMAT.md §9.15.
 *
 * An instrument whose record names an IMix block (byte 255) does not mix its
 * voices straight into the master: they sum into the instrument's own bus —
 * or, in bus mode, into one bus every instrument on the block shares — that
 * bus runs the block's chain, and the result joins the master, scaled by the
 * song's global gain, in ascending slot order. A block that is off, or holds
 * nothing that is not bypassed, makes no bus at all: the plain path, to the
 * bit.
 *
 *   order 0: itrm → expd → equ1 → deq1 → tsh1 → comp → equ2 → deq2 → unis → satu → dlay → verb → limi → mxtl
 *   order 1: itrm → expd → equ1 → deq1 → tsh1 → comp → equ2 → deq2 → unis → satu → verb → dlay → limi → mxtl
 *
 * Everything that accumulates (an LFO's phase, a pitch shifter's sweep, a
 * tape delay's glide, its wow and flutter) is integer arithmetic on 16.16
 * quantities, held in longs where the JS holds exact binary64 integers;
 * filters and envelopes are binary64 as everywhere else. A chain's output
 * does not depend on how its bus is cut into spans: every counter, phase and
 * the idle rule are per sample.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

import net.torvald.taudplay.Mastering.Biquad;

final class Imix {
    private Imix() {}

    private static final double PI = 3.141592653589793;
    private static final double SQRT1_2 = 0.7071067811865476;
    private static final double LN2 = 0.6931471805599453;
    private static final int FX16 = 65536;
    private static final double TWO32 = 4294967296.0;
    private static final long TWO32I = 1L << 32;

    private static final int IMX_DELAY_MAX_SEC = 4;
    private static final double IMX_PREDELAY_MAX_SEC = 0.5;
    private static final int IMX_CONTROL_PERIOD = 16;
    private static final double IMX_IDLE_LEVEL = 1.0 / 16777216.0;    /* 2^-24 */
    private static final double IMX_IDLE_MARGIN_SEC = 0.05;
    private static final double IMX_DC_HZ = 3.0;
    private static final double IMX_DC_K = 6.3;
    private static final double IMX_TSH_WINDOW_SEC = 0.025;
    private static final int IMX_TSH_SMOOTH_MS = 1;
    private static final double IMX_EXPD_WINDOW_SEC = 0.025;

    /* Equaliser band shapes. */
    private static final int EQ_HIGH_PASS = 0;
    private static final int EQ_LOW_SHELF_ = 1;
    private static final int EQ_BELL = 2;
    private static final int EQ_HIGH_SHELF_ = 3;
    private static final int EQ_LOW_PASS = 4;
    private static final int EQ_NOTCH = 5;
    private static final int EQ_SHAPES = 6;
    private static final int EQ_SLOPES = 4;

    private static final int SAT_TAPE = 0;
    private static final int SAT_SOFT = 1;
    private static final int SAT_HARD = 2;
    private static final int SAT_TUBE = 3;
    private static final int SAT_FOLD = 4;
    private static final int SAT_CURVES = 5;

    private static final int LFO_SINE = 0;
    private static final int LFO_TRIANGLE = 1;
    private static final int LFO_SQUARE = 2;
    private static final int LFO_SAW_UP = 3;
    private static final int LFO_SAW_DOWN = 4;
    private static final int LFO_RANDOM = 5;
    private static final int LFO_WAVES = 6;

    private static final int DEQ_LOW_SHELF_BAND = 0;
    private static final int DEQ_HIGH_SHELF_BAND = 1;

    private static final String[] FOURCC = {
        "itrm", "expd", "equ1", "deq1", "tsh1", "comp", "equ2", "deq2", "unis", "satu", "dlay", "verb", "limi", "mxtl"
    };

    private static final int[][] PIPELINES = {
        { IMX_ITRM, IMX_EXPD, IMX_EQU1, IMX_DEQ1, IMX_TSH1, IMX_COMP, IMX_EQU2, IMX_DEQ2,
          IMX_UNIS, IMX_SATU, IMX_DLAY, IMX_VERB, IMX_LIMI, IMX_MXTL },
        { IMX_ITRM, IMX_EXPD, IMX_EQU1, IMX_DEQ1, IMX_TSH1, IMX_COMP, IMX_EQU2, IMX_DEQ2,
          IMX_UNIS, IMX_SATU, IMX_VERB, IMX_DLAY, IMX_LIMI, IMX_MXTL },
    };

    /** Equipment whose wet/dry byte means something; everything else must carry 0. */
    private static boolean wetApplicable(int k) {
        return k == IMX_EXPD || k == IMX_TSH1 || k == IMX_COMP || k == IMX_UNIS || k == IMX_SATU || k == IMX_DLAY
            || k == IMX_VERB;
    }

    private static boolean isCutShape(int shape) { return shape == EQ_HIGH_PASS || shape == EQ_LOW_PASS; }

    /* ── the decoded equipment ── */

    static final class LfoParams {
        int wave; boolean retrigger, sync; long rate; float depth;
        void copyFrom(LfoParams o) { wave = o.wave; retrigger = o.retrigger; sync = o.sync; rate = o.rate; depth = o.depth; }
    }
    static final class EqBand { boolean on; int shape, slope; float freq, gainDb, q; }
    static final class CompBand { boolean on; float threshDb, ratio, attackMs, releaseMs, kneeDb, makeupDb; }
    static final class TshBand { boolean on; float attackDb, sustainDb, attackMs, releaseMs, outDb; }
    static final class DeqBand {
        boolean on, unlinked, boost;
        float freq, q, gainDb, senseFreq, senseQ, threshDb, ratio, attackMs, releaseMs, rangeDb, actorDb;
    }

    static final class Trim { float gainDb; }
    /** floorDb is −∞ for no floor (a pure expander). */
    static final class Expd {
        boolean keyFilter; int detector;
        float threshDb, floorDb, ratio, rangeDb, attackMs, holdMs, releaseMs, hysteresisDb, keyLowCut, keyHighCut;
    }
    static final class Tsh {
        int n; final float[] xover = new float[IMX_TSH_MAX_BANDS - 1]; final TshBand[] b = new TshBand[IMX_TSH_MAX_BANDS];
        Tsh() { for (int i = 0; i < b.length; i++) b[i] = new TshBand(); }
    }
    static final class Eq {
        int n; final EqBand[] b = new EqBand[IMX_EQ_MAX_BANDS];
        Eq() { for (int i = 0; i < b.length; i++) b[i] = new EqBand(); }
    }
    static final class Comp {
        int detector, n; final float[] xover = new float[IMX_COMP_MAX_BANDS - 1]; final CompBand[] b = new CompBand[IMX_COMP_MAX_BANDS];
        Comp() { for (int i = 0; i < b.length; i++) b[i] = new CompBand(); }
    }
    static final class Deq {
        final DeqBand[] b = new DeqBand[IMX_DEQ_BANDS];
        Deq() { for (int i = 0; i < b.length; i++) b[i] = new DeqBand(); }
    }
    static final class Sat { int curve; float driveDb, inDb, outDb, bias; }
    static final class Unis { int voices; float detune, width, spacing; final LfoParams detuneLfo = new LfoParams(), widthLfo = new LfoParams(); }
    static final class Verb { float size, decay, damping; long preDelay; }
    static final class Dlay { boolean sync, pingPong, pingRight; long time; float feedback, highCut, lowCut, wow, driveDb; }
    static final class Lim { boolean truePeak; float ceilingDb, releaseMs, gainDb; }
    static final class Mx { boolean swap, invL, invR, removeDc; float gainDb, width; }

    /** One piece of equipment, normalised (normaliseEquipment): every float a
     *  binary32, every 16.16 quantity an integer. Only the kind's own part is
     *  meaningful (the C's union). */
    static final class Equipment {
        boolean present, bypass;
        int dry;                /* 0 = all wet … 255 = all dry */
        final Trim trim = new Trim();
        final Expd expd = new Expd();
        final Tsh tsh = new Tsh();
        final Eq eq = new Eq();
        final Comp comp = new Comp();
        final Deq deq = new Deq();
        final Sat sat = new Sat();
        final Unis unis = new Unis();
        final Verb verb = new Verb();
        final Dlay dlay = new Dlay();
        final Lim lim = new Lim();
        final Mx mx = new Mx();
    }

    /** One IMix block (§9.15): what playback reads of it. Equipment this
     *  reader does not know is skipped, as it is by the reference. */
    static final class Block {
        int id;                 /* 1…255 */
        boolean enabled, bus;
        int order;              /* 0 or 1 */
        final Equipment[] eq = new Equipment[IMX_KINDS];
        Block() { for (int i = 0; i < IMX_KINDS; i++) eq[i] = new Equipment(); }
    }

    /* ── the section's decoder (imix-section.js) ── */

    private static long rdU32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static double rdF32(byte[] b, int o) { return Mastering.rdF32(b, o); }

    private static boolean fourccEq(byte[] b, int o, byte[] c, int co) {
        return b[o] == c[co] && b[o + 1] == c[co + 1] && b[o + 2] == c[co + 2] && b[o + 3] == c[co + 3];
    }

    private static int fourccKind(byte[] b, int o) {
        for (int k = 0; k < IMX_KINDS; k++) {
            String f = FOURCC[k];
            if (b[o] == f.charAt(0) && b[o + 1] == f.charAt(1) && b[o + 2] == f.charAt(2) && b[o + 3] == f.charAt(3)) return k;
        }
        return -1;
    }

    /** imxClamp: a binary32 inside the range, or the fallback when not finite. */
    private static float clampf(double v, double lo, double hi, double fallback) {
        if (!isfinite(v)) return (float) fallback;
        return (float) (v < lo ? lo : v > hi ? hi : v);
    }

    /** imxClampFx: the 16.16 fields are U32 in the file — integers, clamped. */
    private static long clampfx(long v, long lo, long hi) { return v < lo ? lo : v > hi ? hi : v; }

    private static boolean readLfo(byte[] b, int o, LfoParams l) {
        if ((b[o] & 0xff) >= LFO_WAVES) return false;
        l.wave = b[o] & 0xff;
        l.retrigger = (b[o + 1] & 1) != 0;
        l.sync = (b[o + 1] & 2) != 0;
        /* normLfo: the rate in 16.16 Hz (0.01…40) or quarter ticks (1…4096). */
        l.rate = l.sync ? clampfx(rdU32(b, o + 4), FX16, 4096L * FX16) : clampfx(rdU32(b, o + 4), 655, 40L * FX16);
        l.depth = clampf(rdF32(b, o + 8), 0, 1, 0);
        return true;
    }

    /** Where the crossovers of an n-band split start: log-spaced over 80 Hz…8 kHz. */
    private static void defaultCrossovers(int n, float[] out) {
        for (int i = 1; i < n; i++) out[i - 1] = (float) (80 * js_pow(100, (double) (i - 1) / imax(1, n - 2)));
        if (n == 2) out[0] = (float) 400;
    }

    private static final double[] DEQ_DEFAULT_FREQS = { 100, 8000, 250, 1000, 3000, 6000 };
    private static final int[] FIXED = { 8, 48, 0, 296, 0, 0, 0, 296, 44, 24, 32, 24, 20, 16 };

    /** Decode one known, version-0 payload at p, normalised (decodeEquipment +
     *  normaliseEquipment); false on a contradiction. */
    private static boolean decodeEquipment(int k, byte[] b, int p, int len, Equipment e) {
        if (FIXED[k] != 0 && len != FIXED[k]) return false;
        if (!wetApplicable(k) && b[p + 1] != 0) return false;
        e.present = true;
        e.bypass = (b[p] & 0x80) != 0;
        e.dry = wetApplicable(k) ? b[p + 1] & 0xff : 0;
        switch (k) {
        case IMX_ITRM:
            e.trim.gainDb = clampf(rdF32(b, p + 4), -24, 24, 0);
            return true;
        case IMX_EXPD: {
            Expd x = e.expd;
            double floorRaw = rdF32(b, p + 12);
            if ((b[p + 5] & 0xff) > 1) return false;
            /* A floor above the threshold says the sound is shut where it should pass. */
            if (floorRaw > rdF32(b, p + 8)) return false;
            x.keyFilter = (b[p + 4] & 1) != 0;
            x.detector = (b[p + 5] & 0xff) == COMP_RMS ? COMP_RMS : COMP_PEAK;
            x.threshDb = clampf(rdF32(b, p + 8), -80, 0, -50);
            /* No floor is −∞ (a pure expander); one above the threshold, once
             * clamped, is brought down to it. */
            if (isnan(floorRaw) || floorRaw == Double.NEGATIVE_INFINITY) x.floorDb = Float.NEGATIVE_INFINITY;
            else {
                float f = clampf(floorRaw, -80, 0, x.threshDb);
                x.floorDb = f < x.threshDb ? f : x.threshDb;
            }
            x.ratio = clampf(rdF32(b, p + 16), 1, 20, 2);
            x.rangeDb = clampf(rdF32(b, p + 20), 0, 80, 24);
            x.attackMs = clampf(rdF32(b, p + 24), 0.1, 300, 1);
            x.holdMs = clampf(rdF32(b, p + 28), 0, 2000, 20);
            x.releaseMs = clampf(rdF32(b, p + 32), 5, 3000, 150);
            x.hysteresisDb = clampf(rdF32(b, p + 36), 0, 12, 4);
            x.keyLowCut = clampf(rdF32(b, p + 40), 20, 2000, 80);
            x.keyHighCut = clampf(rdF32(b, p + 44), 500, 20000, 12000);
            return true;
        }
        case IMX_TSH1: {
            boolean ok = true;
            double prev = 0;
            if (len < 8) return false;
            int n = b[p + 4] & 0xff;
            if (n < 1 || n > IMX_TSH_MAX_BANDS) return false;
            if (len != 8 + 4 * (n - 1) + 24 * n) return false;
            for (int i = 0; i < n - 1; i++) {
                double x = rdF32(b, p + 8 + 4 * i);
                if (i > 0 && !(x > prev)) return false;
                prev = x;
            }
            e.tsh.n = n;
            for (int i = 0; i < n; i++) {
                int o = p + 8 + 4 * (n - 1) + i * 24;
                TshBand t = e.tsh.b[i];
                t.on = (b[o] & 1) != 0;
                t.attackDb = clampf(rdF32(b, o + 4), -24, 24, 0);
                t.sustainDb = clampf(rdF32(b, o + 8), -24, 24, 0);
                t.attackMs = clampf(rdF32(b, o + 12), 1, 300, 20);
                t.releaseMs = clampf(rdF32(b, o + 16), 10, 3000, 250);
                t.outDb = clampf(rdF32(b, o + 20), -24, 24, 0);
            }
            /* n − 1 crossovers, strictly ascending once clamped, or re-spread. */
            for (int i = 0; i < n - 1; i++) {
                e.tsh.xover[i] = clampf(rdF32(b, p + 8 + 4 * i), 20, 20000, 1000);
                if (i > 0 && !(e.tsh.xover[i] > e.tsh.xover[i - 1])) ok = false;
            }
            if (!ok) defaultCrossovers(n, e.tsh.xover);
            return true;
        }
        case IMX_EQU1:
        case IMX_EQU2: {
            if (len < 8) return false;
            int n = b[p + 4] & 0xff;
            if (n > IMX_EQ_MAX_BANDS) return false;
            if (len != 8 + n * 16) return false;
            e.eq.n = n;
            for (int i = 0; i < n; i++) {
                int o = p + 8 + i * 16;
                EqBand t = e.eq.b[i];
                int shape = b[o + 1] & 0xff, slope = b[o + 2] & 0xff;
                if (shape >= EQ_SHAPES || slope >= EQ_SLOPES) return false;
                if (!isCutShape(shape) && slope != 0) return false;
                t.on = (b[o] & 1) != 0;
                t.shape = shape;
                t.slope = isCutShape(shape) ? slope : 0;
                t.freq = clampf(rdF32(b, o + 4), 20, 20000, 1000);
                t.gainDb = clampf(rdF32(b, o + 8), -24, 24, 0);
                t.q = clampf(rdF32(b, o + 12), 0.1, 18, SQRT1_2);
            }
            return true;
        }
        case IMX_COMP: {
            boolean ok = true;
            double prev = 0;
            if (len < 8) return false;
            int n = b[p + 4] & 0xff;
            if (n < 1 || n > IMX_COMP_MAX_BANDS) return false;
            if ((b[p + 5] & 0xff) > 1) return false;
            if (len != 8 + 4 * (n - 1) + 28 * n) return false;
            for (int i = 0; i < n - 1; i++) {
                double x = rdF32(b, p + 8 + 4 * i);
                if (i > 0 && !(x > prev)) return false;
                prev = x;
            }
            e.comp.detector = (b[p + 5] & 0xff) == COMP_RMS ? COMP_RMS : COMP_PEAK;
            e.comp.n = n;
            for (int i = 0; i < n; i++) {
                int o = p + 8 + 4 * (n - 1) + i * 28;
                CompBand t = e.comp.b[i];
                t.on = (b[o] & 1) != 0;
                t.threshDb = clampf(rdF32(b, o + 4), -60, 0, -18);
                t.ratio = clampf(rdF32(b, o + 8), 1, 20, 3);
                t.attackMs = clampf(rdF32(b, o + 12), 0.1, 300, 10);
                t.releaseMs = clampf(rdF32(b, o + 16), 5, 3000, 120);
                t.kneeDb = clampf(rdF32(b, o + 20), 0, 24, 6);
                t.makeupDb = clampf(rdF32(b, o + 24), -12, 24, 0);
            }
            /* n − 1 crossovers, strictly ascending once clamped, or re-spread. */
            for (int i = 0; i < n - 1; i++) {
                e.comp.xover[i] = clampf(rdF32(b, p + 8 + 4 * i), 20, 20000, 1000);
                if (i > 0 && !(e.comp.xover[i] > e.comp.xover[i - 1])) ok = false;
            }
            if (!ok) defaultCrossovers(n, e.comp.xover);
            return true;
        }
        case IMX_DEQ1:
        case IMX_DEQ2:
            for (int i = 0; i < IMX_DEQ_BANDS; i++) {
                int o = p + 8 + i * 48;
                DeqBand t = e.deq.b[i];
                double rf = rdF32(b, o + 4), rq = rdF32(b, o + 8), sf = rdF32(b, o + 16), sq = rdF32(b, o + 20);
                double dq = i < 2 ? SQRT1_2 : 1;
                boolean unlinked = (b[o] & 2) != 0;
                /* A linked band senses where it acts, and its file says so. */
                if (!unlinked && !(sf == rf && sq == rq)) return false;
                t.on = (b[o] & 1) != 0;
                t.unlinked = unlinked;
                t.boost = (b[o] & 4) != 0;
                t.freq = clampf(rf, 20, 20000, DEQ_DEFAULT_FREQS[i]);
                t.q = clampf(rq, 0.1, 18, dq);
                t.gainDb = clampf(rdF32(b, o + 12), -24, 24, 0);
                t.senseFreq = unlinked ? clampf(sf, 20, 20000, t.freq) : t.freq;
                t.senseQ = unlinked ? clampf(sq, 0.1, 18, t.q) : t.q;
                t.threshDb = clampf(rdF32(b, o + 24), -60, 0, -24);
                t.ratio = clampf(rdF32(b, o + 28), 1, 20, 3);
                t.attackMs = clampf(rdF32(b, o + 32), 0.1, 300, 5);
                t.releaseMs = clampf(rdF32(b, o + 36), 5, 3000, 100);
                t.rangeDb = clampf(rdF32(b, o + 40), 0, 24, 6);
                t.actorDb = clampf(rdF32(b, o + 44), -24, 24, 0);
            }
            return true;
        case IMX_SATU:
            if ((b[p + 4] & 0xff) >= SAT_CURVES) return false;
            e.sat.curve = b[p + 4] & 0xff;
            e.sat.driveDb = clampf(rdF32(b, p + 8), 0, 36, 6);
            e.sat.inDb = clampf(rdF32(b, p + 12), -24, 24, 0);
            e.sat.outDb = clampf(rdF32(b, p + 16), -24, 24, 0);
            e.sat.bias = clampf(rdF32(b, p + 20), -1, 1, 0);
            return true;
        case IMX_UNIS:
            if ((b[p + 4] & 0xff) < 2 || (b[p + 4] & 0xff) > IMX_UNIS_MAX_VOICES) return false;
            e.unis.voices = b[p + 4] & 0xff;
            e.unis.detune = clampf(rdF32(b, p + 8), 0, 100, 15);
            e.unis.width = clampf(rdF32(b, p + 12), 0, 1, 0.8);
            e.unis.spacing = clampf(rdF32(b, p + 16), 0, 1, 0);
            return readLfo(b, p + 20, e.unis.detuneLfo) && readLfo(b, p + 32, e.unis.widthLfo);
        case IMX_VERB:
            e.verb.size = clampf(rdF32(b, p + 8), 0, 1, 0.5);
            e.verb.decay = clampf(rdF32(b, p + 12), 0.1, 30, 1.8);
            e.verb.damping = clampf(rdF32(b, p + 16), 1000, 20000, 8000);
            e.verb.preDelay = clampfx(rdU32(b, p + 20), 0, (long) (IMX_PREDELAY_MAX_SEC * FX16));
            return true;
        case IMX_DLAY:
            e.dlay.sync = (b[p + 4] & 1) != 0;
            e.dlay.pingPong = (b[p + 4] & 2) != 0;
            e.dlay.pingRight = (b[p + 4] & 6) == 6;   /* right first: R-L-R-L */
            e.dlay.time = e.dlay.sync ? clampfx(rdU32(b, p + 8), FX16 / 4, 1024L * FX16)
                                      : clampfx(rdU32(b, p + 8), 66, (long) IMX_DELAY_MAX_SEC * FX16);
            e.dlay.feedback = clampf(rdF32(b, p + 12), 0, 1.2, 0.35);
            e.dlay.highCut = clampf(rdF32(b, p + 16), 500, 20000, 6000);
            e.dlay.lowCut = clampf(rdF32(b, p + 20), 20, 2000, 120);
            e.dlay.wow = clampf(rdF32(b, p + 24), 0, 1, 0.2);
            e.dlay.driveDb = clampf(rdF32(b, p + 28), 0, 24, 3);
            return true;
        case IMX_LIMI:
            e.lim.truePeak = (b[p + 4] & 1) != 0;
            e.lim.ceilingDb = clampf(rdF32(b, p + 8), -24, 0, -1);
            e.lim.releaseMs = clampf(rdF32(b, p + 12), 1, 1000, 60);
            e.lim.gainDb = clampf(rdF32(b, p + 16), -12, 24, 0);
            return true;
        default: /* IMX_MXTL */
            e.mx.swap = (b[p + 4] & 1) != 0;
            e.mx.invL = (b[p + 4] & 2) != 0;
            e.mx.invR = (b[p + 4] & 4) != 0;
            e.mx.removeDc = (b[p + 4] & 8) != 0;
            e.mx.gainDb = clampf(rdF32(b, p + 8), -24, 24, 0);
            e.mx.width = clampf(rdF32(b, p + 12), 0, 2, 1);
            return true;
        }
    }

    /** Decode the block at `o` (decodeBlock). Returns the offset past it, or 0
     *  when it is invalid. */
    private static int decodeBlock(byte[] b, int len, int o, Block blk) {
        if (o + 4 > len) return 0;
        if (b[o] == 0) return 0;                    /* id 0 is "no chain" */
        int status = b[o + 2] & 0xff;
        if ((status >> 4) > 1) return 0;            /* pipeline order */
        int n = b[o + 3] & 0xff;
        int p = o + 4;
        if (p + 4L * n > len) return 0;
        int list = p;
        for (int i = 0; i < n; i++)                 /* the list names no piece twice */
            for (int j = i + 1; j < n; j++)
                if (fourccEq(b, list + 4 * i, b, list + 4 * j)) return 0;
        p += 4 * n;
        blk.id = b[o] & 0xff;
        blk.enabled = (status & 1) != 0;
        blk.bus = (status & 2) != 0;
        blk.order = status >> 4;
        int[] seen = new int[n];
        for (int i = 0; i < n; i++) {
            boolean listed = false;
            if (p + 8 > len) return 0;
            int fc = p;
            long size = rdU32(b, p + 4);
            p += 8;
            if (size > len - p) return 0;
            if (size < 4) return 0;
            int payload = p;
            p += (int) size;
            for (int j = 0; j < i; j++) if (fourccEq(b, seen[j], b, fc)) return 0;  /* a record twice */
            seen[i] = fc;
            /* The list is a table of contents: every record must be in it. */
            for (int j = 0; j < n; j++) if (fourccEq(b, list + 4 * j, b, fc)) listed = true;
            if (!listed) return 0;
            int k = fourccKind(b, fc);
            /* Equipment this reader does not know — an unknown FourCC, or a
             * payload version above 0 — is kept verbatim by the reference and
             * skipped by playback. */
            if (k < 0 || (b[payload] & 0x0f) > 0) continue;
            if (!decodeEquipment(k, b, payload, (int) size, blk.eq[k])) return 0;
        }
        return p;
    }

    /** Where the block at `o` ends, from its framing alone, or 0 (skipBlock). */
    private static int skipBlock(byte[] b, int len, int o) {
        if (o + 4 > len) return 0;
        int n = b[o + 3] & 0xff;
        long p = o + 4 + 4L * n;
        for (int i = 0; i < n; i++) {
            if (p + 8 > len) return 0;
            long size = rdU32(b, (int) p + 4);
            if (size > len - p - 8) return 0;
            p += 8 + size;
        }
        return (int) p;
    }

    /** Whole-section payload (`len` bytes at `off`) → its valid blocks
     *  (parseImixSection). A block that contradicts itself is dropped whole,
     *  and so is every block of an id two blocks share; one that cannot be
     *  delimited ends the walk. */
    static Block[] parseSection(byte[] all, int off, int len) {
        byte[] b = java.util.Arrays.copyOfRange(all, off, off + len);
        java.util.ArrayList<Block> list = new java.util.ArrayList<>();
        int o = 0;
        while (o < len) {
            Block blk = new Block();
            int next = decodeBlock(b, len, o, blk);
            if (next == 0) {
                next = skipBlock(b, len, o);
                if (next == 0) break;
                o = next;
                continue;
            }
            o = next;
            list.add(blk);
        }
        /* An id two decoded blocks share is invalid however many copies there are. */
        boolean[] shared = new boolean[256];
        for (int i = 0; i < list.size(); i++)
            for (int j = i + 1; j < list.size(); j++)
                if (list.get(j).id == list.get(i).id) shared[list.get(i).id] = true;
        java.util.ArrayList<Block> kept = new java.util.ArrayList<>();
        for (Block blk : list) if (!shared[blk.id]) kept.add(blk);
        return kept.toArray(new Block[0]);
    }

    /** Would a chain built from this block change a sample (imixBlockEngaged)? */
    static boolean blockEngaged(Block b) {
        if (b == null || !b.enabled) return false;
        for (int k = 0; k < IMX_KINDS; k++) if (b.eq[k].present && !b.eq[k].bypass) return true;
        return false;
    }

    /* ── the equipment ── */
    /* Every piece: configure (keeps state), reset, process over a
     * channel-major buffer in place, and its memory (the samples its delays
     * hold). */

    static final class Ctx {
        int nch;
        boolean stereo, ambisonic;
        int bpm;
        double dry;
        double[] dryBuf;
    }

    /** One LFO: a U32 phase, a waveform, and (for S&H) its own random stream. */
    static final class Lfo {
        final LfoParams p = new LfoParams();
        long phase, inc;
        int kSync = -1, kBpm = -1, kFs = -1;
        long kRate = -1;
        double held;
        int rng = 0x9e3779b9;

        Lfo() { p.rate = FX16 / 2; }

        void configure(LfoParams q) { p.copyFrom(q); kSync = -1; }
        void reset() { phase = 0; held = 0; rng = 0x9e3779b9; }

        /** Phase increment per sample (lfoIncrement): floor(R·2^16 / fs) in Hz
         *  mode, floor(2^51·bpm / (5·P·fs)) in sync mode, at most 2^31 − 1. */
        static long increment(boolean sync, long rate, int bpm, int fs) {
            long inc;
            if (!sync) {
                inc = (long) floor(((double) rate * FX16) / fs);
            } else {
                long num = (1L << 51) * (long) (bpm < 0 ? 0 : bpm);
                long den = 5L * rate * (long) fs;
                long q = Long.divideUnsigned(num, den);
                inc = q > 0x7fffffffL ? 0x7fffffffL : q;
            }
            return inc > 0x7fffffffL ? 0x7fffffffL : inc < 0 ? 0 : inc;
        }

        void tempo(int bpm, int fs) {
            int sync = p.sync ? 1 : 0;
            if (sync != kSync || p.rate != kRate || fs != kFs || (sync == 1 && bpm != kBpm)) {
                kSync = sync; kRate = p.rate; kBpm = bpm; kFs = fs;
                inc = increment(p.sync, p.rate, bpm, fs);
            }
        }

        void draw() {
            int x = rng;
            x ^= x << 13;
            x ^= x >>> 17;
            x ^= x << 5;
            rng = x;
            held = ((x & 0xffffffffL) / TWO32) * 2 - 1;
        }

        void step() {
            long next = phase + inc;
            if (next >= TWO32I) {
                if (p.wave == LFO_RANDOM) draw();
                phase = next - TWO32I;
            } else {
                phase = next;
            }
        }

        void skip(int n) {
            long total = phase + inc * n;
            long wraps = total >> 32;
            if (p.wave == LFO_RANDOM) for (long i = 0; i < wraps; i++) draw();
            phase = total - wraps * TWO32I;
        }

        double value() {
            double ph = (double) phase / TWO32;
            switch (p.wave) {
            case LFO_SINE: return js_sin(2 * PI * ph);
            case LFO_TRIANGLE: return ph < 0.25 ? 4 * ph : ph < 0.75 ? 2 - 4 * ph : 4 * ph - 4;
            case LFO_SQUARE: return ph < 0.5 ? 1 : -1;
            case LFO_SAW_UP: return 2 * ph - 1;
            case LFO_SAW_DOWN: return 1 - 2 * ph;
            default: return held;
            }
        }
    }

    /** 4-point, 3rd-order Hermite read of a circular buffer at a fractional
     *  delay of `delay` samples behind write index `w` (which holds the
     *  newest sample). `delay` must be at least 1. */
    private static double hermiteRead(double[] buf, int off, int len, int w, double delay) {
        int di = (int) floor(delay);
        double f = delay - di;
        int i0 = w - di + 1, i1, i2, i3;
        if (i0 < 0) i0 += len;
        if (i0 >= len) i0 -= len;
        i1 = i0 - 1; if (i1 < 0) i1 += len;
        i2 = i1 - 1; if (i2 < 0) i2 += len;
        i3 = i2 - 1; if (i3 < 0) i3 += len;
        double xm1 = buf[off + i0], x0 = buf[off + i1], x1 = buf[off + i2], x2 = buf[off + i3];
        double c0 = x0;
        double c1 = 0.5 * (x1 - xm1);
        double c2 = xm1 - 2.5 * x0 + 2 * x1 - 0.5 * x2;
        double c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1);
        return ((c3 * f + c2) * f + c1) * f + c0;
    }

    /** Wet/dry crossfade, in place: data ← wet·(1 − d) + dry·d over [n0, n1). */
    private static void mixDry(double[] data, double[] dryBuf, int nch, int stride, int n0, int n1, double d) {
        if (d <= 0) return;
        double w = 1 - d;
        for (int c = 0; c < nch; c++) {
            int o = c * stride;
            for (int n = n0; n < n1; n++) data[o + n] = data[o + n] * w + dryBuf[o + n] * d;
        }
    }

    private static void copySpan(double[] src, double[] dst, int nch, int stride, int n0, int n1) {
        for (int c = 0; c < nch; c++) {
            int o = c * stride;
            for (int n = n0; n < n1; n++) dst[o + n] = src[o + n];
        }
    }

    /** A stage of the chain: one piece of equipment's running state. */
    private abstract static class Stage {
        abstract void configure(Equipment e, double rate);
        void reset() {}
        abstract void process(double[] data, int stride, int n0, int n1, Ctx ctx);
        int memory() { return 0; }
        void skip(int frames, Ctx ctx) {}
        boolean oom() { return false; }
    }

    /* — trim — */

    private static final class TrimStage extends Stage {
        double g;
        void configure(Equipment p, double rate) { g = Mastering.dbToGain(p.trim.gainDb); }
        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            if (g == 1) return;
            for (int c = 0; c < ctx.nch; c++) {
                int o = c * stride;
                for (int n = n0; n < n1; n++) data[o + n] *= g;
            }
        }
    }

    /* — the peak of a level over the last W samples, this one included (0
     *   before a reset): the transient shaper's and the expander's detector
     *   (ImxPeakWindow). A monotonic deque of the samples that can still be
     *   the peak, oldest first, levels strictly falling; the sample counter
     *   runs mod 2^31, as the reference's does — */

    private static final class PeakWin {
        int w, head, len;
        int t;
        int[] idx;
        double[] val;

        PeakWin(int w) {
            this.w = w;
            idx = new int[w];
            val = new double[w];
        }

        void reset() { head = len = 0; t = 0; }

        /** Take the next sample's level; return the window's peak. */
        double push(double l) {
            int W = w, head = this.head, len = this.len;
            int ix = t;
            t = (ix + 1) & 0x7fffffff;
            if (len > 0 && ((ix - idx[head]) & 0x7fffffff) >= W) {
                head = head + 1 == W ? 0 : head + 1;
                len--;
            }
            while (len > 0) {
                int back = head + len - 1;
                if (back >= W) back -= W;
                if (val[back] > l) break;
                len--;
            }
            int at = head + len;
            if (at >= W) at -= W;
            idx[at] = ix;
            val[at] = l;
            this.head = head;
            this.len = len + 1;
            return val[head];
        }
    }

    /* — downward expander, shutting like a gate below its floor
     *   (ImxExpander). The detector hears the bus linked — through the key
     *   filter (an RBJ high-pass, then low-pass, at Q 1/√2) when that is on —
     *   as the window's peak or the compressor's 10 ms RMS. Shut below the
     *   floor until the level is back the hysteresis above it; otherwise the
     *   expander's curve under the threshold. The gain opens at the attack,
     *   and closes at the release once the hold is over — */

    private static final class ExpdStage extends Stage {
        Equipment p;
        PeakWin win;
        final Biquad hp = new Biquad(), lp = new Biquad();
        double atk, rel, rmsCoef;
        int holdN;
        double g;          /* the gain, dB (≤ 0) */
        int hold;          /* samples of hold left */
        boolean shut;
        double ms;         /* RMS detector's mean square */

        void configure(Equipment p, double rate) {
            int w = imax(1, (int) js_round(IMX_EXPD_WINDOW_SEC * rate));
            this.p = p;
            if (win == null || w != win.w) win = new PeakWin(w);
            atk = Mastering.onePole(p.expd.attackMs, rate);
            rel = Mastering.onePole(p.expd.releaseMs, rate);
            rmsCoef = Mastering.onePole(10, rate);
            holdN = (int) js_round((p.expd.holdMs * rate) / 1000);
            hp.highPass(p.expd.keyLowCut, SQRT1_2, rate);
            lp.lowPass(p.expd.keyHighCut, SQRT1_2, rate);
        }

        void reset() {
            if (win != null) win.reset();
            hp.reset();
            lp.reset();
            g = 0; hold = 0; shut = false; ms = 0;
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            double dry = ctx.dry;
            boolean key = p.expd.keyFilter, peak = p.expd.detector != COMP_RMS;
            double T = p.expd.threshDb, slope = (double) p.expd.ratio - 1, range = p.expd.rangeDb;
            double floor_ = p.expd.floorDb, reopen = floor_ + p.expd.hysteresisDb;
            double g = this.g, ms = this.ms;
            int hold = this.hold;
            boolean shut = this.shut;
            if (dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
            for (int i = n0; i < n1; i++) {
                double l = 0, sum = 0, level, L, target, k;
                for (int c = 0; c < nch; c++) {
                    double x = data[c * stride + i];
                    if (key) x = lp.run(c, hp.run(c, x));
                    if (peak) { double a = x < 0 ? -x : x; if (a > l) l = a; } else sum += x * x;
                }
                if (peak) level = win.push(l);
                else { ms += (sum / nch - ms) * rmsCoef; level = sqrt(ms); }
                L = level > 1e-6 ? 20 * js_log10(level) : -120;
                if (L < floor_) shut = true;
                else if (L >= reopen) shut = false;
                if (shut) target = -range;
                else if (L < T) {
                    double under = (T - L) * slope;
                    target = under > range ? -range : -under;
                } else target = 0;
                /* Opening at the attack; closing at the release, once the hold is over. */
                if (target >= g) { g += (target - g) * atk; hold = holdN; }
                else if (hold > 0) hold--;
                else g += (target - g) * rel;
                k = Mastering.dbToGain(g);
                for (int c = 0; c < nch; c++) data[c * stride + i] *= k;
            }
            this.g = g; this.hold = hold; this.shut = shut; this.ms = ms;
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, dry);
        }
    }

    /* — equaliser: up to eight bands; a cut band a Butterworth cascade of up
     *   to four sections, every other shape one RBJ section — */

    private static final class EqStage extends Stage {
        final Biquad[][] secs = new Biquad[IMX_EQ_MAX_BANDS][4];
        final Biquad[] live = new Biquad[IMX_EQ_MAX_BANDS * 4];
        int nLive;

        EqStage() { for (Biquad[] r : secs) for (int j = 0; j < 4; j++) r[j] = new Biquad(); }

        void configure(Equipment p, double rate) {
            double[] qs = new double[4];
            nLive = 0;
            for (int i = 0; i < p.eq.n; i++) {
                EqBand b = p.eq.b[i];
                if (!b.on) continue;
                boolean cut = isCutShape(b.shape);
                int k = cut ? b.slope + 1 : 1;
                /* A 12 dB/octave cut is one section at the band's own Q; a
                 * steeper one a maximally flat Butterworth cascade (butterQs). */
                if (cut && k > 1) {
                    for (int j = 1; j <= k; j++) qs[j - 1] = 1 / (2 * js_cos(((2 * j - 1) * PI) / (4 * k)));
                } else {
                    qs[0] = b.q;
                }
                for (int j = 0; j < k; j++) {
                    Biquad sec = secs[i][j];
                    switch (b.shape) {
                    case EQ_HIGH_PASS: sec.highPass(b.freq, qs[j], rate); break;
                    case EQ_LOW_PASS: sec.lowPass(b.freq, qs[j], rate); break;
                    case EQ_LOW_SHELF_: sec.lowShelf(b.freq, b.gainDb, b.q, rate); break;
                    case EQ_HIGH_SHELF_: sec.highShelf(b.freq, b.gainDb, b.q, rate); break;
                    case EQ_NOTCH: sec.notch(b.freq, b.q, rate); break;
                    default: sec.peaking(b.freq, b.gainDb, b.q, rate); break;
                    }
                    live[nLive++] = sec;
                }
            }
        }

        void reset() { for (Biquad[] r : secs) for (Biquad q : r) q.reset(); }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nl = nLive;
            if (nl == 0) return;
            for (int c = 0; c < ctx.nch; c++) {
                int o = c * stride;
                for (int n = n0; n < n1; n++) {
                    double y = data[o + n];
                    for (int i = 0; i < nl; i++) y = live[i].run(c, y);
                    data[o + n] = y;
                }
            }
        }
    }

    /* — dynamic equaliser: each band a SENSOR (low-pass for the low shelf,
     *   high-pass for the high one, band-pass for the bells) and an ACTOR
     *   whose gain is the static gain plus a dynamic part, its coefficients
     *   following that gain every IMX_CONTROL_PERIOD samples — */

    private static final class DeqStage extends Stage {
        Equipment p;
        double rate;
        final Biquad[] act = new Biquad[IMX_DEQ_BANDS], sense = new Biquad[IMX_DEQ_BANDS];
        final double[] env = new double[IMX_DEQ_BANDS], actor = new double[IMX_DEQ_BANDS];
        final double[] atk = new double[IMX_DEQ_BANDS], rel = new double[IMX_DEQ_BANDS];
        final int[] live = new int[IMX_DEQ_BANDS];
        int nLive;
        int ctl;

        DeqStage(int rate) {
            for (int i = 0; i < IMX_DEQ_BANDS; i++) { act[i] = new Biquad(); sense[i] = new Biquad(); actor[i] = 1; }
            this.rate = rate;
        }

        private void setAct(int i) {
            DeqBand b = p.deq.b[i];
            double g = b.gainDb + env[i] * actor[i];
            if (i == DEQ_LOW_SHELF_BAND) act[i].lowShelf(b.freq, g, b.q, rate);
            else if (i == DEQ_HIGH_SHELF_BAND) act[i].highShelf(b.freq, g, b.q, rate);
            else act[i].peaking(b.freq, g, b.q, rate);
        }

        void configure(Equipment p, double rate) {
            this.p = p;
            this.rate = rate;
            nLive = 0;
            for (int i = 0; i < IMX_DEQ_BANDS; i++) {
                DeqBand b = p.deq.b[i];
                if (!b.on) continue;
                live[nLive++] = i;
                atk[i] = Mastering.onePole(b.attackMs, rate);
                actor[i] = Mastering.dbToGain(b.actorDb);
                rel[i] = Mastering.onePole(b.releaseMs, rate);
                if (i == DEQ_LOW_SHELF_BAND) sense[i].lowPass(b.senseFreq, b.senseQ, rate);
                else if (i == DEQ_HIGH_SHELF_BAND) sense[i].highPass(b.senseFreq, b.senseQ, rate);
                else sense[i].bandPass(b.senseFreq, b.senseQ, rate);
                setAct(i);
            }
        }

        void reset() {
            for (int i = 0; i < IMX_DEQ_BANDS; i++) {
                act[i].reset();
                sense[i].reset();
                env[i] = 0;
            }
            ctl = 0;
            if (p != null) for (int i = 0; i < nLive; i++) setAct(live[i]);
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            if (nLive == 0) return;
            for (int n = n0; n < n1; n++) {
                if (ctl == 0) for (int k = 0; k < nLive; k++) setAct(live[k]);
                ctl = ctl + 1 == IMX_CONTROL_PERIOD ? 0 : ctl + 1;
                for (int k = 0; k < nLive; k++) {
                    int i = live[k];
                    DeqBand b = p.deq.b[i];
                    double level = 0;
                    /* Sense on the band's INPUT: the bands run in series. */
                    for (int c = 0; c < nch; c++) {
                        double sv = sense[i].run(c, data[c * stride + n]);
                        double a = sv < 0 ? -sv : sv;
                        if (a > level) level = a;
                    }
                    double levelDb = level > 1e-6 ? 20 * js_log10(level) : -120;
                    double over = levelDb - b.threshDb;
                    double amt = over > 0 ? over * (1 - 1 / (double) b.ratio) : 0;
                    if (amt > b.rangeDb) amt = b.rangeDb;
                    double target = b.boost ? amt : -amt;
                    double cur = env[i];
                    boolean away = (target < 0 ? -target : target) > (cur < 0 ? -cur : cur);
                    env[i] = cur + (target - cur) * (away ? atk[i] : rel[i]);
                    for (int c = 0; c < nch; c++) {
                        int j = c * stride + n;
                        data[j] = act[i].run(c, data[j]);
                    }
                }
            }
        }
    }

    /* — the multiband split the compressor and the transient shaper share
     *   (ImxSplit): Linkwitz–Riley 4th-order crossovers in a cascade, each
     *   band phase-matched through the all-pass of every later crossover, so
     *   the bands sum to the input through one all-pass each — */

    private static final int IMX_SPLIT_MAX = 6;

    private static final class Split {
        int nch, bandsN;
        final Biquad[][] lp = new Biquad[IMX_SPLIT_MAX - 1][2], hp = new Biquad[IMX_SPLIT_MAX - 1][2];
        final Biquad[][] ap = new Biquad[IMX_SPLIT_MAX][IMX_SPLIT_MAX - 2];
        final double[][] bufs = new double[IMX_SPLIT_MAX][];

        Split(int nch) {
            this.nch = nch;
            for (int k = 0; k < IMX_SPLIT_MAX - 1; k++) for (int j = 0; j < 2; j++) { lp[k][j] = new Biquad(); hp[k][j] = new Biquad(); }
            for (int k = 0; k < IMX_SPLIT_MAX; k++) for (int j = 0; j < IMX_SPLIT_MAX - 2; j++) ap[k][j] = new Biquad();
        }

        /** Make room for n bands; true when the count changed, so the
         *  caller's per-band state starts again too. */
        boolean resize(int n) {
            if (n == bandsN) return false;
            bandsN = n;
            for (int k = 0; k < IMX_SPLIT_MAX - 1; k++) {
                lp[k][0].init(); lp[k][1].init();
                hp[k][0].init(); hp[k][1].init();
            }
            for (int k = 0; k < IMX_SPLIT_MAX; k++)
                for (int j = 0; j < IMX_SPLIT_MAX - 2; j++) ap[k][j].init();
            java.util.Arrays.fill(bufs, null);
            if (n > 1) {
                for (int k = 0; k < n; k++) bufs[k] = new double[nch * TRACKER_CHUNK];
            }
            return true;
        }

        void configure(float[] xover, double rate) {
            int n = bandsN;
            for (int k = 0; k < n - 1; k++) {
                double f = xover[k];
                lp[k][0].lowPass(f, SQRT1_2, rate); lp[k][1].lowPass(f, SQRT1_2, rate);
                hp[k][0].highPass(f, SQRT1_2, rate); hp[k][1].highPass(f, SQRT1_2, rate);
            }
            for (int k = 0; k < n; k++)
                for (int j = k + 1; j < n - 1; j++) ap[k][j - k - 1].allPass(xover[j], SQRT1_2, rate);
        }

        void reset() {
            for (int k = 0; k < IMX_SPLIT_MAX - 1; k++) {
                lp[k][0].reset(); lp[k][1].reset();
                hp[k][0].reset(); hp[k][1].reset();
            }
            for (int k = 0; k < IMX_SPLIT_MAX; k++)
                for (int j = 0; j < IMX_SPLIT_MAX - 2; j++) ap[k][j].reset();
        }

        /** The bands summed into `out` over [n0, n1). */
        void sum(double[] out, int stride, int n0, int n1, int nch) {
            int n = bandsN;
            for (int c = 0; c < nch; c++) {
                int o = c * stride;
                for (int i = n0; i < n1; i++) {
                    double sum = 0;
                    for (int k = 0; k < n; k++) sum += bufs[k][o + i];
                    out[o + i] = sum;
                }
            }
        }

        /** Split [n0, n1) of `data` into the bands (bufs), and — when
         *  `dryBuf` is given — their untouched sum into it. */
        void run(double[] data, int stride, int n0, int n1, int nch, double[] dryBuf) {
            int n = bandsN;
            /* Split: rest ← input; band k ← LR4 low of rest; rest ← LR4 high of rest. */
            for (int c = 0; c < nch; c++) {
                int o = c * stride;
                for (int i = n0; i < n1; i++) {
                    double rest = data[o + i];
                    for (int k = 0; k < n - 1; k++) {
                        bufs[k][o + i] = lp[k][1].run(c, lp[k][0].run(c, rest));
                        rest = hp[k][1].run(c, hp[k][0].run(c, rest));
                    }
                    bufs[n - 1][o + i] = rest;
                    /* Phase-match each band to the crossovers it did not pass through. */
                    for (int k = 0; k < n - 2; k++) {
                        double y = bufs[k][o + i];
                        int naps = n - 2 - k;
                        for (int j = 0; j < naps; j++) y = ap[k][j].run(c, y);
                        bufs[k][o + i] = y;
                    }
                }
            }
            if (dryBuf != null) sum(dryBuf, stride, n0, n1, nch);
        }
    }

    /* — compressor, one to six bands: one band is the mastering chain's
     *   compressor, linked across the bus; more compress each band of the
     *   split by its own detector and sum them — */

    private static final class CompStage extends Stage {
        Equipment p;
        final Split xo;
        final Mastering.Compressor[] comps = new Mastering.Compressor[IMX_COMP_MAX_BANDS];

        CompStage(int nch) {
            xo = new Split(nch);
            for (int k = 0; k < comps.length; k++) comps[k] = new Mastering.Compressor();
        }

        void configure(Equipment p, double rate) {
            int n = p.comp.n;
            this.p = p;
            if (xo.resize(n)) {
                for (int k = 0; k < IMX_COMP_MAX_BANDS; k++) { comps[k] = new Mastering.Compressor(); comps[k].levelDb = -120; }
            }
            xo.configure(p.comp.xover, rate);
            for (int k = 0; k < n; k++) {
                CompBand b = p.comp.b[k];
                comps[k].configure(p.comp.detector, b.threshDb, b.ratio, b.attackMs,
                                   b.releaseMs, b.kneeDb, b.makeupDb, rate);
            }
        }

        void reset() {
            xo.reset();
            for (int k = 0; k < IMX_COMP_MAX_BANDS; k++) comps[k].reset();
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch, n = xo.bandsN;
            double dry = ctx.dry;
            if (n == 1) {
                Mastering.Compressor comp = comps[0];
                if (dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
                if (p.comp.b[0].on) {
                    for (int i = n0; i < n1; i++) {
                        double g = comp.stepPlanar(data, i, stride, nch);
                        for (int c = 0; c < nch; c++) data[c * stride + i] *= g;
                    }
                }
                mixDry(data, ctx.dryBuf, nch, stride, n0, n1, dry);
                return;
            }
            xo.run(data, stride, n0, n1, nch, dry > 0 ? ctx.dryBuf : null);
            for (int k = 0; k < n; k++) {
                Mastering.Compressor comp = comps[k];
                double[] b = xo.bufs[k];
                if (!p.comp.b[k].on) continue;     /* an unused band passes, unprocessed */
                for (int i = n0; i < n1; i++) {
                    double g = comp.stepPlanar(b, i, stride, nch);
                    for (int c = 0; c < nch; c++) b[c * stride + i] *= g;
                }
            }
            xo.sum(data, stride, n0, n1, nch);
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, dry);
        }
    }

    /* — transient shaper, one to six bands over the split: blind to level.
     *   Per band, S rises toward the window's peak P at the attack time and
     *   drops with it at once (transient = 1 − S/P); L rises with P at once
     *   and falls at the sustain time (sustain = 1 − P/L); the gain glides
     *   toward attack × transient + sustain × sustain through a 1 ms
     *   one-pole — */

    private static final class TshStage extends Stage {
        Equipment p;
        int win;
        double rate;
        final Split xo;
        final PeakWin[] peak = new PeakWin[IMX_TSH_MAX_BANDS];
        final double[] S = new double[IMX_TSH_MAX_BANDS], L = new double[IMX_TSH_MAX_BANDS], e = new double[IMX_TSH_MAX_BANDS];
        final double[] atk = new double[IMX_TSH_MAX_BANDS], rel = new double[IMX_TSH_MAX_BANDS];
        double smooth;

        TshStage(int nch) { xo = new Split(nch); }

        private void clear() {
            for (int k = 0; k < IMX_TSH_MAX_BANDS; k++) {
                if (peak[k] != null) peak[k].reset();
                S[k] = L[k] = e[k] = 0;
            }
        }

        void configure(Equipment p, double rate) {
            int n = p.tsh.n;
            int win = imax(1, (int) js_round(IMX_TSH_WINDOW_SEC * rate));
            this.p = p;
            if (xo.resize(n) || win != this.win || rate != this.rate) {
                this.win = win;
                java.util.Arrays.fill(peak, null);
                for (int k = 0; k < n; k++) peak[k] = new PeakWin(win);
                clear();
            }
            this.rate = rate;
            smooth = Mastering.onePole(IMX_TSH_SMOOTH_MS, rate);
            xo.configure(p.tsh.xover, rate);
            for (int k = 0; k < n; k++) {
                atk[k] = Mastering.onePole(p.tsh.b[k].attackMs, rate);
                rel[k] = Mastering.onePole(p.tsh.b[k].releaseMs, rate);
            }
        }

        void reset() {
            xo.reset();
            clear();
        }

        /** Band k over [n0, n1) of `b`, in place. */
        private void band(int k, double[] b, int stride, int n0, int n1, int nch) {
            TshBand band = p.tsh.b[k];
            PeakWin win = peak[k];
            double S = this.S[k], L = this.L[k], e = this.e[k];
            double atk = this.atk[k], rel = this.rel[k], sm = smooth;
            double ga = band.attackDb, gs = band.sustainDb, out = band.outDb;
            for (int i = n0; i < n1; i++) {
                double l = 0;
                for (int c = 0; c < nch; c++) {
                    double x = b[c * stride + i];
                    double a = x < 0 ? -x : x;
                    if (a > l) l = a;
                }
                double P = win.push(l);
                S = P > S ? S + (P - S) * atk : P;
                L = P < L ? L + (P - L) * rel : P;
                double tr = P > 0 ? 1 - S / P : 0;
                double su = L > 0 ? 1 - P / L : 0;
                e += (ga * tr + gs * su - e) * sm;
                double g = Mastering.dbToGain(e + out);
                for (int c = 0; c < nch; c++) b[c * stride + i] *= g;
            }
            this.S[k] = S; this.L[k] = L; this.e[k] = e;
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch, n = xo.bandsN;
            double dry = ctx.dry;
            if (n == 1) {
                if (dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
                if (p.tsh.b[0].on) band(0, data, stride, n0, n1, nch);
            } else {
                xo.run(data, stride, n0, n1, nch, dry > 0 ? ctx.dryBuf : null);
                for (int k = 0; k < n; k++) if (p.tsh.b[k].on) band(k, xo.bufs[k], stride, n0, n1, nch);
                xo.sum(data, stride, n0, n1, nch);
            }
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, dry);
        }
    }

    /* — saturator: first-order antiderivative anti-aliasing,
     *   y[n] = (F(u[n]) − F(u[n−1])) / (u[n] − u[n−1]) — */

    private static double lnCosh(double x) {
        double a = x < 0 ? -x : x;
        return a + js_log1p(js_exp(-2 * a)) - LN2;
    }

    private static double satF(int curve, double x) {
        switch (curve) {
        case SAT_SOFT:
            if (x >= 1) return 1;
            if (x <= -1) return -1;
            return 1.5 * (x - (x * x * x) / 3);
        case SAT_HARD: return x > 1 ? 1 : x < -1 ? -1 : x;
        case SAT_TUBE: return x >= 0 ? js_tanh(x) : 0.5 * js_tanh(2 * x);
        case SAT_FOLD: return js_sin(x);
        default: return js_tanh(x);
        }
    }

    private static double satAd(int curve, double x) {
        switch (curve) {
        case SAT_SOFT: {
            double a = x < 0 ? -x : x;
            if (a >= 1) return a - 0.375;
            double x2 = x * x;
            return 1.5 * (x2 * 0.5 - (x2 * x2) / 12);
        }
        case SAT_HARD: {
            double a = x < 0 ? -x : x;
            return a > 1 ? a - 0.5 : x * x * 0.5;
        }
        case SAT_TUBE: return x >= 0 ? lnCosh(x) : 0.25 * lnCosh(2 * x);
        case SAT_FOLD: return 1 - js_cos(x);
        default: return lnCosh(x);
        }
    }

    private static final double SAT_EPS = 1e-5;

    private static final class SatStage extends Stage {
        Equipment p;
        boolean hasShape;
        int shapeCurve;
        double shapeBias;
        final double[] u1 = new double[TE_MAX_BUS_CH];
        final double[] dcM = new double[TE_MAX_BUS_CH];      /* the DC blocker's running mean */
        double gIn, gOut, fb, dcA;

        void configure(Equipment p, double rate) {
            this.p = p;
            gIn = Mastering.dbToGain(p.sat.inDb) * Mastering.dbToGain(p.sat.driveDb);
            gOut = Mastering.dbToGain(p.sat.outDb);
            fb = satF(p.sat.curve, p.sat.bias);
            /* The mix tool's DC blocker follows every curve (Microtone 4.7.0). */
            dcA = (IMX_DC_K * IMX_DC_HZ) / rate;
            if (!hasShape || shapeCurve != p.sat.curve || shapeBias != p.sat.bias) {
                hasShape = true;
                shapeCurve = p.sat.curve;
                shapeBias = p.sat.bias;
                for (int c = 0; c < TE_MAX_BUS_CH; c++) u1[c] = p.sat.bias;
            }
        }

        void reset() {
            for (int c = 0; c < TE_MAX_BUS_CH; c++) {
                u1[c] = p != null ? p.sat.bias : 0;
                dcM[c] = 0;
            }
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            int curve = p.sat.curve;
            double bias = p.sat.bias, gIn = this.gIn, gOut = this.gOut, fb = this.fb;
            double a = dcA, b = 1 - a;
            if (ctx.dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
            for (int c = 0; c < nch; c++) {
                int o = c * stride;
                double u1 = this.u1[c];
                double F1 = satAd(curve, u1);
                double m = dcM[c];
                for (int n = n0; n < n1; n++) {
                    double u = data[o + n] * gIn + bias;
                    double d = u - u1;
                    double y, F0;
                    if (d > SAT_EPS || d < -SAT_EPS) {
                        F0 = satAd(curve, u);
                        y = (F0 - F1) / d;
                    } else {
                        F0 = satAd(curve, u);
                        y = satF(curve, (u + u1) * 0.5);
                    }
                    u1 = u; F1 = F0;
                    y -= fb;
                    /* Opus's dc_reject, as the mix tool runs it. */
                    double yy = y - m;
                    m = a * y + b * m;
                    data[o + n] = yy * gOut;
                }
                this.u1[c] = u1;
                dcM[c] = m;
            }
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, ctx.dry);
        }
    }

    /* — unison: 2…8 delay-line pitch shifters, two taps half a window apart
     *   sweeping it at (1 − ratio) samples per sample, crossfaded by sin² — */

    private static final double UNIS_WINDOW_SEC = 0.025;

    private static final class UnisStage extends Stage {
        Equipment p;
        int nch, len, w, win, ctl, rate;
        double[] buf;
        final long[] phase = new long[IMX_UNIS_MAX_VOICES], inc = new long[IMX_UNIS_MAX_VOICES];
        final double[] pan = new double[IMX_UNIS_MAX_VOICES], pos = new double[IMX_UNIS_MAX_VOICES];
        final Lfo lfoD = new Lfo(), lfoW = new Lfo();
        final double[] xfade = new double[4097];         /* sin²(π·i/4096) */

        UnisStage(int nch, int rate) {
            this.nch = nch;
            this.rate = rate;
            for (int i = 0; i <= 4096; i++) { double sn = js_sin((PI * i) / 4096); xfade[i] = sn * sn; }
        }

        private double xfadeAt(long phase) {
            double x = ((double) phase / TWO32) * 4096;
            int i = (int) floor(x);
            double f = x - i;
            return xfade[i] + (xfade[i + 1] - xfade[i]) * f;
        }

        /** Where voice k of n sits in [−1, 1] (unisonPositions): uniform
         *  spacing blended toward a raised-cosine bell's quantiles. */
        private static void positions(int n, double spacing, double[] out) {
            if (n < 4) spacing = 0;
            for (int k = 0; k < n; k++) {
                double q = n == 1 ? 0.5 : (double) k / (n - 1);
                double u = 2 * q - 1, lo = -1, hi = 1;
                for (int it = 0; it < 64; it++) {
                    double mid = (lo + hi) * 0.5;
                    double F = (mid + 1) * 0.5 + js_sin(PI * mid) / (2 * PI);
                    if (F < q) lo = mid; else hi = mid;
                }
                double b = q == 0 ? -1 : q == 1 ? 1 : (lo + hi) * 0.5;
                out[k] = (1 - spacing) * u + spacing * b;
            }
        }

        void configure(Equipment p, double rate) {
            int win = (int) js_round(UNIS_WINDOW_SEC * rate);
            this.p = p;
            this.rate = (int) rate;
            if (win != this.win) {
                this.win = win;
                len = win + 8;
                buf = new double[nch * len];
                w = 0;
            }
            positions(p.unis.voices, p.unis.spacing, pos);
            lfoD.configure(p.unis.detuneLfo);
            lfoW.configure(p.unis.widthLfo);
            ctl = 0;
        }

        void reset() {
            if (buf != null) java.util.Arrays.fill(buf, 0);
            w = 0;
            /* Staggered start: voices that began in step would comb against each other. */
            for (int k = 0; k < IMX_UNIS_MAX_VOICES; k++) phase[k] = (long) floor((k * TWO32) / IMX_UNIS_MAX_VOICES);
            lfoD.reset();
            lfoW.reset();
            ctl = 0;
        }

        void retrigger() {
            if (p.unis.detuneLfo.retrigger) lfoD.phase = 0;
            if (p.unis.widthLfo.retrigger) lfoW.phase = 0;
        }

        int memory() { return len; }

        void skip(int frames, Ctx ctx) {
            lfoD.tempo(ctx.bpm, rate); lfoW.tempo(ctx.bpm, rate);
            lfoD.skip(frames); lfoW.skip(frames);
        }

        private void control() {
            int nv = p.unis.voices;
            double dl = lfoD.value() * p.unis.detuneLfo.depth;
            double wl = lfoW.value() * p.unis.widthLfo.depth;
            double cents = p.unis.detune * (1 + dl);
            double width = p.unis.width * (1 + wl);
            cents = cents < 0 ? 0 : cents > 100 ? 100 : cents;
            width = width < 0 ? 0 : width > 1 ? 1 : width;
            for (int k = 0; k < nv; k++) {
                double r = js_pow(2, (pos[k] * cents) / 1200);
                /* (1 − r) samples per sample across a window of `win`, as a U32 phase. */
                inc[k] = (long) js_round(((1 - r) * TWO32) / win);
                pan[k] = pos[k] * width;
            }
        }

        private static long wrapPhase(long nx) {
            if (nx >= TWO32I) nx -= TWO32I;
            else if (nx < 0) nx += TWO32I;
            return nx;
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch, nv = p.unis.voices;
            int len = this.len, win = this.win;
            double[] buf = this.buf;
            double norm = 1 / sqrt((double) nv);
            boolean stereo = ctx.stereo && nch == 2;
            if (ctx.dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
            lfoD.tempo(ctx.bpm, rate);
            lfoW.tempo(ctx.bpm, rate);
            for (int n = n0; n < n1; n++) {
                if (ctl == 0) control();
                ctl = ctl + 1 == IMX_CONTROL_PERIOD ? 0 : ctl + 1;
                lfoD.step();
                lfoW.step();
                int w = this.w;
                for (int c = 0; c < nch; c++) buf[c * len + w] = data[c * stride + n];
                if (stereo) {
                    double L = 0, R = 0;
                    for (int k = 0; k < nv; k++) {
                        long ph = phase[k];
                        long ph2 = ph >= 2147483648L ? ph - 2147483648L : ph + 2147483648L;
                        double g1 = xfadeAt(ph);
                        double d1 = 2 + ((double) ph / TWO32) * win;
                        double d2 = 2 + ((double) ph2 / TWO32) * win;
                        double yl = hermiteRead(buf, 0, len, w, d1) * g1 + hermiteRead(buf, 0, len, w, d2) * (1 - g1);
                        double yr = hermiteRead(buf, len, len, w, d1) * g1 + hermiteRead(buf, len, len, w, d2) * (1 - g1);
                        /* A pair moved as a pair, its image intact at the centre. */
                        double pp = pan[k];
                        if (pp >= 0) { L += yl * (1 - pp); R += yr + yl * pp; }
                        else { L += yl + yr * -pp; R += yr * (1 + pp); }
                        phase[k] = wrapPhase(ph + inc[k]);
                    }
                    data[n] = L * norm;
                    data[stride + n] = R * norm;
                } else {
                    for (int c = 0; c < nch; c++) {
                        double sum = 0;
                        for (int k = 0; k < nv; k++) {
                            long ph = phase[k];
                            long ph2 = ph >= 2147483648L ? ph - 2147483648L : ph + 2147483648L;
                            double g1 = xfadeAt(ph);
                            sum += hermiteRead(buf, c * len, len, w, 2 + ((double) ph / TWO32) * win) * g1 +
                                   hermiteRead(buf, c * len, len, w, 2 + ((double) ph2 / TWO32) * win) * (1 - g1);
                        }
                        data[c * stride + n] = sum * norm;
                    }
                    for (int k = 0; k < nv; k++) phase[k] = wrapPhase(phase[k] + inc[k]);
                }
                this.w = w + 1 == len ? 0 : w + 1;
            }
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, ctx.dry);
        }
    }

    /* — reverb: an eight-line feedback delay network, Householder matrix, a
     *   damping one-pole in every line, four Schroeder all-passes of input
     *   diffusion and a pre-delay — */

    private static final int[] VERB_LINES = { 2473, 2767, 3217, 3557, 3907, 4127, 4639, 4999 };
    private static final int[] VERB_DIFFUSERS = { 245, 371, 479, 605 };
    private static final double VERB_DIFFUSE_G = 0.5;

    private static final class VerbStage extends Stage {
        int nch, rate, ins;
        boolean allocated;
        final double[][] lines = new double[8][];
        final int[] lineLen = new int[8], lens = new int[8], pos = new int[8];
        final double[] g = new double[8], z = new double[8];
        double damp;
        final double[][][] diff = new double[2][4][];
        final int[][] diffLen = new int[2][4], dpos = new int[2][4];
        double[] pre;
        int preLen, prePos, preD;
        final double[] o = new double[8], f = new double[8];

        VerbStage(int nch) { this.nch = nch; }

        void configure(Equipment p, double rate) {
            ins = nch == 2 ? 2 : 1;
            if ((int) rate != this.rate || !allocated) {
                this.rate = (int) rate;
                allocated = true;
                for (int i = 0; i < 8; i++) {
                    lineLen[i] = (int) ceil((VERB_LINES[i] * rate) / 48000) + 2;
                    lines[i] = new double[lineLen[i]];
                }
                for (int c = 0; c < ins; c++) {
                    for (int i = 0; i < 4; i++) {
                        int l = (int) js_round((VERB_DIFFUSERS[i] * rate) / 48000);
                        diffLen[c][i] = l > 1 ? l : 1;
                        diff[c][i] = new double[diffLen[c][i]];
                        dpos[c][i] = 0;
                    }
                }
                preLen = (int) ceil(IMX_PREDELAY_MAX_SEC * rate) + 2;
                pre = new double[ins * preLen];
            }
            double scale = (0.15 + 0.85 * p.verb.size) * (rate / 48000);
            for (int i = 0; i < 8; i++) {
                int len = (int) js_round(VERB_LINES[i] * scale);
                if (len > lineLen[i] - 1) len = lineLen[i] - 1;
                if (len < 16) len = 16;
                lens[i] = len;
                g[i] = js_pow(10, (-3 * len) / (p.verb.decay * rate));
                if (pos[i] >= lineLen[i]) pos[i] = 0;
            }
            damp = 1 - js_exp((-2 * PI * p.verb.damping) / rate);
            /* Pre-delay in whole samples, from 16.16 seconds: floor(P·fs / 2^16). */
            preD = (int) floor(((double) p.verb.preDelay * rate) / FX16);
            if (preD > preLen - 1) preD = preLen - 1;
        }

        int memory() {
            int m = preD;
            for (int i = 0; i < 8; i++) m += lens[i];
            for (int i = 0; i < 4; i++) m += (int) js_round(((double) VERB_DIFFUSERS[i] * rate) / 48000);
            return m;
        }

        void reset() {
            if (!allocated) return;
            for (int i = 0; i < 8; i++) { java.util.Arrays.fill(lines[i], 0); pos[i] = 0; z[i] = 0; }
            for (int c = 0; c < ins; c++) {
                for (int i = 0; i < 4; i++) {
                    java.util.Arrays.fill(diff[c][i], 0);
                    dpos[c][i] = 0;
                }
            }
            java.util.Arrays.fill(pre, 0);
            prePos = 0;
        }

        private double diffuse(int c, double x) {
            for (int i = 0; i < 4; i++) {
                double[] d = diff[c][i];
                int k = dpos[c][i];
                double v = d[k];
                double y = v - VERB_DIFFUSE_G * x;
                d[k] = x + VERB_DIFFUSE_G * y;
                dpos[c][i] = k + 1 == diffLen[c][i] ? 0 : k + 1;
                x = y;
            }
            return x;
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            boolean stereo = ins == 2;
            int preLen = this.preLen, preD = this.preD;
            double damp = this.damp;
            if (ctx.dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
            for (int n = n0; n < n1; n++) {
                double inL, inR, dL, dR = 0, xL, xR, sum = 0;
                if (stereo) { inL = data[n]; inR = data[stride + n]; }
                else {
                    double sm = 0;
                    for (int c = 0; c < nch; c++) sm += data[c * stride + n];
                    inL = sm / nch; inR = 0;
                }
                int pp = prePos;
                int rp = pp - preD < 0 ? pp - preD + preLen : pp - preD;
                pre[pp] = inL;
                dL = pre[rp];
                if (stereo) { pre[preLen + pp] = inR; dR = pre[preLen + rp]; }
                prePos = pp + 1 == preLen ? 0 : pp + 1;
                xL = diffuse(0, dL);
                xR = stereo ? diffuse(1, dR) : xL;
                for (int i = 0; i < 8; i++) {
                    double[] line = lines[i];
                    int r = pos[i] - lens[i];
                    if (r < 0) r += lineLen[i];
                    double v = line[r];
                    o[i] = v;
                    z[i] += (v - z[i]) * damp;
                    f[i] = z[i] * g[i];
                    sum += f[i];
                }
                /* Householder (I − (2/8)·11ᵀ), written back with the input. */
                double h = sum * 0.25;
                for (int i = 0; i < 8; i++) {
                    double inj = stereo ? ((i & 1) == 0 ? xL : xR) : xL;
                    lines[i][pos[i]] = f[i] - h + inj * 0.5;
                    pos[i] = pos[i] + 1 == lineLen[i] ? 0 : pos[i] + 1;
                }
                if (stereo) {
                    data[n] = 0.5 * (o[0] - o[2] + o[4] - o[6]);
                    data[stride + n] = 0.5 * (o[1] - o[3] + o[5] - o[7]);
                } else {
                    for (int c = 0; c < nch; c++) data[c * stride + n] = ((c & 8) != 0 ? -0.5 : 0.5) * o[c & 7];
                }
            }
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, ctx.dry);
        }
    }

    /* — tape delay: 16.16 seconds or quarter ticks; the read head glides to
     *   a new time by 1/2048 of the distance a sample; the feedback through a
     *   low cut, a high cut and a soft saturator; wow and flutter two fixed
     *   sines — */

    private static final int WOW_RATE = FX16 / 2;                   /* 0.5 Hz */
    private static final int FLUTTER_RATE = 6 * FX16 + FX16 / 2;    /* 6.5 Hz */
    private static final double WOW_SEC = 0.002;
    private static final double FLUTTER_SEC = 0.00015;
    private static final int GLIDE_SHIFT = 2048;

    private static final class DelayStage extends Stage {
        Equipment p;
        int nch, len, w, rate;
        double[] buf;
        long d, t;              /* current and target delay, 1/65536 samples */
        boolean started;
        long wow, flutter, wowInc, flutInc;
        final Biquad lc = new Biquad(), hc = new Biquad();
        final double[] fbs = new double[TE_MAX_BUS_CH], xs = new double[TE_MAX_BUS_CH];
        double g = 1;

        DelayStage(int nch, int rate) { this.nch = nch; this.rate = rate; }

        void configure(Equipment p, double rate) {
            this.p = p;
            if ((int) rate != this.rate || buf == null) {
                this.rate = (int) rate;
                len = (int) ceil(IMX_DELAY_MAX_SEC * rate) + 16;
                buf = new double[nch * len];
                w = 0;
                started = false;
            }
            lc.highPass(p.dlay.lowCut, SQRT1_2, rate);
            hc.lowPass(p.dlay.highCut, SQRT1_2, rate);
            g = Mastering.dbToGain(p.dlay.driveDb);
            wowInc = (long) floor(((double) WOW_RATE * FX16) / rate);
            flutInc = (long) floor(((double) FLUTTER_RATE * FX16) / rate);
        }

        void reset() {
            if (buf != null) java.util.Arrays.fill(buf, 0);
            w = 0;
            lc.reset();
            hc.reset();
            java.util.Arrays.fill(fbs, 0);
            wow = 0;
            flutter = 0;
            started = false;
        }

        int memory() { return len; }

        /** The target delay in 1/65536 samples for this tempo. */
        private long target(int bpm) {
            double fs = rate, t;
            long lo = 2L * FX16, hi = (long) (len - 8) * FX16;
            if (p.dlay.sync) {
                /* P quarter ticks · (fs · 2.5 / bpm) / 4 samples, ×2^16: P·fs·5 / (8·bpm). */
                t = floor(((double) p.dlay.time * fs * 5) / (8.0 * bpm));
            } else {
                t = (double) p.dlay.time * fs;
            }
            long ti = (long) t;
            return ti < lo ? lo : ti > hi ? hi : ti;
        }

        void skip(int frames, Ctx ctx) {
            wow = (wow + wowInc * frames) % TWO32I;
            flutter = (flutter + flutInc * frames) % TWO32I;
        }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch, len = this.len;
            double[] buf = this.buf;
            double fs = rate, fb = p.dlay.feedback, g = this.g;
            double wowAmp = p.dlay.wow * WOW_SEC * fs;
            double flutAmp = p.dlay.wow * FLUTTER_SEC * fs;
            boolean pingPong = p.dlay.pingPong && ctx.stereo && nch == 2;
            /* The line the mid goes into (0 left, 1 right) and the other one. */
            int pa = pingPong && p.dlay.pingRight ? 1 : 0, pb = 1 - pa;
            if (ctx.dry > 0) copySpan(data, ctx.dryBuf, nch, stride, n0, n1);
            t = target(ctx.bpm);
            if (!started) { d = t; started = true; }
            for (int n = n0; n < n1; n++) {
                long diff = t - d;
                /* The glide: integer arithmetic (JS: exact binary64 integers). */
                if (diff >= GLIDE_SHIFT || diff <= -GLIDE_SHIFT) d += diff / GLIDE_SHIFT;
                else d = t;
                double delay = (double) d / FX16;
                if (wowAmp != 0) {
                    delay += wowAmp * js_sin((2 * PI * (double) wow) / TWO32) +
                             flutAmp * js_sin((2 * PI * (double) flutter) / TWO32);
                }
                if (delay < 2) delay = 2;
                wow += wowInc; if (wow >= TWO32I) wow -= TWO32I;
                flutter += flutInc; if (flutter >= TWO32I) flutter -= TWO32I;
                int w = this.w;
                /* Read every channel first (the write below may cross them).
                 * The newest sample in the line is the PREVIOUS one. */
                for (int c = 0; c < nch; c++) {
                    xs[c] = data[c * stride + n];
                    double y = hermiteRead(buf, c * len, len, w == 0 ? len - 1 : w - 1, delay - 1);
                    double f = hc.run(c, lc.run(c, y * fb));
                    f = js_tanh(g * f) / g;
                    fbs[c] = f;
                    data[c * stride + n] = y;
                }
                if (pingPong) {
                    double mid = (xs[0] + xs[1]) * 0.5;
                    buf[pa * len + w] = mid + fbs[pb];
                    buf[pb * len + w] = fbs[pa];
                } else {
                    for (int c = 0; c < nch; c++) buf[c * len + w] = xs[c] + fbs[c];
                }
                this.w = w + 1 == len ? 0 : w + 1;
            }
            mixDry(data, ctx.dryBuf, nch, stride, n0, n1, ctx.dry);
        }
    }

    /* — limiter: the mastering chain's look-ahead limiter, after a gain — */

    private static final class LimiStage extends Stage {
        final Mastering.Limiter stage;
        double g;

        LimiStage(int nch) { stage = new Mastering.Limiter(nch); }

        void configure(Equipment p, double rate) {
            g = Mastering.dbToGain(p.lim.gainDb);
            stage.configure(p.lim.ceilingDb, p.lim.truePeak, p.lim.releaseMs, rate);
        }

        void reset() { stage.reset(); }

        int memory() { return stage.d == 0 ? 0 : 2 * stage.d; }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            if (stage.d == 0) return;
            stage.grDb = 0;
            for (int n = n0; n < n1; n++) {
                if (g != 1) for (int c = 0; c < nch; c++) data[c * stride + n] *= g;
                stage.stepPlanar(data, n, stride);
            }
        }
    }

    /* — mix tool: DC removal (Opus's dc_reject), gain, mid/side width, swap
     *   and polarity; an ambisonic bus's width is surround width — */

    private static final class MxStage extends Stage {
        Equipment p;
        double g, a;
        final double[] m = new double[TE_MAX_BUS_CH];

        void configure(Equipment p, double rate) {
            this.p = p;
            g = Mastering.dbToGain(p.mx.gainDb);
            a = (IMX_DC_K * IMX_DC_HZ) / rate;
        }

        void reset() { java.util.Arrays.fill(m, 0); }

        void process(double[] data, int stride, int n0, int n1, Ctx ctx) {
            int nch = ctx.nch;
            double g = this.g;
            if (p.mx.removeDc) {
                double a = this.a, b = 1 - a;
                for (int c = 0; c < nch; c++) {
                    int o = c * stride;
                    double m = this.m[c];
                    for (int n = n0; n < n1; n++) {
                        double x = data[o + n];
                        data[o + n] = x - m;
                        m = a * x + b * m;
                    }
                    this.m[c] = m;
                }
            }
            if (ctx.stereo && nch == 2) {
                double gl = p.mx.invL ? -g : g;
                double gr = p.mx.invR ? -g : g;
                double w = p.mx.width;
                for (int n = n0; n < n1; n++) {
                    double l = data[n], r = data[stride + n];
                    if (p.mx.swap) { double t = l; l = r; r = t; }
                    if (w != 1) {
                        double m = (l + r) * 0.5;
                        double sd = (l - r) * 0.5 * w;
                        l = m + sd; r = m - sd;
                    }
                    data[n] = l * gl;
                    data[stride + n] = r * gr;
                }
                return;
            }
            if (ctx.ambisonic && p.mx.width != 1) {
                double w = p.mx.width;
                for (int c = 1; c < nch; c++) {
                    int o = c * stride;
                    for (int n = n0; n < n1; n++) data[o + n] *= w;
                }
            }
            double gg = p.mx.invL && p.mx.invR ? -g : g;
            if (gg == 1) return;
            for (int c = 0; c < nch; c++) {
                int o = c * stride;
                for (int n = n0; n < n1; n++) data[o + n] *= gg;
            }
        }
    }

    /* ── the chain (InstrumentChain) ── */

    static final class Chain {
        final int rate, nch;
        final boolean stereo, ambisonic;
        final Stage[] stage = new Stage[IMX_KINDS];     /* made the first time the piece is in */
        final int[] active = new int[IMX_KINDS];
        final double[] activeDry = new double[IMX_KINDS];
        int nActive;
        final double[] dryBuf;
        boolean idle = true;
        int silentRun, idleAfter;
        final Ctx ctx = new Ctx();

        Chain(Block block, int rate, int nch, boolean stereo, boolean ambisonic) {
            this.rate = rate;
            this.nch = nch;
            this.stereo = stereo;
            this.ambisonic = ambisonic && !stereo && nch > 1;
            dryBuf = new double[nch * TRACKER_CHUNK];
            ctx.nch = nch;
            ctx.stereo = stereo;
            ctx.ambisonic = this.ambisonic;
            ctx.bpm = 125;
            ctx.dryBuf = dryBuf;
            setBlock(block);
        }

        private Stage makeStage(int k) {
            switch (k) {
            case IMX_ITRM: return new TrimStage();
            case IMX_EXPD: return new ExpdStage();
            case IMX_TSH1: return new TshStage(nch);
            case IMX_EQU1: case IMX_EQU2: return new EqStage();
            case IMX_DEQ1: case IMX_DEQ2: return new DeqStage(rate);
            case IMX_COMP: return new CompStage(nch);
            case IMX_SATU: return new SatStage();
            case IMX_UNIS: return new UnisStage(nch, rate);
            case IMX_VERB: return new VerbStage(nch);
            case IMX_DLAY: return new DelayStage(nch, rate);
            case IMX_LIMI: return new LimiStage(nch);
            default: return new MxStage();
            }
        }

        /** Install a block's settings (setBlock). Delay lines and envelopes
         *  are kept; a piece coming (back) into the chain starts clean. */
        void setBlock(Block b) {
            int[] order = PIPELINES[b.order == 1 ? 1 : 0];
            boolean[] was = new boolean[IMX_KINDS];
            int memory = 0;
            for (int i = 0; i < nActive; i++) was[active[i]] = true;
            nActive = 0;
            for (int i = 0; i < IMX_KINDS; i++) {
                int k = order[i];
                Equipment e = b.eq[k];
                if (!e.present || e.bypass) continue;     /* bypassed equipment is not processed at all */
                if (stage[k] == null) stage[k] = makeStage(k);
                stage[k].configure(e, rate);
                if (!was[k]) stage[k].reset();
                active[nActive] = k;
                activeDry[nActive] = wetApplicable(k) ? e.dry / 255.0 : 0;
                nActive++;
                memory += stage[k].memory();
            }
            idleAfter = memory + (int) js_round(IMX_IDLE_MARGIN_SEC * rate);
        }

        /** Clear every delay line, envelope and phase (a transport reset). */
        void reset() {
            for (int k = 0; k < IMX_KINDS; k++) if (stage[k] != null) stage[k].reset();
            idle = true;
            silentRun = 0;
        }

        /** A note of an instrument on this bus has started: phase-reset its
         *  retriggering LFOs. */
        void noteOn() {
            UnisStage u = (UnisStage) stage[IMX_UNIS];
            if (u != null && u.p != null) u.retrigger();
        }

        private static int firstSignal(double[] data, int nch, int stride, int n0, int n1) {
            for (int n = n0; n < n1; n++) for (int c = 0; c < nch; c++) if (data[c * stride + n] != 0) return n;
            return -1;
        }

        private void stageSkip(int k, int frames) {
            if (k == IMX_UNIS || k == IMX_DLAY) stage[k].skip(frames, ctx);
        }

        /** Run [n0, n1) of the bus in place. `fed`: did any voice write into
         *  this span — a chain that is idle and was not fed only keeps its
         *  LFOs in time. */
        void process(double[] data, int stride, int n0, int n1, int bpm, boolean fed) {
            int nch = this.nch, frames = n1 - n0, lastIn = -1;
            ctx.bpm = bpm;
            if (idle) {
                /* Wake on the first non-zero SAMPLE, not at the top of the span. */
                int m = fed ? firstSignal(data, nch, stride, n0, n1) : -1;
                if (m < 0) {
                    for (int i = 0; i < nActive; i++) stageSkip(active[i], frames);
                    return;
                }
                if (m > n0) for (int i = 0; i < nActive; i++) stageSkip(active[i], m - n0);
                n0 = m;
                idle = false;
                silentRun = 0;
            }
            /* The last non-zero input sample of the span: the idle rule needs it. */
            for (int n = n1 - 1; n >= n0 && lastIn < 0; n--) {
                for (int ch = 0; ch < nch; ch++) if (data[ch * stride + n] != 0) { lastIn = n; break; }
            }
            for (int i = 0; i < nActive; i++) {
                ctx.dry = activeDry[i];
                stage[active[i]].process(data, stride, n0, n1, ctx);
            }
            /* Idle rule: the trailing run of samples that had no input AND
             * left the chain below the idle level, counted sample by sample. */
            for (int n = n0; n < n1; n++) {
                boolean quiet = n > lastIn;
                if (quiet) {
                    for (int ch = 0; ch < nch; ch++) {
                        double y = data[ch * stride + n];
                        if (y >= IMX_IDLE_LEVEL || y <= -IMX_IDLE_LEVEL) { quiet = false; break; }
                    }
                }
                if (!quiet) { silentRun = 0; continue; }
                if (++silentRun >= idleAfter) {
                    /* Idle from the NEXT sample: what a cleared chain fed silence makes. */
                    int remain = n1 - (n + 1);
                    for (int m = n + 1; m < n1; m++) for (int ch = 0; ch < nch; ch++) data[ch * stride + m] = 0;
                    for (int k = 0; k < IMX_KINDS; k++) if (stage[k] != null) stage[k].reset();
                    if (remain > 0) for (int i = 0; i < nActive; i++) stageSkip(active[i], remain);
                    idle = true;
                    silentRun = 0;
                    break;
                }
            }
        }
    }

    /* ── buses and routing (InstrumentBus, syncImixRoutes) ── */

    /** One bus on the playhead (InstrumentBus): a chunk of channel-major
     *  binary64 in the song's own channel layout — `sb` is filled the way the
     *  master object bus is, through the same renderer gains — and the chain
     *  that runs over it. In bus mode `slots` lists every instrument sharing
     *  it, ascending. */
    static final class Bus {
        int slot;
        int[] slots = new int[4];
        int nSlots;
        int blockId;
        Object renderer;        /* identity of the render target it was built for */
        int nch;
        boolean stereo;
        final SpatialBus sb = new SpatialBus();    /* sb.data is the bus; sb.numChannels = nch */
        Chain chain;
        boolean fed;

        void addSlot(int s) {
            if (nSlots == slots.length) slots = java.util.Arrays.copyOf(slots, slots.length * 2);
            slots[nSlots++] = s;
        }
    }

    /** Which render target the master bus is built on: none (the stereo
     *  model), the binaural renderer (kept across re-applications, as the JS
     *  keeps its object), or a fold renderer, which every application makes
     *  afresh. */
    static Object currentRenderer(Engine e) {
        if (e.spatial == null) return null;
        if (e.spatial.kind == 1) return e.spatial.bin;
        return Integer.valueOf(e.rendererGen);
    }

    private static Bus busNew(Engine e, int slot, Block block) {
        Bus bus = new Bus();
        boolean scene;
        bus.slot = slot;
        bus.blockId = block.id;
        bus.renderer = currentRenderer(e);
        if (e.spatial == null) {
            bus.nch = 2;
            bus.stereo = true;
            scene = false;
        } else {
            bus.sb.kind = e.spatial.kind;
            bus.sb.bin = e.spatial.bin;
            bus.nch = e.spatial.numChannels;
            /* The device renders a surround song through a stereo fold: its
             * two channels ARE a left/right pair. */
            bus.stereo = bus.nch == 2 && e.spatial.kind == 0;
            scene = e.spatial.kind == 1;
        }
        bus.sb.numChannels = bus.nch;
        bus.chain = new Chain(block, e.rate, bus.nch, bus.stereo, scene);
        return bus;
    }

    /** Rebuild the route table from the blocks and the instruments. A slot
     *  gets a bus when its record names a block that exists and is engaged;
     *  in bus mode the first slot naming the block makes the bus and every
     *  later one joins it. A bus that survives — same first slot, same render
     *  target — keeps its chain and is only re-set. */
    static void syncRoutes(Engine e) {
        Bus[] old = e.imixList;
        int oldCount = e.imixCount;
        Bus[] oldBySlot = new Bus[1024];
        Bus[] shared = new Bus[256];
        Object renderer = currentRenderer(e);
        int nch = e.spatial == null ? 2 : e.spatial.numChannels;
        e.imixSyncedGen = e.imixGen;
        e.imixRenderer = renderer;
        for (int i = 0; i < oldCount; i++) oldBySlot[old[i].slot] = old[i];
        Bus[] list = new Bus[8];
        int count = 0;
        java.util.Arrays.fill(e.imixRoutes, null);
        for (int slot = 1; slot < 1024; slot++) {
            int id = e.instruments[slot].imixId();
            if (id == 0) continue;
            Block block = e.imixBlocks[id];
            if (block == null || !blockEngaged(block)) continue;
            if (block.bus && shared[id] != null) {
                shared[id].addSlot(slot);
                continue;
            }
            Bus bus = oldBySlot[slot];
            if (bus != null && java.util.Objects.equals(bus.renderer, renderer) && bus.nch == nch) {
                oldBySlot[slot] = null;
                bus.blockId = id;
                bus.chain.setBlock(block);
            } else {
                bus = busNew(e, slot, block);
            }
            bus.nSlots = 0;
            bus.addSlot(slot);
            if (block.bus) shared[id] = bus;
            if (count == list.length) list = java.util.Arrays.copyOf(list, list.length * 2);
            /* Made in ascending slot order: the order the buses join the mix. */
            list[count++] = bus;
        }
        e.imixList = list;
        e.imixCount = count;
        for (int i = 0; i < count; i++) {
            Bus bus = list[i];
            for (int k = 0; k < bus.nSlots; k++) e.imixRoutes[bus.slots[k]] = bus;
        }
    }

    /** The project's blocks (setImix): replaces the whole set. */
    static void setImix(Engine e, Block[] blocks) {
        java.util.Arrays.fill(e.imixBlocks, null);
        if (blocks != null) for (Block b : blocks) e.imixBlocks[b.id & 0xff] = b;
        e.imixGen++;
    }

    /** Every chain's running state goes with a transport reset; so do the
     *  note flags. */
    static void resetChains(Engine e) {
        for (int i = 0; i < e.imixCount; i++) e.imixList[i].chain.reset();
        java.util.Arrays.fill(e.imixTrig, false);
    }
}
