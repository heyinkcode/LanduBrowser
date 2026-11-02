#!/usr/bin/env python3
"""Verify real staged GN source assignments, APK package and resource bindings."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def option(command, key):
    for index, arg in enumerate(command):
        if arg == key:
            return command[index + 1]
        if arg.startswith(key + '='):
            return arg.split('=', 1)[1]
    return None


def logical_path(build, name, source):
    return Path(os.path.abspath(build / name)).relative_to(source).as_posix()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gn-report', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    previous = args.gn_report.resolve(strict=True)
    stage = args.stage.resolve(strict=True)
    native = Path(__file__).resolve().parents[1]
    data = json.loads(previous.read_text())
    manifest = stage / 'manifest.json'
    if (data.get('kind') != 'whole-staged-gn-graph-private-source-overlay' or not data.get('passed')
            or not data.get('inputsStable') or data['stageManifestSha256'] != sha(manifest)):
        raise RuntimeError('Use a passing graph report for this exact stage')
    for name, expected in data['inputFiles'].items():
        if sha(Path(name)) != expected:
            raise RuntimeError('GN input changed: ' + name)
    shadow, build = Path(data['sourceOverlay']), Path(data['buildDirectory'])
    gn = Path(data['command'][0])
    source = gn.parents[2]
    baseline_args = [Path(name) for name in data['inputFiles'] if Path(name).name == 'args.gn']
    if len(baseline_args) != 1 or (shadow / 'out').is_symlink():
        raise RuntimeError('Unexpected GN output tree')
    baseline = baseline_args[0].parent
    entries = {item['path']: item for item in json.loads(manifest.read_text())['files']}
    inputs = {str(previous): sha(previous), str(manifest): sha(manifest)}
    sources = {}
    modules = ['chrome/android/base_module_java', 'chrome/android/chrome_java',
               'chrome/browser/profiles/android/java', 'chrome/browser/tab/java',
               'chrome/browser/tabmodel/java', 'chrome/browser/tabpersistence/java']
    for module in modules:
        old_list = baseline / ('gen/' + module + '.sources')
        new_list = build / ('gen/' + module + '.sources')
        original = {logical_path(baseline, line, source) for line in old_list.read_text().splitlines()}
        current = {logical_path(build, line, shadow) for line in new_list.read_text().splitlines()}
        prefix = ('chrome/android/java/src/' if module == 'chrome/android/base_module_java' else
                  'chrome/browser/tabpersistence/android/java/src/' if module == 'chrome/browser/tabpersistence/java' else None)
        additions = {name for name, item in entries.items() if prefix and name.startswith(prefix)
                     and name.endswith('.java') and item['originalSha256'] is None}
        if current != original | additions:
            raise RuntimeError('GN source assignment mismatch: ' + module)
        sources[module] = {'sourceCount': len(current), 'stagedAdditions': len(additions)}
        inputs[str(old_list)] = sha(old_list)
        inputs[str(new_list)] = sha(new_list)
    command = [str(gn), 'desc', str(build), '//chrome/android:chrome_public_apk',
               'deps', '--all', '--format=json']
    text = subprocess.check_output(command, cwd=shadow, text=True)
    (previous.parent / 'apk-dependencies.json').write_text(text)
    dependencies = next(iter(json.loads(text).values()))['deps']
    if not {'//chrome/android:landu_notices_assets', '//chrome/android:landu_brand_resources'}.issubset(dependencies):
        raise RuntimeError('Landu assets/resources are not part of the APK dependency graph')
    notices_path = build / 'gen/chrome/android/landu_notices_assets.params.json'
    brand_path = build / 'gen/chrome/android/landu_brand_resources.params.json'
    notices, brand = json.loads(notices_path.read_text()), json.loads(brand_path.read_text())
    expected_assets = {name for name in entries if name.startswith('chrome/android/landu/assets/')}
    actual_assets = {logical_path(build, name, shadow) for name in notices['assets']['sources']}
    if actual_assets != expected_assets or set(notices['assets']['outputs']) != {
            'landu/' + Path(name).name for name in expected_assets}:
        raise RuntimeError('Notice asset mapping changed')
    resource_list = build / brand['res_sources_path']
    expected_resources = {name for name in entries if name.startswith('chrome/android/landu/res/')}
    actual_resources = {logical_path(build, name, shadow) for name in resource_list.read_text().splitlines()}
    if actual_resources != expected_resources or not brand.get('resources_overlay_zip'):
        raise RuntimeError('Brand resources are missing or are not an overlay')
    product_path = native / 'branding/product.json'
    package = json.loads(product_path.read_text())['package']
    package_actions = {}
    active = ''
    wanted = {'android_manifest', 'compile_resources', 'merge_manifests'}
    for line in (build / 'toolchain.ninja').open():
        if line.startswith('rule '):
            active = line.strip()[5:]
        if not line.lstrip().startswith('command = '):
            continue
        for action in wanted:
            if active != ('__chrome_android_chrome_public_apk__' + action
                          + '___build_toolchain_android_android_clang_arm64__rule'):
                continue
            raw = line.strip()[len('command = '):]
            values = shlex.split(raw.replace('$:', ':').replace('$ ', ' ').replace('$$', '$'))
            if action == 'android_manifest':
                matches = 'manifest_package=' + package in json.loads(option(values, '--variables'))
            else:
                matches = option(values, '--manifest-package') == package
            if not matches:
                raise RuntimeError('APK package mismatch: ' + action)
            package_actions[action] = hashlib.sha256(raw.encode()).hexdigest()
    if package_actions.keys() != wanted:
        raise RuntimeError('Missing APK manifest/resource actions')
    for path in (notices_path, brand_path, resource_list, product_path, Path(__file__).resolve()):
        inputs[str(path)] = sha(path)
    stable = all(sha(Path(name)) == expected for name, expected in inputs.items())
    record = {'kind': 'real-gn-landu-source-package-and-resource-bindings', 'passed': stable,
              'gnReport': str(previous), 'stageManifestSha256': sha(manifest),
              'inputFiles': inputs, 'inputsStable': stable, 'javaModules': sources,
              'package': package, 'packageActionSha256': package_actions,
              'noticeAssetCount': len(actual_assets), 'brandResourceCount': len(actual_resources),
              'apkDependencyCount': len(dependencies), 'fullResourceMergeBuilt': False,
              'apkBuilt': False, 'deviceTested': False, 'baselineModified': False}
    path = previous.parent / 'gn-bindings.json'
    path.write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'report': str(path), 'passed': stable, 'javaModules': len(sources),
                      'package': package, 'assets': len(actual_assets), 'resources': len(actual_resources)}))
    if not stable:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
