package com.landu.browser.nativecore;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Waits for every live window, without letting duplicate or late replies close a newer attempt. */
public final class EnvironmentSaveBarrier {
    private final Object lock = new Object();
    public interface Save { void save(EnvironmentShutdownCoordinator.Result result); }
    private final IdentityHashMap<Object, Save> windows = new IdentityHashMap<>();
    private long revision;

    public void register(Object owner, Save save) {
        synchronized (lock) {
            windows.put(Objects.requireNonNull(owner), Objects.requireNonNull(save));
            revision++;
        }
    }

    public void unregister(Object owner) {
        synchronized (lock) {
            if (windows.remove(owner) != null) revision++;
        }
    }

    public void saveAll(EnvironmentShutdownCoordinator.Result result) {
        final List<Save> snapshot;
        final long expected;
        synchronized (lock) {
            snapshot = new ArrayList<>(windows.values());
            expected = revision;
        }
        Batch batch = new Batch(snapshot.size(), expected, Objects.requireNonNull(result));
        if (snapshot.isEmpty()) { batch.finishEmpty(); return; }
        for (Save save : snapshot) {
            if (!batch.pending()) break;
            EnvironmentShutdownCoordinator.Result reply = batch.reply();
            try { save.save(reply); }
            catch (RuntimeException error) { reply.complete("标签页保存失败，环境仍保持打开"); }
        }
    }

    long revision() {
        synchronized (lock) {
            return revision;
        }
    }
    boolean unchanged(long expected) {
        synchronized (lock) {
            return revision == expected;
        }
    }

    private final class Batch {
        private int remaining;
        private final long expected;
        private final EnvironmentShutdownCoordinator.Result result;
        private boolean finished;

        Batch(int remaining, long expected, EnvironmentShutdownCoordinator.Result result) {
            this.remaining = remaining;
            this.expected = expected;
            this.result = result;
        }

        boolean pending() {
            boolean active = result.isPending();
            synchronized (this) { return !finished && active; }
        }

        void finishEmpty() { deliver(null, true); }

        EnvironmentShutdownCoordinator.Result reply() {
            return new EnvironmentShutdownCoordinator.Result() {
                private boolean replied;
                @Override public void complete(String error) {
                    synchronized (this) {
                        if (replied) return;
                        replied = true;
                    }
                    deliver(error, false);
                }
                @Override public boolean isPending() { return Batch.this.pending(); }
            };
        }

        private void deliver(String error, boolean empty) {
            if (!result.isPending()) {
                synchronized (this) { finished = true; }
                return;
            }
            boolean stable = unchanged(expected);
            synchronized (this) {
                if (finished) return;
                if (!stable) error = "浏览器窗口状态已变化，请重新关闭环境";
                if (error == null && !empty && --remaining != 0) return;
                finished = true;
            }
            result.complete(error);
        }
    }
}
