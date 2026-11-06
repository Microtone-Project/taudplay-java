/* Compare two interleaved stereo float32 renders: exact-match count, the
 * largest difference, and the first frame that differs (taudplay-c
 * tests/compare.c).   java ... Compare REF.f32 TEST.f32 */
package net.torvald.taudplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public final class Compare {
    public static void main(String[] argv) throws Exception {
        if (argv.length < 2) { System.err.println("usage: Compare REF.f32 TEST.f32"); System.exit(2); }
        ByteBuffer a = ByteBuffer.wrap(Files.readAllBytes(Paths.get(argv[0]))).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(Paths.get(argv[1]))).order(ByteOrder.LITTLE_ENDIAN);
        int na = a.capacity() / 4, nb = b.capacity() / 4, n = Math.min(na, nb);
        long same = 0, first = -1, worst = 0;
        double maxd = 0, sumsq = 0, refsq = 0;
        for (int i = 0; i < n; i++) {
            int ia = a.getInt(i * 4), ib = b.getInt(i * 4);
            float fa = Float.intBitsToFloat(ia), fb = Float.intBitsToFloat(ib);
            double d = Math.abs((double) fa - (double) fb);
            if (ia == ib) same++;
            else if (first < 0) first = i;
            if (d > maxd) { maxd = d; worst = i; }
            sumsq += d * d;
            refsq += (double) fa * fa;
        }
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT,
            "  samples %d vs %d, bit-identical %.4f%%, max |diff| %.3g (frame %d)",
            na, nb, n > 0 ? 100.0 * same / n : 100.0, maxd, worst / 2));
        if (first >= 0) s.append(String.format(Locale.ROOT, ", first diff at frame %d", first / 2));
        if (sumsq > 0) s.append(String.format(Locale.ROOT, ", SNR %.1f dB", 10 * Math.log10(refsq / sumsq)));
        System.out.println(s);
        System.exit(na == nb && same == n ? 0 : 1);
    }
}
