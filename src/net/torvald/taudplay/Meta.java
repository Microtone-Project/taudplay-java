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

        int note(int noteVal) { return fixedPitch ? (detune & 0xffff) : noteVal + detune; }
    }

    int count;
    final Layer[] layers = new Layer[C.META_MAX_LAYERS];
    int flags;              /* record byte 0 */
    int imixId;             /* record byte 255: the IMix block (§9.15) */
    boolean strict;
    int type;
    boolean hasProgram;     /* FM: did the program decode? */
    int programLen;
    final int[] program = new int[128];

    Meta() { for (int i = 0; i < layers.length; i++) layers[i] = new Layer(); }
}
