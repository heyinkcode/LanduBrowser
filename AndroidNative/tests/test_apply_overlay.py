"""Data-preservation tests on disposable Linux fixtures, never the real checkout."""
import contextlib
import copy
import fcntl
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location('landu_overlay', Path(__file__).parents[1] / 'scripts/apply-overlay.py')
overlay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(overlay)


class OverlayTransactionTests(unittest.TestCase):
    def setUp(self):
        cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
        if str(cache).startswith('/mnt/c/'):
            raise RuntimeError('Tests require a non-C cache')
        self.temporary = tempfile.TemporaryDirectory(prefix='overlay-transaction-', dir=cache)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'work/chromium/src'
        self.stage = self.root / 'stage'
        self.native = self.root / 'native'
        self.backups = self.root / 'backups'
        for path in (self.source, self.stage, self.native, self.backups):
            path.mkdir(parents=True)
        self.lock_path = self.source.parent.parent / 'native-build.lock'
        self.lock_path.write_bytes(b'')
        self.original = b'original upstream\n'
        self.patched = b'patched upstream\n'
        self.new_path = 'chrome/android/java/src/com/landu/browser/nativecore/Test.java'
        self.local_path = 'java/com/landu/browser/nativecore/Test.java'
        self.lock = {'revision': '1' * 40, 'version': '1.2.3.4',
                     'files': {'chrome/one.cc': overlay.sha(self.original),
                               'chrome/two.cc': overlay.sha(self.original)},
                     'behaviorFiles': {'chrome/behavior.cc': overlay.sha(b'behavior\n')}}
        self.write(self.source, 'chrome/behavior.cc', b'behavior\n')
        self.write(self.source, 'unrelated-local.txt', b'user work\n')
        for name in self.lock['files']:
            self.write(self.source, name, self.original)
        (self.source / 'chrome/one.cc').chmod(0o640)
        inventory = {'format': 1, 'packagePath': 'com/landu/browser/nativecore',
                     'javaFiles': [['java', 'Test.java']], 'nativeFiles': []}
        self.write(self.native, 'overlay-inventory.json', overlay.encoded(inventory))
        self.write(self.native, 'upstream-lock.json', overlay.encoded(self.lock))
        for name in overlay.BUILDERS:
            if not (self.native / name).exists():
                self.write(self.native, name, b'fixture builder\n')
        self.write(self.native, self.local_path, b'new Java integration\n')
        self.manifest = {'format': 1, 'revision': self.lock['revision'], 'version': self.lock['version'],
                         'kind': 'landu-native-integration-stage-not-an-apk',
                         'builderInputs': [{'path': n, 'sha256': overlay.digest_at(self.native, n)}
                                           for n in overlay.BUILDERS],
                         'behaviorSources': [{'path': n, 'sha256': h}
                                             for n, h in self.lock['behaviorFiles'].items()], 'files': []}
        for name, original in [*self.lock['files'].items(), (self.new_path, None)]:
            payload = self.patched if original else (self.native / self.local_path).read_bytes()
            self.write(self.stage, f'files/{name}', payload)
            self.manifest['files'].append({'path': name, 'originalSha256': original,
                                           'sha256': overlay.sha(payload), 'bytes': len(payload)})
        self.seal()

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, root, name, data):
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def seal(self):
        raw = overlay.encoded(self.manifest)
        self.write(self.stage, 'manifest.json', raw)
        self.manifest_sha = overlay.sha(raw)

    def validate(self):
        return overlay.validate_stage(self.source, self.stage, self.manifest_sha, self.native, self.lock)

    def apply(self, *, writer=overlay.atomic_write):
        with contextlib.redirect_stdout(io.StringIO()):
            return overlay.apply_stage(self.source, self.stage, self.validate(), self.backups, writer=writer)

    def restore(self, receipt):
        return overlay.restore_backup(self.source, Path(receipt['backup']), receipt['backupSha256'],
                                      self.native, self.lock)

    def assert_baseline(self):
        for name in self.lock['files']:
            self.assertEqual((self.source / name).read_bytes(), self.original)
        self.assertFalse((self.source / self.new_path).exists())
        self.assertFalse((self.source / overlay.MARKER).exists())
        self.assertEqual((self.source / 'unrelated-local.txt').read_bytes(), b'user work\n')
        self.assertEqual((self.source / 'chrome/one.cc').stat().st_mode & 0o777, 0o640)

    def enable_branding(self):
        spec = json.loads((self.native / 'overlay-inventory.json').read_bytes())
        local = 'branding/generated/assets/OPENBROWSER-LICENSE.txt'
        target = 'chrome/android/landu/assets/OPENBROWSER-LICENSE.txt'
        spec['resourceFiles'] = [[local, target]]
        self.write(self.native, 'overlay-inventory.json', overlay.encoded(spec))
        for name in overlay.EXTERNAL_BRAND_INPUTS:
            self.write(self.native.parent, name, b'original brand input: ' + name.encode())
        external = [{'path': name, 'sha256': overlay.digest_at(self.native.parent, name)}
                    for name in overlay.EXTERNAL_BRAND_INPUTS]
        kernel_license = b'pinned Chromium notice\n'
        self.write(self.source, 'LICENSE', kernel_license)
        self.lock['behaviorFiles']['LICENSE'] = overlay.sha(kernel_license)
        self.write(self.native, 'upstream-lock.json', overlay.encoded(self.lock))
        self.write(self.native, 'branding/CHROMIUM-LICENSE.txt', kernel_license)
        self.write(self.native, 'branding/asset-manifest.json', overlay.encoded({'inputs': external}))
        for name in ('scripts/generate-brand-assets.py', 'branding/product.json'):
            self.write(self.native, name, b'fixture brand builder\n')
        data = (self.native.parent / 'LICENSE').read_bytes()
        self.write(self.native, local, data)
        self.write(self.stage, 'files/' + target, data)
        self.manifest['files'].append({'path': target, 'originalSha256': None,
                                       'sha256': overlay.sha(data), 'bytes': len(data)})
        self.manifest['externalInputs'] = external
        self.manifest['behaviorSources'] = [{'path': n, 'sha256': h} for n, h in self.lock['behaviorFiles'].items()]
        self.refresh_branded_builders()
        return target

    def refresh_branded_builders(self):
        self.manifest['builderInputs'] = [{'path': n, 'sha256': overlay.digest_at(self.native, n)}
                                         for n in overlay.BUILDERS + overlay.BRAND_BUILDERS]
        self.seal()

    def test_brand_resources_round_trip_and_notices_preserved(self):
        target = self.enable_branding()
        receipt = self.apply()
        self.assertEqual((self.source / target).read_bytes(), (self.native.parent / 'LICENSE').read_bytes())
        self.assertEqual(self.validate()['state'], 'applied')
        self.restore(receipt)
        self.assertFalse((self.source / target).exists())
        self.assert_baseline()

    def test_changed_external_logo_or_license_refuses_before_any_write(self):
        self.enable_branding()
        for name in overlay.EXTERNAL_BRAND_INPUTS:
            original = (self.native.parent / name).read_bytes()
            self.write(self.native.parent, name, b'user changed brand input')
            with self.subTest(path=name), self.assertRaisesRegex(overlay.OverlayError, 'Stale external brand source'):
                self.apply()
            self.assertEqual(list(self.backups.iterdir()), [])
            self.assert_baseline()
            self.write(self.native.parent, name, original)

    def test_missing_extra_or_changed_brand_receipts_are_refused(self):
        self.enable_branding()
        original = copy.deepcopy(self.manifest['externalInputs'])
        for entries in ([], original + [copy.deepcopy(original[0])],
                        [dict(original[0], sha256='0' * 64), original[1]],
                        original + [{'path': 'private/user.txt', 'sha256': '0' * 64}]):
            self.manifest['externalInputs'] = entries
            self.seal()
            with self.subTest(entries=entries), self.assertRaises(overlay.OverlayError):
                self.validate()
            self.assert_baseline()

    def test_resealed_chromium_notice_edit_still_fails_pinned_license_check(self):
        self.enable_branding()
        self.write(self.native, 'branding/CHROMIUM-LICENSE.txt', b'changed license')
        self.refresh_branded_builders()
        with self.assertRaisesRegex(overlay.OverlayError, 'Chromium notice differs'):
            self.apply()
        self.assert_baseline()

    def test_check_never_writes_source_or_creates_backup(self):
        self.assertEqual(self.validate()['state'], 'baseline')
        self.assertEqual(list(self.backups.iterdir()), [])
        self.assert_baseline()

    def test_apply_restore_preserves_unrelated_work_and_modes(self):
        receipt = self.apply()
        self.assertEqual(receipt['state'], 'applied-not-built')
        self.assertFalse(receipt['apkBuilt'])
        self.assertEqual(self.validate()['state'], 'applied')
        self.assertEqual(self.apply()['state'], 'already-applied')
        self.assertEqual(len(list(self.backups.iterdir())), 1)
        self.restore(receipt)
        self.assert_baseline()
        # A completed restore can be repeated without losing unrelated files.
        self.restore(receipt)
        self.assert_baseline()

    def test_original_local_changes_are_rejected_before_any_write(self):
        self.write(self.source, 'chrome/two.cc', b'user edit')
        with self.assertRaisesRegex(overlay.OverlayError, 'local modification'):
            self.apply()
        self.assertEqual(list(self.backups.iterdir()), [])
        self.assertEqual((self.source / 'chrome/one.cc').read_bytes(), self.original)

    def test_stale_native_builder_behavior_and_payload_are_rejected(self):
        for root, name in [(self.native, self.local_path),
                           *((self.native, item) for item in overlay.BUILDERS),
                           (self.source, 'chrome/behavior.cc'), (self.stage, 'files/chrome/one.cc')]:
            with self.subTest(path=name):
                original = (root / name).read_bytes()
                self.write(root, name, b'changed')
                try:
                    with self.assertRaises(overlay.OverlayError):
                        self.validate()
                finally:
                    self.write(root, name, original)
        self.assert_baseline()

    def test_malformed_inventory_json_is_a_controlled_read_only_refusal(self):
        target = self.native / 'overlay-inventory.json'
        original = target.read_bytes()
        for data in (b'changed', b'\xff', b'[]', b'null', b'42', b'{"format":1,"format":1}'):
            with self.subTest(data=data):
                target.write_bytes(data)
                try:
                    with self.assertRaises(overlay.OverlayError):
                        self.validate()
                    self.assert_baseline()
                finally:
                    target.write_bytes(original)

    def test_manifest_hash_and_duplicate_entries(self):
        raw = (self.stage / 'manifest.json').read_bytes()
        self.write(self.stage, 'manifest.json', raw + b' ')
        with self.assertRaisesRegex(overlay.OverlayError, 'manifest hash'):
            self.validate()
        self.write(self.stage, 'manifest.json', raw)
        self.manifest['files'].append(copy.deepcopy(self.manifest['files'][0]))
        self.seal()
        with self.assertRaisesRegex(overlay.OverlayError, 'Duplicate stage files'):
            self.validate()
        with self.assertRaisesRegex(overlay.OverlayError, 'Duplicate JSON key'):
            overlay.json_bytes(b'{"a":1,"a":2}')

    def test_unsafe_paths_and_missing_extra_targets(self):
        for name in ('../escape', '/absolute', 'a//b', 'a/./b', 'a/../b', 'a\\b', 'c:/cache',
                     'x/.git/config', overlay.MARKER, 'a\x00b'):
            with self.subTest(name=name), self.assertRaises(overlay.OverlayError):
                overlay.relative(name)
        saved = self.manifest['files'].pop()
        self.seal()
        with self.assertRaisesRegex(overlay.OverlayError, 'exact allowed paths'):
            self.validate()
        self.manifest['files'].append(saved)
        self.manifest['files'].append({'path': 'unrelated-local.txt', 'sha256': '0' * 64})
        self.seal()
        with self.assertRaisesRegex(overlay.OverlayError, 'exact allowed paths'):
            self.validate()

    def test_linked_source_or_stage_component_is_rejected(self):
        for root, name in ((self.source, 'chrome/one.cc'), (self.stage, 'files/chrome/one.cc')):
            with self.subTest(root=root):
                target = root / name
                data = target.read_bytes()
                target.unlink()
                target.symlink_to(self.source / 'unrelated-local.txt')
                with self.assertRaisesRegex(overlay.OverlayError, 'symlink'):
                    self.validate()
                target.unlink()
                target.write_bytes(data)
        outside = self.root / 'outside'
        outside.mkdir()
        (self.source / 'linked').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(overlay.OverlayError, 'symlink'):
            overlay.safe_file(self.source, 'linked/new.java')

    def test_busy_build_prevents_mutation_but_allows_read_only_check(self):
        with self.lock_path.open('rb') as held:
            fcntl.flock(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(overlay.OverlayError, 'build is running'):
                with overlay.build_lock(self.source, required=True):
                    self.fail('Entered a busy build')
            with overlay.build_lock(self.source, required=False) as busy:
                self.assertTrue(busy)
                self.assertEqual(self.validate()['state'], 'baseline')
        with overlay.build_lock(self.source, required=True) as busy:
            self.assertFalse(busy)
        self.assert_baseline()

    def test_failure_before_after_replace_and_marker_rolls_back(self):
        for fail_at, after in ((1, False), (2, False), (3, True), (4, True)):
            with self.subTest(fail_at=fail_at, after=after):
                count = 0
                def interrupted(*args, **kwargs):
                    nonlocal count
                    count += 1
                    if count == fail_at and not after:
                        raise OSError('injected write failure')
                    overlay.atomic_write(*args, **kwargs)
                    if count == fail_at:
                        raise OSError('injected post-replace failure')
                with self.assertRaisesRegex(overlay.OverlayError, 'was rolled back'):
                    self.apply(writer=interrupted)
                self.assert_baseline()

    def test_user_edit_during_failure_is_preserved_and_reported(self):
        def conflicted(root, name, data, *args, **kwargs):
            overlay.atomic_write(root, name, data, *args, **kwargs)
            self.write(self.source, name, b'concurrent user change')
            raise OSError('interrupted')
        with self.assertRaisesRegex(overlay.OverlayError, 'preserve changes'):
            self.apply(writer=conflicted)
        self.assertEqual((self.source / 'chrome/one.cc').read_bytes(), b'concurrent user change')
        self.assertEqual((self.source / 'chrome/two.cc').read_bytes(), self.original)

    def test_partial_interrupted_state_requires_explicit_restore(self):
        receipt = self.apply()
        self.write(self.source, 'chrome/one.cc', self.original)
        (self.source / self.new_path).unlink()
        (self.source / overlay.MARKER).unlink()
        with self.assertRaisesRegex(overlay.OverlayError, 'Mixed or incomplete'):
            self.validate()
        self.restore(receipt)
        self.assert_baseline()

    def test_restore_rejects_changes_or_corruption_before_any_write(self):
        receipt = self.apply()
        self.write(self.source, 'chrome/two.cc', b'user work after apply')
        with self.assertRaisesRegex(overlay.OverlayError, 'local change'):
            self.restore(receipt)
        self.assertEqual((self.source / 'chrome/one.cc').read_bytes(), self.patched)
        self.write(self.source, 'chrome/two.cc', self.patched)
        backup = Path(receipt['backup'])
        original = (backup / 'originals/chrome/one.cc').read_bytes()
        self.write(backup, 'originals/chrome/one.cc', b'corrupted')
        with self.assertRaisesRegex(overlay.OverlayError, 'Backup content changed'):
            self.restore(receipt)
        self.assertEqual((self.source / 'chrome/two.cc').read_bytes(), self.patched)
        self.write(backup, 'originals/chrome/one.cc', original)
        with self.assertRaisesRegex(overlay.OverlayError, 'Backup manifest hash'):
            overlay.restore_backup(self.source, backup, '0' * 64, self.native, self.lock)
        self.restore(receipt)
        self.assert_baseline()

    def test_backup_directory_cannot_overlap_source_stage_or_native(self):
        for path in (self.source, self.source / 'chrome', self.source.parent, self.stage, self.native):
            with self.subTest(path=path), self.assertRaises(overlay.OverlayError):
                overlay.external_directory(path, (self.source, self.stage, self.native))
        self.assertEqual(overlay.external_directory(self.backups, (self.source, self.stage, self.native)), self.backups)


if __name__ == '__main__':
    unittest.main(verbosity=2)
