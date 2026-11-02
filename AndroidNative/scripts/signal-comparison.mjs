const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const uints = (a, length) => Array.isArray(a) && a.length === length
  && a.every(v => Number.isInteger(v) && v >= 0 && v <= 0xffffffff);
const floatBound = (before, after) => uints(before, after?.length) && uints(after, before.length)
  && before.every((bits, i) => {
    const exponent = bits & 0x7f800000;
    return exponent === 0 || exponent === 0x7f800000 ? bits === after[i]
      : (bits & ~1) === (after[i] & ~1);
  });
const byteBound = (before, after) => Array.isArray(before) && before.length === after?.length
  && before.every((v, i) => Number.isInteger(v) && Number.isInteger(after[i])
    && v >= 0 && v <= 255 && after[i] >= 0 && after[i] <= 255 && Math.abs(v - after[i]) <= 1);

export function compareAudio(before, after, repeat) {
  const checks = [], coverageChecks = [];
  const add = (context, property, passed) => checks.push({ context, property, passed: passed === true });
  for (const context of ['main', 'iframe']) {
    const off = before?.[context]?.values, on = after?.[context]?.values, again = repeat?.[context]?.values;
    if (!off || !on || !again) { add(context, 'three complete context observations', false); continue; }
    for (const [mode, probe] of [['off', off], ['on', on], ['repeat', again]]) {
      add(context, `${mode}: authored buffer unchanged`, probe.authoredUnchanged);
      add(context, `${mode}: source input shape`, probe.input?.length === 2 && probe.input.every(a => uints(a, 2048)));
      for (const [round, render] of [['first', probe.first], ['second', probe.second]]) {
        add(context, `${mode}/${round}: buffer metadata`, render?.numberOfChannels === 2
          && render.length === 2048 && render.sampleRate === 44100 && render.channels?.length === 2);
        for (let channel = 0; channel < 2; channel++) {
          const value = render?.channels?.[channel];
          add(context, `${mode}/${round}/${channel}: coherent reads`, uints(value?.bits, 2048)
            && value?.repeatEqual && value.copyEqual && value.eventEqual);
        }
      }
      add(context, `${mode}: repeated rendering stable`, equal(probe.first, probe.second));
      for (const signal of ['silence', 'tone']) for (const name of ['FloatFrequency', 'ByteFrequency', 'FloatTimeDomain', 'ByteTimeDomain']) {
        const value = probe[signal]?.[name], count = name.endsWith('Frequency') ? 128 : 256;
        add(context, `${mode}/${signal}/${name}: lengths, repeat and tail`, value?.count === count
          && uints(value.values, count) && value.repeatEqual && value.tailPreserved);
      }
      const silent = probe.silence;
      add(context, `${mode}: native silence preserved`, silent?.FloatFrequency?.values?.every(v => v === 0xff800000)
        && silent?.FloatTimeDomain?.values?.every(v => v === 0)
        && silent?.ByteFrequency?.values?.every(v => v === 0)
        && silent?.ByteTimeDomain?.values?.every(v => v === 128));
      add(context, `${mode}: worklet collected full signal`, probe.worklet?.frames === 4096
        && uints(probe.worklet.capturedBits, 4096) && uints(probe.worklet.finalBits, 4096));
    }
    add(context, 'authored input identical across settings', equal(off.input, on.input) && equal(on.input, again.input));
    add(context, 'offline float noise within one ULP, zeros preserved', [0, 1].every(ch =>
      floatBound(off.first?.channels?.[ch]?.bits, on.first?.channels?.[ch]?.bits)));
    add(context, 'offline readback actually changed', [0, 1].every(ch =>
      !equal(off.first?.channels?.[ch]?.bits, on.first?.channels?.[ch]?.bits)));
    add(context, 'offline readback stable after process restart', equal(on.first, again.first));
    for (const name of ['FloatFrequency', 'ByteFrequency', 'FloatTimeDomain', 'ByteTimeDomain']) {
      const a = off.tone?.[name]?.values, b = on.tone?.[name]?.values;
      add(context, `${name}: bounded tone noise`, name.startsWith('Float') ? floatBound(a, b) : byteBound(a, b));
      add(context, `${name}: noise active`, !equal(a, b));
      add(context, `${name}: restart stable`, equal(on.tone?.[name], again.tone?.[name]));
    }
    add(context, 'worklet output unmodified when disabled', equal(off.worklet?.capturedBits, off.worklet?.finalBits));
    add(context, 'worklet input noise bounded', floatBound(off.worklet?.capturedBits, on.worklet?.capturedBits));
    add(context, 'completed worklet graph remains within one ULP', floatBound(off.worklet?.finalBits, on.worklet?.finalBits));
    add(context, 'completed worklet graph changed with noise', !equal(off.worklet?.finalBits, on.worklet?.finalBits));
    add(context, 'worklet and completed graph restart stable', equal(on.worklet, again.worklet));
    coverageChecks.push({ context, property: 'AudioWorklet graph inputs receive environment noise',
      passed: uints(on.worklet?.capturedBits, 4096)
        && !equal(off.worklet?.capturedBits, on.worklet?.capturedBits),
      observation: equal(off.worklet?.capturedBits, on.worklet?.capturedBits)
        ? 'worklet exposes the same raw signal with noise off and on' : 'worklet signal differs; additional policy validation required' });
  }
  return { corePassed: checks.length > 0 && checks.every(c => c.passed), checks, coverageChecks,
    passed: checks.length > 0 && checks.every(c => c.passed) && coverageChecks.every(c => c.passed),
    doesNotCertify: ['audible output or mute', 'all AudioWorklet-generated signals', 'media capture', 'other devices'] };
}

export function compareRects(before, after, repeat) {
  const checks = [], fields = ['x', 'y', 'width', 'height', 'top', 'right', 'bottom', 'left'];
  const add = (context, property, passed) => checks.push({ context, property, passed: passed === true });
  const finite = rect => rect && fields.every(field => typeof rect[field] === 'number' && Number.isFinite(rect[field]));
  const bounded = (a, b) => finite(a) && finite(b) && fields.every(field => Math.abs(a[field] - b[field]) <= 2 / 16384 + 1e-9);
  for (const context of ['main', 'iframe']) {
    const off = before?.[context]?.values, on = after?.[context]?.values, again = repeat?.[context]?.values;
    if (!off || !on || !again) { add(context, 'three complete context observations', false); continue; }
    for (const [mode, probe] of [['off', off], ['on', on], ['repeat', again]]) {
      add(context, `${mode}: all phases`, equal(probe.phases?.map(p => p.phase), ['base', 'scroll', 'zoom']));
      for (const phase of probe.phases || []) for (const name of ['element', 'range', 'collapsed', 'hidden']) {
        const value = phase.targets?.[name];
        add(context, `${mode}/${phase.phase}/${name}: native types and stable reads`,
          value?.listType === '[object DOMRectList]' && value?.rectType === '[object DOMRect]'
          && value.repeatEqual && finite(value.bounds) && Array.isArray(value.list) && value.list.every(finite));
        if (name === 'element' || name === 'range') add(context, `${mode}/${phase.phase}/${name}: nonempty geometry`, value?.list?.length > 0);
        if (name === 'element' || name === 'range') {
          const fragments = value?.list?.filter(rect => rect.width !== 0 && rect.height !== 0) || [];
          const left = Math.min(...fragments.map(rect => rect.left));
          const top = Math.min(...fragments.map(rect => rect.top));
          const right = Math.max(...fragments.map(rect => rect.right));
          const bottom = Math.max(...fragments.map(rect => rect.bottom));
          add(context, `${mode}/${phase.phase}/${name}: bounds agree with fragments`, fragments.length > 0
            && [['left', left], ['top', top], ['right', right], ['bottom', bottom]].every(([field, expected]) =>
              Math.abs(value.bounds[field] - expected) < 1e-8));
        }
        if (name === 'hidden') add(context, `${mode}/${phase.phase}: hidden stays empty`, value?.list?.length === 0
          && fields.every(field => value?.bounds?.[field] === 0));
        if (name === 'collapsed') add(context, `${mode}/${phase.phase}: collapsed width remains zero`, value?.list?.length > 0
          && value.list.every(rect => rect.width === 0) && value.bounds.width === 0);
      }
    }
    let changed = false;
    for (let i = 0; i < 3; i++) {
      const a = off.phases?.[i], b = on.phases?.[i], c = again.phases?.[i];
      add(context, `${i}: internal layout unchanged`, a?.layout && equal(a.layout, b?.layout) && equal(b?.layout, c?.layout));
      for (const name of ['element', 'range', 'collapsed', 'hidden']) {
        const original = a?.targets?.[name], noisy = b?.targets?.[name];
        add(context, `${i}/${name}: noise bounded and fragment count preserved`, bounded(original?.bounds, noisy?.bounds)
          && original?.list?.length === noisy?.list?.length
          && original?.list?.every((rect, index) => bounded(rect, noisy?.list?.[index])));
        changed ||= !equal(original?.bounds, noisy?.bounds);
        add(context, `${i}/${name}: process restart stable`, noisy && equal(noisy, c?.targets?.[name]));
      }
    }
    add(context, 'geometry noise actually active', changed);
    add(context, 'scroll and zoom fixtures moved', off.phases?.[0]?.targets?.element?.bounds?.y !== off.phases?.[1]?.targets?.element?.bounds?.y
      && off.phases?.[1]?.targets?.element?.bounds?.width !== off.phases?.[2]?.targets?.element?.bounds?.width);
  }
  return { checks, passed: checks.length > 0 && checks.every(c => c.passed),
    doesNotCertify: ['pinch zoom', 'ResizeObserver', 'IntersectionObserver', 'SVG geometry', 'other devices'] };
}
