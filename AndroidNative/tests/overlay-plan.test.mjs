import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import path from 'node:path';
import { bootstrapPath, buildPath, apkTemplatePath, digest, preparePatchedFile } from '../scripts/overlay-plan.mjs';

const bootstrap = Buffer.from('start\n    protected void attachBaseContext(Context context) {\n' +
  '        boolean isBrowserProcess = isBrowserProcess();\n' +
  '            PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIRECTORY_SUFFIX);\n}\n' +
  '        super.onCreate();\n        super.onTrimMemory(level);\n' +
  '        super.onConfigurationChanged(newConfig);\n' +
  '    public void startActivity(Intent intent, @Nullable Bundle options) {\n' +
  '        getImpl().startActivity(intent, options);\n}\n');
const build = Buffer.from('start\n  android_library("base_module_java") {\n    sources = [\n    ]\n    resources_package = "org.chromium.chrome.base"\n  }\n' +
  '  version_resource_dir = "$target_gen_dir/templates/chrome_version_xml/res"\n' +
  '  java_group("chrome_public_non_pak_assets") {\n    deps = [\n    ]\n  }\n');

test('accepts an exact pinned file and is idempotent for this overlay', () => {
  for (const [file, bytes] of [[bootstrapPath, bootstrap], [buildPath, build]]) {
    const first = preparePatchedFile(file, bytes, digest(bytes));
    const second = preparePatchedFile(file, Buffer.from(first.content), digest(bytes));
    assert.equal(first.alreadyPatched, false);
    assert.equal(second.alreadyPatched, true);
    assert.equal(first.content, second.content);
  }
});

test('rejects upstream changes and local changes even after overlay insertion', () => {
  const first = preparePatchedFile(bootstrapPath, bootstrap, digest(bootstrap));
  assert.throws(() => preparePatchedFile(bootstrapPath, Buffer.concat([bootstrap, Buffer.from('local work')]), digest(bootstrap)));
  assert.throws(() => preparePatchedFile(bootstrapPath, Buffer.from(first.content + 'local work'), digest(bootstrap)));
});

test('rejects ambiguous anchors instead of patching an arbitrary method', () => {
  const ambiguous = Buffer.concat([bootstrap, bootstrap]);
  assert.throws(() => preparePatchedFile(bootstrapPath, ambiguous, digest(ambiguous)), /ambiguous/);
});

test('adds native sources to the early-loading base module', () => {
  const result = preparePatchedFile(buildPath, build, digest(build));
  assert.match(result.content, /base_module_java[\s\S]*EnvironmentLeaseStore\.java/);
  assert.match(result.content, /LanduEnvironmentBootstrap\.java/);
  assert.match(result.content, /android_resources\("landu_brand_resources"\)[\s\S]*resource_overlay = true/);
  assert.match(result.content, /deps = \[ ":chrome_public_apk_base_module_resources" \]/);
  const destinations = result.content.match(/renaming_destinations = \[([\s\S]*?)\]/)?.[1];
  assert.equal(typeof destinations, 'string');
  assert.match(destinations, /"landu\/build-info.json"/);
  assert.doesNotMatch(destinations, /"landu\/assets\/build-info.json"/);
});

test('native APK uses a separate Landu package and the overriding brand resources', () => {
  const input = Buffer.from('  # *_bundle variants). This defaults to the Chromium browser package name.\n' +
    '  chrome_public_manifest_package = "org.chromium.chrome"\n' +
    '          "//chrome/android:chrome_public_apk_base_module_resources",\n' +
    '    library_always_compress = []\n');
  const result = preparePatchedFile(apkTemplatePath, input, digest(input));
  assert.match(result.content, /chrome_public_manifest_package = "com.landu.browser"/);
  assert.match(result.content, /"\/\/chrome\/android:landu_brand_resources"/);
  assert.doesNotMatch(result.content, /chrome_public_manifest_package = "org.chromium.chrome"/);
  assert.equal(preparePatchedFile(apkTemplatePath, Buffer.from(result.content), digest(input)).alreadyPatched, true);
});

test('the unavailable account backend returns through the existing null-intent callback', () => {
  const file = 'components/signin/public/android/java/src/org/chromium/components/signin/NullAccountManagerDelegate.java';
  const original = Buffer.from('        throw new UnsupportedOperationException(\n' +
    '                "NullAccountManagerDelegate does not implement createAddAccountIntent");');
  const result = preparePatchedFile(file, original, digest(original));
  assert.match(result.content, /callback\.onResult\(null\);/);
  assert.doesNotMatch(result.content, /throw new UnsupportedOperationException/);
  assert.equal(preparePatchedFile(file, Buffer.from(result.content), digest(original)).content, result.content);
});

test('manual translation keeps the native path and falls back inside the current document',
  { skip: !process.env.LANDU_SOURCE_REFERENCE }, () => {
    const file = 'chrome/browser/language/android/java/src/org/chromium/chrome/browser/translate/TranslateBridge.java';
    const original = fs.readFileSync(path.join(process.env.LANDU_SOURCE_REFERENCE, file));
    const { content } = preparePatchedFile(file, original, digest(original));
    assert.match(content, /LanduPageTranslator\.translate\(tab\)/);
    assert.match(content, /manualTranslateWhenReady\(contents\)/);
    assert.match(content, /canTranslateInPlace\(tab\)/);
    assert.doesNotMatch(content, /translate\.google|translate\.goog|tab\.loadUrl/);
  });

test('page translation uses the production isolated-world API and checks frame lifetime', () => {
  const helper = fs.readFileSync(new URL(
    '../chromium/org/chromium/chrome/browser/translate/LanduPageTranslator.java', import.meta.url), 'utf8');
  assert.equal((helper.match(/executeJavaScriptInIsolatedWorld\(/g) || []).length, 2);
  assert.match(helper, /ISOLATED_WORLD_ID_CONTENT_END \+ 1/);
  assert.match(helper, /mFrame\.isRenderFrameLive\(\)/);
  assert.doesNotMatch(helper, /\.evaluateJavaScript(?:ForTests)?\(/);
});

test('translation feedback defaults to an edge button with bounded touch area and timed collapse', () => {
  const helper = fs.readFileSync(new URL(
    '../chromium/org/chromium/chrome/browser/translate/LanduPageTranslator.java', import.meta.url), 'utf8');
  assert.doesNotMatch(helper, /Snackbar|TYPE_PERSISTENT/);
  assert.match(helper, /new PopupWindow\(mRoot, dp\(44\), dp\(44\), false\)/);
  assert.match(helper, /mPopup\.setTouchModal\(false\)/);
  assert.match(helper, /mDetails\.setVisibility\(View\.GONE\)/);
  assert.match(helper, /COLLAPSE_DELAY_MS = 5000/);
  assert.match(helper, /mAnchor\.postDelayed\(mCollapse, COLLAPSE_DELAY_MS\)/);
  assert.match(helper, /mAnchor\.removeCallbacks\(mCollapse\)/);
  assert.match(helper, /mAnchor\.removeOnLayoutChangeListener\(mLayoutListener\)/);
  assert.match(helper, /mPopup\.dismiss\(\)/);
});

test('all pinned source patches reapply without drift and reject later edits',
  { skip: !process.env.LANDU_SOURCE_REFERENCE }, () => {
    const lock = JSON.parse(fs.readFileSync(new URL('../upstream-lock.json', import.meta.url), 'utf8'));
    for (const [relative, expected] of Object.entries(lock.files)) {
      const checkoutPath = path.join(process.env.LANDU_SOURCE_REFERENCE, relative);
      const original = fs.readFileSync(fs.existsSync(checkoutPath) ? checkoutPath
        : path.join(process.env.LANDU_SOURCE_REFERENCE, relative.replaceAll('/', '__')));
      assert.equal(digest(original), expected, relative);
      const first = preparePatchedFile(relative, original, expected);
      const next = preparePatchedFile(relative, Buffer.from(first.content), expected);
      assert.equal(next.alreadyPatched, true, relative);
      assert.equal(next.content, first.content, relative);
      assert.throws(() => preparePatchedFile(relative, Buffer.from(first.content + '\n// later local edit\n'), expected), relative);
    }
  });
