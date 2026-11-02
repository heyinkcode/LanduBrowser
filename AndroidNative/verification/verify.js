const $ = id => document.getElementById(id);
const report = { format: 1, startedAt: new Date().toISOString(), url: location.href, hardware: null, storage: [] };
const timeoutMs = 12000;
let busy = false;

function show() {
  $('result').textContent = JSON.stringify(report, null, 2);
  $('export').disabled = false;
}

async function action(button, run) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  $('status').className = '';
  $('status').textContent = '正在检查…';
  delete report.error;
  try {
    const passed = await run();
    $('status').textContent = passed === null ? '已记录结果；证据不足或尚需对照验证，请查看报告'
      : passed ? '本轮所列检查通过' : '本轮有失败项，请查看报告';
    $('status').className = passed === null ? '' : passed ? 'ok' : 'fail';
  } catch (error) {
    $('status').textContent = error.message;
    $('status').className = 'fail';
    report.error = String(error);
  } finally {
    busy = false;
    button.disabled = false;
    show();
  }
}

function waitResponse(subscribe, dispose) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(null, new Error('执行环境响应超时')), timeoutMs);
    let unsubscribe;
    let settled = false;
    function finish(value, error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      unsubscribe?.();
      dispose?.();
      error ? reject(error) : resolve(value);
    }
    try {
      unsubscribe = subscribe(value => finish(value), error => finish(null, error));
      if (settled) unsubscribe?.();
    }
    catch (error) { finish(null, error); }
  });
}

function bounded(promise, label) {
  return waitResponse((done, fail) => {
    promise.then(done, fail);
    return () => {};
  }).catch(error => { throw new Error(`${label}: ${error.message}`); });
}

async function frameValues(nonce, type = 'landu-probe') {
  const frame = document.createElement('iframe');
  frame.src = 'frame.html';
  return waitResponse((done, fail) => {
    const listener = event => {
      if (event.source === frame.contentWindow && event.origin === location.origin && event.data?.nonce === nonce) {
        event.data.error ? fail(new Error(event.data.error)) : done(event.data.values);
      }
    };
    addEventListener('message', listener);
    frame.onload = () => frame.contentWindow.postMessage({ type, nonce }, location.origin);
    frame.onerror = () => fail(new Error('iframe 加载失败'));
    document.body.append(frame);
    return () => removeEventListener('message', listener);
  }, () => frame.remove());
}

async function workerValues(nonce, type = 'landu-probe') {
  const worker = new Worker('worker.js');
  return waitResponse((done, fail) => {
    worker.onmessage = event => {
      if (event.data?.nonce === nonce) event.data.error ? fail(new Error(event.data.error)) : done(event.data.values);
    };
    worker.onerror = () => fail(new Error('Dedicated Worker 启动失败'));
    worker.postMessage({ type, nonce });
    return () => {};
  }, () => worker.terminate());
}

async function navigationHeaders(nonce) {
  const frame = document.createElement('iframe');
  frame.src = 'navigation-check';
  return waitResponse((done, fail) => {
    const listener = event => {
      if (event.source === frame.contentWindow && event.origin === location.origin && event.data?.nonce === nonce) done(event.data.headers);
    };
    addEventListener('message', listener);
    frame.onload = () => frame.contentWindow.postMessage({ type: 'landu-probe', nonce }, location.origin);
    frame.onerror = () => fail(new Error('导航请求头检查加载失败'));
    document.body.append(frame);
    return () => removeEventListener('message', listener);
  }, () => frame.remove());
}

async function fetchProbeJson(route) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(route, { cache: 'no-store', signal: controller.signal });
    if (!response.ok) throw new Error(`请求头检查失败：HTTP ${response.status}`);
    return await response.json();
  } finally { clearTimeout(timer); controller.abort(); }
}

async function subresourceHeaders() { return fetchProbeJson('request-headers'); }

async function serviceWorkerValues(nonce, type = 'landu-probe') {
  if (!navigator.serviceWorker) throw new Error('Service Worker 不可用，请使用 localhost 安全上下文');
  const registration = await bounded(navigator.serviceWorker.register('service-worker.js?probe=2', {
    updateViaCache: 'none',
  }), 'Service Worker 注册');
  await bounded(registration.update(), 'Service Worker 检查更新');
  let worker = registration.installing || registration.waiting || registration.active;
  if (!worker || worker.state !== 'activated') {
    if (!worker) throw new Error('Service Worker 没有可用实例');
    await waitResponse((done, fail) => {
      const listener = () => {
        if (worker.state === 'activated') done(true);
        if (worker.state === 'redundant') fail(new Error('Service Worker 已失效'));
      };
      worker.addEventListener('statechange', listener);
      listener();
      return () => worker.removeEventListener('statechange', listener);
    });
  }
  const channel = new MessageChannel();
  return waitResponse((done, fail) => {
    channel.port1.onmessage = event => {
      if (event.data?.nonce === nonce) event.data.error ? fail(new Error(event.data.error)) : done(event.data.values);
    };
    worker.postMessage({ type, nonce }, [channel.port2]);
    return () => {};
  }, () => channel.port1.close());
}

$('run').onclick = () => action($('run'), async () => {
  const nonce = crypto.randomUUID();
  const expected = { hardwareConcurrency: $('cpu').value ? Number($('cpu').value) : null,
    deviceMemory: $('memory').value ? Number($('memory').value) : null };
  const expectedRegion = landuParseExpectedRegion($('languages').value, $('timezone').value);
  if (!$('cpu').checkValidity() || !$('memory').checkValidity()
      || Object.values(expected).some(value => value !== null && (!Number.isFinite(value) || value <= 0))) {
    throw new Error('请填写有效的预期核心数和内存，或留空核对一致性');
  }
  const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
  const results = await Promise.allSettled([Promise.resolve(landuReadHardware()), frameValues(nonce), workerValues(nonce), serviceWorkerValues(nonce)]);
  const contexts = Object.fromEntries(results.map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const checks = [];
  for (const [name, context] of Object.entries(contexts)) {
    for (const key of ['hardwareConcurrency', 'deviceMemory']) {
      const value = context.values?.[key];
      checks.push({ context: name, property: key, actual: value ?? null,
        expected: expected[key] ?? contexts.main.values?.[key] ?? null,
        passed: typeof value === 'number' && value === (expected[key] ?? contexts.main.values?.[key]) });
    }
  }
  const headerResults = await Promise.allSettled([navigationHeaders(nonce), subresourceHeaders()]);
  const requestHeaders = Object.fromEntries(headerResults.map((result, i) => [i === 0 ? 'navigation' : 'subresource',
    result.status === 'fulfilled' ? { values: result.value } : { error: String(result.reason) }]));
  const expectedMemory = expected.deviceMemory ?? contexts.main.values?.deviceMemory;
  for (const [name, context] of Object.entries(requestHeaders)) {
    for (const key of ['sec-ch-device-memory', 'device-memory']) {
      const value = context.values?.[key];
      checks.push({ context: name, property: key, actual: value ?? null, expected: expectedMemory ?? null,
        passed: typeof value === 'string' && value.trim() !== '' && Number(value) === expectedMemory });
    }
  }
  checks.push(...landuRegionChecks(contexts, expectedRegion, requestHeaders));
  report.hardware = { time: new Date().toISOString(), expected, expectedRegion, contexts, requestHeaders, checks, passed: checks.every(check => check.passed) };
  return report.hardware.passed;
});

function updateGeolocationExpectedFields() {
  for (const id of ['geo-latitude', 'geo-longitude', 'geo-accuracy']) $(id).disabled = $('geo-mode').value !== 'custom';
}
$('geo-mode').onchange = updateGeolocationExpectedFields;
updateGeolocationExpectedFields();
$('geolocation').onclick = () => action($('geolocation'), async () => {
  const expected = landuParseExpectedGeolocation($('geo-mode').value, $('geo-latitude').value,
    $('geo-longitude').value, $('geo-accuracy').value);
  const evidence = await landuReadGeolocation(expected);
  const checks = landuGeolocationChecks(evidence, expected);
  const passed = checks.every(check => check.passed);
  report.geolocation = { schema: 1, expected, evidence, checks, passed: expected.mode === 'system' ? false : passed,
    outcome: !passed ? 'failed' : expected.mode === 'system' ? 'inconclusive' : 'passed',
    note: '仅覆盖本页面观察到的权限、坐标和持续更新；阻止结果不区分已有站点拒绝与环境策略，必须重启对照。' };
  return !passed ? false : expected.mode === 'system' ? null : true;
});

const updateIdentityExpectedFields = () => {
  const disabled = $('identity-mode').value === 'coherence';
  $('identity-version').disabled = disabled;
  $('identity-model').disabled = disabled;
};
$('identity-mode').onchange = updateIdentityExpectedFields;
updateIdentityExpectedFields();

$('identity').onclick = () => action($('identity'), async () => {
  const expected = landuParseExpectedAndroidIdentity($('identity-mode').value, $('identity-version').value, $('identity-model').value);
  const nonce = crypto.randomUUID();
  const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
  const kernel = await fetchProbeJson('expected-kernel');
  if (kernel.kind !== 'source-reference-not-installed-package') throw new Error('内核版本参考格式无效');
  const results = await Promise.allSettled([bounded(landuReadBrowserIdentity(), '主页面身份'), frameValues(nonce, 'landu-identity-probe'),
    workerValues(nonce, 'landu-identity-probe'), serviceWorkerValues(nonce, 'landu-identity-probe'), navigationHeaders(nonce)]);
  const contexts = Object.fromEntries(results.slice(0, 4).map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const navigation = results[4].status === 'fulfilled' ? { values: results[4].value } : { error: String(results[4].reason) };
  const checks = landuIdentityChecks(contexts, navigation.values, expected, kernel.version);
  report.identity = { schema: 1, time: new Date().toISOString(), expected, sourceKernelReference: kernel,
    contexts, navigation, checks, passed: checks.every(check => check.passed) };
  return report.identity.passed;
});

function updateGpuExpectedFields() {
  for (let i = 0; i < 6; i++) $('gpu-' + i).disabled = $('gpu-mode').value === 'coherence';
}
$('gpu-mode').onchange = updateGpuExpectedFields;
updateGpuExpectedFields();
$('gpu').onclick = () => action($('gpu'), async () => {
  const expected = landuParseExpectedGpu($('gpu-mode').value, Array.from({ length: 6 }, (_, i) => $('gpu-' + i).value));
  const nonce = crypto.randomUUID(), names = ['main', 'iframe', 'dedicatedWorker'];
  const results = await Promise.allSettled([bounded(landuReadGpu(), '主页面 GPU'),
    frameValues(nonce, 'landu-gpu-probe'), workerValues(nonce, 'landu-gpu-probe')]);
  const contexts = Object.fromEntries(results.map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const checks = landuGpuChecks(contexts, expected);
  report.gpu = { schema: 1, time: new Date().toISOString(), expected, contexts, checks,
    passed: checks.every(check => check.passed), installedPackageVerified: false,
    note: '仅验证当前浏览器所列 GPU 标识；不认证安装包来源，不代表像素噪声、实际 GPU 或完整能力画像通过。' };
  return report.gpu.passed;
});

$('dnt').onclick = () => action($('dnt'), async () => {
  const mode = $('dnt-mode').value;
  const nonce = crypto.randomUUID();
  const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
  const results = await Promise.allSettled([landuReadDnt(), frameValues(nonce, 'landu-dnt-probe'),
    workerValues(nonce, 'landu-dnt-probe'), serviceWorkerValues(nonce, 'landu-dnt-probe'), navigationHeaders(nonce)]);
  const contexts = Object.fromEntries(results.slice(0, 4).map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const navigation = results[4].status === 'fulfilled' ? { values: results[4].value } : { error: String(results[4].reason) };
  const checks = landuDntChecks(contexts, navigation.values, mode);
  report.dnt = { schema: 1, time: new Date().toISOString(), mode, contexts, navigation, checks,
    passed: checks.every(check => check.passed) };
  return report.dnt.passed;
});

$('fonts').onclick = () => action($('fonts'), async () => {
  const mode = $('font-mode').value;
  let baseline;
  if (mode === 'isolated') {
    const file = $('font-baseline').files[0];
    if (!file || file.size > 1024 * 1024) throw new Error('请载入不超过 1MB 的字体对照 JSON 报告');
    baseline = JSON.parse(await file.text()).fonts;
    if (baseline?.schema !== 1 || baseline?.mode !== 'baseline') throw new Error('文件不是关闭字体隔离时的对照报告');
  }
  const nonce = crypto.randomUUID();
  const names = ['main', 'iframe', 'dedicatedWorker'];
  const values = await Promise.allSettled([
    bounded(landuReadFonts(), '主页面字体'), frameValues(nonce, 'landu-font-probe'), workerValues(nonce, 'landu-font-probe'),
  ]);
  const contexts = Object.fromEntries(values.map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const checks = baseline ? names.map(name => ({ context: name,
    ...landuCompareFonts(baseline.contexts?.[name]?.values, contexts[name]?.values, name !== 'dedicatedWorker'),
  })) : [];
  const outcome = values.some(result => result.status === 'rejected') || checks.some(check => check.outcome === 'failed')
    ? 'failed' : checks.length === 3 && checks.every(check => check.outcome === 'passed') ? 'passed' : 'inconclusive';
  report.fonts = { schema: 1, mode, time: new Date().toISOString(), contexts, checks, outcome,
    passed: outcome === 'passed', note: '只验证所列本地字体名称；不代表跨设备字形或全部字体指纹一致。' };
  return outcome === 'passed' ? true : outcome === 'failed' ? false : null;
});

$('canvas').onclick = () => action($('canvas'), async () => {
  const mode = $('canvas-mode').value;
  let baseline = null;
  if (mode === 'isolated') {
    const file = $('canvas-baseline').files[0];
    if (!file || file.size > 1024 * 1024) throw new Error('请载入不超过 1MB 的 Canvas 关闭对照 JSON 报告');
    baseline = JSON.parse(await file.text()).canvas;
    if (baseline?.schema !== 1 || baseline.mode !== 'baseline' || baseline.passed !== true)
      throw new Error('文件不是通过检查的 Canvas 关闭对照报告');
  }
  const nonce = crypto.randomUUID(), type = 'landu-canvas-probe';
  const names = ['main', 'iframe', 'dedicatedWorker', 'serviceWorker'];
  const results = await Promise.allSettled([bounded(landuReadCanvas(), '主页面 Canvas'),
    frameValues(nonce, type), workerValues(nonce, type), serviceWorkerValues(nonce, type)]);
  const contexts = Object.fromEntries(results.map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const checks = landuCanvasChecks(contexts, baseline);
  report.canvas = { schema: 1, mode, time: new Date().toISOString(), contexts, checks,
    passed: checks.every(check => check.passed),
    note: '覆盖已知 RGBA8 图案的 2D 读回、PNG 原始像素与所列执行环境；不代表 WebGL、HDR 或全部绘制行为已经验收。' };
  return report.canvas.passed;
});

$('webgl').onclick = () => action($('webgl'), async () => {
  const mode = $('webgl-mode').value;
  let baseline = null;
  if (mode === 'isolated') {
    const file = $('webgl-baseline').files[0];
    if (!file || file.size > 1024 * 1024) throw new Error('请载入不超过 1MB 的 WebGL 关闭对照 JSON 报告');
    baseline = JSON.parse(await file.text()).webgl;
    if (baseline?.schema !== 1 || baseline.mode !== 'baseline' || baseline.passed !== true)
      throw new Error('文件不是通过检查的 WebGL 关闭对照报告');
  }
  const nonce = crypto.randomUUID(), type = 'landu-webgl-probe', names = ['main', 'iframe', 'dedicatedWorker'];
  const results = await Promise.allSettled([bounded(landuReadWebgl(), '主页面 WebGL'), frameValues(nonce, type), workerValues(nonce, type)]);
  const contexts = Object.fromEntries(results.map((result, i) => [names[i], result.status === 'fulfilled'
    ? { values: result.value } : { error: String(result.reason) }]));
  const checks = landuWebglChecks(contexts, baseline);
  report.webgl = { schema: 1, mode, time: new Date().toISOString(), contexts, checks, passed: checks.every(check => check.passed),
    note: '仅覆盖报告所列 RGBA8 路径。API 不可用或超时不算通过；此页面不能证明所有像素格式、WebGLOnWebGPU 或 WebGPU 像素隔离。' };
  return report.webgl.passed;
});

function databaseOwner(next) {
  return new Promise((resolve, reject) => {
    let db;
    let transaction;
    let settled = false;
    const timer = setTimeout(() => finish(null, new Error('IndexedDB 操作超时')), timeoutMs);
    function finish(value, error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) { try { transaction?.abort(); } catch {} }
      db?.close();
      error ? reject(error) : resolve(value);
    }
    const open = indexedDB.open('landu-native-isolation-probe', 1);
    open.onupgradeneeded = () => {
      if (settled) { open.transaction.abort(); return; }
      open.result.createObjectStore('state');
    };
    open.onerror = () => finish(null, open.error);
    open.onblocked = () => finish(null, new Error('IndexedDB 打开被阻止'));
    open.onsuccess = () => {
      db = open.result;
      if (settled) { db.close(); return; }
      db.onversionchange = () => finish(null, new Error('IndexedDB 版本已改变'));
      try {
        transaction = db.transaction('state', next === undefined ? 'readonly' : 'readwrite');
        const request = next === undefined ? transaction.objectStore('state').get('owner') : transaction.objectStore('state').put(next, 'owner');
        transaction.oncomplete = () => finish(next === undefined ? request.result ?? null : next);
        transaction.onerror = transaction.onabort = () => finish(null, transaction.error || new Error('IndexedDB 操作失败'));
      } catch (error) { finish(null, error); }
    };
  });
}

function cookieOwner() {
  const cookie = document.cookie.split(';').map(value => value.trim()).find(value => value.startsWith('landu_probe_owner='));
  return cookie ? decodeURIComponent(cookie.slice('landu_probe_owner='.length)) : null;
}

$('storage').onclick = () => action($('storage'), async () => {
  const owner = $('owner').value;
  if (!/^[A-Za-z0-9_-]{1,32}$/.test(owner)) throw new Error('环境标记只使用 1–32 位英文字母、数字、下划线或短横线');
  const expected = $('expect').value === 'empty' ? null : owner;
  const cache = await bounded(caches.open('landu-native-isolation-probe'), '打开 Cache Storage');
  const cacheKey = new URL('probe-owner', location.href).href;
  const read = async () => {
    const response = await bounded(cache.match(cacheKey), '读取 Cache Storage');
    return { cookie: cookieOwner(), localStorage: localStorage.getItem('landu_probe_owner'),
      indexedDB: await databaseOwner(), cacheStorage: response ? await bounded(response.text(), '读取缓存内容') : null };
  };
  const before = await read();
  const passedBefore = Object.values(before).every(value => value === expected);
  const evidence = { time: new Date().toISOString(), owner, expected, before, passed: false, wrote: false };
  report.storage.push(evidence);
  if (!passedBefore) return false;
  document.cookie = `landu_probe_owner=${owner}; Path=/; SameSite=Strict; Max-Age=604800`;
  localStorage.setItem('landu_probe_owner', owner);
  await databaseOwner(owner);
  await bounded(cache.put(cacheKey, new Response(owner, { headers: { 'Content-Type': 'text/plain' } })), '写入 Cache Storage');
  evidence.wrote = true;
  evidence.after = await read();
  evidence.passed = Object.values(evidence.after).every(value => value === owner);
  return evidence.passed;
});

$('export').onclick = () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = `landu-native-check-${Date.now()}.json`;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};
