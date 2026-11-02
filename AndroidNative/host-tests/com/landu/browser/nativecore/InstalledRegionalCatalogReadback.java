package com.landu.browser.nativecore;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read only a copied synthetic emulator catalog, using the actual production decoder. */
public final class InstalledRegionalCatalogReadback {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);
        EnvironmentCatalog.Snapshot snapshot=new EnvironmentCatalog(root).read();
        EnvironmentDefinition a=find(snapshot,"LanduEmuA"),b=find(snapshot,"LanduEmuB"),c=find(snapshot,"LanduDesktopIPQA");
        check(ByteBuffer.wrap(Files.readAllBytes(root.resolve("environments.bin"))).getInt(4)==17,"GUI saved V17");
        check(a.regionalPolicy.languageMode==EnvironmentRegionalPolicy.LanguageMode.FIXED
                &&a.regionalPolicy.timeZoneMode==EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM
                &&a.region.languages.equals("en-US,en")&&a.region.timeZone.equals("America/Los_Angeles"),"Legacy manual A retained");
        check(b.regionalPolicy.languageMode==EnvironmentRegionalPolicy.LanguageMode.SYSTEM
                &&b.regionalPolicy.timeZoneMode==EnvironmentRegionalPolicy.TimeZoneMode.SYSTEM,"Legacy B retains device policy");
        check(c.regionalPolicy.languageMode==EnvironmentRegionalPolicy.LanguageMode.EXIT_IP
                &&c.regionalPolicy.timeZoneMode==EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP
                &&c.regionalPolicy.locationMode==EnvironmentRegionalPolicy.LocationMode.EXIT_IP,"New C automatic modes persisted");
        check(c.regionalPolicy.preferredLanguages.equals("de-DE,de"),"Preferred fallback persisted through real GUI");
        if(args.length>1&&args[1].equals("launch")) {
            EnvironmentRegionalLaunchStore.Receipt receipt=new EnvironmentRegionalLaunchStore(root).read(c);
            check(receipt.measured!=null&&receipt.measured.countryCode.equals("US")&&receipt.measured.city.equals("Los Angeles"),"Actual full APK detected Los Angeles exit");
            check(receipt.resolved.region.languages.equals("en-US,en")&&receipt.resolved.region.timeZone.equals("America/Los_Angeles"),"Actual exit resolves language and timezone rather than German fallback");
            check(receipt.resolved.geolocation.mode==EnvironmentGeolocationSettings.CUSTOM
                    &&receipt.resolved.geolocation.latitude==receipt.measured.latitude&&receipt.resolved.geolocation.longitude==receipt.measured.longitude,"Measured coordinates reach native location configuration");
            check(!receipt.resolved.languageFallback&&!receipt.resolved.timeZoneFallback&&!receipt.resolved.missingExitCoordinates,"Full response uses no fallback");
            check(c.region.languages.isEmpty()&&c.region.timeZone.isEmpty(),"Measured values did not overwrite stored automatic configuration");
            System.out.println("{\"suite\":\"installed-apk-actual-exit-launch-receipt\",\"passed\":true,\"assertions\":5,\"measuredCountry\":\"US\",\"measuredCity\":\"Los Angeles\",\"effectiveLanguage\":\"en-US,en\",\"effectiveTimeZone\":\"America/Los_Angeles\",\"coordinatesMatched\":true,\"noFallback\":true,\"browserRenderingAccepted\":false}");
        }
        System.out.println("{\"suite\":\"installed-apk-real-gui-catalog-readback\",\"passed\":true,\"assertions\":5,\"version\":17,\"environmentCount\":"+snapshot.allEnvironments.size()+",\"legacyAUnchanged\":true,\"legacyBUnchanged\":true,\"newAutomaticEnvironmentPersisted\":true}");
    }
    private static EnvironmentDefinition find(EnvironmentCatalog.Snapshot s,String name)throws Exception {
        EnvironmentDefinition found=null;for(EnvironmentDefinition e:s.allEnvironments)if(e.name.equals(name)){if(found!=null)throw new AssertionError("Duplicate synthetic environment");found=e;}
        if(found==null)throw new AssertionError("Missing synthetic environment: "+name);return found;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
