package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Immutable authenticated-encryption records, bound to an environment and exact proxy endpoint. */
public final class ProxyCredentialStore {
    public interface Keys { SecretKey get(boolean create) throws GeneralSecurityException, IOException; }
    private static final int MAGIC = 0x4c445031;
    private static final int MAX_BYTES = 8192;
    private final Path root;
    private final Keys keys;

    public ProxyCredentialStore(Path controlRoot, Keys keys) throws IOException {
        Path privateRoot = controlRoot.toRealPath();
        root = privateRoot.resolve("proxy-credentials");
        if (Files.isSymbolicLink(root)) throw failure();
        Files.createDirectories(root);
        if (!root.toRealPath().getParent().equals(privateRoot)) throw failure();
        this.keys = java.util.Objects.requireNonNull(keys);
    }

    public String put(EnvironmentIdentity owner, EnvironmentNetworkSettings network,
            ProxyCredentials credentials) throws IOException {
        if (credentials == null || network == null || !network.requiresAuthentication) throw failure();
        credentials.validateFor(network);
        byte[] clear = encode(credentials);
        Path target = null;
        boolean complete = false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(true));
            cipher.updateAAD(aad(owner, network));
            byte[] encrypted = cipher.doFinal(clear);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw failure();
            ByteBuffer payload = ByteBuffer.allocate(4 + 12 + encrypted.length);
            payload.putInt(MAGIC).put(iv).put(encrypted).flip();
            String reference = UUID.randomUUID().toString();
            target = file(reference);
            // The catalog references this unique record only after the full file is forced.
            try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (payload.hasRemaining()) channel.write(payload);
                channel.force(true);
            }
            complete = true;
            return reference;
        } catch (GeneralSecurityException error) {
            throw new IOException("无法加密代理凭据，请重新保存", error);
        } finally {
            Arrays.fill(clear, (byte) 0);
            if (!complete && target != null) Files.deleteIfExists(target);
        }
    }

    public ProxyCredentials read(EnvironmentIdentity owner, EnvironmentNetworkSettings network) throws IOException {
        if (!network.requiresAuthentication || network.credentialReference.isEmpty()) {
            throw new IOException("请先填写并保存此环境的代理账号密码");
        }
        Path target = file(network.credentialReference);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw failure();
        long size = Files.size(target);
        if (size < 32 || size > MAX_BYTES) throw failure();
        ByteBuffer data = ByteBuffer.allocate((int) size);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (data.hasRemaining()) if (channel.read(data) < 0) throw failure();
            if (channel.size() != size) throw failure();
        }
        data.flip();
        if (data.getInt() != MAGIC) throw failure();
        byte[] iv = new byte[12];
        data.get(iv);
        byte[] encrypted = new byte[data.remaining()];
        data.get(encrypted);
        byte[] clear = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys.get(false), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(owner, network));
            clear = cipher.doFinal(encrypted);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(clear))) {
                ProxyCredentials result = new ProxyCredentials(input.readUTF(), input.readUTF());
                if (input.available() != 0) throw failure();
                result.validateFor(network);
                return result;
            }
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("代理凭据不可用，请重新填写；不会改用手机直连", error);
        } finally {
            if (clear != null) Arrays.fill(clear, (byte) 0);
        }
    }

    /** Roll back only a newly created record after a failed catalog save. */
    public void discardNew(String reference) throws IOException { Files.deleteIfExists(file(reference)); }

    private Path file(String reference) throws IOException {
        if (!EnvironmentNetworkSettings.validCredentialReference(reference)) throw failure();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw failure();
        Path target = root.resolve(reference + ".bin");
        if (Files.isSymbolicLink(target)) throw failure();
        return target;
    }

    private static byte[] encode(ProxyCredentials credentials) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(credentials.username);
            output.writeUTF(credentials.password);
        }
        if (bytes.size() > MAX_BYTES - 32) throw failure();
        return bytes.toByteArray();
    }

    private static byte[] aad(EnvironmentIdentity owner, EnvironmentNetworkSettings network) throws IOException {
        if (owner == null || network == null || network.proxyUri.isEmpty()) throw failure();
        return ("landu-proxy-v1\n" + owner.id() + "\n" + network.proxyUri)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static IOException failure() { return new IOException("代理凭据文件无效或不可用，请重新填写"); }
}
