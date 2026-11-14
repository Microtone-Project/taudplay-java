/*
 * An FM operator rack's running state (fm.js; taudplay-c TaudFmRig).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class FmRig {
    int count;
    final Voice[] voices = new Voice[FM_MAX_OPERATORS];
    final double[] gain = new double[FM_MAX_OPERATORS];
    final double[] cur = new double[FM_MAX_OPERATORS];
    final double[] last = new double[FM_MAX_OPERATORS];
    final boolean[] done = new boolean[FM_MAX_OPERATORS];
    int[] program;          /* the meta's program, borrowed */
    int programLen;
    final double[] stack = new double[FM_STACK_MAX + 2];
    /* Oversampling (spec §5.5.1 "The rack's own rate"): what an operator's
     * per-frame chain made of its gain, and a chip operator's value, both
     * held over the frame's sub-steps; and the state of the low-pass the rack
     * comes down through, two numbers a section. */
    final double[] opGain = new double[FM_MAX_OPERATORS];
    final double[] held = new double[FM_MAX_OPERATORS];
    final double[] dec = new double[FM_DEC_SECTIONS * 2];

    void init(int count) {
        this.count = count;
        java.util.Arrays.fill(voices, null);
        java.util.Arrays.fill(gain, 0);
        java.util.Arrays.fill(cur, 0);
        java.util.Arrays.fill(last, 0);
        java.util.Arrays.fill(done, false);
        program = null;
        programLen = 0;
        java.util.Arrays.fill(stack, 0);
        java.util.Arrays.fill(opGain, 0);
        java.util.Arrays.fill(held, 0);
        java.util.Arrays.fill(dec, 0);
    }
}
