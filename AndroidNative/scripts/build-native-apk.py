#!/usr/bin/env python3
"""Build the staged Landu APK incrementally after the upstream baseline succeeds."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BUILD_ROOT = None
SOURCE = None
OUT = None


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def space():
    for path in (BUILD_ROOT, SOURCE):
        if shutil.disk_usage(path).free < 12 * 1024 ** 3:
            raise RuntimeError('Build stopped to preserve 12 GiB free space: ' + str(path))


def v8_release_arguments(arguments):
    # Keep the public V8 header configuration compatible with existing Blink
    # objects while removing V8's internal development checks and test features.
    for name, value in (('v8_dcheck_always_on', 'false'),
                        ('v8_enable_memory_accounting_checks', 'true')):
        assignment = re.compile(r'(?m)^[ \t]*' + name + r'[ \t]*=.*$')
        matches = list(assignment.finditer(arguments))
        if len(matches) > 1:
            raise RuntimeError('Duplicate GN argument: ' + name)
        if matches:
            if not re.fullmatch(r'[ \t]*' + name + r'[ \t]*=[ \t]*(true|false)[ \t]*(?:#.*)?', matches[0].group()):
                raise RuntimeError('Expected a literal boolean GN argument: ' + name)
            arguments = assignment.sub(name + ' = ' + value, arguments)
        else:
            arguments = arguments.rstrip() + '\n' + name + ' = ' + value + '\n'
    return arguments


def run(command, log, env):
    with log.open('a') as stream:
        stream.write('\n' + json.dumps(command) + '\n')
        stream.flush()
        process = subprocess.Popen(command, cwd=SOURCE, env=env, stdout=stream,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            while process.poll() is None:
                space()
                time.sleep(5)
            if process.returncode:
                raise RuntimeError(f'Command exited {process.returncode}; see {log}')
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            raise


def main():
    global BUILD_ROOT, SOURCE, OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Pinned Chromium src checkout')
    parser.add_argument('--build-root', type=Path, required=True, help='Separate existing build workspace')
    parser.add_argument('--output-directory', default='out/LanduBaseline')
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--build', action='store_true', help='Without this, validate only')
    parser.add_argument('--v8-release', action='store_true',
                        help='Rebuild V8 without internal development checks; preserve public memory accounting checks')
    parser.add_argument('--jobs', type=int, choices=(1, 2), default=1,
                        help='Local compile parallelism; keep 1 for native rebuilds')
    args = parser.parse_args()
    if not sys.platform.startswith('linux') or os.geteuid() == 0:
        raise RuntimeError('Use a non-root Linux build account')
    SOURCE = args.source.resolve(strict=True)
    BUILD_ROOT = args.build_root.resolve(strict=True)
    if not re.fullmatch(r'out/[A-Za-z0-9_.-]+', args.output_directory):
        raise RuntimeError('Output directory must be out/<single folder name>')
    OUT = SOURCE / args.output_directory
    spec = importlib.util.spec_from_file_location('landu_apply_overlay', ROOT / 'scripts/apply-overlay.py')
    overlay = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(overlay)
    BUILD_ROOT = overlay.external_directory(str(BUILD_ROOT), (SOURCE, ROOT))
    for name in ('logs', 'exports'):
        if not (BUILD_ROOT / name).is_dir():
            raise RuntimeError('Build workspace must contain logs/ and exports/')
    stage = overlay.external_directory(str(args.stage), (SOURCE, ROOT))
    lock = json.loads((ROOT / 'upstream-lock.json').read_text())
    overlay.verify_revision(SOURCE, lock['revision'])
    # Keep the same lock across validation, application, GN generation and linking.
    # The upstream builder cannot race this handoff or reset a Landu checkout.
    with overlay.build_lock(SOURCE, required=True):
        validated = overlay.validate_stage(SOURCE, stage, args.manifest_sha256, ROOT, lock)
        if validated['state'] != 'applied':
            baseline = json.loads((BUILD_ROOT / 'logs/chromium-status.json').read_text())
            if baseline.get('stage') != 'complete' or baseline.get('state') != 'success':
                raise RuntimeError('Finish the upstream baseline before applying Landu changes')
            artifact = baseline.get('artifact', '')
            if not artifact or Path(artifact).name != artifact:
                raise RuntimeError('Invalid baseline artifact receipt')
            if digest(BUILD_ROOT / 'exports' / artifact) != baseline.get('sha256'):
                raise RuntimeError('Baseline APK does not match its successful build receipt')
        space()
        if not args.build:
            print(json.dumps({'ready': True, 'state': validated['state'], 'sourceModified': False, 'apkBuilt': False}))
            return
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        log = BUILD_ROOT / 'logs' / ('landu-apk-' + stamp + '.log')
        receipt = BUILD_ROOT / 'logs' / ('landu-apk-' + stamp + '.json')
        state = {'kind': 'landu-native-development-apk-build', 'stageManifestSha256': args.manifest_sha256,
                 'nativeRoot': str(ROOT), 'buildDriverSha256': digest(Path(__file__)),
                 'log': str(log), 'jobs': args.jobs, 'apkBuilt': False, 'deviceTested': False, 'releaseReady': False}
        def save():
            temporary = receipt.with_suffix('.tmp')
            temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')
            temporary.replace(receipt)
        try:
            state['state'] = 'applying'; save()
            parent = BUILD_ROOT / 'exports/native-backups'
            parent.mkdir(exist_ok=True)
            state['overlay'] = overlay.apply_stage(SOURCE, stage, validated, parent)
            state['state'] = 'building'; save()
            env = dict(os.environ, PYTHONDONTWRITEBYTECODE='1',
                       JAVA_HOME=str(SOURCE / 'third_party/jdk/current'))
            # The SDK apksigner shell script invokes `java` from PATH, not JAVA_HOME.
            env['PATH'] = str(SOURCE / 'third_party/jdk/current/bin') + os.pathsep + env.get('PATH', '')
            arguments = OUT / 'args.gn'
            if args.v8_release:
                original = arguments.read_text()
                updated = v8_release_arguments(original)
                (BUILD_ROOT / 'logs' / ('landu-args-before-' + stamp + '.gn')).write_text(original)
                temporary = arguments.with_suffix('.gn.tmp')
                temporary.write_text(updated)
                temporary.replace(arguments)
            state['gnArguments'] = arguments.read_text()
            state['v8ReleaseRequested'] = args.v8_release
            save()
            run(['gn', 'gen', str(OUT), '--fail-on-unused-args'], log, env)
            run(['autoninja', '-C', str(OUT), '-j', str(args.jobs), 'chrome_public_apk'], log, env)
            if args.v8_release:
                configuration = json.loads((OUT / 'v8_build_config.json').read_text())
                for name in ('dcheck_always_on', 'DEBUG_defined', 'debug_code', 'verification_features'):
                    if configuration.get(name) is not False:
                        raise RuntimeError('V8 release configuration was not built: ' + name)
                state['v8BuildConfiguration'] = configuration
            apk = OUT / 'apks/ChromePublic.apk'
            sdk = SOURCE / 'third_party/android_sdk/public/build-tools/37.0.0'
            badging = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
            if not re.search(r"^package: name='com\.landu\.browser'", badging, re.M):
                raise RuntimeError('APK package is not com.landu.browser')
            with zipfile.ZipFile(apk) as package:
                libraries = [item for item in package.infolist()
                             if item.filename.startswith('lib/arm64-v8a/') and item.filename.endswith('.so')]
                if not libraries or sum(item.file_size for item in libraries) < 20 * 1024 ** 2:
                    raise RuntimeError('APK has no substantial bundled arm64 native engine')
                if package.testzip() is not None:
                    raise RuntimeError('APK ZIP integrity check failed')
            signing = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--verbose',
                                               '--print-certs', str(apk)], env=env, text=True)
            target = BUILD_ROOT / 'exports' / ('LanduBrowser-native-arm64-development-' + stamp + '.apk')
            temporary = target.with_suffix('.apk.partial')
            shutil.copy2(apk, temporary)
            if digest(temporary) != digest(apk):
                raise RuntimeError('Exported APK hash mismatch')
            temporary.replace(target)
            state.update(state='success', apkBuilt=True, artifact=str(target), sha256=digest(target),
                         bytes=target.stat().st_size, badging=badging, signatureVerification=signing)
            save(); print(json.dumps({'receipt': str(receipt), 'artifact': str(target), 'deviceTested': False}))
        except BaseException as error:
            state.update(state='stopped', error=str(error)); save(); raise


if __name__ == '__main__':
    def interrupted(signum, frame):
        raise InterruptedError(f'Interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
