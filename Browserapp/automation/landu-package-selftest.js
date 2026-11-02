const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const { isPackagingExcluded, copyRecursive } = require('../scripts/package-portable');

const root = path.resolve(__dirname, '..');
const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'landu-package-test-'));
const source = path.join(workspace, 'source');
const output = path.join(workspace, 'output');
const excluded = ['debug.log', '.env', '.env.production', 'auth.json', 'credentials.json', 'electron-startup-debug.txt', 'browser-profiles-v2', 'User Data', 'Cookies', 'Login Data', 'History', 'Local State', 'Preferences', 'Secure Preferences', 'reports', 'node_modules', 'CODE_OVERVIEW.md'];
try {
  fs.mkdirSync(path.join(source, 'nested'), { recursive: true });
  for (const name of excluded) {
    assert.equal(isPackagingExcluded(name), true, name);
    fs.writeFileSync(path.join(source, 'nested', name), 'test-only');
  }
  for (const name of ['main.js', 'native-input-mirror.cs', 'LICENSE', 'LICENSE.chrome-power.txt', 'logo-landu.svg', 'kernel.json', 'arial.woff2']) {
    assert.equal(isPackagingExcluded(name), false, name);
    fs.writeFileSync(path.join(source, name), 'test-only');
  }
  copyRecursive(source, output);
  assert.deepEqual(fs.readdirSync(path.join(output, 'nested')), []);
  assert.ok(fs.existsSync(path.join(output, 'native-input-mirror.cs')));
  assert.ok(fs.existsSync(path.join(output, 'LICENSE.chrome-power.txt')));
  const main = fs.readFileSync(path.join(root, 'main.js'), 'utf8');
  assert.ok(main.includes("const PROJECT_REPOSITORY = 'heyinkcode/LanduBrowser'"));
  assert.ok(main.includes('const UPDATE_REPOSITORY = PROJECT_REPOSITORY'));
  assert.ok(main.includes("'LanduBrowser-Windows-x86_64-with-kernel.exe'"));
  assert.ok(main.includes("path.join(appDataRoot, 'openbrowser')"));
  assert.ok(main.includes('timer.refresh()'));
  const renderer = fs.readFileSync(path.join(root, 'renderer.js'), 'utf8');
  assert.ok(renderer.includes("savedUiTheme = storedUiTheme || 'landu-apple'"));
  const packager = fs.readFileSync(path.join(root, 'scripts/package-portable.js'), 'utf8');
  assert.ok(packager.includes("const mainExe = path.join(runtimeRoot, 'LanduBrowser.exe')"));
  assert.ok(packager.includes('Refusing to remove a path outside package output'));
  console.log('landu-package-selftest: PASS (private files, licenses, brand, update origin, data compatibility)');
} finally {
  fs.rmSync(workspace, { recursive: true, force: true });
}
