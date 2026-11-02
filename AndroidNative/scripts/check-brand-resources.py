#!/usr/bin/env python3
"""Compile/link a resource-only fixture; parse staged GN without modifying Chromium."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


NATIVE = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--stage', required=True, type=Path)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    stage = args.stage.resolve(strict=True)
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use a separate non-C test cache')
    lock = json.loads((NATIVE / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned Chromium checkout')
    spec = importlib.util.spec_from_file_location('landu_brand', NATIVE / 'scripts/generate-brand-assets.py')
    brand = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(brand)
    manifest = brand.check_assets()
    inventory = json.loads((NATIVE / 'overlay-inventory.json').read_text())
    receipt = json.loads((stage / 'manifest.json').read_text())
    staged = {item['path']: item['sha256'] for item in receipt['files']}
    source_hashes = {}
    for local, target in inventory['resourceFiles']:
        path = NATIVE / local
        expected = sha(path)
        if staged.get(target) != expected or sha(stage / 'files' / target) != expected:
            raise RuntimeError('Stale staged resource: ' + target)
        source_hashes[local] = expected
    tools = source / 'third_party/android_sdk/public'
    aapt = tools / 'build-tools/37.0.0/aapt2'
    android = tools / 'platforms/android-37.0/android.jar'
    gn = source / 'buildtools/linux64/gn'
    if not gn.is_file() or not aapt.is_file() or not android.is_file():
        raise RuntimeError('Pinned GN/Android resource tools are unavailable')
    output = Path(tempfile.mkdtemp(prefix='brand-resource-check-', dir=cache))
    for name in ('assets/landu', 'temp'):
        (output / name).mkdir(parents=True)
    for name in ('OPENBROWSER-LICENSE.txt', 'CHROMIUM-LICENSE.txt', 'build-info.json'):
        shutil.copyfile(NATIVE / 'branding/generated/assets' / name, output / 'assets/landu' / name)
    fixture = output / 'AndroidManifest.xml'
    fixture.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.landu.browser">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="37" />
  <application android:label="@string/app_name" android:icon="@drawable/ic_launcher" android:roundIcon="@drawable/ic_launcher_round" />
</manifest>\n''')
    commands = [
        [str(aapt), 'compile', '--dir', str(NATIVE / 'branding/generated/res'), '-o', str(output / 'resources.zip')],
        [str(aapt), 'link', '-o', str(output / 'resource-fixture.ap_'), '--manifest', str(fixture),
         '-I', str(android), '-A', str(output / 'assets'), str(output / 'resources.zip')],
    ]
    logs = []
    env = {**os.environ, 'TMPDIR': str(output / 'temp')}
    for index, command in enumerate(commands):
        log = output / f'aapt-{index}.log'
        with log.open('w') as handle:
            result = subprocess.run(command, stdout=handle, stderr=subprocess.STDOUT, env=env, timeout=60)
        logs.append(str(log))
        if result.returncode:
            raise RuntimeError(f'Resource compile/link failed: {log}\n' + log.read_text()[:4000])
    package = output / 'resource-fixture.ap_'
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(package)], text=True, timeout=30)
    (output / 'badging.txt').write_text(badging)
    for text in ("package: name='com.landu.browser'", "application-label:'Landu Browser'", "application-label-zh-CN:'岚渡浏览器'"):
        if text not in badging:
            raise RuntimeError('Unexpected linked product metadata: ' + text)
    with zipfile.ZipFile(package) as archive:
        for name in ('OPENBROWSER-LICENSE.txt', 'CHROMIUM-LICENSE.txt', 'build-info.json'):
            if archive.read('assets/landu/' + name) != (NATIVE / 'branding/generated/assets' / name).read_bytes():
                raise RuntimeError('Notice missing or changed in linked fixture')
    gn_files = [name for name in staged if name.endswith(('.gn', '.gni'))]
    for relative in gn_files:
        path = stage / 'files' / relative
        if sha(path) != staged.get(relative):
            raise RuntimeError('Changed staged GN file')
        log = output / (relative.replace('/', '__') + '.parse.log')
        with log.open('w') as handle:
            result = subprocess.run([str(gn), 'format', '--dump-tree=json', str(path)], stdout=handle,
                                    stderr=subprocess.STDOUT, timeout=30, env=env, cwd=source)
        if result.returncode:
            raise RuntimeError('GN parsing failed: ' + str(log))
        json.loads(log.read_text())
    brand.check_assets()
    if any(sha(NATIVE / name) != value for name, value in source_hashes.items()):
        raise RuntimeError('Inputs changed during resource checks')
    report = {'kind': 'android-brand-resource-fixture-only', 'passed': True,
              'revision': lock['revision'], 'sourceFiles': source_hashes,
              'checkerSha256': sha(Path(__file__)),
              'assetManifestSha256': sha(NATIVE / 'branding/asset-manifest.json'),
              'stageManifestSha256': sha(stage / 'manifest.json'),
              'aaptVersion': subprocess.check_output([str(aapt), 'version'], text=True, stderr=subprocess.STDOUT).strip(),
              'resources': len(manifest['outputs']), 'linkedNotices': 3, 'gnSyntaxParsed': len(gn_files),
              'chromiumCheckoutModified': False, 'fullResourceMergeBuilt': False,
              'apkBuilt': False, 'deviceTested': False, 'uiTested': False, 'logs': logs}
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': True,
                      'apkBuilt': False, 'fullResourceMergeBuilt': False}))


if __name__ == '__main__':
    main()
