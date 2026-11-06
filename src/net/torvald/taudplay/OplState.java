/*
 * One sounding OPL channel (opl.js OplState), hung off a voice. A fresh one is
 * exactly what `new OplState()` leaves.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class OplState {
    OplPatch patch;
    int gen;
    double rate;
    final int[] phase = new int[4];     /* uint32 */
    final int[] inc = new int[4], env = new int[4];
    final int[] state = new int[4];
    final int[] out = new int[4], prev = new int[4], kslAtt = new int[4], ksrOffset = new int[4];
    int hhPhase, tcPhase;               /* uint32 */
    int hhInc, tcInc;
    int noise;                          /* uint32 */
    boolean keyed;
    boolean kValid;                     /* false = the JS NaN: not yet on the chip clock */
    long k;
    double cur, last;
    double vol;
    int volAtt;
    /** Phase modulation from outside the chip, in 1/1024 of a cycle, added to
     *  every OUTPUT operator's phase: what an FM rack reading this channel as
     *  an operator writes (§5.5.1). 0 on every other path. */
    double pm;

    OplState() { init(); }

    void init() {
        patch = null;
        gen = -1;
        rate = -1;
        java.util.Arrays.fill(phase, 0);
        java.util.Arrays.fill(inc, 0);
        java.util.Arrays.fill(env, C.OPL_ENV_MAX);
        java.util.Arrays.fill(state, 0);
        java.util.Arrays.fill(out, 0);
        java.util.Arrays.fill(prev, 0);
        java.util.Arrays.fill(kslAtt, 0);
        java.util.Arrays.fill(ksrOffset, 0);
        hhPhase = tcPhase = hhInc = tcInc = 0;
        noise = 1;
        keyed = false;
        kValid = true;          /* the constructor's k is -1, not NaN */
        k = -1;
        cur = last = 0;
        vol = -1;
        volAtt = 0;
        pm = 0;
    }

    /** A by-value copy (the C's struct assignment). */
    void copyFrom(OplState o) {
        patch = o.patch; gen = o.gen; rate = o.rate;
        System.arraycopy(o.phase, 0, phase, 0, 4);
        System.arraycopy(o.inc, 0, inc, 0, 4);
        System.arraycopy(o.env, 0, env, 0, 4);
        System.arraycopy(o.state, 0, state, 0, 4);
        System.arraycopy(o.out, 0, out, 0, 4);
        System.arraycopy(o.prev, 0, prev, 0, 4);
        System.arraycopy(o.kslAtt, 0, kslAtt, 0, 4);
        System.arraycopy(o.ksrOffset, 0, ksrOffset, 0, 4);
        hhPhase = o.hhPhase; tcPhase = o.tcPhase; hhInc = o.hhInc; tcInc = o.tcInc;
        noise = o.noise; keyed = o.keyed; kValid = o.kValid; k = o.k;
        cur = o.cur; last = o.last; vol = o.vol; volAtt = o.volAtt; pm = o.pm;
    }
}
