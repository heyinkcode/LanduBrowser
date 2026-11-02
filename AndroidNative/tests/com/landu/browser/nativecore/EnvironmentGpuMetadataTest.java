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

public final class EnvironmentGpuMetadataTest {
    private static int assertions;
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
    private static final String[] VALUES = {"Google Inc. (Qualcomm)", "ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)",
            "qualcomm", "adreno-7xx", "0x1234", "Adreno (TM) 740"};
    private static final String[] FLAGS = {"landu-webgl-vendor", "landu-webgl-renderer", "landu-webgpu-vendor",
            "landu-webgpu-architecture", "landu-webgpu-device", "landu-webgpu-description"};

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).resolve("gpu-metadata-" + UUID.randomUUID()));
        EnvironmentGpuMetadata metadata = new EnvironmentGpuMetadata(VALUES);
        check(!metadata.isSystem() && EnvironmentGpuMetadata.system().isSystem(), "Native defaults and custom identities must differ");
        String[] copy = metadata.values(); copy[0] = "changed";
        check(metadata.values()[0].equals(VALUES[0]), "Callers must not mutate the selected identity");
        String[] padded = VALUES.clone(); padded[0] = " " + padded[0] + " ";
        check(new EnvironmentGpuMetadata(padded).values()[0].equals(VALUES[0]), "Normalize input before native transport");
        rejects(() -> new EnvironmentGpuMetadata((String[]) null));
        rejects(() -> new EnvironmentGpuMetadata("short"));
        for (int i = 0; i < VALUES.length; i++) {
            final int index = i;
            for (String bad : new String[] {null, "x\n", "x\t", "x\0", "x\u007f", "显卡", "x".repeat(EnvironmentGpuMetadata.limit(i) + 1)}) {
                String[] values = VALUES.clone(); values[index] = bad;
                rejects(() -> new EnvironmentGpuMetadata(values));
            }
            String[] boundary = VALUES.clone(); boundary[i] = "x".repeat(EnvironmentGpuMetadata.limit(i));
            check(new EnvironmentGpuMetadata(boundary).values()[i].equals(boundary[i]), "Printable limit must remain usable");
        }
        for (int i = 0; i < 3; i++) {
            String[] values = VALUES.clone(); values[i] = " ";
            rejects(() -> new EnvironmentGpuMetadata(values));
        }
        EnvironmentGpuMetadata hidden = new EnvironmentGpuMetadata(VALUES[0], VALUES[1], VALUES[2], "", "", "");
        Switches target = new Switches(); target.values.put("accept-lang", "en-US,en");
        metadata.applyTo(target);
        for (int i = 0; i < FLAGS.length; i++) check(VALUES[i].equals(target.values.get(FLAGS[i])), "Every native flag must be present");
        hidden.applyTo(target);
        for (int i = 3; i < FLAGS.length; i++) check(target.values.containsKey(FLAGS[i]) && target.values.get(FLAGS[i]).isEmpty(), "Hidden optional fields must stay explicit");
        EnvironmentGpuMetadata.system().applyTo(target);
        check(target.values.size() == 1 && target.values.get("accept-lang").equals("en-US,en"), "Real mode must remove all stale GPU flags only");
        FingerprintSettings selected = FingerprintSettings.defaults(A).withGpuMetadata(metadata);
        FingerprintSettings other = selected.withHardware(8, 4).withCanvasNoise(true).withRegionalFontIsolation(true).withDoNotTrack(1);
        check(other.gpuMetadata == metadata && other.seed.equals(selected.seed), "Other fingerprint edits must preserve the immutable GPU selection");
        other.applyTo(target);
        check(target.values.get(FLAGS[1]).equals(VALUES[1]) && target.values.get("landu-device-memory").equals("4"), "GPU and hardware flags must coexist");
        rejects(() -> selected.withGpuMetadata(null));
        EnvironmentDefinition initial = new EnvironmentDefinition(A, "A", "", "notes", 100, 200, other);
        EnvironmentDefinition edited = initial.edit("renamed", "", "").withNetwork(EnvironmentNetworkSettings.system())
                .withRegion(EnvironmentRegionSettings.system()).withAndroidIdentity(new EnvironmentAndroidIdentity("16", ""));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("pair"));
        catalog.save(0, edited);
        EnvironmentDefinition b = EnvironmentDefinition.create("B", "", ""); catalog.save(1, b);
        check(Arrays.equals(catalog.read().find(A).fingerprint.gpuMetadata.values(), VALUES), "Saving metadata and other edits must preserve every GPU field");
        check(catalog.read().find(b.identity).fingerprint.gpuMetadata.isSystem(), "GPU identities must remain independent across environments");
        for (int version = 1; version <= 8; version++) migrate(root.resolve("v" + version), version, metadata);
        for (String[] bad : new String[][] {{"", "renderer", "vendor", "", "", ""},
                {"vendor", "renderer", "vendor", "", "", "secret\n"}}) {
            Path badRoot = Files.createDirectories(root.resolve("invalid-" + assertions));
            byte[] data = fixture(9, bad); Files.write(badRoot.resolve("environments.bin"), data);
            expectCorruptionPreserved(badRoot, data);
        }
        Path truncated = Files.createDirectories(root.resolve("truncated"));
        byte[] full = fixture(9, VALUES); byte[] data = Arrays.copyOf(full, full.length - 1);
        Files.write(truncated.resolve("environments.bin"), data); expectCorruptionPreserved(truncated, data);
        String report = "{\"suite\":\"environment-gpu-metadata\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report); System.out.println(report); System.out.println("Evidence: " + root);
    }

    private static void migrate(Path root, int version, EnvironmentGpuMetadata metadata) throws Exception {
        Files.createDirectories(root); byte[] previous = fixture(version, null);
        Path file = root.resolve("environments.bin"); Files.write(file, previous);
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentDefinition old = catalog.read().find(A);
        check(old.fingerprint.gpuMetadata.isSystem() && old.updatedAt == 200, "Old catalog must retain real GPU identity and timestamps");
        check(Arrays.equals(previous, Files.readAllBytes(file)), "Reading an old catalog must not rewrite it");
        EnvironmentDefinition next = new EnvironmentDefinition(old.identity, old.name, old.startPage, old.notes, old.createdAt,
                old.updatedAt, old.fingerprint.withGpuMetadata(metadata), old.network, old.region, old.androidIdentity);
        catalog.save(7, next); EnvironmentDefinition saved = catalog.read().find(A);
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17, "Save must upgrade to V17");
        check(Arrays.equals(saved.fingerprint.gpuMetadata.values(), VALUES), "Migration must keep all custom GPU values");
        check(saved.fingerprint.seed.equals(old.fingerprint.seed) && saved.fingerprint.canvasNoise == old.fingerprint.canvasNoise
                && saved.fingerprint.doNotTrack == old.fingerprint.doNotTrack && saved.fingerprint.regionalFontIsolation == old.fingerprint.regionalFontIsolation
                && saved.network.proxyUri.equals(old.network.proxyUri) && saved.region.languages.equals(old.region.languages)
                && saved.androidIdentity.platformVersion.equals(old.androidIdentity.platformVersion)
                && saved.androidIdentity.model.equals(old.androidIdentity.model), "Migration must preserve all existing fingerprint/network/identity data");
    }

    private static byte[] fixture(int version, String[] gpu) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(version); out.writeLong(7); out.writeInt(1);
            out.writeUTF(A.id()); out.writeUTF("legacy"); out.writeUTF("about:blank"); out.writeUTF("notes"); out.writeLong(100); out.writeLong(200);
            if (version >= 2) { out.writeUTF("ab".repeat(32)); out.writeInt(8); out.writeInt(4); }
            if (version >= 3) out.writeByte(1);
            if (version >= 4) { out.writeUTF("http://proxy.test:8080"); out.writeByte(1); }
            if (version >= 5) { out.writeUTF("ja-JP,ja"); out.writeUTF("Asia/Tokyo"); }
            if (version >= 6) out.writeByte(1);
            if (version >= 7) out.writeByte(1);
            if (version >= 8) { out.writeUTF("16.0.0"); out.writeUTF("Pixel 8"); }
            if (version >= 9) for (String value : gpu) out.writeUTF(value);
        }
        return bytes.toByteArray();
    }
    private static void expectCorruptionPreserved(Path root, byte[] data) throws Exception {
        boolean rejected = false;
        try { new EnvironmentCatalog(root).read(); } catch (IOException expected) { rejected = true; }
        check(rejected && Arrays.equals(data, Files.readAllBytes(root.resolve("environments.bin"))), "Malformed GPU data must be rejected without overwriting the catalog");
    }
    private static void rejects(Runnable action) { boolean rejected = false; try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; } check(rejected, "Invalid settings must be rejected"); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        @Override public void remove(String name) { values.remove(name); }
        @Override public void set(String name, String value) { values.put(name, value); }
    }
}
