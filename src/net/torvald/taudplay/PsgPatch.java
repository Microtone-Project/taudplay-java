/*
 * A decoded type-9 PSG patch (psg.js PsgPatch), with the S $Axxx run-time
 * overlay: each field -1 while the record's own value stands.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class PsgPatch {
    /** One oscillator's twelve bytes, unpacked (PsgOscillator). The envelope's
     *  fields sit in an operator, so the chip's envelope generator reads them
     *  unchanged: ar dr sl rr eg, plus mult ksl tl ksr. */
    static final class Oscillator {
        final OplPatch.Operator op = new OplPatch.Operator();
        int wave;
        boolean reset, invert;
        int tremolo, vibrato;       /* 0 off, 1 shallow, 2 deep */
        int advance, pattern;
    }

    int gen;
    boolean multiply, muteA, muteB;
    final Oscillator[] osc = { new Oscillator(), new Oscillator() };
    final int[] rtAdvance = new int[2], rtLevel = new int[2];
    int rtMute;
    int rtGen;
}
