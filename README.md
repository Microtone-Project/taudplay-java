# taudplay-java

<p align="center"><em>You are currently looking at the Java version.</em></p>
<p align="center"><a href="https://github.com/Microtone-Project/microtone-taudplay">JavaScript</a> · <a href="https://github.com/Microtone-Project/taudplay-c">C99</a> · Java · <a href="https://github.com/Microtone-Project/taudplay-godot">Godot 4</a> · <a href="https://github.com/Microtone-Project/taudplay-libgdx">LibGDX</a></p>

---

**Play `.taud` songs from Java. No tracker, no browser, no native code, no dependencies.**

A pure-Java port of [`taudplay`](https://github.com/curioustorvald/microtone-taudplay),
the playback half of the [Microtone](https://microtone.cc) tracker. It is the
same engine, note for note and **sample for sample**: a render here is
bit-identical to the same song rendered by the JavaScript library (and so by
the tracker itself), at any engine rate — verified over the whole Microtone
test corpus, a differential fuzzer and an API-level scenario test (see
[Verification](#verification)).

The surface is the JS library's, kept deliberately small:

| | |
|---|---|
| **Knob** | `setVoiceGain(v, gain, fadeSeconds)` — one fader per lane |
| **Probe** | `getVoiceVolume(v)` — how loud lane `v` is, 0…1 |
| **Probe** | `getVoicePan(v)` — where it sits, 0 (left) … 0.5 … 1 (right) |

…plus the transport, and the sixteen **interrupts** a song can fire at the
program playing it (`Int0`…`IntF` in a note column, each with a 16-bit
argument), delivered in time with the music.

For libGDX (a `Music`, an OpenAL stream, a render thread with a ring buffer)
see [taudplay-libgdx](https://github.com/Microtone-Project/taudplay-libgdx),
which is built on this.

## Build

```
make            # build/taudplay.jar (library + CLI) and build/taudplay-sources.jar
```

JDK 17 or later (`javac --release 17`); the jar has no dependencies. Nothing
about the platform is assumed: since Java 17 every `float` and `double`
operation is strict IEEE 754 on every JVM, the engine never calls `Math.fma`,
and the transcendental functions come from its own fdlibm port (below), so the
output does not depend on the JVM, the CPU or the operating system.

## Play something

```java
import net.torvald.taudplay.*;

TaudPlayer p = new TaudPlayer(Files.readAllBytes(path));   // throws TaudException
p.play();
float[] block;
while ((block = p.renderChunk()) != null) {
    // TaudPlayer.CHUNK (128) frames of interleaved stereo float at 48 kHz
}
```

The file bytes are only read by the constructor; everything the player needs
is copied out. A `TaudException` says what was wrong (`getKind()`: `FORMAT`,
`NOT_A_SONG`, `DECOMPRESS`, `RANGE`).

### From an audio callback

```java
void fill(float[] out, int frames) {
    player.render(out, 0, frames);   // any frame count; silence after the end
}
```

`render(float[] interleaved, …)` and `render(float[] left, float[] right, …)`
buffer across block boundaries; `renderS16` does the same into interleaved
`short`s (clamped and rounded, without the dither the clean WAV writer adds).
`renderChunkU8` renders the Taud device's own output — noise-shaped, dithered
unsigned 8-bit.

### Render at the device's rate

```java
TaudPlayer p = new TaudPlayer(bytes, new TaudConfig().sampleRate(44100));
```

The engine renders natively at any rate — every rate-derived quantity (tick
length, filter and Amiga-LPF coefficients, anti-click ramps, the binaural
HRIRs) is computed from it — so a device running at 44.1 kHz needs no
resampler, and the output still agrees bit for bit with the JavaScript library
set to the same rate.

### Mix it

```java
p.setVoiceGain(4, 0.0, 1.2);   // fade lane 5 out over 1.2 s of rendered audio
p.setVoiceGain(4, 1.0, 0.4);   // …and back in, faster

for (int v = 0; v < p.getChannelCount(); v++)   // duck everything but the drums
    if (v != DRUMS) p.setVoiceGain(v, 0.3, 0.8);
```

A fader reaches everything its lane spawned — NNA ghosts and metainstrument
layers included — so a faded lane really does take its whole sound with it.

A song's instrument mixing chains (`IMix`) and its mastering chain (`sMst`) are
installed from the file and played as the tracker plays them; there is nothing
to set.

### Let the song call you

```java
p.setInterrupt(0, (n, arg) -> flashLight(arg));   // null unregisters
```

Listeners run on the rendering thread, from inside the render call, once per
128-frame block the interrupt fired in (2.7 ms at 48 kHz), lowest number first.
If one interrupt fires twice inside a block you are called once, with the later
argument. A listener that must not block the audio thread should post the
event somewhere and return.

### Watch it

```java
for (int v = 0; v < p.getChannelCount(); v++)
    drawMeter(v, p.getVoiceVolume(v), p.getVoicePan(v));
```

The probes describe the state at the end of the last rendered block.

## The CLI

```
java -jar taudplay.jar song.taud                          describe the file
java -jar taudplay.jar song.taud -o song.wav              render song 0 to a 16-bit WAV
java -jar taudplay.jar song.taud --gritty -o song.wav     …or to the device's own 8-bit sound
java -jar taudplay.jar song.taud -s 2 -t 60 -o x.wav      song 2, at most 60 seconds
java -jar taudplay.jar song.taud -r 44100 -o x.wav        render natively at 44.1 kHz
java -jar taudplay.jar song.taud --wav-rate 44100 -o x.wav  render at 48 kHz, resample the WAV
java -jar taudplay.jar song.taud --binaural -o x.wav      head-model downmix of a surround song
java -jar taudplay.jar song.taud --mute 0,3 -o x.wav      without lanes 1 and 4
java -jar taudplay.jar song.taud --interrupts             print the song's interrupt cue sheet
java -jar taudplay.jar song.taud --raw x.f32              interleaved float32, engine rate
java -jar taudplay.jar song.taud --raw - | ffplay -f f32le -ar 48000 -ac 2 -i pipe:0
java -jar taudplay.jar song.taud --raw - --s16 | aplay -f S16_LE -r 48000 -c 2
java -jar taudplay.jar song.taud --bench --raw /dev/null  how fast it renders here
```

It is the C library's CLI, option for option. `--raw` streams as it renders;
`-o -` has to render the whole song first, because a WAV header carries the
length. Messages, and the `--interrupts` log when stdout carries audio, go to
stderr. `-t` caps the length (600 s by default).

## API

All in the package `net.torvald.taudplay`; the rest of the package is the
engine, package-private.

- **`TaudPlayer`** — the player.
  - The file: `getTitle`, `getSongCount`, `getSongInfo` (a `TaudSongInfo`),
    `selectSong`, `getCurrentSong`, `getSampleRate`
  - Transport: `play` (from the top), `stop`, `seekCue`, `setVolume`,
    `setBinaural`; `isPlaying`, `getCue`, `getRow`, `getBpm`, `getSpeed`,
    `getChannelCount`
  - Knob / probes: `setVoiceGain`, `getVoiceGain`, `getVoiceVolume`,
    `getVoicePan`
  - Interrupts: `setInterrupt` (a `TaudInterruptListener`), `clearInterrupts`
  - Rendering: `renderChunk`, `render` (interleaved or planar), `renderS16`,
    `renderChunkU8`
  - `gainToFader`, `faderToGain`; `CHUNK`, `DEFAULT_RATE`, `MAX_VOICES`,
    `NUM_INTERRUPTS`, `VERSION`
- **`TaudConfig`** — `sampleRate`, `songIndex`, `binaural`, `randomSeed`,
  `random`, as fields or chained setters.
- **`TaudWav`** — `encode(interleaved, frames, srcRate, dstRate[, quality])`,
  with the library's Kaiser-sinc resampler. `Quality.CLEAN` (the default) is
  16-bit with a plain TPDF dither of one LSB; `Quality.GRITTY` is 8-bit through
  the device's own noise-shaped dither, so at the engine's rate it is byte for
  byte what `renderChunkU8` delivers. Each dither starts afresh, so the same
  floats always make the same file.
- **`TaudException`**, **`TaudSongInfo`**, **`TaudInterruptListener`**.

## Embedding notes

- **Threads.** No static mutable state: each `TaudPlayer` is independent and
  may live on its own thread. One player must not be called from two threads
  at once; calling the knobs from a UI thread while the audio thread renders
  needs your own lock or a command queue (taudplay-libgdx's `TaudStream` is
  one).
- **Memory.** About 10–40 MB of heap per open player, almost all of it the
  song's own sample data (the format's 8 MB sample pool is decompressed once
  and trimmed to its last non-zero byte). An instrument mixing chain with a
  tape delay adds about 1.5 MB per channel of its bus.
- **Allocation on the render path.** A sounding voice is an object, made when
  its note starts and left to the garbage collector when it has died away, as
  in the JavaScript engine; the instrument buses are built at the first block
  after a song is selected or the monitor mode changes.
- **Randomness.** Instruments may ask for random volume/pan swing, and some
  effects are randomised. The JS library draws those from `Math.random`; this
  library from a seeded mulberry32 (`TaudConfig.randomSeed`, default 1) or
  your own `TaudConfig.random` — so a render is reproducible, and is exactly
  the JS render when the JS side is seeded the same way
  (`setRandomSource(makeSeededRandom(1))`).
- **Speed.** About 20–70× real time on one core of a desktop x86-64 for an
  ordinary 32-lane song, and about 10× for a dense 64-lane one with forty lanes
  sounding (`--bench` measures it; the first seconds run before the JIT has
  warmed up).

## Numerics

The engine does its arithmetic in binary64 and narrows the mix to binary32 once
per frame, exactly where the JavaScript does; every expression keeps the
reference's order of operations, and JavaScript's integer semantics (`ToInt32`,
`>>>`, `Math.round`'s half-up) are spelt out where Java's differ.

V8 computes `Math.sin`, `cos`, `tan`, `exp`, `log`, `log10`, `log2`, `acos`,
`asin`, `atan2`, `tanh`, `log1p`, `expm1` and `pow` (and `**`) with its own
fdlibm port. `java.lang.Math` may use intrinsics that differ from it in the
last bit, and even `StrictMath` (fdlibm too) groups `pow`'s final step the way
Sun's original does, where V8's copy does not — enough to move about 4% of
`pow` results by an ulp. So `JsMath` carries V8's fdlibm functions, verified
bit for bit against V8 over hundreds of thousands of arguments
(`JsMathCheck`).

Current V8 (14.x, as in Node 26) no longer calls its own `pow` by default:
with its `use_std_math_pow` flag on, the default, `Math.pow` and `**` are the
platform's `std::pow`, so the JavaScript's own output depends on the machine it
runs on. V8 still computes its fdlibm port under `--no-use-std-math-pow`, and
the tests run Node with that flag. A JS render made without it differs from
this library's by an occasional one-ulp `pow` result, far below anything
audible.

## Verification

`make check` (needs Node ≥ 22 and a checkout of the JS library at
`../microtone-taudplay`, or `JS=path`) runs:

- `JsMathCheck` — the math functions against V8.
- `WavCheck` — the WAV encoder, clean and gritty, at the engine rate and
  resampled, against the JS library's `encodeWav`, byte for byte.
- `tests/run.sh` — every song of every corpus file, stereo fold-down and
  binaural, JS vs Java, sample for sample. Also takes any files:
  `sh tests/run.sh path/to/*.taud`.
- `tests/scenario.sh` — the public API against the JS `TaudRenderer`: faders
  and fades, master volume, seeking, song switching, stop and replay, probes,
  interrupts, the dithered 8-bit output, and non-48 kHz engine rates. Audio and
  every probe reading (as raw bits) must match, through `renderChunk` and
  through `renderChunkU8`.
- `tests/fuzz.sh` — differential fuzzing with taudplay-c's generator
  (`tests/gen_fuzz.mjs`, kept identical to it): random but valid songs of every
  cell format, lane count, monitor mode, instrument kind (sampled, SF2-filtered,
  layered and FM metainstruments, OPL, PSG, formant, lyric), effect command,
  mastering chain and instrument mixing chain, and both implementations must
  render them identically. The same `FUZZ_*` knobs as the C library's narrow it
  (`FUZZ_INSTS=opl|psg|formant|fm|layered`, `FUZZ_IMIX`, `FUZZ_IMIX_KINDS`,
  `FUZZ_LYRIC`, `FUZZ_S9`, `FUZZ_CUTS`, `FUZZ_GLIDES`, `FUZZ_WILD`).

At the time of writing all of it is bit-identical against Node 26 (V8 14.6):
the fifteen files of the Microtone corpus and demos (every song, whole, in both
monitor modes), and hundreds of fuzzed songs.

## Relationship to the other libraries

A port of [taudplay-c](https://github.com/Microtone-Project/taudplay-c), itself
a port of the JS library's `core/engine`, `core/format`,
`core/audio/offline-render.js` and `src/taudplay/render.js` — the
`TaudRenderer` half. Classes mirror the C files one for one (`Sampler` is
`sampler.c` is `sampler.js`, `Tick` is `tick.c` is `tick.js`, `Imix` is
`imix.c` is `imix.js`, …) and keep their function names, so the three can be
diffed when the engine moves. Where C passes a struct by value or a pointer to
fill, Java copies (`copyFrom`) or passes a small box; where C keeps a voice
pool, Java lets the collector have the voices, as the JavaScript does.

In step with taudplay 4.8.3 (Microtone.js 4.8.3, engine ce007aa85fda). As in
the C library, the engine's chase (`chase.js`) is not ported: seeking is
`seekCue`, as it is in `TaudRenderer`.

## Licence

LGPL-3.0-or-later — see `COPYING.LESSER` (and `COPYING` for the GPL text it
builds on). You may use this library from a proprietary program; changes *to
the library itself* must be shared.

Where the LGPL does not fit — a console title, a shaded or obfuscated build,
changes to the library you cannot publish — taudplay-java is also available
under a proprietary licence: write to <microtone@curioustorvald.com>. It is
offered beside the LGPL, never instead of it.

Contributions are welcome once their author has agreed to the
[Microtone Contributor Licence Agreement](https://github.com/Microtone-Project/Microtone.js/blob/master/CLA.md):
you keep your copyright, and what is merged stays LGPL. Changes to how the
engine behaves go to [Microtone.js](https://github.com/Microtone-Project/Microtone.js),
where it is authored; this library follows it.

`JsMath` is derived from fdlibm, Copyright © 1993 Sun Microsystems, Inc.
("Permission to use, copy, modify, and distribute this software is freely
granted, provided that this notice is preserved"). The binaural filter set in
`HrirSadie` is the GoogleVR/SADIE set, © 2017 Google Inc. and the University
of York, Apache-2.0.

---

Songs are made with **Microtone** — a tracker for the notes a piano cannot
play, free and in your browser at [microtone.cc](https://microtone.cc).
