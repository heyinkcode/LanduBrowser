#!/usr/bin/env bash
set -euo pipefail

: "${JAVA_HOME:?Set JAVA_HOME to the Linux JDK}"
: "${LANDU_TEST_CACHE:?Set LANDU_TEST_CACHE to a dedicated test directory}"
native_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$LANDU_TEST_CACHE/classes" "$LANDU_TEST_CACHE/temp"
cd "$native_root"
find java tests -name '*.java' -print > "$LANDU_TEST_CACHE/sources.txt"
"$JAVA_HOME/bin/javac" --release 11 -encoding UTF-8 \
    -d "$LANDU_TEST_CACHE/classes" @"$LANDU_TEST_CACHE/sources.txt"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentLeaseStoreTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentCatalogTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentRecycleTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentOrganizationTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentTransferTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentTransferControllerTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.FingerprintSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentShutdownCoordinatorTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentSaveBarrierTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentLegacySaveBarrierTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentNetworkSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.ProxyCredentialsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.TotpTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.AccountCredentialsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.CookieTransferStreamsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentRegionSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentGeolocationSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.CanvasSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.DoNotTrackSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentAndroidIdentityTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentGpuMetadataTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.AudioSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.ClientRectsSettingsTest "$LANDU_TEST_CACHE"
"$JAVA_HOME/bin/java" -Djava.io.tmpdir="$LANDU_TEST_CACHE/temp" \
    -cp "$LANDU_TEST_CACHE/classes" \
    com.landu.browser.nativecore.EnvironmentMediaSettingsTest "$LANDU_TEST_CACHE"
