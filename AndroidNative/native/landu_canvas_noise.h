// Copyright 2026 Landu Browser contributors.
// SPDX-License-Identifier: BSD-3-Clause
#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_CANVAS_NOISE_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_CANVAS_NOISE_H_

#include <cstddef>
#include <cstdint>
#include <limits>
#include <optional>
#include <span>
#include <string_view>

namespace blink::landu {

inline uint64_t MixCanvasValue(uint64_t value) {
  value = (value ^ (value >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
  value = (value ^ (value >> 27)) * UINT64_C(0x94d049bb133111eb);
  return value ^ (value >> 31);
}

inline std::optional<uint64_t> CanvasNoiseSeed(std::string_view text) {
  if (text.size() != 64) return std::nullopt;
  uint64_t seed = UINT64_C(0x6c616e6475637631);
  for (char ch : text) {
    unsigned digit;
    if (ch >= '0' && ch <= '9') digit = ch - '0';
    else if (ch >= 'a' && ch <= 'f') digit = ch - 'a' + 10;
    else return std::nullopt;
    seed = MixCanvasValue(seed ^ digit);
  }
  return seed;
}

enum class CanvasPixelFormat { kRgba8, kBgra8, kRgbaF16 };

// Quantize each pair to one environment-specific value. Reapplying at the
// same coordinate is idempotent: read -> put -> read does not accumulate noise.
// This is bounded pixel perturbation, not a claim of hardware-independent
// rendering or resistance to every fingerprinting technique.
inline uint16_t CanvasChannel(uint16_t value, uint64_t key, bool half_float) {
  if (!value) return value;  // Preserve cleared colour channels, including clipped pixels.
  if (half_float && ((value & 0x7fff) == 0 || (value & 0x7c00) == 0x7c00))
    return value;  // Preserve signed zero, infinity and NaN payloads.
  const uint16_t pair = value & 0xfffe;
  return pair | (MixCanvasValue(key ^ pair) & 1);
}

// Only call on owned readback/export memory, never the live canvas backing.
// row_bytes may include padding; RGB/BGR order and cropped read coordinates are
// explicit. Half-floats are IEEE 754 binary16 in little-endian Android memory.
inline bool NoiseCanvasPixels(std::span<uint8_t> bytes,
                              size_t width, size_t height, size_t row_bytes,
                              CanvasPixelFormat format, uint64_t seed,
                              int64_t origin_x = 0, int64_t origin_y = 0) {
  const size_t pixel_bytes = format == CanvasPixelFormat::kRgbaF16 ? 8 : 4;
  if (!width || !height || width > std::numeric_limits<size_t>::max() / pixel_bytes)
    return false;
  const size_t active_row = width * pixel_bytes;
  if (row_bytes < active_row || (height - 1) >
      (std::numeric_limits<size_t>::max() - active_row) / row_bytes ||
      bytes.size() < (height - 1) * row_bytes + active_row ||
      width > UINT32_MAX || height > UINT32_MAX ||
      origin_x < INT32_MIN || origin_x > INT32_MAX ||
      origin_y < INT32_MIN || origin_y > INT32_MAX)
    return false;
  for (size_t y = 0; y < height; ++y) {
    auto row = bytes.subspan(y * row_bytes, active_row);
    for (size_t x = 0; x < width; ++x) {
      auto pixel = row.subspan(x * pixel_bytes, pixel_bytes);
      const bool half = format == CanvasPixelFormat::kRgbaF16;
      if (half ? ((pixel[6] | (uint16_t(pixel[7]) << 8)) & 0x7fff) == 0
               : pixel[3] == 0)
        continue;  // Keep transparent and out-of-bounds pixels untouched.
      const uint64_t location = uint64_t(uint32_t(origin_x + int64_t(x))) << 32 |
                                uint32_t(origin_y + int64_t(y));
      const uint64_t key = MixCanvasValue(seed ^ location);
      for (size_t component = 0; component < 3; ++component) {
        const size_t channel = format == CanvasPixelFormat::kBgra8 ? 2 - component : component;
        const size_t offset = channel * (half ? 2 : 1);
        const uint16_t value = half ? pixel[offset] | (uint16_t(pixel[offset + 1]) << 8)
                                    : pixel[offset];
        const uint16_t noised = CanvasChannel(value, key ^
            (UINT64_C(0x9e3779b97f4a7c15) * (component + 1)), half);
        pixel[offset] = noised & 0xff;
        if (half) pixel[offset + 1] = noised >> 8;
      }
    }
  }
  return true;
}
}  // namespace blink::landu
#endif
