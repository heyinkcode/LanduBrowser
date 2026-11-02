#!/usr/bin/env python3
"""Execute staged sync/async tagged notification paths, using explicit host OS substitutes."""
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
    parser.add_argument('--negative-control', choices=['global-cancel', 'query-leak', 'foreground-id'])
    args = parser.parse_args()
    source, stage = (p.resolve(strict=True) for p in (args.source, args.stage))
    native = Path(__file__).resolve().parents[1]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    assert not cache.is_relative_to(source) and not str(cache).startswith('/mnt/c/')
    manifest = stage / 'manifest.json'
    metadata = json.loads(manifest.read_text())
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() == metadata['revision']
    entries = {entry['path']: entry for entry in metadata['files']}
    fixture = native / 'host-tests/notification-tags/NotificationTagsTest.java'
    registry_fixture = native / 'host-tests/notification-tags/NotificationIdStoreTest.java'
    inputs = {str(p): sha(p) for p in (manifest, fixture, registry_fixture, Path(__file__).resolve())}
    package = 'org/chromium/components/browser_ui/notifications/'
    prefix = 'components/browser_ui/notifications/android/java/src/'
    files = {}
    for name in ('NotificationMetadata', 'NotificationManagerProxyImpl', 'AsyncNotificationManagerProxyImpl',
                 'StatusBarNotificationAdaptor', 'BaseNotificationManagerProxy', 'NotificationManagerProxy',
                 'NotificationWrapper', 'ForegroundServiceUtils'):
        relative = prefix + package + name + '.java'
        path = (stage / 'files' if relative in entries else source) / relative
        if relative in entries:
            assert sha(path) == entries[relative]['sha256']
            assert sha(source / relative) == entries[relative]['originalSha256']
        else:
            assert path.read_bytes() == subprocess.check_output(['git', 'show', 'HEAD:' + relative], cwd=source)
        inputs[str(path)] = sha(path)
        files[package + name + '.java'] = path.read_text()
    utils = stage / 'files/base/android/java/src/org/chromium/base/IntentUtils.java'
    assert sha(utils) == entries['base/android/java/src/org/chromium/base/IntentUtils.java']['sha256']
    inputs[str(utils)] = sha(utils)
    store_relative = 'chrome/android/java/src/com/landu/browser/nativecore/NotificationIdStore.java'
    store = stage / 'files' / store_relative
    assert sha(store) == entries[store_relative]['sha256']
    inputs[str(store)] = sha(store)
    files['com/landu/browser/nativecore/NotificationIdStore.java'] = store.read_text()
    files['com/landu/browser/nativecore/NotificationIdStoreTest.java'] = registry_fixture.read_text()
    text = utils.read_text()
    start = text.index('    public static final class LanduEnvironment {')
    ownership = text[start:text.index('\n    }', start) + len('\n    }')]
    files.update({
        'org/chromium/base/IntentUtils.java': 'package org.chromium.base; import android.content.Intent; import org.chromium.build.annotations.Nullable; public class IntentUtils {\n' + ownership + '\n}',
        'org/chromium/build/annotations/Nullable.java': 'package org.chromium.build.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}',
        'org/chromium/build/annotations/NullMarked.java': 'package org.chromium.build.annotations; public @interface NullMarked {}',
        'org/chromium/build/annotations/NullUnmarked.java': 'package org.chromium.build.annotations; public @interface NullUnmarked {}',
        'org/chromium/build/annotations/MockedInTests.java': 'package org.chromium.build.annotations; public @interface MockedInTests {}',
        'android/content/Intent.java': '''package android.content; public class Intent {
 public static final int FILL_IN_IDENTIFIER=0x100;private String identifier;
 public Intent(){}public Intent(Intent i){identifier=i.identifier;}
 public Intent setIdentifier(String s){identifier=s;return this;}public String getIdentifier(){return identifier;}
}''',
        'android/app/Notification.java': 'package android.app; public class Notification {public static final int FLAG_FOREGROUND_SERVICE=64;public int flags;public final String value; public Notification(String s){value=s;}public String getChannelId(){return "channel";}}',
        'android/app/ForegroundServiceStartNotAllowedException.java': 'package android.app;public class ForegroundServiceStartNotAllowedException extends IllegalStateException {}',
        'android/app/Service.java': '''package android.app;public class Service {
 public int notificationId,type;public void startForeground(int id,Notification n){startForeground(id,n,0);}
 public void startForeground(int id,Notification n,int t){notificationId=id;type=t;n.flags|=Notification.FLAG_FOREGROUND_SERVICE;androidx.core.app.NotificationManagerCompat.INSTANCE.notify(id,n);}
}''',
        'androidx/core/app/ServiceCompat.java': 'package androidx.core.app;public class ServiceCompat {public static void stopForeground(android.app.Service s,int flags){if((flags&1)!=0)NotificationManagerCompat.INSTANCE.cancel(s.notificationId);}}',
        'androidx/core/content/ContextCompat.java': 'package androidx.core.content;public class ContextCompat {public static void startForegroundService(Object c,android.content.Intent i){}}',
        'android/app/NotificationChannel.java': 'package android.app; public class NotificationChannel {public String getId(){return "channel";}}',
        'android/app/NotificationChannelGroup.java': 'package android.app; public class NotificationChannelGroup {}',
        'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}public static class VERSION_CODES {public static final int O=26,Q=29,S=31;}}',
        'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long elapsedRealtime(){return 1;}}',
        'android/service/notification/StatusBarNotification.java': '''package android.service.notification; public class StatusBarNotification {
 private final String tag;private final int id;private final android.app.Notification notification;
 public StatusBarNotification(String t,int i,android.app.Notification n){tag=t;id=i;notification=n;}
 public int getId(){return id;}public String getTag(){return tag;}public android.app.Notification getNotification(){return notification;}
}''',
        'androidx/core/app/NotificationManagerCompat.java': '''package androidx.core.app;
import android.app.*; import android.service.notification.StatusBarNotification;import java.util.*;
public class NotificationManagerCompat {
 public static final NotificationManagerCompat INSTANCE=new NotificationManagerCompat();public int globalCancels;
 private final Map<List<Object>,StatusBarNotification> active=new LinkedHashMap<>();
 private List<Object> key(String tag,int id){return Arrays.asList(tag,id);}
 public static NotificationManagerCompat from(Object c){return INSTANCE;}
 public void notify(int id,Notification n){notify(null,id,n);}public void notify(String tag,int id,Notification n){active.put(key(tag,id),new StatusBarNotification(tag,id,n));}
 public void cancel(int id){cancel(null,id);}public void cancel(String tag,int id){active.remove(key(tag,id));}
 public void cancelAll(){globalCancels++;active.clear();}
 public StatusBarNotification[] getActiveNotifications(){return active.values().toArray(new StatusBarNotification[0]);}
 public Notification peek(String tag,int id){var n=active.get(key(tag,id));return n==null?null:n.getNotification();}
 public void clearFixture(){active.clear();globalCancels=0;}
 public void createNotificationChannel(NotificationChannel c){}public void createNotificationChannelGroup(NotificationChannelGroup c){}
 public List<NotificationChannel> getNotificationChannels(){return new ArrayList<>();}public List<NotificationChannelGroup> getNotificationChannelGroups(){return new ArrayList<>();}
 public NotificationChannel getNotificationChannel(String id){return null;}public void deleteNotificationChannel(String id){}public void deleteNotificationChannelGroup(String id){}
}''',
        'org/chromium/base/Callback.java': 'package org.chromium.base; public interface Callback<T> {void onResult(T result);default Runnable bind(T result){return ()->onResult(result);}}',
        'org/chromium/base/ContextUtils.java': 'package org.chromium.base;public class ContextUtils {public static Object getApplicationContext(){return new Object();}}',
        'org/chromium/base/Log.java': 'package org.chromium.base;public class Log {public static void e(String t,String m,Object...args){}public static void d(String t,String m,Object...args){}}',
        'org/chromium/base/ResettersForTesting.java': 'package org.chromium.base;public class ResettersForTesting {public static void register(Runnable r){}}',
        'org/chromium/base/TraceEvent.java': 'package org.chromium.base;public class TraceEvent implements AutoCloseable {public static TraceEvent scoped(String s){return new TraceEvent();}public void close(){}}',
        'org/chromium/base/task/TaskTraits.java': 'package org.chromium.base.task;public class TaskTraits {public static final int UI_DEFAULT=1;}',
        'org/chromium/base/task/PostTask.java': '''package org.chromium.base.task;public class PostTask {
 private static final java.util.Queue<Runnable> queue=new java.util.ArrayDeque<>();
 public static void postTask(int t,Runnable r){queue.add(r);}public static void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
        'org/chromium/base/task/AsyncTask.java': '''package org.chromium.base.task;public abstract class AsyncTask<T> {
 private static final java.util.Queue<Runnable> queue=new java.util.ArrayDeque<>();
 public static final java.util.concurrent.Executor SERIAL_EXECUTOR=r->queue.add(r);
 protected abstract T doInBackground();protected abstract void onPostExecute(T result);
 public void executeOnExecutor(java.util.concurrent.Executor ex){ex.execute(()->{T result=doInBackground();PostTask.postTask(1,()->onPostExecute(result));});}
 public static void drain(){while(!queue.isEmpty())queue.remove().run();}
}''',
        package + 'NotificationTagsTest.java': fixture.read_text(),
    })
    if args.negative_control == 'foreground-id':
        key = package + 'ForegroundServiceUtils.java'
        old = 'id = org.chromium.base.IntentUtils.LanduEnvironment.notificationId(id);'
        assert files[key].count(old) == 1
        files[key] = files[key].replace(old, '// Deliberately bypass the mapper in a private test copy.')
    elif args.negative_control:
        key = package + 'NotificationManagerProxyImpl.java'
        old, new = ('NotificationMetadata.landuCancelAll(mNotificationManager)', 'mNotificationManager.cancelAll()') if args.negative_control == 'global-cancel' else (
            'if (!NotificationMetadata.landuOwnsTag(notification.getTag())) continue;', '// Deliberately remove ownership filter in private negative-control copy.')
        assert files[key].count(old) == 1
        files[key] = files[key].replace(old, new)
    output = Path(tempfile.mkdtemp(prefix='notification-tags-' + (args.negative_control or 'host') + '-', dir=cache))
    for relative, code in files.items():
        path = output / 'src' / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code)
    jdk = source / 'third_party/jdk/current/bin'
    with (output / 'compile.log').open('w') as log:
        compiled = subprocess.run([str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8',
            '-d', str(output / 'classes'), *(str(output / 'src' / n) for n in files)], stdout=log, stderr=subprocess.STDOUT)
    runs = []
    registry_result = None
    if compiled.returncode == 0:
        if not args.negative_control:
            run = subprocess.run([str(jdk / 'java'), '-ea', '-cp', str(output / 'classes'),
                'com.landu.browser.nativecore.NotificationIdStoreTest', str(output / 'registry-tests')], capture_output=True, text=True)
            (output / 'registry-tests.log').write_text(run.stdout + run.stderr)
            if run.returncode == 0: registry_result = json.loads(run.stdout)
        for owner in ('A', 'B'):
            run = subprocess.run([str(jdk / 'java'), '-ea', '-cp', str(output / 'classes'),
                'org.chromium.components.browser_ui.notifications.NotificationTagsTest', owner,
                str(output / 'registry')], capture_output=True, text=True)
            (output / ('run-' + owner + '.log')).write_text(run.stdout + run.stderr)
            runs.append({'owner': owner, 'exitCode': run.returncode,
                         'result': json.loads(run.stdout) if run.returncode == 0 else None})
    stable = all(sha(Path(p)) == digest for p, digest in inputs.items())
    expected = {'global-cancel': 'uninitialized cancel removed notification',
                'query-leak': 'uninitialized query leaked notifications',
                'foreground-id': 'foreground service bypassed the ID mapping'}.get(args.negative_control)
    detected = bool(args.negative_control) and len(runs) == 2 and all(
        r['exitCode'] != 0 and expected in (output / ('run-' + r['owner'] + '.log')).read_text() for r in runs)
    passed = compiled.returncode == 0 and stable and len(runs) == 2 and (
        detected if args.negative_control else registry_result is not None and registry_result['passed']
        and all(r['exitCode'] == 0 and r['result']['passed'] for r in runs))
    report = {'kind': 'staged-tagged-notification-host-test', 'stageManifestSha256': sha(manifest),
              'inputFiles': inputs, 'inputsStable': stable, 'compileExitCode': compiled.returncode,
              'runs': runs, 'passed': passed, 'negativeControl': args.negative_control,
              'registryResult': registry_result,
              'faultDetected': detected, 'fullSyncAndAsyncProxyExecuted': True,
              'platformSubstitutes': True, 'androidFrameworkExecuted': False, 'deviceTested': False,
              'realPersistentIdRegistryExecuted': True,
              'exclusions': ['Android foreground-service lifecycle', 'channel and group isolation', 'direct OS manager bypasses']}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'report': str(output / 'report.json'), 'runs': runs, 'registryResult': registry_result, 'faultDetected': detected}))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
