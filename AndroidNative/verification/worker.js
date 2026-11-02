importScripts('values.js', 'fonts.js', 'canvas.js', 'identity.js', 'gpu.js', 'webgl.js');
self.onmessage = async event => {
  if (event.data?.type === 'landu-webgl-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadWebgl() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
  if (event.data?.type === 'landu-gpu-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadGpu() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
  if (event.data?.type === 'landu-identity-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadBrowserIdentity() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
  if (event.data?.type === 'landu-dnt-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadDnt() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
  if (event.data?.type === 'landu-probe') self.postMessage({ nonce: event.data.nonce, values: landuReadHardware() });
  if (event.data?.type === 'landu-font-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadFonts() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
  if (event.data?.type === 'landu-canvas-probe') {
    try { self.postMessage({ nonce: event.data.nonce, values: await landuReadCanvas() }); }
    catch (error) { self.postMessage({ nonce: event.data.nonce, error: String(error) }); }
  }
};
