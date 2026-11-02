package com.landu.browser.nativecore;

/** Local account fields. They are never part of a configuration export or command line. */
public final class AccountCredentials {
    public final String username;
    public final String password;
    public final String totpSecret;

    public AccountCredentials(String username, String password, String totpSecret) {
        this.username = checked(username, 200, "账号");
        this.password = checked(password, 500, "密码");
        this.totpSecret = checked(totpSecret, 2048, "2FA 密钥").trim();
        if (!this.totpSecret.isEmpty()) Totp.parse(this.totpSecret);
    }

    public static AccountCredentials empty() { return new AccountCredentials("", "", ""); }
    public boolean isEmpty() { return username.isEmpty() && password.isEmpty() && totpSecret.isEmpty(); }

    private static String checked(String value, int limit, String label) {
        if (value == null || value.length() > limit) throw new IllegalArgumentException(label + "长度无效");
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == 0 || value.charAt(i) == '\r' || value.charAt(i) == '\n') {
                throw new IllegalArgumentException(label + "不能包含换行或空字符");
            }
        }
        return value;
    }

    @Override public String toString() { return "AccountCredentials[redacted]"; }
}
