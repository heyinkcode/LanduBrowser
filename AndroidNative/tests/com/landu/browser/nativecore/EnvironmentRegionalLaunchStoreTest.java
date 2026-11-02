package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class EnvironmentRegionalLaunchStoreTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        Path control=Path.of(args[0]).resolve("control");
        EnvironmentCatalog catalog=new EnvironmentCatalog(control);
        EnvironmentDefinition a=EnvironmentDefinition.create("a","","").withRegionalPolicy(EnvironmentRegionalPolicy.desktopDefaults());
        catalog.save(0,a); a=catalog.read().find(a.identity);final EnvironmentDefinition original=a;
        EnvironmentLeaseStore leases=new EnvironmentLeaseStore(control);
        EnvironmentLeaseStore.Ticket first=leases.prepareLaunch(a.identity);
        EnvironmentRegionalLaunchStore store=new EnvironmentRegionalLaunchStore(control);
        rejects(()->store.read(original,first));
        rejects(()->store.write(original,first,null,"failed"));
        EnvironmentExitInfo la=new EnvironmentExitInfo("203.0.113.10","US","Los Angeles","America/Los_Angeles",34.05,-118.24,123);
        byte[] before=Files.readAllBytes(control.resolve("environments.bin"));
        store.write(a,first,la,"");
        EnvironmentRegionalLaunchStore.Receipt receipt=store.read(a,first);
        check(receipt.measured.city.equals("Los Angeles") && receipt.resolved.region.timeZone.equals("America/Los_Angeles"),"Receipt resolves actual measured exit");
        check(receipt.resolved.geolocation.latitude==34.05,"IP position reaches launch resolution");
        check(Arrays.equals(before,Files.readAllBytes(control.resolve("environments.bin"))),"Launch receipt never edits configured catalog");
        check(store.read(a).measured.checkedAt==123,"Measured timestamp survives file reopen for UI");
        leases.cancelPending(first);EnvironmentLeaseStore.Ticket second=leases.prepareLaunch(a.identity);
        rejects(()->store.read(original,second));
        rejects(()->store.read(original.edit("changed","",""),first));
        rejects(()->store.read(original.withNetwork(new EnvironmentNetworkSettings("socks5://localhost:1080",true)),first));
        EnvironmentDefinition b=EnvironmentDefinition.create("b","","");
        rejects(()->store.write(b,first,la,""));
        rejects(()->store.read(b,first));
        Path file=control.resolve("regional-launch").resolve(a.identity.id()+".bin");byte[] valid=Files.readAllBytes(file);
        Files.write(file,Arrays.copyOf(valid,valid.length-1));rejects(()->store.read(original,first));
        Files.write(file,Arrays.copyOf(valid,valid.length+1));rejects(()->store.read(original,first));
        Files.write(file,valid);check(store.read(a,first).measured.ip.equals("203.0.113.10"),"Valid receipt can be recovered without profile changes");
        leases.cancelPending(second);
        System.out.println("{\"suite\":\"regional-launch-snapshot\",\"assertions\":"+assertions+",\"passed\":true}");
    }
    interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{assertions++;try{action.run();}catch(IOException expected){return;}throw new AssertionError("Invalid receipt accepted");}
    private static void check(boolean ok,String msg){assertions++;if(!ok)throw new AssertionError(msg);}
}
