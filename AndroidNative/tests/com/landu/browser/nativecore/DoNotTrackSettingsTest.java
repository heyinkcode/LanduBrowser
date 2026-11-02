package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class DoNotTrackSettingsTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("dnt-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        FingerprintSettings defaults = FingerprintSettings.defaults(A);
        check(defaults.doNotTrack == 0 && FingerprintSettings.forNewEnvironment(A).doNotTrack == 0,
                "Existing and new environments must initially follow their own browser preference");
        for (int invalid : new int[] {-1, 3, 255, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { defaults.withDoNotTrack(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Invalid DNT modes must be rejected");
        }
        FingerprintSettings enabled = defaults.withDoNotTrack(1).withHardware(8, 4)
                .withRegionalFontIsolation(true).withCanvasNoise(true);
        check(enabled.doNotTrack == 1 && enabled.seed.equals(defaults.seed), "Other settings must preserve DNT and seed");
        check(defaults.doNotTrack == 0, "Editing must not mutate the running snapshot");
        Switches switches = new Switches();
        switches.values.put("proxy-server", "socks5://proxy.test:1080");
        switches.values.put("accept-lang", "ja-JP,ja");
        for (int mode : new int[] {1, 2, 0, 2, 1, 0}) {
            // Both stale flags must be cleared even if they came from a previous launch.
            switches.values.put("landu-dnt-on", "");
            switches.values.put("landu-dnt-off", "");
            enabled.withDoNotTrack(mode).applyTo(switches);
            check(switches.values.containsKey("landu-dnt-on") == (mode == 1), "Enable flag must match mode");
            check(switches.values.containsKey("landu-dnt-off") == (mode == 2), "Disable flag must match mode");
            check(switches.values.get("landu-canvas-seed").equals(defaults.seed)
                    && switches.values.get("landu-hardware-concurrency").equals("8"), "DNT must preserve fingerprint delivery");
            check(switches.values.get("accept-lang").equals("ja-JP,ja") && switches.values.containsKey("proxy-server"),
                    "DNT must preserve region and network configuration");
        }
        EnvironmentDefinition a = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200, enabled);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        b = b.withFingerprint(b.fingerprint.withDoNotTrack(2));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        catalog.save(0, a);
        catalog.save(1, b);
        EnvironmentDefinition restored = catalog.read().find(A);
        check(restored.fingerprint.doNotTrack == 1 && catalog.read().find(b.identity).fingerprint.doNotTrack == 2,
                "DNT modes must be independent and persist between environments");
        check(restored.edit("renamed", "", "").withRegion(EnvironmentRegionSettings.system())
                .withNetwork(EnvironmentNetworkSettings.system()).fingerprint.doNotTrack == 1,
                "Metadata, network and region edits must preserve DNT");
        byte[] legacyV7 = null;
        for (int version = 1; version <= 7; version++) legacyV7 = migrate(root.resolve("v" + version), version);
        Path file = root.resolve("pair/environments.bin");
        byte[] valid = legacyV7;
        for (int invalid : new int[] {3, 255}) {
            byte[] damaged = valid.clone(); damaged[damaged.length - 1] = (byte) invalid;
            Files.write(file, damaged);
            boolean rejected = false;
            try { catalog.read(); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(damaged, Files.readAllBytes(file)), "Bad DNT bytes must not be silently repaired");
        }
        String report = "{\"suite\":\"environment-dnt-settings\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static byte[] migrate(Path root, int version) throws Exception {
        Files.createDirectories(root);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(version); out.writeLong(7); out.writeInt(1);
            out.writeUTF(A.id()); out.writeUTF("legacy"); out.writeUTF("about:blank"); out.writeUTF("notes");
            out.writeLong(100); out.writeLong(200);
            if (version >= 2) { out.writeUTF("ab".repeat(32)); out.writeInt(8); out.writeInt(4); }
            if (version >= 3) out.writeByte(1);
            if (version >= 4) { out.writeUTF("http://proxy.test:8080"); out.writeByte(1); }
            if (version >= 5) { out.writeUTF("ja-JP,ja"); out.writeUTF("Asia/Tokyo"); }
            if (version >= 6) out.writeByte(1);
            if (version >= 7) out.writeByte(0);
        }
        Path file = root.resolve("environments.bin");
        byte[] previous = bytes.toByteArray(); Files.write(file, previous);
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentDefinition old = catalog.read().find(A);
        check(old.fingerprint.doNotTrack == 0 && old.updatedAt == 200, "Legacy reads must preserve default DNT and timestamps");
        check(Arrays.equals(previous, Files.readAllBytes(file)), "Reading legacy data must never rewrite the file");
        catalog.save(7, old.withFingerprint(old.fingerprint.withDoNotTrack(1)));
        EnvironmentDefinition next = catalog.read().find(A);
        check(next.fingerprint.doNotTrack == 1 && next.fingerprint.seed.equals(old.fingerprint.seed),
                "Migration must persist DNT without changing seed");
        check(next.fingerprint.canvasNoise == old.fingerprint.canvasNoise
                && next.fingerprint.regionalFontIsolation == old.fingerprint.regionalFontIsolation
                && next.fingerprint.hardwareConcurrency == old.fingerprint.hardwareConcurrency
                && next.fingerprint.deviceMemory == old.fingerprint.deviceMemory
                && next.network.proxyUri.equals(old.network.proxyUri) && next.region.timeZone.equals(old.region.timeZone),
                "Migration must preserve the other environment settings");
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17, "Saving must use the current V17 catalog");
        return previous;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        public void remove(String name) { values.remove(name); }
        public void set(String name, String value) { values.put(name, value); }
    }
}
