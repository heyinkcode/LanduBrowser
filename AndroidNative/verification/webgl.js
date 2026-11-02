// Local device probe. Exported PNG bytes are decoded without repainting them.
(() => {
  const width = 17, height = 9;
  const digest = async bytes => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)),
    value => value.toString(16).padStart(2, '0')).join('');
  const equal = (a, b) => a.length === b.length && a.every((value, i) => value === b[i]);
  const topDown = bytes => {
    const flipped = new Uint8Array(bytes.length);
    for (let y = 0; y < height; y++) flipped.set(bytes.subarray(y * width * 4, (y + 1) * width * 4), (height - y - 1) * width * 4);
    return flipped;
  };
  const source = new Uint8Array(width * height * 4);
  for (let y = 1; y < height; y++) for (let x = 0; x < width; x++) {
    source.set([x % 5 ? (x * 19 + y * 13) % 255 + 1 : 0,
      (x * 7 + y * 23) % 255 + 1, (x * 29 + y * 3) % 255 + 1, 255], (y * width + x) * 4);
  }
  const errors = gl => {
    const values = [];
    for (let i = 0; i < 32; i++) { const error = gl.getError(); if (!error) return values; values.push(error); }
    throw new Error('WebGL 错误队列未能清空');
  };
  const bounded = promise => new Promise((resolve, reject) => {
    // Four variants run sequentially; leave room inside the outer 12s scope
    // timeout, so a failed export cannot leave a second probe running behind it.
    const timer = setTimeout(() => reject(new Error('PNG 导出超时')), 1500);
    Promise.resolve(promise).then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
  const decode = async blob => {
    if (!blob) throw new Error('PNG 导出为空');
    const png = await landuReadPngPixels(new Uint8Array(await blob.arrayBuffer()));
    if (png.width !== width || png.height !== height) throw new Error('PNG 尺寸不一致');
    return digest(png.pixels);
  };
  async function readVariant(canvasKind, api) {
    let gl;
    const buffers = [], textures = [], framebuffers = [];
    try {
      const canvas = canvasKind === 'html' ? document.createElement('canvas') : new OffscreenCanvas(width, height);
      canvas.width = width; canvas.height = height;
      gl = canvas.getContext(api, { alpha: true, antialias: false, premultipliedAlpha: false, preserveDrawingBuffer: true });
      if (!gl) return { available: false, error: api + ' 不可用，不能验证' };
      const webgl2 = api === 'webgl2';
      const draw = () => {
        gl.disable(gl.DITHER); gl.disable(gl.BLEND); gl.enable(gl.SCISSOR_TEST);
        gl.colorMask(true, true, true, true);
        for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
          const i = (y * width + x) * 4;
          gl.scissor(x, y, 1, 1);
          gl.clearColor(source[i] / 255, source[i + 1] / 255, source[i + 2] / 255, source[i + 3] / 255);
          gl.clear(gl.COLOR_BUFFER_BIT);
        }
        gl.disable(gl.SCISSOR_TEST);
      };
      const read = (x = 0, y = 0, w = width, h = height) => {
        const bytes = new Uint8Array(w * h * 4);
        gl.readPixels(x, y, w, h, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
        return bytes;
      };
      draw();
      const first = read(), hash = await digest(first), flippedHash = await digest(topDown(first));
      let maxRgbDelta = 0, alphaPreserved = true, zeroPreserved = true, changedBytes = 0;
      for (let i = 0; i < source.length; i++) {
        if (i % 4 === 3) alphaPreserved &&= first[i] === source[i];
        else maxRgbDelta = Math.max(maxRgbDelta, Math.abs(first[i] - source[i]));
        if (source[i] === 0) zeroPreserved &&= first[i] === 0;
        if (first[i] !== source[i]) changedBytes++;
      }
      const cropped = read(3, 2, 7, 4), cropExpected = new Uint8Array(7 * 4 * 4);
      for (let row = 0; row < 4; row++) cropExpected.set(first.subarray(((row + 2) * width + 3) * 4, ((row + 2) * width + 10) * 4), row * 28);
      const clipped = read(-2, -1, width + 4, height + 2), clippedExpected = new Uint8Array(clipped.length);
      for (let row = 0; row < height; row++) clippedExpected.set(first.subarray(row * width * 4, (row + 1) * width * 4), ((row + 1) * (width + 4) + 2) * 4);
      const offsetBytes = new Uint8Array(first.length + 23).fill(173);
      if (webgl2) gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, offsetBytes, 7);
      else gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, offsetBytes.subarray(7, 7 + first.length));
      const arrayOffsetPreserved = equal(offsetBytes.subarray(7, 7 + first.length), first)
        && offsetBytes.subarray(0, 7).every(value => value === 173) && offsetBytes.subarray(7 + first.length).every(value => value === 173);
      const pack = (alignment, rowLength = 0, skipPixels = 0, skipRows = 0) => {
        gl.pixelStorei(gl.PACK_ALIGNMENT, alignment);
        if (webgl2) { gl.pixelStorei(gl.PACK_ROW_LENGTH, rowLength); gl.pixelStorei(gl.PACK_SKIP_PIXELS, skipPixels); gl.pixelStorei(gl.PACK_SKIP_ROWS, skipRows); }
      };
      let packedRowsPreserved = true, pboRowsPreserved = true, pboCopyPreserved = true;
      for (const alignment of [1, 2, 4, 8]) {
        const rowLength = webgl2 ? width + 5 : width, skipPixels = webgl2 ? 3 : 0, skipRows = webgl2 ? 2 : 0;
        const stride = Math.ceil(rowLength * 4 / alignment) * alignment;
        const skip = skipRows * stride + skipPixels * 4;
        const prefix = 7, length = prefix + skip + (height - 1) * stride + width * 4 + 13;
        const expected = new Uint8Array(length).fill(173);
        for (let row = 0; row < height; row++) expected.set(first.subarray(row * width * 4, (row + 1) * width * 4), prefix + skip + row * stride);
        pack(alignment, webgl2 ? rowLength : 0, skipPixels, skipRows);
        const output = new Uint8Array(length).fill(173);
        if (webgl2) gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, output, prefix);
        else gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, output.subarray(prefix));
        packedRowsPreserved &&= equal(output, expected);
        if (webgl2) {
          const buffer = gl.createBuffer(); buffers.push(buffer);
          gl.bindBuffer(gl.PIXEL_PACK_BUFFER, buffer); gl.bufferData(gl.PIXEL_PACK_BUFFER, new Uint8Array(length).fill(173), gl.STREAM_READ);
          gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, prefix);
          const packed = new Uint8Array(length); gl.getBufferSubData(gl.PIXEL_PACK_BUFFER, 0, packed);
          pboRowsPreserved &&= equal(packed, expected) && gl.getParameter(gl.PIXEL_PACK_BUFFER_BINDING) === buffer;
          const copy = gl.createBuffer(); buffers.push(copy);
          gl.bindBuffer(gl.COPY_READ_BUFFER, buffer); gl.bindBuffer(gl.COPY_WRITE_BUFFER, copy);
          gl.bufferData(gl.COPY_WRITE_BUFFER, length, gl.STREAM_READ);
          gl.copyBufferSubData(gl.COPY_READ_BUFFER, gl.COPY_WRITE_BUFFER, 0, 0, length);
          const copied = new Uint8Array(length); gl.getBufferSubData(gl.COPY_WRITE_BUFFER, 0, copied);
          pboCopyPreserved &&= equal(copied, expected);
          gl.bindBuffer(gl.COPY_READ_BUFFER, null); gl.bindBuffer(gl.COPY_WRITE_BUFFER, null); gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
        }
        packedRowsPreserved &&= gl.getParameter(gl.PACK_ALIGNMENT) === alignment;
        if (webgl2) packedRowsPreserved &&= gl.getParameter(gl.PACK_ROW_LENGTH) === rowLength
          && gl.getParameter(gl.PACK_SKIP_PIXELS) === skipPixels && gl.getParameter(gl.PACK_SKIP_ROWS) === skipRows;
      }
      pack(4);
      const validReadsNoError = errors(gl).length === 0;
      gl.enable(0xffffffff);
      const afterError = read(), preservedErrors = errors(gl);
      const earlierErrorPreserved = preservedErrors.length === 1 && preservedErrors[0] === gl.INVALID_ENUM && equal(afterError, first);
      const invalid = new Uint16Array(width * height * 4).fill(0x5a5a);
      gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_SHORT_5_6_5, invalid);
      const rejected = errors(gl);
      const invalidReadPreserved = rejected.length > 0 && rejected.every(error => error === gl.INVALID_OPERATION || error === gl.INVALID_ENUM)
        && invalid.every(value => value === 0x5a5a);
      let invalidPackPreserved = true, pboConflictPreserved = true;
      if (webgl2) {
        pack(4, width - 1);
        const untouched = new Uint8Array(first.length + 32).fill(173);
        gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, untouched);
        const packErrors = errors(gl);
        invalidPackPreserved = packErrors.length === 1 && packErrors[0] === gl.INVALID_OPERATION && untouched.every(value => value === 173);
        pack(4);
        const buffer = gl.createBuffer(); buffers.push(buffer);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, buffer);
        gl.bufferData(gl.PIXEL_PACK_BUFFER, new Uint8Array(first.length).fill(173), gl.STREAM_READ);
        gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, buffer);
        gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, 0);
        const conflictErrors = errors(gl);
        gl.bindBufferBase(gl.TRANSFORM_FEEDBACK_BUFFER, 0, null); gl.bindBuffer(gl.TRANSFORM_FEEDBACK_BUFFER, null);
        const untouchedPbo = new Uint8Array(first.length); gl.getBufferSubData(gl.PIXEL_PACK_BUFFER, 0, untouchedPbo);
        pboConflictPreserved = conflictErrors.length === 1 && conflictErrors[0] === gl.INVALID_OPERATION && untouchedPbo.every(value => value === 173);
        gl.bindBuffer(gl.PIXEL_PACK_BUFFER, null);
      }
      const exports = {};
      if (canvasKind === 'html') {
        const encoded = atob(canvas.toDataURL('image/png').split(',')[1] || '');
        exports.dataUrlPng = await decode(new Blob([Uint8Array.from(encoded, char => char.charCodeAt(0))], { type: 'image/png' }));
        exports.toBlobPng = await decode(await bounded(new Promise((resolve, reject) => {
          try { canvas.toBlob(resolve, 'image/png'); } catch (error) { reject(error); }
        })));
      } else exports.convertToBlobPng = await decode(await bounded(canvas.convertToBlob({ type: 'image/png' })));
      // A user framebuffer uses its own bottom-left coordinates. Test stable
      // crops separately; its seed coordinates need not equal the default FBO.
      const texture = gl.createTexture(); textures.push(texture);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      const framebuffer = gl.createFramebuffer(); framebuffers.push(framebuffer);
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('RGBA8 framebuffer 不完整');
      draw();
      const fbo = read(), fboCrop = read(3, 2, 7, 4), fboExpected = new Uint8Array(fboCrop.length);
      for (let row = 0; row < 4; row++) fboExpected.set(fbo.subarray(((row + 2) * width + 3) * 4, ((row + 2) * width + 10) * 4), row * 28);
      const framebufferStable = equal(fbo, read()) && equal(fboCrop, fboExpected);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      return { available: true, hash, flippedHash, sourceHash: await digest(source), changedBytes, maxRgbDelta, alphaPreserved, zeroPreserved,
        repeatStable: equal(first, read()), cropConsistent: equal(cropped, cropExpected), clippedConsistent: equal(clipped, clippedExpected),
        arrayOffsetPreserved, packedRowsPreserved, validReadsNoError, earlierErrorPreserved, invalidReadPreserved, invalidPackPreserved,
        pboRowsPreserved, pboCopyPreserved, pboConflictPreserved, framebufferStable, exports, noError: errors(gl).length === 0 };
    } catch (error) { return { available: false, error: String(error) }; }
    finally {
      if (gl) {
        for (const framebuffer of framebuffers) gl.deleteFramebuffer(framebuffer);
        for (const texture of textures) gl.deleteTexture(texture);
        for (const buffer of buffers) gl.deleteBuffer(buffer);
        try { gl.getExtension('WEBGL_lose_context')?.loseContext(); } catch {}
      }
    }
  }
  globalThis.landuReadWebgl = async () => {
    const variants = {};
    for (const kind of typeof document === 'object' ? ['html', 'offscreen'] : ['offscreen'])
      for (const api of ['webgl', 'webgl2']) variants[kind + '-' + api] = await readVariant(kind, api);
    return { schema: 1, width, height, userAgent: navigator.userAgent, variants };
  };
  globalThis.landuWebglChecks = (contexts, baseline = null) => {
    const checks = [], sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
    for (const context of ['main', 'iframe', 'dedicatedWorker']) {
      const value = contexts?.[context]?.values;
      for (const kind of context === 'dedicatedWorker' ? ['offscreen'] : ['html', 'offscreen']) for (const api of ['webgl', 'webgl2']) {
        const name = kind + '-' + api, actual = value?.variants?.[name];
        const add = (test, passed) => checks.push({ context, variant: name, test, passed: passed === true });
        const ready = value?.schema === 1 && value.width === width && value.height === height && typeof value.userAgent === 'string'
          && !!value.userAgent && actual?.available === true
          && sha(actual.hash) && sha(actual.flippedHash) && sha(actual.sourceHash);
        add('available', ready);
        for (const test of ['alphaPreserved', 'zeroPreserved', 'repeatStable', 'cropConsistent', 'clippedConsistent', 'arrayOffsetPreserved',
          'packedRowsPreserved', 'validReadsNoError', 'earlierErrorPreserved', 'invalidReadPreserved', 'framebufferStable', 'noError',
          ...(api === 'webgl2' ? ['invalidPackPreserved', 'pboRowsPreserved', 'pboCopyPreserved', 'pboConflictPreserved'] : [])]) add(test, ready && actual[test] === true);
        add('boundedRgbDelta', ready && Number.isInteger(actual.maxRgbDelta) && actual.maxRgbDelta >= 0 && actual.maxRgbDelta <= 1);
        for (const exportApi of kind === 'html' ? ['dataUrlPng', 'toBlobPng'] : ['convertToBlobPng'])
          add(exportApi, ready && actual.exports?.[exportApi] === actual.flippedHash);
        const reference = contexts?.main?.values?.variants?.['html-' + api];
        add('scopeCoherence', ready && sha(reference?.hash) && actual.hash === reference.hash && actual.sourceHash === reference.sourceHash);
        if (!baseline) add('knownBaseline', ready && actual.hash === actual.sourceHash && actual.changedBytes === 0 && actual.maxRgbDelta === 0);
        else {
          const beforeValue = baseline.contexts?.[context]?.values, before = beforeValue?.variants?.[name];
          const paired = baseline.schema === 1 && baseline.mode === 'baseline' && baseline.passed === true && beforeValue?.schema === 1
            && beforeValue.width === width && beforeValue.height === height && beforeValue.userAgent === value?.userAgent
            && before?.available === true && sha(before.hash) && before.hash === before.sourceHash && before.sourceHash === actual?.sourceHash
            && before.changedBytes === 0 && before.maxRgbDelta === 0;
          add('pairedBaseline', ready && paired);
          add('observedNoise', ready && paired && actual.hash !== before.hash && Number.isInteger(actual.changedBytes) && actual.changedBytes > 0 && actual.changedBytes <= width * height * 3);
        }
      }
    }
    return checks;
  };
})();
