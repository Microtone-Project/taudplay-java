/*
 * taudplay-java — a portable, embeddable .taud replayer for the JVM.
 *
 * The Java twin of the `taudplay` JavaScript library (the playback half of
 * the Microtone tracker) and of taudplay-c: the same engine, note for note,
 * with the same small surface — a transport, one fader per lane, two probes
 * per lane, and the sixteen interrupts a song can fire at the program playing
 * it. Port of taudplay/render.js (TaudRenderer), faders.js and interrupts.js
 * by way of taudplay-c player.c.
 *
 *     TaudPlayer p = new TaudPlayer(bytes);
 *     p.play();
 *     float[] block;
 *     while ((block = p.renderChunk()) != null) {
 *         // CHUNK frames of interleaved stereo float
 *     }
 *
 * Pure Java, no dependencies, no global state: any number of players may run
 * side by side, each on its own thread. One player must not be called from
 * two threads at once.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

public final class TaudPlayer {
    /** The library's version. */
    public static final String VERSION = "4.7.4";
    /** Frames per rendered block. Tick and row timing are per SAMPLE, so this
     *  is pure batching granularity and never changes what a song sounds like. */
    public static final int CHUNK = C.TAUD_CHUNK;
    /** The engine's reference output rate; a render at this rate is bit for
     *  bit what the JavaScript library renders. */
    public static final int DEFAULT_RATE = C.TAUD_DEFAULT_RATE;
    /** Lanes a song can have (32 or 64). */
    public static final int MAX_VOICES = C.MAX_VOICES;
    /** Interrupts a song can fire. */
    public static final int NUM_INTERRUPTS = C.NUM_INTERRUPTS;

    /* ── the fader bank (faders.js) ── */

    private static final class FaderBank {
        final double[] now = new double[MAX_VOICES];     /* current attenuation, 0..255 */
        final double[] target = new double[MAX_VOICES];
        final double[] step = new double[MAX_VOICES];    /* per frame */
        final double[] remain = new double[MAX_VOICES];  /* frames left in the ramp */
        boolean dirty;

        void set(int v, double value, double samples) {
            if (v < 0 || v >= MAX_VOICES) return;
            double t = value < 0 ? 0 : value > 255 ? 255 : value;
            target[v] = t;
            if (samples <= 0) {
                now[v] = t;
                remain[v] = 0;
                step[v] = 0;
            } else {
                step[v] = (t - now[v]) / samples;
                remain[v] = samples;
            }
            dirty = true;
        }

        void advance(int frames) {
            for (int v = 0; v < MAX_VOICES; v++) {
                double left = remain[v];
                if (left <= 0) continue;
                if (left <= frames) {
                    now[v] = target[v];
                    remain[v] = 0;
                } else {
                    now[v] += step[v] * frames;
                    remain[v] = left - frames;
                }
                dirty = true;
            }
        }

        void writeInto(Engine e) {
            if (!dirty) return;
            dirty = false;
            for (int v = 0; v < MAX_VOICES; v++) {
                int b = (int) JsMath.round(now[v]) & 255;
                if (e.voices[v].fader != b) e.voices[v].fader = b;
            }
        }
    }

    /** Gain (0…1) → the engine's 0…255 attenuation byte. */
    public static int gainToFader(double gain) {
        double g = gain < 0 ? 0 : gain > 1 ? 1 : gain;
        return (int) JsMath.round((1 - g) * 255);
    }

    /** The engine's 0…255 attenuation byte → gain (0…1). */
    public static double faderToGain(int fader) { return (255 - fader) / 255.0; }

    /* ── the player ── */

    private final Parse.Doc doc;
    private final Engine engine;
    private final FaderBank faders = new FaderBank();
    private int monitor;
    private int songIndex = -1;
    private final String[] songNames;
    private final TaudInterruptListener[] interrupts = new TaudInterruptListener[NUM_INTERRUPTS];
    private final float[] out = new float[CHUNK * 2];
    private final byte[] outU8 = new byte[CHUNK * 2];
    private int pendingPos;     /* frames of `out` already handed to render() */
    private int pendingLen;

    /** Parse a full .taud file and load its first song at 48 kHz. The bytes
     *  are only read during the call. */
    public TaudPlayer(byte[] bytes) throws TaudException {
        this(bytes, null);
    }

    /** Parse a full .taud file and load a song. The bytes are only read
     *  during the call; `cfg` may be null. */
    public TaudPlayer(byte[] bytes, TaudConfig cfg) throws TaudException {
        if (cfg == null) cfg = new TaudConfig();
        int rate = cfg.sampleRate <= 0 ? DEFAULT_RATE : cfg.sampleRate;
        try {
            doc = Parse.parse(bytes);
        } catch (Parse.Failure f) {
            throw new TaudException(f.kind);
        } catch (RuntimeException ex) {
            /* A file the bounds checks let through but the engine cannot hold. */
            throw new TaudException(TaudException.Kind.FORMAT);
        }
        if (doc.kind != 0 || doc.songs.length == 0) throw new TaudException(TaudException.Kind.NOT_A_SONG);
        songNames = new String[doc.songs.length];
        engine = new Engine(rate);
        for (int i = 0; i < doc.songs.length; i++) {
            String nm = doc.songs[i].name;
            songNames[i] = nm != null && !nm.isEmpty() ? nm : "song " + i;
        }
        engine.random = cfg.random;
        engine.mulberry = cfg.randomSeed;
        monitor = cfg.binaural ? C.MONITOR_BINAURAL : C.MONITOR_FOLD;
        faders.dirty = true;
        selectSong(cfg.songIndex);
    }

    /** The file's project name, or null when it has none. */
    public String getTitle() { return doc.projectName; }
    public int getSongCount() { return doc.songs.length; }
    public int getCurrentSong() { return songIndex; }
    public int getSampleRate() { return engine.rate; }

    public TaudSongInfo getSongInfo(int index) {
        if (index < 0 || index >= doc.songs.length) return null;
        Parse.Song s = doc.songs[index];
        return new TaudSongInfo(index, songNames[index], s.numPats, s.bpm, s.numVoices, s.surroundModel != 0);
    }

    /** Load another song of the file (stopped; play() starts it). */
    public void selectSong(int index) throws TaudException {
        Engine e = engine;
        if (index < 0 || index >= doc.songs.length) throw new TaudException(TaudException.Kind.RANGE);
        songIndex = index;
        e.stop();
        Parse.loadIntoEngine(e, doc, index);
        e.setMonitorMode(monitor);
        faders.dirty = true;
        faders.writeInto(e);
        pendingPos = pendingLen = 0;
    }

    /* ── transport ── */

    /** Play the song from the top. */
    public void play() {
        Engine e = engine;
        e.resetSampleFxState();
        e.setCuePosition(0);
        e.setTrackerRow(0);
        e.play();
        pendingPos = pendingLen = 0;
    }

    public void stop() {
        engine.stop();
        pendingPos = pendingLen = 0;
    }

    public void seekCue(int cue) {
        engine.setCuePosition(cue < 0 ? 0 : cue);
        engine.setTrackerRow(0);
        pendingPos = pendingLen = 0;
    }

    /** Master volume, 0…1. */
    public void setVolume(double gain) {
        double g = gain < 0 ? 0 : gain > 1 ? 1 : gain;
        engine.masterVolume = (int) JsMath.round(g * 255) & 255;
    }

    /** The binaural monitor (surround songs only). */
    public void setBinaural(boolean on) {
        monitor = on ? C.MONITOR_BINAURAL : C.MONITOR_FOLD;
        engine.setMonitorMode(monitor);
    }

    public boolean isPlaying() { return engine.isPlaying; }
    public int getCue() { return engine.cuePos; }
    public int getRow() { return engine.rowIndex; }
    public int getBpm() { return engine.bpm; }
    public int getSpeed() { return engine.tickRate; }
    /** The song's lane count: 32 or 64. */
    public int getChannelCount() { return engine.channelCount(); }

    /* ── the knob: one fader per lane ── */

    /** Lane `v` to `gain` (1 = as written, 0 = silent), faded there over
     *  `fadeSeconds` of RENDERED time. */
    public void setVoiceGain(int v, double gain, double fadeSeconds) {
        double samples = JsMath.round(fadeSeconds * engine.rate);
        faders.set(v, gainToFader(gain), samples > 0 ? samples : 0);
        faders.writeInto(engine);
    }

    public double getVoiceGain(int v) {
        if (v < 0 || v >= MAX_VOICES) return 1.0;
        return faderToGain((int) JsMath.round(faders.now[v]));
    }

    /* ── the probes: two per lane ── */

    /** How loud lane `v` is, 0…1, at the end of the last rendered block. */
    public double getVoiceVolume(int v) {
        if (v < 0 || v >= MAX_VOICES) return 0;
        Voice voice = engine.voices[v];
        if (!voice.active) return 0;
        /* render.js's own reading, which (unlike the engine's internal probe)
         * does not consult the envelope's P bit. */
        double effEnvVol = voice.volEnvOn ? voice.envVolMix : 1.0;
        double faderGain = (255 - voice.fader) / 255.0;
        double ev = effEnvVol * voice.fadeoutVolume * voice.currentMixVolume * faderGain;
        return ev < 0 ? 0 : ev > 1 ? 1 : ev;
    }

    /** Where lane `v` sits: 0 (left) … 0.5 … 1 (right). */
    public double getVoicePan(int v) {
        if (v < 0 || v >= MAX_VOICES) return 0.5;
        Voice voice = engine.voices[v];
        if (!voice.active) return 0.5;
        return Spatial.displayPanByte(engine, v, voice) / 255.0;
    }

    /* ── interrupts: the song calling out ── */

    /** Register (listener != null) or unregister interrupt `n` (0…15).
     *  Handlers run on the rendering thread, once per block the interrupt
     *  fired in — edge-triggered, lowest number first. */
    public void setInterrupt(int n, TaudInterruptListener listener) {
        if (n < 0 || n >= NUM_INTERRUPTS) return;
        interrupts[n] = listener;
    }

    public void clearInterrupts() { java.util.Arrays.fill(interrupts, null); }

    private void dispatchInterrupts() {
        int mask = engine.drainInterrupts();
        if (mask == 0) return;
        for (int n = 0; n < NUM_INTERRUPTS; n++) {
            if ((mask & (1 << n)) == 0 || interrupts[n] == null) continue;
            interrupts[n].onInterrupt(n, engine.interruptArgs[n]);
        }
    }

    /* ── rendering ── */

    private boolean renderBlock() {
        Engine e = engine;
        if (!e.isPlaying) return false;
        faders.advance(CHUNK);
        faders.writeInto(e);
        if (!e.renderChunk()) return false;
        dispatchInterrupts();
        for (int n = 0; n < CHUNK; n++) {
            out[n * 2] = e.mixLeft[n];
            out[n * 2 + 1] = e.mixRight[n];
        }
        return true;
    }

    /** Render one block: CHUNK frames of interleaved stereo float, valid until
     *  the next call, or null once the song has halted. */
    public float[] renderChunk() {
        pendingPos = pendingLen = 0;
        return renderBlock() ? out : null;
    }

    /** Render one block as the Taud device does: noise-shaped, dithered
     *  unsigned 8-bit, interleaved stereo (2 × CHUNK bytes). Null once halted. */
    public byte[] renderChunkU8() {
        pendingPos = pendingLen = 0;
        if (!renderBlock()) return null;
        Mixer.pcm32fToPcm8(engine, outU8);
        return outU8;
    }

    /** Pull exactly `frames` frames of interleaved stereo float into
     *  out[offset…], buffering across block boundaries — the shape an audio
     *  callback wants. Frames past the end of the song are silence. Returns
     *  the frames that were real song (fewer than `frames` once it halted). */
    public int render(float[] dst, int offset, int frames) {
        int done = 0, real = 0;
        while (done < frames) {
            if (pendingPos >= pendingLen) {
                pendingPos = pendingLen = 0;
                if (!renderBlock()) break;
                pendingLen = CHUNK;
            }
            int take = pendingLen - pendingPos;
            if (take > frames - done) take = frames - done;
            System.arraycopy(out, pendingPos * 2, dst, offset + done * 2, take * 2);
            pendingPos += take;
            done += take;
            real += take;
        }
        if (done < frames) java.util.Arrays.fill(dst, offset + done * 2, offset + frames * 2, 0f);
        return real;
    }

    /** As {@link #render(float[], int, int)}, into two planar buffers. */
    public int render(float[] left, float[] right, int offset, int frames) {
        int done = 0, real = 0;
        while (done < frames) {
            if (pendingPos >= pendingLen) {
                pendingPos = pendingLen = 0;
                if (!renderBlock()) break;
                pendingLen = CHUNK;
            }
            int take = pendingLen - pendingPos;
            if (take > frames - done) take = frames - done;
            for (int k = 0; k < take; k++) {
                left[offset + done + k] = out[(pendingPos + k) * 2];
                right[offset + done + k] = out[(pendingPos + k) * 2 + 1];
            }
            pendingPos += take;
            done += take;
            real += take;
        }
        if (done < frames) {
            java.util.Arrays.fill(left, offset + done, offset + frames, 0f);
            java.util.Arrays.fill(right, offset + done, offset + frames, 0f);
        }
        return real;
    }

    private static short toS16(float v) {
        double d = v < -1.0f ? -1.0 : v > 1.0f ? 1.0 : (double) v;
        return (short) JsMath.round(d * 32767);
    }

    private final float[] s16tmp = new float[256 * 2];

    /** Pull `frames` frames as interleaved stereo signed 16-bit, clamped and
     *  rounded (round(x × 32767), no dither). Returns as render(). */
    public int renderS16(short[] dst, int offset, int frames) {
        int done = 0, real = 0;
        while (done < frames) {
            int n = frames - done;
            if (n > 256) n = 256;
            int got = render(s16tmp, 0, n);
            for (int k = 0; k < n * 2; k++) dst[offset + done * 2 + k] = toS16(s16tmp[k]);
            real += got;
            done += n;
        }
        return real;
    }
}
