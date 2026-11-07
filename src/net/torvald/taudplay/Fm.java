/*
 * FM operator racks — Metainstrument type 4 — port of fm.js (taudplay-c fm.c).
 *
 * An operator may also be a low-meta instrument — an OPL channel or a PSG
 * pair — which is a generator rather than a waveform to read at an offset:
 * the modulation goes into the phase of what the channel outputs.
 *
 * A rig lives inside the voice that owns it (voice.rigStore): in the JS every
 * rig belongs to exactly one voice, is created by that voice's trigger (or by
 * a ghost's clone), and dies when the voice drops it.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Fm {
    private Fm() {}

    static void rigInit(FmRig rig, int count) { rig.init(count); }

    /** The rack's non-voice state onto a fresh rig: algorithm, gains, feedback taps. */
    static void rigClone(FmRig dst, FmRig src) {
        dst.init(src.count);
        dst.program = src.program;
        dst.programLen = src.programLen;
        for (int k = 0; k < src.count; k++) {
            dst.gain[k] = src.gain[k];
            dst.last[k] = src.last[k];
        }
    }

    static void referencedOperators(int[] program, int len, int count, boolean[] used) {
        java.util.Arrays.fill(used, 0, count, false);
        if (program == null) return;
        for (int i = 0; i < len; i++) {
            int w = program[i];
            if (w >= FM_WORD_OP) continue;
            int cls = w & ~FM_INDEX_MASK;
            if (cls != FM_WORD_OSC && cls != FM_WORD_MOD) continue;
            int k = w & FM_INDEX_MASK;
            if (k < count) used[k] = true;
        }
    }

    static void seedGains(FmRig rig, Meta meta) {
        for (int k = 0; k < rig.count && k < meta.count; k++) {
            rig.gain[k] = Tables.META_MIX_GAIN[meta.layers[k].mixOctet & 0xff];
        }
    }

    private static double fmCycleFrames(Voice v) {
        int span = v.activeSampleLoopEnd - v.activeSampleLoopStart;
        if ((v.activeLoopMode & 3) != 0 && span > 0) return span;
        return imax(v.activeSampleLength, 1);
    }

    /* An OPL operator's value at output frame `frame`, read with `offset`
     * cycles of phase modulation (one cycle = 1024 of the chip's phase steps),
     * at unity volume. A channel that has rung out ends its operator. */
    private static double fmChipOperator(Engine e, Voice v, long frame, double offset) {
        OplState st = v.opl;
        st.pm = offset == 0 ? 0 : JsMath.round(offset * 1024);
        double s = Opl.render(e, st, frame, v.currentPlaybackRate, 1.0, v.keyOff);
        st.pm = 0;
        if (!Opl.channelAlive(st)) v.active = false;
        return s;
    }

    /* A PSG operator, read the same way: one cycle of the oscillator a cycle. */
    private static double fmPsgOperator(Engine e, Voice v, long frame, double offset) {
        PsgState st = v.psg;
        st.pm = offset == 0 ? 0 : JsMath.round((offset - Math.floor(offset)) * 4294967296.0) % 4294967296.0;
        double s = Psg.render(e, st, frame, v.currentPlaybackRate, v.keyOff);
        st.pm = 0;
        if (!Psg.channelAlive(st)) v.active = false;
        return s;
    }

    private static double fmEvalOperator(Engine e, FmRig rig, int k, int interpMode, double spt, double offset,
                                         long frame) {
        if (rig.done[k]) return rig.cur[k];
        rig.done[k] = true;
        Voice v = rig.voices[k];
        if (v == null || !v.active) { rig.cur[k] = 0.0; return 0.0; }
        Inst inst = e.instruments[v.instrumentId];
        double s;
        if (v.opl != null) {
            s = fmChipOperator(e, v, frame, offset);
        } else if (v.psg != null) {
            s = fmPsgOperator(e, v, frame, offset);
        } else {
            double frames = offset == 0 ? 0 : offset * fmCycleFrames(v);
            s = Sampler.fetchTrackerSample(e, v, inst, interpMode, frames);
        }
        double g = rig.gain[k];
        if (k != 0) {
            s = Filter.applyVoiceFilterSelf(v, s);
            Filter.advanceFilterGlide(e, v);
            v.envVolMix += v.envVolStep;
            double effEnvVol = v.hasVolEnv && v.volEnvOn ? v.envVolMix : 1.0;
            Sampler.advanceVolumeRamp(e, v, e.volDiv);
            Sampler.advancePitchRamp(e, v, spt);
            g *= effEnvVol * Sampler.advanceFadeout(v) * v.activeAttenGain * (inst.instGlobalVolume / 255.0);
            if (v.rampOutSamples > 0) {
                g *= v.rampOutGain;
                v.rampOutGain -= v.rampOutStep;
                v.rampOutSamples--;
                if (v.rampOutSamples == 0) v.active = false;
            }
            if (v.attackRampSamples > 0) {
                int elapsed = e.attackRampSamples - v.attackRampSamples;
                g *= 0.5 - 0.5 * JsMath.cos((3.141592653589793 * elapsed) / e.attackRampSamples);
                v.attackRampSamples--;
            }
        }
        double out = s * g;
        rig.cur[k] = out;
        return out;
    }

    /** Run the rack's algorithm for one output sample. */
    static double renderFmVoice(Engine e, Voice voice, int interpMode, double spt, long frame) {
        FmRig rig = voice.fmRig;
        int[] prog = rig.program;
        double[] stack = rig.stack;
        int sp = 0;
        if (prog == null) return 0.0;
        java.util.Arrays.fill(rig.done, false);

        for (int i = 0; i < rig.programLen; i++) {
            int w = prog[i];
            if (w >= FM_WORD_OP) {
                switch (w) {
                case FMOP_ADD:
                    if (sp >= 2) { double b = stack[--sp]; stack[sp - 1] += b; }
                    break;
                case FMOP_MUL:
                    if (sp >= 2) { double b = stack[--sp]; stack[sp - 1] *= b; }
                    break;
                case FMOP_NEG:
                    if (sp >= 1) stack[sp - 1] = -stack[sp - 1];
                    break;
                case FMOP_DUP:
                    if (sp >= 1 && sp < FM_STACK_MAX) { stack[sp] = stack[sp - 1]; sp++; }
                    break;
                case FMOP_SWAP:
                    if (sp >= 2) { double b = stack[sp - 1]; stack[sp - 1] = stack[sp - 2]; stack[sp - 2] = b; }
                    break;
                default:
                    break;
                }
                continue;
            }
            int k = w & FM_INDEX_MASK;
            if (k >= rig.count) continue;
            int cls = w & ~FM_INDEX_MASK;
            if (cls == FM_WORD_FB) {
                if (sp < FM_STACK_MAX) stack[sp++] = rig.last[k];
                continue;
            }
            double offset = cls == FM_WORD_MOD && sp > 0 ? stack[--sp] : 0.0;
            if (sp < FM_STACK_MAX) stack[sp++] = fmEvalOperator(e, rig, k, interpMode, spt, offset, frame);
        }

        for (int k = 0; k < rig.count; k++) if (rig.done[k]) rig.last[k] = rig.cur[k];
        return sp > 0 ? stack[sp - 1] : 0.0;
    }

    /** True when operator 0 is a DSP-ONLY principal (fmPrincipalIsDsp): the
     *  program ends by multiplying everything above it by operator 0 (`$0000
     *  $FF01`) and names it nowhere else (§5.5.1). */
    static boolean principalIsDsp(int[] program, int len) {
        if (program == null || len < 3 || program[len - 1] != FMOP_MUL || program[len - 2] != FM_WORD_OSC) return false;
        for (int i = 0; i < len - 2; i++) {
            int w = program[i];
            if (w < FM_WORD_OP && (w & FM_INDEX_MASK) == 0) return false;
        }
        return true;
    }

    /** S $Apsd on a rack (fmSetOperatorVibrato): operator p's auto-vibrato
     *  speed and depth, s × 17 and d × 17, for the note now sounding. */
    static void setOperatorVibrato(FmRig rig, int p, int s, int d) {
        int k = principalIsDsp(rig.program, rig.programLen) ? p + 1 : p;
        Voice v = k < rig.count ? rig.voices[k] : null;
        if (v == null) return;
        v.activeVibratoSpeed = s * 17;
        v.activeVibratoDepth = d * 17;
    }

    /** Detach and silence every operator the foreground voice of lane `vi` drives. */
    static void dropFmOperators(Engine e, int vi) {
        Voice fg = e.voices[vi];
        for (int i = e.bgCount - 1; i >= 0; i--) {
            Voice bg = e.bg[i];
            if (bg.fmOperator && bg.fmParent == fg) {
                bg.active = false;
                bg.fmOperator = false;
                bg.fmParent = null;
                bg.isLayerChild = false;
                e.bgRemoveAt(i);
            }
        }
    }
}
