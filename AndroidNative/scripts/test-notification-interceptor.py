#!/usr/bin/env python3
"""Exercise the actual staged notification wrapper with explicit Android substitutes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def block(text, signature):
    start = text.index(signature)
    end, depth = text.index('{', start) + 1, 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--negative-control', choices=['identity', 'receiver', 'activity', 'destroy', 'flags'])
    args = parser.parse_args()
    source, stage = [p.resolve(strict=True) for p in (args.source, args.stage)]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use an independent non-C cache')
    native = Path(__file__).resolve().parents[1]
    manifest = stage / 'manifest.json'
    data = json.loads(manifest.read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != data['revision']:
        raise RuntimeError('Use the pinned Chromium revision')
    entries = {e['path']: e['sha256'] for e in data['files']}
    inputs = {str(manifest): sha(manifest), str(Path(__file__).resolve()): sha(Path(__file__).resolve())}

    def read(name, patched=True):
        p = (stage / 'files' if patched else source) / name
        if patched:
            if sha(p) != entries[name]: raise RuntimeError('Stage drift: ' + name)
        elif p.read_bytes() != subprocess.check_output(['git', '-C', str(source), 'show', 'HEAD:' + name]):
            raise RuntimeError('Upstream drift: ' + name)
        inputs[str(p)] = sha(p)
        return p.read_text()

    pkg = 'org/chromium/chrome/browser/notifications/'
    common = 'org/chromium/components/browser_ui/notifications/'
    upstream_root = 'chrome/browser/notifications/android/java/src/'
    shared_root = 'components/browser_ui/notifications/android/java/src/'
    ownership = block(read('base/android/java/src/org/chromium/base/IntentUtils.java'),
                      'public static final class LanduEnvironment')
    uma = read(upstream_root + pkg + 'NotificationUmaTracker.java', False)
    fixture = native / 'host-tests/notification-interceptor/NotificationInterceptorTest.java'
    inputs[str(fixture)] = sha(fixture)
    files = {
        pkg + 'NotificationIntentInterceptor.java': read(upstream_root + pkg + 'NotificationIntentInterceptor.java'),
        pkg + 'NotificationConstants.java': read(upstream_root + pkg + 'NotificationConstants.java', False),
        common + 'PendingIntentProvider.java': read(shared_root + common + 'PendingIntentProvider.java'),
        common + 'NotificationMetadata.java': read(shared_root + common + 'NotificationMetadata.java'),
        pkg + fixture.name: fixture.read_text(),
        'org/chromium/base/IntentUtils.java': '''package org.chromium.base;import android.content.Intent;
import android.app.PendingIntent;import org.chromium.build.annotations.Nullable;
public class IntentUtils {public static int getPendingIntentMutabilityFlag(boolean mutable){return mutable?PendingIntent.FLAG_MUTABLE:PendingIntent.FLAG_IMMUTABLE;}
''' + ownership + '\n}',
        'android/content/Intent.java': '''package android.content;import java.util.*;
public class Intent {public static final int FILL_IN_IDENTIFIER=256;
public static final int FLAG_ACTIVITY_NO_USER_ACTION=0x40000,FLAG_ACTIVITY_NEW_TASK=0x10000000,
FLAG_ACTIVITY_NEW_DOCUMENT=0x80000,FLAG_ACTIVITY_MULTIPLE_TASK=0x8000000,FLAG_RECEIVER_FOREGROUND=0x10000000;
public final Map<String,Object> extras=new HashMap<>();public String component;private String action,identifier;public int flags;
public Intent(){}public Intent(String a){action=a;}public Intent(Context c,Class<?> cls){component=cls.getName();}
public Intent setClassName(Context c,String name){component=name;return this;}
public Intent(Intent i){action=i.action;identifier=i.identifier;component=i.component;flags=i.flags;extras.putAll(i.extras);}
public String getIdentifier(){return identifier;}public Intent setIdentifier(String s){identifier=s;return this;}
public String getAction(){return action;}public Intent setAction(String a){action=a;return this;}
public Intent addFlags(int f){flags|=f;return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
public int getIntExtra(String k,int d){Object v=extras.get(k);return v instanceof Integer?(int)v:d;}
public long getLongExtra(String k,long d){Object v=extras.get(k);return v instanceof Long?(long)v:d;}
@SuppressWarnings("unchecked") public <T> T getParcelableExtra(String k){return (T)extras.get(k);}}
''',
        'android/content/Context.java': '''package android.content;public class Context {
public static class ApplicationInfo{public int targetSdkVersion=36;}private static final ApplicationInfo INFO=new ApplicationInfo();
public ApplicationInfo getApplicationInfo(){return INFO;}public Context getApplicationContext(){return this;}}
''',
        'android/content/BroadcastReceiver.java': 'package android.content;public abstract class BroadcastReceiver{public int result;public boolean isOrderedBroadcast(){return true;}public void setResultCode(int r){result=r;}public abstract void onReceive(Context c,Intent i);}',
        'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle{private final Map<String,CharSequence> strings=new HashMap<>();
public void putCharSequence(String k,CharSequence v){strings.put(k,v);}public CharSequence getCharSequence(String k){return strings.get(k);}}''',
        'android/os/Build.java': '''package android.os;public class Build{public static class VERSION{public static int SDK_INT=36;}
public static class VERSION_CODES{public static final int S=31,VANILLA_ICE_CREAM=35;}}''',
        'android/view/MotionEvent.java': 'package android.view;public class MotionEvent{public static final int ACTION_DOWN=0;public int getAction(){return ACTION_DOWN;}}',
        'android/app/Activity.java': '''package android.app;import android.content.*;import android.os.Bundle;import android.view.MotionEvent;
public class Activity extends Context{public static final int RESULT_OK=-1;private Intent intent;public boolean finished;public int contents,superDestroy;
protected void onCreate(Bundle b){}protected void onDestroy(){superDestroy++;}public void setIntent(Intent i){intent=i;}public Intent getIntent(){return intent;}
public void finish(){finished=true;}public void setContentView(int view){contents++;}public boolean onTouchEvent(MotionEvent e){return false;}}
''',
        'android/app/PendingIntent.java': '''package android.app;import android.content.*;import java.util.*;
public class PendingIntent {
public static final int FLAG_UPDATE_CURRENT=0x8000000,FLAG_MUTABLE=0x2000000,FLAG_IMMUTABLE=0x4000000;
static final Map<String,PendingIntent> registry=new HashMap<>();public static int sends;public final String key,kind;public final int flags;
public Intent intent,lastFill;public boolean cancelled;
private PendingIntent(String key,String kind,Intent i,int flags){this.key=key;this.kind=kind;this.intent=new Intent(i);this.flags=flags;}
private static PendingIntent get(String kind,int code,Intent i,int flags){
String key=kind+"|"+code+"|"+i.component+"|"+i.getAction()+"|"+i.getIdentifier()+"|"+(flags&(FLAG_MUTABLE|FLAG_IMMUTABLE));
PendingIntent existing=registry.get(key);if(existing!=null){if((flags&FLAG_UPDATE_CURRENT)!=0)existing.intent=new Intent(i);return existing;}
PendingIntent p=new PendingIntent(key,kind,i,flags);registry.put(key,p);return p;}
public static PendingIntent getBroadcast(Context c,int r,Intent i,int f){return get("broadcast",r,i,f);}
public static PendingIntent getService(Context c,int r,Intent i,int f){return get("service",r,i,f);}
public static PendingIntent getActivity(Context c,int r,Intent i,int f){return get("activity",r,i,f);}
public static class CanceledException extends Exception{}
public void send(Context c,int r,Intent fill)throws CanceledException{if(cancelled)throw new CanceledException();sends++;lastFill=new Intent(fill);}}
''',
        'android/app/RemoteInput.java': '''package android.app;public class RemoteInput{public static android.os.Bundle getResultsFromIntent(android.content.Intent i){return (android.os.Bundle)i.extras.get("host.remoteInput");}}''',
        'android/app/Notification.java': 'package android.app;public class Notification{public static final int FLAG_FOREGROUND_SERVICE=64;public int flags;}',
        'android/service/notification/StatusBarNotification.java': '''package android.service.notification;public class StatusBarNotification{public String getTag(){return null;}
public int getId(){return 0;}public android.app.Notification getNotification(){return new android.app.Notification();}}''',
        'androidx/core/app/NotificationManagerCompat.java': '''package androidx.core.app;public class NotificationManagerCompat{public void cancel(String tag,int id){}
public void cancel(int id){}public java.util.List<android.service.notification.StatusBarNotification> getActiveNotifications(){return java.util.Collections.emptyList();}}''',
        'androidx/core/app/PendingIntentCompat.java': 'package androidx.core.app;import java.lang.annotation.*;public class PendingIntentCompat{@Target({ElementType.TYPE_USE,ElementType.FIELD,ElementType.METHOD})public @interface Flags{}}',
        'org/chromium/base/ApplicationStatus.java': '''package org.chromium.base;import java.util.*;import android.app.Activity;
public class ApplicationStatus{public static final List<Activity> activities=new ArrayList<>();public static List<Activity> getRunningActivities(){return activities;}
public static int getStateForActivity(Activity a){return ActivityState.RESUMED;}}''',
        'org/chromium/base/ActivityState.java': 'package org.chromium.base;public @interface ActivityState{int RESUMED=3,PAUSED=4;}',
        'org/chromium/base/ContextUtils.java': 'package org.chromium.base;public class ContextUtils{public static android.content.Context getApplicationContext(){return new android.content.Context();}}',
        'org/chromium/base/Log.java': 'package org.chromium.base;public class Log{public static void e(String t,String m,Object... a){}public static void d(String t,String m,Object... a){}}',
        'org/chromium/base/TimeUtils.java': 'package org.chromium.base;public class TimeUtils{public static long elapsedRealtimeMillis(){return 100;}}',
        'org/chromium/base/metrics/RecordHistogram.java': '''package org.chromium.base.metrics;public class RecordHistogram{public static int events;
public static void recordEnumeratedHistogram(String n,int v,int c){events++;}public static void recordTimesHistogram(String n,long v){events++;}}''',
        'org/chromium/chrome/browser/base/SplitCompatIntentService.java': '''package org.chromium.chrome.browser.base;import android.content.Intent;
public class SplitCompatIntentService{public abstract static class Impl{protected abstract void onHandleIntent(Intent intent);}}''',
        'org/chromium/chrome/browser/flags/ChromeFeatureList.java': '''package org.chromium.chrome.browser.flags;public class ChromeFeatureList{
public static class Flag{public boolean enabled;public boolean isEnabled(){return enabled;}}
public static final Flag sForceTranslucentNotificationTrampoline=new Flag(),sNotificationTrampolineNoNewTask=new Flag();}''',
        'org/chromium/ui/widget/Toast.java': '''package org.chromium.ui.widget;public class Toast{public static final int LENGTH_SHORT=0;
public static Toast makeText(android.content.Context c,int t,int d){return new Toast();}public void show(){}}''',
        pkg + 'R.java': 'package org.chromium.chrome.browser.notifications;public class R{public static class layout{public static final int notification_trampoline=1;}public static class string{public static final int notification_trampoline_toast_message=2;}}',
        pkg + 'TrampolineActivityTracker.java': '''package org.chromium.chrome.browser.notifications;import android.app.Activity;
public class TrampolineActivityTracker{public static int accesses;private Activity tracked;private static final TrampolineActivityTracker I=new TrampolineActivityTracker();
public static TrampolineActivityTracker getInstance(){accesses++;return I;}public boolean tryTrackActivity(Activity a){tracked=a;return true;}
public void finishTrackedActivity(){if(tracked!=null)tracked.finish();tracked=null;}public void onNotificationIntentStarted(){}}
''',
        pkg + 'NotificationUmaTracker.java': 'package org.chromium.chrome.browser.notifications;public class NotificationUmaTracker{'
            + block(uma, 'public @interface SystemNotificationType') + block(uma, 'public @interface ActionType') + '''
public static int events;private static final NotificationUmaTracker I=new NotificationUmaTracker();public static NotificationUmaTracker getInstance(){return I;}
public void onNotificationContentClick(int t,long time){events++;}public void onNotificationDismiss(int t,long time){events++;}
public void onNotificationActionClick(int action,int type,long time){events++;}}''',
    }
    for annotation in ('org/chromium/build/annotations/Nullable', 'org/chromium/build/annotations/NullMarked',
                       'androidx/annotation/IntDef', 'android/annotation/SuppressLint'):
        package, name = annotation.rsplit('/', 1)
        value = 'String[] value();' if name == 'SuppressLint' else 'long[] value() default {};'
        files[annotation + '.java'] = ('package ' + package.replace('/', '.') + ';import java.lang.annotation.*;'
            '@Target({ElementType.TYPE_USE,ElementType.TYPE,ElementType.METHOD,ElementType.PARAMETER,ElementType.FIELD})'
            'public @interface ' + name + '{' + value + '}')
    if args.negative_control:
        key = pkg + 'NotificationIntentInterceptor.java'
        before, after = {
            'identity': ('intent = IntentUtils.LanduEnvironment.scopePendingIntent(intent);', '// Fault: leave outer identity unscoped.'),
            'receiver': ('if (intent == null || !IntentUtils.LanduEnvironment.owns(intent)) return false;', 'if (intent == null) return false;'),
            'activity': ('if (!IntentUtils.LanduEnvironment.owns(getIntent())) {', 'if (false) {'),
            'destroy': ('if (!mLanduAcceptedIntent) {', 'if (false) {'),
            'flags': ('flags = IntentUtils.LanduEnvironment.protectPendingIntentFlags(flags);', '// Fault: permit identifier fill-in.'),
        }[args.negative_control]
        if files[key].count(before) != 1: raise RuntimeError('Negative-control anchor drift')
        files[key] = files[key].replace(before, after)
    out = Path(tempfile.mkdtemp(prefix='notification-interceptor-' + (args.negative_control or 'host') + '-', dir=cache))
    for name, code in files.items():
        p = out/'src'/name;p.parent.mkdir(parents=True, exist_ok=True);p.write_text(code)
    jdk = source/'third_party/jdk/current/bin'
    command = [str(jdk/'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(out/'classes'), *(str(out/'src'/n) for n in files)]
    compile_run = subprocess.run(command, capture_output=True, text=True)
    (out/'compile.log').write_text(compile_run.stdout+compile_run.stderr)
    runs=[]
    if compile_run.returncode == 0:
        for owner in ['A','B']:
            run = subprocess.run([str(jdk/'java'), '-ea', '-cp', str(out/'classes'), pkg.replace('/', '.')+'NotificationInterceptorTest', owner], capture_output=True, text=True)
            (out/('run-'+owner+'.log')).write_text(run.stdout+run.stderr)
            runs.append({'owner': owner, 'exitCode': run.returncode, 'result': json.loads(run.stdout) if run.returncode==0 else None})
    expected={'identity':'uninitialized wrapper factory accepted an ownerless notification',
              'receiver':'foreign wrapper accessed metrics or dispatched an operation',
              'activity':'foreign activity', 'destroy':'rejected activity recorded destruction metrics',
              'flags':'outer factory did not protect legacy provider flags'}.get(args.negative_control)
    detected = bool(args.negative_control) and len(runs)==2 and all(r['exitCode']!=0 and expected in (out/('run-'+r['owner']+'.log')).read_text() for r in runs)
    disjoint = len(runs)==2 and all(r['exitCode']==0 and r['result']['passed'] for r in runs) and set(runs[0]['result']['keys']).isdisjoint(runs[1]['result']['keys'])
    stable=all(sha(Path(p))==digest for p,digest in inputs.items())
    passed=stable and compile_run.returncode==0 and (detected if args.negative_control else disjoint)
    report={'kind':'staged-notification-interceptor-host-protocol','passed':passed,'stageManifestSha256':sha(manifest),
            'inputFiles':inputs,'inputsStable':stable,'compileExitCode':compile_run.returncode,'runs':runs,'command':command,
            'crossProcessIdentitiesDisjoint':disjoint,'negativeControl':args.negative_control,'faultDetected':detected,
            'fullInterceptorExecuted':True,'realCommonPendingIntentProviderExecuted':True,'platformSubstitutes':True,
            'androidFrameworkExecuted':False,'deviceTested':False,'exclusions':['Android OS PendingIntent registry and notification shade',
              'actual service/Activity task lifecycle','metrics persistence and translucent-window timing','closed environment launch routing']}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':passed,'report':str(out/'report.json'),'assertions':[r['result']['assertions'] if r['result'] else None for r in runs],
                      'crossProcessIdentitiesDisjoint':disjoint,'faultDetected':detected}))
    if not passed:
        print((out/'compile.log').read_text()[-2500:])
        for r in runs:print((out/('run-'+r['owner']+'.log')).read_text()[-4000:])
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
