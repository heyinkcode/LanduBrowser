#!/usr/bin/env python3
"""Run the staged APK's real manifest/resource actions in its private GN tree.

This is resource linking, not an APK build. Existing resource archives may be
copied from the baseline only when their GN resource description and source
list match. The active baseline's outputs are never written by this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import traceback
from manifest_audit import audit_manifest
import xml.etree.ElementTree as ET
import zipfile


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def commands(build):
    result, active = {}, ''
    for line in (build / 'toolchain.ninja').open():
        if line.startswith('rule '):
            active = line.strip()[5:]
        elif line.startswith('  command = '):
            raw = line.strip()[len('command = '):]
            result[active] = shlex.split(
                raw.replace('$:', ':').replace('$ ', ' ').replace('$$', '$'))
    return result


def option(command, key):
    for index, arg in enumerate(command):
        if arg == key:
            return command[index + 1]
        if arg.startswith(key + '='):
            return arg.split('=', 1)[1]
    return None


class Check:
    def __init__(self, report_path, skip_image_transcoding=False):
        self.previous = report_path.resolve(strict=True)
        graph = json.loads(self.previous.read_text())
        if (graph.get('kind') != 'whole-staged-gn-graph-private-source-overlay'
                or not graph.get('passed') or not graph.get('inputsStable')):
            raise RuntimeError('Use a passing staged GN graph report')
        self.shadow = Path(graph['sourceOverlay'])
        self.build = Path(graph['buildDirectory'])
        self.source = Path(graph['command'][0]).parents[2]
        args = [Path(n) for n in graph['inputFiles'] if Path(n).name == 'args.gn']
        if len(args) != 1 or (self.shadow / 'out').is_symlink():
            raise RuntimeError('Unexpected private GN tree')
        self.baseline = args[0].parent
        if (self.build.resolve().is_relative_to(self.source)
                or str(self.build.resolve()).startswith('/mnt/c/')):
            raise RuntimeError('Resource checks require a private non-C output tree')
        self.inputs = dict(graph['inputFiles'])
        self.track(self.previous)
        self.actions, self.copied = [], []
        self.rules = commands(self.build)
        self.old_rules = commands(self.baseline)
        self.native = Path(__file__).resolve().parents[1]
        self.track(Path(__file__).resolve())
        self.stage_sha = graph['stageManifestSha256']
        self.skip_image_transcoding = skip_image_transcoding
        self.audit = Path(tempfile.mkdtemp(prefix='resource-check-', dir=self.previous.parent))
        self.temp = self.audit / 'temp'
        self.temp.mkdir()
        self.environment = dict(os.environ, TMPDIR=str(self.temp), TMP=str(self.temp),
                                TEMP=str(self.temp), PYTHONDONTWRITEBYTECODE='1')
        self.resource_owners = {}
        self.resource_actions = {option(c, '--resource-zip-out'): n for n, c in self.rules.items()
                                 if len(c) > 1 and Path(c[1]).name == 'prepare_resources.py'}
        for path in (self.build / 'gen').rglob('*.params.json'):
            data = json.loads(path.read_text())
            for field in ('resources_zip', 'resources_overlay_zip'):
                if data.get(field):
                    self.resource_owners[data[field]] = path

    def track(self, path):
        key = str(path)
        digest = sha(path)
        if key in self.inputs and self.inputs[key] != digest:
            raise RuntimeError('Input changed: ' + key)
        self.inputs[key] = digest
        return path

    def private(self, name):
        path = self.build / name
        if not path.resolve().is_relative_to(self.build.resolve()):
            raise RuntimeError('Output escapes private tree: ' + name)
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def rule(self, suffix, namespace='chrome_android_chrome_public_apk'):
        name = '__' + namespace + '__' + suffix + '___build_toolchain_android_android_clang_arm64__rule'
        if name not in self.rules:
            raise RuntimeError('Missing real GN action: ' + name)
        return name

    def run(self, name):
        command = list(self.rules[name])
        allowed = {'jinja_template.py', 'write_build_config.py', 'prepare_resources.py',
                   'merge_manifest.py', 'compile_resources.py', 'validate_inputs.py'}
        if (command[0] != '../../third_party/cpython3/host/bin/python3'
                or Path(command[1]).name not in allowed):
            raise RuntimeError('Unexpected GN resource command')
        self.track((self.build / command[0]).resolve(strict=True))
        self.track((self.build / command[1]).resolve(strict=True))
        if Path(command[1]).name == 'compile_resources.py' and self.skip_image_transcoding:
            if '--png-to-webp' not in command:
                raise RuntimeError('Unexpected image-transcoding configuration')
            command.remove('--png-to-webp')
            index = command.index('--webp-binary')
            del command[index:index + 2]
        command.insert(1, '-B')
        log = self.audit / ('resource-action-%02d.log' % len(self.actions))
        with log.open('w') as stream:
            code = subprocess.run(command, cwd=self.build, env=self.environment,
                                  stdout=stream, stderr=subprocess.STDOUT,
                                  timeout=1200).returncode
        self.actions.append({'rule': name, 'command': command, 'cwd': str(self.build),
                             'exitCode': code, 'log': str(log)})
        print(json.dumps({'rule': name, 'exitCode': code, 'log': str(log)}), flush=True)
        if code:
            raise RuntimeError('Resource action failed:\n' + log.read_text()[-14000:])

    def matching_params(self, start):
        """Compare the full metadata closure before reusing shared APK metadata."""
        pending, seen = [start], set()
        while pending:
            name = pending.pop()
            if name in seen:
                continue
            seen.add(name)
            current, old = self.build / name, self.baseline / name
            if not old.is_file() or sha(self.track(current)) != sha(self.track(old)):
                raise RuntimeError('Cannot reuse changed GN metadata: ' + name)
            def visit(value):
                if isinstance(value, str) and value.endswith('.params.json'):
                    pending.append(value)
                elif isinstance(value, list):
                    for child in value:
                        visit(child)
                elif isinstance(value, dict):
                    for child in value.values():
                        visit(child)
            visit(json.loads(current.read_text()))

    def copy(self, name, resource=False, allow_empty=False):
        old, destination = self.baseline / name, self.private(name)
        if allow_empty and not (name.endswith('.resources.zip.info') or Path(name).name == 'R.txt'):
            raise RuntimeError('Only resource mapping info or AAR symbol lists may be empty')
        if not old.is_file() or (old.stat().st_size == 0 and not allow_empty):
            raise RuntimeError('Baseline resource input not ready: ' + name)
        if resource:
            owner = self.resource_owners.get(name)
            if owner is None:
                raise RuntimeError('No resource metadata owner: ' + name)
            self.matching_params(owner.relative_to(self.build).as_posix())
            source_list = json.loads(owner.read_text()).get('res_sources_path')
            if source_list:
                current, previous = self.build / source_list, self.baseline / source_list
                if sha(self.track(current)) != sha(self.track(previous)):
                    raise RuntimeError('Resource source list changed: ' + name)
            if not zipfile.is_zipfile(old):
                raise RuntimeError('Invalid baseline resource ZIP: ' + name)
        self.track(old)
        shutil.copy2(old, destination)
        if sha(destination) != self.inputs[str(old)]:
            raise RuntimeError('Input changed during copy: ' + name)
        self.copied.append({'name': name, 'source': str(old), 'sha256': sha(destination)})

    def prepare_resource(self, name):
        rule = self.resource_actions.get(name)
        if not rule:
            raise RuntimeError('Missing real resource generation action: ' + name)
        source_list = self.track(self.build / option(self.rules[rule], '--res-sources-path'))
        symbol_list = option(self.rules[rule], '--r-text-in')
        if symbol_list:
            if not (self.build / symbol_list).is_file():
                self.copy(symbol_list, allow_empty=True)
            self.track(self.build / symbol_list)
        for item in source_list.read_text().splitlines():
            path = self.build / item
            if not path.is_file():
                if Path(item).is_absolute() or '..' in Path(item).parts:
                    raise RuntimeError('Resource source is not ready: ' + item)
                self.copy(item)
            self.track(path.resolve(strict=True))
        self.private(name)
        rtxt = option(self.rules[rule], '--r-text-out')
        if rtxt:
            self.private(rtxt)
        self.run(rule)

    def check_manifest(self):
        name = 'gen/chrome/android/chrome_public_apk/AndroidManifest.merged.xml'
        root = ET.parse(self.build / name).getroot()
        android = '{http://schemas.android.com/apk/res/android}'
        app = root.find('application')
        policy_path = Path(__file__).resolve().parents[1] / 'manifest-component-policy.json'
        self.track(policy_path)
        self.track(Path(__file__).resolve().with_name('manifest_audit.py'))
        boundary = audit_manifest(root, json.loads(policy_path.read_text()))
        manager = 'com.landu.browser.nativecore.LanduManagerActivity'
        launchers = [item for item in app if any(
            category.get(android + 'name') == 'android.intent.category.LAUNCHER'
            for category in item.findall('intent-filter/category'))]
        private = {item.get(android + 'name'): item for item in app
                   if item.get(android + 'name', '').startswith('com.landu.browser.nativecore.')}
        if (root.get('package') != 'com.landu.browser'
                or app.get(android + 'label') != '@string/app_name'
                or app.get(android + 'icon') != '@drawable/ic_launcher'
                or app.get(android + 'roundIcon') != '@drawable/ic_launcher_round'
                or len(launchers) != 1
                or launchers[0].get(android + 'targetActivity') != manager
                or launchers[0].get(android + 'exported') != 'true'
                or manager not in private
                or private[manager].get(android + 'process') != ':landu_manager'
                or any(item.get(android + 'exported') != 'false' for item in private.values())):
            raise RuntimeError('Merged manifest changed Landu identity, launcher or private components')
        self.manifest_summary = {'package': root.get('package'), 'launcherCount': len(launchers),
                                 'launcherTarget': manager, 'privateComponentCount': len(private),
                                 'externalBoundary': boundary}

    def perform(self):
        self.private('gen/chrome/android/chrome_public_apk/AndroidManifest.xml')
        self.run(self.rule('android_manifest'))
        # This unchanged WebView target supplies shared-asset suffix metadata;
        # copying metadata does not build or include a WebView browser shell.
        shared = 'gen/android_webview/system_webview_64_apk.build_config.json'
        self.matching_params(shared.replace('.build_config.json', '.params.json'))
        self.copy(shared)
        self.run(self.rule('build_config_crbug_908819'))
        manifest_config = self.track(self.build / 'gen/chrome/android/chrome_public_apk.manifest.build_config.json')
        for name in json.loads(manifest_config.read_text())['extra_android_manifests']:
            if not (self.build / name).is_file():
                if Path(name).is_absolute() or '..' in Path(name).parts:
                    raise RuntimeError('Missing source manifest: ' + name)
                self.copy(name)
        self.private('gen/chrome/android/chrome_public_apk/AndroidManifest.merged.xml')
        self.run(self.rule('merge_manifests'))
        self.check_manifest()
        brand = 'obj/chrome/android/landu_brand_resources.resources.zip'
        self.private(brand)
        self.run('__chrome_android_landu_brand_resources___build_toolchain_android_android_clang_arm64__rule')
        self.run('__chrome_android_landu_notices_assets___build_toolchain_android_android_clang_arm64__rule')
        resource_config = self.track(self.build / 'gen/chrome/android/chrome_public_apk.res.build_config.json')
        data = json.loads(resource_config.read_text())
        for name in sorted(set(data['dependency_zips'] + data['dependency_zip_overlays'])):
            if name == brand:
                continue
            baseline = self.baseline / name
            if baseline.is_file() and baseline.stat().st_size > 0:
                self.copy(name, resource=True)
                if (self.baseline / (name + '.info')).is_file():
                    self.copy(name + '.info', allow_empty=True)
            else:
                self.prepare_resource(name)
        if not self.skip_image_transcoding:
            self.copy('clang_x64/cwebp')
        self.private('obj/chrome/android/chrome_public_apk/chrome_public_apk.resources.proguard.txt')
        self.run(self.rule('compile_resources'))

    def report(self, failure):
        stable = all(Path(p).is_file() and sha(Path(p)) == h for p, h in self.inputs.items())
        record = {'kind': 'real-staged-apk-resource-actions', 'gnReport': str(self.previous),
                  'stageManifestSha256': self.stage_sha, 'inputFiles': self.inputs,
                  'inputsStable': stable, 'actions': self.actions, 'copiedInputs': self.copied,
                  'mergedManifest': getattr(self, 'manifest_summary', None),
                  'passed': failure is None and stable, 'failure': failure,
                  'resourceTableLinked': failure is None and stable,
                  'imageTranscodingSkipped': self.skip_image_transcoding,
                  'fullResourceMergeBuilt': failure is None and stable and not self.skip_image_transcoding,
                  'apkBuilt': False, 'nativeLinked': False, 'deviceTested': False,
                  'baselineModified': False}
        path = self.audit / 'report.json'
        path.write_text(json.dumps(record, indent=2) + '\n')
        print(json.dumps({'report': str(path), 'passed': record['passed'],
                          'actions': len(self.actions), 'copiedInputs': len(self.copied)}), flush=True)
        return record['passed']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gn-report', type=Path, required=True)
    parser.add_argument('--skip-image-transcoding', action='store_true',
                        help='Link all actual resources with AAPT2, keeping PNGs; this is not the final resource action.')
    args = parser.parse_args()
    check = Check(args.gn_report, args.skip_image_transcoding)
    failure = None
    try:
        check.perform()
    except Exception:
        failure = traceback.format_exc()
        print(failure, flush=True)
    if not check.report(failure):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
