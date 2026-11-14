/*
 * The pitch extension — the `IPit` section (file format §9.18; taudplay-c's
 * pitchext.c).
 *
 * An instrument's pitch fields are sixteen bits each: 4096 steps to the
 * octave over sixteen octaves. `IPit` gives a field one more byte, `oooo
 * ffff`: four more bits of fraction below it (65536 steps to the octave, the
 * cell's own grid) and four more bits of octave above it, as a two's-
 * complement nibble. The value of an extended field, in 65536-TET units, is
 *
 *     field × 16 + ffff + oooo × 2^20
 *
 * with the field read as it always was — so a zero byte changes nothing, and
 * a file that needs none of this has no section. The engine keeps a field's
 * sixteen bits where they were and the extension beside them as a DELTA in
 * its own units (4096-TET, a double): 0 wherever there is none.
 *
 * A range's HIGH bound keeps its fraction complemented: unextended, the
 * bound covers the whole of the unit it names, and the engine's test is
 * against the exclusive end `pitchEnd + 1 + dHi` (pitchInRange).
 *
 * An entry carries a guard, a CRC-16 of the sixteen-bit fields it extends. An
 * editor that does not know the section keeps it while it rewrites the
 * instruments under it; an entry whose guard no longer matches is ignored
 * whole, and the instrument plays its sixteen-bit values.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class PitchExt {
    static final int BASE = 0x04;
    static final int TABLE = 0x08;
    static final int PATCHES = 0x10;
    static final int RESERVED = 0xe0;

    int flags;
    int guard;
    final int[] base = new int[3];  /* sample detune, pitch-pan centre, the kind's own field */
    int tableN, patchN;
    int[] data;                     /* tableN × 3 (detune, low, high), then patchN × 3 (start, end, detune) */

    /** A pitch range's test: from pitchStart through the whole of the last
     *  unit pitchEnd names, each moved by its extension (§9.11, §9.18). */
    static boolean pitchInRange(double noteVal, int start, double dLo, int end, double dHi) {
        return noteVal >= start + dLo && noteVal < end + 1 + dHi;
    }

    /** oooo, as sixteen-octave steps in 4096-TET units. */
    private static double extOctaves(int x) {
        int o = x >> 4;
        if (o >= 8) o -= 16;
        return o * 65536.0;
    }
    /** What a byte adds to its field… */
    private static double extDelta(int x) { return extOctaves(x) + (x & 15) / 16.0; }
    /** …and to the exclusive end of a range, whose fraction is kept complemented. */
    private static double extDeltaHigh(int x) { return extOctaves(x) - (x & 15) / 16.0; }

    /** CRC-16/CCITT-FALSE: polynomial $1021, initial value $FFFF, no reflection. */
    private static int crc16(int crc, byte[] p, int off, int n) {
        for (int i = 0; i < n; i++) {
            crc ^= (p[off + i] & 0xff) << 8;
            for (int b = 0; b < 8; b++) crc = ((crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff;
        }
        return crc;
    }
    private static int crc16Word(int crc, int word) {
        byte[] le = { (byte) (word & 0xff), (byte) ((word >> 8) & 0xff) };
        return crc16(crc, le, 0, 2);
    }

    /** The sixteen-bit word of the pitch field an instrument's KIND keeps in
     *  its own block: a formant instrument's reference word, an OPL
     *  instrument's rhythm-partner pitch; 0 for a kind with none. */
    private static int kindPitchWord(Inst inst) {
        if (inst.formant != null) return (inst.formant.refNote & 0x7fff) | (inst.formant.nasal ? 0x8000 : 0);
        if (inst.opl != null) return inst.opl.partnerDetune & 0xffff;
        return 0;
    }

    /** Bring one instrument's deltas in line with its entry: cleared, then —
     *  when there is an entry, its groups fit the instrument as it is now and
     *  its guard matches — set. */
    static void apply(Engine e, Inst inst) {
        boolean tabled = inst.meta != null &&
            (inst.meta.type == META_TYPE_LAYERED || inst.meta.type == META_TYPE_FM);

        inst.dSampleDetune = 0.0;
        inst.dPitchPanCentre = 0.0;
        if (inst.opl != null) inst.opl.dPartnerDetune = 0.0;
        if (inst.formant != null) {
            boolean had = inst.formant.dRefNote != 0.0;
            inst.formant.dRefNote = 0.0;
            if (had && inst.lyric != null) inst.refreshLyric();
        }
        if (inst.meta != null) {
            for (int i = 0; i < inst.meta.count; i++) {
                Meta.Layer l = inst.meta.layers[i];
                l.dLo = l.dHi = l.dDetune = 0.0;
            }
        }
        for (int i = 0; i < inst.patchCount; i++) {
            Patch p = inst.extraPatches[i];
            p.dLo = p.dHi = p.dDetune = 0.0;
        }

        if (e.pitchExt == null || inst.index < 0 || inst.index > 1023) return;
        PitchExt x = e.pitchExt[inst.index];
        if (x == null) return;
        /* A group must be about what the instrument HAS: a table record has
         * no base fields (its bytes are entries), and the counts are the
         * record's. */
        if ((x.flags & BASE) != 0 && tabled) return;
        if ((x.flags & TABLE) != 0 && (!tabled || x.tableN != inst.meta.rawCount)) return;
        if ((x.flags & PATCHES) != 0 && x.patchN != inst.patchCount) return;

        int crc = 0xffff;
        if ((x.flags & BASE) != 0) {
            crc = crc16Word(crc, inst.sampleDetune);
            crc = crc16Word(crc, inst.pitchPanCentre);
            crc = crc16Word(crc, kindPitchWord(inst));
        }
        if ((x.flags & TABLE) != 0) crc = crc16(crc, inst.meta.rawPitch, 0, x.tableN * 6);
        if ((x.flags & PATCHES) != 0) {
            for (int i = 0; i < inst.patchCount; i++) {
                Patch p = inst.extraPatches[i];
                crc = crc16Word(crc, p.pitchStart);
                crc = crc16Word(crc, p.pitchEnd);
                crc = crc16Word(crc, p.sampleDetune);
            }
        }
        if (crc != x.guard) return;

        int table = 0, patch = x.tableN * 3;
        if ((x.flags & BASE) != 0) {
            inst.dSampleDetune = extDelta(x.base[0]);
            inst.dPitchPanCentre = extDelta(x.base[1]);
            if (inst.opl != null) inst.opl.dPartnerDetune = extDelta(x.base[2]);
            if (inst.formant != null) {
                inst.formant.dRefNote = extDelta(x.base[2]);
                /* A lyric instrument's syllables carry the record's reference note. */
                if (inst.lyric != null) inst.refreshLyric();
            }
        }
        if ((x.flags & TABLE) != 0) {
            for (int i = 0; i < inst.meta.count; i++) {
                Meta.Layer l = inst.meta.layers[i];
                int b = table + l.srcIndex * 3;
                l.dDetune = extDelta(x.data[b]);
                l.dLo = extDelta(x.data[b + 1]);
                l.dHi = extDeltaHigh(x.data[b + 2]);
            }
        }
        if ((x.flags & PATCHES) != 0) {
            for (int i = 0; i < inst.patchCount; i++) {
                Patch p = inst.extraPatches[i];
                int b = patch + i * 3;
                p.dLo = extDelta(x.data[b]);
                p.dHi = extDeltaHigh(x.data[b + 1]);
                p.dDetune = extDelta(x.data[b + 2]);
            }
        }
    }

    /** Install the whole `IPit` payload s[off … off+len) (len 0 = none) and
     *  apply it. An entry the section cannot finish — a reserved flag, a
     *  group that runs off the end — ends the read: what came before it
     *  stands. Two entries for one instrument are INVALID, and the later one
     *  wins. */
    static void set(Engine e, byte[] s, int off, int len) {
        e.pitchExt = len >= 4 ? new PitchExt[1024] : null;
        int o = 0;
        while (e.pitchExt != null && o + 4 <= len) {
            int flags = s[off + o + 1] & 0xff;
            int id = (s[off + o] & 0xff) | ((flags & 0x03) << 8);
            int guard = (s[off + o + 2] & 0xff) | ((s[off + o + 3] & 0xff) << 8);
            int tableN = 0, patchN = 0;
            int base = -1, table = -1, patch = -1;
            int p = o + 4;
            if ((flags & RESERVED) != 0) break;
            if ((flags & BASE) != 0) {
                if (p + 3 > len) break;
                base = p;
                p += 3;
            }
            if ((flags & TABLE) != 0) {
                if (p + 1 > len) break;
                tableN = s[off + p] & 0xff;
                if (p + 1 + tableN * 3 > len) break;
                table = p + 1;
                p += 1 + tableN * 3;
            }
            if ((flags & PATCHES) != 0) {
                if (p + 2 > len) break;
                patchN = (s[off + p] & 0xff) | ((s[off + p + 1] & 0xff) << 8);
                if (p + 2 + patchN * 3 > len) break;
                patch = p + 2;
                p += 2 + patchN * 3;
            }
            PitchExt x = new PitchExt();
            x.flags = flags;
            x.guard = guard;
            x.tableN = tableN;
            x.patchN = patchN;
            x.data = new int[(tableN + patchN) * 3];
            if (base >= 0) for (int i = 0; i < 3; i++) x.base[i] = s[off + base + i] & 0xff;
            if (table >= 0) for (int i = 0; i < tableN * 3; i++) x.data[i] = s[off + table + i] & 0xff;
            if (patch >= 0) for (int i = 0; i < patchN * 3; i++) x.data[tableN * 3 + i] = s[off + patch + i] & 0xff;
            e.pitchExt[id] = x;
            o = p;
        }
        for (int i = 0; i < 1024; i++) apply(e, e.instruments[i]);
    }
}
