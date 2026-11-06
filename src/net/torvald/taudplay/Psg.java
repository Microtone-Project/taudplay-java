/*
 * PSG instruments — Metainstrument type 9 — port of psg.js (taudplay-c psg.c).
 *
 * A type-9 instrument is an ordinary instrument record whose sample is a pair
 * of programmable-sound-generator oscillators, A and B, mixed by adding them
 * or by multiplying them (TAUD_FILE_FORMAT.md §7.9). Each oscillator is a
 * 0-to-1-volt generator playing a waveform; the pair is added or multiplied
 * in volts, then mapped onto the lane's ±1, each oscillator's envelope
 * scaling what the mapping makes of it. The envelope is the OPL chip's own,
 * number for number, and the PSG runs on the same chip clock (e.oplFrame) at
 * 49716 Hz, read back by linear interpolation (Opl).
 *
 * What is not the chip's is the oscillator's internal status — its phase, the
 * noise position and the auto-advance — which a lane keeps across notes
 * unless the oscillator's X flag resets it at a key-on. The origin its LFOs
 * run from follows the same flag but is never carried: X puts it at the
 * note's first frame, and without X it is the chip clock's 0.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Psg {
    private Psg() {}

    private static final double PSG_HZ_PER_SPS = LINEAR_FREQ_C4_HZ / OPL_C4_RATE;

    private static final int PSG_B_FLAGS = 0;
    private static final int PSG_B_OSC = 2;
    private static final int PSG_OSC_BYTES = 12;
    private static final int PSG_O_ADVANCE = 5;
    private static final int PSG_O_PATTERN = 6;

    private static final int PSG_MIX_MULTIPLY = 0x01;
    private static final int PSG_MUTE_B = 0x04;
    private static final int PSG_MUTE_A = 0x08;

    private static final int PSG_OF_KSR = 0x01;
    private static final int PSG_OF_SUSTAIN = 0x02;
    private static final int PSG_OF_RESET = 0x04;
    private static final int PSG_OF_INVERT = 0x08;
    private static final int PSG_OF_TREMOLO_SHIFT = 4;
    private static final int PSG_OF_VIBRATO_SHIFT = 6;

    private static final int PSG_WAVE_DC = 0x0;
    private static final int PSG_WAVE_PULSE_LAST = 0x7;
    private static final int PSG_WAVE_SAW = 0x8;
    private static final int PSG_WAVE_TRIANGLE = 0x9;
    private static final int PSG_WAVE_NOISE_LONG = 0xa;
    private static final int PSG_WAVE_NOISE_MID = 0xb;
    private static final int PSG_WAVE_NOISE_SHORT = 0xc;
    private static final int PSG_WAVE_SAW4 = 0xd;
    private static final int PSG_WAVE_TRIANGLE4 = 0xe;

    private static final double PSG_TWO32 = 4294967296.0;
    private static final double PSG_TWO28 = 268435456.0;
    /* A common multiple of the three noise cycles, so every noise stays exact
     * across the counter's wrap. */
    private static final double PSG_NOISE_WRAP = 32767.0 * 2047 * 127;

    private static final int PSG_ENV_TO_LOG = 8;
    private static final int PSG_TL_TO_LOG = 32;
    private static final double PSG_OUTPUT_SCALE = 1.0 / 4096;

    /* One period of a Fibonacci shift register's output from a seed of 1
     * (psgLfsrTable): 15 bits (x^15 + x^14 + 1), 11 bits (x^11 + x^9 + 1) and
     * 7 bits (x^7 + x^6 + 1). */
    private static byte[] lfsrTable(int bits, int tapA, int tapB, int len) {
        byte[] t = new byte[len];
        int r = 1;
        for (int i = 0; i < len; i++) {
            t[i] = (byte) (r & 1);
            int fb = ((r >>> tapA) ^ (r >>> tapB)) & 1;
            r = (r >>> 1) | (fb << (bits - 1));
        }
        return t;
    }

    static final byte[] NOISE_LONG = lfsrTable(15, 0, 1, PSG_NOISE_LONG_LEN);
    static final byte[] NOISE_MID = lfsrTable(11, 0, 2, PSG_NOISE_MID_LEN);
    static final byte[] NOISE_SHORT = lfsrTable(7, 0, 1, PSG_NOISE_SHORT_LEN);

    /** ToUint32 then `>>> n`, as the JS's `p >>> n` on a phase that may be NaN. */
    private static int ushr(double p, int n) { return JsMath.toInt32(p) >>> n; }

    /* Waveform w at 32-bit phase p, on 0…1, before the invert flag
     * (psgWaveValue); a noise reads its table at noisePos instead. A noise
     * read the JS answers `undefined` (a NaN position) is NaN here, which
     * every use of it then carries exactly as the JS carries undefined. */
    private static double waveValue(int w, double p, double noisePos) {
        switch (w) {
        case PSG_WAVE_DC: return 1;
        case 1: case 2: case 3: case 4: case 5: case 6: case 7:
            return ushr(p, 29) < (8 - w) ? 1 : 0;
        case PSG_WAVE_SAW: return 1 - p / PSG_TWO32;
        case PSG_WAVE_TRIANGLE: {
            double x = p / PSG_TWO32;
            return x < 0.5 ? 2 * x : 2 - 2 * x;
        }
        case PSG_WAVE_NOISE_LONG: case PSG_WAVE_NOISE_MID: case PSG_WAVE_NOISE_SHORT: {
            byte[] t = w == PSG_WAVE_NOISE_LONG ? NOISE_LONG : w == PSG_WAVE_NOISE_MID ? NOISE_MID : NOISE_SHORT;
            int len = t.length;
            double i = noisePos % len;
            if (!(i >= 0)) return Double.NaN;
            return t[(int) i];
        }
        case PSG_WAVE_SAW4: return (15 - ushr(p, 28)) / 15.0;
        case PSG_WAVE_TRIANGLE4: {
            int s = ushr(p, 27);                /* 32 steps a cycle */
            return (s < 16 ? s : 31 - s) / 15.0;
        }
        default: return 0;
        }
    }

    /* ── the patch ── */

    static void decodePsgPatch(byte[] bytes, int at, PsgPatch into) {
        int f = bytes[at + PSG_B_FLAGS] & 0xff;
        into.multiply = (f & PSG_MIX_MULTIPLY) != 0;
        into.muteA = (f & PSG_MUTE_A) != 0;
        into.muteB = (f & PSG_MUTE_B) != 0;
        for (int i = 0; i < 2; i++) {
            int o = at + PSG_B_OSC + i * PSG_OSC_BYTES;
            PsgPatch.Oscillator osc = into.osc[i];
            int b0 = bytes[o] & 0xff, b1 = bytes[o + 1] & 0xff, b2 = bytes[o + 2] & 0xff;
            int b3 = bytes[o + 3] & 0xff, b4 = bytes[o + 4] & 0xff;
            int tt = (b4 >> PSG_OF_TREMOLO_SHIFT) & 3, vv = (b4 >> PSG_OF_VIBRATO_SHIFT) & 3;
            osc.wave = b0 >> 4; osc.op.ar = b0 & 15;
            osc.op.dr = b1 >> 4; osc.op.sl = Opl.OPL_SUSTAIN_LEVEL[b1 & 15];
            osc.op.rr = b2 >> 4; osc.op.mult = b2 & 15;
            osc.op.ksl = b3 >> 6; osc.op.tl = b3 & 63;
            osc.op.ksr = (b4 & PSG_OF_KSR) != 0 ? 1 : 0;
            osc.op.eg = (b4 & PSG_OF_SUSTAIN) != 0 ? 1 : 0;
            osc.reset = (b4 & PSG_OF_RESET) != 0;
            osc.invert = (b4 & PSG_OF_INVERT) != 0;
            osc.tremolo = tt == 3 ? 0 : tt;
            osc.vibrato = vv == 3 ? 0 : vv;
            osc.advance = bytes[o + PSG_O_ADVANCE] & 0xff;
            osc.pattern = bytes[o + PSG_O_PATTERN] & 3;
        }
        into.gen++;
    }

    /* ── the run-time overlay (S $Axxx) ── */

    static void clearRuntime(PsgPatch patch) {
        if (patch.rtAdvance[0] == -1 && patch.rtAdvance[1] == -1 &&
            patch.rtLevel[0] == -1 && patch.rtLevel[1] == -1 && patch.rtMute == -1) return;
        patch.rtAdvance[0] = patch.rtAdvance[1] = -1;
        patch.rtLevel[0] = patch.rtLevel[1] = -1;
        patch.rtMute = -1;
        patch.rtGen++;
    }

    /** $000…$0FF / $800…$8FF an auto-advance, $100…$13F / $900…$93F a level,
     *  $F00…$F03 the two mutes outright; anything else does nothing. */
    static boolean writeRegister(PsgPatch patch, int reg) {
        int r = reg & 0xfff;
        int i = (r & 0x800) != 0 ? 1 : 0;
        int hi = (r >> 8) & 0x7;
        if (r >= 0xf00) {
            if (r > 0xf03) return false;
            patch.rtMute = r & 3;
        } else if (hi == 0) {
            patch.rtAdvance[i] = r & 0xff;
        } else if (hi == 1 && (r & 0xff) <= 0x3f) {
            patch.rtLevel[i] = r & 0x3f;
        } else {
            return false;
        }
        patch.rtGen++;
        return true;
    }

    private static int level(PsgPatch patch, int i) {
        int v = patch.rtLevel[i];
        return v >= 0 ? v : patch.osc[i].op.tl;
    }

    private static int advance(PsgPatch patch, int i) {
        int v = patch.rtAdvance[i];
        return v >= 0 ? v : patch.osc[i].advance;
    }

    private static boolean muted(PsgPatch patch, int i) {
        if (patch.rtMute >= 0) return ((patch.rtMute >> i) & 1) != 0;
        return i == 0 ? patch.muteA : patch.muteB;
    }

    /* ── the voice ── */

    static void stateInit(PsgState st) { st.init(); }

    static void keyOn(PsgState st, PsgPatch patch, boolean carry) {
        st.patch = patch;
        st.gen = -1;
        st.rate = -1;
        if (!carry) {
            st.env[0] = st.env[1] = OPL_ENV_MAX;
            st.cur = 0;
            st.last = 0;
            st.kValid = false;
        }
        for (int i = 0; i < 2; i++) {
            st.state[i] = EG_ATTACK;
            if (patch.osc[i].reset) {
                st.phase[i] = 0;
                st.noise[i] = 0;
                st.adv[i] = 0;
                st.advCount[i] = 0;
                st.lfo[i] = Double.NaN;         /* anchored at the first frame rendered */
            } else {
                st.lfo[i] = 0;                  /* the chip clock itself */
            }
        }
        st.keyed = true;
    }

    static void keyOff(PsgState st) {
        for (int i = 0; i < 2; i++) if (st.state[i] != EG_OFF) st.state[i] = EG_RELEASE;
        st.keyed = false;
    }

    static void reattack(PsgState st) {
        st.state[0] = st.state[1] = EG_ATTACK;
        st.keyed = true;
    }

    /* The auto-advance's four patterns, as offsets from the record's pulse. */
    private static final int[][] PATTERNS = {
        { 1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2 },
        { 1, 3, 5, 7, 5, 3 },
        { 1, 4, 7, 4 },
        { 1, 4 },
    };

    private static boolean advances(int w) { return w >= 1 && w <= PSG_WAVE_PULSE_LAST; }

    static int currentWave(PsgState st, int i) {
        PsgPatch.Oscillator osc = st.patch.osc[i];
        int w = osc.wave;
        if (!advances(w)) return w;
        int[] pat = PATTERNS[osc.pattern];
        int k = pat[st.adv[i] % pat.length];
        return ((w - 1 + k - 1) % 7) + 1;
    }

    /** One quarter tick of the auto-advance (psgQuarterTick). */
    static void quarterTick(PsgState st) {
        PsgPatch patch = st.patch;
        if (patch == null) return;
        for (int i = 0; i < 2; i++) {
            int n = advance(patch, i);
            if (n == 0 || !advances(patch.osc[i].wave)) { st.advCount[i] = 0; continue; }
            if (++st.advCount[i] >= n) {
                st.advCount[i] = 0;
                st.adv[i] = (st.adv[i] + 1) % PATTERNS[patch.osc[i].pattern].length;
            }
        }
    }

    /** The quarter of its tick a playhead is in, `into` frames into a tick of
     *  `spt` frames: 0…3. */
    static int quarterAt(double into, double spt) {
        double q = Math.floor((into * 4) / spt);
        return q < 0 ? 0 : q > 3 ? 3 : (int) q;
    }

    private static void retune(Engine e, PsgState st, double rate) {
        PsgPatch patch = st.patch;
        double hz = rate * e.rate * PSG_HZ_PER_SPS;
        int[] scale = e.scaleBox;
        for (int i = 0; i < 2; i++) {
            PsgPatch.Oscillator osc = patch.osc[i];
            Opl.keyScaling(hz, osc.op.ksr, osc.op.ksl, scale);
            st.ksrOffset[i] = scale[0];
            st.kslAtt[i] = scale[1];
            double inc = JsMath.round((hz * Opl.OPL_MULTIPLE_X2[osc.op.mult] * PSG_TWO32) / (2 * OPL_RATE_HZ));
            st.inc[i] = inc < 0 ? 0 : inc >= PSG_TWO32 ? PSG_TWO32 - 1 : inc;
        }
        st.rate = rate;
        st.gen = patch.gen;
    }

    /* Oscillator i for one chip sample at chip clock k (psgOscStep): its
     * waveform in volts, 0…1 with the invert applied, and its gain left in
     * st.gain[i]. */
    private static double oscStep(PsgState st, int i, long k) {
        PsgPatch patch = st.patch;
        PsgPatch.Oscillator osc = patch.osc[i];
        Opl.advanceEnvelope(st.state, st.env, i, st.ksrOffset[i], osc.op, (int) k);
        if (Double.isNaN(st.lfo[i])) st.lfo[i] = (double) k;
        double lk = (double) k - st.lfo[i];     /* never negative: every key-on sets the origin */
        double inc = st.inc[i];
        if (osc.vibrato != 0) {
            double vib = Opl.vibratoAt(osc.vibrato - 1 != 0, (int) (lk % OPL_VIBRATO_PERIOD));
            if (vib != 1) inc = JsMath.round(inc * vib);
        }
        /* The noise is clocked by the phase: one step each 1/16 of a cycle crossed. */
        double old = st.phase[i];
        double next = old + inc;
        double steps = Math.floor(next / PSG_TWO28) - Math.floor(old / PSG_TWO28);
        if (steps != 0) st.noise[i] = (st.noise[i] + steps) % PSG_NOISE_WRAP;
        st.phase[i] = next >= PSG_TWO32 ? next - PSG_TWO32 * Math.floor(next / PSG_TWO32) : next;
        if (st.state[i] == EG_OFF) { st.gain[i] = 0; return 0; }
        double att = (st.env[i] + st.kslAtt[i]) * PSG_ENV_TO_LOG + level(patch, i) * PSG_TL_TO_LOG;
        if (osc.tremolo != 0) att += Opl.tremoloAt(osc.tremolo - 1 != 0, (int) (lk % OPL_TREMOLO_PERIOD));
        st.gain[i] = Opl.expand(att);
        int w = currentWave(st, i);
        double p = (st.phase[i] + st.pm) % PSG_TWO32;
        double v = waveValue(w, p, st.noise[i]);
        return osc.invert ? 1 - v : v;          /* 0…1 turned over: DC <-> silence */
    }

    /* The pair's next chip sample (psgChipStep). Both oscillators always run,
     * muted or not; with one muted the other passes through untouched. */
    private static double chipStep(PsgState st, long k) {
        double a = oscStep(st, 0, k);
        double b = oscStep(st, 1, k);
        double ga = st.gain[0], gb = st.gain[1];
        PsgPatch patch = st.patch;
        boolean ma = muted(patch, 0), mb = muted(patch, 1);
        if (ma) return mb ? 0 : (2 * b - 1) * gb;
        if (mb) return (2 * a - 1) * ga;
        return patch.multiply
            ? (2 * a * b - 1) * ((ga * gb) / 4084)
            : (2 * a - 1) * ga + (2 * b - 1) * gb;
    }

    private static boolean oscAlive(PsgState st, int i) {
        int s = st.state[i];
        if (s == EG_OFF) return false;
        return !(st.env[i] >= OPL_ENV_MAX && (s != EG_ATTACK || st.patch.osc[i].op.ar == 0));
    }

    static boolean channelAlive(PsgState st) {
        if (st.patch == null) return false;
        return oscAlive(st, 0) || oscAlive(st, 1);
    }

    /** The pair at output frame `frame`, on the lane's ±1 (psgRender). The
     *  note volume is not applied: the mixer applies it, as to a sample's. */
    static double render(Engine e, PsgState st, long frame, double rate, boolean keyOff) {
        PsgPatch patch = st.patch;
        if (patch == null) return 0;
        if (keyOff && st.keyed) keyOff(st);
        if (rate != st.rate || patch.gen != st.gen) retune(e, st, rate);
        double[] frac = e.fracBox;
        long k = Opl.chipIndex(e, frame, frac);
        if (!st.kValid || k - st.k > 8) { st.k = k - 1; st.kValid = true; }
        while (st.k < k) {
            st.k++;
            st.last = st.cur;
            st.cur = chipStep(st, st.k);
        }
        return (st.last + (st.cur - st.last) * frac[0]) * PSG_OUTPUT_SCALE;
    }

    static double renderPsgVoice(Engine e, Voice voice, long frame) {
        PsgState st = voice.psg;
        if (st.patch == null) return 0;
        double s = render(e, st, frame, voice.currentPlaybackRate, voice.keyOff);
        if (!channelAlive(st)) voice.active = false;
        return s;
    }
}
