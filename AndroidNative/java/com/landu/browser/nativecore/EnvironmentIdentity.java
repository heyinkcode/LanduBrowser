package com.landu.browser.nativecore;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class EnvironmentIdentity {
    private static final Pattern ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private final String id;

    private EnvironmentIdentity(String id) {
        this.id = id;
    }

    public static EnvironmentIdentity parse(String id) {
        if (id == null || !ID.matcher(id).matches()
                || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("Invalid environment ID");
        }
        return new EnvironmentIdentity(id);
    }

    public String id() {
        return id;
    }

    public String directoryName() {
        return "landu_" + id.replace("-", "");
    }

    public Path resolveUnder(Path root) {
        return root.resolve(directoryName());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EnvironmentIdentity
                && id.equals(((EnvironmentIdentity) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
