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
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Owner-bound encrypted account records. Each update is atomic and rejects a stale editor. */
public final class AccountCredentialStore {
    public interface Keys { SecretKey get(boolean create) throws GeneralSecurityException, IOException; }
    private static final int MAGIC = 0x4c444131;
    private static final int MAX_BYTES = 16384;
    private static final Object LOCAL_LOCK = new Object();
    private final Path root;
    private final Keys keys;

    public AccountCredentialStore(Path controlRoot, Keys keys) throws IOException {
        Path control = controlRoot.toRealPath();
        root = control.resolve("account-credentials");
        if (Files.isSymbolicLink(root)) throw unavailable();
        Files.createDirectories(root);
        if (!root.toRealPath().getParent().equals(control)) throw unavailable();
        this.keys = java.util.Objects.requireNonNull(keys);
    }

    public Snapshot read(EnvironmentDefinition environment) throws IOException {
        if (environment == null) throw new IllegalArgumentException("缺少账号所属环境");
        Path path = file(environment, ".bin");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Snapshot(0, AccountCredentials.empty());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        long size = Files.size(path);
        if (size < 40 || size > MAX_BYTES) throw unavailable();
        ByteBuffer data = ByteBuffer.allocate((int) size);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (data.hasRemaining()) if (channel.read(data) < 0) throw unavailable();
            if (channel.size() != size) throw unavailable();
        }
        data.flip();
        if (data.getInt() != MAGIC) throw unavailable();
        long revision = data.getLong();
        if (revision < 1) throw unavailable();
        byte[] iv = new byte[12];
        data.get(iv);
        byte[] encrypted = new byte[data.remaining()];
        data.get(encrypted);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(environment, revision));
            clear = cipher.doFinal(encrypted);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clear))) {
                AccountCredentials value = new AccountCredentials(input.readUTF(), input.readUTF(), input.readUTF());
                if (input.available() != 0) throw unavailable();
                return new Snapshot(revision, value);
            }
        } catch (GeneralSecurityException | IllegalArgumentException | IOException error) {
            throw new IOException("此环境的账号数据不可用，原文件已保留", error);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    public Snapshot save(EnvironmentDefinition environment, long expectedRevision, AccountCredentials value) throws IOException {
        if (environment == null || value == null || expectedRevision < 0) throw new IllegalArgumentException("账号保存参数无效");
        synchronized (LOCAL_LOCK) {
            Path lockPath = file(environment, ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); FileLock ignored = channel.lock()) {
                Snapshot previous = read(environment);
                if (previous.revision != expectedRevision) throw new IOException("账号信息已更新，请关闭并重新打开账号窗口");
                if (expectedRevision == Long.MAX_VALUE) throw unavailable();
                long revision = expectedRevision + 1;
                byte[] clear = encode(value);
                Path temporary = null;
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, keys.get(true));
                    cipher.updateAAD(aad(environment, revision));
                    byte[] encrypted = cipher.doFinal(clear);
                    byte[] iv = cipher.getIV();
                    if (iv.length != 12) throw unavailable();
                    ByteBuffer data = ByteBuffer.allocate(24 + encrypted.length);
                    data.putInt(MAGIC).putLong(revision).put(iv).put(encrypted).flip();
                    if (data.remaining() > MAX_BYTES) throw unavailable();
                    temporary = Files.createTempFile(root, "account-", ".tmp");
                    try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        while (data.hasRemaining()) output.write(data);
                        output.force(true);
                    }
                    Path target = file(environment, ".bin");
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return new Snapshot(revision, value);
                } catch (GeneralSecurityException error) {
                    throw new IOException("账号加密保存失败，原数据已保留", error);
                } finally {
                    Arrays.fill(clear, (byte) 0);
                    if (temporary != null) Files.deleteIfExists(temporary);
                }
            }
        }
    }

    private Path file(EnvironmentDefinition environment, String suffix) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        Path target = root.resolve(environment.identity.id() + suffix);
        if (Files.isSymbolicLink(target)) throw unavailable();
        return target;
    }

    private static byte[] encode(AccountCredentials value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(value.username);
            output.writeUTF(value.password);
            output.writeUTF(value.totpSecret);
        }
        if (bytes.size() > MAX_BYTES - 40) throw unavailable();
        return bytes.toByteArray();
    }

    private static byte[] aad(EnvironmentDefinition environment, long revision) {
        return ("landu-account-v1\n" + environment.identity.id() + "\n" + environment.createdAt + "\n" + revision)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static IOException unavailable() { return new IOException("此环境的账号文件无效或不可用，原文件已保留"); }

    public static final class Snapshot {
        public final long revision;
        public final AccountCredentials credentials;
        private Snapshot(long revision, AccountCredentials credentials) {
            this.revision = revision;
            this.credentials = credentials;
        }
        @Override public String toString() { return "AccountCredentialStore.Snapshot[redacted]"; }
    }
}
