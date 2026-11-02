package com.landu.browser.nativecore;

import android.app.Activity;
import android.app.job.*;
import android.content.*;
import android.os.Build;
import android.os.Handler;

import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class NotificationBackgroundTest {
    static final String A="11111111-1111-4111-8111-111111111111", B="22222222-2222-4222-8222-222222222222";
    static final String C="33333333-3333-4333-8333-333333333333";
    static final List<LanduNotificationJobService> services=new ArrayList<>();
    static Path root;static EnvironmentLeaseStore leases;static int assertions,scenario;
    static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    static void await(BooleanSupplier ready)throws Exception{
        long end=System.nanoTime()+15_000_000_000L;
        do{Handler.drain();if(ready.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<end);
        throw new AssertionError("host worker timeout");
    }
    static EnvironmentIdentity id(String s){return EnvironmentIdentity.parse(s);}
    static String ns(String s){return LanduNotificationReceiver.NAMESPACE+s;}
    static Intent intent(String owner){
        Intent i=new Intent(LanduNotificationReceiver.ACTION).setClassName("com.landu.browser",LanduNotificationReceiver.class.getName());
        i.setIdentifier("com.landu.browser.intent:v1:"+owner+":1notification-event");
        i.putExtra(LanduNotificationReceiver.TYPE,2).putExtra("reply","原始回复");i.setClipData("remote-input");i.setFlags(255);return i;
    }
    static JobParameters params(String owner,Intent...items){return new JobParameters(ns(owner),Arrays.stream(items).map(JobWorkItem::new).toArray(JobWorkItem[]::new));}
    static LanduNotificationJobService service(){LanduNotificationJobService s=new LanduNotificationJobService();services.add(s);return s;}
    static void setup()throws Exception{
        Handler.drain();Context.files=root.resolve("case-"+(scenario++));Files.createDirectories(Context.files);
        Context.deliveries.clear();Context.failSend=false;Build.VERSION.SDK_INT=36;
        EnvironmentCatalog c=new EnvironmentCatalog(Context.files.resolve("landu-control"));
        c.save(0,new EnvironmentDefinition(id(A),"环境A","about:blank","",1,1));
        c.save(1,new EnvironmentDefinition(id(B),"环境B","about:blank","",1,1));
        leases=new EnvironmentLeaseStore(Context.files.resolve("landu-control"));
    }
    static EnvironmentLeaseStore.Session active(String owner)throws Exception{
        leases.prepareLaunch(id(owner));return leases.claimSelected(Context.files.resolve("landu-environments"));
    }
    static void tests()throws Exception{
        setup();Context context=new Context();Intent original=intent(A);
        check(LanduNotificationReceiver.enqueue(context,original)==JobScheduler.RESULT_SUCCESS,"valid operation not queued");
        LanduNotificationReceiver.enqueue(context,intent(B));
        check(JobScheduler.queues.size()==2,"different environments share a queue");
        Intent copy=JobScheduler.queues.get(ns(A)).get(0).getIntent();original.putExtra("reply","changed");
        check("原始回复".equals(copy.getStringExtra("reply"))&&"remote-input".equals(copy.getClipData()),"queued reply was lost or aliased");
        check(copy.getFlags()==Intent.FLAG_RECEIVER_FOREGROUND&&original.getFlags()==255,"caller flags were modified or forwarded");
        for(Intent bad:new Intent[]{null,new Intent(),intent(A).setIdentifier("invalid"),intent(A).setClassName("other","Receiver"),intent(A).putExtra(LanduNotificationReceiver.TYPE,9)}){
            try{LanduNotificationReceiver.enqueue(context,bad);throw new AssertionError("malformed work queued");}catch(IllegalArgumentException expected){assertions++;}
        }
        JobScheduler.fail=true;check(LanduNotificationReceiver.enqueue(context,intent(A))==0,"queue failure hidden");JobScheduler.fail=false;
        Build.VERSION.SDK_INT=33;check(LanduNotificationReceiver.enqueue(context,intent(A))==0,"old Android used global queue");Build.VERSION.SDK_INT=36;
        LanduNotificationJobService s=service();
        check(!s.onStartJob(new JobParameters(null)),"unscoped job accepted");
        check(!s.onStartJob(new JobParameters(ns("bad-id"))),"malformed namespace accepted");
        JobParameters wrong=params(A,intent(A));wrong.id=99;check(!s.onStartJob(wrong),"unexpected job ID accepted");

        // A consciously closed environment remains closed until the user selects it again.
        try(EnvironmentLeaseStore.Session closed=active(A)){}
        JobParameters stopped=params(A,intent(A));check(s.onStartJob(stopped),"closed work not queued for retry");
        await(()->s.finished.containsKey(stopped)||!Context.deliveries.isEmpty());
        check(Context.deliveries.isEmpty(),"closed environment was restarted by a background notification");
        check(stopped.completed==0&&Boolean.TRUE.equals(s.finished.get(stopped))&&leases.currentLaunch()==null,"closed work consumed or selection changed");
        try(EnvironmentLeaseStore.Session session=active(A)){
            JobParameters p=params(A,intent(A),intent(A));check(s.onStartJob(p),"active job rejected");
            check(Context.deliveries.isEmpty(),"dispatch happened synchronously in onStartJob");
            await(()->Context.deliveries.size()==1);
            check(Context.deliveries.get(0).intent.getComponent().getClassName().equals(LanduNotificationReceiver.TARGET),"dispatch target was not fixed");
            check(p.completed==0,"work consumed before acknowledgement");
            Context.deliveries.get(0).reply(Activity.RESULT_OK);await(()->Context.deliveries.size()==2);
            check(p.completed==1,"first acknowledged item not completed");
            Context.deliveries.get(1).reply(Activity.RESULT_OK);await(()->p.autoFinished);
            check(p.completed==2&&!s.finished.containsKey(p),"queue exhaustion did not follow enqueue lifecycle");
            JobParameters other=params(B,intent(B));s.onStartJob(other);await(()->s.finished.containsKey(other));
            check(other.completed==0&&Context.deliveries.size()==2&&leases.currentLaunch().identity.equals(id(A)),"foreign environment switched or consumed");
            JobParameters forged=params(A,intent(B));s.onStartJob(forged);await(()->forged.autoFinished||Context.deliveries.size()>2);
            check(Context.deliveries.size()==2&&forged.completed==1,"foreign namespace work was dispatched");
            JobParameters rejected=params(A,intent(A));s.onStartJob(rejected);await(()->Context.deliveries.size()==3);
            Context.deliveries.get(2).reply(Activity.RESULT_CANCELED);await(()->s.finished.containsKey(rejected)||rejected.completed>0);
            check(rejected.completed==0&&Boolean.TRUE.equals(s.finished.get(rejected)),"unacknowledged work was consumed");
            Context.failSend=true;JobParameters failed=params(A,intent(A));s.onStartJob(failed);await(()->s.finished.containsKey(failed));
            check(failed.completed==0,"failed delivery consumed work");Context.failSend=false;
            JobParameters old=params(A,intent(A));s.onStartJob(old);await(()->Context.deliveries.size()==4);
            check(s.onStopJob(old),"stopped job lost retry");
            Context.deliveries.get(3).reply(Activity.RESULT_OK);Handler.drain();
            check(old.completed==0&&!s.finished.containsKey(old),"old callback completed a stopped run");
            JobParameters replacement=params(A,intent(A));s.onStartJob(replacement);await(()->Context.deliveries.size()==5);
            s.onStopJob(old);Context.deliveries.get(3).reply(Activity.RESULT_OK);Handler.drain();
            check(replacement.completed==0,"old stop or reply completed replacement run");
            Context.deliveries.get(4).reply(Activity.RESULT_OK);await(()->replacement.autoFinished);
            JobParameters dead=params(A,intent(A));s.onStartJob(dead);await(()->Context.deliveries.size()==6);
            s.onDestroy();Context.deliveries.get(5).reply(Activity.RESULT_OK);Handler.drain();
            check(dead.completed==0&&!s.finished.containsKey(dead),"destroyed service completed late delivery");
        }
        setup();LanduNotificationJobService gone=service();JobParameters removed=params(C,intent(C));gone.onStartJob(removed);await(()->removed.autoFinished);
        check(removed.completed==1&&Context.deliveries.isEmpty()&&leases.currentLaunch()==null,"unknown environment created or retried");
        // A pending launch for another environment is never canceled by the queue.
        EnvironmentLeaseStore.Ticket pending=leases.prepareLaunch(id(B));JobParameters waiting=params(A,intent(A));gone.onStartJob(waiting);await(()->gone.finished.containsKey(waiting));
        check(waiting.completed==0&&leases.pendingLaunch().nonce.equals(pending.nonce),"queue replaced another pending launch");
        leases.cancelPending(pending);gone.onDestroy();
        // Real child-process exit releases the OS file lock but leaves its RUNNING record.
        setup();Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),NotificationBackgroundTest.class.getName(),"crash",Context.files.toString()).inheritIO().start();
        check(child.waitFor()==0&&!leases.isBrowserRunning(),"child session did not release the real process lock");
        LanduNotificationJobService recovered=service();JobParameters recovery=params(A,intent(A));recovered.onStartJob(recovery);await(()->Context.deliveries.size()==1);
        check(leases.currentLaunch().identity.equals(id(A)),"cold recovery changed selected identity");
        Context.deliveries.get(0).reply(Activity.RESULT_OK);await(()->recovery.autoFinished);
        check(recovery.completed==1,"selected crashed session work did not complete");
    }
    public static void main(String[] args)throws Exception{
        if(args[0].equals("crash")){
            EnvironmentLeaseStore store=new EnvironmentLeaseStore(Path.of(args[1]).resolve("landu-control"));
            store.prepareLaunch(id(A));store.claimSelected(Path.of(args[1]).resolve("landu-environments"));Runtime.getRuntime().halt(0);
        }
        root=Path.of(args[0]);Files.createDirectories(root);
        try{tests();System.out.println("{\"passed\":true,\"assertions\":"+assertions+",\"realLeaseAndCatalogFiles\":true,\"childProcessLockRelease\":true,\"androidFrameworkExecuted\":false}");}
        finally{for(LanduNotificationJobService s:services)s.onDestroy();}
    }
}
