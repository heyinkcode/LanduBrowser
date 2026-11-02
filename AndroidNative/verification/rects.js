globalThis.landuReadClientRects = async () => {
  await document.fonts.ready;
  const root = document.createElement('div');
  root.style.cssText = 'all:initial;position:absolute;left:20.25px;top:30.5px;width:240px;height:200px;z-index:-1;visibility:hidden;contain:layout style;';
  const scroller = document.createElement('div');
  scroller.style.cssText = 'width:220px;height:140px;overflow:scroll;position:relative;';
  const content = document.createElement('div');
  content.style.cssText = 'position:relative;width:400px;height:400px;';
  const box = document.createElement('div');
  box.style.cssText = 'position:absolute;left:12.25px;top:18.5px;width:120.75px;height:42.125px;';
  const text = document.createElement('span');
  text.style.cssText = 'position:absolute;left:18.5px;top:70.25px;width:100px;font:16px/20px monospace;white-space:pre;';
  text.textContent = 'Landu 012\nBox 345';
  const empty = document.createElement('div'); empty.style.display = 'none';
  content.append(box, text, empty); scroller.append(content); root.append(scroller); document.body.append(root);
  const fields = ['x', 'y', 'width', 'height', 'top', 'right', 'bottom', 'left'];
  const rect = item => Object.fromEntries(fields.map(field => [field, item[field]]));
  const snapshot = target => {
    const list = target.getClientRects();
    const bounds = target.getBoundingClientRect();
    return { list: Array.from(list, rect), bounds: rect(bounds),
      listType: Object.prototype.toString.call(list), rectType: Object.prototype.toString.call(bounds) };
  };
  const phases = [];
  try {
    const range = document.createRange(); range.selectNodeContents(text);
    const collapsed = document.createRange(); collapsed.setStart(text.firstChild, 2); collapsed.collapse(true);
    for (const phase of ['base', 'scroll', 'zoom']) {
      scroller.scrollLeft = phase === 'base' ? 0 : 7;
      scroller.scrollTop = phase === 'base' ? 0 : 21;
      root.style.zoom = phase === 'zoom' ? '1.25' : '1';
      await new Promise(resolve => setTimeout(resolve, 30));
      const targets = {};
      for (const [name, target] of [['element', box], ['range', range], ['collapsed', collapsed], ['hidden', empty]]) {
        const first = snapshot(target), second = snapshot(target);
        targets[name] = { ...first, repeatEqual: JSON.stringify(first) === JSON.stringify(second) };
      }
      phases.push({ phase, targets, layout: { offsetWidth: box.offsetWidth, offsetHeight: box.offsetHeight,
        offsetLeft: box.offsetLeft, offsetTop: box.offsetTop, scrollLeft: scroller.scrollLeft, scrollTop: scroller.scrollTop } });
    }
    return { schema: 1, phases,
      scope: 'Element/Range rectangles, collapsed and hidden shapes, scroll container and CSS zoom; not all geometry APIs or pinch zoom' };
  } finally { root.remove(); }
};
