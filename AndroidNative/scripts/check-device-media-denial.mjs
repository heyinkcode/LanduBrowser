#!/usr/bin/env node
// Read-only state checks after temporary media permission tests. Never grants or revokes.
import { execFile } from 'node:child_process';
import { promisify, parseArgs } from 'node:util';
import { realpath, lstat, writeFile, rename, unlink } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const execute = promisify(execFile);
const packageName = 'com.landu.browser';
const permissionNames = ['android.permission.CAMERA', 'android.permission.RECORD_AUDIO'];
const isC = name => /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(name);

export function parseMediaPermissions(dump, userId) {
  if (!Number.isSafeInteger(userId) || userId < 0) throw Error('Invalid Android user');
  const sections = [...dump.matchAll(/^\s*User (\d+):[^\n]*\n/gm)];
  const index = sections.findIndex(item => Number(item[1]) === userId);
  if (index < 0) throw Error('Requested Android user is missing from package dump');
  const section = dump.slice(sections[index].index, sections[index + 1]?.index ?? dump.length);
  const runtime = section.indexOf('runtime permissions:');
  if (runtime < 0) throw Error('Runtime permission state is missing');
  const lines = section.slice(runtime).split(/\r?\n/);
  return Object.fromEntries(permissionNames.map(name => {
    const prefix = `${name}: granted=`;
    const matches = lines.map(line => line.trim()).filter(line => line.startsWith(prefix));
    if (matches.length !== 1) throw Error(`Missing or ambiguous runtime permission: ${name}`);
    const entry = matches[0].match(/^android\.permission\.(?:CAMERA|RECORD_AUDIO): granted=(true|false), flags=\[([^\]]*)\]\s*$/);
    if (!entry) throw Error(`Unrecognized runtime permission state: ${name}`);
    return [name, { granted: entry[1] === 'true', flags: entry[2].split('|').map(flag => flag.trim()).filter(Boolean) }];
  }));
}

export function parseAppOp(dump, operation) {
  if (!['CAMERA', 'RECORD_AUDIO'].includes(operation)) throw Error('Unsupported media operation');
  const uidMode = dump.match(new RegExp(`^Uid mode: ${operation}: ([a-z_]+)`, 'm'))?.[1] ?? null;
  const packageMode = dump.match(new RegExp(`^${operation}: ([a-z_]+)`, 'm'))?.[1] ?? null;
  return { uidMode, packageMode, raw: dump.trim() };
}

export function assessMediaDenial(samples, requireAppOpsDenied = false) {
  if (!samples.length) return { passed: false, failedSamples: [], reason: 'No observations' };
  const failedSamples = [];
  for (const [index, sample] of samples.entries()) {
    const permissionsDenied = permissionNames.every(name => sample.permissions?.[name]?.granted === false);
    const operationsDenied = ['CAMERA', 'RECORD_AUDIO'].every(name => {
      const op = sample.appOps?.[name];
      if (!op) return false;
      // UID overrides must win over an older per-package allow entry.
      return ['ignore', 'deny', 'errored'].includes(op.uidMode ?? op.packageMode);
    });
    if (!permissionsDenied || (requireAppOpsDenied && !operationsDenied)) failedSamples.push(index);
  }
  return { passed: failedSamples.length === 0, failedSamples };
}

async function main() {
  const { values } = parseArgs({ options: {
    adb: { type: 'string' }, serial: { type: 'string' }, output: { type: 'string' },
    samples: { type: 'string', default: '6' }, 'interval-ms': { type: 'string', default: '4000' },
    'require-appops-denied': { type: 'boolean', default: false },
  } });
  const count = Number(values.samples), interval = Number(values['interval-ms']);
  if (!values.adb || !path.isAbsolute(values.adb) || !/^[a-zA-Z0-9._:-]{1,128}$/.test(values.serial ?? '')
      || !values.output || !path.isAbsolute(values.output) || isC(values.output)
      || !Number.isInteger(count) || count < 2 || count > 20
      || !Number.isInteger(interval) || interval < 100 || interval > 10000) {
    throw Error('Use an absolute adb path, explicit device serial, non-C output, 2..20 samples and 100..10000ms interval');
  }
  const adb = await realpath(values.adb);
  const parent = await realpath(path.dirname(values.output));
  if (isC(parent)) throw Error('Output resolves to drive C');
  const output = path.join(parent, path.basename(values.output));
  try { if ((await lstat(output)).isSymbolicLink()) throw Error('Linked output is not allowed'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  const command = async (...args) => {
    const result = await execute(adb, ['-s', values.serial, 'shell', ...args], {
      windowsHide: true, encoding: 'utf8', timeout: 10000, maxBuffer: 8 * 1024 * 1024,
    });
    return result.stdout;
  };
  const userText = (await command('am', 'get-current-user')).trim();
  if (!/^\d+$/.test(userText)) throw Error('Current Android user could not be verified');
  const userId = Number(userText);
  const observations = [];
  const started = performance.now();
  let failure;
  try {
    for (let i = 0; i < count; i++) {
      const permissions = parseMediaPermissions(await command('dumpsys', 'package', packageName), userId);
      const appOps = {};
      for (const operation of ['CAMERA', 'RECORD_AUDIO']) {
        appOps[operation] = parseAppOp(await command('cmd', 'appops', 'get', '--user', userText, packageName, operation), operation);
      }
      observations.push({ atUtc: new Date().toISOString(), elapsedMs: Math.round(performance.now() - started), permissions, appOps });
      if (i + 1 < count) await new Promise(resolve => setTimeout(resolve, interval));
    }
  } catch (error) { failure = String(error); }
  const result = assessMediaDenial(observations, values['require-appops-denied']);
  const report = {
    kind: 'device-media-denial-state-only', checkedAtUtc: new Date().toISOString(), package: packageName,
    serial: values.serial, userId, requestedSamples: count, intervalMs: interval,
    requireAppOpsDenied: values['require-appops-denied'], observations,
    ...result, passed: !failure && observations.length === count && result.passed,
    error: failure ?? null, permissionsChangedByThisScript: false, actualSensorAccessTested: false,
    doesNotCertify: ['Actual camera or microphone access', 'Future updates or other devices', 'Browser site-permission decisions'],
  };
  const temporary = `${output}.${process.pid}.tmp`;
  try { await writeFile(temporary, JSON.stringify(report, null, 2) + '\n', { flag: 'wx' }); await rename(temporary, output); }
  finally { await unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error; }); }
  console.log(JSON.stringify({ passed: report.passed, samples: observations.length, failedSamples: result.failedSamples, report: output }));
  if (!report.passed) process.exitCode = 1;
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  await main().catch(error => { console.error(String(error)); process.exitCode = 1; });
}
