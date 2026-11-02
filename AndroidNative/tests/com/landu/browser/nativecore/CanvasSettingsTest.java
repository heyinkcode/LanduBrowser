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

public final class CanvasSettingsTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("canvas-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        FingerprintSettings defaults = FingerprintSettings.defaults(A);
        check(!defaults.canvasNoise, "Existing behavior must be the default");
        FingerprintSettings fresh = FingerprintSettings.forNewEnvironment(A);
        check(fresh.canvasNoise && fresh.regionalFontIsolation && fresh.seed.equals(defaults.seed),
                "Fresh environments must enable fingerprint protection with their own stable seed");
        check(!defaults.regionalFontIsolation && !defaults.canvasNoise, "Legacy defaults must preserve the saved behavior");
        FingerprintSettings enabled = defaults.withCanvasNoise(true).withHardware(8, 4).withRegionalFontIsolation(true);
        check(enabled.canvasNoise && enabled.seed.equals(defaults.seed), "Other edits must preserve Canvas policy and seed");
        check(!defaults.canvasNoise, "An active immutable snapshot must not change");
        check(!enabled.withCanvasNoise(false).canvasNoise && enabled.withCanvasNoise(false).regionalFontIsolation,
                "Disabling Canvas must preserve other fingerprint settings");
        Switches switches = new Switches();
        switches.values.put("proxy-server", "http://proxy.test:8080");
        switches.values.put("landu-web-locale", "ja-JP");
        enabled.applyTo(switches);
        check(switches.values.get("landu-canvas-seed").equals(defaults.seed), "Enabled native seed must match the environment");
        defaults.applyTo(switches);
        check(!switches.values.containsKey("landu-canvas-seed") && !switches.values.containsKey("landu-hardware-concurrency")
                && !switches.values.containsKey("landu-device-memory") && !switches.values.containsKey("landu-isolate-regional-fonts"),
                "Disabling settings must clear every stale fingerprint switch");
        check(switches.values.containsKey("proxy-server") && switches.values.get("landu-web-locale").equals("ja-JP"),
                "Fingerprint changes must preserve network and region switches");
        byte[] legacyV6 = null;
        for (int version = 1; version <= 6; version++) legacyV6 = migrate(root.resolve("v" + version), version);

        EnvironmentCatalog store = new EnvironmentCatalog(root.resolve("pair"));
        EnvironmentDefinition a = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200,
                enabled, new EnvironmentNetworkSettings("socks5://proxy.test:1080", true),
                new EnvironmentRegionSettings("ja-JP,ja", "Asia/Tokyo"));
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        check(b.fingerprint.canvasNoise && b.fingerprint.regionalFontIsolation,
                "The real environment creation factory must enable new-environment protection");
        check(!b.fingerprint.seed.equals(a.fingerprint.seed), "New environments must not reuse another environment's seed");
        b = b.withFingerprint(b.fingerprint.withCanvasNoise(false));
        store.save(0, a);
        store.save(1, b);
        EnvironmentDefinition restored = store.read().find(A);
        check(restored.fingerprint.canvasNoise && !store.read().find(b.identity).fingerprint.canvasNoise,
                "Canvas settings must be independent between environments");
        check(restored.updatedAt == 200 && restored.createdAt == 100, "Reading the catalog must preserve both timestamps");
        check(restored.fingerprint.seed.equals(defaults.seed) && restored.fingerprint.regionalFontIsolation
                && restored.network.restrictWebRtcUdp && restored.region.timeZone.equals("Asia/Tokyo"),
                "Catalog round trip must preserve the full environment");
        check(restored.edit("renamed", "about:blank", "").withRegion(EnvironmentRegionSettings.system())
                .withNetwork(EnvironmentNetworkSettings.system()).fingerprint.canvasNoise, "Metadata edits must preserve Canvas isolation");
        Path file = root.resolve("pair/environments.bin");
        byte[] damaged = legacyV6.clone();
        damaged[damaged.length - 1] = 2;
        Files.write(file, damaged);
        boolean rejected = false;
        try { store.read(); } catch (IOException expected) { rejected = true; }
        check(rejected && Arrays.equals(damaged, Files.readAllBytes(file)), "Invalid Canvas flag must preserve the original file");
        String report = "{\"suite\":\"environment-canvas-settings\",\"assertions\":" + assertions
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
            if (version >= 6) out.writeByte(0);
        }
        Path file = root.resolve("environments.bin");
        byte[] previous = bytes.toByteArray();
        Files.write(file, previous);
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentDefinition old = store.read().find(A);
        check(!old.fingerprint.canvasNoise && old.updatedAt == 200, "Legacy reads must retain behavior and timestamps");
        check(Arrays.equals(previous, Files.readAllBytes(file)), "Reading legacy data must not rewrite it");
        store.save(7, old.withFingerprint(old.fingerprint.withCanvasNoise(true)));
        EnvironmentDefinition next = store.read().find(A);
        check(next.fingerprint.canvasNoise && next.fingerprint.seed.equals(old.fingerprint.seed), "Upgrade must persist Canvas without changing the seed");
        check(next.region.timeZone.equals(old.region.timeZone) && next.network.proxyUri.equals(old.network.proxyUri)
                && next.fingerprint.regionalFontIsolation == old.fingerprint.regionalFontIsolation,
                "Upgrade must preserve region, network and font settings");
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17, "The new catalog must use current V17");
        return previous;
    }

    private static void check(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        public void remove(String name) { values.remove(name); }
        public void set(String name, String value) { values.put(name, value); }
    }
}
