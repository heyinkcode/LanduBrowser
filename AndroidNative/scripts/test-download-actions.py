#!/usr/bin/env python3
"""Run the complete staged download notification service with explicit platform substitutes."""
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


def nested_class(text, signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--stage', type=Path, required=True)
    parser.add_argument('--negative-control', choices=['owner', 'destroyed', 'eager', 'internal'])
    args = parser.parse_args()
    source, stage = [p.resolve(strict=True) for p in (args.source, args.stage)]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use an independent non-C test cache')
    native = Path(__file__).resolve().parents[1]
    manifest = stage / 'manifest.json'
    meta = json.loads(manifest.read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != meta['revision']:
        raise RuntimeError('Use the pinned source revision')
    entries = {e['path']: e['sha256'] for e in meta['files']}
    inputs = {str(manifest): sha(manifest), str(Path(__file__).resolve()): sha(Path(__file__).resolve())}

    def staged(name):
        path = stage / 'files' / name
        if sha(path) != entries[name]: raise RuntimeError('Stage drift: ' + name)
        inputs[str(path)] = sha(path)
        return path.read_text()

    def upstream(name):
        path = source / name
        if path.read_bytes() != subprocess.check_output(['git', '-C', str(source), 'show', 'HEAD:' + name]):
            raise RuntimeError('Upstream drift: ' + name)
        inputs[str(path)] = sha(path)
        return path.read_text()

    pkg = 'org/chromium/chrome/browser/download/'
    impl = staged('chrome/android/java/src/' + pkg + 'DownloadBroadcastManagerImpl.java')
    identity = nested_class(staged('base/android/java/src/org/chromium/base/IntentUtils.java'),
                            'public static final class LanduEnvironment')
    constants = upstream('chrome/android/java/src/' + pkg + 'DownloadNotificationService.java')
    declarations = '\n'.join('public static final String ' + m.group(1) + '=' + m.group(2) + ';'
                            for m in re.finditer(r'static final String (\w+)\s*=\s*("[^"\n]*")\s*;', constants))
    paths = subprocess.check_output(['rg', '--files', '-g', 'NotificationConstants.java'], cwd=source, text=True).splitlines()
    if len(paths) != 1: raise RuntimeError('Ambiguous notification constants')
    note_constants = upstream(paths[0])
    note_value = re.search(r'EXTRA_NOTIFICATION_ID\s*=\s*("[^"\n]*")\s*;', note_constants).group(1)
    fixture = native / 'host-tests/download-actions/DownloadActionOwnershipTest.java'
    inputs[str(fixture)] = sha(fixture)
    files = {
        pkg + 'DownloadBroadcastManagerImpl.java': impl,
        pkg + fixture.name: fixture.read_text(),
        'org/chromium/base/IntentUtils.java': '''package org.chromium.base;
import android.content.Intent;import org.chromium.build.annotations.Nullable;
public class IntentUtils {
public static String safeGetStringExtra(Intent i,String k){Object v=i.extras.get(k);return v instanceof String?(String)v:null;}
public static int safeGetIntExtra(Intent i,String k,int d){Object v=i.extras.get(k);return v instanceof Integer?(int)v:d;}
public static boolean safeGetBooleanExtra(Intent i,String k,boolean d){Object v=i.extras.get(k);return v instanceof Boolean?(boolean)v:d;}
@SuppressWarnings("unchecked") public static <T> T safeGetParcelableExtra(Intent i,String k){return (T)i.extras.get(k);}
''' + identity + '\n}',
        'android/content/Intent.java': '''package android.content;import java.util.*;
public class Intent {
public static final int FILL_IN_IDENTIFIER=256;public static final String EXTRA_ORIGINATING_URI="origin",EXTRA_REFERRER="referrer";
public final Map<String,Object> extras=new HashMap<>();private String action,identifier;private ComponentName component;
public Intent(){}public Intent(String a){action=a;}public Intent(Intent i){action=i.action;identifier=i.identifier;component=i.component;extras.putAll(i.extras);}
public String getIdentifier(){return identifier;}public Intent setIdentifier(String i){identifier=i;return this;}
public String getAction(){return action;}public Intent setAction(String a){action=a;return this;}
public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public boolean hasExtra(String k){return extras.containsKey(k);}
public Intent removeExtra(String k){extras.remove(k);return this;}public long getLongExtra(String k,long d){Object v=extras.get(k);return v instanceof Long?(long)v:d;}
public long[] getLongArrayExtra(String k){return (long[])extras.get(k);}public Intent setComponent(ComponentName c){component=c;return this;}
public ComponentName getComponent(){return component;}}
''',
        'android/content/Context.java': '''package android.content;public class Context {
public Intent started;public int starts;public ComponentName startService(Intent i){started=i;starts++;return i.getComponent();}}''',
        'android/content/ComponentName.java': 'package android.content;public class ComponentName{public ComponentName(Context c,Class<?> t){}}',
        'android/app/Service.java': '''package android.app;public class Service extends android.content.Context {
public static final int START_NOT_STICKY=2;public int stops,lastStart,unconditionalStops;
public boolean stopSelfResult(int id){stops++;lastStart=id;return true;}public void stopSelf(){stops++;unconditionalStops++;}}''',
        'android/app/DownloadManager.java': '''package android.app;public class DownloadManager {
public static final String ACTION_NOTIFICATION_CLICKED="android.intent.action.DOWNLOAD_NOTIFICATION_CLICKED",EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS="ids";}''',
        'android/net/Uri.java': 'package android.net;public class Uri {}',
        'android/os/IBinder.java': 'package android.os;public interface IBinder {}',
        'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {
static final List<Runnable> pending=new ArrayList<>();public boolean postDelayed(Runnable r,long delay){pending.add(r);return true;}
public void removeCallbacks(Runnable r){pending.removeIf(p->p==r);}public static void drain(){List<Runnable> p=new ArrayList<>(pending);pending.clear();p.forEach(Runnable::run);}}''',
        'org/chromium/base/SplitCompatService.java': '''package org.chromium.base;import android.content.Intent;import android.os.IBinder;
public class SplitCompatService {public abstract static class Impl {
public final android.app.Service hostService=new android.app.Service();protected android.app.Service getService(){return hostService;}
public int onStartCommand(Intent i,int flags,int start){return 0;}public void onDestroy(){}public abstract IBinder onBind(Intent i);}}''',
        'org/chromium/base/ContentUriUtils.java': 'package org.chromium.base;public class ContentUriUtils {public static boolean isContentUri(String p){return p!=null&&p.startsWith("content:");}}',
        'org/chromium/base/ContextUtils.java': 'package org.chromium.base;public class ContextUtils {public static android.content.Context getApplicationContext(){return new android.content.Context();}}',
        'org/chromium/base/TimeUtils.java': 'package org.chromium.base;public class TimeUtils {public static long elapsedRealtimeMillis(){return 100L;}}',
        'org/chromium/base/metrics/RecordHistogram.java': 'package org.chromium.base.metrics;public class RecordHistogram {public static void recordEnumeratedHistogram(String n,int v,int c){}public static void recordTimesHistogram(String n,long v){}}',
        'org/chromium/build/NullUtil.java': 'package org.chromium.build;public class NullUtil {public static <T> T assertNonNull(T v){if(v==null)throw new AssertionError("null upstream argument");return v;}}',
        pkg + 'DownloadBroadcastManager.java': 'package org.chromium.chrome.browser.download;public class DownloadBroadcastManager {}',
        pkg + 'DownloadNotificationService.java': '''package org.chromium.chrome.browser.download;public class DownloadNotificationService {
''' + declarations + '''
public static int accesses,updates;static final DownloadNotificationService INSTANCE=new DownloadNotificationService();
public static DownloadNotificationService getInstance(){accesses++;return INSTANCE;}
public void notifyDownloadPaused(Object... args){updates++;}public void notifyDownloadCanceled(Object... args){updates++;}
public void notifyDownloadPending(Object... args){updates++;}}
''',
        pkg + 'DownloadSharedPreferenceHelper.java': '''package org.chromium.chrome.browser.download;
import org.chromium.components.offline_items_collection.ContentId;
public class DownloadSharedPreferenceHelper {public static int accesses,writes;public static DownloadSharedPreferenceEntry entry;
static final DownloadSharedPreferenceHelper INSTANCE=new DownloadSharedPreferenceHelper();
public static DownloadSharedPreferenceHelper getInstance(){accesses++;return INSTANCE;}
public DownloadSharedPreferenceEntry getDownloadSharedPreferenceEntry(ContentId id){accesses++;return entry;}
public void addOrReplaceSharedPreferenceEntry(DownloadSharedPreferenceEntry e){writes++;entry=e;}}
''',
        pkg + 'DownloadSharedPreferenceEntry.java': '''package org.chromium.chrome.browser.download;
import org.chromium.components.offline_items_collection.ContentId;import org.chromium.chrome.browser.profiles.OtrProfileId;
public class DownloadSharedPreferenceEntry {public final ContentId id;public final int notificationId;
public final OtrProfileId otrProfileId;public final boolean canDownloadWhileMetered,isTransient;public final String fileName;
public DownloadSharedPreferenceEntry(ContentId id,int n,OtrProfileId p,boolean metered,String f,boolean auto,boolean trans){
this.id=id;notificationId=n;otrProfileId=p;canDownloadWhileMetered=metered;fileName=f;isTransient=trans;}
public DownloadItem buildDownloadItem(){return new DownloadItem(false,new DownloadInfo());}}
''',
        pkg + 'DownloadServiceDelegate.java': '''package org.chromium.chrome.browser.download;
import org.chromium.components.offline_items_collection.*;import org.chromium.chrome.browser.profiles.OtrProfileId;
public class DownloadServiceDelegate {public static int calls,destroyed;public static String lastAction,lastId;
private void action(String a,ContentId id){calls++;lastAction=a;lastId=id.id;}
public void cancelDownload(ContentId id,OtrProfileId p){action(DownloadNotificationService.ACTION_DOWNLOAD_CANCEL,id);}
public void pauseDownload(ContentId id,OtrProfileId p){action(DownloadNotificationService.ACTION_DOWNLOAD_PAUSE,id);}
public void resumeDownload(ContentId id,DownloadItem item){action(DownloadNotificationService.ACTION_DOWNLOAD_RESUME,id);}
public void openItem(OpenParams p,ContentId id){action(DownloadNotificationService.ACTION_DOWNLOAD_OPEN,id);}
public void destroyServiceDelegate(){destroyed++;}}
''',
        pkg + 'items/OfflineContentAggregatorNotificationBridgeUiFactory.java': '''package org.chromium.chrome.browser.download.items;
import org.chromium.chrome.browser.download.DownloadServiceDelegate;
public class OfflineContentAggregatorNotificationBridgeUiFactory {static final DownloadServiceDelegate D=new DownloadServiceDelegate();
public static DownloadServiceDelegate instance(){return D;}}
''',
        pkg + 'DownloadUtils.java': '''package org.chromium.chrome.browser.download;import android.content.Intent;
import org.chromium.chrome.browser.profiles.OtrProfileId;
public class DownloadUtils {public static int accesses;public static boolean doesProfileExistFromIntent(Intent i){accesses++;return true;}
public static OtrProfileId getOtrProfileIdFromIntent(Intent i){accesses++;return null;}}
''',
        pkg + 'DownloadManagerService.java': '''package org.chromium.chrome.browser.download;
public class DownloadManagerService {public static int opens,homeOpens;public static boolean isActiveNetworkMetered(android.content.Context c){return true;}
public static void openDownloadsPage(Object... args){homeOpens++;}public static void openDownloadedContent(Object... args){opens++;}}
''',
        pkg + 'DownloadManagerBridge.java': '''package org.chromium.chrome.browser.download;import java.util.*;import java.util.function.Consumer;
public class DownloadManagerBridge {public static int queries;static final List<Consumer<Result>> pending=new ArrayList<>();
public static class Result {public String contentUri;}
public static void queryDownloadResult(long id,Consumer<Result> callback){queries++;pending.add(callback);}
public static void finish(boolean exists){List<Consumer<Result>> p=new ArrayList<>(pending);pending.clear();Result r=new Result();r.contentUri=exists?"content://owned":null;p.forEach(c->c.accept(r));}}
''',
        pkg + 'DownloadInfo.java': 'package org.chromium.chrome.browser.download;public class DownloadInfo{public static class Builder{public Builder setDownloadGuid(String id){return this;}public Builder setOtrProfileId(Object p){return this;}public DownloadInfo build(){return new DownloadInfo();}}}',
        pkg + 'DownloadItem.java': 'package org.chromium.chrome.browser.download;public class DownloadItem{public DownloadItem(boolean b,DownloadInfo i){}}',
        pkg + 'DownloadStartupUtils.java': 'package org.chromium.chrome.browser.download;public class DownloadStartupUtils{public static void ensureDownloadSystemInitialized(boolean b,boolean o){DownloadUtils.accesses++;}}',
        pkg + 'DownloadConstants.java': 'package org.chromium.chrome.browser.download;public class DownloadConstants{public static final long INVALID_DOWNLOAD_ID=-1;}',
        pkg + 'DownloadOpenSource.java': 'package org.chromium.chrome.browser.download;public class DownloadOpenSource{public static final int NOTIFICATION=1;}',
        'org/chromium/chrome/browser/notifications/NotificationConstants.java': 'package org.chromium.chrome.browser.notifications;public class NotificationConstants{public static final String EXTRA_NOTIFICATION_ID=' + note_value + ';}',
        'org/chromium/chrome/browser/notifications/TrampolineActivityTracker.java': '''package org.chromium.chrome.browser.notifications;
public class TrampolineActivityTracker{public static class JobDuration{public static final int IMMEDIATE=1;}static final TrampolineActivityTracker I=new TrampolineActivityTracker();
public static TrampolineActivityTracker getInstance(){return I;}public void startProcessingNewIntent(String id,int time){}public void onIntentCompleted(String id){}}
''',
        'org/chromium/chrome/browser/profiles/OtrProfileId.java': 'package org.chromium.chrome.browser.profiles;public class OtrProfileId{}',
        'org/chromium/chrome/browser/init/BrowserParts.java': 'package org.chromium.chrome.browser.init;public interface BrowserParts{void finishNativeInitialization();boolean startMinimalBrowser();}',
        'org/chromium/chrome/browser/init/EmptyBrowserParts.java': 'package org.chromium.chrome.browser.init;public class EmptyBrowserParts implements BrowserParts{public void finishNativeInitialization(){}public boolean startMinimalBrowser(){return false;}}',
        'org/chromium/chrome/browser/init/ChromeBrowserInitializer.java': '''package org.chromium.chrome.browser.init;import java.util.*;
public class ChromeBrowserInitializer {public static int starts;static final List<BrowserParts> pending=new ArrayList<>();static final ChromeBrowserInitializer I=new ChromeBrowserInitializer();
public static ChromeBrowserInitializer getInstance(){return I;}public void handlePreNativeStartupAndLoadLibraries(BrowserParts p){starts++;}
public void handlePostNativeStartup(boolean async,BrowserParts p){pending.add(p);}public static void finish(){List<BrowserParts> p=new ArrayList<>(pending);pending.clear();p.forEach(BrowserParts::finishNativeInitialization);}}
''',
        'org/chromium/content_public/browser/BrowserStartupController.java': 'package org.chromium.content_public.browser;public class BrowserStartupController{public static BrowserStartupController getInstance(){return new BrowserStartupController();}public boolean isFullBrowserStarted(){return true;}}',
        'org/chromium/components/download/DownloadDangerType.java': 'package org.chromium.components.download;public class DownloadDangerType{public static final int NOT_DANGEROUS=0,MAX=5;}',
        'org/chromium/components/browser_ui/util/DownloadUtils.java': 'package org.chromium.components.browser_ui.util;public class DownloadUtils{public static boolean shouldDisplayDownloadAsDangerous(int d,int s){return d!=0;}}',
    }
    offline = 'org/chromium/components/offline_items_collection/'
    files[offline + 'ContentId.java'] = 'package org.chromium.components.offline_items_collection;public class ContentId{public final String id,namespace;public ContentId(String n,String i){namespace=n;id=i;}}'
    files[offline + 'OpenParams.java'] = 'package org.chromium.components.offline_items_collection;public class OpenParams{public boolean openInIncognito;public OpenParams(int l){}}'
    files[offline + 'LegacyHelpers.java'] = 'package org.chromium.components.offline_items_collection;public class LegacyHelpers{public static boolean isLegacyDownload(ContentId id){return false;}}'
    for cls, fields in {'LaunchLocation': 'NOTIFICATION=1', 'OfflineItemState': 'IN_PROGRESS=1', 'PendingState': 'NOT_PENDING=0,PENDING_NETWORK=1'}.items():
        files[offline + cls + '.java'] = 'package org.chromium.components.offline_items_collection;public class ' + cls + '{public static final int ' + fields + ';}'
    for annotation in ('org/chromium/build/annotations/Nullable', 'org/chromium/build/annotations/NullMarked',
                       'androidx/annotation/IntDef', 'androidx/annotation/VisibleForTesting'):
        package, name = annotation.rsplit('/', 1)
        files[annotation + '.java'] = ('package ' + package.replace('/', '.') + ';import java.lang.annotation.*;'
            '@Target({ElementType.TYPE_USE,ElementType.TYPE,ElementType.METHOD,ElementType.PARAMETER,ElementType.FIELD})'
            'public @interface ' + name + '{long[] value() default {};}')
    if args.negative_control:
        key = pkg + 'DownloadBroadcastManagerImpl.java'
        before, after = {
            'owner': ('!IntentUtils.LanduEnvironment.owns(intent)', 'false'),
            'destroyed': ('mLanduDestroyed || intent == null', 'intent == null'),
            'eager': ('public DownloadBroadcastManagerImpl() {}', 'public DownloadBroadcastManagerImpl() { landuPreferences(); }'),
            'internal': ('IntentUtils.LanduEnvironment.scopePendingIntent(source)', 'new Intent(source)'),
        }[args.negative_control]
        if files[key].count(before) != 1: raise RuntimeError('Negative-control anchor drift')
        files[key] = files[key].replace(before, after)
    out = Path(tempfile.mkdtemp(prefix='download-actions-' + (args.negative_control or 'host') + '-', dir=cache))
    for name, code in files.items():
        p = out / 'src' / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(code)
    jdk = source / 'third_party/jdk/current/bin'
    command = [str(jdk/'javac'), '--release', '11', '-encoding', 'UTF-8', '-d', str(out/'classes'),
               *(str(out/'src'/n) for n in files)]
    compiled = subprocess.run(command, capture_output=True, text=True)
    (out/'compile.log').write_text(compiled.stdout + compiled.stderr)
    run, result = None, None
    if compiled.returncode == 0:
        run = subprocess.run([str(jdk/'java'), '-ea', '-cp', str(out/'classes'),
                              pkg.replace('/', '.')+'DownloadActionOwnershipTest'], capture_output=True, text=True)
        (out/'run.log').write_text(run.stdout + run.stderr)
        if run.returncode == 0: result = json.loads(run.stdout)
    expected = {'owner': 'foreign/malformed notification accessed selected environment',
                'destroyed': 'destroyed service executed late native or download query callback',
                'eager': 'service constructor opened environment data before ownership check',
                'internal': 'internal resume lost source identity'}.get(args.negative_control)
    detected = bool(args.negative_control) and run is not None and run.returncode != 0 and expected in run.stderr
    stable = all(sha(Path(p)) == h for p, h in inputs.items())
    passed = stable and compiled.returncode == 0 and (detected if args.negative_control else result is not None and result['passed'])
    report = {'kind': 'full-staged-download-action-service-host-test', 'passed': passed,
              'stageManifestSha256': sha(manifest), 'inputFiles': inputs, 'inputsStable': stable,
              'command': command, 'compileExitCode': compiled.returncode, 'result': result,
              'negativeControl': args.negative_control, 'faultDetected': detected,
              'fullServiceExecuted': True, 'platformSubstitutes': True, 'androidFrameworkExecuted': False,
              'deviceTested': False, 'exclusions': ['actual download storage/native execution',
                  'SplitCompat service construction', 'cold environment wakeup and foreign action replay',
                  'Android notification trampolines and process lifecycle']}
    (out/'report.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'passed': passed, 'report': str(out/'report.json'), 'result': result, 'faultDetected': detected}))
    if not passed: print((out/('run.log' if run else 'compile.log')).read_text()[-8000:])
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    main()
