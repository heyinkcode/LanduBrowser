'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../main.js'), 'utf8');
const updateFunctions = source.slice(source.indexOf('function normalizeRemoteTag('), source.indexOf('async function resolveReleaseAsset('));
const noticeFunctions = source.slice(source.indexOf('let notifiedAppUpdateVersion'), source.indexOf('async function pushAppUpdateStatus('));
const calls = [];
const context = vm.createContext({
  AbortController, setTimeout, clearTimeout,
  UPDATE_TIMEOUT_MS: 1000,
  UPDATE_REPOSITORY: 'heyinkcode/LanduBrowser',
  UPDATE_LATEST_HTML: 'https://github.com/heyinkcode/LanduBrowser/releases/latest',
  UPDATE_API_URL: 'https://api.github.com/repos/heyinkcode/LanduBrowser/releases/latest',
  updateUserAgent: () => 'LanduBrowser-selftest',
  fetch: async (url) => {
    calls.push(url);
    return { ok: true, url: 'https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.27' };
  },
});
vm.runInContext(updateFunctions, context);

(async () => {
  let release = await context.resolveLatestReleaseMeta();
  assert.equal(release.remoteVersion, '1.0.27');
  assert.equal(calls.length, 1);
  assert.equal(calls[0], context.UPDATE_LATEST_HTML);
  assert.equal(context.metaFromTag('v1.0.26-android.20261104.2', 'fixture'), null);
  assert.equal(context.metaFromTag('v1.0.28-preview.20261105', 'fixture'), null);

  context.fetch = async (url) => {
    if (url !== context.UPDATE_API_URL) throw new Error('HTML unavailable');
    return { ok: true, json: async () => ({ tag_name: 'v1.0.27', name: 'Desktop update', assets: [] }) };
  };
  release = await context.resolveLatestReleaseMeta();
  assert.equal(release.remoteVersion, '1.0.27');
  assert.equal(release.source, 'api');

  let visible = true;
  const dialogs = [];
  const events = [];
  context.mainWindow = { isDestroyed: () => false, isVisible: () => visible };
  context.dialog = { showMessageBox: async (_window, options) => { dialogs.push(options); return { response: 0 }; } };
  context.emit = event => events.push(event);
  vm.runInContext(noticeFunctions, context);
  const update = { upToDate: false, canDownload: true, currentVersion: '1.0.26', remoteVersion: '1.0.27' };
  await context.offerAppUpdate({ ...update, upToDate: true });
  await context.offerAppUpdate({ ...update, canDownload: false });
  visible = false;
  await context.offerAppUpdate(update);
  assert.equal(dialogs.length, 0);
  visible = true;
  await context.offerAppUpdate(update);
  await context.offerAppUpdate(update);
  assert.equal(dialogs.length, 1, 'a release prompts once per running session');
  assert.equal(events[0].type, 'app-update-open');
  await context.offerAppUpdate({ ...update, remoteVersion: '1.0.28' });
  assert.equal(dialogs.length, 2);
  console.log('app-update-selftest: PASS stable release selection, API fallback, preview filtering, update dialog and duplicate suppression');
})().catch(error => { console.error(error); process.exitCode = 1; });
