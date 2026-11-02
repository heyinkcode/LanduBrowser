package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class ProxyCredentialsTest {
    private static int assertions;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Action action) throws Exception {
        assertions++;
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe credential use accepted");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]).resolve("proxy-credentials-" + UUID.randomUUID()));
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        ProxyCredentialStore store = new ProxyCredentialStore(root, create -> key);
        EnvironmentDefinition a = EnvironmentDefinition.create("Proxy QA A", "", "");
        EnvironmentDefinition b = EnvironmentDefinition.create("Proxy QA B", "", "");
        EnvironmentNetworkSettings proxy = new EnvironmentNetworkSettings("https://proxy.test:443", true, true, "");
        ProxyCredentials secret = new ProxyCredentials("test-proxy-user", "test-proxy-password-ONLY-TEST");
        String reference = store.put(a.identity, proxy, secret);
        EnvironmentNetworkSettings configured = new EnvironmentNetworkSettings(proxy.proxyUri, true, true, reference);
        Path record = root.resolve("proxy-credentials").resolve(reference + ".bin");
        byte[] encrypted = Files.readAllBytes(record);
        String raw = new String(encrypted, StandardCharsets.ISO_8859_1);
        check(!raw.contains(secret.username) && !raw.contains(secret.password), "No plaintext secret in file");
        ProxyCredentials restored = store.read(a.identity, configured);
        check(secret.username.equals(restored.username) && secret.password.equals(restored.password), "Encrypted readback");
        check(!secret.toString().contains(secret.username) && !secret.toString().contains(secret.password), "No diagnostic leak");
        rejects(() -> store.read(b.identity, configured));
        rejects(() -> store.read(a.identity, new EnvironmentNetworkSettings("http://proxy.test:443", true, true, reference)));
        rejects(() -> store.read(a.identity, new EnvironmentNetworkSettings("https://other.test:443", true, true, reference)));
        rejects(() -> store.read(a.identity, configured.withoutLocalCredential()));
        rejects(() -> new ProxyCredentialStore(root, create -> generator.generateKey()).read(a.identity, configured));
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(record, encrypted);
        rejects(() -> store.read(a.identity, configured));
        Files.write(record, Arrays.copyOf(encrypted, 10));
        rejects(() -> store.read(a.identity, configured));
        Files.delete(record);
        Files.createSymbolicLink(record, root.resolve("not-a-secret"));
        rejects(() -> store.read(a.identity, configured));
        rejects(() -> new EnvironmentNetworkSettings(proxy.proxyUri, false, true, "../secret"));
        EnvironmentNetworkSettings socks = new EnvironmentNetworkSettings("socks5://proxy.test:1080", false, true, "");
        secret.validateFor(socks);
        String socksReference = store.put(a.identity, socks, secret);
        EnvironmentNetworkSettings savedSocks = new EnvironmentNetworkSettings(socks.proxyUri, false, true, socksReference);
        check(store.read(a.identity, savedSocks).password.equals(secret.password), "SOCKS5 encrypted credential readback");
        rejects(() -> store.read(b.identity, savedSocks));
        rejects(() -> store.read(a.identity, new EnvironmentNetworkSettings("socks5://other.test:1080", false, true, socksReference)));
        rejects(() -> new EnvironmentNetworkSettings("", false, true, ""));
        rejects(() -> new ProxyCredentials("user:extra", "password"));
        rejects(() -> new ProxyCredentials("user", "secret\r\nInjected"));
        check(new ProxyCredentials("user", "").password.isEmpty(), "Empty proxy password is supported");
        authentication(configured, secret);
        concurrentAuthentication(configured, secret);
        numericEndpoints(secret);
        catalog(root.resolve("catalog"), root.resolve("imported"), a, configured);
        String report = "{\"suite\":\"proxy-credentials\",\"assertions\":" + assertions
                + ",\"passed\":true,\"androidKeystoreTested\":false,\"browserProxyAuthenticationTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void authentication(EnvironmentNetworkSettings network, ProxyCredentials secret) {
        ProxyAuthentication authentication = new ProxyAuthentication(network, secret);
        check(authentication.answer(false, "https", "proxy.test", 443, "basic") == null, "Origin 401 is never answered");
        check(authentication.answer(true, "http", "proxy.test", 443, "basic") == null, "Endpoint scheme is bound");
        check(authentication.answer(true, "https", "proxy.test", 80, "basic") == null, "Endpoint port is bound");
        check(authentication.answer(true, "https", "evil.test", 443, "basic") == null, "Endpoint host is bound");
        check(authentication.answer(true, "https", "proxy.test", 443, "ntlm") == null, "Unsupported auth scheme stays native");
        check(authentication.answer(true, "https", "PROXY.test", 443, "Basic") == secret, "Exact proxy challenge gets credentials");
        check(authentication.answer(true, "https", "proxy.test", 443, "basic") == null, "Wrong credentials cannot loop forever");
        ProxyAuthentication digest = new ProxyAuthentication(network, secret);
        check(digest.answer(true, "https", "proxy.test", 443, "digest") == secret, "Digest uses the same exact endpoint boundary");
        ProxyAuthentication ipv6 = new ProxyAuthentication(new EnvironmentNetworkSettings("http://[::1]:8080", true, true, ""), secret);
        check(ipv6.answer(true, "http", "::1", 8080, "basic") == secret, "IPv6 bracket normalization");
        ProxyAuthentication expanded = new ProxyAuthentication(new EnvironmentNetworkSettings(
                "http://[2001:0db8:0:0:0:0:0:1]:8080", true, true, ""), secret);
        check(expanded.answer(true, "http", "[2001:db8::2]", 8080, "basic") == null,
                "Different numeric IPv6 endpoint never receives credentials");
        check(expanded.answer(true, "http", "[::::]", 8080, "basic") == null,
                "Malformed numeric host is rejected without consuming an attempt");
        check(expanded.answer(true, "http", "[2001:db8::1]", 8080, "basic") == secret,
                "Chromium canonical IPv6 challenge matches the configured numeric endpoint");
    }

    private static void concurrentAuthentication(EnvironmentNetworkSettings network, ProxyCredentials secret) throws Exception {
        ProxyAuthentication authentication = new ProxyAuthentication(network, secret);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 8; ++i) results.add(executor.submit(() -> {
                start.await();
                return authentication.answer(true, "https", "proxy.test", 443, "basic") != null;
            }));
            start.countDown();
            int answered = 0;
            for (java.util.concurrent.Future<Boolean> result : results) {
                if (result.get(5, java.util.concurrent.TimeUnit.SECONDS)) ++answered;
            }
            check(answered == 1, "Concurrent native challenges receive at most one automatic secret response");
        } finally { executor.shutdownNow(); }
    }

    private static void numericEndpoints(ProxyCredentials secret) {
        for (String[] pair : new String[][] {
                {"0:0:0:0:0:0:0:1", "::1"}, {"0:0:0:0:0:0:0:0", "::"},
                {"2001:0DB8:0:0:8:800:200C:417A", "2001:db8::8:800:200c:417a"},
                {"::ffff:192.0.2.128", "::ffff:c000:0280"},
                {"2001:db8:0:0:0:0:c000:0280", "2001:db8::192.0.2.128"},
        }) {
            ProxyAuthentication authentication = new ProxyAuthentication(new EnvironmentNetworkSettings(
                    "http://[" + pair[0] + "]:8080", true, true, ""), secret);
            check(authentication.answer(true, "http", pair[1], 8080, "basic") == secret, "Equivalent IPv6 numeric forms match");
        }
        for (String invalid : new String[] {"::::", ":1", "1:", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7",
                "2001::db8::1", "::ffff:256.1.2.3", "::ffff:01.2.3.4", "::ffff:1.2.3", "::ffff:1.2.3.4:0", "dns.example"}) {
            ProxyAuthentication authentication = new ProxyAuthentication(new EnvironmentNetworkSettings(
                    "http://[::1]:8080", true, true, ""), secret);
            check(authentication.answer(true, "http", invalid, 8080, "basic") == null, "Malformed or different numeric challenge cannot obtain credentials");
        }
    }

    private static void catalog(Path root, Path other, EnvironmentDefinition a, EnvironmentNetworkSettings network) throws Exception {
        EnvironmentCatalog catalog = new EnvironmentCatalog(root);
        EnvironmentCatalog.Snapshot saved = catalog.save(0, a.withNetwork(network));
        check(catalog.read().find(a.identity).network.credentialReference.equals(network.credentialReference), "Catalog keeps local reference");
        byte[] raw = Files.readAllBytes(root.resolve("environments.bin"));
        byte[] backup = catalog.exportConfiguration();
        check(Arrays.equals(raw, Files.readAllBytes(root.resolve("environments.bin"))), "Export leaves original catalog intact");
        EnvironmentDefinition exported = EnvironmentCatalog.decodeConfiguration(backup).find(a.identity);
        check(exported.network.requiresAuthentication && exported.network.credentialReference.isEmpty(), "Portable export requires reentry, never anonymous fallback");
        check(!new String(backup, StandardCharsets.ISO_8859_1).contains(network.credentialReference), "No local reference in portable backup");
        EnvironmentCatalog destination = new EnvironmentCatalog(other);
        destination.importConfiguration(0, saved, false);
        check(destination.read().find(a.identity).network.credentialReference.isEmpty(), "Import strips even a forged local reference");
        catalog.importConfiguration(saved.revision, saved, false);
        check(catalog.read().find(a.identity).network.credentialReference.equals(network.credentialReference), "Skipped own entry retains credential");
        catalog.importConfiguration(catalog.read().revision, saved, true);
        check(catalog.read().find(a.identity).network.requiresAuthentication
                && catalog.read().find(a.identity).network.credentialReference.isEmpty(), "Explicit overwrite cannot import local secret reference");
        Map<String, String> switches = new HashMap<>();
        network.applyTo(new BrowserCommandLineTarget() {
            public void remove(String name) { switches.remove(name); }
            public void set(String name, String value) { switches.put(name, value); }
        });
        check(!switches.toString().contains(network.credentialReference), "No credential reference or secret in command line");
    }
}
