package com.landu.browser.nativecore;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Immutable per-environment configuration applied before Chromium starts. */
public final class EnvironmentNetworkSettings {
    private static final String[] OWNED_SWITCHES = {
            "proxy-server", "no-proxy-server", "proxy-pac-url", "proxy-auto-detect",
            "proxy-bypass-list", "webrtc-ip-handling-policy", "force-webrtc-ip-handling-policy"
    };
    public final String proxyUri;
    public final boolean restrictWebRtcUdp;
    public final boolean requiresAuthentication;
    public final String credentialReference;

    public EnvironmentNetworkSettings(String proxyUri, boolean restrictWebRtcUdp) {
        this(proxyUri, restrictWebRtcUdp, false, "");
    }

    public EnvironmentNetworkSettings(String proxyUri, boolean restrictWebRtcUdp,
            boolean requiresAuthentication, String credentialReference) {
        this.proxyUri = validateProxy(proxyUri);
        this.restrictWebRtcUdp = restrictWebRtcUdp;
        if (credentialReference == null || (!credentialReference.isEmpty()
                && !validCredentialReference(credentialReference))) throw new IllegalArgumentException("代理凭据引用无效");
        if ((!requiresAuthentication && !credentialReference.isEmpty())
                || (requiresAuthentication && !(this.proxyUri.startsWith("http://")
                        || this.proxyUri.startsWith("https://") || this.proxyUri.startsWith("socks5://")))) {
            throw new IllegalArgumentException("使用账号密码时请先填写 HTTP、HTTPS 或 SOCKS5 代理地址");
        }
        this.requiresAuthentication = requiresAuthentication;
        this.credentialReference = credentialReference;
    }

    static boolean validCredentialReference(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    public EnvironmentNetworkSettings withoutLocalCredential() {
        return new EnvironmentNetworkSettings(proxyUri, restrictWebRtcUdp, requiresAuthentication, "");
    }

    public static EnvironmentNetworkSettings system() {
        return new EnvironmentNetworkSettings("", false);
    }

    public void applyTo(BrowserCommandLineTarget target) {
        for (String name : OWNED_SWITCHES) target.remove(name);
        if (!proxyUri.isEmpty()) target.set("proxy-server", proxyUri);
        // Chrome consumes this preference switch; the similarly named force-*
        // switch is consumed by content_shell, not the Chrome preference store.
        target.set("webrtc-ip-handling-policy", restrictWebRtcUdp ? "disable_non_proxied_udp" : "default");
    }

    public String summary() { return proxyUri.isEmpty() ? "跟随手机网络（支持系统 VPN）" : proxyUri; }

    /** UI input only; stored/imported proxy URIs still use the strict constructor. */
    public static String addressInput(String input, String protocol) {
        if (!("http".equals(protocol) || "https".equals(protocol) || "socks5".equals(protocol))) throw invalid();
        if (input == null) throw invalid();
        // Validate controls before trim: pasted newlines must not become silent input changes.
        for (int i = 0; i < input.length(); ++i) {
            if (input.charAt(i) < 0x20 || input.charAt(i) == 0x7f) throw invalid();
        }
        String value = input.trim();
        if (value.isEmpty()) return "";
        if (!value.contains("://")) value = protocol + "://" + (value.startsWith("//") ? value.substring(2) : value);
        return validateProxy(value);
    }

    private static String validateProxy(String input) {
        if (input == null || input.length() > 512) throw invalid();
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) < 0x20 || input.charAt(i) == 0x7f) throw invalid();
        }
        String value = input.trim();
        if (value.isEmpty()) return "";
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) <= 0x20 || value.charAt(i) == 0x7f) throw invalid();
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https") || scheme.equals("socks5"))
                    || uri.getRawAuthority() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) throw invalid();
            String authority = uri.getRawAuthority();
            if (authority.contains("@") || authority.contains("%")) throw invalid();
            String host;
            String portText;
            if (authority.startsWith("[")) {
                int end = authority.indexOf(']');
                if (end < 0 || end + 1 >= authority.length() || authority.charAt(end + 1) != ':') throw invalid();
                host = authority.substring(0, end + 1).toLowerCase(Locale.ROOT);
                if (!host.matches("\\[[0-9a-f:.]+\\]") || uri.getHost() == null) throw invalid();
                portText = authority.substring(end + 2);
            } else {
                int colon = authority.lastIndexOf(':');
                if (colon <= 0 || authority.indexOf(':') != colon) throw invalid();
                host = normalizeHost(authority.substring(0, colon));
                portText = authority.substring(colon + 1);
            }
            if (!portText.matches("[0-9]{1,5}")) throw invalid();
            int port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw invalid();
            return scheme + "://" + host + ":" + port;
        } catch (URISyntaxException | NumberFormatException error) {
            throw invalid();
        }
    }

    private static String normalizeHost(String input) {
        final String host;
        try { host = IDN.toASCII(input, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
        catch (IllegalArgumentException error) { throw invalid(); }
        if (host.isEmpty() || host.length() > 253 || host.equals(".")) throw invalid();
        if (host.matches("[0-9.]+")) {
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) throw invalid();
            StringBuilder result = new StringBuilder();
            for (String octet : octets) {
                if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) throw invalid();
                if (result.length() > 0) result.append('.');
                result.append(octet);
            }
            return result.toString();
        }
        return host;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("代理须为 http://、https:// 或 socks5://主机:端口，不能包含账号密码、路径或附加参数");
    }
}
