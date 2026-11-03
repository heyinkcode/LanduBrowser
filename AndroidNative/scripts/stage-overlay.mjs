import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { javaFiles, nativeFiles, resourceFiles, packagePath, digest, preparePatchedFile } from './overlay-plan.mjs';

const nativeRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const lock = JSON.parse(fs.readFileSync(path.join(nativeRoot, 'upstream-lock.json'), 'utf8'));
const args = process.argv.slice(2);
if (args.length !== 4 || args[0] !== '--source' || args[2] !== '--output-parent') {
  throw new Error('Usage: node stage-overlay.mjs --source <Chromium src> --output-parent <separate existing directory>');
}
const source = fs.realpathSync(args[1]);
const outputParent = fs.realpathSync(args[3]);
const within = (child, parent) => {
  const relative = path.relative(parent, child);
  return relative === '' || (!path.isAbsolute(relative) && relative !== '..' && !relative.startsWith(`..${path.sep}`));
};
if (/^c:[\\/]/i.test(outputParent) || /^\/mnt\/c(?:\/|$)/i.test(outputParent)
    || within(outputParent, source) || within(source, outputParent)
    || within(outputParent, nativeRoot)) {
  throw new Error('Use a dedicated output directory outside Chromium, AndroidNative, and drive C');
}
const revision = execFileSync('git', ['-C', source, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (revision !== lock.revision) throw new Error(`Expected revision ${lock.revision}, found ${revision}`);

const payload = [];
const brandBuilders = resourceFiles.length ? ['scripts/generate-brand-assets.py', 'branding/product.json',
  'branding/asset-manifest.json', 'branding/CHROMIUM-LICENSE.txt'] : [];
let externalInputs = [];
if (resourceFiles.length) {
  execFileSync(process.env.PYTHON || 'python3', ['-B', path.join(nativeRoot, 'scripts/generate-brand-assets.py'), '--check'], { encoding: 'utf8' });
  externalInputs = JSON.parse(fs.readFileSync(path.join(nativeRoot, 'branding/asset-manifest.json'), 'utf8')).inputs;
  const expected = ['Browserapp/assets/logo-landu.svg', 'LICENSE'];
  if (externalInputs.length !== expected.length || externalInputs.some((item, i) => item.path !== expected[i]
      || digest(fs.readFileSync(path.join(nativeRoot, '..', item.path))) !== item.sha256)) {
    throw new Error('Brand source inputs differ from the reviewed assets');
  }
}
// Bind the stage to the exact patch builder and local integration revision.
const builderInputs = ['scripts/overlay-plan.mjs', 'scripts/stage-overlay.mjs',
  'scripts/manifest-policy.mjs', 'manifest-component-policy.json',
  'upstream-lock.json', 'overlay-inventory.json', 'translation-dependencies.json',
  'scripts/fetch-translation-deps.py', ...brandBuilders].map(relative => ({
    path: relative, sha256: digest(fs.readFileSync(path.join(nativeRoot, relative))),
  }));
const behaviorSources = [];
for (const [relative, sha256] of Object.entries(lock.behaviorFiles || {})) {
  const file = path.join(source, relative);
  if (fs.lstatSync(file).isSymbolicLink() || !within(fs.realpathSync(file), source)
      || digest(fs.readFileSync(file)) !== sha256) {
    throw new Error(`Backend behavior source changed: ${relative}`);
  }
  behaviorSources.push({ path: relative, sha256 });
}
for (const [relative, originalHash] of Object.entries(lock.files)) {
  const file = path.join(source, relative);
  if (fs.lstatSync(file).isSymbolicLink() || !within(fs.realpathSync(file), source)) {
    throw new Error(`Unexpected linked source: ${relative}`);
  }
  const patched = preparePatchedFile(relative, fs.readFileSync(file), originalHash);
  // A baseline is preserved as the input; do not silently produce a mixed stage.
  if (patched.alreadyPatched) throw new Error(`Expected unmodified baseline: ${relative}`);
  payload.push({ path: relative, originalSha256: originalHash, content: Buffer.from(patched.content) });
}
for (const [local, target] of [
  ...javaFiles.map(([folder, name]) => [`${folder}/${packagePath}/${name}`, `chrome/android/java/src/${packagePath}/${name}`]),
  ...nativeFiles,
  ...resourceFiles,
]) {
  const file = path.join(nativeRoot, local);
  if (fs.lstatSync(file).isSymbolicLink() || !within(fs.realpathSync(file), nativeRoot)) {
    throw new Error(`Unexpected linked integration source: ${local}`);
  }
  if (fs.existsSync(path.join(source, target))) throw new Error(`New integration path already exists: ${target}`);
  payload.push({ path: target, originalSha256: null, content: fs.readFileSync(file) });
}
if (new Set(payload.map(item => item.path)).size !== payload.length) throw new Error('Duplicate payload path');

// Write only after all inputs validate; unique directory leaves previous stages intact.
const output = fs.mkdtempSync(path.join(outputParent, 'landu-overlay-'));
const files = [];
for (const item of payload) {
  const destination = path.join(output, 'files', item.path);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, item.content, { flag: 'wx' });
  files.push({ path: item.path, originalSha256: item.originalSha256, sha256: digest(item.content), bytes: item.content.length });
}
const manifest = {
  format: 1, revision: lock.revision, version: lock.version, createdAt: new Date().toISOString(),
  kind: 'landu-native-integration-stage-not-an-apk', files,
  behaviorSources, builderInputs, externalInputs,
  chromiumCheckoutModified: false, apkBuilt: false, deviceTested: false,
};
// This marker is written last; incomplete stages cannot be mistaken for ready input.
const manifestText = JSON.stringify(manifest, null, 2) + '\n';
fs.writeFileSync(path.join(output, 'manifest.json'), manifestText, { flag: 'wx' });
process.stdout.write(JSON.stringify({ output, fileCount: files.length, manifestSha256: digest(manifestText),
  chromiumCheckoutModified: false, apkBuilt: false }, null, 2) + '\n');
