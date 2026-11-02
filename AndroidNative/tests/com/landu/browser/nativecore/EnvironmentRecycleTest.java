package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise real catalog files and the real process lease; no Android UI substitutes. */
public final class EnvironmentRecycleTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("recycle-" + UUID.randomUUID());
        Path control = root.resolve("control");
        EnvironmentCatalog catalog = new EnvironmentCatalog(control);
        EnvironmentLeaseStore leases = new EnvironmentLeaseStore(control);
        EnvironmentDefinition a = EnvironmentDefinition.create("日常 A", "https://example.com/", "原备注");
        EnvironmentDefinition b = EnvironmentDefinition.create("日常 B", "", "");
        EnvironmentCatalog.Snapshot snapshot = catalog.save(0, a);
        snapshot = catalog.save(snapshot.revision, b);
        a = snapshot.find(a.identity);
        b = snapshot.find(b.identity);
        EnvironmentDefinition originalA = a;
        EnvironmentDefinition originalB = b;
        Path profile = root.resolve("environments").resolve(a.identity.directoryName()).resolve("profile");
        Files.createDirectories(profile);
        Path marker = profile.resolve("retained-session-marker");
        Files.writeString(marker, "test-login-and-storage-remain");
        byte[] browserData = Files.readAllBytes(marker);
        Path file = control.resolve("environments.bin");

        snapshot = catalog.recycle(snapshot.revision, a.identity);
        check(snapshot.environments.size() == 1 && snapshot.recycledEnvironments.size() == 1, "Recycled is not active");
        check(snapshot.allEnvironments.size() == 2, "Recycling keeps full definitions");
        check(snapshot.filter(null, "日常 A").isEmpty(), "List search excludes recycled environments");
        rejects(() -> catalog.read().find(originalA.identity));
        rejects(() -> leases.requestLaunch(originalA.identity));
        check(leases.observe().status == EnvironmentLeaseStore.Status.IDLE, "Rejected stale launch leaves no pending selection");
        check(Arrays.equals(browserData, Files.readAllBytes(marker)), "Recycling never changes profile data");
        byte[] archivedBytes = Files.readAllBytes(file);
        final long revision = snapshot.revision;
        rejects(() -> catalog.recycle(revision, originalA.identity));
        rejects(() -> catalog.restore(revision - 1, originalA.identity));
        rejects(() -> catalog.restore(revision, originalB.identity));
        rejects(() -> catalog.restore(revision, EnvironmentIdentity.parse(UUID.randomUUID().toString())));
        rejects(() -> catalog.save(revision, originalA.edit("stale edit", "", "")));
        check(Arrays.equals(archivedBytes, Files.readAllBytes(file)), "Rejected mutations preserve catalog bytes");
        EnvironmentCatalog reopened = new EnvironmentCatalog(control);
        EnvironmentDefinition retained = reopened.read().recycledEnvironments.get(0);
        check(retained.identity.equals(a.identity) && retained.organization.number == a.organization.number, "Identity and number survive restart");
        check(retained.fingerprint.seed.equals(a.fingerprint.seed) && retained.createdAt == a.createdAt
                && retained.updatedAt == a.updatedAt && retained.notes.equals(a.notes), "Recycling does not edit environment configuration");
        rejectsUnsupported(() -> reopened.read().recycledEnvironments.clear());

        byte[] backup = catalog.exportConfiguration();
        EnvironmentCatalog destination = new EnvironmentCatalog(root.resolve("import"));
        EnvironmentCatalog.ImportResult imported = destination.importConfiguration(0,
                EnvironmentCatalog.decodeConfiguration(backup), false);
        check(imported.added == 2 && imported.snapshot.environments.size() == 1
                && imported.snapshot.recycledEnvironments.size() == 1, "Backup includes recycled state without activating it");
        rejects(() -> new EnvironmentLeaseStore(root.resolve("import")).prepareLaunch(originalA.identity));
        long importedRevision = imported.snapshot.revision;
        rejects(() -> destination.importConfiguration(importedRevision, EnvironmentCatalog.decodeConfiguration(backup), true));
        check(destination.read().revision == importedRevision, "Import cannot silently overwrite recycled local identity");
        imported = destination.importConfiguration(importedRevision, EnvironmentCatalog.decodeConfiguration(backup), false);
        check(imported.skipped == 2 && imported.snapshot.recycledEnvironments.size() == 1, "Default import preserves recycled duplicate");

        snapshot = catalog.restore(catalog.read().revision, a.identity);
        retained = snapshot.find(a.identity);
        check(snapshot.environments.size() == 2 && snapshot.recycledEnvironments.isEmpty(), "Restore returns original environment");
        check(retained.organization.number == a.organization.number && retained.fingerprint.seed.equals(a.fingerprint.seed), "Restore keeps number and fingerprint");
        check(Arrays.equals(browserData, Files.readAllBytes(marker)), "Restore never clears login/storage data");
        imported = catalog.importConfiguration(snapshot.revision, EnvironmentCatalog.decodeConfiguration(backup), true);
        check(imported.snapshot.environments.size() == 2 && imported.snapshot.recycledEnvironments.isEmpty(), "Archived backup cannot recycle an active local environment");

        EnvironmentLeaseStore.Ticket ticket = leases.prepareLaunch(a.identity);
        long pendingRevision = catalog.read().revision;
        rejectsBusy(() -> catalog.recycle(pendingRevision, originalA.identity));
        check(catalog.read().revision == pendingRevision, "Pending launch blocks catalog transition");
        try (EnvironmentLeaseStore.Session session = leases.claimSelected(root.resolve("environments"))) {
            rejectsBusy(() -> catalog.recycle(pendingRevision, originalB.identity));
            check(catalog.read().revision == pendingRevision, "Running session blocks recycle, including other profiles");
        }
        check(leases.observe().status == EnvironmentLeaseStore.Status.IDLE, "Normal close releases transition lock");

        EnvironmentGroup group = EnvironmentGroup.create("原分组");
        snapshot = catalog.saveGroup(catalog.read().revision, group);
        snapshot = catalog.save(snapshot.revision, snapshot.find(a.identity)
                .withOrganization(snapshot.find(a.identity).organization.withGroup(group.id)));
        snapshot = catalog.recycle(snapshot.revision, a.identity);
        snapshot = catalog.removeGroup(snapshot.revision, group.id);
        check(snapshot.recycledEnvironments.get(0).organization.groupId.isEmpty(), "Removing a group also reconciles recycled membership");
        snapshot = catalog.save(snapshot.revision, EnvironmentDefinition.create("C", "", ""));
        check(snapshot.allEnvironments.get(2).organization.number == 3, "New environment does not reuse recycled number");
        snapshot = catalog.renumber(snapshot.revision);
        check(snapshot.recycledEnvironments.size() == 1, "Renumbering does not restore recycled environment");
        snapshot = catalog.restore(snapshot.revision, a.identity);
        check(snapshot.find(a.identity).organization.groupId.isEmpty(), "Restore respects removed group");

        javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(new byte[32], "AES");
        ProxyCredentialStore secrets = new ProxyCredentialStore(control, create -> key);
        EnvironmentNetworkSettings endpoint = new EnvironmentNetworkSettings("http://proxy.test:8080", true, true, "");
        String reference = secrets.put(a.identity, endpoint, new ProxyCredentials("owned-recycle-user", "owned-recycle-password"));
        EnvironmentNetworkSettings authenticated = new EnvironmentNetworkSettings(endpoint.proxyUri, true, true, reference);
        snapshot = catalog.save(snapshot.revision, snapshot.find(a.identity).withNetwork(authenticated));
        snapshot = catalog.recycle(snapshot.revision, a.identity);
        check(snapshot.recycledEnvironments.get(0).network.credentialReference.equals(reference), "Recycling retains local credential reference");
        byte[] archivedExport = catalog.exportConfiguration();
        EnvironmentDefinition portable = EnvironmentCatalog.decodeConfiguration(archivedExport).recycledEnvironments.get(0);
        check(portable.network.requiresAuthentication && portable.network.credentialReference.isEmpty(), "Recycled backup also strips private references");
        snapshot = catalog.restore(snapshot.revision, a.identity);
        check(secrets.read(a.identity, snapshot.find(a.identity).network).username.equals("owned-recycle-user"), "Restored environment decrypts original credential record");
        check(Arrays.equals(browserData, Files.readAllBytes(marker)), "Credential and group operations preserve browser data");

        // An operation holding the launch lock prevents racing prepareLaunch, not just UI observation.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread guarded = new Thread(() -> {
            try { leases.whileIdle(() -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out waiting for test release"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                return null;
            }); } catch (Throwable error) { failure.set(error); }
        });
        guarded.start();
        check(entered.await(5, TimeUnit.SECONDS), "Real lock acquired by transition");
        try { rejects(() -> leases.prepareLaunch(originalA.identity)); }
        finally { release.countDown(); guarded.join(5000); }
        check(!guarded.isAlive() && failure.get() == null, "Transition releases lock normally");
        ticket = leases.prepareLaunch(a.identity);
        leases.cancelPending(ticket);

        legacyMigrationAndCorruption(root.resolve("legacy-migration"));
        String report = "{\"suite\":\"environment-recycle\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidDeviceTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void legacyMigrationAndCorruption(Path control) throws Exception {
        EnvironmentCatalog catalog = new EnvironmentCatalog(control);
        Path file = control.resolve("environments.bin");
        EnvironmentIdentity legacyIdentity = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
        // V15 bytes are derived by removing only V16's empty recycle footer.
        byte[] v16 = java.util.Base64.getDecoder().decode("TERDMgAAABAAAAAAAAAAAwAAAAMAJDEwYWYwMTAxLTIwMDAtNDAwMC04MDAwLTAwMDAwMDAwMDAwMQAGbGVnYWN5AAthYm91dDpibGFuawAAAAAAAAAAAGQAAAAAAAAAyABAMzkyNGNhZmE4M2RjMjRiOTBiMWY5MjVlMTJiMDZjNjljMjA2YzUwOTE3Y2E2MzljZWZhNjAwOWIxN2MzNjQ2YQAAAAAAAAAAAAAAAAAIZW4tVVMsZW4AE0FtZXJpY2EvTG9zX0FuZ2VsZXMAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAkMTBhZjAxMDEtMjAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAyAA1sZWdhY3kgc3lzdGVtAAthYm91dDpibGFuawAAAAAAAAAAAGQAAAAAAAAAyABAMGYyMWQ1NGQ1YTFkYjI2M2FhZWM5MDZlNTI2NjExNjQ0NGY2MTBiMjI4Mjg4NmI1MTZmOWQyMjg3OWMxNWUwMwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAkMTBhZjAxMDEtMjAwMC00MDAwLTgwMDAtMDAwMDAwMDAwMDAzAA1sZWdhY3kgY3VzdG9tAAthYm91dDpibGFuawAAAAAAAAAAAGQAAAAAAAAAyABAMzRmOTIxZDg4MTJmYjRlZGNlMTFhMjA0ODJkZmVkYTk5NTEwYTc0MmM4NzIxOTI1ZDRmYWUxMTUxMTdhZmRiOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAAFAQsAAAAAAAMBekAAAAAAAQFLAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        check(ByteBuffer.wrap(v16).getInt(4) == 16 && ByteBuffer.wrap(v16).getInt(v16.length - 4) == 0, "Verified historical V16 fixture contains empty recycle footer");
        byte[] v15 = Arrays.copyOf(v16, v16.length - 4);
        ByteBuffer.wrap(v15).putInt(4, 15);
        Files.write(file, v15);
        check(catalog.read().environments.size() == 3 && catalog.read().recycledEnvironments.isEmpty(), "V15 migrates with all environments active");
        check(Arrays.equals(v15, Files.readAllBytes(file)), "Read-only migration never rewrites old bytes");
        catalog.recycle(catalog.read().revision, legacyIdentity);
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17, "First mutation upgrades atomically to V17");

        // Unknown/duplicate recycle IDs, invalid counts and truncation must fail closed.
        byte[] good = Files.readAllBytes(file);
        byte[] noFooter = v16;
        byte[][] corrupt = { footer(noFooter, -1), footer(noFooter, 4),
                footer(noFooter, 1, UUID.randomUUID().toString()),
                footer(noFooter, 2, legacyIdentity.id(), legacyIdentity.id()), Arrays.copyOf(good, good.length - 1) };
        for (byte[] damaged : corrupt) {
            Files.write(file, damaged);
            rejects(catalog::read);
            rejects(() -> catalog.restore(100, legacyIdentity));
            check(Arrays.equals(damaged, Files.readAllBytes(file)), "Malformed catalog is preserved without partial restore");
        }
        Files.write(file, good);
        check(catalog.read().recycledEnvironments.size() == 1, "Restoring original bytes recovers recycle state");
    }

    private static byte[] footer(byte[] emptyFooter, int count, String... identities) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(emptyFooter, 0, emptyFooter.length - 4);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(count);
            for (String identity : identities) out.writeUTF(identity);
        }
        return bytes.toByteArray();
    }
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }
    private static void rejectsUnsupported(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable snapshot");
    }
    private static void rejectsBusy(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException expected) {
            check(expected.getMessage().equals("请先关闭运行中的环境，或取消待启动的环境，再进行回收或恢复"), "Busy UI feedback gives a localized recovery action");
            check(expected.getCause() instanceof EnvironmentLeaseStore.BusyException, "Original busy reason remains available for diagnostics");
            return;
        }
        throw new AssertionError("Expected localized busy rejection");
    }
    private interface Action { void run() throws Exception; }
}
