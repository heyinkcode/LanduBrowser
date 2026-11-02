#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import vm from 'node:vm';
import path from 'node:path';

const { values } = parseArgs({ options: {
  'fonts-before': { type: 'string' }, 'canvas-before': { type: 'string' },
  after: { type: 'string' }, repeat: { type: 'string' }, output: { type: 'string' },
} });
if (!values.output || !path.isAbsolute(values.output) || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) throw Error('Use an authorized non-C output path');
async function load(filename) {
  if (!filename) throw Error('A required device report is missing');
  const raw = await readFile(filename, 'utf8');
  return { data: JSON.parse(raw.replace(/^\uFEFF/, '')), file: path.basename(filename),
    sha256: createHash('sha256').update(raw).digest('hex') };
}
const [fontsBefore, canvasBefore, after, repeat] = await Promise.all([
  load(values['fonts-before']), load(values['canvas-before']), load(values.after), load(values.repeat),
]);
const sandbox = {};
const verifierSources = [];
for (const name of ['fonts', 'canvas']) {
  const source = await readFile(new URL(`../verification/${name}.js`, import.meta.url), 'utf8');
  verifierSources.push({ file: `verification/${name}.js`, sha256: createHash('sha256').update(source).digest('hex') });
  vm.runInNewContext(source, sandbox);
}
const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
const fonts = Object.fromEntries(names.map(name => [name, sandbox.landuCompareFonts(
  fontsBefore.data.sections.fonts?.[name]?.values, after.data.sections.fonts?.[name]?.values,
  name === 'main' || name === 'iframe')]));
const canvas = sandbox.landuCanvasChecks(after.data.sections.canvas, { contexts: canvasBefore.data.sections.canvas });
const reference = after.data.sections.canvas?.main?.values?.variants?.html?.hash;
for (const name of names) {
  for (const kind of name === 'main' || name === 'iframe' ? ['html', 'offscreen'] : ['offscreen']) {
    const current = after.data.sections.canvas?.[name]?.values?.variants?.[kind]?.hash;
    const previous = repeat.data.sections.canvas?.[name]?.values?.variants?.[kind]?.hash;
    canvas.push({ context: name, canvas: kind, property: 'crossContextPatternConsistent',
      passed: typeof reference === 'string' && current === reference });
    canvas.push({ context: name, canvas: kind, property: 'processRestartPatternStable',
      passed: typeof current === 'string' && current === previous });
  }
}
const sectionChecks = ['hardware', 'identity', 'gpu'].map(name => ({ section: name,
  passed: after.data.sections[name]?.passed === true,
  assertions: after.data.sections[name]?.checks?.length || 0 }));
const roles = ['fonts disabled baseline only', 'Canvas disabled baseline only',
  'current enabled probes and explicit hardware/identity/GPU checks', 'Canvas process-restart reference only'];
const evidence = [fontsBefore, canvasBefore, after, repeat].map(({ file, sha256 }, index) => ({ file, sha256, role: roles[index] }));
const report = { format: 1, checkedAt: new Date().toISOString(), evidence,
  verifierSources,
  scope: 'Android 15 tested A profile; named-font filtering and deterministic RGBA8 Canvas paths only',
  fonts, canvas, sectionChecks,
  passed: Object.values(fonts).every(value => value.outcome === 'passed')
    && canvas.every(check => check.passed) && sectionChecks.every(check => check.passed),
  releaseReady: false,
  remaining: ['The first post-install text Canvas hash from the earlier smoke run remains unexplained',
    'Hardware-independent glyph rendering, all graphics formats, other GPUs and Android 16 are not certified'] };
await mkdir(path.dirname(values.output), { recursive: true });
await writeFile(values.output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ passed: report.passed, fonts: Object.fromEntries(Object.entries(fonts).map(([name, result]) =>
  [name, { outcome: result.outcome, blockedObservedFonts: result.checks.filter(check => check.outcome === 'passed').map(check => check.name) }])),
  canvasAssertions: canvas.length, failedCanvas: canvas.filter(check => !check.passed), sectionChecks }, null, 2));
if (!report.passed) process.exitCode = 1;
