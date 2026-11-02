import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../verification/geolocation.js', import.meta.url), 'utf8');
const sandbox = extra => {
  const value = { setTimeout, clearTimeout, ...extra };
  vm.runInNewContext(source, value);
  return value;
};
const pure = sandbox();
const expected = pure.landuParseExpectedGeolocation('custom', '37.5', '-122.25', '75');
const point = timestamp => ({ kind: 'position', timestamp, latitude: 37.5, longitude: -122.25, accuracy: 75,
  altitude: null, altitudeAccuracy: null, heading: null, speed: null });
const fixture = () => ({ supported: true, secureContext: true, permissionBefore: 'granted', permissionAfter: 'granted',
  current: point(100000), watchPositions: [point(100100), point(101100)], watchCleared: true, watchError: null, checkedAt: 101200 });
const passed = (data, setting = expected) => pure.landuGeolocationChecks(data, setting).every(item => item.passed);

test('expected coordinates use finite, bounded decimal input', () => {
  assert.equal(expected.latitude, 37.5);
  assert.equal(pure.landuParseExpectedGeolocation('custom', '0', '0', '1e2').accuracy, 100);
  for (const value of ['', 'NaN', 'Infinity', '0x20', '1,2', '1'.repeat(41)]) {
    assert.throws(() => pure.landuParseExpectedGeolocation('custom', value, '0', '75'));
  }
  assert.throws(() => pure.landuParseExpectedGeolocation('custom', '91', '0', '75'));
  assert.throws(() => pure.landuParseExpectedGeolocation('custom', '0', '-181', '75'));
  assert.throws(() => pure.landuParseExpectedGeolocation('custom', '0', '0', '0'));
  assert.throws(() => pure.landuParseExpectedGeolocation('unknown', '', '', ''));
});

test('custom location requires current and repeated fresh matching data with cleanup', () => {
  assert.equal(passed(fixture()), true);
  for (const [field, value] of [['latitude', 38], ['longitude', 0], ['accuracy', 100], ['altitude', 0],
    ['heading', 1], ['speed', 5], ['timestamp', 0]]) {
    const data = fixture(); data.watchPositions[1][field] = value;
    assert.equal(passed(data), false, field);
  }
  for (const update of [data => { data.watchCleared = false; }, data => { data.watchError = { code: 3 }; },
    data => { data.watchPositions = []; }, data => { data.current = null; }, data => { data.permissionAfter = 'prompt'; },
    data => { data.supported = false; }, data => { data.secureContext = false; }]) {
    const data = fixture(); update(data); assert.equal(passed(data), false);
  }
});

test('blocked location requires denied permissions and permission-denied result', () => {
  const expected = { mode: 'blocked' };
  const data = { supported: true, secureContext: true, permissionBefore: 'denied', permissionAfter: 'denied', current: { kind: 'error', code: 1 } };
  assert.equal(passed(data, expected), true);
  for (const state of ['prompt', 'granted', 'unavailable']) assert.equal(passed({ ...data, permissionBefore: state }, expected), false);
  for (const code of [0, 2, 3]) assert.equal(passed({ ...data, current: { kind: 'error', code } }, expected), false);
  assert.equal(passed({ ...data, current: point(100000) }, expected), false);
});

const browserPosition = timestamp => ({ timestamp, coords: point(timestamp) });
test('reader clears watch ID zero even when the platform calls back synchronously', async () => {
  const cleared = [];
  const now = Date.now();
  const context = sandbox({ isSecureContext: true, navigator: { permissions: { query: async () => ({ state: 'granted' }) },
    geolocation: { getCurrentPosition: success => success(browserPosition(now - 10)), watchPosition: success => {
      success(browserPosition(now)); success(browserPosition(now + 1)); return 0;
    }, clearWatch: id => cleared.push(id) } } });
  const data = await context.landuReadGeolocation(expected);
  assert.deepEqual(cleared, [0]); assert.equal(data.watchCleared, true);
  assert.equal(passed(data), true);
});

test('a blocked check with prompt state never asks the browser for location', async () => {
  let calls = 0;
  const context = sandbox({ isSecureContext: true, navigator: { permissions: { query: async () => ({ state: 'prompt' }) },
    geolocation: { getCurrentPosition: () => { calls++; } } } });
  const data = await context.landuReadGeolocation({ mode: 'blocked' });
  assert.equal(calls, 0); assert.equal(data.current.kind, 'skipped');
  assert.equal(passed(data, { mode: 'blocked' }), false);
});

test('watch errors are retained and their subscription is cleared', async () => {
  const cleared = [];
  const context = sandbox({ isSecureContext: true, navigator: { permissions: { query: async () => ({ state: 'granted' }) },
    geolocation: { getCurrentPosition: success => success(browserPosition(Date.now())),
      watchPosition: (_success, error) => { error({ code: 3, message: 'timeout' }); return 4; }, clearWatch: id => cleared.push(id) } } });
  const data = await context.landuReadGeolocation(expected);
  assert.deepEqual(cleared, [4]); assert.equal(data.watchError.code, 3);
  assert.equal(passed(data), false);
});

test('insecure pages never trigger a position request', async () => {
  const context = sandbox({ isSecureContext: false, navigator: { geolocation: { getCurrentPosition: () => { throw new Error('must not execute'); } } } });
  const data = await context.landuReadGeolocation(expected);
  assert.equal(data.secureContext, false); assert.equal(passed(data), false);
});
