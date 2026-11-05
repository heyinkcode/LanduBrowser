package com.landu.browser.nativecore;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One validated source for UA, HTTP client hints and navigator metadata in every realm. */
public final class EnvironmentUserAgentSettings {
    public static final int FIELD_COUNT = 11;
    private static final String[] LABELS = {"User-Agent", "Chrome 完整版本", "UA-CH 平台", "UA-CH 平台版本",
        "UA-CH 架构", "UA-CH 位数", "UA-CH 型号", "navigator.platform", "UA-CH brands",
        "UA-CH fullVersionList", "UA-CH formFactors"};
    private static final Set<String> PLATFORMS = new HashSet<>(Arrays.asList("Android", "Windows", "macOS", "Linux", "Chrome OS"));
    private static final Set<String> FACTORS = new HashSet<>(Arrays.asList("Desktop", "Automotive", "Mobile", "Tablet", "XR", "EInk", "Watch"));
    public final boolean custom;
    public final boolean mobile;
    public final boolean wow64;
    private final String[] fields;

    public EnvironmentUserAgentSettings(boolean custom, String[] values, boolean mobile, boolean wow64) {
        if (values == null || values.length != FIELD_COUNT) throw new IllegalArgumentException("UA-CH 字段数量无效");
        this.custom = custom;
        this.mobile = custom && mobile;
        this.wow64 = custom && wow64;
        fields = values.clone();
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null || fields[i].length() > (i == 0 ? 1024 : 512)) invalid(i);
            fields[i] = fields[i].trim();
            for (int j = 0; j < fields[i].length(); j++) {
                char c = fields[i].charAt(j);
                if (c > 126 || (c < 32 && !((i == 8 || i == 9) && c == '\n'))) invalid(i);
            }
        }
        if (!custom) {
            if (mobile || wow64) throw new IllegalArgumentException("自动 UA-CH 不应包含自定义标识");
            for (String field : fields) if (!field.isEmpty()) throw new IllegalArgumentException("自动 UA-CH 不应包含自定义字段");
            return;
        }
        fields[1] = normalizeVersion(fields[1]);
        String major = fields[1].split("\\.")[0];
        if (!PLATFORMS.contains(fields[2])) invalid(2);
        if (!fields[3].matches("[0-9]{1,3}(\\.[0-9]{1,5}){0,2}")) invalid(3);
        if (!fields[4].matches("|arm|x86")) invalid(4);
        if (!fields[5].matches("|32|64")) invalid(5);
        if (fields[6].length() > 80 || !fields[6].matches("[A-Za-z0-9 ._()/-]*")) invalid(6);
        if (!fields[7].matches("Win32|MacIntel|Linux x86_64|Linux armv8l|Linux armv81|Linux aarch64")) invalid(7);
        if ((fields[2].equals("Windows") && !fields[7].equals("Win32"))
                || (fields[2].equals("macOS") && !fields[7].equals("MacIntel"))
                || (!fields[2].equals("Windows") && !fields[2].equals("macOS") && !fields[7].startsWith("Linux "))) invalid(7);
        if (!fields[2].equals("Android") && !fields[6].isEmpty()) throw new IllegalArgumentException("桌面 UA-CH 型号须留空");
        if (wow64 && (!fields[2].equals("Windows") || !fields[4].equals("x86") || !fields[5].equals("64"))) {
            throw new IllegalArgumentException("WoW64 需匹配 Windows / x86 / 64 位");
        }
        String[][] brands = pairs(fields[8], false);
        String[][] fullBrands = pairs(fields[9], true);
        if (brands.length != fullBrands.length) throw new IllegalArgumentException("brands 与 fullVersionList 须一一对应");
        boolean chromium = false;
        for (int i = 0; i < brands.length; i++) {
            if (!brands[i][0].equals(fullBrands[i][0]) || !brands[i][1].equals(fullBrands[i][1].split("\\.")[0])) {
                throw new IllegalArgumentException("brands 与 fullVersionList 的品牌及主版本须一致");
            }
            if (brands[i][0].equals("Chromium") || brands[i][0].equals("Google Chrome")) {
                if (!brands[i][1].equals(major) || !fullBrands[i][1].equals(fields[1])) {
                    throw new IllegalArgumentException("Chrome / Chromium 品牌版本须匹配 Chrome 完整版本");
                }
                chromium = true;
            }
        }
        if (!chromium) throw new IllegalArgumentException("UA-CH 品牌列表须包含 Chromium 或 Google Chrome");
        Set<String> seen = new HashSet<>();
        if (fields[10].isEmpty()) invalid(10);
        for (String factor : fields[10].split(",", -1)) if (!FACTORS.contains(factor.trim()) || !seen.add(factor.trim())) invalid(10);
        if (fields[0].isEmpty()) fields[0] = generatedUa(major);
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("Chrome/([0-9]+(?:\\.[0-9]+){3})(?: |$)").matcher(fields[0]);
        if (!token.find() || !(token.group(1).equals(fields[1]) || token.group(1).equals(major + ".0.0.0"))) {
            throw new IllegalArgumentException("User-Agent 的 Chrome 版本须与 UA-CH 完整版本匹配");
        }
        boolean uaMobile = fields[0].contains(" Mobile ");
        String expectedOs = fields[2].equals("Android") ? "Android" : fields[2].equals("Windows") ? "Windows NT"
                : fields[2].equals("macOS") ? "Macintosh" : fields[2].equals("Chrome OS") ? "CrOS" : "Linux";
        if (uaMobile != mobile || !fields[0].contains(expectedOs)) throw new IllegalArgumentException("User-Agent 的系统与 Mobile 标识须匹配 UA-CH");
    }

    public static EnvironmentUserAgentSettings automatic() {
        String[] values = new String[FIELD_COUNT]; Arrays.fill(values, "");
        return new EnvironmentUserAgentSettings(false, values, false, false);
    }

    public static EnvironmentUserAgentSettings androidDefaults(String version, String platformVersion, String model) {
        String full = normalizeVersion(version);
        String major = full.split("\\.")[0];
        String[] values = {"", full, "Android", platformVersion, "", "", model, "Linux armv8l",
            "Not_A Brand=99\nChromium=" + major + "\nGoogle Chrome=" + major,
            "Not_A Brand=99.0.0.0\nChromium=" + full + "\nGoogle Chrome=" + full, "Mobile"};
        return new EnvironmentUserAgentSettings(true, values, true, false);
    }

    public static EnvironmentUserAgentSettings androidWithMajor(String kernelVersion, String major,
            EnvironmentAndroidIdentity identity) {
        String actual = normalizeVersion(kernelVersion);
        if (major == null || !major.matches("[1-9][0-9]{1,2}")) {
            throw new IllegalArgumentException("Chrome 主版本须为 10–999");
        }
        String version = major.equals(actual.split("\\.")[0]) ? actual : major + ".0.0.0";
        return androidDefaults(version, identity.platformVersion, identity.model);
    }

    public String[] values() { return fields.clone(); }
    public static String label(int i) { return LABELS[i]; }

    public String metadataJson() {
        if (!custom) return "";
        return "{\"fullVersion\":" + quote(fields[1]) + ",\"platform\":" + quote(fields[2])
                + ",\"platformVersion\":" + quote(fields[3]) + ",\"architecture\":" + quote(fields[4])
                + ",\"bitness\":" + quote(fields[5]) + ",\"model\":" + quote(fields[6])
                + ",\"mobile\":" + mobile + ",\"wow64\":" + wow64 + ",\"brands\":" + pairJson(fields[8], false)
                + ",\"fullVersionList\":" + pairJson(fields[9], true) + ",\"formFactors\":["
                + formFactorsJson() + "]}";
    }

    private String formFactorsJson() {
        StringBuilder result = new StringBuilder();
        for (String factor : fields[10].split(",")) {
            if (result.length() > 0) result.append(',');
            result.append(quote(factor.trim()));
        }
        return result.toString();
    }

    public void applyTo(BrowserCommandLineTarget target) {
        target.remove("user-agent"); target.remove("landu-user-agent-metadata"); target.remove("landu-navigator-platform");
        if (custom) {
            target.set("user-agent", fields[0]); target.set("landu-user-agent-metadata", metadataJson());
            target.set("landu-navigator-platform", fields[7]);
        }
    }

    private String generatedUa(String major) {
        String os;
        switch (fields[2]) {
            case "Android": os = "Linux; Android " + fields[3] + (fields[6].isEmpty() ? "" : "; " + fields[6]); break;
            case "Windows": os = "Windows NT 10.0; Win64; x64"; break;
            case "macOS": os = "Macintosh; Intel Mac OS X 10_15_7"; break;
            case "Chrome OS": os = "X11; CrOS x86_64 14541.0.0"; break;
            default: os = "X11; Linux x86_64";
        }
        return "Mozilla/5.0 (" + os + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major + ".0.0.0"
                + (mobile ? " Mobile" : "") + " Safari/537.36";
    }

    private static String normalizeVersion(String value) {
        if (value == null || !value.matches("[1-9][0-9]{1,2}(\\.[0-9]{1,5}){0,3}")) invalid(1);
        String result = value;
        for (int n = value.split("\\.").length; n < 4; n++) result += ".0";
        return result;
    }

    private static String[][] pairs(String value, boolean full) {
        String[] lines = value.split("\n", -1);
        if (lines.length < 1 || lines.length > 8) throw new IllegalArgumentException("UA-CH 品牌列表须为 1–8 行，每行 品牌=版本");
        String[][] result = new String[lines.length][2]; Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].lastIndexOf('=');
            if (at < 1) throw new IllegalArgumentException("UA-CH 品牌列表每行须为 品牌=版本");
            String name = lines[i].substring(0, at).trim();
            String version = lines[i].substring(at + 1).trim();
            if (!name.matches("[A-Za-z0-9 ._;:!?()/-]{1,64}") || !seen.add(name)
                    || !version.matches(full ? "[0-9]{1,3}(\\.[0-9]{1,5}){3}" : "[0-9]{1,3}")) {
                throw new IllegalArgumentException("UA-CH 品牌名称、版本无效或重复");
            }
            result[i][0] = name; result[i][1] = version;
        }
        return result;
    }

    private static String pairJson(String value, boolean full) {
        StringBuilder json = new StringBuilder("[");
        for (String[] pair : pairs(value, full)) {
            if (json.length() > 1) json.append(',');
            json.append("{\"brand\":").append(quote(pair[0])).append(",\"version\":").append(quote(pair[1])).append('}');
        }
        return json.append(']').toString();
    }

    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static void invalid(int i) { throw new IllegalArgumentException(LABELS[i] + " 格式不正确"); }
}
