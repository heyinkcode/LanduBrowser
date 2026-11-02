importScripts('values.js', 'fonts.js', 'canvas.js', 'identity.js');
// This registration belongs only to the loopback development probe.
self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));
self.addEventListener('message', event => {
  if (event.data?.type === 'landu-font-probe' && event.ports[0]) {
    event.waitUntil((async () => {
      try { event.ports[0].postMessage({ nonce: event.data.nonce, values: await landuReadFonts() }); }
      catch (error) { event.ports[0].postMessage({ nonce: event.data.nonce, error: String(error) }); }
    })());
  }
  if (event.data?.type === 'landu-identity-probe' && event.ports[0]) {
    event.waitUntil((async () => {
      try { event.ports[0].postMessage({ nonce: event.data.nonce, values: await landuReadBrowserIdentity() }); }
      catch (error) { event.ports[0].postMessage({ nonce: event.data.nonce, error: String(error) }); }
    })());
  }
  if (event.data?.type === 'landu-dnt-probe' && event.ports[0]) {
    event.waitUntil((async () => {
      try { event.ports[0].postMessage({ nonce: event.data.nonce, values: await landuReadDnt() }); }
      catch (error) { event.ports[0].postMessage({ nonce: event.data.nonce, error: String(error) }); }
    })());
  }
  if (event.data?.type === 'landu-canvas-probe' && event.ports[0]) {
    event.waitUntil((async () => {
      try { event.ports[0].postMessage({ nonce: event.data.nonce, values: await landuReadCanvas() }); }
      catch (error) { event.ports[0].postMessage({ nonce: event.data.nonce, error: String(error) }); }
    })());
  }
  if (event.data?.type === 'landu-probe' && event.ports[0]) {
    event.ports[0].postMessage({ nonce: event.data.nonce, values: landuReadHardware() });
  }
});
