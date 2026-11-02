package com.landu.browser.nativecore;

import android.content.Context;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Real Android worker-side launch helper and private storage; no browser native loading. */
public final class AndroidRegionalLaunchTest {
    private static int assertions;
    public static void run(Context context)throws Exception {
        Path control=context.getNoBackupFilesDir().toPath().resolve("landu-control");
        EnvironmentCatalog catalog=new EnvironmentCatalog(control);EnvironmentLeaseStore leases=new EnvironmentLeaseStore(control);
        EnvironmentDefinition automatic=EnvironmentDefinition.create("automatic exit qa","","")
                .withRegion(new EnvironmentRegionSettings("de-DE,de","Europe/Berlin"))
                .withRegionalPolicy(EnvironmentRegionalPolicy.desktopDefaults());
        catalog.save(catalog.read().revision,automatic);
        byte[] config=Files.readAllBytes(control.resolve("environments.bin"));
        EnvironmentLeaseStore.LaunchRequest request=AndroidRegionalLaunch.prepare(context,leases,automatic.identity);
        check(!request.resumeExisting,"Fresh launch requires preparation");
        EnvironmentRegionalLaunchStore store=new EnvironmentRegionalLaunchStore(control);
        EnvironmentRegionalLaunchStore.Receipt receipt=store.read(automatic,request.ticket);
        check(receipt.measured!=null&&!receipt.measured.ip.isEmpty(),"Launch receipt contains measured exit");
        check(receipt.resolved.region.timeZone.equals(receipt.measured.timeZone),"Launch timezone matches actual response");
        check(receipt.resolved.geolocation.mode==EnvironmentGeolocationSettings.CUSTOM&&receipt.measured.hasPosition(),"IP coordinates resolved");
        EnvironmentDefinition runtime=automatic.withResolvedRegionalSettings(receipt.resolved);
        Switches flags=new Switches();runtime.region.applyTo(flags,"zh-CN,zh");runtime.geolocation.applyTo(flags);
        check(flags.values.get("landu-timezone").equals(receipt.measured.timeZone),"Measured timezone reaches real bootstrap flag adapter");
        check(flags.values.get("landu-geolocation-mode").equals("custom"),"IP location reaches native provider mode");
        check(Arrays.equals(config,Files.readAllBytes(control.resolve("environments.bin"))),"Startup never overwrites configured catalog");
        Context isolated=NativeEnvironmentRuntime.attach(context);
        check(isolated instanceof IsolatedEnvironmentContext,"Actual application attach installs isolated context");
        check(NativeEnvironmentRuntime.region().timeZone.equals(receipt.measured.timeZone)
                && NativeEnvironmentRuntime.geolocation().latitude == receipt.measured.latitude,"Actual runtime reads resolved launch receipt before native initialization");
        check(isolated.getFilesDir().toPath().toRealPath().startsWith(context.getNoBackupFilesDir().toPath().resolve("landu-environments").toRealPath()),"Actual runtime storage stays inside canonical environment directory");
        try {
            EnvironmentLeaseStore.LaunchRequest resumed=AndroidRegionalLaunch.prepare(context,leases,automatic.identity);
            check(resumed.resumeExisting&&resumed.ticket.nonce.equals(request.ticket.nonce),"Live resume preserves launch lease");
            check(store.read(automatic,request.ticket).measured.checkedAt==receipt.measured.checkedAt,"Live resume keeps immutable exit observation");
            EnvironmentDefinition edited=catalog.read().find(automatic.identity).withRegion(new EnvironmentRegionSettings("ja-JP,ja","Asia/Tokyo"));
            catalog.save(catalog.read().revision,edited);
            check(NativeEnvironmentRuntime.region().timeZone.equals(receipt.measured.timeZone),"Saving new manual settings does not change current startup snapshot");
        } finally {
            // Release only this harness process's own lease; production runtime exposes no reset API.
            java.lang.reflect.Field session=NativeEnvironmentRuntime.class.getDeclaredField("session");session.setAccessible(true);
            ((EnvironmentLeaseStore.Session)session.get(null)).close();
        }
        EnvironmentDefinition failure=EnvironmentDefinition.create("failed automatic qa","","")
                .withNetwork(new EnvironmentNetworkSettings("http://127.0.0.1:9",true))
                .withRegionalPolicy(EnvironmentRegionalPolicy.desktopDefaults());
        catalog.save(catalog.read().revision,failure);
        try{AndroidRegionalLaunch.prepare(context,leases,failure.identity);throw new AssertionError("Failed proxy incorrectly started automatic environment");}
        catch(IOException expected){assertions++;}
        check(leases.pendingLaunch()==null&&leases.observe().status==EnvironmentLeaseStore.Status.IDLE,"Failed automatic lookup cancels pending lease");
        EnvironmentDefinition manual=EnvironmentDefinition.create("fixed offline qa","","")
                .withRegion(new EnvironmentRegionSettings("en-US,en","America/Los_Angeles"))
                .withNetwork(new EnvironmentNetworkSettings("http://127.0.0.1:9",true));
        catalog.save(catalog.read().revision,manual);
        EnvironmentLeaseStore.LaunchRequest offline=AndroidRegionalLaunch.prepare(context,leases,manual.identity);
        EnvironmentRegionalLaunchStore.Receipt explicit=store.read(manual,offline.ticket);
        check(explicit.measured==null&&!explicit.failure.isEmpty(),"Manual offline launch marks detection failure explicitly");
        check(explicit.resolved.region.timeZone.equals("America/Los_Angeles"),"Offline manual LA is not replaced or presented as measured exit");
        leases.cancelPending(offline.ticket);
        System.out.println("{\"suite\":\"android-regional-launch-preflight\",\"passed\":true,\"assertions\":"+assertions+",\"browserNativeLoaded\":false,\"phoneUsed\":false}");
    }
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String,String> values=new HashMap<>();
        public void remove(String name){values.remove(name);}public void set(String name,String value){values.put(name,value);}
    }
}
