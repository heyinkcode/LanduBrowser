#!/usr/bin/env python3
"""Compile Landu Java sources against real pinned Chromium interface jars."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--build-dir', required=True, type=Path)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    build = args.build_dir.resolve(strict=True)
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != lock['revision'] or not build.is_relative_to(source):
        raise RuntimeError('Use the pinned Chromium checkout and its build directory')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use a separate non-C test cache')
    config = build / 'gen/chrome/android/base_module_java.javac.build_config.json'
    classpath = json.loads(config.read_text())
    paths = []
    for name in [*classpath['sdk_interface_jars'], *classpath['javac_full_interface_classpath']]:
        path = (build / name).resolve()
        if not path.is_relative_to(source):
            raise RuntimeError('Class path escapes the source checkout')
        if path.is_file() and path.stat().st_size > 0 and path not in paths:
            paths.append(path)
    required = {
        'obj/base/path_utils_java.turbine.jar': 'org/chromium/base/PathUtils.class',
        'obj/base/command_line_java.turbine.jar': 'org/chromium/base/CommandLine.class',
        classpath['sdk_interface_jars'][0]: 'android/app/Activity.class',
    }
    jar_hashes = {}
    for name, entry in required.items():
        path = (build / name).resolve(strict=True)
        if path not in paths:
            raise RuntimeError('Required interface jar is not ready: ' + name)
        with zipfile.ZipFile(path) as jar:
            jar.getinfo(entry)
        jar_hashes[name] = digest(path)
    sources = sorted(path for folder in ('java', 'android', 'chromium') for path in (native / folder).rglob('*.java'))
    if not sources:
        raise RuntimeError('Missing Landu Java sources')
    for path in sources:
        if path.is_symlink() or not path.resolve().is_relative_to(native):
            raise RuntimeError('Unexpected linked Java source')
    source_hashes = {str(path.relative_to(native)): digest(path) for path in sources}
    output = Path(tempfile.mkdtemp(prefix='chromium-java-adapter-', dir=cache))
    (output / 'classes').mkdir()
    (output / 'temp').mkdir()
    # The module's own R classes are not on its dependency-only javac classpath.
    # Extract only those generated classes; never use stale Landu class bodies.
    resource_source = build / 'obj/chrome/android/base_module_java.turbine.jar'
    resource_source_sha = digest(resource_source)
    resources = output / 'base-module-resources.jar'
    with zipfile.ZipFile(resource_source) as original, zipfile.ZipFile(resources, 'w') as generated:
        names = [name for name in original.namelist()
                 if Path(name).name == 'R.class'
                 or (Path(name).name.startswith('R$') and name.endswith('.class'))]
        if 'org/chromium/chrome/base/R$string.class' not in names:
            raise RuntimeError('Build the actual base-module resource classes first')
        for name in names:
            generated.writestr(name, original.read(name))
    paths.append(resources)
    javac = source / 'third_party/jdk/current/bin/javac'
    version = subprocess.check_output([str(javac), '--version'], text=True).strip()
    command = [str(javac), '-J-Xmx256m', '-J-XX:MaxMetaspaceSize=128m',
               '-J-Djava.io.tmpdir=' + str(output / 'temp'), '-J-Dfile.encoding=UTF-8',
               '--release', '11', '-encoding', 'UTF-8', '-proc:none',
               '-classpath', os.pathsep.join(str(path) for path in paths),
               '-d', str(output / 'classes'), *(str(path) for path in sources)]
    with (output / 'javac.log').open('w') as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=90)
    stable = all(digest(build / name) == value for name, value in jar_hashes.items())
    stable = stable and all(digest(native / name) == value for name, value in source_hashes.items())
    stable = stable and digest(resource_source) == resource_source_sha
    report = {'kind': 'chromium-java-interface-compile-only', 'revision': revision,
              'javac': version, 'sourceFiles': source_hashes, 'requiredInterfaceJars': jar_hashes,
              'availableClasspathJars': len(paths), 'inputsStable': stable,
              'generatedResourceClasses': {'source': str(resource_source.relative_to(build)),
                                           'sha256': resource_source_sha, 'classes': len(names)},
              'exitCode': result.returncode, 'passed': result.returncode == 0 and stable,
              'log': str(output / 'javac.log'), 'chromiumCheckoutModified': False,
              'fullGnJavaModuleBuilt': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': report['passed'],
                      'sources': len(sources), 'fullGnJavaModuleBuilt': False}), flush=True)
    if not report['passed']:
        print((output / 'javac.log').read_text()[:8000])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
