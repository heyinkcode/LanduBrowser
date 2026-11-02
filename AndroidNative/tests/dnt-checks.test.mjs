import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sandbox = {};
vm.runInNewContext(fs.readFileSync(new URL('../verification/dnt-checks.js', import.meta.url), 'utf8'), sandbox);
const check = sandbox.landuDntChecks;
const fixture = mode => Object.fromEntries(['main', 'iframe', 'dedicatedWorker', 'serviceWorker'].map(name =>
  [name, { values: { supported: name === 'main' || name === 'iframe', value: mode === 'enabled' ? '1' : null,
    headers: { dnt: mode === 'enabled' ? '1' : null } } }]));

test('DNT enabled and disabled require coherent APIs and actual request headers', () => {
  for (const mode of ['enabled', 'disabled']) {
    const results = check(fixture(mode), { dnt: mode === 'enabled' ? '1' : null }, mode);
    assert.equal(results.length, 7);
    assert.ok(results.every(item => item.passed));
  }
});

test('changing only navigator.doNotTrack cannot hide unmodified request headers', () => {
  const contexts = fixture('enabled');
  contexts.main.values.headers.dnt = null;
  assert.ok(check(contexts, { dnt: '1' }, 'enabled').some(item => !item.passed));
  const disabled = fixture('disabled');
  disabled.iframe.values.value = '1';
  assert.ok(check(disabled, { dnt: null }, 'disabled').some(item => !item.passed));
});

test('missing contexts, echoed header keys, or page APIs cannot pass disabled mode', () => {
  for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker']) {
    const missing = fixture('disabled'); delete missing[name];
    assert.ok(check(missing, { dnt: null }, 'disabled').some(item => !item.passed), name);
    const incomplete = fixture('disabled'); incomplete[name].values.headers = {};
    assert.ok(check(incomplete, { dnt: null }, 'disabled').some(item => !item.passed), name);
  }
  for (const name of ['main', 'iframe']) {
    const missing = fixture('disabled'); missing[name].values.supported = false;
    assert.ok(check(missing, { dnt: null }, 'disabled').some(item => !item.passed), name);
  }
  assert.ok(check(fixture('disabled'), {}, 'disabled').some(item => !item.passed));
  assert.ok(check(fixture('disabled'), null, 'disabled').some(item => !item.passed));
});

test('worker and navigation requests must honor the selected state independently', () => {
  for (const name of ['dedicatedWorker', 'serviceWorker']) {
    const contexts = fixture('enabled'); contexts[name].values.headers.dnt = '0';
    assert.ok(check(contexts, { dnt: '1' }, 'enabled').some(item => !item.passed));
  }
  assert.ok(check(fixture('enabled'), { dnt: null }, 'enabled').some(item => !item.passed));
  assert.throws(() => check(fixture('enabled'), { dnt: '1' }, 'default'), /Do Not Track/);
});
