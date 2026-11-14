/*
 * A decoded type-8 OPL patch (opl.js OplPatch) and its operators.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class OplPatch {
    /** One operator's registers, unpacked. The PSG keeps its envelope in one too. */
    static final class Operator {
        int am, vib, eg, ksr, mult, ksl, tl, ar, dr, sl, rr, ws;
    }

    int gen;
    int kind;
    boolean deepTremolo, deepVibrato, nts, lowFnum;
    int feedback, cntA, cntB, partnerMult, partnerDetune;
    double dPartnerDetune;      /* the pitch extension of partnerDetune (§9.18) */
    final Operator[] ops = { new Operator(), new Operator(), new Operator(), new Operator() };
    int outputs;
}
