package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvironmentLeaseStore {
    private static final int MAGIC = 0x4c444e31;
    private static final int PENDING = 1;
    private static final int RUNNING = 2;
    private static final int CLOSED = 3;
    private static final Map<Path, Guard> OWNED_LOCKS = new HashMap<>();
    private final Path root;
    private final Path selection;
    private final Path lockPath;

    public EnvironmentLeaseStore(Path privateControlDirectory) throws IOException {
        Files.createDirectories(privateControlDirectory);
        root = privateControlDirectory.toRealPath();
        selection = root.resolve("active-environment.bin");
        lockPath = root.resolve("browser-process.lock");
    }

    public Ticket prepareLaunch(EnvironmentIdentity identity) throws IOException {
        try (Guard ignored = acquire()) {
            return prepareLocked(identity);
        }
    }

    private Ticket prepareLocked(EnvironmentIdentity identity) throws IOException {
        if (identity == null) throw new IllegalArgumentException("环境标识不能为空");
        // Catalog transitions also hold this lease lock. Re-read here so a stale UI or
        // notification cannot select an environment after it entered the recycle bin.
        Path catalogPath = root.resolve("environments.bin");
        if (Files.exists(catalogPath, LinkOption.NOFOLLOW_LINKS)) {
            new EnvironmentCatalog(root).read().find(identity);
        }
        LeaseRecord previous = read();
        if (previous != null && previous.state == PENDING) {
            if (previous.ticket.identity.equals(identity)) return previous.ticket;
            throw new BusyException("请先取消另一个待启动的环境");
        }
        Ticket ticket = new Ticket(identity, UUID.randomUUID().toString());
        write(new LeaseRecord(ticket, PENDING));
        return ticket;
    }

    /** Decisions still require the browser-side nonce check; UI observations are advisory. */
    public LaunchRequest requestLaunch(EnvironmentIdentity identity) throws IOException {
        if (identity == null) throw new IllegalArgumentException("环境标识不能为空");
        try (Guard ignored = acquire()) {
            return new LaunchRequest(prepareLocked(identity), false);
        } catch (BusyException busy) {
            LeaseRecord current = read();
            if (current != null && current.state == RUNNING && current.ticket.identity.equals(identity)) {
                return new LaunchRequest(current.ticket, true);
            }
            if (current != null && current.state == CLOSED) throw new BusyException("环境正在关闭，请稍后重试");
            if (current != null && current.state == PENDING && current.ticket.identity.equals(identity)) {
                throw new BusyException("环境正在启动，请稍后重试");
            }
            throw new BusyException("请先关闭当前环境，再启动其他环境");
        }
    }

    public Observation observe() throws IOException {
        try (Guard ignored = acquire()) {
            LeaseRecord record = read();
            if (record != null && record.state == PENDING) return new Observation(Status.PENDING, record.ticket, false);
            // A persisted RUNNING record after a crash is not proof of a live browser.
            return new Observation(Status.IDLE, null, false);
        } catch (BusyException busy) {
            LeaseRecord record = read();
            if (record == null) return new Observation(Status.BUSY, null, true);
            Status status = record.state == RUNNING ? Status.RUNNING
                    : record.state == CLOSED ? Status.STOPPING : Status.PENDING;
            return new Observation(status, record.ticket, true);
        }
    }

    public interface IdleOperation<T> { T run() throws IOException; }

    /** Hold the same process lock used by launch and the entire browser session. */
    public <T> T whileIdle(IdleOperation<T> operation) throws IOException {
        if (operation == null) throw new IllegalArgumentException("缺少环境操作");
        try (Guard ignored = acquire()) {
            LeaseRecord record = read();
            if (record != null && record.state == PENDING) {
                throw new BusyException("请先取消待启动的环境");
            }
            return operation.run();
        }
    }

    public void cancelPending(Ticket ticket) throws IOException {
        try (Guard ignored = acquire()) {
            LeaseRecord record = requireRecord();
            requireTicket(record.ticket, ticket);
            if (record.state != PENDING) throw new IOException("Launch is not pending");
            write(new LeaseRecord(record.ticket, CLOSED));
        }
    }

    public Ticket pendingLaunch() throws IOException {
        try (Guard ignored = acquire()) {
            LeaseRecord record = read();
            return record != null && record.state == PENDING ? record.ticket : null;
        }
    }

    public Ticket currentLaunch() throws IOException {
        LeaseRecord record = read();
        return record != null && record.state != CLOSED ? record.ticket : null;
    }

    public Session claimSelected(Path privateEnvironmentDirectory) throws IOException {
        Guard guard = acquire();
        try {
            LeaseRecord record = requireRecord();
            if (record.state != PENDING && record.state != RUNNING) {
                throw new IOException("No environment is selected for launch");
            }
            Files.createDirectories(privateEnvironmentDirectory);
            Path environmentRoot = privateEnvironmentDirectory.toRealPath();
            Path directory = checkedDirectory(environmentRoot,
                    record.ticket.identity.directoryName());
            for (String name : new String[] {"profile", "cache", "files", "textures"}) {
                checkedDirectory(directory, name);
            }
            write(new LeaseRecord(record.ticket, RUNNING));
            return new Session(this, guard, record.ticket, directory);
        } catch (IOException | RuntimeException error) {
            guard.close();
            throw error;
        }
    }

    public boolean isBrowserRunning() throws IOException {
        try (Guard ignored = acquire()) {
            return false;
        } catch (BusyException busy) {
            return true;
        }
    }

    private static Path checkedDirectory(Path parent, String name) throws IOException {
        Path path = parent.resolve(name);
        rejectLink(path);
        Files.createDirectories(path);
        if (!path.toRealPath().equals(path)) throw new IOException("Environment path escaped its root");
        return path;
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed");
    }

    private Guard acquire() throws IOException {
        synchronized (OWNED_LOCKS) {
            return acquireLocked();
        }
    }

    private Guard acquireLocked() throws IOException {
        if (OWNED_LOCKS.containsKey(lockPath)) {
            throw new BusyException("Browser process still owns the environment");
        }
        rejectLink(lockPath);
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new BusyException("Browser process still owns the environment");
            Guard guard = new Guard(lockPath, channel, lock);
            OWNED_LOCKS.put(lockPath, guard);
            return guard;
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new BusyException("Browser process still owns the environment");
        } catch (IOException | RuntimeException error) {
            channel.close();
            throw error;
        }
    }

    private LeaseRecord requireRecord() throws IOException {
        LeaseRecord record = read();
        if (record == null) throw new IOException("No environment selected");
        return record;
    }

    private LeaseRecord read() throws IOException {
        rejectLink(selection);
        if (!Files.exists(selection, LinkOption.NOFOLLOW_LINKS)) return null;
        long length = Files.size(selection);
        if (length < 12 || length > 256) throw new IOException("Invalid launch record length");
        try (DataInputStream stream = new DataInputStream(
                new ByteArrayInputStream(Files.readAllBytes(selection)))) {
            if (stream.readInt() != MAGIC) throw new IOException("Unsupported launch record");
            int state = stream.readInt();
            if (state < PENDING || state > CLOSED) throw new IOException("Invalid launch state");
            EnvironmentIdentity identity = EnvironmentIdentity.parse(stream.readUTF());
            String nonce = stream.readUTF();
            EnvironmentIdentity.parse(nonce);
            if (stream.available() != 0) throw new IOException("Unexpected launch record data");
            return new LeaseRecord(new Ticket(identity, nonce), state);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid launch identity", error);
        }
    }

    private void write(LeaseRecord record) throws IOException {
        rejectLink(selection);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream stream = new DataOutputStream(bytes)) {
            stream.writeInt(MAGIC);
            stream.writeInt(record.state);
            stream.writeUTF(record.ticket.identity.id());
            stream.writeUTF(record.ticket.nonce);
        }
        Path temporary = Files.createTempFile(root, "activation-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes.toByteArray());
                while (data.hasRemaining()) output.write(data);
                output.force(true);
            }
            Files.move(temporary, selection, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireTicket(Ticket expected, Ticket actual) throws IOException {
        if (actual == null || !expected.identity.equals(actual.identity)
                || !expected.nonce.equals(actual.nonce)) {
            throw new IOException("Environment launch ticket is stale");
        }
    }

    public static final class Ticket {
        public final EnvironmentIdentity identity;
        public final String nonce;

        private Ticket(EnvironmentIdentity identity, String nonce) {
            this.identity = identity;
            this.nonce = nonce;
        }
    }

    public enum Status { IDLE, PENDING, RUNNING, STOPPING, BUSY }

    public static final class Observation {
        public final Status status;
        public final Ticket ticket;
        public final boolean processRunning;

        private Observation(Status status, Ticket ticket, boolean processRunning) {
            this.status = status;
            this.ticket = ticket;
            this.processRunning = processRunning;
        }

        public boolean belongsTo(EnvironmentIdentity identity) {
            return ticket != null && ticket.identity.equals(identity);
        }
    }

    public static final class LaunchRequest {
        public final Ticket ticket;
        public final boolean resumeExisting;

        private LaunchRequest(Ticket ticket, boolean resumeExisting) {
            this.ticket = ticket;
            this.resumeExisting = resumeExisting;
        }
    }

    public static final class Session implements AutoCloseable {
        private final Object lock = new Object();
        private final EnvironmentLeaseStore store;
        private final Guard guard;
        private final Ticket ticket;
        private final Path directory;
        private boolean closed;

        private Session(EnvironmentLeaseStore store, Guard guard, Ticket ticket, Path directory) {
            this.store = store;
            this.guard = guard;
            this.ticket = ticket;
            this.directory = directory;
        }

        public Ticket ticket() {
            synchronized (lock) {
                if (closed) throw new IllegalStateException("Environment session is closed");
                return ticket;
            }
        }

        public Path directory() {
            synchronized (lock) {
                if (closed) throw new IllegalStateException("Environment session is closed");
                return directory;
            }
        }

        public void verifyLaunch(String identity, String nonce) throws IOException {
            synchronized (lock) {
                if (closed || !ticket.identity.id().equals(identity) || !ticket.nonce.equals(nonce)) {
                    throw new IOException("Activity does not belong to this environment session");
                }
            }
        }

        public void preventBackgroundRestart() throws IOException {
            synchronized (lock) {
                if (closed) throw new IOException("Environment session is closed");
                store.write(new LeaseRecord(ticket, CLOSED));
            }
        }

        public void restoreAfterFailedStop() throws IOException {
            synchronized (lock) {
                if (closed) throw new IOException("Environment session is closed");
                store.write(new LeaseRecord(ticket, RUNNING));
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                if (closed) return;
                try {
                    store.write(new LeaseRecord(ticket, CLOSED));
                } finally {
                    closed = true;
                    guard.close();
                }
            }
        }
    }

    public static final class BusyException extends IOException {
        public BusyException(String message) {
            super(message);
        }
    }

    private static final class LeaseRecord {
        final Ticket ticket;
        final int state;

        LeaseRecord(Ticket ticket, int state) {
            this.ticket = ticket;
            this.state = state;
        }
    }

    private static final class Guard implements AutoCloseable {
        final Path path;
        final FileChannel channel;
        final FileLock lock;

        Guard(Path path, FileChannel channel, FileLock lock) {
            this.path = path;
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            synchronized (OWNED_LOCKS) {
                if (OWNED_LOCKS.get(path) != this) return;
                try {
                    try {
                        lock.release();
                    } finally {
                        channel.close();
                    }
                } finally {
                    OWNED_LOCKS.remove(path);
                }
            }
        }
    }
}
