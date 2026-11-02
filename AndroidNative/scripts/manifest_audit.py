"""Release boundary checks for the actual merged manifest, including library entries."""
import collections

ANDROID = '{http://schemas.android.com/apk/res/android}'
COMPONENTS = {'activity', 'activity-alias', 'service', 'receiver', 'provider'}


def audit_manifest(root, policy):
    app = root.find('application')
    if root.get('package') != 'com.landu.browser' or app is None:
        raise ValueError('Expected merged Landu application')
    if app.get(ANDROID + 'allowBackup') != 'false' or app.get(ANDROID + 'fullBackupContent') != 'false':
        raise ValueError('Environment data must not use global application backup')
    by_name = {}
    for component in app:
        if component.tag not in COMPONENTS:
            continue
        name = component.get(ANDROID + 'name')
        if not name or name in by_name:
            raise ValueError('Merged component has missing or duplicate name')
        by_name[name] = component

    allowed = policy['allowedExternalAliases']
    exported = []
    for name, component in by_name.items():
        value = component.get(ANDROID + 'exported')
        # Missing exported on an entry with intent filters is not a private entry.
        # Android 12+ rejects such declarations; never silently pass them here.
        if value not in (None, 'true', 'false') or (value is None and component.findall('intent-filter')):
            raise ValueError('Missing or dynamic exported attribute: ' + name)
        if value == 'true':
            exported.append(name)
            if name not in allowed:
                raise ValueError('Unreviewed external component: ' + name)
    if set(exported) != set(allowed):
        raise ValueError('Expected exactly the two manager aliases')
    for name, target in allowed.items():
        alias = by_name[name]
        activity = by_name.get(target)
        if (alias.tag != 'activity-alias' or alias.get(ANDROID + 'targetActivity') != target
                or alias.get(ANDROID + 'enabled') == 'false' or activity is None
                or activity.tag != 'activity' or activity.get(ANDROID + 'exported') != 'false'
                or activity.get(ANDROID + 'process') != ':landu_manager'
                or activity.get(ANDROID + 'enabled') == 'false'
                or alias.get(ANDROID + 'process') not in (None, ':landu_manager')):
            raise ValueError('External alias does not terminate in the manager process: ' + name)
    restricted = policy['privateComponents'] + policy['libraryOverrides']
    for entry in restricted:
        component = by_name.get(entry['name'])
        if (component is None or component.tag != entry['tag']
                or component.get(ANDROID + 'exported') != 'false'
                or (entry.get('disable') and component.get(ANDROID + 'enabled') != 'false')):
            raise ValueError('External restriction missing after library merge: ' + entry['name'])
    browser = by_name.get('org.chromium.chrome.browser.ChromeTabbedActivity')
    if browser is None or browser.get(ANDROID + 'exported') != 'false':
        raise ValueError('Direct browser activity must remain private')
    for entry in policy['privateNotificationRoutes']:
        component = by_name.get(entry['name'])
        if (component is None or component.tag != 'activity'
                or component.get(ANDROID + 'exported') != 'false'
                or component.get(ANDROID + 'process') != entry['process']
                or component.get(ANDROID + 'enabled') == 'false'
                or component.findall('intent-filter')):
            raise ValueError('Notification handoff must stay private in its assigned process: ' + entry['name'])
    for entry in policy['privateNotificationBackgroundRoutes']:
        component = by_name.get(entry['name'])
        if (component is None or component.tag != entry['tag']
                or component.get(ANDROID + 'exported') != 'false'
                or component.get(ANDROID + 'process') != entry['process']
                or component.get(ANDROID + 'permission') != entry['permission']
                or component.get(ANDROID + 'enabled') == 'false'
                or component.findall('intent-filter')):
            raise ValueError('Notification background route boundary changed: ' + entry['name'])
    return {
        'externalComponentCount': len(exported), 'externalAliases': dict(allowed),
        'restrictedComponentCount': len(restricted),
        'disabledRegistrationCount': sum(bool(entry.get('disable')) for entry in restricted),
        'componentCounts': dict(collections.Counter(item.tag for item in by_name.values())),
        'privateNotificationRoutesChecked': len(policy['privateNotificationRoutes']),
        'privateNotificationBackgroundRoutesChecked': len(policy['privateNotificationBackgroundRoutes']),
        'backgroundJobsAudited': False, 'androidRuntimeTested': False,
    }
