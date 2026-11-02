package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class EnvironmentTransferTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Invalid import accepted");
    }
    private static final class OwnedStream extends ByteArrayInputStream {
        boolean closed;
        boolean zeroFirst;
        OwnedStream(byte[] bytes, boolean zeroFirst) { super(bytes); this.zeroFirst = zeroFirst; }
        @Override public synchronized int read(byte[] bytes, int offset, int length) {
            if (zeroFirst) { zeroFirst = false; return 0; }
            return super.read(bytes, offset, length);
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).resolve("transfer-" + UUID.randomUUID()));
        EnvironmentCatalog source = new EnvironmentCatalog(root.resolve("source"));
        EnvironmentGroup work = EnvironmentGroup.create("Work"), empty = EnvironmentGroup.create("空分组");
        EnvironmentCatalog.Snapshot sourceState = source.saveGroup(0, work);
        sourceState = source.saveGroup(sourceState.revision, empty);
        EnvironmentDefinition a = EnvironmentDefinition.create("主环境", "https://example.com/", "中文备注")
                .withOrganization(new EnvironmentOrganization(0, work.id, "美国、主力"));
        a = a.withFingerprint(a.fingerprint.withHardware(8, 4).withDoNotTrack(FingerprintSettings.DNT_ENABLED)
                .withCanvasNoise(true).withRegionalFontIsolation(true)
                .withGpuMetadata(new EnvironmentGpuMetadata("vendor", "renderer", "gpu", "", "", "")))
                .withNetwork(new EnvironmentNetworkSettings("socks5://proxy.example.com:1080", true,
                        true, UUID.randomUUID().toString()))
                .withRegion(new EnvironmentRegionSettings("en-US,en", "America/New_York"))
                .withAndroidIdentity(new EnvironmentAndroidIdentity("16", "Test Phone"));
        sourceState = source.save(sourceState.revision, a);
        EnvironmentDefinition b = EnvironmentDefinition.create("备份新增", "", "");
        sourceState = source.save(sourceState.revision, b);
        byte[] beforeSource = Files.readAllBytes(root.resolve("source/environments.bin"));
        byte[] backup = source.exportConfiguration();
        check(Arrays.equals(beforeSource, Files.readAllBytes(root.resolve("source/environments.bin"))), "export never modifies original configuration");
        OwnedStream input = new OwnedStream(backup, true);
        EnvironmentCatalog.Snapshot incoming = EnvironmentCatalog.readConfiguration(input);
        check(!input.closed, "reader leaves stream ownership with caller"); input.close(); check(input.closed, "caller can close the provider stream");
        EnvironmentDefinition restored = incoming.find(a.identity);
        check(source.read().find(a.identity).network.credentialReference.equals(a.network.credentialReference),
                "portable export leaves the source's local credential binding intact");
        check(restored.network.requiresAuthentication && restored.network.credentialReference.isEmpty(),
                "authenticated SOCKS5 survives portable transfer without exporting a phone-local credential binding");
        check(restored.fingerprint.seed.equals(a.fingerprint.seed) && restored.fingerprint.hardwareConcurrency == 8
                && restored.fingerprint.deviceMemory == 4 && restored.fingerprint.canvasNoise
                && restored.fingerprint.regionalFontIsolation && restored.fingerprint.doNotTrack == FingerprintSettings.DNT_ENABLED
                && Arrays.equals(restored.fingerprint.gpuMetadata.values(), a.fingerprint.gpuMetadata.values()),
                "backup retains all supported fingerprint settings and explicit empty GPU fields");
        check(restored.network.proxyUri.equals(a.network.proxyUri) && restored.network.restrictWebRtcUdp
                && restored.region.languages.equals(a.region.languages) && restored.region.timeZone.equals(a.region.timeZone)
                && restored.androidIdentity.platformVersion.equals(a.androidIdentity.platformVersion)
                && restored.androidIdentity.model.equals(a.androidIdentity.model)
                && restored.organization.tags.equals("美国、主力") && incoming.groups.size() == 2,
                "backup preserves network, identity, regional, grouping and tag configuration");

        EnvironmentCatalog destination = new EnvironmentCatalog(root.resolve("destination"));
        EnvironmentGroup localWork = EnvironmentGroup.create("work");
        EnvironmentCatalog.Snapshot local = destination.saveGroup(0, localWork);
        EnvironmentDefinition localA = a.edit("本机名称", "", "本机备注")
                .withOrganization(new EnvironmentOrganization(0, localWork.id, "本机标签"));
        local = destination.save(local.revision, localA);
        EnvironmentDefinition outsider = EnvironmentDefinition.create("仅本机", "", "保留");
        local = destination.save(local.revision, outsider);
        Path sentinel = root.resolve("destination/private-browser-data"); Files.writeString(sentinel, "keep");
        EnvironmentCatalog.ImportResult merged = destination.importConfiguration(local.revision, incoming, false);
        check(merged.added == 1 && merged.updated == 0 && merged.skipped == 1 && merged.groupsAdded == 1, "default merge adds new entries and skips existing identities");
        check(merged.snapshot.find(a.identity).name.equals("本机名称")
                && merged.snapshot.find(a.identity).organization.tags.equals("本机标签"), "default import cannot replace local settings");
        check(merged.snapshot.find(b.identity).organization.number == 3
                && merged.snapshot.find(outsider.identity).organization.number == 2,
                "new imported environments get unused local numbers without renumbering existing environments");
        check(merged.snapshot.groups.size() == 2 && merged.snapshot.groupName(localWork.id).equals("work"), "case-equivalent group reuses local identity and name");
        EnvironmentCatalog.ImportResult updated = destination.importConfiguration(merged.snapshot.revision, incoming, true);
        check(updated.updated == 2 && updated.added == 0 && updated.skipped == 0, "explicit update mode replaces matching configuration only");
        check(updated.snapshot.find(a.identity).name.equals(a.name)
                && updated.snapshot.find(a.identity).organization.groupId.equals(localWork.id)
                && updated.snapshot.find(a.identity).organization.number == 1
                && updated.snapshot.find(b.identity).organization.number == 3,
                "updates preserve local numbering while mapping imported membership to the local group");
        check(updated.snapshot.find(outsider.identity).name.equals("仅本机") && Files.readString(sentinel).equals("keep"), "merge does not remove unlisted environments or private files");
        byte[] stable = Files.readAllBytes(root.resolve("destination/environments.bin"));
        rejects(() -> destination.importConfiguration(merged.snapshot.revision, incoming, true));
        check(Arrays.equals(stable, Files.readAllBytes(root.resolve("destination/environments.bin"))), "stale preview cannot overwrite newer catalog");

        EnvironmentCatalog conflicting = new EnvironmentCatalog(root.resolve("conflicting"));
        long created = a.createdAt + 1;
        EnvironmentDefinition collision = new EnvironmentDefinition(a.identity, "不同来源", "", "", created,
                Math.max(created, a.updatedAt), a.fingerprint);
        EnvironmentCatalog.Snapshot conflictSource = conflicting.save(0, EnvironmentDefinition.create("先处理的新环境", "", ""));
        conflicting.save(conflictSource.revision, collision);
        EnvironmentCatalog.Snapshot conflict = EnvironmentCatalog.decodeConfiguration(conflicting.exportConfiguration());
        rejects(() -> destination.importConfiguration(updated.snapshot.revision, conflict, true));
        check(Arrays.equals(stable, Files.readAllBytes(root.resolve("destination/environments.bin"))), "creation-time collision aborts whole import with original bytes retained");
        EnvironmentCatalog.ImportResult skipped = destination.importConfiguration(updated.snapshot.revision, conflict, false);
        check(skipped.skipped == 1 && skipped.added == 1 && skipped.snapshot.find(a.identity).name.equals(a.name), "conflicting identity may safely remain skipped in default mode");

        Path crowdedRoot = Files.createDirectories(root.resolve("group-limit"));
        ByteArrayOutputStream groupBytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(groupBytes)) {
            out.writeInt(0x4c444332); out.writeInt(10); out.writeLong(1); out.writeInt(0); out.writeInt(100);
            for (int i = 0; i < 100; i++) { out.writeUTF(UUID.randomUUID().toString()); out.writeUTF("本机分组" + i); }
        }
        Files.write(crowdedRoot.resolve("environments.bin"), groupBytes.toByteArray());
        EnvironmentCatalog crowded = new EnvironmentCatalog(crowdedRoot);
        rejects(() -> crowded.importConfiguration(1, incoming, false));
        check(Arrays.equals(groupBytes.toByteArray(), Files.readAllBytes(crowdedRoot.resolve("environments.bin"))),
                "merged capacity overflow aborts without writing any imported group or environment");

        byte[] future = backup.clone(); ByteBuffer.wrap(future).putInt(4, Integer.MAX_VALUE);
        byte[] trailing = Arrays.copyOf(backup, backup.length + 1);
        for (byte[] bad : new byte[][] {new byte[] {1}, future, trailing, Arrays.copyOf(backup, backup.length - 1)}) {
            rejects(() -> EnvironmentCatalog.decodeConfiguration(bad));
        }
        rejects(() -> EnvironmentCatalog.readConfiguration(new InputStream() {
            int left = 16 * 1024 * 1024 + 1;
            @Override public int read() { return left-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (left <= 0) return -1;
                int count = Math.min(left, length); Arrays.fill(bytes, offset, offset + count, (byte) 0); left -= count; return count;
            }
        }));
        EnvironmentCatalog fresh = new EnvironmentCatalog(root.resolve("fresh"));
        byte[] hugeRevision = backup.clone(); ByteBuffer.wrap(hugeRevision).putLong(8, Long.MAX_VALUE);
        EnvironmentCatalog.ImportResult freshImport = fresh.importConfiguration(0, EnvironmentCatalog.decodeConfiguration(hugeRevision), false);
        check(freshImport.snapshot.revision == 1 && freshImport.added == 2, "source revision is never used as destination revision");
        EnvironmentCatalog emptyCatalog = new EnvironmentCatalog(root.resolve("empty"));
        check(EnvironmentCatalog.decodeConfiguration(emptyCatalog.exportConfiguration()).environments.isEmpty(), "empty configuration still exports as a valid backup");
        String report = "{\"suite\":\"environment-config-transfer\",\"assertions\":" + assertions
                + ",\"passed\":true,\"documentProviderTested\":false,\"androidDeviceTested\":false}";
        Files.writeString(root.resolve("result.json"), report); System.out.println(report); System.out.println("Evidence: " + root);
    }
}
