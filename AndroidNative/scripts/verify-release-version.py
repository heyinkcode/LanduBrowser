#!/usr/bin/env python3
"""Verify a release APK has the configured app identity and a newer Android code."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apk', type=Path, required=True)
parser.add_argument('--aapt2', type=Path, required=True)
parser.add_argument('--previous-version-code', type=int, required=True)
args = parser.parse_args()
native = Path(__file__).resolve().parents[1]
product = json.loads((native / 'branding/product.json').read_text())
kernel = json.loads((native / 'upstream-lock.json').read_text())['version']
badging = subprocess.check_output([str(args.aapt2), 'dump', 'badging', str(args.apk)], text=True)
match = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
if not match:
    raise SystemExit('Cannot read APK version identity')
package, code, version = match.groups()
if package != product['package'] or version != product['releaseVersion'] or int(code) != product['androidVersionCode']:
    raise SystemExit('APK version differs from the configured Landu release')
if int(code) <= args.previous_version_code:
    raise SystemExit('Android versionCode must increase for every published APK')
with zipfile.ZipFile(args.apk) as archive:
    info = json.loads(archive.read('assets/landu/build-info.json'))
if (info.get('releaseVersion') != version or info.get('androidVersionCode') != int(code)
        or info.get('kernelVersion') != kernel):
    raise SystemExit('Packaged application/kernel version metadata does not match')
print(json.dumps({'passed': True, 'package': package, 'versionName': version, 'versionCode': int(code),
                  'previousVersionCode': args.previous_version_code, 'kernelVersion': kernel}))
