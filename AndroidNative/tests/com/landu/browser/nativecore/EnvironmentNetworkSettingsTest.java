package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvironmentNetworkSettingsTest {
    private static final EnvironmentIdentity A = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000001");
    private static final EnvironmentIdentity B = EnvironmentIdentity.parse("10af0101-2000-4000-8000-000000000002");
    private static int assertions;

    public static void main(String[] args) throws Exception {
        validation();
        commandLine();
        Path root = Path.of(args[0]).resolve("network-settings-" + UUID.randomUUID());
        Files.createDirectories(root);
        persistence(root.resolve("current"));
        for (int version = 1; version <= 3; version++) migration(root.resolve("v" + version), version);
        corruptProtection(root.resolve("corrupt"));
        String report = "{\"suite\":\"environment-network-settings\",\"assertions\":" + assertions
                + ",\"passed\":true,\"proxyRuntimeTested\":false,\"webrtcRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report);
        System.out.println(report);
        System.out.println("Evidence: " + root);
    }

    private static void validation() {
        for (String[] value : new String[][] {
                {"", ""}, {"  ", ""}, {"http://127.0.0.1:8080", "http://127.0.0.1:8080"},
                {" HTTPS://Proxy.Example.COM:00443/ ", "https://proxy.example.com:443"},
                {"socks5://localhost:1080", "socks5://localhost:1080"},
                {"socks5://[2001:DB8::1]:1080", "socks5://[2001:db8::1]:1080"},
                {"http://[::1]:80", "http://[::1]:80"},
                {"http://例子.测试:8080", "http://xn--fsqu00a.xn--0zwm56d:8080"},
                {"http://proxy.test:65535", "http://proxy.test:65535"},
        }) check(new EnvironmentNetworkSettings(value[0], false).proxyUri.equals(value[1]), "Proxy canonicalization failed: " + value[0]);
        for (String invalid : new String[] {null, "proxy.test:8080", "direct://proxy.test:80", "socks4://proxy.test:80",
                "http://u:p@proxy.test:80", "http://u%40p@proxy.test:80", "http://proxy.test:80/path",
                "http://proxy.test:80/?x=1", "http://proxy.test:80#fragment", "http://proxy.test",
                "http://proxy.test:0", "http://proxy.test:-1", "http://proxy.test:65536", "http://proxy.test:123456",
                "http://proxy.test:80;direct://", "http://proxy.test:80,http://other.test:80",
                "http://127.1:80", "http://999.1.2.3:80", "http://01.2.3.4:80", "http://1234:80",
                "http://[not-an-ip]:80", "http://[::1%eth0]:80", "http://2001:db8::1:80",
                "http://proxy test:80", "http://proxy.test:80\n--no-proxy-server", "http://proxy.test:80\0",
                "http://" + "a".repeat(64) + ".test:80", "http://user:pass@host:80", "x".repeat(513)}) {
            rejects(() -> new EnvironmentNetworkSettings(invalid, false), invalid);
        }
    }

    private static void commandLine() {
        Switches target = new Switches();
        target.values.put("no-proxy-server", "");
        target.values.put("proxy-pac-url", "https://old.test/pac");
        target.values.put("proxy-auto-detect", "");
        target.values.put("proxy-bypass-list", "*");
        target.values.put("force-webrtc-ip-handling-policy", "default");
        target.values.put("unrelated-setting", "retained");
        new EnvironmentNetworkSettings("socks5://proxy.test:1080", true).applyTo(target);
        check(target.values.get("proxy-server").equals("socks5://proxy.test:1080"), "Selected proxy must reach Chromium");
        check(target.values.get("webrtc-ip-handling-policy").equals("disable_non_proxied_udp"), "Use the Chrome WebRTC preference switch");
        check(target.values.size() == 3 && target.values.get("unrelated-setting").equals("retained"),
                "Conflicting PAC, direct, bypass and obsolete flags must be cleared");
        EnvironmentNetworkSettings.system().applyTo(target);
        check(!target.values.containsKey("proxy-server") && !target.values.containsKey("no-proxy-server"),
                "System mode must permit Android system networking, not force direct");
        check(target.values.get("webrtc-ip-handling-policy").equals("default"), "Reset the previous environment WebRTC override");
    }

    private static void persistence(Path root) throws Exception {
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentDefinition first = new EnvironmentDefinition(A, "A", "about:blank", "", 100, 200);
        store.save(0, first.withNetwork(new EnvironmentNetworkSettings("https://proxy.test:443", true)));
        store.save(1, new EnvironmentDefinition(B, "B", "about:blank", "", 100, 200));
        EnvironmentCatalog.Snapshot pair = store.read();
        EnvironmentDefinition saved = pair.find(A);
        check(saved.network.proxyUri.equals("https://proxy.test:443") && saved.network.restrictWebRtcUdp, "Network settings must survive disk reload");
        check(pair.find(B).network.proxyUri.isEmpty() && !pair.find(B).network.restrictWebRtcUdp, "Other environments must retain their own network settings");
        check(first.network.proxyUri.isEmpty() && !first.network.restrictWebRtcUdp, "Editing must not mutate the active snapshot");
        check(saved.identity.equals(A) && saved.fingerprint.seed.equals(first.fingerprint.seed), "Network changes must retain profile identity and seed");
        EnvironmentDefinition renamed = saved.edit("renamed", saved.startPage, saved.notes)
                .withFingerprint(saved.fingerprint.withHardware(8, 4));
        check(renamed.network.proxyUri.equals(saved.network.proxyUri) && renamed.network.restrictWebRtcUdp,
                "Editing names or fingerprints must preserve network configuration");
    }

    private static void migration(Path root, int version) throws Exception {
        Files.createDirectories(root);
        byte[] original = fixture(version, "", 0);
        Path file = root.resolve("environments.bin");
        Files.write(file, original);
        EnvironmentCatalog store = new EnvironmentCatalog(root);
        EnvironmentDefinition old = store.read().find(A);
        check(old.createdAt == 100 && old.updatedAt == 200 && old.name.equals("legacy"), "Reading old catalogs must preserve metadata");
        check(old.network.proxyUri.isEmpty() && !old.network.restrictWebRtcUdp, "Old catalogs must retain system networking");
        check(old.fingerprint.regionalFontIsolation == (version >= 3), "Migration must preserve the font setting");
        check(Arrays.equals(original, Files.readAllBytes(file)), "Reading old catalogs must not rewrite them");
        store.save(7, old.withNetwork(new EnvironmentNetworkSettings("http://proxy.test:8080", true)));
        EnvironmentDefinition upgraded = store.read().find(A);
        check(upgraded.network.proxyUri.equals("http://proxy.test:8080") && upgraded.network.restrictWebRtcUdp,
                "Old catalogs must upgrade with actual network configuration");
        check(upgraded.fingerprint.seed.equals(old.fingerprint.seed) && upgraded.createdAt == 100,
                "Upgrade must retain fingerprint and profile creation data");
    }

    private static void corruptProtection(Path root) throws Exception {
        Files.createDirectories(root);
        for (byte[] invalid : new byte[][] {fixture(4, "http://u:p@proxy.test:80", 0), fixture(4, "", 2)}) {
            Path file = root.resolve("environments.bin");
            Files.write(file, invalid);
            EnvironmentCatalog store = new EnvironmentCatalog(root);
            boolean rejected = false;
            try { store.read(); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(invalid, Files.readAllBytes(file)), "Invalid persisted network settings must be rejected and preserved");
        }
    }

    private static byte[] fixture(int version, String proxy, int restrict) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x4c444332); output.writeInt(version); output.writeLong(7); output.writeInt(1);
            output.writeUTF(A.id()); output.writeUTF("legacy"); output.writeUTF("about:blank"); output.writeUTF("");
            output.writeLong(100); output.writeLong(200);
            if (version >= 2) { output.writeUTF("ab".repeat(32)); output.writeInt(8); output.writeInt(4); }
            if (version >= 3) output.writeByte(1);
            if (version >= 4) { output.writeUTF(proxy); output.writeByte(restrict); }
        }
        return bytes.toByteArray();
    }

    private static void check(boolean passed, String message) {
        assertions++;
        if (!passed) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String label) {
        assertions++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsafe or malformed proxy URI was accepted: " + label);
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        public void remove(String name) { values.remove(name); }
        public void set(String name, String value) { values.put(name, value); }
    }
}
