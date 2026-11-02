"""Run existing Android Mojo/protobuf actions in an independent header tree."""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess


def option(command, key):
    for index, value in enumerate(command):
        if value == key:
            return command[index + 1]
        if value.startswith(key + '='):
            return value.split('=', 1)[1]
    return None


class IsolatedMojo:
    def __init__(self, source, build, generated):
        self.source, self.build, self.generated = source, build, generated
        self.inputs, self.parsers, self.visiting, self.done, self.actions = {}, {}, set(), set(), []
        self.helper_sha = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        active = ''
        for line in (build / 'toolchain.ninja').open():
            if line.startswith('rule '):
                active = line.strip()[5:]
            if (not line.lstrip().startswith('command = ') or '/mojom_parser.py ' not in line
                    or not active.endswith('___build_toolchain_android_android_clang_arm64__rule')):
                continue
            command = self.decode(line.strip()[len('command = '):])
            metadata = option(command, '--check-imports')
            if metadata and option(command, '--output-root') == 'gen':
                path = (build / metadata).resolve(strict=True)
                if path in self.parsers and self.parsers[path] != command:
                    raise RuntimeError('Ambiguous Mojo parser')
                self.parsers[path] = command

    @staticmethod
    def decode(command):
        return shlex.split(command.replace('$:', ':').replace('$ ', ' ').replace('$$', '$'))

    def track(self, path):
        path = path.resolve(strict=True)
        if not path.is_relative_to(self.source) or not path.is_file():
            raise RuntimeError('Unexpected Mojo input: ' + str(path))
        self.inputs.setdefault(str(path), hashlib.sha256(path.read_bytes()).hexdigest())
        return path

    def relative_source(self, path):
        path = path.resolve(strict=True)
        root = self.build / 'gen' if path.is_relative_to(self.build / 'gen') else self.source
        return path.relative_to(root).as_posix()

    def command_for(self, target):
        result = subprocess.check_output(
            ['ninja', '-C', str(self.build), '-t', 'commands', '-s', target], text=True)
        lines = result.splitlines()
        if len(lines) != 1:
            raise RuntimeError('Expected one Mojo command')
        return self.decode(lines[0])

    def run(self, command, kind):
        if command[0] != '../../third_party/cpython3/host/bin/python3':
            raise RuntimeError('Unexpected Mojo interpreter')
        self.track(self.build / command[1])
        prepared = [str((self.build / command[0]).resolve(strict=True)), '-B',
                    str((self.build / command[1]).resolve(strict=True)), *command[2:]]
        flag = '--output-root' if kind == 'parser' else '--cc-out-dir' if kind == 'protobuf' else '-o'
        if (option(command, flag) or '').rstrip('/') != 'gen':
            raise RuntimeError('Unexpected Mojo output directory')
        prepared[prepared.index(flag) + 1] = str(self.generated)
        if kind == 'parser':
            prepared += ['--module-root', 'gen']
        if kind == 'protobuf' and option(command, '--py-out-dir'):
            destination = self.generated.parent / 'pyproto'
            destination.mkdir(exist_ok=True)
            index = prepared.index('--py-out-dir') if '--py-out-dir' in prepared else None
            if index is None:
                raise RuntimeError('Review protobuf output option syntax')
            prepared[index + 1] = str(destination)
        log = self.generated.parent / ('mojo-action-%03d.log' % len(self.actions))
        with log.open('w') as stream:
            result = subprocess.run(prepared, cwd=self.build,
                                    env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'),
                                    stdout=stream, stderr=subprocess.STDOUT, timeout=90)
        self.actions.append({'kind': kind, 'command': prepared, 'exitCode': result.returncode,
                             'log': str(log)})
        if result.returncode:
            raise RuntimeError('Mojo generation failed: ' + log.read_text()[-3500:])

    def ensure_modules(self, metadata):
        metadata = self.track(metadata)
        if metadata in self.done:
            return
        if metadata in self.visiting:
            raise RuntimeError('Mojo metadata cycle')
        self.visiting.add(metadata)
        data = json.loads(metadata.read_text())
        sources = [self.track(metadata.parent / name) for name in data['sources']]
        modules = [self.relative_source(path) + '-module' for path in sources]
        if any(not (self.generated / name).is_file() and not (self.build / 'gen' / name).is_file()
               for name in modules):
            if metadata not in self.parsers:
                raise RuntimeError('Missing declared Mojo parser: ' + str(metadata))
            for dependency in data['deps']:
                self.ensure_modules(metadata.parent / dependency)
            command = self.parsers[metadata]
            if command[1] != '../../mojo/public/tools/mojom/mojom_parser.py':
                raise RuntimeError('Unexpected parser script')
            response = self.track(self.build / option(command, '--mojom-file-list'))
            listed = {(self.build / line).resolve(strict=True) for line in response.read_text().splitlines()}
            if listed != set(sources):
                raise RuntimeError('Mojo metadata/source-list mismatch')
            self.run(command, 'parser')
        for name in modules:
            if not (self.generated / name).is_file():
                self.track(self.build / 'gen' / name)
        self.visiting.remove(metadata)
        self.done.add(metadata)

    def prepare_header(self, header):
        path = Path(header)
        if path.is_absolute() or '..' in path.parts or not header.endswith('.h') or '.mojom' not in header:
            raise RuntimeError('Unexpected generated header path')
        command = self.command_for('gen/' + header)
        prefix = ['../../third_party/cpython3/host/bin/python3',
                  '../../mojo/public/tools/bindings/mojom_bindings_generator.py',
                  '--use_bundled_pylibs', '-o', 'gen', 'generate', '-d', '../../', '-I', '../../',
                  '--bytecode_path', 'gen/mojo/public/tools/bindings']
        if command[:12] != prefix or option(command, '-g') != 'c++':
            raise RuntimeError('Unreviewed generated-header action')
        response = self.track(self.build / option(command, '--filelist'))
        files = [self.track(self.build / name) for name in response.read_text().splitlines()]
        if not files or any(path.suffix != '.mojom' for path in files):
            raise RuntimeError('Unexpected Mojo file list')
        first = self.relative_source(files[0])
        parser = self.command_for('gen/' + first + '-module')
        if parser[:2] != ['../../third_party/cpython3/host/bin/python3',
                          '../../mojo/public/tools/mojom/mojom_parser.py']:
            raise RuntimeError('Unexpected Mojo parser owner')
        metadata = self.track(self.build / option(parser, '--check-imports'))
        self.ensure_modules(metadata)
        for source in files:
            name = self.relative_source(source) + '-module'
            destination = self.generated / name
            if not destination.is_file():
                original = self.track(self.build / 'gen' / name)
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(original.read_bytes())
        typemap = option(command, '--typemap')
        if typemap:
            self.track(self.build / typemap)
        self.run(command, 'headers')
        if '--generate_non_variant_code' not in command:
            shared = self.command_for('gen/' + first + '-features.h')
            if (shared[:12] != prefix or '--generate_non_variant_code' not in shared
                    or option(shared, '-g') != 'c++'):
                raise RuntimeError('Unreviewed shared-header action')
            shared_response = self.track(self.build / option(shared, '--filelist'))
            if shared_response.read_text() != response.read_text():
                raise RuntimeError('Shared Mojo input set changed')
            self.run(shared, 'shared-headers')
        if not (self.generated / header).is_file():
            raise RuntimeError('Mojo action did not create requested header')

    def prepare_proto_header(self, header):
        path = Path(header)
        if path.is_absolute() or '..' in path.parts or not header.endswith('.pb.h'):
            raise RuntimeError('Unexpected protobuf header path')
        command = self.command_for('gen/' + header)
        if command[:2] != ['../../third_party/cpython3/host/bin/python3',
                           '../../tools/protoc_wrapper/protoc_wrapper.py']:
            raise RuntimeError('Unreviewed protobuf action')
        if option(command, '--proto-in-dir') != '../../':
            raise RuntimeError('Review protobuf source root')
        if any('out' in arg.split('=', 1)[0] and arg.startswith('--')
               and arg.split('=', 1)[0] not in ('--cc-out-dir', '--py-out-dir') for arg in command):
            raise RuntimeError('Review additional protobuf outputs')
        protoc = self.track(self.build / option(command, '--protoc'))
        if protoc.stat().st_size == 0 or not os.access(protoc, os.X_OK):
            raise RuntimeError('Pinned protoc is not ready')
        roots = [self.source]
        for index, arg in enumerate(command):
            if arg == '--import-dir':
                roots.append((self.build / command[index + 1]).resolve(strict=True))
            elif arg.startswith('--import-dir='):
                roots.append((self.build / arg.split('=', 1)[1]).resolve(strict=True))
        seen = set()

        def include(name):
            candidate = next((root / name for root in roots if (root / name).is_file()), None)
            if candidate is None:
                raise RuntimeError('Missing protobuf source import: ' + name)
            candidate = self.track(candidate)
            if candidate in seen:
                return
            seen.add(candidate)
            for dependency in re.findall(r'^\s*import\s+(?:(?:public|weak)\s+)?"([^"]+)"\s*;',
                                          candidate.read_text(), re.MULTILINE):
                include(dependency)
        sources = [arg for arg in command[2:] if arg.endswith('.proto') and not arg.startswith('--')]
        if not sources:
            raise RuntimeError('Missing protobuf input')
        for name in sources:
            include(name)
        self.run(command, 'protobuf')
        generated = self.generated / header
        if not generated.is_file() or generated.stat().st_size == 0:
            raise RuntimeError('Protobuf action did not create real header content')

    def report(self, failure=None):
        stable = all(hashlib.sha256(Path(path).read_bytes()).hexdigest() == value
                     for path, value in self.inputs.items())
        stable = stable and hashlib.sha256(Path(__file__).read_bytes()).hexdigest() == self.helper_sha
        record = {'kind': 'isolated-actual-native-generated-input-actions', 'inputs': self.inputs,
                  'inputsStable': stable, 'actions': self.actions,
                  'helperSha256': self.helper_sha, 'failure': failure,
                  'passed': failure is None and stable and all(x['exitCode'] == 0 for x in self.actions),
                  'baselineModified': False}
        path = self.generated.parent / 'mojo-actions.json'
        path.write_text(json.dumps(record, indent=2) + '\n')
        return path, record['passed']
