package com.landu.browser.nativecore;

/** A secret value, never serialized into the environment catalog or command line. */
public final class ProxyCredentials {
    public final String username;
    public final String password;

    public ProxyCredentials(String username, String password) {
        if (username == null || username.isEmpty() || username.length() > 255
                || username.indexOf(':') >= 0 || password == null || password.length() > 1024
                || hasControl(username) || hasControl(password)) {
            throw new IllegalArgumentException("代理账号或密码格式无效");
        }
        this.username = username;
        this.password = password;
    }

    public void validateFor(EnvironmentNetworkSettings network) {
        if (!network.requiresAuthentication) throw new IllegalArgumentException("此代理未启用账号密码");
        if (network.proxyUri.startsWith("socks5://")) {
            int userBytes = username.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            int passwordBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (userBytes < 1 || userBytes > 255 || passwordBytes < 1 || passwordBytes > 255) {
                throw new IllegalArgumentException("SOCKS5 账号和密码各需 1–255 个 UTF-8 字节");
            }
        }
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); ++i) {
            if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7f) return true;
        }
        return false;
    }

    @Override public String toString() { return "ProxyCredentials[redacted]"; }
}
