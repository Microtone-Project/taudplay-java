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

    void copyFrom(Cell o) {
        note = o.note; instrment = o.instrment; volume = o.volume; volumeEff = o.volumeEff;
        pan = o.pan; panEff = o.panEff; effect = o.effect; effectArg = o.effectArg;
        azimuth = o.azimuth; elevation = o.elevation; effect2 = o.effect2; effectArg2 = o.effectArg2;
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
