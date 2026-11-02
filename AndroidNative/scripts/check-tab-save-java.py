#!/usr/bin/env python3
"""Compile patched tab persistence APIs and their regenerated JNI proxies."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    source, build, stage = [p.resolve(strict=True) for p in (args.source, args.build_dir, args.stage)]
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/') or not build.is_relative_to(source):
        raise RuntimeError('Use an independent non-C test directory')
    relative = ['chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/' + name + '.java'
                for name in ('TabStateStorageService', 'StorageCollectionSynchronizer', 'TabStateAttributes', 'TabStateExtractor')]
    relative.append('chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabPersistentStore.java')
    manifest = stage / 'manifest.json'
    expected = {item['path']: item['sha256'] for item in json.loads(manifest.read_text())['files']}
    patched = [stage / 'files' / name for name in relative]
    if any(sha(path) != expected[name] for path, name in zip(patched, relative)):
        raise RuntimeError('Staged tab API differs from its manifest')
    output = Path(tempfile.mkdtemp(prefix='tab-save-java-', dir=cache))
    for name in ('generated', 'classes', 'java', 'temp'):
        (output / name).mkdir()
    checker_file = native / 'scripts/check-native-overlay.py'
    checker_sha = sha(checker_file)
    spec = importlib.util.spec_from_file_location('landu_cpp_checker', checker_file)
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
    checker.prepare_profile_jni(source, build, stage, output / 'generated', tab=True)
    jar = output / 'generated/chrome/browser/tab/jni_headers.srcjar'
    sources = list(patched)
    bridge = output / 'java/LanduLegacyTabApiCheck.java'
    bridge.write_text('''import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabStateAttributes;
import org.chromium.chrome.browser.tab.TabStateAttributesRegistry;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
final class LanduLegacyTabApiCheck implements TabStateAttributes.StoreKey {
  static boolean matches(TabModelSelector selector, Tab tab, TabStateAttributes attributes, long revision) {
    return attributes.isReadyForLanduSave() && attributes.getLanduSaveRevision() == revision
      && TabStateAttributesRegistry.getAttributesFor(tab, LanduLegacyTabApiCheck.class) == attributes
      && selector.getModel(tab.isIncognito()).getTabById(tab.getId()) == tab;
  }
}
''')
    sources.append(bridge)
    with zipfile.ZipFile(jar) as generated:
        for name in ('org/chromium/chrome/browser/tab/TabStateStorageServiceJni.java',
                     'org/chromium/chrome/browser/tab/StorageCollectionSynchronizerJni.java', 'org/jni_zero/GEN_JNI.java'):
            path = output / 'java' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(generated.read(name))
            sources.append(path)
    config = json.loads((build / 'gen/chrome/browser/tab/java.javac.build_config.json').read_text())
    model_config = json.loads((build / 'gen/chrome/browser/tabmodel/java.javac.build_config.json').read_text())
    jars = []
    for name in [*config['sdk_interface_jars'], *config['javac_full_interface_classpath'],
                 *model_config['javac_full_interface_classpath'], 'obj/chrome/browser/tab/java.turbine.jar',
                 'obj/chrome/browser/tabmodel/java.turbine.jar']:
        path = (build / name).resolve(strict=True)
        if not path.is_relative_to(source) or not path.is_file():
            raise RuntimeError('Unexpected Java interface path')
        if path not in jars:
            jars.append(path)
    inputs = {str(path): sha(path) for path in [manifest, *jars, *sources]}
    command = [str(source / 'third_party/jdk/current/bin/javac'), '-J-Xmx256m',
               '-J-Djava.io.tmpdir=' + str(output / 'temp'), '--release', '17', '-encoding', 'UTF-8', '-proc:none',
               '-classpath', os.pathsep.join(map(str, jars)), '-d', str(output / 'classes'), *map(str, sources)]
    log = output / 'javac.log'
    with log.open('w') as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=90)
    stable = sha(checker_file) == checker_sha and all(sha(Path(path)) == value for path, value in inputs.items())
    report = {'kind': 'upstream-tab-persistence-apis-and-generated-jni-compile-only', 'revision': lock['revision'],
              'upstreamJavaFiles': relative,
              'stageManifestSha256': sha(manifest), 'inputFiles': inputs, 'checkerSha256': checker_sha,
              'inputsStable': stable, 'exitCode': result.returncode, 'passed': result.returncode == 0 and stable,
              'log': str(log), 'fullChromeJavaBuilt': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': report['passed']}), flush=True)
    if not report['passed']:
        print(log.read_text()[:7000])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
