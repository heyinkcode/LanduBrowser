#!/usr/bin/env python3
"""Derive Android vector resources from the approved repository SVG; no raster edits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET


NATIVE = Path(__file__).resolve().parents[1]
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
SVG = 'http://www.w3.org/2000/svg'
TOOLS = 'http://schemas.android.com/tools'
EXTERNAL_INPUTS = ('Browserapp/assets/logo-landu.svg', 'LICENSE')
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)
ET.register_namespace('tools', TOOLS)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def serialized(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def xml(root):
    ET.indent(root, space='  ')
    return b'<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding='utf-8') + b'\n'


def attrs(**items):
    return {f'{{{ANDROID}}}{name}': str(value) for name, value in items.items()}


def color(value, opacity='1'):
    if not re.fullmatch(r'#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?', value):
        raise ValueError('Unsupported logo color')
    raw = value[1:]
    if len(raw) == 3:
        raw = ''.join(c * 2 for c in raw)
    alpha = float(opacity)
    if not 0 <= alpha <= 1:
        raise ValueError('Invalid color opacity')
    return f'#{round(alpha * 255):02x}{raw.lower()}'


def parse_logo(data):
    root = ET.fromstring(data)
    if root.tag != f'{{{SVG}}}svg' or root.get('viewBox') != '0 0 512 512':
        raise ValueError('Expected the approved 512-unit SVG')
    allowed = {'svg': {'viewBox', 'width', 'height', 'role', 'aria-label'}, 'title': set(), 'defs': set(),
               'linearGradient': {'id', 'x1', 'y1', 'x2', 'y2'}, 'stop': {'offset', 'stop-color', 'stop-opacity'},
               'rect': {'x', 'y', 'width', 'height', 'rx', 'fill'}, 'path': {'d', 'fill'}}
    for node in root.iter():
        tag = node.tag.split('}')[-1]
        if node.tag != f'{{{SVG}}}{tag}' or tag not in allowed or set(node.attrib) - allowed[tag]:
            raise ValueError('Active or unsupported SVG content')
    rects = root.findall(f'{{{SVG}}}rect')
    paths = root.findall(f'{{{SVG}}}path')
    gradients = root.findall(f'{{{SVG}}}defs/{{{SVG}}}linearGradient')
    if len(rects) != 1 or len(paths) != 2 or len(gradients) != 2:
        raise ValueError('Logo geometry changed; review the Android vector adapter')
    rect = rects[0]
    if any(rect.get(k) != v for k, v in {'x': '24', 'y': '24', 'width': '464', 'height': '464', 'rx': '108', 'fill': 'url(#landu-blue)'}.items()):
        raise ValueError('Unexpected logo container')
    gradient_map = {}
    for node in gradients:
        name = node.get('id')
        expected = {'landu-blue': ('0', '0', '1', '1'), 'landu-wave': ('0', '0', '1', '0')}.get(name)
        if not expected or tuple(node.get(k) for k in ('x1', 'y1', 'x2', 'y2')) != expected:
            raise ValueError('Unsupported gradient coordinates')
        stops = []
        for stop in node.findall(f'{{{SVG}}}stop'):
            offset = float(stop.get('offset', '0'))
            if not 0 <= offset <= 1:
                raise ValueError('Invalid gradient stop')
            stops.append((offset, color(stop.get('stop-color', ''), stop.get('stop-opacity', '1'))))
        if len(stops) < 2 or stops[0][0] != 0 or stops[-1][0] != 1 or stops != sorted(stops):
            raise ValueError('Invalid gradient stop sequence')
        gradient_map[name] = stops
    if set(gradient_map) != {'landu-blue', 'landu-wave'}:
        raise ValueError('Duplicate or missing logo gradients')
    for node, fill in zip(paths, ('#fff', 'url(#landu-wave)')):
        if node.get('fill') != fill or not re.fullmatch(r'[MmZzLlHhVvCcSsQqTtAa0-9+.,eE\s-]{1,10000}', node.get('d', '')):
            raise ValueError('Unsupported foreground path')
    return [node.get('d') for node in paths], gradient_map


def vector():
    return ET.Element('vector', attrs(width='108dp', height='108dp', viewportWidth='512', viewportHeight='512'))


def gradient_path(parent, path, stops, start, end):
    element = ET.SubElement(parent, 'path', attrs(pathData=path))
    fill = ET.SubElement(element, f'{{{AAPT}}}attr', {'name': 'android:fillColor'})
    gradient = ET.SubElement(fill, 'gradient', attrs(type='linear', startX=start[0], startY=start[1], endX=end[0], endY=end[1]))
    for offset, value in stops:
        ET.SubElement(gradient, 'item', attrs(offset=f'{offset:g}', color=value))


def foreground(parent, paths, gradients, *, monochrome=False, scaled=False):
    group = ET.SubElement(parent, 'group', attrs(pivotX='256', pivotY='256', scaleX='.75', scaleY='.75')) if scaled else parent
    ET.SubElement(group, 'path', attrs(pathData=paths[0], fillColor='#ffffffff'))
    if monochrome:
        ET.SubElement(group, 'path', attrs(pathData=paths[1], fillColor='#ffffffff'))
    else:
        gradient_path(group, paths[1], gradients['landu-wave'], (108, 0), (299, 0))


def string_resources(values, curated_translation=False):
    root = ET.Element('resources')
    for name, value in values.items():
        element = ET.SubElement(root, 'string', {'name': name, 'translatable': 'false'})
        if curated_translation:
            # Product names have curated EN/zh-CN forms, not a general translation set.
            element.set(f'{{{TOOLS}}}ignore', 'ExtraTranslation')
        element.text = value.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"')
    return xml(root)


def build_assets(native=NATIVE):
    repo = native.parent
    inputs = {name: checked_file(repo, name).read_bytes() for name in EXTERNAL_INPUTS}
    if not inputs['LICENSE'].startswith(b'MIT License') or b'OpenBrowser' not in inputs['LICENSE']:
        raise ValueError('Expected the original OpenBrowser MIT notice')
    product = json.loads(checked_file(native, 'branding/product.json').read_text(encoding='utf-8'))
    keys = {'format', 'package', 'displayName', 'displayNameZhCN', 'projectUrl', 'upstreamUrl', 'website', 'free'}
    if set(product) != keys or product['format'] != 1 or product['free'] is not True or product['package'] != 'com.landu.browser':
        raise ValueError('Unexpected product identity; review package and distribution settings')
    for key in ('displayName', 'displayNameZhCN'):
        value = product[key]
        if not isinstance(value, str) or not value.strip() or len(value) > 60 or any(ord(c) < 32 for c in value):
            raise ValueError('Invalid product name')
    for key in ('projectUrl', 'upstreamUrl', 'website'):
        url = urlsplit(product[key])
        if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError('Invalid public product link')
    lock = json.loads(checked_file(native, 'upstream-lock.json').read_text(encoding='utf-8'))
    kernel_license = checked_file(native, 'branding/CHROMIUM-LICENSE.txt').read_bytes()
    if digest(kernel_license) != lock.get('behaviorFiles', {}).get('LICENSE'):
        raise ValueError('Chromium notice differs from the pinned source license')
    paths, gradients = parse_logo(inputs['Browserapp/assets/logo-landu.svg'])
    outputs = {}
    legacy = vector()
    rounded = 'M132,24 H380 A108,108 0 0,1 488,132 V380 A108,108 0 0,1 380,488 H132 A108,108 0 0,1 24,380 V132 A108,108 0 0,1 132,24 Z'
    gradient_path(legacy, rounded, gradients['landu-blue'], (24, 24), (488, 488))
    foreground(legacy, paths, gradients)
    for name in ('ic_launcher', 'ic_launcher_round'):
        outputs[f'res/drawable-anydpi/{name}.xml'] = xml(legacy)
    background = vector()
    # Referenced by adaptive icon variants in the final resource overlay.
    background.set(f'{{{TOOLS}}}ignore', 'UnusedResources')
    gradient_path(background, 'M0,0 H512 V512 H0 Z', gradients['landu-blue'], (0, 0), (512, 512))
    outputs['res/drawable/landu_launcher_background.xml'] = xml(background)
    for name, mono in (('landu_launcher_foreground', False), ('landu_launcher_monochrome', True), ('themed_app_icon', True)):
        root = vector(); foreground(root, paths, gradients, monochrome=mono, scaled=True)
        if name != 'themed_app_icon':
            root.set(f'{{{TOOLS}}}ignore', 'UnusedResources')
        outputs[f'res/drawable/{name}.xml'] = xml(root)
    for api in (26, 33):
        adaptive = ET.Element('adaptive-icon')
        if api == 26:
            # Android 13+ receives the separate v33 monochrome variant below.
            adaptive.set(f'{{{TOOLS}}}ignore', 'MonochromeLauncherIcon')
        ET.SubElement(adaptive, 'background', attrs(drawable='@drawable/landu_launcher_background'))
        ET.SubElement(adaptive, 'foreground', attrs(drawable='@drawable/landu_launcher_foreground'))
        if api == 33:
            ET.SubElement(adaptive, 'monochrome', attrs(drawable='@drawable/landu_launcher_monochrome'))
        for name in ('ic_launcher', 'ic_launcher_round'):
            outputs[f'res/drawable-anydpi-v{api}/{name}.xml'] = xml(adaptive)
    for qualifier, name in (('values', product['displayName']), ('values-zh-rCN', product['displayNameZhCN'])):
        outputs[f'res/{qualifier}/landu_brand.xml'] = string_resources({
            'app_name': name, 'bookmark_widget_title': name + (' 书签' if qualifier != 'values' else ' bookmarks'),
            'search_widget_title': name + (' 搜索' if qualifier != 'values' else ' search'),
            'quick_action_search_widget_title': name + (' 快捷搜索' if qualifier != 'values' else ' quick action search'),
            'landu_import_complete': '导入完成：环境新增 %1$d、更新 %2$d、跳过 %3$d；新增分组 %4$d%5$s',
            'landu_import_next_launch': '。运行中的环境配置在下次启动后生效。',
            'landu_period': '。',
            'landu_export_complete': '已导出 %1$d 个环境、%2$d 个分组的配置',
            'landu_status': '%1$s%2$s',
            'landu_choice': '%1$s  ▾',
            'landu_cpu_count': '%1$d 核',
            'landu_memory_count': '%1$d GB',
            'landu_notification_switch': '此通知属于「%1$s」。「%2$s」正在运行，保存并关闭后即可打开通知。',
            'landu_notification_pending': '「%1$s」正在等待启动。取消该待启动请求后，打开「%2$s」的通知。',
            'landu_totp_empty': '填写有效 2FA 密钥后显示验证码',
            'landu_totp_countdown': '%1$d 秒后更新 · %2$d 位',
            'landu_totp_invalid': '请检查 2FA 密钥格式和手机时间',
            'landu_cookie_importing': '正在校验、导入并核对 Cookie…',
            'landu_cookie_exporting': '正在读取此环境 Cookie…',
            'landu_cookie_import_complete': '已导入并读回核对 %1$d 项 Cookie',
            'landu_cookie_export_complete': '已导出并读回核对 %1$d 项 Cookie',
            'landu_cookie_export_skipped_complete': '已导出并读回核对 %1$d 项 Cookie；%2$d 项临时分区 Cookie 无法持久化，已跳过',
        }, qualifier != 'values')
    outputs['assets/OPENBROWSER-LICENSE.txt'] = inputs['LICENSE']
    outputs['assets/CHROMIUM-LICENSE.txt'] = kernel_license
    outputs['assets/build-info.json'] = serialized({'format': 1, **product, 'kernelVersion': lock['version'],
                                                    'kernelRevision': lock['revision'], 'kind': 'bundled-source-reference'})
    manifest = {'format': 1, 'kind': 'landu-android-brand-assets',
                'inputs': [{'path': name, 'sha256': digest(data)} for name, data in inputs.items()],
                'outputs': [{'path': f'branding/generated/{name}', 'sha256': digest(data), 'bytes': len(data)}
                            for name, data in sorted(outputs.items())]}
    return {f'branding/generated/{name}': data for name, data in outputs.items()}, manifest


def checked_file(native, name):
    if name.startswith('/') or '\\' in name or ':' in name or any(x in ('', '.', '..') for x in name.split('/')):
        raise ValueError('Unsafe asset path')
    path = native
    for part in name.split('/'):
        path = path / part
        if path.is_symlink():
            raise ValueError('Linked asset files are not allowed')
    if path.exists() and not path.is_file():
        raise ValueError('Asset target is not a regular file')
    return path


def safe_target(native, name):
    if not name.startswith('branding/generated/'):
        raise ValueError('Unsafe generated asset path')
    return checked_file(native, name)


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix='.landu-brand-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data); output.flush(); os.fsync(output.fileno())
        os.replace(temp, path)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def check_assets(native=NATIVE):
    outputs, manifest = build_assets(native)
    for name, data in outputs.items():
        if safe_target(native, name).read_bytes() != data:
            raise ValueError('Stale generated brand asset: ' + name)
    if checked_file(native, 'branding/asset-manifest.json').read_bytes() != serialized(manifest):
        raise ValueError('Stale brand asset manifest')
    return manifest


def write_assets(native=NATIVE):
    outputs, manifest = build_assets(native)
    manifest_path = checked_file(native, 'branding/asset-manifest.json')
    previous = json.loads(manifest_path.read_bytes()) if manifest_path.exists() else {'outputs': []}
    known = {item['path']: item['sha256'] for item in previous['outputs']}
    for name, data in outputs.items():
        target = safe_target(native, name)
        if target.exists() and target.read_bytes() != data and digest(target.read_bytes()) != known.get(name):
            raise ValueError('Generated asset has local edits; preserved: ' + name)
    for name, data in outputs.items():
        target = safe_target(native, name)
        if not target.exists() or target.read_bytes() != data:
            atomic_write(target, data)
    atomic_write(manifest_path, serialized(manifest))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--write', action='store_true')
    group.add_argument('--check', action='store_true')
    args = parser.parse_args()
    manifest = write_assets() if args.write else check_assets()
    print(json.dumps({'checked': True, 'files': len(manifest['outputs']), 'apkBuilt': False}))


if __name__ == '__main__':
    main()
