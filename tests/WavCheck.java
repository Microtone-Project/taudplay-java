/* WAV encoder check: encode the float signal tests/wav_ref.mjs wrote, clean
 * and gritty, at the engine rate and at 44.1 kHz, and compare with the files
 * the JavaScript library made from it — byte for byte (taudplay-c
 * tests/wavcheck.c).   java ... WavCheck PREFIX */
package net.torvald.taudplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class WavCheck {
    public static void main(String[] argv) throws Exception {
        int[] rates = { 48000, 44100 };
        String[] names = { "clean", "gritty" };
        int status = 0;
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(Paths.get(argv[0] + ".f32"))).order(ByteOrder.LITTLE_ENDIAN);
        float[] f32 = new float[bb.capacity() / 4];
        for (int i = 0; i < f32.length; i++) f32[i] = bb.getFloat();
        for (int r = 0; r < 2; r++) {
            for (int q = 0; q < 2; q++) {
                byte[] wav = TaudWav.encode(f32, f32.length / 2, 48000, rates[r], q == 1 ? TaudWav.Quality.GRITTY : TaudWav.Quality.CLEAN);
                byte[] ref = Files.readAllBytes(Paths.get(argv[0] + "." + names[q] + "." + rates[r] + ".wav"));
                System.out.print("wav " + names[q] + " " + rates[r] + " Hz:");
                if (wav.length != ref.length) { System.out.println(" DIFFER in length (" + wav.length + " vs " + ref.length + ")"); status = 1; continue; }
                int i = 0;
                while (i < wav.length && wav[i] == ref[i]) i++;
                if (i < wav.length) { System.out.println(" DIFFER at byte " + i); status = 1; }
                else System.out.println(" identical (" + wav.length + " bytes)");
            }
        }
        System.exit(status);
    }
}
