/* Checks JsMath against V8, bit for bit (tests/jsmath_ref.mjs; taudplay-c
 * tests/jsmath_check.c). */
package net.torvald.taudplay;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;

public final class JsMathCheck {
    private static final String[] NAMES = { "sin", "cos", "tan", "exp", "log10", "log2", "acos", "asin", "atan2", "log", "pow", "tanh", "log1p", "expm1" };

    public static void main(String[] argv) throws Exception {
        ByteBuffer f = ByteBuffer.wrap(Files.readAllBytes(Paths.get(argv.length > 0 ? argv[0] : "tests/out/jsmath.bin")))
            .order(ByteOrder.LITTLE_ENDIAN);
        long[] total = new long[14], bad = new long[14];
        boolean fail = false;
        while (f.remaining() >= 32) {
            double[] r = { f.getDouble(), f.getDouble(), f.getDouble(), f.getDouble() };
            int id = (int) r[0];
            if (id < 0 || id >= 14) continue;
            double v;
            switch (id) {
            case 0: v = JsMath.sin(r[1]); break;
            case 1: v = JsMath.cos(r[1]); break;
            case 2: v = JsMath.tan(r[1]); break;
            case 3: v = JsMath.exp(r[1]); break;
            case 4: v = JsMath.log10(r[1]); break;
            case 5: v = JsMath.log2(r[1]); break;
            case 6: v = JsMath.acos(r[1]); break;
            case 7: v = JsMath.asin(r[1]); break;
            case 8: v = JsMath.atan2(r[1], r[2]); break;
            case 9: v = JsMath.log(r[1]); break;
            case 11: v = JsMath.tanh(r[1]); break;
            case 12: v = JsMath.log1p(r[1]); break;
            case 13: v = JsMath.expm1(r[1]); break;
            default: v = JsMath.pow(r[1], r[2]); break;
            }
            total[id]++;
            if (Double.doubleToRawLongBits(v) != Double.doubleToRawLongBits(r[3]) && !(Double.isNaN(v) && Double.isNaN(r[3]))) {
                if (bad[id] < 3) System.out.println(String.format(Locale.ROOT, "  %s(%.17g, %.17g): got %.17g want %.17g", NAMES[id], r[1], r[2], v, r[3]));
                bad[id]++;
            }
        }
        for (int i = 0; i < 14; i++) {
            System.out.println(String.format(Locale.ROOT, "%-6s %9d args, %d mismatches", NAMES[i], total[i], bad[i]));
            if (bad[i] != 0) fail = true;
        }
        System.exit(fail ? 1 : 0);
    }
}
