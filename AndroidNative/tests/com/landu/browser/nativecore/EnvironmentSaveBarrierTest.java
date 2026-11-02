package com.landu.browser.nativecore;

import java.util.ArrayList;
import java.util.List;

public final class EnvironmentSaveBarrierTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Result implements EnvironmentShutdownCoordinator.Result {
        final List<String> replies = new ArrayList<>();
        boolean active = true;
        @Override public void complete(String error) { replies.add(error); }
        @Override public boolean isPending() { return active; }
    }
    private static final class Window {
        int calls;
        final List<EnvironmentShutdownCoordinator.Result> pending = new ArrayList<>();
        void save(EnvironmentShutdownCoordinator.Result result) { calls++; pending.add(result); }
        void done(int i, String error) { pending.get(i).complete(error); }
        @Override public boolean equals(Object other) { return other instanceof Window; }
        @Override public int hashCode() { return 1; }
    }

    public static void main(String[] args) {
        EnvironmentSaveBarrier empty = new EnvironmentSaveBarrier();
        Result emptyResult = new Result(); empty.saveAll(emptyResult);
        check(emptyResult.replies.size() == 1 && emptyResult.replies.get(0) == null, "empty window set completes once");

        EnvironmentSaveBarrier barrier = new EnvironmentSaveBarrier();
        Window a = new Window(), b = new Window();
        barrier.register(a, a::save); barrier.register(b, b::save);
        Result all = new Result(); barrier.saveAll(all);
        check(a.calls == 1 && b.calls == 1, "different equal-valued owners remain separate");
        check(all.replies.isEmpty(), "waiting for windows");
        a.done(0, null); a.done(0, "duplicate");
        check(all.replies.isEmpty(), "duplicate callback cannot replace another window");
        b.done(0, null); b.done(0, null);
        check(all.replies.size() == 1 && all.replies.get(0) == null, "all window replies required");

        Result failed = new Result(); barrier.saveAll(failed);
        a.done(1, "failed"); b.done(1, null);
        check(failed.replies.size() == 1 && "failed".equals(failed.replies.get(0)), "failure preserved without success afterwards");

        Result changed = new Result(); barrier.saveAll(changed);
        barrier.unregister(b); a.done(2, null); b.done(2, null);
        check(changed.replies.size() == 1 && changed.replies.get(0) != null, "removing a window invalidates pending save");
        Result current = new Result(); barrier.saveAll(current);
        check(b.calls == 3, "unregistered window is not called");
        a.done(3, null);
        check(current.replies.size() == 1 && current.replies.get(0) == null, "remaining live window saves normally");

        Result replaced = new Result(); barrier.saveAll(replaced);
        barrier.register(a, a::save); a.done(4, null);
        check(replaced.replies.size() == 1 && replaced.replies.get(0) != null, "replacing a window saver invalidates snapshot");

        Result expired = new Result(); barrier.saveAll(expired); expired.active = false; a.done(5, null);
        check(expired.replies.isEmpty(), "late save cannot advance an expired attempt");
        Result retry = new Result(); barrier.saveAll(retry); a.done(5, "very late");
        check(retry.replies.isEmpty(), "old callback cannot complete retry");
        a.done(6, null); check(retry.replies.size() == 1 && retry.replies.get(0) == null, "retry uses its own callback");
        long savedRevision = barrier.revision();
        check(barrier.unchanged(savedRevision), "window revision remains valid for the native save phase");
        barrier.register(b, b::save);
        check(!barrier.unchanged(savedRevision), "new window invalidates a completed first phase too");
        barrier.unregister(b);
        check(!barrier.unchanged(savedRevision), "removing it cannot make an old first phase current again");

        EnvironmentSaveBarrier throwsBeforeReply = new EnvironmentSaveBarrier();
        throwsBeforeReply.register(new Object(), result -> { throw new IllegalStateException("disk"); });
        Result exception = new Result(); throwsBeforeReply.saveAll(exception);
        check(exception.replies.size() == 1 && exception.replies.get(0) != null, "save exception is a failure");
        EnvironmentSaveBarrier synchronous = new EnvironmentSaveBarrier();
        synchronous.register(new Object(), result -> { result.complete(null); result.complete("duplicate"); throw new IllegalStateException(); });
        Result one = new Result(); synchronous.saveAll(one);
        check(one.replies.size() == 1 && one.replies.get(0) == null, "callback and exception cannot report twice");
        Result preExpired = new Result(); preExpired.active = false;
        int count = a.calls; barrier.saveAll(preExpired);
        check(a.calls == count && preExpired.replies.isEmpty(), "expired operation starts no new work");

        final EnvironmentShutdownCoordinator.Result[] held = new EnvironmentShutdownCoordinator.Result[1];
        final Runnable[] timeout = new Runnable[1];
        int[] terminate = {0}, phaseTwo = {0};
        EnvironmentShutdownCoordinator coordinator = new EnvironmentShutdownCoordinator(new EnvironmentShutdownCoordinator.Operations() {
            public void blockBackgroundRestart() {}
            public void restoreAfterFailure() {}
            public void flush(EnvironmentShutdownCoordinator.Result result) { held[0] = result; }
            public void terminate() { terminate[0]++; }
        }, (run, delay) -> { timeout[0] = run; return () -> {}; });
        coordinator.request(error -> {});
        check(held[0].isPending(), "coordinator marks live flush pending");
        EnvironmentSaveBarrier staged = new EnvironmentSaveBarrier();
        Window late = new Window(); staged.register(late, late::save);
        EnvironmentShutdownCoordinator.Result firstAttempt = held[0];
        staged.saveAll(new EnvironmentShutdownCoordinator.Result() {
            public void complete(String error) { phaseTwo[0]++; firstAttempt.complete(error); }
            public boolean isPending() { return firstAttempt.isPending(); }
        });
        timeout[0].run();
        check(!firstAttempt.isPending(), "timeout expires exact attempt");
        coordinator.request(error -> {});
        late.done(0, null);
        check(phaseTwo[0] == 0 && terminate[0] == 0, "late window reply does not start native flush or terminate retry");
        held[0].complete(null);
        check(terminate[0] == 1 && !held[0].isPending(), "current attempt completes once and stops being pending");
        System.out.println("{\"suite\":\"environment-window-save-barrier\",\"assertions\":" + assertions
                + ",\"passed\":true,\"deviceTested\":false}");
    }
}
