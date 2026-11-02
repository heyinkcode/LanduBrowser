#include "../../native/landu_webgl_noise.h"

#include <bit>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace blink::landu;
namespace {
unsigned checks = 0;
void Check(bool value) { ++checks; if (!value) throw std::runtime_error("WebGL readback invariant failed"); }
}

int main() {
  const auto seed = CanvasNoiseSeed(std::string(64, 'a')).value();
  const auto other = CanvasNoiseSeed(std::string(64, 'b')).value();
  const auto rgba = DescribeWebGLPixels(0x1908, 0x1401).value();
  Check(!DescribeWebGLPixels(0xdead, 0x1401)); Check(!DescribeWebGLPixels(0x1908, 0xdead));
  Check(!DescribeWebGLPixels(0x1908, 0x8363)); Check(!DescribeWebGLPixels(0x1907, 0x8033));

  // Each allowed scalar encoding and component count, including alpha-only
  // and integer formats. Source patterns are not a mirror of the algorithm.
  for (uint32_t format : {0x1903u, 0x8d94u, 0x8227u, 0x8228u, 0x1907u, 0x8d98u,
                          0x1908u, 0x8d99u, 0x1906u, 0x1909u, 0x190au}) {
    for (uint32_t type : {0x1400u, 0x1401u, 0x1402u, 0x1403u, 0x1404u, 0x1405u, 0x1406u, 0x140bu, 0x8d61u}) {
      auto description = DescribeWebGLPixels(format, type); Check(bool(description));
      std::vector<uint8_t> source(256 * description->pixel_bytes);
      for (size_t i = 0; i < source.size(); ++i) source[i] = (i * 73 + 1) & 0xff;
      auto result = source;
      Check(NoiseWebGLPixels(result, 256, 1, *description, seed));
      auto again = result; Check(NoiseWebGLPixels(again, 256, 1, *description, seed)); Check(again == result);
      for (size_t pixel = 0; pixel < 256; ++pixel) {
        for (unsigned channel = 0; channel < description->channels; ++channel) {
          const auto start = pixel * description->pixel_bytes + channel * description->component_bytes;
          const auto before = ReadWebGLWord(std::span(source).subspan(start, description->component_bytes));
          const auto after = ReadWebGLWord(std::span(result).subspan(start, description->component_bytes));
          if (int(channel) == description->alpha) Check(before == after);
          else Check((before > after ? before - after : after - before) <= 1);
        }
      }
    }
  }

  // Signed zero, NaN payloads and infinities are never changed.
  for (uint32_t bits : {0u, 0x80000000u, 0x7f800000u, 0xff800000u, 0x7fc12345u})
    Check(WebGLChannel(bits, seed, 0x7fffffff, 0x7f800000) == bits);
  for (uint32_t bits = 0; bits < 65536; ++bits) {
    auto value = WebGLChannel(bits, seed, 0x7fff, 0x7c00);
    Check(WebGLChannel(value, seed, 0x7fff, 0x7c00) == value);
    Check((value > bits ? value - bits : bits - value) <= 1);
    if (!(bits & 0x7fff) || (bits & 0x7c00) == 0x7c00) Check(value == bits);
  }

  // Packed colour channels change by at most one native unit; alpha and
  // shared exponent fields remain unchanged. Cover every 16-bit word.
  for (uint32_t type : {0x8363u, 0x8033u, 0x8034u, 0x8368u, 0x8c3bu, 0x8c3eu}) {
    auto description = DescribeWebGLPixels(type == 0x8363 || type == 0x8c3b || type == 0x8c3e ? 0x1907 : 0x1908, type).value();
    const size_t count = 65536;
    std::vector<uint8_t> source(count * description.pixel_bytes);
    for (size_t i = 0; i < count; ++i) {
      uint32_t value = description.pixel_bytes == 2 ? i : uint32_t(i * 0x9e3779b9u);
      WriteWebGLWord(std::span(source).subspan(i * description.pixel_bytes, description.pixel_bytes), value);
    }
    auto result = source; Check(NoiseWebGLPixels(result, count, 1, description, seed));
    auto again = result; Check(NoiseWebGLPixels(again, count, 1, description, seed)); Check(again == result);
    for (size_t i = 0; i < count; ++i) {
      const auto before = ReadWebGLWord(std::span(source).subspan(i * description.pixel_bytes, description.pixel_bytes));
      const auto after = ReadWebGLWord(std::span(result).subspan(i * description.pixel_bytes, description.pixel_bytes));
      const uint32_t allowed = type == 0x8363 ? 0x821u : type == 0x8033 ? 0x1110u : type == 0x8034 ? 0x842u
          : type == 0x8368 ? 0x100401u : type == 0x8c3b ? 0x400801u : 0x40201u;
      Check(!((before ^ after) & ~allowed));
      const uint32_t preserved = type == 0x8033 ? 0xf : type == 0x8034 ? 1 : type == 0x8368 ? 0xc0000000u : type == 0x8c3e ? 0xf8000000u : 0;
      Check((before & preserved) == (after & preserved));
      if (type == 0x8033 && !(before & 15)) Check(before == after);
      if (type == 0x8034 && !(before & 1)) Check(before == after);
      if (type == 0x8368 && !(before & 0xc0000000u)) Check(before == after);
    }
  }

  constexpr size_t width = 31, height = 7;
  std::vector<uint8_t> source(width * height * 4);
  for (size_t i = 0; i < source.size(); i += 4) {
    source[i] = i & 0xff; source[i + 1] = 73; source[i + 2] = 201; source[i + 3] = i % 3 ? 255 : 0;
  }
  auto noised = source, different = source;
  Check(NoiseWebGLPixels(noised, width, height, rgba, seed, 0, height - 1, -1));
  Check(NoiseWebGLPixels(different, width, height, rgba, other, 0, height - 1, -1)); Check(noised != source && noised != different);
  auto top_down = source;
  for (size_t y = 0; y < height; ++y) std::copy_n(source.begin() + y * width * 4, width * 4, top_down.begin() + (height - 1 - y) * width * 4);
  Check(NoiseCanvasPixels(top_down, width, height, width * 4, CanvasPixelFormat::kRgba8, seed));
  for (size_t y = 0; y < height; ++y) Check(std::equal(noised.begin() + y * width * 4, noised.begin() + (y + 1) * width * 4, top_down.begin() + (height - 1 - y) * width * 4));
  for (size_t y = 0; y < height; ++y) {
    std::vector<uint8_t> crop(source.begin() + (y * width + 3) * 4, source.begin() + (y * width + 15) * 4);
    Check(NoiseWebGLPixels(crop, 12, 1, rgba, seed, 3, height - 1 - y, -1));
    Check(std::equal(crop.begin(), crop.end(), noised.begin() + (y * width + 3) * 4));
  }
  for (size_t alignment : {1, 2, 4, 8}) {
    Check(!MakeWebGLPackLayout(width, height, 4, alignment, 5, 0, 0));
    Check(!MakeWebGLPackLayout(width, height, 4, alignment, 31, 2, 3));
    Check(!MakeWebGLPackLayout(width, height, 4, alignment, 0, 2, 3));
    for (size_t row_length : {0, 33, 37, 68}) {
      auto layout = MakeWebGLPackLayout(width, height, 4, alignment, row_length, row_length ? 2 : 0, 3).value();
      std::vector<uint8_t> destination(layout.end_bytes + 32, 0xa5), expected = destination;
      for (size_t y = 0; y < height; ++y) std::copy_n(noised.begin() + y * width * 4, width * 4,
          expected.begin() + layout.skip_bytes + y * layout.row_bytes);
      Check(CopyWebGLPackedRows(noised, destination, layout)); Check(destination == expected);
      auto short_source = std::span(noised).first(noised.size() - 1);
      Check(!CopyWebGLPackedRows(short_source, destination, layout)); Check(destination == expected);
    }
  }
  auto rgb_layout = MakeWebGLPackLayout(3, 2, 3, 4, 5, 1, 2).value();
  Check(rgb_layout.row_bytes == 16 && rgb_layout.row_size == 9 && rgb_layout.skip_bytes == 35
        && rgb_layout.end_bytes == 60 && rgb_layout.snapshot_bytes == 18);
  for (size_t bytes : {1, 2, 3, 4, 8, 16}) {
    for (size_t alignment : {1, 2, 4, 8}) {
      auto layout = MakeWebGLPackLayout(3, 5, bytes, alignment, 7, 2, 1).value();
      std::vector<uint8_t> tight(layout.snapshot_bytes, 0x37), destination(layout.end_bytes + 11, 0xa5);
      Check(CopyWebGLPackedRows(tight, destination, layout));
      size_t changed = 0;
      for (auto value : destination) { Check(value == 0x37 || value == 0xa5); changed += value == 0x37; }
      Check(changed == tight.size());
    }
  }
  Check(!MakeWebGLPackLayout(SIZE_MAX, 2, 4, 4)); Check(!MakeWebGLPackLayout(1, SIZE_MAX, 4, 4));
  Check(!MakeWebGLPackLayout(1, 1, 4, 3)); Check(!MakeWebGLPackLayout(1, 1, 4, 4, SIZE_MAX));
  Check(!MakeWebGLPackLayout(1, 1, 4, 4, 0, SIZE_MAX)); Check(!MakeWebGLPackLayout(1, 1, 4, 4, 0, 0, SIZE_MAX));
  for (size_t offset = 0; offset < 32; ++offset) {
    Check(ValidWebGLPackOffset(offset, 1));
    Check(ValidWebGLPackOffset(offset, 2) == (offset % 2 == 0));
    Check(ValidWebGLPackOffset(offset, 4) == (offset % 4 == 0));
  }
  Check(!ValidWebGLPackOffset(0, 0)); Check(!ValidWebGLPackOffset(0, 3));
  auto guarded = source;
  Check(!NoiseWebGLPixels(guarded, SIZE_MAX, height, rgba, seed)); Check(guarded == source);
  Check(!NoiseWebGLPixels(guarded, width, height, rgba, seed, INT64_MAX)); Check(guarded == source);
  auto invalid = rgba; invalid.alpha = 9;
  Check(!NoiseWebGLPixels(guarded, width, height, invalid, seed)); Check(guarded == source);
  std::vector<uint8_t> cleared(source.size(), 0);
  Check(NoiseWebGLPixels(cleared, width, height, rgba, seed, INT32_MIN, int64_t(INT32_MAX) + 20000, -1));
  Check(std::all_of(cleared.begin(), cleared.end(), [](auto value) { return value == 0; }));
  std::cout << "{\"suite\":\"native-webgl-pixel-readback\",\"assertions\":" << checks
            << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
