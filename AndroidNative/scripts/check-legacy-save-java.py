#!/usr/bin/env python3
"""Compile all six legacy tab-file classes against their real Chromium dependencies."""
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
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    source, build, stage = [p.resolve(strict=True) for p in (args.source, args.build_dir, args.stage)]
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/') or not build.is_relative_to(source):
        raise RuntimeError('Use an independent non-C test directory')
    prefix = 'chrome/browser/tabpersistence/android/java/src/org/chromium/chrome/browser/tabpersistence/'
    original = ['FlatBufferTabStateSerializer', 'TabStateDirectory', 'TabStateSerializer']
    patched = ['TabStateFileManager', 'TabMetadataFileManager', 'LanduAtomicFileWriter']
    manifest = stage / 'manifest.json'
    expected = {item['path']: item['sha256'] for item in json.loads(manifest.read_text())['files']}
    sources = []
    for names, root, hashes in [(original, source, lock['behaviorFiles']), (patched, stage / 'files', expected)]:
        for name in names:
            relative = prefix + name + '.java'
            path = root / relative
            if sha(path) != hashes[relative]:
                raise RuntimeError('Legacy source drift: ' + relative)
            sources.append(path)
    config_file = build / 'gen/chrome/browser/tabpersistence/java.javac.build_config.json'
    config = json.loads(config_file.read_text())
    jars = []
    for name in [*config['sdk_interface_jars'], *config['javac_full_interface_classpath']]:
        path = (build / name).resolve(strict=True)
        if not path.is_relative_to(source) or not path.is_file():
            raise RuntimeError('Unexpected Java interface path')
        if path not in jars:
            jars.append(path)
    output = Path(tempfile.mkdtemp(prefix='legacy-save-java-', dir=cache))
    for name in ('classes', 'temp'):
        (output / name).mkdir()
    inputs = {str(p): sha(p) for p in [manifest, config_file, *sources, *jars]}
    command = [str(source / 'third_party/jdk/current/bin/javac'), '-J-Xmx256m',
               '-J-Djava.io.tmpdir=' + str(output / 'temp'), '--release', '17', '-encoding', 'UTF-8', '-proc:none',
               '-classpath', os.pathsep.join(map(str, jars)), '-d', str(output / 'classes'), *map(str, sources)]
    log = output / 'javac.log'
    with log.open('w') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=90)
    stable = all(sha(Path(path)) == value for path, value in inputs.items())
    report = {'kind': 'six-legacy-tab-file-classes-interface-compile-only', 'revision': lock['revision'],
              'stageManifestSha256': sha(manifest), 'inputFiles': inputs, 'inputsStable': stable,
              'exitCode': result.returncode, 'passed': result.returncode == 0 and stable, 'log': str(log),
              'fullChromeJavaBuilt': False, 'errorProneOrNullAwayRun': False,
              'androidRuntimeExecuted': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': report['passed']}), flush=True)
    if not report['passed']:
        print(log.read_text()[:6000])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
