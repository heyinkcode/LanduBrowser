package com.landu.browser.nativecore;

import java.util.Objects;

/** Configured policies, kept separately from a single launch's detected exit values. */
public final class EnvironmentRegionalPolicy {
    public enum LanguageMode { EXIT_IP, SYSTEM, FIXED }
    public enum TimeZoneMode { EXIT_IP, SYSTEM, CUSTOM }
    public enum LocationMode { EXIT_IP, PROMPT, BLOCKED, CUSTOM }

    public final LanguageMode languageMode;
    public final TimeZoneMode timeZoneMode;
    public final LocationMode locationMode;
    public final String preferredLanguages;

    public EnvironmentRegionalPolicy(LanguageMode languageMode, TimeZoneMode timeZoneMode,
            LocationMode locationMode, String preferredLanguages) {
        if (languageMode == null || timeZoneMode == null || locationMode == null) {
            throw new IllegalArgumentException("地区策略不能为空");
        }
        String normalized = new EnvironmentRegionSettings(preferredLanguages, "").languages;
        if (normalized.isEmpty()) throw new IllegalArgumentException("首选语言不能为空");
        this.languageMode = languageMode;
        this.timeZoneMode = timeZoneMode;
        this.locationMode = locationMode;
        this.preferredLanguages = normalized;
    }

    public static EnvironmentRegionalPolicy desktopDefaults() {
        return new EnvironmentRegionalPolicy(LanguageMode.EXIT_IP, TimeZoneMode.EXIT_IP,
                LocationMode.EXIT_IP, "en-US,en");
    }

    public static EnvironmentRegionalPolicy fromLegacy(
            EnvironmentRegionSettings region, EnvironmentGeolocationSettings location) {
        if (region == null || location == null) throw new IllegalArgumentException("旧地区配置不能为空");
        return new EnvironmentRegionalPolicy(
                region.languages.isEmpty() ? LanguageMode.SYSTEM : LanguageMode.FIXED,
                region.timeZone.isEmpty() ? TimeZoneMode.SYSTEM : TimeZoneMode.CUSTOM,
                location.mode == EnvironmentGeolocationSettings.BLOCKED ? LocationMode.BLOCKED
                        : location.mode == EnvironmentGeolocationSettings.CUSTOM ? LocationMode.CUSTOM : LocationMode.PROMPT,
                "en-US,en");
    }

    public boolean needsExitLookup() {
        return languageMode == LanguageMode.EXIT_IP || timeZoneMode == TimeZoneMode.EXIT_IP
                || locationMode == LocationMode.EXIT_IP;
    }

    public boolean coordinatesFromIp() { return locationMode == LocationMode.EXIT_IP; }

    public EnvironmentRegionalPolicy withManualRegion(EnvironmentRegionSettings region) {
        EnvironmentRegionalPolicy explicit = fromLegacy(region, EnvironmentGeolocationSettings.system());
        return new EnvironmentRegionalPolicy(explicit.languageMode, explicit.timeZoneMode, locationMode, preferredLanguages);
    }

    public EnvironmentRegionalPolicy withManualLocation(EnvironmentGeolocationSettings location) {
        EnvironmentRegionalPolicy explicit = fromLegacy(EnvironmentRegionSettings.system(), location);
        return new EnvironmentRegionalPolicy(languageMode, timeZoneMode, explicit.locationMode, preferredLanguages);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof EnvironmentRegionalPolicy)) return false;
        EnvironmentRegionalPolicy value = (EnvironmentRegionalPolicy) other;
        return languageMode == value.languageMode && timeZoneMode == value.timeZoneMode
                && locationMode == value.locationMode && preferredLanguages.equals(value.preferredLanguages);
    }

    @Override public int hashCode() {
        return Objects.hash(languageMode, timeZoneMode, locationMode, preferredLanguages);
    }
}
