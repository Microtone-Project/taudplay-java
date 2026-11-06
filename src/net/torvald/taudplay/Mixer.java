/*
 * Mixer + output quantiser — port of mixer.js (taudplay-c mixer.c).
 *
 * The voices sum in binary64; the pair narrows to binary32 (the mix bus)
 * exactly once per frame, then mastering, clamp and (optionally) dither.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Mixer {
    private Mixer() {}

    private static final double PI = 3.141592653589793;

    /** One voice's contribution as a [left, right] pair, before pan and gain. */
    private static void renderVoicePair(Engine e, Voice v, Inst inst, int interpMode, double spt,
                                        double[] out, int n) {
        double s;
        /* An FM rack (item 159) replaces the voice's own sample fetch. It is
         * asked first because its operator 0 may itself be a chip channel
         * (§5.5.1), which the rack reads — the voice it sounds on is the
         * rack's. */
        if (v.fmRig != null) {
            s = Fm.renderFmVoice(e, v, interpMode, spt, e.oplFrame + n);
            s = Filter.applyVoiceFilterSelf(v, s);
            s = Filter.applyTaudVoiceFx(v, s, null);
            out[0] = s;
            out[1] = s;
            return;
        }
        /* An OPL channel (item 209) replaces the sample fetch, reading the
         * chip clock at this frame; it has applied the note volume itself. */
        if (v.opl != null) {
            s = Opl.renderOplVoice(e, v, e.oplFrame + n);
            s = Filter.applyVoiceFilterSelf(v, s);
            s = Filter.applyTaudVoiceFx(v, s, null);
            out[0] = s;
            out[1] = s;
            return;
        }
        /* A PSG pair (§7.9) on the same chip clock. Unlike OPL it leaves the
         * note volume to the gain chain, as a sample does: its multiplying
         * mix would otherwise square it. */
        if (v.psg != null) {
            s = Psg.renderPsgVoice(e, v, e.oplFrame + n);
            s = Filter.applyVoiceFilterSelf(v, s);
            s = Filter.applyTaudVoiceFx(v, s, null);
            out[0] = s;
            out[1] = s;
            return;
        }
        /* A formant voice (item 212) plays its sample — or the chip channel it
         * is sourced from, on the same clock — through the tract. */
        if (v.formant != null) {
            s = Formant.renderFormantVoice(e, v, inst, interpMode, e.oplFrame + n);
            s = Filter.applyVoiceFilterSelf(v, s);
            s = Filter.applyTaudVoiceFx(v, s, null);
            out[0] = s;
            out[1] = s;
            return;
        }
        if (v.activeChanCount != 2) {
            s = Sampler.fetchTrackerSample(e, v, inst, interpMode, 0);
            s = Filter.applyVoiceFilterSelf(v, s);
            s = Filter.applyTaudVoiceFx(v, s, null);
            out[0] = s;
            out[1] = s;
            return;
        }
        Sampler.fetchTrackerSampleStereo(e, v, inst, interpMode, out);
        double c0 = out[0];
        double c1 = out[1];
        if (v.activeChanMode == CHAN_MODE_MATRIX) {
            double m = c0, sd = c1;
            c0 = m + sd;
            c1 = m - sd;
        }
        out[0] = Filter.applyTaudVoiceFx(v, Filter.applyVoiceFilterSelf(v, c0), null);
        out[1] = Filter.applyTaudVoiceFx(v, Filter.applyVoiceFilter(v, c1, v.right), v.right);
    }

    /* ── pcm32fToPcm8: noise-shaped TPDF dither, binary32 at every step ── */

    /** One step of the dither's xorshift32 stream; the state is xs[0]. */
    static int xorshift32(int[] xs) {
        int x = xs[0];
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        xs[0] = x;
        return x;
    }

    private static float urand(int[] xs) { return (float) ((xorshift32(xs) & 0xffffff) / 16777216.0); }
    private static float tpdf1(int[] xs) { float a = urand(xs); float b = urand(xs); return a - b; }

    /** The device's dither over any pair of buffers, its state passed in: the
     *  engine's own, or a fresh one (seed TE_XORSHIFT_SEED, errors zero) for a
     *  gritty WAV. Writes interleaved unsigned 8-bit pairs at out[outOff]. */
    static void ditherU8(int[] xs, float[] err, float[] left, float[] right, int off, int frames, byte[] out, int outOff) {
        final float b1 = 1.5f, b2 = -0.75f, scale = 127.5f, ditherScale = 0.2f;
        for (int i = 0; i < frames; i++) {
            /* left */
            float feedback = (b1 * err[0]) + (b2 * err[1]);
            float dither = ditherScale * tpdf1(xs);
            float shaped = (left[off + i] + feedback) + (dither / scale);
            shaped = shaped < -1.0f ? -1.0f : shaped > 1.0f ? 1.0f : shaped;
            int q = (int) JsMath.round(shaped * scale);
            q = q < -128 ? -128 : q > 127 ? 127 : q;
            out[outOff + i * 2] = (byte) ((q + 128) & 0xff);
            float qerr = shaped - (q / scale);
            err[1] = err[0];
            err[0] = qerr;
            /* right */
            feedback = (b1 * err[2]) + (b2 * err[3]);
            dither = ditherScale * tpdf1(xs);
            shaped = (right[off + i] + feedback) + (dither / scale);
            shaped = shaped < -1.0f ? -1.0f : shaped > 1.0f ? 1.0f : shaped;
            q = (int) JsMath.round(shaped * scale);
            q = q < -128 ? -128 : q > 127 ? 127 : q;
            out[outOff + i * 2 + 1] = (byte) ((q + 128) & 0xff);
            qerr = shaped - (q / scale);
            err[3] = err[2];
            err[2] = qerr;
        }
    }

    static void pcm32fToPcm8(Engine e, byte[] out) {
        int[] xs = { e.xorshift };
        ditherU8(xs, e.ditherError, e.mixLeft, e.mixRight, 0, TRACKER_CHUNK, out, 0);
        e.xorshift = xs[0];
    }

    private static double attackGain(Engine e, int attackRampSamples) {
        int elapsed = e.attackRampSamples - attackRampSamples;
        return 0.5 - 0.5 * js_cos((PI * elapsed) / e.attackRampSamples);
    }

    /* Every instrument bus runs its chain over [n0, n1) and joins the mix —
     * the stereo accumulators, or the object bus channel for channel — scaled
     * by the song's global gain, in ascending slot order (runImixBuses). A bus
     * whose chain is idle and was not fed is skipped outright. Each bus is
     * then left clear for the next span. */
    private static void runImixBuses(Engine e, int n0, int n1, double globalGain) {
        SpatialBus spatial = e.spatial;
        for (int b = 0; b < e.imixCount; b++) {
            Imix.Bus bus = e.imixList[b];
            Imix.Chain chain = bus.chain;
            double[] d = bus.sb.data;
            boolean hit = false;
            /* A note on ANY instrument of a shared bus retriggers its LFOs. */
            for (int k = 0; k < bus.nSlots; k++) {
                if (e.imixTrig[bus.slots[k]]) { e.imixTrig[bus.slots[k]] = false; hit = true; }
            }
            if (hit) chain.noteOn();
            if (chain.idle && !bus.fed) {
                chain.process(d, TRACKER_CHUNK, n0, n1, e.bpm, false);
            } else {
                chain.process(d, TRACKER_CHUNK, n0, n1, e.bpm, bus.fed);
                if (spatial == null) {
                    for (int n = n0; n < n1; n++) {
                        e.accL[n] += d[n] * globalGain;
                        e.accR[n] += d[TRACKER_CHUNK + n] * globalGain;
                    }
                } else {
                    for (int c = 0; c < bus.nch; c++) {
                        int o = c * TRACKER_CHUNK;
                        for (int n = n0; n < n1; n++) spatial.data[o + n] += d[o + n] * globalGain;
                    }
                }
            }
            for (int c = 0; c < bus.nch; c++) java.util.Arrays.fill(d, c * TRACKER_CHUNK + n0, c * TRACKER_CHUNK + n1, 0);
            bus.fed = false;
        }
    }

    /* Finish frames [n0, n1) — one of the JS mixer's spans, which end where
     * the tick clock fires next: the buses join the mix, then every frame is
     * folded to the device's pair, through the Amiga filters, and narrowed to
     * binary32. It runs before that tick, so it sees the span's tempo, gain
     * and filter state. */
    private static void finishSpan(Engine e, int n0, int n1) {
        SpatialBus spatial = e.spatial;
        if (e.imixCount > 0) {
            double gvol = e.globalVolume / 255.0;
            double mvol = e.mixingVolume / 255.0;
            runImixBuses(e, n0, n1, (gvol * mvol * e.masterVolume) / 255.0);
        }
        double[] pair = e.pairBox;
        for (int n = n0; n < n1; n++) {
            double mixL, mixR;
            if (spatial != null) {
                Spatial.busStereoAt(spatial, n, pair);
                mixL = pair[0];
                mixR = pair[1];
            } else {
                mixL = e.accL[n];
                mixR = e.accR[n];
            }

            if (e.interpolationMode == INTERP_A500) {
                e.amigaLPStateL = mixL * e.a500A0 + e.amigaLPStateL * e.a500B1;
                e.amigaLPStateR = mixR * e.a500A0 + e.amigaLPStateR * e.a500B1;
                mixL = e.amigaLPStateL;
                mixR = e.amigaLPStateR;
            }
            if ((e.interpolationMode == INTERP_A500 || e.interpolationMode == INTERP_A1200) && e.ledFilterOn) {
                double[] sl = e.amigaLEDStateL, sr = e.amigaLEDStateR;
                double outL = mixL * e.ledA1 + sl[0] * e.ledA2 + sl[1] * e.ledA1 - sl[2] * e.ledB1 - sl[3] * e.ledB2;
                double outR = mixR * e.ledA1 + sr[0] * e.ledA2 + sr[1] * e.ledA1 - sr[2] * e.ledB1 - sr[3] * e.ledB2;
                sl[1] = sl[0]; sl[0] = mixL; sl[3] = sl[2]; sl[2] = outL;
                sr[1] = sr[0]; sr[0] = mixR; sr[3] = sr[2]; sr[2] = outR;
                mixL = outL;
                mixR = outR;
            }

            e.mixLeft[n] = (float) mixL;
            e.mixRight[n] = (float) mixR;
        }
    }

    /** The bus a voice mixes into (item 217), or null for the plain path: the
     *  chain of the instrument the NOTE named — a metainstrument's, not its
     *  layer's. */
    private static Imix.Bus voiceRoute(Engine e, Voice v) {
        if (e.imixCount == 0) return null;
        return e.imixRoutes[(v.displayInst != 0 ? v.displayInst : v.instrumentId) & 0x3ff];
    }

    /** Render one TRACKER_CHUNK into mixLeft/mixRight. */
    static boolean generateTrackerAudio(Engine e) {
        boolean advancing = e.isPlaying;
        SpatialBus spatial = e.spatial;
        double srate = e.rate;
        int n0 = 0;
        double[] pair = e.voicePairBox;

        if (spatial != null) {
            java.util.Arrays.fill(spatial.data, 0, spatial.numChannels * TRACKER_CHUNK, 0.0);
        }

        if (advancing && e.firstRow) {
            e.firstRow = false;
            Row.applyTrackerRow(e);
        }
        /* Instrument mixing chains (item 217): bring the route table up to
         * date with the blocks, the records and the channel layout (imixGen
         * counts every change to any of them). */
        if (e.imixSyncedGen != e.imixGen) Imix.syncRoutes(e);

        for (int n = 0; n < TRACKER_CHUNK; n++) {
            double spt = (srate * 2.5) / e.bpm;

            /* A tick about to fire ends the span before it. */
            if (n > n0 && e.samplesIntoTick + 1.0 >= spt) {
                finishSpan(e, n0, n);
                n0 = n;
            }

            if (advancing) {
                e.samplesIntoTick += 1.0;
                if (e.samplesIntoTick >= spt) {
                    e.samplesIntoTick -= spt;
                    Tick.applyTrackerTick(e);
                    e.tickInRow++;
                    if (e.tickInRow >= e.tickRate + e.finePatternDelayExtra) {
                        e.tickInRow = 0;
                        Row.advanceRow(e);
                    } else {
                        /* what the row scheduled for the tick now starting (item 232) */
                        Tick.applyTickEvents(e);
                    }
                }
            } else {
                e.samplesIntoTick += 1.0;
                if (e.samplesIntoTick >= spt) {
                    e.samplesIntoTick -= spt;
                    Tick.applyTrackerTick(e);
                }
            }
            /* PSG quarter ticks (§5.5.4): the tick pass ran the first; each
             * later quarter runs at the first frame inside it, while a PSG
             * pair sounds. */
            if (Tick.psgLive(e)) {
                int q = Psg.quarterAt(e.samplesIntoTick, spt);
                while (e.psgQuarter < q) { e.psgQuarter++; Tick.psgQuarterPass(e); }
            }

            double gvol = e.globalVolume / 255.0;
            double mvol = e.mixingVolume / 255.0;
            double globalGain = (gvol * mvol * e.masterVolume) / 255.0;
            e.accL[n] = 0.0;
            e.accR[n] = 0.0;

            for (int vi = 0; vi < MAX_VOICES; vi++) {
                Voice voice = e.voices[vi];
                double lGain = 0.0, rGain = 0.0, rampGain;
                if (!voice.active || voice.fader == 255) continue;
                Inst inst = e.instruments[voice.instrumentId];
                Tick.advanceSampleModExtended(e, voice, spt);
                renderVoicePair(e, voice, inst, e.interpolationMode, spt, pair, n);
                double sL = pair[0];
                double sR = pair[1];
                double instGv = inst.instGlobalVolume / 255.0;
                double swingScale = 1.0 + voice.randomVolBias / 255.0;
                voice.envVolMix += voice.envVolStep;
                double effEnvVol = voice.hasVolEnv && voice.volEnvOn ? voice.envVolMix : 1.0;
                Sampler.advanceVolumeRamp(e, voice, e.volDiv);
                Sampler.advancePitchRamp(e, voice, spt);
                double faderGain = (255 - voice.fader) / 255.0;
                /* An OPL voice took its note volume into the operators —
                 * unless the chip is operator 0 of a rack, which reads it at
                 * unity and leaves the note's volume to the finished patch
                 * (§5.5.1). */
                double perVoiceGain = effEnvVol * voice.fadeoutVolume *
                    (voice.opl != null && voice.fmRig == null ? 1.0 : voice.currentMixVolume) *
                    swingScale * instGv * faderGain * voice.layerMixGain * voice.activeAttenGain;
                double vol = perVoiceGain * globalGain;
                if (spatial == null) {
                    double pan = Sampler.advancePanRamp(e, voice, Spatial.voicePanByte(voice), false);
                    if (pan != voice.panLawPan) {
                        voice.panLawPan = pan;
                        voice.panLawL = js_cos((PI * pan) / 512.0);
                        voice.panLawR = js_sin((PI * pan) / 512.0);
                    }
                    lGain = voice.panLawL;
                    rGain = voice.panLawR;
                } else {
                    Sampler.advancePanRamp(e, voice, Spatial.voiceAzimuth(voice), true);
                }
                if (voice.rampOutSamples > 0) {
                    rampGain = voice.rampOutGain;
                    voice.rampOutGain -= voice.rampOutStep;
                    voice.rampOutSamples--;
                    if (voice.rampOutSamples == 0) voice.active = false;
                } else {
                    rampGain = 1.0;
                }
                if (voice.attackRampSamples > 0) {
                    rampGain *= attackGain(e, voice.attackRampSamples);
                    voice.attackRampSamples--;
                }
                /* An instrument with a mixing chain mixes into its own bus,
                 * ahead of the song's global gain — which the bus's output
                 * gets instead. */
                Imix.Bus route = voiceRoute(e, voice);
                if (route != null) {
                    route.fed = true;
                    if (spatial == null) {
                        route.sb.data[n] += sL * perVoiceGain * lGain * rampGain;
                        route.sb.data[TRACKER_CHUNK + n] += sR * perVoiceGain * rGain * rampGain;
                    } else {
                        double[] g = Spatial.spatialVoiceGains(e, voice);
                        Spatial.busAddSource(route.sb, n, sL * perVoiceGain, g, 0, rampGain);
                        if (voice.activeChanCount == 2) {
                            Spatial.busAddSource(route.sb, n, sR * perVoiceGain, g, spatial.numChannels, rampGain);
                        }
                    }
                } else if (spatial == null) {
                    e.accL[n] += sL * vol * lGain * rampGain;
                    e.accR[n] += sR * vol * rGain * rampGain;
                } else {
                    double[] g = Spatial.spatialVoiceGains(e, voice);
                    Spatial.busAddSource(spatial, n, sL * vol, g, 0, rampGain);
                    if (voice.activeChanCount == 2) {
                        Spatial.busAddSource(spatial, n, sR * vol, g, spatial.numChannels, rampGain);
                    }
                }
            }

            for (int i = 0; i < e.bgCount; i++) {
                Voice bg = e.bg[i];
                double lGain = 0.0, rGain = 0.0, rampGain;
                if (bg.fmOperator) continue;
                Voice srcVoice = bg.sourceChannel >= 0 && bg.sourceChannel < MAX_VOICES ? e.voices[bg.sourceChannel] : null;
                int bgFader = srcVoice != null && srcVoice.fader > bg.fader ? srcVoice.fader : bg.fader;
                if (!bg.active || bgFader == 255) continue;
                Inst inst = e.instruments[bg.instrumentId];
                if (bg.isLayerChild) Tick.advanceSampleModExtended(e, bg, spt);
                renderVoicePair(e, bg, inst, e.interpolationMode, spt, pair, n);
                double sL = pair[0];
                double sR = pair[1];
                double instGv = inst.instGlobalVolume / 255.0;
                double swingScale = 1.0 + bg.randomVolBias / 255.0;
                bg.envVolMix += bg.envVolStep;
                double effEnvVol = bg.hasVolEnv && bg.volEnvOn ? bg.envVolMix : 1.0;
                Sampler.advanceVolumeRamp(e, bg, e.volDiv);
                Sampler.advancePitchRamp(e, bg, spt);
                double faderGain = (255 - bgFader) / 255.0;
                double vol = (effEnvVol * bg.fadeoutVolume *
                              (bg.opl != null && bg.fmRig == null ? 1.0 : bg.currentMixVolume) *
                              swingScale * gvol * mvol * instGv * faderGain * bg.layerMixGain * bg.activeAttenGain *
                              e.masterVolume) / 255.0;
                if (spatial == null) {
                    double pan = Sampler.advancePanRamp(e, bg, Spatial.voicePanByte(bg), false);
                    if (pan != bg.panLawPan) {
                        bg.panLawPan = pan;
                        bg.panLawL = js_cos((PI * pan) / 512.0);
                        bg.panLawR = js_sin((PI * pan) / 512.0);
                    }
                    lGain = bg.panLawL;
                    rGain = bg.panLawR;
                } else {
                    Sampler.advancePanRamp(e, bg, Spatial.voiceAzimuth(bg), true);
                }
                if (bg.rampOutSamples > 0) {
                    rampGain = bg.rampOutGain;
                    bg.rampOutGain -= bg.rampOutStep;
                    bg.rampOutSamples--;
                    if (bg.rampOutSamples == 0) bg.active = false;
                } else {
                    rampGain = 1.0;
                }
                if (bg.attackRampSamples > 0) {
                    rampGain *= attackGain(e, bg.attackRampSamples);
                    bg.attackRampSamples--;
                }
                Imix.Bus route = voiceRoute(e, bg);
                if (route != null) {
                    /* The same chain without the three song-level gains the bus is ahead of. */
                    double bvol = effEnvVol * bg.fadeoutVolume *
                        (bg.opl != null && bg.fmRig == null ? 1.0 : bg.currentMixVolume) *
                        swingScale * instGv * faderGain * bg.layerMixGain * bg.activeAttenGain;
                    route.fed = true;
                    if (spatial == null) {
                        route.sb.data[n] += sL * bvol * lGain * rampGain;
                        route.sb.data[TRACKER_CHUNK + n] += sR * bvol * rGain * rampGain;
                    } else {
                        double[] g = Spatial.spatialVoiceGains(e, bg);
                        Spatial.busAddSource(route.sb, n, sL * bvol, g, 0, rampGain);
                        if (bg.activeChanCount == 2) {
                            Spatial.busAddSource(route.sb, n, sR * bvol, g, spatial.numChannels, rampGain);
                        }
                    }
                } else if (spatial == null) {
                    e.accL[n] += sL * vol * lGain * rampGain;
                    e.accR[n] += sR * vol * rGain * rampGain;
                } else {
                    double[] g = Spatial.spatialVoiceGains(e, bg);
                    Spatial.busAddSource(spatial, n, sL * vol, g, 0, rampGain);
                    if (bg.activeChanCount == 2) {
                        Spatial.busAddSource(spatial, n, sR * vol, g, spatial.numChannels, rampGain);
                    }
                }
            }
        }
        finishSpan(e, n0, TRACKER_CHUNK);

        if (e.mastering != null) e.mastering.process(e.mixLeft, e.mixRight, TRACKER_CHUNK);
        /* The OPL chip clock (item 209) counts the frames this playhead has mixed. */
        e.oplFrame += TRACKER_CHUNK;
        for (int n = 0; n < TRACKER_CHUNK; n++) {
            float fl = e.mixLeft[n], fr = e.mixRight[n];
            if (fl < -1.0f) e.mixLeft[n] = -1.0f; else if (fl > 1.0f) e.mixLeft[n] = 1.0f;
            if (fr < -1.0f) e.mixRight[n] = -1.0f; else if (fr > 1.0f) e.mixRight[n] = 1.0f;
        }

        /* A halt cue clears isPlaying mid-chunk: end the song's voices here. */
        if (advancing && !e.isPlaying) e.silenceSongVoices(false);
        return true;
    }
}
