/*
 * The object bus a surround song renders into (spatial.js): channel-major
 * binary64, TRACKER_CHUNK frames per channel, folded to stereo or decoded by
 * the binaural monitor.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class SpatialBus {
    int kind;                   /* 0 = stereo fold, 1 = binaural */
    Binaural bin;
    int numChannels;
    final double[] data = new double[16 * C.TRACKER_CHUNK];
}
