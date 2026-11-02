package com.landu.browser.nativecore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Two-phase close; a delayed flush reply can never terminate a later attempt. */
public final class EnvironmentShutdownCoordinator {
    private final Object lock = new Object();
    public interface Result {
        void complete(String error);
        default boolean isPending() { return true; }
    }
    public interface Cancellation { void cancel(); }
    public interface Scheduler { Cancellation schedule(Runnable action, long delayMillis); }
    public interface Operations {
        void blockBackgroundRestart() throws IOException;
        void restoreAfterFailure() throws IOException;
        void flush(Result result);
        void terminate();
    }

    private final Operations operations;
    private final Scheduler scheduler;
    private Attempt current;

    public EnvironmentShutdownCoordinator(Operations operations, Scheduler scheduler) {
        this.operations = operations;
        this.scheduler = scheduler;
    }

    public boolean isStopping() {
        synchronized (lock) {
            return current != null;
        }
    }

    public void request(Result result) {
        synchronized (lock) {
            if (current != null) {
                if (current.finished) result.complete(current.error);
                else current.results.add(result);
                return;
            }
            Attempt attempt = new Attempt();
            attempt.results.add(result);
            current = attempt;
            try {
                operations.blockBackgroundRestart();
                attempt.blocked = true;
                attempt.timeout = scheduler.schedule(
                        () -> flushed(attempt, "保存超时，环境仍保持打开，请稍后重试"), 12_000);
                if (current == attempt && !attempt.finished) operations.flush(new Result() {
                    @Override public void complete(String error) { flushed(attempt, error); }
                    @Override public boolean isPending() {
                        synchronized (lock) {
                            return current == attempt && !attempt.finished;
                        }
                    }
                });
            } catch (IOException | RuntimeException error) {
                failed(attempt, "无法准备环境关闭：" + error.getMessage());
            }
        }
    }

    private void flushed(Attempt attempt, String error) {
        synchronized (lock) {
            if (current != attempt || attempt.finished) return;
            if (error != null) { failed(attempt, error); return; }
            if (attempt.timeout != null) attempt.timeout.cancel();
            try {
                operations.terminate();
                // Keep the attempt and process lock until actual process death.
                complete(attempt, null);
            } catch (RuntimeException failure) {
                failed(attempt, "环境退出失败：" + failure.getMessage());
            }
        }
    }

    private void failed(Attempt attempt, String error) {
        if (current != attempt || attempt.finished) return;
        if (attempt.timeout != null) attempt.timeout.cancel();
        boolean restored = !attempt.blocked;
        if (attempt.blocked) {
            try { operations.restoreAfterFailure(); restored = true; }
            catch (IOException | RuntimeException failure) {
                error += "；关闭状态恢复失败，环境仍被锁定";
            }
        }
        if (restored) current = null;
        complete(attempt, error);
    }

    private void complete(Attempt attempt, String error) {
        attempt.finished = true;
        attempt.error = error;
        List<Result> results = new ArrayList<>(attempt.results);
        attempt.results.clear();
        for (Result result : results) {
            try { result.complete(error); }
            catch (RuntimeException ignored) { /* A disconnected observer cannot affect the lock. */ }
        }
    }

    private static final class Attempt {
        final List<Result> results = new ArrayList<>();
        boolean blocked;
        boolean finished;
        String error;
        Cancellation timeout;
    }
}
