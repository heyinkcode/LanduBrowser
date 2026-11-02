package com.landu.browser.nativecore;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

public final class EnvironmentTransferControllerTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Queue implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();
        boolean reject;
        @Override public void execute(Runnable task) {
            if (reject) throw new RejectedExecutionException("unavailable");
            tasks.add(task);
        }
        void run() { while (!tasks.isEmpty()) tasks.remove().run(); }
        Runnable runOnWorker() throws Exception {
            Runnable task = tasks.remove();
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { task.run(); } catch (Throwable failure) { error.set(failure); } });
            worker.start(); worker.join(5000);
            if (worker.isAlive() || error.get() != null) throw new AssertionError("worker failed", error.get());
            return task;
        }
    }
    private static final class Observer implements EnvironmentTransferController.Observer {
        final List<EnvironmentTransferController.Outcome> results = new ArrayList<>();
        @Override public void completed(EnvironmentTransferController.Outcome outcome) { results.add(outcome); }
    }
    public static void main(String[] args) throws Exception {
        Thread uiThread = Thread.currentThread();
        Queue background = new Queue(), ui = new Queue();
        EnvironmentTransferController jobs = new EnvironmentTransferController(background, ui);
        int[] writes = new int[1];
        String id = jobs.start(EnvironmentTransferController.Kind.EXPORT, () -> {
            check(Thread.currentThread() != uiThread, "I/O task runs on a real worker thread");
            writes[0]++; return "saved";
        });
        Observer old = new Observer(), recreated = new Observer();
        check(jobs.attach(id, old), "attach current activity observer");
        check(!jobs.consume(id), "active I/O cannot be discarded");
        try { jobs.start(EnvironmentTransferController.Kind.IMPORT, () -> null); throw new AssertionError("parallel write"); }
        catch (IllegalStateException expected) { check(writes[0] == 0, "second transfer cannot start while first is active"); }
        Runnable submitted = background.runOnWorker(); submitted.run();
        check(writes[0] == 1, "duplicate execution cannot repeat file mutation");
        jobs.detach(old); jobs.attach(id, recreated); ui.run();
        check(old.results.isEmpty() && recreated.results.size() == 1, "recreated UI receives result and stale queued observer does not");
        check(recreated.results.get(0).error == null && recreated.results.get(0).value.equals("saved"), "completed result survives detach/reattach");
        jobs.detach(recreated);
        Observer later = new Observer(); jobs.attach(id, later); ui.run();
        check(later.results.size() == 1 && writes[0] == 1, "completed transfer is replayed to later UI without repeating I/O");
        check(jobs.consume(id) && jobs.currentId() == null && !jobs.attach(id, old), "acknowledging result removes only that completed transfer");
        for (int failure = 0; failure < 3; failure++) {
            final int kind = failure;
            String failed = jobs.start(EnvironmentTransferController.Kind.PREVIEW, () -> {
                if (kind == 0) throw new IOException("read failed");
                if (kind == 1) throw new IllegalArgumentException("invalid backup");
                throw new OutOfMemoryError("decode allocation");
            });
            Observer observer = new Observer(); jobs.attach(failed, observer); background.runOnWorker(); ui.run();
            check(observer.results.size() == 1 && observer.results.get(0).error != null && observer.results.get(0).value == null,
                    "I/O, validation, and memory failure produce a retained error outcome");
            check(jobs.consume(failed), "failed result can be acknowledged for retry");
        }
        background.reject = true;
        try { jobs.start(EnvironmentTransferController.Kind.EXPORT, () -> null); throw new AssertionError("rejected task"); }
        catch (RejectedExecutionException expected) { check(jobs.currentId() == null, "worker rejection leaves no phantom active job"); }
        background.reject = false;
        String delayed = jobs.start(EnvironmentTransferController.Kind.IMPORT, () -> "merged");
        Observer delayedObserver = new Observer(); jobs.attach(delayed, delayedObserver);
        ui.reject = true; background.runOnWorker();
        check(jobs.currentId().equals(delayed) && delayedObserver.results.isEmpty(), "unavailable UI retains completed result");
        ui.reject = false; jobs.attach(delayed, delayedObserver); ui.run();
        check(delayedObserver.results.size() == 1 && delayedObserver.results.get(0).value.equals("merged"), "later UI delivery succeeds without repeating import");
        jobs.consume(delayed);
        String fresh = jobs.start(EnvironmentTransferController.Kind.EXPORT, () -> "new");
        check(!fresh.equals(id) && !jobs.consume(id) && !jobs.attach(id, old), "old tokens cannot consume or observe a later operation");
        background.runOnWorker(); Observer freshObserver = new Observer(); jobs.attach(fresh, freshObserver);
        jobs.detach(freshObserver); ui.run();
        check(freshObserver.results.isEmpty() && jobs.currentId().equals(fresh), "detaching before delivery preserves outcome for next UI");
        jobs.attach(fresh, freshObserver); ui.run();
        check(freshObserver.results.size() == 1, "final UI reattachment receives pending outcome once");
        String report = "{\"suite\":\"environment-transfer-lifecycle\",\"assertions\":" + assertions
                + ",\"passed\":true,\"realHostWorkerThread\":true,\"androidActivityTested\":false}";
        System.out.println(report);
    }
}
