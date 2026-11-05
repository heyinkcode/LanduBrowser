"""Verify product assets in disposable fixtures, without rendering or building Chromium."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

NATIVE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('landu_brand', NATIVE / 'scripts/generate-brand-assets.py')
brand = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(brand)
A = '{' + brand.ANDROID + '}'


class BrandAssetsTests(unittest.TestCase):
    def setUp(self):
        cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
        if str(cache).startswith('/mnt/c/'):
            raise RuntimeError('Use a non-C test cache')
        self.temp = tempfile.TemporaryDirectory(prefix='brand-fixture-', dir=cache)
        self.repo = Path(self.temp.name)
        self.native = self.repo / 'AndroidNative'
        for name in brand.EXTERNAL_INPUTS:
            self.write(self.repo / name, (NATIVE.parent / name).read_bytes())
        for name in ('branding/product.json', 'branding/CHROMIUM-LICENSE.txt', 'upstream-lock.json'):
            self.write(self.native / name, (NATIVE / name).read_bytes())

    def tearDown(self):
        self.temp.cleanup()

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def test_deterministic_resources_keep_approved_geometry_and_adaptive_safe_scale(self):
        outputs, manifest = brand.build_assets(self.native)
        self.assertEqual(len(outputs), 15)
        paths, _ = brand.parse_logo((self.repo / brand.EXTERNAL_INPUTS[0]).read_bytes())
        foreground = ET.fromstring(outputs['branding/generated/res/drawable/landu_launcher_foreground.xml'])
        group = foreground.find('group')
        self.assertEqual([group.get(A + n) for n in ('pivotX', 'pivotY', 'scaleX', 'scaleY')], ['256', '256', '.75', '.75'])
        self.assertEqual([p.get(A + 'pathData') for p in group.findall('path')], paths)
        for api in (26, 33):
            for name in ('ic_launcher', 'ic_launcher_round'):
                icon = ET.fromstring(outputs[f'branding/generated/res/drawable-anydpi-v{api}/{name}.xml'])
                self.assertEqual(icon.find('foreground').get(A + 'drawable'), '@drawable/landu_launcher_foreground')
                self.assertEqual(icon.find('monochrome') is not None, api == 33)
        for qualifier, expected in (('values', 'Landu Browser'), ('values-zh-rCN', '岚渡浏览器')):
            root = ET.fromstring(outputs[f'branding/generated/res/{qualifier}/landu_brand.xml'])
            self.assertEqual(root.find("string[@name='app_name']").text, expected)
        brand.write_assets(self.native)
        self.assertEqual(brand.check_assets(self.native), manifest)
        before = (self.native / 'branding/asset-manifest.json').read_bytes()
        brand.write_assets(self.native)
        self.assertEqual(before, (self.native / 'branding/asset-manifest.json').read_bytes())

    def test_exact_licenses_and_public_metadata_only(self):
        outputs, _ = brand.build_assets(self.native)
        self.assertEqual(outputs['branding/generated/assets/OPENBROWSER-LICENSE.txt'], (self.repo / 'LICENSE').read_bytes())
        self.assertEqual(outputs['branding/generated/assets/CHROMIUM-LICENSE.txt'], (self.native / 'branding/CHROMIUM-LICENSE.txt').read_bytes())
        raw = outputs['branding/generated/assets/build-info.json']
        info = json.loads(raw)
        self.assertTrue(info['free'])
        self.assertEqual(info['package'], 'com.landu.browser')
        self.assertEqual(info['kind'], 'bundled-source-reference')
        product = json.loads((self.native / 'branding/product.json').read_text())
        self.assertEqual(info['releaseVersion'], product['releaseVersion'])
        self.assertEqual(info['androidVersionCode'], product['androidVersionCode'])
        self.assertEqual(info['kernelVersion'], json.loads((self.native / 'upstream-lock.json').read_text())['version'])
        self.assertNotIn(str(self.repo).encode(), raw)
        self.assertNotIn(b'F:', raw)
        self.assertNotIn(b'C:', raw)

    def test_release_metadata_rejects_invalid_android_versions(self):
        path = self.native / 'branding/product.json'
        original = json.loads(path.read_text())
        for field, value in [('releaseVersion', '1.0.26-preview'),
                             ('releaseVersion', '1.0.4294967296'),
                             ('androidVersionCode', 0),
                             ('androidVersionCode', 2100000001),
                             ('androidVersionCode', True)]:
            product = dict(original)
            product[field] = value
            path.write_text(json.dumps(product))
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, 'Invalid Android release version'):
                brand.build_assets(self.native)

    def test_local_generated_edits_are_preserved_before_any_other_write(self):
        brand.write_assets(self.native)
        target = self.native / 'branding/generated/res/values/landu_brand.xml'
        target.write_bytes(b'user edit')
        old = (self.native / 'branding/asset-manifest.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'Stale generated'):
            brand.check_assets(self.native)
        with self.assertRaisesRegex(ValueError, 'local edits; preserved'):
            brand.write_assets(self.native)
        self.assertEqual(target.read_bytes(), b'user edit')
        self.assertEqual(old, (self.native / 'branding/asset-manifest.json').read_bytes())

    def test_source_drift_requires_regeneration_and_keeps_exact_current_notice(self):
        brand.write_assets(self.native)
        notice = (self.repo / 'LICENSE').read_bytes() + b'\nAdditional attribution\n'
        (self.repo / 'LICENSE').write_bytes(notice)
        with self.assertRaises(ValueError):
            brand.check_assets(self.native)
        brand.write_assets(self.native)
        brand.check_assets(self.native)
        self.assertEqual((self.native / 'branding/generated/assets/OPENBROWSER-LICENSE.txt').read_bytes(), notice)
        (self.native / 'branding/CHROMIUM-LICENSE.txt').write_bytes(b'changed notice')
        with self.assertRaisesRegex(ValueError, 'pinned source license'):
            brand.write_assets(self.native)

    def test_rejects_active_namespaced_or_transformed_svg(self):
        svg = (self.repo / brand.EXTERNAL_INPUTS[0]).read_bytes()
        for modified in (svg.replace(b'<path ', b'<path onclick="bad()" ', 1),
                         svg.replace(b'<path ', b'<path transform="scale(2)" ', 1),
                         svg.replace(b'<path ', b'<x:path xmlns:x="https://invalid.test/" ', 1),
                         svg.replace(b'</svg>', b'<script>bad()</script></svg>')):
            with self.subTest(svg=modified[-60:]), self.assertRaises(ValueError):
                brand.parse_logo(modified)

    def test_linked_inputs_outputs_and_manifest_are_rejected(self):
        brand.write_assets(self.native)
        for target in (self.repo / 'LICENSE', self.native / 'branding/asset-manifest.json',
                       self.native / 'branding/generated/res/values/landu_brand.xml'):
            original = target.read_bytes()
            outside = self.repo / 'unrelated.txt'
            outside.write_bytes(original)
            target.unlink()
            target.symlink_to(outside)
            with self.subTest(path=target), self.assertRaisesRegex(ValueError, 'Linked asset'):
                brand.write_assets(self.native)
            self.assertEqual(outside.read_bytes(), original)
            target.unlink()
            target.write_bytes(original)


if __name__ == '__main__':
    unittest.main(verbosity=2)
