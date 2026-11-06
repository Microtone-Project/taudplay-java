/*
 * The library's WAV writer (the JS library's encodeWav; taudplay-c
 * taud_encode_wav_quality).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

public final class TaudWav {
    private TaudWav() {}

    /** How a WAV is quantised. CLEAN is 16-bit with a plain TPDF dither of one
     *  LSB; GRITTY is 8-bit through the device's own noise-shaped dither,
     *  which at the engine's rate is byte for byte what
     *  {@link TaudPlayer#renderChunkU8()} delivers. Both dithers start afresh,
     *  so one input always makes one file. */
    public enum Quality { CLEAN, GRITTY }

    private static void wr16(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void wr32(byte[] b, int o, long v) { wr16(b, o, (int) (v & 0xffff)); wr16(b, o + 2, (int) (v >>> 16)); }
    private static void ascii(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }

    /* The clean WAV's 16-bit quantiser (dither.js in the JS library): clamp,
     * scale by 32767, add a triangular -1…+1 LSB, round. */
    private static int ditherS16(int[] xs, float v) {
        double d = v < -1.0f ? -1.0 : v > 1.0f ? 1.0 : (double) v;
        double a = Mixer.xorshift32(xs) & 0xffffff;
        double b = Mixer.xorshift32(xs) & 0xffffff;
        double q = JsMath.round(d * 32767 + (a - b) / 16777216.0);
        return (short) (q < -32768 ? -32768 : q > 32767 ? 32767 : q);
    }

    /** Encode `frames` frames of interleaved stereo float at `srcRate` as a
     *  stereo WAV at `dstRate` (Kaiser-windowed-sinc resampled when they
     *  differ). Byte for byte the JS library's encodeWav. */
    public static byte[] encode(float[] interleaved, int frames, int srcRate, int dstRate, Quality quality) {
        float[] pcm = interleaved;
        boolean gritty = quality == Quality.GRITTY;
        int bytesPer = gritty ? 1 : 2;
        int outFrames = frames;
        int[] xs = { C.TE_XORSHIFT_SEED };
        if (srcRate != dstRate) {
            pcm = Resample.interleaved(interleaved, frames, 2, srcRate, dstRate);
            outFrames = pcm.length / 2;
        }
        int numSamples = outFrames * 2;
        int dataBytes = numSamples * bytesPer;
        byte[] buf = new byte[44 + dataBytes];
        ascii(buf, 0, "RIFF");
        wr32(buf, 4, 36L + dataBytes);
        ascii(buf, 8, "WAVE");
        ascii(buf, 12, "fmt ");
        wr32(buf, 16, 16);
        wr16(buf, 20, 1);
        wr16(buf, 22, 2);
        wr32(buf, 24, dstRate);
        wr32(buf, 28, (long) dstRate * 2 * bytesPer);
        wr16(buf, 32, 2 * bytesPer);
        wr16(buf, 34, 8 * bytesPer);
        ascii(buf, 36, "data");
        wr32(buf, 40, dataBytes);
        if (gritty) {
            /* The device's own dither, from a fresh state, a block at a time:
             * at the engine's rate these are the bytes renderChunkU8 gives. */
            float[] l = new float[256], r = new float[256], err = new float[4];
            for (int f0 = 0; f0 < outFrames; f0 += 256) {
                int n = Math.min(outFrames - f0, 256);
                for (int k = 0; k < n; k++) {
                    l[k] = pcm[(f0 + k) * 2];
                    r[k] = pcm[(f0 + k) * 2 + 1];
                }
                Mixer.ditherU8(xs, err, l, r, 0, n, buf, 44 + f0 * 2);
            }
        } else {
            for (int i = 0; i < numSamples; i++) wr16(buf, 44 + i * 2, ditherS16(xs, pcm[i]));
        }
        return buf;
    }

    /** {@link #encode} with {@link Quality#CLEAN}. */
    public static byte[] encode(float[] interleaved, int frames, int srcRate, int dstRate) {
        return encode(interleaved, frames, srcRate, dstRate, Quality.CLEAN);
    }
}
