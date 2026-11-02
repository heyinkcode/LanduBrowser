#!/usr/bin/env bash
set -euo pipefail
: "${CLANG_CXX:?Set CLANG_CXX to the pinned Chromium clang++}"
: "${LANDU_TEST_CACHE:?Set LANDU_TEST_CACHE to a dedicated test directory}"
native_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$LANDU_TEST_CACHE"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/fingerprint_settings_test.cc" \
    -o "$LANDU_TEST_CACHE/fingerprint_settings_test"
"$LANDU_TEST_CACHE/fingerprint_settings_test" | tee "$LANDU_TEST_CACHE/native-fingerprint-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/geolocation_policy_test.cc" \
    -o "$LANDU_TEST_CACHE/geolocation_policy_test"
"$LANDU_TEST_CACHE/geolocation_policy_test" | tee "$LANDU_TEST_CACHE/native-geolocation-policy-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/canvas_noise_test.cc" \
    -o "$LANDU_TEST_CACHE/canvas_noise_test"
"$LANDU_TEST_CACHE/canvas_noise_test" | tee "$LANDU_TEST_CACHE/native-canvas-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/android_identity_test.cc" \
    -o "$LANDU_TEST_CACHE/android_identity_test"
"$LANDU_TEST_CACHE/android_identity_test" | tee "$LANDU_TEST_CACHE/native-android-identity-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/gpu_metadata_test.cc" \
    -o "$LANDU_TEST_CACHE/gpu_metadata_test"
"$LANDU_TEST_CACHE/gpu_metadata_test" | tee "$LANDU_TEST_CACHE/native-gpu-metadata-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/webgl_noise_test.cc" \
    -o "$LANDU_TEST_CACHE/webgl_noise_test"
"$LANDU_TEST_CACHE/webgl_noise_test" | tee "$LANDU_TEST_CACHE/native-webgl-noise-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/audio_noise_test.cc" \
    -o "$LANDU_TEST_CACHE/audio_noise_test"
"$LANDU_TEST_CACHE/audio_noise_test" | tee "$LANDU_TEST_CACHE/native-audio-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/client_rects_noise_test.cc" \
    -o "$LANDU_TEST_CACHE/client_rects_noise_test"
"$LANDU_TEST_CACHE/client_rects_noise_test" | tee "$LANDU_TEST_CACHE/native-client-rects-result.json"
"$CLANG_CXX" -std=c++20 -Wall -Wextra -Werror -pedantic \
    "$native_root/tests/native/media_policy_test.cc" \
    -o "$LANDU_TEST_CACHE/media_policy_test"
"$LANDU_TEST_CACHE/media_policy_test" | tee "$LANDU_TEST_CACHE/native-media-policy-result.json"
