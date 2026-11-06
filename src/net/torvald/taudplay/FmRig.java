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
    }
}
