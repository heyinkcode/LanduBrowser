import assert from 'node:assert/strict';
import test from 'node:test';
import { assessProxyObservations } from '../verification/verify-owned-proxy-browser.mjs';

const since = '2026-11-01T18:00:00Z';
const atUtc = '2026-11-01T18:00:01Z';
const path = '/qa/own';
const options = { label: 'A', casePath: path, since, mode: 'http' };

test('requires fresh matching proxy traffic and an actual origin request', () => {
  const events = [{ atUtc, path, kind: 'http-proxy', authorized: true, label: 'A' },
    { atUtc, path, kind: 'origin', proxyAuthorizationLeaked: false, originAuthorizationPresent: false }];
  assert.equal(assessProxyObservations(events, options).passed, true);
  assert.equal(assessProxyObservations(events, { ...options, label: 'B' }).passed, false);
  assert.equal(assessProxyObservations(events, { ...options, since: '2026-11-01T18:00:02Z' }).passed, false);
  assert.equal(assessProxyObservations(events.slice(0, 1), options).passed, false);
  assert.equal(assessProxyObservations([events[0], { atUtc, path, kind: 'origin' }], options).passed, false);
  assert.equal(assessProxyObservations(undefined, options).passed, false);
  assert.equal(assessProxyObservations([...events, { kind: 'origin' }], options).passed, false);
  assert.equal(assessProxyObservations([...events, { ...events[1], originAuthorizationPresent: true }], options).passed, false);
});

test('denial cannot pass from absence of evidence, a retry loop, or a successful request', () => {
  const denied = { ...options, mode: 'denied' };
  const challenge = { atUtc, path, kind: 'challenge', authorized: false };
  assert.equal(assessProxyObservations([], denied).passed, false);
  assert.equal(assessProxyObservations([challenge], denied).passed, true);
  assert.equal(assessProxyObservations(Array(4).fill(challenge), denied).passed, false);
  assert.equal(assessProxyObservations([challenge, { atUtc, path, kind: 'origin' }], denied).passed, false);
});
