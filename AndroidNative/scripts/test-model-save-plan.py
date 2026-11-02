#!/usr/bin/env python3
"""Run the exact model/store save methods with host-only lifecycle/native dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    source, stage = [p.resolve(strict=True) for p in (args.source, args.stage)]
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use an independent non-C test directory')
    relative = 'chrome/android/java/src/org/chromium/chrome/browser/app/tabmodel/ModelTrackingOrchestrator.java'
    staged = stage / 'files' / relative
    manifest = stage / 'manifest.json'
    expected = {i['path']: i['sha256'] for i in json.loads(manifest.read_text())['files']}
    if sha(staged) != expected[relative]:
        raise RuntimeError('Staged model source changed')
    text = staged.read_text()
    signature = '    public void saveAllBeforeLanduExit(Runnable onDone, Runnable onFailure) {'
    if text.count(signature) != 1:
        raise RuntimeError('Ambiguous model method')
    begin = text.index(signature)
    end = text.index('\n    public boolean isSynchronizerPresent(boolean incognito) {', begin)
    method = text[begin:end]
    store_relative = 'chrome/android/java/src/org/chromium/chrome/browser/app/tabmodel/TabStateStore.java'
    store = stage / 'files' / store_relative
    if sha(store) != expected[store_relative]:
        raise RuntimeError('Staged store source changed')
    store_text = store.read_text()
    store_signature = '    public void saveStateBeforeLanduExit(Runnable onDone, Runnable onFailure) {'
    if store_text.count(store_signature) != 1:
        raise RuntimeError('Ambiguous store method')
    store_begin = store_text.index(store_signature)
    store_end = store_text.index('\n    @Override\n    public void loadState(', store_begin)
    store_method = store_text[store_begin:store_end]
    template = native / 'host-tests/TabModelSavePlanTest.java.in'
    helpers = [native / 'java/com/landu/browser/nativecore' / (name + '.java')
               for name in ('EnvironmentSaveBarrier', 'EnvironmentShutdownCoordinator')]
    input_hashes = {str(p): sha(p) for p in [staged, store, manifest, template, *helpers]}
    output = Path(tempfile.mkdtemp(prefix='model-save-host-', dir=cache))
    for name in ('classes', 'temp', 'java/org/chromium/base'):
        (output / name).mkdir(parents=True)
    rendered = output / 'java/TabModelSavePlanTest.java'
    body = template.read_text()
    if (body.count('/* LANDU_MODEL_SAVE_METHOD */') != 1
            or body.count('/* LANDU_STORE_SAVE_METHOD */') != 1):
        raise RuntimeError('Ambiguous host fixture')
    rendered.write_text(body.replace('/* LANDU_MODEL_SAVE_METHOD */', method)
                        .replace('/* LANDU_STORE_SAVE_METHOD */', store_method))
    shim = output / 'java/org/chromium/base/ThreadUtils.java'
    shim.write_text('package org.chromium.base; public final class ThreadUtils { '
                    'private static final Thread OWNER = Thread.currentThread(); '
                    'public static void assertOnUiThread() { if (Thread.currentThread() != OWNER) '
                    'throw new AssertionError("Wrong host thread"); } }')
    jdk = source / 'third_party/jdk/current/bin'
    commands = [[str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(output / 'classes'),
                 *map(str, helpers), str(shim), str(rendered)],
                [str(jdk / 'java'), '-Xmx128m', '-Djava.io.tmpdir=' + str(output / 'temp'),
                 '-cp', str(output / 'classes'), 'com.landu.browser.nativecore.TabModelSavePlanTest']]
    log = output / 'host-check.log'
    with log.open('w') as stream:
        for command in commands:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
            if result.returncode:
                stream.flush()
                raise RuntimeError('Model save host test failed: ' + str(log))
    stable = all(sha(Path(path)) == value for path, value in input_hashes.items())
    report = {'kind': 'extracted-model-and-store-methods-host-only', 'revision': lock['revision'], 'passed': stable,
              'stageManifestSha256': sha(manifest), 'inputFiles': input_hashes,
              'methodSha256': hashlib.sha256(method.encode()).hexdigest(), 'log': str(log),
              'storeMethodSha256': hashlib.sha256(store_method.encode()).hexdigest(),
              'androidRuntimeExecuted': False, 'fullChromeJavaBuilt': False,
              'realDatabaseTested': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(log.read_text(), end='')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': stable}))
    if not stable:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
