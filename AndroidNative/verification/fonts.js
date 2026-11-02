// Shared by the page, iframe and worker. The same probes must be run before and
// after enabling isolation; an absent font alone is never proof of filtering.
globalThis.landuFontNames = [
  'Microsoft YaHei', 'Microsoft JhengHei', 'SimSun', 'SimHei', 'PingFang SC',
  'Noto Sans CJK SC', 'Noto Serif CJK SC', 'Noto Sans SC', 'Source Han Sans SC',
  'MiSans', 'MiSansVF', 'MI LanTing', 'HarmonyOS Sans SC', 'OPPO Sans',
  'vivo Sans', 'HONOR Sans',
];
globalThis.landuReadFonts = async () => {
  const sample = 'mmmmWWWW0123456789中文简繁體岚渡浏览器';
  const fallbacks = ['monospace', 'serif', 'sans-serif'];
  const canvas = typeof document !== 'undefined' ? document.createElement('canvas') : new OffscreenCanvas(600, 80);
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Canvas 2D 不可用');
  function widths(family) {
    return fallbacks.map(fallback => {
      context.font = `32px ${family ? '"' + family + '", ' : ''}${fallback}`;
      return context.measureText(sample).width;
    });
  }
  function domWidths(family) {
    if (typeof document === 'undefined') return null;
    const span = document.createElement('span');
    span.textContent = sample;
    Object.assign(span.style, { position: 'absolute', visibility: 'hidden', whiteSpace: 'nowrap', fontSize: '32px' });
    document.body.append(span);
    try {
      return fallbacks.map(fallback => {
        span.style.fontFamily = family ? `"${family}", ${fallback}` : fallback;
        return span.getBoundingClientRect().width;
      });
    } finally { span.remove(); }
  }
  async function localFace(family) {
    if (typeof FontFace !== 'function') return 'unsupported';
    let timer;
    try {
      const face = new FontFace('LanduProbe', `local("${family}")`);
      return await Promise.race([
        face.load().then(() => 'available', () => 'unavailable'),
        new Promise(resolve => { timer = setTimeout(() => resolve('timeout'), 4000); }),
      ]);
    } finally { clearTimeout(timer); }
  }
  const base = { canvas: widths(null), dom: domWidths(null) };
  const fonts = await Promise.all(landuFontNames.map(async name => ({
    name, canvas: widths(name), dom: domWidths(name), local: await localFace(name),
  })));
  return { base, fonts, userAgent: navigator.userAgent };
};

globalThis.landuCompareFonts = (baseline, current, needsDom) => {
  const valid = values => Array.isArray(values) && values.length === 3
    && values.every(value => Number.isFinite(value) && value > 0);
  const differs = (values, base) => values.some((value, i) => Math.abs(value - base[i]) > 0.01);
  if (!baseline || !current || baseline.userAgent !== current.userAgent
      || !valid(baseline.base?.canvas) || !valid(current.base?.canvas)
      || (needsDom && (!valid(baseline.base?.dom) || !valid(current.base?.dom)))) {
    return { outcome: 'inconclusive', reason: '对照数据缺失、浏览器标识不同或测量不可用', checks: [] };
  }
  const checks = landuFontNames.map(name => {
    const before = baseline.fonts?.find(font => font.name === name);
    const after = current.fonts?.find(font => font.name === name);
    if (!before || !after || !valid(before.canvas) || !valid(after.canvas)
        || !['available', 'unavailable'].includes(before.local)
        || !['available', 'unavailable'].includes(after.local)
        || (needsDom && (!valid(before.dom) || !valid(after.dom)))) {
      return { name, outcome: 'inconclusive', reason: '字体 API 缺失、超时或测量数据不完整' };
    }
    const visibleBefore = before.local === 'available' || differs(before.canvas, baseline.base.canvas)
      || (needsDom && differs(before.dom, baseline.base.dom));
    const visibleAfter = after.local === 'available' || differs(after.canvas, current.base.canvas)
      || (needsDom && differs(after.dom, current.base.dom));
    return { name, visibleBefore, visibleAfter,
      outcome: visibleAfter ? 'failed' : visibleBefore ? 'passed' : 'not-observed-before' };
  });
  return { checks, outcome: checks.some(check => check.outcome === 'failed') ? 'failed'
    : checks.some(check => check.outcome === 'inconclusive') || !checks.some(check => check.outcome === 'passed')
      ? 'inconclusive' : 'passed' };
};
