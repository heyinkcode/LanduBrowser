import copy
import importlib.util
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('landu_manifest_audit', ROOT / 'scripts/manifest_audit.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
POLICY = json.loads((ROOT / 'manifest-component-policy.json').read_text())
A = audit.ANDROID


def fixture():
    root = ET.Element('manifest', {'package': 'com.landu.browser'})
    app = ET.SubElement(root, 'application', {A + 'allowBackup': 'false', A + 'fullBackupContent': 'false'})
    for name, target in POLICY['allowedExternalAliases'].items():
        ET.SubElement(app, 'activity-alias', {A + 'name': name, A + 'targetActivity': target, A + 'exported': 'true'})
        ET.SubElement(app, 'activity', {A + 'name': target, A + 'process': ':landu_manager', A + 'exported': 'false'})
    for entry in POLICY['privateComponents'] + POLICY['libraryOverrides']:
        values = {A + 'name': entry['name'], A + 'exported': 'false'}
        if entry.get('disable'):
            values[A + 'enabled'] = 'false'
        ET.SubElement(app, entry['tag'], values)
    ET.SubElement(app, 'activity', {A + 'name': 'org.chromium.chrome.browser.ChromeTabbedActivity', A + 'exported': 'false'})
    for entry in POLICY['privateNotificationRoutes']:
        values = {A + 'name': entry['name'], A + 'exported': 'false'}
        if entry['process'] is not None:
            values[A + 'process'] = entry['process']
        ET.SubElement(app, 'activity', values)
    for entry in POLICY['privateNotificationBackgroundRoutes']:
        values = {A+'name':entry['name'], A+'exported':'false'}
        for attr in ['process', 'permission']:
            if entry[attr] is not None: values[A+attr] = entry[attr]
        ET.SubElement(app, entry['tag'], values)
    return root


class ManifestBoundaryTest(unittest.TestCase):
    def test_notification_background_route_process_and_system_binding(self):
        for entry in POLICY['privateNotificationBackgroundRoutes']:
            for attr, value in [('process', ':wrong'), ('exported', 'true'), ('enabled', 'false'), ('permission', 'wrong.permission')]:
                root = fixture()
                component = next(c for c in root.find('application') if c.get(A+'name') == entry['name'])
                component.set(A+attr, value)
                with self.subTest(name=entry['name'], attr=attr), self.assertRaises(ValueError):
                    audit.audit_manifest(root, POLICY)

    def test_notification_routes_cannot_start_in_another_process_or_be_exported(self):
        for entry in POLICY['privateNotificationRoutes']:
            for attr, value in [('process', ':unexpected'), ('exported', 'true'), ('enabled', 'false')]:
                root = fixture()
                component = next(c for c in root.find('application') if c.get(A + 'name') == entry['name'])
                component.set(A + attr, value)
                with self.subTest(name=entry['name'], attr=attr), self.assertRaises(ValueError):
                    audit.audit_manifest(root, POLICY)
            root = fixture()
            component = next(c for c in root.find('application') if c.get(A + 'name') == entry['name'])
            root.find('application').remove(component)
            with self.subTest(missing=entry['name']), self.assertRaises(ValueError):
                audit.audit_manifest(root, POLICY)

    def test_only_selected_manager_aliases_are_external(self):
        result = audit.audit_manifest(fixture(), POLICY)
        self.assertEqual(2, result['externalComponentCount'])
        self.assertFalse(result['backgroundJobsAudited'])
        self.assertFalse(result['androidRuntimeTested'])

    def test_a_library_cannot_add_an_exported_component_even_with_a_permission(self):
        for kind in audit.COMPONENTS:
            with self.subTest(kind=kind):
                root = fixture()
                ET.SubElement(root.find('application'), kind, {A + 'name': 'unexpected.Entry', A + 'exported': 'true', A + 'permission': 'signature.permission'})
                with self.assertRaises(ValueError):
                    audit.audit_manifest(root, POLICY)

    def test_implicit_export_from_filters_is_rejected(self):
        root = fixture()
        component = ET.SubElement(root.find('application'), 'service', {A + 'name': 'implicit.Service'})
        ET.SubElement(component, 'intent-filter')
        with self.assertRaises(ValueError):
            audit.audit_manifest(root, POLICY)

    def test_aliases_cannot_be_redirected_to_browser_or_another_process(self):
        for attr, value in [('targetActivity', 'org.chromium.chrome.browser.ChromeTabbedActivity'), ('process', ':other'), ('enabled', 'false')]:
            with self.subTest(attribute=attr):
                root = fixture()
                root.find('application/activity-alias').set(A + attr, value)
                with self.assertRaises(ValueError):
                    audit.audit_manifest(root, POLICY)
        root = fixture()
        root.find('application/activity').set(A + 'process', 'com.landu.browser')
        with self.assertRaises(ValueError):
            audit.audit_manifest(root, POLICY)

    def test_removed_reenabled_or_exported_restrictions_are_rejected(self):
        for entry in POLICY['privateComponents'] + POLICY['libraryOverrides']:
            root = fixture()
            component = next(item for item in root.find('application') if item.get(A + 'name') == entry['name'])
            component.set(A + 'exported', 'true')
            with self.subTest(name=entry['name']), self.assertRaises(ValueError):
                audit.audit_manifest(root, POLICY)
            if entry.get('disable'):
                component.set(A + 'exported', 'false')
                component.set(A + 'enabled', 'true')
                with self.subTest(reenabled=entry['name']), self.assertRaises(ValueError):
                    audit.audit_manifest(root, POLICY)

    def test_duplicate_names_missing_aliases_and_global_backup_are_rejected(self):
        for mutation in [lambda app: app.append(copy.deepcopy(app[0])), lambda app: app.remove(app[0]),
                         lambda app: app.set(A + 'allowBackup', 'true')]:
            root = fixture()
            mutation(root.find('application'))
            with self.assertRaises(ValueError):
                audit.audit_manifest(root, POLICY)


if __name__ == '__main__':
    unittest.main()
