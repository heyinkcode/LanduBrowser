package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class FingerprintSettingsTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        EnvironmentIdentity a = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
        EnvironmentIdentity b = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000002");
        FingerprintSettings first = FingerprintSettings.defaults(a);
        check(first.seed.equals(FingerprintSettings.defaults(a).seed), "Same environment must retain its seed");
        check(!first.seed.equals(FingerprintSettings.defaults(b).seed), "Different environments need different seeds");
        check(first.hardwareConcurrency == 0 && first.deviceMemory == 0, "Existing environments must default to actual hardware");
        check(!first.regionalFontIsolation, "Migration must not silently change existing font behavior");
        FingerprintSettings isolated = first.withRegionalFontIsolation(true).withHardware(8, 4);
        check(isolated.regionalFontIsolation, "Editing hardware must preserve font policy");
        check(isolated.seed.equals(first.seed), "Font policy must preserve the environment seed");
        FingerprintSettings disabled = isolated.withRegionalFontIsolation(false);
        check(!disabled.regionalFontIsolation && disabled.hardwareConcurrency == 8 && disabled.deviceMemory == 4,
                "Disabling font filtering must preserve hardware settings");
        for (int cpu : FingerprintSettings.cpuChoices()) {
            for (int memory : FingerprintSettings.memoryChoices()) {
                FingerprintSettings changed = first.withHardware(cpu, memory);
                check(changed.seed.equals(first.seed), "Changing hardware must preserve seed");
                check(changed.hardwareConcurrency == cpu && changed.deviceMemory == memory, "Hardware choice must match");
            }
        }
        for (int cpu : new int[] {-1, 1, 3, 32, Integer.MAX_VALUE}) {
            rejects(() -> first.withHardware(cpu, 0));
        }
        for (int memory : new int[] {-1, 3, 16, Integer.MAX_VALUE}) {
            rejects(() -> first.withHardware(0, memory));
        }
        for (String seed : new String[] {"", "abc", first.seed.toUpperCase(), "g".repeat(64), first.seed + "\n"}) {
            rejects(() -> new FingerprintSettings(seed, 0, 0));
        }
        int[] choices = FingerprintSettings.cpuChoices();
        choices[0] = -1;
        check(FingerprintSettings.cpuChoices()[0] == 0, "Callers must not mutate allowed settings");

        Path root = Path.of(args[0]).resolve("fingerprint-migration-" + UUID.randomUUID());
        Files.createDirectories(root);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4c444332);
            output.writeInt(1);
            output.writeLong(7);
            output.writeInt(1);
            output.writeUTF(a.id());
            output.writeUTF("原有环境");
            output.writeUTF("https://example.com/account");
            output.writeUTF("保留备注");
            output.writeLong(100);
            output.writeLong(200);
        }
        Path file = root.resolve("environments.bin");
        byte[] originalBytes = bytes.toByteArray();
        Files.write(file, originalBytes);
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentCatalog.Snapshot old = store.read();
        EnvironmentDefinition original = old.find(a);
        check(old.revision == 7, "Version migration must preserve catalog revision");
        check(original.fingerprint.seed.equals(first.seed), "Old-format seed must be deterministic");
        check(original.fingerprint.hardwareConcurrency == 0, "Old formats must preserve actual device behavior");
        check(java.util.Arrays.equals(originalBytes, Files.readAllBytes(file)), "Read must not rewrite old data");
        EnvironmentDefinition changed = original.edit("已编辑", original.startPage, original.notes)
                .withFingerprint(first.withHardware(4, 2).withRegionalFontIsolation(true));
        store.save(old.revision, changed);
        EnvironmentDefinition saved = new EnvironmentCatalog(root).read().find(a);
        check(saved.identity.equals(a), "Upgrade must not change the profile identity");
        check(saved.createdAt == 100 && saved.startPage.equals(original.startPage), "Upgrade must preserve original fields");
        check(saved.notes.equals("保留备注"), "Upgrade must preserve notes");
        check(saved.fingerprint.seed.equals(first.seed), "Seed must persist to disk");
        check(saved.fingerprint.hardwareConcurrency == 4 && saved.fingerprint.deviceMemory == 2, "Hardware settings must persist");
        check(saved.fingerprint.regionalFontIsolation, "Font policy must persist");
        check(saved.edit("再次改名", saved.startPage, saved.notes).fingerprint.regionalFontIsolation,
                "Renaming must preserve font policy");
        check(!original.fingerprint.regionalFontIsolation, "Saving must not mutate a running font snapshot");
        EnvironmentDefinition second = new EnvironmentDefinition(b, "未隔离字体", "about:blank", "", 100, 200);
        store.save(store.read().revision, second);
        EnvironmentCatalog.Snapshot pair = store.read();
        check(pair.find(a).fingerprint.regionalFontIsolation && !pair.find(b).fingerprint.regionalFontIsolation,
                "Font policy must be independent between environments");
        check(saved.edit("再次改名", saved.startPage, saved.notes).fingerprint.hardwareConcurrency == 4,
                "Renaming must preserve hardware settings");
        check(original.fingerprint.hardwareConcurrency == 0, "Editing must not mutate a running snapshot");
        for (String invalid : new String[] {"about:blank", "", "javascript:alert(1)", "file:///data/private", "https://u:p@example.com"}) {
            rejects(() -> ExternalNavigation.webAddress(invalid));
        }
        check(ExternalNavigation.webAddress("https://example.com/path?q=1").equals("https://example.com/path?q=1"),
                "Ordinary web navigation must be preserved");
        verifyVersionTwoMigration(root.resolve("version-two"), a);
        String report = "{\"suite\":\"fingerprint-persistence-and-migration\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void verifyVersionTwoMigration(Path root, EnvironmentIdentity identity) throws Exception {
        Files.createDirectories(root);
        String originalSeed = "ab".repeat(32);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4c444332);
            output.writeInt(2);
            output.writeLong(9);
            output.writeInt(1);
            output.writeUTF(identity.id());
            output.writeUTF("V2 环境");
            output.writeUTF("https://example.com/legacy");
            output.writeUTF("V2 备注");
            output.writeLong(10);
            output.writeLong(20);
            output.writeUTF(originalSeed);
            output.writeInt(12);
            output.writeInt(8);
        }
        Path file = root.resolve("environments.bin");
        byte[] oldBytes = bytes.toByteArray();
        Files.write(file, oldBytes);
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentCatalog.Snapshot before = store.read();
        EnvironmentDefinition previous = before.find(identity);
        check(before.revision == 9 && !previous.fingerprint.regionalFontIsolation, "V2 must retain revision and font behavior");
        check(previous.fingerprint.seed.equals(originalSeed), "V2 seed must not be regenerated");
        check(previous.fingerprint.hardwareConcurrency == 12 && previous.fingerprint.deviceMemory == 8,
                "V2 hardware settings must survive migration");
        check(java.util.Arrays.equals(oldBytes, Files.readAllBytes(file)), "V2 read must not rewrite the catalog");
        store.save(before.revision, previous.withFingerprint(previous.fingerprint.withRegionalFontIsolation(true)));
        EnvironmentDefinition after = store.read().find(identity);
        check(after.fingerprint.regionalFontIsolation && after.fingerprint.seed.equals(originalSeed), "Font setting must persist with old seed");
        check(after.identity.equals(previous.identity) && after.createdAt == 10 && after.notes.equals(previous.notes)
                && after.startPage.equals(previous.startPage), "Font upgrade must preserve profile and metadata");
        byte[] corrupted = Files.readAllBytes(file);
        try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(corrupted))) {
            decoded.readInt(); decoded.readInt(); decoded.readLong(); decoded.readInt();
            decoded.readUTF(); decoded.readUTF(); decoded.readUTF(); decoded.readUTF();
            decoded.readLong(); decoded.readLong(); decoded.readUTF(); decoded.readInt(); decoded.readInt();
            corrupted[corrupted.length - decoded.available()] = 2;
        }
        Files.write(file, corrupted);
        boolean rejected = false;
        try { store.read(); } catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "Unknown font policy values must be rejected, not treated as enabled");
        check(java.util.Arrays.equals(corrupted, Files.readAllBytes(file)), "Invalid settings must not overwrite existing data");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Runnable action) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid fingerprint/navigation setting was accepted");
    }
}
