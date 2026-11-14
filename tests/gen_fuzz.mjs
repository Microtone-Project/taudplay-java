// Generates a deterministic, randomised .taud that exercises as much of the
// engine as it can reach: every effect column command, both cell formats,
// 32 and 64 lanes, surround models, envelopes of every kind, IT and SF2
// filters, NNA / duplicate checks (the lane check included), Ixmp patches
// (stereo included), layered and FM metainstruments (chip operators
// included), OPL, PSG and formant instruments (sung from a sample or from a
// chip), table kinds the engine does not know, sample modifications,
// interrupts, mastering, instrument mixing chains (IMix, valid and not),
// lyric instruments (ILyr, valid and not), wide-cell notes with sixteenths
// (65536-TET), 8-bit volume envelopes and pitch extensions (IPit, guarded
// and not).
//
//   node tests/gen_fuzz.mjs <microtone-taudplay> SEED OUT.taud
//
// The song is not meant to sound like anything. It is meant to make the C
// port and the JavaScript library disagree if they can.
import { writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";
import { resolve } from "node:path";

const [root, seedArg, out] = process.argv.slice(2);
const { gzipSync } = await import(pathToFileURL(resolve(root, "core/vendor/fflate.esm.js")).href);
const IMX = await import(pathToFileURL(resolve(root, "core/format/imix-section.js")).href);
const LYR = await import(pathToFileURL(resolve(root, "core/format/lyric-section.js")).href);

let seed = (+seedArg >>> 0) || 1;
const rnd = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
const ri = (lo, hi) => lo + Math.floor(rnd() * (hi - lo + 1));
const pick = (a) => a[Math.floor(rnd() * a.length)];
const chance = (p) => rnd() < p;

const variant = +seedArg % 4;          // 0: v2 stereo, 1: v2 64-lane, 2: v3 planar, 3: v3 spatial
const wide = variant >= 2;
const lanes64 = variant === 1;
const surround = variant === 2 ? 1 : variant === 3 ? 2 : 0;
const fmtVer = wide ? 3 : 2;
const numLanes = lanes64 ? ri(33, 40) : ri(4, 10);

// FUZZ_REPEAT=1: every note is one of four pitches, each with its own
// sixteenths in a wide-cell song (65536-TET), played on three sample
// instruments whose Duplicate Check is the same-note kind — so the same pitch
// comes round again on a lane still sounding it, and the check's same-note
// test has something to find. $3355 + 9/16 sits just past a semitone's
// rounding edge, where a glissando (S $1x, switched on and off in a wide
// cell's second effect column) snaps up only if the fraction is kept.
const denseRepeat = process.env.FUZZ_REPEAT === "1";
const REPEAT_NOTES = [0x3355, 0x4000, 0x4abc, 0x5123];
const REPEAT_FINE = new Map([[0x3355, 9], [0x4000, 0], [0x4abc, 8], [0x5123, 3]]);

// ── sample pool ──
const SAMPLEBIN = 8388608, INSTBIN = 262144;
const image = new Uint8Array(SAMPLEBIN + INSTBIN);
const samples = [];
let poolPos = 16;
function addSample(len, fn) {
  const ptr = poolPos;
  for (let i = 0; i < len; i++) image[ptr + i] = Math.max(0, Math.min(255, Math.round(127.5 + 127.5 * fn(i, len))));
  poolPos += len + ri(0, 64);
  samples.push({ ptr, len });
  return samples.length - 1;
}
addSample(64, (i, n) => Math.sin((2 * Math.PI * i) / n));
addSample(128, (i, n) => (2 * i) / n - 1);
addSample(32, (i, n) => (i < n / 2 ? 0.8 : -0.8));
addSample(4000, (i, n) => (rnd() * 2 - 1) * Math.exp(-i / 700));
addSample(12000, (i, n) => Math.sin(i * 0.05 + Math.sin(i * 0.013) * 3) * (1 - i / n));
addSample(256, (i, n) => Math.sin((2 * Math.PI * i) / n) * 0.5 + Math.sin((6 * Math.PI * i) / n) * 0.3);
for (let k = 0; k < 4; k++) {
  const f = 0.01 + rnd() * 0.2;
  addSample(ri(200, 9000), (i) => Math.sin(i * f) * (rnd() < 0.05 ? 0.3 : 1));
}

// ── instruments ──
const rec = (idx) => image.subarray(SAMPLEBIN + idx * 256, SAMPLEBIN + (idx + 1) * 256);
const w16 = (b, o, v) => { b[o] = v & 0xff; b[o + 1] = (v >>> 8) & 0xff; };
const w32 = (b, o, v) => { w16(b, o, v & 0xffff); w16(b, o + 2, (v >>> 16) & 0xffff); };

function envWord({ present = true, loop = false, start = 0, end = 0, carry = false, bit7 = false } = {}) {
  return (present ? 1 << 13 : 0) | (loop ? 1 << 5 : 0) | ((start & 0x1f) << 8) | (end & 0x1f) |
    (carry ? 1 << 6 : 0) | (bit7 ? 1 << 7 : 0);
}
function randEnv(b, o, maxVal) {
  const n = ri(2, 24);
  for (let k = 0; k < 25; k++) {
    b[o + 2 * k] = k < n ? ri(0, maxVal) : ri(0, maxVal);
    b[o + 2 * k + 1] = k < n - 1 ? (chance(0.15) ? 0 : ri(1, 90)) : 0;
  }
  return n;
}
function randLoopWords(n, extra = {}) {
  const s = ri(0, n - 1), e = ri(s, n - 1);
  const loop = envWord({ present: true, loop: chance(0.5), start: s, end: e, ...extra });
  const s2 = ri(0, n - 1), e2 = ri(s2, n - 1);
  const sus = chance(0.5) ? (1 << 5) | (s2 << 8) | e2 : 0;
  return [loop, sus];
}

const sampleInsts = [];
for (let idx = 1; idx <= 24; idx++) {
  const b = rec(idx);
  const s = samples[ri(0, samples.length - 1)];
  w32(b, 0, s.ptr);
  w16(b, 4, s.len);
  w16(b, 6, pick([8363, 16726, 22050, 44100, ri(2000, 60000)]));
  w16(b, 8, chance(0.2) ? ri(0, s.len >> 1) : 0);
  const ls = ri(0, s.len - 2), le = ri(ls + 1, s.len);
  w16(b, 10, ls); w16(b, 12, le);
  b[14] = ri(0, 3) | (chance(0.2) ? 4 : 0) | (chance(0.1) ? 0x20 : 0);
  let n = randEnv(b, 21, 63); { const [l, su] = randLoopWords(n, { carry: chance(0.2) }); w16(b, 15, l); w16(b, 189, su); }
  if (chance(0.5)) { n = randEnv(b, 71, 255); const [l, su] = randLoopWords(n, { bit7: chance(0.5) }); w16(b, 17, l); w16(b, 191, su); }
  else w16(b, 17, chance(0.5) ? 1 << 7 : 0);
  if (chance(0.5)) { n = randEnv(b, 121, 255); const [l, su] = randLoopWords(n, { bit7: chance(0.5) }); w16(b, 19, l); w16(b, 193, su); }
  if (chance(0.3)) { n = randEnv(b, 201, 255); const [l, su] = randLoopWords(n, { bit7: chance(0.5) }); w16(b, 197, l); w16(b, 199, su); }
  b[171] = ri(128, 255);
  b[172] = ri(0, 255);
  const sf = chance(0.3);
  b[173] = ri(0, 3) | (sf ? 0x10 : 0);
  b[174] = chance(0.3) ? ri(0, 40) : 0;
  b[175] = chance(0.4) ? ri(0, 255) : 0;
  b[176] = chance(0.3) ? ri(0, 40) : 0;
  b[177] = ri(0, 255);
  // Byte 14 bit 6, 8-bit volume envelopes (full volume 255, not 63), on a
  // quarter of them: picked by a byte already drawn, the nodes widened from
  // 0…63 to 0…255 in place, so every seed's random stream stays as it was.
  if ((b[177] & 3) === 0) {
    b[14] |= 0x40;
    for (let k = 0; k < 25; k++) b[21 + 2 * k] = (b[21 + 2 * k] << 2) | (b[21 + 2 * k] >> 4);
  }
  w16(b, 178, ri(0x3000, 0x7000));
  b[180] = chance(0.3) ? ri(0, 255) : 0;
  b[181] = chance(0.3) ? ri(0, 60) : 0;
  b[182] = chance(0.5) ? ri(0, 255) : 0xff;
  b[183] = ri(0, 255);
  w16(b, 184, chance(0.3) ? ri(0, 0xffff) : 0);
  b[186] = ri(0, 255);
  b[187] = chance(0.4) ? ri(0, 255) : 0;
  b[188] = chance(0.3) ? ri(0, 255) : 0;
  b[195] = chance(0.3) ? ri(0, 255) : 0;
  if (denseRepeat) b[195] = (b[195] & 0xcc) | 0x01;  // type 1 (same note), the action as drawn
  b[196] = chance(0.5) ? ri(1, 255) : 0;
  b[251] = chance(0.3) ? ri(0, 255) : 0;
  b[252] = ri(0, 255); b[253] = ri(0, 255); b[254] = ri(0, 255);
  sampleInsts.push(idx);
}

// The ordinary fields of a typed record (OPL type 8, formant type 1): bytes
// 4…196 and 251…255, the second pitch/filter slot left to the type's block.
function typedFields(b, s) {
  w16(b, 4, s ? s.len : ri(0, 0xffff));
  w16(b, 6, chance(0.3) ? 0 : pick([8363, 16726, 22050, ri(2000, 40000)]));
  w16(b, 8, 0);
  const len = s ? s.len : 64;
  const ls = ri(0, len - 2), le = ri(ls + 1, len);
  w16(b, 10, ls); w16(b, 12, le);
  b[14] = ri(0, 3) | (chance(0.2) ? 4 : 0);
  if (chance(0.5)) { const n = randEnv(b, 21, 63); const [l, su] = randLoopWords(n, { carry: chance(0.2) }); w16(b, 15, l); w16(b, 189, su); }
  else { for (let k = 0; k < 25; k++) b[21 + 2 * k] = chance(0.5) ? 0x3f : ri(0, 63); w16(b, 15, 0); }
  if (chance(0.3)) { const n = randEnv(b, 71, 255); const [l, su] = randLoopWords(n); w16(b, 17, l); w16(b, 191, su); }
  if (chance(0.3)) { const n = randEnv(b, 121, 255); const [l, su] = randLoopWords(n, { bit7: chance(0.5) }); w16(b, 19, l); w16(b, 193, su); }
  b[171] = ri(128, 255);
  b[172] = ri(0, 255);
  b[173] = ri(0, 3) | (chance(0.3) ? 0x10 : 0);
  b[174] = chance(0.3) ? ri(0, 40) : 0;
  b[175] = chance(0.3) ? ri(0, 255) : 0;
  b[177] = ri(0, 255);
  // Byte 14 bit 6, 8-bit volume envelopes (full volume 255, not 63), on a
  // quarter of them: picked by a byte already drawn, the nodes widened from
  // 0…63 to 0…255 in place, so every seed's random stream stays as it was.
  if ((b[177] & 3) === 0) {
    b[14] |= 0x40;
    for (let k = 0; k < 25; k++) b[21 + 2 * k] = (b[21 + 2 * k] << 2) | (b[21 + 2 * k] >> 4);
  }
  w16(b, 178, 0x5000);
  b[182] = chance(0.4) ? ri(0, 255) : 0xff;
  b[183] = ri(0, 255);
  w16(b, 184, chance(0.3) ? ri(0, 0xffff) : 0);
  b[186] = pick([0, 1, 1, 2, 3]) | (chance(0.2) ? 0x20 : 0) | (ri(0, 7) << 2);
  // FUZZ_RETRIG=1: Q's retrigger follows the NNA (byte 186 bit 6) on half the
  // instruments. Opt-in, so every existing seed keeps its stream.
  if (process.env.FUZZ_RETRIG && chance(0.5)) b[186] |= 0x40;
  b[187] = chance(0.3) ? ri(0, 255) : 0;
  b[188] = chance(0.3) ? ri(0, 255) : 0;
  b[195] = chance(0.5) ? ri(0, 255) : pick([0x04, 0x14, 0x24, 0x10, 0x02, 0x06]);
  b[196] = chance(0.5) ? ri(1, 255) : 0;
  b[251] = chance(0.3) ? ri(0, 255) : 0;
}

// OPL instruments (type 8): every kind, random registers.
const oplInsts = [];
for (let idx = 50; idx < 56; idx++) {
  const b = rec(idx);
  b.fill(0);
  b.set([0x80, 0x00, 0xff, 0xff], 0);
  typedFields(b, null);
  const blk = b.subarray(197, 251);
  blk[0] = (idx === 50 ? 0 : idx === 51 ? 1 : ri(0, 6)) | (ri(0, 15) << 3);
  blk[1] = ri(0, 15); blk[2] = ri(0, 1); blk[3] = ri(0, 15);
  w16(blk, 4, ri(-3000, 3000) & 0xffff);
  for (let k = 0; k < 4; k++) {
    const o = 6 + k * 5;
    blk[o] = ri(0, 255); blk[o + 1] = chance(0.5) ? ri(0, 63) : ri(0, 255);
    blk[o + 2] = (ri(1, 15) << 4) | ri(0, 15); blk[o + 3] = ri(0, 255); blk[o + 4] = ri(0, 7);
  }
  oplInsts.push(idx);
}

// PSG instruments (type 9): two oscillators, every waveform, both mixes,
// mutes, the chip envelope, tremolo/vibrato, X resets and the auto-advance.
const psgInsts = [];
for (let idx = 70; idx < 76; idx++) {
  const b = rec(idx);
  b.fill(0);
  b.set([0x90, 0x00, 0xff, 0xff], 0);
  typedFields(b, null);
  const blk = b.subarray(197, 251);
  blk[0] = (chance(0.5) ? 1 : 0) | (chance(0.15) ? 4 : 0) | (chance(0.15) ? 8 : 0) | (chance(0.1) ? ri(0, 255) & 0xf2 : 0);
  blk[1] = chance(0.2) ? ri(0, 255) : 0;
  for (let k = 0; k < 2; k++) {
    const o = 2 + k * 12;
    const wave = idx === 70 + k ? ri(1, 7) : ri(0, 15);
    blk[o] = (wave << 4) | ri(1, 15);
    blk[o + 1] = (ri(0, 15) << 4) | ri(0, 15);
    blk[o + 2] = (ri(1, 15) << 4) | ri(0, 15);
    blk[o + 3] = (ri(0, 3) << 6) | (chance(0.6) ? ri(0, 20) : ri(0, 63));
    blk[o + 4] = ri(0, 255);
    blk[o + 5] = chance(0.4) ? 0 : pick([1, 2, 4, 8, ri(1, 255)]);
    blk[o + 6] = ri(0, 3) | (chance(0.1) ? ri(0, 255) & 0xfc : 0);
    if (chance(0.1)) for (let j = 7; j < 12; j++) blk[o + j] = ri(0, 255);
  }
  psgInsts.push(idx);
}

// Formant instruments (type 1): a source, a vowel, consonant slots, a glide.
const CONS_IDS = [0, 0, 1, 2, 3, 4, 5, 7, 9, 10, 11, 13, 14, 15, 16, 32, 34, 37, 40, 41, 44, 45, 48, 49, 50, 51,
  52, 54, 55, 56, 58, 60, 62, 64, 66, 67, 68, 69, 70, 72, 74, 80, 81, 83, 85, 86, 88, 90, 96, 98, 100, 104, 105, 200,
  17, 18, 19, 20, 21, 75, 106, 107, 108, 109, 110, 111, 111];
const formantInsts = [];
for (let idx = 60; idx < 68; idx++) {
  const b = rec(idx);
  b.fill(0);
  b.set([0x10, 0x00, 0xff, 0xff], 0);
  // The source instrument (bits 8…9 in byte 0, 0…7 in byte 1): a chip one
  // sings from it; anything else (itself, a sample, a formant) is ignored.
  if (idx >= 64 || chance(0.25)) {
    const src = idx >= 66 ? pick(psgInsts) : idx >= 64 ? pick(oplInsts)
      : pick([...oplInsts, ...psgInsts, idx, 1, 61, 999]);
    b[0] |= (src >>> 8) & 3;
    b[1] = src & 0xff;
  }
  const s = samples[pick([0, 1, 2, 5, ri(0, samples.length - 1)])];
  typedFields(b, s);
  const blk = b.subarray(197, 251);
  blk[0] = s.ptr & 0xff; blk[1] = (s.ptr >>> 8) & 0xff; blk[2] = (s.ptr >>> 16) & 0xff;
  blk[3] = ri(0, 255); blk[4] = ri(0, 255); blk[5] = ri(0, 255);
  blk[6] = pick([0, 64, ri(0, 255)]);
  // The reference note (0 = no vowel: a syllabic consonant) and bit 15, a
  // nasalised vowel.
  w16(blk, 7, pick([0x4000, 0x5000, ri(0x3000, 0x7000), 0, 0]) | (chance(0.3) ? 0x8000 : 0));
  blk[9] = pick([0, 20, ri(0, 255)]);
  blk[10] = pick([128, ri(64, 192), ri(0, 255)]);
  const lo = pick([s.ptr, 16]);
  w16(blk, 11, lo & 0xffff); blk[13] = lo >>> 16;
  w16(blk, 14, (lo + 4096) & 0xffff); blk[16] = (lo + 4096) >>> 16;
  for (let k = 0; k < 3; k++) {
    const o = 17 + k * 12;
    const ns = samples[ri(0, samples.length - 1)];
    blk[o] = pick(CONS_IDS);
    if (chance(0.6)) {
      blk[o + 1] = ns.ptr & 0xff; blk[o + 2] = (ns.ptr >>> 8) & 0xff; blk[o + 3] = ns.ptr >>> 16;
      w16(blk, o + 4, ns.len); w16(blk, o + 6, chance(0.5) ? 0 : ri(0, ns.len));
      w16(blk, o + 8, chance(0.1) ? 0 : ri(2000, 48000));
    }
    blk[o + 10] = chance(0.5) ? 0 : ri(0, 255);
    blk[o + 11] = chance(0.5) ? 0 : ri(0, 255);
  }
  // CCGVRC's G (bits 0–2), the onset's secondary articulation (3–4), the
  // coda's release (5–6) and R (bit 7).
  blk[53] = (chance(0.5) ? ri(0, 7) : 0) | (ri(0, 3) << 3) | (ri(0, 3) << 5) | (chance(0.3) ? 0x80 : 0);
  // Now and then nothing to say at all: no vowel and no consonant.
  if (chance(0.08)) { w16(blk, 7, 0); blk[17] = 0; blk[29] = 0; blk[41] = 0; }
  formantInsts.push(idx);
}

// Layered metainstruments (type 0) and FM racks (type 4).
// (An FM rack's bit 0 is the editor's Easy Mode mark, not strict layering.)
function metaRecord(idx, type, layers, program, strict) {
  const b = rec(idx);
  b.fill(0);
  b[0] = ((type & 0xf) << 4) | (strict ? 1 : 0);
  b[1] = layers.length;
  b[2] = 0xff; b[3] = 0xff;
  let o = 4;
  for (const l of layers) {
    b[o] = l.inst & 0xff;
    b[o + 1] = l.mix;
    w16(b, o + 2, l.detune & 0xffff);
    w16(b, o + 4, l.ps); w16(b, o + 6, l.pe);
    b[o + 8] = (l.vs & 0x3f) | (((l.inst >>> 8) & 3) << 6);
    b[o + 9] = (l.ve & 0x3f) | (l.fixed ? 0x40 : 0);
    o += 10;
  }
  if (program) {
    // The program area ends at byte 253: byte 255 is the record's IMix id.
    for (const w of program) { if (o + 2 > 254) break; w16(b, o, w); o += 2; }
    if (o + 2 <= 254) w16(b, o, 0xffff);
  }
}
for (let idx = 30; idx < 34; idx++) {
  const nL = ri(1, 4);
  const layers = [];
  for (let k = 0; k < nL; k++) {
    const ps = chance(0.7) ? 0 : ri(0, 0x8000);
    layers.push({ inst: chance(0.75) ? pick(sampleInsts) : pick([...oplInsts, ...psgInsts, ...formantInsts]), mix: ri(100, 200), detune: ri(-2000, 2000),
      ps, pe: chance(0.7) ? 0xffff : ri(ps, 0xffff), vs: 0, ve: chance(0.8) ? 63 : ri(0, 63),
      fixed: chance(0.2) });
  }
  if (layers[0].fixed) layers[0].detune = ri(0x3000, 0x7000);
  for (const l of layers) if (l.fixed) l.detune = ri(0x3000, 0x7000);
  metaRecord(idx, 0, layers, null, chance(0.2));
}
for (let idx = 40; idx < 43; idx++) {
  const nOps = ri(2, 4);
  const ops = [];
  for (let k = 0; k < nOps; k++) {
    // Chip operators (OPL, PSG) are read with phase modulation; a formant
    // instrument is a high meta and is refused.
    ops.push({ inst: chance(0.7) ? pick(sampleInsts.slice(0, 6)) : pick([...oplInsts, ...psgInsts, ...formantInsts]), mix: ri(120, 200), detune: ri(-300, 3000),
      ps: 0, pe: 0xffff, vs: 0, ve: 63, fixed: false });
  }
  // FUZZ_FIXED=1: fixed-frequency operators (item 278, the DX7's FIXED mode),
  // operator 0 included. Opt-in, so the seeds without it keep their streams.
  if (process.env.FUZZ_FIXED) {
    for (const o of ops) if (chance(0.35)) { o.fixed = true; o.detune = ri(0x2000, 0x8000); }
  }
  const progs = [
    [nOps - 1, 0x0400 | 0],
    [0x0800 | 0, 0x0400 | 0],
    [1, 0x0400 | 0, 0, 0xff00],
    [1, 0xff03, 0x0400 | 0, 0xff01],
    [nOps - 1, 0xff02, 0x0400 | 0],
    // A DSP-only principal (Easy Mode's layout): S $Apsd counts past it.
    [1, 0x0000, 0xff01],
    [1, 2, 0xff00, 0x0000, 0xff01],
  ];
  metaRecord(idx, 4, ops, pick(progs).map((w) => (w & 0xff00) === 0xff00 ? w : (w & ~0x3ff) | Math.min(w & 0x3ff, nOps - 1)), chance(0.5));
}
// A full rack: sixteen operators and a program that runs to byte 253 with no
// END — the area's last word — or one word short of it.
{
  const ops = [];
  for (let k = 0; k < 16; k++) {
    ops.push({ inst: pick(sampleInsts.slice(0, 6)), mix: ri(60, 120), detune: ri(-300, 3000),
      ps: 0, pe: 0xffff, vs: 0, ve: 63, fixed: false });
  }
  // 16 × 10 bytes of rack leave bytes 164…253: 45 words. Either all 45 (no
  // END: the area's end ends it) or 44 and END in the area's last word.
  const prog = [1];
  for (let k = 0; k < 21; k++) prog.push(2 + (k % 14), 0xff00);
  prog.push(chance(0.5) ? 0xff03 : 15, ...(chance(0.5) ? [0xff00] : []));
  metaRecord(43, 4, ops, prog, chance(0.5));
}
// Tables of kinds the engine has no reading for (types 2, 3, 5, 7, 10…15):
// the Layered shape, sounding nothing.
for (let idx = 34; idx < 36; idx++) {
  metaRecord(idx, pick([2, 3, 5, 7, 10, 11, 12, 13, 14, 15]),
    [{ inst: pick(sampleInsts), mix: 160, detune: 0, ps: 0, pe: 0xffff, vs: 0, ve: 63, fixed: false }], null, false);
}

// ── Ixmp patches ──
function patchRecord(p) {
  const bytes = [];
  let ver = 0x01;
  if (p.extra) ver |= 0x80;
  if (p.vol) ver |= 0x02;
  if (p.chan) ver |= 0x20;
  const b8 = (v) => bytes.push(v & 0xff);
  const b16 = (v) => { b8(v); b8(v >>> 8); };
  const b32 = (v) => { b16(v & 0xffff); b16(v >>> 16); };
  b8(ver); b16(p.ps); b16(p.pe); b8(p.vs); b8(p.ve);
  b32(p.ptr); b16(p.len); b16(p.play); b16(p.ls); b16(p.le); b16(p.rate); b16(p.detune & 0xffff);
  b8(p.loop); b8(p.pan); b8(p.dnv); b8(p.vs1); b8(p.vs2); b8(p.vd); b8(p.vr); b8(p.vw);
  if (p.extra) { b32(p.sf ? 1 : 0); b32(0); b16(p.fade); b16(p.cut); b16(p.res); b8(p.atten); }
  if (p.vol) { b16(envWord({ loop: true, start: 1, end: 3 })); b16(0); for (let k = 0; k < 25; k++) { b8(ri(0, 63)); b8(k < 5 ? ri(1, 60) : 0); } }
  if (p.chan) { b8((1 << 4) | p.chanMode); b8(0); b8(0); b8(0); b32(p.ptr2); }
  return bytes;
}
const ixmp = [];
for (const idx of [...sampleInsts.slice(0, 10), oplInsts[0], psgInsts[0], formantInsts[0]]) {
  if (!chance(0.6)) continue;
  const n = ri(1, 3);
  const bytes = [];
  const pitch = [];   // each patch's three pitch words, for an IPit guard
  for (let k = 0; k < n; k++) {
    const s = samples[ri(0, samples.length - 1)], s2 = samples[ri(0, samples.length - 1)];
    const len = Math.min(s.len, s2.len);
    const ls = ri(0, len - 2);
    const pr = {
      ps: k === n - 1 ? 0 : ri(0, 0x6000), pe: 0xffff, vs: 0, ve: 63,
      ptr: s.ptr, len, play: 0, ls, le: ri(ls + 1, len), rate: pick([8363, 22050, 44100]),
      detune: ri(-500, 500), loop: ri(0, 3), pan: chance(0.5) ? 0xff : ri(0, 254), dnv: chance(0.5) ? 0 : ri(1, 255),
      vs1: 0, vs2: 0, vd: 0, vr: 0, vw: 0xff,
      extra: chance(0.5), sf: chance(0.5), fade: ri(0, 200), cut: ri(0, 0xffff), res: ri(0, 0x3ff), atten: ri(0, 255),
      vol: chance(0.3), chan: chance(0.4), chanMode: ri(0, 1), ptr2: s2.ptr,
    };
    bytes.push(...patchRecord(pr));
    pitch.push([pr.ps, pr.pe, pr.detune & 0xffff]);
  }
  ixmp.push({ inst: idx, count: n, bytes, pitch });
}

// ── patterns ──
// S $9xxx, formant control: nasal/rhotic set (and the coda called), a glide
// time (0 included), now and then a reserved argument.
const s9Arg = () => pick([0x9000 | ri(0, 3), 0x9000 | ri(0, 3), 0x9010 | ri(0, 3), 0x9010 | ri(0, 3),
  0x9100 | ri(0, 0xff), 0x9100 | ri(0, 20), 0x9100, 0x9000 | ri(0, 0xfff)]);
// FUZZ_S9=1 makes it half of every effect column command, on far more rows.
const denseS9 = process.env.FUZZ_S9 === "1";
// FUZZ_CUTS=1 does the same for S $70…$76 — the past-note actions (S $70 cuts
// the lane's ghosts, which must ramp out) and the New Note Action overrides
// (S $73 on a layered note cuts every layer on the next one). With
// FUZZ_INSTS=layered the notes are the layered metas and plain samples.
const denseCuts = process.env.FUZZ_CUTS === "1";
// FUZZ_GLIDES=1: tone portamentos (G, L) on lanes that keep going silent —
// a quarter of the notes are cuts — so a G/L often lands with nothing
// sounding and triggers as its note's glide (a lyric instrument then says its
// syllable again and keeps its place). Half of those, in a wide-cell song, are
// delayed by an S $Dx in the second effect column. Pair it with
// FUZZ_LYRIC=1 FUZZ_INSTS=formant.
const denseGlides = process.env.FUZZ_GLIDES === "1";
const cutArg = () => pick([0x7000, 0x7000, 0x7000, 0x7100, 0x7200, 0x7300, 0x7300, 0x7300, 0x7400, 0x7500, 0x7600]);
const EFFECTS = [
  [0x01, () => (pick([0, 1, 2, 4, 8, 12, 16, 20, 5, 9]) << 8)],
  [0x02, () => ri(0, 0xffff)], [0x03, () => ri(0, 0xffff)],
  [0x05, () => chance(0.2) ? 0xffff : ri(0, 0xffff)], [0x06, () => chance(0.2) ? 0xffff : ri(0, 0xffff)],
  [0x07, () => (ri(1, 4) << 8) | ri(1, 4)],
  [0x08, () => ri(0, 0xffff)], [0x09, () => (ri(0, 3) << 12) | ri(0, 255)],
  [0x0a, () => ri(1, 12) << 8],
  [0x0b, () => ri(0, 1)], [0x0c, () => ri(0, 63)],
  [0x0d, () => ri(0, 255) << 8], [0x0e, () => chance(0.3) ? 0xf000 | ri(0, 0xfff) : ri(0, 0x200)],
  [0x0f, () => chance(0.3) ? 0xf000 | ri(0, 0xfff) : ri(0, 0x200)],
  [0x10, () => ri(0, 0x400)], [0x11, () => ri(0, 0xffff)], [0x12, () => ri(0, 0x0f0f)],
  [0x13, () => ri(0, 0xffff)], [0x14, () => ri(0, 255) << 8], [0x15, () => ri(0, 255) << 8],
  [0x16, () => ri(0, 70) << 8], [0x17, () => ri(0, 255) << 8],
  [0x18, () => ri(0, 0x2000)], [0x19, () => ri(0, 255) << 8],
  [0x1a, () => ri(0, 255) << 8], [0x1b, () => ri(0, 0xffff)],
  [0x1c, () => {
    const sub = pick([0, 1, 2, 3, 4, 5, 6, 7, 7, 8, 9, 9, 9, 0xa, 0xa, 0xa, 0xb, 0xc, 0xd, 0xe, 0xf]);
    // S $9 on a formant voice: nasal/rhotic set (and the coda called), a glide
    // time (0 included), now and then a reserved argument.
    if (sub === 9) return s9Arg();
    // S $A on a PSG voice: an auto-advance, a level, the mutes.
    if (sub === 0xa && chance(0.5)) {
      return pick([0xa000 | ri(0, 0xff), 0xa100 | ri(0, 0x3f), 0xa800 | ri(0, 0xff), 0xa900 | ri(0, 0x3f),
        0xaf00 | ri(0, 3), 0xaf00 | ri(0, 0xff), 0xa200 | ri(0, 0xff)]);
    }
    if (sub === 0xd) return 0xd000 | (ri(0, 5) << 8) | (ri(0, 4) << 4) | ri(0, 4);
    if (sub === 0xb) return 0xb000 | (ri(0, 2) << 8);
    if (sub === 0xe) return 0xe000 | (ri(0, 2) << 8);
    return (sub << 12) | ri(0, 0xfff);
  }],
  [0x1d, () => pick([ri(0x20, 0xff) << 8, 0xff00 | ri(0, 255), ri(0, 0x1f), (ri(0xfb, 0xfe) << 8) | ri(0, 255)])],
  [0x1e, () => ri(0, 0xffff)], [0x1f, () => ri(0, 255) << 8],
  [0x20, () => ri(0, 255) << 8], [0x21, () => ri(0, 0xffff)], [0x22, () => ri(0, 0xffff)],
  [0x23, () => chance(0.5) ? 0xf000 | ri(0, 0xfff) : ri(0, 0xfff)],
  [0x04, () => ri(0, 0xffff)],
];
/** G (seven times in ten) or L, with the argument EFFECTS draws for it. */
const glideFx = () => { const op = chance(0.7) ? 0x10 : 0x15; return EFFECTS.find((x) => x[0] === op); };
const ROWS = 64;
const cellBytes = wide ? 16 : 8;
function randNote() {
  const r = rnd();
  if (r < 0.04) return 1;
  if (r < 0.06) return 2;
  if (r < 0.07) return 3;
  if (r < 0.08) return 4;
  if (r < 0.10) return ri(0x10, 0x1f);
  return denseRepeat ? pick(REPEAT_NOTES) : ri(0x3000, 0x7000);
}
// FUZZ_WILD=1 overwrites every OPL, PSG and formant block with random bytes
// (for the sanitizer build: pointers, lengths and rates anywhere at all).
if (process.env.FUZZ_WILD) {
  for (const idx of [...oplInsts, ...psgInsts, ...formantInsts]) {
    const blk = rec(idx).subarray(197, 251);
    for (let k = 0; k < blk.length; k++) blk[k] = ri(0, 255);
    if (formantInsts.includes(idx)) for (let k = 4; k < 15; k++) rec(idx)[k] = ri(0, 255);
  }
}

// FUZZ_INSTS=opl|psg|formant|fm narrows the patterns to one kind, to isolate
// a failure.
const instChoices = process.env.FUZZ_INSTS === "opl" ? oplInsts
  : process.env.FUZZ_INSTS === "psg" ? psgInsts
  : process.env.FUZZ_INSTS === "formant" ? formantInsts
  : process.env.FUZZ_INSTS === "fm" ? [40, 41, 42, 43]
  : process.env.FUZZ_INSTS === "layered" ? [30, 31, 32, 33, 30, 31, 32, 33, ...sampleInsts]
  : denseRepeat ? sampleInsts.slice(0, 3)
  : [...sampleInsts, 30, 31, 32, 33, 34, 35, 40, 41, 42, 43, ...oplInsts, ...oplInsts, ...psgInsts, ...psgInsts,
    ...formantInsts, ...formantInsts];

// ── instrument mixing chains (IMix) ──
// FUZZ_IMIX=0 leaves them out. Blocks are encoded by the JS library's own
// codec, then now and then corrupted, so both readers' strictness is tested
// too: a contradiction drops the block, an out-of-range value is clamped.
const imixSection = [];
if (process.env.FUZZ_IMIX !== "0" && chance(process.env.FUZZ_IMIX ? 1 : 0.7)) {
  const f = (lo, hi) => lo + (hi - lo) * rnd();
  const lfo = () => {
    const sync = chance(0.4);
    return { wave: ri(0, 5), retrigger: chance(0.5), sync, rate: sync ? ri(1 << 16, 64 << 16) : ri(655, 12 << 16), depth: rnd() };
  };
  const eqBand = () => {
    const shape = ri(0, 5);
    return { on: chance(0.85), shape, slope: shape === 0 || shape === 4 ? ri(0, 3) : 0,
      freq: f(25, 18000), gainDb: f(-18, 18), q: f(0.2, 8) };
  };
  const compBand = () => ({ on: chance(0.85), threshDb: f(-45, -3), ratio: f(1, 12), attackMs: f(0.2, 80),
    releaseMs: f(10, 600), kneeDb: f(0, 12), makeupDb: f(-3, 9) });
  const deqBand = () => {
    const freq = f(40, 12000), q = f(0.3, 6), unlinked = chance(0.4);
    return { on: chance(0.6), unlinked, boost: chance(0.3), freq, q, gainDb: f(-9, 9),
      senseFreq: unlinked ? f(40, 12000) : freq, senseQ: unlinked ? f(0.3, 6) : q,
      threshDb: f(-50, -5), ratio: f(1.2, 10), attackMs: f(0.5, 50), releaseMs: f(10, 400),
      rangeDb: f(0, 18), actorDb: f(-12, 12) };
  };
  const gen = {
    itrm: () => ({ gainDb: f(-12, 12) }),
    equ1: () => ({ bands: Array.from({ length: ri(0, 8) }, eqBand) }),
    deq1: () => ({ bands: Array.from({ length: 6 }, deqBand) }),
    comp: () => {
      const n = ri(1, 6);
      const xs = [];
      let x = 60;
      for (let i = 1; i < n; i++) { x *= f(1.6, 4); xs.push(x); }
      return { detector: ri(0, 1), crossovers: xs, bands: Array.from({ length: n }, compBand) };
    },
    satu: () => ({ curve: ri(0, 4), driveDb: f(0, 24), inDb: f(-6, 6), outDb: f(-12, 3), bias: chance(0.5) ? 0 : f(-0.6, 0.6) }),
    unis: () => ({ voices: ri(2, 8), detune: f(0, 60), width: rnd(), spacing: chance(0.5) ? 0 : rnd(),
      detuneLfo: lfo(), widthLfo: lfo() }),
    verb: () => ({ size: rnd(), decay: f(0.2, 6), damping: f(1500, 18000), preDelay: ri(0, 20000) }),
    dlay: () => {
      const sync = chance(0.5);
      const pingPong = chance(0.5), time = sync ? ri(1 << 14, 96 << 16) : ri(66, 1 << 16);
      // Right first (item 301) from the time's parity: no draw of its own, so
      // every seed's random stream is what it was before.
      return { sync, pingPong, pingRight: (time & 1) === 1, time,
        feedback: f(0, 1.1), highCut: f(800, 18000), lowCut: f(30, 900), wow: chance(0.3) ? 0 : rnd(), driveDb: f(0, 18) };
    },
    limi: () => ({ truePeak: chance(0.5), ceilingDb: f(-18, 0), releaseMs: f(5, 400), gainDb: f(-6, 12) }),
    // The expander: no floor (−∞), a floor at the threshold (a pure gate), or one under it.
    expd: () => {
      const threshDb = f(-70, -5);
      const floorDb = chance(0.4) ? -Infinity : chance(0.35) ? threshDb : f(Math.max(-80, threshDb - 30), threshDb);
      return { threshDb, floorDb, ratio: f(1, 8), rangeDb: f(0, 70), attackMs: f(0.1, 40), holdMs: chance(0.3) ? 0 : f(0, 200),
        releaseMs: f(5, 600), hysteresisDb: f(0, 10), detector: ri(0, 1), keyFilter: chance(0.4),
        keyLowCut: f(20, 800), keyHighCut: f(800, 18000) };
    },
    tsh1: () => {
      const n = ri(1, 6);
      const xs = [];
      let x = 60;
      for (let i = 1; i < n; i++) { x *= f(1.6, 4); xs.push(x); }
      return { crossovers: xs, bands: Array.from({ length: n }, () => ({ on: chance(0.85), attackDb: f(-18, 18),
        sustainDb: f(-18, 18), attackMs: f(1, 120), releaseMs: f(10, 1500), outDb: f(-6, 6) })) };
    },
    mxtl: () => ({ swap: chance(0.3), invL: chance(0.3), invR: chance(0.3), removeDc: chance(0.5),
      gainDb: f(-9, 9), width: chance(0.4) ? 1 : f(0, 2) }),
  };
  gen.equ2 = gen.equ1;
  gen.deq2 = gen.deq1;
  // FUZZ_IMIX_KINDS=tsh1,expd draws the equipment from those alone.
  const KINDS = process.env.FUZZ_IMIX_KINDS ? process.env.FUZZ_IMIX_KINDS.split(",") : Object.keys(gen);
  const ids = [];
  const nBlocks = ri(1, 4);
  for (let k = 0; k < nBlocks; k++) {
    const id = chance(0.1) && ids.length ? pick(ids) : ri(1, 12);   // now and then an id twice
    ids.push(id);
    const equipment = {};
    const nEq = chance(0.15) ? 0 : ri(1, 5);
    for (let q = 0; q < nEq; q++) {
      const kind = pick(KINDS);
      equipment[kind] = { bypass: chance(0.1), folded: chance(0.2), dry: chance(0.5) ? 0 : ri(0, 255), ...gen[kind]() };
    }
    const unknown = chance(0.15) ? [{ fourcc: pick(["zzzz", "Xtra"]), bytes: Uint8Array.from({ length: ri(4, 20) }, () => ri(0, 255)) }] : [];
    let bytes = IMX.buildImixBlock({ id, enabled: chance(0.9), bus: chance(0.35), order: ri(0, 1), equipment, unknown });
    if (chance(0.06) && bytes.length > 12) {
      // A known piece declared at a version this reader does not know.
      bytes = Uint8Array.from(bytes);
      bytes[4 + 4 * bytes[3] + 8] |= ri(1, 15);
    }
    if (chance(0.12)) {
      bytes = Uint8Array.from(bytes);
      const n = ri(1, 3);
      for (let i = 0; i < n; i++) {
        const at = ri(0, bytes.length - 1);
        bytes[at] = chance(0.5) ? ri(0, 255) : bytes[at] ^ (1 << ri(0, 7));
      }
    }
    imixSection.push(...bytes);
  }
  if (chance(0.05)) imixSection.push(...Array.from({ length: ri(1, 6) }, () => ri(0, 255)));  // trailing junk
  // Byte 255 of the records: the block, one no block has, or none.
  const targets = [...sampleInsts, 30, 31, 32, 33, 34, 35, 40, 41, 42, 43, ...oplInsts, ...psgInsts, ...formantInsts];
  for (const idx of targets) {
    if (chance(0.45)) rec(idx)[255] = chance(0.9) ? pick(ids) : ri(13, 255);
  }
}
function makePattern() {
  const p = new Uint8Array(ROWS * cellBytes);
  for (let r = 0; r < ROWS; r++) {
    const o = r * cellBytes;
    const busy = chance(0.35);
    const note = busy ? (denseGlides && chance(0.25) ? 2 : randNote()) : (chance(0.1) ? 0 : 0);
    const inst = busy && chance(0.8) ? pick(instChoices) : (chance(0.05) ? pick(instChoices) : 0);
    w16(p, o, note);
    p[o + 2] = inst;
    const volEff = chance(0.6) ? 0 : ri(1, 3);
    const hasFx = chance(denseS9 || denseCuts || denseGlides ? 0.8 : 0.45);
    const [op, argf] = !hasFx ? [0, () => 0] : denseS9 && chance(0.5) ? [0x1c, s9Arg]
      : denseCuts && chance(0.5) ? [0x1c, cutArg]
      : denseGlides && chance(0.6) ? glideFx() : pick(EFFECTS);
    const arg = hasFx ? argf() : 0;
    if (!wide) {
      p[o + 3] = (chance(0.5) ? ri(0, 63) : 0) | ((chance(0.5) ? 3 : volEff) << 6);
      p[o + 4] = (chance(0.4) ? ri(0, 63) : 0) | ((chance(0.6) ? 3 : ri(0, 2)) << 6);
      p[o + 5] = op;
      w16(p, o + 6, arg);
    } else {
      p[o + 3] = chance(0.5) ? ri(0, 255) : 0;
      const az = ri(0, 511);
      p[o + 4] = az & 0xff;
      p[o + 5] = op;
      w16(p, o + 6, arg);
      const panEff = chance(0.6) ? 3 : ri(0, 2);
      p[o + 8] = ((az >>> 8) << 7) | ((chance(0.5) ? 3 : volEff) << 4) | panEff;
      p[o + 9] = ri(0, 255);
      // Byte 13: the note's sixteenths (high nibble — a wide cell's pitch is
      // 65536-TET) on half the cells, sentinels and empty notes included, and
      // junk in the RESERVED low nibble. Read off bytes already drawn, so the
      // random stream stays as it was.
      const fine = denseRepeat && REPEAT_FINE.has(note) ? REPEAT_FINE.get(note)
        : (p[o + 9] & 0x10) !== 0 ? p[o + 9] & 0x0f : 0;
      p[o + 13] = (fine << 4) | (p[o + 4] & 0x0f);
      if (denseGlides && (op === 0x10 || op === 0x15) && chance(0.5)) {
        p[o + 10] = 0x1c; w16(p, o + 11, 0xd000 | (ri(1, 3) << 8));
      } else if (denseRepeat && (p[o + 9] & 0xc0) === 0xc0) {
        // Glissando on (S $11) on one row in eight and off (S $10) on another
        // one in eight: it snaps the four pitches to their semitones.
        p[o + 10] = 0x1c; w16(p, o + 11, (p[o + 9] & 0x20) !== 0 ? 0x1100 : 0x1000 | (p[o + 9] & 0xff));
      } else if (chance(0.3)) {
        if (chance(0.4)) { p[o + 10] = 0xba; w16(p, o + 11, ri(0, 0xffff)); }
        else { const [op2, a2] = pick(EFFECTS); p[o + 10] = op2; w16(p, o + 11, a2()); }
      }
    }
  }
  return p;
}

const songs = [];
const nSongs = ri(1, 2);
for (let s = 0; s < nSongs; s++) {
  const nPats = ri(3, 8);
  const pats = [];
  for (let k = 0; k < nPats; k++) pats.push(makePattern());
  const nCues = ri(3, 6);
  const chans = lanes64 ? 64 : 32;
  const stride = chans * 2;
  const cues = new Uint8Array(nCues * stride);
  for (let c = 0; c < nCues; c++) {
    const words = new Array(chans).fill(0x7fff);
    for (let ch = 0; ch < numLanes; ch++) words[ch] = chance(0.9) ? ri(0, nPats - 1) : 0x7fff;
    let w0 = 0;
    if (c === nCues - 1) w0 = 0x0100;                       // HALT
    else if (chance(0.15)) w0 = 0x0200 | ri(8, 40);          // pattern length
    else if (chance(0.03)) w0 = 0x0100 | 0x40 | ri(8, 50);   // halt at x
    for (let ch = 0; ch < 16; ch++) if ((w0 >> ch) & 1) words[ch] |= 0x8000;
    for (let ch = 0; ch < chans; ch++) w16(cues, c * stride + ch * 2, words[ch]);
  }
  songs.push({ pats, cues, nPats, nCues, bpm: chance(0.25) ? ri(537, 1048) : ri(80, 200), tick: ri(3, 8),
    flags: pick([0, 0, 1, 2, 4, 8, 12, 16, 20]), gv: ri(64, 255), mv: ri(64, 255),
    tuneNote: pick([0, 0xa000, 0x5000]), tuneFreq: pick([0, 8363, 261.6255653005986, 440]) });
}

// ── assemble ──
const LEVEL = process.env.TAUD_GZIP_LEVEL === undefined ? 6 : +process.env.TAUD_GZIP_LEVEL;
const imageComp = gzipSync(image, { mtime: 0, level: LEVEL });
const header = new Uint8Array(32);
header.set([0x1f, 0x54, 0x53, 0x56, 0x4d, 0x61, 0x75, 0x64]);
header[8] = fmtVer | (lanes64 ? 0x20 : 0);
header[9] = songs.length;
w32(header, 10, imageComp.length);
const sig = "taudplay-fuzz ";
for (let i = 0; i < 14; i++) header[18 + i] = sig.charCodeAt(i);

const tableOff = 32 + imageComp.length;
const table = new Uint8Array(songs.length * 32);
const songBlobs = [];
let off = tableOff + table.length;
songs.forEach((s, i) => {
  const patBin = new Uint8Array(s.nPats * ROWS * cellBytes);
  s.pats.forEach((p, k) => patBin.set(p, k * ROWS * cellBytes));
  const pc = gzipSync(patBin, { mtime: 0, level: LEVEL });
  const cc = gzipSync(s.cues, { mtime: 0, level: LEVEL });
  const e = i * 32;
  w32(table, e, off);
  table[e + 4] = numLanes;
  w16(table, e + 5, s.nPats);
  const bpmStored = s.bpm - 25;
  table[e + 7] = bpmStored & 0xff;
  table[e + 8] = s.tick | ((bpmStored >> 8) & 1) << 7;
  w16(table, e + 9, s.tuneNote);
  new DataView(table.buffer).setFloat32(e + 11, s.tuneFreq, true);
  table[e + 15] = s.flags;
  table[e + 16] = s.gv;
  table[e + 17] = s.mv;
  w32(table, e + 18, pc.length);
  w32(table, e + 22, cc.length);
  w16(table, e + 26, s.nCues);
  table[e + 28] = surround | ((bpmStored >> 9) & 1) << 7;
  songBlobs.push(pc, cc);
  off += pc.length + cc.length;
});

// Project data: PNam, xHDR, Ixmp, sMst.
const sections = [];
const sec = (fc, payload) => {
  const h = new Uint8Array(8);
  for (let i = 0; i < 4; i++) h[i] = fc.charCodeAt(i);
  w32(h, 4, payload.length);
  sections.push(h, payload);
};
sec("PNam", new TextEncoder().encode(`fuzz ${seedArg}\0`));
if (lanes64) sec("xHDR", Uint8Array.from([1]));
if (ixmp.length) {
  const bytes = [];
  for (const e of ixmp) { bytes.push(e.inst & 0xff, e.count & 0xff, (e.count >>> 8) & 0xff, (e.inst >>> 8) & 3, ...e.bytes); }
  sec("Ixmp", Uint8Array.from(bytes));
}
if (chance(0.6)) {
  const blk = new Uint8Array(160);
  const dv = new DataView(blk.buffer);
  const f = (o, v) => dv.setFloat32(o, v, true);
  blk[0] = 1; blk[1] = 1;
  f(4, (rnd() - 0.5) * 12);
  blk[8] = (chance(0.5) ? 1 : 0) | (chance(0.5) ? 2 : 0); f(12, ri(10, 300));
  blk[16] = chance(0.6) ? 1 : 0;
  for (let b = 0; b < 4; b++) {
    const o = 20 + b * 16;
    blk[o] = chance(0.7) ? 1 : 0; blk[o + 1] = ri(0, 2);
    f(o + 4, ri(40, 12000)); f(o + 8, (rnd() - 0.5) * 24); f(o + 12, 0.3 + rnd() * 3);
  }
  blk[84] = (chance(0.6) ? 1 : 0) | (chance(0.5) ? 2 : 0);
  f(88, -ri(3, 30)); f(92, 1 + rnd() * 6); f(96, 1 + rnd() * 40); f(100, 20 + rnd() * 400); f(104, rnd() * 10); f(108, rnd() * 6);
  blk[112] = chance(0.5) ? 1 : 0; f(116, rnd() * 2);
  blk[120] = (chance(0.6) ? 1 : 0) | (chance(0.5) ? 2 : 0); f(124, -rnd() * 6); f(128, 10 + rnd() * 200);
  f(132, (rnd() - 0.5) * 6);
  const payload = new Uint8Array(5 + 160);
  payload[0] = 0; w32(payload, 1, 160); payload.set(blk, 5);
  sec("sMst", payload);
}
if (imixSection.length) sec("IMix", Uint8Array.from(imixSection));

// ── lyric instruments (ILyr) ──
// Drawn last, so the rest of a seed's song is what it was before lyrics.
// FUZZ_LYRIC=0 leaves them out, FUZZ_LYRIC=1 always has them. Entries go to
// formant instruments mostly, now and then to another kind (ignored); recipes
// repeat a consonant (the first wins) and leave some unread (the generator);
// a second entry for an instrument, an empty one, a short one and a
// truncated tail are all dropped by a reader.
if (process.env.FUZZ_LYRIC !== "0" && chance(process.env.FUZZ_LYRIC ? 1 : 0.6)) {
  const bytes = [];
  const targets = [...formantInsts, ...formantInsts, ...formantInsts, pick(sampleInsts), pick(oplInsts)];
  const syllable = () => ({
    front: ri(0, 255), open: ri(0, 255), round: ri(0, 255),
    vowel: chance(0.85), nasal: chance(0.2),
    gr: (chance(0.5) ? ri(0, 7) : 0) | (ri(0, 3) << 3) | (ri(0, 3) << 5) | (chance(0.3) ? 0x80 : 0),
    cons: [pick(CONS_IDS), pick(CONS_IDS), pick(CONS_IDS)],
    timing: [0, 0, 0, 0].map(() => pick([128, ri(64, 192), ri(0, 255)])),
  });
  const recipe = () => {
    const ns = samples[ri(0, samples.length - 1)];
    return {
      cons: pick(CONS_IDS), ptr: ns.ptr, len: ns.len, offset: chance(0.5) ? 0 : ri(0, ns.len),
      rate: chance(0.1) ? 0 : ri(2000, 48000), tilt: chance(0.5) ? 0 : ri(-128, 127), gain: chance(0.5) ? 0 : ri(0, 255),
    };
  };
  const n = ri(2, 6);
  for (let k = 0; k < n; k++) {
    const entry = {
      instId: pick(targets),
      syllables: Array.from({ length: pick([1, 2, ri(3, 12)]) }, syllable),
      recipes: Array.from({ length: ri(0, 5) }, recipe),
    };
    const b = LYR.buildLyricEntry(entry);
    if (chance(0.08)) { b[6] = 0; b[7] = 0; }                     // no syllable: invalid
    else if (chance(0.06)) { b[6] = 0xff; b[7] = 0x00; }          // more syllables than bytes
    bytes.push(...b);
  }
  if (chance(0.1)) bytes.push(...[ri(0, 255), 0, ri(1, 255), 0, 0, 0]);   // truncated tail
  sec("ILyr", Uint8Array.from(bytes));
}

// ── pitch extensions (IPit, file format §9.18) ──
// Drawn after everything else, so the rest of a seed's song is what it was
// before them. FUZZ_IPIT=0 leaves them out, FUZZ_IPIT=1 always has them. An
// entry gives a sample instrument its base fields (sample detune, pitch-pan
// centre) and its Ixmp patches' ranges and detunes, an OPL instrument its
// rhythm partner, a formant one its reference note (lyrics included), a
// layered table or a rack its entries' detunes and ranges. A delta byte is
// mostly a fraction (the cell's sixteenths meet the ranges' edges), now and
// then sixteen octaves up or down (a layer or an operator out of a note
// value's range: voice.pitchExcess). Some guards are wrong, some groups do
// not fit the instrument (both ignored whole), a later entry for an
// instrument replaces the earlier, and a reserved flag or a cut-off group
// ends the read.
if (process.env.FUZZ_IPIT !== "0" && chance(process.env.FUZZ_IPIT ? 1 : 0.5)) {
  const crc16 = (crc, bytes) => {
    for (const x of bytes) {
      crc ^= x << 8;
      for (let k = 0; k < 8; k++) crc = ((crc & 0x8000) !== 0 ? (crc << 1) ^ 0x1021 : crc << 1) & 0xffff;
    }
    return crc;
  };
  const le = (w) => [w & 0xff, (w >>> 8) & 0xff];
  const word = (b, o) => b[o] | (b[o + 1] << 8);
  const delta = () => (chance(0.08) ? pick([0x10, 0xf0, 0x10 | ri(0, 15), 0xf0 | ri(0, 15)]) : 0) | (chance(0.85) ? ri(0, 15) : 0);
  const entry = (id, flags, guard, groups) => {
    const out = [id & 0xff, flags | ((id >>> 8) & 3), ...le(guard)];
    return out.concat(...groups);
  };
  const bytes = [];
  const baseOf = (idx, kindWord) => {
    const b = rec(idx);
    return { crc: crc16(0xffff, [...le(word(b, 184)), ...le(word(b, 178)), ...le(kindWord)]),
      group: [delta(), delta(), delta()] };
  };
  const targets = [];
  for (const idx of sampleInsts.slice(0, 12)) if (chance(0.6)) targets.push(idx);
  for (const idx of [...oplInsts, ...formantInsts]) if (chance(0.5)) targets.push(idx);
  for (const idx of [30, 31, 32, 33, 40, 41, 42, 43]) if (chance(0.6)) targets.push(idx);
  for (const idx of targets) {
    const b = rec(idx);
    const isTable = (idx >= 30 && idx < 34) || (idx >= 40 && idx < 44);
    let flags = 0, crc = 0xffff;
    const groups = [];
    if (isTable) {
      // The entries the engine reads: the count byte's, a rack's at most 16,
      // as many as fit the record.
      let n = b[1];
      if (idx >= 40) n = Math.min(n, 16);
      n = Math.min(n, Math.floor((256 - 4) / 10));
      const raw = [];
      for (let k = 0; k < n; k++) raw.push(...b.subarray(4 + k * 10 + 2, 4 + k * 10 + 8));
      crc = crc16(crc, raw);
      flags |= 0x08;
      const tn = chance(0.05) ? n + 1 : n;   // a count that does not fit: ignored
      const g = [tn];
      for (let k = 0; k < tn; k++) g.push(delta(), delta(), delta());
      groups.push(g);
    } else {
      const kindWord = oplInsts.includes(idx) ? word(b, 197 + 4) : formantInsts.includes(idx) ? word(b, 197 + 7) : 0;
      if (!ixmp.some((e) => e.inst === idx) || chance(0.6)) {
        const base = baseOf(idx, kindWord);
        crc = base.crc;
        flags |= 0x04;
        groups.push(base.group);
      }
      const ix = ixmp.find((e) => e.inst === idx);
      if (ix && chance(0.8)) {
        for (const [ps, pe, det] of ix.pitch) crc = crc16(crc, [...le(ps), ...le(pe), ...le(det)]);
        flags |= 0x10;
        const pn = chance(0.05) ? ix.count + 1 : ix.count;
        const g = [pn & 0xff, pn >>> 8];
        for (let k = 0; k < pn; k++) g.push(delta(), delta(), delta());
        groups.push(g);
      }
    }
    if (flags === 0) continue;
    if (chance(0.12)) crc ^= 1 << ri(0, 15);   // a stale guard
    bytes.push(...entry(idx, flags, crc, groups));
  }
  if (chance(0.15) && bytes.length) bytes.push(...entry(pick(sampleInsts), 0x04, 0, [[1, 2, 3]]));   // replaces, guard wrong
  if (chance(0.08)) bytes.push(...entry(pick(sampleInsts), 0x24, 0, [[0, 0, 0]]));   // reserved flag: the read ends
  if (chance(0.08)) bytes.push(...entry(pick(sampleInsts), 0x10, 0, [[5, 0, 1, 2]]));   // a group cut off
  sec("IPit", Uint8Array.from(bytes));
}
const projHeader = new Uint8Array(16);
projHeader.set([0x1e, 0x54, 0x61, 0x75, 0x64, 0x50, 0x72, 0x4a]);
const projOff = off;
w32(header, 14, projOff);

const parts = [header, imageComp, table, ...songBlobs, projHeader, ...sections];
const total = parts.reduce((n, p) => n + p.length, 0);
const file = new Uint8Array(total);
let o = 0;
for (const p of parts) { file.set(p, o); o += p.length; }
writeFileSync(out, file);
console.error(`fuzz ${seedArg}: v${fmtVer} ${lanes64 ? "64-lane" : "32-lane"} surround=${surround} lanes=${numLanes} songs=${songs.length} ${file.length} bytes`);
