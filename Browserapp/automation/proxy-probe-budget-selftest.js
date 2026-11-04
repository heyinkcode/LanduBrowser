'use strict';
const assert = require('node:assert/strict');
const net = require('node:net');
const { performance } = require('node:perf_hooks');
const { runProxyProbes } = require('../engine/proxy-probe-runner');
const { parseProxy, lookupProxyCountry, clearProbeCache } = require('../proxy-forwarder');
const { BrowserEngine } = require('../engine');
(async () => {
  let cancelled = false;
  const started = performance.now();
  const fast = await runProxyProbes([
    () => Promise.resolve({ip:'192.0.2.1',countryCode:'US'}),
    () => { throw new Error('secondary probe should not run'); },
  ]);
  assert.equal(fast[0].countryCode,'US'); assert.ok(performance.now()-started < 200);
  const budgetStart = performance.now();
  await runProxyProbes([signal => new Promise(() => {signal.addEventListener('abort',()=>{cancelled=true;});})],{totalTimeoutMs:100,attemptTimeoutMs:50});
  assert.equal(cancelled,true); assert.ok(performance.now()-budgetStart < 300);
  const sockets = new Set(); const hosts = [];
  let responseDelay = 0;
  const server = net.createServer(socket => {
    sockets.add(socket); socket.on('close',()=>sockets.delete(socket));
    let input = '';
    socket.on('data',chunk => {
      input += chunk.toString('latin1'); if(!input.includes('\r\n\r\n'))return;
      const host = input.match(/Host: ([^\r\n]+)/i)?.[1]; hosts.push(host);
      const body = JSON.stringify({status:'success',query:'192.0.2.1',countryCode:'US',country:'United States',timezone:'America/New_York',lat:40,lon:-74});
      const respond = () => socket.end('HTTP/1.1 200 OK\r\nContent-Length: '+Buffer.byteLength(body)+'\r\nConnection: close\r\n\r\n'+body);
      if (responseDelay) setTimeout(respond, responseDelay);
      else respond();
    });
  });
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const config = parseProxy('http://127.0.0.1:'+server.address().port);
  const wireStart = performance.now();
  let fastElapsed;
  try {
    clearProbeCache();
    const result = await lookupProxyCountry(config,{force:true,totalTimeoutMs:800,attemptTimeoutMs:300});
    assert.equal(result.ip,'192.0.2.1'); assert.equal(result.countryCode,'US');
    assert.equal(hosts.length,1,'complete primary response skips optional providers and risk lookup');
    assert.ok(performance.now()-wireStart < 500);
    fastElapsed = Math.round(performance.now()-wireStart);

    // The editor invokes BrowserEngine.testProxy. Exercise its default budget
    // with an authenticated route whose response exceeds the old 2.2s cutoff.
    responseDelay = 2500;
    const engine = Object.create(BrowserEngine.prototype);
    engine.profiles = new Map();
    const slowStart = performance.now();
    const slow = await engine.testProxy({
      id: 'slow-proxy-probe',
      name: 'slow-proxy-probe',
      networkMode: 'proxy',
      proxy: 'http://fixture-user:fixture-pass@127.0.0.1:' + server.address().port,
    });
    assert.equal(slow.ip,'192.0.2.1');
    assert.equal(slow.countryCode,'US');
    assert.ok(performance.now()-slowStart >= responseDelay);
    assert.equal(hosts.length,2,'a delayed healthy primary response must not be cancelled');

    await assert.rejects(engine.testProxy({
      id: 'bounded-proxy-probe',
      name: 'bounded-proxy-probe',
      networkMode: 'proxy',
      proxy: 'http://127.0.0.1:' + server.address().port,
    }, {totalTimeoutMs:100, attemptTimeoutMs:50}), /超时|无响应/);
  } finally {for(const socket of sockets)socket.destroy();await new Promise(resolve=>server.close(resolve));}
  console.log('proxy-probe-budget-selftest: OK first-complete-ms='+fastElapsed+' total-budget=1 cancellation=1 secondary-skipped=1 real-local-proxy=1 slow-authenticated-proxy=1 engine-budget-override=1');
})().catch(error=>{console.error(error);process.exitCode=1;});
