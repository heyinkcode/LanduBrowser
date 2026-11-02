addEventListener('message', event => {
  if (event.source !== parent || event.origin !== location.origin || event.data?.type !== 'landu-probe') return;
  parent.postMessage({ nonce: event.data.nonce, headers: JSON.parse(document.getElementById('headers').textContent) }, location.origin);
});
