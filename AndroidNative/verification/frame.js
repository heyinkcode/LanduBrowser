addEventListener('message', async event => {
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-media-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadMedia() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin
      && ['landu-audio-probe', 'landu-rects-probe'].includes(event.data?.type)) {
    try {
      const values = await (event.data.type === 'landu-audio-probe' ? landuReadAudio() : landuReadClientRects());
      parent.postMessage({ nonce: event.data.nonce, values }, location.origin);
    } catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-webgl-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadWebgl() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-gpu-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadGpu() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-identity-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadBrowserIdentity() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-dnt-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadDnt() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-canvas-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadCanvas() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-probe') {
    parent.postMessage({ nonce: event.data.nonce, values: landuReadHardware() }, location.origin);
  }
  if (event.source === parent && event.origin === location.origin && event.data?.type === 'landu-font-probe') {
    try { parent.postMessage({ nonce: event.data.nonce, values: await landuReadFonts() }, location.origin); }
    catch (error) { parent.postMessage({ nonce: event.data.nonce, error: String(error) }, location.origin); }
  }
});
