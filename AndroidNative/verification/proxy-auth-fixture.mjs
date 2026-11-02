import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import net from 'node:net';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// Only this reserved origin is mapped to the local fixture. No arbitrary HTTP relay.
export const QA_ORIGIN = 'landu-proxy-qa.test';

export async function startProxyFixture({ port = 0, onObservation = () => {} } = {}) {
  const token = randomBytes(16).toString('hex');
  const credentials = Object.fromEntries(['A', 'B'].map(label => [label, {
    username: `landu-qa-${label}`, password: randomBytes(24).toString('base64url'),
  }]));
  const headers = Object.entries(credentials).map(([label, value]) => ({
    label, bytes: Buffer.from(`Basic ${Buffer.from(value.username + ':' + value.password).toString('base64')}`),
  }));
  const observations = [];
  const sockets = new Set();
  const record = value => {
    observations.push({ atUtc: new Date().toISOString(), ...value });
    if (observations.length > 500) observations.shift();
    onObservation(observations);
  };
  const authenticated = request => {
    const input = Buffer.from(request.headers['proxy-authorization'] ?? '');
    return headers.find(item => item.bytes.length === input.length && timingSafeEqual(item.bytes, input))?.label ?? null;
  };
  const ownedPath = value => {
    try {
      const url = new URL(value);
      return url.hostname === QA_ORIGIN && /^\/qa\/[a-zA-Z0-9_-]{1,80}$/.test(url.pathname)
        && !url.search ? url.pathname : null;
    } catch { return null; }
  };
  const origin = http.createServer({ maxHeaderSize: 16384 }, (req, res) => {
    const leaked = req.headers['proxy-authorization'] !== undefined;
    record({ kind: 'origin', path: req.url, proxyAuthorizationLeaked: leaked,
      originAuthorizationPresent: req.headers.authorization !== undefined });
    if (req.url === '/qa/origin-auth') {
      res.writeHead(401, { 'www-authenticate': 'Basic realm="Owned origin is not proxy"',
        'content-type': 'text/plain', 'cache-control': 'no-store' });
      res.end('Origin credentials were not supplied by the QA fixture.');
      return;
    }
    res.writeHead(leaked ? 500 : 200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ fixture: 'landu-authenticated-proxy', path: req.url,
      proxyAuthorizationLeaked: leaked }));
  });
  await new Promise((resolve, reject) => { origin.once('error', reject); origin.listen(0, '127.0.0.1', resolve); });
  const originPort = origin.address().port;
  const proxy = http.createServer({ maxHeaderSize: 16384 }, (req, res) => {
    const label = authenticated(req);
    if (req.url === `/__landu_status/${token}`) {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end(JSON.stringify({ observations }));
      return;
    }
    if (!label) {
      record({ kind: 'challenge', authorized: false, path: ownedPath(req.url) });
      res.writeHead(407, { 'proxy-authenticate': 'Basic realm="Landu owned proxy QA"',
        'content-length': '0', connection: 'close' });
      res.end();
      return;
    }
    let destination;
    try { destination = new URL(req.url); } catch { res.writeHead(400); res.end(); return; }
    if (destination.protocol !== 'http:' || destination.hostname !== QA_ORIGIN
        || destination.port || destination.username || destination.password
        || !/^\/qa\/[a-zA-Z0-9_-]{1,80}$/.test(destination.pathname)
        || destination.search || req.method !== 'GET') {
      record({ kind: 'rejected-target', authorized: true, label });
      res.writeHead(403); res.end(); return;
    }
    record({ kind: 'http-proxy', authorized: true, label, path: destination.pathname });
    // Construct a fresh upstream header set. Proxy credentials must stop here.
    const upstreamHeaders = { host: QA_ORIGIN, connection: 'close' };
    // Preserve origin authentication so this fixture cannot hide an application leak.
    if (req.headers.authorization !== undefined) upstreamHeaders.authorization = req.headers.authorization;
    const upstream = http.get({ hostname: '127.0.0.1', port: originPort,
      path: destination.pathname, headers: upstreamHeaders }, response => {
      res.writeHead(response.statusCode, response.headers);
      response.pipe(res);
    });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    upstream.setTimeout(10000, () => upstream.destroy());
    res.on('close', () => upstream.destroy());
  });
  proxy.on('connect', (req, client, head) => {
    const label = authenticated(req);
    if (!label) {
      record({ kind: 'connect-challenge', authorized: false,
        target: req.url === 'example.com:443' ? req.url : null });
      client.end('HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm="Landu owned proxy QA"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
      return;
    }
    if (req.url !== 'example.com:443') {
      record({ kind: 'rejected-connect-target', authorized: true, label });
      client.end('HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n');
      return;
    }
    record({ kind: 'connect', authorized: true, label, target: 'example.com:443' });
    const upstream = net.connect(443, 'example.com', () => {
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head.length) upstream.write(head);
      client.pipe(upstream).pipe(client);
    });
    sockets.add(upstream);
    upstream.on('close', () => sockets.delete(upstream));
    upstream.setTimeout(30000, () => upstream.destroy());
    client.setTimeout(30000, () => client.destroy());
    upstream.on('error', () => client.destroy());
    client.on('error', () => upstream.destroy());
    client.on('close', () => upstream.destroy());
    upstream.on('close', () => client.destroy());
  });
  proxy.on('connection', socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
  });
  proxy.requestTimeout = 10000;
  proxy.headersTimeout = 10000;
  proxy.keepAliveTimeout = 1000;
  try {
    await new Promise((resolve, reject) => { proxy.once('error', reject); proxy.listen(port, '127.0.0.1', resolve); });
  } catch (error) { await new Promise(resolve => origin.close(resolve)); throw error; }
  return {
    port: proxy.address().port, token, credentials, observations,
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all([new Promise(resolve => proxy.close(resolve)), new Promise(resolve => origin.close(resolve))]);
    },
  };
}

async function main() {
  const [flag, requestedDirectory, serialFlag, serial] = process.argv.slice(2);
  if (flag !== '--output-dir' || !requestedDirectory || serialFlag !== '--serial'
      || !/^[A-Za-z0-9._:-]{1,128}$/.test(serial ?? '') || process.argv.length !== 6) {
    throw new Error('Use --output-dir <existing non-C directory> --serial <owned test phone>');
  }
  const directory = fs.realpathSync(requestedDirectory);
  if (/^c:[\\/]/i.test(directory) || /^\/mnt\/c(?:\/|$)/i.test(directory)) throw new Error('Non-C output required');
  if (!fs.statSync(directory).isDirectory()) throw new Error('Existing output directory required');
  const configPath = path.join(directory, 'owned-proxy-test-config.json');
  const reportPath = path.join(directory, 'proxy-observations.json');
  if (fs.existsSync(configPath) || fs.existsSync(reportPath)) throw new Error('Use a fresh QA output directory');
  const fixture = await startProxyFixture({ port: 18767, onObservation(observations) {
    fs.writeFileSync(reportPath, JSON.stringify({ testOnly: true, observations }, null, 2) + '\n', { mode: 0o600 });
  } });
  try {
    fs.writeFileSync(configPath, JSON.stringify({ testOnly: true, serial,
      proxy: `http://127.0.0.1:${fixture.port}`, origin: QA_ORIGIN,
      credentials: fixture.credentials, controlToken: fixture.token }, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    fs.writeFileSync(reportPath, JSON.stringify({ testOnly: true, observations: [] }) + '\n', { flag: 'wx', mode: 0o600 });
  } catch (error) { await fixture.close(); throw error; }
  process.stdout.write(JSON.stringify({ state: 'listening', port: fixture.port,
    fixtureOnly: true, credentialsLogged: false }) + '\n');
  const shutdown = async () => { await fixture.close(); process.exit(0); };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  main().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
}
