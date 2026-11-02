#!/usr/bin/env python3
"""Run the actual staged provider body with platform protocol substitutes and ASan."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    native = Path(__file__).resolve().parents[1]
    stage = args.stage.resolve(strict=True)
    manifest = stage / 'manifest.json'
    entries = {item['path']: item['sha256'] for item in json.loads(manifest.read_text())['files']}
    header = stage / 'files/chrome/browser/landu_geolocation_provider.h'
    implementation = stage / 'files/chrome/browser/landu_geolocation_provider.cc'
    permission = stage / 'files/components/permissions/permission_context_base.cc'
    policy = stage / 'files/components/permissions/landu_geolocation_policy.h'
    template = native / 'host-tests/GeolocationProviderTest.cpp.in'
    inputs = {str(p): sha(p) for p in (manifest, header, implementation, permission, policy, template, Path(__file__).resolve())}
    for p in (header, implementation, permission, policy):
        if sha(p) != entries[p.relative_to(stage / 'files').as_posix()]:
            raise RuntimeError('Staged provider changed')
    text = header.read_text()
    body = text[text.index('class GeolocationProvider final'):text.index('}  // namespace landu')]
    code = implementation.read_text()
    body += code[code.index('namespace landu {') + len('namespace landu {'):code.index('}  // namespace landu')]
    fixture = template.read_text().replace('@PROVIDER_CLASS@', body).replace('@POLICY_HEADER@', str(policy))
    permission_text = permission.read_text()
    for key, arguments in (
            ('REQUEST', '    const PermissionRequestData& request_data,\n    content::RenderFrameHost* render_frame_host'),
            ('RESOLVER', '    const PermissionResolver& resolver,\n    content::RenderFrameHost* render_frame_host,\n'
                         '    const GURL& requesting_origin,\n    const GURL& embedding_origin')):
        signature = 'content::PermissionResult PermissionContextBase::GetPermissionStatus(\n' + arguments + ') const {\n'
        start = permission_text.index(signature) + len(signature)
        end = permission_text.index('#endif', start) + len('#endif')
        guard = permission_text[start:end]
        if not guard.lstrip().startswith('#if BUILDFLAG(IS_ANDROID)'):
            raise RuntimeError('Location block must precede all original permission logic')
        fixture = fixture.replace('@GUARD_' + key + '@', guard)
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if str(cache).startswith('/mnt/c/') or cache.is_relative_to(args.source.resolve()):
        raise RuntimeError('Use a private non-C test cache')
    output = Path(tempfile.mkdtemp(prefix='geolocation-provider-host-', dir=cache))
    cpp = output / 'provider-test.cc'
    cpp.write_text(fixture)
    binary = output / 'provider-test'
    compiler = args.source.resolve() / 'third_party/llvm-build/Release+Asserts/bin/clang++'
    command = [str(compiler), '-std=c++20', '-Wall', '-Wextra', '-Werror', '-pedantic',
               '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-O1', '-gline-tables-only',
               str(cpp), '-o', str(binary)]
    with (output / 'compile.log').open('w') as stream:
        code = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT).returncode
    result = None
    if code == 0:
        with (output / 'run.log').open('w') as stream:
            code = subprocess.run([str(binary)], stdout=stream, stderr=subprocess.STDOUT).returncode
        if code == 0:
            result = json.loads((output / 'run.log').read_text())
    stable = all(sha(Path(p)) == h for p, h in inputs.items())
    record = {'kind': 'staged-geolocation-provider-host-protocol-test', 'command': command,
              'stageManifestSha256': sha(manifest), 'inputFiles': inputs, 'inputsStable': stable,
              'result': result, 'exitCode': code, 'passed': code == 0 and stable,
              'asanAndUbsanEnabled': True, 'platformSubstitutes': True, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': record['passed']}))
    if not record['passed']:
        print((output / ('compile.log' if result is None and not (output / 'run.log').exists() else 'run.log')).read_text()[-9000:])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
