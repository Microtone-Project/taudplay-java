/*
 * Per-tick voice processing — port of tick.js (taudplay-c tick.c).
 *
 * A mid-tick note-delay trigger (S $Dx) may swap the voice's instrument, so
 * nothing below caches one across it: every use indexes voice.instrumentId
 * afresh.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Tick {
    private Tick() {}

    private static final int FUNK_JITTER_DIVISOR = 16;
    private static final int EXT_FUNK_MODE = 3;

    private static int funkHop(int funkMode, int loopLen) { return imax(1, loopLen >> (funkMode & 3)); }

    private static int funkGridTop(int hop, int loopStart, int loopLen, int sampleLen) {
        return (int) floor((double) (sampleLen - loopLen - loopStart) / hop);
    }

    private static int funkGridIndex(int pos, int hop, int loopStart, int K) {
        if (pos < 0) return 0;
        return iclamp((int) js_round((double) (pos - loopStart) / hop), 0, K);
    }

    private static int uniformInt(Engine e, int n) {
        return imin((int) floor(e.random() * n), n - 1);
    }

    static int funkWalkStep(int funkMode, int walk, int loopStart, int loopLen, int sampleLen) {
        int hop = funkHop(funkMode, loopLen);
        int K = funkGridTop(hop, loopStart, loopLen, sampleLen);
        if (K <= 0) return loopStart;
        int n = funkGridIndex(walk, hop, loopStart, K);
        int next = (funkMode >> 2) == 1 ? (n - 1 < 0 ? K : n - 1) : (n + 1 > K ? 0 : n + 1);
        return loopStart + next * hop;
    }

    static int funkWalkPointer(Engine e, int funkMode, int walk, int loopStart, int loopLen, int sampleLen) {
        int family = funkMode >> 2;
        if (family < 2) return walk;
        int hop = funkHop(funkMode, loopLen);
        int K = funkGridTop(hop, loopStart, loopLen, sampleLen);
        if (K <= 0) return loopStart;
        if (family == 3) return loopStart + uniformInt(e, K + 1) * hop;
        int n = funkGridIndex(walk, hop, loopStart, K);
        int reach = imax(1, (int) js_round((double) (K + 1) / FUNK_JITTER_DIVISOR));
        int thrown = n + uniformInt(e, 2 * reach + 1) - reach;
        return loopStart + iclamp(thrown, 0, K) * hop;
    }

    private static void armModXfade(Engine e, int instId) {
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = e.voices[i];
            if (v.active && v.instrumentId == instId) v.modXfade = MOD_XFADE_SAMPLES;
        }
        for (int i = 0; i < e.bgCount; i++) {
            Voice bg = e.bg[i];
            if (bg.active && bg.instrumentId == instId) bg.modXfade = MOD_XFADE_SAMPLES;
        }
    }

    /** One quarter tick of every sounding PSG pair's auto-advance — a lane's,
     *  a ghost's, an FM operator's (§5.5.4). Quarter 0 is the tick pass's;
     *  1…3 are the mixer's, at the frames a quarter of the tick apart. */
    static void psgQuarterPass(Engine e) {
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = e.voices[i];
            if (v.psg != null && v.active) Psg.quarterTick(v.psg);
        }
        for (int i = 0; i < e.bgCount; i++) {
            Voice v = e.bg[i];
            if (v.psg != null && v.active) Psg.quarterTick(v.psg);
        }
    }

    /** Whether any voice is sounding a PSG pair: the mixer runs quarters only then. */
    static boolean psgLive(Engine e) {
        if (!e.hasPsg) return false;
        for (int i = 0; i < MAX_VOICES; i++) if (e.voices[i].psg != null && e.voices[i].active) return true;
        for (int i = 0; i < e.bgCount; i++) if (e.bg[i].psg != null && e.bg[i].active) return true;
        return false;
    }

    private static void restartVoice(Engine e, Voice v) {
        v.samplePos = v.activeSamplePlayStart;
        v.keyOff = false;
        v.envIndex = 0; v.envTimeSec = 0.0;
        v.envPanIndex = 0; v.envPanTimeSec = 0.0;
        v.envPan = v.activePanEnv[0].value / 255.0;
        Envelope.PfBox box = e.pfBox;
        if (v.hasPitchEnv) {
            v.envPitchValue = Envelope.seedPfRole(v.activePitchEnv, v.activePitchEnvLoop, v.activePitchEnvSustain, box);
            v.envPitchIndex = box.idx;
            v.envPitchTimeSec = box.time;
        } else {
            v.envPitchValue = 0.5; v.envPitchIndex = 0; v.envPitchTimeSec = 0.0;
        }
        if (v.hasFilterEnv) {
            v.envFilterValue = Envelope.seedPfRole(v.activeFilterEnv, v.activeFilterEnvLoop, v.activeFilterEnvSustain, box);
            v.envFilterIndex = box.idx;
            v.envFilterTimeSec = box.time;
        } else {
            v.envFilterValue = 0.5; v.envFilterIndex = 0; v.envFilterTimeSec = 0.0;
        }
        v.fadeoutVolume = 1.0;
        v.autoVibPhase = 0;
        v.autoVibTicksSinceTrigger = 0;
        v.filterY1 = 0.0; v.filterY2 = 0.0; v.filterX1 = 0.0; v.filterX2 = 0.0;
        v.right.filterY1 = 0.0; v.right.filterY2 = 0.0;
        v.right.filterX1 = 0.0; v.right.filterX2 = 0.0;
        v.right.bitcrusherCounter = 0;
        v.right.bitcrusherHeld = 0.0;
        v.right.nesDpcmCounter = 63;
        /* An OPL channel restarts the way the chip re-keys one: the attack
         * from wherever each envelope stands, the phase from zero (§5.5.2).
         * Unless retrigHandOff gave its sound to a ghost, which took those
         * envelopes along: then it keys on from silence, as for a fresh note. */
        boolean carry = !v.retrigFresh;
        v.retrigFresh = false;
        if (v.opl != null && v.opl.patch != null) Opl.keyOn(v.opl, v.opl.patch, carry);
        /* …and a PSG pair the same way; an oscillator with its X flag set
         * restarts its internal status here too, as at any key-on (§5.5.4). */
        if (v.psg != null && v.psg.patch != null) Psg.keyOn(v.psg, v.psg.patch, carry);
        /* A voice sounding a formant instrument says its syllable again (item 212). */
        if (v.formant != null) Formant.restart(e, v.formant);
    }

    private static void advanceSampleMod(Engine e, Voice voice) {
        if (voice.modPeriod == 0 || !voice.active || voice.modExtended) return;
        Inst inst = e.instruments[voice.instrumentId];
        if (inst.modOp == MOD_OFF) return;
        int sampleLen = imax(voice.activeSampleLength, 1);
        ModGeom g = SampleMod.resolveModGeom(voice.modGeom, inst, voice.activeSampleLoopStart, voice.activeSampleLoopEnd, sampleLen);
        if (!g.live) return;
        if (++voice.modTickCount < voice.modPeriod) return;
        voice.modTickCount = 0;
        int step = SampleMod.MOD_STEP[inst.modOp];
        if (inst.modOp == MOD_INVERT) {
            for (int n = 0; n < MOD_WALK_SCAN; n++) {
                voice.modWritePos = (voice.modWritePos + 1) % imax(g.dl, 1);
                int i = g.ds + voice.modWritePos;
                if (SampleMod.modTouches(g, inst.modInvert, i)) { inst.toggleModBit(i, sampleLen); break; }
            }
            return;
        }
        inst.snapshotModState();
        if (SampleMod.isRolOp(inst.modOp)) {
            inst.modRot = (inst.modRot + step) % g.dl;
            inst.modOn = inst.modRot != 0;
        } else if (SampleMod.isJumpOp(inst.modOp)) {
            inst.modRot = SampleMod.jumpRot(e, inst.modOp, g.dl);
            inst.modOn = inst.modRot != 0;
        } else if (SampleMod.isRndOp(inst.modOp)) {
            inst.modScatter = SampleMod.scatterReach(inst.modOp, g.dl);
            inst.modSeed = SampleMod.scatterSeed(e);
            inst.modOn = inst.modScatter > 0;
        } else {
            inst.modSub = (inst.modSub + step) & 0xff;
            inst.modOn = inst.modSub != 0;
        }
        armModXfade(e, voice.instrumentId);
    }

    private static void stepExtendedModOnce(Engine e, Voice voice, Inst inst, ModGeom g, int sampleLen) {
        inst.modStepIndex++;
        SampleMod.ExtOp op = SampleMod.decodeExtOp(inst.modOpExt);
        int dl = imax(g.dl, 1);
        switch (op.kind) {
        case EXT_KIND_NOOP:
            break;
        case EXT_KIND_INVERT: {
            for (int n = 0; n < MOD_WALK_SCAN; n++) {
                voice.modWritePos = (voice.modWritePos + 1) % dl;
                int i = g.ds + voice.modWritePos;
                if (SampleMod.extModTouches(g, inst.modInvert, inst.modF, inst.modStepIndex, i)) {
                    inst.toggleModBit(i, sampleLen);
                    break;
                }
            }
            break;
        }
        case EXT_KIND_INVERT_JIT: {
            int reach = imax(1, (int) js_round(SampleMod.extJitterFrac((int) op.param) * dl));
            for (int n = 0; n < MOD_WALK_SCAN; n++) {
                int jitter = (int) floor(e.random() * (2 * reach + 1)) - reach;
                voice.modWritePos = (((voice.modWritePos + 1 + jitter) % dl) + dl) % dl;
                int i = g.ds + voice.modWritePos;
                if (SampleMod.extModTouches(g, inst.modInvert, inst.modF, inst.modStepIndex, i)) {
                    inst.toggleModBit(i, sampleLen);
                    break;
                }
            }
            break;
        }
        case EXT_KIND_FUNK:
        case EXT_KIND_FUNK_JIT: {
            inst.modFunkLen = dl;
            inst.modFunkWalk = funkWalkStep(EXT_FUNK_MODE, inst.modFunkWalk, g.ds, dl, sampleLen);
            int pos = inst.modFunkWalk;
            if (op.kind == EXT_KIND_FUNK_JIT) {
                int hop = funkHop(EXT_FUNK_MODE, dl);
                int K = funkGridTop(hop, g.ds, dl, sampleLen);
                if (K > 0) {
                    int reach = imax(1, (int) js_round(SampleMod.extJitterFrac((int) op.param) * (K + 1)));
                    int n = funkGridIndex(inst.modFunkWalk, hop, g.ds, K);
                    int thrown = n + uniformInt(e, 2 * reach + 1) - reach;
                    pos = g.ds + iclamp(thrown, 0, K) * hop;
                }
            }
            inst.modFunkPos = pos;
            break;
        }
        case EXT_KIND_MIRROR:
            inst.modExtMirror = !inst.modExtMirror;
            inst.modOn = inst.modExtMirror;
            break;
        case EXT_KIND_SWAP:
            if (dl >= 2) {
                int a = g.ds + (int) floor(e.random() * dl);
                int b2 = g.ds + (int) floor(e.random() * dl);
                if (b2 == a) b2 = g.ds + ((a - g.ds + 1) % dl);
                inst.modExtSwapA = a;
                inst.modExtSwapB = b2;
                inst.modOn = true;
            }
            break;
        case EXT_KIND_ROL:
            inst.snapshotModState();
            inst.modRot = ((inst.modRot + (int) op.param) % dl + dl) % dl;
            inst.modOn = inst.modRot != 0;
            armModXfade(e, voice.instrumentId);
            break;
        case EXT_KIND_JUMP_N: {
            int param = (int) op.param;
            int slice = imax(1, (int) js_round((double) dl / param));
            int idx = imin((int) floor(e.random() * param), param - 1);
            inst.snapshotModState();
            inst.modRot = (idx * slice) % dl;
            inst.modOn = true;
            armModXfade(e, voice.instrumentId);
            break;
        }
        case EXT_KIND_JUMP_N_BOUNDED: {
            inst.snapshotModState();
            int reach = imax(1, (int) js_round((double) dl / op.param));
            int thrown = (int) floor(e.random() * (2 * reach + 1)) - reach;
            inst.modRot = ((thrown % dl) + dl) % dl;
            inst.modOn = true;
            armModXfade(e, voice.instrumentId);
            break;
        }
        case EXT_KIND_SCATTER:
            inst.snapshotModState();
            inst.modScatter = imax(1, imin((int) js_round(dl * op.param), dl));
            inst.modSeed = SampleMod.scatterSeed(e);
            inst.modOn = inst.modScatter > 0;
            armModXfade(e, voice.instrumentId);
            break;
        case EXT_KIND_SUB:
            inst.snapshotModState();
            inst.modSub = (inst.modSub + (int) op.param) & 0xff;
            inst.modOn = inst.modSub != 0;
            armModXfade(e, voice.instrumentId);
            break;
        case EXT_KIND_XOR:
            inst.snapshotModState();
            inst.modXor = (inst.modXor ^ (int) op.param) & 0xff;
            inst.modOn = inst.modXor != 0;
            armModXfade(e, voice.instrumentId);
            break;
        case EXT_KIND_BITROT:
            inst.modBitRot = (((inst.modBitRot + (int) op.param) % 8) + 8) % 8;
            inst.modOn = inst.modBitRot != 0;
            break;
        case EXT_KIND_BITPERM:
            inst.modBitPermIdx = (int) op.param;
            inst.modBitPermOn = !inst.modBitPermOn;
            inst.modOn = inst.modBitPermOn;
            break;
        default:
            break;
        }
    }

    /** Argument-extension clock: stepped per SAMPLE from the mixer. */
    static void advanceSampleModExtended(Engine e, Voice voice, double spt) {
        if (!voice.modExtended || !voice.active) return;
        Inst inst = e.instruments[voice.instrumentId];
        if (inst.modOpExt == 0 || voice.modStepTicks <= 0) return;
        int sampleLen = imax(voice.activeSampleLength, 1);
        ModGeom g = SampleMod.resolveModGeom(voice.modGeom, inst, voice.activeSampleLoopStart, voice.activeSampleLoopEnd, sampleLen);
        if (!g.live) return;
        voice.modSamplesIntoStep += 1.0;
        double periodSamples = voice.modStepTicks * spt;
        if (periodSamples <= 0 || voice.modSamplesIntoStep < periodSamples) return;
        voice.modSamplesIntoStep -= periodSamples;
        stepExtendedModOnce(e, voice, inst, g, sampleLen);
    }

    private static void fireNoteAction(Engine e, Voice voice, int vi) {
        switch (voice.delayedAction) {
        case 0:
            voice.keyOff = true;
            Envelope.applyKeyLift(voice, e.instruments[voice.instrumentId]);
            break;
        case 1:
            Sampler.startCutRamp(e, voice);
            Trigger.cutLayerChildren(e, vi);
            break;
        case 2:
            break;
        case 3:
            voice.noteFading = true;
            break;
        case 4:
            voice.keyOff = true;
            Envelope.forceKeyLift(voice);
            for (int i = 0; i < e.bgCount; i++) {
                Voice bg = e.bg[i];
                if (Spatial.isSoundingChild(e, bg, vi)) { bg.keyOff = true; Envelope.forceKeyLift(bg); }
            }
            break;
        default:
            break;
        }
    }

    private static void updateFilterCutoff(Voice v) {
        if (v.hasFilterEnv && v.filterEnvOn) {
            if (v.filterSfMode) {
                int baseCut = v.activeDefaultCutoff < 0xffff ? v.activeDefaultCutoff : 13500;
                v.currentCutoff = (int) dclamp(trunc(baseCut * v.envFilterValue), 0, 0xffff);
            } else {
                int baseCut = v.activeDefaultCutoff < 255 ? v.activeDefaultCutoff : 254;
                v.currentCutoff = (int) dclamp(trunc(baseCut * v.envFilterValue), 0, 254);
            }
        }
    }

    private static int pitchEnvDelta(Voice v) {
        return v.hasPitchEnv && v.pitchEnvOn
            ? (int) trunc(((v.envPitchValue - 0.5) * 2.0 * 16.0 * 4096.0) / 12.0)
            : 0;
    }

    /** What a row scheduled for a later tick of itself: the note cut (S $Cx),
     *  the delayed note event (S $Dx) and the S $Dxny follow-up action. They
     *  fire at the boundary that STARTS their tick, as a row's own events
     *  fire at the boundary that starts the row (TAUD_ENGINE_SPEC.md §2.2,
     *  §6.3): the mixer's clock runs this after the tick pass and the
     *  tickInRow increment, whenever the row goes on. */
    static void applyTickEvents(Engine e) {
        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Voice voice = e.voices[vi];
            if (voice.cutAtTick != e.tickInRow && voice.noteDelayTick != e.tickInRow &&
                voice.noteActionTick != e.tickInRow) continue;

            if (voice.cutAtTick == e.tickInRow) {
                voice.noteVolume = 0;
                voice.rowVolume = 0;
                voice.noteWasCut = true;
            }

            if (voice.noteDelayTick == e.tickInRow) {
                switch (voice.delayedNote) {
                case 0x0001:
                    voice.keyOff = true;
                    Envelope.applyKeyLift(voice, e.instruments[voice.instrumentId]);
                    break;
                case 0x0002:
                    Sampler.startCutRamp(e, voice);
                    Trigger.cutLayerChildren(e, vi);
                    break;
                case 0x0003:
                    voice.noteFading = true;
                    break;
                case 0x0004:
                    Sampler.startFastFade(e, voice);
                    break;
                default:
                    if (voice.delayedNote >= 0x0010 && voice.delayedNote <= 0x001f) {
                        e.pendingInterrupts |= 1 << (voice.delayedNote - 0x0010);
                        e.interruptArgs[voice.delayedNote - 0x0010] = voice.delayedInterruptArg & 0xffff;
                        break;
                    }
                    Trigger.applyDuplicateCheck(e, vi, voice.delayedInst, voice.delayedNote);
                    Trigger.maybeSpawnBackgroundForNna(e, voice, vi, voice.delayedInst);
                    Trigger.triggerMetaOrNote(e, voice, vi, voice.delayedNote, voice.delayedInst, voice.delayedVol);
                    break;
                }
                voice.noteDelayTick = -1;
            }

            if (voice.noteActionTick == e.tickInRow) {
                fireNoteAction(e, voice, vi);
                voice.noteActionTick = -1;
            }
        }
    }

    static void applyTrackerTick(Engine e) {
        double tickSec = 2.5 / e.bpm;
        double spt = e.rate * tickSec;

        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Voice voice = e.voices[vi];
            if (!voice.active && voice.noteDelayTick < 0 && voice.noteActionTick < 0) continue;

            if (!voice.active) {
                Envelope.advanceEnvelope(e, voice, tickSec);
                voice.envVolStep = spt > 0.0 ? (voice.envVolume - voice.envVolMix) / spt : 0.0;
                continue;
            }

            /* Pitch slides (E/F coarse on tick > 0). */
            if (e.tickInRow > 0 && (voice.slideMode == 1 || voice.slideMode == 2)) {
                int nv;
                if (e.toneMode == 1) nv = Tables.amigaSlideTick(voice, voice.slideArg);
                else if (e.toneMode == 2) nv = Tables.linearFreqSlideTick(voice, voice.slideArg);
                else nv = voice.noteVal + voice.slideArg;
                voice.noteVal = iclamp(nv, 0x20, 0xffff);
                voice.basePitch = voice.noteVal;
            }

            /* Tone portamento (G). */
            if (voice.tonePortaTarget >= 0 && e.tickInRow > 0) {
                int target = voice.tonePortaTarget;
                int sp = voice.tonePortaSpeed;
                if (e.toneMode == 2) {
                    if (voice.linearFreq < 0.0) voice.linearFreq = Tables.noteValToFreqHz(voice.noteVal);
                    double targetFreq = Tables.noteValToFreqHz(target);
                    double dir = targetFreq > voice.linearFreq ? 1.0 : -1.0;
                    voice.linearFreq += dir * sp;
                    if ((dir > 0 && voice.linearFreq >= targetFreq) || (dir < 0 && voice.linearFreq <= targetFreq)) {
                        voice.linearFreq = targetFreq;
                        voice.noteVal = target;
                        voice.tonePortaTarget = -1;
                    } else {
                        voice.noteVal = iclamp(Tables.freqHzToNoteVal(voice.linearFreq), 0x20, 0xffff);
                    }
                    voice.basePitch = voice.noteVal;
                    voice.amigaPeriod = -1.0;
                } else {
                    int delta = target > voice.noteVal ? sp : -sp;
                    voice.noteVal += delta;
                    if ((delta > 0 && voice.noteVal >= target) || (delta < 0 && voice.noteVal <= target)) {
                        voice.noteVal = target;
                        voice.tonePortaTarget = -1;
                    }
                    voice.basePitch = voice.noteVal;
                    voice.amigaPeriod = -1.0;
                    voice.linearFreq = -1.0;
                }
            }

            /* Volume slides (D coarse on tick > 0). */
            if (e.tickInRow > 0 && voice.slideMode == 5) {
                voice.noteVolume = iclamp(voice.noteVolume + voice.slideArg * e.volStep, 0, e.volMax);
                voice.rowVolume = voice.noteVolume;
            }

            if (e.tickInRow > 0) {
                if (voice.volColSlideUp != 0) {
                    voice.noteVolume = imin(voice.noteVolume + voice.volColSlideUp, e.volMax);
                    voice.rowVolume = voice.noteVolume;
                }
                if (voice.volColSlideDown != 0) {
                    voice.noteVolume = imax(voice.noteVolume - voice.volColSlideDown, 0);
                    voice.rowVolume = voice.noteVolume;
                }
                if (voice.nSlideDir != 0) {
                    voice.channelVolume = iclamp(voice.channelVolume + voice.nSlideDir * e.volStep, 0, e.volMax);
                }
                if (voice.panColSlideRight != 0) Spatial.applyNotePanSlide(e, voice, voice.panColSlideRight);
                if (voice.panColSlideLeft != 0) Spatial.applyNotePanSlide(e, voice, -voice.panColSlideLeft);
                if (voice.chanPanSlideRight != 0) Spatial.applyPanSlide(e, voice, voice.chanPanSlideRight);
                if (voice.chanPanSlideLeft != 0) Spatial.applyPanSlide(e, voice, -voice.chanPanSlideLeft);
                if (voice.spatialSlideActive) {
                    double[] step = new double[2];
                    Spatial.stepTowardTarget(voice.panAzimuth, voice.panElevation,
                        voice.spatialTargetAz, voice.spatialTargetEl, voice.mem.z / 8.0, step);
                    Spatial.applyPanSet(e, voice, step[0]);
                    voice.panElevation = step[1];
                }
            }

            /* Tremor (I). */
            if (voice.tremorOn != 0) {
                voice.tremorTickInPhase++;
                int limit = voice.tremorPhaseOn ? voice.tremorOnTime : voice.tremorOffTime;
                if (voice.tremorTickInPhase >= limit) {
                    voice.tremorTickInPhase = 0;
                    voice.tremorPhaseOn = !voice.tremorPhaseOn;
                }
                if (!voice.tremorPhaseOn) voice.rowVolume = 0;
            }

            /* Vibrato (H/U). */
            int pitchToMixer = voice.noteVal;
            if (voice.vibratoActive) {
                int sine = Tables.lfoSampleWide(e, voice.vibratoLfoPos, voice.vibratoWave);
                int pitchDelta = (sine * voice.mem.huDepth) >> voice.vibratoFineShift;
                pitchToMixer = iclamp(voice.noteVal + pitchDelta, 0x20, 0xffff);
                voice.vibratoLfoPos = Tables.advanceLfoPhase(voice.vibratoLfoPos, voice.mem.huSpeed);
            }

            /* Glissando (S $1x). */
            if (voice.glissandoOn) {
                int semis = (pitchToMixer * 12 + 2048) / 4096;
                pitchToMixer = iclamp((semis * 4096) / 12, 0x20, 0xffff);
            }

            /* Tremolo (R). */
            if (voice.tremoloActive) {
                int sine = Tables.lfoSampleWide(e, voice.tremoloLfoPos, voice.tremoloWave);
                int volDelta = (sine * voice.mem.rDepth) >> 9;
                voice.rowVolume = iclamp(voice.noteVolume + volDelta * e.volStep, 0, e.volMax);
                voice.tremoloLfoPos = Tables.advanceLfoPhase(voice.tremoloLfoPos, voice.mem.rSpeed);
            }

            /* Panbrello (Y). */
            if (voice.panbrelloActive) {
                int sine = Tables.lfoSampleWide(e, voice.panbrelloLfoPos, voice.panbrelloWave);
                voice.panbrelloOffset = (sine * voice.mem.yDepth) >> 7;
                voice.panbrelloLfoPos = Tables.advanceLfoPhase(voice.panbrelloLfoPos, voice.mem.ySpeed);
            } else {
                voice.panbrelloOffset = 0;
            }

            /* Arpeggio (J). */
            if (voice.arpActive) {
                int voiceIdx = e.tickInRow % 3;
                int arpDelta = voiceIdx == 1 ? voice.arpOff1 : voiceIdx == 2 ? voice.arpOff2 : 0;
                pitchToMixer = iclamp(voice.basePitch + arpDelta, 0x20, 0xffff);
                voice.lastArpVoice = voiceIdx;
            }

            /* Q retrigger — a metainstrument retriggers whole. */
            if (voice.retrigActive && !voice.noteWasCut) {
                voice.retrigCounter++;
                if (voice.retrigCounter >= voice.retrigInterval) {
                    voice.retrigCounter = 0;
                    /* Byte 186 bit 6: ghost the outgoing sound per its NNA first. */
                    Trigger.retrigHandOff(e, voice, vi);
                    restartVoice(e, voice);
                    for (int i = 0; i < e.bgCount; i++) {
                        if (Spatial.isSoundingChild(e, e.bg[i], vi)) restartVoice(e, e.bg[i]);
                    }
                    voice.noteVolume = Effects.applyRetrigVolMod(voice.noteVolume, voice.retrigVolMod, e.volStep, e.volMax);
                    voice.rowVolume = voice.noteVolume;
                }
            }

            voice.pitchModDelta = pitchToMixer - voice.noteVal;

            int autoVibDelta = Envelope.advanceAutoVibrato(e, voice);
            int finalPitch = iclamp(pitchToMixer + autoVibDelta + pitchEnvDelta(voice), 0x20, 0xffff);
            voice.playbackRate = Sampler.computePlaybackRate(e, voice, finalPitch, e.tuningRatio);
            voice.renderPitch = finalPitch;

            updateFilterCutoff(voice);
            Filter.refreshVoiceFilter(e, voice);

            if (voice.keyOff || voice.noteFading) {
                int fadeStep = voice.activeFadeoutStep;
                if (fadeStep > 0) {
                    voice.fadeoutVolume = dmax(voice.fadeoutVolume - fadeStep / 1024.0, 0.0);
                    if (voice.fadeoutVolume <= 0.0) voice.active = false;
                }
            }

            Envelope.advanceEnvelope(e, voice, tickSec);
            voice.envVolStep = spt > 0.0 ? (voice.envVolume - voice.envVolMix) / spt : 0.0;
            Envelope.advancePitchEnvelope(e, voice, tickSec);
            Envelope.advanceFilterEnvelope(e, voice, tickSec);
        }

        /* Tempo slide — once per tick at the playhead level. */
        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Voice voice = e.voices[vi];
            if (voice.tempoSlideDir != 0 && e.tickInRow > 0) {
                int tempoByte = iclamp(e.bpm - 0x19 + voice.tempoSlideDir * voice.tempoSlideAmount, 0, 0xff);
                e.bpm = iclamp(tempoByte + 0x19, 25, 280);
            }
        }

        /* Global volume slide (W coarse). */
        if (e.tickInRow > 0) {
            for (int vi = 0; vi < MAX_VOICES; vi++) {
                Voice voice = e.voices[vi];
                if (voice.wSlideDir != 0) {
                    e.globalVolume = iclamp(e.globalVolume + voice.wSlideDir * voice.wSlideAmount, 0, 0xff);
                }
            }
        }

        /* PSG waveform auto-advance (§5.5.4): the tick's first quarter. The
         * mixer runs the other three at their own frames. */
        e.psgQuarter = 0;
        psgQuarterPass(e);

        /* Invert loop (S $F0xx). */
        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Voice voice = e.voices[vi];
            if (voice.invertSpeed == 0 || !voice.active) continue;
            Inst inst = e.instruments[voice.instrumentId];
            if (voice.activeSampleLoopEnd <= voice.activeSampleLoopStart) continue;
            voice.invertAccumulator += voice.invertSpeed;
            if (voice.invertAccumulator >= 0x80) {
                int loopLen = imax(voice.activeSampleLoopEnd - voice.activeSampleLoopStart, 1);
                voice.invertAccumulator = 0;
                voice.invertWritePos = (voice.invertWritePos + 1) % loopLen;
                inst.toggleInvertBit(voice.invertWritePos, loopLen);
            }
        }

        /* Funk repeat (Z $Ffxx). */
        for (int vi = 0; vi < MAX_VOICES; vi++) {
            Voice voice = e.voices[vi];
            if (voice.funkSpeed == 0 || !voice.active) continue;
            int mode = voice.activeLoopMode & 3;
            if (mode != 1 && mode != 2) continue;
            int loopStart = voice.activeSampleLoopStart;
            int loopLen = voice.activeSampleLoopEnd - loopStart;
            int sampleLen = voice.activeSampleLength;
            if (loopLen <= 0 || loopStart + loopLen > sampleLen) continue;
            voice.funkAccumulator = (voice.funkAccumulator + voice.funkSpeed) & 0xff;
            if ((voice.funkAccumulator & 0x80) != 0) {
                voice.funkAccumulator = 0;
                voice.funkWalk = funkWalkStep(voice.funkMode, voice.funkWalk, loopStart, loopLen, sampleLen);
                voice.funkPos = funkWalkPointer(e, voice.funkMode, voice.funkWalk, loopStart, loopLen, sampleLen);
            }
        }

        /* Sample modification (notefx 2 / 3), lane-clocked. */
        for (int vi = 0; vi < MAX_VOICES; vi++) advanceSampleMod(e, e.voices[vi]);
        for (int i = 0; i < e.bgCount; i++) {
            if (e.bg[i].isLayerChild) advanceSampleMod(e, e.bg[i]);
        }

        /* Background (NNA-ghost) voices: passive maintenance only. */
        for (int i = e.bgCount - 1; i >= 0; i--) {
            Voice bg = e.bg[i];
            if (!bg.active) { e.bgRemoveAt(i); continue; }
            if (bg.isLayerChild) {
                Voice parent = bg.fmOperator
                    ? bg.fmParent
                    : (bg.sourceChannel >= 0 && bg.sourceChannel < MAX_VOICES ? e.voices[bg.sourceChannel] : null);
                if (bg.fmOperator && (parent == null || !parent.active ||
                    parent.fmRig == null || parent.fmRig.voices[0] != parent)) {
                    bg.active = false;
                    bg.fmOperator = false;
                    bg.fmParent = null;
                    e.bgRemoveAt(i);
                    continue;
                }
                if (parent == null || !parent.active) {
                    if (parent != null && !bg.keyOff && !bg.noteFading) {
                        if (parent.keyOff) {
                            bg.keyOff = true;
                            Envelope.applyKeyLift(bg, e.instruments[bg.instrumentId]);
                        } else if (parent.noteFading) {
                            bg.noteFading = true;
                        }
                    }
                    bg.isLayerChild = false;
                    bg.layerPitchMod = 0;
                    bg.layerFixedNote = -1;
                } else {
                    bg.noteVal = bg.layerFixedNote >= 0
                        ? bg.layerFixedNote
                        : iclamp(parent.noteVal + bg.layerRelDetune, 0x20, 0xffff);
                    bg.layerPitchMod = parent.pitchModDelta;
                    bg.basePitch = bg.noteVal;
                    bg.amigaPeriod = -1.0;
                    bg.linearFreq = -1.0;
                    if (parent.keyOff && !bg.keyOff) {
                        bg.keyOff = true;
                        Envelope.applyKeyLift(bg, e.instruments[bg.instrumentId]);
                    }
                    if (parent.noteFading && !bg.noteFading) bg.noteFading = true;
                    bg.channelVolume = parent.channelVolume;
                    bg.channelVolumeSet = parent.channelVolumeSet;
                    bg.channelPanSet = parent.channelPanSet;
                    bg.noteVolume = parent.noteVolume;
                    bg.rowVolume = parent.rowVolume;
                    bg.channelPan = parent.channelPan;
                    bg.rowPan = parent.rowPan;
                    bg.panbrelloOffset = parent.panbrelloOffset;
                    bg.panAzimuth = parent.panAzimuth;
                    bg.panElevation = parent.panElevation;
                    bg.notePan = Spatial.boundNotePan(e, parent.notePan + bg.layerRelPan);
                    bg.noteElevation = parent.noteElevation + bg.layerRelElevation;
                }
            }
            Envelope.advanceEnvelope(e, bg, tickSec);
            bg.envVolStep = spt > 0.0 ? (bg.envVolume - bg.envVolMix) / spt : 0.0;
            Envelope.advancePitchEnvelope(e, bg, tickSec);
            Envelope.advanceFilterEnvelope(e, bg, tickSec);
            if (bg.keyOff || bg.noteFading) {
                int fadeStep = bg.activeFadeoutStep;
                if (fadeStep > 0) bg.fadeoutVolume = dmax(bg.fadeoutVolume - fadeStep / 1024.0, 0.0);
            }
            int autoVibDelta = Envelope.advanceAutoVibrato(e, bg);
            int finalPitch = iclamp(bg.noteVal + bg.layerPitchMod + autoVibDelta + pitchEnvDelta(bg), 0x20, 0xffff);
            bg.playbackRate = Sampler.computePlaybackRate(e, bg, finalPitch, e.tuningRatio);
            bg.renderPitch = finalPitch;
            updateFilterCutoff(bg);
            Filter.refreshVoiceFilter(e, bg);
            if ((bg.keyOff || bg.noteFading) && bg.fadeoutVolume <= 0.0) {
                bg.active = false;
                e.bgRemoveAt(i);
            }
        }
    }
}
