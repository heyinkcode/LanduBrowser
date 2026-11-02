package com.landu.browser.nativecore;

import java.time.ZoneId;
import java.util.Collections;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class EnvironmentRegionSettings {
    private static final Set<String> TIME_ZONES = Collections.unmodifiableSet(new HashSet<>(ZoneId.getAvailableZoneIds()));
    public final String languages;
    public final String timeZone;

    public enum Preset {
        DEVICE("跟随设备", "", ""),
        LOS_ANGELES("美国 · 洛杉矶", "en-US,en", "America/Los_Angeles"),
        NEW_YORK("美国 · 纽约", "en-US,en", "America/New_York"),
        UNITED_KINGDOM("英国", "en-GB,en", "Europe/London"),
        GERMANY("德国", "de-DE,de", "Europe/Berlin"),
        JAPAN("日本", "ja-JP,ja", "Asia/Tokyo"),
        SINGAPORE("新加坡", "en-SG,en", "Asia/Singapore"),
        SHANGHAI("中国 · 上海", "zh-CN,zh", "Asia/Shanghai"),
        UTC("UTC", "en-US,en", "UTC");

        public final String label;
        public final String languages;
        public final String timeZone;

        Preset(String label, String languages, String timeZone) {
            this.label = label;
            this.languages = languages;
            this.timeZone = timeZone;
        }
    }

    public EnvironmentRegionSettings(String languages, String timeZone) {
        this.languages = normalizeLanguages(languages, 8);
        this.timeZone = normalizeTimeZone(timeZone);
    }

    public static EnvironmentRegionSettings system() { return new EnvironmentRegionSettings("", ""); }

    public Preset preset() {
        for (Preset choice : Preset.values()) {
            if (languages.equals(choice.languages) && timeZone.equals(choice.timeZone)) return choice;
        }
        return null;
    }

    public static Preset matchingPreset(String languages, String timeZone) {
        try {
            return new EnvironmentRegionSettings(languages, timeZone).preset();
        } catch (IllegalArgumentException invalidDraft) {
            return null;
        }
    }

    public String primaryLanguage() {
        int comma = languages.indexOf(',');
        return comma < 0 ? languages : languages.substring(0, comma);
    }

    public void applyTo(BrowserCommandLineTarget target, String deviceLanguages) {
        String effectiveLanguages = languages.isEmpty() ? normalizeLanguages(deviceLanguages, 64) : languages;
        if (effectiveLanguages.isEmpty()) throw new IllegalArgumentException("无法读取设备网页语言");
        target.remove("accept-lang");
        target.remove("landu-web-locale");
        target.remove("landu-timezone");
        // LanguagePrefs persists accept-languages to profile prefs. Merely
        // removing the old flag can retain a previous custom language, so
        // follow-device mode resolves and applies the device list explicitly.
        target.set("accept-lang", effectiveLanguages);
        target.set("landu-web-locale", effectiveLanguages.split(",")[0]);
        if (!timeZone.isEmpty()) target.set("landu-timezone", timeZone);
    }

    private static String normalizeLanguages(String input, int maxLanguages) {
        String value = text(input, maxLanguages * 129, "网页语言");
        if (value.isEmpty()) return "";
        String[] tags = value.split(",", -1);
        if (tags.length > maxLanguages) throw new IllegalArgumentException("网页语言最多填写 " + maxLanguages + " 项");
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String trimmed = tag.trim();
            if (trimmed.isEmpty() || trimmed.length() > 128 || !trimmed.matches("[A-Za-z0-9-]+")) {
                throw new IllegalArgumentException("网页语言须为 en-US、ja-JP 等语言标签，多项用英文逗号分隔");
            }
            try {
                Locale locale = new Locale.Builder().setLanguageTag(trimmed).build();
                if (locale.getLanguage().isEmpty()) throw new IllformedLocaleException("Missing language");
                normalized.add(locale.toLanguageTag());
            } catch (IllformedLocaleException error) {
                throw new IllegalArgumentException("网页语言标签格式不正确");
            }
        }
        return String.join(",", normalized);
    }

    private static String normalizeTimeZone(String input) {
        String value = text(input, 128, "时区");
        if (value.isEmpty()) return "";
        if (!TIME_ZONES.contains(value)) throw new IllegalArgumentException("时区须为有效 IANA 名称，例如 America/Los_Angeles");
        return value;
    }

    private static String text(String input, int limit, String label) {
        if (input == null || input.length() > limit) throw new IllegalArgumentException(label + "长度或格式不正确");
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) < 0x20 || input.charAt(i) == 0x7f) throw new IllegalArgumentException(label + "包含无效控制字符");
        }
        return input.trim();
    }
}
