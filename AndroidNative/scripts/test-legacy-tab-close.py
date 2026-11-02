#!/usr/bin/env python3
"""Execute exact added legacy owner methods with host-only dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_method(text, signature):
    start = text.index(signature)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        if text[end] == '{': depth += 1
        if text[end] == '}': depth -= 1
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--build-dir', type=Path, required=True)
    args = parser.parse_args()
    source, stage = [p.resolve(strict=True) for p in (args.source, args.stage)]
    build = args.build_dir.resolve(strict=True)
    native = Path(__file__).resolve().parents[1]
    lock = json.loads((native / 'upstream-lock.json').read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != lock['revision']:
        raise RuntimeError('Use the pinned checkout')
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/') or not build.is_relative_to(source):
        raise RuntimeError('Use an independent non-C test directory')
    relative = 'chrome/android/java/src/org/chromium/chrome/browser/tabmodel/TabPersistentStoreImpl.java'
    staged = stage / 'files' / relative
    manifest = stage / 'manifest.json'
    expected = {i['path']: i['sha256'] for i in json.loads(manifest.read_text())['files']}
    if sha(staged) != expected[relative]:
        raise RuntimeError('Staged owner source changed')
    text = staged.read_text()
    begin = text.index('        sLanduSaveBarrier.register(this,')
    end = text.index('        mPrefetchTabListToMergeTasks =', begin)
    register = text[begin:end]
    begin = text.index('    @Override\n    public void saveStateBeforeLanduExit(')
    end = text.index('    private void saveListToFile(', begin)
    methods = text[begin:end]
    attributes = stage / 'files/chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/TabStateAttributes.java'
    attr_text = attributes.read_text()
    if sha(attributes) != expected[str(attributes.relative_to(stage / 'files'))]:
        raise RuntimeError('Staged attributes changed')
    attr_methods = '\n'.join(java_method(attr_text, sig) for sig in [
        '    public long getLanduSaveRevision()', '    public boolean isReadyForLanduSave()',
        '    private void updateIsDirtyInternal('])
    template = native / 'host-tests/LegacyTabCloseTest.java.in'
    extractor = stage / 'files/chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/TabStateExtractor.java'
    extraction_template = native / 'host-tests/TabStateExtractionTest.java.in'
    if sha(extractor) != expected[str(extractor.relative_to(stage / 'files'))]:
        raise RuntimeError('Staged extractor changed')
    helpers = [native / 'java/com/landu/browser/nativecore' / (n + '.java')
               for n in ('EnvironmentLegacySaveBarrier', 'EnvironmentShutdownCoordinator')]
    inputs = {str(p): sha(p) for p in [staged, attributes, extractor, manifest, template, extraction_template, *helpers]}
    output = Path(tempfile.mkdtemp(prefix='legacy-tab-close-host-', dir=cache))
    for name in ('classes', 'temp'):
        (output / name).mkdir()
    rendered = output / 'LegacyTabCloseTest.java'
    body = template.read_text()
    for marker, code in [('/* LANDU_REGISTER */', register), ('/* LANDU_OWNER_METHODS */', methods),
                         ('/* LANDU_ATTRIBUTE_METHODS */', attr_methods)]:
        if body.count(marker) != 1:
            raise RuntimeError('Ambiguous fixture')
        body = body.replace(marker, code)
    rendered.write_text(body)
    extraction_source = output / 'TabStateExtractionTest.java'
    extraction_body = extraction_template.read_text()
    marker = '/* LANDU_EXTRACTION_METHOD */'
    if extraction_body.count(marker) != 1:
        raise RuntimeError('Ambiguous extraction fixture')
    extraction_source.write_text(extraction_body.replace(marker,
        java_method(extractor.read_text(), '    public static @Nullable TabState from(Tab tab)')))
    # Compile the same extracted registration against the real sequenced executor
    # interface as well. The executable fixture deliberately uses a host queue.
    config_file = build / 'gen/chrome/browser/tabpersistence/java.javac.build_config.json'
    config = json.loads(config_file.read_text())
    jars = []
    for name in [*config['sdk_interface_jars'], *config['javac_full_interface_classpath']]:
        path = (build / name).resolve(strict=True)
        if not path.is_relative_to(source):
            raise RuntimeError('Unexpected API dependency')
        if path not in jars:
            jars.append(path)
    inputs.update({str(p): sha(p) for p in [config_file, *jars]})
    api_dir = output / 'api'
    api_dir.mkdir()
    api_source = api_dir / 'LegacyTabCloseTest.java'
    api_source.write_text(body.replace('static class Queue {',
            'static class Queue implements org.chromium.base.task.SequencedTaskRunner {')
        .replace('        void execute(Runnable task) {',
            '        @Override public void execute(Runnable task) {')
        .replace('        void run() {',
            '        @Override public void execute(Runnable task, org.chromium.base.task.Location location) { execute(task); }\n'
            '        @Override public void postDelayedTask(Runnable task, long delay, org.chromium.base.task.Location location) { execute(task); }\n'
            '        void run() {'))
    jdk = source / 'third_party/jdk/current/bin'
    commands = [[str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-proc:none',
                 '-classpath', os.pathsep.join(map(str, jars)), '-d', str(api_dir / 'classes'),
                 *map(str, helpers), str(api_source)],
                [str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(output / 'classes'),
                 *map(str, helpers), str(rendered), str(extraction_source)],
                [str(jdk / 'java'), '-Xmx128m', '-Djava.io.tmpdir=' + str(output / 'temp'),
                 '-cp', str(output / 'classes'), 'com.landu.browser.nativecore.LegacyTabCloseTest'],
                [str(jdk / 'java'), '-Xmx128m', '-Djava.io.tmpdir=' + str(output / 'temp'),
                 '-cp', str(output / 'classes'), 'com.landu.browser.nativecore.TabStateExtractionTest']]
    log = output / 'host-check.log'
    passed = True
    with log.open('w') as stream:
        for command in commands:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=60)
            if result.returncode:
                passed = False
                break
    stable = all(sha(Path(path)) == value for path, value in inputs.items())
    report = {'kind': 'exact-legacy-owner-methods-host-only', 'revision': lock['revision'],
              'passed': passed and stable, 'inputsStable': stable, 'inputFiles': inputs,
              'stageManifestSha256': sha(manifest), 'log': str(log),
              'realSequencedExecutorInterfaceCompiled': passed,
              'hostWorkerThreadsExecuted': passed,
              'extractionFailureOwnershipMethodExecuted': passed,
              'realFileManagerRun': False, 'androidRuntimeExecuted': False,
              'fullChromeJavaBuilt': False, 'apkBuilt': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(log.read_text()[:6000], end='')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': report['passed']}))
    if not report['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
