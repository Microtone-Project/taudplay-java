/*
 * A lyric instrument's `ILyr` entry (lyric-section.js; file format §9.17,
 * engine spec §5.5.3 "Lyric instruments").
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class LyricEntry {
    static final class Syllable {
        int front, open, round;
        boolean vowel, nasal;
        int gr;                         /* the tract block's +53 */
        final int[] cons = new int[3];  /* onset 1, onset 2, coda */
        final int[] timing = new int[4];/* onset 1, onset 2, coda, glide */
    }

    /** A consonant slot's layout (§7.6): the noise consonant `cons` reads. */
    static final class Recipe {
        int cons;
        long ptr;
        int len, offset, rate, tilt, gain;
    }

    int instId;
    int numSyllables, numRecipes;
    Syllable[] syllables;
    Recipe[] recipes;
    /** The first recipe for each consonant id, -1 for none (the generator). */
    final int[] recipeOf = new int[256];
}
