/*
 * OPL instruments — Metainstrument type 8 (item 209) — port of opl.js
 * (taudplay-c opl.c).
 *
 * A type-8 instrument is an ordinary instrument record whose sample is a
 * YM3812 / YMF262 channel: two operators, four, or one of the five
 * rhythm-mode drums (TAUD_FILE_FORMAT.md §7.7). Every sounding voice is its
 * own channel; the things a real chip shares between channels — the envelope
 * counter, the LFOs and the sample grid — come from one chip clock per
 * playhead (e.oplFrame), so every voice reads the same counter at the same
 * frame. Each voice runs the chip at 49716 Hz and is read back at the engine
 * rate by linear interpolation between its last two chip samples.
 *
 * Integer arithmetic follows the JS's int32 / uint32 semantics; the two
 * transcendental tables are constants, and `2 ** rational` (JsMath.pow) is
 * the only transcendental used.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Opl {
    private Opl() {}

    private static final int OPL_CLOCK_HZ = 3579545;
    private static final int OPL_CLOCKS_PER_SAMPLE = 72;
    private static final double OPL_RATE = OPL_RATE_HZ;

    private static final int OPL_B_KIND = 0;
    private static final int OPL_B_C0A = 1;
    private static final int OPL_B_C0B = 2;
    private static final int OPL_B_PARTNER_MULT = 3;
    private static final int OPL_B_PARTNER_DETUNE = 4;
    private static final int OPL_B_OPS = 6;
    private static final int OPL_OP_BYTES = 5;

    static final int OPL_KIND_2OP = 0;
    static final int OPL_KIND_4OP = 1;
    static final int OPL_KIND_BD = 2;
    static final int OPL_KIND_SD = 3;
    static final int OPL_KIND_TOM = 4;
    static final int OPL_KIND_TC = 5;
    static final int OPL_KIND_HH = 6;
    private static final int OPL_KIND_MASK = 0x07;
    private static final int OPL_FLAG_DEEP_TREMOLO = 0x08;
    private static final int OPL_FLAG_DEEP_VIBRATO = 0x10;
    private static final int OPL_FLAG_NTS = 0x20;
    private static final int OPL_FLAG_LOW_FNUM = 0x40;

    private static final int[] LOG_SIN = {
    2137, 1731, 1543, 1419, 1326, 1252, 1190, 1137, 1091, 1050, 1013, 979, 949, 920, 894, 869,
    846, 825, 804, 785, 767, 749, 732, 717, 701, 687, 672, 659, 646, 633, 621, 609,
    598, 587, 576, 566, 556, 546, 536, 527, 518, 509, 501, 492, 484, 476, 468, 461,
    453, 446, 439, 432, 425, 418, 411, 405, 399, 392, 386, 380, 375, 369, 363, 358,
    352, 347, 341, 336, 331, 326, 321, 316, 311, 307, 302, 297, 293, 289, 284, 280,
    276, 271, 267, 263, 259, 255, 251, 248, 244, 240, 236, 233, 229, 226, 222, 219,
    215, 212, 209, 205, 202, 199, 196, 193, 190, 187, 184, 181, 178, 175, 172, 169,
    167, 164, 161, 159, 156, 153, 151, 148, 146, 143, 141, 138, 136, 134, 131, 129,
    127, 125, 122, 120, 118, 116, 114, 112, 110, 108, 106, 104, 102, 100, 98, 96,
    94, 92, 91, 89, 87, 85, 83, 82, 80, 78, 77, 75, 74, 72, 70, 69,
    67, 66, 64, 63, 62, 60, 59, 57, 56, 55, 53, 52, 51, 49, 48, 47,
    46, 45, 43, 42, 41, 40, 39, 38, 37, 36, 35, 34, 33, 32, 31, 30,
    29, 28, 27, 26, 25, 24, 23, 23, 22, 21, 20, 20, 19, 18, 17, 17,
    16, 15, 15, 14, 13, 13, 12, 12, 11, 10, 10, 9, 9, 8, 8, 7,
    7, 7, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2,
    2, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0,
    };

    private static final int[] EXP_TABLE = {
    0, 3, 6, 8, 11, 14, 17, 20, 22, 25, 28, 31, 34, 37, 40, 42,
    45, 48, 51, 54, 57, 60, 63, 66, 69, 72, 75, 78, 81, 84, 87, 90,
    93, 96, 99, 102, 105, 108, 111, 114, 117, 120, 123, 126, 130, 133, 136, 139,
    142, 145, 148, 152, 155, 158, 161, 164, 168, 171, 174, 177, 181, 184, 187, 190,
    194, 197, 200, 204, 207, 210, 214, 217, 220, 224, 227, 231, 234, 237, 241, 244,
    248, 251, 255, 258, 262, 265, 268, 272, 276, 279, 283, 286, 290, 293, 297, 300,
    304, 308, 311, 315, 318, 322, 326, 329, 333, 337, 340, 344, 348, 352, 355, 359,
    363, 367, 370, 374, 378, 382, 385, 389, 393, 397, 401, 405, 409, 412, 416, 420,
    424, 428, 432, 436, 440, 444, 448, 452, 456, 460, 464, 468, 472, 476, 480, 484,
    488, 492, 496, 501, 505, 509, 513, 517, 521, 526, 530, 534, 538, 542, 547, 551,
    555, 560, 564, 568, 572, 577, 581, 585, 590, 594, 599, 603, 607, 612, 616, 621,
    625, 630, 634, 639, 643, 648, 652, 657, 661, 666, 670, 675, 680, 684, 689, 693,
    698, 703, 708, 712, 717, 722, 726, 731, 736, 741, 745, 750, 755, 760, 765, 770,
    774, 779, 784, 789, 794, 799, 804, 809, 814, 819, 824, 829, 834, 839, 844, 849,
    854, 859, 864, 869, 874, 880, 885, 890, 895, 900, 906, 911, 916, 921, 927, 932,
    937, 942, 948, 953, 959, 964, 969, 975, 980, 986, 991, 996, 1002, 1007, 1013, 1018,
    };

    /** Total attenuation (1/256-log2 units, possibly fractional) to amplitude. */
    static int expand(double att) {
        if (att >= 0x1800) return 0;
        int a = JsMath.toInt32(att);
        return ((EXP_TABLE[255 - (a & 0xff)] + 1024) << 1) >> (a >> 8);
    }

    private static final int OPL_RHYTHM_MIX = 2;
    private static final double OUTPUT_SCALE = 1.0 / 4096;

    private static final int ENV_TO_LOG = 8;
    private static final int TL_TO_LOG = 32;
    private static final int SILENCE = 0x1000;

    private static final int[] KSL_ROM = { 0, 24, 32, 37, 40, 43, 45, 47, 48, 50, 51, 52, 53, 54, 55, 56 };
    private static final int[] KSL_SHIFT = { -1, 1, 2, 0 };
    static final int[] OPL_MULTIPLE_X2 = { 1, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 20, 24, 24, 30, 30 };
    static final int[] OPL_SUSTAIN_LEVEL = { 0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 496 };
    private static final int[][] EG_DUTY = {
        { 1, 0, 1, 0, 1, 0, 1, 0 },
        { 1, 0, 1, 0, 1, 0, 1, 1 },
        { 1, 0, 1, 1, 1, 0, 1, 1 },
        { 1, 0, 1, 1, 1, 1, 1, 1 },
    };

    /* ── LFOs: free-running triangles of the chip clock ── */

    private static double triangle(int p, int period) {
        int half = period >> 1;
        return p < half ? (double) p / half : 2 - (double) p / half;
    }

    /** TREMOLO_TABLE[depth][p]: Math.round(1/0.1875) = 5, Math.round(4.8/0.1875) = 26.
     *  The PSG's oscillators read both tables too. */
    static double tremoloAt(boolean deep, int p) {
        return (deep ? 26 : 5) * ENV_TO_LOG * triangle(p, OPL_TREMOLO_PERIOD);
    }

    /** VIBRATO_TABLE[depth][p]. */
    static double vibratoAt(boolean deep, int p) {
        double cents = (deep ? 14 : 7) * (2 * triangle(p, OPL_VIBRATO_PERIOD) - 1);
        return cents == 0 ? 1 : JsMath.pow(2, cents / 1200);
    }

    /* ── waveforms: a 10-bit phase to a log attenuation, and a sign ── */

    /** logv << 1 | (1 when the sign is negative). */
    private static int waveform(int shape, int phase) {
        int quarter = phase & 0xff;
        int mirrored = (phase & 0x100) != 0 ? 255 - quarter : quarter;
        boolean negative = (phase & 0x200) != 0;
        switch (shape) {
        case 0:
            return (LOG_SIN[mirrored] << 1) | (negative ? 1 : 0);
        case 1:
            return (negative ? SILENCE : LOG_SIN[mirrored]) << 1;
        case 2:
            return LOG_SIN[mirrored] << 1;
        case 3:
            return ((phase & 0x100) != 0 ? SILENCE : LOG_SIN[quarter]) << 1;
        case 4: {
            if (negative) return SILENCE << 1;
            int p = (phase << 1) & 0x3ff;
            int q = p & 0xff;
            boolean neg = (p & 0x200) != 0;
            return (LOG_SIN[(p & 0x100) != 0 ? 255 - q : q] << 1) | (neg ? 1 : 0);
        }
        case 5: {
            if (negative) return SILENCE << 1;
            int p = (phase << 1) & 0x1ff;
            int q = p & 0xff;
            return LOG_SIN[(p & 0x100) != 0 ? 255 - q : q] << 1;
        }
        case 6:
            return negative ? 1 : 0;
        default: {
            int ramp = phase & 0x1ff;
            return (((negative ? 0x1ff - ramp : ramp) << 3) << 1) | (negative ? 1 : 0);
        }
        }
    }

    /* ── the patch ── */

    /* Which operators reach the output. Four operators: operator 4 always,
     * operator 1 when CNT A is set, and the other pair's last operator when
     * CNT B is — operator 2 when CNT A is clear, operator 3 when it is set
     * (the YMF262 data sheet's four connection figures, catalogue 4MF262A6,
     * p. 13). */
    private static int outputMask(int kind, int cntA, int cntB) {
        switch (kind) {
        case OPL_KIND_2OP: case OPL_KIND_BD: return cntA != 0 ? 3 : 2;
        case OPL_KIND_4OP: return 8 | (cntA != 0 ? 1 : 0) | (cntB != 0 ? (cntA != 0 ? 4 : 2) : 0);
        case OPL_KIND_SD: case OPL_KIND_TOM: case OPL_KIND_TC: case OPL_KIND_HH: return 1;
        default: return 0;
        }
    }

    /** Unpack the 54-byte block at bytes[at] into `into` (decodeOplPatch). */
    static void decodeOplPatch(byte[] bytes, int at, OplPatch into) {
        int kindByte = bytes[at + OPL_B_KIND] & 0xff, c0a = bytes[at + OPL_B_C0A] & 0xff, c0b = bytes[at + OPL_B_C0B] & 0xff;
        into.kind = kindByte & OPL_KIND_MASK;
        into.deepTremolo = (kindByte & OPL_FLAG_DEEP_TREMOLO) != 0;
        into.deepVibrato = (kindByte & OPL_FLAG_DEEP_VIBRATO) != 0;
        into.nts = (kindByte & OPL_FLAG_NTS) != 0;
        into.lowFnum = (kindByte & OPL_FLAG_LOW_FNUM) != 0;
        into.feedback = (c0a >> 1) & 7;
        into.cntA = c0a & 1;
        into.cntB = c0b & 1;
        into.partnerMult = bytes[at + OPL_B_PARTNER_MULT] & 0x0f;
        int pd = (bytes[at + OPL_B_PARTNER_DETUNE] & 0xff) | ((bytes[at + OPL_B_PARTNER_DETUNE + 1] & 0xff) << 8);
        into.partnerDetune = pd >= 0x8000 ? pd - 0x10000 : pd;
        for (int i = 0; i < 4; i++) {
            int o = at + OPL_B_OPS + i * OPL_OP_BYTES;
            OplPatch.Operator op = into.ops[i];
            int r20 = bytes[o] & 0xff, r40 = bytes[o + 1] & 0xff, r60 = bytes[o + 2] & 0xff;
            int r80 = bytes[o + 3] & 0xff, rE0 = bytes[o + 4] & 0xff;
            op.am = (r20 >> 7) & 1; op.vib = (r20 >> 6) & 1; op.eg = (r20 >> 5) & 1;
            op.ksr = (r20 >> 4) & 1; op.mult = r20 & 0x0f;
            op.ksl = (r40 >> 6) & 3; op.tl = r40 & 0x3f;
            op.ar = (r60 >> 4) & 0x0f; op.dr = r60 & 0x0f;
            op.sl = OPL_SUSTAIN_LEVEL[(r80 >> 4) & 0x0f]; op.rr = r80 & 0x0f;
            op.ws = rE0 & 7;
        }
        into.outputs = outputMask(into.kind, into.cntA, into.cntB);
        into.gen++;
    }

    /** The block and F-number a driver would write for `hz`, as block << 10 | fnum. */
    private static int blockFnum(double hz, boolean lowFnum) {
        double x = (hz * 1048576) / OPL_RATE;
        int ceiling = lowFnum ? 686 : 1024;
        for (int b = 0; b < 8; b++) {
            double f = JsMath.round(x / (1 << b));
            if (f < ceiling) return (b << 10) | (int) f;
        }
        return (7 << 10) | 1023;
    }

    /* ── the voice ── */

    /** The chip-sample index output frame `frame` falls on; the fraction past
     *  it goes into frac[0]. In the JS's double arithmetic (num/den exactly
     *  while num < 2^53). */
    static long chipIndex(Engine e, long frame, double[] frac) {
        double num = (double) frame * OPL_CLOCK_HZ;
        double den = (double) OPL_CLOCKS_PER_SAMPLE * e.rate;
        double k = Math.floor(num / den);
        double rem = num - k * den;
        if (rem < 0) { k--; rem += den; }
        else if (rem >= den) { k++; rem -= den; }
        frac[0] = rem / den;
        return (long) k;
    }

    private static int noiseSeed(long k) {
        int s = (((int) k * 0x9e3779b1) >>> 9) & 0x7fffff;
        return s == 0 ? 1 : s;
    }

    static void stateInit(OplState st) { st.init(); }

    /** The key scaling an operator at `hz` takes, as retune() works it out for
     *  a melodic channel (NTS 0, the ordinary F-number ceiling): out[0] the
     *  rate offset KSR adds to every envelope rate, out[1] the key-scale level
     *  in envelope units — for the PSG, which borrows the chip's envelope. */
    static void keyScaling(double hz, int ksr, int ksl, int[] out) {
        int bf = blockFnum(hz, false);
        int block = bf >> 10, fnum = bf & 1023;
        int ksv = (block << 1) | ((fnum >> 9) & 1);
        int kslBase = KSL_ROM[(fnum >> 6) & 15] - 8 * (7 - block);
        if (kslBase < 0) kslBase = 0;
        out[0] = ksr != 0 ? ksv : ksv >> 2;
        int shift = KSL_SHIFT[ksl & 3];
        out[1] = shift < 0 ? 0 : (kslBase << 2) >> shift;
    }

    static void keyOn(OplState st, OplPatch patch, boolean carry) {
        st.patch = patch;
        st.gen = -1;
        st.rate = -1;
        if (!carry) {
            for (int i = 0; i < 4; i++) { st.env[i] = OPL_ENV_MAX; st.out[i] = 0; st.prev[i] = 0; }
            st.cur = 0;
            st.last = 0;
            st.kValid = false;
        }
        for (int i = 0; i < 4; i++) {
            st.state[i] = EG_ATTACK;
            st.phase[i] = 0;
        }
        st.hhPhase = 0;
        st.tcPhase = 0;
        st.keyed = true;
    }

    static void keyOff(OplState st) {
        for (int i = 0; i < 4; i++) if (st.state[i] != EG_OFF) st.state[i] = EG_RELEASE;
        st.keyed = false;
    }

    static void reattack(OplState st) {
        for (int i = 0; i < 4; i++) st.state[i] = EG_ATTACK;
        st.keyed = true;
    }

    private static final double HZ_PER_SPS = LINEAR_FREQ_C4_HZ / OPL_C4_RATE;

    private static void retune(Engine e, OplState st, double rate) {
        OplPatch patch = st.patch;
        double hz = rate * e.rate * HZ_PER_SPS;
        int bf = blockFnum(hz, patch.lowFnum);
        int block = bf >> 10, fnum = bf & 1023;
        int ksv = (block << 1) | ((fnum >> (patch.nts ? 8 : 9)) & 1);
        int kslBase = KSL_ROM[(fnum >> 6) & 15] - 8 * (7 - block);
        if (kslBase < 0) kslBase = 0;
        for (int i = 0; i < 4; i++) {
            OplPatch.Operator op = patch.ops[i];
            int shift = KSL_SHIFT[op.ksl];
            st.inc[i] = ((fnum * OPL_MULTIPLE_X2[op.mult]) << block) >> 1;
            st.ksrOffset[i] = op.ksr != 0 ? ksv : ksv >> 2;
            st.kslAtt[i] = shift < 0 ? 0 : (kslBase << 2) >> shift;
        }
        int kind = patch.kind;
        if (kind == OPL_KIND_SD || kind == OPL_KIND_HH || kind == OPL_KIND_TC) {
            int pbf = blockFnum(hz * JsMath.pow(2, patch.partnerDetune / 4096.0), patch.lowFnum);
            int pInc = (((pbf & 1023) * OPL_MULTIPLE_X2[patch.partnerMult]) << (pbf >> 10)) >> 1;
            int own = st.inc[0];
            if (kind == OPL_KIND_SD) {
                st.hhInc = ((fnum * OPL_MULTIPLE_X2[patch.partnerMult]) << block) >> 1;
                st.tcInc = 0;
            } else if (kind == OPL_KIND_HH) {
                st.hhInc = own;
                st.tcInc = pInc;
            } else {
                st.tcInc = own;
                st.hhInc = pInc;
            }
        }
        st.rate = rate;
        st.gen = patch.gen;
    }

    /** One envelope tick for operator i (oplAdvanceEnvelope): `stateArr[i]`
     *  and `envArr[i]` are its entries, `eg` is the chip clock as the JS's
     *  int32 bit operations see it. The PSG's oscillators run on it too. */
    static void advanceEnvelope(int[] stateArr, int[] envArr, int i, int ksrOffset, OplPatch.Operator op, int eg) {
        int state = stateArr[i];
        if (state == EG_OFF) return;
        int rateParam = state == EG_ATTACK ? op.ar
            : state == EG_DECAY ? op.dr
            : state == EG_SUSTAIN ? (op.eg != 0 ? 0 : op.rr)
            : op.rr;
        if (rateParam == 0) return;
        int rate = rateParam * 4 + ksrOffset;
        if (rate > 60) rate = 60;
        int shift = 12 - (rate >> 2);
        int steps;
        if (shift >= 0) {
            if ((eg & ((1 << shift) - 1)) != 0) return;
            steps = EG_DUTY[rate & 3][(eg >> shift) & 7];
        } else {
            steps = EG_DUTY[rate & 3][eg & 7] << -shift;
        }
        for (int n = 0; n < steps; n++) {
            int env = envArr[i];
            switch (stateArr[i]) {
            case EG_ATTACK:
                env -= (env >> 3) + 1;
                if (env <= 0) { env = 0; stateArr[i] = EG_DECAY; }
                break;
            case EG_DECAY:
                env += 1;
                if (env >= op.sl) { env = op.sl; stateArr[i] = EG_SUSTAIN; }
                break;
            default:
                env += 1;
                if (env >= OPL_ENV_MAX) { env = OPL_ENV_MAX; stateArr[i] = EG_OFF; }
                break;
            }
            envArr[i] = env < 0 ? 0 : env > OPL_ENV_MAX ? OPL_ENV_MAX : env;
            if (stateArr[i] != state) break;
        }
    }

    private static void advanceEnvelope(OplState st, int i, OplPatch.Operator op, int eg) {
        advanceEnvelope(st.state, st.env, i, st.ksrOffset[i], op, eg);
    }

    /* `mod` is the phase modulation in phase steps: an integer, but a double,
     * since an FM rack's offset (st.pm) can carry it anywhere (`& 0x3ff` of
     * the JS's ToInt32 of the sum). */
    private static int operate(OplState st, int i, OplPatch.Operator op, double mod, double extra, double vibrato) {
        double att = (double) ((st.env[i] + st.kslAtt[i]) * ENV_TO_LOG + op.tl * TL_TO_LOG) + extra;
        int inc = st.inc[i];
        if (op.vib != 0 && vibrato != 1) inc = (int) JsMath.round(inc * vibrato);
        st.phase[i] = st.phase[i] + inc;
        st.prev[i] = st.out[i];
        if (st.state[i] == EG_OFF) { st.out[i] = 0; return 0; }
        int w = waveform(op.ws, JsMath.toInt32((double) (st.phase[i] >>> 10) + mod) & 0x3ff);
        int logv = w >> 1;
        if (logv == SILENCE) { st.out[i] = 0; return 0; }
        int v = expand(logv + att) * ((w & 1) != 0 ? -1 : 1);
        st.out[i] = v;
        return v;
    }

    private static int rhythmOperator(OplState st, int i, OplPatch.Operator op, int phase, double extra) {
        st.prev[i] = st.out[i];
        if (st.state[i] == EG_OFF) { st.out[i] = 0; return 0; }
        int w = waveform(op.ws, phase);
        int logv = w >> 1;
        if (logv == SILENCE) { st.out[i] = 0; return 0; }
        double att = (double) ((st.env[i] + st.kslAtt[i]) * ENV_TO_LOG + op.tl * TL_TO_LOG) + extra;
        int v = expand(att + logv) * ((w & 1) != 0 ? -1 : 1);
        st.out[i] = v;
        return v;
    }

    private static int feedbackOf(OplState st, int fb) {
        if (fb == 0) return 0;
        return JsMath.toInt32((double) (st.out[0] + st.prev[0]) / 2 / (1 << (8 - fb)));
    }

    /* The chip's next sample for this channel at chip clock k (oplChipStep).
     * The four four-operator connections are the YMF262 data sheet's
     * (catalogue 4MF262A6, p. 13): CNT A 0, CNT B 1 is OP1 -> OP2 plus
     * OP3 -> OP4. */
    private static int chipStep(OplState st, long k) {
        OplPatch patch = st.patch;
        OplPatch.Operator[] ops = patch.ops;
        int eg = (int) k;
        double trem = tremoloAt(patch.deepTremolo, (int) (k % OPL_TREMOLO_PERIOD));
        /* The JS reads its vibrato table every sample; only an operator with
         * its VIB bit set uses the value, so it is evaluated only for one. */
        double vibrato = 1;
        int outputs = patch.outputs;
        double va = st.volAtt;
        double x0 = (ops[0].am != 0 ? trem : 0) + ((outputs & 1) != 0 ? va : 0);
        /* Outside phase modulation reaches the operators the channel OUTPUTS,
         * the way the chip's own modulator reaches its carrier; 0 changes
         * nothing. */
        double pm = st.pm;
        double p0 = (outputs & 1) != 0 ? pm : 0;
        if (ops[0].vib != 0 || ops[1].vib != 0 || ops[2].vib != 0 || ops[3].vib != 0) {
            vibrato = vibratoAt(patch.deepVibrato, (int) (k % OPL_VIBRATO_PERIOD));
        }
        switch (patch.kind) {
        case OPL_KIND_2OP: case OPL_KIND_BD: {
            advanceEnvelope(st, 0, ops[0], eg);
            advanceEnvelope(st, 1, ops[1], eg);
            double x1 = (ops[1].am != 0 ? trem : 0) + va;
            int m = operate(st, 0, ops[0], feedbackOf(st, patch.feedback) + p0, x0, vibrato);
            int v = patch.cntA != 0 ? m + operate(st, 1, ops[1], pm, x1, vibrato)
                                    : operate(st, 1, ops[1], m + pm, x1, vibrato);
            return patch.kind == OPL_KIND_BD ? OPL_RHYTHM_MIX * v : v;
        }
        case OPL_KIND_4OP: {
            for (int i = 0; i < 4; i++) advanceEnvelope(st, i, ops[i], eg);
            double x1 = (ops[1].am != 0 ? trem : 0) + ((outputs & 2) != 0 ? va : 0);
            double x2 = (ops[2].am != 0 ? trem : 0) + ((outputs & 4) != 0 ? va : 0);
            double x3 = (ops[3].am != 0 ? trem : 0) + va;
            double p1 = (outputs & 2) != 0 ? pm : 0;
            double p2 = (outputs & 4) != 0 ? pm : 0;
            int o1 = operate(st, 0, ops[0], feedbackOf(st, patch.feedback) + p0, x0, vibrato);
            int o2, o3;
            if (patch.cntA == 0) {
                o2 = operate(st, 1, ops[1], o1 + p1, x1, vibrato);
                if (patch.cntB != 0) {
                    /* Two pairs side by side: 1 -> 2, and 3 -> 4. */
                    o3 = operate(st, 2, ops[2], 0, x2, vibrato);
                    return o2 + operate(st, 3, ops[3], o3 + pm, x3, vibrato);
                }
                o3 = operate(st, 2, ops[2], o2, x2, vibrato);
                return operate(st, 3, ops[3], o3 + pm, x3, vibrato);
            }
            o2 = operate(st, 1, ops[1], 0, x1, vibrato);
            o3 = operate(st, 2, ops[2], o2 + p2, x2, vibrato);
            if (patch.cntB != 0) {
                int sum = o1 + o3;
                return sum + operate(st, 3, ops[3], pm, x3, vibrato);
            }
            return o1 + operate(st, 3, ops[3], o3 + pm, x3, vibrato);
        }
        case OPL_KIND_TOM:
            advanceEnvelope(st, 0, ops[0], eg);
            return OPL_RHYTHM_MIX * operate(st, 0, ops[0], pm, x0, vibrato);
        case OPL_KIND_SD: case OPL_KIND_TC: case OPL_KIND_HH: {
            advanceEnvelope(st, 0, ops[0], eg);
            st.hhPhase = st.hhPhase + st.hhInc;
            st.tcPhase = st.tcPhase + st.tcInc;
            int hp = (st.hhPhase >>> 10) & 0x3ff;
            int tp = (st.tcPhase >>> 10) & 0x3ff;
            int noise = st.noise & 1;
            st.noise = (st.noise >>> 1) | (((st.noise ^ (st.noise >>> 14)) & 1) << 22);
            int phase;
            if (patch.kind == OPL_KIND_SD) {
                phase = (((hp >> 8) & 1) != 0 ? 0x200 : 0x100) ^ (noise << 8);
            } else {
                int x = (((hp >> 2) ^ (hp >> 7)) | (hp >> 3) | ((tp >> 5) ^ (tp >> 3))) & 1;
                phase = patch.kind == OPL_KIND_HH ? (x << 9) | ((x ^ noise) != 0 ? 0x0d0 : 0x034)
                                                  : (x << 9) | 0x100;
            }
            return OPL_RHYTHM_MIX * rhythmOperator(st, 0, ops[0], JsMath.toInt32(phase + pm) & 0x3ff, x0);
        }
        default:
            return 0;
        }
    }

    static boolean channelAlive(OplState st) {
        OplPatch patch = st.patch;
        for (int i = 0; i < 4; i++) {
            if ((patch.outputs & (1 << i)) == 0) continue;
            int s = st.state[i];
            if (s == EG_OFF) continue;
            if (st.env[i] >= OPL_ENV_MAX && (s != EG_ATTACK || patch.ops[i].ar == 0)) continue;
            return true;
        }
        return false;
    }

    /** The note volume as an attenuation: the nearest 1/256-octave step. */
    private static int volumeAttenuation(double vol) {
        int lo = 0, hi = 0x1800;
        if (vol >= 1) return 0;
        if (!(vol > 0)) return 0x1800;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (vol >= JsMath.pow(2, -(double) (2 * mid + 1) / 512)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** The channel `st` at output frame `frame`, played at playback rate
     *  `rate` with note volume `vol`, keyed off once `keyOff` is (oplRender):
     *  the voice's render but its deactivation, so that something other than
     *  the voice it sounds on can read a chip channel — an FM rack reading it
     *  as an operator, a formant instrument as its glottal source. */
    static double render(Engine e, OplState st, long frame, double rate, double vol, boolean keyOff) {
        OplPatch patch = st.patch;
        if (patch == null) return 0;
        if (keyOff && st.keyed) keyOff(st);
        if (rate != st.rate || patch.gen != st.gen) retune(e, st, rate);
        if (vol != st.vol) { st.vol = vol; st.volAtt = volumeAttenuation(vol); }
        double[] frac = e.fracBox;
        long k = chipIndex(e, frame, frac);
        if (!st.kValid) {
            st.k = k - 1;
            st.kValid = true;
            st.noise = noiseSeed(k);
        } else if (k - st.k > 8) {
            st.k = k - 1;
        }
        while (st.k < k) {
            st.k++;
            st.last = st.cur;
            st.cur = chipStep(st, st.k);
        }
        return (st.last + (st.cur - st.last) * frac[0]) * OUTPUT_SCALE;
    }

    static double renderOplVoice(Engine e, Voice voice, long frame) {
        OplState st = voice.opl;
        if (st.patch == null) return 0;
        double s = render(e, st, frame, voice.currentPlaybackRate, voice.currentMixVolume, voice.keyOff);
        if (!channelAlive(st)) voice.active = false;
        return s;
    }
}
