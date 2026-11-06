/*
 * Taud instrument data model — port of inst.js (taudplay-c inst.c).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

final class Inst {
    int index;

    long samplePtr;
    int sampleLength, samplingRate, samplePlayStart, sampleLoopStart, sampleLoopEnd;
    int loopMode;
    int volEnvLoop, panEnvLoop, pfEnvLoop;
    int instGlobalVolume = 0xff;
    final EnvPoint[] volEnvelopes = EnvPoint.make(0x3f), panEnvelopes = EnvPoint.make(0x80);
    final EnvPoint[] pfEnvelopes = EnvPoint.make(0x80), pf2Envelopes = EnvPoint.make(0x80);
    int volumeFadeoutLow, fadeoutHigh, volumeSwing, vibratoSpeed, vibratoSweep;
    int defaultPan = 0x80, pitchPanCentre = 0x5000, pitchPanSeparation, panSwing;
    int defaultCutoff = 0xff, defaultResonance, sampleDetune, instrumentFlag;
    int vibratoDepth, vibratoRate;
    int volEnvSustainWord, panEnvSustainWord, pfEnvSustainWord;
    int dupCheckFlag, defaultNoteVolume, pf2EnvLoop, pf2EnvSustainWord;
    final int[] reserved = new int[5];
    int initialAttenOctet;

    int cutoffOverride = -1, resonanceOverride = -1;

    Patch[] extraPatches;       /* null when none */
    int patchCount;

    Meta meta;                  /* null unless a metainstrument */
    OplPatch opl;               /* null unless a type-8 OPL instrument */
    PsgPatch psg;               /* null unless a type-9 PSG instrument */
    FormantPatch formant;       /* null unless a type-1 formant instrument */
    /* Lyric instrument: the `ILyr` entry installed (setLyric), kept across
     * record loads, and — while the record is a formant instrument — one patch
     * per syllable, refreshed in place on every load. `lyric` is null for any
     * other instrument; lyricStore is the storage it points at. */
    LyricEntry lyricEntry;
    FormantPatch[] lyric;
    FormantPatch[] lyricStore;

    /* Invert loop (S $F0xx). invertMaskLen 0 = no mask. */
    byte[] invertMask;
    int invertMaskLen;

    /* Sample modification (notefx 2 / 3). */
    int modOp;
    boolean modInvert;
    double modFrom, modTo = 1;
    int modCombBits = -1;
    boolean modCombOdd;
    byte[] modMask;
    int modMaskLen;             /* 0 = no mask */
    int modRot, modSub, modScatter;
    int modSeed;                /* uint32 */
    boolean modOn;
    int modEpoch;
    int modPrevRot, modPrevSub, modPrevScatter;
    int modPrevSeed;            /* uint32 */
    int modOpExt, modF, modStepIndex, modXor, modPrevXor, modBitRot, modBitPermIdx;
    boolean modBitPermOn;
    int modExtSwapA = -1, modExtSwapB = -1;
    boolean modExtMirror;
    int modFunkWalk = -1, modFunkPos = -1, modFunkLen;

    Inst(int index) { this.index = index; }

    /* ── derived properties (taud_internal.h's inst_* helpers) ── */

    boolean isMeta() { return meta != null; }
    /** The IMix block the instrument's voices mix through (byte 255; 0 = none). */
    int imixId() { return meta != null ? meta.imixId : reserved[4]; }
    boolean isFm() { return meta != null && meta.type == META_TYPE_FM; }
    int newNoteAction() {
        int n = (instrumentFlag & 0x03) | (((instrumentFlag >> 5) & 1) << 2);
        return n <= 4 ? n : 0;
    }
    boolean nnaKeyLift() { return newNoteAction() == 4; }
    /** Byte 186 bit 6: Q's retrigger hands the outgoing sound to the background
     *  pool per the New Note Action instead of cutting it (spec §5.7). */
    boolean retrigFollowsNna() { return (instrumentFlag & 0x40) != 0; }
    int vibratoWaveform() { return (instrumentFlag >> 2) & 0x07; }
    int sampleDetuneSigned() {
        int v = sampleDetune & 0xffff;
        return v >= 0x8000 ? v - 0x10000 : v;
    }
    /** Byte 195's two nibbles, `0b AATT aatt` (packDupCheck). */
    int dupCheckType() { return (dupCheckFlag & 0x03) | ((dupCheckFlag >> 2) & 0x0c); }
    int dupCheckAction() { return ((dupCheckFlag >> 2) & 0x03) | ((dupCheckFlag >> 4) & 0x0c); }
    boolean isOpl() { return opl != null; }
    boolean isPsg() { return psg != null; }
    /** A chip instrument (OPL or PSG): what a high meta can sound through. */
    boolean isChip() { return opl != null || psg != null; }
    boolean isFormant() { return formant != null; }

    static int metaTierOf(int type) {
        int t = type & 0x0f;
        return t <= 7 ? META_TIER_HIGH : t <= 13 ? META_TIER_LOW : META_TIER_RESERVED;
    }
    int metaTier() {
        if (opl != null || psg != null) return META_TIER_LOW;
        if (formant != null) return META_TIER_HIGH;
        if (meta != null) return metaTierOf(meta.type);
        return META_TIER_NONE;
    }
    boolean isHighMeta() { return metaTier() == META_TIER_HIGH; }
    boolean filterSfMode() { return ((fadeoutHigh >> 4) & 1) != 0; }
    int defaultAzimuth() { return ((loopMode & 0x20) != 0 ? 256 : 0) | (defaultPan & 0xff); }
    int defaultElevation() {
        int b = reserved[3] & 0xff;
        return b >= 0x80 ? b - 256 : b;
    }
    int defaultCutoff16() {
        if (cutoffOverride >= 0) return cutoffOverride;
        return filterSfMode() ? ((defaultCutoff & 0xff) << 8) | (reserved[1] & 0xff) : defaultCutoff;
    }
    int defaultResonance16() {
        if (resonanceOverride >= 0) return resonanceOverride;
        return filterSfMode() ? ((defaultResonance & 0xff) << 8) | (reserved[2] & 0xff) : defaultResonance;
    }

    /* ── patches ── */

    static Patch patchAt(Inst inst, int patchIndex) {
        if (inst == null || patchIndex < 0) return null;
        return inst.extraPatches != null && patchIndex < inst.patchCount ? inst.extraPatches[patchIndex] : null;
    }

    private static int u8(byte[] b, int x) { return b[x] & 0xff; }
    private static int u16(byte[] b, int x) { return (b[x] & 0xff) | ((b[x + 1] & 0xff) << 8); }
    private static long u32(byte[] b, int x) {
        return (b[x] & 0xffL) | ((b[x + 1] & 0xffL) << 8) | ((b[x + 2] & 0xffL) << 16) | ((b[x + 3] & 0xffL) << 24);
    }

    /** Parse a flat variable-length Ixmp patch blob (`len` bytes at `off`).
     *  Returns the patches, or null for a short or empty blob. */
    static Patch[] parsePatchesBlob(byte[] bytes, int off, int len) {
        int o = 0;
        java.util.ArrayList<Patch> patches = new java.util.ArrayList<>();
        if (len < 31) return null;
        outer:
        while (o + 31 <= len) {
            int ver = u8(bytes, off + o);
            int p = o + 31;
            Patch t = new Patch();
            if ((ver & 0x80) != 0) { /* 'x' block (15 bytes) */
                if (p + 15 > len) break;
                t.filterSfMode = (u8(bytes, off + p) & 0x01) != 0;
                t.fadeoutStep = u16(bytes, off + p + 8);
                t.extraCutoff = u16(bytes, off + p + 10);
                t.extraResonance = u16(bytes, off + p + 12);
                t.extraInitialAttenOctet = u8(bytes, off + p + 14);
                t.hasExtra = true;
                p += 15;
            }
            for (int which = 0; which < 4; which++) {
                if ((ver & (0x02 << which)) == 0) continue;
                if (p + 54 > len) break outer;
                int loopW = u16(bytes, off + p), susW = u16(bytes, off + p + 2);
                EnvPoint[] arr = which == 0 ? t.volEnv : which == 1 ? t.panEnv : which == 2 ? t.filterEnv : t.pitchEnv;
                for (int k = 0; k < 25; k++) {
                    arr[k].value = u8(bytes, off + p + 4 + 2 * k);
                    arr[k].offset = u8(bytes, off + p + 5 + 2 * k);
                }
                switch (which) {
                case 0: t.volEnvLoop = loopW; t.volEnvSustain = susW; t.hasVolEnv = true; break;
                case 1: t.panEnvLoop = loopW; t.panEnvSustain = susW; t.hasPanEnv = true; break;
                case 2: t.filterEnvLoop = loopW; t.filterEnvSustain = susW; t.hasFilterEnv = true; break;
                default: t.pitchEnvLoop = loopW; t.pitchEnvSustain = susW; t.hasPitchEnv = true; break;
                }
                p += 54;
            }
            if ((ver & 0x20) != 0) {
                if (p + 4 > len) break;
                int cb = u8(bytes, off + p);
                t.chanCount = (cb >> 4) + 1;
                t.chanMode = cb & 0x0f;
                t.chanFlags = u8(bytes, off + p + 1) | (u8(bytes, off + p + 2) << 8) | (u8(bytes, off + p + 3) << 16);
                if (p + 4 + 4 * (long) (t.chanCount - 1) > len) break;
                for (int k = 0; k < t.chanCount - 1; k++) t.chanPtrs[k] = u32(bytes, off + p + 4 + 4 * k);
                t.chanPtrCount = t.chanCount - 1;
                t.hasChanBlock = true;
                p += 4 + 4 * (t.chanCount - 1);
            }
            t.pitchStart = u16(bytes, off + o + 1);
            t.pitchEnd = u16(bytes, off + o + 3);
            t.volumeStart = u8(bytes, off + o + 5);
            t.volumeEnd = u8(bytes, off + o + 6);
            t.samplePtr = u32(bytes, off + o + 7);
            t.sampleLength = u16(bytes, off + o + 11);
            t.playStart = u16(bytes, off + o + 13);
            t.loopStart = u16(bytes, off + o + 15);
            t.loopEnd = u16(bytes, off + o + 17);
            t.samplingRate = u16(bytes, off + o + 19);
            { int v = u16(bytes, off + o + 21); t.sampleDetune = v >= 0x8000 ? v - 0x10000 : v; }
            t.loopMode = u8(bytes, off + o + 23);
            t.defaultPan = u8(bytes, off + o + 24);
            t.defaultNoteVolume = u8(bytes, off + o + 25);
            t.vibratoSpeed = u8(bytes, off + o + 26);
            t.vibratoSweep = u8(bytes, off + o + 27);
            t.vibratoDepth = u8(bytes, off + o + 28);
            t.vibratoRate = u8(bytes, off + o + 29);
            t.vibratoWaveform = u8(bytes, off + o + 30);
            t.index = patches.size();
            patches.add(t);
            o = p;
        }
        if (patches.isEmpty()) return null;
        return patches.toArray(new Patch[0]);
    }

    static Patch resolvePatch(Inst inst, int noteVal, int rowVolume) {
        /* An OPL, PSG or formant instrument has no keyboard map (§7.8, §7.9,
         * §7.6): a list a project carries for one is ignored on every path. */
        if (inst.extraPatches == null || inst.opl != null || inst.psg != null || inst.formant != null) return null;
        for (int i = 0; i < inst.patchCount; i++) {
            Patch p = inst.extraPatches[i];
            if (noteVal >= p.pitchStart && noteVal <= p.pitchEnd &&
                rowVolume >= p.volumeStart && rowVolume <= p.volumeEnd) return p;
        }
        return null;
    }

    /** All meta layers whose rectangle contains the trigger, in record order.
     *  Writes them into out[] (capacity META_MAX_LAYERS), returns the count. */
    static int resolveMetaLayers(Inst inst, int noteVal, int rowVolume, Meta.Layer[] out) {
        int n = 0;
        if (inst.meta == null) return 0;
        for (int i = 0; i < inst.meta.count; i++) {
            Meta.Layer l = inst.meta.layers[i];
            if (noteVal >= l.pitchStart && noteVal <= l.pitchEnd &&
                rowVolume >= l.volStart && rowVolume <= l.volEnd) out[n++] = l;
        }
        return n;
    }

    /* ── FM program (§7.6) ── */

    /** pop | push << 8, or -1 when the word is not a valid one. */
    private static int fmWordArity(int word, int opCount) {
        int w = word & 0xffff, idx;
        if (w >= FM_WORD_OP) {
            switch (w) {
            case FMOP_ADD: case FMOP_MUL: return 2 | (1 << 8);
            case FMOP_NEG: return 1 | (1 << 8);
            case FMOP_DUP: return 1 | (2 << 8);
            case FMOP_SWAP: return 2 | (2 << 8);
            default: return -1;
            }
        }
        idx = w & FM_INDEX_MASK;
        if (idx >= opCount) return -1;
        switch (w & ~FM_INDEX_MASK) {
        case FM_WORD_OSC: case FM_WORD_FB: return 0 | (1 << 8);
        case FM_WORD_MOD: return 1 | (1 << 8);
        default: return -1;
        }
    }

    /** Read the RPN program at byte `off`; false when it does not parse. */
    private static boolean decodeFmProgram(byte[] b, int base, int off, int opCount, Meta m) {
        int depth = 0, n = 0;
        boolean ended = false;
        for (int o = off; o + 2 <= FM_PROGRAM_END; o += 2) {
            int w = (b[base + o] & 0xff) | ((b[base + o + 1] & 0xff) << 8);
            if (w == FMOP_END) { ended = true; break; }
            int a = fmWordArity(w, opCount);
            if (a < 0) return false;
            int pop = a & 0xff, push = a >> 8;
            if (depth < pop) return false;
            depth += push - pop;
            if (depth > FM_STACK_MAX) return false;
            m.program[n++] = w;
        }
        /* A program that fills its area to the last byte ends there; one that
         * stops early must say so. */
        if (!ended && off + n * 2 + 2 <= FM_PROGRAM_END) return false;
        if (depth < 1) return false;
        m.programLen = n;
        return true;
    }

    /* ── the 256-byte record ── */

    private static void envPointSet(EnvPoint[] env, int base, int offset, int byte_) {
        int rel = offset - base;
        EnvPoint pt = env[rel >> 1];
        if ((rel & 1) == 0) pt.value = byte_ & 0xff;
        else pt.offset = byte_ & 0xff;
    }

    private void setByte(int o, int b) {
        if (o >= 21 && o <= 70) { envPointSet(volEnvelopes, 21, o, b); return; }
        if (o >= 71 && o <= 120) { envPointSet(panEnvelopes, 71, o, b); return; }
        if (o >= 121 && o <= 170) { envPointSet(pfEnvelopes, 121, o, b); return; }
        if (o >= 201 && o <= 250) { envPointSet(pf2Envelopes, 201, o, b); return; }
        if (o >= 252 && o <= 255) { reserved[o - 251] = b & 0xff; return; }
        switch (o) {
        case 0: samplePtr = (samplePtr & 0xffffff00L) | b; break;
        case 1: samplePtr = (samplePtr & 0xffff00ffL) | ((long) b << 8); break;
        case 2: samplePtr = (samplePtr & 0xff00ffffL) | ((long) b << 16); break;
        case 3: samplePtr = (samplePtr & 0x00ffffffL) | ((long) b << 24); break;
        case 4: sampleLength = (sampleLength & 0xff00) | b; break;
        case 5: sampleLength = (sampleLength & 0x00ff) | (b << 8); break;
        case 6: samplingRate = (samplingRate & 0xff00) | b; break;
        case 7: samplingRate = (samplingRate & 0x00ff) | (b << 8); break;
        case 8: samplePlayStart = (samplePlayStart & 0xff00) | b; break;
        case 9: samplePlayStart = (samplePlayStart & 0x00ff) | (b << 8); break;
        case 10: sampleLoopStart = (sampleLoopStart & 0xff00) | b; break;
        case 11: sampleLoopStart = (sampleLoopStart & 0x00ff) | (b << 8); break;
        case 12: sampleLoopEnd = (sampleLoopEnd & 0xff00) | b; break;
        case 13: sampleLoopEnd = (sampleLoopEnd & 0x00ff) | (b << 8); break;
        case 14: loopMode = b & 0x37; break;
        case 15: volEnvLoop = (volEnvLoop & 0xff00) | b; break;
        case 16: volEnvLoop = (volEnvLoop & 0x00ff) | (b << 8); break;
        case 17: panEnvLoop = (panEnvLoop & 0xff00) | b; break;
        case 18: panEnvLoop = (panEnvLoop & 0x00ff) | (b << 8); break;
        case 19: pfEnvLoop = (pfEnvLoop & 0xff00) | b; break;
        case 20: pfEnvLoop = (pfEnvLoop & 0x00ff) | (b << 8); break;
        case 171: instGlobalVolume = b & 0xff; break;
        case 172: volumeFadeoutLow = b & 0xff; break;
        case 173: fadeoutHigh = b & 0x1f; break;
        case 174: volumeSwing = b & 0xff; break;
        case 175: vibratoSpeed = b & 0xff; break;
        case 176: vibratoSweep = b & 0xff; break;
        case 177: defaultPan = b & 0xff; break;
        case 178: pitchPanCentre = (pitchPanCentre & 0xff00) | b; break;
        case 179: pitchPanCentre = (pitchPanCentre & 0x00ff) | (b << 8); break;
        case 180: pitchPanSeparation = b >= 0x80 ? b - 0x100 : b; break;
        case 181: panSwing = b & 0xff; break;
        case 182: defaultCutoff = b & 0xff; break;
        case 183: defaultResonance = b & 0xff; break;
        case 184: sampleDetune = (sampleDetune & 0xff00) | b; break;
        case 185: sampleDetune = (sampleDetune & 0x00ff) | (b << 8); break;
        case 186: instrumentFlag = b & 0xff; break;
        case 187: vibratoDepth = b & 0xff; break;
        case 188: vibratoRate = b & 0xff; break;
        case 189: volEnvSustainWord = (volEnvSustainWord & 0xff00) | b; break;
        case 190: volEnvSustainWord = (volEnvSustainWord & 0x00ff) | (b << 8); break;
        case 191: panEnvSustainWord = (panEnvSustainWord & 0xff00) | b; break;
        case 192: panEnvSustainWord = (panEnvSustainWord & 0x00ff) | (b << 8); break;
        case 193: pfEnvSustainWord = (pfEnvSustainWord & 0xff00) | b; break;
        case 194: pfEnvSustainWord = (pfEnvSustainWord & 0x00ff) | (b << 8); break;
        case 195: dupCheckFlag = b & 0xff; break;
        case 196: defaultNoteVolume = b & 0xff; break;
        case 197: pf2EnvLoop = (pf2EnvLoop & 0xff00) | b; break;
        case 198: pf2EnvLoop = (pf2EnvLoop & 0x00ff) | (b << 8); break;
        case 199: pf2EnvSustainWord = (pf2EnvSustainWord & 0xff00) | b; break;
        case 200: pf2EnvSustainWord = (pf2EnvSustainWord & 0x00ff) | (b << 8); break;
        case 251: initialAttenOctet = b & 0xff; break;
        default: break;
        }
    }

    /* An ordinary record read as one, except that bytes 0…3 are the type
     * header and bytes 197…250 are the OPL or PSG patch or the tract block
     * rather than a second pitch/filter envelope, which is therefore always
     * absent. */
    private void loadTypedRecord(byte[] b, int at) {
        meta = null;
        extraPatches = null;
        patchCount = 0;
        for (int i = 4; i < 256; i++) {
            if (i >= OPL_BLOCK_START && i < OPL_BLOCK_START + OPL_BLOCK_LEN) continue;
            setByte(i, b[at + i] & 0xff);
        }
        pf2EnvLoop = 0;
        pf2EnvSustainWord = 0;
        EnvPoint.reset(pf2Envelopes, 0x80);
    }

    private void loadOplRecord(byte[] b, int at) {
        formant = null;
        psg = null;
        if (opl == null) opl = new OplPatch();
        Opl.decodeOplPatch(b, at + OPL_BLOCK_START, opl);
        samplePtr = 0;
        loadTypedRecord(b, at);
    }

    /* Type 9, laid out as type 8. The decoded patch is reused across reloads,
     * so the S $Axxx overlay it carries survives one, as invert loop's mask
     * does. */
    private void loadPsgRecord(byte[] b, int at) {
        formant = null;
        opl = null;
        if (psg == null) {
            psg = new PsgPatch();
            psg.rtAdvance[0] = psg.rtAdvance[1] = -1;
            psg.rtLevel[0] = psg.rtLevel[1] = -1;
            psg.rtMute = -1;
        }
        Psg.decodePsgPatch(b, at + PSG_BLOCK_START, psg);
        samplePtr = 0;
        loadTypedRecord(b, at);
    }

    private void loadFormantRecord(byte[] b, int at) {
        opl = null;
        psg = null;
        if (formant == null) formant = new FormantPatch();
        Formant.decodeFormantBlock(b, at + FORMANT_BLOCK_START, formant);
        /* The source instrument (§7.6): bits 8…9 in byte 0's two low bits,
         * bits 0…7 in byte 1; 0 sings with the sample. */
        formant.srcInst = ((b[at] & 0x03) << 8) | (b[at + 1] & 0xff);
        samplePtr = formant.srcPtr;
        loadTypedRecord(b, at);
    }

    /* Re-derive the lyric syllables from the record as it now stands, in place
     * where the count allows (Microtone 4.7.0, `ILyr`). */
    private void refreshLyric() {
        LyricEntry e = lyricEntry;
        if (formant == null || e == null) { lyric = null; return; }
        if (lyricStore == null || lyricStore.length != e.numSyllables) {
            /* The engine only ever installs the lyrics of the document it
             * loads, at load, so no voice still sings from the storage
             * dropped here. */
            lyricStore = new FormantPatch[e.numSyllables];
            for (int i = 0; i < lyricStore.length; i++) lyricStore[i] = new FormantPatch();
        }
        for (int i = 0; i < e.numSyllables; i++) {
            Formant.lyricSyllable(formant, e.syllables[i], e, lyricStore[i]);
        }
        lyric = lyricStore;
    }

    /** Install (or, with null, drop) this instrument's `ILyr` entry. */
    void setLyric(LyricEntry entry) {
        lyricEntry = entry != null && entry.numSyllables > 0 ? entry : null;
        refreshLyric();
    }

    /** Load a full 256-byte record at b[at]; detects the Metainstrument sentinel. */
    void loadRecord(byte[] b, int at) {
        loadRecord0(b, at);
        refreshLyric();
    }

    private void loadRecord0(byte[] b, int at) {
        long sp = (b[at] & 0xffL) | ((b[at + 1] & 0xffL) << 8) | ((b[at + 2] & 0xffL) << 16) | ((b[at + 3] & 0xffL) << 24);
        int b0 = b[at] & 0xff;
        cutoffOverride = -1;
        resonanceOverride = -1;
        boolean sentinel = ((sp >> 16) & 0xffff) == 0xffff;
        if (sentinel && ((b0 >> 4) & 0x0f) == META_TYPE_OPL) { loadOplRecord(b, at); return; }
        if (sentinel && ((b0 >> 4) & 0x0f) == META_TYPE_PSG) { loadPsgRecord(b, at); return; }
        if (sentinel && ((b0 >> 4) & 0x0f) == META_TYPE_FORMANT) { loadFormantRecord(b, at); return; }
        opl = null;
        psg = null;
        formant = null;
        if (sentinel) {
            int type = (b0 >> 4) & 0x0f;
            boolean fm = type == META_TYPE_FM;
            int rawCount = (int) ((sp >> 8) & 0xff);
            int count = fm ? imin(rawCount, FM_MAX_OPERATORS) : rawCount;
            int o = 4;
            Meta m = new Meta();
            for (int n = 0; n < count; n++) {
                if (o + 10 > 256) break;
                int bo8 = b[at + o + 8] & 0xff, bo9 = b[at + o + 9] & 0xff;
                int instIdx = (b[at + o] & 0xff) | (((bo8 >> 6) & 0x3) << 8);
                int mixOctet = b[at + o + 1] & 0xff;
                int detRaw = (b[at + o + 2] & 0xff) | ((b[at + o + 3] & 0xff) << 8);
                /* Fixed pitch: a layer's (item 179) or a rack operator's fixed
                 * frequency (item 278) — the detune field is then an unsigned
                 * note word, so read the flag before the sign conversion. */
                boolean fixedPitch = (bo9 & META_LAYER_FIXED_PITCH) != 0;
                int detune = fixedPitch ? detRaw : (detRaw >= 0x8000 ? detRaw - 0x10000 : detRaw);
                int pStart = (b[at + o + 4] & 0xff) | ((b[at + o + 5] & 0xff) << 8);
                int pEnd = (b[at + o + 6] & 0xff) | ((b[at + o + 7] & 0xff) << 8);
                int vStart = bo8 & 0x3f;
                int vEnd = bo9 & 0x3f;
                boolean usable = instIdx >= 1 && instIdx <= 1023 && instIdx != index;
                if ((usable || fm) && m.count < META_MAX_LAYERS) {
                    Meta.Layer l = m.layers[m.count++];
                    l.instIdx = usable ? instIdx : 0;
                    l.mixOctet = mixOctet;
                    l.detune = detune;
                    l.pitchStart = pStart;
                    l.pitchEnd = pEnd;
                    l.volStart = vStart;
                    l.volEnd = vEnd;
                    l.fixedPitch = fixedPitch;
                }
                o += 10;
            }
            meta = null;
            if (m.count > 0) {
                m.flags = b0;
                /* Bit 0 is strict layering in a Layered record only; in a rack
                 * it is the editor's Easy Mode mark (§7.7), which the engine
                 * does not read. */
                m.strict = !fm && (b0 & 0x01) != 0;
                m.imixId = b[at + 255] & 0xff;
                m.type = type;
                m.hasProgram = fm && decodeFmProgram(b, at, o, m.count, m);
                meta = m;
            }
            extraPatches = null;
            patchCount = 0;
        } else {
            meta = null;
            for (int i = 0; i < 256; i++) setByte(i, b[at + i] & 0xff);
        }
    }

    /* ── invert-loop mask (S $F0xx) ── */

    void toggleInvertBit(int loopOffset, int loopLen) {
        int len = imax(loopLen, 1);
        int expectedSize = (len + 7) >> 3;
        if (expectedSize > MOD_MASK_CAP) expectedSize = MOD_MASK_CAP;
        if (invertMaskLen == 0 || invertMaskLen != expectedSize) {
            if (invertMask == null) invertMask = new byte[MOD_MASK_CAP];
            java.util.Arrays.fill(invertMask, 0, expectedSize, (byte) 0);
            invertMaskLen = expectedSize;
        }
        int idx = iclamp(loopOffset, 0, len - 1);
        if ((idx >> 3) < invertMaskLen) invertMask[idx >> 3] ^= (byte) (1 << (idx & 7));
    }

    /* A read never changes the mask: one sized for another loop (a voice on a
     * different Ixmp patch of this instrument) simply does not apply to it. */
    boolean invertBit(int loopOffset, int loopLen) {
        if (invertMaskLen == 0) return false;
        int len = imax(loopLen, 1);
        if (invertMaskLen != ((len + 7) >> 3)) return false;
        int idx = iclamp(loopOffset, 0, len - 1);
        return ((invertMask[idx >> 3] >> (idx & 7)) & 1) != 0;
    }

    /* ── sample modification state ── */

    void clearModState() {
        modMaskLen = 0;
        modRot = 0;
        modSub = 0;
        modScatter = 0;
        modSeed = 0;
        modOn = false;
        modPrevRot = 0;
        modPrevSub = 0;
        modPrevScatter = 0;
        modPrevSeed = 0;
        modStepIndex = 0;
        modXor = 0;
        modPrevXor = 0;
        modBitRot = 0;
        modBitPermIdx = 0;
        modBitPermOn = false;
        modExtSwapA = -1;
        modExtSwapB = -1;
        modExtMirror = false;
        modFunkWalk = -1;
        modFunkPos = -1;
        modFunkLen = 0;
    }

    boolean setModRegion(double from, double to) {
        if (modFrom == from && modTo == to && modCombBits == -1) return false;
        modFrom = from;
        modTo = to;
        modCombBits = -1;
        modCombOdd = false;
        modEpoch++;
        clearModState();
        return true;
    }

    boolean setModComb(int bits, boolean odd) {
        if (modCombBits == bits && modCombOdd == odd) return false;
        modCombBits = bits;
        modCombOdd = odd;
        modEpoch++;
        clearModState();
        return true;
    }

    boolean setModOp(int op, boolean invert) {
        if (modOp == op && modInvert == invert && modOpExt == 0) return false;
        modOp = op;
        modInvert = invert;
        modOpExt = 0;
        modF = 0;
        modEpoch++;
        clearModState();
        return true;
    }

    boolean setModOpExt(int code, boolean invert, int f) {
        if (modOpExt == code && modInvert == invert && modF == f && modOp == 0) return false;
        modOp = 0;
        modOpExt = code;
        modInvert = invert;
        modF = f;
        modEpoch++;
        clearModState();
        return true;
    }

    void snapshotModState() {
        modPrevRot = modRot;
        modPrevSub = modSub;
        modPrevScatter = modScatter;
        modPrevSeed = modSeed;
        modPrevXor = modXor;
    }

    void resetMod() {
        modOp = 0;
        modOpExt = 0;
        modF = 0;
        modInvert = false;
        modFrom = 0;
        modTo = 1;
        modCombBits = -1;
        modCombOdd = false;
        modEpoch++;
        clearModState();
    }

    void toggleModBit(int i, int sampleLen) {
        int len = imax(sampleLen, 1);
        int expectedSize = (len + 7) >> 3;
        if (expectedSize > MOD_MASK_CAP) expectedSize = MOD_MASK_CAP;
        if (modMaskLen == 0 || modMaskLen != expectedSize) {
            if (modMask == null) modMask = new byte[MOD_MASK_CAP];
            java.util.Arrays.fill(modMask, 0, expectedSize, (byte) 0);
            modMaskLen = expectedSize;
        }
        int idx = iclamp(i, 0, len - 1);
        if ((idx >> 3) < modMaskLen) modMask[idx >> 3] ^= (byte) (1 << (idx & 7));
        modOn = true;
    }

    boolean modBit(int i) {
        if (modMaskLen == 0) return false;
        int byte_ = i >> 3;
        if (byte_ < 0 || byte_ >= modMaskLen) return false;
        return ((modMask[byte_] >> (i & 7)) & 1) != 0;
    }
}
