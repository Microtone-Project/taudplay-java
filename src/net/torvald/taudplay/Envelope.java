/*
 * Envelope walkers — port of envelope.js (taudplay-c envelope.c).
 *  - advancePfRole SKIPS zero-duration nodes, stopping at a sustain/loop
 *    boundary or maxIdx; seedPfRole settles the note-on seed past them.
 *  - the vol/pan walker FREEZES on zero-offset nodes (IT terminator).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Envelope {
    private Envelope() {}

    private static final int MAX_IDX = 24;

    /** A pitch/filter role's walk position (the C passes two pointers). */
    static final class PfBox {
        int idx;
        double time;
    }

    static boolean envPresent(int loopWord) { return ((loopWord >> 13) & 1) != 0; }
    static boolean envCarry(int loopWord) { return ((loopWord >> 6) & 1) != 0; }

    /* A 25-node envelope indexed by a node number. LOOP/SUSTAIN words can name
     * nodes up to 31; the JS throws on those (the array has 25 entries), so
     * any reading here is ours to choose — the last node — and every index a
     * file the JS can play produces reads exactly what it reads. */
    private static EnvPoint node(EnvPoint[] env, int i) { return env[i > MAX_IDX ? MAX_IDX : i]; }

    /* Resolve the active wrap region from LOOP and SUSTAIN words + key state:
     * start | end << 8, or -1 when no wrap. */
    private static int resolveEnvWrapStart(int loopWord, int sustainWord, boolean keyOff) {
        boolean susB = ((sustainWord >> 5) & 1) != 0;
        boolean loopB = ((loopWord >> 5) & 1) != 0;
        if (susB && !keyOff) return (sustainWord >> 8) & 0x1f;
        if (loopB) return (loopWord >> 8) & 0x1f;
        return -1;
    }
    private static int resolveEnvWrapEnd(int loopWord, int sustainWord, boolean keyOff) {
        boolean susB = ((sustainWord >> 5) & 1) != 0;
        boolean loopB = ((loopWord >> 5) & 1) != 0;
        if (susB && !keyOff) return sustainWord & 0x1f;
        if (loopB) return loopWord & 0x1f;
        return -1;
    }

    private static double volOf(int value) { return dmin(dmax(value / 63.0, 0.0), 1.0); }

    private static void jumpToSustainEnd(Voice v) {
        int sus = v.activeVolEnvSustain;
        if (((sus >> 5) & 1) == 0) return;
        int susEnd = sus & 0x1f;
        if (v.envIndex >= susEnd) return;
        v.envIndex = susEnd;
        v.envTimeSec = 0.0;
        v.envVolume = volOf(node(v.activeVolEnv, susEnd).value);
    }

    static void applyKeyLift(Voice v, Inst inst) {
        if (!inst.nnaKeyLift()) return;
        jumpToSustainEnd(v);
    }

    static void forceKeyLift(Voice v) { jumpToSustainEnd(v); }

    /** Volume + pan envelope advance (once per tick). */
    static void advanceEnvelope(Engine e, Voice v, double tickSec) {
        EnvPoint[] volEnv = v.activeVolEnv;

        if (v.hasVolEnv && v.volEnvOn) {
            int wStart = resolveEnvWrapStart(v.activeVolEnvLoop, v.activeVolEnvSustain, v.keyOff);
            int wEnd = resolveEnvWrapEnd(v.activeVolEnvLoop, v.activeVolEnvSustain, v.keyOff);
            boolean wrapping = wStart >= 0;

            if (wrapping && v.envIndex == wEnd && wStart == wEnd) {
                v.envVolume = volOf(node(volEnv, v.envIndex).value);
            } else if (wrapping && v.envIndex == wEnd) {
                v.envTimeSec = 0.0;
                v.envIndex = wStart;
                v.envVolume = volOf(node(volEnv, v.envIndex).value);
            } else if (v.envIndex >= MAX_IDX) {
                int vEnd = node(volEnv, MAX_IDX).value;
                v.envVolume = volOf(vEnd);
                if (vEnd == 0 && !wrapping) Sampler.startRampOut(e, v);
            } else {
                double vOffset = Tables.minifloatToDouble(node(volEnv, v.envIndex).offset);
                int vCurValue = node(volEnv, v.envIndex).value;
                if (vOffset == 0.0) {
                    v.envVolume = volOf(vCurValue);
                    if (vCurValue == 0 && !wrapping) Sampler.startRampOut(e, v);
                } else {
                    v.envTimeSec += tickSec;
                    if (v.envTimeSec >= vOffset) {
                        v.envTimeSec -= vOffset;
                        int nextIdx = wrapping && v.envIndex == wEnd ? wStart : imin(v.envIndex + 1, MAX_IDX);
                        v.envIndex = nextIdx;
                        v.envVolume = volOf(node(volEnv, v.envIndex).value);
                    } else {
                        double cur = volOf(vCurValue);
                        double nxt = volOf(node(volEnv, imin(v.envIndex + 1, MAX_IDX)).value);
                        v.envVolume = cur + (nxt - cur) * (v.envTimeSec / vOffset);
                    }
                }
            }
        }

        if (!v.hasPanEnv || !v.panEnvOn) return;
        EnvPoint[] panEnv = v.activePanEnv;
        int pStart = resolveEnvWrapStart(v.activePanEnvLoop, v.activePanEnvSustain, v.keyOff);
        int pEnd = resolveEnvWrapEnd(v.activePanEnvLoop, v.activePanEnvSustain, v.keyOff);
        boolean pWrapping = pStart >= 0;

        if (pWrapping && v.envPanIndex == pEnd && pStart == pEnd) {
            v.envPan = node(panEnv, v.envPanIndex).value / 255.0;
        } else if (pWrapping && v.envPanIndex == pEnd) {
            v.envPanTimeSec = 0.0;
            v.envPanIndex = pStart;
            v.envPan = node(panEnv, v.envPanIndex).value / 255.0;
        } else if (v.envPanIndex >= MAX_IDX) {
            v.envPan = node(panEnv, MAX_IDX).value / 255.0;
        } else {
            double pOffset = Tables.minifloatToDouble(node(panEnv, v.envPanIndex).offset);
            if (pOffset == 0.0) {
                v.envPan = node(panEnv, v.envPanIndex).value / 255.0;
            } else {
                v.envPanTimeSec += tickSec;
                if (v.envPanTimeSec >= pOffset) {
                    v.envPanTimeSec -= pOffset;
                    v.envPanIndex = pWrapping && v.envPanIndex == pEnd ? pStart : imin(v.envPanIndex + 1, MAX_IDX);
                    v.envPan = node(panEnv, v.envPanIndex).value / 255.0;
                } else {
                    double cur = node(panEnv, v.envPanIndex).value / 255.0;
                    double nxt = node(panEnv, imin(v.envPanIndex + 1, MAX_IDX)).value / 255.0;
                    v.envPan = cur + (nxt - cur) * (v.envPanTimeSec / pOffset);
                }
            }
        }
    }

    /** Generic 25-node walk shared by pitch and filter envelopes. */
    private static double advancePfRole(EnvPoint[] env, int loopWord, int susWord, boolean keyOff,
                                        double tickSec, PfBox box) {
        int susStart = resolveEnvWrapStart(loopWord, susWord, keyOff);
        int susEnd = resolveEnvWrapEnd(loopWord, susWord, keyOff);
        boolean susOn = susStart >= 0;
        int idx = box.idx;
        if (susOn && idx == susEnd && susStart == susEnd) {
            return node(env, idx).value / 255.0;
        } else if (susOn && idx == susEnd) {
            box.time = 0.0;
            idx = susStart;
            box.idx = idx;
            return node(env, idx).value / 255.0;
        } else if (idx >= MAX_IDX) {
            return node(env, MAX_IDX).value / 255.0;
        }
        while (idx < MAX_IDX && !(susOn && idx == susEnd) && Tables.minifloatToDouble(node(env, idx).offset) == 0.0) idx++;
        if (susOn && idx == susEnd) {
            if (susStart != susEnd) { box.time = 0.0; idx = susStart; }
            box.idx = idx;
            return node(env, idx).value / 255.0;
        }
        box.idx = idx;
        if (idx >= MAX_IDX) return node(env, MAX_IDX).value / 255.0;
        double offset = Tables.minifloatToDouble(node(env, idx).offset);
        box.time += tickSec;
        if (box.time >= offset) {
            box.time -= offset;
            idx = imin(idx + 1, MAX_IDX);
            box.idx = idx;
            return node(env, idx).value / 255.0;
        }
        double cur = node(env, idx).value / 255.0;
        double nxt = node(env, imin(idx + 1, MAX_IDX)).value / 255.0;
        return cur + (nxt - cur) * (box.time / offset);
    }

    /** Seeds `box` at the note-on and returns the role's first value. */
    static double seedPfRole(EnvPoint[] env, int loopWord, int susWord, PfBox box) {
        box.idx = 0;
        box.time = 0.0;
        return advancePfRole(env, loopWord, susWord, false, 0.0, box);
    }

    static void advancePitchEnvelope(Engine e, Voice v, double tickSec) {
        if (!v.hasPitchEnv || !v.pitchEnvOn) return;
        PfBox box = e.pfBox;
        box.idx = v.envPitchIndex;
        box.time = v.envPitchTimeSec;
        v.envPitchValue = advancePfRole(v.activePitchEnv, v.activePitchEnvLoop,
            v.activePitchEnvSustain, v.keyOff, tickSec, box);
        v.envPitchIndex = box.idx;
        v.envPitchTimeSec = box.time;
    }

    static void advanceFilterEnvelope(Engine e, Voice v, double tickSec) {
        if (!v.hasFilterEnv || !v.filterEnvOn) return;
        PfBox box = e.pfBox;
        box.idx = v.envFilterIndex;
        box.time = v.envFilterTimeSec;
        v.envFilterValue = advancePfRole(v.activeFilterEnv, v.activeFilterEnvLoop,
            v.activeFilterEnvSustain, v.keyOff, tickSec, box);
        v.envFilterIndex = box.idx;
        v.envFilterTimeSec = box.time;
    }

    /** IT-style auto-vibrato: a 4096-TET pitch delta for this tick. */
    static int advanceAutoVibrato(Engine e, Voice v) {
        int depth0 = v.activeVibratoDepth;
        if (depth0 == 0 || v.activeVibratoSpeed == 0) return 0;

        int ftSweep = v.activeVibratoSweep;
        int itRate = v.activeVibratoRate;
        long t = v.autoVibTicksSinceTrigger;
        int rampDepth;
        if (ftSweep != 0) {
            long q = ((long) depth0 * t) / ftSweep;
            rampDepth = q < depth0 ? (int) q : depth0;
        } else if (itRate != 0) {
            int q = ((int) (t * itRate)) >>> 8;
            rampDepth = q < depth0 ? q : depth0;
        } else {
            rampDepth = depth0;
        }
        v.autoVibTicksSinceTrigger++;

        int wave = v.activeVibratoWaveform;
        int pos = v.autoVibPhase >> 2;
        int rawSample = wave == 4 ? -Tables.lfoSample(e, pos, 1) : Tables.lfoSample(e, pos, wave & 3);
        int pitchDelta = (rawSample * rampDepth * AUTOVIB_DEPTH_MUL) >> AUTOVIB_DEPTH_SHIFT;
        v.autoVibPhase = (v.autoVibPhase + v.activeVibratoSpeed) & (AUTOVIB_PHASE_STEPS - 1);
        return pitchDelta;
    }
}
