#!/usr/bin/env python3
"""Host JVM checks for session decisions only; Android stubs never execute UI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--adapter-report', required=True)
    parser.add_argument('--android-jar', required=True)
    args = parser.parse_args()
    native = Path(__file__).resolve().parents[1]
    source = Path(args.source).resolve(strict=True)
    android = Path(args.android_jar).resolve(strict=True)
    previous = Path(args.adapter_report).resolve(strict=True)
    report = json.loads(previous.read_text())
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    pinned = json.loads((native / 'upstream-lock.json').read_text())['revision']
    if (revision != pinned or report.get('revision') != pinned or not report.get('passed')
            or not report.get('inputsStable') or report.get('kind') != 'chromium-java-interface-compile-only'):
        raise RuntimeError('Use a passing current Java adapter check from the pinned checkout')
    for name, expected in report['sourceFiles'].items():
        path = (native / name).resolve(strict=True)
        if not path.is_relative_to(native) or digest(path) != expected:
            raise RuntimeError('Java source changed since the adapter check: ' + name)
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use a separate non-C test cache')
    compiled = previous.parent / 'classes'
    if not (compiled / 'com/landu/browser/nativecore/NativeEnvironmentRuntime.class').is_file():
        raise RuntimeError('Missing compiled NativeEnvironmentRuntime class')
    output = Path(tempfile.mkdtemp(prefix='runtime-launch-host-', dir=cache))
    classes = output / 'classes'; classes.mkdir()
    temporary = output / 'temp'; temporary.mkdir()
    test = native / 'host-tests/com/landu/browser/nativecore/NativeRuntimeLaunchTest.java'
    android_hash = digest(android)
    test_hash = digest(test)
    classpath = os.pathsep.join(map(str, (compiled, android)))
    jdk = source / 'third_party/jdk/current/bin'
    commands = [
        [str(jdk / 'javac'), '-J-Xmx128m', '-J-Djava.io.tmpdir=' + str(temporary),
         '--release', '11', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), str(test)],
        [str(jdk / 'java'), '-Xmx128m', '-Djava.io.tmpdir=' + str(temporary), '-cp',
         str(classes) + os.pathsep + classpath, 'com.landu.browser.nativecore.NativeRuntimeLaunchTest', str(output)],
    ]
    log = output / 'host-check.log'
    with log.open('w') as stream:
        for command in commands:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
            if result.returncode:
                stream.flush()
                raise RuntimeError('Host Java decision test failed; see ' + str(log))
    stable = (all(digest(native / name) == expected for name, expected in report['sourceFiles'].items())
              and digest(android) == android_hash and digest(test) == test_hash)
    if not stable:
        raise RuntimeError('Java source changed during the host check')
    record = {'kind': 'host-jvm-java-session-decisions-only', 'revision': revision,
              'adapterReportSha256': digest(previous), 'androidStubJarSha256': android_hash,
              'testSourceSha256': test_hash, 'inputsStable': stable, 'log': str(log),
              'passed': True, 'apkBuilt': False, 'androidRuntimeExecuted': False, 'uiTested': False}
    (output / 'report.json').write_text(json.dumps(record, indent=2))
    print(log.read_text(), end='')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': True, 'uiTested': False}))


if __name__ == '__main__':
    main()
