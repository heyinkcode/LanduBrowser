package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class EnvironmentOrganizationTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid organization accepted");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).resolve("organization-" + UUID.randomUUID()));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentDefinition a = EnvironmentDefinition.create("日常", "https://example.com", "客服账号");
        EnvironmentDefinition b = EnvironmentDefinition.create("工作", "", "");
        EnvironmentCatalog.Snapshot state = catalog.save(0, a);
        state = catalog.save(state.revision, b);
        check(state.find(a.identity).organization.number == 1 && state.find(b.identity).organization.number == 2,
                "automatic unique numbers persist independently of names");
        EnvironmentGroup group = EnvironmentGroup.create("跨境业务");
        state = catalog.saveGroup(state.revision, group);
        EnvironmentDefinition member = state.find(a.identity).withOrganization(new EnvironmentOrganization(1, group.id, "主力, 美国"));
        state = catalog.save(state.revision, member);
        state = new EnvironmentCatalog(root).read();
        check(state.groups.size() == 1 && state.find(a.identity).organization.tags.equals("主力, 美国"), "group and tags survive restart");
        check(state.filter(group.id, "美国").size() == 1 && state.filter("", "").size() == 1, "group and ungrouped filters are independent");
        check(state.filter(null, "客服").size() == 1 && state.filter(null, "跨境").size() == 1
                && state.filter(null, "EXAMPLE.COM").size() == 1, "search includes notes, group, URL and case-insensitive text");
        EnvironmentCatalog.Snapshot readOnly = state;
        rejects(() -> catalog.saveGroup(readOnly.revision, EnvironmentGroup.create(" 跨境业务 ")));
        rejects(() -> catalog.save(readOnly.revision, member.withOrganization(new EnvironmentOrganization(2, group.id, ""))));
        rejects(() -> catalog.save(readOnly.revision, member.withOrganization(new EnvironmentOrganization(1, UUID.randomUUID().toString(), ""))));
        byte[] good = Files.readAllBytes(root.resolve("environments.bin"));
        rejects(() -> catalog.removeGroup(readOnly.revision - 1, group.id));
        check(Arrays.equals(good, Files.readAllBytes(root.resolve("environments.bin"))), "rejected metadata changes preserve catalog bytes");
        EnvironmentDefinition changed = member.edit("新名称", "", "备注")
                .withFingerprint(member.fingerprint.withHardware(4, 8)).withNetwork(EnvironmentNetworkSettings.system())
                .withRegion(EnvironmentRegionSettings.system()).withAndroidIdentity(EnvironmentAndroidIdentity.system());
        check(changed.organization.number == 1 && changed.organization.groupId.equals(group.id)
                && changed.organization.tags.equals("主力, 美国"), "every ordinary editor retains organization");
        String seed = state.find(a.identity).fingerprint.seed;
        state = catalog.saveGroup(state.revision, group.rename("新分组名"));
        check(state.groupName(state.find(a.identity).organization.groupId).equals("新分组名"), "renaming keeps stable group membership");
        Path sentinel = root.resolve("private-data-sentinel"); Files.writeString(sentinel, "keep browser data");
        state = catalog.removeGroup(state.revision, group.id);
        check(state.groups.isEmpty() && state.environments.size() == 2
                && state.find(a.identity).organization.groupId.isEmpty(), "removing a group only moves its members to ungrouped");
        check(state.find(a.identity).fingerprint.seed.equals(seed)
                && state.find(a.identity).organization.tags.equals("主力, 美国")
                && Files.readString(sentinel).equals("keep browser data"), "group removal preserves seed, tags, identity and unrelated private files");
        state = catalog.renumber(state.revision);
        check(state.find(a.identity).organization.number == 1 && state.find(b.identity).organization.number == 2,
                "explicit renumber preserves identities and current list order");
        EnvironmentGroup english = EnvironmentGroup.create("Work"); state = catalog.saveGroup(state.revision, english);
        EnvironmentCatalog.Snapshot englishState = state;
        rejects(() -> catalog.saveGroup(englishState.revision, EnvironmentGroup.create("work")));
        for (String bad : new String[] {"", "  ", "name\n", "x".repeat(61)}) rejects(() -> EnvironmentGroup.create(bad));
        rejects(() -> new EnvironmentOrganization(-1, "", ""));
        rejects(() -> new EnvironmentOrganization(0, "not-a-uuid", ""));
        rejects(() -> new EnvironmentOrganization(0, "", "tag\t"));
        rejects(() -> new EnvironmentOrganization(0, "", "x".repeat(161)));

        Path legacy = Files.createDirectories(root.resolve("v9"));
        EnvironmentDefinition old = a.withStoredOrganization(EnvironmentOrganization.unassigned())
                .withFingerprint(a.fingerprint.withHardware(8, 4).withDoNotTrack(FingerprintSettings.DNT_ENABLED)
                        .withGpuMetadata(new EnvironmentGpuMetadata("vendor", "renderer", "gpu", "", "", "")))
                .withNetwork(new EnvironmentNetworkSettings("socks5://proxy.example.com:1080", true))
                .withRegion(new EnvironmentRegionSettings("en-US,en", "America/New_York"))
                .withAndroidIdentity(new EnvironmentAndroidIdentity("15", "Test Phone"));
        byte[] v9 = encode(9, Arrays.asList(old, b), Collections.emptyList());
        Files.write(legacy.resolve("environments.bin"), v9);
        EnvironmentCatalog oldCatalog = new EnvironmentCatalog(legacy); EnvironmentCatalog.Snapshot migrated = oldCatalog.read();
        check(migrated.find(a.identity).organization.number == 1 && migrated.find(b.identity).organization.number == 2,
                "V9 gains deterministic display numbers without rewriting");
        check(Arrays.equals(v9, Files.readAllBytes(legacy.resolve("environments.bin"))), "reading V9 leaves original bytes untouched");
        migrated = oldCatalog.saveGroup(migrated.revision, EnvironmentGroup.create("新增分组"));
        EnvironmentDefinition restored = migrated.find(a.identity);
        check(ByteBuffer.wrap(Files.readAllBytes(legacy.resolve("environments.bin"))).getInt(4) == 17,
                "first mutation upgrades catalog atomically to V15");
        check(restored.createdAt == old.createdAt && restored.updatedAt == old.updatedAt
                && restored.fingerprint.seed.equals(old.fingerprint.seed)
                && Arrays.equals(restored.fingerprint.gpuMetadata.values(), old.fingerprint.gpuMetadata.values())
                && restored.fingerprint.hardwareConcurrency == 8 && restored.fingerprint.deviceMemory == 4
                && restored.fingerprint.doNotTrack == FingerprintSettings.DNT_ENABLED
                && restored.region.languages.equals("en-US,en") && restored.region.timeZone.equals("America/New_York")
                && restored.androidIdentity.model.equals("Test Phone")
                && restored.network.proxyUri.equals(old.network.proxyUri), "group creation during upgrade preserves original environment configuration and timestamps");
        for (List<EnvironmentDefinition> invalid : Arrays.asList(
                Arrays.asList(old, b),
                Arrays.asList(old.withStoredOrganization(new EnvironmentOrganization(1, "", "")), b.withStoredOrganization(new EnvironmentOrganization(1, "", ""))),
                Arrays.asList(old.withStoredOrganization(new EnvironmentOrganization(1, group.id, ""))))) {
            byte[] bytes = encode(10, invalid, Collections.emptyList());
            Files.write(legacy.resolve("environments.bin"), bytes);
            rejects(oldCatalog::read);
            check(Arrays.equals(bytes, Files.readAllBytes(legacy.resolve("environments.bin"))), "invalid number or orphan group does not repair/overwrite original catalog");
        }
        byte[] duplicateGroups = encode(10, Collections.singletonList(old.withStoredOrganization(new EnvironmentOrganization(1, "", ""))), Arrays.asList(group, group));
        Files.write(legacy.resolve("environments.bin"), duplicateGroups); rejects(oldCatalog::read);
        byte[] gaps = encode(10, Arrays.asList(
                old.withStoredOrganization(new EnvironmentOrganization(19, "", "一组")),
                b.withStoredOrganization(new EnvironmentOrganization(4, "", "二组"))), Collections.emptyList());
        Files.write(legacy.resolve("environments.bin"), gaps);
        EnvironmentCatalog.Snapshot reordered = oldCatalog.renumber(7);
        check(reordered.find(a.identity).organization.number == 1 && reordered.find(b.identity).organization.number == 2
                && reordered.find(a.identity).organization.tags.equals("一组")
                && reordered.find(a.identity).fingerprint.seed.equals(old.fingerprint.seed),
                "gapped out-of-order display numbers renumber by persisted order without changing other settings");
        java.util.ArrayList<EnvironmentGroup> excessive = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) excessive.add(EnvironmentGroup.create("分组" + i));
        byte[] overLimit = encode(10, Collections.emptyList(), excessive);
        Files.write(legacy.resolve("environments.bin"), overLimit); rejects(oldCatalog::read);
        check(Arrays.equals(overLimit, Files.readAllBytes(legacy.resolve("environments.bin"))), "over-limit group input remains untouched");
        String report = "{\"suite\":\"environment-organization\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidDeviceTested\":false}";
        Files.writeString(root.resolve("result.json"), report); System.out.println(report); System.out.println("Evidence: " + root);
    }

    private static byte[] encode(int version, List<EnvironmentDefinition> entries, List<EnvironmentGroup> groups) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(version); out.writeLong(7); out.writeInt(entries.size());
            for (EnvironmentDefinition e : entries) {
                out.writeUTF(e.identity.id()); out.writeUTF(e.name); out.writeUTF(e.startPage); out.writeUTF(e.notes);
                out.writeLong(e.createdAt); out.writeLong(e.updatedAt); out.writeUTF(e.fingerprint.seed);
                out.writeInt(e.fingerprint.hardwareConcurrency); out.writeInt(e.fingerprint.deviceMemory);
                out.writeByte(e.fingerprint.regionalFontIsolation ? 1 : 0); out.writeUTF(e.network.proxyUri);
                out.writeByte(e.network.restrictWebRtcUdp ? 1 : 0); out.writeUTF(e.region.languages); out.writeUTF(e.region.timeZone);
                out.writeByte(e.fingerprint.canvasNoise ? 1 : 0); out.writeByte(e.fingerprint.doNotTrack);
                out.writeUTF(e.androidIdentity.platformVersion); out.writeUTF(e.androidIdentity.model);
                for (String value : e.fingerprint.gpuMetadata.values()) out.writeUTF(value);
                if (version >= 10) { out.writeInt(e.organization.number); out.writeUTF(e.organization.groupId); out.writeUTF(e.organization.tags); }
            }
            if (version >= 10) {
                out.writeInt(groups.size());
                for (EnvironmentGroup group : groups) { out.writeUTF(group.id); out.writeUTF(group.name); }
            }
        }
        return bytes.toByteArray();
    }
}
