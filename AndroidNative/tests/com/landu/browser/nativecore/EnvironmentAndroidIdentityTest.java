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

public final class EnvironmentAndroidIdentityTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("android-identity-" + UUID.randomUUID());
        Files.createDirectories(root);
        check(EnvironmentAndroidIdentity.system().isSystem(), "Legacy default must follow the device");
        EnvironmentAndroidIdentity custom = new EnvironmentAndroidIdentity(" 15.1 ", " Pixel 8 ");
        check(custom.platformVersion.equals("15.1.0") && custom.model.equals("Pixel 8"), "Input must canonicalize before native delivery");
        check(new EnvironmentAndroidIdentity("16", "").platformVersion.equals("16.0.0"), "An empty custom model must not disable the identity");
        for (String version : new String[] {"9", "100", "015", "15.00", "15.0.00", "15.-1", "15.1000",
                "15.0.1000", "15.1.2.3", "15\n", "15\t", "15\u007f", "１５"}) {
            rejects(() -> new EnvironmentAndroidIdentity(version, "Pixel 8"));
        }
        rejects(() -> new EnvironmentAndroidIdentity(null, ""));
        rejects(() -> new EnvironmentAndroidIdentity("15", null));
        rejects(() -> new EnvironmentAndroidIdentity("", "Pixel 8"));
        for (String model : new String[] {"x;y", "x/y", "x\"y", "x\ty", "x\ny", "x\u007fy", "x\0y", "小米", "x".repeat(81)}) {
            rejects(() -> new EnvironmentAndroidIdentity("15", model));
        }
        java.util.Random random = new java.util.Random(51415);
        java.util.Set<String> models = new java.util.HashSet<>();
        String previousModel = "";
        for (int i = 0; i < 100; i++) {
            EnvironmentAndroidIdentity generated = EnvironmentAndroidIdentity.random(random, previousModel);
            check(!generated.isSystem() && !generated.model.equals(previousModel), "Random choices must change model and remain explicit");
            check(generated.platformVersion.equals("15.0.0") || generated.platformVersion.equals("16.0.0"), "Model and supported Android version must be selected together");
            EnvironmentUserAgentSettings ua = EnvironmentUserAgentSettings.androidWithMajor("156.0.8078.48", "156", generated);
            check(ua.values()[1].equals("156.0.8078.48") && ua.values()[6].equals(generated.model), "Generated identity must keep the real complete kernel version and paired UA-CH model");
            check(ua.values()[0].contains(generated.model) && ua.mobile, "Generated mobile UA must use the same device model");
            models.add(generated.model); previousModel = generated.model;
        }
        check(models.size() == 9, "Random generation must use the complete curated device pool");
        EnvironmentAndroidIdentity randomIdentity = EnvironmentAndroidIdentity.random(random, previousModel);
        EnvironmentUserAgentSettings manual = EnvironmentUserAgentSettings.androidWithMajor("156.0.8078.48", "131", randomIdentity);
        check(manual.values()[1].equals("131.0.0.0") && manual.values()[0].contains("Chrome/131.0.0.0"), "Explicit major versions must update UA and Client Hints together");
        rejects(() -> EnvironmentUserAgentSettings.androidWithMajor("156.0.8078.48", "1560", randomIdentity));
        EnvironmentCatalog randomCatalog = new EnvironmentCatalog(root.resolve("random-roundtrip"));
        EnvironmentDefinition randomEnvironment = EnvironmentDefinition.create("random", "", "").withAndroidIdentity(randomIdentity);
        randomCatalog.save(0, randomEnvironment);
        EnvironmentAndroidIdentity restoredRandom = randomCatalog.read().find(randomEnvironment.identity).androidIdentity;
        check(restoredRandom.model.equals(randomIdentity.model) && restoredRandom.platformVersion.equals(randomIdentity.platformVersion), "Generated identity must persist without changing on catalog reload");
        Switches switches = new Switches();
        switches.values.put("accept-lang", "ja-JP,ja");
        switches.values.put("landu-canvas-seed", "kept");
        custom.applyTo(switches);
        check(switches.values.get("landu-android-version").equals("15.1.0") && switches.values.get("landu-android-model").equals("Pixel 8"),
                "Native identity switches must be paired and canonical");
        new EnvironmentAndroidIdentity("16", "").applyTo(switches);
        check(switches.values.containsKey("landu-android-model") && switches.values.get("landu-android-model").isEmpty(),
                "An explicit empty model must be present, not fall back to the real device");
        EnvironmentAndroidIdentity.system().applyTo(switches);
        check(!switches.values.containsKey("landu-android-version") && !switches.values.containsKey("landu-android-model"),
                "Returning to device defaults must clear both stale flags");
        check(switches.values.get("accept-lang").equals("ja-JP,ja") && switches.values.get("landu-canvas-seed").equals("kept"),
                "Identity changes must preserve unrelated configuration");
        EnvironmentDefinition initial = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200);
        EnvironmentDefinition edited = initial.withAndroidIdentity(custom);
        EnvironmentDefinition renamed = edited.edit("renamed", "", "").withNetwork(EnvironmentNetworkSettings.system())
                .withRegion(EnvironmentRegionSettings.system()).withFingerprint(edited.fingerprint.withDoNotTrack(1));
        check(renamed.androidIdentity == custom && initial.androidIdentity.isSystem(), "Metadata edits must preserve immutable identity snapshots");
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        catalog.save(0, renamed);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        catalog.save(1, b);
        check(catalog.read().find(A).androidIdentity.model.equals("Pixel 8") && catalog.read().find(b.identity).androidIdentity.isSystem(),
                "Identity settings must remain independent between environments");
        for (int version = 1; version <= 7; version++) migrate(root.resolve("v" + version), version);
        for (String[] bad : new String[][] {{"9.0.0", "x"}, {"", "x"}, {"15.0.0", "x;y"}, {"15.0.0", "小米"}}) {
            Path badRoot = root.resolve("bad-" + assertions); Files.createDirectories(badRoot);
            byte[] invalid = fixture(8, bad[0], bad[1]);
            Files.write(badRoot.resolve("environments.bin"), invalid);
            boolean rejected = false;
            try { new EnvironmentCatalog(badRoot).read(); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(invalid, Files.readAllBytes(badRoot.resolve("environments.bin"))),
                    "Invalid new-format identity data must be preserved and rejected");
        }
        String report = "{\"suite\":\"environment-android-identity\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void migrate(Path root, int version) throws Exception {
        Files.createDirectories(root);
        byte[] previous = fixture(version, "", "");
        Path file = root.resolve("environments.bin"); Files.write(file, previous);
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentDefinition old = catalog.read().find(A);
        check(old.androidIdentity.isSystem() && old.updatedAt == 200, "Legacy identity must retain the actual device and timestamps");
        check(Arrays.equals(previous, Files.readAllBytes(file)), "Reading old formats must never rewrite the original file");
        catalog.save(7, old.withAndroidIdentity(new EnvironmentAndroidIdentity("16", "")));
        EnvironmentDefinition next = catalog.read().find(A);
        check(next.androidIdentity.platformVersion.equals("16.0.0") && next.androidIdentity.model.isEmpty(), "Migration must persist an explicit hidden model");
        check(next.fingerprint.seed.equals(old.fingerprint.seed) && next.fingerprint.canvasNoise == old.fingerprint.canvasNoise
                && next.fingerprint.regionalFontIsolation == old.fingerprint.regionalFontIsolation
                && next.fingerprint.doNotTrack == old.fingerprint.doNotTrack
                && next.network.proxyUri.equals(old.network.proxyUri) && next.region.languages.equals(old.region.languages),
                "Migration must preserve all previous environment policies");
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 18, "Saving must upgrade to current V18");
    }

    private static byte[] fixture(int version, String androidVersion, String model) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(version); out.writeLong(7); out.writeInt(1);
            out.writeUTF(A.id()); out.writeUTF("legacy"); out.writeUTF("about:blank"); out.writeUTF("notes"); out.writeLong(100); out.writeLong(200);
            if (version >= 2) { out.writeUTF("ab".repeat(32)); out.writeInt(8); out.writeInt(4); }
            if (version >= 3) out.writeByte(1);
            if (version >= 4) { out.writeUTF("http://proxy.test:8080"); out.writeByte(1); }
            if (version >= 5) { out.writeUTF("ja-JP,ja"); out.writeUTF("Asia/Tokyo"); }
            if (version >= 6) out.writeByte(1);
            if (version >= 7) out.writeByte(2);
            if (version >= 8) { out.writeUTF(androidVersion); out.writeUTF(model); }
        }
        return bytes.toByteArray();
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid Android identity accepted");
    }
    private interface Action { void run() throws Exception; }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        public void remove(String name) { values.remove(name); }
        public void set(String name, String value) { values.put(name, value); }
    }
}
