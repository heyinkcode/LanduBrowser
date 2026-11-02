#!/usr/bin/env python3
"""Prepare the main Chrome Java module's real GN inputs without changing the live build.

Only resource-symbol, resource-Java, interface-jar and ijar actions are allowed.
Patched dependency modules and regenerated JNI use the same reviewed stage.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
import traceback


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def option(command, key):
    for index, arg in enumerate(command):
        if arg == key:
            return command[index + 1]
        if arg.startswith(key + '='):
            return arg.split('=', 1)[1]
    return None


class Pipeline:
    def __init__(self, source, build, stage, cache):
        self.source, self.build, self.stage = source, build, stage
        self.native = Path(__file__).resolve().parents[1]
        self.output = Path(tempfile.mkdtemp(prefix='main-java-inputs-', dir=cache))
        (self.output / 'temp').mkdir()
        self.env = dict(os.environ, TMPDIR=str(self.output / 'temp'),
                        TMP=str(self.output / 'temp'), TEMP=str(self.output / 'temp'),
                        PYTHONDONTWRITEBYTECODE='1')
        self.inputs, self.outputs, self.commands, self.running = {}, {}, [], set()
        self.rules, self.symbols, self.resources, self.ijars, self.jni = {}, {}, {}, {}, {}
        manifest = self.read_json(stage / 'manifest.json')
        self.entries = {item['path']: item for item in manifest['files']}
        self.revision = subprocess.check_output(
            ['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
        if self.revision != manifest['revision']:
            raise RuntimeError('Use the pinned Chromium checkout')
        for name, item in self.entries.items():
            if sha(stage / 'files' / name) != item['sha256']:
                raise RuntimeError('Stage drift: ' + name)
            if item['originalSha256'] is not None and sha(source / name) != item['originalSha256']:
                raise RuntimeError('Original source drift: ' + name)
        # GN action arguments are parsed as data, never passed to a shell.
        allowed = {'turbine.py', 'prepare_resources.py', 'create_r_java.py', 'ijar.py'}
        with (build / 'toolchain.ninja').open() as stream:
            for line in stream:
                if not line.lstrip().startswith('command = '):
                    continue
                if not any('/' + name + ' ' in line for name in allowed | {'jni_zero.py'}):
                    continue
                raw = line.strip()[len('command = '):]
                command = shlex.split(raw.replace('$:', ':').replace('$ ', ' ').replace('$$', '$'))
                if command[0] != '../../third_party/cpython3/host/bin/python3':
                    raise RuntimeError('Unexpected GN interpreter')
                script = Path(command[1]).name
                if script == 'turbine.py':
                    self.rules[option(command, '--jar-path')] = command
                elif script == 'prepare_resources.py':
                    self.symbols[option(command, '--r-text-out')] = command
                elif script == 'create_r_java.py':
                    self.resources[option(command, '--srcjar-out')] = command
                elif script == 'ijar.py':
                    if len(command) != 5:
                        raise RuntimeError('Unexpected ijar command')
                    self.ijars[command[-1]] = command
                elif script == 'jni_zero.py' and 'from-source' in command:
                    self.jni[option(command, '--srcjar-path')] = command
        changed_java = {name for name in self.entries if name.endswith('.java')}
        self.forced = set()
        for jar, command in self.rules.items():
            for arg in command:
                if arg.startswith('@') and not arg.startswith('@FileArg'):
                    response = build / arg[1:]
                    if not response.is_file():
                        continue
                    for name in response.read_text().splitlines():
                        path = (build / name).resolve()
                        if path.is_relative_to(source) and path.relative_to(source).as_posix() in changed_java:
                            self.forced.add(jar)
        self.root = 'obj/chrome/android/chrome_java.turbine.jar'
        self.forced.discard(self.root)

    def track(self, path):
        path = path.resolve(strict=True)
        if not path.is_file():
            raise RuntimeError('Expected an input file: ' + str(path))
        if str(path) not in self.inputs:
            self.inputs[str(path)] = sha(path)
        return path

    def read_json(self, path):
        self.track(path)
        return json.loads(path.read_text())

    def mapped(self, name):
        return self.outputs.get(name, self.build / name)

    def put_output(self, name):
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or path.parts[0] not in ('gen', 'obj'):
            raise RuntimeError('Unexpected output: ' + name)
        target = self.output / 'outputs' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        self.outputs[name] = target
        return target

    def expand(self, command):
        expanded = []
        for arg in command:
            match = re.search(r'@FileArg\(([^:()]+):([^()]+)\)', arg)
            if match:
                config, keys = match.groups()
                value = self.read_json(self.build / config)
                for key in keys.split(':'):
                    value = value[key]
                if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
                    raise RuntimeError('Unexpected file-arg schema')
                if keys != 'processor_classes':
                    for name in value:
                        self.ensure(name)
                arg = arg[:match.start()] + json.dumps(value) + arg[match.end():]
            expanded.append(arg)
        return expanded

    def run(self, kind, target, command):
        self.track((self.build / command[0]).resolve())
        self.track((self.build / command[1]).resolve())
        working = self.build
        prepared = []
        if kind == 'resource-java':
            # create_r_java derives its private Java package from srcjar-out.
            # Preserve the canonical GN-relative output name, with a private
            # cwd; a ../../ prefix would turn that package into an absolute ZIP
            # entry and would also change the resource class ABI.
            working = self.output / 'outputs'
            for index, arg in enumerate(command):
                if index < 2:
                    arg = str((self.build / arg).resolve(strict=True))
                elif arg.startswith('--deps-rtxts='):
                    names = json.loads(arg.split('=', 1)[1])
                    arg = '--deps-rtxts=' + json.dumps(
                        [os.path.relpath(self.mapped(name), working) for name in names])
                prepared.append(arg)
        else:
            for arg in command:
                for old, path in sorted(self.outputs.items(), key=lambda item: -len(item[0])):
                    arg = arg.replace(old, os.path.relpath(path, self.build))
                prepared.append(arg)
        prepared.insert(1, '-B')
        if any(arg in ('--use-build-server', '--auto-add-deps') for arg in prepared):
            raise RuntimeError('Do not share a server or edit build sources')
        log = self.output / ('action-%04d.log' % len(self.commands))
        started = time.monotonic()
        print(json.dumps({'action': kind, 'target': target, 'completed': len(self.commands),
                          'log': str(log)}), flush=True)
        with log.open('w') as stream:
            result = subprocess.run(prepared, cwd=working, env=self.env, stdout=stream,
                                    stderr=subprocess.STDOUT, timeout=180)
        self.commands.append({'kind': kind, 'target': target, 'exitCode': result.returncode,
                              'command': prepared, 'cwd': str(working),
                              'seconds': round(time.monotonic() - started, 2),
                              'log': str(log)})
        if result.returncode:
            raise RuntimeError('Action failed: ' + target + '\n' + log.read_text()[-7000:])

    def java_sources(self, jar, command):
        responses = [arg[1:] for arg in command if arg.startswith('@') and not arg.startswith('@FileArg')]
        if len(responses) != 1:
            raise RuntimeError('Expected one Java source list')
        response = self.track(self.build / responses[0])
        files = []
        for name in response.read_text().splitlines():
            path = self.track((self.build / name).resolve(strict=True))
            relative = path.relative_to(self.source).as_posix()
            if relative in self.entries:
                path = self.track(self.stage / 'files' / relative)
                copied = self.output / 'sources' / relative
                copied.parent.mkdir(parents=True, exist_ok=True)
                copied.write_bytes(path.read_bytes())
                path = copied
            files.append(path)
        additions = []
        if jar == 'obj/chrome/android/base_module_java.turbine.jar':
            inventory = self.read_json(self.native / 'overlay-inventory.json')
            additions = ['chrome/android/java/src/' + inventory['packagePath'] + '/' + name
                         for _, name in inventory['javaFiles']]
        elif jar == 'obj/chrome/browser/tabpersistence/java.turbine.jar':
            additions = ['chrome/browser/tabpersistence/android/java/src/org/chromium/chrome/browser/tabpersistence/LanduAtomicFileWriter.java']
        for relative in additions:
            path = self.track(self.stage / 'files' / relative)
            copied = self.output / 'sources' / relative
            copied.parent.mkdir(parents=True, exist_ok=True)
            copied.write_bytes(path.read_bytes())
            files.append(copied)
        destination = self.output / 'source-lists' / (hashlib.sha256(jar.encode()).hexdigest() + '.sources')
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(''.join(os.path.relpath(path, self.build) + '\n' for path in files))
        return responses[0], destination

    def ensure(self, name):
        if name in self.outputs and self.outputs[name].is_file():
            return
        if name not in self.forced and (self.build / name).is_file():
            self.track(self.build / name)
            return
        if name in self.running:
            raise RuntimeError('Dependency cycle: ' + name)
        self.running.add(name)
        if name in self.rules:
            command = self.expand(self.rules[name])
            for arg in command:
                if arg.startswith('--java-srcjars='):
                    for source_jar in json.loads(arg.split('=', 1)[1]):
                        self.ensure(source_jar)
            response_name, source_list = self.java_sources(name, command)
            command = ['@' + os.path.relpath(source_list, self.build)
                       if arg == '@' + response_name else arg for arg in command]
            for flag in ('--jar-path', '--depfile', '--generated-jar-path'):
                value = option(command, flag)
                if value:
                    self.put_output(value)
            self.run('header', name, command)
        elif name in self.resources:
            command = self.expand(self.resources[name])
            for flag in ('--srcjar-out', '--depfile'):
                value = option(command, flag)
                if value:
                    self.put_output(value)
            self.run('resource-java', name, command)
        elif name in self.symbols:
            command = self.symbols[name]
            response = self.track(self.build / option(command, '--res-sources-path'))
            for path in response.read_text().splitlines():
                self.track(self.build / path)
            for flag in ('--r-text-out', '--resource-zip-out', '--depfile'):
                value = option(command, flag)
                if value:
                    self.put_output(value)
            self.run('resource-symbols', name, command)
        elif name in self.ijars:
            command = self.ijars[name]
            self.track(self.build / command[2])
            self.ensure(command[3])
            self.put_output(name)
            self.run('ijar', name, command)
        else:
            raise RuntimeError('Input is not ready and has no permitted action: ' + name)
        if not self.mapped(name).is_file():
            raise RuntimeError('Action did not produce its declared input: ' + name)
        self.running.remove(name)

    def prepare_jni(self):
        checker = self.track(self.native / 'scripts/check-native-overlay.py')
        spec = importlib.util.spec_from_file_location('landu_native_checker', checker)
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        generated = self.output / 'jni'
        generated.mkdir()
        for tab, group in [(False, 'chrome/browser/profiles/android'), (True, 'chrome/browser/tab')]:
            key = 'gen/' + group + '/jni_headers.srcjar'
            command = self.jni[key]
            self.track(self.build / option(command, '--type-catalogs-file'))
            self.track(self.build / command[1])
            for arg in command:
                if arg.startswith('--input-file='):
                    original = (self.build / arg.split('=', 1)[1]).resolve(strict=True)
                    relative = original.relative_to(self.source).as_posix()
                    self.track(self.stage / 'files' / relative if relative in self.entries else original)
            helper.prepare_profile_jni(self.source, self.build, self.stage, generated, tab=tab)
            for suffix in ('.srcjar', '_placeholder.srcjar', '.type_catalog.json'):
                key = 'gen/' + group + '/jni_headers' + suffix
                path = generated / (group + '/jni_headers' + suffix)
                if not path.is_file():
                    raise RuntimeError('Missing regenerated JNI output')
                self.outputs[key] = path

    def prepare(self):
        self.prepare_jni()
        root_command = self.expand(self.rules[self.root])
        for arg in root_command:
            if arg.startswith('--java-srcjars='):
                for name in json.loads(arg.split('=', 1)[1]):
                    self.ensure(name)
        # Every changed dependency must have a matching new interface, even when
        # the unmodified baseline happens to have an older jar already built.
        for name in sorted(self.forced):
            self.ensure(name)

    def report(self, failure):
        stable = all(sha(Path(path)) == digest for path, digest in self.inputs.items())
        record = {'kind': 'main-java-private-gn-inputs', 'revision': self.revision,
                  'stageManifestSha256': sha(self.stage / 'manifest.json'),
                  'inputs': self.inputs, 'inputsStable': stable,
                  'outputs': {name: {'file': str(path), 'sha256': sha(path)}
                              for name, path in self.outputs.items() if path.is_file()},
                  'forcedStagedHeaderModules': sorted(self.forced), 'actions': self.commands,
                  'passed': failure is None and stable, 'failure': failure,
                  'chromiumCheckoutModified': False, 'fullChromeJavaBuilt': False,
                  'apkBuilt': False, 'deviceTested': False}
        path = self.output / 'report.json'
        path.write_text(json.dumps(record, indent=2) + '\n')
        print(json.dumps({'report': str(path), 'passed': record['passed'],
                          'actions': len(self.commands), 'outputs': len(record['outputs'])}), flush=True)
        return record['passed']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    source, build, stage = [path.resolve(strict=True) for path in
                            (args.source, args.build_dir, args.stage)]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/') or not build.is_relative_to(source):
        raise RuntimeError('Use a separate non-C cache and the pinned build directory')
    pipeline = Pipeline(source, build, stage, cache)
    failure = None
    try:
        pipeline.prepare()
    except Exception:
        failure = traceback.format_exc()
        print(failure, flush=True)
    if not pipeline.report(failure):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
