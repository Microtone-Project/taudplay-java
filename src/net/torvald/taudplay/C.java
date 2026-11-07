/*
 * Engine constants (constants.js and the rest of taud_internal.h's #defines).
 *
 * Copyright (C) 2026 CuriousTorvald. Licensed under the GNU Lesser General
 * Public License version 3 or later — see COPYING.LESSER.
 */
package net.torvald.taudplay;

final class C {
    private C() {}

    static final int TAUD_CHUNK = 128;
    static final int TAUD_DEFAULT_RATE = 48000;
    static final int TRACKER_CHUNK = TAUD_CHUNK;
    static final int MAX_BG_VOICES = 64;
    static final int MIDDLE_C = 0x5000;
    static final double AMIGA_BASE_PERIOD = 428.0;
    static final double LINEAR_FREQ_C4_HZ = 261.6255653005986;
    static final double TUNING_REF_C4_HZ = LINEAR_FREQ_C4_HZ;
    static final int TUNING_DEFAULT_BASE_NOTE = 0xa000;
    static final double TUNING_DEFAULT_FREQ_HZ = 8363.0;
    /* The tempo register: ten bits biased by -25 (song table byte 7, byte 8
     * bit 7, byte 28 bit 7), so 25..1048 BPM. Every path that sets the tempo
     * clamps to it. */
    static final int BPM_MIN = 25;
    static final int BPM_MAX = 25 + 0x3ff;
    static final double RAMP_OUT_SEC = 0.008;
    static final double FAST_FADE_SEC = 0.3;
    static final double VOL_RAMP_SEC = 0.002;
    static final double ATTACK_RAMP_SEC = 32.0 / 48000.0;
    /* SoundFont-mode filter glide (item 297): FluidSynth's FLUID_BUFSIZE, 64
     * samples at its default 44.1 kHz, carried as a time. */
    static final double SF_FILTER_GLIDE_SEC = 64.0 / 44100.0;
    static final double PITCH_GLIDE_FULL_RATIO = 0.014545334937523746;

    static final int SAMPLE_BIN_TOTAL = 8388608;
    static final int INSTBIN_SIZE = 262144;
    static final int SAMPLEINST_SIZE = SAMPLE_BIN_TOTAL + INSTBIN_SIZE;
    static final int NUM_VOICES = 32;
    static final int MAX_VOICES = 64;
    static final int NUM_CUES = 8192;
    static final int NUM_PATTERNS = 0x7fff;
    static final int PATTERN_EMPTY = 0x7fff;
    static final int ROWS_PER_PATTERN = 64;
    static final int CELL_BYTES = 8;
    static final int CELL_BYTES_WIDE = 16;
    static final int PATTERN_BYTES = 512;
    static final int PATTERN_BYTES_WIDE = 1024;
    static final int VOLUME_MAX = 0x3f;
    static final int VOLUME_MAX_WIDE = 0xff;
    static final int VOLUME_STEP_WIDE = 4;

    static final int INTERP_DEFAULT = 0;
    static final int INTERP_NONE = 1;
    static final int INTERP_A500 = 2;
    static final int INTERP_A1200 = 3;
    static final int INTERP_SNES = 4;
    static final int INTERP_NES_DPCM = 5;

    static final int SINC_WIDTH = 3;
    static final int SINC_PRECISION = 1024;
    static final int SINC_TABLE_SIZE = SINC_PRECISION * SINC_WIDTH;

    static final int NUM_INTERRUPTS = 16;
    static final int AUDITION_SLOT = 1024;
    static final int NUM_INST_SLOTS = AUDITION_SLOT + 1;

    static final int LFO_PHASE_STEPS = 1088;
    static final int LFO_STEPS_PER_ENTRY = 17;
    static final int AUTOVIB_PHASE_STEPS = 1024;
    static final int AUTOVIB_DEPTH_MUL = 43;
    static final int AUTOVIB_DEPTH_SHIFT = 12;

    /* Effect opcodes (base-36 digit values). */
    static final int OP_NONE = 0x00, OP_1 = 0x01, OP_2 = 0x02, OP_3 = 0x03, OP_4 = 0x04, OP_5 = 0x05,
        OP_6 = 0x06, OP_7 = 0x07, OP_8 = 0x08, OP_9 = 0x09,
        OP_A = 0x0a, OP_B = 0x0b, OP_C = 0x0c, OP_D = 0x0d, OP_E = 0x0e, OP_F = 0x0f, OP_G = 0x10,
        OP_H = 0x11, OP_I = 0x12, OP_J = 0x13, OP_K = 0x14, OP_L = 0x15, OP_M = 0x16, OP_N = 0x17,
        OP_O = 0x18, OP_P = 0x19, OP_Q = 0x1a, OP_R = 0x1b, OP_S = 0x1c, OP_T = 0x1d, OP_U = 0x1e,
        OP_V = 0x1f, OP_W = 0x20, OP_X = 0x21, OP_Y = 0x22, OP_Z = 0x23,
        OP_COLON = 0xba;

    /* Surround models and monitor modes. */
    static final int SURROUND_STEREO = 0;
    static final int SURROUND_PLANAR = 1;
    static final int SURROUND_SPATIAL = 2;
    static final int AZIMUTH_TURN = 512;
    static final int ELEVATION_QUARTER = 128;
    static final int MONITOR_FOLD = 0;
    static final int MONITOR_BINAURAL = 1;

    /* ── instrument model ── */
    static final int ENV_POINTS = 25;
    static final int CHAN_MODE_DISCRETE = 0;
    static final int CHAN_MODE_MATRIX = 1;

    static final int META_MAX_LAYERS = 25;
    static final int META_TYPE_LAYERED = 0;
    static final int META_TYPE_FORMANT = 1;
    static final int META_TYPE_FM = 4;
    static final int META_TYPE_OPL = 8;
    static final int META_TYPE_PSG = 9;
    static final int META_LAYER_FIXED_PITCH = 0x40;
    static final int FM_MAX_OPERATORS = 16;
    /* Bytes 4…253 hold an FM rack and its program; byte 255 is every record's
     * IMix id and byte 254 stays clear (FM_PROGRAM_END). */
    static final int FM_PROGRAM_END = 254;
    static final int FM_STACK_MAX = 16;
    static final int FM_WORD_OSC = 0x0000;
    static final int FM_WORD_MOD = 0x0400;
    static final int FM_WORD_FB = 0x0800;
    static final int FM_WORD_OP = 0xff00;
    static final int FM_INDEX_MASK = 0x03ff;
    static final int FMOP_ADD = 0xff00;
    static final int FMOP_MUL = 0xff01;
    static final int FMOP_NEG = 0xff02;
    static final int FMOP_DUP = 0xff03;
    static final int FMOP_SWAP = 0xff04;
    static final int FMOP_END = 0xffff;

    /* The metainstrument tiers (inst.js metaTier). */
    static final int META_TIER_NONE = -1;
    static final int META_TIER_HIGH = 0;
    static final int META_TIER_LOW = 1;
    static final int META_TIER_RESERVED = 2;

    /* ── OPL ── */
    static final int OPL_BLOCK_START = 197;
    static final int OPL_BLOCK_LEN = 54;
    static final int OPL_C4_RATE = 8363;
    /** The chip's sample rate, 3579545 / 72 Hz (OPL_RATE). */
    static final double OPL_RATE_HZ = 3579545.0 / 72;
    static final int EG_OFF = 0;
    static final int EG_ATTACK = 1;
    static final int EG_DECAY = 2;
    static final int EG_SUSTAIN = 3;
    static final int EG_RELEASE = 4;
    static final int OPL_ENV_MAX = 511;
    static final int OPL_VIBRATO_PERIOD = 8192;
    static final int OPL_TREMOLO_PERIOD = 13440;

    /* ── PSG ── */
    static final int PSG_BLOCK_START = 197;
    static final int PSG_BLOCK_LEN = 54;
    static final int PSG_NOISE_LONG_LEN = 32767;
    static final int PSG_NOISE_MID_LEN = 2047;
    static final int PSG_NOISE_SHORT_LEN = 127;

    /* ── formant ── */
    static final int FORMANT_BLOCK_START = 197;
    static final int FORMANT_BLOCK_LEN = 54;
    static final int FORMANT_SLOTS = 3;
    static final int FORMANT_TRACK_MAX = 32;
    static final int NORM_HARMONICS = 64;
    static final int LYRIC_SYLLABLE_BYTES = 12;
    static final int LYRIC_RECIPE_BYTES = 12;
    static final int CHIP_NONE = 0;
    static final int CHIP_OPL = 1;
    static final int CHIP_PSG = 2;
    static final int PHASE_ONSET = 0;
    static final int PHASE_VOWEL = 1;
    static final int PHASE_CODA = 2;
    static final int PHASE_DONE = 3;
    static final int ISO226_N = 29;
    static final int BANK_GRID_MAX = 64;
    static final int SOURCE_DFT_MAX = 4096;

    /** Sample-modification masks never exceed a 16-bit length. */
    static final int MOD_MASK_CAP = 8192;

    /** Duplicate Check Type 4: every note arriving on the lane. */
    static final int DCT_LANE = 4;

    /* ── pattern data ── */
    static final int INST_NOP = 0;
    static final int INST_GOBACK = 1;
    static final int INST_SKIP = 2;
    static final int INST_JUMP = 3;
    static final int INST_PATLEN = 4;
    static final int INST_HALTAT = 5;
    static final int INST_HALT = 6;
    static final int CUE_BLOCK = 256;
    static final int CUE_BLOCKS = NUM_CUES / CUE_BLOCK;

    /* ── mastering ── */
    static final int EQ_BANDS = 4;
    static final int EQ_LOW_SHELF = 0;
    static final int EQ_PEAKING = 1;
    static final int EQ_HIGH_SHELF = 2;
    static final int COMP_PEAK = 0;
    static final int COMP_RMS = 1;
    static final int HP_SLOPE_12 = 0;
    static final int HP_SLOPE_24 = 1;
    /** The widest bus a song can have. */
    static final int TE_MAX_BUS_CH = 16;
    static final int TP_PHASES = 4;
    static final int TP_TAPS = 8;

    /* ── instrument mixing chains ── */
    static final int IMX_ITRM = 0, IMX_EXPD = 1, IMX_EQU1 = 2, IMX_DEQ1 = 3, IMX_TSH1 = 4, IMX_COMP = 5,
        IMX_EQU2 = 6, IMX_DEQ2 = 7, IMX_UNIS = 8, IMX_SATU = 9, IMX_DLAY = 10, IMX_VERB = 11,
        IMX_LIMI = 12, IMX_MXTL = 13, IMX_KINDS = 14;
    static final int IMX_EQ_MAX_BANDS = 8;
    static final int IMX_COMP_MAX_BANDS = 6;
    static final int IMX_TSH_MAX_BANDS = 6;
    static final int IMX_DEQ_BANDS = 6;
    static final int IMX_UNIS_MAX_VOICES = 8;
    /** Slots a route table covers: the 1024 bank slots and the audition slot. */
    static final int IMX_ROUTE_SLOTS = NUM_INST_SLOTS;

    /* ── sample modification ── */
    static final int MOD_OFF = 0x0;
    static final int MOD_INVERT = 0x1;
    static final int MOD_ROL1 = 0x2;
    static final int MOD_ROL8 = 0x5;
    static final int MOD_SUB2 = 0x6;
    static final int MOD_SUB128 = 0x9;
    static final int MOD_JUMP8 = 0xa;
    static final int MOD_JUMP16 = 0xb;
    static final int MOD_JUMP_ALL = 0xc;
    static final int MOD_RND512 = 0xd;
    static final int MOD_RND8 = 0xf;
    static final int MOD_WALK_SCAN = 4096;
    static final int MOD_XFADE_SAMPLES = 64;
    static final int REGION_NONE = 0;
    static final int REGION_SET = 1;
    static final int REGION_COMB = 2;
    static final int EXT_KIND_NOOP = 0, EXT_KIND_INVERT = 1, EXT_KIND_FUNK = 2, EXT_KIND_XOR = 3,
        EXT_KIND_MIRROR = 4, EXT_KIND_INVERT_JIT = 5, EXT_KIND_FUNK_JIT = 6, EXT_KIND_JUMP_N = 7,
        EXT_KIND_JUMP_N_BOUNDED = 8, EXT_KIND_SWAP = 9, EXT_KIND_SCATTER = 10, EXT_KIND_ROL = 11,
        EXT_KIND_SUB = 12, EXT_KIND_BITROT = 13, EXT_KIND_BITPERM = 14;

    /** The adapter's xorshift32 seed (AudioAdapter.kt; rng.js makeXorshift32). */
    static final int TE_XORSHIFT_SEED = 0x9e3779b9;

    /* ── helpers (taud_internal.h's inline functions) ── */

    static int imin(int a, int b) { return a < b ? a : b; }
    static int imax(int a, int b) { return a > b ? a : b; }
    static int iclamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
    static double dclamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
    static double dmin(double a, double b) { return a < b ? a : b; }
    static double dmax(double a, double b) { return a > b ? a : b; }
    static double fround(double x) { return (double) (float) x; }
    static double jsRound(double x) { return JsMath.round(x); }
    static int toInt32(double x) { return JsMath.toInt32(x); }

    /* C library and JS spellings, so expressions carry over from the C port
     * verbatim. Every js_* is V8's own (JsMath); the rest are exact. */
    static double fabs(double x) { return Math.abs(x); }
    static double floor(double x) { return Math.floor(x); }
    static double ceil(double x) { return Math.ceil(x); }
    static double sqrt(double x) { return Math.sqrt(x); }
    static double fmod(double a, double b) { return a % b; }
    static double trunc(double x) { return x < 0 ? Math.ceil(x) : Math.floor(x); }
    static boolean isnan(double x) { return Double.isNaN(x); }
    static boolean isfinite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }
    static double js_round(double x) { return JsMath.round(x); }
    static int js_toint32(double x) { return JsMath.toInt32(x); }
    static double js_pow(double x, double y) { return JsMath.pow(x, y); }
    static double js_exp(double x) { return JsMath.exp(x); }
    static double js_log(double x) { return JsMath.log(x); }
    static double js_log2(double x) { return JsMath.log2(x); }
    static double js_log10(double x) { return JsMath.log10(x); }
    static double js_sin(double x) { return JsMath.sin(x); }
    static double js_cos(double x) { return JsMath.cos(x); }
    static double js_tan(double x) { return JsMath.tan(x); }
    static double js_acos(double x) { return JsMath.acos(x); }
    static double js_asin(double x) { return JsMath.asin(x); }
    static double js_atan2(double y, double x) { return JsMath.atan2(y, x); }
    static double js_tanh(double x) { return JsMath.tanh(x); }
    static double js_log1p(double x) { return JsMath.log1p(x); }
    static double js_expm1(double x) { return JsMath.expm1(x); }
    static final double NAN = Double.NaN;
    static final double HUGE_VAL = Double.POSITIVE_INFINITY;
}
