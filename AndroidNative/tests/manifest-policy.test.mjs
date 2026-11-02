import assert from 'node:assert/strict';
import test from 'node:test';
import { manifestPolicy, restrictManifestComponents, libraryManifestOverrides } from '../scripts/manifest-policy.mjs';

const fixture = () => '<application>\n' + manifestPolicy.privateComponents.flatMap(entry =>
  (entry.originalExports ?? ['true']).map(value =>
    `  <${entry.tag} android:name="${entry.name}"\n    android:exported="${value}" android:permission="unchanged" />`)).join('\n') +
  '\n  <service android:name="unrelated.Service" android:exported="false" />\n</application>';

test('explicit reviewed entries become private, with unsupported registrations disabled', () => {
  const original = fixture(), result = restrictManifestComponents(original);
  assert.equal((result.match(/android:exported="true"/g) ?? []).length, 0);
  assert.equal((result.match(/android:enabled="false"/g) ?? []).length,
    manifestPolicy.privateComponents.filter(entry => entry.disable).reduce((count, entry) => count + (entry.originalExports ?? ['true']).length, 0));
  assert.equal((result.match(/android:permission="unchanged"/g) ?? []).length,
    manifestPolicy.privateComponents.reduce((count, entry) => count + (entry.originalExports ?? ['true']).length, 0));
  assert.match(result, /<service android:name="unrelated.Service" android:exported="false" \/>/);
  assert.equal(restrictManifestComponents(result, true), original);
});

test('two selected manager aliases and unrelated text are preserved exactly', () => {
  const extra = Object.keys(manifestPolicy.allowedExternalAliases).map(name =>
    `<activity-alias android:name="${name}" android:exported="true" />`).join('\n');
  const result = restrictManifestComponents(fixture() + extra);
  assert.equal(result.endsWith(extra), true);
  assert.equal((result.match(/android:exported="true"/g) ?? []).length, 2);
});

test('missing, duplicate, differently typed and locally changed components are rejected', () => {
  const original = fixture(), first = manifestPolicy.privateComponents[0];
  assert.throws(() => restrictManifestComponents(original.replace(first.name, 'missing.Component')));
  assert.throws(() => restrictManifestComponents(original + `<${first.tag} android:name="${first.name}" android:exported="true" />`));
  assert.throws(() => restrictManifestComponents(original.replace('<' + first.tag, '<service')));
  assert.throws(() => restrictManifestComponents(original.replace('android:exported="true"', 'android:exported="false"')));
  const disabled = manifestPolicy.privateComponents.find(entry => entry.disable);
  assert.throws(() => restrictManifestComponents(original.replace(`android:name="${disabled.name}"`, `android:name="${disabled.name}" android:enabled="true"`)));
  assert.throws(() => restrictManifestComponents(restrictManifestComponents(original).replace('android:permission="unchanged"', 'android:exported="false"')));
});

test('library override is explicitly private and disabled, without changing its permission', () => {
  assert.match(libraryManifestOverrides, /FirebaseInstanceIdReceiver/);
  assert.match(libraryManifestOverrides, /android:exported="false"/);
  assert.match(libraryManifestOverrides, /android:enabled="false"/);
  assert.doesNotMatch(libraryManifestOverrides, /android:permission=/);
});

test('private viewer filters do not intercept implicit VIEW and remain reversible', () => {
  const viewer = manifestPolicy.privateComponents[0];
  const original = fixture().replace(
    `<${viewer.tag} android:name="${viewer.name}"\n    android:exported="true" android:permission="unchanged" />`,
    `<${viewer.tag} android:name="${viewer.name}" android:exported="true">` +
      '<intent-filter><action android:name="android.intent.action.VIEW" /></intent-filter>' +
      `</${viewer.tag}>`);
  const publicRoute = '<activity android:name="unrelated.Public" android:exported="true">' +
    '<intent-filter><action android:name="android.intent.action.VIEW" /></intent-filter></activity>';
  const result = restrictManifestComponents(original + publicRoute);
  assert.match(result, /PdfLauncherActivityAlias\.LANDU_PRIVATE_VIEW/);
  assert.equal(result.endsWith(publicRoute), true);
  assert.equal(restrictManifestComponents(result, true), original + publicRoute);
});
