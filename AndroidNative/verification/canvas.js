// Read PNG bytes directly. Drawing an exported PNG back to Canvas would apply
// the readback noise again and could hide an unpatched export path.
globalThis.landuReadPngPixels = async bytes => {
  if (!(bytes instanceof Uint8Array) || bytes.length < 45 || bytes.length > 1024 * 1024)
    throw new Error('PNG 数据大小无效');
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  if (!signature.every((value, index) => bytes[index] === value)) throw new Error('PNG 文件头无效');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const idat = [];
  let width, height, channels, ended = false, offset = 8;
  while (offset < bytes.length) {
    if (offset + 12 > bytes.length) throw new Error('PNG 块不完整');
    const size = view.getUint32(offset);
    const end = offset + 8 + size;
    if (end + 4 > bytes.length) throw new Error('PNG 块越界');
    const type = String.fromCharCode(...bytes.subarray(offset + 4, offset + 8));
    let crc = 0xffffffff;
    for (let index = offset + 4; index < end; index++) {
      crc ^= bytes[index];
      for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
    if (((crc ^ 0xffffffff) >>> 0) !== view.getUint32(end)) throw new Error('PNG 校验失败');
    if (offset === 8 && type !== 'IHDR') throw new Error('PNG 缺少首部');
    if (type === 'IHDR') {
      if (width !== undefined || size !== 13) throw new Error('PNG 首部异常');
      width = view.getUint32(offset + 8); height = view.getUint32(offset + 12);
      const depth = bytes[offset + 16], color = bytes[offset + 17];
      if (!width || !height || width > 1024 || height > 1024 || depth !== 8 || ![2, 6].includes(color)
          || bytes[offset + 18] || bytes[offset + 19] || bytes[offset + 20])
        throw new Error('PNG 格式超出本验收工具支持范围');
      channels = color === 6 ? 4 : 3;
    } else if (type === 'IDAT') idat.push(bytes.subarray(offset + 8, end));
    else if (type === 'IEND') {
      if (size || end + 4 !== bytes.length) throw new Error('PNG 结束块异常');
      ended = true;
    } else if ((bytes[offset + 4] & 32) === 0) throw new Error('PNG 包含未知关键块');
    offset = end + 4;
  }
  if (!ended || !idat.length) throw new Error('PNG 缺少像素或结束标记');
  const stride = width * channels, expected = height * (stride + 1);
  const filtered = new Uint8Array(expected);
  const reader = new Blob(idat).stream().pipeThrough(new DecompressionStream('deflate')).getReader();
  let written = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      if (written + next.value.length > expected) { await reader.cancel(); throw new Error('PNG 解压长度异常'); }
      filtered.set(next.value, written); written += next.value.length;
    }
  } finally { reader.releaseLock(); }
  if (written !== expected) throw new Error('PNG 解压数据不完整');
  const raw = new Uint8Array(height * stride);
  for (let y = 0; y < height; y++) {
    const filter = filtered[y * (stride + 1)];
    if (filter > 4) throw new Error('PNG 行过滤类型无效');
    for (let x = 0; x < stride; x++) {
      const left = x >= channels ? raw[y * stride + x - channels] : 0;
      const above = y ? raw[(y - 1) * stride + x] : 0;
      const upperLeft = y && x >= channels ? raw[(y - 1) * stride + x - channels] : 0;
      const estimate = left + above - upperLeft;
      const a = Math.abs(estimate - left), b = Math.abs(estimate - above), c = Math.abs(estimate - upperLeft);
      const predictor = filter === 0 ? 0 : filter === 1 ? left : filter === 2 ? above
        : filter === 3 ? Math.floor((left + above) / 2) : a <= b && a <= c ? left : b <= c ? above : upperLeft;
      raw[y * stride + x] = (filtered[y * (stride + 1) + x + 1] + predictor) & 255;
    }
  }
  const pixels = new Uint8ClampedArray(width * height * 4);
  for (let i = 0; i < width * height; i++) pixels.set([
    raw[i * channels], raw[i * channels + 1], raw[i * channels + 2], channels === 4 ? raw[i * channels + 3] : 255,
  ], i * 4);
  return { width, height, pixels };
};

// A known opaque RGBA pattern avoids font/GPU differences in the first gate.
// Hashes and deltas leave this page only when the user exports a local report.
globalThis.landuReadCanvas = async () => {
  const width = 64, height = 32;
  const hash = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)))
    .map(value => value.toString(16).padStart(2, '0')).join('');
  const context = canvas => {
    const result = canvas.getContext('2d', { willReadFrequently: true, colorSpace: 'srgb' });
    if (!result) throw new Error('Canvas 2D 不可用');
    return result;
  };
  const make = kind => {
    if (kind === 'html') { const canvas = document.createElement('canvas'); canvas.width = width; canvas.height = height; return canvas; }
    if (typeof OffscreenCanvas !== 'function') throw new Error('OffscreenCanvas 不可用');
    return new OffscreenCanvas(width, height);
  };
  const source = new Uint8ClampedArray(width * height * 4);
  for (let y = 1; y < height; y++) for (let x = 0; x < width; x++) {
    const offset = (y * width + x) * 4;
    source.set([(x * 37 + y * 13) % 256, (x * 71 + y * 29) % 256, (x * 19 + y * 53) % 256, 255], offset);
  }
  const read = ctx => ctx.getImageData(0, 0, width, height).data;
  const toBlob = canvas => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('toBlob 响应超时')), 6000);
    try { canvas.toBlob(blob => { clearTimeout(timer); blob ? resolve(blob) : reject(new Error('toBlob 未返回图片')); }, 'image/png'); }
    catch (error) { clearTimeout(timer); reject(error); }
  });
  const decode = async blob => {
    const image = await landuReadPngPixels(new Uint8Array(await blob.arrayBuffer()));
    if (image.width !== width || image.height !== height) throw new Error('PNG 导出尺寸不一致');
    return hash(image.pixels);
  };
  const variants = {};
  for (const kind of typeof document === 'object' ? ['html', 'offscreen'] : ['offscreen']) {
    try {
      const canvas = make(kind), ctx = context(canvas);
      ctx.putImageData(new ImageData(source.slice(), width, height), 0, 0);
      const first = read(ctx), firstHash = await hash(first);
      let maxRgbDelta = 0, alphaPreserved = true, transparentPreserved = true;
      for (let i = 0; i < first.length; i++) {
        if (i % 4 === 3) alphaPreserved &&= first[i] === source[i];
        else maxRgbDelta = Math.max(maxRgbDelta, Math.abs(first[i] - source[i]));
        if (i < width * 4) transparentPreserved &&= first[i] === source[i];
      }
      const cropped = ctx.getImageData(11, 9, 13, 7).data;
      const expectedCrop = new Uint8ClampedArray(13 * 7 * 4);
      for (let row = 0; row < 7; row++) expectedCrop.set(first.subarray(((row + 9) * width + 11) * 4,
        ((row + 9) * width + 24) * 4), row * 13 * 4);
      const repeatStable = firstHash === await hash(read(ctx));
      const copy = context(make(kind));
      copy.putImageData(new ImageData(first.slice(), width, height), 0, 0);
      const exports = {};
      if (kind === 'html') {
        const dataUrl = canvas.toDataURL('image/png');
        const encoded = atob(dataUrl.split(',')[1] || '');
        exports.dataUrlPng = await decode(new Blob([Uint8Array.from(encoded, char => char.charCodeAt(0))], { type: 'image/png' }));
        exports.toBlobPng = await decode(await toBlob(canvas));
      } else exports.convertToBlobPng = await decode(await canvas.convertToBlob({ type: 'image/png' }));
      variants[kind] = { hash: firstHash, maxRgbDelta, alphaPreserved, transparentPreserved, repeatStable,
        cropConsistent: await hash(cropped) === await hash(expectedCrop),
        writeBackStable: firstHash === await hash(read(copy)),
        backingUnchangedAfterExport: firstHash === await hash(read(ctx)), exports };
    } catch (error) { variants[kind] = { error: String(error) }; }
  }
  return { schema: 1, userAgent: navigator.userAgent, width, height, sourceHash: await hash(source), variants };
};

globalThis.landuCanvasChecks = (contexts, baseline = null) => {
  const checks = [];
  for (const name of ['main', 'iframe', 'dedicatedWorker', 'serviceWorker']) {
    const value = contexts?.[name]?.values;
    const kinds = name === 'main' || name === 'iframe' ? ['html', 'offscreen'] : ['offscreen'];
    for (const kind of kinds) {
      const variant = value?.variants?.[kind];
      const ready = value?.schema === 1 && value.width === 64 && value.height === 32
        && /^[a-f0-9]{64}$/.test(value.sourceHash) && /^[a-f0-9]{64}$/.test(variant?.hash);
      const add = (property, passed) => checks.push({ context: name, canvas: kind, property, passed: passed === true });
      add('available', ready);
      for (const property of ['alphaPreserved', 'transparentPreserved', 'repeatStable', 'cropConsistent', 'writeBackStable', 'backingUnchangedAfterExport'])
        add(property, ready && variant[property] === true);
      add('boundedRgbDelta', ready && Number.isInteger(variant.maxRgbDelta) && variant.maxRgbDelta >= 0 && variant.maxRgbDelta <= 1);
      const exports = kind === 'html' ? ['dataUrlPng', 'toBlobPng'] : ['convertToBlobPng'];
      for (const api of exports) add(api, ready && variant.exports?.[api] === variant.hash);
      if (baseline) {
        const before = baseline.contexts?.[name]?.values;
        const original = before?.variants?.[kind];
        const comparable = ready && before?.schema === 1 && before.userAgent === value.userAgent
          && before.width === value.width && before.height === value.height && before.sourceHash === value.sourceHash
          && original?.hash === value.sourceHash && original.maxRgbDelta === 0;
        add('baselineComparable', comparable);
        add('enabledPixelsDiffer', comparable && variant.hash !== original.hash && variant.maxRgbDelta === 1);
      } else add('disabledPixelsUnchanged', ready && variant.hash === value.sourceHash && variant.maxRgbDelta === 0);
    }
  }
  return checks;
};
