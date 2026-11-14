/*
 * Row processing + cue advance — port of row.js (taudplay-c row.c).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Row {
    private Row() {}

    /** The argument an Int0..IntF marker on this row carries: the first `:`. */
    private static int interruptArgOf(Engine e, Cell row) {
        if (row.effect == OP_COLON) return row.effectArg & 0xffff;
        if (e.tsWideCells && row.effect2 == OP_COLON) return row.effectArg2 & 0xffff;
        return 0;
    }

    /** This row's S $Dxny argument (first slot wins), or -1. */
    private static int sDelayArg(Engine e, Cell row) {
        if (row.effect == OP_S && ((row.effectArg >> 12) & 0xf) == 0xd) return row.effectArg;
        if (e.tsWideCells && row.effect2 == OP_S && ((row.effectArg2 >> 12) & 0xf) == 0xd) return row.effectArg2;
        return -1;
    }

    private static void scheduleDxnyAction(Voice v, int sArg, int delayTick) {
        if (sArg < 0) return;
        int y = sArg & 0xf;
        if (y == 0) return;
        v.noteActionTick = delayTick + y;
        v.delayedAction = (sArg >> 4) & 0xf;
    }

    /** "Instrument byte without retrigger" (no-note-inst and porta+inst rows). */
    private static void applyInstrumentChange(Engine e, Voice v, Inst newInst, Patch newPatch,
                                              boolean reAttack, boolean mayCarry) {
        Trigger.applyActiveSample(v, newInst, newPatch);
        /* A formant instrument sung from a chip instrument takes the chip's
         * C2SPD where its sample's rate would be, as on a trigger. */
        Inst formantChip = newInst.formant != null ? Formant.chipSource(e, newInst) : null;
        if (formantChip != null) v.activeSamplingRate = Formant.chipRate(formantChip);
        /* An OPL voice takes the new patch onto the channel it is sounding; on
         * a porta row the instrument byte re-attacks its envelopes without
         * restarting the phase (§5.5.2). A sampled note handed an OPL
         * instrument gets a fresh channel, keyed at the note it is already
         * playing. */
        if (newInst.opl != null && v.opl != null) {
            v.opl.patch = newInst.opl;
            v.opl.gen = -1;
            if (reAttack) Opl.reattack(v.opl);
        } else if (newInst.opl != null) {
            v.opl = v.oplStore();
            v.opl.init();
            Opl.keyOn(v.opl, newInst.opl, false);
        } else {
            v.opl = null;
        }
        /* A PSG pair takes a new patch the same way, its internal status kept. */
        if (newInst.psg != null && v.psg != null) {
            v.psg.patch = newInst.psg;
            v.psg.gen = -1;
            if (reAttack) Psg.reattack(v.psg);
        } else if (newInst.psg != null) {
            v.psg = v.psgStore();
            v.psg.init();
            Psg.keyOn(v.psg, newInst.psg, false);
        } else {
            v.psg = null;
        }
        /* A formant voice keeps saying its syllable under the new record's
         * tract; a sampled note handed one starts its tract at the vowel. Its
         * source follows the record, a chip channel taking a new patch where
         * it stands.
         *
         * A LYRIC instrument's byte starts its lyrics over. The tract it hands
         * over is the syllable the note is already singing, when that is one
         * of this instrument's; otherwise its first syllable. */
        FormantPatch tract = newInst.formant;
        if (newInst.lyric != null) {
            FormantPatch cur = v.formant != null ? v.formant.patch : null;
            v.lyricPos = 0;
            tract = cur != null && cur.lyricOf == newInst.formant ? cur : newInst.lyric[0];
        }
        if (newInst.formant != null && v.formant != null) {
            Formant.relatch(e, v.formant, tract);
            Formant.relatchSource(v.formant, formantChip);
        } else if (newInst.formant != null) {
            v.formant = v.formantStore();
            v.formant.init();
            Formant.startAtVowel(e, v.formant, tract, false);
            Formant.keySource(v.formant, formantChip);
        } else {
            v.formant = null;
        }
        int seedVol = Trigger.rowVolumeFromDefault(newInst, newPatch, e.volMax);
        v.noteVolume = seedVol;
        v.rowVolume = seedVol;
        v.keyOff = false;
        v.noteFading = false;
        v.fadeoutVolume = 1.0;
        v.fadeoutMix = 1.0;
        v.fadeoutMixStep = 0.0;
        if (!reAttack) return;
        if (!(mayCarry && Envelope.envCarry(v.activeVolEnvLoop))) {
            v.envIndex = 0;
            v.envTimeSec = 0.0;
            v.envVolume = dclamp(v.activeVolEnv[0].value / (double) v.activeVolEnvMax, 0.0, 1.0);
        }
        v.hasPanEnv = Envelope.envPresent(v.activePanEnvLoop);
        if (!(mayCarry && Envelope.envCarry(v.activePanEnvLoop))) {
            v.envPanIndex = 0;
            v.envPanTimeSec = 0.0;
            v.envPan = v.activePanEnv[0].value / 255.0;
        }
        Envelope.PfBox box = e.pfBox;
        if (!v.hasPitchEnv) {
            v.envPitchValue = 0.5; v.envPitchIndex = 0; v.envPitchTimeSec = 0.0;
        } else if (!(mayCarry && Envelope.envCarry(v.activePitchEnvLoop))) {
            v.envPitchValue = Envelope.seedPfRole(v.activePitchEnv, v.activePitchEnvLoop, v.activePitchEnvSustain, box);
            v.envPitchIndex = box.idx;
            v.envPitchTimeSec = box.time;
        }
        if (!v.hasFilterEnv) {
            v.envFilterValue = 0.5; v.envFilterIndex = 0; v.envFilterTimeSec = 0.0;
        } else if (!(mayCarry && Envelope.envCarry(v.activeFilterEnvLoop))) {
            v.envFilterValue = Envelope.seedPfRole(v.activeFilterEnv, v.activeFilterEnvLoop, v.activeFilterEnvSustain, box);
            v.envFilterIndex = box.idx;
            v.envFilterTimeSec = box.time;
        }
    }

    private static int cueRowLimit(Cell.Cue c) {
        int a = c.inst0.type == INST_PATLEN || c.inst0.type == INST_HALTAT ? c.inst0.rows : 64;
        int b = c.inst1.type == INST_PATLEN || c.inst1.type == INST_HALTAT ? c.inst1.rows : 64;
        return imin(a, b);
    }

    private static void deferNote(Voice v, int tick, int note) {
        v.noteDelayTick = tick;
        v.delayedNote = note;
        v.delayedInst = 0;
        v.delayedVol = -1;
    }

    static void applyTrackerRow(Engine e) {
        Cell.Cue cue = e.cueAt(e.cuePos);
        int channels = e.channelCount();
        if (!e.patternDelayActive) e.sexWinningChannel = -1;
        e.finePatternDelayExtra = 0;

        for (int vi = 0; vi < channels; vi++) {
            int patNum = cue.raw[vi] & 0x7fff;
            int ext1 = 0, ext2 = 0;
            boolean hasExt1 = false, hasExt2 = false;
            Cell row;

            if (patNum == PATTERN_EMPTY) continue;
            int patIdx = iclamp(patNum, 0, NUM_PATTERNS - 1);
            Cell[] rows = e.patternRead(patIdx);
            Cell rawRow = rows[e.rowIndex];
            Voice voice = e.voices[vi];

            /* ── Pattern Ditto (effect 7) row-time expansion ── */
            int n = e.rowIndex;
            boolean isArmer = rawRow.effect == OP_7 && rawRow.effectArg != 0;
            if (isArmer) {
                int length = (rawRow.effectArg >> 8) & 0xff;
                int repeats = rawRow.effectArg & 0xff;
                if (length > 0 && repeats > 0 && length <= n) {
                    int patLen = cueRowLimit(cue);
                    voice.dittoSourceStart = n - length;
                    voice.dittoLength = length;
                    voice.dittoEndRow = imin(n + length * repeats - 1, patLen - 1);
                    voice.dittoActive = true;
                }
            }

            int dittoArmRow = voice.dittoSourceStart + voice.dittoLength;
            if (voice.dittoActive && n >= dittoArmRow && n <= voice.dittoEndRow) {
                int rel = (n - voice.dittoSourceStart) % voice.dittoLength;
                Cell src = rows[voice.dittoSourceStart + rel];
                boolean volIsSet = !(rawRow.volumeEff == 3 && rawRow.volume == 0);
                boolean panIsSet = e.tsWideCells
                    ? !(rawRow.panEff == 3 && rawRow.azimuth == 0 && rawRow.elevation == 0)
                    : !(rawRow.panEff == 3 && rawRow.pan == 0);
                int destOp = isArmer ? 0 : rawRow.effect;
                int destArg = isArmer ? 0 : rawRow.effectArg;
                int effOp, effArg;
                if (destOp != 0) { effOp = destOp; effArg = destArg; }
                else if (src.effect != OP_7) { effOp = src.effect; effArg = src.effectArg; }
                else { effOp = 0; effArg = 0; }

                Cell dittoRow = new Cell();
                /* The note travels whole: its word and its sixteenths. */
                dittoRow.note = rawRow.note != 0 ? rawRow.note : src.note;
                dittoRow.noteFine = rawRow.note != 0 ? rawRow.noteFine : src.noteFine;
                dittoRow.noteFrac = rawRow.note != 0 ? rawRow.noteFrac : src.noteFrac;
                dittoRow.instrment = rawRow.instrment != 0 ? rawRow.instrment : src.instrment;
                dittoRow.volume = volIsSet ? rawRow.volume : src.volume;
                dittoRow.volumeEff = volIsSet ? rawRow.volumeEff : src.volumeEff;
                dittoRow.pan = panIsSet ? rawRow.pan : src.pan;
                dittoRow.panEff = panIsSet ? rawRow.panEff : src.panEff;
                dittoRow.azimuth = panIsSet ? rawRow.azimuth : src.azimuth;
                dittoRow.elevation = panIsSet ? rawRow.elevation : src.elevation;
                dittoRow.effect = effOp;
                dittoRow.effectArg = effArg;
                boolean dittoUsedSrc = destOp == 0 && effOp != 0;
                dittoRow.effect2 = dittoUsedSrc ? src.effect2 : rawRow.effect2;
                dittoRow.effectArg2 = dittoUsedSrc ? src.effectArg2 : rawRow.effectArg2;
                row = dittoRow;
            } else {
                row = rawRow;
            }

            /* Reset per-row transient state. */
            voice.cutAtTick = -1;
            voice.noteDelayTick = -1;
            voice.noteActionTick = -1;
            voice.delayedAction = -1;
            voice.slideMode = 0;
            voice.slideArg = 0;
            voice.arpActive = false;
            voice.tremorOn = 0;
            voice.vibratoActive = false;
            voice.tremoloActive = false;
            voice.panbrelloActive = false;
            voice.retrigActive = false;
            voice.tempoSlideDir = 0;
            voice.wSlideDir = 0;
            voice.volColSlideUp = 0; voice.volColSlideDown = 0;
            voice.panColSlideRight = 0; voice.panColSlideLeft = 0;
            voice.chanPanSlideRight = 0; voice.chanPanSlideLeft = 0;
            voice.spatialSlideActive = false;
            voice.nSlideDir = 0;
            voice.rowEffect = row.effect;
            voice.rowEffectArg = row.effectArg;
            voice.rowVolume = voice.noteVolume;

            /* ── Note ── */
            boolean toneG = row.effect == OP_G || row.effect == OP_L;
            double note = row.noteValue();
            int sArg = sDelayArg(e, row);
            int sDelayTick = sArg < 0 ? 0 : (sArg >> 8) & 0xf;

            if (note == 0x0000) {
                boolean pitchFx = row.effect == OP_E || row.effect == OP_F || row.effect == OP_G;
                if (row.instrment != 0 && pitchFx && voice.noteVal >= 0x20) {
                    int trigVol = row.volumeEff == 0 ? row.volume : -1;
                    Trigger.applyDuplicateCheck(e, vi, row.instrment, voice.noteVal);
                    Trigger.maybeSpawnBackgroundForNna(e, voice, vi, row.instrment);
                    Trigger.triggerMetaOrNote(e, voice, vi, voice.noteVal, row.instrment, trigVol, false);
                } else if (row.instrment != 0 && !e.instruments[row.instrment].isMeta()) {
                    voice.instrumentId = row.instrment;
                    Inst newInst = e.instruments[voice.instrumentId];
                    Patch newPatch = Inst.resolvePatch(newInst, voice.noteVal, Trigger.narrowVolAxis(e, voice.noteVolume));
                    applyInstrumentChange(e, voice, newInst, newPatch, false, false);
                }
                scheduleDxnyAction(voice, sArg, sDelayTick);
            } else if (note == 0x0001) {
                if (sDelayTick > 0) deferNote(voice, sDelayTick, 0x0001);
                else {
                    voice.keyOff = true;
                    Envelope.applyKeyLift(voice, e.instruments[voice.instrumentId]);
                }
                scheduleDxnyAction(voice, sArg, sDelayTick);
            } else if (note == 0x0002) {
                if (sDelayTick > 0) deferNote(voice, sDelayTick, 0x0002);
                else {
                    Sampler.startCutRamp(e, voice);
                    Trigger.cutLayerChildren(e, vi);
                }
                scheduleDxnyAction(voice, sArg, sDelayTick);
            } else if (note == 0x0004) {
                if (sDelayTick > 0) deferNote(voice, sDelayTick, 0x0004);
                else Sampler.startFastFade(e, voice);
                scheduleDxnyAction(voice, sArg, sDelayTick);
            } else if (note == 0x0003) {
                if (sDelayTick > 0) deferNote(voice, sDelayTick, 0x0003);
                else voice.noteFading = true;
                scheduleDxnyAction(voice, sArg, sDelayTick);
            } else if (note >= 0x0005 && note <= 0x000f) {
                /* reserved sentinel range */
            } else if (note >= 0x0010 && note <= 0x001f) {
                if (sDelayTick > 0) {
                    deferNote(voice, sDelayTick, (int) note);
                    voice.delayedInterruptArg = interruptArgOf(e, row);
                } else {
                    int k = (int) note - 0x0010;
                    e.pendingInterrupts |= 1 << k;
                    e.interruptArgs[k] = interruptArgOf(e, row) & 0xffff;
                }
            } else {
                if (toneG && voice.active) {
                    voice.tonePortaTarget = voice.metaForeground && voice.layerFixedNote >= 0
                        ? -1
                        : dclamp(note + voice.metaForegroundDetune, 0x20, 0xffff);
                    if (row.instrment != 0 && !e.instruments[row.instrment].isMeta()) {
                        boolean mayCarry = !voice.keyOff && !voice.noteFading && row.instrment == voice.instrumentId;
                        voice.instrumentId = row.instrment;
                        Inst newInst = e.instruments[voice.instrumentId];
                        Patch newPatch = Inst.resolvePatch(newInst, voice.noteVal, Trigger.narrowVolAxis(e, voice.noteVolume));
                        applyInstrumentChange(e, voice, newInst, newPatch, true, mayCarry);
                    }
                } else if (sArg >= 0) {
                    voice.noteDelayTick = sDelayTick;
                    voice.delayedNote = note;
                    voice.delayedInst = row.instrment;
                    voice.delayedVol = row.volumeEff == 0 ? row.volume : -1;
                    voice.delayedGlide = toneG; /* a G/L on a silent lane: a glide's trigger (lyrics) */
                    scheduleDxnyAction(voice, sArg, sDelayTick);
                } else {
                    int trigVol = row.volumeEff == 0 ? row.volume : -1;
                    Trigger.applyDuplicateCheck(e, vi, row.instrment, note);
                    Trigger.maybeSpawnBackgroundForNna(e, voice, vi, row.instrment);
                    /* toneG here = a G/L on a silent lane: it triggers, but as
                     * a glide (a lyric instrument's syllable, Trigger.lyricTrigger). */
                    Trigger.triggerMetaOrNote(e, voice, vi, note, row.instrment, trigVol, toneG);
                    scheduleDxnyAction(voice, sArg, sDelayTick);
                }
            }

            /* ── Volume / pan columns ── */
            Trigger.applyVolColumn(e, voice, row.volume, row.volumeEff);
            if (e.tsWideCells) Trigger.applyPanColumnWide(e, voice, row);
            else Trigger.applyPanColumn(e, voice, row.pan, row.panEff);

            /* ── Effect columns (a `:` hands its argument to the other slot) ── */
            if (e.tsWideCells) {
                if (row.effect == OP_COLON && row.effect2 != OP_COLON) { ext2 = row.effectArg; hasExt2 = true; }
                else if (row.effect2 == OP_COLON && row.effect != OP_COLON) { ext1 = row.effectArg2; hasExt1 = true; }
            }
            Effects.applyEffectRow(e, voice, vi, row.effect, row.effectArg, ext1, hasExt1);
            if (e.tsWideCells && row.effect2 != 0) {
                Effects.applyEffectRow(e, voice, vi, row.effect2, row.effectArg2, ext2, hasExt2);
            }
        }
    }

    private static boolean cueIsHalt(Cell.Cue c) {
        return c.inst0.type == INST_HALT || c.inst0.type == INST_HALTAT ||
               c.inst1.type == INST_HALT || c.inst1.type == INST_HALTAT;
    }

    private static final Cell.PlayInst NOP = new Cell.PlayInst(INST_NOP, 0, 0);

    private static Cell.PlayInst cueFlowInstruction(Cell.Cue c) {
        int t0 = c.inst0.type, t1 = c.inst1.type;
        if (t0 == INST_GOBACK || t0 == INST_SKIP || t0 == INST_JUMP) return c.inst0;
        if (t1 == INST_GOBACK || t1 == INST_SKIP || t1 == INST_JUMP) return c.inst1;
        return NOP;
    }

    private static void advanceTrackerCue(Engine e) {
        Cell.Cue cue = e.cueAt(e.cuePos);
        if (cueIsHalt(cue)) { e.isPlaying = false; return; }
        Cell.PlayInst instr = cueFlowInstruction(cue);
        switch (instr.type) {
        case INST_GOBACK: e.cuePos = imax(e.cuePos - instr.arg, 0); break;
        case INST_SKIP: e.cuePos = imin(e.cuePos + instr.arg, NUM_CUES - 1); break;
        case INST_JUMP: e.cuePos = iclamp(instr.arg, 0, NUM_CUES - 1); break;
        default: e.cuePos = imin(e.cuePos + 1, NUM_CUES - 1); break;
        }
        e.position = e.cuePos;
    }

    static void reconstructDittoState(Engine e, int startRow) {
        Cell.Cue cue = e.cueAt(e.cuePos);
        int patLen = cueRowLimit(cue);
        int limit = imin(startRow, patLen);
        int channels = e.channelCount();
        for (int vi = 0; vi < channels; vi++) {
            Voice voice = e.voices[vi];
            voice.dittoActive = false;
            voice.dittoSourceStart = 0;
            voice.dittoLength = 0;
            voice.dittoEndRow = 0;
            int patNum = cue.raw[vi] & 0x7fff;
            if (patNum == PATTERN_EMPTY) continue;
            Cell[] rows = e.patternRead(iclamp(patNum, 0, NUM_PATTERNS - 1));
            for (int n = 0; n < limit; n++) {
                Cell rawRow = rows[n];
                if (rawRow.effect != OP_7 || rawRow.effectArg == 0) continue;
                int length = (rawRow.effectArg >> 8) & 0xff;
                int repeats = rawRow.effectArg & 0xff;
                if (length > 0 && repeats > 0 && length <= n) {
                    voice.dittoSourceStart = n - length;
                    voice.dittoLength = length;
                    voice.dittoEndRow = imin(n + length * repeats - 1, patLen - 1);
                    voice.dittoActive = true;
                }
            }
        }
    }

    private static void resetPatternLoopState(Engine e) {
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = e.voices[i];
            v.loopStartRow = 0;
            v.loopCount = 0;
            v.dittoActive = false;
            v.dittoSourceStart = 0;
            v.dittoLength = 0;
            v.dittoEndRow = 0;
        }
    }

    /** Advance to the next row: pending B/C jumps and pattern-delay repeats. */
    static void advanceRow(Engine e) {
        advanceRowPosition(e);
        applyTrackerRow(e);
    }

    /** advanceRow's first half: move to the row that plays next — the same
     *  row again while a pattern delay repeats it, else wherever a pending
     *  jump or the cue's end sends it — without applying it. */
    static void advanceRowPosition(Engine e) {
        if (e.patternDelayRemaining > 0) {
            e.patternDelayRemaining--;
            e.patternDelayActive = true;
            return;
        }
        e.patternDelayActive = false;

        int pendingB = e.pendingOrderJump;
        int pendingC = e.pendingRowJump;
        boolean pendingLocal = e.pendingRowJumpLocal;
        e.pendingOrderJump = -1;
        e.pendingRowJump = -1;
        e.pendingRowJumpLocal = false;

        if (pendingB >= 0) {
            e.cuePos = imin(pendingB, NUM_CUES - 1);
            e.rowIndex = pendingC >= 0 ? pendingC : 0;
            e.position = e.cuePos;
            resetPatternLoopState(e);
        } else if (pendingC >= 0 && pendingLocal) {
            e.rowIndex = iclamp(pendingC, 0, 63);
        } else if (pendingC >= 0) {
            advanceTrackerCue(e);
            e.rowIndex = iclamp(pendingC, 0, 63);
            resetPatternLoopState(e);
        } else {
            e.rowIndex++;
            if (e.rowIndex >= cueRowLimit(e.cueAt(e.cuePos))) {
                e.rowIndex = 0;
                advanceTrackerCue(e);
                resetPatternLoopState(e);
            }
        }
    }
}
