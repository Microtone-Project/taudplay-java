/*
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

/** A handler for one of the sixteen interrupts a song can fire (`Int0`…`IntF`
 *  in a note column). It runs on the rendering thread, from inside the render
 *  call, once per block the interrupt fired in. */
@FunctionalInterface
public interface TaudInterruptListener {
    /** @param n   the interrupt, 0…15
     *  @param arg the 0…65535 the song's `:` named on the marker row (0 when
     *             it named none) */
    void onInterrupt(int n, int arg);
}
