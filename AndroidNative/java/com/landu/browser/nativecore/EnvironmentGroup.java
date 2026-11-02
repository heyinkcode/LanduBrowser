package com.landu.browser.nativecore;

import java.util.UUID;
import java.util.Locale;

public final class EnvironmentGroup {
    public final String id;
    public final String name;

    public EnvironmentGroup(String id, String name) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) {
            throw new IllegalArgumentException("分组标识无效");
        }
        this.id = id;
        this.name = EnvironmentOrganization.text(name, 60, "分组名称");
        if (this.name.isEmpty()) throw new IllegalArgumentException("请输入分组名称");
    }

    public static EnvironmentGroup create(String name) { return new EnvironmentGroup(UUID.randomUUID().toString(), name); }
    public EnvironmentGroup rename(String name) { return new EnvironmentGroup(id, name); }
    String nameKey() { return name.toLowerCase(Locale.ROOT); }
}
