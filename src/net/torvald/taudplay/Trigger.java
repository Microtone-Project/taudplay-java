/*
 * Trigger path + NNA/Metainstrument machinery — port of trigger.js
 * (taudplay-c trigger.c).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Trigger {
    private Trigger() {}

    /** What the INSTRUMENT left on the note-pan axis for the trigger just
     *  run: [notePan, noteElevation, present]. Read only by
     *  triggerMetaOrNote. */
    private static final class NotePanSeed { double pan, elevation; boolean present; }

    private static void resolveActiveEnvelopes(Voice v, Inst inst, Patch patch) {
        EnvPoint[] pitEnv = inst.pfEnvelopes, filEnv = inst.pfEnvelopes;
        int pitLoop = 0, pitSus = 0, filLoop = 0, filSus = 0;
        boolean pitOn = false, filOn = false;

        if (patch != null && patch.hasVolEnv) {
            v.activeVolEnv = patch.volEnv;
            v.activeVolEnvLoop = patch.volEnvLoop;
            v.activeVolEnvSustain = patch.volEnvSustain;
        } else {
            v.activeVolEnv = inst.volEnvelopes;
            v.activeVolEnvLoop = inst.volEnvLoop;
            v.activeVolEnvSustain = inst.volEnvSustainWord;
        }
        /* An Ixmp patch's volume envelope is in its instrument's units: byte
         * 14's bit 6 says whether a node's full volume is 63 or 255 (file
         * format §7.2). */
        v.activeVolEnvMax = inst.volEnv8() ? 255 : 63;
        /* The volume envelope answers to its P bit like every other envelope
         * (spec §7.2): a record that says "absent" plays at unity whatever its
         * nodes hold. */
        v.hasVolEnv = Envelope.envPresent(v.activeVolEnvLoop);
        if (patch != null && patch.hasPanEnv) {
            v.activePanEnv = patch.panEnv;
            v.activePanEnvLoop = patch.panEnvLoop;
            v.activePanEnvSustain = patch.panEnvSustain;
        } else {
            v.activePanEnv = inst.panEnvelopes;
            v.activePanEnvLoop = inst.panEnvLoop;
            v.activePanEnvSustain = inst.panEnvSustainWord;
        }

        if (Envelope.envPresent(inst.pfEnvLoop)) {
            if (((inst.pfEnvLoop >> 7) & 1) != 0) {
                filEnv = inst.pfEnvelopes; filLoop = inst.pfEnvLoop; filSus = inst.pfEnvSustainWord; filOn = true;
            } else {
                pitEnv = inst.pfEnvelopes; pitLoop = inst.pfEnvLoop; pitSus = inst.pfEnvSustainWord; pitOn = true;
            }
        }
        if (Envelope.envPresent(inst.pf2EnvLoop)) {
            if (((inst.pf2EnvLoop >> 7) & 1) != 0) {
                filEnv = inst.pf2Envelopes; filLoop = inst.pf2EnvLoop; filSus = inst.pf2EnvSustainWord; filOn = true;
            } else {
                pitEnv = inst.pf2Envelopes; pitLoop = inst.pf2EnvLoop; pitSus = inst.pf2EnvSustainWord; pitOn = true;
            }
        }
        if (patch != null && patch.hasPitchEnv) {
            pitEnv = patch.pitchEnv; pitLoop = patch.pitchEnvLoop; pitSus = patch.pitchEnvSustain;
            pitOn = Envelope.envPresent(patch.pitchEnvLoop);
        }
        if (patch != null && patch.hasFilterEnv) {
            filEnv = patch.filterEnv; filLoop = patch.filterEnvLoop; filSus = patch.filterEnvSustain;
            filOn = Envelope.envPresent(patch.filterEnvLoop);
        }
        v.activePitchEnv = pitEnv; v.activePitchEnvLoop = pitLoop;
        v.activePitchEnvSustain = pitSus; v.hasPitchEnv = pitOn;
        v.activeFilterEnv = filEnv; v.activeFilterEnvLoop = filLoop;
        v.activeFilterEnvSustain = filSus; v.hasFilterEnv = filOn;

        if (patch != null && patch.hasExtra) {
            v.activeFadeoutStep = patch.fadeoutStep;
            v.filterSfMode = patch.filterSfMode;
            v.activeDefaultCutoff = patch.extraCutoff;
            v.activeDefaultResonance = patch.extraResonance;
            v.activeAttenGain = Tables.attenGainOf(patch.extraInitialAttenOctet);
        } else {
            v.activeFadeoutStep = inst.volumeFadeoutLow | ((inst.fadeoutHigh & 0x0f) << 8);
            v.filterSfMode = inst.filterSfMode();
            v.activeDefaultCutoff = inst.defaultCutoff16();
            v.activeDefaultResonance = inst.defaultResonance16();
            v.activeAttenGain = Tables.attenGainOf(inst.initialAttenOctet);
        }
    }

    static void applyActiveSample(Voice v, Inst inst, Patch patch) {
        v.activePatchIndex = patch == null || inst.extraPatches == null ? -1 : patch.index;
        if (patch == null) {
            v.activeSamplePtr = inst.samplePtr;
            v.activeSampleLength = inst.sampleLength;
            v.activeSamplePlayStart = inst.samplePlayStart;
            v.activeSampleLoopStart = inst.sampleLoopStart;
            v.activeSampleLoopEnd = inst.sampleLoopEnd;
            /* An OPL or PSG instrument's rate is its C2SPD; 0 plays at the
             * tracker convention (§7.8, §7.9) — the pitch has to come from
             * somewhere. */
            v.activeSamplingRate = inst.isChip() && inst.samplingRate == 0 ? OPL_C4_RATE : inst.samplingRate;
            v.activeSampleDetune = inst.sampleDetuneSigned() + inst.dSampleDetune;
            v.activeLoopMode = inst.loopMode;
            v.activeVibratoSpeed = inst.vibratoSpeed;
            v.activeVibratoSweep = inst.vibratoSweep;
            v.activeVibratoDepth = inst.vibratoDepth;
            v.activeVibratoRate = inst.vibratoRate;
            v.activeVibratoWaveform = inst.vibratoWaveform();
            v.activeChanCount = 1;
            v.activeChanMode = 0;
            v.activeChanPtr2 = 0;
        } else {
            boolean vibDefer = patch.vibratoInherits();
            v.activeSamplePtr = patch.samplePtr;
            v.activeSampleLength = patch.sampleLength;
            v.activeSamplePlayStart = patch.playStart;
            v.activeSampleLoopStart = patch.loopStart;
            v.activeSampleLoopEnd = patch.loopEnd;
            v.activeSamplingRate = patch.samplingRate;
            v.activeSampleDetune = patch.sampleDetune + patch.dDetune;
            v.activeLoopMode = patch.loopMode;
            v.activeVibratoSpeed = vibDefer ? inst.vibratoSpeed : patch.vibratoSpeed;
            v.activeVibratoSweep = vibDefer ? inst.vibratoSweep : patch.vibratoSweep;
            v.activeVibratoDepth = vibDefer ? inst.vibratoDepth : patch.vibratoDepth;
            v.activeVibratoRate = vibDefer ? inst.vibratoRate : patch.vibratoRate;
            v.activeVibratoWaveform = patch.vibratoWaveform == 0xff ? inst.vibratoWaveform() : patch.vibratoWaveform;
            if (patch.isStereo()) {
                v.activeChanCount = 2;
                v.activeChanMode = patch.chanMode;
                v.activeChanPtr2 = patch.chanPtrs[0];
            } else {
                v.activeChanCount = 1;
                v.activeChanMode = 0;
                v.activeChanPtr2 = 0;
            }
        }
        resolveActiveEnvelopes(v, inst, patch);
    }

    static int rowVolumeFromDefault(Inst inst, Patch patch, int volMax) {
        int dnv = patch != null && patch.defaultNoteVolume != 0 ? patch.defaultNoteVolume : inst.defaultNoteVolume;
        if (dnv == 0) return volMax;
        return volMax == 0xff ? dnv : (dnv * 63 + 127) / 255;
    }

    /** Drop bg[idx] on the spot — and, for a ghosted rack, its operands too. */
    private static void dropBackgroundVoice(Engine e, int idx) {
        Voice culled = e.bg[idx];
        culled.active = false;
        e.bgRemoveAt(idx);
        if (culled.fmRig != null) {
            for (int i = e.bgCount - 1; i >= 0; i--) {
                Voice bg = e.bg[i];
                if (!bg.fmOperator || bg.fmParent != culled) continue;
                bg.active = false;
                bg.fmOperator = false;
                bg.fmParent = null;
                bg.isLayerChild = false;
                e.bgRemoveAt(i);
            }
        }
    }

    /** Already on its way out: no longer counts towards the cap. */
    private static boolean bgLeaving(Voice v) {
        return !v.active || v.rampOutSamples > 0 ||
            (v.fmOperator && v.fmParent != null && v.fmParent.rampOutSamples > 0);
    }

    /** Cap the pool at MAX_BG_VOICES SOUNDING voices (trigger.js
     *  capBackgroundVoices): the evicted voice is ramped out, not dropped; a
     *  hard drop only past twice the cap. */
    static void capBackgroundVoices(Engine e) {
        int live = 0;
        for (int i = 0; i < e.bgCount; i++) if (!bgLeaving(e.bg[i])) live++;
        while (live > MAX_BG_VOICES) {
            int idx = -1;
            for (int i = 0; i < e.bgCount; i++) {
                Voice v = e.bg[i];
                if (!bgLeaving(v) && !v.isLayerChild && !v.fmOperator) { idx = i; break; }
            }
            if (idx < 0) for (int i = 0; i < e.bgCount; i++) if (!bgLeaving(e.bg[i]) && !e.bg[i].fmOperator) { idx = i; break; }
            if (idx < 0) for (int i = 0; i < e.bgCount; i++) if (!bgLeaving(e.bg[i])) { idx = i; break; }
            Voice culled = e.bg[idx];
            if (culled.fmOperator) {
                dropBackgroundVoice(e, idx);
                live--;
                continue;
            }
            Sampler.startCutRamp(e, culled);
            live--;
            if (culled.fmRig != null) {
                for (int i = 0; i < e.bgCount; i++) {
                    Voice bg = e.bg[i];
                    if (bg.fmOperator && bg.fmParent == culled && bg.active) live--;
                }
            }
        }
        while (e.bgCount > 2 * MAX_BG_VOICES) {
            int idx = -1;
            for (int i = 0; i < e.bgCount; i++) if (!e.bg[i].isLayerChild && !e.bg[i].fmOperator) { idx = i; break; }
            if (idx < 0) for (int i = 0; i < e.bgCount; i++) if (!e.bg[i].fmOperator) { idx = i; break; }
            if (idx < 0) idx = 0;
            dropBackgroundVoice(e, idx);
        }
    }

    private static void releaseLayerChildren(Engine e, int vi) {
        int override = e.voices[vi].nnaOverride;
        for (int i = 0; i < e.bgCount; i++) {
            Voice bg = e.bg[i];
            if (!bg.isLayerChild || bg.sourceChannel != vi) continue;
            if (bg.fmOperator) continue;
            bg.isLayerChild = false;
            bg.layerPitchMod = 0;
            int nna = override >= 0 ? override : e.instruments[bg.instrumentId].newNoteAction();
            switch (nna) {
            case 0:
            case 4:
                if (!bg.keyOff) { bg.keyOff = true; Envelope.applyKeyLift(bg, e.instruments[bg.instrumentId]); }
                break;
            case 1: Sampler.startCutRamp(e, bg); break; /* ramped out, as layer 0's ghost is */
            case 3: bg.noteFading = true; break;
            default: break;
            }
        }
    }

    static void cutLayerChildren(Engine e, int vi) {
        for (int i = 0; i < e.bgCount; i++) {
            if (Spatial.isSoundingChild(e, e.bg[i], vi)) Sampler.startCutRamp(e, e.bg[i]);
        }
    }

    private static double pitchOf(Meta.Layer l, double noteVal) { return l.pitch(noteVal); }

    private static void seedChildLaneContext(Voice child, Voice voice, int chanPan, int chanRowPan,
                                             int chanPanbrello, double chanAzimuth, double chanElevation) {
        child.channelVolume = voice.channelVolume;
        child.channelVolumeSet = voice.channelVolumeSet;
        child.channelPanSet = voice.channelPanSet;
        child.channelPan = chanPan;
        child.rowPan = chanRowPan;
        child.panbrelloOffset = chanPanbrello;
        child.panAzimuth = chanAzimuth;
        child.panElevation = chanElevation;
        child.clipMode = voice.clipMode;
        child.bitcrusherDepth = voice.bitcrusherDepth;
        child.bitcrusherSkip = voice.bitcrusherSkip;
        child.overdriveAmp = voice.overdriveAmp;
    }

    private static boolean fmSounds(Engine e, Meta.Layer o, double noteVal, int seedVol) {
        /* An operator is an ordinary instrument or a low meta (an OPL channel
         * or a PSG pair, read as the chip reads its carriers). A high meta is
         * not: a formant instrument, a layer table or another rack is a sound
         * made OUT of sources, and taking one would be recursion (§7.4). */
        return o.instIdx >= 1 && o.instIdx <= 1023 &&
            !e.instruments[o.instIdx].isMeta() && !e.instruments[o.instIdx].isHighMeta() &&
            PitchExt.pitchInRange(noteVal, o.pitchStart, o.dLo, o.pitchEnd, o.dHi) &&
            seedVol >= o.volStart && seedVol <= o.volEnd;
    }

    /* The syllable a trigger of a LYRIC INSTRUMENT sings on the lane whose
     * foreground voice is `voice` (spec §5.5.3, "Lyric instruments"), or null
     * for any other instrument. An instrument byte starts the lyrics over;
     * every trigger sings the syllable at the lane's position and moves it
     * on, back to the first after the last.
     *
     * A tone portamento (G, L) that triggers only because nothing is
     * sounding on the lane — a play started after the note it glides, or a
     * note that had run out — is still that note's glide (`glide`): it says
     * the syllable the lane's note was singing again and leaves the position
     * where it is, as a glide from a sounding note does. An instrument byte
     * still starts the lyrics over, handing the note its first syllable when
     * it was singing none of this instrument's (the porta row's instrument
     * change, Row.applyInstrumentChange). */
    private static FormantPatch lyricTrigger(Voice voice, Inst inst, int instId, boolean glide) {
        if (inst.lyric == null) return null;
        int n = inst.lyricEntry.numSyllables;
        if (glide) {
            FormantPatch cur = voice.formant != null ? voice.formant.patch : null;
            int k = -1;
            /* JS inst.lyric.indexOf(cur): the syllable patches are this array. */
            if (cur != null) for (int i = 0; i < n; i++) if (inst.lyric[i] == cur) { k = i; break; }
            if (instId != 0) {
                voice.lyricPos = 0;
                return k >= 0 ? cur : inst.lyric[0];
            }
            if (k >= 0) return cur;
        }
        if (instId != 0 || voice.lyricPos >= n) voice.lyricPos = 0;
        FormantPatch syl = inst.lyric[voice.lyricPos];
        voice.lyricPos = voice.lyricPos + 1 >= n ? 0 : voice.lyricPos + 1;
        return syl;
    }

    /** glide: a G/L on a silent lane, triggering as its note's glide (lyrics). */
    static void triggerMetaOrNote(Engine e, Voice voice, int vi, double noteVal, int instId, int rowVolOverride,
                                  boolean glide) {
        Meta.Layer[] layers = new Meta.Layer[META_MAX_LAYERS];
        NotePanSeed seed = new NotePanSeed();

        if (instId != 0) voice.displayInst = instId;
        /* A note on an instrument with a mixing chain (item 217): its bus
         * restarts the LFOs that retrigger on a note, at the top of the span
         * this lands in. */
        e.imixTrig[(voice.displayInst != 0 ? voice.displayInst : voice.instrumentId) & 0x3ff] = true;
        releaseLayerChildren(e, vi);
        Fm.dropFmOperators(e, vi);
        Inst inst = instId != 0 ? e.instruments[instId] : e.instruments[voice.instrumentId];
        if (!inst.isMeta()) {
            triggerNote(e, voice, noteVal, instId, rowVolOverride, lyricTrigger(voice, inst, instId, glide));
            voice.layerMixGain = 1.0;
            voice.layerRelDetune = 0;
            voice.layerFixedNote = -1;
            voice.layerRelPan = 0;
            voice.layerRelElevation = 0;
            voice.isLayerChild = false;
            return;
        }
        int gateVol = e.tsWideCells ? rowVolOverride >> 2 : rowVolOverride;
        int seedVol = gateVol >= 0 && gateVol <= 0x3f ? gateVol : 0x3f;
        if (inst.isFm()) {
            triggerFmRack(e, voice, vi, noteVal, inst, rowVolOverride, seedVol);
            return;
        }
        /* A table of a kind this engine does not know sounds nothing (§7.4):
         * its entries have the Layered shape, but what they mean is the kind's. */
        if (inst.meta.type != META_TYPE_LAYERED) {
            voice.active = false;
            voice.layerMixGain = 1.0;
            voice.layerRelDetune = 0;
            voice.layerFixedNote = -1;
            return;
        }
        int nLayers = Inst.resolveMetaLayers(inst, noteVal, seedVol, layers);
        if (inst.meta.strict) {
            int n = 0;
            for (int k = 0; k < nLayers; k++) {
                Meta.Layer l = layers[k];
                if (Inst.resolvePatch(e.instruments[l.instIdx], pitchOf(l, noteVal), seedVol) != null) layers[n++] = l;
            }
            nLayers = n;
        }
        if (nLayers == 0) {
            voice.active = false;
            voice.layerMixGain = 1.0;
            voice.layerRelDetune = 0;
            voice.layerFixedNote = -1;
            return;
        }
        Meta.Layer l0 = layers[0];
        int chanPan = voice.channelPan, chanRowPan = voice.rowPan;
        int chanPanbrello = voice.panbrelloOffset;
        double chanAzimuth = voice.panAzimuth, chanElevation = voice.panElevation;
        triggerNoteSeed(e, voice, pitchOf(l0, noteVal), l0.instIdx, rowVolOverride, seed, null);
        boolean l0HasPan = seed.present;
        double l0Pan = l0HasPan ? seed.pan : 0;
        double l0Elevation = l0HasPan ? seed.elevation : 0;
        voice.layerMixGain = Tables.META_MIX_GAIN[l0.mixOctet & 0xff];
        voice.layerRelDetune = 0;
        voice.layerFixedNote = l0.fixedPitch ? pitchOf(l0, noteVal) : -1;
        voice.pitchExcess = l0.excess(noteVal);
        voice.layerRelPan = 0;
        voice.layerRelElevation = 0;
        voice.isLayerChild = false;
        voice.metaForeground = true;
        voice.metaForegroundDetune = l0.detune + l0.dDetune;
        for (int k = 1; k < nLayers; k++) {
            Meta.Layer lk = layers[k];
            Voice child = new Voice();
            seedChildLaneContext(child, voice, chanPan, chanRowPan, chanPanbrello, chanAzimuth, chanElevation);
            triggerNoteSeed(e, child, pitchOf(lk, noteVal), lk.instIdx, rowVolOverride, seed, null);
            child.isLayerChild = true;
            child.sourceChannel = vi;
            child.displayInst = voice.displayInst;
            child.layerRelDetune = lk.note(noteVal) - l0.note(noteVal);
            child.layerFixedNote = lk.fixedPitch ? pitchOf(lk, noteVal) : -1;
            child.pitchExcess = lk.excess(noteVal);
            if (seed.present) {
                child.layerRelPan = seed.pan - l0Pan;
                child.layerRelElevation = seed.elevation - l0Elevation;
            } else {
                child.layerRelPan = 0;
                child.layerRelElevation = 0;
            }
            child.notePan = Spatial.boundNotePan(e, voice.notePan + child.layerRelPan);
            child.noteElevation = voice.noteElevation + child.layerRelElevation;
            child.layerMixGain = Tables.META_MIX_GAIN[lk.mixOctet & 0xff];
            e.bgPush(child);
        }
        capBackgroundVoices(e);
    }

    private static void triggerFmRack(Engine e, Voice voice, int vi, double noteVal, Inst inst,
                                      int rowVolOverride, int seedVol) {
        Meta meta = inst.meta;
        Meta.Layer[] ops = meta.layers;
        boolean[] referenced = new boolean[FM_MAX_OPERATORS];

        if (!meta.hasProgram || !fmSounds(e, ops[0], noteVal, seedVol)) {
            voice.active = false;
            voice.fmRig = null;
            voice.layerMixGain = 1.0;
            voice.layerRelDetune = 0;
            voice.layerFixedNote = -1;
            return;
        }
        Fm.referencedOperators(meta.program, meta.programLen, meta.count, referenced);

        int chanPan = voice.channelPan, chanRowPan = voice.rowPan;
        int chanPanbrello = voice.panbrelloOffset;
        double chanAzimuth = voice.panAzimuth, chanElevation = voice.panElevation;
        /* What each operator SOUNDS: the trigger at its ratio, or a FIXED-
         * frequency operator's own note (item 278, the DX7's FIXED mode). */
        triggerNote(e, voice, pitchOf(ops[0], noteVal), ops[0].instIdx, rowVolOverride, null);
        voice.layerMixGain = 1.0;
        voice.layerRelDetune = 0;
        voice.layerFixedNote = ops[0].fixedPitch ? pitchOf(ops[0], noteVal) : -1;
        voice.pitchExcess = ops[0].excess(noteVal);
        voice.layerRelPan = 0;
        voice.layerRelElevation = 0;
        voice.isLayerChild = false;
        voice.metaForeground = true;
        voice.metaForegroundDetune = ops[0].detune + ops[0].dDetune;

        /* The rig is built after the trigger (which drops any previous one);
         * the JS builds it before, but nothing in between reads it. */
        FmRig rig = voice.rigStore();
        Fm.rigInit(rig, meta.count);
        rig.program = meta.program;
        rig.programLen = meta.programLen;
        Fm.seedGains(rig, meta);
        voice.fmRig = rig;
        rig.voices[0] = voice;

        for (int k = 1; k < meta.count; k++) {
            if (!referenced[k] || !fmSounds(e, ops[k], noteVal, seedVol)) continue;
            Voice op = new Voice();
            seedChildLaneContext(op, voice, chanPan, chanRowPan, chanPanbrello, chanAzimuth, chanElevation);
            /* Its filter runs at the rack's rate (Fm): said before the
             * trigger makes the first coefficients. */
            op.filterOversample = e.fmOversample;
            triggerNote(e, op, pitchOf(ops[k], noteVal), ops[k].instIdx, rowVolOverride, null);
            op.isLayerChild = true;
            op.fmOperator = true;
            op.fmParent = voice;
            op.sourceChannel = vi;
            op.displayInst = voice.displayInst;
            op.layerRelDetune = ops[k].note(noteVal) - ops[0].note(noteVal);
            op.layerFixedNote = ops[k].fixedPitch ? pitchOf(ops[k], noteVal) : -1;
            op.pitchExcess = ops[k].excess(noteVal);
            op.layerMixGain = 1.0;
            op.layerRelPan = 0;
            op.layerRelElevation = 0;
            op.notePan = voice.notePan;
            op.noteElevation = voice.noteElevation;
            rig.voices[k] = op;
            e.bgPush(op);
        }
        capBackgroundVoices(e);
    }

    static int narrowVolAxis(Engine e, int v) {
        return iclamp(e.tsWideCells ? v >> 2 : v, 0, 0x3f);
    }

    static void triggerNote(Engine e, Voice voice, double noteVal, int instId, int volOverride, FormantPatch lyricPatch) {
        triggerNoteSeed(e, voice, noteVal, instId, volOverride, new NotePanSeed(), lyricPatch);
    }

    private static void triggerNoteSeed(Engine e, Voice voice, double noteVal, int instId, int volOverride,
                                        NotePanSeed seed, FormantPatch lyricPatch) {
        boolean mayCarry = voice.active && !voice.keyOff && !voice.noteFading &&
            (instId == 0 || instId == voice.instrumentId);
        /* An OPL channel handed over by maybeSpawnBackgroundForNna (a
         * retrigger the New Note Action cuts) keeps its envelopes, as the
         * chip does — read before anything below marks the voice active. */
        boolean oplCarry = voice.oplCarry && voice.active && voice.opl != null;
        /* …and a PSG pair handed over the same way keeps its envelopes too. */
        boolean psgCarry = voice.psgCarry && voice.active && voice.psg != null;
        int seedVolForLookup;

        voice.oplCarry = false;
        voice.psgCarry = false;
        voice.pitchExcess = 0.0;   /* a layer's or an operator's is set by its trigger, after this */

        if (instId != 0) voice.instrumentId = instId;
        Inst inst = e.instruments[voice.instrumentId];
        if (volOverride >= 0) seedVolForLookup = narrowVolAxis(e, volOverride);
        else if (instId != 0) seedVolForLookup = rowVolumeFromDefault(inst, null, 0x3f);
        else seedVolForLookup = narrowVolAxis(e, voice.noteVolume);
        Patch patch = Inst.resolvePatch(inst, noteVal, seedVolForLookup);
        applyActiveSample(voice, inst, patch);
        /* A formant instrument sung from a chip instrument (§7.6) takes the
         * chip's C2SPD where its sample's rate would be: the pitch is the chip's. */
        Inst formantChip = inst.formant != null ? Formant.chipSource(e, inst) : null;
        if (formantChip != null) voice.activeSamplingRate = Formant.chipRate(formantChip);
        voice.tonePortaTarget = -1;
        voice.samplePos = voice.activeSamplePlayStart;
        voice.forward = true;
        voice.active = true;
        voice.keyOff = false;
        if (!(mayCarry && Envelope.envCarry(voice.activeVolEnvLoop))) {
            voice.envIndex = 0;
            voice.envTimeSec = 0.0;
            voice.envVolume = dclamp(voice.activeVolEnv[0].value / (double) voice.activeVolEnvMax, 0.0, 1.0);
            voice.envVolMix = voice.envVolume;
            voice.envVolStep = 0.0;
        }
        voice.hasPanEnv = Envelope.envPresent(voice.activePanEnvLoop);
        if (!(mayCarry && Envelope.envCarry(voice.activePanEnvLoop))) {
            voice.envPanIndex = 0;
            voice.envPanTimeSec = 0.0;
            voice.envPan = voice.activePanEnv[0].value / 255.0;
        }
        Envelope.PfBox box = e.pfBox;
        if (!voice.hasPitchEnv) {
            voice.envPitchValue = 0.5; voice.envPitchIndex = 0; voice.envPitchTimeSec = 0.0;
        } else if (!(mayCarry && Envelope.envCarry(voice.activePitchEnvLoop))) {
            voice.envPitchValue = Envelope.seedPfRole(voice.activePitchEnv, voice.activePitchEnvLoop,
                voice.activePitchEnvSustain, box);
            voice.envPitchIndex = box.idx;
            voice.envPitchTimeSec = box.time;
        }
        if (!voice.hasFilterEnv) {
            voice.envFilterValue = 0.5; voice.envFilterIndex = 0; voice.envFilterTimeSec = 0.0;
        } else if (!(mayCarry && Envelope.envCarry(voice.activeFilterEnvLoop))) {
            voice.envFilterValue = Envelope.seedPfRole(voice.activeFilterEnv, voice.activeFilterEnvLoop,
                voice.activeFilterEnvSustain, box);
            voice.envFilterIndex = box.idx;
            voice.envFilterTimeSec = box.time;
        }
        voice.fadeoutVolume = 1.0;
        voice.fadeoutMix = 1.0;
        voice.fadeoutMixStep = 0.0;
        voice.rampOutSamples = 0;
        voice.rampOutGain = 0.0;
        /* Not on an OPL or PSG voice: its attack IS the chip's envelope. */
        voice.attackRampSamples = inst.isChip() ? 0 : e.attackRampSamples;
        voice.autoVibPhase = 0;
        voice.autoVibTicksSinceTrigger = 0;
        voice.nesDpcmCounter = 63;
        voice.right.clear();
        voice.right.nesDpcmCounter = 63;
        voice.invertWritePos = 0;
        voice.funkWalk = -1;
        voice.funkPos = -1;
        voice.funkWindow = -1;
        voice.funkXfade = 0;
        voice.modFunkWindow = -1;
        voice.modFunkXfade = 0;
        voice.randomVolBias = inst.volumeSwing != 0
            ? (int) (e.random() * (2 * inst.volumeSwing + 1)) - inst.volumeSwing : 0;
        voice.randomPanBias = inst.panSwing != 0
            ? (int) (e.random() * (2 * inst.panSwing + 1)) - inst.panSwing : 0;

        seed.present = false;
        seed.pan = 0;
        seed.elevation = 0;
        if (instId != 0) {
            if (patch != null && patch.defaultPan != 0xff) {
                Spatial.applyNotePanSet(e, voice, patch.defaultPan);
                seed.present = true;
            } else if (((voice.activePanEnvLoop >> 7) & 1) != 0) {
                seed.present = true;
                if (e.tsSurroundModel == SURROUND_STEREO) {
                    Spatial.applyNotePanSet(e, voice, inst.defaultPan);
                } else {
                    Spatial.applyNotePanSet(e, voice, inst.defaultAzimuth());
                    Spatial.applyNoteElevation(e, voice, inst.defaultElevation());
                }
            }
            if (inst.pitchPanSeparation != 0) {
                double noteDelta = (noteVal - (inst.pitchPanCentre + inst.dPitchPanCentre)) / 4096.0;
                double panShift = trunc(noteDelta * inst.pitchPanSeparation * 4.0);
                Spatial.applyNotePanSlide(e, voice, panShift);
                seed.present = true;
            }
            seed.pan = voice.notePan;
            seed.elevation = voice.noteElevation;
        }
        voice.currentCutoff = voice.activeDefaultCutoff;
        voice.currentResonance = voice.activeDefaultResonance;
        voice.filterY1 = 0.0; voice.filterY2 = 0.0; voice.filterX1 = 0.0; voice.filterX2 = 0.0;
        voice.filterCutoffCached = -1;
        voice.filterResonanceCached = -1;
        voice.sfCents = Double.NaN; voice.sfCentsLeft = 0; voice.sfQLeft = 0; /* a new note's filter lands at once */
        voice.noteVal = noteVal;
        voice.basePitch = noteVal;
        voice.renderPitch = noteVal;
        voice.amigaPeriod = -1.0;
        voice.linearFreq = -1.0;
        voice.playbackRate = Sampler.computePlaybackRate(e, voice, noteVal, e.tuningRatio);
        if (volOverride >= 0) voice.noteVolume = iclamp(volOverride, 0, e.volMax);
        else if (instId != 0) voice.noteVolume = rowVolumeFromDefault(inst, patch, e.volMax);
        voice.rowVolume = voice.noteVolume;
        voice.snapMixVolume = true;
        voice.volRampSamples = 0;
        voice.volRampStep = 0.0;
        voice.snapPlaybackRate = true;
        voice.snapPan = true;
        /* …and at its lane's fader and elevation: a fresh note has no
         * waveform for a jump to click in. */
        voice.elevCur = Double.NaN;
        voice.faderCur = Double.NaN;
        voice.noteWasCut = false;
        voice.noteFading = false;
        voice.nnaOverride = -1;
        voice.volEnvOn = true;
        voice.panEnvOn = true;
        voice.pitchEnvOn = true;
        voice.filterEnvOn = true;
        voice.metaForeground = false;
        voice.metaForegroundDetune = 0;
        voice.fmRig = null;
        /* The OPL channel (item 209): keyed on here, carried or fresh. */
        if (inst.opl != null) {
            if (voice.opl == null) { voice.opl = voice.oplStore(); voice.opl.init(); }
            Opl.keyOn(voice.opl, inst.opl, oplCarry);
        } else {
            voice.opl = null;
        }
        /* The PSG pair (§7.9): keyed on here, carried or fresh, the voice's
         * own state reused — which keeps an oscillator's internal status from
         * note to note on the lane, unless its X flag resets it at this key-on. */
        if (inst.psg != null) {
            if (voice.psg == null) { voice.psg = voice.psgStore(); voice.psg.init(); }
            Psg.keyOn(voice.psg, inst.psg, psgCarry);
        } else {
            voice.psg = null;
        }
        /* A formant instrument (item 212): every trigger is a fresh syllable. */
        if (inst.formant != null) {
            if (voice.formant == null) { voice.formant = voice.formantStore(); voice.formant.init(); }
            /* A lyric instrument's note sings the syllable its trigger chose. */
            Formant.noteOn(e, voice.formant, lyricPatch != null ? lyricPatch : inst.formant, false);
            Formant.keySource(voice.formant, formantChip);
        } else {
            voice.formant = null;
        }
        if (voice.vibratoRetrig) voice.vibratoLfoPos = 0;
        if (voice.tremoloRetrig) voice.tremoloLfoPos = 0;
        if (voice.panbrelloRetrig) voice.panbrelloLfoPos = 0;
    }

    private static final class DupKey { int newInstId; double newNote; long newSmpPtr; int newSmpLen; }

    /** What a trigger of (instId, note) sounds on the foreground voice: only
     *  an FM rack shifts it, to operator 0's instrument and detune. Written
     *  into k's newInstId and newNote. */
    private static void principalOf(Engine e, int instId, double note, DupKey k) {
        Inst inst = e.instruments[instId];
        k.newInstId = instId;
        k.newNote = note;
        if (!inst.isFm() || inst.meta.count == 0) return;
        Meta.Layer op0 = inst.meta.layers[0];
        if (op0.instIdx < 1 || op0.instIdx > 1023) return;
        k.newInstId = op0.instIdx;
        k.newNote = op0.pitch(note);
    }

    private static boolean isDuplicate(Engine e, Voice v, DupKey k, boolean bare) {
        Inst existInst = e.instruments[v.instrumentId];
        Inst newInst = e.instruments[k.newInstId];
        /* A voice already ramping out is already gone; a formant voice
         * already into its coda is no duplicate: what the check ends is the
         * VOWEL. */
        if (v.rampOutSamples > 0) return false;
        if (v.formant != null && v.formant.phase >= PHASE_CODA) return false;
        if (bare && existInst.dupCheckType() != DCT_LANE) return false;
        switch (existInst.dupCheckType()) {
        case 1: return v.noteVal == k.newNote && v.instrumentId == k.newInstId;
        case 2:
            /* Formant instruments' "sample" is the pool range they are made from. */
            if (v.formant != null && existInst.formant != null && newInst.formant != null) {
                return existInst.formant.rangeLo == newInst.formant.rangeLo &&
                       existInst.formant.rangeHi == newInst.formant.rangeHi;
            }
            return v.instrumentId == k.newInstId && v.activeSamplePtr == k.newSmpPtr &&
                   v.activeSampleLength == k.newSmpLen;
        case 3: return v.instrumentId == k.newInstId;
        case DCT_LANE: return true;
        default: return false;
        }
    }

    private static void applyDupAction(Engine e, Voice v, boolean isFg, int channel) {
        Inst existInst = e.instruments[v.instrumentId];
        switch (existInst.dupCheckAction()) {
        case 0: {
            /* Ramped out over the incoming note's attack — the foreground
             * voice through a ghost, since the note is about to reuse it — as
             * a New Note Action's cut is. */
            Voice r = isFg ? ghostRig(e, v, channel) : v;
            if (r != null) Sampler.startCutRamp(e, r);
            if (isFg) { v.active = false; capBackgroundVoices(e); }
            break;
        }
        case 1: v.keyOff = true; Envelope.applyKeyLift(v, existInst); break;
        case 2: v.noteFading = true; break;
        default: break;
        }
    }

    static void applyDuplicateCheck(Engine e, int channel, int instId, double note) {
        DupKey k = new DupKey();
        /* Type 4, Lane (item 216): a note with no instrument byte is checked
         * too, against the lane's instrument; types 1-3 ask only about a
         * named one. */
        boolean bare = instId == 0;
        if (bare) instId = e.voices[channel].instrumentId;
        if (instId == 0) return;
        principalOf(e, instId, note, k);
        Inst newInst = e.instruments[k.newInstId];
        Patch newPatch = Inst.resolvePatch(newInst, k.newNote, 0x3f);
        k.newSmpPtr = newPatch != null ? newPatch.samplePtr : newInst.samplePtr;
        k.newSmpLen = newPatch != null ? newPatch.sampleLength : newInst.sampleLength;

        Voice fg = e.voices[channel];
        if (fg.active && e.instruments[fg.instrumentId].dupCheckType() != 0 && isDuplicate(e, fg, k, bare)) {
            applyDupAction(e, fg, true, channel);
        }
        for (int i = e.bgCount - 1; i >= 0; i--) {
            Voice bg = e.bg[i];
            if (bg.sourceChannel != channel || !bg.active) continue;
            if (bg.fmOperator) continue;
            if (e.instruments[bg.instrumentId].dupCheckType() == 0) continue;
            if (!isDuplicate(e, bg, k, bare)) continue;
            applyDupAction(e, bg, false, channel);
            if (!bg.active) e.bgRemoveAt(i);
        }
    }

    /** Snapshot the playback-relevant state of src into a fresh background voice. */
    private static Voice ghostVoice(Engine e, Voice src, int channel) {
        Voice v = new Voice();
        v.active = true;
        v.fader = src.fader;
        v.faderCur = src.faderCur; v.faderAim = src.faderAim;
        v.faderStep = src.faderStep; v.faderLeft = src.faderLeft;
        v.elevCur = src.elevCur; v.elevAim = src.elevAim;
        v.elevStep = src.elevStep; v.elevLeft = src.elevLeft;
        v.instrumentId = src.instrumentId;
        v.displayInst = src.displayInst;
        v.samplePos = src.samplePos;
        v.playbackRate = src.playbackRate;
        v.currentPlaybackRate = src.currentPlaybackRate;
        v.currentPan = src.currentPan;
        v.forward = src.forward;
        v.noteVolume = src.noteVolume;
        v.channelVolume = src.channelVolume;
        v.channelVolumeSet = src.channelVolumeSet;
        v.channelPanSet = src.channelPanSet;
        v.rowVolume = src.rowVolume;
        v.channelPan = src.channelPan;
        v.rowPan = src.rowPan;
        v.panAzimuth = src.panAzimuth;
        v.panElevation = src.panElevation;
        v.notePan = src.notePan;
        v.noteElevation = src.noteElevation;
        v.spatialTargetAz = src.spatialTargetAz;
        v.spatialTargetEl = src.spatialTargetEl;
        v.currentMixVolume = src.currentMixVolume;
        v.attackRampSamples = src.attackRampSamples;
        v.keyOff = src.keyOff;
        v.envIndex = src.envIndex;
        v.envTimeSec = src.envTimeSec;
        v.envVolume = src.envVolume;
        v.envVolMix = src.envVolMix;
        v.envVolStep = src.envVolStep;
        v.envPanIndex = src.envPanIndex;
        v.envPanTimeSec = src.envPanTimeSec;
        v.envPan = src.envPan;
        v.hasVolEnv = src.hasVolEnv;
        v.hasPanEnv = src.hasPanEnv;
        v.hasPitchEnv = src.hasPitchEnv;
        v.envPitchIndex = src.envPitchIndex;
        v.envPitchTimeSec = src.envPitchTimeSec;
        v.envPitchValue = src.envPitchValue;
        v.hasFilterEnv = src.hasFilterEnv;
        v.envFilterIndex = src.envFilterIndex;
        v.envFilterTimeSec = src.envFilterTimeSec;
        v.envFilterValue = src.envFilterValue;
        v.fadeoutVolume = src.fadeoutVolume;
        v.fadeoutMix = src.fadeoutMix;
        v.fadeoutMixStep = src.fadeoutMixStep;
        v.autoVibPhase = src.autoVibPhase;
        v.autoVibTicksSinceTrigger = src.autoVibTicksSinceTrigger;
        v.currentCutoff = src.currentCutoff;
        v.currentResonance = src.currentResonance;
        v.filterSfMode = src.filterSfMode;
        v.filterActive = src.filterActive;
        v.filterA0 = src.filterA0;
        v.filterB0 = src.filterB0;
        v.filterB1 = src.filterB1;
        v.filterY1 = src.filterY1;
        v.filterY2 = src.filterY2;
        v.filterIsBiquad = src.filterIsBiquad;
        v.filterBqB02 = src.filterBqB02;
        v.filterBqB1 = src.filterBqB1;
        v.filterBqA1 = src.filterBqA1;
        v.filterBqA2 = src.filterBqA2;
        v.filterX1 = src.filterX1;
        v.filterX2 = src.filterX2;
        v.filterCutoffCached = src.filterCutoffCached;
        v.filterOversample = src.filterOversample;
        v.filterResonanceCached = src.filterResonanceCached;
        v.sfCents = src.sfCents; v.sfCentsIncr = src.sfCentsIncr; v.sfCentsLeft = src.sfCentsLeft;
        v.sfCentsAim = src.sfCentsAim; v.sfHzAim = src.sfHzAim; v.sfQ = src.sfQ;
        v.sfQIncr = src.sfQIncr; v.sfQLeft = src.sfQLeft; v.sfQAim = src.sfQAim;
        v.randomVolBias = src.randomVolBias;
        v.randomPanBias = src.randomPanBias;
        v.panbrelloOffset = src.panbrelloOffset;
        v.noteVal = src.noteVal;
        v.basePitch = src.basePitch;
        v.amigaPeriod = src.amigaPeriod;
        v.linearFreq = src.linearFreq;
        v.volEnvOn = src.volEnvOn;
        v.panEnvOn = src.panEnvOn;
        v.pitchEnvOn = src.pitchEnvOn;
        v.filterEnvOn = src.filterEnvOn;
        v.metaForeground = src.metaForeground;
        v.metaForegroundDetune = src.metaForegroundDetune;
        v.noteFading = src.noteFading;
        v.layerMixGain = src.layerMixGain;
        v.layerRelPan = src.layerRelPan;
        v.layerRelElevation = src.layerRelElevation;
        v.clipMode = src.clipMode;
        v.bitcrusherDepth = src.bitcrusherDepth;
        v.bitcrusherSkip = src.bitcrusherSkip;
        v.bitcrusherCounter = src.bitcrusherCounter;
        v.bitcrusherHeld = src.bitcrusherHeld;
        v.overdriveAmp = src.overdriveAmp;
        v.sourceChannel = channel;
        v.activeSamplePtr = src.activeSamplePtr;
        v.activeSampleLength = src.activeSampleLength;
        v.activeSamplePlayStart = src.activeSamplePlayStart;
        v.activeSampleLoopStart = src.activeSampleLoopStart;
        v.activeSampleLoopEnd = src.activeSampleLoopEnd;
        v.activeSamplingRate = src.activeSamplingRate;
        v.activeSampleDetune = src.activeSampleDetune;
        v.pitchExcess = src.pitchExcess;
        v.activeLoopMode = src.activeLoopMode;
        v.funkWindow = src.funkWindow;
        v.modFunkWindow = src.modFunkWindow;
        v.activeVibratoSpeed = src.activeVibratoSpeed;
        v.activeVibratoSweep = src.activeVibratoSweep;
        v.activeVibratoDepth = src.activeVibratoDepth;
        v.activeVibratoRate = src.activeVibratoRate;
        v.activeVibratoWaveform = src.activeVibratoWaveform;
        v.activePatchIndex = src.activePatchIndex;
        v.activeChanCount = src.activeChanCount;
        v.activeChanMode = src.activeChanMode;
        v.activeChanPtr2 = src.activeChanPtr2;
        v.right.copyFrom(src.right);
        v.activeVolEnv = src.activeVolEnv;
        v.activeVolEnvLoop = src.activeVolEnvLoop;
        v.activeVolEnvSustain = src.activeVolEnvSustain;
        v.activeVolEnvMax = src.activeVolEnvMax;
        v.activePanEnv = src.activePanEnv;
        v.activePanEnvLoop = src.activePanEnvLoop;
        v.activePanEnvSustain = src.activePanEnvSustain;
        v.activePitchEnv = src.activePitchEnv;
        v.activePitchEnvLoop = src.activePitchEnvLoop;
        v.activePitchEnvSustain = src.activePitchEnvSustain;
        v.activeFilterEnv = src.activeFilterEnv;
        v.activeFilterEnvLoop = src.activeFilterEnvLoop;
        v.activeFilterEnvSustain = src.activeFilterEnvSustain;
        v.activeFadeoutStep = src.activeFadeoutStep;
        v.activeDefaultCutoff = src.activeDefaultCutoff;
        v.activeDefaultResonance = src.activeDefaultResonance;
        v.activeAttenGain = src.activeAttenGain;
        /* An OPL channel is the voice's whole sound and state: the ghost takes
         * a copy, clock and envelopes and all. A formant ghost keeps saying
         * the syllable it was saying, into its coda once released. */
        if (src.opl != null) { v.oplStore().copyFrom(src.opl); v.opl = v.oplStore; }
        if (src.psg != null) { v.psgStore().copyFrom(src.psg); v.psg = v.psgStore; }
        if (src.formant != null) { v.formantStore().copyFrom(src.formant); v.formant = v.formantStore; }
        return v;
    }

    /** Ghost `voice` into the background pool — and, for an FM rack, its operands. */
    private static Voice ghostRig(Engine e, Voice voice, int channel) {
        Voice bg = ghostVoice(e, voice, channel);
        e.bgPush(bg);
        FmRig rig = voice.fmRig;
        if (rig == null) return bg;
        FmRig copy = bg.rigStore();
        Fm.rigClone(copy, rig);
        bg.fmRig = copy;
        copy.voices[0] = bg;
        for (int k = 1; k < rig.count; k++) {
            Voice src = rig.voices[k];
            if (src == null || !src.active) continue;
            Voice op = ghostVoice(e, src, channel);
            op.isLayerChild = true;
            op.fmOperator = true;
            op.fmParent = bg;
            op.layerRelDetune = src.layerRelDetune;
            op.layerFixedNote = src.layerFixedNote; /* a FIXED operand stays fixed (item 278) */
            op.layerPitchMod = 0;
            op.layerMixGain = src.layerMixGain;
            copy.voices[k] = op;
            e.bgPush(op);
        }
        return bg;
    }

    /** Apply New Note Action `nna` to a freshly spawned ghost. */
    private static void releaseGhost(Engine e, Voice bg, int nna) {
        if (nna == 1) {
            Sampler.startCutRamp(e, bg);
        } else if (nna == 0 || nna == 4) {
            bg.keyOff = true;
            Envelope.applyKeyLift(bg, e.instruments[bg.instrumentId]);
        } else if (nna == 3) {
            bg.noteFading = true;
        }
    }

    static void maybeSpawnBackgroundForNna(Engine e, Voice voice, int channel, int incomingInst) {
        if (!voice.active) return;
        int nna = voice.nnaOverride >= 0 ? voice.nnaOverride : e.instruments[voice.instrumentId].newNoteAction();
        if (nna == 1 && voice.opl != null && voice.fmRig == null &&
            e.instruments[incomingInst != 0 ? incomingInst : voice.instrumentId].isOpl()) {
            /* An OPL note cut by an OPL note is a retrigger of the same
             * channel (§5.5.2): no ghost, no ramp; triggerNote picks it up. */
            voice.oplCarry = true;
            return;
        }
        if (nna == 1 && voice.psg != null && voice.fmRig == null &&
            e.instruments[incomingInst != 0 ? incomingInst : voice.instrumentId].isPsg()) {
            /* A PSG note cut by a PSG note re-keys the same pair, as OPL does (§5.5.4). */
            voice.psgCarry = true;
            return;
        }
        Voice bg = ghostRig(e, voice, channel);
        if (bg != null) releaseGhost(e, bg, nna);
        capBackgroundVoices(e);
    }

    /** One voice of lane vi's note, for Q's retrigger: false = restart in place. */
    private static boolean handOffOne(Engine e, Voice v, int vi, int override) {
        Inst inst = e.instruments[v.instrumentId];
        if (!inst.retrigFollowsNna()) return false;
        int nna = override >= 0 ? override : inst.newNoteAction();
        /* An OPL or PSG note cut by a note of its own instrument is the chip
         * keying the same channel on again: no ghost — the plain restart. */
        if (nna == 1 && (v.opl != null || v.psg != null) && v.fmRig == null) return false;
        Voice bg = ghostRig(e, v, vi);
        if (bg != null) releaseGhost(e, bg, nna);
        v.retrigFresh = true;
        v.attackRampSamples = inst.isChip() ? 0 : e.attackRampSamples;
        return true;
    }

    /** Q's retrigger on lane vi, before the restart (spec §5.7, §6 step 13):
     *  every sounding voice of the note whose OWN instrument sets byte 186
     *  bit 6 hands its sound to the background pool per the New Note Action
     *  (the lane's S $73…$76 override first), then restarts as a fresh
     *  trigger does. Layer children first, the foreground last; FM operands
     *  go with their carrier. The pool is capped once, at the end, so the
     *  scan never sees it shrink. */
    static void retrigHandOff(Engine e, Voice voice, int vi) {
        int override = voice.nnaOverride;
        int n = e.bgCount;
        for (int i = 0; i < n; i++) {
            Voice bg = e.bg[i];
            if (bg.fmOperator || !bg.active || !Spatial.isSoundingChild(e, bg, vi)) continue;
            handOffOne(e, bg, vi, override);
        }
        if (voice.active && handOffOne(e, voice, vi, override) && voice.fmRig != null) {
            for (int i = 0; i < n; i++) {
                Voice op = e.bg[i];
                if (!op.fmOperator || !Spatial.isSoundingChild(e, op, vi)) continue;
                op.retrigFresh = true;
                op.attackRampSamples = e.instruments[op.instrumentId].isChip() ? 0 : e.attackRampSamples;
            }
        }
        if (e.bgCount > n) capBackgroundVoices(e);
    }

    static void applyPastNoteAction(Engine e, int channel, int action) {
        switch (action) {
        case 0:
            /* Ramped out, as every cut is; an FM operand ends with its carrier. */
            for (int i = 0; i < e.bgCount; i++) {
                Voice bg = e.bg[i];
                if (bg.sourceChannel == channel && !bg.fmOperator) Sampler.startCutRamp(e, bg);
            }
            break;
        case 1:
            for (int i = 0; i < e.bgCount; i++) {
                Voice bg = e.bg[i];
                if (bg.sourceChannel == channel) {
                    bg.keyOff = true;
                    Envelope.applyKeyLift(bg, e.instruments[bg.instrumentId]);
                }
            }
            break;
        case 2:
            for (int i = 0; i < e.bgCount; i++) {
                if (e.bg[i].sourceChannel == channel) e.bg[i].noteFading = true;
            }
            break;
        default:
            break;
        }
    }

    static void applyVolColumn(Engine e, Voice v, int value, int sel) {
        int dirBit = e.tsWideCells ? 0x80 : 0x20;
        switch (sel) {
        case 0:
            v.noteVolume = iclamp(value, 0, e.volMax);
            v.rowVolume = v.noteVolume;
            break;
        case 1: v.volColSlideUp = value; break;
        case 2: v.volColSlideDown = value; break;
        case 3: {
            if (value == 0) return;
            int mag = value & (dirBit - 1);
            v.noteVolume = (value & dirBit) != 0 ? imin(v.noteVolume + mag, e.volMax) : imax(v.noteVolume - mag, 0);
            v.rowVolume = v.noteVolume;
            break;
        }
        default: break;
        }
    }

    static void applyPanColumn(Engine e, Voice v, int value, int sel) {
        switch (sel) {
        case 0:
            Spatial.applyNotePanSet(e, v, (value << 2) | (value >> 4));
            break;
        case 1: v.panColSlideRight = value; break;
        case 2: v.panColSlideLeft = value; break;
        case 3: {
            if (value == 0) return;
            int mag = value & 0x1f;
            Spatial.applyNotePanSlide(e, v, (value & 0x20) != 0 ? mag : -mag);
            break;
        }
        default: break;
        }
    }

    private static boolean rowSlidesSpatially(Cell row) {
        return (row.effect == OP_Z && (row.effectArg & 0xfff) != 0) ||
               (row.effect2 == OP_Z && (row.effectArg2 & 0xfff) != 0);
    }

    static void applyPanColumnWide(Engine e, Voice v, Cell row) {
        switch (row.panEff) {
        case 0:
            if (rowSlidesSpatially(row)) {
                v.spatialTargetAz = row.azimuth;
                v.spatialTargetEl = e.tsSurroundModel == SURROUND_SPATIAL ? row.elevation : 0;
            } else {
                Spatial.applyNotePanSet(e, v, row.azimuth);
                Spatial.applyNoteElevation(e, v, row.elevation);
            }
            break;
        case 1: v.panColSlideRight = row.azimuth & 0xff; break;
        case 2: v.panColSlideLeft = row.azimuth & 0xff; break;
        case 3: {
            int mag = row.azimuth & 0xff;
            if (mag == 0) return;
            Spatial.applyNotePanSlide(e, v, (row.azimuth & 0x100) != 0 ? mag : -mag);
            break;
        }
        default: break;
        }
    }
}
