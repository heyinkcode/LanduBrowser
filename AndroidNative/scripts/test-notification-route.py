#!/usr/bin/env python3
"""Run full notification routing Activities with explicit Android/UI substitutes and real lease files."""
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
    parser.add_argument('--negative-control', choices=['owner', 'visible', 'ticket', 'replay'])
    args = parser.parse_args()
    source, stage = [p.resolve(strict=True) for p in (args.source, args.stage)]
    native = Path(__file__).resolve().parents[1]
    cache = Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):
        raise RuntimeError('Use an independent non-C test directory')
    manifest = stage/'manifest.json'
    data = json.loads(manifest.read_text())
    if subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip() != data['revision']:
        raise RuntimeError('Use the pinned Chromium revision')
    entries = {e['path']:e['sha256'] for e in data['files']}
    inputs = {str(manifest):sha(manifest),str(Path(__file__).resolve()):sha(Path(__file__).resolve())}
    inventory = native/'overlay-inventory.json'; inputs[str(inventory)]=sha(inventory)
    pkg = 'com/landu/browser/nativecore/'

    def staged(name):
        path = stage/'files'/name
        if sha(path) != entries[name]: raise RuntimeError('Stage drift: '+name)
        inputs[str(path)] = sha(path)
        return path.read_text()

    files = {pkg+name:staged('chrome/android/java/src/'+pkg+name)
             for kind,name in json.loads(inventory.read_text())['javaFiles'] if kind=='java'}
    for name in ('LanduNotificationRoute.java','LanduNotificationActivity.java','LanduLaunchActivity.java'):
        files[pkg+name] = staged('chrome/android/java/src/'+pkg+name)
    fixture = native/'host-tests/notification-route/NotificationRouteTest.java'; inputs[str(fixture)]=sha(fixture)
    files[pkg+fixture.name]=fixture.read_text()
    ownership = block(staged('base/android/java/src/org/chromium/base/IntentUtils.java'), 'public static final class LanduEnvironment')
    files.update({
      'org/chromium/base/IntentUtils.java': 'package org.chromium.base;import android.content.Intent;import android.app.PendingIntent;import org.chromium.build.annotations.Nullable;public class IntentUtils{'+ownership+'}',
      'org/chromium/build/annotations/Nullable.java': 'package org.chromium.build.annotations;import java.lang.annotation.*;@Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.FIELD,ElementType.METHOD})public @interface Nullable{}',
      'android/app/PendingIntent.java': 'package android.app;public class PendingIntent{}',
      'android/content/ComponentName.java': '''package android.content;public class ComponentName{private final String pkg,cls;
public ComponentName(String p,String c){pkg=p;cls=c;}public String getPackageName(){return pkg;}public String getClassName(){return cls;}}''',
      'android/content/Intent.java': '''package android.content;import java.util.*;import android.net.Uri;
public class Intent{public static final int FILL_IN_IDENTIFIER=256,FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_NEW_DOCUMENT=0x80000,
FLAG_ACTIVITY_MULTIPLE_TASK=0x8000000,FLAG_ACTIVITY_NO_USER_ACTION=0x40000,FLAG_ACTIVITY_CLEAR_TOP=0x4000000;
public static final String ACTION_MAIN="android.intent.action.MAIN",ACTION_VIEW="android.intent.action.VIEW",CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";
private String action,id,pkg;private ComponentName component;private int flags;private Object clip,selector;private Uri data;
private final Map<String,Object> extras=new HashMap<>();
public Intent(){}public Intent(String a){action=a;}public Intent(String a,Uri u){action=a;data=u;}
public Intent(Context c,Class<?> cls){setClassName(c.getPackageName(),cls.getName());}
public Intent(Intent i){action=i.action;id=i.id;pkg=i.pkg;component=i.component;flags=i.flags;clip=i.clip;selector=i.selector;data=i.data;extras.putAll(i.extras);}
public String getAction(){return action;}public Intent setAction(String a){action=a;return this;}
public String getIdentifier(){return id;}public Intent setIdentifier(String i){id=i;return this;}
public Intent setClassName(String p,String c){component=new ComponentName(p,c);return this;}public ComponentName getComponent(){return component;}
public Intent setClassName(Context c,String name){return setClassName(c.getPackageName(),name);}
public Intent setPackage(String p){pkg=p;return this;}public void setSelector(Intent i){selector=i;}
public Intent setDataAndType(Uri u,String type){data=u;return this;}public Intent setFlags(int f){flags=f;return this;}
public Intent addFlags(int f){flags|=f;return this;}public int getFlags(){return flags;}
public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public boolean hasExtra(String k){return extras.containsKey(k);}
public String getStringExtra(String k){return (String)extras.get(k);}public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(boolean)extras.get(k):d;}
public int getIntExtra(String k,int d){return extras.containsKey(k)?(int)extras.get(k):d;}
@SuppressWarnings("unchecked")public <T>T getParcelableExtra(String k){return (T)extras.get(k);}
public void setClipData(Object c){clip=c;}public Object getClipData(){return clip;}public Intent addCategory(String c){return this;}}
''',
      'android/content/Context.java': '''package android.content;import java.nio.file.Path;import java.io.File;import android.content.res.Resources;
public class Context{public static Path files;public String getPackageName(){return "com.landu.browser";}
public File getNoBackupFilesDir(){return files.toFile();}public Resources getResources(){return new Resources();}
public <T>T getSystemService(Class<T> type){return null;}}
''',
      'android/app/Activity.java': '''package android.app;import android.content.*;import android.os.Bundle;import android.view.*;import java.util.*;
public class Activity extends Context{private Intent intent;private boolean finishing,destroyed;public final List<Intent> started=new ArrayList<>();
public void setIntent(Intent i){intent=i;}public Intent getIntent(){return intent;}public void startActivity(Intent i){started.add(new Intent(i));}
protected void onCreate(Bundle state){}protected void onResume(){}protected void onPause(){}protected void onSaveInstanceState(Bundle state){}
protected void onDestroy(){destroyed=true;}public boolean isDestroyed(){return destroyed;}public boolean isFinishing(){return finishing;}
public void finish(){finishing=true;}public Window getWindow(){return new Window();}public void setContentView(View view){}}
''',
      'android/app/ActivityManager.java': '''package android.app;import java.util.*;import android.content.ComponentName;
public class ActivityManager{public List<AppTask>getAppTasks(){return Collections.emptyList();}
public static class AppTask{public RecentTaskInfo getTaskInfo(){return new RecentTaskInfo();}public void moveToFront(){}}
public static class RecentTaskInfo{public ComponentName baseActivity;}}
''',
      'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle{private final Map<String,Object> data=new HashMap<>();
public void putBoolean(String k,boolean v){data.put(k,v);}public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(boolean)data.get(k):d;}}
''',
      'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler{private static final List<Entry> tasks=new ArrayList<>();
private static class Entry{Handler owner;Runnable task;Entry(Handler h,Runnable r){owner=h;task=r;}}
public Handler(Looper l){}public boolean post(Runnable r){synchronized(tasks){tasks.add(new Entry(this,r));}return true;}
public void removeCallbacksAndMessages(Object ignored){synchronized(tasks){tasks.removeIf(e->e.owner==this);}}
public static void drain(){while(true){Entry e;synchronized(tasks){if(tasks.isEmpty())return;e=tasks.remove(0);}e.task.run();}}}
''',
      'android/os/Looper.java': 'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
      'android/os/Build.java': 'package android.os;public class Build{public static class VERSION{public static int SDK_INT=36;}}',
      'android/net/Uri.java': 'package android.net;public class Uri{public static Uri parse(String s){return new Uri();}}',
      'android/content/res/Resources.java': 'package android.content.res;public class Resources{public static class DisplayMetrics{public float density=2;}public DisplayMetrics getDisplayMetrics(){return new DisplayMetrics();}}',
      'android/content/res/ColorStateList.java': 'package android.content.res;public class ColorStateList{public static ColorStateList valueOf(int c){return new ColorStateList();}}',
      'android/graphics/Typeface.java': 'package android.graphics;public class Typeface{public static final Typeface DEFAULT=new Typeface();public static Typeface create(Typeface t,int weight,boolean italic){return t;}}',
      'android/graphics/Insets.java': 'package android.graphics;public class Insets{public int left,top,right,bottom;}',
      'android/graphics/drawable/GradientDrawable.java': 'package android.graphics.drawable;public class GradientDrawable{public void setColor(int c){}public void setCornerRadius(int r){}public void setStroke(int w,int c){}}',
      'android/graphics/drawable/RippleDrawable.java': 'package android.graphics.drawable;import android.content.res.ColorStateList;public class RippleDrawable{public RippleDrawable(ColorStateList c,GradientDrawable s,Object mask){}}',
      'android/view/Gravity.java': 'package android.view;public class Gravity{public static final int CENTER_VERTICAL=16;}',
      'android/view/Window.java': 'package android.view;public class Window{public void setDecorFitsSystemWindows(boolean v){}}',
      'android/view/WindowInsets.java': 'package android.view;import android.graphics.Insets;public class WindowInsets{public Insets getInsets(int t){return new Insets();}public static class Type{public static int systemBars(){return 1;}public static int displayCutout(){return 2;}}}',
      'android/view/View.java': '''package android.view;import android.content.Context;public class View{public interface Click{void click(View v);}
public interface ApplyInsets{WindowInsets apply(View v,WindowInsets i);}private boolean enabled=true;private Click click;
public View(Context c){}public void setBackgroundColor(int c){}public void setPadding(int a,int b,int c,int d){}
public void setBackground(Object shape){}public void setOnApplyWindowInsetsListener(ApplyInsets l){}
public void setEnabled(boolean e){enabled=e;}public boolean isEnabled(){return enabled;}
public void setOnClickListener(Click c){click=c;}public boolean performClick(){if(enabled&&click!=null){click.click(this);return true;}return false;}}
''',
      'android/widget/TextView.java': '''package android.widget;import android.content.Context;import android.graphics.Typeface;import android.view.View;
public class TextView extends View{private String text;public TextView(Context c){super(c);}public void setText(String s){text=s;}public String getText(){return text;}
public void setTextSize(int s){}public void setTextColor(int c){}public void setTypeface(Typeface t){}public void setLineSpacing(int a,int b){}}''',
      'android/widget/Button.java': 'package android.widget;import android.content.Context;public class Button extends TextView{public Button(Context c){super(c);}public void setAllCaps(boolean c){}}',
      'android/widget/LinearLayout.java': '''package android.widget;import android.content.Context;import android.view.View;
public class LinearLayout extends View{public static final int VERTICAL=1;public LinearLayout(Context c){super(c);}public void setOrientation(int i){}public void setGravity(int i){}
public void addView(View v){}public void addView(View v,LayoutParams p){}public static class LayoutParams{public int width,height,topMargin;public LayoutParams(int w,int h){width=w;height=h;}}}''',
      'android/widget/ScrollView.java': 'package android.widget;import android.content.Context;import android.view.View;public class ScrollView extends View{public ScrollView(Context c){super(c);}public void setFillViewport(boolean b){}public void addView(View v){}}',
      'android/widget/Toast.java': 'package android.widget;import android.content.Context;public class Toast{public static final int LENGTH_LONG=1;public static Toast makeText(Context c,String s,int t){return new Toast();}public void show(){}}',
      pkg+'LanduLinkActivity.java': 'package com.landu.browser.nativecore;public class LanduLinkActivity{public static final String EXTRA_LINK="external-link";}',
      pkg+'LanduManagerActivity.java': 'package com.landu.browser.nativecore;public class LanduManagerActivity{}',
      pkg+'NativeEnvironmentRuntime.java': '''package com.landu.browser.nativecore;import java.io.IOException;
public class NativeEnvironmentRuntime{public static final String EXTRA_ID="com.landu.browser.environment.ID",EXTRA_NONCE="com.landu.browser.environment.NONCE",EXTRA_RESUME="com.landu.browser.environment.RESUME";
public static EnvironmentLeaseStore.Ticket ticket;public static EnvironmentDefinition environment;
public static EnvironmentDefinition verifyLaunch(String id,String nonce)throws IOException{if(ticket==null||!ticket.identity.id().equals(id)||!ticket.nonce.equals(nonce))throw new IOException("wrong lease");return environment;}
public static boolean shouldResumeWindow(String i,String n,boolean r,boolean link){return r;}
public static void noteBrowserLaunchDispatched(String i,String n) throws IOException{verifyLaunch(i,n);}}
''',
      pkg+'NativeSessionClient.java': '''package com.landu.browser.nativecore;import android.content.Context;import java.util.concurrent.ExecutorService;
public class NativeSessionClient implements AutoCloseable{public interface Callback{void complete(String error);}
public static int stopCalls;public static Callback callback;public static EnvironmentLeaseStore.Session session;
public NativeSessionClient(Context c,EnvironmentLeaseStore s,ExecutorService e){}public void stop(EnvironmentLeaseStore.Ticket ticket,Callback c){stopCalls++;callback=c;}
public static void complete(String error)throws Exception{Callback c=callback;callback=null;if(error==null)session.close();c.complete(error);}
public void close(){callback=null;}public static void reset(){stopCalls=0;callback=null;session=null;}}
''',
    })
    expected = None
    if args.negative_control:
        cases = {
          'owner': ('LanduNotificationRoute.java', '\n                || !IntentUtils.LanduEnvironment.owns(entry.payload)', '', 'foreign process owner reached notification dispatch'),
          'visible': ('LanduNotificationActivity.java', 'if (!resumed || whenResumed == null', 'if (whenResumed == null', 'notification dispatched before the routing Activity became visible'),
          'ticket': ('LanduNotificationActivity.java', '!sameTicket(confirmed, current.ticket)\n                        || ', '', 'stale confirmation tried to close a replacement session'),
          'replay': ('LanduLaunchActivity.java', 'if (state != null && state.getBoolean("notificationDispatched", false))', 'if (false)', 'launch recreation replayed the notification'),
        }
        name,before,after,expected=cases[args.negative_control]
        if files[pkg+name].count(before)!=1:raise RuntimeError('Fault anchor changed')
        files[pkg+name]=files[pkg+name].replace(before,after)
    out=Path(tempfile.mkdtemp(prefix='notification-route-'+(args.negative_control or 'host')+'-',dir=cache))
    java=out/'src'; classes=out/'classes'; classes.mkdir(); temporary=out/'temp';temporary.mkdir()
    sources=[]
    for name,content in files.items():
        path=java/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);sources.append(str(path))
    jdk=source/'third_party/jdk/current/bin'
    command=[str(jdk/'javac'),'-J-Xmx256m','-J-Djava.io.tmpdir='+str(temporary),'--release','17','-encoding','UTF-8','-d',str(classes),*sources]
    compiled=subprocess.run(command,capture_output=True,text=True,timeout=90);(out/'compile.log').write_text(compiled.stdout+compiled.stderr)
    run=None;result=None;detected=False
    if compiled.returncode==0:
        run=subprocess.run([str(jdk/'java'),'-Xmx128m','-Djava.io.tmpdir='+str(temporary),'-cp',str(classes),'com.landu.browser.nativecore.NotificationRouteTest',str(out/'data')],capture_output=True,text=True,timeout=60)
        (out/'run.log').write_text(run.stdout+run.stderr)
        if run.returncode==0:result=json.loads(run.stdout)
        detected=bool(expected) and run.returncode!=0 and expected in run.stderr
    stable=all(sha(Path(p))==digest for p,digest in inputs.items())
    passed=stable and compiled.returncode==0 and (detected if expected else bool(result and result['passed']))
    report={'kind':'full-notification-routing-activities-host-protocol','passed':passed,'stageManifestSha256':sha(manifest),'inputFiles':inputs,'inputsStable':stable,
            'compileExitCode':compiled.returncode,'runExitCode':run.returncode if run else None,'result':result,'negativeControl':args.negative_control,'faultDetected':detected,
            'platformSubstitutes':True,'realLeaseAndCatalogFiles':True,'androidFrameworkExecuted':False,'deviceTested':False,
            'exclusions':['Android process startup and Activity/task lifecycle','actual notification shade and RemoteInput parceling','real Chromium shutdown IPC and database flush','pixel layout and accessibility on device']}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'passed':passed,'report':str(out/'report.json'),'result':result,'faultDetected':detected}))
    if not passed:
        print((out/'compile.log').read_text()[-3500:])
        if run:print((out/'run.log').read_text()[-5000:])
    raise SystemExit(0 if passed else 1)


if __name__=='__main__':main()
