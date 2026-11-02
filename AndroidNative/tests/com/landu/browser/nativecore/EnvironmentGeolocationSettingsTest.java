package com.landu.browser.nativecore;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EnvironmentGeolocationSettingsTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        for (double latitude : new double[] {-90, 0, 90}) {
            for (double longitude : new double[] {-180, 0, 180}) {
                EnvironmentGeolocationSettings point = new EnvironmentGeolocationSettings(1, latitude, longitude, 1);
                check(point.latitude == latitude && point.longitude == longitude, "Boundary coordinates must survive");
            }
        }
        check(EnvironmentGeolocationSettings.custom(" -0.0 ", "+.25", "1e2").accuracy == 100,
                "Decimal/exponent input should be canonicalized");
        for (String invalid : new String[] {"", "NaN", "Infinity", "0x1p1", "12,5", "1\n2", "1e999", "1".repeat(41)}) {
            rejects(() -> EnvironmentGeolocationSettings.custom(invalid, "0", "100"));
        }
        for (double[] invalid : new double[][] {{91, 0, 100}, {-91, 0, 100}, {0, 181, 100},
                {0, -181, 100}, {0, 0, 0}, {0, 0, 1000001}, {Double.NaN, 0, 100},
                {0, Double.POSITIVE_INFINITY, 100}, {0, 0, Double.NaN}}) {
            rejects(() -> new EnvironmentGeolocationSettings(1, invalid[0], invalid[1], invalid[2]));
        }
        rejects(() -> new EnvironmentGeolocationSettings(3, 0, 0, 0));
        rejects(() -> new EnvironmentGeolocationSettings(0, 1, 0, 0));
        rejects(() -> new EnvironmentGeolocationSettings(2, 0, 0, 100));
        Switches switches = new Switches();
        switches.values.put("proxy-server", "socks5://proxy.test:1080");
        EnvironmentGeolocationSettings point = EnvironmentGeolocationSettings.custom("37.5", "-122.25", "75");
        point.applyTo(switches);
        check(switches.values.get("landu-geolocation-mode").equals("custom")
                && switches.values.get("landu-geolocation-position").equals("37.5,-122.25,75.0"),
                "Native location input must carry the same configured point");
        EnvironmentGeolocationSettings.blocked().applyTo(switches);
        check(!switches.values.containsKey("landu-geolocation-position")
                && switches.values.get("landu-geolocation-mode").equals("blocked"), "Blocking must remove stale coordinates");
        EnvironmentGeolocationSettings.system().applyTo(switches);
        check(switches.values.size() == 1 && switches.values.containsKey("proxy-server"),
                "Device mode must clear only owned location arguments");

        Path root = Files.createDirectories(Path.of(args[0]).resolve("geolocation-" + UUID.randomUUID()));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("catalog"));
        EnvironmentDefinition initial = EnvironmentDefinition.create("A", "", "");
        EnvironmentDefinition custom = initial.withGeolocation(point);
        EnvironmentDefinition blocked = EnvironmentDefinition.create("B", "", "").withGeolocation(EnvironmentGeolocationSettings.blocked());
        catalog.save(0, custom); catalog.save(1, blocked);
        EnvironmentCatalog.Snapshot snapshot = catalog.read();
        check(snapshot.find(custom.identity).geolocation.longitude == -122.25
                && snapshot.find(blocked.identity).geolocation.mode == 2, "Environments must persist independent location policies");
        check(initial.geolocation.mode == 0 && custom.geolocation == point, "Edits must not change an existing launch snapshot");
        EnvironmentDefinition edited = custom.edit("renamed", "", "note").withFingerprint(custom.fingerprint.withHardware(8, 4))
                .withNetwork(new EnvironmentNetworkSettings("http://proxy.test:8080", true))
                .withRegion(new EnvironmentRegionSettings("en-US,en", "America/Los_Angeles"))
                .withAndroidIdentity(new EnvironmentAndroidIdentity("16", "Phone"))
                .withOrganization(new EnvironmentOrganization(1, "", "tag"));
        check(edited.geolocation == point && edited.withStoredOrganization(new EnvironmentOrganization(2, "", "")).geolocation == point,
                "All unrelated edit paths must preserve immutable location settings");
        EnvironmentCatalog imported = new EnvironmentCatalog(root.resolve("imported"));
        EnvironmentCatalog.Snapshot backup = EnvironmentCatalog.decodeConfiguration(catalog.exportConfiguration());
        imported.importConfiguration(0, backup, false);
        check(imported.read().find(custom.identity).geolocation.accuracy == 75
                && imported.read().find(blocked.identity).geolocation.mode == 2, "Export and merge import must retain location policy");
        imported.save(imported.read().revision, imported.read().find(custom.identity).withGeolocation(EnvironmentGeolocationSettings.system()));
        imported.importConfiguration(imported.read().revision, backup, true);
        check(imported.read().find(custom.identity).geolocation.mode == 1, "Updating existing imports must restore custom settings");

        Path legacy = Files.createDirectories(root.resolve("legacy"));
        byte[] v10 = legacyFixture(initial);
        Files.write(legacy.resolve("environments.bin"), v10);
        EnvironmentCatalog old = new EnvironmentCatalog(legacy);
        EnvironmentDefinition migrated = old.read().find(initial.identity);
        check(migrated.geolocation.mode == 0 && Arrays.equals(v10, Files.readAllBytes(legacy.resolve("environments.bin"))),
                "V10 reads must keep the device policy and must not rewrite old data");
        old.save(7, migrated.withGeolocation(point));
        check(ByteBuffer.wrap(Files.readAllBytes(legacy.resolve("environments.bin"))).getInt(4) == 17
                && old.read().find(initial.identity).geolocation.latitude == 37.5, "First edit must atomically upgrade to V17");
        byte[] bytes = old.exportConfiguration();
        // Locate the actual custom-position record independently of trailing version fields.
        byte[] location = ByteBuffer.allocate(25).put((byte) point.mode).putDouble(point.latitude)
                .putDouble(point.longitude).putDouble(point.accuracy).array();
        int offset = -1;
        for (int i = 0; i <= bytes.length - location.length; i++) {
            if (Arrays.equals(location, Arrays.copyOfRange(bytes, i, i + location.length))) {
                check(offset == -1, "Location fixture must have a unique record"); offset = i;
            }
        }
        check(offset >= 0, "Export contains actual custom location");
        byte[] invalidMode = bytes.clone(); invalidMode[offset] = 9;
        byte[] invalidPoint = bytes.clone(); ByteBuffer.wrap(invalidPoint).putDouble(offset + 1, Double.NaN);
        for (byte[] malformed : new byte[][] {invalidMode, invalidPoint, Arrays.copyOf(bytes, bytes.length - 2)}) {
            byte[] before = Files.readAllBytes(legacy.resolve("environments.bin"));
            boolean rejected = false;
            try { EnvironmentCatalog.decodeConfiguration(malformed); } catch (IOException expected) { rejected = true; }
            check(rejected && Arrays.equals(before, Files.readAllBytes(legacy.resolve("environments.bin"))),
                    "Invalid location backups must fail without modifying the stored environment");
        }
        String report = "{\"suite\":\"environment-geolocation-settings\",\"assertions\":" + assertions
                + ",\"passed\":true,\"browserRuntimeTested\":false}";
        Files.writeString(root.resolve("result.json"), report); System.out.println(report);
    }

    private static byte[] legacyFixture(EnvironmentDefinition e) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x4c444332); out.writeInt(10); out.writeLong(7); out.writeInt(1);
            out.writeUTF(e.identity.id()); out.writeUTF(e.name); out.writeUTF(e.startPage); out.writeUTF(e.notes);
            out.writeLong(e.createdAt); out.writeLong(e.updatedAt); out.writeUTF(e.fingerprint.seed);
            out.writeInt(0); out.writeInt(0); out.writeByte(1); out.writeUTF(""); out.writeByte(0);
            out.writeUTF(""); out.writeUTF(""); out.writeByte(1); out.writeByte(0); out.writeUTF(""); out.writeUTF("");
            for (int i = 0; i < 6; i++) out.writeUTF("");
            out.writeInt(1); out.writeUTF(""); out.writeUTF(""); out.writeInt(0);
        }
        return bytes.toByteArray();
    }

    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Invalid location policy must be rejected");
    }
    private static final class Switches implements BrowserCommandLineTarget {
        final Map<String, String> values = new HashMap<>();
        @Override public void remove(String name) { values.remove(name); }
        @Override public void set(String name, String value) { values.put(name, value); }
    }
}
