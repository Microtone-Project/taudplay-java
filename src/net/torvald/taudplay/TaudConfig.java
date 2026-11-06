/*
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

import java.util.function.DoubleSupplier;

/** How a {@link TaudPlayer} opens a file. The defaults are 48 kHz, song 0,
 *  the stereo fold-down and random seed 1. */
public final class TaudConfig {
    /** Engine output rate in Hz; 0 = {@link TaudPlayer#DEFAULT_RATE}. The
     *  engine renders natively at any rate. */
    public int sampleRate = TaudPlayer.DEFAULT_RATE;
    /** Which song of the file to load. */
    public int songIndex = 0;
    /** Run a SURROUND song through the head model (binaural monitor). */
    public boolean binaural = false;
    /** Seed of the built-in generator (mulberry32), read as a uint32. */
    public int randomSeed = 1;
    /** A uniform [0,1) source for the engine's randomness (volume/pan swing,
     *  the random LFO shape, the randomised sample effects); null = the
     *  built-in seeded generator. */
    public DoubleSupplier random = null;

    public TaudConfig() {}

    public TaudConfig sampleRate(int hz) { this.sampleRate = hz; return this; }
    public TaudConfig songIndex(int i) { this.songIndex = i; return this; }
    public TaudConfig binaural(boolean on) { this.binaural = on; return this; }
    public TaudConfig randomSeed(int seed) { this.randomSeed = seed; return this; }
    public TaudConfig random(DoubleSupplier source) { this.random = source; return this; }
}
