class LanduOfflineCapture extends AudioWorkletProcessor {
  constructor() { super(); this.frames = 0; }
  process(inputs, outputs) {
    const input = inputs[0]?.[0], output = outputs[0]?.[0];
    if (!output) return false;
    if (input) output.set(input); else output.fill(0);
    if (this.frames < 4096) {
      const count = Math.min(output.length, 4096 - this.frames);
      this.frames += count;
      this.port.postMessage({ samples: Array.from(output.subarray(0, count)), complete: this.frames === 4096 });
    }
    return this.frames < 4096;
  }
}
registerProcessor('landu-offline-capture', LanduOfflineCapture);
