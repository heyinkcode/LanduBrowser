package com.landu.browser.nativecore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Real old-serializer fixtures and current file IO; never touches an installed profile. */
public final class EnvironmentRegionalPersistenceTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        byte[] historical = Files.readAllBytes(Path.of(args[1]));
        check(ByteBuffer.wrap(historical).getInt(4) == 16, "Fixture was encoded by the old V16 serializer");
        Path control = Files.createDirectories(root.resolve("policy-control"));
        Path file = control.resolve("environments.bin"); Files.write(file, historical);
        EnvironmentCatalog catalog = new EnvironmentCatalog(control);
        EnvironmentCatalog.Snapshot old = catalog.read();
        EnvironmentDefinition manual = old.allEnvironments.get(0);
        EnvironmentDefinition system = old.allEnvironments.get(1);
        EnvironmentDefinition custom = old.allEnvironments.get(2);
        check(manual.region.timeZone.equals("America/Los_Angeles")
                && manual.regionalPolicy.languageMode == EnvironmentRegionalPolicy.LanguageMode.FIXED
                && manual.regionalPolicy.timeZoneMode == EnvironmentRegionalPolicy.TimeZoneMode.CUSTOM,
                "Legacy manual LA remains manual");
        check(!system.regionalPolicy.needsExitLookup(), "Legacy device mode is not silently changed");
        check(custom.regionalPolicy.locationMode == EnvironmentRegionalPolicy.LocationMode.CUSTOM
                && custom.geolocation.latitude == 37.5, "Legacy coordinates preserved");
        check(Arrays.equals(historical, Files.readAllBytes(file)), "Read-only migration preserves old bytes");
        EnvironmentRegionalPolicy defaults = EnvironmentRegionalPolicy.desktopDefaults();
        EnvironmentRegionalPolicy policy = new EnvironmentRegionalPolicy(defaults.languageMode,
                defaults.timeZoneMode, defaults.locationMode, "de-DE,de");
        EnvironmentDefinition configured = manual.withRegionalPolicy(policy);
        EnvironmentCatalog.Snapshot saved = catalog.save(old.revision, configured);
        EnvironmentDefinition restored = new EnvironmentCatalog(control).read().find(manual.identity);
        check(ByteBuffer.wrap(Files.readAllBytes(file)).getInt(4) == 17, "Write upgrades to V17");
        check(restored.regionalPolicy.equals(policy), "All modes and preferred languages survive reopen");
        check(restored.createdAt == manual.createdAt && restored.fingerprint.seed.equals(manual.fingerprint.seed)
                && restored.identity.equals(manual.identity), "Upgrade preserves identity, creation and fingerprint");
        check(restored.region.timeZone.equals(manual.region.timeZone), "Policy changes do not replace manual fallback data");
        check(restored.edit("renamed", restored.startPage, "note").regionalPolicy.equals(policy), "Text edit keeps auto policies");
        check(restored.withNetwork(restored.network).regionalPolicy.equals(policy), "Network edit keeps auto policies");
        check(restored.withFingerprint(restored.fingerprint).regionalPolicy.equals(policy), "Fingerprint edit keeps auto policies");
        check(restored.withOrganization(restored.organization).regionalPolicy.equals(policy), "Organization edit keeps auto policies");
        check(restored.withAndroidIdentity(restored.androidIdentity).regionalPolicy.equals(policy), "Identity edit keeps auto policies");
        byte[] before = Files.readAllBytes(file);
        EnvironmentRegionalResolver.Result resolved = EnvironmentRegionalResolver.resolve(policy, restored.region,
                restored.geolocation, new EnvironmentExitInfo("203.0.113.4", "JP", "Tokyo", "Asia/Tokyo", 35.6,139.7,123));
        EnvironmentDefinition runtime = restored.withResolvedRegionalSettings(resolved);
        check(runtime.region.timeZone.equals("Asia/Tokyo") && runtime.geolocation.latitude == 35.6, "Launch snapshot uses measured exit");
        check(runtime.updatedAt == restored.updatedAt && runtime.createdAt == restored.createdAt
                && runtime.regionalPolicy.equals(policy), "Runtime resolution preserves configuration revision and policy");
        check(Arrays.equals(before, Files.readAllBytes(file)) && restored.region.timeZone.equals("America/Los_Angeles"),
                "Ephemeral snapshot never overwrites stored manual fallback");
        EnvironmentCatalog destination = new EnvironmentCatalog(root.resolve("import"));
        EnvironmentCatalog.ImportResult imported = destination.importConfiguration(0,
                EnvironmentCatalog.decodeConfiguration(catalog.exportConfiguration()), false);
        check(destination.read().find(restored.identity).regionalPolicy.equals(policy), "Export/import preserves auto policies");
        saved = catalog.recycle(saved.revision, restored.identity);
        check(saved.recycledEnvironments.get(0).regionalPolicy.equals(policy), "Recycle retains auto policies");
        saved = catalog.restore(saved.revision, restored.identity);
        check(saved.find(restored.identity).regionalPolicy.equals(policy), "Restore retains auto policies");
        byte[] valid = Files.readAllBytes(file); byte[] corrupt = valid.clone();
        byte[] marker = "EXIT_IP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int offset = -1;
        for (int i=0;i<=corrupt.length-marker.length;i++) {
            if (Arrays.equals(marker, Arrays.copyOfRange(corrupt,i,i+marker.length))) { offset=i; break; }
        }
        check(offset>=0, "Actual file contains persisted mode"); corrupt[offset]='Z';
        try { EnvironmentCatalog.decodeConfiguration(corrupt); throw new AssertionError("Invalid persisted mode accepted"); }
        catch (IOException expected) { assertions++; }
        check(Arrays.equals(valid, Files.readAllBytes(file)), "Malformed import leaves valid stored data intact");
        System.out.println("{\"suite\":\"regional-policy-persistence\",\"assertions\":"+assertions+",\"passed\":true}");
    }
    private static void check(boolean ok,String message) { assertions++; if(!ok) throw new AssertionError(message); }
}
