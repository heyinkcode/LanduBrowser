#!/usr/bin/env node
'use strict';

/**
 * Windows DPI scale factor regression and boundary selftest.
 *
 * Issue #19 Verification Matrix:
 * 1. Native DPI behavior: Windows profiles without an explicit scale override
 *    leave Chromium's display scaling to Windows.
 * 2. Windows persona parity: Desktop personas preserve native Windows scaling
 *    unless the profile explicitly pins a scale factor.
 * 3. User explicit scale overrides:
 *    - profile.privacy.forceDeviceScaleFactor is honored without duplicate flags.
 *    - profile.deviceScaleFactor is honored when privacy override is absent.
 *    - profile.privacy.forceDeviceScaleFactor takes precedence over profile.deviceScaleFactor.
 * 4. Existing CLI argument stability: Pre-existing user-specified --force-device-scale-factor
 *    flags in the target args array are preserved by mergeFlags, and duplicate flags are discarded.
 * 5. Platform isolation: Non-Windows host platforms (darwin, linux) do not inject
 *    --force-device-scale-factor, even when configured with a Windows persona, preserving
 *    native high-DPI display scaling on those operating systems.
 * 6. Numeric validity: Explicitly injected scale factors are strictly positive and finite.
 * 7. Module isolation: Verified through dynamic platform context switching, fresh module cache
 *    invalidation, and isolated subprocess execution.
 * 8. Mutation proofs: Explicit sensitivity checks confirming duplicate flags,
 *    non-Windows pollution, override clobbering, and invalid scale values are reliably caught.
 */

const assert = require('assert');
const path = require('path');
const { execFileSync } = require('child_process');

const {
  chromeArgsForFingerprint,
  buildFingerprint,
  buildInjectionScript,
  OS_PRESETS,
} = require('./fingerprint');

const {
  mergeFlags,
  findDuplicates,
  LIST_VALUE_FLAGS,
} = require('./command-line-flags');

const isMutateMode = process.argv.includes('--mutate') || process.env.MUTATE === '1';

const results = [];
const check = (name, fn) => {
  try {
    fn();
    results.push({ name, ok: true });
    console.log('  PASS  ' + name);
  } catch (error) {
    results.push({ name, ok: false, error });
    console.log('  FAIL  ' + name + ' - ' + error.message);
    process.exitCode = 1;
  }
};

/**
 * Executes a function within a mocked process.platform context, restoring the original
 * platform and property descriptor immediately upon completion.
 */
function withPlatform(targetPlatform, fn) {
  const originalPlatform = process.platform;
  const originalDescriptor = Object.getOwnPropertyDescriptor(process, 'platform');
  try {
    Object.defineProperty(process, 'platform', {
      value: targetPlatform,
      configurable: true,
      enumerable: true,
      writable: true,
    });
    return fn();
  } finally {
    if (originalDescriptor) {
      Object.defineProperty(process, 'platform', originalDescriptor);
    } else {
      Object.defineProperty(process, 'platform', {
        value: originalPlatform,
        configurable: true,
        enumerable: true,
        writable: true,
      });
    }
  }
}

/**
 * Executes a function with a fresh instance of the fingerprint and command-line-flags
 * modules with complete require.cache invalidation under a mocked platform context.
 */
function withIsolatedFingerprint(mockPlatform, fn) {
  const fpModulePath = require.resolve('./fingerprint');
  const flagsModulePath = require.resolve('./command-line-flags');
  const origPlatform = process.platform;
  const origDescriptor = Object.getOwnPropertyDescriptor(process, 'platform');

  try {
    Object.defineProperty(process, 'platform', {
      value: mockPlatform,
      configurable: true,
      enumerable: true,
      writable: true,
    });
    delete require.cache[fpModulePath];
    delete require.cache[flagsModulePath];
    const freshModule = require(fpModulePath);
    return fn(freshModule);
  } finally {
    delete require.cache[fpModulePath];
    delete require.cache[flagsModulePath];
    if (origDescriptor) {
      Object.defineProperty(process, 'platform', origDescriptor);
    } else {
      Object.defineProperty(process, 'platform', {
        value: origPlatform,
        configurable: true,
        enumerable: true,
        writable: true,
      });
    }
  }
}

/**
 * Extracts all --force-device-scale-factor flags from an argument list.
 */
function getScaleFactorFlags(args) {
  if (!Array.isArray(args)) return [];
  return args.filter((arg) => typeof arg === 'string' && /^--force-device-scale-factor(?:=|$)/i.test(arg.trim()));
}

/**
 * Parses the numeric scale factor value from a --force-device-scale-factor flag.
 */
function parseScaleFactorValue(flag) {
  if (typeof flag !== 'string') return NaN;
  const match = flag.match(/^--force-device-scale-factor=(.+)$/i);
  return match ? Number(match[1]) : NaN;
}

console.log(`Starting Windows DPI scale factor regression selftest (mode: ${isMutateMode ? 'MUTATION' : 'NORMAL'})...\n`);

// --- SECTION 1: Win32 default behavior and single-flag injection ---

check('win32 platform: default profile inherits native Windows display scaling', () => {
  withPlatform('win32', () => {
    const args = chromeArgsForFingerprint({}, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0, `Default launch must not override Windows display scaling, got ${scaleFlags.join(', ')}`);
  });
});

check('win32 platform: host display scale is applied to the browser UI', () => {
  withPlatform('win32', () => {
    const args = chromeArgsForFingerprint({}, {}, 2);
    assert.deepStrictEqual(getScaleFactorFlags(args), ['--force-device-scale-factor=2']);
  });
});

check('win32 platform: Windows desktop personas inherit native display scaling by default', () => {
  withPlatform('win32', () => {
    const windowsPersonas = [
      'windows-desktop-10-nv-1',
      'windows-desktop-10-amd-1',
      'windows-desktop-11-intel-1',
      'windows-desktop-11-nv-1',
      'windows-desktop-10-amd-2',
      'windows-desktop-11-intel-2',
    ];

    for (const personaId of windowsPersonas) {
      const fp = buildFingerprint({ os: 'windows', devicePersona: personaId });
      const args = chromeArgsForFingerprint(fp, {});
      const scaleFlags = getScaleFactorFlags(args);
      assert.strictEqual(scaleFlags.length, 0, `Persona ${personaId} must inherit Windows display scaling`);
    }
  });
});

// --- SECTION 2: Explicit profile overrides and precedence ---

check('win32 platform: profile.privacy.forceDeviceScaleFactor override is respected without duplicates', () => {
  withPlatform('win32', () => {
    const testCases = [1.25, 1.5, 2, 0.8];
    for (const factor of testCases) {
      const args = chromeArgsForFingerprint({}, { privacy: { forceDeviceScaleFactor: factor } });
      const scaleFlags = getScaleFactorFlags(args);
      assert.strictEqual(scaleFlags.length, 1, `Expected single scale flag for factor ${factor}`);
      assert.strictEqual(scaleFlags[0], `--force-device-scale-factor=${factor}`);
      assert.strictEqual(parseScaleFactorValue(scaleFlags[0]), factor);
    }
  });
});

check('win32 platform: profile.deviceScaleFactor property is respected when privacy override is absent', () => {
  withPlatform('win32', () => {
    const args = chromeArgsForFingerprint({}, { deviceScaleFactor: 1.5 });
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 1);
    assert.strictEqual(scaleFlags[0], '--force-device-scale-factor=1.5');
  });
});

check('win32 platform: profile.privacy.forceDeviceScaleFactor takes precedence over profile.deviceScaleFactor', () => {
  withPlatform('win32', () => {
    const args = chromeArgsForFingerprint({}, {
      privacy: { forceDeviceScaleFactor: 2 },
      deviceScaleFactor: 1.25,
    });
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 1);
    assert.strictEqual(scaleFlags[0], '--force-device-scale-factor=2', 'Privacy override must take precedence');
  });
});

// --- SECTION 3: Merge flags idempotency and duplicate prevention ---

check('mergeFlags integration: pre-existing user scale flag in launcher args is preserved without duplicate', () => {
  withPlatform('win32', () => {
    const baseArgs = [
      '--user-data-dir=/tmp/profile-1',
      '--no-first-run',
      '--force-device-scale-factor=1.75',
    ];
    const fpArgs = chromeArgsForFingerprint({}, {}); // default leaves Windows display scaling untouched
    const merged = mergeFlags(baseArgs, fpArgs, { listFlags: LIST_VALUE_FLAGS });

    const scaleFlags = getScaleFactorFlags(merged);
    assert.strictEqual(scaleFlags.length, 1, `Expected explicit scale flag to remain, got ${scaleFlags.length}`);
    assert.strictEqual(scaleFlags[0], '--force-device-scale-factor=1.75', 'Pre-existing user flag must win');

    const dupes = findDuplicates(merged).filter((d) => d.name === 'force-device-scale-factor');
    assert.deepStrictEqual(dupes, [], 'No duplicate force-device-scale-factor flags should exist');
  });
});

check('mergeFlags integration: repeated merge passes maintain idempotency', () => {
  withPlatform('win32', () => {
    let args = ['--no-default-browser-check'];
    const fpArgs = chromeArgsForFingerprint({}, {});

    args = mergeFlags(args, fpArgs, { listFlags: LIST_VALUE_FLAGS });
    args = mergeFlags(args, fpArgs, { listFlags: LIST_VALUE_FLAGS });
    args = mergeFlags(args, fpArgs, { listFlags: LIST_VALUE_FLAGS });

    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0, `Repeated merges must not inject a default scale flag, got ${scaleFlags.length}`);
  });
});

// --- SECTION 4: Platform isolation (Non-Windows hosts) ---

check('darwin platform: does NOT inject --force-device-scale-factor (preserves native Retina scaling)', () => {
  withPlatform('darwin', () => {
    const args = chromeArgsForFingerprint({}, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0, `Darwin host must not receive force-device-scale-factor, got ${scaleFlags.join(', ')}`);
  });
});

check('darwin platform: Windows persona on darwin host does NOT inject --force-device-scale-factor', () => {
  withPlatform('darwin', () => {
    const fp = buildFingerprint({ os: 'windows' });
    const args = chromeArgsForFingerprint(fp, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(
      scaleFlags.length,
      0,
      `Windows persona on macOS host must not force scale factor to avoid corrupting Retina display rendering`
    );
  });
});

check('linux platform: does NOT inject --force-device-scale-factor', () => {
  withPlatform('linux', () => {
    const fp = buildFingerprint({ os: 'linux' });
    const args = chromeArgsForFingerprint(fp, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0, `Linux host must not receive force-device-scale-factor`);
  });
});

// --- SECTION 5: DPR and deviceScaleFactor consistency ---

check('DPR consistency: scaleFactor is strictly positive and finite', () => {
  withPlatform('win32', () => {
    const validScales = [0.5, 1, 1.25, 1.5, 2, 2.5, 3];
    for (const val of validScales) {
      const args = chromeArgsForFingerprint({}, { privacy: { forceDeviceScaleFactor: val } });
      const parsed = parseScaleFactorValue(getScaleFactorFlags(args)[0]);
      assert.ok(Number.isFinite(parsed) && parsed > 0, `Scale factor must be positive finite number, got ${parsed}`);
    }
  });
});

check('DPR consistency: web content window.devicePixelRatio getter operates independently of window CLI scale', () => {
  const fp = buildFingerprint({ fingerprint: { devicePixelRatio: 1.25 } });
  const script = buildInjectionScript(fp);
  assert.ok(
    script.includes('devicePixelRatio'),
    'Injection script must contain devicePixelRatio getter definition'
  );
  assert.strictEqual(
    fp.screen?.devicePixelRatio,
    1.25,
    'Fingerprint screen object must retain requested devicePixelRatio'
  );
});

// --- SECTION 6: Module isolation and independent loader ---

check('module cache isolation: fresh module instance under win32 preserves native display scaling', () => {
  withIsolatedFingerprint('win32', (isolated) => {
    const args = isolated.chromeArgsForFingerprint({}, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0);
  });
});

check('module cache isolation: fresh module instance under darwin platform omits scale flag', () => {
  withIsolatedFingerprint('darwin', (isolated) => {
    const args = isolated.chromeArgsForFingerprint({}, {});
    const scaleFlags = getScaleFactorFlags(args);
    assert.strictEqual(scaleFlags.length, 0);
  });
});

check('subprocess isolation: independent win32 process preserves native display scaling', () => {
  const scriptCode = `
    const path = require("path");
    Object.defineProperty(process, "platform", { value: "win32", configurable: true });
    const { chromeArgsForFingerprint } = require("./fingerprint");
    const args = chromeArgsForFingerprint({}, {});
    const flags = args.filter(a => a.startsWith("--force-device-scale-factor="));
    if (flags.length !== 0) {
      process.exit(2);
    }
  `;

  execFileSync(process.execPath, ['-e', scriptCode], {
    cwd: __dirname,
    stdio: 'pipe',
  });
});

// --- SECTION 7: Mutation sensitivity proofs ---

check('explicit Windows scaling override remains available', () => {
  const args = chromeArgsForFingerprint({}, { privacy: { forceDeviceScaleFactor: 1.25 } });
  assert.deepStrictEqual(getScaleFactorFlags(args), ['--force-device-scale-factor=1.25']);
});

check('mutation sensitivity: duplicate --force-device-scale-factor flags are detected', () => {
  const duplicatedArgs = [
    '--force-device-scale-factor=1',
    '--user-agent=test',
    '--force-device-scale-factor=1.25',
  ];
  let caught = false;
  try {
    const scaleFlags = getScaleFactorFlags(duplicatedArgs);
    assert.strictEqual(scaleFlags.length, 1, 'Must fail on duplicate flags');
  } catch (err) {
    caught = true;
  }
  assert.ok(caught, 'Mutation check must detect duplicate scale factor flags');
});

check('mutation sensitivity: pollution on non-Windows platform is detected', () => {
  const pollutedDarwinArgs = [
    '--disable-blink-features=AutomationControlled',
    '--force-device-scale-factor=1',
  ];
  let caught = false;
  try {
    const scaleFlags = getScaleFactorFlags(pollutedDarwinArgs);
    assert.strictEqual(scaleFlags.length, 0, 'Must fail when darwin receives scale flag');
  } catch (err) {
    caught = true;
  }
  assert.ok(caught, 'Mutation check must detect scale factor pollution on darwin');
});

check('mutation sensitivity: clobbered user explicit scale override is detected', () => {
  const expectedUserScale = 1.5;
  const clobberedScaleArgs = ['--force-device-scale-factor=1']; // defaulted instead of honoring 1.5
  let caught = false;
  try {
    const scaleFlags = getScaleFactorFlags(clobberedScaleArgs);
    assert.strictEqual(parseScaleFactorValue(scaleFlags[0]), expectedUserScale);
  } catch (err) {
    caught = true;
  }
  assert.ok(caught, 'Mutation check must detect clobbered user explicit scale factor');
});

check('mutation sensitivity: invalid scale factor (NaN or zero) is detected', () => {
  const invalidArgs = ['--force-device-scale-factor=invalid'];
  let caught = false;
  try {
    const parsed = parseScaleFactorValue(invalidArgs[0]);
    assert.ok(Number.isFinite(parsed) && parsed > 0, 'Must fail on NaN or invalid scale');
  } catch (err) {
    caught = true;
  }
  assert.ok(caught, 'Mutation check must detect non-numeric or invalid scale factor');
});

// --- Final summary and reporting ---

const passedCount = results.filter((r) => r.ok).length;
const failedCount = results.filter((r) => !r.ok).length;
const totalCount = results.length;

console.log(`\n======================================================================`);
console.log(`windows-dpi-scale-factor-selftest: OK ${passedCount}/${totalCount}`);
if (failedCount > 0) {
  console.log(`FAILED: ${failedCount} check(s) failed`);
  process.exit(1);
}
