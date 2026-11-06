/*
 * taudplay — command-line front end: list a file's songs, bounce one to WAV
 * or raw float, and print the interrupts it fires (taudplay-c
 * tools/taudplay.c).
 *
 *   java -jar taudplay.jar song.taud                     describe the file
 *   java -jar taudplay.jar song.taud -o out.wav          render song 0 to a 48 kHz WAV
 *   java -jar taudplay.jar song.taud -s 2 -t 60 -o x.wav song 2, at most 60 seconds
 *   java -jar taudplay.jar song.taud --gritty -o x.wav   the device's own 8-bit sound
 *   java -jar taudplay.jar song.taud --raw out.f32       interleaved stereo float32
 *   java -jar taudplay.jar song.taud --interrupts        print the cue sheet the song fires
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public final class Main {
    private Main() {}

    private static void usage() {
        System.err.print(
            "usage: taudplay FILE.taud [options]\n" +
            "  -o FILE.wav        render to a stereo WAV (- = stdout): clean, 16-bit\n" +
            "                     with a light TPDF dither\n" +
            "  --gritty           make the WAV 8-bit through the device's own\n" +
            "                     noise-shaped dither instead\n" +
            "  --raw FILE         stream interleaved stereo float32 little-endian at the\n" +
            "                     engine rate (- = stdout, e.g. for ffplay -f f32le)\n" +
            "  --s16              make --raw signed 16-bit little-endian instead\n" +
            "  -s N               song index (default 0)\n" +
            "  -t SECONDS         maximum length (default 600)\n" +
            "  -r HZ              engine rate (default 48000)\n" +
            "  --wav-rate HZ      resample the WAV to this rate (default: engine rate)\n" +
            "  --binaural         head-model monitoring for surround songs\n" +
            "  --mute V[,V...]    silence these lanes (0-based)\n" +
            "  --seed N           seed of the random source (default 1)\n" +
            "  --interrupts       print interrupts as they fire\n" +
            "  --bench            report the render speed\n");
    }

    /** Interleaved stereo as little-endian float32 or signed 16-bit. */
    private static void writeRaw(OutputStream os, float[] block, int frames, boolean s16, byte[] buf) throws IOException {
        int n = frames * 2;
        for (int i = 0; i < n; i++) {
            if (s16) {
                /* clamp and round exactly as the WAV writer (Math.round) */
                double d = (block[i] < -1.0f ? -1.0 : block[i] > 1.0f ? 1.0 : (double) block[i]) * 32767;
                double r = Math.floor(d);
                if (d - r >= 0.5) r += 1.0;
                int u = (int) ((long) r & 0xffff);
                buf[i * 2] = (byte) u;
                buf[i * 2 + 1] = (byte) (u >> 8);
            } else {
                int u = Float.floatToRawIntBits(block[i]);
                buf[i * 4] = (byte) u;
                buf[i * 4 + 1] = (byte) (u >> 8);
                buf[i * 4 + 2] = (byte) (u >> 16);
                buf[i * 4 + 3] = (byte) (u >> 24);
            }
        }
        os.write(buf, 0, n * (s16 ? 2 : 4));
    }

    public static void main(String[] argv) throws IOException {
        String in = null, wavOut = null, rawOut = null, mute = null;
        double seconds = 600;
        int wavRate = 0;
        boolean printInts = false, s16 = false, gritty = false, bench = false;
        TaudConfig cfg = new TaudConfig();

        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.equals("-o") && i + 1 < argv.length) wavOut = argv[++i];
            else if (a.equals("--raw") && i + 1 < argv.length) rawOut = argv[++i];
            else if (a.equals("-s") && i + 1 < argv.length) cfg.songIndex = Integer.parseInt(argv[++i]);
            else if (a.equals("-t") && i + 1 < argv.length) seconds = Double.parseDouble(argv[++i]);
            else if (a.equals("-r") && i + 1 < argv.length) cfg.sampleRate = Integer.parseInt(argv[++i]);
            else if (a.equals("--wav-rate") && i + 1 < argv.length) wavRate = Integer.parseInt(argv[++i]);
            else if (a.equals("--binaural")) cfg.binaural = true;
            else if (a.equals("--mute") && i + 1 < argv.length) mute = argv[++i];
            else if (a.equals("--seed") && i + 1 < argv.length) cfg.randomSeed = (int) Long.decode(argv[++i]).longValue();
            else if (a.equals("--interrupts")) printInts = true;
            else if (a.equals("--s16")) s16 = true;
            else if (a.equals("--gritty")) gritty = true;
            else if (a.equals("--bench")) bench = true;
            else if (a.equals("-h") || a.equals("--help")) { usage(); return; }
            else if (!a.startsWith("-") && in == null) in = a;
            else { usage(); System.exit(2); }
        }
        if (in == null) { usage(); System.exit(2); }
        if (rawOut != null && wavOut != null && rawOut.equals("-") && wavOut.equals("-")) {
            System.err.println("taudplay: only one of -o and --raw can write to stdout");
            System.exit(2);
        }
        /* When stdout carries audio, the interrupt log moves to stderr. */
        final PrintStream intOut = (rawOut != null && rawOut.equals("-")) || (wavOut != null && wavOut.equals("-"))
            ? System.err : System.out;

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Paths.get(in));
        } catch (IOException ex) {
            System.err.println("taudplay: cannot read " + in);
            System.exit(1);
            return;
        }
        TaudPlayer p;
        try {
            p = new TaudPlayer(bytes, cfg);
        } catch (TaudException ex) {
            System.err.println("taudplay: " + in + ": " + ex.getMessage());
            System.exit(1);
            return;
        }
        final int rate = p.getSampleRate();

        if (wavOut == null && rawOut == null && !printInts) {
            String title = p.getTitle();
            System.out.println(title != null ? title : "(untitled)");
            for (int i = 0; i < p.getSongCount(); i++) {
                TaudSongInfo s = p.getSongInfo(i);
                System.out.println(String.format(Locale.ROOT, "  [%d] %-32s %3d patterns  %3d bpm  %2d lanes%s",
                    s.index, s.name, s.patterns, s.bpm, s.channels, s.surround ? "  surround" : ""));
            }
            return;
        }

        if (mute != null) {
            for (String v : mute.split(",")) {
                if (v.isEmpty()) break;
                p.setVoiceGain(Integer.parseInt(v.trim()), 0.0, 0.0);
            }
        }
        final long[] frameNow = new long[1];
        if (printInts) {
            for (int n = 0; n < TaudPlayer.NUM_INTERRUPTS; n++) {
                p.setInterrupt(n, (k, arg) -> intOut.println(String.format(Locale.ROOT, "%10.4f s  Int%X  arg %d",
                    (double) frameNow[0] / rate, k, arg)));
            }
        }

        long maxFrames = (long) (seconds * rate + 0.5), frames = 0;
        float[] pcm = null;
        int status = 0;
        OutputStream raw = null;
        byte[] rawBuf = new byte[TaudPlayer.CHUNK * 2 * 4];
        if (rawOut != null) {
            raw = rawOut.equals("-") ? new BufferedOutputStream(System.out, 1 << 16)
                                     : new BufferedOutputStream(new FileOutputStream(rawOut), 1 << 16);
        }
        if (wavOut != null) pcm = new float[rate * 16 * 2];
        long t0 = System.nanoTime();
        p.play();
        float[] block;
        while (frames < maxFrames && (block = p.renderChunk()) != null) {
            frameNow[0] = frames + TaudPlayer.CHUNK;
            if (raw != null) {
                try {
                    writeRaw(raw, block, TaudPlayer.CHUNK, s16, rawBuf);
                } catch (IOException ex) {
                    if (!rawOut.equals("-")) { System.err.println("taudplay: cannot write " + rawOut); status = 1; }
                    break;
                }
            }
            if (pcm != null) {
                if ((frames + TaudPlayer.CHUNK) * 2 > pcm.length) pcm = java.util.Arrays.copyOf(pcm, pcm.length * 2);
                System.arraycopy(block, 0, pcm, (int) frames * 2, TaudPlayer.CHUNK * 2);
            }
            frames += TaudPlayer.CHUNK;
        }
        long t1 = System.nanoTime();
        if (raw != null) raw.close();
        if (wavOut != null && status == 0) {
            byte[] wav = TaudWav.encode(pcm, (int) frames, rate, wavRate > 0 ? wavRate : rate,
                                        gritty ? TaudWav.Quality.GRITTY : TaudWav.Quality.CLEAN);
            if (wavOut.equals("-")) { System.out.write(wav); System.out.flush(); }
            else try (OutputStream f = new FileOutputStream(wavOut)) { f.write(wav); }
        }
        System.err.println(String.format(Locale.ROOT, "taudplay: rendered %.2f s%s", (double) frames / rate,
            p.isPlaying() ? "" : " (song halted)"));
        if (bench) {
            double secs = (t1 - t0) / 1e9;
            System.err.println(String.format(Locale.ROOT, "taudplay: %.2f s of audio in %.2f s (%.1f× real time)",
                (double) frames / rate, secs, ((double) frames / rate) / secs));
        }
        if (status != 0) System.exit(status);
    }
}
