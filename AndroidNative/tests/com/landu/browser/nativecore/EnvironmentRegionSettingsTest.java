package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvironmentRegionSettingsTest {
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
    private static final EnvironmentIdentity B = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000002");
    private static int assertions;

    public static void main(String[] args) throws Exception {
        validation();
        presetSelection();
        commandLine();
        Path root = Path.of(args[0]).resolve("region-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        persistence(root.resolve("current"));
        for (int version = 1; version <= 4; version++) migration(root.resolve("v" + version), version);
        corruption(root.resolve("corrupt"));
        String report = "{\"suite\":\"environment-region-settings\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void validation() {
        for (String[] values : new String[][] {
                {"", ""}, {" EN-us, en ,EN-US ", "en-US,en"}, {"zh-hans-cn,zh-CN", "zh-Hans-CN,zh-CN"},
                {"iw-IL", "he-IL"}, {"sr-latn-rs", "sr-Latn-RS"}, {"fr-CA,fr,en-CA,en", "fr-CA,fr,en-CA,en"},
                {"en-u-ca-gregory", "en-u-ca-gregory"},
        }) {
            EnvironmentRegionSettings region = new EnvironmentRegionSettings(values[0], "");
            check(region.languages.equals(values[1]), "Language normalization failed");
            check(region.primaryLanguage().equals(values[1].split(",")[0]), "Primary language must be the first preference");
        }
        for (String zone : new String[] {"", "UTC", "Etc/UTC", "America/Los_Angeles", "Europe/London", "Asia/Tokyo", "US/Pacific"}) {
            check(new EnvironmentRegionSettings("", zone).timeZone.equals(zone), "Valid IANA zone must be retained");
        }
        for (String invalid : new String[] {null, "en_US", "en US", "en-US,", ",en", "en,,ja", "*", "und", "x-private",
                "en-abcdefghi", "en-US\0", "en\r\nheader", "en;ja", "en,ja,fr,de,es,it,pt,ko,zh", "a".repeat(1025)}) {
            rejects(() -> new EnvironmentRegionSettings(invalid, ""));
        }
        for (String invalid : new String[] {null, "No/Such_Zone", "America/los_angeles", "UTC\0", "UTC\r", "utc", "+08:00", "Z", "GMT+08:00", "../UTC"}) {
            rejects(() -> new EnvironmentRegionSettings("", invalid));
        }
    }

    private static void commandLine() {
        Switches switches = new Switches();
        switches.values.put("lang", "zh-CN");
        switches.values.put("proxy-server", "http://proxy.test:8080");
        new EnvironmentRegionSettings("en-US,en", "America/Los_Angeles").applyTo(switches, "zh-CN,zh");
        check(switches.values.get("accept-lang").equals("en-US,en"), "HTTP language preferences must be configured");
        check(switches.values.get("landu-web-locale").equals("en-US"), "Renderer locale must use the same first language");
        check(switches.values.get("landu-timezone").equals("America/Los_Angeles"), "Renderer time zone must be configured");
        check(switches.values.get("lang").equals("zh-CN"), "Web language must not change the browser UI language");
        check(switches.values.get("proxy-server").equals("http://proxy.test:8080"), "Region settings must preserve network configuration");
        EnvironmentRegionSettings.system().applyTo(switches, "zh-CN,zh");
        check(switches.values.get("accept-lang").equals("zh-CN,zh") && switches.values.get("landu-web-locale").equals("zh-CN"),
                "Following the device must replace stale profile languages with the current device list");
        check(!switches.values.containsKey("landu-timezone") && switches.values.get("lang").equals("zh-CN")
                && switches.values.containsKey("proxy-server"), "Following the device must preserve UI/network settings and remove fixed timezone");
        EnvironmentRegionSettings.system().applyTo(switches, "en,ja,fr,de,es,it,pt,ko,zh");
        check(switches.values.get("accept-lang").split(",").length == 9, "System locale lists must not inherit the manual eight-language limit");
        rejects(() -> EnvironmentRegionSettings.system().applyTo(switches, ""));
    }

    private static void presetSelection() {
        for (EnvironmentRegionSettings.Preset preset : EnvironmentRegionSettings.Preset.values()) {
            check(new EnvironmentRegionSettings(preset.languages, preset.timeZone).preset() == preset,
                    "Saved settings must identify the matching radio selection");
        }
        check(EnvironmentRegionSettings.matchingPreset(" EN-us, en ,EN-US ", " America/Los_Angeles ")
                == EnvironmentRegionSettings.Preset.LOS_ANGELES, "Canonical language and zone input must select Los Angeles");
        check(EnvironmentRegionSettings.matchingPreset("de-DE,de", "Europe/Berlin")
                == EnvironmentRegionSettings.Preset.GERMANY, "German settings must not check Los Angeles");
        check(EnvironmentRegionSettings.matchingPreset("en-US,en", "Europe/Berlin") == null,
                "Mixed manual settings must remain custom");
        check(EnvironmentRegionSettings.matchingPreset("en-US,", "America/Los_Angeles") == null,
                "Invalid unfinished input must not check a preset");
        check(EnvironmentRegionSettings.matchingPreset("", "") == EnvironmentRegionSettings.Preset.DEVICE,
                "Blank settings must check follow-device mode");
        EnvironmentRegionSettings.Preset[] options = EnvironmentRegionSettings.Preset.values();
        options[1] = EnvironmentRegionSettings.Preset.GERMANY;
        check(EnvironmentRegionSettings.matchingPreset("en-US,en", "America/Los_Angeles")
                == EnvironmentRegionSettings.Preset.LOS_ANGELES, "A caller must not alter the shared preset mapping");
    }

    private static void persistence(Path root) throws Exception {
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentDefinition before = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200);
        store.save(0, before.withRegion(new EnvironmentRegionSettings("en-US,en", "America/Los_Angeles")));
        store.save(1, new EnvironmentDefinition(B, "B", "about:blank", "", 100, 200)
                .withRegion(new EnvironmentRegionSettings("ja-JP,ja", "Asia/Tokyo")));
        EnvironmentCatalog.Snapshot pair = store.read();
        EnvironmentDefinition saved = pair.find(A);
        check(saved.region.languages.equals("en-US,en") && saved.region.timeZone.equals("America/Los_Angeles"), "Region settings must persist");
        check(saved.region.preset() == EnvironmentRegionSettings.Preset.LOS_ANGELES,
                "A reopened catalog must restore the Los Angeles selection");
        check(pair.find(B).region.timeZone.equals("Asia/Tokyo"), "Another environment must retain its independent region");
        check(before.region.languages.isEmpty() && before.region.timeZone.isEmpty(), "Editing must preserve an active snapshot");
        EnvironmentDefinition edited = saved.edit("renamed", saved.startPage, saved.notes)
                .withNetwork(new EnvironmentNetworkSettings("socks5://proxy.test:1080", true))
                .withFingerprint(saved.fingerprint.withHardware(8, 4).withRegionalFontIsolation(true));
        check(edited.region.timeZone.equals(saved.region.timeZone) && edited.region.languages.equals(saved.region.languages),
                "Other settings edits must preserve region settings");
        EnvironmentDefinition changed = edited.withRegion(new EnvironmentRegionSettings("en-GB,en", "Europe/London"));
        check(changed.network.proxyUri.equals(edited.network.proxyUri) && changed.fingerprint.regionalFontIsolation,
                "Region edits must preserve network and fingerprint settings");
        check(changed.identity.equals(A) && changed.createdAt == 100 && changed.fingerprint.seed.equals(before.fingerprint.seed),
                "Region changes must preserve identity, creation time and fingerprint seed");
    }

    private static void migration(Path root, int version) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("environments.bin");
        byte[] bytes = fixture(version, "", "");
        Files.write(file, bytes);
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentDefinition old = store.read().find(A);
        check(old.region.languages.isEmpty() && old.region.timeZone.isEmpty(), "Legacy catalogs must follow device locale and timezone");
        check(old.createdAt == 100 && old.updatedAt == 200, "Reading legacy settings must preserve timestamps");
        check(Arrays.equals(bytes, Files.readAllBytes(file)), "Reading legacy catalogs must not rewrite the source");
        check(old.fingerprint.regionalFontIsolation == (version >= 3), "Migration must preserve font settings");
        check(old.network.restrictWebRtcUdp == (version >= 4), "Migration must preserve network settings");
        store.save(7, old.withRegion(new EnvironmentRegionSettings("en-US,en", "America/New_York")));
        EnvironmentDefinition upgraded = store.read().find(A);
        check(upgraded.region.timeZone.equals("America/New_York") && upgraded.region.languages.equals("en-US,en"), "Upgrade must persist the new region");
        check(upgraded.identity.equals(A) && upgraded.fingerprint.seed.equals(old.fingerprint.seed)
                && upgraded.network.proxyUri.equals(old.network.proxyUri), "Upgrade must preserve existing profile configuration");
    }

    private static void corruption(Path root) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("environments.bin");
        for (byte[] invalid : new byte[][] {fixture(5, "en-US,,en", "UTC"), fixture(5, "en-US", "No/Such_Zone")}) {
            Files.write(file, invalid);
            boolean rejected = false;
            try { new EnvironmentCatalog(root).read(); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(invalid, Files.readAllBytes(file)), "Invalid region settings must be rejected without overwriting data");
        }
    }

    private static byte[] fixture(int version, String languages, String zone) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4c444332); output.writeInt(version); output.writeLong(7); output.writeInt(1);
            output.writeUTF(A.id()); output.writeUTF("legacy"); output.writeUTF("about:blank"); output.writeUTF("");
            output.writeLong(100); output.writeLong(200);
            if (version >= 2) { output.writeUTF("ab".repeat(32)); output.writeInt(8); output.writeInt(4); }
            if (version >= 3) output.writeByte(1);
            if (version >= 4) { output.writeUTF("http://proxy.test:8080"); output.writeByte(1); }
            if (version >= 5) { output.writeUTF(languages); output.writeUTF(zone); }
        }
        return bytes.toByteArray();
    }

    private static void check(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid region configuration accepted");
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        public void remove(String name) { values.remove(name); }
        public void set(String name, String value) { values.put(name, value); }
    }
}
