import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { compareAudio, compareRects } from '../scripts/signal-comparison.mjs';
const read = phase => JSON.parse(readFileSync(new URL(`../validation/android15-signals-20261101/android15-a-audio-rects-${phase}-20261101.json`, import.meta.url))).sections;
const off = read('off'), on = read('initial'), repeat = read('restored');
test('actual device observations retain the AudioWorklet bypass as failure', () => {
  const audio = compareAudio(off.audio, on.audio, repeat.audio);
  assert.equal(audio.corePassed, true);
  assert.equal(audio.passed, false);
  assert.equal(audio.coverageChecks.length, 2);
  assert.ok(audio.coverageChecks.every(check => !check.passed));
  assert.equal(compareRects(off.rects, on.rects, repeat.rects).passed, true);
});
test('missing execution contexts and phases cannot count as passing', () => {
  assert.equal(compareAudio({}, {}, {}).passed, false);
  assert.equal(compareRects({}, {}, {}).passed, false);
  const missing = structuredClone(on.rects); missing.iframe.values.phases.pop();
  assert.equal(compareRects(off.rects, missing, repeat.rects).passed, false);
});
test('modified analyser tails and introduced silent samples fail core checks', () => {
  for (const mutate of [value => { value.tone.FloatFrequency.tailPreserved = false; },
    value => { value.silence.FloatTimeDomain.values[4] = 0x3f800000; }]) {
    const bad = structuredClone(on.audio); mutate(bad.main.values);
    assert.equal(compareAudio(off.audio, bad, repeat.audio).corePassed, false);
  }
});
test('large audio changes and unstable process restarts are rejected', () => {
  const bad = structuredClone(on.audio); bad.main.values.first.channels[0].bits[9] ^= 32;
  assert.equal(compareAudio(off.audio, bad, repeat.audio).corePassed, false);
  const unstable = structuredClone(repeat.audio); unstable.iframe.values.first.channels[1].bits[8] ^= 1;
  assert.equal(compareAudio(off.audio, on.audio, unstable).corePassed, false);
});
test('layout changes and oversized rectangle offsets are not accepted as noise', () => {
  const layout = structuredClone(on.rects); layout.main.values.phases[0].layout.offsetWidth++;
  assert.equal(compareRects(off.rects, layout, repeat.rects).passed, false);
  const geometry = structuredClone(on.rects); geometry.iframe.values.phases[1].targets.range.bounds.x += 0.01;
  assert.equal(compareRects(off.rects, geometry, repeat.rects).passed, false);
});
