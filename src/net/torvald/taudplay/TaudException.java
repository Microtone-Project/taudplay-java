/*
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

/** A file that could not be opened, or a song index out of range. */
public class TaudException extends Exception {
    /** What went wrong (taudplay-c's TaudError). */
    public enum Kind {
        /** Not a Taud container, or a malformed one. */
        FORMAT("not a valid Taud file"),
        /** A .tsii / .tpif: valid, but no song to play. */
        NOT_A_SONG("the file holds no song to play (need a full .taud)"),
        /** A compressed section did not decode. */
        DECOMPRESS("a compressed section did not decode"),
        /** Song index out of range. */
        RANGE("song index out of range");

        private final String message;
        Kind(String message) { this.message = message; }
        @Override public String toString() { return message; }
    }

    private final Kind kind;

    public TaudException(Kind kind) {
        super(kind.toString());
        this.kind = kind;
    }

    public Kind getKind() { return kind; }
}
