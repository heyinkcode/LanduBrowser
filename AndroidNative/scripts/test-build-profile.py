"""Check the scoped V8 configuration without changing a Chromium checkout."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('native_build', Path(__file__).with_name('build-native-apk.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class V8BuildProfileTest(unittest.TestCase):
    def test_append_preserves_other_arguments(self):
        original = 'target_cpu = "arm64"\ndcheck_always_on = true\n'
        updated = module.v8_release_arguments(original)
        self.assertTrue(updated.startswith(original))
        self.assertIn('v8_dcheck_always_on = false\n', updated)
        self.assertIn('v8_enable_memory_accounting_checks = true\n', updated)

    def test_replace_and_repeat(self):
        original = '# existing build\nv8_dcheck_always_on = true # development\nv8_enable_memory_accounting_checks = false\n'
        updated = module.v8_release_arguments(original)
        self.assertEqual(updated.count('v8_dcheck_always_on ='), 1)
        self.assertEqual(updated.count('v8_enable_memory_accounting_checks ='), 1)
        self.assertEqual(module.v8_release_arguments(updated), updated)
        self.assertTrue(updated.startswith('# existing build\n'))

    def test_duplicate_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'Duplicate GN argument'):
            module.v8_release_arguments('v8_dcheck_always_on = true\nv8_dcheck_always_on = false\n')

    def test_expression_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'literal boolean'):
            module.v8_release_arguments('v8_dcheck_always_on = is_debug || dcheck_always_on\n')


if __name__ == '__main__':
    unittest.main()
