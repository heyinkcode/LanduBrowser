package com.landu.browser.nativecore;

import java.util.HashMap;
import java.util.Map;

public final class EnvironmentRegionalPolicyTest {
    private static int assertions;

    public static void main(String[] args) {
        EnvironmentRegionalPolicy defaults = EnvironmentRegionalPolicy.desktopDefaults();
        check(defaults.needsExitLookup(), "Desktop defaults must require real exit lookup");
        check(defaults.coordinatesFromIp(), "Automatic coordinates must be active");
        EnvironmentRegionSettings configured = EnvironmentRegionSettings.system();
        EnvironmentGeolocationSettings device = EnvironmentGeolocationSettings.system();
        EnvironmentExitInfo la = new EnvironmentExitInfo("203.0.113.10", "us", "Los Angeles",
                "America/Los_Angeles", 34.0522, -118.2437, 123);
        EnvironmentRegionalResolver.Result resolved = EnvironmentRegionalResolver.resolve(defaults, configured, device, la);
        check(resolved.region.languages.equals("en-US,en"), "US exit language");
        check(resolved.region.timeZone.equals("America/Los_Angeles"), "Use actual LA timezone rather than country default");
        check(resolved.geolocation.mode == EnvironmentGeolocationSettings.CUSTOM, "IP coordinates must reach native custom-location input");
        check(resolved.geolocation.latitude == 34.0522 && resolved.geolocation.longitude == -118.2437, "Use measured coordinates");
        check(resolved.measuredExit == la && resolved.measuredExit.city.equals("Los Angeles"), "Keep measured exit separate");
        check(!resolved.languageFallback && !resolved.timeZoneFallback && !resolved.missingExitCoordinates, "A full observation must not claim fallback");
        check(configured.languages.isEmpty() && configured.timeZone.isEmpty(), "Resolution must not mutate configuration");

        Switches flags = new Switches();
        flags.values.put("proxy-server", "socks5://proxy.test:1080");
        flags.values.put("lang", "zh-CN");
        resolved.region.applyTo(flags, "zh-CN,zh");
        resolved.geolocation.applyTo(flags);
        check(flags.values.get("accept-lang").equals("en-US,en"), "Native language flag");
        check(flags.values.get("landu-web-locale").equals("en-US"), "Native web locale flag");
        check(flags.values.get("landu-timezone").equals("America/Los_Angeles"), "Native IANA flag");
        check(flags.values.get("landu-geolocation-position").equals("34.0522,-118.2437,100.0"), "Native position flag");
        check(flags.values.get("proxy-server").equals("socks5://proxy.test:1080"), "Region cannot silently change exit routing");
        check(flags.values.get("lang").equals("zh-CN"), "Web locale must not replace browser UI language");

        EnvironmentExitInfo japan = new EnvironmentExitInfo("203.0.113.11", "JP", "Tokyo", "Asia/Tokyo", null, null, 123);
        resolved = EnvironmentRegionalResolver.resolve(defaults, configured, device, japan);
        check(resolved.region.languages.equals("ja-JP,ja") && resolved.region.timeZone.equals("Asia/Tokyo"), "JP exit strategy");
        check(resolved.missingExitCoordinates && resolved.geolocation.mode == EnvironmentGeolocationSettings.BLOCKED,
                "Unavailable IP coordinates must not silently expose phone GPS");

        EnvironmentRegionalPolicy fallback = new EnvironmentRegionalPolicy(defaults.languageMode, defaults.timeZoneMode,
                defaults.locationMode, " de-DE, de ");
        resolved = EnvironmentRegionalResolver.resolve(fallback, configured, device,
                new EnvironmentExitInfo("203.0.113.12", "XX", "", "", null, null, 123));
        check(resolved.region.languages.equals("de-DE,de") && resolved.languageFallback, "Unknown country uses preferred language");
        check(resolved.region.timeZone.isEmpty() && resolved.timeZoneFallback, "Unknown timezone leaves device fallback explicit");
        check(resolved.measuredExit.city.isEmpty(), "A fallback must not fabricate city");
        resolved = EnvironmentRegionalResolver.resolve(fallback, configured, device, null);
        check(resolved.measuredExit == null && resolved.languageFallback && resolved.missingExitCoordinates, "Failed lookup cannot masquerade as measured exit");

        EnvironmentRegionalPolicy fixed = new EnvironmentRegionalPolicy(EnvironmentRegionalPolicy.LanguageMode.FIXED,
                EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM, EnvironmentRegionalPolicy.LocationMode.CUSTOM, "en-US,en");
        EnvironmentGeolocationSettings custom = new EnvironmentGeolocationSettings(EnvironmentGeolocationSettings.CUSTOM, 10, 20, 200);
        resolved = EnvironmentRegionalResolver.resolve(fixed, new EnvironmentRegionSettings("ja-JP,ja", "Asia/Tokyo"), custom, la);
        check(!fixed.needsExitLookup(), "All explicit settings must work without a mandatory lookup");
        check(resolved.region.languages.equals("ja-JP,ja") && resolved.region.timeZone.equals("Asia/Tokyo"), "Fixed choices must win over US probe");
        check(resolved.geolocation == custom && resolved.measuredExit.city.equals("Los Angeles"), "Fixed location and measured exit are independent");

        resolved = EnvironmentRegionalResolver.resolve(defaults, new EnvironmentRegionSettings("de-DE,de", "Europe/Berlin"), device,
                new EnvironmentExitInfo("203.0.113.13", "JP", "", "", null, null, 123));
        check(resolved.region.timeZone.equals("Asia/Tokyo") && resolved.timeZoneFallback, "New country must not retain old Berlin zone");
        EnvironmentRegionalPolicy system = EnvironmentRegionalPolicy.fromLegacy(configured, device);
        check(!system.needsExitLookup() && system.languageMode == EnvironmentRegionalPolicy.LanguageMode.SYSTEM
                && system.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.SYSTEM, "Legacy device settings remain device settings");
        resolved = EnvironmentRegionalResolver.resolve(system, configured, device, la);
        check(resolved.region.languages.isEmpty() && resolved.region.timeZone.isEmpty()
                && resolved.geolocation.mode == EnvironmentGeolocationSettings.SYSTEM, "System and prompt policy preserve existing behavior");
        EnvironmentRegionalPolicy legacy = EnvironmentRegionalPolicy.fromLegacy(new EnvironmentRegionSettings("en-US,en", "America/Los_Angeles"), custom);
        check(legacy.languageMode == fixed.languageMode && legacy.timeZoneMode == fixed.timeZoneMode
                && legacy.locationMode == fixed.locationMode, "Legacy manual values must not be changed to automatic");
        resolved = EnvironmentRegionalResolver.resolve(defaults, configured, device,
                new EnvironmentExitInfo("203.0.113.14", "US", "", "", 0.0, 0.0, 123));
        check(!resolved.missingExitCoordinates && resolved.geolocation.latitude == 0 && resolved.geolocation.longitude == 0,
                "Zero coordinates are legitimate measured values");
        check(resolved.timeZoneFallback, "Country-derived timezone is a fallback, not exact measured timezone");
        rejects(() -> new EnvironmentRegionalPolicy(defaults.languageMode, defaults.timeZoneMode, defaults.locationMode, ""));
        rejects(() -> new EnvironmentExitInfo("203.0.113.10", "US", "bad\ncity", "UTC", null, null, 123));
        rejects(() -> new EnvironmentExitInfo("203.0.113.10", "US", "", "UTC", 34.0, null, 123));
        rejects(() -> new EnvironmentExitInfo("203.0.113.10", "US", "", "UTC", 91.0, 1.0, 123));
        rejects(() -> new EnvironmentExitInfo("999.1.2.3", "US", "", "UTC", null, null, 123));
        rejects(() -> new EnvironmentExitInfo("host.example", "US", "", "UTC", null, null, 123));
        rejects(() -> EnvironmentRegionalResolver.resolve(fixed, configured, device, la));
        System.out.println("{\"suite\":\"desktop-region-policy-resolution\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidLaunchIntegrated\":false,\"networkProbeTested\":false}");
    }

    private static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Invalid policy/exit response accepted");
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String,String> values = new HashMap<>();
        @Override public void remove(String name) { values.remove(name); }
        @Override public void set(String name, String value) { values.put(name,value); }
    }
}
