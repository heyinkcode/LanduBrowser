package com.landu.browser.nativecore;

public final class ExternalNavigation {
    private ExternalNavigation() {}

    public static String webAddress(String value) {
        String normalized = EnvironmentDefinition.validateStartPage(value);
        if (normalized.equals("about:blank")) {
            throw new IllegalArgumentException("请选择完整的 http:// 或 https:// 网址");
        }
        return normalized;
    }
}
