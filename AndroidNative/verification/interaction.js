globalThis.landuPrepareInteraction = () => {
  document.getElementById('landu-interaction-fixture')?.remove();
  const root = document.createElement('div'); root.id = 'landu-interaction-fixture';
  root.style.cssText = 'position:fixed;left:12px;top:12px;width:300px;height:300px;z-index:2147483647;background:white;border:1px solid #adb5c4;touch-action:auto;';
  const state = { events: [], counts: {}, zoom: 1 };
  for (const [id, caption, x, y, width, height] of [['large', 'Landu large', 12, 12, 180, 48],
    ['small', 'OK', 220, 12, 32, 24], ['text', 'Landu link', 12, 96, 110, 32]]) {
    const button = document.createElement('button'); button.id = `landu-tap-${id}`; button.textContent = caption;
    button.style.cssText = `position:absolute;left:${x}px;top:${y}px;width:${width}px;height:${height}px;margin:0;padding:0;min-height:0;min-width:0;font:14px sans-serif;`;
    state.counts[id] = 0;
    for (const type of ['pointerdown', 'touchstart', 'click']) button.addEventListener(type, event => {
      state.events.push({ id, type, trusted: event.isTrusted, zoom: state.zoom });
      if (type === 'click') state.counts[id]++;
    });
    root.append(button);
  }
  document.body.append(root);
  globalThis.landuInteractionState = state;
};
globalThis.landuInteractionTarget = (id, zoom, position = 'center') => {
  const root = document.getElementById('landu-interaction-fixture');
  root.style.zoom = String(zoom); landuInteractionState.zoom = zoom;
  const button = document.getElementById(`landu-tap-${id}`), rect = button.getBoundingClientRect();
  const x = position === 'left' ? rect.left + 0.25 : position === 'right' ? rect.right - 0.25 : rect.x + rect.width / 2;
  const y = position === 'top' ? rect.top + 0.25 : position === 'bottom' ? rect.bottom - 0.25 : rect.y + rect.height / 2;
  return { id, zoom, position, before: landuInteractionState.counts[id], eventOffset: landuInteractionState.events.length,
    x, y, width: rect.width, height: rect.height,
    viewport: { width: visualViewport.width, height: visualViewport.height, scale: visualViewport.scale },
    dpr: devicePixelRatio };
};
