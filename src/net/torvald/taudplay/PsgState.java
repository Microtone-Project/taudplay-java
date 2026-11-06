/*
 * One sounding PSG pair (psg.js PsgState), hung off a voice. A fresh one is
 * exactly what `new PsgState()` leaves.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class PsgState {
    PsgPatch patch;
    int gen;
    double rate;
    final int[] state = new int[2];
    final int[] env = new int[2], ksrOffset = new int[2], kslAtt = new int[2];
    final double[] gain = new double[2], inc = new double[2];
    final double[] phase = new double[2], noise = new double[2];
    final int[] adv = new int[2];
    final int[] advCount = new int[2];
    /** Set at every key-on; NaN = anchored at the next frame rendered. */
    final double[] lfo = new double[2];
    boolean keyed;
    boolean kValid;             /* false = the JS NaN */
    long k;
    double cur, last;
    double pm;                  /* a 32-bit phase offset from an FM rack */

    PsgState() { init(); }

    void init() {
        patch = null;
        gen = -1;
        rate = -1;
        state[0] = state[1] = 0;
        env[0] = env[1] = C.OPL_ENV_MAX;
        ksrOffset[0] = ksrOffset[1] = 0;
        kslAtt[0] = kslAtt[1] = 0;
        gain[0] = gain[1] = 0;
        inc[0] = inc[1] = 0;
        phase[0] = phase[1] = 0;
        noise[0] = noise[1] = 0;
        adv[0] = adv[1] = 0;
        advCount[0] = advCount[1] = 0;
        lfo[0] = lfo[1] = 0;
        keyed = false;
        kValid = true;          /* the constructor's k is -1, not NaN */
        k = -1;
        cur = last = 0;
        pm = 0;
    }

    /** A by-value copy (the C's struct assignment). */
    void copyFrom(PsgState o) {
        patch = o.patch; gen = o.gen; rate = o.rate;
        System.arraycopy(o.state, 0, state, 0, 2);
        System.arraycopy(o.env, 0, env, 0, 2);
        System.arraycopy(o.ksrOffset, 0, ksrOffset, 0, 2);
        System.arraycopy(o.kslAtt, 0, kslAtt, 0, 2);
        System.arraycopy(o.gain, 0, gain, 0, 2);
        System.arraycopy(o.inc, 0, inc, 0, 2);
        System.arraycopy(o.phase, 0, phase, 0, 2);
        System.arraycopy(o.noise, 0, noise, 0, 2);
        System.arraycopy(o.adv, 0, adv, 0, 2);
        System.arraycopy(o.advCount, 0, advCount, 0, 2);
        System.arraycopy(o.lfo, 0, lfo, 0, 2);
        keyed = o.keyed; kValid = o.kValid; k = o.k; cur = o.cur; last = o.last; pm = o.pm;
    }
}
