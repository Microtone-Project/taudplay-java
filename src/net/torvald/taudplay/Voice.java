/*
 * One sounding voice (voice.js; taudplay-c's TaudVoice). A fresh Voice is
 * exactly what `new Voice()` leaves in the JavaScript.
 *
 * The C port embeds the chip and tract states in the voice (oplStore,
 * psgStore, formantStore, rigStore) and points `opl`/`psg`/`formant`/`fmRig`
 * at them; here the stores are allocated the first time they are wanted and
 * kept, so the "kept across notes" semantics are the same.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class Voice {
    static final EnvPoint[] DEFAULT_ENV_3F = EnvPoint.make(0x3f);
    static final EnvPoint[] DEFAULT_ENV_80 = EnvPoint.make(0x80);

    static final class MemorySlots {
        int ef, g, huSpeed, huDepth, rSpeed, rDepth, ySpeed, yDepth;
        int d, i, j, jExt1, jExt2, o;
        double oExt;
        int q, tslide, w, k, l, n, p, z;
    }

    static final class ChannelState {
        double filterY1, filterY2, filterX1, filterX2;
        int bitcrusherCounter;
        double bitcrusherHeld;
        int nesDpcmCounter;

        void copyFrom(ChannelState o) {
            filterY1 = o.filterY1; filterY2 = o.filterY2; filterX1 = o.filterX1; filterX2 = o.filterX2;
            bitcrusherCounter = o.bitcrusherCounter; bitcrusherHeld = o.bitcrusherHeld; nesDpcmCounter = o.nesDpcmCounter;
        }

        void clear() {
            filterY1 = filterY2 = filterX1 = filterX2 = 0;
            bitcrusherCounter = 0; bitcrusherHeld = 0; nesDpcmCounter = 0;
        }
    }

    /** Renderer gains for a voice's (at most two) sample channels. */
    static final class SpatialCache {
        boolean valid;
        double az, el;
        int chans;
        int rendererGen;
        final double[] gains = new double[2 * 16];
    }

    boolean active;
    int fader;
    double samplePos;
    double playbackRate = 1.0, currentPlaybackRate = 1.0;
    int pitchRampSamples;
    double pitchRampStep;
    boolean snapPlaybackRate = true;
    boolean forward = true;
    int instrumentId;
    int displayInst;
    int sourceChannel = -1;
    int activePatchIndex = -1;

    boolean isLayerChild;
    int layerRelDetune;
    int layerFixedNote = -1;
    double layerRelPan, layerRelElevation;
    double layerMixGain = 1.0;
    int layerPitchMod;
    int nnaOverride = -1;
    boolean volEnvOn = true, panEnvOn = true, pitchEnvOn = true, filterEnvOn = true;
    boolean metaForeground;
    int metaForegroundDetune;
    boolean noteFading;

    FmRig fmRig;                /* null, or rigStore */
    FmRig rigStore;
    boolean fmOperator;
    Voice fmParent;

    /* OPL channel (item 209): null or oplStore, kept across notes. */
    OplState opl;
    OplState oplStore;
    boolean oplCarry;
    /* PSG pair (type 9): null or psgStore, kept across notes as OPL's is —
     * an oscillator's internal status lives here. */
    PsgState psg;
    PsgState psgStore;
    boolean psgCarry;
    /* Formant tract (item 212): null or formantStore. */
    FormantState formant;
    FormantState formantStore;
    /* A lyric instrument's place in its lyrics on this LANE: the syllable the
     * lane's next trigger of it sings. Read and written on the foreground
     * voice only. */
    int lyricPos;

    int noteVolume = 0x3f, channelVolume = 0x3f, rowVolume = 63;
    int channelPan = 0x80, rowPan = 32;
    boolean channelVolumeSet, channelPanSet;
    double notePan, noteElevation;

    double panAzimuth = 128.0, panElevation;
    double spatialTargetAz = 128.0, spatialTargetEl;
    boolean spatialSlideActive;
    final SpatialCache spatial = new SpatialCache();

    double currentMixVolume = 1.0;
    int volRampSamples;
    double volRampStep;
    double currentPan = 128.0;
    int panRampSamples;
    double panRampStep;
    boolean snapPan = true, snapMixVolume;

    boolean keyOff;
    int envIndex;
    double envTimeSec, envVolume = 1.0, envVolMix = 1.0, envVolStep;
    boolean hasVolEnv;          /* the ACTIVE volume envelope's P bit */
    int envPanIndex;
    double envPanTimeSec, envPan = 0.5;
    boolean hasPanEnv;
    boolean hasPitchEnv;
    int envPitchIndex;
    double envPitchTimeSec, envPitchValue = 0.5;
    boolean hasFilterEnv;
    int envFilterIndex;
    double envFilterTimeSec, envFilterValue = 0.5;

    double fadeoutVolume = 1.0;
    int rampOutSamples;
    double rampOutGain, rampOutStep;
    int attackRampSamples;
    int autoVibPhase;
    long autoVibTicksSinceTrigger;

    long activeSamplePtr;
    int activeSampleLength, activeSamplePlayStart, activeSampleLoopStart, activeSampleLoopEnd;
    int activeSamplingRate, activeSampleDetune, activeLoopMode;
    int activeVibratoSpeed, activeVibratoSweep, activeVibratoDepth, activeVibratoRate;
    int activeVibratoWaveform;
    int activeChanCount = 1, activeChanMode;
    long activeChanPtr2;
    final ChannelState right = new ChannelState();

    EnvPoint[] activeVolEnv = DEFAULT_ENV_3F;
    int activeVolEnvLoop, activeVolEnvSustain;
    EnvPoint[] activePanEnv = DEFAULT_ENV_80;
    int activePanEnvLoop, activePanEnvSustain;
    EnvPoint[] activePitchEnv = DEFAULT_ENV_80;
    int activePitchEnvLoop, activePitchEnvSustain;
    EnvPoint[] activeFilterEnv = DEFAULT_ENV_80;
    int activeFilterEnvLoop, activeFilterEnvSustain;
    int activeFadeoutStep, activeDefaultCutoff = 0xff, activeDefaultResonance = 0xff;
    boolean filterSfMode;
    double activeAttenGain = 1.0;

    int nesDpcmCounter = 63;

    int currentCutoff = 0xff, currentResonance = 0xff;
    boolean filterActive;
    double filterA0 = 1.0, filterB0, filterB1, filterY1, filterY2;
    boolean filterIsBiquad;
    double filterBqB02, filterBqB1, filterBqA1, filterBqA2, filterX1, filterX2;
    int filterCutoffCached = -1, filterResonanceCached = -1;

    int randomVolBias, randomPanBias;

    int noteVal, basePitch = 0x4000;
    double amigaPeriod = -1.0, linearFreq = -1.0;
    int renderPitch;
    int pitchModDelta;

    int rowEffect, rowEffectArg;
    int slideMode, slideArg;
    int tonePortaTarget = -1, tonePortaSpeed;
    int arpOff1, arpOff2;
    boolean arpActive;
    int lastArpVoice;
    int tremorOn, tremorOnTime = 1, tremorOffTime = 1;
    boolean tremorPhaseOn = true;
    int tremorTickInPhase;

    boolean vibratoActive;
    int vibratoLfoPos, vibratoWave;
    boolean vibratoRetrig = true;
    int vibratoFineShift = 6;
    boolean tremoloActive;
    int tremoloLfoPos, tremoloWave;
    boolean tremoloRetrig = true;
    boolean panbrelloActive;
    int panbrelloLfoPos, panbrelloWave;
    boolean panbrelloRetrig = true;
    int panbrelloOffset;
    boolean glissandoOn;

    int retrigCounter, retrigInterval, retrigVolMod;
    boolean retrigActive;
    /* Set by retrigHandOff on a voice Q just ghosted; read and cleared by the
     * restart, whose chip then keys on from silence. */
    boolean retrigFresh;

    int noteDelayTick = -1, delayedNote, delayedInst, delayedVol = -1;
    int delayedInterruptArg;
    int noteActionTick = -1, delayedAction = -1;
    int cutAtTick = -1;
    boolean noteWasCut;

    int invertSpeed, invertAccumulator, invertWritePos;

    int funkSpeed, funkMode, funkAccumulator;
    int funkWalk = -1, funkPos = -1, funkWindow = -1;
    int funkXfade, funkXfadeLen = 1, funkXfadeOffset;

    int modPeriod, modTickCount, modWritePos, modXfade;
    boolean modExtended;
    double modStepTicks, modSamplesIntoStep;
    final ModGeom modGeom = new ModGeom();
    int modFunkWindow = -1, modFunkXfade, modFunkXfadeLen = 1, modFunkXfadeOffset;

    int loopStartRow, loopCount;
    boolean dittoActive;
    int dittoSourceStart, dittoLength, dittoEndRow;

    int tempoSlideDir, tempoSlideAmount;
    int wSlideDir, wSlideAmount;
    int volColSlideUp, volColSlideDown;
    int panColSlideRight, panColSlideLeft, chanPanSlideRight, chanPanSlideLeft;
    int nSlideDir;

    int clipMode, bitcrusherDepth, bitcrusherSkip, bitcrusherCounter;
    double bitcrusherHeld;
    int overdriveAmp;

    final MemorySlots mem = new MemorySlots();

    double panLawPan = Double.NaN, panLawL, panLawR;

    Voice() {
        right.nesDpcmCounter = 63;
        modGeom.epoch = -1;
        modGeom.base = -1;
        modGeom.len = -1;
    }

    /* The stores, allocated on first use (the C embeds them). */
    OplState oplStore() { if (oplStore == null) oplStore = new OplState(); return oplStore; }
    PsgState psgStore() { if (psgStore == null) psgStore = new PsgState(); return psgStore; }
    FormantState formantStore() { if (formantStore == null) formantStore = new FormantState(); return formantStore; }
    FmRig rigStore() { if (rigStore == null) rigStore = new FmRig(); return rigStore; }
}
