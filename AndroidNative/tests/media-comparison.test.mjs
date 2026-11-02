import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { compareMedia } from '../scripts/media-comparison.mjs';
const read = name => JSON.parse(readFileSync(new URL(`../validation/android15-media-webgl-20261101/${name}-20261101.json`, import.meta.url))).sections;
const capture = sections => ({ ...sections.mediaCapture, selection: sections.mediaSelect });
const data = { initial: read('android15-a-webgl-media-initial').media,
  blocked: read('android15-a-webgl-off-media-blocked').mediaBlocked,
  restoredPermission: read('android15-a-webgl-media-restored').media,
  isolated: capture(read('android15-a-media-capture-authorized')),
  hidden: capture(read('android15-a-media-hidden')), restored: capture(read('android15-a-media-restored')),
  other: capture(read('android15-b-media-isolated')), selection: read('android15-a-media-selection').mediaSelect };
test('actual two-environment capture, hidden labels, blocking and device selection observations pass', () => {
  const result = compareMedia(data); assert.equal(result.passed, true); assert.equal(result.checks.length, 60);
});
test('missing scopes, early labels and an unblocked capture cannot pass', () => {
  for (const mutate of [d => { delete d.initial.iframe; },
    d => { d.initial.main.values.devices[0].label = 'Real microphone'; },
    d => { d.blocked.requests[0].granted = true; }]) {
    const bad = structuredClone(data); mutate(bad); assert.equal(compareMedia(bad).passed, false);
  }
});
test('timeout and tracks left running are not accepted as capture proof', () => {
  const timeout = structuredClone(data); timeout.isolated = { timeout: true };
  assert.equal(compareMedia(timeout).passed, false);
  const running = structuredClone(data); running.hidden.tracksStopped = false;
  assert.equal(compareMedia(running).passed, false);
});
test('label leakage and wrong exact device selection fail', () => {
  const hidden = structuredClone(data); hidden.hidden.tracks[0].label = 'Hardware name';
  assert.equal(compareMedia(hidden).passed, false);
  const wrong = structuredClone(data); wrong.selection.selections[0].tracks[0].settings.deviceId = 'default';
  assert.equal(compareMedia(wrong).passed, false);
});
test('shared nondefault IDs and unstable restored IDs fail', () => {
  const shared = structuredClone(data);
  shared.other.active.devices.find(d => d.kind === 'videoinput').deviceId = shared.isolated.active.devices.find(d => d.kind === 'videoinput').deviceId;
  assert.equal(compareMedia(shared).passed, false);
  const drift = structuredClone(data); drift.restored.active.devices.find(d => d.kind === 'videoinput').deviceId = 'a'.repeat(64);
  assert.equal(compareMedia(drift).passed, false);
});
