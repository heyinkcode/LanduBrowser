package com.landu.browser.nativecore;

import java.util.ArrayList;
import java.util.List;

public final class EnvironmentLegacySaveBarrierTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Store implements EnvironmentLegacySaveBarrier.Store {
        final String name;
        final List<String> log;
        final List<Runnable> replies = new ArrayList<>();
        final List<EnvironmentLegacySaveBarrier.Result> writes = new ArrayList<>();
        Runnable onPause = () -> {};
        Runnable onSave = () -> {};
        boolean saved = true;
        boolean unchanged = true;
        boolean writeSynchronously = true;
        boolean synchronous;
        boolean paused;
        int saves;
        int releases;
        Store(String name, List<String> log) { this.name = name; this.log = log; }
        @Override public void pause() { paused = true; log.add(name + ":pause"); onPause.run(); }
        @Override public void drain(Runnable ready) {
            log.add(name + ":drain"); replies.add(ready);
            if (synchronous) ready.run();
        }
        @Override public EnvironmentLegacySaveBarrier.Snapshot capture() {
            check(paused, "snapshot only while paused");
            saves++; log.add(name + ":capture"); onSave.run();
            return new EnvironmentLegacySaveBarrier.Snapshot() {
                @Override public void write(EnvironmentLegacySaveBarrier.Result result) {
                    log.add(name + ":write"); writes.add(result);
                    if (writeSynchronously) result.complete(saved);
                }
                @Override public boolean unchanged() { return unchanged; }
                @Override public void release(boolean success) {
                    releases++; log.add(name + ":release:" + success);
                }
            };
        }
        @Override public void resume(boolean success) {
            paused = false; log.add(name + ":resume:" + success);
        }
        @Override public boolean equals(Object value) { return value instanceof Store; }
        @Override public int hashCode() { return 1; }
    }
    public static void main(String[] args) {
        List<String> log = new ArrayList<>();
        int[] results = new int[2];
        Runnable done = () -> results[0]++;
        Runnable fail = () -> results[1]++;
        EnvironmentLegacySaveBarrier barrier = new EnvironmentLegacySaveBarrier();
        barrier.saveAll(done, fail);
        check(results[0] == 1 && results[1] == 0, "empty registry");
        Store a = new Store("a", log), b = new Store("b", log);
        barrier.register(a, a); barrier.register(b, b);
        barrier.saveAll(done, fail); barrier.saveAll(done, fail);
        check(a.paused && b.paused && a.replies.size() == 1 && b.replies.size() == 1,
                "all windows paused; simultaneous closes share a drain");
        check(log.indexOf("a:pause") < log.indexOf("b:drain")
                && log.indexOf("b:pause") < log.indexOf("a:drain"), "pause all before draining any");
        a.replies.get(0).run(); a.replies.get(0).run();
        check(a.saves == 0 && b.saves == 0, "one queue and duplicate reply cannot advance batch");
        b.replies.get(0).run();
        check(a.saves == 1 && b.saves == 1 && results[0] == 3 && results[1] == 0,
                "both queues drained before either snapshot, all observers complete once");
        check(log.indexOf("a:capture") < log.indexOf("b:write")
                && log.indexOf("b:capture") < log.indexOf("a:write"), "capture every owner before starting any I/O");
        check(!a.paused && !b.paused, "success unpauses all stores");
        a.replies.get(0).run(); b.replies.get(0).run();
        check(results[0] == 3, "late duplicate ignored");

        barrier.saveAll(done, fail); barrier.unregister(b);
        a.replies.get(1).run(); b.replies.get(1).run();
        check(results[1] == 1 && a.saves == 1 && b.saves == 1, "destroyed window prevents snapshots");
        check(!a.paused && !b.paused, "destroy invalidation resumes even removed participant");
        a.saved = false;
        barrier.saveAll(done, fail); a.replies.get(2).run();
        check(results[1] == 2 && !a.paused, "write failure keeps all callers on failure path");
        a.saved = true;
        barrier.saveAll(done, fail); a.replies.get(1).run();
        check(a.saves == 2, "old marker cannot complete a later attempt");
        a.replies.get(3).run();
        check(results[0] == 4 && results[1] == 2, "fresh snapshot retry after failed I/O");

        a.onPause = () -> { throw new IllegalStateException("cannot pause"); };
        barrier.saveAll(done, fail);
        check(!a.paused && results[1] == 3, "partial pause failure releases gate");
        a.onPause = () -> {};
        a.onSave = () -> { throw new OutOfMemoryError("snapshot allocation"); };
        barrier.saveAll(done, fail); a.replies.get(4).run();
        check(results[1] == 4 && !a.paused, "allocation failure fails close and releases gate");
        a.onSave = () -> {};
        a.synchronous = true;
        barrier.saveAll(done, fail);
        check(results[0] == 5, "synchronous marker works");
        a.onSave = () -> barrier.saveAll(done, fail);
        barrier.saveAll(done, fail);
        check(results[0] == 7, "reentrant observer joins current batch");
        a.onSave = () -> {};
        a.synchronous = false;
        barrier.saveAll(done, fail);
        Store c = new Store("c", log); barrier.register(c, c);
        a.replies.get(a.replies.size() - 1).run();
        check(results[1] == 5, "new store invalidates in-flight close");
        try { barrier.register(new Object(), c); throw new AssertionError("duplicate store"); }
        catch (IllegalArgumentException expected) { check(true, "duplicate participant rejected"); }
        barrier.unregister(a); barrier.unregister(c);
        barrier.saveAll(() -> { throw new IllegalStateException("observer gone"); }, fail);
        barrier.saveAll(done, fail);
        check(results[0] == 8, "disconnected observer cannot strand subsequent saves");

        Store x = new Store("x", log), y = new Store("y", log);
        x.synchronous = true; y.synchronous = true;
        x.writeSynchronously = false; y.writeSynchronously = false;
        barrier.register(x, x); barrier.register(y, y);
        barrier.saveAll(done, fail);
        check(x.paused && y.paused && x.writes.size() == 1 && y.writes.size() == 1,
                "asynchronous I/O keeps every queue paused");
        x.writes.get(0).complete(false); x.writes.get(0).complete(true);
        check(x.releases == 0 && y.releases == 0 && x.paused && y.paused,
                "failure and duplicates cannot release the other live writer's buffer");
        y.writes.get(0).complete(true);
        check(results[1] == 6 && x.releases == 1 && y.releases == 1 && !x.paused && !y.paused,
                "release failure batch only after all I/O stops");
        barrier.saveAll(done, fail); x.unchanged = false;
        x.writes.get(1).complete(true); y.writes.get(1).complete(true);
        check(results[1] == 7, "mutation while writing invalidates close");
        x.unchanged = true; barrier.saveAll(done, fail); barrier.unregister(y);
        x.writes.get(2).complete(true);
        check(x.releases == 2 && y.releases == 2, "destroyed owner still retains snapshot until its I/O stops");
        y.writes.get(2).complete(true);
        check(results[1] == 8 && x.releases == 3 && y.releases == 3, "lifecycle invalidation after safe buffer release");
        barrier.saveAll(done, fail); x.writes.get(0).complete(false);
        check(x.releases == 3, "old write callback cannot release new snapshot");
        x.writes.get(3).complete(true);
        check(results[0] == 9 && x.releases == 4, "retry after mutation uses new asynchronous write");
        x.onSave = () -> x.unchanged = false;
        int writes = x.writes.size();
        barrier.saveAll(done, fail);
        check(results[1] == 9 && x.writes.size() == writes && x.releases == 5,
                "capture-time mutation releases resources before any I/O starts");
        System.out.println("Legacy save barrier tests passed: " + assertions + " assertions");
    }
}
