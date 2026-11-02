import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const code = fs.readFileSync(new URL('../verification/gpu.js', import.meta.url), 'utf8');
const raw = ['Google Inc. (Qualcomm)', 'ANGLE (Qualcomm, Adreno 740, OpenGL ES 3.2)', 'qualcomm', '', '', ''];
const load = extra => {
  const context = vm.createContext({ setTimeout, clearTimeout, navigator: {}, ...extra });
  vm.runInContext(code, context);
  return context;
};
const api = load();
const expected = api.landuParseExpectedGpu('custom', raw);
const info = () => ({ vendor: raw[2], architecture: '', device: '', description: '', developer: {} });
const gl = () => ({ available: true, vendor: raw[0], renderer: raw[1], repeatedVendor: raw[0], repeatedRenderer: raw[1], beforeExtensionGated: true, noError: true });
const contexts = () => Object.fromEntries(['main', 'iframe', 'dedicatedWorker'].map(name => [name,
  { values: { webgl: gl(), webgl2: gl(), webgpu: { available: true, adapter: info(), device: info() } } }]));
const passes = (values, want = expected) => api.landuGpuChecks(values, want).every(check => check.passed);

test('expected GPU values normalize but reject missing, non-ASCII, control and oversized input', () => {
  assert.equal(api.landuParseExpectedGpu('coherence', null), null);
  assert.equal(api.landuParseExpectedGpu('custom', [' vendor ', ' renderer ', ' vendor ', '', '', '']).webGlVendor, 'vendor');
  assert.throws(() => api.landuParseExpectedGpu('bad', raw));
  assert.throws(() => api.landuParseExpectedGpu('custom', []));
  for (let i = 0; i < 6; i++) {
    for (const bad of [null, 'x\n', 'x\t', 'x\0', 'x\x7f', '显卡', 'x'.repeat([128, 512, 64, 128, 64, 512][i] + 1)]) {
      const values = [...raw]; values[i] = bad;
      assert.throws(() => api.landuParseExpectedGpu('custom', values));
    }
    if (i < 3) { const values = [...raw]; values[i] = ''; assert.throws(() => api.landuParseExpectedGpu('custom', values)); }
  }
});
test('matching three scopes pass both custom and coherence checks', () => {
  assert.equal(passes(contexts()), true);
  assert.equal(passes(contexts(), null), true);
});
test('missing scope or unavailable APIs never count as isolation passing', () => {
  assert.equal(passes({}), false);
  for (const scope of ['main', 'iframe', 'dedicatedWorker']) {
    const missing = contexts(); delete missing[scope]; assert.equal(passes(missing), false);
    for (const kind of ['webgl', 'webgl2', 'webgpu']) {
      const values = contexts(); values[scope].values[kind].available = false;
      assert.equal(passes(values), false);
    }
  }
});
test('native extension gating and repeated WebGL identity are required in both versions', () => {
  for (const scope of ['main', 'iframe', 'dedicatedWorker']) {
    for (const kind of ['webgl', 'webgl2']) {
      for (const [field, bad] of [['beforeExtensionGated', false], ['noError', false], ['vendor', 'real GPU'],
        ['renderer', 'real GPU'], ['repeatedVendor', 'changed'], ['repeatedRenderer', 'changed']]) {
        const values = contexts(); values[scope].values[kind][field] = bad; assert.equal(passes(values), false);
      }
    }
  }
});
test('GPUDevice.adapterInfo is checked independently, including explicit empty optional values', () => {
  for (const scope of ['main', 'iframe', 'dedicatedWorker']) {
    for (const kind of ['adapter', 'device']) {
      for (const key of ['vendor', 'architecture', 'device', 'description']) {
        const values = contexts(); values[scope].values.webgpu[kind][key] = 'leaked hardware';
        assert.equal(passes(values), false);
        const missing = contexts(); delete missing[scope].values.webgpu[kind][key];
        assert.equal(passes(missing), false);
      }
    }
  }
});
test('developer and experimental details cannot leak beside a custom identity', () => {
  for (const bad of [undefined, null, [], '']) {
    const values = contexts(); values.main.values.webgpu.device.developer = bad;
    assert.equal(passes(values), false);
  }
  for (const [key, good, bad] of [['driver', '', 'real driver'], ['backend', '', 'Vulkan'], ['type', '', 'integrated'],
    ['powerPreference', '', 'high-performance'], ['d3dShaderModel', null, 66], ['vkDriverVersion', null, 123],
    ['memoryHeapsCount', 0, 1], ['subgroupMatrixConfigsCount', 0, 2]]) {
    for (const kind of ['adapter', 'device']) {
      const values = contexts(); values.main.values.webgpu[kind].developer[key] = good;
      assert.equal(passes(values), true);
      values.main.values.webgpu[kind].developer[key] = bad; assert.equal(passes(values), false);
    }
  }
});
test('collector releases both GL contexts and the WebGPU device on successful measurement', async () => {
  let lost = 0, destroyed = 0;
  class Canvas {
    getContext() {
      let enabled = false, error = 0;
      return { INVALID_ENUM: 1280, NO_ERROR: 0,
        getParameter(key) { if (!enabled) { error = 1280; return null; } return key === 0x9245 ? raw[0] : raw[1]; },
        getError() { const value = error; error = 0; return value; },
        getExtension(name) { if (name === 'WEBGL_lose_context') return { loseContext() { lost++; } };
          enabled = true; return { UNMASKED_VENDOR_WEBGL: 0x9245, UNMASKED_RENDERER_WEBGL: 0x9246 }; } };
    }
  }
  const context = load({ OffscreenCanvas: Canvas, navigator: { gpu: { async requestAdapter() {
    return { info: info(), async requestDevice() { return { adapterInfo: info(), destroy() { destroyed++; } }; } };
  } } } });
  const measured = await context.landuReadGpu();
  assert.equal(measured.webgl.beforeExtensionGated, true); assert.equal(measured.webgl2.beforeExtensionGated, true);
  assert.equal(measured.webgpu.available, true); assert.equal(lost, 2); assert.equal(destroyed, 1);
});
test('collector reports unsupported APIs and rejected device creation as unavailable', async () => {
  assert.equal((await load().landuReadGpu()).webgpu.available, false);
  const context = load({ navigator: { gpu: { async requestAdapter() {
    return { info: info(), async requestDevice() { throw new Error('device denied'); } };
  } } } });
  const measured = await context.landuReadGpu();
  assert.equal(measured.webgpu.available, false); assert.match(measured.webgpu.error, /denied/);
});
test('timed-out GPU device creation still destroys a device that arrives late', async () => {
  const timers = new Set(); let completeDevice, destroyed = 0;
  const context = load({ setTimeout(fn) { timers.add(fn); return fn; }, clearTimeout(fn) { timers.delete(fn); },
    navigator: { gpu: { async requestAdapter() { return { info: info(), requestDevice() {
      return new Promise(resolve => { completeDevice = resolve; });
    } }; } } } });
  const measurement = context.landuReadGpu();
  await new Promise(setImmediate);
  assert.equal(typeof completeDevice, 'function'); assert.equal(timers.size, 1);
  for (const timer of timers) { timers.delete(timer); timer(); }
  const measured = await measurement; assert.equal(measured.webgpu.available, false);
  completeDevice({ adapterInfo: info(), destroy() { destroyed++; } });
  await new Promise(setImmediate);
  assert.equal(destroyed, 1); assert.equal(timers.size, 0);
});
