#!/usr/bin/env python3
"""Run a pinned Android module's GN Java actions in a private output directory.

This uses the generated GN commands and their actual dependency jars without
changing the busy Chromium checkout or sharing its build-server output.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def option(command, key):
    for index, arg in enumerate(command):
        if arg == key:
            return command[index + 1]
        if arg.startswith(key + '='):
            return arg.split('=', 1)[1]
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--module', choices=['tabpersistence', 'base_module', 'chrome',
                                             'profiles', 'tab', 'tabmodel',
                                             'background_public', 'background_internal',
                                             'base', 'notifications', 'notification_interceptor'],
                        default='tabpersistence')
    parser.add_argument('--prepared-inputs', type=Path,
                        help='Passing private input report from prepare-main-java.py')
    parser.add_argument('--java-dependency-report', type=Path, action='append', default=[],
                        help='Passing same-stage GN Java report whose private jars replace baseline dependencies')
    args = parser.parse_args()
    source, build, stage = [p.resolve(strict=True) for p in
                            (args.source, args.build_dir, args.stage)]
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    revision = subprocess.check_output(
        ['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != lock['revision']:
        raise RuntimeError('Use the pinned Chromium checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if (cache.is_relative_to(source) or str(cache).startswith('/mnt/c/')
            or not build.is_relative_to(source)):
        raise RuntimeError('Use a separate non-C output directory')
    manifest = stage / 'manifest.json'
    entries = {item['path']: item for item in json.loads(manifest.read_text())['files']}
    dependency_map = {}
    prepared_files = set()
    prepared_stage_sha = None
    reused_main_source_changes = []
    for path in args.java_dependency_report:
        path = path.resolve(strict=True)
        if not path.is_relative_to(cache):
            raise RuntimeError('Java dependency report must be in the private test cache')
        previous = json.loads(path.read_text())
        if (previous.get('kind') != 'real-gn-java-actions-private-output'
                or not previous.get('passed') or not previous.get('inputsStable')
                or previous.get('revision') != revision
                or previous.get('stageManifestSha256') != sha(manifest)):
            raise RuntimeError('Java dependency report must match this exact stage')
        prepared_files.add(path)
        for name, expected in previous['inputs'].items():
            dependency = Path(name).resolve(strict=True)
            if sha(dependency) != expected:
                raise RuntimeError('Java dependency input changed: ' + name)
            prepared_files.add(dependency)
        for name, item in previous.get('outputs', {}).items():
            relative = Path(name)
            dependency = Path(item['file']).resolve(strict=True)
            if (relative.is_absolute() or '..' in relative.parts
                    or relative.parts[0] not in ('obj', 'gen') or not dependency.is_relative_to(cache)
                    or sha(dependency) != item['sha256']):
                raise RuntimeError('Java dependency output changed: ' + name)
            dependency_map[name] = dependency
            prepared_files.add(dependency)
        if not previous.get('outputs'):
            raise RuntimeError('Java dependency report has no verifiable outputs')
    if args.prepared_inputs:
        prepared_path = args.prepared_inputs.resolve(strict=True)
        if not prepared_path.is_relative_to(cache):
            raise RuntimeError('Prepared inputs must be in the independent test cache')
        prepared = json.loads(prepared_path.read_text())
        if (prepared.get('kind') != 'main-java-private-gn-inputs' or not prepared.get('passed')
                or not prepared.get('inputsStable') or prepared.get('revision') != revision):
            raise RuntimeError('Prepared Java inputs do not match this stage')
        prepared_stage_sha = prepared.get('stageManifestSha256')
        if prepared_stage_sha != sha(manifest):
            # A main-module-only repair does not change its dependency ABI.
            # Reuse is allowed only when the complete staged file sets match
            # and every changed file belongs to the freshly compiled main
            # module. JNI, lower modules, GN files and native files must match.
            old_manifests = [Path(name) for name, digest in prepared['inputs'].items()
                             if Path(name).name == 'manifest.json' and digest == prepared_stage_sha]
            if len(old_manifests) != 1:
                raise RuntimeError('Prepared inputs require a matching stage')
            old_manifest = old_manifests[0]
            if sha(old_manifest) != prepared_stage_sha:
                raise RuntimeError('Previous dependency stage changed')
            previous = {item['path']: item for item in json.loads(old_manifest.read_text())['files']}
            if previous.keys() != entries.keys():
                raise RuntimeError('Dependency stage file set changed')
            allowed_main = {(build / line).resolve(strict=True).relative_to(source).as_posix()
                            for line in (build / 'gen/chrome/android/chrome_java.sources').read_text().splitlines()}
            for name, item in entries.items():
                if item['originalSha256'] != previous[name]['originalSha256']:
                    raise RuntimeError('Original dependency pin changed: ' + name)
                if item['sha256'] != previous[name]['sha256']:
                    if name not in allowed_main:
                        raise RuntimeError('Regenerate changed dependency: ' + name)
                    reused_main_source_changes.append(name)
        prepared_files.add(prepared_path)
        for name, expected in prepared['inputs'].items():
            path = Path(name).resolve(strict=True)
            if sha(path) != expected:
                raise RuntimeError('Prepared input changed: ' + name)
            prepared_files.add(path)
        for name, item in prepared['outputs'].items():
            relative = Path(name)
            path = Path(item['file']).resolve(strict=True)
            if (relative.is_absolute() or '..' in relative.parts
                    or relative.parts[0] not in ('gen', 'obj') or not path.is_relative_to(cache)
                    or sha(path) != item['sha256']):
                raise RuntimeError('Prepared output changed: ' + name)
            dependency_map[name] = path
            prepared_files.add(path)
    if args.module == 'tabpersistence':
        module = 'chrome/browser/tabpersistence/java'
        gn_path = 'chrome/browser/tabpersistence/BUILD.gn'
        prefix = 'chrome/browser/tabpersistence/android/java/src/org/chromium/chrome/browser/tabpersistence/'
        additions = [prefix + 'LanduAtomicFileWriter.java']
    elif args.module == 'base_module':
        module = 'chrome/android/base_module_java'
        gn_path = 'chrome/android/BUILD.gn'
        inventory = json.loads((native / 'overlay-inventory.json').read_text())
        additions = ['chrome/android/java/src/' + inventory['packagePath'] + '/' + name
                     for _, name in inventory['javaFiles']]
    elif args.module in ('base', 'notifications'):
        module = ('base/base_java' if args.module == 'base'
                  else 'components/browser_ui/notifications/android/java')
        gn_path = module.rsplit('/', 1)[0] + '/BUILD.gn'
        additions = []
    elif args.module == 'notification_interceptor':
        module = 'chrome/browser/notifications/java'
        gn_path = 'chrome/browser/notifications/BUILD.gn'
        additions = []
    elif args.module in ('background_public', 'background_internal'):
        module = ('components/background_task_scheduler/public_java'
                  if args.module == 'background_public'
                  else 'components/background_task_scheduler/internal/internal_java')
        gn_path = module.rsplit('/', 1)[0] + '/BUILD.gn'
        additions = []
    elif args.module == 'chrome':
        module = 'chrome/android/chrome_java'
        gn_path = 'chrome/android/BUILD.gn'
        additions = []
    else:
        module = {'profiles': 'chrome/browser/profiles/android/java',
                  'tab': 'chrome/browser/tab/java',
                  'tabmodel': 'chrome/browser/tabmodel/java'}[args.module]
        gn_path = module.rsplit('/', 1)[0] + '/BUILD.gn'
        additions = []
    original_list = build / ('gen/' + module + '.sources')
    original_sources = [(build / line).resolve(strict=True) for line in
                        original_list.read_text().splitlines() if line.strip()]
    if len(set(original_sources)) != len(original_sources):
        raise RuntimeError('Duplicate module sources')
    if subprocess.run(['git', '-C', str(source), 'diff', '--quiet', 'HEAD', '--',
                       *(str(p.relative_to(source)) for p in original_sources)],
                      check=False).returncode != 0:
        raise RuntimeError('Module sources differ from the pinned checkout')
    sources = []
    for original in original_sources:
        relative = original.relative_to(source).as_posix()
        if relative in entries:
            entry = entries[relative]
            if sha(original) != entry['originalSha256']:
                raise RuntimeError('Original source drift: ' + relative)
            path = stage / 'files' / relative
            if sha(path) != entry['sha256']:
                raise RuntimeError('Staged source drift: ' + relative)
        else:
            path = original
        sources.append((relative, path))
    for relative in additions:
        path = stage / 'files' / relative
        if sha(path) != entries[relative]['sha256'] or (source / relative).exists():
            raise RuntimeError('Added source drift: ' + relative)
        sources.append((relative, path))
    staged_gn = stage / 'files' / gn_path
    if gn_path in entries:
        if sha(staged_gn) != entries[gn_path]['sha256']:
            raise RuntimeError('Staged GN file drift')
    else:
        staged_gn = source / gn_path
        if subprocess.run(['git', '-C', str(source), 'diff', '--quiet', 'HEAD', '--', gn_path],
                          check=False).returncode != 0:
            raise RuntimeError('Original GN file changed')
    if any(Path(name).name not in staged_gn.read_text() for name in additions):
        raise RuntimeError('GN does not include the added Java sources')

    analysis_actions = (tuple('errorprone_shard' + str(i) for i in range(8))
                        if args.module == 'chrome' else ('errorprone',))
    actions = ('header', 'compile_java', 'validate_deps', *analysis_actions, 'dex')
    rules = {}
    active = ''
    with (build / 'toolchain.ninja').open() as stream:
        for line in stream:
            if line.startswith('rule '):
                active = line.strip()[5:]
            if not line.lstrip().startswith('command = '):
                continue
            for action in actions:
                if active == ('__' + module.replace('/', '_') + '__' + action
                              + '___build_toolchain_android_android_clang_arm64__rule'):
                    command = line.strip()[len('command = '):]
                    command = command.replace('$:', ':').replace('$ ', ' ').replace('$$', '$')
                    rules[action] = shlex.split(command)
    if set(rules) != set(actions):
        raise RuntimeError('Missing generated GN actions')

    output = Path(tempfile.mkdtemp(prefix='gn-' + args.module + '-', dir=cache))
    (output / 'temp').mkdir()
    source_list = output / 'java.sources'
    # Chromium's action cache serializes compiler arguments as ASCII. Staging on
    # a Windows path containing Chinese is fine; compile byte-identical private
    # copies under the Linux cache, just as applying into the checkout would.
    compile_sources = []
    for relative, path in sources:
        copied = output / 'inputs' / relative
        copied.parent.mkdir(parents=True, exist_ok=True)
        copied.write_bytes(path.read_bytes())
        if sha(copied) != sha(path):
            raise RuntimeError('Source copy mismatch')
        compile_sources.append(copied)
    source_list.write_text(''.join(os.path.relpath(p, build) + '\n' for p in compile_sources))
    source_responses = {'gen/' + module + '.sources': source_list}
    response_inputs = {source_list}
    if args.module == 'chrome':
        copies = {source / relative: copied for (relative, _), copied in zip(sources, compile_sources)}
        seen = []
        for action in analysis_actions:
            name = 'gen/' + module + '__' + action + '.sources'
            response = build / name
            shard = [(build / line).resolve(strict=True) for line in response.read_text().splitlines()]
            if any(path not in copies for path in shard):
                raise RuntimeError('Unexpected static-analysis shard source')
            seen.extend(shard)
            private = output / (action + '.sources')
            private.write_text(''.join(os.path.relpath(copies[path], build) + '\n' for path in shard))
            source_responses[name] = private
            response_inputs.update((response, private))
        if set(seen) != set(original_sources) or len(seen) != len(original_sources):
            raise RuntimeError('Static-analysis shards do not cover the whole module exactly once')
    remap = {}
    output_flags = {'--jar-path', '--depfile', '--generated-jar-path',
                    '--generated-dir', '--stamp', '--output', '--incremental-dir', '--filtered-jar'}
    for command in rules.values():
        for i, arg in enumerate(command):
            key, equals, value = arg.partition('=')
            if key in output_flags:
                value = value if equals else command[i + 1]
                parent = module.rsplit('/', 1)[0] + '/'
                if not value.startswith(('obj/' + parent, 'gen/' + parent)):
                    raise RuntimeError('Unexpected GN output: ' + value)
                remap[value] = os.path.relpath(output / value, build)
                (output / value).parent.mkdir(parents=True, exist_ok=True)
    # This module regenerates its own outputs. A prepared dependency jar with
    # the same name must not rewrite an output first and then be rewritten
    # again by the local output map.
    for name in remap:
        dependency_map.pop(name, None)

    # Hash the real dependencies as well as the sources so a concurrent baseline
    # build cannot silently invalidate the result by replacing an input jar.
    inputs = {manifest, staged_gn, original_list, *original_sources,
              *(path for _, path in sources), *prepared_files, *response_inputs, *compile_sources,
              Path(__file__).resolve()}
    filearg = re.compile(r'@FileArg\(([^:()]+):([^()]+)\)')
    for command in rules.values():
        for arg in command:
            if arg.startswith('--java-srcjars='):
                for name in json.loads(arg.split('=', 1)[1]):
                    if name not in remap:
                        inputs.add(dependency_map.get(name, build / name).resolve(strict=True))
            for config_name, keys in filearg.findall(arg):
                config_path = (build / config_name).resolve(strict=True)
                inputs.add(config_path)
                values = json.loads(config_path.read_text())
                for key in keys.split(':'):
                    values = values[key]
                if keys in ('processor_classes', 'javac_full_classpath_targets'):
                    continue
                for value in values:
                    dependency = dependency_map.get(value, build / value).resolve(strict=True)
                    if (not dependency.is_file() or not
                            (dependency.is_relative_to(source) or dependency.is_relative_to(cache))):
                        raise RuntimeError('Unexpected input: ' + str(dependency))
                    inputs.add(dependency)
        for value in command[:2]:
            inputs.add((build / value).resolve(strict=True))
    for name in ('third_party/turbine/cipd/turbine.jar',
                 'third_party/android_build_tools/nullaway/cipd/nullaway.jar',
                 'third_party/r8/d8/cipd/lib/r8.jar', 'third_party/r8/custom_d8.jar'):
        inputs.add((source / name).resolve(strict=True))
    before = {str(p): sha(p) for p in sorted(inputs)}
    env = os.environ.copy()
    env.update(TMPDIR=str(output / 'temp'), TMP=str(output / 'temp'),
               TEMP=str(output / 'temp'), PYTHONDONTWRITEBYTECODE='1')
    env.pop('AUTONINJA_BUILD_ID', None)
    env.pop('AUTONINJA_STDOUT_NAME', None)
    results = []
    for action in actions:
        command = []
        for index, arg in enumerate(rules[action]):
            if arg.startswith('@') and not arg.startswith('@FileArg'):
                if arg[1:] not in source_responses:
                    raise RuntimeError('Unexpected GN source response')
                arg = '@' + os.path.relpath(source_responses[arg[1:]], build)
            else:
                match = filearg.search(arg)
                if match:
                    value = json.loads((build / match.group(1)).read_text())
                    for key in match.group(2).split(':'):
                        value = value[key]
                    def mapped(item):
                        for old, path in sorted(dependency_map.items(), key=lambda pair: -len(pair[0])):
                            item = item.replace(old, os.path.relpath(path, build))
                        for old, new in sorted(remap.items(), key=lambda pair: -len(pair[0])):
                            item = item.replace(old, new)
                        return item
                    value = [mapped(item) for item in value] if isinstance(value, list) else mapped(value)
                    private_config = output / 'fileargs' / (action + '-' + str(index) + '.json')
                    private_config.parent.mkdir(exist_ok=True)
                    private_config.write_text(json.dumps({'value': value}) + '\n')
                    before[str(private_config)] = sha(private_config)
                    # Keep the original file-arg transport so a large classpath
                    # never exceeds the host's per-argument execve limit.
                    arg = (arg[:match.start()] + '@FileArg('
                           + os.path.relpath(private_config, build) + ':value)'
                           + arg[match.end():])
                else:
                    for old, path in sorted(dependency_map.items(), key=lambda item: -len(item[0])):
                        arg = arg.replace(old, os.path.relpath(path, build))
                    for old, new in sorted(remap.items(), key=lambda item: -len(item[0])):
                        arg = arg.replace(old, new)
            command.append(arg)
        command.insert(1, '-B')
        if '--use-build-server' in command:
            raise RuntimeError('Do not share the baseline build server')
        for flag in output_flags:
            original_output = option(rules[action], flag)
            if original_output:
                actual = (build / option(command, flag)).resolve()
                expected_output = (build / remap[original_output]).resolve()
                if actual != expected_output or not actual.is_relative_to(output):
                    raise RuntimeError('Action output was redirected incorrectly: ' + flag)
        log = output / (action + '.log')
        started = time.monotonic()
        print(json.dumps({'action': action, 'state': 'running', 'log': str(log)}), flush=True)
        try:
            with log.open('w') as stream:
                process = subprocess.run(command, cwd=build, env=env, stdout=stream,
                                         stderr=subprocess.STDOUT, timeout=240)
            code = process.returncode
        except subprocess.TimeoutExpired:
            code = 'timeout'
        results.append({'action': action, 'exitCode': code, 'command': command,
                        'seconds': round(time.monotonic() - started, 2), 'log': str(log)})
        if code != 0:
            print(log.read_text()[-12000:], flush=True)
            break
    stable = all(sha(Path(path)) == value for path, value in before.items())
    passed = len(results) == len(actions) and all(r['exitCode'] == 0 for r in results) and stable
    outputs = {name: {'file': str((build / private).resolve()),
                      'sha256': sha((build / private).resolve())}
               for name, private in remap.items() if (build / private).is_file()}
    report = {'kind': 'real-gn-java-actions-private-output', 'module': module,
              'revision': revision, 'stageManifestSha256': sha(manifest),
              'sourceCount': len(sources), 'inputs': before, 'inputsStable': stable,
              'errorProneShards': len(analysis_actions),
              'gnCommandsSha256': hashlib.sha256(json.dumps(rules, sort_keys=True).encode()).hexdigest(),
              'preparedInputsStageSha256': prepared_stage_sha,
              'reusedMainSourceChanges': reused_main_source_changes,
              'actions': results, 'outputs': outputs, 'passed': passed,
              'baselineOutputsModified': False,
              'fullChromeJavaBuilt': passed and args.module == 'chrome',
              'lintRun': False, 'apkBuilt': False, 'androidRuntimeExecuted': False,
              'deviceTested': False}
    report_path = output / 'report.json'
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(report_path), 'passed': passed}), flush=True)
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
