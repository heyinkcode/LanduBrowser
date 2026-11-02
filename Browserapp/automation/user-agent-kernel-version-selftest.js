#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const { parseArgs } = require('node:util');
const { values } = parseArgs({ options: { snapshot: { type: 'string' }, output: { type: 'string' } } });

function fromSnapshot(directory) {
  const load = (name, ua) => {
    const filename = path.join(__dirname, name);
    const loaded = new Module(filename, module);
    loaded.filename = filename;
    loaded.paths = Module._nodeModulePaths(__dirname);
    const originalRequire = loaded.require.bind(loaded);
    loaded.require = request => request === './user-agent' && ua ? ua : originalRequire(request);
    loaded._compile(fs.readFileSync(path.join(directory, name), 'utf8'), filename);
    return loaded.exports;
  };
  return load('fingerprint.js', load('user-agent.js'));
}
const { buildFingerprint } = values.snapshot ? fromSnapshot(path.resolve(values.snapshot)) : require('./fingerprint');
const results = [];
function check(name, run) {
  try { run(); results.push({ name, passed: true }); }
  catch (error) { results.push({ name, passed: false, error: error.message }); }
}
function profile(kernelVersion, os, id = 'automatic-kernel-UA') {
  return { id, kernelVersion, os, privacy: { fingerprint: {} } };
}
const versions = ['130.0.6723.116', '149.0.7827.114', '156.0.8078.48'];
for (const kernel of versions) {
  for (const os of ['windows', 'macos', 'macos_arm', 'linux']) {
    const fp = buildFingerprint(profile(kernel, os));
    const major = kernel.split('.')[0];
    check(`${os}/${kernel}: automatic UA and CH major`, () => {
      assert.match(fp.userAgent, new RegExp(`Chrome/${major}\\.0\\.0\\.0`));
      assert.equal(fp.uaProfile.chromeMajor, Number(major));
      for (const brand of fp.userAgentMetadata.brands.filter(b => ['Chromium', 'Google Chrome'].includes(b.brand))) assert.equal(brand.version, major);
    });
    check(`${os}/${kernel}: automatic CH full version is actual kernel`, () => {
      assert.equal(fp.uaProfile.chromeFull, kernel);
      assert.equal(fp.userAgentMetadata.fullVersion, kernel);
      assert.equal(fp.userAgentMetadata.uaFullVersion, kernel);
      assert.equal(fp.clientHints.ua_full_version, kernel);
      for (const brand of fp.userAgentMetadata.fullVersionList.filter(b => ['Chromium', 'Google Chrome'].includes(b.brand))) assert.equal(brand.version, kernel);
    });
  }
}
for (const product of ['Chrome/149.0.7827.114', 'HeadlessChrome/149.0.7827.114', 'Chromium/149.0.7827.114']) {
  check(`${product}: recognized browser version`, () => {
    const fp = buildFingerprint(profile(product, 'windows'));
    assert.equal(fp.uaProfile.chromeMajor, 149);
    assert.equal(fp.userAgentMetadata.fullVersion, '149.0.7827.114');
  });
}
check('major-only kernel: no invented build or patch', () => {
  assert.equal(buildFingerprint(profile('149', 'windows')).userAgentMetadata.fullVersion, '149.0.0.0');
});
check('manual UA remains explicit across kernel changes', () => {
  const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.205 Safari/537.36';
  const before = buildFingerprint({ ...profile('149.0.7827.114', 'windows'), userAgent: ua });
  const after = buildFingerprint({ ...profile('156.0.8078.48', 'windows'), userAgent: ua });
  assert.equal(after.userAgent, ua);
  assert.deepEqual(after.uaProfile, before.uaProfile);
  assert.equal(after.userAgentMetadata.fullVersion, '131.0.6778.205');
});
check('explicit CH complete version remains a user choice', () => {
  const fp = buildFingerprint({ ...profile('149.0.7827.114', 'windows'), clientHints: { ua_full_version: '149.0.7000.3' } });
  assert.equal(fp.userAgentMetadata.fullVersion, '149.0.7000.3');
});
check('same automatic profile remains deterministic', () => {
  assert.deepEqual(buildFingerprint(profile('149.0.7827.114', 'macos_arm')), buildFingerprint(profile('149.0.7827.114', 'macos_arm')));
});
check('unknown kernel keeps deterministic fallback', () => {
  const fp = buildFingerprint(profile('', 'windows'));
  assert.deepEqual(fp, buildFingerprint(profile('', 'windows')));
  assert.equal(fp.uaProfile.chromeMajor, Number(fp.userAgentMetadata.fullVersion.split('.')[0]));
});
const report = { suite: 'desktop-automatic-UA-real-kernel-version', passed: results.every(r => r.passed), assertions: results.length, results, browserRuntimeTested: false, userDataTouched: false };
if (values.output) fs.writeFileSync(path.resolve(values.output), JSON.stringify(report, null, 2) + '\n');
process.stdout.write(JSON.stringify({ ...report, results: results.filter(r => !r.passed) }, null, 2) + '\n');
if (!report.passed) process.exitCode = 1;
