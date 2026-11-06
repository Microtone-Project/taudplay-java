/* The API scenario of tests/scenario_ref.mjs, through the Java API
 * (taudplay-c tests/scenario.c).
 *   java ... Scenario FILE RATE BINAURAL OUTPREFIX U8(0|1) */
package net.torvald.taudplay;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class Scenario {
    private static PrintWriter log;
    private static OutputStream audio;
    private static int block;
    private static boolean useU8;
    private static TaudPlayer r;

    private static void hexd(double x) { log.print(String.format(" %016x", Double.doubleToRawLongBits(x))); }

    private static void probes(String tag) {
        log.print(tag + " " + (r.isPlaying() ? 1 : 0) + " " + r.getCue() + " " + r.getRow() + " " + r.getBpm() + " " +
                  r.getSpeed() + " " + r.getChannelCount());
        for (int v = 0; v < 64; v++) { hexd(r.getVoiceVolume(v)); hexd(r.getVoicePan(v)); hexd(r.getVoiceGain(v)); }
        log.print("\n");
    }

    private static void render(int n) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(TaudPlayer.CHUNK * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            if (useU8) {
                byte[] b = r.renderChunkU8();
                block++;
                if (b == null) { log.print("null @" + block + "\n"); return; }
                audio.write(b);
            } else {
                float[] b = r.renderChunk();
                block++;
                if (b == null) { log.print("null @" + block + "\n"); return; }
                bb.clear();
                for (float x : b) bb.putFloat(x);
                audio.write(bb.array());
            }
        }
    }

    public static void main(String[] argv) throws Exception {
        if (argv.length < 5) System.exit(2);
        byte[] bytes = Files.readAllBytes(Paths.get(argv[0]));
        TaudConfig cfg = new TaudConfig();
        cfg.sampleRate = Integer.parseInt(argv[1]);
        cfg.binaural = Integer.parseInt(argv[2]) != 0;
        useU8 = Integer.parseInt(argv[4]) != 0;
        r = new TaudPlayer(bytes, cfg);
        log = new PrintWriter(argv[3] + ".log", "UTF-8");
        audio = new java.io.BufferedOutputStream(new FileOutputStream(argv[3] + (useU8 ? ".u8" : ".f32")));
        for (int n = 0; n < 16; n++) r.setInterrupt(n, (k, arg) -> log.print("int " + k + " " + arg + " @" + block + "\n"));
        r.play();
        render(100); probes("a");
        r.setVoiceGain(1, 0.25, 0.5);
        r.setVoiceGain(3, 0.0, 0);
        render(150); probes("b");
        r.setVoiceGain(1, 1.0, 1.0);
        r.setVolume(0.7);
        render(150); probes("c");
        r.seekCue(1);
        render(200); probes("d");
        if (r.getSongCount() > 1) {
            r.selectSong(1); r.play(); render(200); probes("e"); r.selectSong(0);
        }
        r.stop(); render(3); probes("f");
        r.play(); render(300); probes("g");
        r.setVoiceGain(0, 0.0, 2.0);
        render(3000); probes("h");
        log.close();
        audio.close();
    }
}
