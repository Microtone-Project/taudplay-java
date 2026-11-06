/*
 * A voice's cached sample-modification geometry (samplemod.js resolveModGeom).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class ModGeom {
    int epoch;
    Inst inst;
    int base, len;
    boolean live;
    int es, ee;
    int combN;
    boolean combOdd;
    double combScale;
    int ds, dl;

    void copyFrom(ModGeom o) {
        epoch = o.epoch; inst = o.inst; base = o.base; len = o.len; live = o.live;
        es = o.es; ee = o.ee; combN = o.combN; combOdd = o.combOdd; combScale = o.combScale;
        ds = o.ds; dl = o.dl;
    }
}
