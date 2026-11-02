#!/usr/bin/env python3
"""Compile against the real SDK, then exercise filesystem faults with host Android protocol doubles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use an independent non-C test directory')
    code = native / 'chromium/org/chromium/chrome/browser/tabpersistence/LanduAtomicFileWriter.java'
    fixture = native / 'host-tests/atomic'
    tests = sorted(fixture.rglob('*.java'))
    android = source / 'third_party/android_sdk/public/platforms/android-37.0/android.jar'
    with zipfile.ZipFile(android) as jar:
        jar.getinfo('android/util/AtomicFile.class')
    inputs = {str(p): sha(p) for p in [code, android, *tests]}
    output = Path(tempfile.mkdtemp(prefix='strict-atomic-host-', dir=cache))
    for name in ('api-classes', 'host-classes', 'temp', 'data'): (output / name).mkdir()
    jdk = source / 'third_party/jdk/current/bin'
    common = [str(jdk / 'javac'), '-J-Xmx128m', '-J-Djava.io.tmpdir=' + str(output / 'temp'),
              '--release', '11', '-encoding', 'UTF-8']
    commands = [[*common, '-classpath', str(android), '-d', str(output / 'api-classes'), str(code)],
                [*common, '-d', str(output / 'host-classes'), str(code), *map(str, tests)],
                [str(jdk / 'java'), '-Xmx128m', '-Djava.io.tmpdir=' + str(output / 'temp'),
                 '-cp', str(output / 'host-classes'),
                 'org.chromium.chrome.browser.tabpersistence.LanduAtomicFileWriterTest', str(output / 'data')]]
    log = output / 'checks.log'
    with log.open('w') as stream:
        for command in commands:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
            if result.returncode:
                stream.flush()
                raise RuntimeError('Strict atomic checks failed: ' + str(log))
    stable = all(sha(Path(path)) == value for path, value in inputs.items())
    report = {'kind': 'strict-writer-sdk-compile-and-host-io-faults', 'revision': lock['revision'],
              'passed': stable, 'inputFiles': inputs, 'log': str(log), 'androidApiCompiled': True,
              'hostFilesystemUsed': True, 'androidRuntimeExecuted': False, 'realDeviceFsyncTested': False,
              'legacyStoreCallersVerifiedByThisTest': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(log.read_text(), end='')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': stable}))
    if not stable: raise SystemExit(1)


if __name__ == '__main__': main()
