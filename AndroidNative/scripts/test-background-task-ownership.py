#!/usr/bin/env python3
"""Execute the full staged TaskInfo with explicit Bundle/command-line host substitutes."""
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
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    source = args.source.resolve(strict=True)
    if str(cache).startswith('/mnt/c/') or cache.is_relative_to(source):
        raise RuntimeError('Use a private non-C test cache')
    manifest = stage / 'manifest.json'
    entries = {entry['path']: entry['sha256'] for entry in json.loads(manifest.read_text())['files']}
    public = 'components/background_task_scheduler/android/java/src/org/chromium/components/background_task_scheduler/'
    internal = 'components/background_task_scheduler/internal/android/java/src/org/chromium/components/background_task_scheduler/internal/'
    names = [public + 'TaskInfo.java', internal + 'BackgroundTaskJobService.java',
             internal + 'BackgroundTaskSchedulerImpl.java', internal + 'BackgroundTaskSchedulerJobService.java']
    inputs = {str(manifest): sha(manifest), str(Path(__file__).resolve()): sha(Path(__file__).resolve())}
    for name in names:
        path = stage / 'files' / name
        if sha(path) != entries[name]:
            raise RuntimeError('Staged task source changed')
        inputs[str(path)] = sha(path)
    service = (stage / 'files' / names[1]).read_text()
    start = service.index('public boolean onStartJob(')
    guard = service.index('if (!TaskInfo.hasMatchingLanduEnvironment(taskParams.getExtras()))', start)
    factory = service.index('getBackgroundTaskFromTaskId(', start)
    if not start < guard < factory:
        raise RuntimeError('Owner validation must precede task construction')
    scheduler = (stage / 'files' / names[2]).read_text()
    if 'public boolean schedule(Context context, TaskInfo taskInfo) {\n        if (!TaskInfo.hasMatchingLanduEnvironment(taskInfo.getExtras())) return false;' not in scheduler:
        raise RuntimeError('Scheduling must validate ownership before any delegate action')
    output = Path(tempfile.mkdtemp(prefix='background-task-host-', dir=cache))
    definitions = {
        'android/content/Context.java': 'package android.content; public class Context {}',
        'android/os/PersistableBundle.java': '''package android.os;
public class PersistableBundle {
 private final java.util.Map<String,Object> values = new java.util.HashMap<>();
 public PersistableBundle() {} public PersistableBundle(PersistableBundle source) { values.putAll(source.values); }
 public void putString(String key,String value) { values.put(key,value); }
 public String getString(String key) { return (String)values.get(key); }
 public void putInt(String key,int value) { values.put(key,value); }
 public int getInt(String key,int fallback) { return values.containsKey(key) ? (Integer)values.get(key) : fallback; }
 public boolean containsKey(String key) { return values.containsKey(key); }
}''',
        'org/chromium/base/CommandLine.java': '''package org.chromium.base;
public final class CommandLine {
 private static CommandLine instance; private String owner;
 public static boolean isInitialized() { return instance != null; }
 public static CommandLine getInstance() { if(instance==null) throw new AssertionError(); return instance; }
 public String getSwitchValue(String key) { return key.equals("landu-environment-id") ? owner : null; }
 public static void setForHostTest(String owner) { instance=new CommandLine(); instance.owner=owner; }
 public static void clearForHostTest() { instance=null; }
}''',
        'org/chromium/build/annotations/NullMarked.java': 'package org.chromium.build.annotations; public @interface NullMarked {}',
        'org/chromium/build/annotations/Nullable.java': '''package org.chromium.build.annotations;
@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE, java.lang.annotation.ElementType.TYPE_PARAMETER}) public @interface Nullable {}''',
        'androidx/annotation/IntDef.java': 'package androidx.annotation; public @interface IntDef {long[] value() default {}; boolean flag() default false;}',
        'org/chromium/components/background_task_scheduler/TaskInfo.java': (stage / 'files' / names[0]).read_text(),
    }
    fixture = native / 'host-tests/background-task/BackgroundTaskOwnershipTest.java'
    inputs[str(fixture)] = sha(fixture)
    definitions['BackgroundTaskOwnershipTest.java'] = fixture.read_text()
    files = []
    for name, text in definitions.items():
        path = output / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        files.append(str(path))
    classes = output / 'classes'
    classes.mkdir()
    jdk = source / 'third_party/jdk/current/bin'
    command = [str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(classes), *files]
    with (output / 'compile.log').open('w') as log:
        code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
    result = None
    if code == 0:
        with (output / 'run.log').open('w') as log:
            code = subprocess.run([str(jdk / 'java'), '-cp', str(classes), 'BackgroundTaskOwnershipTest'], stdout=log, stderr=subprocess.STDOUT).returncode
        if code == 0:
            result = json.loads((output / 'run.log').read_text())
    stable = all(sha(Path(path)) == value for path, value in inputs.items())
    record = {'kind': 'full-staged-taskinfo-host-protocol-test', 'stageManifestSha256': sha(manifest),
              'inputFiles': inputs, 'inputsStable': stable, 'result': result, 'exitCode': code,
              'passed': code == 0 and stable, 'command': command, 'platformSubstitutes': True,
              'sourceGuardPlacementChecked': True, 'jobServiceRuntimeExecuted': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': record['passed']}))
    if not record['passed']:
        print((output / ('run.log' if (output / 'run.log').exists() else 'compile.log')).read_text()[-6000:])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
