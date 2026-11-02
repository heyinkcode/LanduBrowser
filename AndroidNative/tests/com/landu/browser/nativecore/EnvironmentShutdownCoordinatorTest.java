package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EnvironmentShutdownCoordinatorTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        successfulAndRepeated();
        timeoutAndLateReplies();
        failures();
        realLease(Path.of(args[0]).resolve("shutdown-" + UUID.randomUUID()));
        System.out.println("{\"suite\":\"environment-async-shutdown\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidDeviceTested\":false,\"chromiumIntegrated\":false}");
    }

    private static void successfulAndRepeated() {
        Fixture fixture = new Fixture();
        List<String> replies = new ArrayList<>();
        fixture.coordinator.request(replies::add);
        fixture.coordinator.request(replies::add);
        check(fixture.operations.terminates == 0 && replies.isEmpty(), "Never terminate or accept before flushing completes");
        check(fixture.operations.blocks == 1 && fixture.operations.flushes == 1, "Duplicate close must share one flush");
        fixture.operations.pending.get(0).complete(null);
        check(fixture.operations.terminates == 1 && replies.size() == 2 && replies.get(0) == null,
                "Successful flush must terminate once and notify both requests");
        check(fixture.scheduler.tasks.get(0).cancelled, "Success must cancel the timeout");
        fixture.operations.pending.get(0).complete(null);
        fixture.scheduler.tasks.get(0).action.run();
        fixture.coordinator.request(replies::add);
        check(fixture.operations.terminates == 1 && fixture.operations.restores == 0, "Late duplicate callbacks cannot repeat termination");
        check(fixture.coordinator.isStopping() && replies.size() == 3, "Terminating process remains marked stopping");
    }

    private static void timeoutAndLateReplies() {
        Fixture fixture = new Fixture();
        List<String> replies = new ArrayList<>();
        fixture.coordinator.request(replies::add);
        fixture.scheduler.tasks.get(0).action.run();
        check(fixture.operations.terminates == 0 && fixture.operations.restores == 1, "Timeout must restore the running environment");
        check(replies.size() == 1 && replies.get(0) != null && !fixture.coordinator.isStopping(), "Timeout must permit retry with an error");
        fixture.coordinator.request(replies::add);
        fixture.operations.pending.get(0).complete(null);
        fixture.scheduler.tasks.get(0).action.run();
        check(fixture.operations.terminates == 0 && replies.size() == 1, "An old reply must not close a newer attempt");
        fixture.operations.pending.get(1).complete(null);
        check(fixture.operations.terminates == 1 && replies.size() == 2 && replies.get(1) == null, "Only the current successful flush can terminate");
    }

    private static void failures() {
        Fixture flushFailure = new Fixture();
        List<String> replies = new ArrayList<>();
        flushFailure.coordinator.request(replies::add);
        flushFailure.operations.pending.get(0).complete("接口断开");
        check(flushFailure.operations.terminates == 0 && flushFailure.operations.restores == 1 && replies.get(0).equals("接口断开"),
                "Flush failure must be reported and never terminate");

        Fixture prepareFailure = new Fixture();
        prepareFailure.operations.failPrepare = true;
        prepareFailure.coordinator.request(replies::add);
        check(prepareFailure.operations.flushes == 0 && !prepareFailure.coordinator.isStopping(), "Failed preparation cannot start a flush");

        Fixture quitFailure = new Fixture();
        quitFailure.operations.failTerminate = true;
        quitFailure.coordinator.request(replies::add);
        quitFailure.operations.pending.get(0).complete(null);
        check(quitFailure.operations.restores == 1 && !quitFailure.coordinator.isStopping(), "Synchronous exit failure must restore the environment");

        Fixture restoreFailure = new Fixture();
        restoreFailure.operations.failRestore = true;
        restoreFailure.coordinator.request(replies::add);
        restoreFailure.scheduler.tasks.get(0).action.run();
        int replyCount = replies.size();
        restoreFailure.coordinator.request(replies::add);
        restoreFailure.operations.pending.get(0).complete(null);
        check(restoreFailure.coordinator.isStopping() && restoreFailure.operations.flushes == 1
                && restoreFailure.operations.terminates == 0 && replies.size() == replyCount + 1,
                "Failed state restoration must remain locked and ignore late success");

        Fixture callbackFailure = new Fixture();
        callbackFailure.coordinator.request(error -> { throw new IllegalStateException("observer disconnected"); });
        callbackFailure.coordinator.request(replies::add);
        int count = replies.size();
        callbackFailure.operations.pending.get(0).complete(null);
        check(replies.size() == count + 1 && callbackFailure.operations.terminates == 1,
                "Disconnected observer must not prevent other replies or release the lock");
    }

    private static void realLease(Path root) throws Exception {
        Files.createDirectories(root);
        EnvironmentIdentity a = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
        EnvironmentIdentity b = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000002");
        EnvironmentLeaseStore store = new EnvironmentLeaseStore(root.resolve("control"));
        store.prepareLaunch(a);
        Scheduler scheduler = new Scheduler();
        List<EnvironmentShutdownCoordinator.Result> pending = new ArrayList<>();
        int[] terminated = {0};
        try (EnvironmentLeaseStore.Session session = store.claimSelected(root.resolve("environments"))) {
            EnvironmentShutdownCoordinator coordinator = new EnvironmentShutdownCoordinator(new EnvironmentShutdownCoordinator.Operations() {
                public void blockBackgroundRestart() throws IOException { session.preventBackgroundRestart(); }
                public void restoreAfterFailure() throws IOException { session.restoreAfterFailedStop(); }
                public void flush(EnvironmentShutdownCoordinator.Result result) { pending.add(result); }
                public void terminate() { terminated[0]++; }
            }, scheduler);
            coordinator.request(error -> {});
            check(store.currentLaunch() == null && store.isBrowserRunning(), "Flushing must block restart while retaining OS ownership");
            rejectsBusy(() -> store.prepareLaunch(b));
            scheduler.tasks.get(0).action.run();
            check(store.currentLaunch() != null && store.currentLaunch().identity.equals(a), "Timeout must restore the original ticket");
            rejectsBusy(() -> store.prepareLaunch(b));
            coordinator.request(error -> {});
            pending.get(0).complete(null);
            check(terminated[0] == 0, "Late callback with a real lease must not close the retry");
            pending.get(1).complete(null);
            check(terminated[0] == 1 && store.isBrowserRunning() && store.currentLaunch() == null,
                    "Exit request cannot release ownership before actual process death");
            rejectsBusy(() -> store.prepareLaunch(b));
        }
        check(!store.isBrowserRunning(), "Only actual session lifetime end releases the OS lock");
        check(store.prepareLaunch(b).identity.equals(b), "Next environment can start only after ownership ends");
    }

    private static void check(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
    private interface Checked { void run() throws IOException; }
    private static void rejectsBusy(Checked action) throws IOException {
        assertions++;
        try { action.run(); } catch (EnvironmentLeaseStore.BusyException expected) { return; }
        throw new AssertionError("A live environment was allowed to switch");
    }
    private static final class Fixture {
        final Operations operations = new Operations();
        final Scheduler scheduler = new Scheduler();
        final EnvironmentShutdownCoordinator coordinator = new EnvironmentShutdownCoordinator(operations, scheduler);
    }
    private static final class Operations implements EnvironmentShutdownCoordinator.Operations {
        int blocks, restores, flushes, terminates;
        boolean failPrepare, failRestore, failTerminate;
        final List<EnvironmentShutdownCoordinator.Result> pending = new ArrayList<>();
        public void blockBackgroundRestart() throws IOException { blocks++; if (failPrepare) throw new IOException("prepare"); }
        public void restoreAfterFailure() throws IOException { restores++; if (failRestore) throw new IOException("restore"); }
        public void flush(EnvironmentShutdownCoordinator.Result result) { flushes++; pending.add(result); }
        public void terminate() { terminates++; if (failTerminate) throw new IllegalStateException("terminate"); }
    }
    private static final class Scheduler implements EnvironmentShutdownCoordinator.Scheduler {
        final List<Task> tasks = new ArrayList<>();
        public EnvironmentShutdownCoordinator.Cancellation schedule(Runnable action, long delay) {
            check(delay == 12_000, "Flush deadline must be earlier than the IPC observer deadline");
            Task task = new Task(action);
            tasks.add(task);
            return () -> task.cancelled = true;
        }
    }
    private static final class Task {
        final Runnable action;
        boolean cancelled;
        Task(Runnable action) { this.action = action; }
    }
}
