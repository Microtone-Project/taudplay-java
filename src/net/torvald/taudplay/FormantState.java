/*
 * One sounding formant tract (formant.js FormantState), hung off a voice. A
 * fresh one is exactly what `new FormantState()` leaves.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class FormantState {
    /** A piecewise-linear track: (time, value) pairs. */
    static final class Track {
        int n;                  /* doubles used (2 per point) */
        final double[] p = new double[FORMANT_TRACK_MAX];

        void copyFrom(Track o) { n = o.n; System.arraycopy(o.p, 0, p, 0, FORMANT_TRACK_MAX); }
        void clear() { n = 0; java.util.Arrays.fill(p, 0); }
    }

    /** One consonant as it sounds (FormantSegment). `next` is an index into
     *  the onset array, -1 for the vowel. */
    static final class Segment {
        Formant.Consonant row;
        final FormantPatch.Slot slot = new FormantPatch.Slot();
        double end;
        Track w = new Track(), av = new Track(), ah = new Track(), af = new Track(), nz = new Track();
        int next;
        int art;                /* as blendInto reads it: ART_NONE, _PALATAL or _DARK */
        double loop;            /* a held nucleus's repeat, ms (a trill's cycle); a coda's, while held */
        double hold;            /* a coda: where S $9010 holds it while the key is held */
        /* A coda after a hissing nucleus: its own noise starts this far in, the
         * nucleus's going on until then through the nucleus's bank. */
        double noiseAt;
        Formant.Consonant prevRow;
        int prevArt;

        void copyFrom(Segment o) {
            row = o.row; slot.copyFrom(o.slot); end = o.end;
            w.copyFrom(o.w); av.copyFrom(o.av); ah.copyFrom(o.ah); af.copyFrom(o.af); nz.copyFrom(o.nz);
            next = o.next; art = o.art; loop = o.loop; hold = o.hold; noiseAt = o.noiseAt;
            prevRow = o.prevRow; prevArt = o.prevArt;
        }

        void clear() {
            row = null; slot.copyFrom(new FormantPatch.Slot()); end = 0;
            w.clear(); av.clear(); ah.clear(); af.clear(); nz.clear();
            next = 0; art = 0; loop = 0; hold = 0; noiseAt = 0; prevRow = null; prevArt = 0;
        }
    }

    /** How far the vowel is nasalised, or r-coloured (FormantAmount): 0 not
     *  at all, 1 fully, and between while S $90kx, or an instrument change,
     *  glides it. */
    static final class Amount {
        boolean own;
        double v;               /* where it stands */
        double to, step, left;  /* where a glide is heading, per ms, ms to go */
        boolean rejoin;
    }

    FormantPatch patch;
    int phase = PHASE_DONE;
    int onsetCount;
    final Segment[] onset = { new Segment(), new Segment() };
    int seg;
    boolean hasCoda;
    final Segment coda = new Segment();
    double t;
    boolean codaVeto, codaNoise, fresh = true;
    /* A syllable with no vowel: the onset index of its nucleus (-1 when there
     * is a vowel), and whether it has come to hold. */
    int nucleus = -1;
    boolean held;
    /* The glottal source when it is a chip channel (CHIP_*), keyed with the
     * note; CHIP_NONE when the source is the record's sample. */
    int srcKind = CHIP_NONE;
    final OplState srcOpl = new OplState();
    final PsgState srcPsg = new PsgState();
    int upd = 48, ctl, normCount;
    boolean ownVowel;
    int sv;                     /* SEMIVOWELS index, 0 = none */
    final Track svW = new Track(), svAv = new Track();
    double svT, svEnd;
    final double[] VG = new double[5], VGB = new double[5];
    boolean rejoin;
    double vFront, vOpen, vRound, gFront, gOpen, gRound, tFront, tOpen, tRound;
    double glideLeft;
    /* S $9xxx (item 292): the vowel's nasalisation and r-colouring as the note
     * has them, the glide time it was given (-1: the record's), and a coda
     * called for while the key is still held. */
    final Amount nasAmt = new Amount(), rhoAmt = new Amount();
    int glideOv = -1;
    boolean codaCall;
    double codaZ0;              /* the vowel nasality the coda was built from */
    boolean atVowel;            /* started at the vowel (Formant.startAtVowel) */
    double av, avStep, ah, ahStep, af, afStep;
    double nv = 1.0, nvStep, nvGoal = 1.0;
    double nb, sg = 1.0, sm;
    boolean sgDue = true;
    final double[] hw = new double[NORM_HARMONICS + 1];
    int hwK;
    final double[] casc = new double[21], bank = new double[15], bankGain = new double[6];
    final double[] cy = new double[14], by = new double[10];
    boolean nOn;
    long nBase;
    int nSpan;
    double nPos, nStep;
    int nTilt;
    double nTiltC, nGain = 1.0, nZ;
    boolean nGen;
    int nSeed = 0x9e3779b9;     /* uint32 */
    final double[] V = new double[5], VB = new double[5], F = new double[5], B = new double[5];
    final double[] VR = new double[5], VRB = new double[5];

    /** A by-value copy (the C's struct assignment). */
    void copyFrom(FormantState o) {
        patch = o.patch;
        phase = o.phase;
        onsetCount = o.onsetCount;
        for (int i = 0; i < onset.length; i++) onset[i].copyFrom(o.onset[i]);
        seg = o.seg;
        hasCoda = o.hasCoda;
        coda.copyFrom(o.coda);
        t = o.t;
        codaVeto = o.codaVeto;
        codaNoise = o.codaNoise;
        fresh = o.fresh;
        nucleus = o.nucleus;
        held = o.held;
        srcKind = o.srcKind;
        srcOpl.copyFrom(o.srcOpl);
        srcPsg.copyFrom(o.srcPsg);
        upd = o.upd;
        ctl = o.ctl;
        normCount = o.normCount;
        ownVowel = o.ownVowel;
        sv = o.sv;
        svW.copyFrom(o.svW);
        svAv.copyFrom(o.svAv);
        svT = o.svT;
        svEnd = o.svEnd;
        System.arraycopy(o.VG, 0, VG, 0, VG.length);
        System.arraycopy(o.VGB, 0, VGB, 0, VGB.length);
        rejoin = o.rejoin;
        vFront = o.vFront;
        vOpen = o.vOpen;
        vRound = o.vRound;
        gFront = o.gFront;
        gOpen = o.gOpen;
        gRound = o.gRound;
        tFront = o.tFront;
        tOpen = o.tOpen;
        tRound = o.tRound;
        glideLeft = o.glideLeft;
        nasAmt.own = o.nasAmt.own; nasAmt.v = o.nasAmt.v; nasAmt.to = o.nasAmt.to; nasAmt.step = o.nasAmt.step; nasAmt.left = o.nasAmt.left; nasAmt.rejoin = o.nasAmt.rejoin;
        rhoAmt.own = o.rhoAmt.own; rhoAmt.v = o.rhoAmt.v; rhoAmt.to = o.rhoAmt.to; rhoAmt.step = o.rhoAmt.step; rhoAmt.left = o.rhoAmt.left; rhoAmt.rejoin = o.rhoAmt.rejoin;
        glideOv = o.glideOv;
        codaCall = o.codaCall;
        codaZ0 = o.codaZ0;
        atVowel = o.atVowel;
        av = o.av;
        avStep = o.avStep;
        ah = o.ah;
        ahStep = o.ahStep;
        af = o.af;
        afStep = o.afStep;
        nv = o.nv;
        nvStep = o.nvStep;
        nvGoal = o.nvGoal;
        nb = o.nb;
        sg = o.sg;
        sm = o.sm;
        sgDue = o.sgDue;
        System.arraycopy(o.hw, 0, hw, 0, hw.length);
        hwK = o.hwK;
        System.arraycopy(o.casc, 0, casc, 0, casc.length);
        System.arraycopy(o.bank, 0, bank, 0, bank.length);
        System.arraycopy(o.bankGain, 0, bankGain, 0, bankGain.length);
        System.arraycopy(o.cy, 0, cy, 0, cy.length);
        System.arraycopy(o.by, 0, by, 0, by.length);
        nOn = o.nOn;
        nBase = o.nBase;
        nSpan = o.nSpan;
        nPos = o.nPos;
        nStep = o.nStep;
        nTilt = o.nTilt;
        nTiltC = o.nTiltC;
        nGain = o.nGain;
        nZ = o.nZ;
        nGen = o.nGen;
        nSeed = o.nSeed;
        System.arraycopy(o.V, 0, V, 0, V.length);
        System.arraycopy(o.VB, 0, VB, 0, VB.length);
        System.arraycopy(o.F, 0, F, 0, F.length);
        System.arraycopy(o.B, 0, B, 0, B.length);
        System.arraycopy(o.VR, 0, VR, 0, VR.length);
        System.arraycopy(o.VRB, 0, VRB, 0, VRB.length);
    }

    /** As `new FormantState()` leaves one (te_formant_state_init). */
    void init() { copyFrom(new FormantState()); }
}
