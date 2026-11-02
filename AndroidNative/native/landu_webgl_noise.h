// Copyright 2026 Landu Browser contributors.
// SPDX-License-Identifier: BSD-3-Clause
#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_WEBGL_NOISE_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_WEBGL_NOISE_H_

#include <algorithm>
#include <array>
#include "landu_canvas_noise.h"

namespace blink::landu {

enum class WebGLStorage { kScalar, k565, k4444, k5551, k2101010, k111110, k9995 };
struct WebGLPixelDescription {
  size_t pixel_bytes;
  unsigned channels;
  int alpha;
  unsigned component_bytes;
  bool floating;
  WebGLStorage storage;
};

// GLenum values are fixed by GLES2/GLES3. Frontend and driver validation still
// run first; this function never declares an invalid GL combination valid.
inline std::optional<WebGLPixelDescription> DescribeWebGLPixels(uint32_t format,
                                                               uint32_t type) {
  unsigned channels;
  int alpha = -1;
  switch (format) {
    case 0x1906: channels = 1; alpha = 0; break;  // ALPHA
    case 0x1909: case 0x1903: case 0x8d94: channels = 1; break;
    case 0x190a: channels = 2; alpha = 1; break;  // LUMINANCE_ALPHA
    case 0x8227: case 0x8228: channels = 2; break;
    case 0x1907: case 0x8d98: channels = 3; break;
    case 0x1908: case 0x8d99: channels = 4; alpha = 3; break;
    default: return std::nullopt;
  }
  unsigned bytes = 0;
  bool floating = false;
  switch (type) {
    case 0x1400: case 0x1401: bytes = 1; break;  // BYTE / UNSIGNED_BYTE
    case 0x1402: case 0x1403: bytes = 2; break;  // SHORT / UNSIGNED_SHORT
    case 0x1404: case 0x1405: bytes = 4; break;  // INT / UNSIGNED_INT
    case 0x1406: bytes = 4; floating = true; break;
    case 0x140b: case 0x8d61: bytes = 2; floating = true; break;
    case 0x8363:
      if (channels != 3) return std::nullopt;
      return WebGLPixelDescription{2, 3, -1, 0, false, WebGLStorage::k565};
    case 0x8033: case 0x8034:
      if (channels != 4) return std::nullopt;
      return WebGLPixelDescription{2, 4, 3, 0, false,
          type == 0x8033 ? WebGLStorage::k4444 : WebGLStorage::k5551};
    case 0x8368:
      if (channels != 4) return std::nullopt;
      return WebGLPixelDescription{4, 4, 3, 0, false, WebGLStorage::k2101010};
    case 0x8c3b: case 0x8c3e:
      if (channels != 3) return std::nullopt;
      return WebGLPixelDescription{4, 3, -1, 0, false,
          type == 0x8c3b ? WebGLStorage::k111110 : WebGLStorage::k9995};
    default: return std::nullopt;
  }
  return WebGLPixelDescription{channels * bytes, channels, alpha, bytes,
                               floating, WebGLStorage::kScalar};
}

inline std::optional<size_t> WebGLProduct(size_t a, size_t b) {
  if (a && b > SIZE_MAX / a) return std::nullopt;
  return a * b;
}
inline std::optional<size_t> WebGLSum(size_t a, size_t b) {
  if (b > SIZE_MAX - a) return std::nullopt;
  return a + b;
}

struct WebGLPackLayout {
  size_t row_bytes;
  size_t row_size;
  size_t skip_bytes;
  size_t end_bytes;
  size_t snapshot_bytes;
  size_t height;
};

inline bool ValidWebGLPackRange(size_t width, size_t row_length, size_t skip_pixels) {
  const auto end = WebGLSum(skip_pixels, width);
  return end && *end <= (row_length ? row_length : width);
}

inline bool ValidWebGLPackOffset(size_t offset, size_t type_bytes) {
  return (type_bytes == 1 || type_bytes == 2 || type_bytes == 4) && offset % type_bytes == 0;
}

inline std::optional<WebGLPackLayout> MakeWebGLPackLayout(
    size_t width, size_t height, size_t pixel_bytes, size_t alignment,
    size_t row_length = 0, size_t skip_pixels = 0, size_t skip_rows = 0) {
  if (!pixel_bytes || pixel_bytes > 16 ||
      (alignment != 1 && alignment != 2 && alignment != 4 && alignment != 8) ||
      !ValidWebGLPackRange(width, row_length, skip_pixels))
    return std::nullopt;
  if (!width || !height) return WebGLPackLayout{0, 0, 0, 0, 0, 0};
  auto row = WebGLProduct(row_length ? row_length : width, pixel_bytes);
  auto active = WebGLProduct(width, pixel_bytes);
  if (!row || !active) return std::nullopt;
  auto stride = WebGLSum(*row, (alignment - *row % alignment) % alignment);
  if (!stride) return std::nullopt;
  auto skipped_rows = WebGLProduct(skip_rows, *stride);
  auto skipped_pixels = WebGLProduct(skip_pixels, pixel_bytes);
  auto rows = WebGLProduct(height - 1, *stride);
  auto snapshot = WebGLProduct(height, *active);
  if (!skipped_rows || !skipped_pixels || !rows || !snapshot) return std::nullopt;
  auto skip = WebGLSum(*skipped_rows, *skipped_pixels);
  auto content = WebGLSum(*rows, *active);
  if (!skip || !content) return std::nullopt;
  auto end = WebGLSum(*skip, *content);
  if (!end) return std::nullopt;
  return WebGLPackLayout{*stride, *active, *skip, *end, *snapshot, height};
}

inline uint32_t ReadWebGLWord(std::span<const uint8_t> data) {
  uint32_t value = 0;
  for (size_t i = 0; i < std::min(data.size(), sizeof(value)); ++i) value |= uint32_t(data[i]) << (8 * i);
  return value;
}
inline void WriteWebGLWord(std::span<uint8_t> data, uint32_t value) {
  for (size_t i = 0; i < std::min(data.size(), sizeof(value)); ++i) data[i] = (value >> (8 * i)) & 0xff;
}

inline uint32_t WebGLChannel(uint32_t value, uint64_t key,
                              uint32_t zero_mask = UINT32_MAX,
                              uint32_t exponent_mask = 0) {
  // Zeroes include driver-cleared pixels outside the framebuffer. Do not turn
  // them into data. Preserve float signed zero, infinity and NaN payloads.
  if (!(value & zero_mask) || (exponent_mask && (value & exponent_mask) == exponent_mask))
    return value;
  const uint32_t pair = value & ~uint32_t{1};
  return pair | (MixCanvasValue(key ^ pair) & 1);
}

// Input is a tightly packed, owned readback snapshot, never live GPU storage.
// top-left default-framebuffer coordinates use row_direction=-1; user FBOs
// retain bottom-left coordinates. Padding and skip areas are not in this span.
inline bool NoiseWebGLPixels(std::span<uint8_t> data, size_t width, size_t height,
                             const WebGLPixelDescription& description,
                             uint64_t seed, int64_t origin_x = 0,
                             int64_t origin_y = 0, int row_direction = 1) {
  if (description.channels < 1 || description.channels > 4 || description.alpha < -1 ||
      description.alpha >= int(description.channels)) return false;
  if (description.storage == WebGLStorage::kScalar) {
    if ((description.component_bytes != 1 && description.component_bytes != 2 && description.component_bytes != 4) ||
        description.pixel_bytes != description.channels * description.component_bytes ||
        (description.floating && description.component_bytes == 1)) return false;
  } else {
    const bool three = description.storage == WebGLStorage::k565 || description.storage == WebGLStorage::k111110 || description.storage == WebGLStorage::k9995;
    const bool four = description.storage == WebGLStorage::k4444 || description.storage == WebGLStorage::k5551 || description.storage == WebGLStorage::k2101010;
    const size_t bytes = description.storage == WebGLStorage::k565 || description.storage == WebGLStorage::k4444 || description.storage == WebGLStorage::k5551 ? 2 : 4;
    if ((!three && !four) || description.pixel_bytes != bytes || description.floating || description.component_bytes ||
        description.channels != (three ? 3u : 4u) || description.alpha != (three ? -1 : 3)) return false;
  }
  auto row_size = WebGLProduct(width, description.pixel_bytes);
  auto total = row_size ? WebGLProduct(*row_size, height) : std::nullopt;
  if (!total || data.size() < *total || !description.pixel_bytes ||
      width > INT32_MAX || height > INT32_MAX ||
      origin_x < INT64_MIN + INT32_MAX || origin_x > INT64_MAX - INT32_MAX ||
      origin_y < INT64_MIN + INT32_MAX || origin_y > INT64_MAX - INT32_MAX ||
      (row_direction != 1 && row_direction != -1)) return false;
  for (size_t y = 0; y < height; ++y) {
    for (size_t x = 0; x < width; ++x) {
      auto pixel = data.subspan(y * *row_size + x * description.pixel_bytes, description.pixel_bytes);
      const uint64_t location = uint64_t(uint32_t(origin_x + int64_t(x))) << 32 |
          uint32_t(origin_y + int64_t(y) * row_direction);
      const uint64_t key = MixCanvasValue(seed ^ location);
      if (description.storage == WebGLStorage::kScalar) {
        const auto bytes = description.component_bytes;
        if (!bytes || bytes > 4 || bytes * description.channels != description.pixel_bytes) return false;
        if (description.alpha >= 0) {
          uint32_t alpha = ReadWebGLWord(pixel.subspan(description.alpha * bytes, bytes));
          if (description.floating) alpha &= bytes == 2 ? 0x7fff : 0x7fffffff;
          if (!alpha) continue;
        }
        for (unsigned channel = 0; channel < description.channels; ++channel) {
          if (int(channel) == description.alpha) continue;
          auto value = pixel.subspan(channel * bytes, bytes);
          WriteWebGLWord(value, WebGLChannel(ReadWebGLWord(value), key ^
              (UINT64_C(0x9e3779b97f4a7c15) * (channel + 1)),
              description.floating ? (bytes == 2 ? 0x7fff : 0x7fffffff) : UINT32_MAX,
              description.floating ? (bytes == 2 ? 0x7c00 : 0x7f800000) : 0));
        }
      } else {
        std::array<unsigned, 3> shifts, widths;
        unsigned alpha_shift = 0, alpha_bits = 0;
        switch (description.storage) {
          case WebGLStorage::k565: shifts = {11, 5, 0}; widths = {5, 6, 5}; break;
          case WebGLStorage::k4444: shifts = {12, 8, 4}; widths = {4, 4, 4}; alpha_bits = 4; break;
          case WebGLStorage::k5551: shifts = {11, 6, 1}; widths = {5, 5, 5}; alpha_bits = 1; break;
          case WebGLStorage::k2101010: shifts = {0, 10, 20}; widths = {10, 10, 10}; alpha_shift = 30; alpha_bits = 2; break;
          case WebGLStorage::k111110: shifts = {0, 11, 22}; widths = {11, 11, 10}; break;
          case WebGLStorage::k9995: shifts = {0, 9, 18}; widths = {9, 9, 9}; break;
          default: return false;
        }
        uint32_t word = ReadWebGLWord(pixel);
        if (alpha_bits && !((word >> alpha_shift) & ((1u << alpha_bits) - 1))) continue;
        for (size_t channel = 0; channel < 3; ++channel) {
          const uint32_t mask = (1u << widths[channel]) - 1;
          const uint32_t exponent = description.storage == WebGLStorage::k111110 ? 0x1fu << (widths[channel] - 5) : 0;
          const auto before = (word >> shifts[channel]) & mask;
          const auto after = WebGLChannel(before, key ^ (UINT64_C(0x9e3779b97f4a7c15) * (channel + 1)), UINT32_MAX, exponent);
          word = (word & ~(mask << shifts[channel])) | (after << shifts[channel]);
        }
        WriteWebGLWord(pixel, word);
      }
    }
  }
  return true;
}

inline bool CopyWebGLPackedRows(std::span<const uint8_t> source,
                                std::span<uint8_t> destination,
                                const WebGLPackLayout& layout) {
  if (!layout.height) return !layout.snapshot_bytes && !layout.end_bytes;
  auto snapshot = WebGLProduct(layout.height, layout.row_size);
  auto rows = WebGLProduct(layout.height - 1, layout.row_bytes);
  auto content = rows ? WebGLSum(*rows, layout.row_size) : std::nullopt;
  auto end = content ? WebGLSum(layout.skip_bytes, *content) : std::nullopt;
  if (!layout.row_bytes || !layout.row_size || !snapshot || !end ||
      *snapshot != layout.snapshot_bytes || *end != layout.end_bytes ||
      source.size() < *snapshot || destination.size() < *end) return false;
  for (size_t row = 0; row < layout.height; ++row) {
    auto input = source.subspan(row * layout.row_size, layout.row_size);
    auto output = destination.subspan(layout.skip_bytes + row * layout.row_bytes, layout.row_size);
    std::copy(input.begin(), input.end(), output.begin());
  }
  return true;
}
}  // namespace blink::landu
#endif
