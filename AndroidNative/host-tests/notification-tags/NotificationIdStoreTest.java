package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Actual filesystem/JVM processes, independent of Android protocol substitutes. */
public class NotificationIdStoreTest {
    private static final String A = "00000000-0000-4000-8000-000000000001";
    private static final String B = "00000000-0000-4000-8000-000000000002";
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(Action action, String message) throws Exception {
        boolean caught = false;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { caught = true; }
        check(caught, message);
    }
    private static Process worker(Path root, String owner, Path result) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-ea", "-cp", System.getProperty("java.class.path"), NotificationIdStoreTest.class.getName(),
                "worker", root.toString(), owner, result.toString()).redirectErrorStream(true)
                .redirectOutput(result.resolveSibling(result.getFileName() + ".log").toFile()).start();
    }
    private static void sign(byte[] bytes) throws Exception {
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(Arrays.copyOf(bytes, bytes.length - 32));
        System.arraycopy(checksum, 0, bytes, bytes.length - 32, 32);
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("worker")) {
            NotificationIdStore store = new NotificationIdStore(Path.of(args[1]));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) ids.add(Integer.toString(store.getOrCreate(args[2], i, id -> false)));
            Files.write(Path.of(args[3]), ids);
            return;
        }
        Path root = Path.of(args[0]); Files.createDirectories(root);
        Path live = root.resolve("live");
        NotificationIdStore store = new NotificationIdStore(live);
        byte[] initial = Files.readAllBytes(live.resolve("notification-ids.bin"));
        check(store.find(A, 9) == 0, "find allocated an ID");
        check(Arrays.equals(initial, Files.readAllBytes(live.resolve("notification-ids.bin"))), "read mutated registry");
        int a = store.getOrCreate(A, 7, id -> id == 0x40000000);
        check(a == 0x40000001, "occupied platform ID not skipped");
        int b = store.getOrCreate(B, 7, id -> false);
        check(a != b && a > 0 && b > 0, "cross-owner collision");
        check(store.getOrCreate(A, 7, id -> true) == a, "existing notification was reallocated");
        check(store.owns(A, a) && !store.owns(B, a) && !store.owns(A, 7), "ownership mismatch");
        check(new NotificationIdStore(live).find(A, 7) == a, "reopen lost mapping");
        Set<Integer> special = new HashSet<>();
        for (int logical : new int[]{Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
            special.add(store.getOrCreate(A, logical, id -> false));
        }
        check(special.size() == 5 && !special.contains(a) && !special.contains(b), "logical integer domain collision");
        fails(() -> store.find("not-a-uuid", 1), "invalid owner accepted");
        fails(() -> store.getOrCreate(A, 88, null), "missing occupied predicate accepted");
        byte[] beforeBlocked = Files.readAllBytes(live.resolve("notification-ids.bin"));
        fails(() -> store.getOrCreate(A, 89, id -> true), "allocation never bounded");
        check(Arrays.equals(beforeBlocked, Files.readAllBytes(live.resolve("notification-ids.bin"))), "failed allocation changed data");
        // Three independent JVMs contend on a shared file; two request identical owner/logical pairs.
        Path concurrent = root.resolve("concurrent");
        Process p1 = worker(concurrent, A, root.resolve("a1.txt"));
        Process p2 = worker(concurrent, A, root.resolve("a2.txt"));
        Process p3 = worker(concurrent, B, root.resolve("b.txt"));
        for (Process p : new Process[]{p1, p2, p3}) {
            check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "concurrent allocator worker failed");
        }
        List<String> a1 = Files.readAllLines(root.resolve("a1.txt"));
        List<String> a2 = Files.readAllLines(root.resolve("a2.txt"));
        List<String> bs = Files.readAllLines(root.resolve("b.txt"));
        check(a1.equals(a2), "same logical keys diverged across processes");
        Set<String> all = new HashSet<>(a1); all.addAll(bs);
        check(all.size() == 40, "separate processes allocated duplicate IDs");
        check(new NotificationIdStore(concurrent).find(B, 19) == Integer.parseInt(bs.get(19)), "cross-process mapping did not persist");
        // Corruption is rejected without rewriting or falling back to raw IDs.
        Path corrupt = root.resolve("corrupt");
        NotificationIdStore bad = new NotificationIdStore(corrupt);
        bad.getOrCreate(A, 1, id -> false); bad.getOrCreate(B, 1, id -> false);
        Path file = corrupt.resolve("notification-ids.bin"); byte[] good = Files.readAllBytes(file);
        for (int kind = 0; kind < 6; kind++) {
            byte[] broken = good.clone();
            if (kind == 0) broken[20] ^= 1;
            if (kind == 1) { ByteBuffer.wrap(broken).putInt(4, 99); sign(broken); }
            if (kind == 2) { ByteBuffer.wrap(broken).putInt(12, 100_001); sign(broken); }
            if (kind == 3) { ByteBuffer.wrap(broken).putInt(8, 1); sign(broken); }
            if (kind == 4) { System.arraycopy(broken, 16, broken, 40, 24); sign(broken); }
            if (kind == 5) broken = Arrays.copyOf(broken, 10);
            Files.write(file, broken);
            fails(() -> bad.find(A, 1), "malformed registry accepted");
            fails(() -> bad.getOrCreate(A, 2, id -> false), "malformed registry was reset");
            check(Arrays.equals(broken, Files.readAllBytes(file)), "corrupt file overwritten");
        }
        Files.write(file, good); Files.delete(file);
        fails(() -> new NotificationIdStore(corrupt), "missing committed registry was reset");
        check(!Files.exists(file), "missing registry recreated with reused IDs");
        Path exhausted = root.resolve("exhausted"); NotificationIdStore full = new NotificationIdStore(exhausted);
        byte[] last = Files.readAllBytes(exhausted.resolve("notification-ids.bin"));
        ByteBuffer.wrap(last).putInt(8, Integer.MAX_VALUE); sign(last);
        Files.write(exhausted.resolve("notification-ids.bin"), last);
        fails(() -> full.getOrCreate(A, 1, id -> false), "exhausted ID counter wrapped");
        check(Arrays.equals(last, Files.readAllBytes(exhausted.resolve("notification-ids.bin"))), "exhaustion changed data");
        Path external = root.resolve("external"); Files.createDirectories(external);
        Path link = root.resolve("link"); Files.createSymbolicLink(link, external);
        fails(() -> new NotificationIdStore(link), "linked registry root accepted");
        Path linkedData = root.resolve("linked-data"); new NotificationIdStore(linkedData);
        Path target = external.resolve("sentinel"); Files.writeString(target, "keep");
        Files.delete(linkedData.resolve("notification-ids.bin"));
        Files.createSymbolicLink(linkedData.resolve("notification-ids.bin"), target);
        fails(() -> new NotificationIdStore(linkedData), "linked registry file accepted");
        check(Files.readString(target).equals("keep"), "linked external file modified");
        // Android app mount namespaces alias /data/user/0 to /data/data. Trust only
        // that framework root; links below it must still fail closed.
        Path app = root.resolve("application"); Files.createDirectories(app);
        Path appAlias = root.resolve("android-user-0"); Files.createSymbolicLink(appAlias, app);
        Path canonicalApp = appAlias.toRealPath();
        NotificationIdStore appStore = new NotificationIdStore(canonicalApp.resolve("landu-control/notifications"));
        int appId = appStore.getOrCreate(A, 1, id -> false);
        check(new NotificationIdStore(app.resolve("landu-control/notifications")).find(A, 1) == appId,
                "Android app-root alias did not share the stable notification registry");
        Path unsafeApp = root.resolve("unsafe-application"); Files.createDirectories(unsafeApp);
        Files.createSymbolicLink(unsafeApp.resolve("landu-control"), app.resolve("landu-control"));
        fails(() -> new NotificationIdStore(unsafeApp.toRealPath().resolve("landu-control/notifications")),
                "linked control child accepted");
        Path unsafeNotifications = root.resolve("unsafe-notifications");
        Files.createDirectories(unsafeNotifications.resolve("landu-control"));
        Files.createSymbolicLink(unsafeNotifications.resolve("landu-control/notifications"),
                app.resolve("landu-control/notifications"));
        fails(() -> new NotificationIdStore(unsafeNotifications.toRealPath().resolve("landu-control/notifications")),
                "linked notification child accepted");
        check(new NotificationIdStore(canonicalApp.resolve("landu-control/notifications")).find(A, 1) == appId,
                "rejected child link changed the existing notification registry");
        System.out.println("{\"passed\":true,\"assertions\":" + checks + ",\"childProcesses\":3}");
    }
}
