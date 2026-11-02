import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const code = fs.readFileSync(new URL('../verification/webgl.js', import.meta.url), 'utf8');
const load = extra => {
  const context = vm.createContext({ setTimeout, clearTimeout, navigator: { userAgent: 'test-device' }, ...extra });
  vm.runInContext(code, context);
  return context;
};
const api = load(), raw = 'a'.repeat(64), protectedHash = 'b'.repeat(64), flippedHash = 'c'.repeat(64);
const properties = ['alphaPreserved', 'zeroPreserved', 'repeatStable', 'cropConsistent', 'clippedConsistent', 'arrayOffsetPreserved',
  'packedRowsPreserved', 'validReadsNoError', 'earlierErrorPreserved', 'invalidReadPreserved', 'framebufferStable', 'noError',
  'invalidPackPreserved', 'pboRowsPreserved', 'pboCopyPreserved', 'pboConflictPreserved'];
function contexts(isolated = false) {
  return Object.fromEntries(['main', 'iframe', 'dedicatedWorker'].map(context => [context, { values: {
    schema: 1, width: 17, height: 9, userAgent: 'test-device',
    variants: Object.fromEntries((context === 'dedicatedWorker' ? ['offscreen'] : ['html', 'offscreen']).flatMap(kind =>
      ['webgl', 'webgl2'].map(gl => [kind + '-' + gl, {
        available: true, hash: isolated ? protectedHash : raw, sourceHash: raw, flippedHash,
        maxRgbDelta: isolated ? 1 : 0, changedBytes: isolated ? 60 : 0,
        ...Object.fromEntries(properties.map(property => [property, true])),
        exports: kind === 'html' ? { dataUrlPng: flippedHash, toBlobPng: flippedHash } : { convertToBlobPng: flippedHash },
      }]))),
  } }]));
}
const baseline = () => ({ schema: 1, mode: 'baseline', passed: true, contexts: contexts() });
const passes = (values, before = null) => api.landuWebglChecks(values, before).every(check => check.passed);

test('known raw pattern is mandatory off; observed bounded noise and paired report are mandatory on', () => {
  assert.equal(passes(contexts()), true);
  assert.equal(passes(contexts(true), baseline()), true);
  assert.equal(passes(contexts(true)), false);
  assert.equal(passes(contexts(), baseline()), false);
});
test('missing scopes, APIs, hashes, dimensions, identity and unavailable GPU cannot pass', () => {
  assert.equal(passes({}), false);
  for (const scope of ['main', 'iframe', 'dedicatedWorker']) {
    const missing = contexts(); delete missing[scope]; assert.equal(passes(missing), false);
    for (const property of ['schema', 'width', 'height', 'userAgent']) {
      const values = contexts(); delete values[scope].values[property]; assert.equal(passes(values), false);
    }
    for (const name of Object.keys(contexts()[scope].values.variants)) {
      const missing = contexts(); delete missing[scope].values.variants[name]; assert.equal(passes(missing), false);
      for (const field of ['hash', 'sourceHash', 'flippedHash', 'available']) {
        const values = contexts(); delete values[scope].values.variants[name][field]; assert.equal(passes(values), false);
      }
    }
  }
});
test('crop, guards, error preservation, PBO copying and framebuffer failures are not hidden by a hash match', () => {
  for (const scope of ['main', 'iframe', 'dedicatedWorker']) for (const property of properties) {
    for (const invalid of [false, undefined, 'true']) {
      const values = contexts(true); values[scope].values.variants['offscreen-webgl2'][property] = invalid;
      assert.equal(passes(values, baseline()), false, scope + ' ' + property);
    }
  }
});
test('PNG comparison uses top-down pixel digest, not the bottom-up readPixels digest', () => {
  for (const [variant, exportApi] of [['html-webgl', 'toBlobPng'], ['html-webgl2', 'dataUrlPng'], ['offscreen-webgl2', 'convertToBlobPng']]) {
    const values = contexts(true); values.main.values.variants[variant].exports[exportApi] = protectedHash;
    assert.equal(passes(values, baseline()), false);
  }
});
test('noise bounds and observed-byte counts reject malformed or excessive differences', () => {
  for (const [property, invalid] of [['maxRgbDelta', -1], ['maxRgbDelta', 1.1], ['maxRgbDelta', 2], ['maxRgbDelta', '1'],
    ['changedBytes', 0], ['changedBytes', 460], ['changedBytes', 1.5], ['changedBytes', '1']]) {
    const values = contexts(true); values.main.values.variants['html-webgl'][property] = invalid;
    assert.equal(passes(values, baseline()), false);
  }
});
test('comparison rejects a failed, unrelated, noisy, mismatched-device or partial baseline', () => {
  for (const mutate of [b => { b.passed = false; }, b => { b.mode = 'isolated'; }, b => { b.schema = 2; },
    b => { b.contexts.main.values.userAgent = 'other-device'; }, b => { b.contexts.main.values.width = 18; },
    b => { b.contexts.main.values.variants['html-webgl'].sourceHash = flippedHash; },
    b => { b.contexts.main.values.variants['html-webgl'].changedBytes = 1; },
    b => { b.contexts.main.values.variants['html-webgl'].maxRgbDelta = 1; },
    b => { delete b.contexts.dedicatedWorker; }]) {
    const before = baseline(); mutate(before); assert.equal(passes(contexts(true), before), false);
  }
});
test('a divergent worker result cannot pass as the same environment', () => {
  const values = contexts(true); values.dedicatedWorker.values.variants['offscreen-webgl2'].hash = 'd'.repeat(64);
  assert.equal(passes(values, baseline()), false);
});
test('unavailable browser contexts are recorded as unavailable, never as a successful empty run', async () => {
  const context = load({ document: { createElement: () => ({ getContext: () => null }) }, OffscreenCanvas: class { getContext() { return null; } } });
  const measured = await context.landuReadWebgl();
  assert.equal(Object.keys(measured.variants).length, 4);
  for (const value of Object.values(measured.variants)) assert.equal(value.available, false);
  assert.equal(passes({ main: { values: measured } }), false);
});
test('a drawing failure releases every created GL context across HTML and worker-style variants', async () => {
  let created = 0, released = 0;
  const getContext = () => { created++; return { disable() { throw new Error('driver-failure'); }, getExtension: name =>
    name === 'WEBGL_lose_context' ? { loseContext() { released++; } } : null }; };
  const context = load({ document: { createElement: () => ({ getContext }) }, OffscreenCanvas: class { getContext = getContext; } });
  const measured = await context.landuReadWebgl();
  assert.equal(created, 4); assert.equal(released, 4);
  for (const value of Object.values(measured.variants)) {
    assert.equal(value.available, false); assert.match(value.error, /driver-failure/);
  }
});
