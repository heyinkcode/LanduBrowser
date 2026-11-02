#!/usr/bin/env python3
"""Execute staged PendingIntentProvider and ownership code with explicit Android substitutes."""
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
    source, stage = [path.resolve(strict=True) for path in (args.source, args.stage)]
    native = Path(__file__).resolve().parents[1]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).lower().startswith('/mnt/c/'):
        raise RuntimeError('Use the independent non-C test cache')
    lock = json.loads((native / 'upstream-lock.json').read_text())
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source,
                                   text=True).strip() == lock['revision']
    manifest = stage / 'manifest.json'
    entries = {item['path']: item for item in json.loads(manifest.read_text())['files']}
    utils = 'base/android/java/src/org/chromium/base/IntentUtils.java'
    provider = 'components/browser_ui/notifications/android/java/src/org/chromium/components/browser_ui/notifications/PendingIntentProvider.java'
    inputs = [manifest, Path(__file__).resolve(), native / 'host-tests/pending-intent/PendingIntentOwnershipTest.java']
    for relative in (utils, provider):
        path = stage / 'files' / relative
        assert sha(path) == entries[relative]['sha256']
        assert sha(source / relative) == entries[relative]['originalSha256']
        inputs.extend((path, source / relative))
    hashes = {str(path): sha(path) for path in inputs}
    text = (stage / 'files' / utils).read_text()
    start = text.index('    public static final class LanduEnvironment {')
    boundary = text[start:text.index('\n    }', start) + len('\n    }')]
    start = text.index('    public static int getPendingIntentMutabilityFlag(')
    mutability = text[start:text.index('\n    }', start) + len('\n    }')]
    output = Path(tempfile.mkdtemp(prefix='pending-intent-host-', dir=cache))
    src = output / 'src'
    files = {
        'org/chromium/base/IntentUtils.java': '''package org.chromium.base;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Build;
import org.chromium.build.annotations.Nullable;
public class IntentUtils {
''' + boundary + '\n' + mutability + '\n}\n',
        'org/chromium/components/browser_ui/notifications/PendingIntentProvider.java': (stage / 'files' / provider).read_text(),
        'PendingIntentOwnershipTest.java': inputs[2].read_text(),
        'android/content/Context.java': 'package android.content; public class Context {}',
        'android/os/Build.java': '''package android.os; public class Build {
public static class VERSION { public static int SDK_INT = 35; }
public static class VERSION_CODES { public static final int S = 31; }}''',
        'androidx/core/app/PendingIntentCompat.java': '''package androidx.core.app;
public class PendingIntentCompat { public @interface Flags {} }''',
        'org/chromium/build/annotations/NullMarked.java': '''package org.chromium.build.annotations;
public @interface NullMarked {}''',
        'org/chromium/build/annotations/Nullable.java': '''package org.chromium.build.annotations;
@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}''',
        'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
 public static final int FILL_IN_IDENTIFIER = 0x100;
 private String action, data, component, identifier;
 private final Map<String,String> extras = new HashMap<>();
 public Intent() {}
 public Intent(String action) { this.action = action; }
 public Intent(Intent other) {
  action = other.action; data = other.data; component = other.component;
  identifier = other.identifier; extras.putAll(other.extras);
 }
 public Intent setData(String value) { data = value; return this; }
 public Intent setComponent(String value) { component = value; return this; }
 public Intent setIdentifier(String value) { identifier = value; return this; }
 public Intent putExtra(String name, String value) { extras.put(name,value); return this; }
 public String getAction() { return action; }
 public String getData() { return data; }
 public String getComponent() { return component; }
 public String getIdentifier() { return identifier; }
 public String getExtra(String name) { return extras.get(name); }
 public java.util.List<String> filterKey() { return Arrays.asList(action,data,component,identifier); }
 public boolean filterEquals(Intent other) { return filterKey().equals(other.filterKey()); }
 public void fillIn(Intent other, int flags) {
  if ((flags & FILL_IN_IDENTIFIER) != 0 || identifier == null) identifier = other.identifier;
  extras.putAll(other.extras);
 }
}''',
        'android/app/PendingIntent.java': '''package android.app;
import android.content.*;
import java.util.*;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT = 0x08000000;
 public static final int FLAG_IMMUTABLE = 0x04000000;
 public static final int FLAG_MUTABLE = 0x02000000;
 private static final Map<List<Object>,PendingIntent> registry = new HashMap<>();
 public static int creations;
 public String kind;
 public Intent intent;
 private int flags;
 public static PendingIntent getActivity(Context c,int request,Intent i,int f) { return get("activity",request,i,f); }
 public static PendingIntent getService(Context c,int request,Intent i,int f) { return get("service",request,i,f); }
 public static PendingIntent getBroadcast(Context c,int request,Intent i,int f) { return get("broadcast",request,i,f); }
 private static PendingIntent get(String kind,int request,Intent intent,int flags) {
  List<Object> key = Arrays.asList(kind,request,intent.filterKey(),flags & (FLAG_IMMUTABLE|FLAG_MUTABLE));
  PendingIntent value = registry.get(key);
  if (value == null) {
   value = new PendingIntent(); value.kind=kind; value.intent=new Intent(intent); value.flags=flags;
   registry.put(key,value); creations++;
  } else if ((flags & FLAG_UPDATE_CURRENT) != 0) value.intent = new Intent(intent);
  return value;
 }
 public void sendFillIn(Intent other) {
  if ((flags & FLAG_IMMUTABLE) == 0) intent.fillIn(other,flags);
 }
}''',
    }
    for relative, contents in files.items():
        path = src / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
    jdk = source / 'third_party/jdk/current/bin'
    classes = output / 'classes'
    compile_command = [str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-d',
                       str(classes), *(str(src / relative) for relative in files)]
    with (output / 'compile.log').open('w') as log:
        compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT)
    results = []
    if compiled.returncode == 0:
        for index, owner in enumerate(('11111111-1111-4111-8111-111111111111',
                                       '22222222-2222-4222-8222-222222222222')):
            command = [str(jdk / 'java'), '-cp', str(classes), 'PendingIntentOwnershipTest', owner]
            run = subprocess.run(command, capture_output=True, text=True)
            (output / f'run-{index}.log').write_text(run.stdout + run.stderr)
            results.append({'exitCode': run.returncode,
                            'result': json.loads(run.stdout) if run.returncode == 0 else None})
    stable = all(sha(Path(path)) == expected for path, expected in hashes.items())
    passed = (compiled.returncode == 0 and len(results) == 2 and stable
              and all(item['exitCode'] == 0 and item['result']['passed'] for item in results)
              and results[0]['result']['identity'] != results[1]['result']['identity'])
    report = {'kind': 'staged-pending-intent-ownership-host-protocol',
              'stageManifestSha256': sha(manifest), 'inputFiles': hashes, 'inputsStable': stable,
              'compileExitCode': compiled.returncode, 'runs': results, 'passed': passed,
              'fullProviderExecuted': True, 'ownershipCodeExtractedFromStagedIntentUtils': True,
              'androidFrameworkExecuted': False, 'platformSubstitutes': True, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'report': str(output / 'report.json'), 'runs': results}))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
