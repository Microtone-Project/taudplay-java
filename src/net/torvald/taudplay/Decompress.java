/*
 * The section-codec dispatcher of compress.js (TSVM sniffs the magic, so a
 * section may be gzip or zstd), a gzip reader on java.util.zip's inflater,
 * and a small, bounds-checked Zstandard decoder (RFC 8878) — frames,
 * raw/RLE/compressed blocks, Huffman literals, FSE sequences, repeat offsets
 * and skippable frames, no dictionaries — ported from taudplay-c zstd.c. The
 * JavaScript library vendors fzstd for the same job.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class Decompress {
    private Decompress() {}

    /** A decoded buffer: `data` holds at least `len` bytes (zero padded to
     *  the expected size when one was given). */
    static final class Result {
        byte[] data;
        int len;
    }

    /** Decode `len` bytes at src[off]: gzip or zstd by their magic; null when
     *  neither, or when the stream does not decode. With `expected` > 0 the
     *  buffer is at least that long, zero padded. */
    static Result decompress(byte[] src, int off, int len, int expected) {
        if (len >= 3 && (src[off] & 0xff) == 0x1f && (src[off + 1] & 0xff) == 0x8b && (src[off + 2] & 0xff) == 0x08) {
            return gunzip(src, off, len, expected);
        }
        if (len >= 4 && (src[off] & 0xff) == 0x28 && (src[off + 1] & 0xff) == 0xb5 &&
            (src[off + 2] & 0xff) == 0x2f && (src[off + 3] & 0xff) == 0xfd) {
            return zstd(src, off, len, expected);
        }
        return null;
    }

    /* ── gzip ── */

    static Result gunzip(byte[] src, int off, int len, int expected) {
        int p = 10;
        if (len < 18 || (src[off] & 0xff) != 0x1f || (src[off + 1] & 0xff) != 0x8b || src[off + 2] != 8) return null;
        int flg = src[off + 3] & 0xff;
        if ((flg & 4) != 0) {
            if (p + 2 > len) return null;
            p += 2 + ((src[off + p] & 0xff) | ((src[off + p + 1] & 0xff) << 8));
        }
        if ((flg & 8) != 0) { while (p < len && src[off + p] != 0) p++; p++; }
        if ((flg & 16) != 0) { while (p < len && src[off + p] != 0) p++; p++; }
        if ((flg & 2) != 0) p += 2;
        if (p > len) return null;
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(src, off + p, len - p);
            byte[] out = new byte[Math.max(expected, 65536)];
            int n = 0;
            while (!inf.finished()) {
                if (n == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
                int got = inf.inflate(out, n, out.length - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) return null;
                n += got;
            }
            Result r = new Result();
            r.len = n;
            r.data = out.length >= expected ? out : java.util.Arrays.copyOf(out, expected);
            return r;
        } catch (DataFormatException ex) {
            return null;
        } finally {
            inf.end();
        }
    }

    /* ── Zstandard ── */

    private static final class Out {
        byte[] buf = new byte[0];
        int len;

        boolean reserve(long extra) {
            long need = len + extra;
            if (need > Integer.MAX_VALUE - 16) return false;
            if (need > buf.length) {
                int nc = buf.length == 0 ? 65536 : buf.length;
                while (nc < need) nc = (int) Math.min((long) nc * 2, Integer.MAX_VALUE - 16);
                buf = java.util.Arrays.copyOf(buf, nc);
            }
            return true;
        }
    }

    /** Backward bit reader: bits are consumed from the END of the stream. */
    private static final class BitsBack {
        byte[] p;
        int off, len;
        long pos;       /* bits still unread; negative = read past the start */

        boolean init(byte[] p, int off, int len) {
            this.p = p;
            this.off = off;
            this.len = len;
            if (len <= 0 || p[off + len - 1] == 0) return false;
            int hb = 7;
            while (((p[off + len - 1] >> hb) & 1) == 0) hb--;
            pos = (long) (len - 1) * 8 + hb;   /* the marker bit itself is dropped */
            return true;
        }

        /** n bits starting at bit `lo` (LSB-first); bits before the start are 0. */
        int peekAt(long lo, int n) {
            if (n == 0) return 0;
            if (lo < 0) {
                int drop = (int) -lo;
                if (drop >= n) return 0;
                return peekAt(0, n - drop) << drop;
            }
            long firstByte = lo >> 3;
            int shift = (int) (lo & 7);
            long v = 0;
            for (int k = 0; k < 8 && firstByte + k < len; k++) v |= (p[off + (int) firstByte + k] & 0xffL) << (8 * k);
            return (int) ((v >>> shift) & (n >= 32 ? 0xffffffffL : ((1L << n) - 1)));
        }

        int read(int n) {
            pos -= n;
            return peekAt(pos, n);
        }
    }

    /** Forward bit reader (FSE table descriptions). */
    private static int bfPeek(byte[] p, int off, int len, long bit, int n) {
        long v = 0;
        long byte_ = bit >> 3;
        for (int k = 0; k < 8 && byte_ + k < len; k++) v |= (p[off + (int) byte_ + k] & 0xffL) << (8 * k);
        return (int) ((v >>> (bit & 7)) & ((1L << n) - 1));
    }

    private static final int FSE_MAX_LOG = 9;

    private static final class FseTable {
        int log;
        final int[] sym = new int[1 << FSE_MAX_LOG], nb = new int[1 << FSE_MAX_LOG], base = new int[1 << FSE_MAX_LOG];
    }

    private static int highbit32(int v) { int n = -1; while (v != 0) { v >>>= 1; n++; } return n; }

    private static boolean fseBuild(FseTable t, int[] prob, int maxSym, int log) {
        int size = 1 << log, high = size - 1, pos = 0;
        int step = (size >> 1) + (size >> 3) + 3, mask = size - 1;
        int[] next = new int[256];
        t.log = log;
        for (int s = 0; s <= maxSym; s++) {
            if (prob[s] == -1) {
                t.sym[high--] = s;
                next[s] = 1;
            } else {
                next[s] = prob[s] > 0 ? prob[s] : 0;
            }
        }
        for (int s = 0; s <= maxSym; s++) {
            for (int i = 0; i < prob[s]; i++) {
                t.sym[pos] = s;
                do pos = (pos + step) & mask; while (pos > high);
            }
        }
        if (pos != 0) return false;
        for (int i = 0; i < size; i++) {
            int sym = t.sym[i];
            int ns = next[sym]++;
            int nb = log - highbit32(ns);
            t.nb[i] = nb;
            t.base[i] = ((ns << nb) - size) & 0xffff;
        }
        return true;
    }

    /** Read an FSE table description at src[off]; returns bytes consumed or 0. */
    private static int fseRead(FseTable t, byte[] src, int off, int len, int maxLog, int maxSym) {
        int[] prob = new int[256];
        int sym = 0;
        if (len < 1) return 0;
        long bit = 0;
        int log = bfPeek(src, off, len, bit, 4) + 5;
        bit += 4;
        if (log > maxLog) return 0;
        int remaining = (1 << log) + 1;
        int threshold = 1 << log;
        int nbBits = log + 1;
        while (remaining > 1 && sym <= maxSym) {
            int max = 2 * threshold - 1 - remaining;
            int count;
            int v = bfPeek(src, off, len, bit, nbBits);
            if ((v & (threshold - 1)) < max) {
                count = v & (threshold - 1);
                bit += nbBits - 1;
            } else {
                count = v & (2 * threshold - 1);
                if (count >= threshold) count -= max;
                bit += nbBits;
            }
            count--;
            remaining -= count < 0 ? -count : count;
            prob[sym++] = count;
            if (count == 0) {
                for (;;) {
                    int rep = bfPeek(src, off, len, bit, 2);
                    bit += 2;
                    sym += rep;
                    if (rep != 3) break;
                }
            }
            while (remaining < threshold && threshold > 1) { nbBits--; threshold >>= 1; }
            if ((bit >> 3) > len) return 0;
        }
        if (remaining != 1 || sym > maxSym + 1) return 0;
        if (!fseBuild(t, prob, sym - 1, log)) return 0;
        return (int) ((bit + 7) >> 3);
    }

    private static void fseRle(FseTable t, int sym) {
        t.log = 0;
        t.sym[0] = sym;
        t.nb[0] = 0;
        t.base[0] = 0;
    }

    private static final int[] LL_DEFAULT = {
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
        -1, -1, -1, -1
    };
    private static final int[] ML_DEFAULT = {
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1
    };
    private static final int[] OF_DEFAULT = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };
    private static final int[] LL_BASE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536
    };
    private static final int[] LL_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final int[] ML_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31, 32, 33, 34, 35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099,
        8195, 16387, 32771, 65539
    };
    private static final int[] ML_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    /* ── Huffman ── */

    private static final int HUF_MAX_BITS = 11;

    private static final class HufTable {
        int maxBits;
        final byte[] sym = new byte[1 << HUF_MAX_BITS];
        final int[] nb = new int[1 << HUF_MAX_BITS];
    }

    private static boolean hufBuild(HufTable h, int[] w, int n) {
        long total = 0;
        int[] rankStart = new int[HUF_MAX_BITS + 2];
        int[] rankCount = new int[HUF_MAX_BITS + 2];
        int[] weights = new int[257];
        if (n < 1 || n > 255) return false;
        for (int i = 0; i < n; i++) {
            if (w[i] > HUF_MAX_BITS) return false;
            if (w[i] != 0) total += 1L << (w[i] - 1);
        }
        if (total == 0) return false;
        int maxBits = highbit32((int) total) + 1;
        if (maxBits > HUF_MAX_BITS) return false;
        long rest = (1L << maxBits) - total;
        if (rest == 0 || (rest & (rest - 1)) != 0) return false;
        int lastW = highbit32((int) rest) + 1;
        System.arraycopy(w, 0, weights, 0, n);
        weights[n] = lastW;
        n++;
        for (int i = 0; i < n; i++) rankCount[weights[i]]++;
        int next = 0;
        for (int r = 1; r <= maxBits; r++) { rankStart[r] = next; next += rankCount[r] << (r - 1); }
        if (next != (1 << maxBits)) return false;
        for (int i = 0; i < n; i++) {
            int wt = weights[i];
            if (wt == 0) continue;
            int len = 1 << (wt - 1);
            for (int u = rankStart[wt]; u < rankStart[wt] + len; u++) {
                h.sym[u] = (byte) i;
                h.nb[u] = maxBits + 1 - wt;
            }
            rankStart[wt] += len;
        }
        h.maxBits = maxBits;
        return true;
    }

    /** Read a Huffman tree description; returns bytes consumed or 0. */
    private static int hufRead(HufTable h, byte[] src, int off, int len) {
        int[] w = new int[256];
        int n = 0;
        int used;
        if (len < 1) return 0;
        int hb = src[off] & 0xff;
        if (hb < 128) {
            FseTable t = new FseTable();
            BitsBack b = new BitsBack();
            if (hb + 1 > len) return 0;
            int tb = fseRead(t, src, off + 1, hb, 6, 255);
            if (tb == 0 || tb > hb) return 0;
            if (!b.init(src, off + 1 + tb, hb - tb)) return 0;
            int s1 = b.read(t.log);
            int s2 = b.read(t.log);
            for (;;) {
                if (n >= 255) return 0;
                w[n++] = t.sym[s1];
                s1 = t.base[s1] + b.read(t.nb[s1]);
                if (b.pos < 0) { w[n++] = t.sym[s2]; break; }
                if (n >= 255) return 0;
                w[n++] = t.sym[s2];
                s2 = t.base[s2] + b.read(t.nb[s2]);
                if (b.pos < 0) { w[n++] = t.sym[s1]; break; }
            }
            used = hb + 1;
        } else {
            n = hb - 127;
            used = 1 + (n + 1) / 2;
            if (used > len) return 0;
            for (int k = 0; k < n; k += 2) {
                w[k] = (src[off + 1 + k / 2] & 0xff) >> 4;
                if (k + 1 < n) w[k + 1] = src[off + 1 + k / 2] & 15;
            }
        }
        if (n > 255 || !hufBuild(h, w, n)) return 0;
        return used;
    }

    private static boolean hufStream(HufTable h, byte[] src, int off, int len, byte[] out, int outOff, int count) {
        BitsBack b = new BitsBack();
        int mb = h.maxBits;
        if (!b.init(src, off, len)) return false;
        for (int i = 0; i < count; i++) {
            int idx = b.peekAt(b.pos - mb, mb);
            out[outOff + i] = h.sym[idx];
            b.pos -= h.nb[idx];
        }
        return b.pos == 0;
    }

    /* ── frame state ── */

    private static final class Frame {
        final HufTable huf = new HufTable();
        boolean hasHuf;
        final FseTable ll = new FseTable(), of = new FseTable(), ml = new FseTable();
        boolean hasLL, hasOF, hasML;
        final long[] rep = new long[3];
        byte[] lit = new byte[0];
        int litLen;
    }

    /** Returns the bytes the literals section used, or -1. */
    private static int decodeLiterals(Frame f, byte[] src, int off, int len) {
        int hsz;
        long regen, comp = 0;
        if (len < 1) return -1;
        int b0 = src[off] & 0xff;
        int type = b0 & 3;
        int sf = (b0 >> 2) & 3;
        if (type < 2) {
            if (sf == 0 || sf == 2) { hsz = 1; regen = b0 >> 3; }
            else if (sf == 1) { if (len < 2) return -1; hsz = 2; regen = (b0 >> 4) | ((src[off + 1] & 0xffL) << 4); }
            else { if (len < 3) return -1; hsz = 3; regen = (b0 >> 4) | ((src[off + 1] & 0xffL) << 4) | ((src[off + 2] & 0xffL) << 12); }
        } else {
            hsz = sf <= 1 ? 3 : sf == 2 ? 4 : 5;
            if (len < hsz) return -1;
            long hdr = 0;
            for (int k = 0; k < hsz; k++) hdr |= (src[off + k] & 0xffL) << (8 * k);
            int bits = sf <= 1 ? 10 : sf == 2 ? 14 : 18;
            regen = (hdr >> 4) & ((1L << bits) - 1);
            comp = (hdr >> (4 + bits)) & ((1L << bits) - 1);
        }
        if (regen > f.lit.length) f.lit = new byte[(int) regen + 64];
        int used;
        if (type == 0) {
            if (hsz + regen > len) return -1;
            System.arraycopy(src, off + hsz, f.lit, 0, (int) regen);
            used = hsz + (int) regen;
        } else if (type == 1) {
            if (hsz + 1 > len) return -1;
            java.util.Arrays.fill(f.lit, 0, (int) regen, src[off + hsz]);
            used = hsz + 1;
        } else {
            int p = off + hsz;
            boolean four = sf != 0;
            if (hsz + comp > len) return -1;
            int avail = (int) comp;
            if (type == 2) {
                int t = hufRead(f.huf, src, p, avail);
                if (t == 0) return -1;
                f.hasHuf = true;
                p += t;
                avail -= t;
            } else if (!f.hasHuf) {
                return -1;
            }
            if (!four) {
                if (!hufStream(f.huf, src, p, avail, f.lit, 0, (int) regen)) return -1;
            } else {
                int seg = (int) ((regen + 3) / 4), o = 6;
                if (avail < 6) return -1;
                int s1 = (src[p] & 0xff) | ((src[p + 1] & 0xff) << 8);
                int s2 = (src[p + 2] & 0xff) | ((src[p + 3] & 0xff) << 8);
                int s3 = (src[p + 4] & 0xff) | ((src[p + 5] & 0xff) << 8);
                if ((long) o + s1 + s2 + s3 > avail || 3L * seg > regen) return -1;
                int s4 = avail - o - s1 - s2 - s3;
                if (!hufStream(f.huf, src, p + o, s1, f.lit, 0, seg)) return -1;
                o += s1;
                if (!hufStream(f.huf, src, p + o, s2, f.lit, seg, seg)) return -1;
                o += s2;
                if (!hufStream(f.huf, src, p + o, s3, f.lit, 2 * seg, seg)) return -1;
                o += s3;
                if (!hufStream(f.huf, src, p + o, s4, f.lit, 3 * seg, (int) regen - 3 * seg)) return -1;
            }
            used = hsz + (int) comp;
        }
        f.litLen = (int) regen;
        return used;
    }

    /** Returns bytes used, or -1. `has` is a one-element flag. */
    private static int readTable(FseTable t, boolean[] has, int mode, byte[] src, int off, int len,
                                 int[] def, int defMax, int defLog, int maxLog, int maxSym) {
        switch (mode) {
        case 0:
            fseBuild(t, def, defMax, defLog);
            has[0] = true;
            return 0;
        case 1:
            if (len < 1 || (src[off] & 0xff) > maxSym) return -1;
            fseRle(t, src[off] & 0xff);
            has[0] = true;
            return 1;
        case 2: {
            int n = fseRead(t, src, off, len, maxLog, maxSym);
            if (n == 0) return -1;
            has[0] = true;
            return n;
        }
        default:
            return has[0] ? 0 : -1;
        }
    }

    private static boolean decodeBlock(Frame f, Out o, byte[] src, int off, int len) {
        int used = decodeLiterals(f, src, off, len);
        if (used < 0) return false;
        int litLen = f.litLen, litPos = 0;
        int p = used;
        if (p >= len) return false;
        int nseq = src[off + p++] & 0xff;
        if (nseq >= 128) {
            if (nseq == 255) {
                if (p + 2 > len) return false;
                nseq = (src[off + p] & 0xff) + ((src[off + p + 1] & 0xff) << 8) + 0x7f00;
                p += 2;
            } else {
                if (p + 1 > len) return false;
                nseq = ((nseq - 128) << 8) + (src[off + p] & 0xff);
                p += 1;
            }
        }
        if (nseq == 0) {
            if (!o.reserve(litLen)) return false;
            System.arraycopy(f.lit, 0, o.buf, o.len, litLen);
            o.len += litLen;
            return true;
        }
        if (p >= len) return false;
        int modes = src[off + p++] & 0xff;
        if ((modes & 3) != 0) return false;
        boolean[] has = new boolean[1];
        has[0] = f.hasLL;
        int n = readTable(f.ll, has, (modes >> 6) & 3, src, off + p, len - p, LL_DEFAULT, 35, 6, 9, 35);
        f.hasLL = has[0];
        if (n < 0) return false;
        p += n;
        has[0] = f.hasOF;
        n = readTable(f.of, has, (modes >> 4) & 3, src, off + p, len - p, OF_DEFAULT, 28, 5, 8, 31);
        f.hasOF = has[0];
        if (n < 0) return false;
        p += n;
        has[0] = f.hasML;
        n = readTable(f.ml, has, (modes >> 2) & 3, src, off + p, len - p, ML_DEFAULT, 52, 6, 9, 52);
        f.hasML = has[0];
        if (n < 0) return false;
        p += n;
        BitsBack b = new BitsBack();
        if (p > len || !b.init(src, off + p, len - p)) return false;
        int sLL = b.read(f.ll.log);
        int sOF = b.read(f.of.log);
        int sML = b.read(f.ml.log);

        for (int s = 0; s < nseq; s++) {
            int llc = f.ll.sym[sLL], ofc = f.of.sym[sOF], mlc = f.ml.sym[sML];
            if (llc > 35 || mlc > 52 || ofc > 31) return false;
            long ofv = (1L << ofc) + (b.read(ofc) & 0xffffffffL);
            long ml = ML_BASE[mlc] + (b.read(ML_BITS[mlc]) & 0xffffffffL);
            long ll = LL_BASE[llc] + (b.read(LL_BITS[llc]) & 0xffffffffL);
            long offset;
            ofv &= 0xffffffffL;
            if (ofv > 3) {
                offset = ofv - 3;
                f.rep[2] = f.rep[1]; f.rep[1] = f.rep[0]; f.rep[0] = offset;
            } else {
                long idx = ofv + (ll == 0 ? 1 : 0);
                if (idx == 1) offset = f.rep[0];
                else if (idx == 2) { offset = f.rep[1]; f.rep[1] = f.rep[0]; f.rep[0] = offset; }
                else if (idx == 3) { offset = f.rep[2]; f.rep[2] = f.rep[1]; f.rep[1] = f.rep[0]; f.rep[0] = offset; }
                else { offset = (f.rep[0] - 1) & 0xffffffffL; f.rep[2] = f.rep[1]; f.rep[1] = f.rep[0]; f.rep[0] = offset; }
            }
            if (s + 1 < nseq) {
                sLL = f.ll.base[sLL] + b.read(f.ll.nb[sLL]);
                sML = f.ml.base[sML] + b.read(f.ml.nb[sML]);
                sOF = f.of.base[sOF] + b.read(f.of.nb[sOF]);
            }
            if (litPos + ll > litLen) return false;
            if (!o.reserve(ll + ml)) return false;
            System.arraycopy(f.lit, litPos, o.buf, o.len, (int) ll);
            o.len += (int) ll;
            litPos += (int) ll;
            if (offset == 0 || offset > o.len) return false;
            int dst = o.len, from = (int) (dst - offset);
            byte[] buf = o.buf;
            for (int k = 0; k < ml; k++) buf[dst + k] = buf[from + k];
            o.len += (int) ml;
        }
        if (b.pos != 0) return false;
        if (!o.reserve(litLen - litPos)) return false;
        System.arraycopy(f.lit, litPos, o.buf, o.len, litLen - litPos);
        o.len += litLen - litPos;
        return true;
    }

    private static long u32(byte[] s, int o) {
        return (s[o] & 0xffL) | ((s[o + 1] & 0xffL) << 8) | ((s[o + 2] & 0xffL) << 16) | ((s[o + 3] & 0xffL) << 24);
    }

    static Result zstd(byte[] src, int off, int len, int expected) {
        Out o = new Out();
        Frame f = new Frame();
        int p = 0;
        if (expected > 0 && !o.reserve(expected)) return null;

        while (p + 4 <= len) {
            long magic = u32(src, off + p);
            if ((magic & 0xfffffff0L) == 0x184d2a50L) {
                if (p + 8 > len) return null;
                long sz = u32(src, off + p + 4);
                if (sz > len - p - 8) return null;
                p += 8 + (int) sz;
                continue;
            }
            if (magic != 0xfd2fb528L) return null;
            p += 4;
            if (p >= len) return null;
            int fhd = src[off + p++] & 0xff;
            int fcsFlag = fhd >> 6;
            int single = (fhd >> 5) & 1;
            int checksum = (fhd >> 2) & 1;
            int dictFlag = fhd & 3;
            if ((fhd & 8) != 0) return null;
            if (single == 0) p++;
            p += dictFlag == 3 ? 4 : dictFlag;
            int fcsSize = fcsFlag == 0 ? (single != 0 ? 1 : 0) : 1 << fcsFlag;
            p += fcsSize;
            if (p > len) return null;
            f.hasHuf = f.hasLL = f.hasOF = f.hasML = false;
            f.rep[0] = 1; f.rep[1] = 4; f.rep[2] = 8;
            boolean last = false;
            while (!last) {
                if (p + 3 > len) return null;
                int bh = (src[off + p] & 0xff) | ((src[off + p + 1] & 0xff) << 8) | ((src[off + p + 2] & 0xff) << 16);
                p += 3;
                last = (bh & 1) != 0;
                int type = (bh >> 1) & 3;
                int bsz = bh >>> 3;
                if (type == 0) {
                    if (bsz > len - p || !o.reserve(bsz)) return null;
                    System.arraycopy(src, off + p, o.buf, o.len, bsz);
                    o.len += bsz;
                    p += bsz;
                } else if (type == 1) {
                    if (p >= len || !o.reserve(bsz)) return null;
                    java.util.Arrays.fill(o.buf, o.len, o.len + bsz, src[off + p]);
                    o.len += bsz;
                    p += 1;
                } else if (type == 2) {
                    if (bsz > len - p || !decodeBlock(f, o, src, off + p, bsz)) return null;
                    p += bsz;
                } else {
                    return null;
                }
            }
            if (checksum != 0) p += 4;
        }
        Result r = new Result();
        if (o.len < expected) {
            if (!o.reserve(expected - o.len)) return null;
            java.util.Arrays.fill(o.buf, o.len, expected, (byte) 0);
            /* fzstd, handed an output buffer, returns all of it. */
            r.data = o.buf;
            r.len = expected;
            return r;
        }
        r.data = o.buf;
        r.len = o.len;
        return r;
    }
}
