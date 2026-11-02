package com.landu.browser.nativecore;

import java.util.UUID;

/** Display metadata never determines an environment's private directory or fingerprint seed. */
public final class EnvironmentOrganization {
    public final int number;
    public final String groupId;
    public final String tags;

    public EnvironmentOrganization(int number, String groupId, String tags) {
        if (number < 0 || number > 1_000_000) throw new IllegalArgumentException("环境编号无效");
        this.number = number;
        if (groupId == null) throw new IllegalArgumentException("分组标识不能为空");
        if (!groupId.isEmpty() && !UUID.fromString(groupId).toString().equals(groupId)) {
            throw new IllegalArgumentException("分组标识无效");
        }
        this.groupId = groupId;
        this.tags = text(tags, 160, "标签");
    }

    public static EnvironmentOrganization unassigned() { return new EnvironmentOrganization(0, "", ""); }
    public EnvironmentOrganization withNumber(int value) { return new EnvironmentOrganization(value, groupId, tags); }
    public EnvironmentOrganization withGroup(String value) { return new EnvironmentOrganization(number, value, tags); }

    static String text(String value, int max, String label) {
        if (value == null || value.length() > max) throw new IllegalArgumentException(label + "长度不正确");
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException(label + "不能包含控制字符");
        }
        return value.trim();
    }
}
