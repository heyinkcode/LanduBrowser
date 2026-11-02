package com.landu.browser.nativecore;

import java.net.URI;

/** Process-fixed credentials are released only to a native proxy challenge at the configured endpoint. */
public final class ProxyAuthentication {
    private final URI endpoint;
    private final ProxyCredentials credentials;
    private final Object lock = new Object();
    private boolean answered;

    public ProxyAuthentication(EnvironmentNetworkSettings settings, ProxyCredentials credentials) {
        if (settings == null || !settings.requiresAuthentication || credentials == null) {
            throw new IllegalArgumentException("Missing authenticated proxy configuration");
        }
        endpoint = URI.create(settings.proxyUri);
        this.credentials = credentials;
    }

    public ProxyCredentials answer(boolean isProxy, String challengerScheme,
            String challengerHost, int challengerPort, String authenticationScheme) {
        synchronized (lock) {
            if (!isProxy || "socks5".equals(endpoint.getScheme()) || !endpoint.getScheme().equalsIgnoreCase(challengerScheme)
                    || !sameHost(endpoint.getHost(), challengerHost)
                    || endpoint.getPort() != challengerPort
                    || !("basic".equalsIgnoreCase(authenticationScheme)
                            || "digest".equalsIgnoreCase(authenticationScheme))) return null;
            // A wrong saved password must not create an automatic retry loop. Chromium's
            // normal prompt remains available after the first automatic response.
            if (answered) return null;
            answered = true;
            return credentials;
        }
    }

    /** SOCKS authenticates each TCP connection, unlike the once-only HTTP challenge. */
    public ProxyCredentials socks5(String host, int port) {
        return "socks5".equals(endpoint.getScheme()) && sameHost(endpoint.getHost(), host)
                && endpoint.getPort() == port ? credentials : null;
    }

    private static boolean sameHost(String configured, String challenged) {
        String first = host(configured);
        String second = host(challenged);
        if (first.equalsIgnoreCase(second)) return true;
        // Only colon-containing numeric literals enter this parser. Hostnames are
        // compared above without resolving DNS aliases to a different credential scope.
        if (!first.contains(":") || !second.contains(":")
                || !first.matches("[0-9a-fA-F:.]+") || !second.matches("[0-9a-fA-F:.]+")) return false;
        byte[] firstBytes = ipv6Bytes(first);
        byte[] secondBytes = ipv6Bytes(second);
        return firstBytes != null && secondBytes != null && java.util.Arrays.equals(firstBytes, secondBytes);
    }

    /** No resolver API: malformed numeric input must never fall through to a DNS lookup. */
    private static byte[] ipv6Bytes(String value) {
        if (value.length() > 45) return null;
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) return null;
        String left = compression < 0 ? value : value.substring(0, compression);
        String right = compression < 0 ? "" : value.substring(compression + 2);
        if (compression >= 0 && left.indexOf('.') >= 0) return null;
        int[] prefix = new int[8];
        int[] suffix = new int[8];
        int prefixCount = words(left, prefix);
        int suffixCount = words(right, suffix);
        if (prefixCount < 0 || suffixCount < 0) return null;
        int count = prefixCount + suffixCount;
        if ((compression < 0 && count != 8) || (compression >= 0 && count >= 8)) return null;
        byte[] result = new byte[16];
        for (int i = 0; i < prefixCount; ++i) {
            result[2 * i] = (byte) (prefix[i] >> 8);
            result[2 * i + 1] = (byte) prefix[i];
        }
        for (int i = 0; i < suffixCount; ++i) {
            int offset = 2 * (8 - suffixCount + i);
            result[offset] = (byte) (suffix[i] >> 8);
            result[offset + 1] = (byte) suffix[i];
        }
        return result;
    }

    private static int words(String value, int[] output) {
        if (value.isEmpty()) return 0;
        String[] parts = value.split(":", -1);
        int count = 0;
        for (int i = 0; i < parts.length; ++i) {
            String part = parts[i];
            if (part.indexOf('.') >= 0) {
                if (i != parts.length - 1 || count > 6) return -1;
                String[] octets = part.split("\\.", -1);
                if (octets.length != 4) return -1;
                int[] bytes = new int[4];
                for (int j = 0; j < bytes.length; ++j) {
                    if (!octets[j].matches("0|[1-9][0-9]{0,2}")) return -1;
                    bytes[j] = Integer.parseInt(octets[j]);
                    if (bytes[j] > 255) return -1;
                }
                output[count++] = (bytes[0] << 8) | bytes[1];
                output[count++] = (bytes[2] << 8) | bytes[3];
            } else {
                if (!part.matches("[0-9a-fA-F]{1,4}") || count >= output.length) return -1;
                output[count++] = Integer.parseInt(part, 16);
            }
        }
        return count;
    }

    private static String host(String value) {
        if (value == null) return "";
        return value.startsWith("[") && value.endsWith("]")
                ? value.substring(1, value.length() - 1) : value;
    }
}
