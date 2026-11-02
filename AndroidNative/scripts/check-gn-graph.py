#!/usr/bin/env python3
"""Generate the complete staged Chromium GN graph in a private source overlay.

Only ancestors of staged files are materialized. Other source entries are
read-only links to the pinned checkout; out/ is always a real private directory.
No C++ compilation or live-checkout patch application is performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


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
        raise RuntimeError('Use a separate non-C test cache and the pinned build directory')
    manifest = stage / 'manifest.json'
    metadata = json.loads(manifest.read_text())
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != metadata['revision']:
        raise RuntimeError('Use the pinned checkout')
    inputs = {str(manifest): sha(manifest), str(build / 'args.gn'): sha(build / 'args.gn')}
    tree = {}
    for item in metadata['files']:
        relative = Path(item['path'])
        if relative.is_absolute() or '..' in relative.parts or relative.parts[0] in ('.git', 'out'):
            raise RuntimeError('Unsafe staged path')
        staged = stage / 'files' / relative
        if not staged.resolve(strict=True).is_relative_to(stage / 'files') or sha(staged) != item['sha256']:
            raise RuntimeError('Stage drift: ' + str(relative))
        inputs[str(staged)] = sha(staged)
        if item['originalSha256'] is not None:
            original = source / relative
            if sha(original) != item['originalSha256']:
                raise RuntimeError('Original drift: ' + str(relative))
            inputs[str(original)] = sha(original)
        elif (source / relative).exists():
            raise RuntimeError('Added path already exists in baseline')
        branch = tree
        for part in relative.parts:
            branch = branch.setdefault(part, {})
        branch[None] = staged
    output = Path(tempfile.mkdtemp(prefix='gn-graph-', dir=cache))
    shadow = output / 'src'
    counts = {'directories': 0, 'links': 0, 'stagedFiles': 0}

    def mirror(relative, overrides):
        target = shadow / relative
        if None in overrides:
            target.write_bytes(overrides[None].read_bytes())
            counts['stagedFiles'] += 1
            return
        target.mkdir()
        counts['directories'] += 1
        original = source / relative
        children = {path.name: path for path in original.iterdir()} if original.is_dir() else {}
        for name in sorted(children.keys() | overrides.keys()):
            if relative == Path('.') and name == 'out':
                (target / name).mkdir()
                continue
            if name in overrides:
                mirror(relative / name, overrides[name])
            else:
                (target / name).symlink_to(children[name], target_is_directory=children[name].is_dir())
                counts['links'] += 1

    mirror(Path('.'), tree)
    destination = shadow / 'out/LanduGraph'
    destination.mkdir(parents=True)
    if (shadow / 'out').is_symlink() or not destination.resolve().is_relative_to(output):
        raise RuntimeError('Never write through the baseline output link')
    (destination / 'args.gn').write_bytes((build / 'args.gn').read_bytes())
    for item in metadata['files']:
        if sha(shadow / item['path']) != item['sha256']:
            raise RuntimeError('Shadow source mismatch')
    gn = source / 'buildtools/linux64/gn'
    inputs[str(gn)] = sha(gn)
    inputs[str(Path(__file__).resolve())] = sha(Path(__file__).resolve())
    baseline_changes = subprocess.check_output(
        ['git', '-C', str(source), 'diff', '--name-only'], text=True)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(output / 'temp'))
    (output / 'temp').mkdir()
    log = output / 'gn-gen.log'
    command = [str(gn), 'gen', str(destination), '--root=' + str(shadow)]
    print(json.dumps({'state': 'gn-gen', 'output': str(output), 'overlay': counts}), flush=True)
    started = time.monotonic()
    with log.open('w') as stream:
        result = subprocess.run(command, cwd=shadow, env=env, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=240)
    stable = all(sha(Path(path)) == value for path, value in inputs.items())
    stable = stable and subprocess.check_output(
        ['git', '-C', str(source), 'diff', '--name-only'], text=True) == baseline_changes
    passed = result.returncode == 0 and stable and (destination / 'build.ninja').is_file()
    report = {'kind': 'whole-staged-gn-graph-private-source-overlay',
              'revision': revision, 'stageManifestSha256': sha(manifest),
              'sourceOverlay': str(shadow), 'buildDirectory': str(destination),
              'sourceEntries': counts, 'inputFiles': inputs, 'inputsStable': stable,
              'command': command, 'exitCode': result.returncode,
              'seconds': round(time.monotonic() - started, 2), 'log': str(log),
              'passed': passed, 'gnIncludesChecked': False, 'nativeCompiled': False,
              'fullResourceMergeBuilt': False, 'apkBuilt': False, 'deviceTested': False,
              'baselineCheckoutModified': False}
    report_path = output / 'report.json'
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(report_path), 'passed': passed}), flush=True)
    if not passed:
        print(log.read_text()[-12000:], flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
