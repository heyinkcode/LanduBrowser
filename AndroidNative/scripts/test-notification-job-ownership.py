#!/usr/bin/env python3
"""Run staged notification routing and job lifecycle code with explicit Android substitutes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    args = parser.parse_args()
    source, stage = (p.resolve(strict=True) for p in (args.source, args.stage))
    native = Path(__file__).resolve().parents[1]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    assert not cache.is_relative_to(source) and not str(cache).startswith('/mnt/c/')
    manifest = stage / 'manifest.json'
    metadata = json.loads(manifest.read_text())
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip() == metadata['revision']
    entries = {entry['path']: entry for entry in metadata['files']}
    fixture = native / 'host-tests/notification-job/NotificationJobOwnershipTest.java'
    inputs = {str(p): sha(p) for p in (manifest, fixture, Path(__file__).resolve())}
    prefix = 'org/chromium/chrome/browser/notifications/'
    files = {}
    for name in ('NotificationJobService', 'NotificationJobServiceImpl', 'NotificationServiceImpl'):
        relative = 'chrome/android/java/src/' + prefix + name + '.java'
        staged = stage / 'files' / relative
        assert sha(staged) == entries[relative]['sha256']
        assert sha(source / relative) == entries[relative]['originalSha256']
        inputs[str(staged)] = sha(staged)
        files[prefix + name + '.java'] = staged.read_text()
    utils = stage / 'files/base/android/java/src/org/chromium/base/IntentUtils.java'
    assert sha(utils) == entries['base/android/java/src/org/chromium/base/IntentUtils.java']['sha256']
    inputs[str(utils)] = sha(utils)
    text = utils.read_text()
    start = text.index('    public static final class LanduEnvironment {')
    ownership = text[start:text.index('\n    }', start) + len('\n    }')]
    constants = set(re.findall(r'NotificationConstants\.([A-Z_]+)', '\n'.join(files.values())))
    files.update({
        prefix + 'NotificationConstants.java': 'package org.chromium.chrome.browser.notifications; public class NotificationConstants {\n' +
            '\n'.join(f'public static final String {name}="{name}";' for name in sorted(constants)) + '\n}',
        'org/chromium/base/IntentUtils.java': 'package org.chromium.base; import android.content.Intent; import org.chromium.build.annotations.Nullable; public class IntentUtils {\n' + ownership + '\n}',
        'org/chromium/build/annotations/Nullable.java': 'package org.chromium.build.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}',
        'org/chromium/build/annotations/NullMarked.java': 'package org.chromium.build.annotations; public @interface NullMarked {}',
        'org/chromium/build/annotations/IdentifierNameString.java': 'package org.chromium.build.annotations; public @interface IdentifierNameString {}',
        'androidx/annotation/RequiresApi.java': 'package androidx.annotation; public @interface RequiresApi {int value();}',
        'org/chromium/build/NullUtil.java': 'package org.chromium.build; public class NullUtil {public static <T> T assumeNonNull(T value) {return java.util.Objects.requireNonNull(value);}}',
        'android/os/PersistableBundle.java': '''package android.os; public class PersistableBundle {
 public final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public PersistableBundle(){} public PersistableBundle(PersistableBundle b){values.putAll(b.values);}
 public void putString(String k,String v){values.put(k,v);}public String getString(String k){return (String)values.get(k);}
 public void putInt(String k,int v){values.put(k,v);}public int getInt(String k,int d){return values.containsKey(k)?(Integer)values.get(k):d;}
 public void putBoolean(String k,boolean v){values.put(k,v);}public boolean getBoolean(String k,boolean d){return values.containsKey(k)?(Boolean)values.get(k):d;}
 public void putLong(String k,long v){values.put(k,v);}public boolean containsKey(String k){return values.containsKey(k);}
}''',
        'android/os/Bundle.java': 'package android.os; public class Bundle extends PersistableBundle {public Bundle(PersistableBundle b){super(b);}}',
        'android/content/Intent.java': '''package android.content; public class Intent {
 public static final int FILL_IN_IDENTIFIER=0x100;private String action,identifier;public final android.os.PersistableBundle extras=new android.os.PersistableBundle();
 public Intent(){}public Intent(String a){action=a;}public Intent(Intent i){action=i.action;identifier=i.identifier;extras.values.putAll(i.extras.values);}
 public Intent setIdentifier(String s){identifier=s;return this;}public String getIdentifier(){return identifier;}
 public String getAction(){return action;}public Intent setAction(String s){action=s;return this;}
 public Intent putExtra(String k,String v){extras.putString(k,v);return this;}public String getStringExtra(String k){return extras.getString(k);}
 public int getIntExtra(String k,int d){return extras.getInt(k,d);}public boolean getBooleanExtra(String k,boolean d){return extras.getBoolean(k,d);}
 public boolean hasExtra(String k){return extras.containsKey(k);}public Intent putExtras(android.os.Bundle b){extras.values.putAll(b.values);return this;}
}''',
        'android/content/Context.java': '''package android.content; public class Context {
 public static final String JOB_SCHEDULER_SERVICE="jobscheduler";public android.app.job.JobScheduler scheduler=new android.app.job.JobScheduler();public boolean failService;
 public Object getSystemService(String name){if(failService)throw new SecurityException();return scheduler;}
}''',
        'android/content/ComponentName.java': 'package android.content; public class ComponentName {public ComponentName(Context c,Class<?> t){}}',
        'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
        'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=35;} public static class VERSION_CODES {public static final int S=31,UPSIDE_DOWN_CAKE=34;}}',
        'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long elapsedRealtime(){return 12345;}}',
        'android/os/Looper.java': 'package android.os; public class Looper {public static Looper getMainLooper(){return new Looper();}}',
        'android/os/Handler.java': '''package android.os; public class Handler {
 private static final java.util.List<java.util.Map.Entry<Handler,Runnable>> queue=new java.util.ArrayList<>();public static boolean acceptPosts=true;
 public Handler(Looper l){}public boolean post(Runnable r){if(!acceptPosts)return false;queue.add(new java.util.AbstractMap.SimpleImmutableEntry<>(this,r));return true;}
 public void removeCallbacks(Runnable r){queue.removeIf(e->e.getKey()==this&&e.getValue()==r);}
 public void removeCallbacksAndMessages(Object t){queue.removeIf(e->e.getKey()==this);}
 public static java.util.List<Runnable> snapshot(){java.util.List<Runnable> out=new java.util.ArrayList<>();for(var e:queue)out.add(e.getValue());return out;}
 public static void drain(){var pending=snapshot();queue.clear();for(var r:pending)r.run();}
}''',
        'android/app/job/JobInfo.java': '''package android.app.job; public class JobInfo {
 public int id;public android.os.PersistableBundle extras;public boolean expedited;public long deadline=-1;
 public static class Builder {private final JobInfo j=new JobInfo();public Builder(int id,android.content.ComponentName c){j.id=id;}
 public Builder setExtras(android.os.PersistableBundle b){j.extras=new android.os.PersistableBundle(b);return this;}
 public Builder setExpedited(boolean b){j.expedited=b;return this;}public Builder setOverrideDeadline(long d){j.deadline=d;return this;}
 public Builder setImportantWhileForeground(boolean b){return this;}public JobInfo build(){return j;}}
}''',
        'android/app/job/JobScheduler.java': '''package android.app.job; import java.util.*;public class JobScheduler {
 public static final int RESULT_SUCCESS=1;public final Map<String,Map<Integer,JobInfo>> queues;public final String namespace;public boolean failNamespace;
 public JobScheduler(){this(new HashMap<>(),"global");}private JobScheduler(Map<String,Map<Integer,JobInfo>> q,String n){queues=q;namespace=n;}
 public JobScheduler forNamespace(String n){if(failNamespace)throw new SecurityException();return new JobScheduler(queues,n);}
 public int schedule(JobInfo j){queues.computeIfAbsent(namespace,k->new HashMap<>()).put(j.id,j);return 1;}
 public JobInfo getPendingJob(int id){return queues.getOrDefault(namespace,Collections.emptyMap()).get(id);}
 public int getPendingJobReason(int id){return 0;}
}''',
        'android/app/job/JobParameters.java': '''package android.app.job; public class JobParameters {
 private final int id;private final String ns;private final android.os.PersistableBundle extras;
 public JobParameters(int i,String n,android.os.PersistableBundle e){id=i;ns=n;extras=e;}
 public int getJobId(){return id;}public String getJobNamespace(){if(android.os.Build.VERSION.SDK_INT<34)throw new AssertionError();return ns;}
 public android.os.PersistableBundle getExtras(){return extras;}
}''',
        'android/app/job/JobService.java': '''package android.app.job;public class JobService extends android.content.Context {
 public final java.util.List<JobParameters> finished=new java.util.ArrayList<>();public final java.util.List<Boolean> retries=new java.util.ArrayList<>();
 public void jobFinished(JobParameters p,boolean r){finished.add(p);retries.add(r);}
 public boolean onStartJob(JobParameters p){return false;}public boolean onStopJob(JobParameters p){return false;}public void onDestroy(){}
}''',
        'org/chromium/chrome/browser/base/SplitCompatJobService.java': '''package org.chromium.chrome.browser.base;import android.app.job.*;
 public class SplitCompatJobService extends JobService {
 private final Impl impl=new org.chromium.chrome.browser.notifications.NotificationJobServiceImpl();public int starts,stops;
 public SplitCompatJobService(String s){}public boolean onStartJob(JobParameters p){starts++;return impl.onStartJob(p);}
 public boolean onStopJob(JobParameters p){stops++;return impl.onStopJob(p);}
 public abstract static class Impl {public abstract boolean onStartJob(JobParameters p);public abstract boolean onStopJob(JobParameters p);}
}''',
        'org/chromium/chrome/browser/base/SplitCompatIntentService.java': 'package org.chromium.chrome.browser.base;public class SplitCompatIntentService {public abstract static class Impl {public abstract void onHandleIntent(android.content.Intent i);}}',
        'org/chromium/base/ContextUtils.java': 'package org.chromium.base;public class ContextUtils {public static android.content.Context context=new android.content.Context();public static android.content.Context getApplicationContext(){return context;}}',
        'org/chromium/base/Log.java': 'package org.chromium.base;public class Log {public static void i(String t,String m,Object...a){}public static void w(String t,String m,Object...a){}}',
        'org/chromium/base/ThreadUtils.java': 'package org.chromium.base;public class ThreadUtils {public static void assertOnUiThread(){}}',
        'org/chromium/base/metrics/RecordHistogram.java': 'package org.chromium.base.metrics;public class RecordHistogram {public static int calls;public static void recordBooleanHistogram(String n,boolean b){calls++;}public static void recordSparseHistogram(String n,int i){calls++;}}',
        'org/chromium/base/task/TaskTraits.java': 'package org.chromium.base.task;public class TaskTraits {public static final int UI_DEFAULT=1,BEST_EFFORT=2;}',
        'org/chromium/base/task/PostTask.java': 'package org.chromium.base.task;public class PostTask {public static void runOrPostTask(int t,Runnable r){r.run();}}',
        'org/chromium/components/background_task_scheduler/TaskIds.java': 'package org.chromium.components.background_task_scheduler;public class TaskIds {public static final int NOTIFICATION_SERVICE_JOB_ID=10,NOTIFICATION_SERVICE_PRE_UNSUBSCRIBE_JOB_ID=11;}',
        prefix + 'NotificationType.java': 'package org.chromium.chrome.browser.notifications;public class NotificationType {public static final int WEB_PERSISTENT=1;}',
        prefix + 'NotificationPlatformBridge.java': '''package org.chromium.chrome.browser.notifications;public class NotificationPlatformBridge {
 public static int preCalls,dispatches;public static android.content.Intent last;
 public static boolean dispatchNotificationEventPreNative(android.content.Intent i){preCalls++;return true;}
 public static boolean dispatchNotificationEvent(android.content.Intent i){dispatches++;last=i;return true;}
 public static String getNotificationReply(android.content.Intent i){return i.getStringExtra("reply");}
}''',
        prefix + 'TrampolineActivityTracker.java': '''package org.chromium.chrome.browser.notifications;public class TrampolineActivityTracker {
 static final TrampolineActivityTracker instance=new TrampolineActivityTracker();public static int calls;public enum JobDuration {LONG}
 public static TrampolineActivityTracker getInstance(){return instance;}public void startProcessingNewIntent(String id,JobDuration d){calls++;}public void onIntentCompleted(String id){calls++;}
}''',
        prefix + 'NotificationUmaTracker.java': '''package org.chromium.chrome.browser.notifications;public class NotificationUmaTracker {
 public static int calls;static final NotificationUmaTracker instance=new NotificationUmaTracker();public static NotificationUmaTracker getInstance(){return instance;}
 public enum IntentHandlerJobStage {ON_START_JOB,ON_STOP_JOB,SCHEDULE_JOB,SCHEDULE_JOB_FAILED,DISPATCH_EVENT,NATIVE_STARTUP}
 public void recordIntentHandlerJobStage(IntentHandlerJobStage s,String a){calls++;}
}''',
        'org/chromium/chrome/browser/init/BrowserParts.java': 'package org.chromium.chrome.browser.init;public abstract class BrowserParts {public abstract void finishNativeInitialization();}',
        'org/chromium/chrome/browser/init/EmptyBrowserParts.java': 'package org.chromium.chrome.browser.init;public class EmptyBrowserParts extends BrowserParts {public void finishNativeInitialization(){}}',
        'org/chromium/chrome/browser/init/ChromeBrowserInitializer.java': '''package org.chromium.chrome.browser.init;public class ChromeBrowserInitializer {
 public static int starts;static final ChromeBrowserInitializer instance=new ChromeBrowserInitializer();public static ChromeBrowserInitializer getInstance(){return instance;}
 public void handlePreNativeStartupAndLoadLibraries(BrowserParts p){starts++;}public void handlePostNativeStartup(boolean async,BrowserParts p){p.finishNativeInitialization();}
}''',
        'org/chromium/chrome/browser/webapps/WebappRegistry.java': 'package org.chromium.chrome.browser.webapps;public class WebappRegistry {public static WebappRegistry getInstance(){return new WebappRegistry();}public static void warmUpSharedPrefs(){}}',
        'org/chromium/components/webapps/WebappsUtils.java': 'package org.chromium.components.webapps;public class WebappsUtils {public static void prepareIsRequestPinShortcutSupported(){}}',
        prefix + 'NotificationJobOwnershipTest.java': fixture.read_text(),
    })
    output = Path(tempfile.mkdtemp(prefix='notification-job-host-', dir=cache))
    for relative, code in files.items():
        path = output / 'src' / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code)
    jdk = source / 'third_party/jdk/current/bin'
    with (output / 'compile.log').open('w') as log:
        compiled = subprocess.run([str(jdk / 'javac'), '--release', '11', '-encoding', 'UTF-8',
            '-d', str(output / 'classes'), *(str(output / 'src' / n) for n in files)], stdout=log, stderr=subprocess.STDOUT)
    result = None
    if compiled.returncode == 0:
        run = subprocess.run([str(jdk / 'java'), '-cp', str(output / 'classes'),
                              'org.chromium.chrome.browser.notifications.NotificationJobOwnershipTest'], capture_output=True, text=True)
        (output / 'run.log').write_text(run.stdout + run.stderr)
        if run.returncode == 0: result = json.loads(run.stdout)
    stable = all(sha(Path(p)) == digest for p, digest in inputs.items())
    passed = compiled.returncode == 0 and result is not None and result['passed'] and stable
    report = {'kind': 'staged-notification-job-host-protocol', 'stageManifestSha256': sha(manifest),
              'inputFiles': inputs, 'inputsStable': stable, 'compileExitCode': compiled.returncode,
              'result': result, 'passed': passed, 'fullServiceAndImplExecuted': True,
              'platformSubstitutes': True, 'androidFrameworkExecuted': False, 'deviceTested': False}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'passed': passed, 'report': str(output / 'report.json'), 'result': result}))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
