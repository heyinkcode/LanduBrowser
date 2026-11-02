package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;

/** App-wide, non-recycling notification IDs. This directory must not be part of a profile backup. */
public final class NotificationIdStore {
    private static final Object LOCAL_LOCK = new Object();
    private static final int MAGIC = 0x4c444e49;
    private static final int VERSION = 1;
    private static final int FIRST_ID = 0x40000000;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_BYTES = 16 + MAX_RECORDS * 24 + 32;
    private final Path root;
    private final Path data;
    private final Path lockPath;

    public NotificationIdStore(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        root = normalized.toRealPath();
        if (!root.equals(normalized)) throw new IOException("Linked notification directory");
        data = root.resolve("notification-ids.bin");
        lockPath = root.resolve("notification-ids.lock");
        locked(state -> 0);
    }

    public int getOrCreate(String owner, int logicalId, IntPredicate occupied) throws IOException {
        String key = key(owner, logicalId);
        if (occupied == null) throw new IllegalArgumentException("Missing occupied-ID check");
        return locked(state -> {
            Integer existing = state.ids.get(key);
            if (existing != null) return existing;
            if (state.ids.size() >= MAX_RECORDS) throw new IOException("Notification ID capacity exceeded");
            int candidate = state.last;
            for (int attempts = 0; attempts < MAX_RECORDS; attempts++) {
                if (candidate == Integer.MAX_VALUE) throw new IOException("Notification IDs exhausted");
                candidate++;
                if (occupied.test(candidate)) continue;
                state.ids.put(key, candidate);
                state.last = candidate;
                write(state);
                return candidate;
            }
            throw new IOException("No unused notification ID in bounded allocation window");
        });
    }

    /** Zero means absent; cancellation must not allocate a new ID. */
    public int find(String owner, int logicalId) throws IOException {
        String key = key(owner, logicalId);
        return locked(state -> state.ids.getOrDefault(key, 0));
    }

    public boolean owns(String owner, int platformId) throws IOException {
        String prefix = canonicalOwner(owner) + ':';
        return locked(state -> {
            for (Map.Entry<String, Integer> entry : state.ids.entrySet()) {
                if (entry.getValue() == platformId && entry.getKey().startsWith(prefix)) return 1;
            }
            return 0;
        }) == 1;
    }

    private static String canonicalOwner(String owner) {
        if (owner == null || owner.length() != 36 || !UUID.fromString(owner).toString().equals(owner)) {
            throw new IllegalArgumentException("Invalid environment owner");
        }
        return owner;
    }

    private static String key(String owner, int logicalId) { return canonicalOwner(owner) + ':' + logicalId; }

    private interface Operation { int run(State state) throws IOException; }
    private static final class State {
        final Map<String, Integer> ids = new HashMap<>();
        int last = FIRST_ID - 1;
    }

    private int locked(Operation operation) throws IOException {
        synchronized (LOCAL_LOCK) {
            if (!root.toRealPath().equals(root)) throw new IOException("Notification directory changed");
            rejectLink(lockPath);
            rejectLink(data);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    FileLock ignored = channel.lock()) {
                State state;
                if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) {
                    // A durable marker distinguishes first use from accidental deletion. Never reset IDs.
                    if (channel.size() != 0) throw new IOException("Notification ID registry is missing");
                    channel.write(ByteBuffer.wrap(new byte[]{1}));
                    channel.force(true);
                    state = new State();
                    write(state);
                } else {
                    state = read();
                    if (channel.size() == 0) {
                        channel.write(ByteBuffer.wrap(new byte[]{1}));
                        channel.force(true);
                    }
                }
                return operation.run(state);
            }
        }
    }

    private State read() throws IOException {
        rejectLink(data);
        byte[] bytes;
        try (FileChannel channel = FileChannel.open(data, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 48 || size > MAX_BYTES) throw new IOException("Invalid notification registry size");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw new IOException("Truncated notification registry");
            }
            if (channel.size() != size) throw new IOException("Notification registry changed while reading");
            bytes = buffer.array();
        }
        int bodyLength = bytes.length - 32;
        if (!MessageDigest.isEqual(hash(Arrays.copyOf(bytes, bodyLength)),
                Arrays.copyOfRange(bytes, bodyLength, bytes.length))) throw new IOException("Notification registry checksum mismatch");
        State state = new State();
        Set<Integer> platformIds = new HashSet<>();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, 0, bodyLength))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unknown notification registry format");
            state.last = input.readInt();
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS || bodyLength != 16 + count * 24
                    || state.last < FIRST_ID - 1) throw new IOException("Invalid notification registry header");
            for (int i = 0; i < count; i++) {
                String owner = new UUID(input.readLong(), input.readLong()).toString();
                int logicalId = input.readInt();
                int platformId = input.readInt();
                if (platformId < FIRST_ID || platformId > state.last || !platformIds.add(platformId)
                        || state.ids.put(key(owner, logicalId), platformId) != null) {
                    throw new IOException("Duplicate or invalid notification mapping");
                }
            }
        }
        return state;
    }

    private void write(State state) throws IOException {
        rejectLink(data);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(state.last);
            output.writeInt(state.ids.size());
            for (Map.Entry<String, Integer> entry : state.ids.entrySet()) {
                UUID owner = UUID.fromString(entry.getKey().substring(0, 36));
                output.writeLong(owner.getMostSignificantBits());
                output.writeLong(owner.getLeastSignificantBits());
                output.writeInt(Integer.parseInt(entry.getKey().substring(37)));
                output.writeInt(entry.getValue());
            }
        }
        byte[] body = bytes.toByteArray();
        bytes.write(hash(body));
        Path temporary = Files.createTempFile(root, "notification-ids-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) { directory.force(true); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Linked notification registry path");
    }
}
