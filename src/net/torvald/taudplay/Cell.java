/*
 * One pattern cell, either wire layout (8-byte v1/2 or 16-byte v3) — and the
 * cue sheet's entries (state.js).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class Cell {
    int note;
    int instrment, volume, volumeEff, pan, panEff, effect;
    int effectArg, azimuth;
    int elevation;              /* signed */
    int effect2;
    int effectArg2;
    /* Byte 13's high nibble as a wide decode last left it (0..15): the four
     * bits that make a wide cell's pitch 65536-TET (file format §5.1). */
    int noteFine;
    /* The sixteenths the note CARRIES: the nibble under a wide decode (none
     * under a sentinel word), none once a narrow decode has written a note
     * byte. A pattern slot is reused across files, so reading noteFine itself
     * could bend a narrow song's pitch. */
    int noteFrac;

    /** The note in 4096-TET units: the word plus the sixteenths it carries. */
    double noteValue() { return note + noteFrac / 16.0; }

    void copyFrom(Cell o) {
        note = o.note; instrment = o.instrment; volume = o.volume; volumeEff = o.volumeEff;
        pan = o.pan; panEff = o.panEff; effect = o.effect; effectArg = o.effectArg;
        azimuth = o.azimuth; elevation = o.elevation; effect2 = o.effect2; effectArg2 = o.effectArg2;
        noteFine = o.noteFine; noteFrac = o.noteFrac;
    }

    static Cell[] newPattern() {
        Cell[] p = new Cell[C.ROWS_PER_PATTERN];
        for (int i = 0; i < p.length; i++) { p[i] = new Cell(); p[i].pan = 32; }
        return p;
    }

    /** A cue's pattern-flow instruction. */
    static final class PlayInst {
        int type, arg, rows;
        PlayInst(int type, int arg, int rows) { this.type = type; this.arg = arg; this.rows = rows; }
    }

    /** One cue: a pattern number per lane, and the two instructions packed
     *  into their top bits. */
    static final class Cue {
        final int[] raw = new int[C.MAX_VOICES];
        PlayInst inst0 = new PlayInst(C.INST_NOP, 0, 0), inst1 = new PlayInst(C.INST_NOP, 0, 0);

        Cue() { java.util.Arrays.fill(raw, C.PATTERN_EMPTY); }
    }
}
