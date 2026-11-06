/*
 * Sample fetch + interpolators + anti-click ramps — port of sampler.js
 * (taudplay-c sampler.c).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Sampler {
    private Sampler() {}

    private static final int FUNK_XFADE_SAMPLES = 64;

    /* The NES DPCM counter an interpolation reads: the voice's own (left) or
     * its right channel's — the C passes a pointer. */
    private static final int LEFT = 0, RIGHT = 1;
    private static int dpcmGet(Voice v, int side) { return side == LEFT ? v.nesDpcmCounter : v.right.nesDpcmCounter; }
    private static void dpcmSet(Voice v, int side, int x) { if (side == LEFT) v.nesDpcmCounter = x; else v.right.nesDpcmCounter = x; }

    static double computePlaybackRate(Engine e, Voice v, int noteVal, double tuningRatio) {
        return ((double) v.activeSamplingRate / e.rate) *
               JsMath.pow(2.0, (noteVal - MIDDLE_C + v.activeSampleDetune) / 4096.0) *
               tuningRatio;
    }

    /** The pool byte at `i` with S $Fxxx's own mask applied. */
    private static int poolByte(Engine e, Inst inst, int i, int binMax, long basePtr, int ls, int le) {
        long p = basePtr + i;
        int b = e.poolAt(p < binMax ? p : binMax);
        if (inst.invertMaskLen != 0 && le > ls && i >= ls && i < le && inst.invertBit(i - ls, le - ls)) {
            return b ^ 0xff;
        }
        return b;
    }

    private static boolean plainFetchOnly(Voice v, Inst inst) {
        return inst.invertMaskLen == 0 &&
            ((inst.modOp == MOD_OFF && inst.modOpExt == 0) || (!inst.modOn && v.modXfade == 0));
    }

    private static double poolPoint(Engine e, int sampleLen, int binMax, long basePtr, int idx) {
        int hi = sampleLen - 1;
        int i = idx < 0 ? 0 : idx > hi ? hi : idx;
        long p = basePtr + i;
        return (e.poolAt(p > binMax ? binMax : p) - 127.5) / 127.5;
    }

    private static double readModifiedSamplePoint(Engine e, Voice v, Inst inst, int idx,
                                                  int sampleLen, int binMax, long basePtr) {
        int i0 = iclamp(idx, 0, sampleLen - 1);
        int ls = v.activeSampleLoopStart;
        int le = v.activeSampleLoopEnd;
        boolean extended = inst.modOpExt != 0;
        ModGeom g = SampleMod.resolveModGeom(v.modGeom, inst, ls, le, sampleLen);
        boolean touches = extended
            ? SampleMod.extModTouches(g, inst.modInvert, inst.modF, inst.modStepIndex, i0)
            : SampleMod.modTouches(g, inst.modInvert, i0);
        if (!g.live || !touches) {
            return (poolByte(e, inst, i0, binMax, basePtr, ls, le) - 127.5) / 127.5;
        }
        int i = extended ? SampleMod.modAddressExt(g, i0, inst)
                         : SampleMod.modAddress(g, i0, inst.modRot, inst.modScatter, inst.modSeed);
        double b;
        {
            int bb = poolByte(e, inst, i, binMax, basePtr, ls, le);
            if (extended) {
                if (inst.modMaskLen != 0) { if (inst.modBit(i)) bb ^= 0xff; }
                bb = SampleMod.applyExtLevel(inst, bb);
            } else if (inst.modMaskLen != 0) { if (inst.modBit(i)) bb = bb ^ 0xff; }
            else if (inst.modSub != 0) bb = (bb - inst.modSub) & 0xff;
            b = bb;
        }
        if (v.modXfade > 0) {
            int j = SampleMod.modAddress(g, i0, inst.modPrevRot, inst.modPrevScatter, inst.modPrevSeed);
            int p = poolByte(e, inst, j, binMax, basePtr, ls, le);
            if (extended) p = SampleMod.applyExtLevelPrev(inst, p);
            else if (inst.modMaskLen != 0) { if (inst.modBit(j)) p = p ^ 0xff; }
            else if (inst.modPrevSub != 0) p = (p - inst.modPrevSub) & 0xff;
            double w = (double) v.modXfade / MOD_XFADE_SAMPLES;
            b = p * w + b * (1.0 - w);
        }
        return (b - 127.5) / 127.5;
    }

    private static double readSamplePoint(Engine e, Voice v, Inst inst, int idx,
                                          int sampleLen, int binMax, long basePtr) {
        if (plainFetchOnly(v, inst)) return poolPoint(e, sampleLen, binMax, basePtr, idx);
        return readModifiedSamplePoint(e, v, inst, idx, sampleLen, binMax, basePtr);
    }

    private static int pcmTo15bit(double x) {
        return (int) dmin(JsMath.round(x * 16384.0), 16383);
    }

    private static double read(boolean plain, Engine e, Voice v, Inst inst, int ix, int sampleLen, int binMax, long basePtr) {
        return plain ? poolPoint(e, sampleLen, binMax, basePtr, ix)
                     : readSamplePoint(e, v, inst, ix, sampleLen, binMax, basePtr);
    }

    /** Interpolate ONE channel at the voice's current position WITHOUT advancing it. */
    private static double interpolateChannel(Engine e, Voice v, Inst inst, int interpMode,
                                             int sampleLen, int binMax, long basePtr, int side) {
        int i0 = iclamp((int) dclamp(v.samplePos, -2147483648.0, 2147483647.0), 0, sampleLen - 1);
        double frac = v.samplePos - i0;
        boolean plain = plainFetchOnly(v, inst);

        switch (interpMode) {
        case INTERP_DEFAULT: {
            double acc = 0.0;
            if (plain) {
                long base = basePtr + i0;
                /* The JS guard is `base + SINC_WIDTH <= binMax`; a trimmed pool
                 * also needs every tap inside it, and when one is not the
                 * clamped path below reads the same zeros the full pool holds. */
                if (i0 >= SINC_WIDTH && i0 + SINC_WIDTH <= sampleLen - 1 && base + SINC_WIDTH <= binMax &&
                    base + SINC_WIDTH < e.samplePoolLen) {
                    byte[] bin = e.sampleBin;
                    for (int j = -SINC_WIDTH; j <= SINC_WIDTH; j++) {
                        double coeff = Tables.sincTap(e, frac, j);
                        if (coeff != 0.0) acc += (((bin[(int) (base + j)] & 0xff) - 127.5) / 127.5) * coeff;
                    }
                    return acc;
                }
                for (int j = -SINC_WIDTH; j <= SINC_WIDTH; j++) {
                    double coeff = Tables.sincTap(e, frac, j);
                    if (coeff != 0.0) acc += poolPoint(e, sampleLen, binMax, basePtr, i0 + j) * coeff;
                }
                return acc;
            }
            for (int j = -SINC_WIDTH; j <= SINC_WIDTH; j++) {
                double coeff = Tables.sincTap(e, frac, j);
                if (coeff != 0.0) acc += readSamplePoint(e, v, inst, i0 + j, sampleLen, binMax, basePtr) * coeff;
            }
            return acc;
        }
        case INTERP_SNES: {
            int oldest = pcmTo15bit(read(plain, e, v, inst, i0 - 1, sampleLen, binMax, basePtr));
            int olders = pcmTo15bit(read(plain, e, v, inst, i0, sampleLen, binMax, basePtr));
            int olds = pcmTo15bit(read(plain, e, v, inst, i0 + 1, sampleLen, binMax, basePtr));
            int news = pcmTo15bit(read(plain, e, v, inst, i0 + 2, sampleLen, binMax, basePtr));
            int offset = (int) dclamp(JsMath.trunc(frac * 256.0), 0.0, 255.0);
            int out = (Tables.SNES_GAUSS[0xff - offset] * oldest) >> 10;
            out += (Tables.SNES_GAUSS[0x1ff - offset] * olders) >> 10;
            out += (Tables.SNES_GAUSS[0x100 + offset] * olds) >> 10;
            out &= 0xffff;
            if (out >= 0x8000) out -= 0x10000;
            out += (Tables.SNES_GAUSS[offset] * news) >> 10;
            out = iclamp(out, -32768, 32767);
            return (out >> 1) / 16384.0;
        }
        case INTERP_NES_DPCM: {
            double target = read(plain, e, v, inst, i0, sampleLen, binMax, basePtr);
            int targetLevel = iclamp((int) JsMath.trunc((target + 1.0) * 63.5), 0, 127);
            int dpcm = dpcmGet(v, side);
            if (targetLevel > dpcm && dpcm <= 125) dpcm += 2;
            else if (targetLevel < dpcm && dpcm >= 2) dpcm -= 2;
            dpcmSet(v, side, dpcm);
            return (dpcm - 63.5) / 63.5;
        }
        case INTERP_NONE:
        case INTERP_A500:
        case INTERP_A1200:
        default:
            return read(plain, e, v, inst, i0, sampleLen, binMax, basePtr);
        }
    }

    private static void armFunkXfade(Voice v, int offset, double windowLen) {
        if (offset == 0) return;
        double rate = Math.abs(v.currentPlaybackRate);
        double grain = rate > 0 ? Math.floor(windowLen / rate) : FUNK_XFADE_SAMPLES;
        int len = (int) dmin(FUNK_XFADE_SAMPLES, dmax(1, grain));
        v.funkXfade = len;
        v.funkXfadeLen = len;
        v.funkXfadeOffset = offset;
    }

    private static void armModFunkXfade(Voice v, int offset, double windowLen) {
        if (offset == 0) return;
        double rate = Math.abs(v.currentPlaybackRate);
        double grain = rate > 0 ? Math.floor(windowLen / rate) : FUNK_XFADE_SAMPLES;
        int len = (int) dmin(FUNK_XFADE_SAMPLES, dmax(1, grain));
        v.modFunkXfade = len;
        v.modFunkXfadeLen = len;
        v.modFunkXfadeOffset = offset;
    }

    private static double ghostChannel(Engine e, Voice v, Inst inst, int interpMode,
                                       int sampleLen, int binMax, long basePtr, int side, int offset) {
        double keepPos = v.samplePos;
        int keepDpcm = dpcmGet(v, side);
        v.samplePos = keepPos + offset;
        double g = interpolateChannel(e, v, inst, interpMode, sampleLen, binMax, basePtr, side);
        v.samplePos = keepPos;
        dpcmSet(v, side, keepDpcm);
        return g;
    }

    private static void beginRampOut(Voice v, int samples) {
        if (v.rampOutSamples > 0) return;
        v.rampOutSamples = samples;
        v.rampOutGain = 1.0;
        v.rampOutStep = 1.0 / samples;
    }

    static void startRampOut(Engine e, Voice v) { beginRampOut(v, e.rampOutSamples); }
    static void startCutRamp(Engine e, Voice v) { beginRampOut(v, e.attackRampSamples); }

    /** Step samplePos by the playback rate and apply the loop/end rules. */
    private static void advanceSamplePos(Engine e, Voice v, Inst inst, int sampleLen) {
        boolean zWindowed = v.funkPos >= 0 || v.funkWindow >= 0;
        double zLoopStart = v.funkWindow >= 0 ? v.funkWindow : v.activeSampleLoopStart;
        double zLoopEnd = zWindowed
            ? zLoopStart + dmax(v.activeSampleLoopEnd - v.activeSampleLoopStart, 1.0)
            : dmax(v.activeSampleLoopEnd, 1.0);

        boolean extFunkLive = SampleMod.isExtFunkOp(inst.modOpExt);
        boolean extWindowed = extFunkLive && (inst.modFunkPos >= 0 || v.modFunkWindow >= 0);
        double extLoopStart = extWindowed && v.modFunkWindow >= 0
            ? v.modFunkWindow : v.activeSampleLoopStart;
        double extLoopEnd = extWindowed ? extLoopStart + dmax(inst.modFunkLen, 1.0) : zLoopEnd;

        double loopStart = extWindowed ? extLoopStart : zLoopStart;
        double loopEnd = extWindowed ? extLoopEnd : zLoopEnd;

        if (v.forward) {
            v.samplePos += v.currentPlaybackRate;
            int effectiveLoopMode = ((v.activeLoopMode & 0x04) != 0 && v.keyOff) ? 0 : v.activeLoopMode & 3;
            switch (effectiveLoopMode) {
            case 0:
            case 3:
                if (v.samplePos >= sampleLen) {
                    v.samplePos = dmax(sampleLen - 1, 0.0);
                    startRampOut(e, v);
                }
                break;
            case 1:
                if (v.samplePos >= loopEnd) {
                    double overshoot = v.samplePos - loopEnd;
                    if (extWindowed) {
                        double prevWindow = extLoopStart;
                        if (inst.modFunkPos >= 0) v.modFunkWindow = inst.modFunkPos;
                        armModFunkXfade(v, (int) (prevWindow - v.modFunkWindow), loopEnd - loopStart);
                        v.samplePos = v.modFunkWindow + overshoot;
                    } else if (zWindowed) {
                        double prevWindow = zLoopStart;
                        if (v.funkPos >= 0) v.funkWindow = v.funkPos;
                        armFunkXfade(v, (int) (prevWindow - v.funkWindow), loopEnd - loopStart);
                        v.samplePos = v.funkWindow + overshoot;
                    } else {
                        v.samplePos -= dmax(loopEnd - loopStart, 1.0);
                    }
                }
                break;
            case 2:
                if (v.samplePos >= loopEnd) { v.samplePos = loopEnd; v.forward = false; }
                break;
            }
        } else {
            v.samplePos -= v.currentPlaybackRate;
            if (v.samplePos < loopStart) {
                if (extWindowed) {
                    double prevWindow = extLoopStart;
                    if (inst.modFunkPos >= 0) v.modFunkWindow = inst.modFunkPos;
                    armModFunkXfade(v, (int) (prevWindow - v.modFunkWindow), loopEnd - loopStart);
                    v.samplePos = v.modFunkWindow;
                } else if (zWindowed) {
                    double prevWindow = zLoopStart;
                    if (v.funkPos >= 0) v.funkWindow = v.funkPos;
                    armFunkXfade(v, (int) (prevWindow - v.funkWindow), loopEnd - loopStart);
                    v.samplePos = v.funkWindow;
                } else {
                    v.samplePos = loopStart;
                }
                v.forward = true;
            }
        }
    }

    static void fetchTrackerSampleStereo(Engine e, Voice v, Inst inst, int interpMode, double[] out) {
        int binMax = SAMPLE_BIN_TOTAL - 1;
        if (inst.index == 0) { out[0] = 0.0; out[1] = 0.0; return; }
        int sampleLen = imax(v.activeSampleLength, 1);
        out[0] = interpolateChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr, LEFT);
        out[1] = interpolateChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeChanPtr2, RIGHT);
        if (v.funkXfade > 0) {
            double w = (double) v.funkXfade / v.funkXfadeLen;
            out[0] = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr,
                                  LEFT, v.funkXfadeOffset) * w + out[0] * (1.0 - w);
            out[1] = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeChanPtr2,
                                  RIGHT, v.funkXfadeOffset) * w + out[1] * (1.0 - w);
            v.funkXfade--;
        }
        if (v.modFunkXfade > 0) {
            double w = (double) v.modFunkXfade / v.modFunkXfadeLen;
            out[0] = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr,
                                  LEFT, v.modFunkXfadeOffset) * w + out[0] * (1.0 - w);
            out[1] = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeChanPtr2,
                                  RIGHT, v.modFunkXfadeOffset) * w + out[1] * (1.0 - w);
            v.modFunkXfade--;
        }
        if (v.modXfade > 0) v.modXfade--;
        if (v.rampOutSamples <= 0) advanceSamplePos(e, v, inst, sampleLen);
    }

    /** The read position a phase-modulated fetch lands on. */
    private static double wrapReadPos(Voice v, double pos, int sampleLen) {
        int mode = v.activeLoopMode & 3;
        int ls = v.activeSampleLoopStart;
        int le = v.activeSampleLoopEnd;
        if ((mode == 1 || mode == 2) && le > ls) {
            int span = le - ls;
            double p = (pos - ls) % span;
            if (p < 0) p += span;
            return ls + p;
        }
        return dmin(dmax(pos, 0), sampleLen - 1);
    }

    static double fetchTrackerSample(Engine e, Voice v, Inst inst, int interpMode, double posOffset) {
        int binMax = SAMPLE_BIN_TOTAL - 1;
        if (inst.index == 0) return 0.0;

        int sampleLen = imax(v.activeSampleLength, 1);
        double keepPos = v.samplePos;
        if (posOffset != 0) v.samplePos = wrapReadPos(v, keepPos + posOffset, sampleLen);
        double sample = interpolateChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr, LEFT);
        if (v.funkXfade > 0) {
            double w = (double) v.funkXfade / v.funkXfadeLen;
            sample = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr,
                                  LEFT, v.funkXfadeOffset) * w + sample * (1.0 - w);
            v.funkXfade--;
        }
        if (v.modFunkXfade > 0) {
            double w = (double) v.modFunkXfade / v.modFunkXfadeLen;
            sample = ghostChannel(e, v, inst, interpMode, sampleLen, binMax, v.activeSamplePtr,
                                  LEFT, v.modFunkXfadeOffset) * w + sample * (1.0 - w);
            v.modFunkXfade--;
        }
        if (posOffset != 0) v.samplePos = keepPos;

        if (v.modXfade > 0) v.modXfade--;
        if (v.rampOutSamples > 0) return sample;
        advanceSamplePos(e, v, inst, sampleLen);
        return sample;
    }

    static void startFastFade(Engine e, Voice v) {
        if (!v.active) return;
        v.noteFading = true;
        double ticks = dmax(FAST_FADE_SEC * e.bpm * 0.4, 1.0);
        v.activeFadeoutStep = (int) dmin(dmax(JsMath.round(1024.0 / ticks), 1), 0xfff);
    }

    private static int pitchGlideSamples(Engine e, double cur, double target, double spt) {
        int tick = spt >= 1 ? (int) JsMath.round(spt) : 1;
        if (!(cur > 0.0) || !(target > 0.0)) return 1;
        double up = target / cur;
        double down = cur / target;
        double interval = (up > down ? up : down) - 1.0;
        if (interval <= PITCH_GLIDE_FULL_RATIO) return tick;
        int n = (int) JsMath.round((tick * PITCH_GLIDE_FULL_RATIO) / interval);
        int fl = e.attackRampSamples < tick ? e.attackRampSamples : tick;
        return n < fl ? fl : n;
    }

    static void advancePitchRamp(Engine e, Voice v, double spt) {
        double target = v.playbackRate;
        if (v.snapPlaybackRate) {
            v.currentPlaybackRate = target;
            v.pitchRampSamples = 0;
            v.pitchRampStep = 0.0;
            v.snapPlaybackRate = false;
            return;
        }
        if (v.pitchRampSamples > 0) {
            v.currentPlaybackRate += v.pitchRampStep;
            v.pitchRampSamples--;
            if (v.pitchRampSamples == 0) v.currentPlaybackRate = target;
        } else if (v.currentPlaybackRate != target) {
            int n = pitchGlideSamples(e, v.currentPlaybackRate, target, spt);
            if (n <= 1) {
                v.currentPlaybackRate = target;
                v.pitchRampSamples = 0;
                v.pitchRampStep = 0.0;
            } else {
                v.pitchRampStep = (target - v.currentPlaybackRate) / n;
                v.pitchRampSamples = n - 1;
                v.currentPlaybackRate += v.pitchRampStep;
            }
        }
    }

    static double advancePanRamp(Engine e, Voice v, double target, boolean wrap) {
        if (wrap && !v.snapPan) {
            double d = target - v.currentPan;
            if (d > 256) v.currentPan += 512;
            else if (d < -256) v.currentPan -= 512;
        }
        if (v.snapPan) {
            v.currentPan = target;
            v.panRampSamples = 0;
            v.panRampStep = 0.0;
            v.snapPan = false;
            return target;
        }
        if (v.panRampSamples > 0) {
            v.currentPan += v.panRampStep;
            v.panRampSamples--;
            if (v.panRampSamples == 0) v.currentPan = target;
        } else if (v.currentPan != target) {
            v.panRampStep = (target - v.currentPan) / e.volRampSamples;
            v.panRampSamples = e.volRampSamples - 1;
            v.currentPan += v.panRampStep;
        }
        if (wrap) {
            if (v.currentPan < 0) v.currentPan += 512;
            else if (v.currentPan >= 512) v.currentPan -= 512;
        }
        return v.currentPan;
    }

    static void advanceVolumeRamp(Engine e, Voice v, double div) {
        double target = (v.rowVolume / div) * (v.channelVolume / div);
        if (v.snapMixVolume) {
            v.currentMixVolume = target;
            v.volRampSamples = 0;
            v.volRampStep = 0.0;
            v.snapMixVolume = false;
            return;
        }
        if (v.volRampSamples > 0) {
            v.currentMixVolume += v.volRampStep;
            v.volRampSamples--;
            if (v.volRampSamples == 0) v.currentMixVolume = target;
        } else if (v.currentMixVolume != target) {
            v.volRampStep = (target - v.currentMixVolume) / e.volRampSamples;
            v.volRampSamples = e.volRampSamples - 1;
            v.currentMixVolume += v.volRampStep;
        }
    }
}
