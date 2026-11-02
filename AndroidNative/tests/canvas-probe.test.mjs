import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { deflateSync } from 'node:zlib';
await import('data:text/javascript;base64,' + fs.readFileSync(new URL('../verification/canvas.js', import.meta.url)).toString('base64'));

const sourceHash = 'a'.repeat(64), isolatedHash = 'b'.repeat(64);
function contexts(isolated = false) {
  return Object.fromEntries(['main', 'iframe', 'dedicatedWorker', 'serviceWorker'].map(name => [name, { values: {
    schema: 1, width: 64, height: 32, userAgent: 'same-phone', sourceHash,
    variants: Object.fromEntries((name === 'main' || name === 'iframe' ? ['html', 'offscreen'] : ['offscreen']).map(kind => {
      const hash = isolated ? isolatedHash : sourceHash;
      return [kind, { hash, maxRgbDelta: isolated ? 1 : 0, alphaPreserved: true, transparentPreserved: true,
        repeatStable: true, cropConsistent: true, writeBackStable: true, backingUnchangedAfterExport: true,
        exports: kind === 'html' ? { dataUrlPng: hash, toBlobPng: hash } : { convertToBlobPng: hash } }];
    })),
  } }]));
}
test('requires unchanged known pixels while noise is off, and actual changed pixels while enabled', () => {
  const baseline = contexts();
  assert.ok(landuCanvasChecks(baseline).every(check => check.passed));
  assert.ok(landuCanvasChecks(contexts(true), { contexts: baseline }).every(check => check.passed));
  assert.ok(landuCanvasChecks(baseline, { contexts: baseline }).some(check => !check.passed));
  assert.ok(landuCanvasChecks(contexts(true)).some(check => !check.passed));
});
test('an unpatched PNG export cannot pass using the changed readback hash', () => {
  const values = contexts(true);
  values.main.values.variants.html.exports.toBlobPng = sourceHash;
  const checks = landuCanvasChecks(values, { contexts: contexts() });
  assert.equal(checks.find(check => check.context === 'main' && check.property === 'toBlobPng').passed, false);
});
test('missing workers, excessive deltas, damaged crop, alpha or writeback all fail', () => {
  for (const property of ['alphaPreserved', 'transparentPreserved', 'cropConsistent', 'writeBackStable', 'repeatStable', 'backingUnchangedAfterExport']) {
    const values = contexts(true);
    values.main.values.variants.html[property] = false;
    assert.ok(landuCanvasChecks(values, { contexts: contexts() }).some(check => !check.passed));
  }
  const values = contexts(true);
  delete values.serviceWorker;
  values.main.values.variants.html.maxRgbDelta = 2;
  assert.ok(landuCanvasChecks(values, { contexts: contexts() }).filter(check => !check.passed).length > 1);
});
test('reports from a different source pattern or user agent are not comparable', () => {
  for (const field of ['sourceHash', 'userAgent', 'width']) {
    const before = contexts(), after = contexts(true);
    before.main.values[field] = field === 'width' ? 32 : 'different';
    assert.equal(landuCanvasChecks(after, { contexts: before })
      .find(check => check.context === 'main' && check.property === 'baselineComparable').passed, false);
  }
});

function chunk(type, data) {
  const typeBytes = Buffer.from(type), bytes = Buffer.concat([typeBytes, data]);
  let crc = -1;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (-(crc & 1) & 0xedb88320);
  }
  const result = Buffer.alloc(12 + data.length);
  result.writeUInt32BE(data.length); bytes.copy(result, 4);
  result.writeUInt32BE((crc ^ -1) >>> 0, result.length - 4);
  return result;
}
function png(filtered, channels = 4, height = 2) {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2); header.writeUInt32BE(height, 4);
  header[8] = 8; header[9] = channels === 4 ? 6 : 2;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(Buffer.from(filtered))), chunk('IEND', Buffer.alloc(0))]);
}
test('raw PNG inspection reverses every PNG row filter without Canvas readback', async () => {
  const pixels = [10,20,30,255,40,50,60,128,70,80,90,255,100,110,120,255];
  // Independently prepared filtered second rows for None/Sub/Up/Average/Paeth.
  const rows = [
    [70,80,90,255,100,110,120,255],
    [70,80,90,255,30,30,30,0],
    [60,60,60,0,60,60,60,127],
    [65,70,75,128,45,45,45,64],
    [60,60,60,0,30,30,30,127],
  ];
  for (let filter = 0; filter < 5; filter++) {
    const decoded = await landuReadPngPixels(png([0, ...pixels.slice(0,8), filter, ...rows[filter]]));
    assert.equal(decoded.width, 2); assert.equal(decoded.height, 2);
    assert.deepEqual(Array.from(decoded.pixels), pixels, `PNG filter ${filter}`);
  }
});
test('RGB PNG exports receive opaque alpha and split IDAT streams decode correctly', async () => {
  const image = png([0,1,2,3,4,5,6], 3, 1);
  assert.deepEqual(Array.from((await landuReadPngPixels(image)).pixels), [1,2,3,255,4,5,6,255]);
  const compressed = deflateSync(Buffer.from([0,1,2,3,4,5,6]));
  const split = Buffer.concat([image.subarray(0,33), chunk('IDAT',compressed.subarray(0,2)),
    chunk('IDAT',compressed.subarray(2)), chunk('IEND',Buffer.alloc(0))]);
  assert.deepEqual(Array.from((await landuReadPngPixels(split)).pixels), [1,2,3,255,4,5,6,255]);
});
test('truncated, altered, overlong and invalid-filter PNGs fail instead of producing a passing hash', async () => {
  const valid = png([0,1,2,3,255,4,5,6,255,0,7,8,9,255,10,11,12,255]);
  const altered = Buffer.from(valid); altered[45] ^= 1;
  const badFilter = png([5,1,2,3,255,4,5,6,255,0,7,8,9,255,10,11,12,255]);
  const tooMuch = png([0,1,2,3,255,4,5,6,255,0,7,8,9,255,10,11,12,255,42]);
  for (const bytes of [valid.subarray(0,20), altered, badFilter, tooMuch, Buffer.concat([valid, Buffer.from([0])])])
    await assert.rejects(() => landuReadPngPixels(bytes));
});
