/*
 * Sample-modification note effects (notefx 2 / 3) — port of samplemod.js
 * (taudplay-c samplemod.c). Non-destructive views over the sample pool,
 * applied when a byte is read.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class SampleMod {
    private SampleMod() {}

    static final int[] MOD_STEP = { 0, 0, 1, 2, 4, 8, 2, 8, 32, 128, 0, 0, 0, 0, 0, 0 };
    private static final int[] MOD_JUMP_SLICES = { 8, 16 };
    private static final double[] MOD_SCATTER_FRAC = { 1.0 / 512, 1.0 / 64, 1.0 / 8 };
    private static final int MOD_COMB_ODD_MAX = 0xd;

    static final class ExtOp {
        int kind = EXT_KIND_NOOP;
        double param;
    }

    static boolean isExtFunkOp(int code) { return code == 0x102 || (code >= 0x120 && code <= 0x12f); }
    static boolean isRolOp(int op) { return op >= MOD_ROL1 && op <= MOD_ROL8; }
    static boolean isSubOp(int op) { return op >= MOD_SUB2 && op <= MOD_SUB128; }
    static boolean isJumpOp(int op) { return op >= MOD_JUMP8 && op <= MOD_JUMP_ALL; }
    static boolean isRndOp(int op) { return op >= MOD_RND512 && op <= MOD_RND8; }

    /** Step period in TICKS for speed nibble $y: $F every tick … $1 every 15th, $0 frozen. */
    static int modStepPeriod(int y) { return (y & 0xf) == 0 ? 0 : 16 - (y & 0xf); }

    private static int uniformFloor(Engine e, double n) { return (int) Math.floor(e.random() * n); }

    static int jumpRot(Engine e, int op, int domainLen) {
        if (domainLen < 2) return 0;
        if (op == MOD_JUMP8 || op == MOD_JUMP16) {
            int slices = MOD_JUMP_SLICES[op - MOD_JUMP8];
            int slice = imax(1, (int) JsMath.round((double) domainLen / slices));
            return (imin(uniformFloor(e, slices), slices - 1) * slice) % domainLen;
        }
        if (op != MOD_JUMP_ALL) return 0;
        return imin(uniformFloor(e, domainLen), domainLen - 1);
    }

    static int scatterReach(int op, int domainLen) {
        int k = op - MOD_RND512;
        if (k < 0 || k > 2 || domainLen < 2) return 0;
        return imax(1, imin((int) JsMath.round(domainLen * MOD_SCATTER_FRAC[k]), domainLen));
    }

    /** uint32 seed. */
    static int scatterSeed(Engine e) { return JsMath.toInt32(e.random() * 4294967296.0); }

    private static int scatterHash(int seed, int i) {
        int h = (seed ^ 0x9e3779b9) * 0x85ebca6b;
        h ^= h >>> 13;
        h = (h ^ i) * 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    private static int scatterSource(int i, int domainStart, int domainLen, int reach, int seed) {
        if (reach <= 0 || domainLen < 2) return i;
        int span = 2 * reach + 1;
        int d = (int) (((scatterHash(seed, i) & 0xffffffffL) * (long) span) >>> 32) - reach;
        int k = (i - domainStart + d) % domainLen;
        if (k < 0) k += domainLen;
        return domainStart + k;
    }

    /** Decode the $se region byte into [from, to, combBits, combOdd]. */
    static int decodeSampleRegion(int se, double[] out) {
        int s = (se >> 4) & 0xf;
        int e = se & 0xf;
        if (s == 0xf && e != 0xf) { out[2] = e; out[3] = 0; return REGION_COMB; }
        if (s == 0xe && e <= MOD_COMB_ODD_MAX) { out[2] = e; out[3] = 1; return REGION_COMB; }
        out[2] = -1;
        out[3] = 0;
        if (se == 0x00) { out[0] = 0; out[1] = 1; return REGION_SET; }
        if (s <= e) { out[0] = s / 16.0; out[1] = (e + 1) / 16.0; return REGION_SET; }
        switch (se) {
        case 0x10: out[0] = 1.0 / 4; out[1] = 3.0 / 4; break;
        case 0x20: out[0] = 0;       out[1] = 2.0 / 3; break;
        case 0x21: out[0] = 1.0 / 3; out[1] = 1;       break;
        case 0x30: out[0] = 0;       out[1] = 1.0 / 3; break;
        case 0x31: out[0] = 1.0 / 3; out[1] = 2.0 / 3; break;
        case 0x32: out[0] = 2.0 / 3; out[1] = 1;       break;
        default: return REGION_NONE;
        }
        return REGION_SET;
    }

    static ModGeom resolveModGeom(ModGeom g, Inst inst, int loopStart, int loopEnd, int sampleLen) {
        boolean looped = loopEnd > loopStart;
        int base = looped ? loopStart : 0;
        int len = looped ? loopEnd - loopStart : sampleLen;
        if (g.epoch == inst.modEpoch && g.inst == inst && g.base == base && g.len == len) return g;
        g.epoch = inst.modEpoch;
        g.inst = inst;
        g.base = base;
        g.len = len;
        int es = base + (int) JsMath.round(len * inst.modFrom);
        int ee = base + (int) JsMath.round(len * inst.modTo);
        g.es = es;
        g.ee = ee;
        g.live = len >= 2 && ee - es >= 2;
        int bits = inst.modCombBits;
        g.combN = bits < 0 ? 0 : 2 << bits;
        g.combOdd = inst.modCombOdd;
        g.combScale = (double) g.combN / imax(ee - es, 1);
        g.ds = inst.modInvert ? base : es;
        g.dl = inst.modInvert ? len : ee - es;
        return g;
    }

    static boolean modTouches(ModGeom g, boolean invert, int i) {
        boolean inside = i >= g.es && i < g.ee;
        if (inside && g.combN > 0) {
            inside = (JsMath.toInt32((i - g.es) * g.combScale) & 1) == (g.combOdd ? 1 : 0);
        }
        return invert ? (!inside && i >= g.ds && i < g.ds + g.dl) : inside;
    }

    static int modAddress(ModGeom g, int i, int rot, int scatter, int seed) {
        int dl = g.dl;
        if (dl < 2) return i;
        if (scatter > 0) return scatterSource(i, g.ds, dl, scatter, seed);
        if (rot == 0) return i;
        int k = (i - g.ds + rot) % dl;
        if (k < 0) k += dl;
        return g.ds + k;
    }

    /* ── argument extension (item 162) ── */

    /** [lo, hi] of a static range, or null. */
    private static double[] fStaticRange(int f) {
        switch (f) {
        case 0x1: return new double[] { 0, 1.0 / 2 };
        case 0x2: return new double[] { 1.0 / 2, 1 };
        case 0x3: return new double[] { 0, 1.0 / 3 };
        case 0x7: return new double[] { 1.0 / 3, 2.0 / 3 };
        case 0x8: return new double[] { 2.0 / 3, 1 };
        case 0x4: return new double[] { 0, 1.0 / 4 };
        case 0x5: return new double[] { 1.0 / 2, 3.0 / 4 };
        case 0x6: return new double[] { 1.0 / 4, 3.0 / 4 };
        case 0x9: return new double[] { 3.0 / 4, 1 };
        default: return null;
        }
    }
    private static final double[][] F_STATIC_RANGE = new double[16][];
    static { for (int f = 0; f < 16; f++) F_STATIC_RANGE[f] = fStaticRange(f); }

    private static boolean fModTouches(int f, int i, int es, int ee, int stepIndex) {
        if (f == 0) return true;
        int len = ee - es;
        if (len < 1) return true;
        int rel = i - es;
        double[] r = f >= 0 && f < 16 ? F_STATIC_RANGE[f] : null;
        if (r != null) {
            int lo = es + (int) JsMath.round(len * r[0]);
            int hi = es + (int) JsMath.round(len * r[1]);
            return i >= lo && i < hi;
        }
        if (f >= 0xa && f <= 0xd) {
            int n = f >= 0xc ? 4 : 2;
            int chunk = imin((int) Math.floor((double) (rel * n) / len), n - 1);
            int startOdd = (f == 0xb || f == 0xd) ? 1 : 0;
            return (chunk & 1) == ((stepIndex + startOdd) & 1);
        }
        if (f == 0xe) return (rel & 7) < 4;
        if (f == 0xf) return (rel & 1) == 0;
        return true;
    }

    static boolean extModTouches(ModGeom g, boolean invert, int f, int stepIndex, int i) {
        return modTouches(g, invert, i) && fModTouches(f, i, g.es, g.ee, stepIndex);
    }

    private static final int[] EXT_JUMP_N = { 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 15, 16, 18, 21, 24, 32 };

    static double extJitterFrac(int x) { return JsMath.pow(2.0, (x & 0xf) - 15); }

    private static final double[] EXT_SCATTER_FRAC = {
        0, 1.0 / 16384, 1.0 / 8192, 1.0 / 4096, 1.0 / 2048, 1.0 / 1024, 1.0 / 512, 1.0 / 256,
        1.0 / 128, 1.0 / 64, 1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2, 1,
    };

    private static int revBits8(int b) {
        b = ((b & 0xf0) >> 4) | ((b & 0x0f) << 4);
        b = ((b & 0xcc) >> 2) | ((b & 0x33) << 2);
        b = ((b & 0xaa) >> 1) | ((b & 0x55) << 1);
        return b & 0xff;
    }
    private static int swapNibbles8(int b) { return (((b & 0xf0) >> 4) | ((b & 0x0f) << 4)) & 0xff; }
    private static int swapTwobits8(int b) { return (((b & 0xaa) >> 1) | ((b & 0x55) << 1)) & 0xff; }

    private static int bitperm(int idx, int b) {
        switch (idx & 7) {
        case 0: return b ^ 0xff;
        case 1: return revBits8(b);
        case 2: return swapNibbles8(b);
        case 3: return swapNibbles8(revBits8(b));
        case 4: return swapTwobits8(b);
        case 5: return swapTwobits8(revBits8(b));
        case 6: return swapTwobits8(swapNibbles8(b));
        default: return swapTwobits8(swapNibbles8(revBits8(b)));
        }
    }

    static ExtOp decodeExtOp(int code) {
        ExtOp r = new ExtOp();
        if (code == 0 || code == 0x100) return r;
        if (code == 0x101) { r.kind = EXT_KIND_INVERT; return r; }
        if (code == 0x102) { r.kind = EXT_KIND_FUNK; return r; }
        if (code == 0x103) { r.kind = EXT_KIND_XOR; r.param = 0xff; return r; }
        if (code == 0x104) { r.kind = EXT_KIND_MIRROR; return r; }
        if (code >= 0x110 && code <= 0x11f) { r.kind = EXT_KIND_INVERT_JIT; r.param = code & 0xf; return r; }
        if (code >= 0x120 && code <= 0x12f) { r.kind = EXT_KIND_FUNK_JIT; r.param = code & 0xf; return r; }
        if (code >= 0x130 && code <= 0x14f) { r.kind = EXT_KIND_JUMP_N; r.param = EXT_JUMP_N[code & 0xf]; return r; }
        if (code >= 0x150 && code <= 0x15f) { r.kind = EXT_KIND_JUMP_N_BOUNDED; r.param = EXT_JUMP_N[code & 0xf]; return r; }
        if (code == 0x160) { r.kind = EXT_KIND_SWAP; return r; }
        if (code >= 0x161 && code <= 0x16f) { r.kind = EXT_KIND_SCATTER; r.param = EXT_SCATTER_FRAC[code & 0xf]; return r; }
        if (code >= 0x200 && code <= 0x2ff) { r.kind = EXT_KIND_ROL; r.param = code & 0xff; return r; }
        if (code >= 0x300 && code <= 0x3ff) { r.kind = EXT_KIND_ROL; r.param = -(code & 0xff); return r; }
        if (code >= 0x400 && code <= 0x4ff) { r.kind = EXT_KIND_ROL; r.param = (code & 0xff) * 256; return r; }
        if (code >= 0x500 && code <= 0x5ff) { r.kind = EXT_KIND_ROL; r.param = -(code & 0xff) * 256; return r; }
        if (code >= 0x600 && code <= 0x6ff) { r.kind = EXT_KIND_SUB; r.param = code & 0xff; return r; }
        if (code >= 0x700 && code <= 0x7ff) { r.kind = EXT_KIND_SUB; r.param = -(code & 0xff); return r; }
        if (code >= 0x800 && code <= 0x8ff) { r.kind = EXT_KIND_XOR; r.param = code & 0xff; return r; }
        if (code >= 0x900 && code <= 0x90f) { r.kind = EXT_KIND_BITROT; r.param = code & 0xf; return r; }
        if (code >= 0x910 && code <= 0x91f) { r.kind = EXT_KIND_BITROT; r.param = -(code & 0xf); return r; }
        if (code >= 0x920 && code <= 0x927) { r.kind = EXT_KIND_BITPERM; r.param = code & 0x7; return r; }
        return r;
    }

    private static final int[] EXT_YK_COARSE = { 15, 16, 18, 20, 22, 24, 28, 32, 36, 40, 44, 48, 52, 56, 60, 64 };

    static double extYkPeriodTicks(int yk) {
        int y = (yk >> 4) & 0xf;
        int k = yk & 0xf;
        if (yk == 0) return 0;
        if (y == 0xf) return EXT_YK_COARSE[k];
        return y + k / 16.0;
    }

    static int modAddressExt(ModGeom g, int i, Inst inst) {
        if (inst.modExtSwapA >= 0) {
            if (i == inst.modExtSwapA) return inst.modExtSwapB;
            if (i == inst.modExtSwapB) return inst.modExtSwapA;
            return i;
        }
        if (inst.modExtMirror) {
            int dl = g.dl;
            if (dl < 2) return i;
            int k = (i - g.ds) % dl;
            if (k < 0) k += dl;
            return g.ds + (dl - 1 - k);
        }
        return modAddress(g, i, inst.modRot, inst.modScatter, inst.modSeed);
    }

    private static int bitRotate8(int b, int amt) {
        int n = ((amt % 8) + 8) % 8;
        if (n == 0) return b;
        return ((b << n) | (b >> (8 - n))) & 0xff;
    }

    static int applyExtLevel(Inst inst, int b) {
        if (inst.modSub != 0) b = (b - inst.modSub) & 0xff;
        if (inst.modXor != 0) b ^= inst.modXor;
        if (inst.modBitRot != 0) b = bitRotate8(b, inst.modBitRot);
        if (inst.modBitPermOn) b = bitperm(inst.modBitPermIdx, b);
        return b;
    }

    static int applyExtLevelPrev(Inst inst, int b) {
        if (inst.modPrevSub != 0) b = (b - inst.modPrevSub) & 0xff;
        if (inst.modPrevXor != 0) b ^= inst.modPrevXor;
        return b;
    }
}
