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

public final class EnvironmentMediaSettingsTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("media-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        check(FingerprintSettings.defaults(A).media.mode == 0 && FingerprintSettings.forNewEnvironment(A).media.mode == 1,
                "Legacy policy remains real; new environment starts isolated");
        for (String bad : new String[] {"a\n", "a\u0000", "a\u007f", "a\u0080", "\ud800", "\udc00", "x".repeat(201)}) {
            boolean rejected = false;
            try { new EnvironmentMediaSettings(1, false, bad, "", ""); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Malformed labels must be rejected");
        }
        for (int mode : new int[] {-1, 3, 255}) {
            boolean rejected = false;
            try { new EnvironmentMediaSettings(mode, false, "", "", ""); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Unknown modes must be rejected");
        }
        String[] labels = {" 麦克风 ", "Camera", "Speaker"};
        EnvironmentMediaSettings custom = new EnvironmentMediaSettings(1, true, labels);
        labels[0] = "changed"; String[] copy = custom.labels(); copy[0] = "changed again";
        check(custom.labels()[0].equals("麦克风"), "Settings must be immutable and trim outer whitespace");
        FingerprintSettings original = FingerprintSettings.defaults(A).withMedia(custom);
        for (FingerprintSettings edited : new FingerprintSettings[] {original.withHardware(8, 4), original.withCanvasNoise(true),
                original.withRegionalFontIsolation(true), original.withDoNotTrack(1), original.withAudio(true, true),
                original.withClientRectsNoise(true), original.withGpuMetadata(EnvironmentGpuMetadata.system())}) {
            check(edited.media == custom && edited.seed.equals(original.seed), "Other edits preserve media settings and seed");
        }
        Switches target = new Switches(); target.values.put("proxy-server", "http://proxy.test:8080");
        for (int mode : new int[] {1, 2, 0, 1, 0}) {
            for (boolean blocked : new boolean[] {true, false}) {
                new EnvironmentMediaSettings(mode, blocked, "麦克风", "Camera", "Speaker").applyTo(target, original.seed);
                check(target.values.containsKey("landu-block-media") == blocked, "Capture denial is independent of identity");
                check(target.values.containsKey("landu-media-seed") == (mode != 0), "Seed is removed when returning to real mode");
                check(target.values.containsKey("landu-media-audioinput") == (mode == 1), "Custom labels cannot linger in other modes");
                check(target.values.containsKey("proxy-server"), "Media edit must preserve proxy settings");
                if (mode == 1) check(target.values.get("landu-media-audioinput").equals("e9baa6e5858be9a38e"), "Unicode label uses UTF-8 hex on the command line");
            }
        }
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        EnvironmentDefinition a = new EnvironmentDefinition(A, "A", "about:blank", "notes", 100, 200, original);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", "");
        b = b.withFingerprint(b.fingerprint.withMedia(EnvironmentMediaSettings.system()));
        catalog.save(0, a); catalog.save(1, b);
        check(catalog.read().find(A).fingerprint.media.blockCapture && catalog.read().find(b.identity).fingerprint.media.mode == 0,
                "Reload preserves independent environment choices");
        var imported = EnvironmentCatalog.readConfiguration(new java.io.ByteArrayInputStream(catalog.exportConfiguration()));
        check(Arrays.equals(imported.find(A).fingerprint.media.labels(), custom.labels()) && !imported.find(b.identity).fingerprint.media.blockCapture,
                "Backups preserve Unicode labels and permissions");
        for (int version = 1; version <= 13; ++version) {
            Path folder = root.resolve("v" + version); Files.createDirectories(folder);
            Path file = folder.resolve("environments.bin"); byte[] before = fixture(version); Files.write(file, before);
            EnvironmentCatalog old = new EnvironmentCatalog(folder); EnvironmentDefinition entry = old.read().find(A);
            check(entry.fingerprint.media.mode == 0 && !entry.fingerprint.media.blockCapture && entry.updatedAt == 200
                    && Arrays.equals(before, Files.readAllBytes(file)), "Old catalogs must keep existing behavior, timestamps and bytes");
            check(entry.fingerprint.audioNoise == (version >= 12) && entry.fingerprint.clientRectsNoise == (version >= 13),
                    "Migrating media preserves previously enabled audio and rectangle settings");
            old.save(7, entry.withFingerprint(entry.fingerprint.withMedia(custom)));
            check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17 && old.read().find(A).fingerprint.media.blockCapture,
                    "Explicit edit atomically upgrades to V17");
        }
        byte[] valid = fixture(14);
        Path damaged = root.resolve("damaged/environments.bin"); Files.createDirectories(damaged.getParent());
        for (int position : new int[] {valid.length - 12, valid.length - 11}) {
            for (int badValue : new int[] {3, 255}) {
                byte[] bad = valid.clone(); bad[position] = (byte) badValue; Files.write(damaged, bad);
                boolean rejected = false;
                try { new EnvironmentCatalog(damaged.getParent()).read(); } catch (IOException expected) { rejected = true; }
                check(rejected && Arrays.equals(bad, Files.readAllBytes(damaged)), "Invalid catalog choices must not be rewritten");
            }
        }
        System.out.println("{\"suite\":\"media-settings\",\"assertions\":" + assertions + ",\"passed\":true,\"captureRuntimeTested\":false}");
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
            if (version >= 14) { out.writeByte(0); out.writeByte(0); out.writeUTF(""); out.writeUTF(""); out.writeUTF(""); }
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
