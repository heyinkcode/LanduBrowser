import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { ExactReader, QA_SOCKS_ORIGIN, startSocks5Fixture } from '../verification/socks5-auth-fixture.mjs';

async function connect(fixture, t) {
  const socket = net.connect(fixture.port, '127.0.0.1');
  socket.setTimeout(2500, () => socket.destroy()); socket.on('error', () => {});
  t.after(() => socket.destroy());
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  return { socket, reader: new ExactReader(socket) };
}
async function authenticate(client, credentials) {
  client.socket.write(Buffer.from([5, 1, 2]));
  assert.deepEqual(await client.reader.read(2), Buffer.from([5, 2]));
  const username = Buffer.from(credentials.username), password = Buffer.from(credentials.password);
  // Separate the fields to exercise the streaming reader, not one message-sized read.
  client.socket.write(Buffer.from([1, username.length]));
  client.socket.write(username);
  client.socket.write(Buffer.from([password.length]));
  client.socket.write(password);
  return client.reader.read(2);
}
async function tunnel(client, hostname = QA_SOCKS_ORIGIN, port = 80) {
  const name = Buffer.from(hostname);
  client.socket.write(Buffer.concat([Buffer.from([5, 1, 0, 3, name.length]), name, Buffer.from([port >> 8, port & 255])]));
  return client.reader.read(10);
}
async function origin(client, endpoint) {
  client.socket.write(`GET ${endpoint} HTTP/1.1\r\nHost: ${QA_SOCKS_ORIGIN}\r\nConnection: close\r\n\r\n`);
  let header = '';
  while (!header.endsWith('\r\n\r\n') && header.length < 16384) header += (await client.reader.read(1)).toString('latin1');
  assert.match(header, /^HTTP\/1\.1 200/);
  const length = Number(/content-length:\s*(\d+)/i.exec(header)?.[1]);
  assert.ok(length > 0 && length < 4096);
  return JSON.parse((await client.reader.read(length)).toString('utf8'));
}

test('SOCKS5 requires RFC1929, carries real A/B requests and never places secrets in observations', { timeout: 7000 }, async t => {
  const fixture = await startSocks5Fixture();
  try {
    for (const label of ['A', 'B']) {
      const client = await connect(fixture, t);
      assert.deepEqual(await authenticate(client, fixture.credentials[label]), Buffer.from([1, 0]));
      assert.equal((await tunnel(client))[1], 0);
      assert.deepEqual(await origin(client, `/qa/owned-${label}`), {
        fixture: 'landu-authenticated-socks5', path: `/qa/owned-${label}`, proxyHeaderPresent: false,
      });
      client.socket.destroy();
    }
    assert.deepEqual(fixture.observations.filter(x => x.kind === 'tunnel').map(x => x.label), ['A', 'B']);
    for (const value of Object.values(fixture.credentials)) {
      assert.ok(!JSON.stringify(fixture.observations).includes(value.username));
      assert.ok(!JSON.stringify(fixture.observations).includes(value.password));
    }
    assert.ok(fixture.observations.filter(x => x.kind === 'origin').every(x => !x.proxyHeaderPresent && !x.originAuthorizationPresent));
  } finally { await fixture.close(); }
});

test('anonymous offers are rejected before authentication', { timeout: 5000 }, async t => {
  const fixture = await startSocks5Fixture();
  try {
    const client = await connect(fixture, t); client.socket.write(Buffer.from([5, 1, 0]));
    assert.deepEqual(await client.reader.read(2), Buffer.from([5, 255]));
    assert.equal(fixture.observations.filter(x => x.kind === 'tunnel' || x.kind === 'origin').length, 0);
  } finally { await fixture.close(); }
});

test('incorrect password cannot create a destination tunnel', { timeout: 5000 }, async t => {
  const fixture = await startSocks5Fixture();
  try {
    const client = await connect(fixture, t);
    assert.deepEqual(await authenticate(client, { ...fixture.credentials.A, password: 'owned-wrong-password' }), Buffer.from([1, 1]));
    assert.equal(fixture.observations.filter(x => x.kind === 'authentication-rejected').length, 1);
    assert.equal(fixture.observations.filter(x => x.kind === 'tunnel' || x.kind === 'origin').length, 0);
  } finally { await fixture.close(); }
});

test('authenticated clients cannot relay an unowned destination', { timeout: 5000 }, async t => {
  const fixture = await startSocks5Fixture();
  try {
    const client = await connect(fixture, t);
    assert.deepEqual(await authenticate(client, fixture.credentials.A), Buffer.from([1, 0]));
    assert.equal((await tunnel(client, 'unowned.invalid', 443))[1], 2);
    assert.equal(fixture.observations.filter(x => x.kind === 'tunnel' || x.kind === 'origin').length, 0);
  } finally { await fixture.close(); }
});

test('reject mode closes pooled tunnels and denies a fresh authenticated attempt', { timeout: 5000 }, async t => {
  const fixture = await startSocks5Fixture();
  try {
    const first = await connect(fixture, t);
    assert.deepEqual(await authenticate(first, fixture.credentials.A), Buffer.from([1, 0]));
    assert.equal((await tunnel(first))[1], 0);
    const closed = new Promise(resolve => first.socket.once('close', resolve));
    fixture.setRejectAuthentication(true); await closed;
    const next = await connect(fixture, t);
    assert.deepEqual(await authenticate(next, fixture.credentials.A), Buffer.from([1, 1]));
    assert.equal(fixture.observations.filter(x => x.kind === 'tunnel').length, 1);
    assert.equal(fixture.observations.filter(x => x.kind === 'origin').length, 0);
  } finally { await fixture.close(); }
});
