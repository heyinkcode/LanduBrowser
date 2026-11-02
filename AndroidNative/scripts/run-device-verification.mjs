#!/usr/bin/env node
// Uses the phone's USB-forwarded DevTools socket and the loopback-only probe server.
import { parseArgs } from 'node:util';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';

const { values } = parseArgs({ options: {
  port: { type: 'string', default: '18766' },
  target: { type: 'string' },
  output: { type: 'string' },
  label: { type: 'string', default: 'device' },
  cpu: { type: 'string', default: '' },
  memory: { type: 'string', default: '' },
  languages: { type: 'string', default: '' },
  timezone: { type: 'string', default: '' },
  dnt: { type: 'string' },
  sections: { type: 'string', default: 'hardware,identity,canvas,fonts,textCanvas' },
  navigate: { type: 'boolean', default: false },
} });
const port = Number(values.port);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error('Invalid local DevTools port');
if (!values.output || !path.isAbsolute(values.output) || /^(?:c:[\\/]|\/mnt\/c(?:\/|$))/i.test(values.output)) {
  throw Error('Choose an absolute output path on the authorized non-C drive');
}
const allowed = new Set(['hardware', 'identity', 'gpu', 'dnt', 'canvas', 'fonts', 'textCanvas', 'audio', 'rects', 'webgl', 'media', 'mediaBlocked', 'mediaCapture', 'mediaSelect', 'interaction']);
const sections = values.sections.split(',');
if (sections.some(section => !allowed.has(section))) throw Error('Unknown probe section');
if (sections.includes('dnt') && !['enabled', 'disabled'].includes(values.dnt)) {
  throw Error('DNT verification requires --dnt enabled or --dnt disabled from the saved environment setting');
}
const origin = `http://127.0.0.1:${port}`;
const safePage = item => item.type === 'page' && (item.url === 'about:blank'
  || /^http:\/\/127\.0\.0\.1:18765\//.test(item.url));
let list = [];
let discoveryError;
const discoveryDeadline = Date.now() + 20000;
while (Date.now() < discoveryDeadline) {
  try {
    list = await (await fetch(`${origin}/json/list`, {
      signal: AbortSignal.timeout(Math.max(1, Math.min(5000, discoveryDeadline - Date.now()))),
    })).json();
    if (Array.isArray(list) && list.some(safePage)) break;
  } catch (error) { discoveryError = error; }
  await new Promise(resolve => setTimeout(resolve, 250));
}
if (!Array.isArray(list) || !list.some(safePage)) throw Error(`Local browser discovery did not become ready: ${discoveryError || 'no test target'}`);
const target = values.target ? list.find(item => item.id === values.target && safePage(item))
  : list.filter(item => item.url === 'about:blank' && safePage(item)).at(-1) || list.filter(safePage).at(-1);
if (!target) throw Error('No explicitly local test page or blank tab is available');
const socketUrl = new URL(target.webSocketDebuggerUrl);
if (socketUrl.hostname !== '127.0.0.1' || Number(socketUrl.port) !== port || socketUrl.protocol !== 'ws:') {
  throw Error('Refuse a non-loopback debugger endpoint');
}
const socket = new WebSocket(socketUrl);
let sequence = 0;
const pending = new Map();
function rejectPending(reason) {
  for (const request of pending.values()) { clearTimeout(request.timer); request.reject(Error(reason)); }
  pending.clear();
}
socket.addEventListener('close', () => rejectPending('The phone debugger connection closed'));
socket.addEventListener('error', () => rejectPending('The phone debugger connection failed'));
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  const request = pending.get(message.id);
  if (!request) return;
  pending.delete(message.id);
  clearTimeout(request.timer);
  message.error ? request.reject(Error(JSON.stringify(message.error))) : request.resolve(message.result);
});
await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(Error('Debugger connection timed out')), 5000);
  socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
  socket.addEventListener('error', () => { clearTimeout(timer); reject(Error('Debugger connection failed')); }, { once: true });
});
function command(method, params = {}, timeout = 90000) {
  return new Promise((resolve, reject) => {
    if (socket.readyState !== WebSocket.OPEN) { reject(Error('The phone debugger is not connected')); return; }
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(Error(`${method} timed out`)); }, timeout);
    pending.set(id, { resolve, reject, timer });
    try { socket.send(JSON.stringify({ id, method, params })); }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
  });
}
async function evaluate(expression, timeout = 90000) {
  const result = await command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true }, timeout);
  if (result.exceptionDetails) throw Error(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
  return result.result?.value;
}
const report = { format: 1, label: values.label, startedAt: new Date().toISOString(),
  source: 'USB-forwarded installed Android browser; explicit local test target only',
  targetId: target.id, sections: {}, complete: false, releaseReady: false };
async function save() {
  await mkdir(path.dirname(values.output), { recursive: true });
  await writeFile(values.output, JSON.stringify(report, null, 2) + '\n');
}

async function textCanvasDiagnostic() {
  const hash = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    byte => byte.toString(16).padStart(2, '0')).join('');
  const samples = [];
  async function sample(phase) {
    const variants = [];
    for (const software of [false, true]) for (const [kind, text] of [
      ['latin', 'Landu 012345'], ['cjk', '中文'], ['mixed', 'Landu 012345 中文'],
    ]) {
      const canvas = document.createElement('canvas'); canvas.width = 240; canvas.height = 80;
      const context = canvas.getContext('2d', { willReadFrequently: software });
      context.fillStyle = '#4789c4'; context.fillRect(0, 0, 240, 80);
      context.fillStyle = '#fff'; context.font = '18px sans-serif'; context.fillText(text, 10, 45);
      const metrics = context.measureText(text);
      variants.push({ software, kind, width: metrics.width,
        ascent: metrics.actualBoundingBoxAscent, descent: metrics.actualBoundingBoxDescent,
        pixels: await hash(context.getImageData(0, 0, 240, 80).data),
        png: await hash(new TextEncoder().encode(canvas.toDataURL())) });
    }
    samples.push({ phase, fontStatus: document.fonts.status, variants });
  }
  await sample('initial');
  await document.fonts.ready;
  await sample('fonts-ready');
  await new Promise(resolve => setTimeout(resolve, 1500));
  await sample('settled');
  return { samples, note: 'Separate font metrics, raster pixels and PNG encoding; no claim of hardware-independent text rendering' };
}

async function interactionProbe() {
  await evaluate('landuPrepareInteraction()');
  const taps = [];
  try {
    for (const zoom of [1, 1.25]) for (const id of ['large', 'small', 'text']) for (const position of ['center', 'left', 'right', 'top', 'bottom']) {
      const target = await evaluate(`landuInteractionTarget(${JSON.stringify(id)},${zoom},${JSON.stringify(position)})`, 8000);
      if (!(target.width > 0 && target.height > 0 && target.x > 0 && target.y > 0
          && target.x < target.viewport.width && target.y < target.viewport.height)) throw Error('Touch fixture outside viewport');
      await command('Input.synthesizeTapGesture', { x: target.x, y: target.y, gestureSourceType: 'touch', tapCount: 1 }, 10000);
      let actual;
      for (let attempt = 0; attempt < 20; attempt++) {
        actual = await evaluate(`({count:landuInteractionState.counts[${JSON.stringify(id)}],events:landuInteractionState.events.slice(${target.eventOffset}).filter(e=>e.id===${JSON.stringify(id)}&&e.zoom===${zoom})})`, 5000);
        if (actual.count > target.before) break;
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      taps.push({ ...target, actual, passed: actual.count === target.before + 1
        && actual.events.some(event => event.type === 'click' && event.trusted)
        && actual.events.some(event => event.type === 'touchstart' && event.trusted) });
    }
    return { taps, passed: taps.length === 30 && taps.every(tap => tap.passed),
      scope: 'Browser-native trusted touch taps at centers and four near-edge points of three fixed targets at two CSS zoom levels; no JS click fallback' };
  } finally {
    try { await evaluate("document.getElementById('landu-interaction-fixture')?.remove();delete globalThis.landuInteractionState", 3000); }
    catch { /* A renderer failure remains a failed section; do not restart it. */ }
  }
}

try {
  await command('Runtime.enable');
  await command('Page.bringToFront');
  if (values.navigate || target.url === 'about:blank') {
    await command('Page.navigate', { url: 'http://127.0.0.1:18765/' });
  }
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { ready = await evaluate(`location.origin === 'http://127.0.0.1:18765' && typeof landuReadCanvas === 'function' && typeof frameValues === 'function'`); }
    catch { /* A navigation can replace the execution context. */ }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  if (!ready) throw Error('The expected local verification page did not load');
  report.page = await evaluate('({url:location.href,ua:navigator.userAgent,secure:isSecureContext})');
  await evaluate(`Object.entries(${JSON.stringify({ cpu: values.cpu, memory: values.memory,
    languages: values.languages, timezone: values.timezone })}).forEach(([id,value])=>document.getElementById(id).value=value)`);
  if (sections.includes('dnt')) {
    await evaluate(`document.getElementById('dnt-mode').value=${JSON.stringify(values.dnt)}`);
  }
  for (const section of sections) {
    console.log(`Starting ${section} (${values.label})`);
    try {
      let result;
      if (['hardware', 'identity', 'gpu', 'dnt'].includes(section)) {
        const button = section === 'hardware' ? 'run' : section;
        result = await evaluate(`(async()=>{await document.getElementById(${JSON.stringify(button)}).onclick();return report[${JSON.stringify(section)}] || {error:report.error || 'No result'};})()`);
      } else if (section === 'textCanvas') {
        result = await evaluate(`(${textCanvasDiagnostic.toString()})()`);
      } else if (section === 'interaction') {
        result = await interactionProbe();
      } else if (['mediaBlocked', 'mediaCapture', 'mediaSelect'].includes(section)) {
        const call = { mediaBlocked: 'landuReadBlockedMedia', mediaCapture: 'landuCaptureMedia', mediaSelect: 'landuSelectMedia' }[section];
        result = await evaluate(`${call}()`);
      } else if (section === 'webgl') {
        result = await evaluate(`(async()=>{const nonce=crypto.randomUUID();const names=['main','iframe','dedicatedWorker'];
          const results=await Promise.allSettled([landuReadWebgl(),frameValues(nonce,'landu-webgl-probe'),workerValues(nonce,'landu-webgl-probe')]);
          return Object.fromEntries(results.map((r,i)=>[names[i],r.status==='fulfilled'?{values:r.value}:{error:String(r.reason)}]));})()`);
      } else if (section === 'media') {
        result = await evaluate(`(async()=>{const nonce=crypto.randomUUID();
          const results=await Promise.allSettled([landuReadMedia(),frameValues(nonce,'landu-media-probe')]);
          return Object.fromEntries(results.map((r,i)=>[['main','iframe'][i],r.status==='fulfilled'?{values:r.value}:{error:String(r.reason)}]));})()`);
      } else if (['audio', 'rects'].includes(section)) {
        const call = section === 'audio' ? 'landuReadAudio' : 'landuReadClientRects';
        result = await evaluate(`(async()=>{const nonce=crypto.randomUUID();
          const results=await Promise.allSettled([${call}(),frameValues(nonce,'landu-${section}-probe')]);
          return Object.fromEntries(results.map((r,i)=>[['main','iframe'][i],r.status==='fulfilled'?{values:r.value}:{error:String(r.reason)}]));})()`);
      } else {
        const type = section === 'canvas' ? 'landu-canvas-probe' : 'landu-font-probe';
        const call = section === 'canvas' ? 'landuReadCanvas' : 'landuReadFonts';
        result = await evaluate(`(async()=>{const nonce=crypto.randomUUID();const names=['main','iframe','dedicatedWorker','serviceWorker'];
          const results=await Promise.allSettled([${call}(),frameValues(nonce,${JSON.stringify(type)}),workerValues(nonce,${JSON.stringify(type)}),serviceWorkerValues(nonce,${JSON.stringify(type)})]);
          return Object.fromEntries(results.map((r,i)=>[names[i],r.status==='fulfilled'?{values:r.value}:{error:String(r.reason)}]));})()`);
      }
      report.sections[section] = result;
      console.log(`Finished ${section}: ${result?.error ? result.error : result?.passed ?? 'observations saved'}`);
    } catch (error) {
      report.sections[section] = { error: String(error) };
      console.log(`Failed ${section}: ${error}`);
    }
    await save();
  }
  report.complete = true;
  report.completeMeaning = 'collection completed; raw observations alone are not acceptance';
  report.checkFailures = Object.entries(report.sections).flatMap(([name, section]) => {
    const failures = [];
    if (section?.error || section?.timeout || section?.late || section?.passed === false
        || (name === 'mediaCapture' && (section.granted !== true || section.tracksStopped !== true))) failures.push(name);
    if (name === 'mediaSelect' && (!section.selections?.length || section.selections.some(value =>
      value.error || value.timeout || value.late || value.tracksStopped !== true))) failures.push(name);
    for (const [context, value] of Object.entries(section || {})) {
      if (value?.error || Object.values(value?.values?.variants || {}).some(variant => variant?.error)) {
        failures.push(`${name}:${context}`);
      }
    }
    return failures;
  });
  report.observationOnlySections = Object.entries(report.sections)
    .filter(([, section]) => typeof section?.passed !== 'boolean').map(([name]) => name);
  report.finishedAt = new Date().toISOString();
  await save();
  if (report.checkFailures.length) process.exitCode = 1;
  console.log(`Saved ${values.output}`);
} finally {
  socket.close();
}
