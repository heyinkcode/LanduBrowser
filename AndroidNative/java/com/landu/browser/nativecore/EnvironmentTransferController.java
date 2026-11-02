package com.landu.browser.nativecore;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** One process-local transfer, retained across UI recreation without retaining an Activity. */
public final class EnvironmentTransferController {
    private final Object lock = new Object();
    public enum Kind { EXPORT, PREVIEW, IMPORT }
    public interface Task { Object run() throws IOException; }
    public interface Observer { void completed(Outcome outcome); }
    public static final class Outcome {
        public final String id;
        public final Kind kind;
        public final Object value;
        public final String error;
        private Outcome(Pending pending, Object value, String error) {
            id = pending.id; kind = pending.kind; this.value = value; this.error = error;
        }
    }
    private final Executor background;
    private final Executor ui;
    private Pending current;

    public EnvironmentTransferController(Executor background, Executor ui) {
        if (background == null || ui == null) throw new IllegalArgumentException("Missing executor");
        this.background = background; this.ui = ui;
    }

    public String currentId() {
        synchronized (lock) {
            return current == null ? null : current.id;
        }
    }

    public String start(Kind kind, Task task) {
        synchronized (lock) {
            if (kind == null || task == null) throw new IllegalArgumentException("Missing transfer");
            if (current != null) throw new IllegalStateException("上一次文件操作尚未处理完成");
            Pending pending = new Pending(kind);
            current = pending;
            try { background.execute(() -> run(pending, task)); }
            catch (RuntimeException error) { current = null; throw error; }
            return pending.id;
        }
    }

    public boolean attach(String id, Observer observer) {
        synchronized (lock) {
            if (current == null || !current.id.equals(id) || observer == null) return false;
            current.observer = new WeakReference<>(observer);
            current.revision++;
            if (current.outcome != null) dispatch(current);
            return true;
        }
    }

    public void detach(Observer observer) {
        synchronized (lock) {
            if (current != null && current.observer.get() == observer) {
                current.observer.clear(); current.revision++;
            }
        }
    }

    /** Acknowledge only after the UI has retained/handled the outcome. Never discard active I/O. */
    public boolean consume(String id) {
        synchronized (lock) {
            if (current == null || !current.id.equals(id) || current.outcome == null) return false;
            current = null;
            return true;
        }
    }

    private void run(Pending pending, Task task) {
        synchronized (lock) {
            if (current != pending || pending.started) return;
            pending.started = true;
        }
        Object value = null;
        String error = null;
        try { value = task.run(); }
        catch (IOException | RuntimeException failure) {
            error = failure.getMessage() == null ? "文件操作失败，请重试" : failure.getMessage();
        } catch (OutOfMemoryError failure) { error = "内存不足，文件操作未完成"; }
        synchronized (lock) {
            pending.outcome = new Outcome(pending, value, error);
            dispatch(pending);
        }
    }

    private void dispatch(Pending pending) {
        if (pending.observer.get() == null) return;
        long revision = pending.revision;
        try { ui.execute(() -> {
            Observer observer;
            synchronized (lock) {
                if (current != pending || revision != pending.revision) return;
                observer = pending.observer.get();
            }
            if (observer != null) observer.completed(pending.outcome);
        }); } catch (RejectedExecutionException ignored) { /* Retain outcome for a later UI attachment. */ }
    }

    private static final class Pending {
        final String id = UUID.randomUUID().toString();
        final Kind kind;
        boolean started;
        long revision;
        Outcome outcome;
        WeakReference<Observer> observer = new WeakReference<>(null);
        Pending(Kind kind) { this.kind = kind; }
    }
}
