/*
 * One Ixmp patch of an instrument's keyboard map (inst.js; TaudPatch).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class Patch {
    /** Its place in the instrument's list (the C's pointer difference). */
    int index;
    int pitchStart, pitchEnd, volumeStart, volumeEnd;
    long samplePtr;
    int sampleLength, playStart, loopStart, loopEnd, samplingRate, sampleDetune;
    int loopMode, defaultPan = 0xff, defaultNoteVolume;
    int vibratoSpeed, vibratoSweep, vibratoDepth, vibratoRate, vibratoWaveform = 0xff;
    boolean hasVolEnv, hasPanEnv, hasFilterEnv, hasPitchEnv;
    final EnvPoint[] volEnv = EnvPoint.make(0), panEnv = EnvPoint.make(0);
    final EnvPoint[] filterEnv = EnvPoint.make(0), pitchEnv = EnvPoint.make(0);
    int volEnvLoop, volEnvSustain, panEnvLoop, panEnvSustain;
    int filterEnvLoop, filterEnvSustain, pitchEnvLoop, pitchEnvSustain;
    boolean hasExtra;
    int fadeoutStep;
    boolean filterSfMode;
    int extraCutoff = 0xff, extraResonance = 0xff, extraInitialAttenOctet;
    boolean hasChanBlock;
    int chanCount = 1, chanMode = C.CHAN_MODE_DISCRETE, chanFlags, chanPtrCount;
    final long[] chanPtrs = new long[15];

    boolean isStereo() { return hasChanBlock && chanCount == 2 && chanPtrCount >= 1; }

    boolean vibratoInherits() {
        return vibratoWaveform == 0xff && vibratoSpeed == 0 &&
            vibratoSweep == 0 && vibratoDepth == 0 && vibratoRate == 0;
    }
}
