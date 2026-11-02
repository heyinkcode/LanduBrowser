import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import net from 'node:net';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const QA_SOCKS_ORIGIN = 'landu-socks-qa.test';

// Never relay arbitrary destinations. Only this reserved test origin reaches a
// loopback HTTP server, so a test cannot consume the user's real proxy account.
export async function startSocks5Fixture({ port = 0, onObservation = () => {} } = {}) {
  const credentials = Object.fromEntries(['A', 'B'].map(label => [label, {
    username: `landu-socks-qa-${label}`, password: randomBytes(24).toString('base64url'),
  }]));
  const observations = [];
  const sockets = new Set();
  let rejectAuthentication = false;
  const record = item => {
    observations.push({ atUtc: new Date().toISOString(), ...item });
    if (observations.length > 500) observations.shift();
    onObservation(observations);
  };
  const equal = (first, second) => {
    const value = Buffer.from(second, 'utf8');
    return value.length === first.length && timingSafeEqual(first, value);
  };
  const ownSocket = socket => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
    socket.on('error', () => {});
    socket.setTimeout(10000, () => socket.destroy());
    return socket;
  };
  const origin = http.createServer({ maxHeaderSize: 16384 }, (req, res) => {
    const valid = req.method === 'GET' && req.headers.host === QA_SOCKS_ORIGIN
      && /^\/qa\/[a-zA-Z0-9_-]{1,80}$/.test(req.url);
    const proxyHeaderPresent = req.headers['proxy-authorization'] !== undefined;
    record({ kind: 'origin', path: valid ? req.url : null, proxyHeaderPresent,
      originAuthorizationPresent: req.headers.authorization !== undefined });
    const body = JSON.stringify({ fixture: 'landu-authenticated-socks5',
      path: valid ? req.url : null, proxyHeaderPresent });
    res.writeHead(valid && !proxyHeaderPresent ? 200 : 403, {
      'content-type': 'application/json', 'cache-control': 'no-store', connection: 'close',
      'content-length': Buffer.byteLength(body),
    });
    res.end(body);
  });
  origin.on('connection', ownSocket);
  await new Promise((resolve, reject) => {
    origin.once('error', reject); origin.listen(0, '127.0.0.1', resolve);
  });
  const originPort = origin.address().port;
  const proxy = net.createServer(socket => {
    ownSocket(socket);
    const reader = new ExactReader(socket);
    (async () => {
      const greeting = await reader.read(2);
      if (greeting[0] !== 5 || greeting[1] === 0) throw Error('greeting');
      const methods = await reader.read(greeting[1]);
      if (!methods.includes(2)) {
        record({ kind: 'method-rejected' }); socket.end(Buffer.from([5, 255])); return;
      }
      socket.write(Buffer.from([5, 2]));
      const header = await reader.read(2);
      if (header[0] !== 1 || header[1] === 0) throw Error('authentication');
      const username = await reader.read(header[1]);
      const passwordSize = (await reader.read(1))[0];
      const password = await reader.read(passwordSize);
      const label = !rejectAuthentication && passwordSize > 0
        ? Object.entries(credentials).find(([, value]) => equal(username, value.username)
          && equal(password, value.password))?.[0] : null;
      username.fill(0); password.fill(0);
      if (!label) {
        record({ kind: 'authentication-rejected' }); socket.end(Buffer.from([1, 1])); return;
      }
      record({ kind: 'authenticated', label });
      socket.write(Buffer.from([1, 0]));
      const request = await reader.read(4);
      if (request[0] !== 5 || request[1] !== 1 || request[2] !== 0 || request[3] !== 3) {
        record({ kind: 'request-rejected', label });
        socket.end(Buffer.from([5, 7, 0, 1, 0, 0, 0, 0, 0, 0])); return;
      }
      const hostSize = (await reader.read(1))[0];
      const hostname = (await reader.read(hostSize)).toString('ascii');
      const targetPort = (await reader.read(2)).readUInt16BE();
      if (hostname !== QA_SOCKS_ORIGIN || targetPort !== 80) {
        record({ kind: 'target-rejected', label });
        socket.end(Buffer.from([5, 2, 0, 1, 0, 0, 0, 0, 0, 0])); return;
      }
      const upstream = ownSocket(net.connect(originPort, '127.0.0.1'));
      await new Promise((resolve, reject) => {
        upstream.once('connect', resolve); upstream.once('error', reject);
      });
      record({ kind: 'tunnel', label, hostname: QA_SOCKS_ORIGIN, targetPort: 80 });
      socket.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, 0, 0]));
      const queued = reader.release();
      if (queued.length) upstream.write(queued);
      socket.pipe(upstream).pipe(socket);
      socket.on('close', () => upstream.destroy());
      upstream.on('close', () => socket.destroy());
    })().catch(() => {
      record({ kind: 'connection-ended-before-tunnel' }); socket.destroy();
    }).finally(() => reader.dispose());
  });
  try {
    await new Promise((resolve, reject) => {
      proxy.once('error', reject); proxy.listen(port, '127.0.0.1', resolve);
    });
  } catch (error) {
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => origin.close(resolve)); throw error;
  }
  return {
    port: proxy.address().port, credentials, observations,
    setRejectAuthentication(value) {
      rejectAuthentication = Boolean(value);
      record({ kind: 'fixture-auth-mode', rejectAuthentication });
      // Drop only this fixture's old tunnels so pooled success cannot hide failure.
      for (const socket of sockets) socket.destroy();
    },
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all([new Promise(resolve => proxy.close(resolve)), new Promise(resolve => origin.close(resolve))]);
    },
  };
}

export class ExactReader {
  constructor(socket) {
    this.socket = socket; this.buffer = Buffer.alloc(0); this.waiter = null; this.failed = false;
    this.onData = chunk => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      if (this.buffer.length > 65536) { this.onEnd(); socket.destroy(); return; }
      this.pump();
    };
    this.onEnd = () => {
      this.failed = true;
      if (this.waiter) { this.waiter.reject(Error('Socket ended')); this.waiter = null; }
    };
    socket.on('data', this.onData); socket.on('end', this.onEnd);
    socket.on('error', this.onEnd); socket.on('close', this.onEnd);
  }
  read(size) {
    if (!Number.isInteger(size) || size < 0 || size > 16384 || this.waiter) return Promise.reject(Error('Invalid read'));
    return new Promise((resolve, reject) => {
      this.waiter = { size, resolve, reject }; this.pump();
    });
  }
  pump() {
    const item = this.waiter;
    if (!item) return;
    if (this.buffer.length >= item.size) {
      const value = Buffer.from(this.buffer.subarray(0, item.size));
      this.buffer = this.buffer.subarray(item.size); this.waiter = null; item.resolve(value);
    } else if (this.failed) this.onEnd();
  }
  release() { this.socket.pause(); this.dispose(); return this.buffer; }
  dispose() {
    this.socket.off('data', this.onData); this.socket.off('end', this.onEnd);
    this.socket.off('error', this.onEnd); this.socket.off('close', this.onEnd);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  const output = args[args.indexOf('--output') + 1];
  const selectedPort = Number(args[args.indexOf('--port') + 1]);
  if (!args.includes('--output') || !path.isAbsolute(output) || /^c:[\\/]/i.test(output)
      || !Number.isInteger(selectedPort) || selectedPort < 1024 || selectedPort > 65535) {
    throw Error('Use --output on an authorized non-C drive and --port 1024..65535');
  }
  const destination = path.resolve(output);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const fixture = await startSocks5Fixture({ port: selectedPort, onObservation(observations) {
    fs.writeFileSync(destination + '.observations.json', JSON.stringify({ observations }, null, 2) + '\n');
  } });
  fs.writeFileSync(destination, JSON.stringify({ kind: 'owned-local-socks5-fixture',
    processId: process.pid,
    proxy: `127.0.0.1:${fixture.port}`, protocol: 'SOCKS5', credentials: fixture.credentials,
    url: `http://${QA_SOCKS_ORIGIN}/qa/phone-socks5`, localTestOnly: true }, null, 2) + '\n');
  console.log(JSON.stringify({ ready: true, port: fixture.port, privateConfig: destination, localTestOnly: true }));
  process.stdin.setEncoding('utf8');
  let command = '';
  process.stdin.on('data', part => {
    command += part;
    let newline;
    while ((newline = command.indexOf('\n')) >= 0) {
      const line = command.slice(0, newline).trim(); command = command.slice(newline + 1);
      if (line === 'reject') fixture.setRejectAuthentication(true);
      if (line === 'allow') fixture.setRejectAuthentication(false);
    }
    if (command.length > 1024) command = '';
  });
  let closing = false;
  const stop = async () => { if (closing) return; closing = true; await fixture.close(); process.exit(0); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
