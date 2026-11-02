#!/usr/bin/env python3
"""Execute the full staged JobService with deterministic Android protocol substitutes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--ownership-report', type=Path, required=True)
    parser.add_argument('--negative-control', choices=['notification-id'])
    args = parser.parse_args()
    source, stage, previous = [p.resolve(strict=True) for p in (args.source, args.stage, args.ownership_report)]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if not previous.is_relative_to(cache) or cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use private non-C cache and report')
    old = json.loads(previous.read_text())
    manifest = stage / 'manifest.json'
    if not old.get('passed') or old.get('stageManifestSha256') != sha(manifest):
        raise RuntimeError('Use a passing same-stage ownership report')
    inputs = dict(old['inputFiles'])
    inputs[str(previous)] = sha(previous)
    inputs[str(Path(__file__).resolve())] = sha(Path(__file__).resolve())
    if not all(sha(Path(p)) == value for p, value in inputs.items()):
        raise RuntimeError('Ownership test input changed')
    entries = {entry['path']: entry['sha256'] for entry in json.loads(manifest.read_text())['files']}
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != json.loads(manifest.read_text())['revision']:
        raise RuntimeError('Use the pinned Chromium revision')
    public = 'org/chromium/components/background_task_scheduler/'
    internal = public + 'internal/'
    root = 'components/background_task_scheduler/'
    service = stage / ('files/' + root + 'internal/android/java/src/' + internal + 'BackgroundTaskJobService.java')
    scheduler = stage / ('files/' + root + 'internal/android/java/src/' + internal + 'BackgroundTaskSchedulerJobService.java')
    for path in (service, scheduler):
        if sha(path) != entries[path.relative_to(stage / 'files').as_posix()]:
            raise RuntimeError('Staged job source changed')
        inputs[str(path)] = sha(path)
    output = Path(tempfile.mkdtemp(prefix='background-job-protocol-', dir=cache))
    shutil.copytree(previous.parent / 'src', output / 'src')
    staged_task = stage / ('files/' + root + 'android/java/src/' + public + 'TaskInfo.java')
    if sha(output / ('src/' + public + 'TaskInfo.java')) != sha(staged_task):
        raise RuntimeError('Host copy is not the actual staged TaskInfo')
    code = scheduler.read_text()
    namespace_method = method(code, 'private static @Nullable JobScheduler landuJobScheduler(')
    parameters_method = method(code, 'static TaskParameters getTaskParametersFromJobParameters(')
    import re
    delegate = source / (root + 'internal/android/java/src/' + internal + 'BackgroundTaskSchedulerDelegate.java')
    inputs[str(delegate)] = sha(delegate)
    if delegate.read_bytes() != subprocess.check_output(['git', '-C', str(source), 'show', 'HEAD:' + delegate.relative_to(source).as_posix()]):
        raise RuntimeError('Task delegate differs from pinned source')
    extras_key = re.search(r'String BACKGROUND_TASK_EXTRAS_KEY\s*=\s*("[^"]+")', delegate.read_text()).group(1)
    definitions = {
        internal + 'BackgroundTaskJobService.java': service.read_text(),
        internal + 'BackgroundTaskSchedulerJobService.java': '''package org.chromium.components.background_task_scheduler.internal;
import android.app.job.*; import android.content.Context; import android.os.*;
import org.chromium.base.Log; import org.chromium.build.annotations.Nullable;
import org.chromium.components.background_task_scheduler.*;
class BackgroundTaskSchedulerJobService {
 static final String TAG="Host";
 static final String BACKGROUND_TASK_EXTRAS_KEY=@EXTRAS_VALUE@;
 static String hostExtrasKey() {return BACKGROUND_TASK_EXTRAS_KEY;}
 interface Clock {long currentTimeMillis();}
 static boolean didTaskExpire(JobParameters params,long now) {return params.expired;}
NAMESPACE_METHOD
PARAMETERS_METHOD
}'''.replace('@EXTRAS_VALUE@', extras_key).replace('NAMESPACE_METHOD', namespace_method).replace('PARAMETERS_METHOD', parameters_method),
        'android/os/PersistableBundle.java': '''package android.os;
public class PersistableBundle {
 private final java.util.Map<String,Object> values = new java.util.HashMap<>();
 public PersistableBundle() {} public PersistableBundle(PersistableBundle other) {values.putAll(other.values);}
 public void putAll(PersistableBundle other) {values.putAll(other.values);}
 public void putString(String k,String v) {values.put(k,v);} public String getString(String k) {return (String)values.get(k);}
 public void putInt(String k,int v) {values.put(k,v);} public int getInt(String k,int fallback) {return values.containsKey(k)?(Integer)values.get(k):fallback;}
 public boolean containsKey(String k) {return values.containsKey(k);}
 public void putPersistableBundle(String k,PersistableBundle v) {values.put(k,v);}
 public PersistableBundle getPersistableBundle(String k) {return (PersistableBundle)values.get(k);}
}''',
        'android/content/Context.java': '''package android.content;
public class Context {public static final String JOB_SCHEDULER_SERVICE="jobscheduler";
 public final android.app.job.JobScheduler scheduler=new android.app.job.JobScheduler(); public boolean failService;
 public Object getSystemService(String key) {if(failService)throw new SecurityException(); return scheduler;}}
''',
        'android/app/job/JobInfo.java': 'package android.app.job; public class JobInfo {private final int id; public JobInfo(int id){this.id=id;} public int getId(){return id;}}',
        'android/app/job/JobScheduler.java': '''package android.app.job; import java.util.*;
public class JobScheduler {
 private final Map<String,Map<Integer,JobInfo>> jobs; private final String namespace;
 public JobScheduler(){this(new HashMap<>(),null);} private JobScheduler(Map<String,Map<Integer,JobInfo>> jobs,String ns){this.jobs=jobs;namespace=ns;}
 private Map<Integer,JobInfo> own(){return jobs.computeIfAbsent(namespace,key->new HashMap<>());}
 public JobScheduler forNamespace(String ns){if(android.os.Build.VERSION.SDK_INT<34)throw new AssertionError();return new JobScheduler(jobs,ns);}
 public String getNamespace(){return namespace;} public int schedule(JobInfo info){own().put(info.getId(),info);return 1;}
 public void cancel(int id){own().remove(id);} public List<JobInfo> getAllPendingJobs(){return new ArrayList<>(own().values());}
 public void cancelInAllNamespaces(){throw new AssertionError("Global cancellation is forbidden");}
}''',
        'android/app/job/JobParameters.java': '''package android.app.job; import android.os.PersistableBundle;
public class JobParameters {private final int id; private final String ns; private final PersistableBundle extras; public boolean expired;
 public JobParameters(int id,String ns,PersistableBundle extras){this.id=id;this.ns=ns;this.extras=extras;}
 public int getJobId(){return id;} public String getJobNamespace(){if(android.os.Build.VERSION.SDK_INT<34)throw new AssertionError();return ns;}
 public PersistableBundle getExtras(){return extras;}}''',
        'android/app/Notification.java': 'package android.app; public class Notification {}',
        'androidx/annotation/AnyThread.java': 'package androidx.annotation; public @interface AnyThread {}',
        'androidx/annotation/MainThread.java': 'package androidx.annotation; public @interface MainThread {}',
        'android/app/job/JobService.java': '''package android.app.job; import java.util.*; import android.app.Notification;
public class JobService extends android.content.Context {public static final int JOB_END_NOTIFICATION_POLICY_DETACH=0;
 public static class Completion {public final JobParameters params;public final boolean retry;Completion(JobParameters p,boolean r){params=p;retry=r;}}
 public final List<Completion> completions=new ArrayList<>(); public int notifications,lastNotificationId;
 public boolean onStartJob(JobParameters p){return false;}public boolean onStopJob(JobParameters p){return false;}public void onDestroy(){}
 public final void jobFinished(JobParameters p,boolean retry){completions.add(new Completion(p,retry));}
 public final void setNotification(JobParameters p,int id,Notification n,int policy){notifications++;lastNotificationId=id;}}
''',
        'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;}public static class VERSION_CODES {public static final int UPSIDE_DOWN_CAKE=34;}}',
        'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long uptimeMillis(){return System.currentTimeMillis();}}',
        'android/os/Looper.java': 'package android.os; public class Looper {private static final Looper MAIN=new Looper();public static Looper getMainLooper(){return MAIN;}}',
        'android/os/Handler.java': '''package android.os; import java.util.*;
public class Handler {
 private static final List<Map.Entry<Handler,Runnable>> queue=new ArrayList<>(); public static boolean acceptPosts=true;
 public Handler(Looper looper){} public boolean post(Runnable r){if(!acceptPosts)return false;queue.add(new AbstractMap.SimpleImmutableEntry<>(this,r));return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(e->e.getKey()==this&&e.getValue()==r);}
 public void removeCallbacksAndMessages(Object token){queue.removeIf(e->e.getKey()==this);}
 public static List<Runnable> queued(){List<Runnable> result=new ArrayList<>();for(Map.Entry<Handler,Runnable> e:queue)result.add(e.getValue());return result;}
 public static void drain(){List<Runnable> work=queued();queue.clear();for(Runnable r:work)r.run();}
}''',
        'org/chromium/base/ContextUtils.java': 'package org.chromium.base; public class ContextUtils {private static final android.content.Context CTX=new android.content.Context();public static android.content.Context getApplicationContext(){return CTX;}}',
        'org/chromium/base/Log.java': 'package org.chromium.base; public class Log {public static void w(String t,String m,Object... a){} public static void e(String t,String m,Object... a){}}',
        'org/chromium/base/ThreadUtils.java': 'package org.chromium.base; public class ThreadUtils {public static void assertOnUiThread(){} public static void runOnUiThreadBlocking(Runnable r){r.run();}}',
        'org/chromium/base/ResettersForTesting.java': 'package org.chromium.base; public class ResettersForTesting {public static void register(Runnable r){}}',
        internal + 'BackgroundTaskSchedulerUma.java': '''package org.chromium.components.background_task_scheduler.internal;
class BackgroundTaskSchedulerUma {static final BackgroundTaskSchedulerUma INSTANCE=new BackgroundTaskSchedulerUma();static BackgroundTaskSchedulerUma getInstance(){return INSTANCE;}
 void reportTaskStarted(int id){}void reportTaskStopped(int id){}void reportTaskFinished(int id,long time){}}''',
        internal + 'BackgroundTaskSchedulerFactoryInternal.java': '''package org.chromium.components.background_task_scheduler.internal;
import android.content.Context; import org.chromium.components.background_task_scheduler.BackgroundTask;
class BackgroundTaskSchedulerFactoryInternal {static BackgroundTask next;static int calls;
 static BackgroundTask getBackgroundTaskFromTaskId(int id){calls++;return next;}
 static class Scheduler {void cancel(Context c,int id){throw new AssertionError("Unexpected cancellation");}}
 static Scheduler getScheduler(){return new Scheduler();}}''',
    }
    for name in ('TaskParameters.java', 'BackgroundTask.java'):
        path = source / (root + 'android/java/src/' + public + name)
        if path.read_bytes() != subprocess.check_output(['git', '-C', str(source), 'show', 'HEAD:' + path.relative_to(source).as_posix()]):
            raise RuntimeError('Background task interface differs from pinned source')
        inputs[str(path)] = sha(path)
        definitions[public + name] = path.read_text()
    native = Path(__file__).resolve().parents[1]
    fixture = native / 'host-tests/background-task/BackgroundJobProtocolTest.java'
    inputs[str(fixture)] = sha(fixture)
    definitions[internal + fixture.name] = fixture.read_text()
    utils = stage / 'files/base/android/java/src/org/chromium/base/IntentUtils.java'
    store = stage / 'files/chrome/android/java/src/com/landu/browser/nativecore/NotificationIdStore.java'
    for path in (utils, store):
        assert sha(path) == entries[path.relative_to(stage / 'files').as_posix()]
        inputs[str(path)] = sha(path)
    ownership = method(utils.read_text(), 'public static final class LanduEnvironment')
    definitions['org/chromium/base/IntentUtils.java'] = ('package org.chromium.base; import android.content.Intent; '
        'import org.chromium.build.annotations.Nullable; public class IntentUtils {' + ownership + '}')
    definitions['android/content/Intent.java'] = '''package android.content;public class Intent {
 public static final int FILL_IN_IDENTIFIER=256;private String identifier;
 public Intent(){}public Intent(Intent i){identifier=i.identifier;}
 public String getIdentifier(){return identifier;}public Intent setIdentifier(String id){identifier=id;return this;}}'''
    definitions['com/landu/browser/nativecore/NotificationIdStore.java'] = store.read_text()
    if args.negative_control:
        name = internal + 'BackgroundTaskJobService.java'
        old_call = 'org.chromium.base.IntentUtils.LanduEnvironment.notificationId(notificationId)'
        assert definitions[name].count(old_call) == 1
        definitions[name] = definitions[name].replace(old_call, 'notificationId')
    for name, value in definitions.items():
        path = output / 'src' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)
    sources = list((output / 'src').rglob('*.java'))
    classes = output / 'classes'
    classes.mkdir()
    jdk = source / 'third_party/jdk/current/bin'
    command = [str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)]
    with (output / 'compile.log').open('w') as log:
        code = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT).returncode
    result = None
    if code == 0:
        with (output / 'run.log').open('w') as log:
            code = subprocess.run([str(jdk / 'java'), '-cp', str(classes), internal.replace('/', '.') + 'BackgroundJobProtocolTest', str(output / 'notification-registry')], stdout=log, stderr=subprocess.STDOUT).returncode
        if code == 0:
            result = json.loads((output / 'run.log').read_text())
    stable = all(sha(Path(p)) == value for p, value in inputs.items())
    detected = bool(args.negative_control) and code != 0 and (output / 'run.log').exists() and (
        'download job bypassed persistent notification ID mapping' in (output / 'run.log').read_text())
    record = {'kind': 'full-staged-jobservice-host-protocol', 'stageManifestSha256': sha(manifest),
              'inputFiles': inputs, 'inputsStable': stable, 'result': result, 'exitCode': code,
              'passed': stable and (detected if args.negative_control else code == 0),
              'negativeControl': args.negative_control, 'faultDetected': detected,
              'realNotificationIdRegistryExecuted': True, 'command': command, 'platformSubstitutes': True,
              'androidFrameworkExecuted': False, 'taskExpirySubstituted': True, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({'report': str(output / 'report.json'), 'passed': record['passed']}))
    if not record['passed']:
        print((output / ('run.log' if (output / 'run.log').exists() else 'compile.log')).read_text()[-8000:])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
