/*
 * A metainstrument record (inst.js): its layers or rack operators, and an FM
 * rack's program.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class Meta {
    static final class Layer {
        int instIdx, mixOctet, detune, pitchStart, pitchEnd, volStart, volEnd;
        boolean fixedPitch;
        int srcIndex;               /* which entry of the record this is (some are dropped) */
        double dLo, dHi, dDetune;   /* the pitch extension, as a patch's (§9.18) */

        /** The pitch this entry sounds for a trigger at noteVal — NOT confined
         *  to the sixteen octaves of a note value (pitch() splits it). */
        double note(double noteVal) { return fixedPitch ? (detune & 0xffff) + dDetune : noteVal + detune + dDetune; }

        /** note(), held inside $0020…$FFFF: the note value its voice holds. */
        double pitch(double noteVal) {
            double full = note(noteVal);
            return full < 0x20 ? 0x20 : full > 0xffff ? 0xffff : full;
        }

        /** What pitch() had to leave out: the part of the entry's pitch that
         *  lies outside a note value's sixteen octaves (voice.pitchExcess). */
        double excess(double noteVal) {
            double full = note(noteVal);
            return full - (full < 0x20 ? 0x20 : full > 0xffff ? 0xffff : full);
        }
    }

    int count;
    final Layer[] layers = new Layer[C.META_MAX_LAYERS];
    /* Every entry the record has, usable or not — its count and the six pitch
     * bytes of each (+2…+7): what an `IPit` guard is computed over. */
    int rawCount;
    final byte[] rawPitch = new byte[C.META_MAX_LAYERS * 6];
    int flags;              /* record byte 0 */
    int imixId;             /* record byte 255: the IMix block (§9.15) */
    boolean strict;
    int type;
    boolean hasProgram;     /* FM: did the program decode? */
    int programLen;
    final int[] program = new int[128];

    Meta() { for (int i = 0; i < layers.length; i++) layers[i] = new Layer(); }
}
