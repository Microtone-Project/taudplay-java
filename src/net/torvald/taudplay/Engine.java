/*
 * The engine — the device facade (engine.js) plus the Playhead/TrackerState
 * parts of state.js that a player drives, and the rng.js seams (taudplay-c
 * engine.c).
 *
 * The C port pools its voices and reclaims them by marking what the lanes can
 * still reach — the objects the JavaScript's garbage collector keeps. Here the
 * garbage collector does that, exactly as in the JavaScript.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Engine {
    /* Rate-derived configuration (setSamplingRate). */
    final int rate;
    int rampOutSamples, volRampSamples, attackRampSamples, sfFilterGlideSamples;
    double a500B1, a500A0, ledA1, ledA2, ledB1, ledB2;

    /* The sample pool, BORROWED from the parsed document and trimmed to its
     * last non-zero byte: a read at or past samplePoolLen is 0, which is
     * exactly what the untrimmed 8 MB pool holds there. */
    byte[] sampleBin = new byte[0];
    int samplePoolLen;
    final Inst[] instruments = new Inst[NUM_INST_SLOTS];
    final Cell[][] playdata = new Cell[NUM_PATTERNS][];
    /** The blank pattern every blank slot shares: narrow, wide. */
    final Cell[][] sharedBlank = new Cell[2][];
    final Cell.Cue[][] cueBlocks = new Cell.Cue[CUE_BLOCKS][];
    final Cell[] emptyPattern = Cell.newPattern();
    final Cell.Cue emptyCue = new Cell.Cue();
    boolean is64ChannelMode;
    boolean wideCells;

    /* Playhead 0 — the only one a player drives. */
    int position;
    int masterVolume, masterPan = 128, bpm = 125, tickRate = 6, globalVolume = 0x80, mixingVolume = 0x80;
    int tuningBaseNote;
    double tuningFreq;
    int initialGlobalFlags;
    int surroundModel;
    int monitorMode;
    boolean isPlaying;

    /* TrackerState. */
    int cuePos, rowIndex, tickInRow;
    double samplesIntoTick;
    boolean firstRow = true;
    long oplFrame;              /* the OPL chip clock: frames mixed since reset */
    int psgQuarter;             /* the quarter of the tick the PSG auto-advance has reached */
    boolean hasPsg;             /* some instrument is a PSG one (else no voice can sound a pair) */
    final Voice[] voices = new Voice[MAX_VOICES];
    int toneMode, interpolationMode;
    boolean ledFilterOn;
    boolean tsWideCells;
    int volMax = VOLUME_MAX, volStep = 1;
    double volDiv = 63.0;
    int tsSurroundModel;
    SpatialBus spatial;         /* null for the stereo model */
    Binaural binaural;          /* kept across model switches */
    int rendererGen;
    final Mastering.Params masteringParams = new Mastering.Params();
    boolean hasMasteringParams = true;
    Mastering.Chain mastering;
    double tuningRatio = 1.0;
    double amigaLPStateL, amigaLPStateR;
    final double[] amigaLEDStateL = new double[4], amigaLEDStateR = new double[4];
    int pendingOrderJump = -1, pendingRowJump = -1;
    boolean pendingRowJumpLocal;
    int patternDelayRemaining;
    boolean patternDelayActive;
    int sexWinningChannel = -1;
    int finePatternDelayExtra;
    int pendingInterrupts;
    final int[] interruptArgs = new int[NUM_INTERRUPTS];
    final float[] mixLeft = new float[TRACKER_CHUNK], mixRight = new float[TRACKER_CHUNK];

    Voice[] bg = new Voice[128];
    int bgCount;

    /* Dither (pcm32fToPcm8). */
    int xorshift = TE_XORSHIFT_SEED;
    final float[] ditherError = new float[4];

    /* Randomness seam (rng.js). */
    java.util.function.DoubleSupplier random;
    int mulberry = 1;

    /* Tables. */
    final double[] sincTable = new double[SINC_TABLE_SIZE];
    final Formant.FTables formantTables = new Formant.FTables();

    /* Instrument mixing chains (item 217): the project's blocks by id
     * (borrowed from the parsed document), and the playhead's route table —
     * rebuilt whenever imixGen moves or the render target changes. */
    final Imix.Block[] imixBlocks = new Imix.Block[256];
    int imixGen, imixSyncedGen;
    Object imixRenderer;
    final Imix.Bus[] imixRoutes = new Imix.Bus[IMX_ROUTE_SLOTS];
    Imix.Bus[] imixList = new Imix.Bus[8];      /* ascending by slot */
    int imixCount;
    final boolean[] imixTrig = new boolean[IMX_ROUTE_SLOTS];  /* a note started on the slot since its bus last ran */
    /* The frame accumulators the buses join (mixAccL/R): a span's frames are
     * finished only once every bus has run over it. */
    final double[] accL = new double[TRACKER_CHUNK], accR = new double[TRACKER_CHUNK];
    /* The song's global, mixing and master volumes as the mixer glides them
     * (Sampler.ByteGlide), and each frame's combined gain, which the buses read. */
    final Sampler.ByteGlide gvolGlide = new Sampler.ByteGlide();
    final Sampler.ByteGlide mvolGlide = new Sampler.ByteGlide();
    final Sampler.ByteGlide masterGlide = new Sampler.ByteGlide();
    final double[] gainSpan = new double[TRACKER_CHUNK];

    /* Scratch (the C's stack locals and out-parameters). */
    final Envelope.PfBox pfBox = new Envelope.PfBox();
    final double[] fracBox = new double[1];
    final int[] scaleBox = new int[2];
    final double[] angBox = new double[2];
    final double[] pairBox = new double[2];
    final double[] voicePairBox = new double[2];

    /* ── randomness (rng.js) ── */

    private double mulberry32() {
        mulberry += 0x6d2b79f5;
        int t = mulberry;
        t = (t ^ (t >>> 15)) * (t | 1);
        t ^= t + (t ^ (t >>> 7)) * (t | 61);
        return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
    }

    double random() {
        if (random != null) return random.getAsDouble();
        return mulberry32();
    }

    /* ── the pool and the voices ── */

    int poolAt(long p) {
        return p >= 0 && p < samplePoolLen ? sampleBin[(int) p] & 0xff : 0;
    }

    int channelCount() { return is64ChannelMode ? MAX_VOICES : NUM_VOICES; }

    void bgPush(Voice v) {
        if (bgCount == bg.length) bg = java.util.Arrays.copyOf(bg, bg.length * 2);
        bg[bgCount++] = v;
    }

    void bgRemoveAt(int i) {
        System.arraycopy(bg, i + 1, bg, i, bgCount - i - 1);
        bg[--bgCount] = null;
    }

    /* ── patterns and cues ── */

    Cell[] patternRead(int idx) {
        if (idx < 0 || idx >= NUM_PATTERNS || playdata[idx] == null) return emptyPattern;
        return playdata[idx];
    }

    private boolean isSharedBlank(Cell[] p) {
        return p != null && (p == sharedBlank[0] || p == sharedBlank[1]);
    }

    /** Writable pattern idx, allocating its rows on first access — and
     *  copying the shared blank before anything is written over it. */
    private Cell[] patternFor(int idx) {
        if (idx < 0 || idx >= NUM_PATTERNS) return null;
        Cell[] p = playdata[idx];
        if (p == null) {
            p = Cell.newPattern();
            playdata[idx] = p;
        } else if (isSharedBlank(p)) {
            Cell[] q = Cell.newPattern();
            for (int i = 0; i < ROWS_PER_PATTERN; i++) q[i].copyFrom(p[i]);
            playdata[idx] = p = q;
        }
        return p;
    }

    private static void cellSetByte(Cell c, int o, int b) {
        switch (o) {
        case 0: c.note = (c.note & 0xff00) | b; break;
        case 1: c.note = (c.note & 0x00ff) | (b << 8); break;
        case 2: c.instrment = b; break;
        case 3: c.volume = b & 63; c.volumeEff = (b >> 6) & 3; break;
        case 4: c.pan = b & 63; c.panEff = (b >> 6) & 3; break;
        case 5: c.effect = b; break;
        case 6: c.effectArg = (c.effectArg & 0xff00) | b; break;
        case 7: c.effectArg = (c.effectArg & 0x00ff) | (b << 8); break;
        default: break;
        }
    }

    private static void cellSetByteWide(Cell c, int o, int b) {
        switch (o) {
        case 0: c.note = (c.note & 0xff00) | b; break;
        case 1: c.note = (c.note & 0x00ff) | (b << 8); break;
        case 2: c.instrment = b; break;
        case 3: c.volume = b & 0xff; break;
        case 4: c.azimuth = (c.azimuth & 0x100) | b; break;
        case 5: c.effect = b; break;
        case 6: c.effectArg = (c.effectArg & 0xff00) | b; break;
        case 7: c.effectArg = (c.effectArg & 0x00ff) | (b << 8); break;
        case 8:
            c.azimuth = (c.azimuth & 0xff) | ((b & 0x80) << 1);
            c.volumeEff = (b >> 4) & 7;
            c.panEff = b & 0xf;
            break;
        case 9: c.elevation = b >= 0x80 ? b - 0x100 : b; break;
        case 10: c.effect2 = b; break;
        case 11: c.effectArg2 = (c.effectArg2 & 0xff00) | b; break;
        case 12: c.effectArg2 = (c.effectArg2 & 0x00ff) | (b << 8); break;
        default: break;
        }
    }

    private void decodePattern(Cell[] pat, byte[] bytes, int off, int len) {
        if (wideCells) {
            int n = len < PATTERN_BYTES_WIDE ? len : PATTERN_BYTES_WIDE;
            for (int i = 0; i < n; i++) cellSetByteWide(pat[i / CELL_BYTES_WIDE], i % CELL_BYTES_WIDE, bytes[off + i] & 0xff);
            return;
        }
        int n = len < PATTERN_BYTES ? len : PATTERN_BYTES;
        for (int i = 0; i < n; i++) cellSetByte(pat[i / CELL_BYTES], i % CELL_BYTES, bytes[off + i] & 0xff);
    }

    /** The no-op cell's bytes: both column selectors FINE by zero, everything
     *  else zero (file format §5.5). */
    private static boolean isBlankPatternBytes(byte[] bytes, int off, int len, boolean wide) {
        int size = wide ? CELL_BYTES_WIDE : CELL_BYTES;
        if (len < 64 * size) return false;
        for (int i = 0; i < 64 * size; i++) {
            int k = i % size;
            int want = wide ? (k == 8 ? 0x33 : 0) : (k == 3 || k == 4 ? 0xc0 : 0);
            if ((bytes[off + i] & 0xff) != want) return false;
        }
        return true;
    }

    /** The one copy every slot holding a blank pattern shares, one per cell
     *  format (blankPattern). Read-only: patternFor copies it before a write. */
    private Cell[] blankPattern() {
        int k = wideCells ? 1 : 0;
        if (sharedBlank[k] == null) {
            byte[] bytes = new byte[PATTERN_BYTES_WIDE];
            int size = wideCells ? CELL_BYTES_WIDE : CELL_BYTES;
            Cell[] p = Cell.newPattern();
            for (int r = 0; r < 64; r++) {
                if (wideCells) bytes[r * size + 8] = 0x33;
                else { bytes[r * size + 3] = (byte) 0xc0; bytes[r * size + 4] = (byte) 0xc0; }
            }
            decodePattern(p, bytes, 0, 64 * size);
            sharedBlank[k] = p;
        }
        return sharedBlank[k];
    }

    /** A blank pattern is not stored but shared: a song renumbered lane by
     *  lane leaves thousands of them in the gaps between lanes. */
    void uploadPattern(int slot, byte[] bytes, int off, int len) {
        int idx = slot & 0x7fff;
        if (idx < NUM_PATTERNS && isBlankPatternBytes(bytes, off, len, wideCells)) {
            playdata[idx] = blankPattern();
            return;
        }
        Cell[] pat = patternFor(idx);
        if (pat == null) return;
        decodePattern(pat, bytes, off, len);
    }

    private static Cell.PlayInst decodeInstWord(int w) {
        if (w == 0) return new Cell.PlayInst(INST_NOP, 0, 0);
        int b30 = (w >> 8) & 0xff;
        int b31 = w & 0xff;
        if (b30 == 0x02) { int n = (b31 & 0x3f) + 1; return new Cell.PlayInst(INST_PATLEN, n, n); }
        if (b30 == 0x01) {
            if ((b31 & 0xc0) == 0x40) {
                int x = b31 & 0x3f;
                int n = x == 0 ? 64 : x;
                return new Cell.PlayInst(INST_HALTAT, n, n);
            }
            return new Cell.PlayInst(INST_HALT, 0, 0);
        }
        if ((b30 & 0xf0) == 0x80) return new Cell.PlayInst(INST_GOBACK, ((b30 & 0xf) << 8) | b31, 0);
        if ((b30 & 0xf0) == 0x90) return new Cell.PlayInst(INST_SKIP, ((b30 & 0xf) << 8) | b31, 0);
        if ((b30 & 0xf0) == 0xf0) return new Cell.PlayInst(INST_JUMP, ((b30 & 0xf) << 8) | b31, 0);
        return new Cell.PlayInst(INST_NOP, 0, 0);
    }

    private static int cueInstWord(Cell.Cue c, int base) {
        int w = 0;
        for (int k = 0; k < 16; k++) w |= ((c.raw[base + k] >> 15) & 1) << k;
        return w;
    }

    Cell.Cue cueAt(int idx) {
        idx &= NUM_CUES - 1;
        Cell.Cue[] block = cueBlocks[idx / CUE_BLOCK];
        return block != null ? block[idx % CUE_BLOCK] : emptyCue;
    }

    void uploadCue(int idx, byte[] bytes, int off, int len) {
        int n = is64ChannelMode ? 128 : 64;
        idx &= NUM_CUES - 1;
        Cell.Cue[] block = cueBlocks[idx / CUE_BLOCK];
        if (block == null) {
            block = new Cell.Cue[CUE_BLOCK];
            for (int k = 0; k < CUE_BLOCK; k++) block[k] = new Cell.Cue();
            cueBlocks[idx / CUE_BLOCK] = block;
        }
        Cell.Cue cue = block[idx % CUE_BLOCK];
        if (len < n) n = len;
        for (int i = 0; i < n; i++) {
            int ch = i >> 1;
            int b = bytes[off + i] & 0xff;
            cue.raw[ch] = (i & 1) == 0 ? (cue.raw[ch] & 0xff00) | b : (cue.raw[ch] & 0x00ff) | (b << 8);
        }
        cue.inst0 = decodeInstWord(cueInstWord(cue, 0));
        cue.inst1 = decodeInstWord(cueInstWord(cue, 16));
    }

    /* ── content upload ── */

    /** Install a sample+instrument image: `pool` is borrowed (the engine
     *  never writes a sample), `instBin` holds the 1024 256-byte instrument
     *  records at instOff. */
    void uploadSampleInstBlob(byte[] pool, int poolLen, byte[] instBin, int instOff) {
        sampleBin = pool;
        samplePoolLen = poolLen;
        for (int i = 0; i < 1024; i++) instruments[i].loadRecord(instBin, instOff + i * 256);
        /* A chip patch may have been replaced. */
        formantTables.chipMemoCount = 0;
        hasPsg = false;
        for (int i = 0; i < 1024; i++) if (instruments[i].psg != null) hasPsg = true;
        for (int i = 0; i < NUM_INST_SLOTS; i++) {
            instruments[i].extraPatches = null;
            instruments[i].patchCount = 0;
        }
        imixGen++;   /* byte 255 of every record may have moved */
    }

    void uploadInstrumentPatches(int slot, byte[] bytes, int off, int len) {
        Inst inst = instruments[slot & 0x3ff];
        Patch[] patches = Inst.parsePatchesBlob(bytes, off, len);
        inst.extraPatches = patches;
        inst.patchCount = patches != null ? patches.length : 0;
    }

    void setCellFormat(boolean wide) {
        wideCells = wide;
        if (tsWideCells == wide) return;
        tsWideCells = wide;
        volMax = wide ? VOLUME_MAX_WIDE : VOLUME_MAX;
        volStep = wide ? VOLUME_STEP_WIDE : 1;
        volDiv = volMax * 1.0;
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = voices[i];
            v.noteVolume = volMax;
            v.channelVolume = volMax;
            v.channelVolumeSet = false;
            v.rowVolume = volMax;
        }
    }

    void setTuning(int baseNote, double freq) {
        tuningBaseNote = baseNote & 0xffff;
        tuningFreq = freq;
        tuningRatio = Tables.tuningRatioOf(tuningBaseNote, tuningFreq);
    }

    void updateGlobalBehaviour(int flags) {
        toneMode = flags & 3;
        interpolationMode = (flags >> 2) & 7;
    }

    void setTrackerMixerFlags(int flags) {
        initialGlobalFlags = flags;
        updateGlobalBehaviour(flags);
    }

    /** (Re-)install the surround model with the monitor's render target. */
    private void applySurroundModel() {
        int model = surroundModel & 3;
        tsSurroundModel = model;
        spatial = null;
        rendererGen++;
        /* The instrument buses are in the song's channel layout, which just moved. */
        imixGen++;
        if (model == SURROUND_STEREO) return;
        spatial = new SpatialBus();
        if (monitorMode == MONITOR_BINAURAL) {
            boolean sphere = model == SURROUND_SPATIAL;
            if (binaural == null || binaural.sphere != sphere) binaural = new Binaural(sphere, rate);
            binaural.reset();
            spatial.kind = 1;
            spatial.bin = binaural;
            spatial.numChannels = binaural.numChannels;
            return;
        }
        spatial.kind = 0;
        spatial.numChannels = 2;
    }

    void setSurroundModel(int model) {
        surroundModel = model & 3;
        applySurroundModel();
    }

    void setMonitorMode(int mode) {
        monitorMode = mode & 1;
        applySurroundModel();
    }

    void setMastering(Mastering.Params params) {
        if (params != null) { masteringParams.copyFrom(params); hasMasteringParams = true; }
        else hasMasteringParams = false;
        if (!Mastering.engaged(params)) {
            mastering = null;
            return;
        }
        if (mastering == null) mastering = new Mastering.Chain(params, rate);
        else mastering.setParams(params);
    }

    /* ── transport ── */

    void setCuePosition(int pos) {
        position = pos & (NUM_CUES - 1);
        cuePos = position;
    }

    /** The per-PLAY half of every reset that starts playback somewhere (§15,
     *  TrackerState.resetPlayState): the tick clock and the jump state, no
     *  voice sounding, the background pool empty, the interrupt latch clear,
     *  the OPL chip clock back at zero and the mastering chain's running
     *  state gone. */
    void resetPlayState() {
        tickInRow = 0;
        samplesIntoTick = 0.0;
        firstRow = true;
        oplFrame = 0;
        psgQuarter = 0;
        pendingOrderJump = -1;
        pendingRowJump = -1;
        pendingRowJumpLocal = false;
        patternDelayRemaining = 0;
        patternDelayActive = false;
        sexWinningChannel = -1;
        finePatternDelayExtra = 0;
        pendingInterrupts = 0;
        java.util.Arrays.fill(interruptArgs, 0);
        /* A play starts AT its levels: nothing glides in from the last one. */
        for (int i = 0; i < MAX_VOICES; i++) { voices[i].active = false; voices[i].faderCur = Double.NaN; }
        gvolGlide.cur = Double.NaN;
        mvolGlide.cur = Double.NaN;
        masterGlide.cur = Double.NaN;
        for (int i = 0; i < bgCount; i++) bg[i] = null;
        bgCount = 0;
        if (mastering != null) mastering.reset();
        /* …and the instruments' chains (item 217) for the same reason: a
         * reverb tail or a delay line still holding the previous take is
         * that take. */
        Imix.resetChains(this);
    }

    void setTrackerRow(int row) {
        rowIndex = iclamp(row, 0, 63);
        resetPlayState();
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = voices[i];
            v.loopStartRow = 0; v.loopCount = 0;
            v.dittoActive = false; v.dittoSourceStart = 0; v.dittoLength = 0; v.dittoEndRow = 0;
            v.channelVolume = volMax;
            v.channelPan = 0x80;
            v.channelVolumeSet = false;
            v.channelPanSet = false;
            v.rowPan = 32;
            v.panAzimuth = 128.0;
            v.panElevation = 0.0;
            v.notePan = 0;
            v.noteElevation = 0.0;
            v.spatialTargetAz = 128.0;
            v.spatialTargetEl = 0.0;
            v.spatialSlideActive = false;
            v.panbrelloOffset = 0;
            v.glissandoOn = false;
            v.clipMode = 0;
            v.bitcrusherDepth = 0;
            v.bitcrusherSkip = 0;
            v.bitcrusherCounter = 0;
            v.bitcrusherHeld = 0.0;
            v.overdriveAmp = 0;
            v.right.bitcrusherCounter = 0;
            v.right.bitcrusherHeld = 0.0;
            v.nnaOverride = -1;
            v.volEnvOn = true; v.panEnvOn = true; v.pitchEnvOn = true; v.filterEnvOn = true;
        }
        Row.reconstructDittoState(this, rowIndex);
    }

    void play() { isPlaying = true; }

    void silenceSongVoices(boolean ramp) {
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = voices[i];
            if (!v.active) continue;
            if (ramp) Sampler.startCutRamp(this, v); else v.active = false;
        }
        for (int i = 0; i < bgCount; i++) {
            Voice b = bg[i];
            if (!b.active) continue;
            if (ramp) Sampler.startCutRamp(this, b); else b.active = false;
        }
    }

    void stop() {
        isPlaying = false;
        silenceSongVoices(false);
    }

    void resetSampleFxState() {
        for (int i = 0; i < MAX_VOICES; i++) {
            Voice v = voices[i];
            v.invertSpeed = 0;
            v.invertAccumulator = 0;
            v.invertWritePos = 0;
            v.funkSpeed = 0;
            v.funkMode = 0;
            v.funkAccumulator = 0;
            v.funkWalk = -1;
            v.funkPos = -1;
            v.funkWindow = -1;
            v.funkXfade = 0;
            v.modFunkWindow = -1;
            v.modFunkXfade = 0;
            v.modPeriod = 0;
            v.modTickCount = 0;
            v.modWritePos = 0;
            v.modXfade = 0;
        }
        for (int i = 0; i < NUM_INST_SLOTS; i++) {
            instruments[i].invertMaskLen = 0;
            instruments[i].resetMod();
            /* The S $Axxx overlay goes with them: a new playback hears the record. */
            if (instruments[i].psg != null) Psg.clearRuntime(instruments[i].psg);
        }
    }

    int drainInterrupts() {
        int m = pendingInterrupts;
        pendingInterrupts = 0;
        return m;
    }

    boolean renderChunk() { return Mixer.generateTrackerAudio(this); }

    /** Install the project's LYRICS (the `ILyr` section, §9.17), replacing
     *  whatever was installed — an instrument no entry names has none. The
     *  first entry for an instrument wins. */
    void setLyrics(LyricEntry[] entries, int count) {
        LyricEntry[] by = new LyricEntry[NUM_INST_SLOTS];
        for (int i = 0; i < count; i++) {
            int id = entries[i].instId & 0x3ff;
            if (id < NUM_INST_SLOTS && by[id] == null) by[id] = entries[i];
        }
        for (int i = 0; i < NUM_INST_SLOTS; i++) {
            Inst inst = instruments[i];
            if (by[i] != inst.lyricEntry) inst.setLyric(by[i]);
        }
    }

    /* ── lifetime ── */

    Engine(int rate) {
        this.rate = rate;
        rampOutSamples = (int) JsMath.round(RAMP_OUT_SEC * rate);
        volRampSamples = (int) JsMath.round(VOL_RAMP_SEC * rate);
        attackRampSamples = (int) JsMath.round(ATTACK_RAMP_SEC * rate);
        sfFilterGlideSamples = (int) JsMath.round(SF_FILTER_GLIDE_SEC * rate);
        if (sfFilterGlideSamples < 1) sfFilterGlideSamples = 1;
        if (volRampSamples < 1) volRampSamples = 1;
        if (attackRampSamples < 1) attackRampSamples = 1;
        if (rampOutSamples < 1) rampOutSamples = 1;
        Tables.buildTables(this);
        Formant.tablesInit(formantTables, rate);

        for (int i = 0; i < NUM_INST_SLOTS; i++) instruments[i] = new Inst(i);
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
        masteringParams.setDefault();
    }
}
