package org.chromium.chrome.browser.tabpersistence;

import android.os.Build;
import android.util.AtomicFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class LanduAtomicFileWriterTest {
    private static int assertions;
    private static final byte[] OLD = "previous-state".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEXT = "complete-next-state".getBytes(StandardCharsets.UTF_8);
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static File oldFile(Path root, String name) throws IOException {
        Path file = root.resolve(name); Files.write(file, OLD); return file.toFile();
    }
    private static void oldKept(File file) throws IOException {
        check(Arrays.equals(OLD, Files.readAllBytes(file.toPath())), "last valid state retained");
    }
    private static boolean write(AtomicFile atomic, LanduAtomicFileWriter.Writer producer, boolean legacy) {
        return LanduAtomicFileWriter.write(atomic, producer, out -> out.getFD().sync(), legacy);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectories(root);
        for (boolean legacy : new boolean[] {false, true}) {
            String prefix = legacy ? "legacy-" : "modern-";
            File base = oldFile(root, prefix + "ok"); AtomicFile atomic = new AtomicFile(base, legacy);
            check(write(atomic, out -> { out.write(NEXT); out.close(); }, legacy), "codec close does not prevent fsync");
            check(Arrays.equals(NEXT, Files.readAllBytes(base.toPath())), "committed bytes match");
            check(atomic.finishes == 1 && atomic.failures == 0, "one successful commit");

            base = oldFile(root, prefix + "partial"); atomic = new AtomicFile(base, legacy);
            check(!write(atomic, out -> { out.write(3); throw new IOException("disk"); }, legacy), "partial writer fails");
            oldKept(base); check(atomic.finishes == 0, "partial write never committed");

            base = oldFile(root, prefix + "memory"); atomic = new AtomicFile(base, legacy);
            check(!write(atomic, out -> { out.write(4); throw new OutOfMemoryError("injected"); }, legacy), "allocation failure reported");
            oldKept(base);

            base = oldFile(root, prefix + "sync"); atomic = new AtomicFile(base, legacy);
            check(!LanduAtomicFileWriter.write(atomic, out -> out.write(NEXT), out -> { throw new IOException("sync"); }, legacy), "fsync failure reported");
            oldKept(base); check(atomic.finishes == 0, "failed sync never committed");

            base = oldFile(root, prefix + "commit"); atomic = new AtomicFile(base, legacy); atomic.failCommit = true;
            check(!write(atomic, out -> out.write(NEXT), legacy), "silent platform commit failure detected"); oldKept(base);

            base = oldFile(root, prefix + "corrupt"); atomic = new AtomicFile(base, legacy);
            File pending = legacy ? base : new File(base.getPath() + ".new");
            check(!write(atomic, out -> { out.write(NEXT); Files.write(pending.toPath(), new byte[] {1, 2}); }, legacy), "readback catches incomplete bytes before commit");
            oldKept(base); check(atomic.finishes == 0, "corruption never committed");

            base = oldFile(root, prefix + "start"); atomic = new AtomicFile(base, legacy); atomic.failStart = true;
            check(!write(atomic, out -> out.write(NEXT), legacy), "start failure reported"); oldKept(base);

            base = root.resolve(prefix + "empty").toFile();
            check(write(new AtomicFile(base, legacy), out -> {}, legacy) && base.isFile() && base.length() == 0, "first empty file commits");

            base = oldFile(root, prefix + "backup"); Files.write(Path.of(base.getPath() + ".bak"), NEXT);
            atomic = new AtomicFile(base, legacy);
            check(!write(atomic, out -> { throw new IOException("failed after recovery"); }, legacy), "recovered backup is retained on failure");
            check(Arrays.equals(NEXT, Files.readAllBytes(base.toPath())), "valid backup restored");

            File directory = root.resolve(prefix + "directory").toFile(); directory.mkdir(); atomic = new AtomicFile(directory, legacy);
            check(!write(atomic, out -> out.write(NEXT), legacy) && directory.isDirectory() && atomic.starts == 0, "never replace a directory");

            File target = oldFile(root, prefix + "link-target"); Path link = root.resolve(prefix + "link");
            Files.createSymbolicLink(link, target.toPath()); atomic = new AtomicFile(link.toFile(), legacy);
            check(!write(atomic, out -> out.write(NEXT), legacy) && atomic.starts == 0, "linked state rejected"); oldKept(target);
        }
        File encrypted = oldFile(root, "encrypted"); byte[] key = new byte[16], iv = new byte[16];
        Cipher encoder = Cipher.getInstance("AES/CBC/PKCS5Padding");
        encoder.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        check(write(new AtomicFile(encrypted, false), out -> {
            try (CipherOutputStream cipher = new CipherOutputStream(out, encoder)) { cipher.write(NEXT); }
        }, false), "encryption can finish padding before fsync");
        Cipher decoder = Cipher.getInstance("AES/CBC/PKCS5Padding");
        decoder.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        check(Arrays.equals(NEXT, decoder.doFinal(Files.readAllBytes(encrypted.toPath()))), "encrypted bytes are complete");
        Build.VERSION.SDK_INT = 36;
        File defaultPath = oldFile(root, "default-entry");
        check(LanduAtomicFileWriter.write(defaultPath, out -> out.write(NEXT)), "public Android API selection works");
        System.out.println("{\"suite\":\"strict-atomic-file-host-faults\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidRuntimeExecuted\":false,\"realDeviceFsyncTested\":false}");
    }
}
