// Offline-only probes: no speaker output, microphone, or media permission.
globalThis.landuReadAudio = async () => {
  if (typeof OfflineAudioContext !== 'function') throw Error('OfflineAudioContext unavailable');
  const bits = data => Array.from(new Uint32Array(data.buffer, data.byteOffset, data.length));
  const same = (a, b) => a.length === b.length && a.every((v, i) => Object.is(v, b[i]));
  const hash = async data => Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', data)),
    byte => byte.toString(16).padStart(2, '0')).join('');
  const length = 2048, rate = 44100;
  const authored = new AudioBuffer({ length, numberOfChannels: 2, sampleRate: rate });
  for (let channel = 0; channel < 2; channel++) {
    const values = authored.getChannelData(channel);
    for (let i = 0; i < length; i++) values[i] = i % 31 === 0 ? 0
      : Math.sin((i + 1) * (channel + 1) * 0.083) * 0.4;
    values[1] = -0;
  }
  const input = [0, 1].map(channel => bits(authored.getChannelData(channel)));
  async function render() {
    const context = new OfflineAudioContext(2, length, rate);
    const source = context.createBufferSource(); source.buffer = authored;
    source.connect(context.destination); source.start();
    let eventBits;
    context.oncomplete = event => { eventBits = [0, 1].map(ch => bits(event.renderedBuffer.getChannelData(ch))); };
    const output = await context.startRendering();
    const channels = [0, 1].map(channel => {
      const first = bits(output.getChannelData(channel));
      const copied = new Float32Array(length); output.copyFromChannel(copied, channel);
      return { bits: first, repeatEqual: same(first, bits(output.getChannelData(channel))),
        copyEqual: same(first, bits(copied)), eventEqual: same(first, eventBits?.[channel] || []) };
    });
    source.disconnect();
    return { channels, numberOfChannels: output.numberOfChannels, length: output.length, sampleRate: output.sampleRate };
  }
  async function analyser(silent) {
    const context = new OfflineAudioContext(1, 4096, rate);
    const source = context.createBufferSource();
    source.buffer = context.createBuffer(1, 4096, rate);
    if (!silent) {
      const values = source.buffer.getChannelData(0);
      for (let i = 0; i < values.length; i++) values[i] = Math.sin(2 * Math.PI * i / 32) * 0.4;
    }
    const node = context.createAnalyser(); node.fftSize = 256; node.smoothingTimeConstant = 0;
    source.connect(node); node.connect(context.destination); source.start();
    const pause = context.suspend(2048 / rate);
    const rendering = context.startRendering();
    await pause;
    const readings = {};
    for (const [name, float, frequency] of [['FloatFrequency', true, true], ['ByteFrequency', false, true],
      ['FloatTimeDomain', true, false], ['ByteTimeDomain', false, false]]) {
      const count = frequency ? node.frequencyBinCount : node.fftSize;
      const create = () => { const a = float ? new Float32Array(count + 16) : new Uint8Array(count + 16);
        a.fill(float ? 123.5 : 173); return a; };
      const first = create(), second = create();
      node[`get${name}Data`](first); node[`get${name}Data`](second);
      const values = float ? bits(first.subarray(0, count)) : Array.from(first.subarray(0, count));
      readings[name] = { values, count, repeatEqual: same(Array.from(first), Array.from(second)),
        tailPreserved: Array.from(first.subarray(count)).every(v => v === (float ? 123.5 : 173)) };
    }
    await context.resume(); await rendering;
    source.disconnect(); node.disconnect();
    return readings;
  }
  async function worklet() {
    const context = new OfflineAudioContext(1, 4096, rate);
    if (!context.audioWorklet) throw Error('Offline AudioWorklet unavailable');
    await context.audioWorklet.addModule('audio-worklet.js');
    const oscillator = context.createOscillator(); oscillator.type = 'triangle'; oscillator.frequency.value = 10000;
    const compressor = context.createDynamicsCompressor();
    compressor.threshold.value = -50; compressor.knee.value = 40; compressor.ratio.value = 12;
    compressor.attack.value = 0; compressor.release.value = 0.25;
    const tap = new AudioWorkletNode(context, 'landu-offline-capture', { outputChannelCount: [1] });
    const captured = [];
    let done;
    const received = new Promise(resolve => { done = resolve; });
    const timer = setTimeout(() => done(false), 8000);
    tap.port.onmessage = event => {
      if (event.data?.samples) captured.push(...event.data.samples);
      if (event.data?.complete) done(true);
    };
    try {
      oscillator.connect(compressor); compressor.connect(tap); tap.connect(context.destination); oscillator.start();
      const output = await context.startRendering();
      if (!await received || captured.length !== 4096) throw Error(`AudioWorklet capture incomplete: ${captured.length}`);
      const raw = Float32Array.from(captured);
      const final = output.getChannelData(0);
      return { frames: captured.length, capturedBits: bits(raw), finalBits: bits(final),
        capturedHash: await hash(raw), finalHash: await hash(final),
        note: 'Worklet sees graph samples before OfflineAudioContext completion; compare off/on before claiming coverage' };
    } finally {
      clearTimeout(timer); tap.port.close(); oscillator.disconnect(); compressor.disconnect(); tap.disconnect();
    }
  }
  const first = await render(), second = await render();
  return { schema: 1, input, first, second,
    authoredUnchanged: input.every((values, ch) => same(values, bits(authored.getChannelData(ch)))),
    silence: await analyser(true), tone: await analyser(false), worklet: await worklet(),
    scope: 'Offline rendering and analysers only; no audible output or mute acceptance' };
};
