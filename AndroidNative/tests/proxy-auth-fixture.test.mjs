import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { startProxyFixture, QA_ORIGIN } from '../verification/proxy-auth-fixture.mjs';

const request = (fixture, url, authorization, extraHeaders = {}) => new Promise((resolve, reject) => {
  const headers = { ...extraHeaders, ...(authorization ? { 'proxy-authorization': authorization } : {}) };
  const req = http.get({ hostname: '127.0.0.1', port: fixture.port, path: url, headers }, res => {
    let body = ''; res.setEncoding('utf8'); res.on('data', part => { body += part; });
    res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body }));
  });
  req.on('error', reject);
});

test('origin 401 remains distinct, and origin Authorization is observable rather than silently stripped', async () => {
  const fixture = await startProxyFixture();
  try {
    const value = fixture.credentials.A;
    const proxyAuth = `Basic ${Buffer.from(value.username + ':' + value.password).toString('base64')}`;
    const url = `http://${QA_ORIGIN}/qa/origin-auth`;
    assert.equal((await request(fixture, url, proxyAuth)).status, 401);
    assert.equal((await request(fixture, url, proxyAuth,
      { authorization: 'Bearer owned-origin-test-value' })).status, 401);
    const origin = fixture.observations.filter(item => item.kind === 'origin');
    assert.deepEqual(origin.map(item => item.originAuthorizationPresent), [false, true]);
    assert.ok(origin.every(item => !item.proxyAuthorizationLeaked));
  } finally { await fixture.close(); }
});

test('real HTTP proxy challenges, isolates two credentials and strips secrets before the origin', async () => {
  const fixture = await startProxyFixture();
  try {
    const url = `http://${QA_ORIGIN}/qa/owned-request`;
    assert.equal((await request(fixture, url)).status, 407);
    assert.equal((await request(fixture, url, 'Basic invalid')).status, 407);
    for (const label of ['A', 'B']) {
      const value = fixture.credentials[label];
      const auth = `Basic ${Buffer.from(value.username + ':' + value.password).toString('base64')}`;
      const response = await request(fixture, url, auth);
      assert.equal(response.status, 200);
      assert.equal(JSON.parse(response.body).proxyAuthorizationLeaked, false);
      assert.equal((await request(fixture, 'http://unowned.test/qa/owned-request', auth)).status, 403);
    }
    assert.deepEqual(fixture.observations.filter(item => item.kind === 'http-proxy').map(item => item.label), ['A', 'B']);
    assert.equal(fixture.observations.filter(item => item.kind === 'origin').length, 2);
    for (const value of Object.values(fixture.credentials)) {
      assert.ok(!JSON.stringify(fixture.observations).includes(value.username));
      assert.ok(!JSON.stringify(fixture.observations).includes(value.password));
    }
  } finally { await fixture.close(); }
});
