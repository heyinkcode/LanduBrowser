import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { parseArgs } from 'node:util';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { compareMedia } from './media-comparison.mjs';
const roles = ['initial', 'blocked', 'permission-restored', 'isolated', 'hidden', 'restored', 'other', 'selection'];
const { values } = parseArgs({ options: Object.fromEntries([...roles, 'output'].map(key => [key, { type: 'string' }])) });
if (!values.output || !path.isAbsolute(values.output) || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) throw Error('Use an authorized non-C output path');
const reports = {}, evidence = [];
for (const role of roles) {
  if (!values[role]) throw Error(`Missing ${role}`);
  const bytes = await readFile(values[role]); const report = JSON.parse(bytes);
  if (!report.complete || report.checkFailures?.length) throw Error(`Incomplete input: ${role}`);
  reports[role] = report.sections;
  evidence.push({ role, file: path.basename(values[role]), sha256: createHash('sha256').update(bytes).digest('hex') });
}
const capture = sections => ({ ...sections.mediaCapture, selection: sections.mediaSelect });
const result = compareMedia({ initial: reports.initial.media, blocked: reports.blocked.mediaBlocked,
  restoredPermission: reports['permission-restored'].media, isolated: capture(reports.isolated),
  hidden: capture(reports.hidden), restored: capture(reports.restored), other: capture(reports.other),
  selection: reports.selection.mediaSelect });
result.evidence = evidence; result.checkedAt = new Date().toISOString(); result.releaseReady = false;
result.sources = [];
for (const relative of ['scripts/media-comparison.mjs', 'scripts/compare-media-verification.mjs', 'verification/media.js',
  'scripts/run-device-verification.mjs', 'verification/frame.js']) {
  const bytes = await readFile(new URL('../' + relative, import.meta.url));
  result.sources.push({ file: relative, sha256: createHash('sha256').update(bytes).digest('hex') });
}
await mkdir(path.dirname(values.output), { recursive: true });
await writeFile(values.output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({ checks: result.checks.length, passed: result.passed, failures: result.checks.filter(check => !check.passed) }));
if (!result.passed) process.exitCode = 1;
