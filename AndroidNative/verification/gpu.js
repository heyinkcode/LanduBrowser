(() => {
  const names = ['webGlVendor', 'webGlRenderer', 'webGpuVendor', 'webGpuArchitecture', 'webGpuDevice', 'webGpuDescription'];
  const limits = [128, 512, 64, 128, 64, 512];
  globalThis.landuParseExpectedGpu = (mode, values) => {
    if (mode === 'coherence') return null;
    if (mode !== 'custom' || !Array.isArray(values) || values.length !== 6
      || values.some((value, i) => typeof value !== 'string' || value.length > limits[i] || /[^\x20-\x7e]/.test(value))) {
      throw new Error('GPU 预期值须使用有效的可打印英文字符');
    }
    const fields = values.map(value => value.trim());
    if (fields.slice(0, 3).some(value => !value)) throw new Error('请填写 WebGL 厂商、渲染器和 WebGPU 厂商');
    return Object.fromEntries(names.map((name, i) => [name, fields[i]]));
  };
  const bounded = (promise, label, disposeLate) => new Promise((resolve, reject) => {
    let expired = false;
    const timer = setTimeout(() => { expired = true; reject(new Error(label + ' 超时')); }, 4000);
    Promise.resolve(promise).then(value => {
      clearTimeout(timer);
      if (expired) { try { disposeLate?.(value); } catch {} }
      else resolve(value);
    }, error => { clearTimeout(timer); if (!expired) reject(error); });
  });
  function readGl(kind) {
    let gl;
    try {
      const canvas = typeof document !== 'undefined' ? document.createElement('canvas') : new OffscreenCanvas(16, 16);
      canvas.width = canvas.height = 16;
      gl = canvas.getContext(kind);
      if (!gl) return { available: false, error: kind + ' 不可用，不能验证' };
      const beforeValue = gl.getParameter(0x9245), beforeError = gl.getError();
      const extension = gl.getExtension('WEBGL_debug_renderer_info');
      if (!extension) return { available: false, error: 'WEBGL_debug_renderer_info 不可用，不能验证' };
      const vendor = gl.getParameter(extension.UNMASKED_VENDOR_WEBGL);
      const renderer = gl.getParameter(extension.UNMASKED_RENDERER_WEBGL);
      const repeatedVendor = gl.getParameter(extension.UNMASKED_VENDOR_WEBGL);
      const repeatedRenderer = gl.getParameter(extension.UNMASKED_RENDERER_WEBGL);
      return { available: true, vendor, renderer, repeatedVendor, repeatedRenderer,
        beforeExtensionGated: beforeValue === null && beforeError === gl.INVALID_ENUM,
        noError: gl.getError() === gl.NO_ERROR };
    } catch (error) { return { available: false, error: String(error) }; }
    finally { try { gl?.getExtension('WEBGL_lose_context')?.loseContext(); } catch {} }
  }
  function adapterInfo(info) {
    if (!info) throw new Error('GPUAdapterInfo 不可用');
    const value = Object.fromEntries(['vendor', 'architecture', 'device', 'description'].map(name => [name, info[name]]));
    value.developer = {};
    for (const name of ['driver', 'backend', 'type', 'powerPreference', 'd3dShaderModel', 'vkDriverVersion']) {
      if (name in info) value.developer[name] = info[name];
    }
    for (const name of ['memoryHeaps', 'subgroupMatrixConfigs']) {
      if (name in info) value.developer[name + 'Count'] = info[name].length;
    }
    return value;
  }
  async function readWebGpu() {
    let device;
    try {
      if (!globalThis.navigator?.gpu) return { available: false, error: 'WebGPU 不可用，不能验证' };
      const adapter = await bounded(navigator.gpu.requestAdapter(), 'GPU 适配器');
      if (!adapter) return { available: false, error: 'WebGPU 没有可用适配器，不能验证' };
      const info = adapterInfo(adapter.info);
      device = await bounded(adapter.requestDevice(), 'GPU 设备', late => late?.destroy());
      return { available: true, adapter: info, device: adapterInfo(device.adapterInfo) };
    } catch (error) { return { available: false, error: String(error) }; }
    finally { device?.destroy(); }
  }
  globalThis.landuReadGpu = async () => ({ webgl: readGl('webgl'), webgl2: readGl('webgl2'), webgpu: await readWebGpu() });
  globalThis.landuGpuChecks = (contexts, expected) => {
    const checks = [];
    const add = (context, test, passed) => checks.push({ context, test, passed: passed === true });
    const reference = contexts?.main?.values;
    for (const name of ['main', 'iframe', 'dedicatedWorker']) {
      const actual = contexts?.[name]?.values;
      add(name, '收到执行环境结果', !!actual);
      for (const kind of ['webgl', 'webgl2']) {
        const value = actual?.[kind], base = reference?.[kind];
        add(name, kind + ' 可用且扩展权限未绕过', value?.available === true && value.beforeExtensionGated === true && value.noError === true);
        const vendor = expected ? expected.webGlVendor : base?.vendor;
        const renderer = expected ? expected.webGlRenderer : base?.renderer;
        add(name, kind + ' 标识匹配且重复读取稳定', !!value && typeof vendor === 'string' && !!vendor && typeof renderer === 'string' && !!renderer
          && value.vendor === vendor && value.renderer === renderer && value.repeatedVendor === vendor && value.repeatedRenderer === renderer);
      }
      add(name, 'WebGPU 适配器及设备可用', actual?.webgpu?.available === true);
      for (const kind of ['adapter', 'device']) {
        const info = actual?.webgpu?.[kind], base = reference?.webgpu?.adapter;
        for (const key of ['vendor', 'architecture', 'device', 'description']) {
          const expectedKey = 'webGpu' + key[0].toUpperCase() + key.slice(1);
          const value = expected ? expected[expectedKey] : base?.[key];
          add(name, 'WebGPU ' + kind + '.' + key, typeof value === 'string' && typeof info?.[key] === 'string' && info[key] === value);
        }
        if (expected) {
          const debug = info?.developer;
          add(name, 'WebGPU ' + kind + ' 不混入真实开发者标识', !!debug && typeof debug === 'object' && !Array.isArray(debug) && Object.entries(debug).every(([key, value]) =>
            ['driver', 'backend', 'type', 'powerPreference'].includes(key) ? value === ''
              : ['d3dShaderModel', 'vkDriverVersion'].includes(key) ? value === null
                : ['memoryHeapsCount', 'subgroupMatrixConfigsCount'].includes(key) && value === 0));
        }
      }
    }
    return checks;
  };
})();
