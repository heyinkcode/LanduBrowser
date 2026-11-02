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

public final class ClientRectsSettingsTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("client-rects-" + UUID.randomUUID());
        Files.createDirectories(root);
        FingerprintSettings legacy = FingerprintSettings.defaults(A);
        check(!legacy.clientRectsNoise, "Legacy environments retain real measurements");
        check(FingerprintSettings.forNewEnvironment(A).clientRectsNoise, "New environments enable stable noise");
        FingerprintSettings enabled = legacy.withClientRectsNoise(true);
        for (FingerprintSettings changed : new FingerprintSettings[] {enabled.withHardware(8, 4),
                enabled.withRegionalFontIsolation(true), enabled.withCanvasNoise(true), enabled.withDoNotTrack(1),
                enabled.withGpuMetadata(EnvironmentGpuMetadata.system()), enabled.withAudio(true, true)}) {
            check(changed.clientRectsNoise && changed.seed.equals(legacy.seed), "Other edits preserve the policy and seed");
        }
        check(!legacy.clientRectsNoise, "Edits must leave existing snapshots unchanged");
        Switches switches = new Switches();
        switches.values.put("proxy-server", "http://proxy.test:8080");
        enabled.withAudio(true, true).applyTo(switches);
        check(switches.values.get("landu-client-rects-seed").equals(legacy.seed), "Enabled flag must reach the native process");
        enabled.withAudio(true, true).withClientRectsNoise(false).applyTo(switches);
        check(!switches.values.containsKey("landu-client-rects-seed") && switches.values.containsKey("landu-audio-seed")
                && switches.values.containsKey("mute-audio") && switches.values.containsKey("proxy-server"),
                "Disabling must remove stale rect flags while preserving independent settings");
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        EnvironmentDefinition a = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200, enabled);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        b = b.withFingerprint(b.fingerprint.withClientRectsNoise(false));
        catalog.save(0, a); catalog.save(1, b);
        check(catalog.read().find(A).fingerprint.clientRectsNoise && !catalog.read().find(b.identity).fingerprint.clientRectsNoise,
                "Separate environments retain their own policies after reload");
        byte[] backup = catalog.exportConfiguration();
        var imported = EnvironmentCatalog.readConfiguration(new java.io.ByteArrayInputStream(backup));
        check(imported.find(A).fingerprint.clientRectsNoise && !imported.find(b.identity).fingerprint.clientRectsNoise,
                "Configuration backup must preserve both enabled and disabled selections");
        for (int version = 1; version <= 12; ++version) {
            Path folder = root.resolve("v" + version); Files.createDirectories(folder);
            byte[] before = fixture(version); Path file = folder.resolve("environments.bin"); Files.write(file, before);
            EnvironmentCatalog old = new EnvironmentCatalog(folder);
            var entry = old.read().find(A);
            check(!entry.fingerprint.clientRectsNoise && entry.updatedAt == 200 && Arrays.equals(before, Files.readAllBytes(file)),
                    "Reading old catalogs must not silently change measurements, timestamps or files");
            check(entry.fingerprint.audioNoise == (version >= 12) && entry.fingerprint.muteAudio == (version >= 12),
                    "V12 audio choices must survive migration");
            old.save(7, entry.withFingerprint(entry.fingerprint.withClientRectsNoise(true)));
            check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17 && old.read().find(A).fingerprint.clientRectsNoise,
                    "Explicit edits upgrade atomically to V17");
        }
        byte[] valid = fixture(13);
        Path damaged = root.resolve("damaged/environments.bin"); Files.createDirectories(damaged.getParent());
        for (int invalid = 2; invalid <= 255; ++invalid) {
            byte[] bad = valid.clone(); bad[bad.length - 5] = (byte) invalid; Files.write(damaged, bad);
            boolean rejected = false;
            try { new EnvironmentCatalog(damaged.getParent()).read(); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(bad, Files.readAllBytes(damaged)), "Invalid policy bytes must be rejected without data loss");
        }
        System.out.println("{\"suite\":\"client-rects-settings\",\"assertions\":" + assertions + ",\"passed\":true,\"deviceTested\":false}");
    }

    private static byte[] fixture(int version) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(version); out.writeLong(7); out.writeInt(1);
            out.writeUTF(A.id().toString()); out.writeUTF("A"); out.writeUTF("about:blank"); out.writeUTF("notes");
            out.writeLong(100); out.writeLong(200);
            if (version >= 2) { out.writeUTF("ab".repeat(32)); out.writeInt(8); out.writeInt(4); }
            if (version >= 3) out.writeByte(1);
            if (version >= 4) { out.writeUTF("http://proxy.test:8080"); out.writeByte(1); }
            if (version >= 5) { out.writeUTF("ja-JP,ja"); out.writeUTF("Asia/Tokyo"); }
            if (version >= 6) out.writeByte(1);
            if (version >= 7) out.writeByte(1);
            if (version >= 8) { out.writeUTF(""); out.writeUTF(""); }
            if (version >= 9) for (int i = 0; i < 6; ++i) out.writeUTF("");
            if (version >= 10) { out.writeInt(1); out.writeUTF(""); out.writeUTF(""); }
            if (version >= 11) { out.writeByte(0); out.writeDouble(0); out.writeDouble(0); out.writeDouble(0); }
            if (version >= 12) { out.writeByte(1); out.writeByte(1); }
            if (version >= 13) out.writeByte(1);
            if (version >= 10) out.writeInt(0);
        }
        return bytes.toByteArray();
    }
    private static void check(boolean ok, String message) { ++assertions; if (!ok) throw new AssertionError(message); }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        @Override public void remove(String name) { values.remove(name); }
        @Override public void set(String name, String value) { values.put(name, value); }
    }
}
