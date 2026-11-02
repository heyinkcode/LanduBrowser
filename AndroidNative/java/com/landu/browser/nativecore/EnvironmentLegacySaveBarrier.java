package com.landu.browser.nativecore;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** UI-sequence only: pause every legacy store, drain every queue, then take fresh snapshots. */
public final class EnvironmentLegacySaveBarrier {
    public interface Result { void complete(boolean success); }
    public interface Snapshot {
        /** Start background I/O; reply on the UI sequence after all writes have stopped. */
        void write(Result result);
        boolean unchanged();
        /** Release captured resources on the UI sequence, after I/O has finished. */
        void release(boolean saved);
    }
    public interface Store {
        void pause();
        void drain(Runnable ready);
        Snapshot capture();
        void resume(boolean saved);
    }

    private final IdentityHashMap<Object, Store> stores = new IdentityHashMap<>();
    private long revision;
    private Batch current;

    public void register(Object owner, Store store) {
        if (owner == null || store == null) throw new IllegalArgumentException("Missing store");
        for (Map.Entry<Object, Store> item : stores.entrySet()) {
            if (item.getKey() != owner && item.getValue() == store) {
                throw new IllegalArgumentException("Store already has an owner");
            }
        }
        if (stores.put(owner, store) != store) revision++;
    }

    public void unregister(Object owner) {
        if (stores.remove(owner) != null) revision++;
    }

    public void saveAll(Runnable done, Runnable failure) {
        if (current != null) {
            current.replies.add(new Reply(done, failure));
            return;
        }
        Batch batch = new Batch(revision, new ArrayList<>(stores.values()));
        batch.replies.add(new Reply(done, failure));
        current = batch;
        try {
            // Pause all stores before posting even the first drain marker. cancel(false)
            // alone does not wait for an already-running FutureTask's file I/O.
            for (Store store : batch.snapshot) {
                batch.paused.add(store);
                store.pause();
            }
            if (batch.snapshot.isEmpty()) { finish(batch, true); return; }
            for (Store store : batch.snapshot) {
                store.drain(() -> drained(batch, store));
                if (current != batch) return;
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            finish(batch, false);
        }
    }

    private void drained(Batch batch, Store store) {
        if (current != batch || batch.drained.put(store, true) != null) return;
        if (batch.drained.size() != batch.snapshot.size()) return;
        if (batch.revision != revision) { finish(batch, false); return; }
        try {
            // Capture every owner first. A later capture failure must not leave
            // an earlier owner's I/O using resources which finish() releases.
            for (Store participant : batch.snapshot) {
                Snapshot snapshot = participant.capture();
                if (snapshot == null) throw new IllegalStateException("Missing snapshot");
                batch.captured.put(participant, snapshot);
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            finish(batch, false);
            return;
        }
        if (!unchanged(batch)) { finish(batch, false); return; }
        for (Store participant : batch.snapshot) {
            try {
                batch.captured.get(participant).write(success -> written(batch, participant, success));
            } catch (RuntimeException | OutOfMemoryError error) {
                // Snapshot.write must throw before starting work or deliver a
                // reply after its work stops; adapters must never abandon I/O.
                written(batch, participant, false);
            }
            if (current != batch) return;
        }
    }

    private void written(Batch batch, Store store, boolean success) {
        if (current != batch || batch.written.put(store, success) != null) return;
        batch.success &= success;
        // A failed writer cannot release another writer's live native buffer.
        if (batch.written.size() == batch.snapshot.size()) {
            finish(batch, batch.success && unchanged(batch));
        }
    }

    private boolean unchanged(Batch batch) {
        if (batch.revision != revision) return false;
        try {
            for (Snapshot snapshot : batch.captured.values()) {
                if (!snapshot.unchanged()) return false;
            }
            return batch.revision == revision;
        } catch (RuntimeException | OutOfMemoryError error) {
            return false;
        }
    }

    private void finish(Batch batch, boolean success) {
        if (current != batch) return;
        for (Snapshot snapshot : batch.captured.values()) {
            try { snapshot.release(success); }
            catch (RuntimeException | OutOfMemoryError error) { success = false; }
        }
        // Releasing a successful snapshot may notify ordinary dirtiness
        // observers. Do not clear newly queued edits caused by those callbacks.
        if (success) success = unchanged(batch);
        // Keep the batch installed during resume, so reentrant requests join it.
        for (Store store : batch.paused) {
            try { store.resume(success); }
            catch (RuntimeException | OutOfMemoryError error) { success = false; }
        }
        current = null;
        for (Reply reply : batch.replies) {
            try { (success ? reply.done : reply.failure).run(); }
            catch (RuntimeException ignored) { /* A disconnected observer cannot change persistence. */ }
        }
    }

    private static final class Reply {
        final Runnable done;
        final Runnable failure;
        Reply(Runnable done, Runnable failure) { this.done = done; this.failure = failure; }
    }

    private static final class Batch {
        final long revision;
        final List<Store> snapshot;
        final List<Store> paused = new ArrayList<>();
        final IdentityHashMap<Store, Boolean> drained = new IdentityHashMap<>();
        final IdentityHashMap<Store, Snapshot> captured = new IdentityHashMap<>();
        final IdentityHashMap<Store, Boolean> written = new IdentityHashMap<>();
        final List<Reply> replies = new ArrayList<>();
        boolean success = true;
        Batch(long revision, List<Store> snapshot) {
            this.revision = revision;
            this.snapshot = snapshot;
        }
    }
}
