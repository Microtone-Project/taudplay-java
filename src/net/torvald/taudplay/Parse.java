/*
 * .taud container parser — port of taud-parse.js, taud-const.js and
 * offline-render.js loadIntoEngine (taudplay-c parse.c).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import static net.torvald.taudplay.C.*;

import java.nio.charset.StandardCharsets;

final class Parse {
    private Parse() {}

    private static final int TAUD_HEADER_SIZE = 32;
    private static final int TAUD_SONG_ENTRY = 32;
    private static final int TAUD_VERSION_MASK = 0x1f;
    private static final int TAUD_XHDR_FLAG = 0x20;
    private static final int TAUD_KIND_MASK = 0xc0;
    private static final int TAUD_KIND_FULL = 0x00;
    private static final int TAUD_KIND_SAMPLEINST = 0x80;
    private static final int TAUD_KIND_PATTERN = 0xc0;
    private static final int CUE_SIZE = 64;
    private static final int CUE_SIZE_64 = 128;
    private static final int NUM_CUES_64 = 4096;
    private static final int CUE_EMPTY = 0x7fff;
    private static final int CUE_SIZE_V1 = 32;
    private static final int NUM_VOICES_V1 = 20;
    private static final int CUE_EMPTY_V1 = 0xfff;

    private static final byte[] TAUD_MAGIC = { 0x1f, 0x54, 0x53, 0x56, 0x4d, 0x61, 0x75, 0x64 };
    private static final byte[] PROJ_MAGIC = { 0x1e, 0x54, 0x61, 0x75, 0x64, 0x50, 0x72, 0x4a };

    /** A parse failure, with the C library's error kinds. */
    static final class Failure extends Exception {
        final TaudException.Kind kind;
        Failure(TaudException.Kind kind) { super(kind.toString()); this.kind = kind; }
    }

    static final class Song {
        int numVoices, numPats, bpm, tickRate, tuningBaseNote;
        float tuningFreq;
        int globalFlags, globalVolume, mixingVolume, numCuesStored, surroundModel;
        byte[] patBin;          /* numPats × patSize */
        int patBinLen;          /* bytes actually decompressed */
        int patSize;
        int numCues;
        int[] cues;             /* numCues × MAX_VOICES words */
        boolean hasMastering;
        final Mastering.Params mastering = new Mastering.Params();
        String name;            /* sMet name (may be null) */
    }

    static final class IxmpEntry {
        int instId;
        int off, len;           /* into Doc.projData */
    }

    static final class Doc {
        int kind;               /* 0 taud, 1 tsii, 2 tpif */
        int fmtVer;
        boolean is64Channel;
        boolean wideCells;
        /** The sample+instrument image (null if absent): the sample pool
         *  trimmed to samplePoolLen bytes, followed by the 256 KiB instrument
         *  bin. */
        byte[] sampleInstImage;
        int samplePoolLen;
        Song[] songs = new Song[0];
        byte[] projData;        /* copy of the Project-Data region */
        IxmpEntry[] ixmp = new IxmpEntry[0];
        String projectName;
        Imix.Block[] imix = new Imix.Block[0];
        LyricEntry[] lyrics = new LyricEntry[0];
    }

    private static int rdU16(byte[] b, int o) { return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8); }
    private static long rdU32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    private static boolean eq(byte[] a, int ao, byte[] b, int bo, int n) {
        for (int i = 0; i < n; i++) if (a[ao + i] != b[bo + i]) return false;
        return true;
    }

    /** A NUL-terminated string from b[o..end), as UTF-8. */
    private static String strNul(byte[] b, int o, int end) {
        int s = o;
        while (o < end && b[o] != 0) o++;
        return new String(b, s, o - s, StandardCharsets.UTF_8);
    }

    private static int ixmpPatchLen(byte[] b, int o, int len) {
        int ver = b[o] & 0xff;
        int base = 31 + ((ver & 0x80) != 0 ? 15 : 0) + ((ver & 0x02) != 0 ? 54 : 0) + ((ver & 0x04) != 0 ? 54 : 0) +
                   ((ver & 0x08) != 0 ? 54 : 0) + ((ver & 0x10) != 0 ? 54 : 0);
        if ((ver & 0x20) == 0) return base;
        int chanByte = o + base < len ? b[o + base] & 0xff : 0;
        return base + 4 + 4 * (chanByte >> 4);
    }

    /** _v1CueToV2: 20 lanes × 12-bit patterns in nibble planes + an instruction word. */
    private static void v1CueToWords(byte[] b, int at, int[] out, int outOff) {
        int word0 = ((b[at + 30] & 0xff) << 8) | (b[at + 31] & 0xff);
        for (int ch = 0; ch < MAX_VOICES; ch++) out[outOff + ch] = CUE_EMPTY;
        for (int ch = 0; ch < NUM_VOICES; ch++) {
            int pat = CUE_EMPTY;
            if (ch < NUM_VOICES_V1) {
                int bi = ch >> 1;
                int lo = (ch & 1) != 0 ? (b[at + bi] & 0xf) : ((b[at + bi] >> 4) & 0xf);
                int mi = (ch & 1) != 0 ? (b[at + 10 + bi] & 0xf) : ((b[at + 10 + bi] >> 4) & 0xf);
                int hi = (ch & 1) != 0 ? (b[at + 20 + bi] & 0xf) : ((b[at + 20 + bi] >> 4) & 0xf);
                int p12 = (hi << 8) | (mi << 4) | lo;
                pat = p12 == CUE_EMPTY_V1 ? CUE_EMPTY : p12;
            }
            int val = pat & 0x7fff;
            if (ch < 16 && ((word0 >> ch) & 1) != 0) val |= 0x8000;
            out[outOff + ch] = val;
        }
    }

    /* The `ILyr` section (§9.17, lyric-section.js parseLyricSection): entries
     * back to back, {U16 instrument (bits 0…9), U32 L, then L bytes: U16 S,
     * U8 R, U8 0, R × 12 recipes, S × 12 syllables, the editor's tail
     * (ignored)}. An entry with no syllable or shorter than its counts is
     * dropped, and so is a second entry for an instrument; a truncated one
     * ends the walk. */
    private static LyricEntry[] parseLyrics(byte[] pl, int base, int len) {
        java.util.ArrayList<LyricEntry> list = new java.util.ArrayList<>();
        boolean[] seen = new boolean[1024];
        long o = 0;
        while (len - o >= 6) {
            int instId = rdU16(pl, base + (int) o) & 0x3ff;
            long L = rdU32(pl, base + (int) o + 2), start = o + 6;
            int b = base + (int) start;
            if (L > len - start) break;
            o = start + L;
            if (L < 4) continue;
            int S = rdU16(pl, b);
            int R = pl[b + 2] & 0xff;
            long fixedEnd = 4 + (long) R * LYRIC_RECIPE_BYTES + (long) S * LYRIC_SYLLABLE_BYTES;
            if (S == 0 || fixedEnd > L || seen[instId]) continue;
            seen[instId] = true;
            LyricEntry e = new LyricEntry();
            e.instId = instId;
            e.numSyllables = S;
            e.numRecipes = R;
            e.syllables = new LyricEntry.Syllable[S];
            e.recipes = new LyricEntry.Recipe[R];
            java.util.Arrays.fill(e.recipeOf, -1);
            for (int k = 0; k < R; k++) {
                int r = b + 4 + k * LYRIC_RECIPE_BYTES;
                LyricEntry.Recipe rc = new LyricEntry.Recipe();
                rc.cons = pl[r] & 0xff;
                rc.ptr = (pl[r + 1] & 0xffL) | ((pl[r + 2] & 0xffL) << 8) | ((pl[r + 3] & 0xffL) << 16);
                rc.len = rdU16(pl, r + 4);
                rc.offset = rdU16(pl, r + 6);
                rc.rate = rdU16(pl, r + 8);
                int t = pl[r + 10] & 0xff;
                rc.tilt = t >= 0x80 ? t - 0x100 : t;
                rc.gain = pl[r + 11] & 0xff;
                e.recipes[k] = rc;
                if (rc.cons != 0 && e.recipeOf[rc.cons] < 0) e.recipeOf[rc.cons] = k;
            }
            for (int k = 0; k < S; k++) {
                int y = b + 4 + R * LYRIC_RECIPE_BYTES + k * LYRIC_SYLLABLE_BYTES;
                LyricEntry.Syllable sy = new LyricEntry.Syllable();
                sy.front = pl[y] & 0xff;
                sy.open = pl[y + 1] & 0xff;
                sy.round = pl[y + 2] & 0xff;
                sy.vowel = (pl[y + 3] & 0x01) != 0;
                sy.nasal = (pl[y + 3] & 0x02) != 0;
                sy.gr = pl[y + 4] & 0xff;
                for (int c = 0; c < 3; c++) sy.cons[c] = pl[y + 5 + c] & 0xff;
                for (int c = 0; c < 4; c++) sy.timing[c] = pl[y + 8 + c] & 0xff;
                e.syllables[k] = sy;
            }
            list.add(e);
        }
        return list.toArray(new LyricEntry[0]);
    }

    private static final class Section {
        final byte[] fourcc = new byte[4];
        int off, len;
    }

    private static Section findSec(java.util.List<Section> secs, String fc) {
        for (Section s : secs) {
            if (s.fourcc[0] == fc.charAt(0) && s.fourcc[1] == fc.charAt(1) &&
                s.fourcc[2] == fc.charAt(2) && s.fourcc[3] == fc.charAt(3)) return s;
        }
        return null;
    }

    static Doc parse(byte[] file) throws Failure {
        int len = file.length;
        Doc doc = new Doc();
        java.util.ArrayList<Section> secs = new java.util.ArrayList<>();
        if (len < TAUD_HEADER_SIZE) throw new Failure(TaudException.Kind.FORMAT);
        if (!eq(file, 0, TAUD_MAGIC, 0, 8)) throw new Failure(TaudException.Kind.FORMAT);
        int version = file[8] & 0xff;
        int numSongs = file[9] & 0xff;
        long compSize = rdU32(file, 10);
        long projOff = rdU32(file, 14);

        int kindBits = version & TAUD_KIND_MASK;
        if (kindBits == TAUD_KIND_SAMPLEINST) doc.kind = 1;
        else if (kindBits == TAUD_KIND_PATTERN) doc.kind = 2;
        else if (kindBits == TAUD_KIND_FULL) doc.kind = 0;
        else throw new Failure(TaudException.Kind.FORMAT);
        doc.fmtVer = version & TAUD_VERSION_MASK;
        doc.wideCells = doc.fmtVer >= 3;

        /* ── Project Data sections (walked first: xHDR changes the cue stride) ── */
        if (projOff != 0 && projOff <= len && len - projOff >= 16 && eq(file, (int) projOff, PROJ_MAGIC, 0, 8)) {
            long p = projOff + 16;
            doc.projData = java.util.Arrays.copyOfRange(file, (int) projOff, len);
            while (len - p >= 8) {
                long secLen = rdU32(file, (int) p + 4);
                long payload = p + 8;
                if (secLen > len - payload) break;
                Section s = new Section();
                System.arraycopy(file, (int) p, s.fourcc, 0, 4);
                s.off = (int) (payload - projOff);   /* offset into projData */
                s.len = (int) secLen;
                secs.add(s);
                p = payload + secLen;
            }
        }
        Section sec = findSec(secs, "xHDR");
        doc.is64Channel = (version & TAUD_XHDR_FLAG) != 0 && sec != null && sec.len >= 1 &&
            (doc.projData[sec.off] & 0x01) != 0;

        /* ── sample+instrument image ── */
        if (doc.kind != 2 && compSize > 0) {
            if (compSize > len - TAUD_HEADER_SIZE) throw new Failure(TaudException.Kind.FORMAT);
            Decompress.Result img = Decompress.decompress(file, TAUD_HEADER_SIZE, (int) compSize, SAMPLEINST_SIZE);
            if (img == null) throw new Failure(TaudException.Kind.DECOMPRESS);
            /* Trim the pool to its last non-zero byte and slide the instrument
             * bin down behind it; reads past the trimmed end are 0, as before. */
            int used = SAMPLE_BIN_TOTAL;
            byte[] d = img.data;
            while (used > 0 && d[used - 1] == 0) used--;
            byte[] shrunk = new byte[used + INSTBIN_SIZE];
            System.arraycopy(d, 0, shrunk, 0, used);
            System.arraycopy(d, SAMPLE_BIN_TOTAL, shrunk, used, INSTBIN_SIZE);
            doc.sampleInstImage = shrunk;
            doc.samplePoolLen = used;
        }

        /* ── song table + per-song pattern/cue bins ── */
        if (doc.kind != 1 && numSongs > 0) {
            long tableOff = TAUD_HEADER_SIZE + compSize;
            if (compSize > len - TAUD_HEADER_SIZE) throw new Failure(TaudException.Kind.FORMAT);
            doc.songs = new Song[numSongs];
            for (int i = 0; i < numSongs; i++) {
                Song s = new Song();
                doc.songs[i] = s;
                long eo = tableOff + (long) i * TAUD_SONG_ENTRY;
                if (eo > len || len - eo < TAUD_SONG_ENTRY) throw new Failure(TaudException.Kind.FORMAT);
                int en = (int) eo;
                long songOffset = rdU32(file, en);
                s.numVoices = file[en + 4] & 0xff;
                s.numPats = rdU16(file, en + 5);
                int bpmStored = file[en + 7] & 0xff;
                int tickPacked = file[en + 8] & 0xff;
                s.tickRate = tickPacked & 0x7f;
                bpmStored |= (tickPacked & 0x80) << 1;
                bpmStored |= (file[en + 28] & 0x80) << 2;      /* song flags bit 7 = BPM bit 9 */
                s.bpm = bpmStored + 25;
                s.tuningBaseNote = rdU16(file, en + 9);
                s.tuningFreq = Mastering.rdF32(file, en + 11);
                s.globalFlags = file[en + 15] & 0xff;
                s.globalVolume = file[en + 16] & 0xff;
                s.mixingVolume = file[en + 17] & 0xff;
                long patComp = rdU32(file, en + 18);
                long cueComp = rdU32(file, en + 22);
                s.numCuesStored = rdU16(file, en + 26);
                s.surroundModel = file[en + 28] & 3;

                s.patSize = doc.fmtVer >= 3 ? PATTERN_BYTES_WIDE : PATTERN_BYTES;
                if (songOffset > len || patComp > len - songOffset || cueComp > len - songOffset - patComp) {
                    throw new Failure(TaudException.Kind.FORMAT);
                }
                if (s.numPats > 0) {
                    Decompress.Result pr = Decompress.decompress(file, (int) songOffset, (int) patComp, s.numPats * s.patSize);
                    if (pr == null) throw new Failure(TaudException.Kind.DECOMPRESS);
                    s.patBin = pr.data;
                    s.patBinLen = pr.len;
                }

                Decompress.Result cr = Decompress.decompress(file, (int) (songOffset + patComp), (int) cueComp, 0);
                if (cr == null) throw new Failure(TaudException.Kind.DECOMPRESS);
                byte[] cueBin = cr.data;
                int cueLen = cr.len;
                if (doc.fmtVer >= 2) {
                    int stride = doc.is64Channel ? CUE_SIZE_64 : CUE_SIZE;
                    int chans = doc.is64Channel ? MAX_VOICES : NUM_VOICES;
                    int maxCues = doc.is64Channel ? NUM_CUES_64 : NUM_CUES;
                    int numCues = cueLen / stride;
                    if (numCues > maxCues) numCues = maxCues;
                    s.numCues = numCues;
                    s.cues = new int[MAX_VOICES * Math.max(numCues, 1)];
                    for (int c = 0; c < numCues; c++) {
                        int w = c * MAX_VOICES;
                        for (int ch = 0; ch < MAX_VOICES; ch++) s.cues[w + ch] = CUE_EMPTY;
                        for (int ch = 0; ch < chans; ch++) s.cues[w + ch] = rdU16(cueBin, c * stride + ch * 2);
                    }
                } else {
                    int numCues = cueLen / CUE_SIZE_V1;
                    s.numCues = numCues;
                    s.cues = new int[MAX_VOICES * Math.max(numCues, 1)];
                    for (int c = 0; c < numCues; c++) v1CueToWords(cueBin, c * CUE_SIZE_V1, s.cues, c * MAX_VOICES);
                }
            }
        }

        /* ── decoded views: Ixmp, PNam, sMet, sMst, IMix, ILyr ── */
        sec = findSec(secs, "Ixmp");
        if (sec != null) {
            byte[] pl = doc.projData;
            int base = sec.off;
            int q = 0, qEnd = sec.len;
            java.util.ArrayList<IxmpEntry> list = new java.util.ArrayList<>();
            while (q + 4 <= qEnd) {
                int instId = (pl[base + q] & 0xff) | ((pl[base + q + 3] & 0x03) << 8);
                int patchCnt = (pl[base + q + 1] & 0xff) | ((pl[base + q + 2] & 0xff) << 8);
                int blobLen = 0;
                boolean ok = true;
                q += 4;
                int scan = q;
                for (int k = 0; k < patchCnt; k++) {
                    if (scan + 31 > qEnd) { ok = false; break; }
                    int plen = ixmpPatchLen(pl, base + scan, base + qEnd);
                    if (scan + plen > qEnd) { ok = false; break; }
                    scan += plen;
                    blobLen += plen;
                }
                if (!ok) break;
                IxmpEntry ie = new IxmpEntry();
                ie.instId = instId;
                ie.off = base + q;
                ie.len = blobLen;
                list.add(ie);
                q += blobLen;
            }
            doc.ixmp = list.toArray(new IxmpEntry[0]);
        }
        sec = findSec(secs, "PNam");
        if (sec != null) doc.projectName = strNul(doc.projData, sec.off, sec.off + sec.len);
        sec = findSec(secs, "sMet");
        if (sec != null) {
            byte[] pl = doc.projData;
            int base = sec.off;
            long p = 0;
            while (sec.len - p >= 5) {
                int songIndex = pl[base + (int) p] & 0xff;
                long subLen = rdU32(pl, base + (int) p + 1);
                long sub = p + 5;
                if (subLen > sec.len - sub) break;
                if (songIndex < doc.songs.length && subLen >= 4) {
                    doc.songs[songIndex].name = strNul(pl, base + (int) sub + 4, base + (int) (sub + subLen));
                }
                p = sub + subLen;
            }
        }
        sec = findSec(secs, "sMst");
        if (sec != null) {
            byte[] pl = doc.projData;
            int base = sec.off;
            long p = 0;
            while (sec.len - p >= 5) {
                int songIndex = pl[base + (int) p] & 0xff;
                long subLen = rdU32(pl, base + (int) p + 1);
                long sub = p + 5;
                if (subLen > sec.len - sub) break;
                Mastering.Params m = new Mastering.Params();
                if (Mastering.parseBlock(pl, base + (int) sub, (int) subLen, m) && songIndex < doc.songs.length) {
                    doc.songs[songIndex].mastering.copyFrom(m);
                    doc.songs[songIndex].hasMastering = true;
                }
                p = sub + subLen;
            }
        }
        /* The instruments' mixing chains (§9.15) — another section a player
         * has to honour. */
        sec = findSec(secs, "IMix");
        if (sec != null) doc.imix = Imix.parseSection(doc.projData, sec.off, sec.len);
        /* …and the lyric instruments' lyrics (§9.17), for the same reason. */
        sec = findSec(secs, "ILyr");
        if (sec != null) doc.lyrics = parseLyrics(doc.projData, sec.off, sec.len);
        return doc;
    }

    /** Load a parsed song into the engine — the JVM oracle's upload order. */
    static void loadIntoEngine(Engine e, Doc doc, int songIndex) {
        Song song = doc.songs[songIndex];
        int chans = doc.is64Channel ? MAX_VOICES : NUM_VOICES;
        byte[] cueBytes = new byte[MAX_VOICES * 2];

        e.is64ChannelMode = doc.is64Channel;
        e.setCellFormat(doc.wideCells);
        if (doc.sampleInstImage != null) {
            e.uploadSampleInstBlob(doc.sampleInstImage, doc.samplePoolLen, doc.sampleInstImage, doc.samplePoolLen);
        }

        /* A short pattern bin uploads short patterns, as the JS subarray does. */
        for (int p = 0; p < song.numPats; p++) {
            int off = p * song.patSize, n = 0;
            if (off < song.patBinLen) {
                n = song.patBinLen - off;
                if (n > song.patSize) n = song.patSize;
            }
            e.uploadPattern(p, song.patBin, off, n);
        }

        for (int c = 0; c < song.numCues; c++) {
            int w = c * MAX_VOICES;
            for (int ch = 0; ch < chans; ch++) {
                cueBytes[ch * 2] = (byte) (song.cues[w + ch] & 0xff);
                cueBytes[ch * 2 + 1] = (byte) ((song.cues[w + ch] >> 8) & 0xff);
            }
            e.uploadCue(c, cueBytes, 0, chans * 2);
        }

        e.bpm = iclamp(song.bpm, BPM_MIN, BPM_MAX);
        e.tickRate = (song.tickRate > 0 ? song.tickRate : 6) & 255;
        e.setTuning(song.tuningBaseNote, song.tuningFreq);
        e.setTrackerMixerFlags(song.globalFlags);
        e.setSurroundModel(song.surroundModel);
        e.globalVolume = song.globalVolume & 255;
        e.mixingVolume = song.mixingVolume & 255;
        e.masterVolume = 255;
        e.setMastering(song.hasMastering ? song.mastering : null);
        /* …and the instruments' mixing chains (item 217), for the same reason. */
        Imix.setImix(e, doc.imix);
        /* …and the lyric instruments' lyrics: which syllable a note sings. */
        e.setLyrics(doc.lyrics, doc.lyrics.length);

        for (IxmpEntry ie : doc.ixmp) {
            e.uploadInstrumentPatches(ie.instId, doc.projData, ie.off, ie.len);
        }
    }
}
