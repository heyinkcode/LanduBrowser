package com.landu.browser.nativecore;

/** Resolves policies for one startup snapshot without altering the configured environment. */
public final class EnvironmentRegionalResolver {
    private EnvironmentRegionalResolver() {}

    public static final class Result {
        public final EnvironmentRegionSettings region;
        public final EnvironmentGeolocationSettings geolocation;
        public final EnvironmentExitInfo measuredExit;
        public final boolean languageFallback;
        public final boolean timeZoneFallback;
        public final boolean missingExitCoordinates;

        private Result(EnvironmentRegionSettings region, EnvironmentGeolocationSettings geolocation,
                EnvironmentExitInfo measuredExit, boolean languageFallback, boolean timeZoneFallback,
                boolean missingExitCoordinates) {
            this.region = region;
            this.geolocation = geolocation;
            this.measuredExit = measuredExit;
            this.languageFallback = languageFallback;
            this.timeZoneFallback = timeZoneFallback;
            this.missingExitCoordinates = missingExitCoordinates;
        }
    }

    public static Result resolve(EnvironmentRegionalPolicy policy, EnvironmentRegionSettings configured,
            EnvironmentGeolocationSettings configuredLocation, EnvironmentExitInfo exit) {
        if (policy == null || configured == null || configuredLocation == null) {
            throw new IllegalArgumentException("地区策略或配置不能为空");
        }
        String languages = "";
        boolean languageFallback = false;
        if (policy.languageMode == EnvironmentRegionalPolicy.LanguageMode.FIXED) {
            if (configured.languages.isEmpty()) throw new IllegalArgumentException("固定网页语言不能为空");
            languages = configured.languages;
        } else if (policy.languageMode == EnvironmentRegionalPolicy.LanguageMode.EXIT_IP) {
            String primary = exit == null ? "" : DesktopRegionalDefaults.language(exit.countryCode);
            languageFallback = primary.isEmpty();
            if (languageFallback) languages = policy.preferredLanguages;
            else {
                String base = primary.split("-", 2)[0];
                languages = primary.equals(base) ? primary : primary + "," + base;
            }
        }

        String zone = "";
        boolean timeZoneFallback = false;
        if (policy.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM) {
            if (configured.timeZone.isEmpty()) throw new IllegalArgumentException("自定义时区不能为空");
            zone = configured.timeZone;
        } else if (policy.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.EXIT_IP) {
            zone = exit == null ? "" : exit.timeZone;
            timeZoneFallback = zone.isEmpty();
            if (zone.isEmpty() && exit != null) zone = DesktopRegionalDefaults.timeZone(exit.countryCode);
            try { zone = new EnvironmentRegionSettings("", zone).timeZone; }
            catch (IllegalArgumentException unavailableDeviceZone) { zone = ""; timeZoneFallback = true; }
        }

        EnvironmentGeolocationSettings location;
        boolean missingCoordinates = false;
        switch (policy.locationMode) {
            case CUSTOM:
                if (configuredLocation.mode != EnvironmentGeolocationSettings.CUSTOM) {
                    throw new IllegalArgumentException("自定义定位须填写坐标");
                }
                location = configuredLocation;
                break;
            case BLOCKED:
                location = EnvironmentGeolocationSettings.blocked();
                break;
            case PROMPT:
                location = EnvironmentGeolocationSettings.system();
                break;
            case EXIT_IP:
                missingCoordinates = exit == null || !exit.hasPosition();
                location = missingCoordinates ? EnvironmentGeolocationSettings.blocked()
                        : new EnvironmentGeolocationSettings(EnvironmentGeolocationSettings.CUSTOM,
                                exit.latitude, exit.longitude, 100);
                break;
            default: throw new IllegalArgumentException("定位策略无效");
        }
        return new Result(new EnvironmentRegionSettings(languages, zone), location, exit,
                languageFallback, timeZoneFallback, missingCoordinates);
    }
}
