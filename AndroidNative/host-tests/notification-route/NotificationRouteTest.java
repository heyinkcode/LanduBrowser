package com.landu.browser.nativecore;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import org.chromium.base.IntentUtils;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class NotificationRouteTest {
    private static final String OWNER = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER = "22222222-2222-4222-8222-222222222222";
    private static final String ACTION = "notifications.NotificationIntentInterceptor.INTENT_ACTION";
    private static final String TYPE = "notifications.NotificationIntentInterceptor.EXTRA_INTENT_TYPE";
    private static final String PAYLOAD = "com.landu.browser.environment.NOTIFICATION";
    private static final List<Activity> activities = new ArrayList<>();
    private static int assertions;
    private static Path root;
    private static EnvironmentLeaseStore leases;

    private static void check(boolean condition, String reason) {
        assertions++;
        if (!condition) throw new AssertionError(reason);
    }
    private static void rejected(Runnable action, String reason) {
        try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(reason);
    }
    private static Object field(Object target, String name) {
        try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Button primary(LanduNotificationActivity activity) { return (Button) field(activity, "primary"); }
    private static void await(BooleanSupplier ready) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        do { Handler.drain(); if (ready.getAsBoolean()) return; Thread.sleep(5); } while(System.nanoTime()<deadline);
        throw new AssertionError("host worker did not reach the expected checkpoint");
    }
    private static Intent notification(String owner) {
        Intent intent=new Intent(ACTION).setClassName("com.landu.browser",LanduNotificationRoute.ENTRY_CLASS);
        intent.setIdentifier("com.landu.browser.intent:v1:"+owner+":1notification-42");
        intent.putExtra(TYPE,0);
        intent.putExtra("reply","回复保留");
        intent.setClipData("remote-input-clip");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | 1);
        return intent;
    }
    private static EnvironmentDefinition definition(String identity) {
        return new EnvironmentDefinition(EnvironmentIdentity.parse(identity),identity.equals(OWNER)?"环境甲":"环境乙","about:blank","",1,1);
    }
    private static void fresh(String name) throws Exception {
        Context.files=root.resolve(name); Files.createDirectories(Context.files);
        EnvironmentCatalog catalog=new EnvironmentCatalog(Context.files.resolve("landu-control"));
        catalog.save(0,definition(OWNER)); catalog.save(1,definition(OTHER));
        leases=new EnvironmentLeaseStore(Context.files.resolve("landu-control"));
        NativeSessionClient.reset();
    }
    private static LanduNotificationActivity open(Intent intent, boolean resume) {
        LanduNotificationActivity activity=new LanduNotificationActivity(); activities.add(activity);
        activity.setIntent(intent); activity.onCreate(null); if(resume) activity.onResume(); return activity;
    }
    private static void destroy(LanduNotificationActivity activity) { activity.onPause(); activity.onDestroy(); }
    private static EnvironmentLeaseStore.Session running(String owner) throws Exception {
        leases.requestLaunch(EnvironmentIdentity.parse(owner));
        return leases.claimSelected(Context.files.resolve("landu-environments"));
    }
    private static LanduLaunchActivity deliver(Intent intent, Bundle saved) {
        LanduLaunchActivity activity=new LanduLaunchActivity(); activities.add(activity);
        activity.setIntent(intent); activity.onCreate(saved); return activity;
    }
    private static boolean dispatchTarget(Intent intent) {
        return intent.getComponent().getClassName().endsWith("NotificationIntentInterceptor$TrampolineActivity");
    }

    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]); Files.createDirectories(root);
        try { run(); }
        finally {
            for(Activity activity:activities) if(!activity.isDestroyed()) {
                if(activity instanceof LanduNotificationActivity) ((LanduNotificationActivity)activity).onDestroy();
            }
        }
        System.out.println("{\"passed\":true,\"assertions\":"+assertions+",\"fullRouteActivitiesExecuted\":true,\"realLeaseFiles\":true,\"androidFrameworkExecuted\":false}");
    }

    private static void run() throws Exception {
        check(IntentUtils.LanduEnvironment.getCurrentOwner()==null,"manager should not initialize Chromium ownership");
        Context context=new Context(); Intent incoming=notification(OWNER);
        for(Intent invalid:new Intent[]{null,new Intent(),new Intent(incoming).setIdentifier("bad"),
                new Intent(incoming).setClassName("elsewhere","Bad"),new Intent(incoming).putExtra(TYPE,2),
                new Intent(incoming).setAction("arbitrary.action")}) {
            rejected(()->LanduNotificationRoute.read("com.landu.browser",invalid),"invalid private route accepted");
        }
        LanduNotificationRoute.Entry entry=LanduNotificationRoute.read("com.landu.browser",incoming);
        incoming.putExtra("reply","caller changed");
        check(entry.identity.id().equals(OWNER),"route owner came from a mutable extra");
        check((incoming.getFlags()&1)!=0,"route mutated the original flags");

        fresh("cold");
        LanduNotificationActivity cold=open(notification(OWNER),false);
        await(()->field(cold,"whenResumed")!=null || !cold.started.isEmpty());
        check(cold.started.isEmpty(),"notification dispatched before the routing Activity became visible");
        check(leases.pendingLaunch().identity.id().equals(OWNER),"cold route selected the wrong environment");
        check(IntentUtils.LanduEnvironment.getCurrentOwner()==null,"routing started the browser in the manager");
        cold.onResume(); Handler.drain();
        check(cold.started.size()==1 && cold.isFinishing(),"cold notification did not start one launch handoff");
        Bundle routeSaved=new Bundle(); cold.onSaveInstanceState(routeSaved);
        LanduNotificationActivity recreated=new LanduNotificationActivity(); activities.add(recreated);
        recreated.setIntent(notification(OWNER)); recreated.onCreate(routeSaved); recreated.onResume(); Handler.drain();
        check(recreated.started.isEmpty() && recreated.isFinishing(),"routing recreation replayed a dispatched click");
        Intent launch=cold.started.get(0);
        try(EnvironmentLeaseStore.Session session=leases.claimSelected(Context.files.resolve("landu-environments"))) {
            NativeEnvironmentRuntime.ticket=session.ticket(); NativeEnvironmentRuntime.environment=definition(OWNER);
            IntentUtils.LanduEnvironment.initialize(OWNER);
            Intent copiedLaunch=entry.launch(context,leases.requestLaunch(EnvironmentIdentity.parse(OWNER)));
            check("回复保留".equals(LanduNotificationRoute.dispatch("com.landu.browser",copiedLaunch,OWNER).getStringExtra("reply")),
                    "caller mutation replaced a captured notification reply");
            EnvironmentLeaseStore otherStore=new EnvironmentLeaseStore(root.resolve("foreign-ticket"));
            EnvironmentLeaseStore.LaunchRequest otherRequest=otherStore.requestLaunch(EnvironmentIdentity.parse(OTHER));
            rejected(()->entry.launch(context,otherRequest),"route accepted a launch ticket for another environment");
            LanduLaunchActivity launcher=deliver(launch,null);
            check(launcher.started.size()==1 && dispatchTarget(launcher.started.get(0)),"launch opened the home page instead of the original notification");
            Intent target=launcher.started.get(0);
            check(OWNER.equals(IntentUtils.LanduEnvironment.ownerOf(target)),"dispatch lost environment identity");
            check("回复保留".equals(target.getStringExtra("reply")) && "remote-input-clip".equals(target.getClipData()),"dispatch lost reply data");
            check((target.getFlags()&1)==0 && (target.getFlags()&Intent.FLAG_ACTIVITY_NEW_DOCUMENT)!=0,"dispatch flags were not bounded or lost upstream task flags");
            Bundle saved=new Bundle(); launcher.onSaveInstanceState(saved);
            LanduLaunchActivity twice=deliver(launch,saved);
            check(twice.started.isEmpty(),"launch recreation replayed the notification");
            Intent corrupt=new Intent(launch).putExtra(NativeEnvironmentRuntime.EXTRA_NONCE,"wrong nonce");
            LanduLaunchActivity denied=deliver(corrupt,null);
            check(denied.started.stream().noneMatch(NotificationRouteTest::dispatchTarget),"wrong lease nonce reached notification dispatch");
            Intent foreign=new Intent(launch).putExtra(PAYLOAD,notification(OTHER));
            rejected(()->LanduNotificationRoute.dispatch("com.landu.browser",foreign,OTHER),"foreign process owner reached notification dispatch");
            rejected(()->LanduNotificationRoute.dispatch("com.landu.browser",foreign,OWNER),"payload and launch owner mismatch accepted");
        }
        destroy(cold);

        fresh("occupied-cancel");
        try(EnvironmentLeaseStore.Session session=running(OTHER)) {
            LanduNotificationActivity occupied=open(notification(OWNER),true);
            await(()->primary(occupied).isEnabled());
            check(NativeSessionClient.stopCalls==0 && occupied.started.isEmpty(),"another environment was stopped without user action");
            ((Button)field(occupied,"cancel")).performClick();
            check(leases.isBrowserRunning() && session.ticket().identity.id().equals(OTHER),"cancel closed the running environment");
            destroy(occupied);
        }

        fresh("switch-success");
        try(EnvironmentLeaseStore.Session session=running(OTHER)) {
            NativeSessionClient.session=session;
            LanduNotificationActivity switching=open(notification(OWNER),true);
            await(()->primary(switching).isEnabled());
            primary(switching).performClick(); primary(switching).performClick();
            await(()->NativeSessionClient.stopCalls==1);
            check(switching.started.isEmpty(),"new environment started before the old one exited");
            switching.onPause(); NativeSessionClient.complete(null); Handler.drain();
            check(switching.started.isEmpty(),"switch dispatched while the route screen was paused");
            switching.onResume(); await(()->switching.started.size()==1);
            check(NativeSessionClient.stopCalls==1,"double click sent multiple shutdown requests");
            check(leases.pendingLaunch().identity.id().equals(OWNER),"successful shutdown did not select the notification owner");
            destroy(switching);
        }

        fresh("switch-failure");
        try(EnvironmentLeaseStore.Session session=running(OTHER)) {
            NativeSessionClient.session=session;
            LanduNotificationActivity failed=open(notification(OWNER),true);
            await(()->primary(failed).isEnabled()); primary(failed).performClick();
            await(()->NativeSessionClient.stopCalls==1); NativeSessionClient.complete("保存失败");
            await(()->primary(failed).isEnabled());
            check(failed.started.isEmpty() && leases.isBrowserRunning(),"failed save still switched environment");
            destroy(failed);
        }

        fresh("stale-confirmation");
        EnvironmentLeaseStore.Session previous=running(OTHER);
        LanduNotificationActivity stale=open(notification(OWNER),true);
        await(()->primary(stale).isEnabled()); previous.close();
        try(EnvironmentLeaseStore.Session replacement=running(OTHER)) {
            NativeSessionClient.session=replacement; primary(stale).performClick();
            await(()->primary(stale).isEnabled() || NativeSessionClient.stopCalls>0);
            check(NativeSessionClient.stopCalls==0,"stale confirmation tried to close a replacement session");
            check(stale.started.isEmpty() && leases.isBrowserRunning(),"stale confirmation changed the running environment");
            destroy(stale);
        }

        fresh("pending-other"); leases.prepareLaunch(EnvironmentIdentity.parse(OTHER));
        LanduNotificationActivity pending=open(notification(OWNER),true);
        await(()->primary(pending).isEnabled());
        check(leases.pendingLaunch().identity.id().equals(OTHER),"other pending launch was canceled automatically");
        primary(pending).performClick(); await(()->pending.started.size()==1);
        check(NativeSessionClient.stopCalls==0 && leases.pendingLaunch().identity.id().equals(OWNER),"pending switch used a running-process shutdown");
        destroy(pending);

        fresh("destroyed");
        try(EnvironmentLeaseStore.Session session=running(OTHER)) {
            NativeSessionClient.session=session;
            LanduNotificationActivity gone=open(notification(OWNER),true);
            await(()->primary(gone).isEnabled()); primary(gone).performClick();
            await(()->NativeSessionClient.stopCalls==1);
            NativeSessionClient.Callback late=NativeSessionClient.callback;
            destroy(gone); late.complete(null); Handler.drain();
            check(gone.started.isEmpty(),"destroyed routing Activity dispatched a late callback");
        }

        fresh("unknown-environment");
        LanduNotificationActivity unknown=open(notification("33333333-3333-4333-8333-333333333333"),true);
        await(()->primary(unknown).isEnabled());
        check(unknown.started.isEmpty() && leases.pendingLaunch()==null,"unknown notification owner created an environment");
        destroy(unknown);
    }
}
