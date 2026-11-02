import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { QA_ORIGIN } from './proxy-auth-fixture.mjs';

async function json(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
  if (!response.ok) throw new Error(`QA endpoint returned ${response.status}`);
  return response.json();
}

export function assessProxyObservations(observations, { label, casePath, since, mode }) {
  if (!Array.isArray(observations) || observations.some(item => !item
      || !Number.isFinite(Date.parse(item.atUtc)) || typeof item.kind !== 'string')) {
    return { passed: false, failures: ['Missing or malformed proxy observations'], observations: [] };
  }
  const events = observations.filter(item => Date.parse(item.atUtc) >= Date.parse(since));
  const own = events.filter(item => item.path === casePath);
  const authorized = own.filter(item => item.kind === 'http-proxy' && item.authorized);
  const origin = own.filter(item => item.kind === 'origin');
  const challenges = own.filter(item => item.kind === 'challenge');
  const failures = [];
  if (mode === 'denied') {
    if (authorized.length || origin.length) failures.push('Wrong credentials reached an authorized proxy/origin request');
    if (!challenges.length || challenges.length > 3) failures.push('Missing denial or repeated automatic authentication loop');
  } else if (mode === 'http' || mode === 'origin-auth') {
    if (!authorized.length || authorized.some(item => item.label !== label)) failures.push('Missing or wrong environment proxy credentials');
    if (!origin.length) failures.push('No real upstream fixture request');
    if (origin.some(item => item.proxyAuthorizationLeaked !== false || item.originAuthorizationPresent !== false)) {
      failures.push('Origin credential absence was not proved');
    }
  } else if (mode === 'connect') {
    const tunnels = events.filter(item => item.kind === 'connect' && item.target === 'example.com:443' && item.authorized);
    if (!tunnels.length || tunnels.some(item => item.label !== label)) failures.push('Missing or wrong authenticated HTTPS tunnel');
  } else failures.push('Unknown QA mode');
  return { passed: failures.length === 0, failures, observations: events };
}

async function readPage(expectedUrl) {
  const pages = await json('http://127.0.0.1:18766/json/list');
  const candidates = pages.filter(item => item.type === 'page' && item.url === expectedUrl);
  if (candidates.length !== 1) throw new Error('Expected one exact owned diagnostic page');
  const page = candidates[0];
  if (!/^ws:\/\/127\.0\.0\.1:18766\//.test(page.webSocketDebuggerUrl ?? '')) throw new Error('Unexpected CDP endpoint');
  await fetch(`http://127.0.0.1:18766/json/activate/${encodeURIComponent(page.id)}`, { signal: AbortSignal.timeout(5000) });
  const socket = new WebSocket(page.webSocketDebuggerUrl);
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP connection timeout')), 5000);
      socket.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('CDP connection failed')); }, { once: true });
    });
    for (let attempt = 1; attempt <= 24; ++attempt) {
      const value = await new Promise((resolve, reject) => {
        const cleanup = () => { clearTimeout(timer); socket.removeEventListener('message', onMessage); };
        const onMessage = event => {
          const message = JSON.parse(event.data);
          if (message.id !== attempt) return;
          cleanup();
          if (message.error || message.result?.exceptionDetails) { reject(new Error('Owned page readback failed')); return; }
          resolve(message.result?.result?.value);
        };
        const timer = setTimeout(() => { cleanup(); reject(new Error('Owned page readback timeout')); }, 5000);
        socket.addEventListener('message', onMessage);
        socket.send(JSON.stringify({ id: attempt, method: 'Runtime.evaluate', params: {
          returnByValue: true, expression: `(() => {
            const text = document.querySelector('pre')?.textContent ?? document.body?.innerText ?? '';
            let fixture = null; try { fixture = JSON.parse(text); } catch {}
            return {href:location.href,title:document.title,ready:document.readyState,secure:isSecureContext,fixture};
          })()`,
        } }));
      });
      // A target URL changes before its new main-world document is committed.
      if (value?.href === expectedUrl && value.ready !== 'loading') return { targetId: page.id, ...value };
      await new Promise(resolve => setTimeout(resolve, 250));
    }
    throw new Error('Expected owned document did not finish committing');
  } finally { socket.close(); }
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length % 2) throw new Error('Expected named QA arguments');
  const options = Object.fromEntries(Array.from({ length: args.length / 2 }, (_, index) => args.slice(index * 2, index * 2 + 2)));
  if (Object.keys(options).some(key => !['--config', '--output', '--label', '--path', '--since', '--mode', '--serial'].includes(key))) throw new Error('Unknown argument');
  const label = options['--label'], mode = options['--mode'], casePath = options['--path'];
  const since = options['--since'];
  if (!['A', 'B'].includes(label) || !['http', 'origin-auth', 'denied', 'connect'].includes(mode)
      || !/^\/qa\/[A-Za-z0-9_-]{1,80}$/.test(casePath ?? '')
      || !Number.isFinite(Date.parse(since)) || Date.parse(since) > Date.now()
      || Date.now() - Date.parse(since) > 10 * 60 * 1000) throw new Error('Bounded owned proxy test arguments required');
  const output = path.resolve(options['--output'] ?? '');
  const directory = fs.realpathSync(path.dirname(output));
  if (/^c:[\\/]/i.test(directory) || /^\/mnt\/c(?:\/|$)/i.test(directory)
      || path.join(directory, path.basename(output)) !== output || fs.existsSync(output)) throw new Error('Fresh non-C report path required');
  const config = JSON.parse(fs.readFileSync(options['--config'], 'utf8').replace(/^\uFEFF/, ''));
  if (!config.testOnly || !/^[A-Za-z0-9._:-]{1,128}$/.test(options['--serial'] ?? '')
      || config.serial !== options['--serial'] || config.origin !== QA_ORIGIN
      || config.proxy !== 'http://127.0.0.1:18767' || !/^[a-f0-9]{32}$/.test(config.controlToken)) throw new Error('Owned fixture configuration required');
  let assessment;
  let page = null;
  let error = null;
  try {
    const status = await json(`http://127.0.0.1:18767/__landu_status/${config.controlToken}`);
    assessment = assessProxyObservations(status.observations, { label, casePath, since, mode });
    if (mode === 'http' || mode === 'connect') {
      const url = mode === 'connect' ? 'https://example.com/' : `http://${QA_ORIGIN}${casePath}`;
      page = await readPage(url);
      if (mode === 'http' && (page.fixture?.fixture !== 'landu-authenticated-proxy'
          || page.fixture.path !== casePath || page.fixture.proxyAuthorizationLeaked !== false)) {
        assessment.failures.push('Browser did not render the actual expected proxy response');
      }
      if (mode === 'connect' && (page.title !== 'Example Domain' || page.secure !== true)) assessment.failures.push('Expected secure public HTTPS page not rendered');
    }
  } catch (failure) { error = failure.message; }
  const passed = !error && !!assessment?.passed && !assessment.failures.length;
  const report = { kind: 'owned-device-proxy-observation', checkedAtUtc: new Date().toISOString(),
    label, mode, casePath, since, passed, failures: assessment?.failures ?? [], error, page,
    observations: assessment?.observations ?? [], actualBrowserPageRead: !!page,
    requiresSeparateUiPromptEvidence: ['origin-auth', 'denied'].includes(mode),
    androidDevice: config.serial, credentialsLogged: false, releaseReady: false };
  const serialized = JSON.stringify(report, null, 2) + '\n';
  for (const secret of Object.values(config.credentials)) {
    if (serialized.includes(secret.password) || serialized.includes(secret.username)) throw new Error('QA report secret leak prevented');
  }
  fs.writeFileSync(output, serialized, { flag: 'wx' });
  process.stdout.write(JSON.stringify({ passed, report: output }) + '\n');
  if (!passed) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  await main().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
}
