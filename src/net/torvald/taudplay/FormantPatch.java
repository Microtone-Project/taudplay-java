/*
 * The decoded tract block of a formant instrument (formant.js FormantPatch) —
 * or a LYRIC SYLLABLE built from one (Formant.lyricSyllable).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class FormantPatch {
    /** A consonant slot: the consonant, and the noise it reads (§7.6). */
    static final class Slot {
        int cons;
        long ptr;
        int len, offset, rate, tilt, gain;

        void copyFrom(Slot o) { cons = o.cons; ptr = o.ptr; len = o.len; offset = o.offset; rate = o.rate; tilt = o.tilt; gain = o.gain; }
    }

    long srcPtr;
    int srcInst;                /* the header's source instrument, 0 = the sample */
    int front, open, round, track, glide, timing;
    int refNote;                /* bits 0…14 of the word; 0 = the syllable has no vowel */
    double dRefNote;            /* the pitch extension of refNote (§9.18) */
    boolean nasal;              /* bit 15: the vowel nasalised */
    int semivowel;
    boolean rhotic;
    int secondary;              /* the onset's last consonant: ART_* */
    int codaArt;                /* the coda: CODA_* */
    long rangeLo, rangeHi;
    final Slot[] slots = { new Slot(), new Slot(), new Slot() };
    /* A lyric syllable times each of its consonants and its glide by a byte
     * of its own — [onset 1, onset 2, coda, glide], read as `timing` is and
     * applied ON TOP of it. hasSlotTiming false on a record, whose one byte
     * times them all. */
    boolean hasSlotTiming;
    final int[] slotTiming = new int[4];
    /** …and the record it is a syllable of; null on a record. */
    FormantPatch lyricOf;
}
