import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const context = vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('../verification/fonts.js', import.meta.url), 'utf8'), context);
const compare = context.landuCompareFonts;
const base = [100, 110, 120];
const snapshot = () => ({ userAgent: 'same-test-device', base: { canvas: [...base], dom: [...base] },
  fonts: Array.from(context.landuFontNames, name => ({ name, canvas: [...base], dom: [...base], local: 'unavailable' })),
});

test('missing fonts on the original device are insufficient evidence', () => {
  assert.equal(compare(snapshot(), snapshot(), true).outcome, 'inconclusive');
});
test('a previously visible font must disappear from every probe', () => {
  const before = snapshot();
  before.fonts[0].local = 'available';
  before.fonts[0].canvas[1] += 10;
  before.fonts[0].dom[1] += 10;
  assert.equal(compare(before, snapshot(), true).outcome, 'passed');
  for (const surface of ['canvas', 'dom', 'local']) {
    const after = snapshot();
    if (surface === 'local') after.fonts[0].local = 'available';
    else after.fonts[0][surface][1] += 10;
    assert.equal(compare(before, after, true).outcome, 'failed', surface);
  }
});
test('timeouts, missing APIs and mismatched baselines never count as passed', () => {
  const before = snapshot();
  before.fonts[0].local = 'available';
  for (const issue of ['timeout', 'unsupported']) {
    const after = snapshot();
    after.fonts[1].local = issue;
    assert.equal(compare(before, after, true).outcome, 'inconclusive');
  }
  const differentDevice = snapshot();
  differentDevice.userAgent = 'different-device';
  assert.equal(compare(before, differentDevice, true).outcome, 'inconclusive');
  assert.equal(compare(undefined, snapshot(), true).outcome, 'inconclusive');
});
test('workers use canvas and local() without pretending to have DOM measurements', () => {
  const before = snapshot();
  const after = snapshot();
  for (const item of [before, after]) {
    item.base.dom = null;
    for (const font of item.fonts) font.dom = null;
  }
  before.fonts[0].local = 'available';
  assert.equal(compare(before, after, false).outcome, 'passed');
  assert.equal(compare(before, after, true).outcome, 'inconclusive');
});

test('service worker font replies keep the event alive and preserve success and error evidence', async () => {
  const source = fs.readFileSync(new URL('../verification/service-worker.js', import.meta.url), 'utf8');
  for (const reject of [false, true]) {
    const handlers = {}, imports = [], replies = [], pending = [];
    let reads = 0;
    const payload = { base: { canvas: [100, 110, 120], dom: null }, fonts: [] };
    vm.runInNewContext(source, {
      importScripts: (...names) => imports.push(...names),
      self: { addEventListener: (type, action) => { handlers[type] = action; } },
      landuReadFonts: async () => { reads++; if (reject) throw Error('font probe failed'); return payload; },
    });
    assert.ok(imports.includes('fonts.js'));
    handlers.message({ data: { type: 'landu-font-probe', nonce: 'test-nonce' },
      ports: [{ postMessage: value => replies.push(value) }], waitUntil: promise => pending.push(promise) });
    assert.equal(pending.length, 1);
    await pending[0];
    assert.equal(replies.length, 1); assert.equal(replies[0].nonce, 'test-nonce');
    if (reject) assert.match(replies[0].error, /font probe failed/);
    else assert.equal(replies[0].values, payload);
    handlers.message({ data: { type: 'landu-font-probe', nonce: 'no-port' }, ports: [],
      waitUntil: () => assert.fail('No reply port should not start a probe') });
    assert.equal(reads, 1);
  }
});
