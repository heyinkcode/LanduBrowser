import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';
import path from 'node:path';
import vm from 'node:vm';
const { values } = parseArgs({ options: Object.fromEntries(['before', 'after', 'repeat', 'output'].map(key => [key, { type: 'string' }])) });
if (!values.output || !path.isAbsolute(values.output) || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) throw Error('Use an authorized non-C output path');
const input = {}, evidence = [], sources = [];
for (const role of ['before', 'after', 'repeat']) {
  if (!values[role]) throw Error(`Missing ${role}`);
  const bytes = await readFile(values[role]); const report = JSON.parse(bytes);
  if (!report.complete || report.checkFailures?.length || !report.sections?.webgl) throw Error(`Incomplete ${role}`);
  input[role] = report.sections.webgl;
  evidence.push({ role, file: path.basename(values[role]), sha256: createHash('sha256').update(bytes).digest('hex') });
}
const webgl = await readFile(new URL('../verification/webgl.js', import.meta.url), 'utf8');
const context = vm.createContext({}); vm.runInContext(webgl, context);
const baselineChecks = context.landuWebglChecks(input.before);
const baseline = { schema: 1, mode: 'baseline', contexts: input.before, passed: baselineChecks.every(check => check.passed) };
const checks = [...baselineChecks.map(check => ({ round: 'baseline', ...check })),
  ...context.landuWebglChecks(input.after, baseline).map(check => ({ round: 'enabled', ...check })),
  ...context.landuWebglChecks(input.repeat, baseline).map(check => ({ round: 'restart', ...check }))];
for (const scope of ['main', 'iframe', 'dedicatedWorker']) {
  for (const kind of scope === 'dedicatedWorker' ? ['offscreen'] : ['html', 'offscreen']) for (const api of ['webgl', 'webgl2']) {
    const variant = `${kind}-${api}`;
    const a = input.after[scope]?.values?.variants?.[variant], b = input.repeat[scope]?.values?.variants?.[variant];
    checks.push({ round: 'restart', context: scope, variant, test: 'sameNoiseAfterProcessRestart',
      passed: !!a?.available && !!b?.available && a.hash === b.hash && a.flippedHash === b.flippedHash
        && a.sourceHash === b.sourceHash && a.changedBytes === b.changedBytes });
  }
}
for (const relative of ['scripts/compare-webgl-verification.mjs', 'verification/webgl.js', 'verification/canvas.js',
  'scripts/run-device-verification.mjs', 'verification/frame.js', 'verification/worker.js']) {
  const bytes = await readFile(new URL('../' + relative, import.meta.url));
  sources.push({ file: relative, sha256: createHash('sha256').update(bytes).digest('hex') });
}
const result = { schema: 1, checkedAt: new Date().toISOString(), evidence, sources, checks,
  scope: 'Known RGBA8 WebGL 1/2 patterns; main/iframe/dedicated worker, HTML/OffscreenCanvas; no HDR or WebGPU pixel acceptance',
  passed: baseline.passed && checks.length > 0 && checks.every(check => check.passed), releaseReady: false };
await mkdir(path.dirname(values.output), { recursive: true });
await writeFile(values.output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ checks: checks.length, passed: result.passed, failures: checks.filter(check => !check.passed) }));
if (!result.passed) process.exitCode = 1;
