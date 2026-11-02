'use strict';

// A directory fsync can fail after the atomic rename already committed. The
// committed data must survive and the directory handle must still be closed.
const assert = require('assert');
const fs = require('fs');
const fsp = require('fs/promises');
const os = require('os');
const path = require('path');
const Module = require('module');

const baselineIndex = process.argv.indexOf('--baseline-dir');
const baseline = baselineIndex >= 0 ? path.resolve(process.argv[baselineIndex + 1]) : null;
function load(relative, backupName) {
  const file = path.resolve(__dirname, relative);
  if (!baseline) return require(file);
  const instance = new Module(file, module);
  instance.filename = file;
  instance.paths = Module._nodeModulePaths(path.dirname(file));
  instance._compile(fs.readFileSync(path.join(baseline, backupName), 'utf8'), file);
  return instance.exports;
}
const { writeRawAtomically } = load('../engine/state-storage.js', 'state-storage.js');
const { ProxyStore } = load('./proxy-store.js', 'proxy-store.js');

async function main() {
  const tempRoot = path.resolve(os.tmpdir());
  if (process.platform === 'win32' && path.parse(tempRoot).root.toLowerCase() === 'c:\\') throw Error('Choose a non-C test temp directory');
  const root = await fsp.mkdtemp(path.join(tempRoot, 'landu-directory-sync-'));
  const results = [];
  for (const writer of ['state', 'proxy']) {
    for (const unsupported of [false, true]) {
      const name = writer + (unsupported ? '-unsupported-directory-sync' : '-successful-directory-sync');
      const directory = path.join(root, name);
      await fsp.mkdir(directory);
      const original = fsp.open;
      let opened = 0, closed = 0;
      fsp.open = async function(target, flags, ...rest) {
        if (path.resolve(String(target)) !== directory || flags !== 'r') return original.call(fsp, target, flags, ...rest);
        opened++;
        return {
          async sync() { if (unsupported) throw Object.assign(Error('Directory sync unsupported'), { code: 'EINVAL' }); },
          async close() { closed++; },
        };
      };
      const result = { name, passed: false };
      try {
        const file = path.join(directory, 'state.json');
        if (writer === 'state') {
          await writeRawAtomically(file, '{"committed":true}');
          assert.deepStrictEqual(JSON.parse(await fsp.readFile(file, 'utf8')), { committed: true });
        } else {
          const store = new ProxyStore(file);
          await store.load();
          const item = await store.create({ name: 'Synthetic QA', protocol: 'socks5', host: '192.0.2.8', port: 1080 });
          const restored = new ProxyStore(file);
          await restored.load();
          assert.strictEqual(restored.get(item.id).host, '192.0.2.8');
        }
        assert(opened > 0, 'The test must exercise a directory sync');
        assert.strictEqual(closed, opened, 'Every directory handle must close even if directory fsync fails');
        result.passed = true;
      } catch (error) { result.error = error.message; }
      finally { fsp.open = original; }
      result.directoryHandlesOpened = opened;
      result.directoryHandlesClosed = closed;
      results.push(result);
    }
  }
  const report = { kind: 'atomic-save-directory-handle-regression', baseline: Boolean(baseline),
    root, cases: results, passed: results.every(item => item.passed) };
  await fsp.writeFile(path.join(root, 'report.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
  if (!report.passed) process.exitCode = 1;
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
