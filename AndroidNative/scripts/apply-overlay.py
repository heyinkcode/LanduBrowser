#!/usr/bin/env python3
"""Pinned Chromium overlay transaction. Check is the default; never builds an APK."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tempfile


NATIVE_ROOT = Path(__file__).resolve().parents[1]
MARKER = '.landu-native-overlay.json'
BUILDERS = ('scripts/overlay-plan.mjs', 'scripts/stage-overlay.mjs',
            'scripts/manifest-policy.mjs', 'manifest-component-policy.json',
            'upstream-lock.json', 'overlay-inventory.json', 'translation-dependencies.json', 'scripts/fetch-translation-deps.py')
BRAND_BUILDERS = ('scripts/generate-brand-assets.py', 'branding/product.json',
                  'branding/asset-manifest.json', 'branding/CHROMIUM-LICENSE.txt')
EXTERNAL_BRAND_INPUTS = ('Browserapp/assets/logo-landu.svg', 'LICENSE')


class OverlayError(RuntimeError):
    pass


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()


def json_bytes(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise OverlayError(f'Duplicate JSON key: {key}')
            result[key] = value
        return result
    try:
        value = json.loads(data, object_pairs_hook=unique)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise OverlayError('Invalid JSON input') from error
    if not isinstance(value, dict):
        raise OverlayError('JSON input must be an object')
    return value


def relative(value):
    if (not isinstance(value, str) or not value or '\\' in value or ':' in value
            or any(ord(c) < 32 for c in value) or value.startswith('/')
            or any(x in ('', '.', '..', '.git') for x in value.split('/'))):
        raise OverlayError(f'Unsafe relative path: {value!r}')
    if str(PurePosixPath(value)) != value or value == MARKER:
        raise OverlayError(f'Reserved or noncanonical path: {value}')
    return value


def safe_file(root, name, *, marker=False):
    if not (marker and name == MARKER):
        relative(name)
    target = root / name
    cursor = root
    for segment in target.relative_to(root).parts:
        cursor = cursor / segment
        if cursor.is_symlink():
            raise OverlayError(f'Refusing symlink: {cursor}')
        if cursor != target and cursor.exists() and not cursor.is_dir():
            raise OverlayError(f'Parent is not a directory: {cursor}')
    if target.exists() and not target.is_file():
        raise OverlayError(f'Not a regular file: {target}')
    return target


def digest_at(root, name, *, marker=False):
    target = safe_file(root, name, marker=marker)
    return sha(target.read_bytes()) if target.exists() else None


def external_directory(value, excluded):
    original = Path(value).absolute()
    if original.is_symlink():
        raise OverlayError('Directory must not be a symlink')
    result = original.resolve(strict=True)
    if not result.is_dir() or re.match(r'(?i)^(?:c:[\\/]|/mnt/c(?:/|$))', str(result)):
        raise OverlayError('Use an existing dedicated directory outside drive C')
    for root in excluded:
        if result == root or result in root.parents or root in result.parents:
            raise OverlayError(f'Directory overlaps protected root: {root}')
    return result


def inventory(native_root, lock):
    spec = json_bytes((native_root / 'overlay-inventory.json').read_bytes())
    if spec.get('format') != 1 or spec.get('packagePath') != 'com/landu/browser/nativecore':
        raise OverlayError('Unsupported overlay inventory')
    expected = {relative(p): {'original': h, 'local': None} for p, h in lock['files'].items()}
    additions = [(f'{folder}/{spec["packagePath"]}/{name}',
                  f'chrome/android/java/src/{spec["packagePath"]}/{name}')
                 for folder, name in spec['javaFiles']] + spec['nativeFiles'] + spec.get('resourceFiles', [])
    for local, target in additions:
        relative(local)
        relative(target)
        if target in expected:
            raise OverlayError(f'Duplicate overlay target: {target}')
        expected[target] = {'original': None, 'local': local}
    return expected


def indexed(entries, names, label):
    if not isinstance(entries, list):
        raise OverlayError(f'Missing {label}')
    result = {}
    for item in entries:
        name = relative(item['path'])
        if name in result:
            raise OverlayError(f'Duplicate {label} path: {name}')
        result[name] = item
    if set(result) != set(names):
        raise OverlayError(f'{label} does not match the exact allowed paths')
    return result


def verify_revision(source, revision):
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != revision:
        raise OverlayError(f'Expected pinned revision {revision}, found {actual}')


def validate_stage(source, stage, manifest_sha, native_root, lock):
    raw = safe_file(stage, 'manifest.json').read_bytes()
    if sha(raw) != manifest_sha:
        raise OverlayError('Stage manifest hash mismatch')
    manifest = json_bytes(raw)
    if (manifest.get('format') != 1 or manifest.get('revision') != lock['revision']
            or manifest.get('version') != lock['version']
            or manifest.get('kind') != 'landu-native-integration-stage-not-an-apk'):
        raise OverlayError('Stage version or kind mismatch')
    spec = json_bytes((native_root / 'overlay-inventory.json').read_bytes())
    branded = bool(spec.get('resourceFiles'))
    builders = indexed(manifest.get('builderInputs'), BUILDERS + (BRAND_BUILDERS if branded else ()), 'builder inputs')
    for name, item in builders.items():
        if digest_at(native_root, name) != item['sha256']:
            raise OverlayError(f'Stale patch builder input: {name}')
    if branded:
        brand = json_bytes(safe_file(native_root, 'branding/asset-manifest.json').read_bytes())
        external = indexed(manifest.get('externalInputs'), EXTERNAL_BRAND_INPUTS, 'external brand inputs')
        reviewed = indexed(brand.get('inputs'), EXTERNAL_BRAND_INPUTS, 'reviewed brand inputs')
        for name in EXTERNAL_BRAND_INPUTS:
            if (external[name]['sha256'] != reviewed[name]['sha256']
                    or digest_at(native_root.parent, name) != external[name]['sha256']):
                raise OverlayError(f'Stale external brand source: {name}')
        if digest_at(native_root, 'branding/CHROMIUM-LICENSE.txt') != lock.get('behaviorFiles', {}).get('LICENSE'):
            raise OverlayError('Chromium notice differs from the pinned source')
    behavior = indexed(manifest.get('behaviorSources'), lock.get('behaviorFiles', {}), 'behavior sources')
    for name, expected in lock.get('behaviorFiles', {}).items():
        if behavior[name]['sha256'] != expected or digest_at(source, name) != expected:
            raise OverlayError(f'Changed backend behavior source: {name}')
    expected_files = inventory(native_root, lock)
    entries = indexed(manifest.get('files'), expected_files, 'stage files')
    records = []
    states = []
    for name, expected in expected_files.items():
        item = entries[name]
        if item.get('originalSha256') != expected['original']:
            raise OverlayError(f'Original hash mismatch: {name}')
        payload = safe_file(stage, f'files/{name}').read_bytes()
        if sha(payload) != item['sha256'] or len(payload) != item['bytes']:
            raise OverlayError(f'Changed staged payload: {name}')
        if expected['local'] and digest_at(native_root, expected['local']) != item['sha256']:
            raise OverlayError(f'Stale integration source: {name}')
        current = digest_at(source, name)
        if current == expected['original']:
            states.append('baseline')
        elif current == item['sha256']:
            states.append('applied')
        else:
            raise OverlayError(f'Existing local modification or missing original: {name}')
        target = safe_file(source, name)
        mode = stat.S_IMODE(target.stat().st_mode) if target.exists() else 0o644
        if mode & ~0o777:
            raise OverlayError(f'Unsupported special file permissions: {name}')
        records.append({'path': name, 'originalSha256': expected['original'],
                        'sha256': item['sha256'], 'mode': mode, 'payload': payload})
    marker_hash = digest_at(source, MARKER, marker=True)
    if all(state == 'baseline' for state in states):
        if marker_hash is not None:
            raise OverlayError('Baseline has an unexpected overlay marker')
        state = 'baseline'
    elif all(state == 'applied' for state in states) and marker_hash is not None:
        marker = json_bytes((source / MARKER).read_bytes())
        if marker.get('stageManifestSha256') != manifest_sha or marker.get('revision') != lock['revision']:
            raise OverlayError('Applied marker belongs to another stage')
        state = 'applied'
    else:
        raise OverlayError('Mixed or incomplete overlay; restore its backup before applying')
    return {'state': state, 'records': records, 'manifest': manifest, 'raw': raw,
            'manifestSha256': manifest_sha}


@contextmanager
def build_lock(source, *, required):
    lock_path = source.parent.parent / 'native-build.lock'
    if lock_path.is_symlink() or not lock_path.is_file():
        raise OverlayError('Expected existing native-build.lock beside the Chromium work directory')
    with lock_path.open('rb') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            if required:
                raise OverlayError('Native build is running; leave its checkout unchanged')
            yield True
        else:
            try:
                yield False
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)


def sync_directory(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write(root, name, data, mode=0o644, *, marker=False):
    target = safe_file(root, name, marker=marker)
    target.parent.mkdir(parents=True, exist_ok=True)
    safe_file(root, name, marker=marker)
    fd, temporary = tempfile.mkstemp(prefix='.landu-write-', dir=target.parent)
    try:
        with os.fdopen(fd, 'wb') as handle:
            handle.write(data)
            handle.flush()
            os.fchmod(handle.fileno(), mode)
            os.fsync(handle.fileno())
        os.replace(temporary, target)
        sync_directory(target.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def restore_records(source, backup, records, marker_bytes):
    # Validate the whole set before the first write, then recheck each target.
    candidates = []
    for item in records:
        name = relative(item['path'])
        current = digest_at(source, name)
        if current not in (item['originalSha256'], item['sha256']):
            raise OverlayError(f'Restore would overwrite a local change: {name}')
        original = None
        if item['originalSha256'] is not None:
            original = safe_file(backup, f'originals/{name}').read_bytes()
            if sha(original) != item['originalSha256']:
                raise OverlayError(f'Backup content changed: {name}')
        candidates.append((item, current, original))
    marker_current = digest_at(source, MARKER, marker=True)
    if marker_current not in (None, sha(marker_bytes)):
        raise OverlayError('Restore would overwrite another overlay marker')
    for item, current, original in reversed(candidates):
        if digest_at(source, item['path']) != current:
            raise OverlayError(f'Source changed during restore: {item["path"]}')
        if current == item['originalSha256']:
            continue
        if original is None:
            target = safe_file(source, item['path'])
            target.unlink()
            sync_directory(target.parent)
        else:
            atomic_write(source, item['path'], original, item['mode'])
    if marker_current is not None:
        if digest_at(source, MARKER, marker=True) != marker_current:
            raise OverlayError('Overlay marker changed during restore')
        (source / MARKER).unlink()
        sync_directory(source)
    # Empty parent directories remain deliberately; unrelated content is never removed.


def apply_stage(source, stage, validated, backup_parent, *, writer=atomic_write):
    if validated['state'] == 'applied':
        return {'state': 'already-applied', 'sourceModified': False, 'apkBuilt': False}
    backup = Path(tempfile.mkdtemp(prefix='landu-overlay-backup-', dir=backup_parent))
    records = []
    for item in validated['records']:
        if digest_at(source, item['path']) != item['originalSha256']:
            raise OverlayError(f'Source changed before backup: {item["path"]}')
        if item['originalSha256'] is not None:
            original = safe_file(source, item['path']).read_bytes()
            if sha(original) != item['originalSha256']:
                raise OverlayError(f'Source changed while backing up: {item["path"]}')
            atomic_write(backup, f'originals/{item["path"]}', original, item['mode'])
        records.append({k: v for k, v in item.items() if k != 'payload'})
    marker_bytes = encoded({'format': 1, 'revision': validated['manifest']['revision'],
                            'stageManifestSha256': validated['manifestSha256'],
                            'backup': str(backup), 'kind': 'landu-overlay-applied-not-built'})
    backup_data = {'format': 1, 'source': str(source), 'revision': validated['manifest']['revision'],
                   'stageManifestSha256': validated['manifestSha256'],
                   'marker': json_bytes(marker_bytes), 'files': records}
    raw = encoded(backup_data)
    atomic_write(backup, 'stage-manifest.json', validated['raw'])
    atomic_write(backup, 'backup.json', raw)
    sync_directory(backup_parent)
    receipt = {'backup': str(backup), 'backupSha256': sha(raw)}
    print(json.dumps({'backupReady': True, **receipt}), flush=True)
    try:
        for item in validated['records']:
            if digest_at(source, item['path']) != item['originalSha256']:
                raise OverlayError(f'Source changed during apply: {item["path"]}')
            writer(source, item['path'], item['payload'], item['mode'])
            if digest_at(source, item['path']) != item['sha256']:
                raise OverlayError(f'Written content mismatch: {item["path"]}')
        for item in validated['records']:
            if digest_at(source, item['path']) != item['sha256']:
                raise OverlayError(f'Source changed before commit: {item["path"]}')
        if digest_at(source, MARKER, marker=True) is not None:
            raise OverlayError('Another overlay marker appeared during apply')
        writer(source, MARKER, marker_bytes, marker=True)
    except BaseException as error:
        try:
            restore_records(source, backup, records, marker_bytes)
        except BaseException as rollback_error:
            raise OverlayError(f'Apply failed: {error}; rollback stopped to preserve changes: '
                               f'{rollback_error}; use backup {backup}, SHA256 {sha(raw)}') from error
        raise OverlayError(f'Apply failed and was rolled back: {error}; backup {backup}') from error
    return {'state': 'applied-not-built', 'sourceModified': True, **receipt,
            'fileCount': len(records), 'apkBuilt': False, 'deviceTested': False}


def restore_backup(source, backup, backup_sha, native_root, lock):
    raw = safe_file(backup, 'backup.json').read_bytes()
    if sha(raw) != backup_sha:
        raise OverlayError('Backup manifest hash mismatch')
    data = json_bytes(raw)
    if (data.get('format') != 1 or data.get('source') != str(source)
            or data.get('revision') != lock['revision']):
        raise OverlayError('Backup belongs to another source or revision')
    expected = inventory(native_root, lock)
    entries = indexed(data.get('files'), expected, 'backup files')
    for name, item in entries.items():
        if (item.get('originalSha256') != expected[name]['original']
                or not re.fullmatch('[0-9a-f]{64}', item.get('sha256', ''))
                or not isinstance(item.get('mode'), int) or not 0 <= item['mode'] <= 0o777):
            raise OverlayError(f'Invalid backup record: {name}')
    marker = data['marker']
    if (marker.get('revision') != lock['revision'] or marker.get('backup') != str(backup)
            or marker.get('stageManifestSha256') != data.get('stageManifestSha256')
            or marker.get('kind') != 'landu-overlay-applied-not-built'):
        raise OverlayError('Invalid backup marker')
    stage_raw = safe_file(backup, 'stage-manifest.json').read_bytes()
    if sha(stage_raw) != data['stageManifestSha256']:
        raise OverlayError('Backup stage receipt changed')
    restore_records(source, backup, list(entries.values()), encoded(marker))
    return {'state': 'restored-baseline', 'sourceModified': True, 'apkBuilt': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True)
    parser.add_argument('--stage')
    parser.add_argument('--manifest-sha256')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-parent')
    parser.add_argument('--restore')
    parser.add_argument('--backup-sha256')
    options = parser.parse_args()
    source = Path(options.source).resolve(strict=True)
    if not source.is_dir() or re.match(r'(?i)^(?:c:[\\/]|/mnt/c(?:/|$))', str(source)):
        raise OverlayError('Use the non-C Chromium source directory')
    lock = json_bytes((NATIVE_ROOT / 'upstream-lock.json').read_bytes())
    verify_revision(source, lock['revision'])
    if options.restore:
        if options.apply or options.stage or options.manifest_sha256 or options.backup_parent or not options.backup_sha256:
            parser.error('Restore requires only --source, --restore and --backup-sha256')
        backup = external_directory(options.restore, (source, NATIVE_ROOT))
        with build_lock(source, required=True):
            result = restore_backup(source, backup, options.backup_sha256, NATIVE_ROOT, lock)
    else:
        if not options.stage or not options.manifest_sha256 or options.backup_sha256:
            parser.error('--stage and its --manifest-sha256 are required')
        if bool(options.backup_parent) != options.apply:
            parser.error('--apply requires --backup-parent; check mode does not create a backup')
        stage = external_directory(options.stage, (source, NATIVE_ROOT))
        with build_lock(source, required=options.apply) as busy:
            validated = validate_stage(source, stage, options.manifest_sha256, NATIVE_ROOT, lock)
            if options.apply:
                parent = external_directory(options.backup_parent, (source, stage, NATIVE_ROOT))
                result = apply_stage(source, stage, validated, parent)
            else:
                result = {'state': validated['state'], 'fileCount': len(validated['records']),
                          'buildBusy': busy, 'sourceModified': False, 'apkBuilt': False}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OverlayError, OSError, ValueError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        print(f'Overlay refused: {error}', file=sys.stderr)
        sys.exit(1)
