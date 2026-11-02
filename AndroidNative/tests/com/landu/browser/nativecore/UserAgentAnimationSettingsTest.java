package com.landu.browser.nativecore;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class UserAgentAnimationSettingsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("invalid setting accepted"); }
    public static void main(String[] args) throws Exception {
        EnvironmentUserAgentSettings ua = EnvironmentUserAgentSettings.androidDefaults("156.0.8078.48", "15.0.0", "Pixel 8");
        String[] values = ua.values();
        check(values[0].contains("Chrome/156.0.0.0"), "generated reduced UA matches actual major");
        check(ua.metadataJson().contains("156.0.8078.48") && ua.metadataJson().contains("\"mobile\":true"), "complete metadata");
        values[1] = "151.0.7890.1";
        rejects(() -> new EnvironmentUserAgentSettings(true, values, true, false));
        for (int index : new int[] {0, 2, 3, 4, 5, 6, 7, 8, 9, 10}) {
            String[] bad = ua.values(); bad[index] += "\r\nInjected: yes";
            rejects(() -> new EnvironmentUserAgentSettings(true, bad, true, false));
        }
        String[] bad = ua.values(); bad[8] += "\nChromium=156";
        rejects(() -> new EnvironmentUserAgentSettings(true, bad, true, false));
        rejects(() -> new EnvironmentUserAgentSettings(true, ua.values(), true, true));
        rejects(() -> new AnimationFrameSettings(-1)); rejects(() -> new AnimationFrameSettings(241));
        Map<String, String> switches = new HashMap<>();
        BrowserCommandLineTarget target = new BrowserCommandLineTarget() {
            public void remove(String name) { switches.remove(name); }
            public void set(String name, String value) { switches.put(name, value); }
        };
        ua.applyTo(target); new AnimationFrameSettings(60).applyTo(target);
        check(switches.size() == 4 && switches.get("landu-raf-max-fps").equals("60"), "independent switches");
        EnvironmentUserAgentSettings.automatic().applyTo(target);
        check(switches.size() == 1, "UA auto does not reset RAF");
        AnimationFrameSettings.device().applyTo(target); check(switches.isEmpty(), "restore native defaults removes stale flags");
        EnvironmentDefinition entry = EnvironmentDefinition.create("UA RAF roundtrip", "about:blank", "");
        FingerprintSettings fp = entry.fingerprint.withUserAgent(ua).withAnimationFrames(new AnimationFrameSettings(30));
        fp = fp.withHardware(8, 4).withRegionalFontIsolation(true).withCanvasNoise(false).withAudio(true, false)
                .withDoNotTrack(1).withGpuMetadata(EnvironmentGpuMetadata.system()).withClientRectsNoise(true)
                .withMedia(EnvironmentMediaSettings.isolated());
        check(fp.userAgent == ua && fp.animationFrames.maxFramesPerSecond == 30, "all fingerprint modifiers preserve settings");
        entry = entry.withFingerprint(fp).edit("changed", "", "kept")
                .withOrganization(EnvironmentOrganization.unassigned()).withAndroidIdentity(EnvironmentAndroidIdentity.system());
        Path root = Files.createDirectories(Path.of(args[0]).resolve("ua-raf-" + UUID.randomUUID()));
        EnvironmentCatalog catalog = new EnvironmentCatalog(root.resolve("source"));
        catalog.save(0, entry);
        EnvironmentDefinition read = catalog.read().find(entry.identity);
        check(Arrays.equals(read.fingerprint.userAgent.values(), ua.values()) && read.fingerprint.userAgent.mobile
                && read.fingerprint.animationFrames.maxFramesPerSecond == 30, "disk persistence and reload");
        EnvironmentCatalog imported = new EnvironmentCatalog(root.resolve("target"));
        imported.importConfiguration(0, EnvironmentCatalog.decodeConfiguration(catalog.exportConfiguration()), false);
        read = imported.read().find(entry.identity);
        check(read.fingerprint.userAgent.metadataJson().equals(ua.metadataJson()) && read.fingerprint.animationFrames.maxFramesPerSecond == 30,
                "portable import keeps metadata and rate");
        if (args.length > 1) {
            byte[] legacy = Files.readAllBytes(Path.of(args[1]));
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(legacy))) { in.readInt(); check(in.readInt() == 17, "real previous catalog is V17"); }
            for (EnvironmentDefinition old : EnvironmentCatalog.decodeConfiguration(legacy).allEnvironments) {
                check(!old.fingerprint.userAgent.custom && old.fingerprint.animationFrames.maxFramesPerSecond == 0, "V17 retains native UA/RAF");
                EnvironmentCatalog migrated = new EnvironmentCatalog(root.resolve("migrate-" + old.identity.id()));
                migrated.save(0, old.withOrganization(EnvironmentOrganization.unassigned()));
                EnvironmentDefinition migratedRead = migrated.read().find(old.identity);
                check(migratedRead.network.proxyUri.equals(old.network.proxyUri)
                        && migratedRead.network.credentialReference.equals(old.network.credentialReference)
                        && migratedRead.fingerprint.seed.equals(old.fingerprint.seed), "migration preserves proxy binding and seed");
            }
        }
        System.out.println("UA-CH/RAF persistence, migration and validation: " + checks + " checks passed");
    }
}
