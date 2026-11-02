// Copyright 2026 Landu Browser contributors.
// SPDX-License-Identifier: BSD-3-Clause
package org.chromium.chrome.browser.tabpersistence;

import android.os.Build;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/** Explicit-close writes. Callers must first drain other writers for this same file. */
final class LanduAtomicFileWriter {
    interface Writer { void write(OutputStream output) throws IOException; }
    interface Sync { void sync(FileOutputStream output) throws IOException; }

    private LanduAtomicFileWriter() {}

    static boolean write(File file, Writer writer) {
        if (file == null || writer == null) return false;
        return write(new AtomicFile(file), writer, output -> output.getFD().sync(),
                Build.VERSION.SDK_INT < 30);
    }

    // Per-call dependencies allow I/O failures to be tested without global mutable hooks.
    static synchronized boolean write(AtomicFile atomic, Writer writer, Sync sync, boolean legacy) {
        if (atomic == null || writer == null || sync == null) return false;
        final File base;
        final File pending;
        final File backup;
        try {
            base = atomic.getBaseFile().getAbsoluteFile();
            pending = legacy ? base : new File(base.getPath() + ".new");
            backup = new File(base.getPath() + ".bak");
            if (!usable(base) || !usable(pending) || !usable(backup)) return false;
        } catch (RuntimeException | OutOfMemoryError error) {
            return false;
        }
        FileOutputStream stream = null;
        boolean confirmed = false;
        try {
            if (backup.exists()) {
                // Recover the platform's old valid state before beginning a new write.
                try (FileInputStream ignored = atomic.openRead()) { }
                if (backup.exists()) return false;
            }
            // Android 10 otherwise logs a failed backup rename and can still
            // truncate the original. Require that rename to succeed ourselves.
            if (legacy && base.exists() && !base.renameTo(backup)) return false;
            stream = atomic.startWrite();
            if (!legacy && backup.exists()) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            RecordingOutput output = new RecordingOutput(stream, digest);
            writer.write(output);
            output.flush();
            sync.sync(stream);
            byte[] expected = digest.digest();
            if (!matches(pending, output.count, expected)) return false;
            atomic.finishWrite(stream);
            // finishWrite() returns void and platform versions log some failures.
            // Check both the commit marker and actual bytes before reporting success.
            if ((legacy ? backup : pending).exists()) return false;
            confirmed = matches(base, output.count, expected);
            return confirmed;
        } catch (IOException | GeneralSecurityException | RuntimeException | OutOfMemoryError error) {
            return false;
        } finally {
            if (!confirmed && stream != null) {
                try { atomic.failWrite(stream); } catch (RuntimeException ignored) { }
            }
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
            // startWrite() can throw after the legacy backup was made. Keep the
            // backup recoverable; never delete it as part of failure handling.
            if (!confirmed && legacy && backup.exists() && !base.exists()) {
                try (FileInputStream ignored = atomic.openRead()) { }
                catch (IOException | RuntimeException ignored) { }
            }
        }
    }

    private static boolean usable(File file) {
        return !Files.isSymbolicLink(file.toPath()) && (!file.exists() || file.isFile());
    }

    private static boolean matches(File file, long expectedSize, byte[] expected)
            throws IOException, GeneralSecurityException {
        if (!usable(file) || !file.isFile() || file.length() != expectedSize) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        byte[] chunk = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(chunk)) > 0) {
                count += read;
                if (count > expectedSize) return false;
                digest.update(chunk, 0, read);
            }
            return read == -1 && count == expectedSize && MessageDigest.isEqual(expected, digest.digest());
        }
    }

    /** Codec close() finalizes encryption/buffering but leaves the file open for fsync. */
    private static final class RecordingOutput extends OutputStream {
        private final FileOutputStream file;
        private final MessageDigest digest;
        long count;

        RecordingOutput(FileOutputStream file, MessageDigest digest) {
            this.file = file;
            this.digest = digest;
        }

        @Override public void write(int value) throws IOException {
            file.write(value);
            digest.update((byte) value);
            count = Math.addExact(count, 1);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            file.write(bytes, offset, length);
            digest.update(bytes, offset, length);
            count = Math.addExact(count, length);
        }

        @Override public void flush() throws IOException { file.flush(); }
        @Override public void close() throws IOException { flush(); }
    }
}
