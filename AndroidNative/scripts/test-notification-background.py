#!/usr/bin/env python3
"""Execute staged manager notification Receiver/JobService with explicit platform substitutes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def block(text, signature):
    start=text.index(signature); end=text.index('{',start)+1; depth=1
    while depth:
        if text[end]=='{': depth+=1
        elif text[end]=='}': depth-=1
        end+=1
    return text[start:end]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--stage',type=Path,required=True)
    parser.add_argument('--negative-control',choices=['owner','lifecycle','acknowledgement','closed'])
    args=parser.parse_args()
    source,stage=[p.resolve(strict=True) for p in (args.source,args.stage)]
    native=Path(__file__).resolve().parents[1]
    cache=Path(os.environ['LANDU_TEST_CACHE']).resolve(strict=True)
    if cache.is_relative_to(source) or str(cache).startswith('/mnt/c/'):raise RuntimeError('Use a private non-C test directory')
    manifest=stage/'manifest.json'; data=json.loads(manifest.read_text())
    assert subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()==data['revision']
    inputs={str(manifest):sha(manifest),str(Path(__file__).resolve()):sha(Path(__file__).resolve())}
    entries={x['path']:x['sha256'] for x in data['files']}
    def staged(name):
        p=stage/'files'/name; assert sha(p)==entries[name],name
        inputs[str(p)]=sha(p);return p.read_text()
    inventory=native/'overlay-inventory.json';inputs[str(inventory)]=sha(inventory)
    pkg='com/landu/browser/nativecore/'
    files={pkg+name:staged('chrome/android/java/src/'+pkg+name) for kind,name in json.loads(inventory.read_text())['javaFiles'] if kind=='java'}
    for name in ['LanduNotificationReceiver.java','LanduNotificationJobService.java']:
        files[pkg+name]=staged('chrome/android/java/src/'+pkg+name)
    ownership=block(staged('base/android/java/src/org/chromium/base/IntentUtils.java'),'public static final class LanduEnvironment')
    fixture=native/'host-tests/notification-background/NotificationBackgroundTest.java'
    inputs[str(fixture)]=sha(fixture);files[pkg+fixture.name]=fixture.read_text()
    files.update({
      'org/chromium/base/IntentUtils.java':'package org.chromium.base;import android.content.Intent;import android.app.PendingIntent;import org.chromium.build.annotations.Nullable;public class IntentUtils{'+ownership+'}',
      'org/chromium/build/annotations/Nullable.java':'package org.chromium.build.annotations;import java.lang.annotation.*;@Target({ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.FIELD,ElementType.METHOD})public @interface Nullable{}',
      'android/app/PendingIntent.java':'package android.app;public class PendingIntent{}',
      'android/app/Activity.java':'package android.app;public class Activity{public static final int RESULT_OK=-1,RESULT_CANCELED=0;}',
      'android/content/ComponentName.java':'''package android.content;public class ComponentName{private final String pkg,cls;
public ComponentName(String p,String c){pkg=p;cls=c;}public ComponentName(Context c,Class<?> t){this(c.getPackageName(),t.getName());}
public String getPackageName(){return pkg;}public String getClassName(){return cls;}}''',
      'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent{
public static final int FILL_IN_IDENTIFIER=256,FLAG_RECEIVER_FOREGROUND=0x10000000;private String action,id,pkg;private ComponentName component;private int flags;private Object clip;
private final Map<String,Object> extras=new HashMap<>();public Intent(){}public Intent(String a){action=a;}
public Intent(Intent i){action=i.action;id=i.id;pkg=i.pkg;component=i.component;flags=i.flags;clip=i.clip;extras.putAll(i.extras);}
public String getAction(){return action;}public Intent setAction(String a){action=a;return this;}
public String getIdentifier(){return id;}public Intent setIdentifier(String i){id=i;return this;}
public Intent setClassName(String p,String c){component=new ComponentName(p,c);return this;}public ComponentName getComponent(){return component;}
public Intent setPackage(String p){pkg=p;return this;}public void setSelector(Intent i){}public Intent setDataAndType(Object d,String t){return this;}
public Intent setFlags(int f){flags=f;return this;}public int getFlags(){return flags;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
public int getIntExtra(String k,int d){Object v=extras.get(k);return v instanceof Integer?(int)v:d;}public String getStringExtra(String k){return (String)extras.get(k);}
public void setClipData(Object c){clip=c;}public Object getClipData(){return clip;}}''',
      'android/content/Context.java':'''package android.content;import java.nio.file.*;import java.io.*;import java.util.*;import android.os.Handler;import android.app.job.JobScheduler;
public class Context{public static Path files;public static JobScheduler scheduler=new JobScheduler();public static boolean failSend;public static final List<Delivery> deliveries=new ArrayList<>();
public static class Delivery{public final Intent intent;final BroadcastReceiver receiver;final Handler handler;Delivery(Intent i,BroadcastReceiver r,Handler h){intent=new Intent(i);receiver=r;handler=h;}
public void reply(int code){receiver.setResultCode(code);handler.post(()->receiver.onReceive(new Context(),intent));}}
public String getPackageName(){return "com.landu.browser";}public File getNoBackupFilesDir(){return files.toFile();}
public <T>T getSystemService(Class<T> t){return t.cast(scheduler);}
public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver r,Handler h,int code,String data,Object extras){if(failSend)throw new IllegalStateException("send failed");r.setResultCode(code);deliveries.add(new Delivery(i,r,h));}}
''',
      'android/content/BroadcastReceiver.java':'package android.content;public abstract class BroadcastReceiver{private int result;public void setResultCode(int r){result=r;}public int getResultCode(){return result;}public abstract void onReceive(Context c,Intent i);}',
      'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=36;}}',
      'android/os/Looper.java':'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
      'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler{private static final List<Entry> tasks=new ArrayList<>();
private record Entry(Handler owner,Runnable task){}public Handler(Looper l){}public boolean post(Runnable r){synchronized(tasks){tasks.add(new Entry(this,r));}return true;}
public void removeCallbacksAndMessages(Object o){synchronized(tasks){tasks.removeIf(e->e.owner==this);}}
public static void drain(){while(true){Entry e;synchronized(tasks){if(tasks.isEmpty())return;e=tasks.remove(0);}e.task.run();}}}''',
      'org/chromium/base/Log.java':'package org.chromium.base;public class Log{public static int w(String tag,String msg){return 0;}}',
      'android/app/job/JobWorkItem.java':'package android.app.job;import android.content.Intent;public class JobWorkItem{private final Intent intent;public JobWorkItem(Intent i){intent=i;}public Intent getIntent(){return intent;}}',
      'android/app/job/JobInfo.java':'''package android.app.job;import android.content.ComponentName;public class JobInfo{public static final int BACKOFF_POLICY_EXPONENTIAL=1;
public int id;public ComponentName service;public long latency,backoff;public static class Builder{private final JobInfo j=new JobInfo();public Builder(int id,ComponentName s){j.id=id;j.service=s;}
public Builder setMinimumLatency(long l){j.latency=l;return this;}public Builder setBackoffCriteria(long b,int p){j.backoff=b;return this;}public JobInfo build(){return j;}}}''',
      'android/app/job/JobScheduler.java':'''package android.app.job;import java.util.*;public class JobScheduler{public static final int RESULT_SUCCESS=1,RESULT_FAILURE=0;
public static final Map<String,List<JobWorkItem>> queues=new HashMap<>();public static boolean fail;private String namespace;public JobScheduler forNamespace(String n){JobScheduler j=new JobScheduler();j.namespace=n;return j;}
public int enqueue(JobInfo j,JobWorkItem w){if(fail)return RESULT_FAILURE;if(namespace==null)throw new AssertionError("global notification queue");queues.computeIfAbsent(namespace,k->new ArrayList<>()).add(w);return RESULT_SUCCESS;}}''',
      'android/app/job/JobParameters.java':'''package android.app.job;import java.util.*;public class JobParameters{public final String namespace;public int id=1;public boolean autoFinished,failComplete;public int completed;
private final Deque<JobWorkItem> items=new ArrayDeque<>();private JobWorkItem inFlight;public JobParameters(String n,JobWorkItem...w){namespace=n;items.addAll(Arrays.asList(w));}
public String getJobNamespace(){return namespace;}public int getJobId(){return id;}public JobWorkItem dequeueWork(){if(inFlight!=null)throw new AssertionError("dequeued with outstanding work");inFlight=items.poll();if(inFlight==null)autoFinished=true;return inFlight;}
public void completeWork(JobWorkItem w){if(failComplete)throw new IllegalStateException("framework stopped");if(w!=inFlight)throw new AssertionError("wrong work completed");completed++;inFlight=null;}}''',
      'android/app/job/JobService.java':'''package android.app.job;import android.content.Context;import java.util.*;public abstract class JobService extends Context{
public final Map<JobParameters,Boolean> finished=new IdentityHashMap<>();public abstract boolean onStartJob(JobParameters p);public abstract boolean onStopJob(JobParameters p);
public void jobFinished(JobParameters p,boolean retry){if(finished.put(p,retry)!=null)throw new AssertionError("job finished twice");}public void onDestroy(){}}''',
    })
    expected=None
    if args.negative_control:
        cases={
          'owner':('!run.identity.equals(LanduNotificationReceiver.owner(this, payload))','false','foreign namespace work was dispatched'),
          'lifecycle':('return !destroyed && runs.get(run.namespace) == run;','return !destroyed;','old callback completed a stopped run'),
          'acknowledgement':('getResultCode() == Activity.RESULT_OK','true','unacknowledged work was consumed'),
          'closed':('allowed = ticket != null && ticket.identity.equals(run.identity);','allowed = true;','closed environment was restarted by a background notification'),
        }
        before,after,expected=cases[args.negative_control];name=pkg+'LanduNotificationJobService.java'
        assert files[name].count(before)==1;files[name]=files[name].replace(before,after)
    out=Path(tempfile.mkdtemp(prefix='notification-background-'+(args.negative_control or 'host')+'-',dir=cache))
    for name,text in files.items():
        p=out/'src'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    jdk=source/'third_party/jdk/current/bin'
    command=[str(jdk/'javac'),'-encoding','UTF-8','-d',str(out/'classes'),*[str(p) for p in sorted((out/'src').rglob('*.java'))]]
    with (out/'compile.log').open('w') as log: compiled=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    run=None; result=None;detected=False
    if compiled.returncode==0:
        command=[str(jdk/'java'),'-ea','-cp',str(out/'classes'),'com.landu.browser.nativecore.NotificationBackgroundTest',str(out/'data')]
        with (out/'run.log').open('w') as log:run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=90)
        text=(out/'run.log').read_text()
        if run.returncode==0:result=json.loads(text.strip().splitlines()[-1])
        detected=bool(expected and run.returncode!=0 and expected in text)
    stable=all(sha(Path(p))==value for p,value in inputs.items())
    passed=compiled.returncode==0 and stable and (detected if expected else bool(run and run.returncode==0))
    report={'kind':'staged-manager-notification-background-host-protocol','stageManifestSha256':sha(manifest),'passed':passed,
      'inputFiles':inputs,'inputsStable':stable,'compileExitCode':compiled.returncode,'runExitCode':run.returncode if run else None,
      'result':result,'negativeControl':args.negative_control,'faultDetected':detected,'realLeaseAndCatalogFiles':True,
      'platformSubstitutes':True,'androidFrameworkExecuted':False,'deviceTested':False,
      'exclusions':['Real Android JobScheduler persistence, quotas and delays','Actual cross-process ordered broadcast delivery and process startup','APK install and Xiaomi device behavior']}
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'report':str(out/'report.json'),'passed':passed,'result':result,'faultDetected':detected}))
    if not passed:
        print((out/'compile.log').read_text()[-3000:])
        if run:print((out/'run.log').read_text()[-3500:])
    raise SystemExit(0 if passed else 1)

if __name__=='__main__':main()
