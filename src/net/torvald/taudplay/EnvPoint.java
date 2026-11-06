/*
 * One envelope node (inst.js): a value and the offset to the next node.
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class EnvPoint {
    int value, offset;

    static EnvPoint[] make(int defaultValue) {
        EnvPoint[] env = new EnvPoint[C.ENV_POINTS];
        for (int i = 0; i < env.length; i++) { env[i] = new EnvPoint(); env[i].value = defaultValue; }
        return env;
    }

    /** Reset in place: voices hold the array itself (activeVolEnv and kin). */
    static void reset(EnvPoint[] env, int defaultValue) {
        for (EnvPoint p : env) { p.value = defaultValue & 0xff; p.offset = 0; }
    }
}
