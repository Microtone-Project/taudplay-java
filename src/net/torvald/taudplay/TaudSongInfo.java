/*
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

/** One song of a file. */
public final class TaudSongInfo {
    public final int index;
    /** The song's own name, or "song N". */
    public final String name;
    public final int patterns;
    public final int bpm;
    /** Lane count stored in the song table. */
    public final int channels;
    /** A planar or spatial song. */
    public final boolean surround;

    TaudSongInfo(int index, String name, int patterns, int bpm, int channels, boolean surround) {
        this.index = index;
        this.name = name;
        this.patterns = patterns;
        this.bpm = bpm;
        this.channels = channels;
        this.surround = surround;
    }

    @Override public String toString() {
        return "[" + index + "] " + name + " (" + patterns + " patterns, " + bpm + " BPM, " + channels + " lanes" +
            (surround ? ", surround" : "") + ")";
    }
}
