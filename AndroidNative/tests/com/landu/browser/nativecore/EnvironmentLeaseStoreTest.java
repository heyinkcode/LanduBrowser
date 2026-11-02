package com.landu.browser.nativecore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class EnvironmentLeaseStoreTest {
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse(
            "10af0101-2000-4000-8000-000000000001");
    private static final EnvironmentIdentity B = EnvironmentIdentity.parse(
            "10af0101-2000-4000-8000-000000000002");
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("probe")) {
            EnvironmentLeaseStore probe = new EnvironmentLeaseStore(Path.of(args[1]));
            if (!probe.isBrowserRunning()) throw new AssertionError("Another process lost its OS lock");
            System.out.println("LOCK_HELD");
            return;
        }
        if (args.length == 2 && args[0].equals("hold")) {
            Path root = Path.of(args[1]);
            EnvironmentLeaseStore child = new EnvironmentLeaseStore(root.resolve("control"));
            try (EnvironmentLeaseStore.Session session = child.claimSelected(root.resolve("environments"))) {
                System.out.println("READY " + session.ticket().identity.id());
                System.out.flush();
                Thread.sleep(120_000);
            }
            return;
        }
        Path root = Path.of(args[0]).resolve("native-isolation-" + UUID.randomUUID());
        Files.createDirectories(root);
        identityValidation();
        lifecycle(root.resolve("lifecycle"));
        missingAndCorrupt(root.resolve("corrupt"));
        crossProcess(root.resolve("cross-process"));
        String report = "{\"suite\":\"native-environment-isolation-core\",\"assertions\":"
                + assertions + ",\"passed\":true,\"androidDeviceTested\":false,"
                + "\"chromiumIntegrated\":false}";
        Files.writeString(root.resolve("result.json"), report, StandardCharsets.UTF_8);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void identityValidation() throws Exception {
        for (String invalid : new String[] {null, "", "../outside", "/tmp/other", "C:\\outside",
                "1-1-1-1-1", A.id().toUpperCase(), A.id() + "\n", A.id() + "/child"}) {
            rejects(IllegalArgumentException.class, () -> EnvironmentIdentity.parse(invalid));
        }
        check(A.equals(EnvironmentIdentity.parse(A.id())), "Identity must be stable");
        check(!A.directoryName().equals(B.directoryName()), "Different IDs must use different directories");
    }

    private static void lifecycle(Path root) throws Exception {
        Path environments = root.resolve("environments");
        EnvironmentLeaseStore store = new EnvironmentLeaseStore(root.resolve("control"));
        EnvironmentLeaseStore other = new EnvironmentLeaseStore(root.resolve("control"));
        check(store.observe().status == EnvironmentLeaseStore.Status.IDLE, "A new manager must show no running environment");
        EnvironmentLeaseStore.Ticket first = store.prepareLaunch(A);
        EnvironmentLeaseStore.Observation pending = other.observe();
        check(pending.status == EnvironmentLeaseStore.Status.PENDING && !pending.processRunning && pending.belongsTo(A),
                "A pending selection must not be labeled as a running process");
        EnvironmentLeaseStore.LaunchRequest retry = other.requestLaunch(A);
        check(!retry.resumeExisting && retry.ticket.nonce.equals(first.nonce), "Pending retries must preserve their original ticket");
        rejects(EnvironmentLeaseStore.BusyException.class, () -> other.requestLaunch(B));
        check(first.nonce.equals(other.prepareLaunch(A).nonce), "Repeated request must be idempotent");
        rejects(EnvironmentLeaseStore.BusyException.class, () -> other.prepareLaunch(B));
        EnvironmentLeaseStore.Ticket restored = other.pendingLaunch();
        check(restored != null && restored.nonce.equals(first.nonce), "Pending launch must survive manager restart");
        other.cancelPending(restored);
        check(store.observe().status == EnvironmentLeaseStore.Status.IDLE, "Cancelling pending must clear the status");
        check(store.pendingLaunch() == null, "Cancelled launch must not remain pending");
        rejects(IOException.class, () -> store.claimSelected(environments));
        EnvironmentLeaseStore.Ticket second = store.prepareLaunch(A);
        check(!first.nonce.equals(second.nonce), "New launch must use a new ticket");
        rejects(IOException.class, () -> store.cancelPending(first));

        Path firstDirectory;
        EnvironmentLeaseStore.Session session = store.claimSelected(environments);
        try {
            firstDirectory = session.directory();
            check(other.isBrowserRunning(), "Separate store must see live process ownership");
            EnvironmentLeaseStore.Observation running = other.observe();
            check(running.status == EnvironmentLeaseStore.Status.RUNNING && running.processRunning && running.belongsTo(A),
                    "A live session must be shown with its actual identity");
            EnvironmentLeaseStore.LaunchRequest resume = other.requestLaunch(A);
            check(resume.resumeExisting && resume.ticket.nonce.equals(second.nonce), "Repeated launch must resume the same browser");
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.requestLaunch(B));
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.prepareLaunch(B));
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.claimSelected(environments));
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.cancelPending(second));
            probeLock(root.resolve("control"));
            session.verifyLaunch(A.id(), second.nonce);
            rejects(IOException.class, () -> session.verifyLaunch(B.id(), second.nonce));
            rejects(IOException.class, () -> session.verifyLaunch(A.id(), first.nonce));
            check(other.currentLaunch().nonce.equals(second.nonce), "Manager must read current ticket without taking lock");
            session.preventBackgroundRestart();
            EnvironmentLeaseStore.Observation stopping = other.observe();
            check(stopping.status == EnvironmentLeaseStore.Status.STOPPING && stopping.processRunning && stopping.belongsTo(A),
                    "A closed restart record cannot make a still-live process look stopped");
            check(running.status == EnvironmentLeaseStore.Status.RUNNING, "Status snapshots must remain immutable");
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.requestLaunch(A));
            check(other.currentLaunch() == null, "Closing must prevent background restart");
            check(other.isBrowserRunning(), "Closing record must not release the live browser lock");
            rejects(EnvironmentLeaseStore.BusyException.class, () -> other.prepareLaunch(B));
            probeLock(root.resolve("control"));
            session.restoreAfterFailedStop();
            check(other.observe().status == EnvironmentLeaseStore.Status.RUNNING, "A failed close must restore the running status");
            check(other.currentLaunch().nonce.equals(second.nonce), "Failed stop must restore current ticket");
            for (String component : new String[] {"profile", "cache", "files", "textures"}) {
                check(Files.isDirectory(firstDirectory.resolve(component)), "Missing environment component");
                Files.writeString(firstDirectory.resolve(component).resolve("retained.txt"), "environment-A");
            }
        } finally {
            session.close();
        }
        session.close();
        check(!other.isBrowserRunning(), "Closing the session must release its lock");
        check(other.observe().status == EnvironmentLeaseStore.Status.IDLE && other.observe().ticket == null,
                "A stopped session must no longer be shown as active");
        rejects(IllegalStateException.class, session::directory);
        rejects(IOException.class, () -> session.verifyLaunch(A.id(), second.nonce));
        store.prepareLaunch(B);
        try (EnvironmentLeaseStore.Session next = other.claimSelected(environments)) {
            check(!firstDirectory.equals(next.directory()), "B must not use A's directory");
            for (String component : new String[] {"profile", "cache", "files", "textures"}) {
                check(!Files.exists(next.directory().resolve(component).resolve("retained.txt")),
                        "B must not inherit A's files");
            }
        }
        store.prepareLaunch(A);
        try (EnvironmentLeaseStore.Session reopened = store.claimSelected(environments)) {
            check(firstDirectory.equals(reopened.directory()), "A's directory must remain stable");
            for (String component : new String[] {"profile", "cache", "files", "textures"}) {
                check(Files.readString(reopened.directory().resolve(component).resolve("retained.txt"))
                        .equals("environment-A"), "Reopening A must retain its data");
            }
        }
    }

    private static void missingAndCorrupt(Path root) throws Exception {
        Path control = root.resolve("control");
        EnvironmentLeaseStore store = new EnvironmentLeaseStore(control);
        rejects(IOException.class, () -> store.claimSelected(root.resolve("environments")));
        check(!Files.exists(root.resolve("environments")), "Missing selection must not create a default profile");
        store.prepareLaunch(A);
        Path file = control.resolve("active-environment.bin");
        byte[] valid = Files.readAllBytes(file);
        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        byte[] badState = valid.clone();
        badState[7] = 99;
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        for (byte[] corrupt : new byte[][] {new byte[] {1}, badMagic, badState, extra, new byte[257]}) {
            Files.write(file, corrupt);
            rejects(IOException.class, () -> store.prepareLaunch(B));
            rejects(IOException.class, () -> store.claimSelected(root.resolve("environments")));
            rejects(IOException.class, store::observe);
            check(Arrays.equals(corrupt, Files.readAllBytes(file)), "Corrupt selection must not be overwritten");
        }
        Files.write(file, valid);
        try (EnvironmentLeaseStore.Session recovered = store.claimSelected(root.resolve("environments"))) {
            check(recovered.ticket().identity.equals(A), "Invalid records must not leak a lock");
        }
    }

    private static void crossProcess(Path root) throws Exception {
        EnvironmentLeaseStore store = new EnvironmentLeaseStore(root.resolve("control"));
        store.prepareLaunch(A);
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable,
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", System.getProperty("java.class.path"),
                EnvironmentLeaseStoreTest.class.getName(), "hold", root.toString())
                .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!output.ready() && child.isAlive() && System.nanoTime() < deadline) Thread.sleep(25);
            check(output.ready(), "Child process did not acquire the native session");
            String ready = output.readLine();
            check(("READY " + A.id()).equals(ready), "Unexpected child result: " + ready);
            check(store.isBrowserRunning(), "Cross-process lock was not visible");
            check(store.observe().status == EnvironmentLeaseStore.Status.RUNNING && store.observe().belongsTo(A),
                    "Manager observation must work across real processes");
            check(store.requestLaunch(A).resumeExisting, "A different manager process must resume an existing session");
            rejects(EnvironmentLeaseStore.BusyException.class, () -> store.prepareLaunch(B));
        } finally {
            child.destroyForcibly();
            if (!child.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Test child did not exit");
        }
        long releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.isBrowserRunning() && System.nanoTime() < releaseDeadline) Thread.sleep(25);
        check(!store.isBrowserRunning(), "OS must release the lock after process termination");
        byte[] crashedRecord = Files.readAllBytes(root.resolve("control/active-environment.bin"));
        check(store.observe().status == EnvironmentLeaseStore.Status.IDLE && !store.observe().processRunning,
                "A stale RUNNING record after process death must not keep the UI running");
        check(Arrays.equals(crashedRecord, Files.readAllBytes(root.resolve("control/active-environment.bin"))),
                "Status observation must not rewrite crash evidence");
        store.prepareLaunch(B);
        try (EnvironmentLeaseStore.Session recovered = store.claimSelected(root.resolve("environments"))) {
            check(recovered.ticket().identity.equals(B), "Crash recovery must allow a new environment");
        }
    }

    private static void probeLock(Path control) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(executable,
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp", System.getProperty("java.class.path"),
                EnvironmentLeaseStoreTest.class.getName(), "probe", control.toString())
                .redirectErrorStream(true).start();
        try {
            check(child.waitFor(15, TimeUnit.SECONDS), "External lock probe timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            check(child.exitValue() == 0 && output.equals("LOCK_HELD"),
                    "Failed overlapping requests released the process lock: " + output);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> type, CheckedAction action) throws Exception {
        assertions++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
