package android.util;

import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Fault-injectable host protocol model, not the actual Android implementation. */
public final class AtomicFile {
    private final File base, pending, backup;
    private final boolean legacy;
    public boolean failStart, failCommit;
    public int starts, finishes, failures;

    public AtomicFile(File base) { this(base, Build.VERSION.SDK_INT < 30); }
    public AtomicFile(File base, boolean legacy) {
        this.base = base; this.legacy = legacy;
        pending = new File(base.getPath() + ".new"); backup = new File(base.getPath() + ".bak");
    }
    public File getBaseFile() { return base; }
    public FileInputStream openRead() throws IOException {
        if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!legacy && pending.exists() && base.exists()) Files.delete(pending.toPath());
        return new FileInputStream(base);
    }
    public FileOutputStream startWrite() throws IOException {
        starts++;
        if (failStart) throw new IOException("injected start failure");
        if (legacy && base.exists()) Files.move(base.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(base.toPath().toAbsolutePath().getParent());
        return new FileOutputStream(legacy ? base : pending);
    }
    public void finishWrite(FileOutputStream output) {
        finishes++;
        try {
            output.close();
            if (failCommit) return; // Platform finishWrite can log, then return normally.
            if (legacy) Files.deleteIfExists(backup.toPath());
            else Files.move(pending.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) { }
    }
    public void failWrite(FileOutputStream output) {
        failures++;
        try {
            output.close();
            if (legacy) {
                Files.deleteIfExists(base.toPath());
                if (backup.exists()) Files.move(backup.toPath(), base.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else Files.deleteIfExists(pending.toPath());
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
