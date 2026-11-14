/*
 * Metainstrument type 1 — the FORMANT INSTRUMENT (item 212) — port of
 * formant.js (taudplay-c formant.c).
 *
 * An ordinary instrument record whose sample is a glottal source, played
 * through Klatt's cascade/parallel formant synthesiser (Klatt 1980), and
 * whose second pitch/filter slot holds what the tract says: a vowel on
 * Catford's cardinal-vowel chart, an onset cluster and a coda (one CCVC
 * syllable, with an optional glide), and how the formants follow pitch.
 * formant.js carries the phonetics; this file keeps its arithmetic,
 * expression for expression.
 *
 * Every transcendental is the JS's own (JsMath): exp, log, log2, log10, cos,
 * sin, pow; sqrt is correctly rounded on both sides.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

import net.torvald.taudplay.FormantState.Segment;
import net.torvald.taudplay.FormantState.Track;

final class Formant {
    private Formant() {}

    private static final double PI = 3.141592653589793;

    private static final int FB_SOURCE = 0;
    private static final int FB_FRONT = 3;
    private static final int FB_OPEN = 4;
    private static final int FB_ROUND = 5;
    private static final int FB_TRACK = 6;
    private static final int FB_REF = 7;
    private static final int FB_GLIDE = 9;
    private static final int FB_TIMING = 10;
    private static final int FB_RANGE_LO = 11;
    private static final int FB_RANGE_HI = 14;
    private static final int FB_SLOTS = 17;
    private static final int FB_GR = 53;
    private static final int FORMANT_SLOT_BYTES = 12;

    /** The reference note a record with NO vowel sizes its consonants by: C3. */
    private static final int FORMANT_DEFAULT_REF = 0x4000;

    /* The onset's secondary articulation (FB_GR bits 3–4) and the coda's own
     * articulation (bits 5–6), which shares the two in the middle. */
    static final int ART_NONE = 0;
    static final int ART_PALATAL = 1;
    static final int ART_DARK = 2;
    static final int ART_GLOTTAL = 3;
    private static final int CODA_RELEASED = 0;
    private static final int CODA_UNRELEASED = 1;
    private static final int CODA_DARK = 2;
    private static final int CODA_GLOTTAL = 3;

    /* A glottalised consonant: the glottis shut this long past the
     * consonant's release (or at a sonorant's start), the voicing then in
     * this fast; a stop's voicing lags its burst by no more than GLOT_LAG; in
     * a coda the voicing stops in GLOT_STOP. */
    private static final int GLOT_CATCH = 10;
    private static final int GLOT_RISE = 2;
    private static final int GLOT_LAG = 10;
    private static final int GLOT_STOP = 3;
    /** What a hissing nucleus takes to let its hiss go before the coda. */
    private static final int NUCLEUS_RELEASE = 10;

    private static int fu16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static long fu24(byte[] b, int o) { return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16); }

    /* The glide, the articulations and R (the block's +53, a lyric
     * syllable's +4) into `patch`; a glide code 5…7 is RESERVED and sounds
     * as none. */
    private static void decodeGr(FormantPatch patch, int gr) {
        patch.semivowel = (gr & 7) <= 4 ? gr & 7 : 0;
        patch.secondary = (gr >> 3) & 3;
        patch.codaArt = (gr >> 5) & 3;
        patch.rhotic = (gr & 0x80) != 0;
    }

    /** Decode the tract block at raw[at] into `patch`. */
    static void decodeFormantBlock(byte[] raw, int at, FormantPatch patch) {
        patch.srcPtr = fu24(raw, at + FB_SOURCE);
        patch.front = raw[at + FB_FRONT] & 0xff;
        patch.open = raw[at + FB_OPEN] & 0xff;
        patch.round = raw[at + FB_ROUND] & 0xff;
        patch.track = raw[at + FB_TRACK] & 0xff;
        patch.refNote = fu16(raw, at + FB_REF) & 0x7fff;
        patch.nasal = (fu16(raw, at + FB_REF) & 0x8000) != 0;
        patch.glide = raw[at + FB_GLIDE] & 0xff;
        patch.timing = raw[at + FB_TIMING] & 0xff;
        patch.rangeLo = fu24(raw, at + FB_RANGE_LO);
        patch.rangeHi = fu24(raw, at + FB_RANGE_HI);
        decodeGr(patch, raw[at + FB_GR] & 0xff);
        for (int i = 0; i < FORMANT_SLOTS; i++) {
            int o = at + FB_SLOTS + i * FORMANT_SLOT_BYTES;
            FormantPatch.Slot s = patch.slots[i];
            int t = raw[o + 10] & 0xff;
            s.cons = raw[o] & 0xff;
            s.ptr = fu24(raw, o + 1);
            s.len = fu16(raw, o + 4);
            s.offset = fu16(raw, o + 6);
            s.rate = fu16(raw, o + 8);
            s.tilt = t >= 0x80 ? t - 0x100 : t;
            s.gain = raw[o + 11] & 0xff;
        }
    }

    /* ── the vowel ── */

    private static final int[][][] CARDINAL_VOWELS = {
        { { 240, 2400 }, { 235, 2100 }, { 300, 1390 }, { 250, 595 } },
        { { 390, 2300 }, { 370, 1900 }, { 460, 1310 }, { 360, 640 } },
        { { 610, 1900 }, { 585, 1710 }, { 600, 1170 }, { 500, 700 } },
        { { 850, 1610 }, { 820, 1530 }, { 750, 940 }, { 700, 760 } },
    };

    private static void vowelF1F2(FTables T, double front, double open, double round, double[] out) {
        double x = dmin(dmax(front / 255, 0), 1);
        double r = dmin(dmax(round / 255, 0), 1);
        double h = dmin(dmax(open / 255, 0), 1) * 3;
        int i = (int) dmin(2, floor(h));
        double u = h - i;
        double w0 = x * (1 - r), w1 = x * r, w2 = (1 - x) * (1 - r), w3 = (1 - x) * r;
        for (int n = 0; n < 2; n++) {
            double[][] lo = T.logCard[i], hi = T.logCard[i + 1];
            double a0 = lo[0][n] + (hi[0][n] - lo[0][n]) * u;
            double a1 = lo[1][n] + (hi[1][n] - lo[1][n]) * u;
            double a2 = lo[2][n] + (hi[2][n] - lo[2][n]) * u;
            double a3 = lo[3][n] + (hi[3][n] - lo[3][n]) * u;
            out[n] = js_exp(w0 * a0 + w1 * a1 + w2 * a2 + w3 * a3);
        }
    }

    private static double vowelF1At(Engine e, double front, double open, double round) {
        double[] f = e.formantTables.f12;
        vowelF1F2(e.formantTables, front, open, round, f);
        return f[0];
    }

    private static final int RHOTIC_F3 = 1711;
    private static final int RHOTIC_F2 = 1379;
    private static final int RHOTIC_F4 = 3335;

    private static void vowelFormants(FTables T, double front, double open, double round, double[] F, boolean rhotic) {
        double[] f12 = T.f12;
        vowelF1F2(T, front, open, round, f12);
        double f1 = f12[0];
        double f2 = f12[1];
        double f3u = dmax(2500, f2 + 700);
        double f3r = dmax(2200, f2 + 250);
        double f3 = f3u * js_pow(f3r / f3u, dmin(dmax(round, 0), 255) / 255);
        if (rhotic) {
            f3 = dmin(f3, RHOTIC_F3);
            f2 = dmin(f2, (f3 * RHOTIC_F2) / RHOTIC_F3);
        }
        double f4 = dmax(3500, f3 + 400);
        if (rhotic) f4 = dmin(f4, RHOTIC_F4);
        double f5 = dmax(4500, f4 + 500);
        F[0] = f1; F[1] = f2; F[2] = f3; F[3] = f4; F[4] = f5;
    }

    /** A syllable without a vowel (reference note 0) is a syllabic consonant. */
    private static boolean hasVowel(FormantPatch patch) { return patch.refNote != 0; }
    private static double refNote(FormantPatch patch) {
        return patch.refNote != 0 ? patch.refNote + patch.dRefNote : FORMANT_DEFAULT_REF;
    }

    private static double noteHz(int note) {
        return LINEAR_FREQ_C4_HZ * js_pow(2, (note - MIDDLE_C) / 4096.0);
    }

    private static double vowelAtPitch(Engine e, double front, double open, double round, double f0,
                                       double refHz, int track, double[] F, double[] B, boolean rhotic) {
        double s = f0 > 0 && refHz > 0 ? js_pow(f0 / refHz, track / 256.0) : 1.0;
        double o = open;
        boolean tuned = f0 > vowelF1At(e, front, open, round) * s;
        if (tuned) {
            double want = f0 / s;
            if (vowelF1At(e, front, 255, round) <= want) {
                o = 255;
            } else {
                double a = open, b = 255;
                for (int i = 0; i < 24; i++) {
                    double m = (a + b) / 2;
                    if (vowelF1At(e, front, m, round) < want) a = m; else b = m;
                }
                o = (a + b) / 2;
            }
        }
        vowelFormants(e.formantTables, front, o, round, F, rhotic);
        for (int n = 0; n < 5; n++) F[n] *= s;
        if (tuned) F[0] = f0;
        double ceil = 0.45 * e.rate;
        for (int n = 1; n < 5; n++) if (F[n] < F[n - 1] * 1.15) F[n] = F[n - 1] * 1.15;
        for (int n = 0; n < 5; n++) {
            if (F[n] > ceil) F[n] = ceil;
            B[n] = dmax(F[n] / 10, 50);
        }
        return s;
    }

    /* ── the consonants ── */

    private static final int MANNER_STOP = 1;
    private static final int MANNER_AFFRICATE = 2;
    private static final int MANNER_FRICATIVE = 3;
    private static final int MANNER_ASPIRATE = 4;
    private static final int MANNER_NASAL = 5;
    private static final int MANNER_APPROX = 6;
    private static final int MANNER_TAP = 7;
    private static final int MANNER_TRILL = 8;
    private static final int MANNER_GLOTTAL = 9;

    /** `dorsal`: the place follows the vowel (a velar); `bankBack`, when
     *  present, the bank before a back vowel; `breath`: the row's frication
     *  is BREATH — its noise goes through the cascade, as an aspirate's does
     *  ([ʔ͡h]). */
    static final class Consonant {
        final int id, manner;
        final boolean voiced;
        final double[] F, B, bank;
        final double lvl, dur, burst, vot, trans, av, fnz;
        final boolean dorsal, hasBankBack;
        final double[] bankBack = new double[6];
        final boolean breath;

        Consonant(int id, int manner, int voiced, double[] F, double[] B, double[] bank,
                  double lvl, double dur, double burst, double vot, double trans, double av, double fnz,
                  int dorsal, int hasBankBack, double[] bankBack, int breath) {
            this.id = id; this.manner = manner; this.voiced = voiced != 0;
            this.F = F; this.B = B; this.bank = bank;
            this.lvl = lvl; this.dur = dur; this.burst = burst; this.vot = vot; this.trans = trans;
            this.av = av; this.fnz = fnz;
            this.dorsal = dorsal != 0; this.hasBankBack = hasBankBack != 0;
            System.arraycopy(bankBack, 0, this.bankBack, 0, bankBack.length);
            this.breath = breath != 0;
        }
    }

    /* Generated from formant.js's ROWS: id, manner, voiced, F, B, bank (A2…A6,
     * AB), lvl, dur, burst, vot, trans, av, fnz, dorsal, bankBack, breath. */
    private static final Consonant[] ROWS = {
        new Consonant( 1, 1, 0, new double[] { 400, 1100, 2150 }, new double[] { 300, 150, 220 }, new double[] { 0, 0, 0, 0, 0, 63 }, -12, 0, 5, 12, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* p */
        new Consonant( 2, 1, 1, new double[] { 200, 1100, 2150 }, new double[] { 60, 110, 130 }, new double[] { 0, 0, 0, 0, 0, 63 }, -14, 0, 5, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* b */
        new Consonant( 3, 1, 0, new double[] { 400, 1600, 2600 }, new double[] { 300, 120, 250 }, new double[] { 0, 30, 45, 57, 63, 0 }, -10, 0, 10, 18, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* t */
        new Consonant( 4, 1, 1, new double[] { 200, 1600, 2600 }, new double[] { 60, 100, 170 }, new double[] { 0, 47, 60, 62, 60, 0 }, -12, 0, 8, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* d */
        new Consonant( 5, 1, 0, new double[] { 400, 1500, 1900 }, new double[] { 300, 120, 250 }, new double[] { 0, 50, 55, 50, 45, 0 }, -10, 0, 10, 18, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʈ */
        new Consonant( 6, 1, 1, new double[] { 200, 1500, 1900 }, new double[] { 60, 100, 170 }, new double[] { 0, 50, 55, 50, 45, 0 }, -12, 0, 8, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɖ */
        new Consonant( 7, 1, 0, new double[] { 300, 2100, 2900 }, new double[] { 250, 160, 330 }, new double[] { 0, 45, 55, 50, 40, 0 }, -10, 0, 15, 22, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* c */
        new Consonant( 8, 1, 1, new double[] { 200, 2100, 2900 }, new double[] { 60, 150, 280 }, new double[] { 0, 45, 55, 50, 40, 0 }, -12, 0, 12, 0, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɟ */
        new Consonant( 9, 1, 0, new double[] { 300, 1990, 2850 }, new double[] { 250, 160, 330 }, new double[] { 0, 60, 43, 45, 45, 0 }, -10, 0, 20, 25, 50, 0, 0, 1, 1, new double[] { 60, 53, 43, 45, 45, 0 }, 0 ), /* k */
        new Consonant( 10, 1, 1, new double[] { 200, 1990, 2850 }, new double[] { 60, 150, 280 }, new double[] { 0, 60, 43, 45, 45, 0 }, -12, 0, 15, 0, 50, 0, 0, 1, 1, new double[] { 60, 53, 43, 45, 45, 0 }, 0 ), /* ɡ */
        new Consonant( 11, 1, 0, new double[] { 400, 1200, 2400 }, new double[] { 250, 150, 300 }, new double[] { 55, 45, 35, 30, 30, 0 }, -10, 0, 20, 25, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* q */
        new Consonant( 12, 1, 1, new double[] { 250, 1200, 2400 }, new double[] { 60, 120, 250 }, new double[] { 55, 45, 35, 30, 30, 0 }, -12, 0, 15, 0, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɢ */
        new Consonant( 13, 9, 0, new double[] { 0, 0, 0 }, new double[] { 0, 0, 0 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 0, 0, 0, 0, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʔ */
        new Consonant( 14, 1, 0, new double[] { 400, 1100, 2150 }, new double[] { 300, 150, 220 }, new double[] { 0, 0, 0, 0, 0, 63 }, -12, 0, 5, 58, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* pʰ */
        new Consonant( 15, 1, 0, new double[] { 400, 1600, 2600 }, new double[] { 300, 120, 250 }, new double[] { 0, 30, 45, 57, 63, 0 }, -10, 0, 10, 70, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* tʰ */
        new Consonant( 16, 1, 0, new double[] { 300, 1990, 2850 }, new double[] { 250, 160, 330 }, new double[] { 0, 60, 43, 45, 45, 0 }, -10, 0, 20, 80, 50, 0, 0, 1, 1, new double[] { 60, 53, 43, 45, 45, 0 }, 0 ), /* kʰ */
        new Consonant( 17, 1, 0, new double[] { 400, 1500, 1900 }, new double[] { 300, 120, 250 }, new double[] { 0, 50, 55, 50, 45, 0 }, -10, 0, 10, 60, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʈʰ */
        new Consonant( 18, 1, 1, new double[] { 200, 1100, 2150 }, new double[] { 60, 110, 130 }, new double[] { 0, 0, 0, 0, 0, 63 }, -14, 0, 5, 31, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* bʱ */
        new Consonant( 19, 1, 1, new double[] { 200, 1600, 2600 }, new double[] { 60, 100, 170 }, new double[] { 0, 47, 60, 62, 60, 0 }, -12, 0, 8, 35, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* dʱ */
        new Consonant( 20, 1, 1, new double[] { 200, 1500, 1900 }, new double[] { 60, 100, 170 }, new double[] { 0, 50, 55, 50, 45, 0 }, -12, 0, 8, 36, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɖʱ */
        new Consonant( 21, 1, 1, new double[] { 200, 1990, 2850 }, new double[] { 60, 150, 280 }, new double[] { 0, 60, 43, 45, 45, 0 }, -12, 0, 15, 44, 50, 0, 0, 1, 1, new double[] { 60, 53, 43, 45, 45, 0 }, 0 ), /* ɡʱ */
        new Consonant( 32, 5, 1, new double[] { 480, 1270, 2130 }, new double[] { 40, 200, 200 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 35, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* m */
        new Consonant( 33, 5, 1, new double[] { 480, 1200, 2100 }, new double[] { 40, 200, 200 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 35, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* ɱ */
        new Consonant( 34, 5, 1, new double[] { 480, 1340, 2470 }, new double[] { 40, 300, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 35, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* n */
        new Consonant( 35, 5, 1, new double[] { 480, 1400, 1900 }, new double[] { 40, 300, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 35, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* ɳ */
        new Consonant( 36, 5, 1, new double[] { 480, 2000, 2900 }, new double[] { 40, 300, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 40, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* ɲ */
        new Consonant( 37, 5, 1, new double[] { 480, 1900, 2700 }, new double[] { 40, 300, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 40, 0.5, 450, 1, 0, new double[] { 0 }, 0 ), /* ŋ */
        new Consonant( 38, 5, 1, new double[] { 480, 1200, 2400 }, new double[] { 40, 300, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 40, 0.5, 450, 0, 0, new double[] { 0 }, 0 ), /* ɴ */
        new Consonant( 40, 8, 1, new double[] { 300, 1100, 2150 }, new double[] { 70, 110, 130 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʙ */
        new Consonant( 41, 8, 1, new double[] { 300, 1500, 2500 }, new double[] { 70, 100, 150 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* r */
        new Consonant( 42, 8, 1, new double[] { 400, 1200, 2300 }, new double[] { 80, 120, 200 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 50, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʀ */
        new Consonant( 44, 7, 1, new double[] { 300, 1100, 2080 }, new double[] { 60, 90, 120 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 20, 0, 0, 35, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ⱱ */
        new Consonant( 45, 7, 1, new double[] { 300, 1600, 2600 }, new double[] { 60, 100, 170 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 20, 0, 0, 35, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɾ */
        new Consonant( 46, 7, 1, new double[] { 300, 1500, 1900 }, new double[] { 60, 100, 170 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 20, 0, 0, 35, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɽ */
        new Consonant( 48, 3, 0, new double[] { 340, 1000, 2100 }, new double[] { 200, 120, 150 }, new double[] { 0, 0, 0, 0, 0, 55 }, -24, 100, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɸ */
        new Consonant( 49, 3, 1, new double[] { 220, 1000, 2100 }, new double[] { 60, 90, 120 }, new double[] { 0, 0, 0, 0, 0, 55 }, -28, 70, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* β */
        new Consonant( 50, 3, 0, new double[] { 340, 1100, 2080 }, new double[] { 200, 120, 150 }, new double[] { 0, 0, 0, 0, 0, 57 }, -22, 100, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* f */
        new Consonant( 51, 3, 1, new double[] { 220, 1100, 2080 }, new double[] { 60, 90, 120 }, new double[] { 0, 0, 0, 0, 0, 57 }, -26, 70, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* v */
        new Consonant( 52, 3, 0, new double[] { 320, 1290, 2540 }, new double[] { 200, 90, 200 }, new double[] { 0, 0, 0, 0, 28, 48 }, -24, 100, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* θ */
        new Consonant( 53, 3, 1, new double[] { 270, 1290, 2540 }, new double[] { 60, 80, 170 }, new double[] { 0, 0, 0, 0, 28, 48 }, -28, 70, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ð */
        new Consonant( 54, 3, 0, new double[] { 320, 1390, 2530 }, new double[] { 200, 80, 200 }, new double[] { 0, 0, 0, 0, 52, 0 }, -8, 120, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* s */
        new Consonant( 55, 3, 1, new double[] { 240, 1390, 2530 }, new double[] { 70, 60, 180 }, new double[] { 0, 0, 0, 0, 52, 0 }, -12, 90, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* z */
        new Consonant( 56, 3, 0, new double[] { 300, 1840, 2750 }, new double[] { 200, 100, 300 }, new double[] { 0, 57, 48, 48, 46, 0 }, -6, 120, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʃ */
        new Consonant( 57, 3, 1, new double[] { 240, 1840, 2750 }, new double[] { 60, 100, 300 }, new double[] { 0, 57, 48, 48, 46, 0 }, -10, 90, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ʒ */
        new Consonant( 58, 3, 0, new double[] { 300, 1600, 2000 }, new double[] { 200, 100, 250 }, new double[] { 0, 57, 50, 40, 30, 0 }, -7, 120, 0, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʂ */
        new Consonant( 59, 3, 1, new double[] { 240, 1600, 2000 }, new double[] { 60, 100, 250 }, new double[] { 0, 57, 50, 40, 30, 0 }, -11, 90, 0, 0, 40, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ʐ */
        new Consonant( 60, 3, 0, new double[] { 300, 2100, 3000 }, new double[] { 200, 150, 300 }, new double[] { 0, 35, 55, 50, 40, 0 }, -12, 110, 0, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ç */
        new Consonant( 61, 3, 1, new double[] { 240, 2100, 3000 }, new double[] { 60, 150, 300 }, new double[] { 0, 35, 55, 50, 40, 0 }, -16, 80, 0, 0, 45, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ʝ */
        new Consonant( 62, 3, 0, new double[] { 300, 1700, 2600 }, new double[] { 200, 150, 300 }, new double[] { 57, 50, 40, 35, 30, 0 }, -14, 110, 0, 0, 45, 0, 0, 1, 0, new double[] { 0 }, 0 ), /* x */
        new Consonant( 63, 3, 1, new double[] { 240, 1700, 2600 }, new double[] { 60, 150, 300 }, new double[] { 57, 50, 40, 35, 30, 0 }, -18, 80, 0, 0, 45, 0.35, 0, 1, 0, new double[] { 0 }, 0 ), /* ɣ */
        new Consonant( 64, 3, 0, new double[] { 400, 1200, 2400 }, new double[] { 200, 150, 300 }, new double[] { 58, 45, 35, 30, 25, 0 }, -13, 110, 0, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* χ */
        new Consonant( 65, 3, 1, new double[] { 300, 1200, 2400 }, new double[] { 80, 150, 300 }, new double[] { 58, 45, 35, 30, 25, 0 }, -17, 80, 0, 0, 45, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ʁ */
        new Consonant( 66, 4, 0, new double[] { 700, 1100, 2500 }, new double[] { 300, 150, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, -16, 90, 0, 0, 30, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ħ */
        new Consonant( 67, 4, 1, new double[] { 700, 1100, 2500 }, new double[] { 100, 150, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, -20, 70, 0, 0, 30, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ʕ */
        new Consonant( 68, 4, 0, new double[] { 0, 0, 0 }, new double[] { 300, 0, 0 }, new double[] { 0, 0, 0, 0, 0, 0 }, -16, 70, 0, 0, 15, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* h */
        new Consonant( 69, 4, 1, new double[] { 0, 0, 0 }, new double[] { 300, 0, 0 }, new double[] { 0, 0, 0, 0, 0, 0 }, -20, 60, 0, 0, 15, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɦ */
        new Consonant( 70, 3, 0, new double[] { 320, 1300, 2600 }, new double[] { 200, 90, 200 }, new double[] { 0, 0, 45, 50, 48, 40 }, -12, 110, 0, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɬ */
        new Consonant( 71, 3, 1, new double[] { 240, 1300, 2600 }, new double[] { 60, 90, 200 }, new double[] { 0, 0, 45, 50, 48, 40 }, -16, 80, 0, 0, 45, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ɮ */
        new Consonant( 72, 3, 0, new double[] { 300, 2000, 2900 }, new double[] { 200, 120, 300 }, new double[] { 0, 45, 57, 50, 45, 0 }, -7, 120, 0, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɕ */
        new Consonant( 73, 3, 1, new double[] { 240, 2000, 2900 }, new double[] { 60, 120, 300 }, new double[] { 0, 45, 57, 50, 45, 0 }, -11, 90, 0, 0, 45, 0.35, 0, 0, 0, new double[] { 0 }, 0 ), /* ʑ */
        new Consonant( 74, 3, 0, new double[] { 290, 610, 2150 }, new double[] { 200, 80, 60 }, new double[] { 0, 0, 0, 0, 0, 50 }, -22, 90, 0, 0, 100, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʍ */
        new Consonant( 75, 3, 0, new double[] { 320, 1390, 2530 }, new double[] { 200, 80, 200 }, new double[] { 0, 0, 0, 0, 52, 0 }, -8, 90, 0, 53, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* sʰ */
        new Consonant( 80, 6, 1, new double[] { 300, 1100, 2200 }, new double[] { 60, 90, 150 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 50, 0, 0, 70, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ʋ */
        new Consonant( 81, 6, 1, new double[] { 310, 1060, 1380 }, new double[] { 70, 100, 120 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 50, 0, 0, 90, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɹ */
        new Consonant( 82, 6, 1, new double[] { 310, 1100, 1500 }, new double[] { 70, 100, 120 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 50, 0, 0, 90, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɻ */
        new Consonant( 83, 6, 1, new double[] { 260, 2070, 3020 }, new double[] { 40, 250, 500 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 100, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* j */
        new Consonant( 84, 6, 1, new double[] { 300, 1200, 2400 }, new double[] { 50, 90, 150 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 100, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɰ */
        new Consonant( 85, 6, 1, new double[] { 290, 610, 2150 }, new double[] { 50, 80, 60 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 100, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* w */
        new Consonant( 86, 6, 1, new double[] { 260, 1700, 2200 }, new double[] { 40, 200, 300 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 40, 0, 0, 100, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɥ */
        new Consonant( 88, 6, 1, new double[] { 310, 1050, 2880 }, new double[] { 50, 100, 280 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 60, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* l */
        new Consonant( 89, 6, 1, new double[] { 310, 1300, 1900 }, new double[] { 50, 100, 200 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 60, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ɭ */
        new Consonant( 90, 6, 1, new double[] { 300, 2000, 2800 }, new double[] { 50, 150, 250 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 70, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ʎ */
        new Consonant( 91, 6, 1, new double[] { 310, 1100, 2700 }, new double[] { 50, 100, 250 }, new double[] { 0, 0, 0, 0, 0, 0 }, 0, 60, 0, 0, 70, 0.4, 0, 0, 0, new double[] { 0 }, 0 ), /* ʟ */
        new Consonant( 96, 2, 0, new double[] { 320, 1500, 2550 }, new double[] { 200, 90, 200 }, new double[] { 0, 0, 0, 0, 52, 0 }, -8, 70, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* t͡s */
        new Consonant( 97, 2, 1, new double[] { 240, 1500, 2550 }, new double[] { 70, 60, 180 }, new double[] { 0, 0, 0, 0, 52, 0 }, -12, 60, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* d͡z */
        new Consonant( 98, 2, 0, new double[] { 350, 1800, 2820 }, new double[] { 200, 90, 300 }, new double[] { 0, 44, 60, 53, 53, 0 }, -6, 70, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* t͡ʃ */
        new Consonant( 99, 2, 1, new double[] { 260, 1800, 2820 }, new double[] { 60, 80, 270 }, new double[] { 0, 44, 60, 53, 53, 0 }, -10, 60, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* d͡ʒ */
        new Consonant( 100, 2, 0, new double[] { 300, 2000, 2900 }, new double[] { 200, 120, 300 }, new double[] { 0, 45, 57, 50, 45, 0 }, -7, 70, 10, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* t͡ɕ */
        new Consonant( 101, 2, 1, new double[] { 240, 2000, 2900 }, new double[] { 60, 120, 300 }, new double[] { 0, 45, 57, 50, 45, 0 }, -11, 60, 10, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* d͡ʑ */
        new Consonant( 102, 2, 0, new double[] { 300, 1600, 2000 }, new double[] { 200, 100, 250 }, new double[] { 0, 57, 50, 40, 30, 0 }, -7, 70, 10, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ʈ͡ʂ */
        new Consonant( 103, 2, 1, new double[] { 240, 1600, 2000 }, new double[] { 60, 100, 250 }, new double[] { 0, 57, 50, 40, 30, 0 }, -11, 60, 10, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɖ͡ʐ */
        new Consonant( 104, 2, 0, new double[] { 340, 1100, 2080 }, new double[] { 200, 120, 150 }, new double[] { 0, 0, 0, 0, 0, 57 }, -16, 70, 5, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* p͡f */
        new Consonant( 105, 2, 0, new double[] { 300, 1800, 2700 }, new double[] { 250, 160, 330 }, new double[] { 57, 50, 40, 35, 30, 0 }, -12, 70, 20, 0, 50, 0, 0, 1, 0, new double[] { 0 }, 0 ), /* k͡x */
        new Consonant( 106, 2, 0, new double[] { 300, 2100, 3000 }, new double[] { 200, 150, 300 }, new double[] { 0, 35, 55, 50, 40, 0 }, -12, 70, 15, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* c͡ç */
        new Consonant( 107, 2, 1, new double[] { 240, 2100, 3000 }, new double[] { 60, 150, 300 }, new double[] { 0, 35, 55, 50, 40, 0 }, -16, 60, 12, 0, 45, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* ɟ͡ʝ */
        new Consonant( 108, 2, 0, new double[] { 320, 1290, 2540 }, new double[] { 200, 90, 200 }, new double[] { 0, 0, 0, 0, 28, 48 }, -18, 70, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* t͡θ */
        new Consonant( 109, 2, 1, new double[] { 270, 1290, 2540 }, new double[] { 60, 80, 170 }, new double[] { 0, 0, 0, 0, 28, 48 }, -22, 60, 8, 0, 40, 0, 0, 0, 0, new double[] { 0 }, 0 ), /* d͡ð */
        new Consonant( 110, 2, 1, new double[] { 240, 1800, 2700 }, new double[] { 60, 150, 300 }, new double[] { 57, 50, 40, 35, 30, 0 }, -16, 60, 15, 0, 50, 0, 0, 1, 0, new double[] { 0 }, 0 ), /* ɡ͡ɣ */
        new Consonant( 111, 2, 0, new double[] { 0, 0, 0 }, new double[] { 300, 0, 0 }, new double[] { 0, 0, 0, 0, 0, 0 }, -16, 50, 0, 0, 15, 0, 0, 0, 0, new double[] { 0 }, 1 ), /* ʔ͡h */
    };

    private static Consonant consonant(int id) {
        for (Consonant r : ROWS) if (r.id == id) return r;
        return null;
    }

    /** The glides: [code] → the syllabic vowel's chart point and the row. */
    private static final int[][] SEMIVOWELS = {
        { 0, 0, 0, 0 },
        { 255, 0, 0, 83 },
        { 255, 0, 255, 86 },
        { 0, 0, 0, 84 },
        { 0, 0, 255, 85 },
    };

    private static boolean consonantNeedsNoise(Consonant r) {
        return r != null && (r.manner == MANNER_STOP || r.manner == MANNER_AFFRICATE ||
                             r.manner == MANNER_FRICATIVE || r.manner == MANNER_ASPIRATE);
    }

    private static final int CLOSURE_MS = 50;
    private static final int ASPIRATION_DB = -6;
    /* A breathy-voiced stop's release: after its noisy lag (the row's `vot`)
     * the voice comes in BREATHY — the breath laid over it — for this long,
     * rising from MURMUR_AV. */
    private static final int BREATHY_SA = 47;
    private static final double MURMUR_AV = 0.05;
    /** Is the row a breathy-voiced stop ([bʱ]): voiced, with a release lag? */
    private static boolean isBreathy(Consonant r) { return r.manner == MANNER_STOP && r.voiced && r.vot > 0; }
    private static double dbGain(double db) { return js_pow(10, db / 20); }

    /* ── segment tracks ── */

    private static void trReset(Track t) { t.n = 0; }

    private static Track trAt(Track t, double time, double v) {
        if (t.n >= 2 && time < t.p[t.n - 2]) time = t.p[t.n - 2];
        if (t.n + 2 <= FORMANT_TRACK_MAX) { t.p[t.n] = time; t.p[t.n + 1] = v; t.n += 2; }
        return t;
    }

    private static void trClose(Track t, double time, double v) {
        if (t.n == 0) trAt(t, 0, v);
        double lt = t.p[t.n - 2];
        if (time > lt) trAt(t, time, v);
        else if (t.p[t.n - 1] != v) trAt(t, lt, v);
    }

    private static double trackAt(Track tr, double t) {
        double[] p = tr.p;
        if (t <= p[0]) return p[1];
        for (int i = 2; i < tr.n; i += 2) {
            if (t < p[i]) {
                double t0 = p[i - 2], t1 = p[i], v0 = p[i - 1], v1 = p[i + 1];
                return t1 > t0 ? v0 + ((v1 - v0) * (t - t0)) / (t1 - t0) : v1;
            }
        }
        return p[tr.n - 1];
    }

    /** What the next sound starts with: av, ah, af, nz. */
    private static final class NextStart { double av, ah, af, nz; }

    /** Append a point as Float64Array.from does, without at()'s clamp. */
    private static void trPush(Track t, double time, double v) {
        if (t.n + 2 <= FORMANT_TRACK_MAX) { t.p[t.n] = time; t.p[t.n + 1] = v; t.n += 2; }
    }

    /* A closed track with a glottal catch at its START (catchTrack): 0 until
     * t0, back on the track's own value by t1. */
    private static void catchTrack(Track tr, double t0, double t1) {
        Track out = new Track();
        trPush(out, 0, 0);
        trPush(out, t0, 0);
        trPush(out, t1, trackAt(tr, t1));
        for (int i = 0; i < tr.n; i += 2) if (tr.p[i] > t1) trPush(out, tr.p[i], tr.p[i + 1]);
        tr.copyFrom(out);
    }

    /** The av track's glottal catch for an obstruent's voicing (onsetTracks catchAt). */
    private static void catchAt(Track av, double t, double k, double nxAv) {
        trAt(trAt(trAt(av, 0, 0), t + GLOT_CATCH * k, 0), t + (GLOT_CATCH + GLOT_RISE) * k, nxAv);
    }

    /* An onset consonant's tracks (onsetTracks). `art` is its secondary
     * articulation, of which only the glottal one changes a track. Every oral
     * consonant's nasality holds 0 until its own part is over. */
    private static void onsetTracksOf(Consonant r, boolean first, NextStart nx, double k, int art, Segment sg) {
        Track w = sg.w, av = sg.av, ah = sg.ah, af = sg.af, nz = sg.nz;
        double L = dbGain(r.lvl);
        double tr = r.trans * k, dur = r.dur * k, end;
        boolean glot = art == ART_GLOTTAL, sonorant = false;
        trReset(w); trReset(av); trReset(ah); trReset(af); trReset(nz);
        switch (r.manner) {
        case MANNER_STOP: {
            double cl = first ? 0 : CLOSURE_MS * k;
            double tb = cl + r.burst * k;
            double tv;
            trAt(trAt(trAt(trAt(trAt(af, 0, 0), cl, 0), cl, L), tb, 0.3 * L), tb + 2 * k, 0);
            trAt(ah, 0, 0);
            trAt(trAt(trAt(trAt(w, 0, 1), tb, 1), tb + 1 * k, 0.5), tb + tr, 0);
            trAt(trAt(nz, 0, 0), tb, 0);
            if (glot) {
                /* The glottis shut through the closure, the voicing in at once. */
                tv = tb + dmin(r.vot, GLOT_LAG) * k;
                trAt(trAt(trAt(av, 0, 0), tv, 0), tv + GLOT_RISE * k, nx.av);
                end = dmax(tv + GLOT_RISE * k, tb + tr);
                break;
            }
            tv = tb + r.vot * k;
            if (isBreathy(r)) {
                /* The closure voiced; the release a noisy lag over a weak
                 * voice — its breath at the burst's own level — then the voice
                 * rising through it. */
                double ts = tv + BREATHY_SA * k;
                trAt(trAt(trAt(trAt(trAt(trAt(av, 0, cl > 0 ? 0.25 : 0.3), cl, cl > 0 ? 0.25 : 0.3), tb, 0.3),
                     tb + 3 * k, MURMUR_AV), tv, MURMUR_AV), ts, nx.av);
                trAt(trAt(trAt(trAt(ah, tb, 0), tb + 3 * k, L), tv, 0.7 * L), ts, nx.ah);
                end = dmax(ts, tb + tr);
                break;
            }
            if (r.voiced) {
                trAt(trAt(trAt(trAt(av, 0, cl > 0 ? 0.25 : 0.3), cl, cl > 0 ? 0.25 : 0.3), tb, 0.3), tb + 10 * k, nx.av);
            } else {
                trAt(trAt(trAt(av, 0, 0), tv, 0), tv + 10 * k, nx.av);
            }
            if (!r.voiced && r.vot > 0) {
                double La = L * dbGain(ASPIRATION_DB);
                trAt(trAt(trAt(trAt(ah, tb, 0), tb + 3 * k, La), tv, 0.7 * La), tv + 10 * k, nx.ah);
            }
            end = dmax(tv + 10 * k, tb + tr);
            break;
        }
        case MANNER_AFFRICATE: {
            double cl = first ? 0 : CLOSURE_MS * k;
            double tb = cl + r.burst * k;
            double tf = tb + dur;
            trAt(trAt(trAt(trAt(trAt(trAt(af, 0, 0), cl, 0), cl, L), tb, 0.8 * L), tf - 10 * k, L), tf + 10 * k, nx.af);
            if (glot) catchAt(av, tf, k, nx.av);
            else if (r.voiced) trAt(trAt(trAt(av, 0, 0.3), tf, 0.3), tf + 15 * k, nx.av);
            else trAt(trAt(trAt(av, 0, 0), tf, 0), tf + 15 * k, nx.av);
            trAt(ah, 0, 0);
            trAt(trAt(trAt(w, 0, 1), tf, 1), tf + tr, 0);
            trAt(trAt(nz, 0, 0), tf, 0);
            end = dmax(tf + 15 * k, tf + tr);
            if (glot) end = dmax(end, tf + (GLOT_CATCH + GLOT_RISE) * k);
            break;
        }
        case MANNER_FRICATIVE:
            trAt(trAt(trAt(trAt(af, 0, 0), 10 * k, L), dur - 10 * k, L), dur + 10 * k, nx.af);
            if (!glot && !r.voiced && r.vot > 0) {
                /* Aspirated: the frication gives way to breath, and the voice
                 * waits for the breath to end. */
                double tv = dur + r.vot * k;
                double La = L * dbGain(ASPIRATION_DB);
                trAt(trAt(trAt(trAt(trAt(ah, 0, 0), dur - 10 * k, 0), dur, La), tv, 0.7 * La), tv + 10 * k, nx.ah);
                trAt(trAt(trAt(av, 0, 0), tv - 5 * k, 0), tv + 15 * k, nx.av);
                trAt(trAt(trAt(w, 0, 1), dur, 1), dur + tr, 0);
                trAt(trAt(nz, 0, 0), tv, 0);
                end = dmax(tv + 15 * k, dur + tr);
                break;
            }
            if (glot) catchAt(av, dur, k, nx.av);
            else if (r.voiced) trAt(trAt(trAt(av, 0, r.av), dur, r.av), dur + 15 * k, nx.av);
            else trAt(trAt(trAt(av, 0, 0), dur - 5 * k, 0), dur + 15 * k, nx.av);
            trAt(ah, 0, 0);
            trAt(trAt(trAt(w, 0, 1), dur, 1), dur + tr, 0);
            trAt(trAt(nz, 0, 0), dur, 0);
            end = dmax(dur + 15 * k, dur + tr);
            if (glot) end = dmax(end, dur + (GLOT_CATCH + GLOT_RISE) * k);
            break;
        case MANNER_ASPIRATE:
            trAt(trAt(trAt(trAt(ah, 0, 0), 10 * k, L), dur - 10 * k, L), dur + 10 * k, nx.ah);
            if (glot) catchAt(av, dur, k, nx.av);
            else if (r.voiced) trAt(trAt(trAt(av, 0, r.av), dur, r.av), dur + 10 * k, nx.av);
            else trAt(trAt(trAt(av, 0, 0), dur - 10 * k, 0), dur + 10 * k, nx.av);
            trAt(af, 0, 0);
            trAt(trAt(trAt(w, 0, 1), dur, 1), dur + tr, 0);
            trAt(trAt(nz, 0, 0), dur, 0);
            end = dmax(dur + 10 * k, dur + tr);
            if (glot) end = dmax(end, dur + (GLOT_CATCH + GLOT_RISE) * k);
            break;
        case MANNER_NASAL:
            trAt(trAt(trAt(av, 0, r.av), dur, r.av), dur + 10 * k, nx.av);
            trAt(trAt(trAt(nz, 0, 1), dur, 1), dur + 10 * k, nx.nz);
            trAt(trAt(trAt(trAt(w, 0, 1), dur, 1), dur + 5 * k, 0.5), dur + tr, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0);
            end = dmax(dur + 10 * k, dur + tr);
            sonorant = true;
            break;
        case MANNER_APPROX:
            trAt(trAt(trAt(av, 0, r.av), dur, r.av), dur + tr * 0.5, nx.av);
            trAt(trAt(trAt(w, 0, 1), dur, 1), dur + tr, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, 0), dur, 0);
            end = dur + tr;
            sonorant = true;
            break;
        case MANNER_TAP:
            trAt(trAt(trAt(av, 0, 0.2), dur, 0.2), dur + 5 * k, nx.av);
            trAt(trAt(trAt(w, 0, 1), dur, 1), dur + tr, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, 0), dur, 0);
            end = dmax(dur + 5 * k, dur + tr);
            sonorant = true;
            break;
        case MANNER_TRILL: {
            double c = dur, shut = c * 0.375;
            trAt(trAt(trAt(trAt(trAt(trAt(trAt(av, 0, 0.12), shut, 0.12), shut + 3 * k, 0.7), c - 3 * k, 0.7),
                 c, 0.12), c + shut, 0.12), c + shut + 3 * k, nx.av);
            double tail = c + shut + 3 * k;
            trAt(trAt(trAt(w, 0, 1), tail, 1), tail + tr, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, 0), tail, 0);
            end = tail + tr;
            sonorant = true;
            break;
        }
        default: {
            double cl = first ? 0 : 30 * k;
            trAt(trAt(trAt(av, 0, 0), cl, 0), cl + 3 * k, nx.av);
            trAt(w, 0, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, 0), cl, 0);
            end = cl + 3 * k;
            break;
        }
        }
        sg.end = end;
        sg.loop = 0;
        trClose(av, end, nx.av);
        /* A glottalised sonorant is said after a glottal stop: the catch first. */
        if (glot && sonorant) catchTrack(av, GLOT_CATCH * k, (GLOT_CATCH + GLOT_RISE) * k);
        trClose(w, end, 0);
        trClose(ah, end, nx.ah);
        trClose(af, end, nx.af);
        trClose(nz, end, nx.nz);
    }

    /** Tracks with their two noise paths exchanged (breathed): frication
     *  built as a row's `af` sounds through the cascade as `ah`, and the
     *  other way about. */
    private static void breathed(Segment sg) {
        Track t = sg.ah;
        sg.ah = sg.af;
        sg.af = t;
    }

    /** The onset tracks — a BREATH row's ([ʔ͡h]) frication is its aspiration. */
    private static void onsetTracks(Consonant r, boolean first, NextStart nx, double k, int art, Segment sg) {
        if (!r.breath) { onsetTracksOf(r, first, nx, k, art, sg); return; }
        NextStart sw = new NextStart();
        sw.av = nx.av; sw.ah = nx.af; sw.af = nx.ah; sw.nz = nx.nz;
        onsetTracksOf(r, first, sw, k, art, sg);
        breathed(sg);
    }

    /* A NUCLEUS's tracks (nucleusTracks): the consonant a syllable with no
     * vowel is held on. It starts as an onset consonant does and then holds
     * what it can sustain; past `end` every track holds, but a trill's time
     * wraps back by `loop` ms. */
    private static void nucleusTracksOf(Consonant r, boolean first, double k, int art, Segment sg) {
        Track w = sg.w, av = sg.av, ah = sg.ah, af = sg.af, nz = sg.nz;
        double L = dbGain(r.lvl);
        double dur = r.dur * k, end, loop = 0;
        boolean glot = art == ART_GLOTTAL, sonorant = false;
        trReset(w); trReset(av); trReset(ah); trReset(af); trReset(nz);
        trAt(w, 0, 1);
        switch (r.manner) {
        case MANNER_STOP: {
            double cl = first ? 0 : CLOSURE_MS * k;
            double tb = cl + r.burst * k;
            trAt(trAt(trAt(trAt(trAt(af, 0, 0), cl, 0), cl, L), tb, 0.3 * L), tb + 2 * k, 0);
            trAt(ah, 0, 0);
            if (glot) {
                double tv = tb + GLOT_LAG * k;
                trAt(trAt(trAt(av, 0, 0), tv, 0), tv + GLOT_RISE * k, r.voiced ? 1 : 0);
                end = tv + GLOT_RISE * k;
            } else if (isBreathy(r)) {
                double a = cl > 0 ? 0.25 : 0.3;
                double tv = tb + r.vot * k, ts = tv + BREATHY_SA * k;
                trAt(trAt(trAt(trAt(trAt(trAt(av, 0, a), cl, a), tb, 0.3), tb + 3 * k, MURMUR_AV), tv, MURMUR_AV), ts, 1);
                trAt(trAt(trAt(trAt(ah, tb, 0), tb + 3 * k, L), tv, 0.7 * L), ts, 0);
                end = ts;
            } else if (r.voiced) {
                double a = cl > 0 ? 0.25 : 0.3;
                trAt(trAt(trAt(trAt(av, 0, a), cl, a), tb, 0.3), tb + 10 * k, 1);
                end = tb + 10 * k;
            } else {
                trAt(av, 0, 0);
                if (r.vot > 0) {
                    double La = L * dbGain(ASPIRATION_DB);
                    trAt(trAt(trAt(ah, tb, 0), tb + 3 * k, La), tb + 10 * k, 0.7 * La);
                }
                end = tb + 10 * k;
            }
            trAt(nz, 0, 0);
            break;
        }
        case MANNER_AFFRICATE: {
            double cl = first ? 0 : CLOSURE_MS * k;
            double tb = cl + r.burst * k;
            trAt(trAt(trAt(trAt(trAt(af, 0, 0), cl, 0), cl, L), tb, 0.8 * L), tb + 10 * k, L);
            trAt(av, 0, r.voiced && !glot ? 0.3 : 0);
            trAt(ah, 0, 0); trAt(nz, 0, 0);
            end = tb + 10 * k;
            break;
        }
        case MANNER_FRICATIVE:
            trAt(trAt(af, 0, 0), 10 * k, L);
            trAt(av, 0, r.voiced && !glot ? r.av : 0);
            trAt(ah, 0, 0); trAt(nz, 0, 0);
            end = 10 * k;
            break;
        case MANNER_ASPIRATE:
            trAt(trAt(ah, 0, 0), 10 * k, L);
            trAt(av, 0, r.voiced && !glot ? r.av : 0);
            trAt(af, 0, 0); trAt(nz, 0, 0);
            end = 10 * k;
            break;
        case MANNER_NASAL:
            trAt(av, 0, 1);
            trAt(nz, 0, 1);
            trAt(ah, 0, 0); trAt(af, 0, 0);
            end = 0;
            sonorant = true;
            break;
        case MANNER_APPROX:
            trAt(av, 0, 1);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(nz, 0, 0);
            end = 0;
            sonorant = true;
            break;
        case MANNER_TAP:            /* the tap, then the voicing held where it let go */
            trAt(trAt(trAt(av, 0, 0.2), dur, 0.2), dur + 5 * k, 1);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(nz, 0, 0);
            end = dur + 5 * k;
            sonorant = true;
            break;
        case MANNER_TRILL: {        /* two cycles; the second repeats */
            double c = dur, shut = c * 0.375;
            for (int n = 0; n < 2; n++) {
                double o = n * c;
                trAt(trAt(trAt(trAt(av, o, 0.12), o + shut, 0.12), o + shut + 3 * k, 0.7), o + c - 3 * k, 0.7);
            }
            trAt(av, 2 * c, 0.12);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(nz, 0, 0);
            end = 2 * c;
            loop = c;
            sonorant = true;
            break;
        }
        default:                    /* the glottis shut, and nothing to hold */
            trAt(av, 0, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(nz, 0, 0);
            end = 0;
            break;
        }
        sg.end = end;
        sg.loop = loop;
        trClose(av, end, av.p[av.n - 1]);
        if (glot && sonorant) catchTrack(av, GLOT_CATCH * k, (GLOT_CATCH + GLOT_RISE) * k);
        trClose(w, end, w.p[w.n - 1]);
        trClose(ah, end, ah.p[ah.n - 1]);
        trClose(af, end, af.p[af.n - 1]);
        trClose(nz, end, nz.p[nz.n - 1]);
    }

    /** The nucleus tracks — a BREATH row's ([ʔ͡h]) frication is its aspiration. */
    private static void nucleusTracks(Consonant r, boolean first, double k, int art, Segment sg) {
        nucleusTracksOf(r, first, k, art, sg);
        if (r.breath) breathed(sg);
    }

    /* A CODA consonant's tracks (codaTracks): from the sound before it — the
     * vowel, or a held nucleus with voicing v0 and nasality z0 — into
     * silence. `art` is the coda's own articulation (CODA_*). */
    private static void codaTracksOf(Consonant r, double k, double v0, double z0, int art, Segment sg) {
        Track w = sg.w, av = sg.av, ah = sg.ah, af = sg.af, nz = sg.nz;
        double L = dbGain(r.lvl);
        double tr = r.trans * k, dur = r.dur * k, end;
        boolean glot = art == CODA_GLOTTAL;
        double gs = GLOT_STOP * k;
        /* Where the coda HOLDS while the key does, after S $9010 (item 292):
         * the end of the consonant's steady part — a stop in its closure,
         * before the burst — and, for a trill, the cycle it repeats there
         * (`loop`). */
        double hold, loop = 0;
        trReset(w); trReset(av); trReset(ah); trReset(af); trReset(nz);
        switch (r.manner) {
        case MANNER_STOP:
        case MANNER_AFFRICATE: {
            double tc = tr + CLOSURE_MS * k;
            double tb = tc + r.burst * k;
            /* A ms before the release: AT tc the burst's frication has already
             * stepped on (the later of two points at one time holds). */
            hold = tc - k;
            trAt(trAt(trAt(w, 0, 0), tr, 0.5), tr + 5 * k, 1);
            if (glot) trAt(trAt(trAt(av, 0, v0), tr, v0), tr + gs, 0);
            else if (r.voiced) trAt(trAt(trAt(trAt(trAt(av, 0, v0), tr, v0), tr + 8 * k, 0.25), tc, 0.2), tc + 5 * k, 0);
            else trAt(trAt(trAt(av, 0, v0), tr, v0), tr + 8 * k, 0);
            if (art == CODA_UNRELEASED) {
                trAt(af, 0, 0);
                trAt(ah, 0, 0);
                end = r.voiced ? tc + 5 * k : tr + 8 * k;
            } else if (r.manner == MANNER_STOP) {
                trAt(trAt(trAt(trAt(trAt(af, 0, 0), tc, 0), tc, 0.7 * L), tb, 0.2 * L), tb + 2 * k, 0);
                trAt(ah, 0, 0);
                if (!glot && r.vot > 0) {
                    double La = L * dbGain(ASPIRATION_DB);
                    trAt(trAt(trAt(ah, tb, 0), tb + 2 * k, 0.5 * La), tb + 30 * k, 0);
                    /* A breathy stop's release is murmured: a weak voice under its breath. */
                    if (r.voiced) trAt(trAt(trAt(av, tb, 0), tb + 2 * k, MURMUR_AV), tb + 30 * k, 0);
                }
                end = tb + 35 * k;
            } else {
                trAt(trAt(trAt(trAt(trAt(af, 0, 0), tc, 0), tc, L), tb + dur, 0.6 * L), tb + dur + 20 * k, 0);
                trAt(ah, 0, 0);
                end = tb + dur + 20 * k;
            }
            trAt(trAt(nz, 0, z0), tr + 5 * k, 0);
            break;
        }
        case MANNER_FRICATIVE: {
            double a = dmax(tr - 15 * k, 0);
            hold = tr + dur;
            trAt(trAt(w, 0, 0), tr, 1);
            if (glot) trAt(trAt(trAt(av, 0, v0), a, v0), a + gs, 0);
            else if (r.voiced) trAt(trAt(trAt(trAt(av, 0, v0), tr, r.av), tr + dur, 0.7 * r.av), tr + dur + 30 * k, 0);
            else trAt(trAt(trAt(av, 0, v0), a, v0), tr + 5 * k, 0);
            trAt(trAt(trAt(trAt(trAt(af, 0, 0), a, 0), tr + 5 * k, L), tr + dur, 0.8 * L), tr + dur + 30 * k, 0);
            trAt(ah, 0, 0); trAt(trAt(nz, 0, z0), tr, 0);
            end = tr + dur + 30 * k;
            if (!glot && !r.voiced && r.vot > 0) {   /* aspirated: its breath after it */
                double La = L * dbGain(ASPIRATION_DB);
                trAt(trAt(trAt(ah, tr + dur, 0), tr + dur + 10 * k, 0.5 * La), tr + dur + (r.vot + 30) * k, 0);
                end = tr + dur + (r.vot + 30) * k;
            }
            break;
        }
        case MANNER_ASPIRATE:
            hold = dur;
            trAt(trAt(w, 0, 0), 15 * k, 1);
            if (glot) trAt(trAt(av, 0, v0), gs, 0);
            else if (r.voiced) trAt(trAt(trAt(trAt(av, 0, v0), 20 * k, r.av), dur, 0.5 * r.av), dur + 20 * k, 0);
            else trAt(trAt(av, 0, v0), 20 * k, 0);
            trAt(trAt(trAt(trAt(ah, 0, 0), 10 * k, L), dur, 0.5 * L), dur + 30 * k, 0);
            trAt(af, 0, 0); trAt(trAt(nz, 0, z0), 15 * k, 0);
            end = dur + 30 * k;
            break;
        case MANNER_NASAL:
            hold = tr + dur;
            trAt(trAt(w, 0, 0), tr, 1);
            trAt(trAt(trAt(nz, 0, z0), dmax(tr - 5 * k, 0), z0), tr, 1);
            trAt(trAt(trAt(av, 0, v0), tr, r.av), tr + dur, 0.8 * r.av);
            end = tr + dur + (glot ? gs : 40 * k);
            trAt(av, end, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0);
            break;
        case MANNER_APPROX:
            hold = tr + dur;
            trAt(trAt(w, 0, 0), tr, 1);
            trAt(trAt(trAt(av, 0, v0), tr, r.av), tr + dur, 0.8 * r.av);
            end = tr + dur + (glot ? gs : 40 * k);
            trAt(av, end, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, z0), tr, 0);
            break;
        case MANNER_TAP:
            trAt(trAt(w, 0, 0), tr, 1);
            trAt(trAt(trAt(trAt(av, 0, v0), tr, v0), tr + 3 * k, 0.2), tr + dur, 0.2);
            if (glot) {
                end = tr + dur + gs;
                hold = tr + dur;
            } else {
                trAt(av, tr + dur + 3 * k, 0.6);
                end = tr + dur + 40 * k;
                hold = tr + dur + 3 * k;
            }
            trAt(av, end, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, z0), tr, 0);
            break;
        case MANNER_TRILL: {
            double c = dur, shut = c * 0.375;
            /* [tr + shut, tr + c + shut) is one whole cycle, closure to closure. */
            hold = tr + c + shut;
            loop = c;
            trAt(trAt(w, 0, 0), tr, 1);
            trAt(trAt(trAt(trAt(trAt(trAt(trAt(trAt(av, 0, v0), tr, 0.7), tr + 3 * k, 0.12),
                 tr + shut, 0.12), tr + shut + 3 * k, 0.7), tr + c - 3 * k, 0.7), tr + c, 0.12), tr + c + shut, 0.12);
            if (glot) {
                end = tr + c + shut + gs;
            } else {
                trAt(av, tr + c + shut + 3 * k, 0.6);
                end = tr + c + shut + 35 * k;
            }
            trAt(av, end, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, z0), tr, 0);
            break;
        }
        default:
            trAt(w, 0, 0);
            trAt(trAt(av, 0, v0), 6 * k, 0);
            trAt(ah, 0, 0); trAt(af, 0, 0); trAt(trAt(nz, 0, z0), 6 * k, 0);
            end = 6 * k;
            hold = end;
            break;
        }
        sg.end = end;
        sg.hold = dmin(hold, end);
        sg.loop = hold < end ? loop : 0;
        trClose(w, end, 1);
        trClose(av, end, 0);
        trClose(ah, end, 0);
        trClose(af, end, 0);
        trClose(nz, end, 0);
    }

    /** The coda tracks — a BREATH row's ([ʔ͡h]) frication is its aspiration. */
    private static void codaTracks(Consonant r, double k, double v0, double z0, int art, Segment sg) {
        codaTracksOf(r, k, v0, z0, art, sg);
        if (r.breath) breathed(sg);
    }

    /* A coda after a nucleus that hisses (afterRelease): the hiss lets go
     * over `rel` ms first, voicing v0 and nasality z0 held, then the coda's
     * tracks. */
    private static void shiftTrack(Track t, double rel, double v) {
        Track out = new Track();
        trPush(out, 0, v);
        for (int i = 0; i < t.n; i += 2) trPush(out, t.p[i] + rel, t.p[i + 1]);
        t.copyFrom(out);
    }

    private static void afterRelease(Segment sg, double rel, double v0, double a0, double h0, double z0) {
        sg.end = sg.end + rel;
        sg.hold = sg.hold + rel;
        shiftTrack(sg.w, rel, 0);
        shiftTrack(sg.av, rel, v0);
        shiftTrack(sg.ah, rel, h0);
        shiftTrack(sg.af, rel, a0);
        shiftTrack(sg.nz, rel, z0);
    }

    private static double timingScale(int b) { return js_pow(2, (b - 128) / 64.0); }

    /** The timing scale of slot `i` (0, 1 the onset, 2 the coda, 3 the
     *  glide): the record's, times a lyric syllable's own for the slot. */
    private static double slotScale(FormantPatch patch, int i) {
        double k = timingScale(patch.timing);
        return patch.hasSlotTiming ? k * timingScale(patch.slotTiming[i]) : k;
    }

    /** A LYRIC SYLLABLE (spec §5.5.3, "Lyric instruments"): the record's
     *  patch `rec` with syllable `syl` of its `ILyr` entry in place. Written
     *  into `out` IN PLACE, so that a note singing it follows a reload of the
     *  record. */
    static void lyricSyllable(FormantPatch rec, LyricEntry.Syllable syl, LyricEntry entry, FormantPatch out) {
        out.srcPtr = rec.srcPtr;
        out.srcInst = rec.srcInst;
        out.front = syl.front & 0xff;
        out.open = syl.open & 0xff;
        out.round = syl.round & 0xff;
        out.track = rec.track;
        out.refNote = syl.vowel ? (rec.refNote != 0 ? rec.refNote : FORMANT_DEFAULT_REF) : 0;
        out.dRefNote = syl.vowel ? rec.dRefNote : 0.0;
        out.nasal = syl.nasal;
        out.glide = rec.glide;
        out.timing = rec.timing;
        out.rangeLo = rec.rangeLo;
        out.rangeHi = rec.rangeHi;
        decodeGr(out, syl.gr & 0xff);
        for (int i = 0; i < FORMANT_SLOTS; i++) {
            FormantPatch.Slot s = out.slots[i];
            int cons = syl.cons[i];
            LyricEntry.Recipe r = cons != 0 && entry.recipeOf[cons] >= 0 ? entry.recipes[entry.recipeOf[cons]] : null;
            s.cons = cons;
            s.ptr = r != null ? r.ptr & 0xffffff : 0;
            s.len = r != null ? r.len & 0xffff : 0;
            s.offset = r != null ? r.offset & 0xffff : 0;
            s.rate = r != null ? r.rate & 0xffff : 0;
            s.tilt = r != null ? r.tilt : 0;
            s.gain = r != null ? r.gain & 0xff : 0;
        }
        out.hasSlotTiming = true;
        for (int i = 0; i < 4; i++) out.slotTiming[i] = syl.timing[i];
        out.lyricOf = rec;
    }

    /* ── loudness tables ── */

    private static final int NORM_EVERY = 8;
    private static final double NORM_MIN = 1.0 / 256;
    private static final double NORM_MAX = 16;
    private static final double FORMANT_LEVEL = 0.5773502691896258;
    private static final double SOURCE_GAIN_MAX = 16;
    private static final int NOISE_SEED = 0x9e3779b9;
    private static final int SOURCE_MEASURE_MAX = 16384;
    private static final int SOURCE_WINDOW = 1024;
    private static final double FNP = 270;
    private static final double BNP = 100;
    private static final double BNZ = 100;
    private static final double LOUDNESS_PHON = 60;

    private static final double[] ISO226_F = { 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400,
        500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500 };
    private static final double[] ISO226_AF = { 0.532, 0.506, 0.480, 0.455, 0.432, 0.409, 0.387, 0.367, 0.349,
        0.330, 0.315, 0.301, 0.288, 0.276, 0.267, 0.259, 0.253, 0.250, 0.246, 0.244, 0.243, 0.243, 0.243, 0.242,
        0.242, 0.245, 0.254, 0.271, 0.301 };
    private static final double[] ISO226_LU = { -31.6, -27.2, -23.0, -19.1, -15.9, -13.0, -10.3, -8.1, -6.2, -4.5,
        -3.1, -2.0, -1.1, -0.4, 0.0, 0.3, 0.5, 0.0, -2.7, -4.1, -1.0, 1.7, 2.5, 1.2, -2.1, -7.1, -11.2, -10.7, -3.1 };
    private static final double[] ISO226_TF = { 78.5, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9, 14.4,
        11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7, -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3 };
    private static final int ELW_LAST = ISO226_N - 1;

    /** formant.js's module-level tables, computed once per engine. The bank
     *  grid is built at the engine rate. */
    static final class FTables {
        final double[][][] logCard = new double[4][4][2];
        final double[][] artF = new double[3][5];      /* [ART_PALATAL], [ART_DARK] */
        final double[] elwLogF = new double[ISO226_N], elwDb = new double[ISO226_N];
        int bankN;
        final double[] bankW = new double[BANK_GRID_MAX], bankC1 = new double[BANK_GRID_MAX], bankS1 = new double[BANK_GRID_MAX];
        final double[] bankC2 = new double[BANK_GRID_MAX], bankS2 = new double[BANK_GRID_MAX];
        double bankRef;
        final double[] dft = new double[SOURCE_DFT_MAX];   /* scratch */
        final double[] f12 = new double[2];                /* scratch */
        final double[] ampNow = new double[3], ampNext = new double[3];   /* scratch */
        final double[] nextF = new double[5], nextB = new double[5];      /* scratch */
        final int[] jBox = new int[1];                     /* scratch */
        /* sourceHarmonics' memo: the same bytes measure the same. */
        int memoCount;
        final long[] memoP = new long[32], memoN = new long[32];
        final double[] memoM = new double[32], memoPP = new double[32];
        final int[] memoH = new int[32], memoK = new int[32];
        final double[][] memoW = new double[32][NORM_HARMONICS + 1];
        /* measureChipSource's memo: a chip patch's measurement at a rate.
         * Cleared whenever the instruments are uploaded. */
        int chipMemoCount;
        final Object[] cmPatch = new Object[32];
        final int[] cmKind = new int[32], cmGen = new int[32], cmRtGen = new int[32], cmK = new int[32];
        final double[] cmPr = new double[32], cmSm = new double[32], cmSg = new double[32];
        final double[][] cmW = new double[32][NORM_HARMONICS + 1];
        final double[] chipBuf = new double[4096];         /* scratch */
    }

    /** The equal-loudness weighting in dB at f, searching up from index j[0]. */
    private static double elwDb(FTables T, double f, int[] j) {
        double lf = js_log2(f);
        int i = j[0];
        if (!(lf > T.elwLogF[0])) { j[0] = 0; return T.elwDb[0]; }
        if (lf >= T.elwLogF[ELW_LAST]) { j[0] = ELW_LAST; return T.elwDb[ELW_LAST]; }
        while (lf > T.elwLogF[i + 1]) i++;
        j[0] = i;
        double u = (lf - T.elwLogF[i]) / (T.elwLogF[i + 1] - T.elwLogF[i]);
        return T.elwDb[i] + u * (T.elwDb[i + 1] - T.elwDb[i]);
    }

    static void tablesInit(FTables T, int rate) {
        for (int h = 0; h < 4; h++)
            for (int c = 0; c < 4; c++)
                for (int n = 0; n < 2; n++) T.logCard[h][c][n] = js_log(CARDINAL_VOWELS[h][c][n]);
        /* The tongue a secondary articulation holds behind a consonant
         * (ART_F): ART_PALATAL [i]'s, ART_DARK the back of the tongue drawn
         * back, midway between [ʌ] and [ɑ]. Its formants at a man's size. */
        vowelFormants(T, 255, 0, 0, T.artF[ART_PALATAL], false);
        vowelFormants(T, 0, 212, 0, T.artF[ART_DARK], false);
        for (int i = 0; i < ISO226_N; i++) {
            double af = ISO226_AF[i], lu = ISO226_LU[i], tf = ISO226_TF[i];
            double A = 4.47e-3 * (js_pow(10, 0.025 * LOUDNESS_PHON) - 1.15) +
                       js_pow(0.4 * js_pow(10, (tf + lu) / 10 - 9), af);
            T.elwLogF[i] = js_log2(ISO226_F[i]);
            T.elwDb[i] = -((10 / af) * js_log10(A) - lu + 94);
        }
        double band = js_pow(2, 1.0 / 12) - js_pow(2, -1.0 / 12);
        T.bankN = 0;
        T.bankRef = 0;
        int[] j = new int[1];
        for (int i = 0; 50 * js_pow(2, i / 6.0) <= 0.45 * rate && i < BANK_GRID_MAX; i++) {
            double f = 50 * js_pow(2, i / 6.0);
            j[0] = 0;
            T.bankW[i] = js_pow(10, elwDb(T, f, j) / 10) * f * band;
            double om = (2 * PI * f) / rate;
            T.bankC1[i] = js_cos(om);
            T.bankS1[i] = js_sin(om);
            T.bankC2[i] = js_cos(2 * om);
            T.bankS2[i] = js_sin(2 * om);
            T.bankN = i + 1;
        }
        for (int i = 0; i < T.bankN; i++) T.bankRef = T.bankRef + T.bankW[i];
    }

    private static final double[] PAR_F = { 3300, 3750, 4900 }, PAR_B = { 250, 200, 1000 };

    /* ── the sounding voice ── */

    private static void nextStartOf(Segment s, NextStart nx) {
        nx.av = s.av.p[1]; nx.ah = s.ah.p[1]; nx.af = s.af.p[1]; nx.nz = s.nz.p[1];
    }

    /** The articulation blendInto gives a segment (tractArt): a secondary
     *  articulation's tongue — the glottal one changes the tracks, not the
     *  tract. */
    private static int tractArt(int a) { return a == ART_PALATAL || a == ART_DARK ? a : ART_NONE; }

    static void stateInit(FormantState st) { st.init(); }

    /* The coda a record gives a note (buildCoda): slot 2's consonant, from
     * the vowel — or, in a syllable with no vowel, from its nucleus as it
     * holds, a hissing nucleus letting its hiss go first. False with no coda
     * consonant. `vz` is how nasalised the vowel is as the coda leaves it
     * (0…1), when it has one. */
    private static boolean buildCoda(FormantState st, FormantPatch patch, double k, double vz) {
        Consonant cr = consonant(patch.slots[2].cons);
        Segment seg = st.coda;
        if (cr == null) return false;
        int art = patch.codaArt;
        seg.row = cr;
        seg.slot.copyFrom(patch.slots[2]);
        seg.next = -1;
        seg.art = art == CODA_DARK ? ART_DARK : ART_NONE;
        seg.noiseAt = 0;
        seg.prevRow = null;
        seg.prevArt = ART_NONE;
        if (st.nucleus < 0) {
            double z0 = hasVowel(patch) ? vz : 0;
            st.codaZ0 = z0;
            codaTracks(cr, k, 1, z0, art, seg);
            return true;
        }
        Segment nuc = st.onset[st.nucleus];
        double v0 = nuc.av.p[nuc.av.n - 1], z0 = nuc.nz.p[nuc.nz.n - 1];
        double a0 = nuc.af.p[nuc.af.n - 1], h0 = nuc.ah.p[nuc.ah.n - 1];
        boolean hiss = a0 > 0 || h0 > 0;
        codaTracks(cr, k, v0, z0, art, seg);
        if (hiss) {
            afterRelease(seg, NUCLEUS_RELEASE * k, v0, a0, h0, z0);
            seg.noiseAt = NUCLEUS_RELEASE * k;
            seg.prevRow = nuc.row;
            seg.prevArt = nuc.art;
        }
        return true;
    }

    /** The coda of a syllable with nothing to say: over before it starts. */
    private static void silentCoda(Segment seg) {
        seg.clear();
        seg.next = -1;
        trPush(seg.w, 0, 1);
        trPush(seg.av, 0, 0);
        trPush(seg.ah, 0, 0);
        trPush(seg.af, 0, 0);
        trPush(seg.nz, 0, 0);
    }

    /** The note's glide time in ms (glideMsOf): what S $91xx gave it, or its record's. */
    private static double glideMsOf(FormantState st) {
        return (st.glideOv >= 0 ? st.glideOv : st.patch != null ? st.patch.glide : 0) * 2;
    }

    /** A note's amount as its trigger starts it: its record's bit (amtReset). */
    private static void amtReset(FormantState.Amount a, boolean on) {
        a.own = false; a.v = on ? 1 : 0; a.to = a.v; a.step = 0.0; a.left = 0.0; a.rejoin = false;
    }

    /** Set an amount gliding to `to` over the note's glide time — at once on
     *  the note's first row, or with a glide of 0 (amtGlideTo). */
    private static void amtGlideTo(FormantState st, FormantState.Amount a, double to) {
        double ms = glideMsOf(st);
        a.to = to;
        if (st.fresh || ms <= 0) { a.v = to; a.left = 0.0; return; }
        a.step = (to - a.v) / ms;
        a.left = ms;
    }

    /** An instrument change: the amount glides to the new record's, which the
     *  note then follows (amtRelatch). */
    private static void amtRelatch(FormantState st, FormantState.Amount a, boolean on) {
        amtGlideTo(st, a, on ? 1 : 0);
        if (a.left > 0) { a.own = true; a.rejoin = true; }
        else { a.own = false; a.rejoin = false; }
    }

    /** One control update of an amount: a glide's step (the last landing
     *  exactly), or the record's bit while the note has none of its own. */
    private static void amtAdvance(FormantState.Amount a, double dt, boolean on) {
        if (a.left > 0) {
            if (a.left <= dt) {
                a.v = a.to; a.left = 0.0;
                if (a.rejoin) { a.own = false; a.rejoin = false; }
            } else {
                a.v += a.step * dt;
                a.left -= dt;
            }
        } else if (!a.own) {
            a.v = on ? 1 : 0;
        }
    }

    /** Is the vowel the onset heads for nasalised, at all? (nasalOf) */
    private static boolean nasalOf(FormantState st, FormantPatch patch) {
        return st.nasAmt.own ? st.nasAmt.v > 0 : patch.nasal;
    }

    /** Note-on: build the syllable. `keep` says the syllable again for the
     *  SAME note (Q's retrigger, or an S $90kx on the trigger row that
     *  changed the nasality the onset heads for): what S $9xxx gave the note
     *  stays its own, its glides stopping where they stand. */
    static void noteOn(Engine e, FormantState st, FormantPatch patch, boolean keep) {
        boolean vowel = hasVowel(patch), coda = true, nasal;
        Consonant[] rows = new Consonant[2];
        int[] rowSlot = new int[2];
        int nrows = 0, next;
        NextStart nx = new NextStart();
        st.patch = patch;
        if (!keep) {
            amtReset(st.nasAmt, patch.nasal);
            amtReset(st.rhoAmt, patch.rhotic);
            st.glideOv = -1;
            st.codaCall = false;
        } else {
            st.nasAmt.left = 0.0; st.nasAmt.rejoin = false;
            st.rhoAmt.left = 0.0; st.rhoAmt.rejoin = false;
        }
        st.atVowel = false;
        for (int i = 0; i < 2; i++) {
            Consonant r = consonant(patch.slots[i].cons);
            if (r != null) { rows[nrows] = r; rowSlot[nrows] = i; nrows++; }
        }
        /* With no vowel, the last onset consonant is the nucleus — or, with
         * no onset, the coda's consonant, which then leaves no coda after it. */
        if (!vowel && nrows == 0) {
            Consonant r = consonant(patch.slots[2].cons);
            if (r != null) { rows[nrows] = r; rowSlot[nrows] = 2; nrows++; coda = false; }
        }
        /* The glide, if any; a syllable with no vowel has none. */
        st.sv = vowel ? patch.semivowel : 0;
        st.svT = 0.0;
        nasal = vowel && nasalOf(st, patch);
        if (st.sv != 0) {
            Consonant r = consonant(SEMIVOWELS[st.sv][3]);
            double k = slotScale(patch, 3);
            double dur = r.dur * k, tr = r.trans * k;
            st.svEnd = dur + tr;
            trReset(st.svW);
            trClose(trAt(trAt(trAt(st.svW, 0, 1), dur, 1), dur + tr, 0), st.svEnd, 0);
            trReset(st.svAv);
            trClose(trAt(trAt(trAt(st.svAv, 0, r.av), dur, r.av), dur + tr * 0.5, 1), st.svEnd, 1);
            nx.av = st.svAv.p[1]; nx.ah = 0; nx.af = 0; nx.nz = nasal ? 1 : 0;
        } else {
            nx.av = 1; nx.ah = 0; nx.af = 0; nx.nz = nasal ? 1 : 0;
        }
        /* Built back to front; the last consonant carries the secondary
         * articulation, and without a vowel it is the nucleus, and holds. */
        next = -1;
        for (int i = nrows - 1; i >= 0; i--) {
            Segment s = st.onset[i];
            int art = i == nrows - 1 ? patch.secondary : ART_NONE;
            double k = slotScale(patch, rowSlot[i]);
            s.row = rows[i];
            s.slot.copyFrom(patch.slots[rowSlot[i]]);
            if (!vowel && i == nrows - 1) nucleusTracks(rows[i], i == 0, k, art, s);
            else onsetTracks(rows[i], i == 0, nx, k, art, s);
            s.art = tractArt(art);
            s.hold = s.end;
            s.noiseAt = 0;
            s.prevRow = null;
            s.prevArt = ART_NONE;
            s.next = next;
            next = i;
            nextStartOf(s, nx);
        }
        st.onsetCount = nrows;
        st.seg = 0;
        st.nucleus = !vowel && nrows > 0 ? nrows - 1 : -1;
        st.held = false;
        st.hasCoda = coda && buildCoda(st, patch, slotScale(patch, 2), nasal ? st.nasAmt.v : 0);
        st.phase = nrows > 0 ? PHASE_ONSET : PHASE_VOWEL;
        st.t = 0.0;
        st.codaVeto = false;
        st.codaNoise = false;
        st.fresh = true;
        st.upd = imax(1, (int) js_round(e.rate / 1000.0));
        st.ctl = 0;
        st.normCount = 0;
        st.ownVowel = false; st.rejoin = false;
        st.vFront = patch.front; st.vOpen = patch.open; st.vRound = patch.round;
        st.glideLeft = 0.0;
        st.sgDue = true;
        st.av = 0.0; st.avStep = 0.0; st.ah = 0.0; st.ahStep = 0.0; st.af = 0.0; st.afStep = 0.0;
        st.nv = 1.0; st.nvStep = 0.0; st.nvGoal = 1.0;
        java.util.Arrays.fill(st.cy, 0);
        java.util.Arrays.fill(st.by, 0);
        st.nOn = false; st.nZ = 0.0;
        /* No vowel and no consonant: nothing to say. The syllable is over at once. */
        if (!vowel && nrows == 0) {
            st.phase = PHASE_CODA;
            silentCoda(st.coda);
            st.hasCoda = true;
            st.codaNoise = true;
        }
        if (st.phase == PHASE_ONSET) startNoise(e, st, st.onset[0]);
    }

    /* ── the glottal source as a chip channel ── */

    /** The instrument a formant instrument is sung from (formantChipSource),
     *  or null when it sings with its own sample: the header names none, or
     *  itself, or an instrument that is not a chip one. */
    static Inst chipSource(Engine e, Inst inst) {
        int i = inst.formant.srcInst;
        if (i < 1 || i > 1023 || i == inst.index) return null;
        Inst src = e.instruments[i];
        return src.isChip() ? src : null;
    }

    /** The rate a chip source plays the part of the sample's rate with: its
     *  own C2SPD, 0 reading as the tracker convention. */
    static int chipRate(Inst src) {
        return src.samplingRate == 0 ? OPL_C4_RATE : src.samplingRate;
    }

    private static int chipKindOf(Inst chip) { return chip.opl != null ? CHIP_OPL : CHIP_PSG; }

    private static void chipKeyOn(FormantState st, boolean carry) {
        if (st.srcKind == CHIP_OPL) Opl.keyOn(st.srcOpl, st.srcOpl.patch, carry);
        else Psg.keyOn(st.srcPsg, st.srcPsg.patch, carry);
    }

    /** Key the note's glottal source (formantKeySource): a chip channel
     *  playing `chip`'s patch from silence — or none, the sample being the
     *  source. A channel of the same kind is reused. */
    static void keySource(FormantState st, Inst chip) {
        if (chip == null) { st.srcKind = CHIP_NONE; return; }
        int kind = chipKindOf(chip);
        if (st.srcKind != kind) {
            st.srcKind = kind;
            if (kind == CHIP_OPL) st.srcOpl.init();
            else st.srcPsg.init();
        }
        if (kind == CHIP_OPL) Opl.keyOn(st.srcOpl, chip.opl, false);
        else Psg.keyOn(st.srcPsg, chip.psg, false);
        st.sgDue = true;
    }

    /** An instrument byte without a retrigger hands the note a new record's
     *  source (formantRelatchSource): a channel already sounding takes the
     *  new patch where it stands; otherwise as a fresh key-on. */
    static void relatchSource(FormantState st, Inst chip) {
        if (chip == null) { st.srcKind = CHIP_NONE; return; }
        if (st.srcKind != chipKindOf(chip)) { keySource(st, chip); return; }
        if (st.srcKind == CHIP_OPL) { st.srcOpl.patch = chip.opl; st.srcOpl.gen = -1; }
        else { st.srcPsg.patch = chip.psg; st.srcPsg.gen = -1; }
        st.sgDue = true;
    }

    static void restart(Engine e, FormantState st) {
        if (st.patch == null) return;
        double f = st.vFront, o = st.vOpen, r = st.vRound;
        boolean own = st.ownVowel;
        double sg = st.sg, sm = st.sm;
        boolean due = st.sgDue;
        noteOn(e, st, st.patch, true);
        st.vFront = f; st.vOpen = o; st.vRound = r; st.ownVowel = own;
        st.sg = sg; st.sm = sm; st.sgDue = due;
        /* A chip source is re-keyed as Q re-keys an OPL note: the attack from
         * where each envelope stands, the phase from zero (§5.5.2). */
        if (st.srcKind == CHIP_OPL ? st.srcOpl.patch != null : st.srcKind == CHIP_PSG && st.srcPsg.patch != null) {
            chipKeyOn(st, true);
        }
    }

    private static void glideTo(FormantState st, double open, double front, double round) {
        double ms = glideMsOf(st);
        if (st.fresh || ms <= 0) {
            st.vOpen = open; st.vFront = front; st.vRound = round;
            st.glideLeft = 0.0;
            return;
        }
        st.gOpen = (open - st.vOpen) / ms;
        st.gFront = (front - st.vFront) / ms;
        st.gRound = (round - st.vRound) / ms;
        st.tOpen = open; st.tFront = front; st.tRound = round;
        st.glideLeft = ms;
    }

    static void relatch(Engine e, FormantState st, FormantPatch patch) {
        st.patch = patch;
        st.sgDue = true;
        glideTo(st, patch.open, patch.front, patch.round);
        if (st.glideLeft > 0) { st.ownVowel = true; st.rejoin = true; }
        else { st.ownVowel = false; st.rejoin = false; }
        /* The nasalisation and the r-colouring go with the vowel (item 292). */
        amtRelatch(st, st.nasAmt, patch.nasal);
        amtRelatch(st, st.rhoAmt, patch.rhotic);
        if (st.phase == PHASE_ONSET || st.phase == PHASE_VOWEL) {
            st.hasCoda = buildCoda(st, patch, slotScale(patch, 2), st.nasAmt.v);
        }
    }

    /** A sampled note handed a formant instrument mid-note: the tract starts
     *  at the vowel — or a syllable with no vowel at its nucleus, holding —
     *  with the coda ready. */
    static void startAtVowel(Engine e, FormantState st, FormantPatch patch, boolean keep) {
        noteOn(e, st, patch, keep);
        st.atVowel = true;
        st.sv = 0;
        if (st.phase == PHASE_CODA) return;   /* nothing to say */
        if (st.nucleus >= 0) {
            if (st.nucleus != 0) st.onset[0].copyFrom(st.onset[st.nucleus]);
            st.onset[0].next = -1;
            st.nucleus = 0;
            st.onsetCount = 1;
            st.seg = 0;
            st.phase = PHASE_ONSET;
            st.t = st.onset[0].end;
            st.held = true;
            startNoise(e, st, st.onset[0]);
            return;
        }
        st.onsetCount = 0;
        st.seg = 0;
        st.phase = PHASE_VOWEL;
        st.nOn = false;
    }

    static void setVowel(FormantState st, int f, int g, int r) {
        st.ownVowel = true;
        st.rejoin = false;
        glideTo(st, (f & 15) * 17, (g & 15) * 17, (r & 15) * 17);
    }

    /** S $91xx: the note's glide time, re-timing what is under way. */
    private static void setGlide(FormantState st, int units) {
        double ms = units * 2;
        st.glideOv = units;
        if (st.glideLeft > 0) {
            if (ms <= 0) {
                st.vOpen = st.tOpen; st.vFront = st.tFront; st.vRound = st.tRound;
                st.glideLeft = 0.0;
                if (st.rejoin) { st.ownVowel = false; st.rejoin = false; }
            } else {
                st.gOpen = (st.tOpen - st.vOpen) / ms;
                st.gFront = (st.tFront - st.vFront) / ms;
                st.gRound = (st.tRound - st.vRound) / ms;
                st.glideLeft = ms;
            }
        }
        for (int i = 0; i < 2; i++) {
            FormantState.Amount a = i == 0 ? st.nasAmt : st.rhoAmt;
            if (a.left <= 0) continue;
            if (ms <= 0) {
                a.v = a.to; a.left = 0.0;
                if (a.rejoin) { a.own = false; a.rejoin = false; }
            } else {
                a.step = (a.to - a.v) / ms;
                a.left = ms;
            }
        }
    }

    /** S $9xxx, formant control (item 292), `arg` the low twelve bits:
     *  $0kx (x = 00nr) the vowel nasalised / r-coloured, k = 1 the coda now;
     *  $1xx the note's glide time in 2 ms units. Every other argument is
     *  RESERVED and does nothing. All of it is the NOTE's. */
    static void command(Engine e, FormantState st, int arg) {
        if (st.patch == null) return;
        int sel = (arg >> 8) & 0xf;
        if (sel == 1) { setGlide(st, arg & 0xff); return; }
        if (sel != 0 || (arg & 0xec) != 0) return;
        boolean was = nasalOf(st, st.patch);
        st.nasAmt.own = true; st.nasAmt.rejoin = false;
        amtGlideTo(st, st.nasAmt, (arg & 2) != 0 ? 1 : 0);
        st.rhoAmt.own = true; st.rhoAmt.rejoin = false;
        amtGlideTo(st, st.rhoAmt, (arg & 1) != 0 ? 1 : 0);
        /* On the trigger row the onset already heads for the vowel it will
         * meet: a changed nasality builds the syllable again. */
        if (st.fresh && nasalOf(st, st.patch) != was && hasVowel(st.patch)) {
            double f = st.vFront, o = st.vOpen, r = st.vRound;
            boolean own = st.ownVowel;
            if (st.atVowel) startAtVowel(e, st, st.patch, true);
            else noteOn(e, st, st.patch, true);
            st.vFront = f; st.vOpen = o; st.vRound = r; st.ownVowel = own;
        }
        if ((arg & 0x10) != 0) st.codaCall = true;
    }

    private static void startNoise(Engine e, FormantState st, Segment seg) {
        FormantPatch.Slot s = seg.slot;
        st.nOn = false;
        if (!consonantNeedsNoise(seg.row)) return;
        long span = (long) s.len - s.offset;
        long base = s.ptr + s.offset;
        if (base + span > SAMPLE_BIN_TOTAL) span = SAMPLE_BIN_TOTAL - base;
        st.nGen = span < 2 || s.rate <= 0;
        st.nOn = true;
        st.nBase = base;
        st.nSpan = (int) span;
        st.nPos = 0.0;
        st.nStep = (double) s.rate / e.rate;
        st.nSeed = NOISE_SEED;
        st.nTilt = s.tilt;
        st.nTiltC = fabs((double) s.tilt) / 128;
        st.nGain = Tables.attenGainOf(s.gain);
        st.nZ = 0.0;
    }

    private static double pcmU8(Engine e, long p) { return (e.poolAt(p) - 127.5) / 127.5; }

    private static double readNoise(Engine e, FormantState st) {
        double x;
        if (st.nGen) {
            int r = st.nSeed;
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5;
            st.nSeed = r;
            x = ((st.nSeed & 0xffffffffL) / 4294967296.0) * 2 - 1;
        } else {
            long i = (long) st.nPos;
            double fr = st.nPos - (double) i;
            double a = pcmU8(e, st.nBase + i);
            double b = pcmU8(e, st.nBase + (i + 1 < st.nSpan ? i + 1 : 0));
            x = a + (b - a) * fr;
            st.nPos += st.nStep;
            while (st.nPos >= st.nSpan) st.nPos -= st.nSpan;
        }
        if (st.nTilt > 0) {
            double y = x - st.nTiltC * st.nZ;
            st.nZ = x;
            x = y;
        } else if (st.nTilt < 0) {
            st.nZ = (1 - st.nTiltC) * x + st.nTiltC * st.nZ;
            x = st.nZ;
        }
        return x * st.nGain;
    }

    private static void setRes(Engine e, double[] a, int i, double f, double bw) {
        double T = 1.0 / e.rate;
        double r = js_exp(-PI * bw * T);
        double c = -(r * r);
        double b = 2 * r * js_cos(2 * PI * f * T);
        a[i] = 1 - b - c;
        a[i + 1] = b;
        a[i + 2] = c;
    }

    private static void setPeakRes(Engine e, double[] a, int i, double f, double bw) {
        setRes(e, a, i, f, bw);
        double b = a[i + 1], c = a[i + 2];
        double w = (2 * PI * f) / e.rate;
        double re = 1 - b * js_cos(w) - c * js_cos(2 * w);
        double im = b * js_sin(w) + c * js_sin(2 * w);
        a[i] = sqrt(re * re + im * im);
    }

    private static double bankNorm(FTables G, double[] bank, double[] g) {
        double p = 0.0;
        for (int i = 0; i < G.bankN; i++) {
            double re = g[5], im = 0.0;
            for (int j = 0; j < 5; j++) {
                if (g[j] == 0) continue;
                double A = bank[j * 3], b = bank[j * 3 + 1], c = bank[j * 3 + 2];
                double dr = 1 - b * G.bankC1[i] - c * G.bankC2[i];
                double di = b * G.bankS1[i] + c * G.bankS2[i];
                double k = (g[j] * A) / (dr * dr + di * di);
                re += k * dr;
                im -= k * di;
            }
            p += G.bankW[i] * (re * re + im * im);
        }
        return p > 0 ? sqrt(G.bankRef / p) : 0.0;
    }

    private static double cascadeNorm(Engine e, double[] co, double f0, double[] w, int wK) {
        FTables T = e.formantTables;
        double R = e.rate, p = 0.0, ref = 0.0;
        int[] j = T.jBox;
        j[0] = 0;
        if (!(f0 > 0)) return 1.0;
        int K = (int) dmax(1, dmin(wK, floor((0.45 * R) / f0)));
        double w0 = (2 * PI * f0) / R;
        double c1 = js_cos(w0), s1 = js_sin(w0);
        double ck = c1, sk = s1;
        for (int k = 1; k <= K; k++) {
            double c2 = ck * ck - sk * sk, s2 = 2 * sk * ck;
            double h = 1.0;
            for (int n = 0; n < 7; n++) {
                double A = co[n * 3], b = co[n * 3 + 1], c = co[n * 3 + 2];
                double re = 1 - b * ck - c * c2;
                double im = b * sk + c * s2;
                double gg = (A * A) / (re * re + im * im);
                h *= n == 1 ? 1 / gg : gg;
            }
            double lw = w[k] * js_pow(10, elwDb(T, k * f0, j) / 10);
            p += h * lw;
            ref += lw;
            double nc = ck * c1 - sk * s1;
            sk = sk * c1 + ck * s1;
            ck = nc;
        }
        double g = p > 0 ? sqrt(ref / p) : NORM_MAX;
        return dmin(dmax(g, NORM_MIN), NORM_MAX);
    }

    /** Mean and AC power of n bytes from p: {mean, ac}. */
    private static void acOf(Engine e, long p, long n, double[] out) {
        double sum = 0.0, sq = 0.0;
        for (long i = 0; i < n; i++) {
            double v = pcmU8(e, p + i);
            sum += v;
            sq += v * v;
        }
        double m = sum / n;
        out[0] = m;
        out[1] = sq / n - m * m;
    }

    private static final class Loudest { long at, n; double ac; }

    private static Loudest loudestWindow(Engine e, long p, long n) {
        Loudest L = new Loudest();
        double[] ma = new double[2];
        L.at = p; L.n = n > 0 ? n : 0; L.ac = 0.0;
        if (n < 2) return L;
        if (n <= SOURCE_WINDOW) { acOf(e, p, n, ma); L.ac = ma[1]; return L; }
        L.n = SOURCE_WINDOW;
        L.ac = -1.0;
        for (long o = 0; ; o += SOURCE_WINDOW >> 1) {
            long q = o < n - SOURCE_WINDOW ? o : n - SOURCE_WINDOW;
            acOf(e, p + q, SOURCE_WINDOW, ma);
            double ac = ma[1];
            if (ac > L.ac) { L.ac = ac; L.at = p + q; }
            if (q == n - SOURCE_WINDOW) break;
        }
        return L;
    }

    private static void sourceGain(Engine e, long p, long n, FormantState st) {
        double[] ma = new double[2];
        st.sm = 0.0;
        st.sg = 1.0;
        if (n < 2) return;
        acOf(e, p, n, ma);
        st.sm = ma[0];
        double ac = ma[1];
        if (!(ac > 1e-12)) return;
        st.sg = dmin(dmax(FORMANT_LEVEL / sqrt(ac), 1.0 / SOURCE_GAIN_MAX), SOURCE_GAIN_MAX);
    }

    private static void sawHarmonics(FormantState st) {
        for (int k = 1; k <= NORM_HARMONICS; k++) st.hw[k] = 1.0 / (k * k);
        st.hwK = NORM_HARMONICS;
    }

    private static void sourceHarmonics(Engine e, long p, long span, boolean whole, double P, FormantState st) {
        double[] w = st.hw, x = e.formantTables.dft;
        double mean = 0.0, sum = 0.0;
        int K = P >= 2 ? (int) dmin(NORM_HARMONICS, floor(P / 2)) : 0;
        long N = span < SOURCE_DFT_MAX ? span : SOURCE_DFT_MAX;
        FTables T = e.formantTables;
        int h = 0x811c9dc5;
        if (K < 1 || N < 2) { sawHarmonics(st); return; }
        boolean exact = whole && span <= SOURCE_DFT_MAX;
        double m = exact ? dmax(1, js_round(N / P)) : 0;
        /* A pure memo, keyed as the JS keys it, so a chord pays for one. */
        for (long i = 0; i < N; i++) h = (h ^ e.poolAt(p + i)) * 16777619;
        for (int j = 0; j < T.memoCount; j++) {
            if (T.memoP[j] == p && T.memoN[j] == N && T.memoM[j] == m && T.memoPP[j] == P && T.memoH[j] == h) {
                System.arraycopy(T.memoW[j], 0, w, 0, T.memoK[j] + 1);
                st.hwK = T.memoK[j];
                return;
            }
        }
        for (long i = 0; i < N; i++) mean += pcmU8(e, p + i);
        mean /= N;
        for (int i = 0; i < N; i++) {
            double v = pcmU8(e, p + i) - mean;
            x[i] = exact ? v : v * (0.5 - 0.5 * js_cos((2 * PI * i) / N));
        }
        for (int k = 1; k <= K; k++) {
            double om = exact ? (2 * PI * k * m) / N : (2 * PI * k) / P;
            double c1 = js_cos(om), s1 = js_sin(om);
            double c = 1.0, s = 0.0, re = 0.0, im = 0.0;
            for (int i = 0; i < N; i++) {
                re += x[i] * c;
                im -= x[i] * s;
                double nc = c * c1 - s * s1;
                s = s * c1 + c * s1;
                c = nc;
            }
            w[k] = re * re + im * im;
            sum += w[k];
        }
        if (!(sum > 0)) { sawHarmonics(st); return; }
        st.hwK = K;
        if (T.memoCount >= 32) T.memoCount = 0;
        int mi = T.memoCount;
        T.memoP[mi] = p;
        T.memoN[mi] = N;
        T.memoM[mi] = m;
        T.memoPP[mi] = P;
        T.memoH[mi] = h;
        T.memoK[mi] = K;
        System.arraycopy(w, 0, T.memoW[mi], 0, K + 1);
        T.memoCount++;
    }

    private static long cut(Voice voice, double x, long lim) {
        return (long) dmax(0, dmin(dmin(x, voice.activeSampleLength), (double) lim));
    }

    private static void measureSource(Engine e, Voice voice, FormantState st) {
        int mode = voice.activeLoopMode & 3;
        boolean looped = (mode == 1 || mode == 2) && voice.activeSampleLoopEnd > voice.activeSampleLoopStart;
        long base = voice.activeSamplePtr;
        long lim = SAMPLE_BIN_TOTAL - base;
        long p0 = voice.activeSamplePlayStart, at, n;
        boolean whole = false;
        if (st.srcKind != CHIP_NONE) { measureChipSource(e, voice, st); return; }
        if (looped) {
            long a = voice.activeSampleLoopStart;
            at = base + a;
            n = cut(voice, voice.activeSampleLoopEnd, lim) - a;
            whole = n == voice.activeSampleLoopEnd - a;
            long preN = cut(voice, a, lim) - p0;
            if (preN > SOURCE_MEASURE_MAX) preN = SOURCE_MEASURE_MAX;
            if (preN >= 2 && n >= 2) {
                double[] ma = new double[2];
                acOf(e, at, n < SOURCE_MEASURE_MAX ? n : SOURCE_MEASURE_MAX, ma);
                double loopAc = ma[1];
                Loudest w = loudestWindow(e, base + p0, preN);
                if (w.ac > loopAc) { at = w.at; n = w.n; whole = false; }
            }
        } else {
            long len = cut(voice, voice.activeSampleLength, lim) - p0;
            Loudest w = loudestWindow(e, base + p0, len < SOURCE_MEASURE_MAX ? len : SOURCE_MEASURE_MAX);
            at = w.at; n = w.n;
        }
        sourceGain(e, at, n < SOURCE_MEASURE_MAX ? n : SOURCE_MEASURE_MAX, st);
        sourceHarmonics(e, at, n, whole, voice.activeSamplingRate / LINEAR_FREQ_C4_HZ, st);
    }

    private static final int CHIP_MEASURE = 4096;

    /* measureSource for a source that is a chip channel (measureChipSource):
     * the note's patch run apart — from key-on, at the note's rate, at unity
     * volume, on a channel of its own — for CHIP_MEASURE output frames,
     * measured as a one-shot is. */
    private static void measureChipSource(Engine e, Voice voice, FormantState st) {
        FTables T = e.formantTables;
        Object patch = st.srcKind == CHIP_OPL ? st.srcOpl.patch : st.srcPsg.patch;
        int gen = st.srcKind == CHIP_OPL ? st.srcOpl.patch.gen : st.srcPsg.patch.gen;
        int rtGen = st.srcKind == CHIP_PSG ? st.srcPsg.patch.rtGen : 0;
        double pr = voice.snapPlaybackRate ? voice.playbackRate : voice.currentPlaybackRate;
        double[] x = T.chipBuf, w = st.hw, y = T.dft;
        double best = -1.0, mean, ac, P, sum = 0.0;
        int at = 0, K;
        /* A pure memo, keyed as the JS keys it: the patch, its decode (and a
         * PSG patch's overlay), the rate. */
        for (int j = 0; j < T.chipMemoCount; j++) {
            if (T.cmPatch[j] == patch && T.cmKind[j] == st.srcKind && T.cmGen[j] == gen &&
                T.cmRtGen[j] == rtGen && T.cmPr[j] == pr) {
                st.sm = T.cmSm[j];
                st.sg = T.cmSg[j];
                System.arraycopy(T.cmW[j], 0, w, 0, T.cmK[j] + 1);
                st.hwK = T.cmK[j];
                return;
            }
        }
        if (st.srcKind == CHIP_PSG) {
            /* A PSG pair keeps its internal status across key-ons, so it is
             * measured from rest, as a fresh lane meets it. */
            PsgState psg = new PsgState();
            Psg.keyOn(psg, st.srcPsg.patch, false);
            for (int i = 0; i < CHIP_MEASURE; i++) x[i] = Psg.render(e, psg, i, pr, false);
        } else {
            OplState opl = new OplState();
            Opl.keyOn(opl, st.srcOpl.patch, false);
            for (int i = 0; i < CHIP_MEASURE; i++) x[i] = Opl.render(e, opl, i, pr, 1.0, false);
        }
        /* The loudest window, every half window; the first of equals wins. */
        for (int o = 0; o + SOURCE_WINDOW <= CHIP_MEASURE; o += SOURCE_WINDOW >> 1) {
            double s1 = 0.0, sq = 0.0;
            for (int i = 0; i < SOURCE_WINDOW; i++) { double v = x[o + i]; s1 += v; sq += v * v; }
            double m = s1 / SOURCE_WINDOW;
            ac = sq / SOURCE_WINDOW - m * m;
            if (ac > best) { best = ac; at = o; }
        }
        {
            double s1 = 0.0, sq = 0.0;
            for (int i = 0; i < SOURCE_WINDOW; i++) { double v = x[at + i]; s1 += v; sq += v * v; }
            mean = s1 / SOURCE_WINDOW;
            ac = sq / SOURCE_WINDOW - mean * mean;
        }
        st.sm = mean;
        st.sg = ac > 1e-12 ? dmin(dmax(FORMANT_LEVEL / sqrt(ac), 1.0 / SOURCE_GAIN_MAX), SOURCE_GAIN_MAX) : 1.0;
        /* The period in output frames: the chip sounds pr × rate × 261.63 ÷ 8363 Hz. */
        P = pr > 0 ? OPL_C4_RATE / (pr * LINEAR_FREQ_C4_HZ) : 0;
        K = P >= 2 ? (int) dmin(NORM_HARMONICS, floor(P / 2)) : 0;
        if (K >= 1) {
            for (int i = 0; i < SOURCE_WINDOW; i++) {
                y[i] = (x[at + i] - mean) * (0.5 - 0.5 * js_cos((2 * PI * i) / SOURCE_WINDOW));
            }
            for (int k = 1; k <= K; k++) {
                double om = (2 * PI * k) / P;
                double c1 = js_cos(om), s1 = js_sin(om);
                double c = 1.0, s = 0.0, re = 0.0, im = 0.0;
                for (int i = 0; i < SOURCE_WINDOW; i++) {
                    re += y[i] * c;
                    im -= y[i] * s;
                    double nc = c * c1 - s * s1;
                    s = s * c1 + c * s1;
                    c = nc;
                }
                w[k] = re * re + im * im;
                sum += w[k];
            }
        }
        if (sum > 0) st.hwK = K;
        else sawHarmonics(st);
        if (T.chipMemoCount >= 32) T.chipMemoCount = 0;
        int ci = T.chipMemoCount;
        T.cmPatch[ci] = patch;
        T.cmKind[ci] = st.srcKind;
        T.cmGen[ci] = gen;
        T.cmRtGen[ci] = rtGen;
        T.cmPr[ci] = pr;
        T.cmSm[ci] = st.sm;
        T.cmSg[ci] = st.sg;
        T.cmK[ci] = st.hwK;
        System.arraycopy(w, 0, T.cmW[ci], 0, st.hwK + 1);
        T.chipMemoCount++;
    }

    private static double voiceF0(Engine e, Voice voice) {
        int rate = voice.activeSamplingRate;
        double pr = voice.snapPlaybackRate ? voice.playbackRate : voice.currentPlaybackRate;
        /* A chip source sounds what the chip makes of the rate, and the chip
         * reads every rate against the tracker convention's 8363. */
        if (voice.formant != null && voice.formant.srcKind != CHIP_NONE) {
            return (pr * e.rate * LINEAR_FREQ_C4_HZ) / OPL_C4_RATE;
        }
        return rate > 0 ? (pr * e.rate * LINEAR_FREQ_C4_HZ) / rate : 0.0;
    }

    private static void segAmps(Segment seg, double t, double[] out) {
        out[0] = trackAt(seg.av, t);
        out[1] = trackAt(seg.ah, t);
        out[2] = trackAt(seg.af, t);
    }

    /** A velar's F2 and F3 meet as close as the order rule lets two formants
     *  come: F3 ÷ F2 = 1.15, about the geometric mean of the two it meets. */
    private static final double PINCH = 1.0723805294763609;
    /** A nasalised vowel's F1 rises this much at a man's size (Klatt 1980 §V.B). */
    private static final double NASAL_F1 = 100;

    /* Configure the tract for this update (blendInto): the consonant's own
     * tract blended with the next sound's by w. */
    private static void blendInto(Engine e, Consonant row, double w, double s, double[] F, double[] B,
                                  double[] NF, double[] NB, int art) {
        double c2 = 0, c3 = 0;
        if (art != ART_NONE) {
            double[] T = e.formantTables.artF[art];
            c2 = T[1] * s; c3 = T[2] * s;
        } else if (row.dorsal) {
            double p = sqrt(NF[1] * NF[2]);
            c2 = p / PINCH; c3 = p * PINCH;
        }
        for (int n = 0; n < 3; n++) {
            double cf = n == 1 && c2 > 0 ? c2 : n == 2 && c3 > 0 ? c3 : row.F[n] > 0 ? row.F[n] * s : NF[n];
            double cb = row.B[n] > 0 ? row.B[n] * s : NB[n];
            F[n] = NF[n] + w * (cf - NF[n]);
            B[n] = NB[n] + w * (cb - NB[n]);
        }
        F[3] = NF[3]; F[4] = NF[4]; B[3] = NB[3]; B[4] = NB[4];
    }

    /** How front the vowel a consonant meets is, 0 (back) … 1 (front) (metFront). */
    private static double metFront(FormantState st) {
        double x = st.vFront;
        if (st.sv != 0) x += trackAt(st.svW, st.svT) * (SEMIVOWELS[st.sv][0] - x);
        return dmin(dmax(x / 255, 0), 1);
    }

    /** Where a consonant's nasal zero opens to (segZero). */
    private static double segZero(Consonant row, double w, double s, double met, double fnp, double ceil) {
        if (!(row.fnz > 0)) return met;
        double own = dmin(row.fnz * s, ceil);
        return met == fnp ? own : met + w * (own - met);
    }

    /** …the zero of the sound an onset consonant heads for (onsetMetZero). */
    private static double onsetMetZero(FormantState st, Segment seg, double s, double vz, double ceil) {
        for (int n = seg.next; n >= 0; n = st.onset[n].next) {
            if (st.onset[n].row.fnz > 0) return dmin(st.onset[n].row.fnz * s, ceil);
        }
        return vz;
    }

    /** A held coda's time (codaHeldTime): at its hold point and no further —
     *  a trill's taken back a cycle at a time, so it goes on trilling. */
    private static double codaHeldTime(Segment seg, double t) {
        if (t <= seg.hold) return t;
        if (seg.loop > 0) {
            while (t >= seg.hold) t -= seg.loop;
            return t;
        }
        return seg.hold;
    }

    /** One control update (≈ 1 ms): the sequencer, the vowel, the coefficients. */
    private static void control(Engine e, Voice voice, FormantState st) {
        int upd = st.upd;
        double dt = (upd * 1000.0) / e.rate;
        FormantPatch patch = st.patch;
        double f0, ref, s, nzAmt = 0.0, ceil, fnp, fnz, vz, zf, nvEnd, inv;
        double[] V = st.V, VB = st.VB, F = st.F, B = st.B, casc = st.casc;
        FTables FT = e.formantTables;
        double[] ampNow = FT.ampNow, ampNext = FT.ampNext, nextF = FT.nextF, nextB = FT.nextB;
        Segment seg = null;
        boolean holding, sung, nasalV, nasality, steady, codaHeld;
        double ra, na;
        st.ctl = upd;

        /* A fade before the coda has started takes the coda away for good. */
        if (voice.noteFading && st.phase != PHASE_CODA) st.codaVeto = true;
        /* A coda S $9010 called for HOLDS at its steady part while the key is
         * held; the key-off lets it go on through its release (item 292). */
        codaHeld = st.codaCall && !voice.keyOff;
        /* Walk past finished onset consonants into the vowel. A nucleus never
         * finishes: it holds, a trill's time wrapping back by its cycle. */
        while (st.phase == PHASE_ONSET && st.t >= st.onset[st.seg].end) {
            Segment cur = st.onset[st.seg];
            if (st.seg == st.nucleus) {
                st.held = true;
                if (cur.loop > 0) while (st.t >= cur.end) st.t -= cur.loop;
                break;
            }
            st.t -= cur.end;
            st.seg++;
            if (st.seg >= st.onsetCount) { st.phase = PHASE_VOWEL; st.nOn = false; }
            else startNoise(e, st, st.onset[st.seg]);
        }
        holding = st.phase == PHASE_ONSET && st.held;
        /* A key-off speaks the coda — and so does S $9010 (item 292), with the
         * key still held. */
        if ((st.phase == PHASE_VOWEL || holding) && (voice.keyOff || st.codaCall) && !st.codaVeto &&
            st.hasCoda) {
            /* Leaving a vowel nasalised other than as the coda was built for
             * (S $90kx, an instrument change gliding): the coda leaves it as
             * it is. */
            if (st.nucleus < 0 && st.nasAmt.v != st.codaZ0) {
                st.hasCoda = buildCoda(st, patch, slotScale(patch, 2), st.nasAmt.v);
            }
            st.phase = PHASE_CODA;
            st.t = 0.0;
            /* After a hissing nucleus the coda's own noise waits for the hiss to go. */
            st.codaNoise = st.coda.noiseAt <= 0;
            if (st.codaNoise) startNoise(e, st, st.coda);
        }
        /* (A coda that holds at its very end — silence — is over there as before.) */
        if (st.phase == PHASE_CODA && st.t >= st.coda.end && !(codaHeld && st.coda.hold < st.coda.end)) {
            st.phase = PHASE_DONE;
            st.av = 0; st.avStep = 0; st.ah = 0; st.ahStep = 0; st.af = 0; st.afStep = 0;
            st.nOn = false;
            Sampler.startRampOut(e, voice);
            return;
        }
        if (st.phase == PHASE_CODA && !st.codaNoise && st.t >= st.coda.noiseAt) {
            st.codaNoise = true;
            startNoise(e, st, st.coda);
        }

        if (st.glideLeft > 0) {
            if (st.glideLeft <= dt) {
                st.vOpen = st.tOpen; st.vFront = st.tFront; st.vRound = st.tRound;
                st.glideLeft = 0.0;
                if (st.rejoin) { st.ownVowel = false; st.rejoin = false; }
            } else {
                st.vOpen += st.gOpen * dt;
                st.vFront += st.gFront * dt;
                st.vRound += st.gRound * dt;
                st.glideLeft -= dt;
            }
        } else if (!st.ownVowel) {
            st.vOpen = patch.open; st.vFront = patch.front; st.vRound = patch.round;
        }
        amtAdvance(st.nasAmt, dt, patch.nasal);
        amtAdvance(st.rhoAmt, dt, patch.rhotic);
        /* A syllable with no vowel still has the tongue's rest — the vowel
         * bytes — for what its consonants take from "the vowel's". */
        sung = st.nucleus < 0;
        f0 = voiceF0(e, voice);
        /* note_hz takes an int in the C: the reference note's fraction is
         * truncated away there, and so here. */
        ref = noteHz((int) refNote(patch));
        /* R-coloured part of the way (S $90kx gliding): between the plain
         * vowel and the r-coloured one, in log frequency, as the glide G
         * blends. */
        ra = sung ? st.rhoAmt.v : 0;
        s = vowelAtPitch(e, st.vFront, st.vOpen, st.vRound, f0, ref, patch.track, V, VB, ra >= 1);
        if (ra > 0 && ra < 1) {
            double[] VR = st.VR, VRB = st.VRB;
            vowelAtPitch(e, st.vFront, st.vOpen, st.vRound, f0, ref, patch.track, VR, VRB, true);
            for (int n = 0; n < 5; n++) {
                V[n] *= js_pow(VR[n] / V[n], ra);
                VB[n] *= js_pow(VRB[n] / VB[n], ra);
            }
        }
        if (st.sv != 0) {
            double gw = trackAt(st.svW, st.svT);
            if (gw > 0) {
                double[] VG = st.VG, VGB = st.VGB;
                int[] sv = SEMIVOWELS[st.sv];
                vowelAtPitch(e, sv[0], sv[1], sv[2], f0, ref, patch.track, VG, VGB, false);
                for (int n = 0; n < 5; n++) {
                    V[n] *= js_pow(VG[n] / V[n], gw);
                    VB[n] *= js_pow(VGB[n] / VB[n], gw);
                }
            }
        }
        ceil = 0.45 * e.rate;
        fnp = dmin(FNP * s, ceil);
        /* Nasalised, as Klatt makes it: F1 a hundred hertz higher (at a man's
         * size), the order kept, and the nasal zero midway between that F1
         * and the nasal pole — part of the way while S $90kx glides it. */
        na = sung ? st.nasAmt.v : 0;
        nasalV = na > 0;
        vz = fnp;
        if (nasalV) {
            V[0] = dmin(V[0] + NASAL_F1 * s * na, ceil);
            VB[0] = dmax(V[0] / 10, 50);
            for (int n = 1; n < 5; n++) if (V[n] < V[n - 1] * 1.15) V[n] = dmin(V[n - 1] * 1.15, ceil);
            double mid = (V[0] + fnp) / 2;
            vz = na >= 1 ? mid : fnp + na * (mid - fnp);
        }
        nasality = nasalV || (st.nucleus >= 0 && st.onset[st.nucleus].row.fnz > 0);

        zf = fnp;
        if (st.phase == PHASE_ONSET) {
            seg = st.onset[st.seg];
            if (seg.next >= 0) {
                Segment nx = st.onset[seg.next];
                blendInto(e, nx.row, nx.w.p[1], s, nextF, nextB, V, VB, nx.art);
            } else {
                System.arraycopy(V, 0, nextF, 0, 5);
                System.arraycopy(VB, 0, nextB, 0, 5);
            }
            double w = trackAt(seg.w, st.t);
            blendInto(e, seg.row, w, s, F, B, nextF, nextB, seg.art);
            nzAmt = trackAt(seg.nz, st.t);
            if (nasality) zf = segZero(seg.row, w, s, onsetMetZero(st, seg, s, vz, ceil), fnp, ceil);
            segAmps(seg, st.t, ampNow);
            segAmps(seg, st.t + dt, ampNext);
        } else if (st.phase == PHASE_CODA) {
            Segment nuc = st.nucleus >= 0 ? st.onset[st.nucleus] : null;
            seg = st.coda;
            double w = trackAt(seg.w, st.t);
            /* From the vowel — or, with none, from the nucleus as it held. */
            if (nuc != null) {
                blendInto(e, nuc.row, 1, s, nextF, nextB, V, VB, nuc.art);
                blendInto(e, seg.row, w, s, F, B, nextF, nextB, seg.art);
            } else {
                blendInto(e, seg.row, w, s, F, B, V, VB, seg.art);
            }
            nzAmt = trackAt(seg.nz, st.t);
            if (nasality) {
                double met = nuc != null ? (nuc.row.fnz > 0 ? dmin(nuc.row.fnz * s, ceil) : fnp) : vz;
                zf = segZero(seg.row, w, s, met, fnp, ceil);
            }
            segAmps(seg, st.t, ampNow);
            segAmps(seg, codaHeld ? codaHeldTime(seg, st.t + dt) : st.t + dt, ampNext);
        } else {
            boolean gl = st.sv != 0;
            System.arraycopy(V, 0, F, 0, 5);
            System.arraycopy(VB, 0, B, 0, 5);
            ampNow[0] = gl ? trackAt(st.svAv, st.svT) : 1; ampNow[1] = 0; ampNow[2] = 0;
            ampNext[0] = gl ? trackAt(st.svAv, st.svT + dt) : 1; ampNext[1] = 0; ampNext[2] = 0;
            if (nasalV) { nzAmt = 1; zf = vz; }
        }
        /* Without nasality the zero opens only to a nasal consonant's own. */
        fnz = nasality ? zf : seg != null && seg.row.fnz > 0 ? dmin(seg.row.fnz * s, ceil) : fnp;
        setRes(e, casc, 0, fnp, BNP * s);
        setRes(e, casc, 3, fnp + nzAmt * (fnz - fnp), BNZ * s);
        for (int n = 0; n < 5; n++) setRes(e, casc, 6 + (4 - n) * 3, dmin(F[n], ceil), dmin(B[n], ceil));
        /* The parallel bank, while a consonant can be fricating — in a coda
         * after a hissing nucleus, the nucleus's bank until the coda's own
         * noise starts. */
        if (seg != null && st.nOn) {
            boolean prev = st.phase == PHASE_CODA && !st.codaNoise;
            Consonant row = prev && seg.prevRow != null ? seg.prevRow : seg.row;
            int art = prev ? seg.prevArt : seg.art;
            double[] bank = st.bank, g = st.bankGain;
            double[] a = row.bank;
            setPeakRes(e, bank, 0, dmin(F[1], ceil), dmin(B[1], ceil));
            setPeakRes(e, bank, 3, dmin(F[2], ceil), dmin(B[2], ceil));
            for (int j = 0; j < 3; j++) {
                setPeakRes(e, bank, 6 + j * 3, dmin(PAR_F[j] * s, ceil), dmin(PAR_B[j] * s, ceil));
            }
            /* A velar's bank follows the vowel it meets from front (the row's)
             * to back (`bankBack`), its own tongue's when it has a secondary
             * one. */
            double back = !row.hasBankBack ? 0 : art == ART_PALATAL ? 0 : art == ART_DARK ? 1 : 1 - metFront(st);
            for (int j = 0; j < 6; j++) {
                double gj = a[j] > 0 ? dbGain(a[j] - 60) : 0;
                if (row.hasBankBack) gj += back * ((row.bankBack[j] > 0 ? dbGain(row.bankBack[j] - 60) : 0) - gj);
                g[j] = j == 5 || (j & 1) == 0 ? gj : -gj;
            }
            st.nb = bankNorm(e.formantTables, bank, g);
        }

        /* The normaliser: in a consonant at every update; in the vowel, and a
         * nucleus holding, every NORM_EVERY updates. */
        steady = st.phase == PHASE_VOWEL || (st.phase == PHASE_ONSET && st.held);
        if (st.fresh || st.normCount <= 0 || !steady) {
            st.nvGoal = cascadeNorm(e, casc, f0, st.hw, st.hwK);
            st.normCount = NORM_EVERY;
        }
        st.normCount--;
        nvEnd = st.fresh || (!steady && st.nvGoal < st.nv)
            ? st.nvGoal : st.nv + (st.nvGoal - st.nv) * 0.25;
        if (st.fresh) st.nv = nvEnd;

        inv = 1.0 / upd;
        st.av = ampNow[0]; st.avStep = (ampNext[0] - ampNow[0]) * inv;
        st.ah = ampNow[1]; st.ahStep = (ampNext[1] - ampNow[1]) * inv;
        st.af = ampNow[2]; st.afStep = (ampNext[2] - ampNow[2]) * inv;
        st.nvStep = (nvEnd - st.nv) * inv;
        st.fresh = false;
        if (st.phase != PHASE_VOWEL) st.t += dt;
        if (st.phase == PHASE_CODA && codaHeld) st.t = codaHeldTime(st.coda, st.t);
        if (st.sv != 0 && st.phase != PHASE_ONSET) {
            st.svT += dt;
            if (st.svT >= st.svEnd) st.sv = 0;
        }
    }

    private static double tractRes(double[] co, int i, double[] cy, int j, double x) {
        double y = co[i] * x + co[i + 1] * cy[j] + co[i + 2] * cy[j + 1];
        cy[j + 1] = cy[j];
        cy[j] = y;
        return y;
    }

    /* A chip source's next sample (chipSource): the note's channel at output
     * frame `frame`, at the voice's rate and at unity volume, keyed off with
     * the voice. A channel that has rung out while the vowel — or a voiced
     * nucleus, holding — still sings ramps the note out, as a sample's end
     * does. */
    private static double chipSourceSample(Engine e, Voice voice, FormantState st, long frame) {
        double s;
        if (st.srcKind == CHIP_OPL) {
            s = Opl.render(e, st.srcOpl, frame, voice.currentPlaybackRate, 1.0, voice.keyOff);
        } else {
            s = Psg.render(e, st.srcPsg, frame, voice.currentPlaybackRate, voice.keyOff);
        }
        boolean sustaining = st.phase == PHASE_VOWEL || (st.phase == PHASE_ONSET && st.held && st.av > 0);
        if (sustaining) {
            boolean alive = st.srcKind == CHIP_OPL ? Opl.channelAlive(st.srcOpl) : Psg.channelAlive(st.srcPsg);
            if (!alive) Sampler.startRampOut(e, voice);
        }
        return s;
    }

    static double renderFormantVoice(Engine e, Voice voice, Inst inst, int interpMode, long frame) {
        FormantState st = voice.formant;
        double[] casc = st.casc, cy = st.cy;
        if (st.sgDue) { measureSource(e, voice, st); st.sgDue = false; }
        double g = st.srcKind != CHIP_NONE ? chipSourceSample(e, voice, st, frame)
                                           : Sampler.fetchTrackerSample(e, voice, inst, interpMode, 0);
        if (st.phase == PHASE_DONE) return 0.0;
        if (st.ctl <= 0) {
            control(e, voice, st);
            if (st.phase == PHASE_DONE) return 0.0;
        }
        st.ctl--;
        double n = st.nOn ? readNoise(e, st) : 0.0;
        double x = st.av * st.sg * (g - st.sm) + st.ah * n;
        x = tractRes(casc, 0, cy, 0, x);
        {
            double A = casc[3], b = casc[4], c = casc[5];
            double y = (x - b * cy[2] - c * cy[3]) / A;
            cy[3] = cy[2];
            cy[2] = x;
            x = y;
        }
        x = tractRes(casc, 6, cy, 4, x);
        x = tractRes(casc, 9, cy, 6, x);
        x = tractRes(casc, 12, cy, 8, x);
        x = tractRes(casc, 15, cy, 10, x);
        x = tractRes(casc, 18, cy, 12, x);
        double out = st.nv * x;
        if (st.nOn) {
            double[] bank = st.bank, by = st.by, gb = st.bankGain;
            double f = gb[5] * n;
            for (int j = 0; j < 5; j++) {
                int i = j * 3;
                double y = bank[i] * n + bank[i + 1] * by[j * 2] + bank[i + 2] * by[j * 2 + 1];
                by[j * 2 + 1] = by[j * 2];
                by[j * 2] = y;
                f += gb[j] * y;
            }
            out += st.af * st.nb * f;
        }
        st.av += st.avStep;
        st.ah += st.ahStep;
        st.af += st.afStep;
        st.nv += st.nvStep;
        return out;
    }
}
