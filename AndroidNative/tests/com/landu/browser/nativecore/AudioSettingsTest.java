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

public final class AudioSettingsTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("audio-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        FingerprintSettings legacy = FingerprintSettings.defaults(A);
        FingerprintSettings fresh = FingerprintSettings.forNewEnvironment(A);
        check(!legacy.audioNoise && !legacy.muteAudio, "Legacy settings must stay unchanged");
        check(fresh.audioNoise && !fresh.muteAudio, "New environments use stable noise without muting output");
        FingerprintSettings changed = legacy.withAudio(true, true).withHardware(8, 4)
                .withCanvasNoise(true).withRegionalFontIsolation(true).withDoNotTrack(1)
                .withGpuMetadata(EnvironmentGpuMetadata.system());
        check(changed.audioNoise && changed.muteAudio && changed.seed.equals(legacy.seed),
                "Every fingerprint edit must preserve the audio policy and stable seed");
        check(!legacy.audioNoise && !legacy.muteAudio, "Edits must not mutate the running snapshot");
        Switches switches = new Switches();
        switches.values.put("proxy-server", "http://proxy.test:8080");
        for (boolean noise : new boolean[] {true, false}) {
            for (boolean muted : new boolean[] {true, false}) {
                switches.values.put("landu-audio-seed", "stale");
                switches.values.put("mute-audio", "");
                changed.withAudio(noise, muted).applyTo(switches);
                check(switches.values.containsKey("landu-audio-seed") == noise, "Noise delivery must match selection");
                check(!noise || switches.values.get("landu-audio-seed").equals(legacy.seed), "Seed must be stable");
                check(switches.values.containsKey("mute-audio") == muted, "Mute must be independent of noise");
                check(switches.values.get("proxy-server").equals("http://proxy.test:8080")
                        && switches.values.get("landu-canvas-seed").equals(legacy.seed), "Other settings must survive");
            }
        }
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        EnvironmentDefinition a = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200, changed);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        b = b.withFingerprint(b.fingerprint.withAudio(false, false));
        catalog.save(0, a); catalog.save(1, b);
        var restored = catalog.read();
        check(restored.find(A).fingerprint.audioNoise && restored.find(A).fingerprint.muteAudio
                && !restored.find(b.identity).fingerprint.audioNoise && !restored.find(b.identity).fingerprint.muteAudio,
                "Environments must keep separate settings across reloads");
        byte[] backup = catalog.exportConfiguration();
        var imported = EnvironmentCatalog.readConfiguration(new java.io.ByteArrayInputStream(backup));
        check(imported.find(A).fingerprint.muteAudio && !imported.find(b.identity).fingerprint.audioNoise,
                "Configuration backups must preserve both audio fields");
        for (int version = 1; version <= 11; ++version) {
            Path folder = root.resolve("v" + version); Files.createDirectories(folder);
            byte[] oldBytes = legacyFile(version);
            Path file = folder.resolve("environments.bin"); Files.write(file, oldBytes);
            EnvironmentCatalog old = new EnvironmentCatalog(folder);
            var oldEnvironment = old.read().find(A);
            check(!oldEnvironment.fingerprint.audioNoise && !oldEnvironment.fingerprint.muteAudio
                    && oldEnvironment.updatedAt == 200 && Arrays.equals(oldBytes, Files.readAllBytes(file)),
                    "Reading old catalogs must not silently change behavior, timestamps or disk content");
            old.save(7, oldEnvironment.withFingerprint(oldEnvironment.fingerprint.withAudio(true, true)));
            check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17
                    && old.read().find(A).fingerprint.audioNoise && old.read().find(A).fingerprint.muteAudio,
                    "Explicit edits must atomically upgrade to V17");
        }
        byte[] valid = legacyFile(12);
        Path damagedFile = root.resolve("damaged/environments.bin"); Files.createDirectories(damagedFile.getParent());
        for (int position : new int[] {valid.length - 6, valid.length - 5}) {
            for (int invalid : new int[] {2, 255}) {
                byte[] bad = valid.clone(); bad[position] = (byte) invalid;
                Files.write(damagedFile, bad);
                boolean rejected = false;
                try { new EnvironmentCatalog(damagedFile.getParent()).read(); } catch (IOException expected) { rejected = true; }
                check(rejected && Arrays.equals(bad, Files.readAllBytes(damagedFile)), "Invalid flags must preserve the original file");
            }
        }
        System.out.println("{\"suite\":\"audio-settings\",\"assertions\":" + assertions + ",\"passed\":true,\"deviceTested\":false}");
    }

    private static byte[] legacyFile(int version) throws Exception {
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
            if (version >= 12) { out.writeByte(0); out.writeByte(0); }
            if (version >= 10) out.writeInt(0);
        }
        return bytes.toByteArray();
    }
    private static void check(boolean ok, String message) {
        ++assertions;
        if (!ok) throw new AssertionError(message);
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        @Override public void remove(String name) { values.remove(name); }
        @Override public void set(String name, String value) { values.put(name, value); }
    }
}
