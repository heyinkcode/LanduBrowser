package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

public final class EnvironmentCatalogTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).resolve("catalog-" + UUID.randomUUID());
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        check(catalog.read().environments.isEmpty(), "Fresh install must be empty");
        EnvironmentDefinition first = EnvironmentDefinition.create("日常", "", "账号一");
        EnvironmentCatalog.Snapshot saved = catalog.save(0, first);
        check(saved.revision == 1, "Save must advance revision");
        EnvironmentCatalog restarted = new EnvironmentCatalog(root);
        EnvironmentDefinition recovered = restarted.read().find(first.identity);
        check(recovered.name.equals("日常"), "Name must survive process restart");
        check(recovered.startPage.equals("about:blank"), "Empty startup URL must be local blank");
        check(recovered.notes.equals("账号一"), "Notes must survive process restart");
        EnvironmentDefinition renamed = recovered.edit("工作", "https://example.com/中文", "账号一");
        saved = restarted.save(saved.revision, renamed);
        check(saved.find(first.identity).identity.equals(first.identity), "Rename must not change isolated directory");
        check(saved.find(first.identity).createdAt == first.createdAt, "Rename must preserve creation time");
        check(saved.find(first.identity).startPage.contains("%"), "Unicode URL path must be encoded");
        rejects(IOException.class, () -> catalog.save(1, first));
        check(catalog.read().find(first.identity).name.equals("工作"), "Stale editor must not overwrite new name");
        EnvironmentDefinition second = EnvironmentDefinition.create("日常", "https://example.com", "");
        saved = catalog.save(2, second);
        check(saved.environments.size() == 2, "Duplicate labels must remain separate identities");
        check(!first.identity.equals(second.identity), "New environment must get a new identity");
        final EnvironmentCatalog.Snapshot readOnly = saved;
        rejects(UnsupportedOperationException.class, () -> readOnly.environments.clear());
        for (String invalid : new String[] {"javascript:alert(1)", "file:///etc/passwd", "intent://test",
                "data:text/html,test", "https://user:pass@example.com", "http://example.com:70000",
                "http://example.com:0", "https://", "example.com", "https://example.com/\nheader"}) {
            rejects(IllegalArgumentException.class, () -> EnvironmentDefinition.validateStartPage(invalid));
        }
        rejects(IllegalArgumentException.class, () -> EnvironmentDefinition.create(" ", "", ""));
        rejects(IllegalArgumentException.class, () -> EnvironmentDefinition.create("a".repeat(81), "", ""));
        Path file = root.resolve("environments.bin");
        byte[] valid = Files.readAllBytes(file);
        byte[] extra = Arrays.copyOf(valid, valid.length + 1);
        byte[] badMagic = valid.clone();
        badMagic[0] = 0;
        for (byte[] corrupt : new byte[][] {new byte[] {1}, badMagic, extra}) {
            Files.write(file, corrupt);
            rejects(IOException.class, catalog::read);
            rejects(IOException.class, () -> catalog.save(3, first));
            check(Arrays.equals(corrupt, Files.readAllBytes(file)), "Failed read/save must preserve damaged file");
        }
        Files.write(file, valid);
        check(catalog.read().environments.size() == 2, "Restored snapshot must recover both environments");
        String report = "{\"suite\":\"native-environment-catalog\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidDeviceTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> type, Action action) throws Exception {
        assertions++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Unexpected failure", error);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private interface Action { void run() throws Exception; }
}
