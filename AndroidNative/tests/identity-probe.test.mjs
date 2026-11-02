import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../verification/identity.js', import.meta.url), 'utf8');
const sandbox = {};
vm.runInNewContext(source, sandbox);
const check = sandbox.landuIdentityChecks;
const kernel = '156.0.8078.48';
const expected = { platformVersion: '15.0.0', model: 'Pixel 8' };
const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
const serialize = list => list.map(({ brand, version }) => JSON.stringify(brand) + ';v=' + JSON.stringify(version)).join(', ');

function fixture(model = 'Pixel 8', ua = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Mobile Safari/537.36') {
  const low = { platform: 'Android', mobile: true, brands: [{ brand: 'Chromium', version: '156' }, { brand: 'Not, A "Brand', version: '99' }] };
  const high = { ...structuredClone(low), platformVersion: '15.0.0', model, architecture: '', bitness: '', wow64: false,
    fullVersionList: [{ brand: 'Chromium', version: kernel }, { brand: 'Not, A "Brand', version: '99.0.0.0' }] };
  const headers = { 'user-agent': ua, 'sec-ch-ua': serialize(low.brands), 'sec-ch-ua-platform': '"Android"',
    'sec-ch-ua-mobile': '?1', 'sec-ch-ua-platform-version': '"15.0.0"', 'sec-ch-ua-model': JSON.stringify(model),
    'sec-ch-ua-full-version-list': serialize(high.fullVersionList) };
  return { contexts: Object.fromEntries(names.map(name => [name, { values: { userAgent: ua, platform: 'Linux armv81',
    low: structuredClone(low), high: structuredClone(high), headers: structuredClone(headers) } }])), navigation: structuredClone(headers) };
}

test('coherent native frozen UA and high entropy identity pass with actual kernel reference', () => {
  const f = fixture();
  const results = check(f.contexts, f.navigation, expected, kernel);
  assert.ok(results.length >= 80 && results.every(item => item.passed));
  for (const name of names) assert.ok(results.some(item => item.context === name && item.property === 'model'));
  assert.ok(check(f.contexts, f.navigation, { platformVersion: null, model: null }, kernel).every(item => item.passed));
});

test('changing a page API alone cannot hide worker or HTTP header leaks', () => {
  for (const name of names) {
    const f = fixture(); f.contexts[name].values.high.model = 'real-device';
    assert.ok(check(f.contexts, f.navigation, expected, kernel).some(item => !item.passed), name);
    const g = fixture(); g.contexts[name].values.headers['sec-ch-ua-platform-version'] = '"16.0.0"';
    assert.ok(check(g.contexts, g.navigation, expected, kernel).some(item => !item.passed), name);
  }
  const f = fixture(); f.navigation['sec-ch-ua-model'] = '"real-device"';
  assert.ok(check(f.contexts, f.navigation, expected, kernel).some(item => !item.passed));
});

test('observed complete native worker hint omission is distinct from partial or missing evidence', () => {
  const keys = ['sec-ch-ua', 'sec-ch-ua-full-version-list', 'sec-ch-ua-mobile',
    'sec-ch-ua-platform', 'sec-ch-ua-platform-version', 'sec-ch-ua-model'];
  const f = fixture();
  for (const name of ['dedicatedWorker', 'serviceWorker']) {
    for (const key of keys) f.contexts[name].values.headers[key] = null;
  }
  const results = check(f.contexts, f.navigation, expected, kernel);
  assert.ok(results.every(item => item.passed));
  assert.equal(results.filter(item => item.coverage?.startsWith('not-sent')).length, 2);
  for (const name of ['main', 'iframe', 'navigation']) {
    const copy = structuredClone(f);
    const headers = name === 'navigation' ? copy.navigation : copy.contexts[name].values.headers;
    for (const key of keys) headers[key] = null;
    assert.ok(check(copy.contexts, copy.navigation, expected, kernel).some(item => !item.passed), name);
  }
  for (const name of ['dedicatedWorker', 'serviceWorker']) {
    const partial = structuredClone(f); partial.contexts[name].values.headers['sec-ch-ua-model'] = '"real-device"';
    assert.ok(check(partial.contexts, partial.navigation, expected, kernel).some(item => !item.passed));
    const incomplete = structuredClone(f); delete incomplete.contexts[name].values.headers['sec-ch-ua'];
    assert.ok(check(incomplete.contexts, incomplete.navigation, expected, kernel).some(item => !item.passed));
    const leaked = structuredClone(f); leaked.contexts[name].values.high.model = 'real-device';
    assert.ok(check(leaked.contexts, leaked.navigation, expected, kernel).some(item => !item.passed));
    const missing = structuredClone(f); delete missing.contexts[name].values.headers;
    assert.ok(check(missing.contexts, missing.navigation, expected, kernel).some(item => !item.passed));
  }
});

test('missing contexts and header keys never pass, including an explicitly empty model', () => {
  const hidden = { platformVersion: '15.0.0', model: '' };
  const f = fixture('');
  assert.ok(check(f.contexts, f.navigation, hidden, kernel).every(item => item.passed));
  for (const name of names) {
    const missing = fixture(); missing.contexts[name] = { error: 'timeout' };
    assert.ok(check(missing.contexts, missing.navigation, expected, kernel).some(item => !item.passed));
    const absentHeader = fixture(''); delete absentHeader.contexts[name].values.headers['sec-ch-ua-model'];
    assert.ok(check(absentHeader.contexts, absentHeader.navigation, hidden, kernel).some(item => !item.passed));
  }
  assert.ok(check(f.contexts, null, hidden, kernel).some(item => !item.passed));
});

test('unreduced UA must use the configured OS version and model while Chromium frozen UA stays valid', () => {
  const ua = 'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.48 Mobile Safari/537.36';
  const f = fixture('Pixel 8', ua);
  assert.ok(check(f.contexts, f.navigation, expected, kernel).every(item => item.passed));
  for (const wrong of [ua.replace('Android 15', 'Android 16'), ua.replace('Pixel 8', 'real-device')]) {
    const bad = fixture('Pixel 8', wrong);
    assert.ok(check(bad.contexts, bad.navigation, expected, kernel).some(item => !item.passed));
  }
});

test('low versus high entropy brands, malformed structured headers and wrong engine versions fail', () => {
  for (const change of [
    f => { f.contexts.main.values.high.platform = 'Windows'; },
    f => { f.contexts.main.values.high.brands[0].version = '155'; },
    f => { f.contexts.serviceWorker.values.high.fullVersionList[0].version = '155.0.0.0'; },
    f => { f.contexts.iframe.values.headers['sec-ch-ua'] += ', garbage'; },
    f => { f.contexts.main.values.headers['sec-ch-ua-model'] = 'Pixel 8'; },
    f => { f.navigation['sec-ch-ua-mobile'] = '?0'; },
  ]) {
    const f = fixture(); change(f);
    assert.ok(check(f.contexts, f.navigation, expected, kernel).some(item => !item.passed));
  }
  const f = fixture();
  assert.ok(check(f.contexts, f.navigation, expected, '155.0.0.0').some(item => !item.passed));
  assert.throws(() => check(f.contexts, f.navigation, expected, null), /固定内核/);
});

test('expected Android values canonicalize explicit hidden models and reject ambiguous input', () => {
  const parsed = sandbox.landuParseExpectedAndroidIdentity('custom', '15.1', '');
  assert.equal(parsed.platformVersion, '15.1.0'); assert.equal(parsed.model, '');
  for (const [mode, version, model] of [['custom', '', 'x'], ['custom', '9', 'x'], ['custom', '15.00', 'x'],
    ['custom', '15', 'x;y'], ['custom', '15\n', 'x'], ['unknown', '15', 'x']]) {
    assert.throws(() => sandbox.landuParseExpectedAndroidIdentity(mode, version, model));
  }
});

function collector(high, response, fastTimeout = false) {
  const f = fixture().contexts.main.values;
  let signal, timerCount = 0;
  const context = { AbortController,
    navigator: { userAgent: f.userAgent, platform: f.platform, userAgentData: {
      ...f.low, getHighEntropyValues: () => high(f.high) } },
    fetch: async (route, options) => { assert.equal(route, 'request-headers'); signal = options.signal; return response(options, f.headers); },
    setTimeout: (action, duration) => { assert.equal(duration, 12000); timerCount++; return setTimeout(action, fastTimeout ? 1 : duration); },
    clearTimeout: handle => { timerCount--; clearTimeout(handle); },
  };
  vm.runInNewContext(source, context);
  return { read: context.landuReadBrowserIdentity, signal: () => signal, timerCount: () => timerCount };
}

test('collector returns actual API and echoed header data and clears its request resources', async () => {
  const c = collector(high => Promise.resolve(high), async (options, headers) => ({ ok: true, json: async () => headers }));
  const values = await c.read();
  assert.equal(values.high.model, 'Pixel 8');
  assert.equal(values.headers['sec-ch-ua-model'], '"Pixel 8"');
  assert.equal(c.timerCount(), 0); assert.equal(c.signal().aborted, true);
});

test('collector metadata rejection or HTTP failure aborts remaining network work', async () => {
  const denied = collector(() => Promise.reject(new Error('policy denied')), options => new Promise((resolve, reject) =>
    options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })));
  await assert.rejects(denied.read(), /policy denied/);
  assert.equal(denied.signal().aborted, true); assert.equal(denied.timerCount(), 0);
  const failed = collector(high => Promise.resolve(high), async () => ({ ok: false, status: 500 }));
  await assert.rejects(failed.read(), /HTTP 500/);
  assert.equal(failed.signal().aborted, true); assert.equal(failed.timerCount(), 0);
  const unsupported = { navigator: {}, fetch: () => { throw new Error('Unexpected request'); } };
  vm.runInNewContext(source, unsupported);
  await assert.rejects(unsupported.landuReadBrowserIdentity(), /Client Hints API/);
});

test('collector times out hung high entropy APIs rather than leaving the check button waiting forever', async () => {
  const c = collector(() => new Promise(() => {}), async (options, headers) => ({ ok: true, json: async () => headers }), true);
  await assert.rejects(c.read(), /读取超时/);
  assert.equal(c.signal().aborted, true); assert.equal(c.timerCount(), 0);
});
