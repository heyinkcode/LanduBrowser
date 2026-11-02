import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';
import path from 'node:path';
import { compareAudio, compareRects } from './signal-comparison.mjs';
const { values } = parseArgs({ options: Object.fromEntries(['before', 'after', 'repeat', 'output'].map(name => [name, { type: 'string' }])) });
if (!values.output || !path.isAbsolute(values.output) || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) throw Error('Use an authorized non-C output path');
const reports = {}, inputs = [];
for (const role of ['before', 'after', 'repeat']) {
  if (!values[role]) throw Error(`Missing ${role}`);
  const bytes = await readFile(values[role]); const report = JSON.parse(bytes);
  if (!report.complete || report.checkFailures?.length) throw Error(`Incomplete input: ${role}`);
  reports[role] = report.sections;
  inputs.push({ role, file: path.basename(values[role]), sha256: createHash('sha256').update(bytes).digest('hex') });
}
const result = { schema: 1, checkedAt: new Date().toISOString(), inputs,
  audio: compareAudio(reports.before.audio, reports.after.audio, reports.repeat.audio),
  rects: compareRects(reports.before.rects, reports.after.rects, reports.repeat.rects), releaseReady: false };
result.verifierSources = [];
for (const relative of ['scripts/signal-comparison.mjs', 'scripts/compare-signal-verification.mjs',
  'scripts/run-device-verification.mjs', 'verification/audio.js', 'verification/audio-worklet.js', 'verification/rects.js']) {
  const bytes = await readFile(new URL('../' + relative, import.meta.url));
  result.verifierSources.push({ file: relative, sha256: createHash('sha256').update(bytes).digest('hex') });
}
result.passed = result.audio.passed && result.rects.passed;
await mkdir(path.dirname(values.output), { recursive: true });
await writeFile(values.output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ audioCorePassed: result.audio.corePassed, audioPassed: result.audio.passed,
  rectsPassed: result.rects.passed, failures: [...result.audio.checks, ...result.audio.coverageChecks, ...result.rects.checks].filter(c => !c.passed) }));
if (!result.passed) process.exitCode = 1;
