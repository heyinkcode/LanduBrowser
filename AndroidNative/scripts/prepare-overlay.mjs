import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { javaFiles, nativeFiles, resourceFiles, packagePath, digest, preparePatchedFile } from './overlay-plan.mjs';

const nativeRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const lock = JSON.parse(fs.readFileSync(path.join(nativeRoot, 'upstream-lock.json'), 'utf8'));
const args = process.argv.slice(2);
if (args.length !== 2 || !['--source', '--reference'].includes(args[0])) {
  throw new Error('Usage: node prepare-overlay.mjs --source <Chromium src> | --reference <pinned reference files>');
}
const root = fs.realpathSync(args[1]);
const reference = args[0] === '--reference';
if (!reference) {
  const revision = execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  if (revision !== lock.revision) throw new Error(`Expected ${lock.revision}, found ${revision}`);
}
const plan = Object.entries(lock.files).map(([relative, sha256]) => {
  const file = path.join(root, reference ? relative.replaceAll('/', '__') : relative);
  return preparePatchedFile(relative, fs.readFileSync(file), sha256);
});
const behaviorFiles = Object.entries(lock.behaviorFiles || {}).map(([relative, sha256]) => {
  const file = path.join(root, reference ? relative.replaceAll('/', '__') : relative);
  if (digest(fs.readFileSync(file)) !== sha256) throw new Error(`Backend behavior source changed: ${relative}`);
  return { path: relative, sha256 };
});
for (const [folder, name] of javaFiles) {
  const file = path.join(nativeRoot, folder, packagePath, name);
  if (!fs.statSync(file).isFile()) throw new Error(`Missing native integration source: ${name}`);
}
for (const [relative] of [...nativeFiles, ...resourceFiles]) {
  if (!fs.statSync(path.join(nativeRoot, relative)).isFile()) throw new Error(`Missing native source: ${relative}`);
}
process.stdout.write(JSON.stringify({
  mode: 'check-only', revision: lock.revision,
  behaviorFiles,
  upstreamFiles: plan.map(({ content, ...item }) => item),
  nativeFiles: [
    ...javaFiles.map(([, name]) => `chrome/android/java/src/${packagePath}/${name}`),
    ...nativeFiles.map(([, target]) => target),
    ...resourceFiles.map(([, target]) => target),
  ],
  chromiumCheckoutModified: false,
  launcherIntegrated: false,
  nativeFingerprintPatches: ['hardwareConcurrency', 'deviceMemory', 'regionalLocalFontFilter', 'canvasReadbackAndExportNoise'],
  canvasSettings: ['perEnvironmentStableSeed', 'rgba8AndBgra8', 'rgbaFloat16', 'htmlAndOffscreenCanvas'],
  canvasBrowserRuntimeVerified: false,
  memorySurfaces: ['navigator', 'workerNavigator', 'navigationClientHints', 'subresourceClientHints'],
  networkSettings: ['proxy-server', 'webrtc-ip-handling-policy'],
  networkBrowserRuntimeVerified: false,
  regionSettings: ['accept-lang', 'landu-web-locale', 'landu-timezone'],
  regionBrowserRuntimeVerified: false,
  fingerprintBrowserRuntimeVerified: false,
}, null, 2) + '\n');
