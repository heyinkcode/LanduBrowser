package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Synthetic accounts only: real AES-GCM, atomic files, catalog locking and child processes. */
public final class AccountCredentialsTest {
    private static int assertions;
    private static final String OTP = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static SecretKey fixtureKey() { return new SecretKeySpec(new byte[32], "AES"); }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[0].equals("child")) { child(args); return; }
        Path root = Path.of(args[0]).resolve("account-" + UUID.randomUUID());
        Files.createDirectories(root);
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentDefinition a = EnvironmentDefinition.create("Account fixture A", "", "");
        EnvironmentDefinition b = EnvironmentDefinition.create("Account fixture B", "", "");
        EnvironmentCatalog.Snapshot catalogSnapshot = catalog.save(0, a);
        catalogSnapshot = catalog.save(catalogSnapshot.revision, b);
        a = catalogSnapshot.find(a.identity);
        b = catalogSnapshot.find(b.identity);
        final EnvironmentDefinition owner = a;
        final EnvironmentDefinition other = b;
        AtomicInteger keyCalls = new AtomicInteger();
        SecretKey key = fixtureKey();
        AccountCredentialStore store = new AccountCredentialStore(root, create -> { keyCalls.incrementAndGet(); return key; });
        check(store.read(a).revision == 0 && store.read(a).credentials.isEmpty(), "Missing account is empty, not an error");
        check(keyCalls.get() == 0, "Reading an absent account never creates/uses a key");
        String username = "owned-account-" + UUID.randomUUID();
        String password = "owned-password-" + UUID.randomUUID();
        AccountCredentials value = new AccountCredentials(username, password, OTP);
        AccountCredentialStore.Snapshot one = store.save(a, 0, value);
        check(one.revision == 1, "First save advances account revision");
        Path file = root.resolve("account-credentials").resolve(a.identity.id() + ".bin");
        byte[] original = Files.readAllBytes(file);
        String encoded = new String(original, StandardCharsets.ISO_8859_1);
        check(!encoded.contains(username) && !encoded.contains(password) && !encoded.contains(OTP), "No plaintext account data on disk");
        AccountCredentialStore reopened = new AccountCredentialStore(root, create -> key);
        AccountCredentialStore.Snapshot read = reopened.read(a);
        check(read.credentials.username.equals(username) && read.credentials.password.equals(password)
                && read.credentials.totpSecret.equals(OTP), "All fields survive an independent store restart");
        check(!read.toString().contains(username) && !value.toString().contains(password), "Debug strings are redacted");
        check(reopened.read(b).credentials.isEmpty(), "Another environment does not inherit an account");
        Path otherFile = file.resolveSibling(b.identity.id() + ".bin");
        Files.write(otherFile, original);
        rejects(() -> reopened.read(other));
        check(Arrays.equals(original, Files.readAllBytes(otherFile)), "Cross-owner ciphertext is rejected without rewrite");
        Files.delete(otherFile);
        EnvironmentDefinition changedCreation = new EnvironmentDefinition(a.identity, a.name, a.startPage, a.notes,
                a.createdAt + 1, Math.max(a.updatedAt, a.createdAt + 1));
        rejects(() -> reopened.read(changedCreation));
        byte[] wrong = new byte[32]; wrong[0] = 1;
        rejects(() -> new AccountCredentialStore(root, create -> new SecretKeySpec(wrong, "AES")).read(owner));
        for (int position : new int[] {4, 12, original.length - 1}) {
            byte[] altered = original.clone(); altered[position] ^= 1;
            Files.write(file, altered);
            rejects(() -> reopened.read(owner));
            rejects(() -> reopened.save(owner, 1, value));
            check(Arrays.equals(altered, Files.readAllBytes(file)), "Malformed records cannot be overwritten by a normal save");
        }
        for (byte[] malformed : new byte[][] {new byte[] {1}, Arrays.copyOf(original, original.length - 1)}) {
            Files.write(file, malformed);
            rejects(() -> reopened.read(owner));
            check(Arrays.equals(malformed, Files.readAllBytes(file)), "Truncated record remains available for recovery");
        }
        Files.write(file, original);
        read = reopened.save(a, 1, value);
        byte[] second = Files.readAllBytes(file);
        check(read.revision == 2 && !Arrays.equals(Arrays.copyOfRange(original, 12, 24), Arrays.copyOfRange(second, 12, 24)), "Every save uses a new GCM IV");
        rejects(() -> reopened.save(owner, 1, new AccountCredentials("stale", "stale", "")));
        check(Arrays.equals(second, Files.readAllBytes(file)), "Stale editor cannot replace a newer account");
        AccountCredentialStore brokenWriter = new AccountCredentialStore(root, create -> {
            if (create) throw new GeneralSecurityException("Synthetic key failure");
            return key;
        });
        rejects(() -> brokenWriter.save(owner, 2, AccountCredentials.empty()));
        check(Arrays.equals(second, Files.readAllBytes(file)), "Encryption failure preserves the previous committed file");
        try (java.util.stream.Stream<Path> entries = Files.list(file.getParent())) {
            check(entries.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "Failed save leaves no plaintext or partial temporary record");
        }

        read = reopened.save(a, 2, AccountCredentials.empty());
        check(read.revision == 3 && reopened.read(a).credentials.isEmpty(), "Clearing is an encrypted empty record with a new revision");
        rejects(() -> reopened.save(owner, 0, value));
        reopened.save(a, 3, value);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        Process first = new ProcessBuilder(javaExecutable, "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classpath,
                AccountCredentialsTest.class.getName(), "child", root.toString(), a.identity.id(), "4", "first")
                .redirectErrorStream(true).redirectOutput(root.resolve("first-child.log").toFile()).start();
        Process next = new ProcessBuilder(javaExecutable, "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classpath,
                AccountCredentialsTest.class.getName(), "child", root.toString(), a.identity.id(), "4", "second")
                .redirectErrorStream(true).redirectOutput(root.resolve("second-child.log").toFile()).start();
        check(first.waitFor(15, TimeUnit.SECONDS) && next.waitFor(15, TimeUnit.SECONDS), "Concurrent account writers terminate");
        check((first.exitValue() == 0 && next.exitValue() == 3) || (first.exitValue() == 3 && next.exitValue() == 0), "Exactly one cross-process writer commits the expected revision");
        check(reopened.read(a).revision == 5, "Cross-process revision remains consistent");

        byte[] catalogBefore = Files.readAllBytes(root.resolve("environments.bin"));
        String currentUser = catalog.accessActive(a.identity, a.createdAt, current -> reopened.read(current).credentials.username);
        check(currentUser.startsWith("owned-writer-"), "Owner-validated catalog access reads the saved account");
        check(Arrays.equals(catalogBefore, Files.readAllBytes(root.resolve("environments.bin"))), "Account save never rewrites environment configuration");
        byte[] backup = catalog.exportConfiguration();
        String portable = new String(backup, StandardCharsets.ISO_8859_1);
        check(!portable.contains("owned-writer-") && !portable.contains(password) && !portable.contains(OTP), "Configuration backup contains no account fields");
        rejects(() -> catalog.accessActive(owner.identity, owner.createdAt + 1, current -> reopened.read(current)));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread accountOperation = new Thread(() -> {
            try { catalog.accessActive(owner.identity, owner.createdAt, current -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test account release timed out"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                return reopened.read(current);
            }); } catch (Throwable failure) { error.set(failure); }
        });
        accountOperation.start();
        check(entered.await(5, TimeUnit.SECONDS), "Owner-validated account operation holds the catalog lock");
        long catalogRevision = catalog.read().revision;
        Thread archive = new Thread(() -> {
            try { catalog.recycle(catalogRevision, owner.identity); }
            catch (Throwable failure) { error.set(failure); }
        });
        archive.start();
        Thread.sleep(100);
        check(catalog.read().environments.size() == 2 && archive.isAlive(), "Recycle waits for the active account operation");
        release.countDown();
        accountOperation.join(5000); archive.join(5000);
        check(!accountOperation.isAlive() && !archive.isAlive() && error.get() == null, "Account/recycle lock order makes progress");
        rejects(() -> catalog.accessActive(owner.identity, owner.createdAt, current -> reopened.read(current)));
        EnvironmentCatalog.Snapshot restored = catalog.restore(catalog.read().revision, a.identity);
        check(catalog.accessActive(a.identity, a.createdAt, current -> reopened.read(current).revision) == 5
                && restored.find(a.identity).identity.equals(a.identity), "Restored environment keeps its original encrypted account");

        int symlinkChecks = 0;
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Path foreign = root.resolve("owned-foreign-marker"); Files.writeString(foreign, "unchanged");
            Path retained = root.resolve("retained-account.bin");
            Files.move(file, retained);
            Files.createSymbolicLink(file, foreign);
            rejects(() -> reopened.read(owner));
            rejects(() -> reopened.save(owner, 5, value));
            check(Files.readString(foreign).equals("unchanged"), "Account symlink cannot write outside its owner record");
            Files.delete(file); Files.move(retained, file); symlinkChecks++;
            Path lock = file.resolveSibling(a.identity.id() + ".lock");
            Path savedLock = root.resolve("retained-account.lock"); Files.move(lock, savedLock);
            Files.createSymbolicLink(lock, foreign);
            rejects(() -> reopened.save(owner, 5, value));
            Files.delete(lock); Files.move(savedLock, lock); symlinkChecks++;
            Path accountRoot = file.getParent();
            Path savedRoot = root.resolve("retained-account-directory"); Files.move(accountRoot, savedRoot);
            Path foreignRoot = root.resolve("owned-foreign-directory"); Files.createDirectories(foreignRoot);
            Files.createSymbolicLink(accountRoot, foreignRoot);
            rejects(() -> new AccountCredentialStore(root, create -> key));
            Files.delete(accountRoot); Files.move(savedRoot, accountRoot); symlinkChecks++;
        }
        rejectsValue(() -> new AccountCredentials(null, "", ""));
        rejectsValue(() -> new AccountCredentials("a".repeat(201), "", ""));
        rejectsValue(() -> new AccountCredentials("", "p".repeat(501), ""));
        rejectsValue(() -> new AccountCredentials("", "", "not-a-2fa-key"));
        rejectsValue(() -> new AccountCredentials("", "line\nbreak", ""));
        check(new AccountCredentials("用户@example.test", "密 码: ✓", "").password.equals("密 码: ✓"), "Unicode passwords are preserved exactly");

        String report = "{\"suite\":\"encrypted-environment-account\",\"assertions\":" + assertions
                + ",\"passed\":true,\"realChildProcessWriters\":2,\"symlinkChecks\":" + symlinkChecks
                + ",\"androidKeystoreTested\":false,\"androidDeviceTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void child(String[] args) throws Exception {
        Path root = Path.of(args[1]);
        EnvironmentDefinition environment = new EnvironmentCatalog(root).read().find(EnvironmentIdentity.parse(args[2]));
        AccountCredentialStore store = new AccountCredentialStore(root, create -> fixtureKey());
        try { store.save(environment, Long.parseLong(args[3]), new AccountCredentials("owned-writer-" + args[4], "synthetic", OTP)); }
        catch (IOException failure) {
            if (failure.getMessage().equals("账号信息已更新，请关闭并重新打开账号窗口")) { System.exit(3); return; }
            throw failure;
        }
    }
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected account I/O rejection");
    }
    private static void rejectsValue(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid account field rejection");
    }
    private interface Action { void run() throws Exception; }
}
