#include "../../native/landu_canvas_noise.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using blink::landu::CanvasPixelFormat;
using blink::landu::NoiseCanvasPixels;

int main() {
  unsigned assertions = 0;
  auto check = [&](bool condition) {
    ++assertions;
    if (!condition) throw std::runtime_error("Canvas pixel isolation invariant failed");
  };
  const auto seed = blink::landu::CanvasNoiseSeed(std::string(64, 'a'));
  const auto other_seed = blink::landu::CanvasNoiseSeed(std::string(64, 'b'));
  check(seed && other_seed && seed != other_seed);
  for (const auto& invalid : {std::string(), std::string(63, 'a'), std::string(65, 'a'),
       std::string(64, 'A'), std::string(64, 'g'), std::string(63, 'a') + "\n"})
    check(!blink::landu::CanvasNoiseSeed(invalid));

  // A padded row, transparent regions and all byte values exercise channel
  // boundaries, row strides and memory guards, not only a synthetic text scene.
  constexpr size_t width = 256, height = 4, stride = width * 4 + 8;
  std::vector<uint8_t> original(height * stride, 0xa5);
  for (size_t y = 0; y < height; ++y) {
    for (size_t x = 0; x < width; ++x) {
      const size_t offset = y * stride + x * 4;
      original[offset] = x;
      original[offset + 1] = 255 - x;
      original[offset + 2] = (x * 73) % 256;
      original[offset + 3] = y == 0 ? 0 : y == 1 ? 128 : 255;
    }
  }
  auto a = original, b = original;
  check(NoiseCanvasPixels(a, width, height, stride, CanvasPixelFormat::kRgba8, *seed));
  check(NoiseCanvasPixels(b, width, height, stride, CanvasPixelFormat::kRgba8, *other_seed));
  check(a != original && a != b);
  for (size_t y = 0; y < height; ++y) {
    for (size_t x = 0; x < stride; ++x) {
      const size_t offset = y * stride + x;
      if (x >= width * 4 || y == 0 || x % 4 == 3) check(a[offset] == original[offset]);
      else check(std::abs(int(a[offset]) - original[offset]) <= 1);
    }
  }
  auto twice = a;
  check(NoiseCanvasPixels(twice, width, height, stride, CanvasPixelFormat::kRgba8, *seed));
  check(twice == a);
  auto cropped = std::vector<uint8_t>(original.begin() + stride + 4 * 37,
                                     original.begin() + stride + 4 * 44);
  check(NoiseCanvasPixels(cropped, 7, 1, 28, CanvasPixelFormat::kRgba8, *seed, 37, 1));
  check(std::equal(cropped.begin(), cropped.end(), a.begin() + stride + 4 * 37));
  auto bgra = original;
  for (size_t y = 0; y < height; ++y)
    for (size_t x = 0; x < width; ++x)
      std::swap(bgra[y * stride + x * 4], bgra[y * stride + x * 4 + 2]);
  check(NoiseCanvasPixels(bgra, width, height, stride, CanvasPixelFormat::kBgra8, *seed));
  for (size_t y = 0; y < height; ++y)
    for (size_t x = 0; x < width; ++x)
      std::swap(bgra[y * stride + x * 4], bgra[y * stride + x * 4 + 2]);
  check(bgra == a);

  std::array<uint8_t, 8> pixel{127, 65, 201, 255, 0, 0, 0, 0};
  auto guard = pixel;
  check(!NoiseCanvasPixels(pixel, SIZE_MAX, 2, 4, CanvasPixelFormat::kRgba8, *seed));
  check(!NoiseCanvasPixels(pixel, 1, SIZE_MAX, 4, CanvasPixelFormat::kRgba8, *seed));
  check(!NoiseCanvasPixels(pixel, 3, 1, 12, CanvasPixelFormat::kRgba8, *seed));
  check(!NoiseCanvasPixels(pixel, 1, 1, 3, CanvasPixelFormat::kRgba8, *seed));
  check(!NoiseCanvasPixels(pixel, 0, 1, 4, CanvasPixelFormat::kRgba8, *seed));
  check(pixel == guard);
  check(NoiseCanvasPixels(pixel, 1, 1, 4, CanvasPixelFormat::kRgba8, *seed, -1, -2));
  auto negative_again = guard;
  check(NoiseCanvasPixels(negative_again, 1, 1, 4, CanvasPixelFormat::kRgba8, *seed, -1, -2));
  check(pixel == negative_again);

  // All binary16 encodings, including NaN/Inf/signed-zero, with opaque alpha.
  for (uint32_t value = 0; value <= 65535; ++value) {
    auto result = blink::landu::CanvasChannel(value, *seed, true);
    check(blink::landu::CanvasChannel(result, *seed, true) == result);
    check(std::abs(int(result) - int(value)) <= 1);
    if ((value & 0x7fff) == 0 || (value & 0x7c00) == 0x7c00) check(result == value);
  }
  std::array<uint8_t, 16> half{0, 0x38, 0, 0x3c, 0, 0xbc, 0, 0x3c,
                             1, 0, 0, 0x80, 0, 0x7c, 0, 0};
  auto half_before = half;
  check(NoiseCanvasPixels(half, 2, 1, 16, CanvasPixelFormat::kRgbaF16, *seed));
  check(half[6] == 0 && half[7] == 0x3c);
  check(std::equal(half.begin() + 8, half.end(), half_before.begin() + 8));
  auto half_again = half;
  check(NoiseCanvasPixels(half_again, 2, 1, 16, CanvasPixelFormat::kRgbaF16, *seed));
  check(half_again == half);
  std::cout << "{\"suite\":\"native-canvas-noise\",\"assertions\":" << assertions
            << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
