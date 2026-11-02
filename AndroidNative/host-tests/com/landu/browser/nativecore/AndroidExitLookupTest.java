package com.landu.browser.nativecore;

import java.io.IOException;

/** Executed by app_process on the isolated API35 emulator, without Chromium native loading. */
public final class AndroidExitLookupTest {
    private static int assertions;
    public static void main(String[] args)throws Exception {
        EnvironmentExitInfo la=AndroidExitLookup.parse("{\"success\":true,\"ip\":\"203.0.113.1\",\"country_code\":\"US\",\"city\":\"Los Angeles\",\"timezone\":{\"id\":\"America/Los_Angeles\"},\"latitude\":34.05,\"longitude\":-118.24}",123);
        check(la.city.equals("Los Angeles")&&la.timeZone.equals("America/Los_Angeles")&&la.hasPosition(),"Full response");
        check(la.checkedAt==123,"Measured timestamp");
        EnvironmentExitInfo partial=AndroidExitLookup.parse("{\"success\":true,\"ip\":\"2001:db8::1\",\"country_code\":\"JP\"}",124);
        check(!partial.hasPosition()&&partial.timeZone.isEmpty(),"Optional absent fields explicit");
        EnvironmentRegionalResolver.Result resolved=EnvironmentRegionalResolver.resolve(EnvironmentRegionalPolicy.desktopDefaults(),EnvironmentRegionSettings.system(),EnvironmentGeolocationSettings.system(),partial);
        check(resolved.region.languages.equals("ja-JP,ja")&&resolved.region.timeZone.equals("Asia/Tokyo"),"Country defaults on Android TZ database");
        check(resolved.missingExitCoordinates&&resolved.geolocation.mode==EnvironmentGeolocationSettings.BLOCKED,"Missing coordinates never fall through to GPS");
        for(String malformed:new String[]{"", "[]", "{\"success\":false}", "{\"success\":true}","{\"success\":true,\"ip\":\"host.example\"}","{\"success\":true,\"ip\":\"203.0.113.1\",\"latitude\":34}"}) {
            assertions++;try{AndroidExitLookup.parse(malformed,1);throw new AssertionError("Invalid response accepted");}catch(IOException expected){}
        }
        boolean livePassed=false;
        if(args.length>0&&args[0].equals("live")) {
            try {
                EnvironmentExitInfo live=AndroidExitLookup.lookup(EnvironmentDefinition.create("qa-live","",""),null);
                check(!live.ip.isEmpty(),"Live endpoint returns numeric exit");livePassed=true;
                System.out.println("{\"liveExitLookupPassed\":true,\"hasCountry\":"+!live.countryCode.isEmpty()+",\"hasCity\":"+!live.city.isEmpty()+",\"hasTimeZone\":"+!live.timeZone.isEmpty()+",\"hasPosition\":"+live.hasPosition()+"}");
            }catch(IOException unavailable){System.out.println("{\"liveExitLookupPassed\":false,\"failureType\":\""+unavailable.getClass().getSimpleName()+"\",\"message\":"+org.json.JSONObject.quote(unavailable.getMessage())+"}");}
        }
        System.out.println("{\"suite\":\"android-native-json-exit-parser\",\"passed\":true,\"assertions\":"+assertions+",\"livePassed\":"+livePassed+",\"browserRuntimeTested\":false}");
    }
    private static void check(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
}
