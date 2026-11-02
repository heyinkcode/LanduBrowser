import fs from 'node:fs';

export const manifestPolicy = JSON.parse(fs.readFileSync(new URL('../manifest-component-policy.json', import.meta.url), 'utf8'));
if (manifestPolicy.format !== 1) throw new Error('Unsupported manifest component policy');
const names = new Set();
for (const entry of [...manifestPolicy.privateComponents, ...manifestPolicy.libraryOverrides]) {
  if (!['activity', 'activity-alias', 'service', 'receiver', 'provider'].includes(entry.tag)
      || !/^[A-Za-z0-9_.$]+$/.test(entry.name) || names.has(entry.name)
      || Object.hasOwn(manifestPolicy.allowedExternalAliases, entry.name)
      || (entry.originalExports && (!Array.isArray(entry.originalExports) || !entry.originalExports.length
        || entry.originalExports.some(value => !['true', 'false'].includes(value))))) throw new Error('Invalid manifest component restriction');
  names.add(entry.name);
}

function attribute(header, name, before, after) {
  const pattern = new RegExp(` android:${name}="([^"]*)"`, 'g');
  const matches = [...header.matchAll(pattern)];
  if (matches.length > 1 || (matches[0]?.[1] ?? null) !== before) throw new Error(`Pinned component ${name} changed`);
  if (before === null) return header.replace(/(\s*\/?>)$/, ` android:${name}="${after}"$1`);
  return header.replace(pattern, after === null ? '' : ` android:${name}="${after}"`);
}

// Only opening tags of explicitly reviewed, hash-pinned components are edited.
// The inverse is checked against the original whole-file digest by overlay-plan.
export function restrictManifestComponents(text, reverse = false) {
  const entries = new Map(manifestPolicy.privateComponents.map(entry => [entry.name, entry]));
  const seen = new Map();
  const result = text.replace(/<(activity-alias|activity|service|receiver|provider)\b[^>]*>/g, (header, tag) => {
    const name = /\bandroid:name="([^"]+)"/.exec(header)?.[1];
    const entry = entries.get(name);
    if (!entry) return header;
    const originals = entry.originalExports ?? ['true'];
    const index = seen.get(name) ?? 0;
    if (tag !== entry.tag || index >= originals.length) throw new Error('Pinned manifest component missing or ambiguous: ' + name);
    seen.set(name, index + 1);
    if (reverse && entry.disable) header = attribute(header, 'enabled', 'false', null);
    header = attribute(header, 'exported', reverse ? 'false' : originals[index], reverse ? originals[index] : 'false');
    if (!reverse && entry.disable) header = attribute(header, 'enabled', null, 'false');
    return header;
  });
  if (seen.size !== entries.size || [...entries].some(([name, entry]) => seen.get(name) !== (entry.originalExports ?? ['true']).length))
    throw new Error('Pinned manifest component is missing');
  // Private viewers are reached explicitly. Do not advertise ACTION_VIEW for
  // implicit in-app navigation: this can resolve outside the selected route.
  return result.replace(/<(activity-alias|activity)\b(?![^>]*\/>)[^>]*>[\s\S]*?<\/\1>/g, block => {
    const name = /\bandroid:name="([^"]+)"/.exec(block)?.[1];
    if (!entries.has(name)) return block;
    const action = `android:name="${name}.LANDU_PRIVATE_VIEW"`;
    return reverse
      ? block.replaceAll(action, 'android:name="android.intent.action.VIEW"')
      : block.replaceAll('android:name="android.intent.action.VIEW"', action);
  });
}

export const libraryManifestOverrides = manifestPolicy.libraryOverrides.map(entry =>
  `      <${entry.tag} android:name="${entry.name}" android:exported="false"` +
  (entry.disable ? ' android:enabled="false" tools:replace="android:exported,android:enabled"' : ' tools:replace="android:exported"') +
  ' />\n').join('');
