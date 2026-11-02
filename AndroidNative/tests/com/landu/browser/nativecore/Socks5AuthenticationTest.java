package com.landu.browser.nativecore;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises real loopback wire exchanges with synthetic credentials only. */
public final class Socks5AuthenticationTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        addresses();
        credentials();
        for (int mode = 0; mode < 6; ++mode) exchange(mode);
        System.out.println("{\"suite\":\"socks5-auth-wire-and-input\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}");
    }

    private static void addresses() {
        for (String protocol : new String[]{"http", "https", "socks5"}) {
            check(EnvironmentNetworkSettings.addressInput("192.0.2.1:5224", protocol)
                    .equals(protocol + "://192.0.2.1:5224"), "Explicit protocol with bare endpoint");
            check(EnvironmentNetworkSettings.addressInput("//proxy.test:1080", protocol)
                    .equals(protocol + "://proxy.test:1080"), "Scheme-relative input");
            check(EnvironmentNetworkSettings.addressInput("[2001:db8::1]:1080", protocol)
                    .equals(protocol + "://[2001:db8::1]:1080"), "IPv6 endpoint");
            check(EnvironmentNetworkSettings.addressInput("HTTPS://Proxy.Test:443/", protocol)
                    .equals("https://proxy.test:443"), "Full pasted URI retains its explicit protocol");
            check(EnvironmentNetworkSettings.addressInput("", protocol).isEmpty(), "Empty endpoint keeps system network");
        }
        for (String bad : new String[]{"u:p@proxy.test:80", "proxy.test:80/path", "proxy.test:80?x=1",
                "proxy.test:80\n", "proxy.test:80#frag", "proxy.test:0", "proxy.test:65536", "proxy.test", "//", "ftp://proxy.test:80"}) {
            rejects(() -> EnvironmentNetworkSettings.addressInput(bad, "socks5"));
        }
        rejects(() -> EnvironmentNetworkSettings.addressInput("proxy.test:80", "ftp"));
        rejects(() -> new EnvironmentNetworkSettings("proxy.test:80", false));
    }

    private static void credentials() {
        EnvironmentNetworkSettings settings = new EnvironmentNetworkSettings("socks5://proxy.test:1080", true, true, "");
        new ProxyCredentials("u".repeat(255), "p".repeat(255)).validateFor(settings);
        check(true, "RFC byte length upper boundary");
        new ProxyCredentials("中".repeat(85), "文".repeat(85)).validateFor(settings);
        check(true, "UTF-8 limits measured in bytes");
        rejects(() -> new ProxyCredentials("中".repeat(86), "pass").validateFor(settings));
        rejects(() -> new ProxyCredentials("user", "文".repeat(86)).validateFor(settings));
        rejects(() -> new ProxyCredentials("user", "").validateFor(settings));
        ProxyCredentials secret = new ProxyCredentials("user", "pass");
        ProxyAuthentication scope = new ProxyAuthentication(settings, secret);
        check(scope.socks5("PROXY.test", 1080) == secret, "Exact proxy endpoint");
        check(scope.socks5("proxy.test", 1080) == secret, "Every independent TCP connection authenticates");
        check(scope.socks5("other.test", 1080) == null, "No other endpoint credential");
        check(scope.socks5("proxy.test", 1081) == null, "No other port credential");
        check(new ProxyAuthentication(new EnvironmentNetworkSettings("http://proxy.test:1080", true, true, ""), secret)
                .socks5("proxy.test", 1080) == null, "HTTP secret never used for SOCKS");
    }

    // 0=success fragmented replies, 1=wrong password, 2=anonymous downgrade,
    // 3=unknown method, 4=EOF during auth, 5=invalid auth version.
    private static void exchange(int mode) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean connectSeen = new AtomicBoolean();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<?> fixture = worker.submit(() -> {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(2500);
                    DataInputStream in = new DataInputStream(peer.getInputStream());
                    OutputStream out = peer.getOutputStream();
                    require(in.readUnsignedByte() == 5 && in.readUnsignedByte() == 1 && in.readUnsignedByte() == 2);
                    out.write(5); out.flush();
                    out.write(mode == 2 ? 0 : mode == 3 ? 255 : 2); out.flush();
                    if (mode == 2 || mode == 3) { require(in.read() == -1); return; }
                    require(in.readUnsignedByte() == 1);
                    byte[] user = new byte[in.readUnsignedByte()]; in.readFully(user);
                    byte[] password = new byte[in.readUnsignedByte()]; in.readFully(password);
                    require(new String(user, StandardCharsets.UTF_8).equals("合成用户"));
                    require(new String(password, StandardCharsets.UTF_8).equals("test-secret-only"));
                    if (mode == 4) return;
                    out.write(mode == 5 ? 5 : 1); out.flush();
                    out.write(mode == 1 ? 1 : 0); out.flush();
                    if (mode != 0) { require(in.read() == -1); return; }
                    require(in.readUnsignedByte() == 5 && in.readUnsignedByte() == 1 && in.readUnsignedByte() == 0 && in.readUnsignedByte() == 3);
                    byte[] host = new byte[in.readUnsignedByte()]; in.readFully(host);
                    require(new String(host, StandardCharsets.US_ASCII).equals("unresolved-target.invalid") && in.readUnsignedShort() == 443);
                    connectSeen.set(true);
                    for (byte value : new byte[]{5,0,0,1,127,0,0,1,0,0}) { out.write(value); out.flush(); }
                } catch (IOException error) { throw new UncheckedIOException(error); }
            });
            boolean success = false;
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.setSoTimeout(2500);
                EnvironmentExitTransport.socksTunnel(client, "unresolved-target.invalid", 443,
                        new ProxyCredentials("合成用户", "test-secret-only"));
                success = true;
            } catch (IOException expected) { if (mode == 0) throw expected; }
            fixture.get(5, TimeUnit.SECONDS);
            check(success == (mode == 0), "Only correct RFC 1929 handshake succeeds: " + mode);
            check(connectSeen.get() == (mode == 0), "No destination request after auth failure: " + mode);
        } finally { worker.shutdownNow(); }
    }

    private static void require(boolean value) { if (!value) throw new AssertionError("Invalid fixture exchange"); }
    private static void check(boolean value, String message) { ++assertions; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        ++assertions;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }
}
