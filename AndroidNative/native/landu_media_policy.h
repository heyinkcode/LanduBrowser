#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_MEDIA_POLICY_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_MEDIA_POLICY_H_

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace blink::landu {

enum class MediaIdentityMode { kReal, kIsolated, kHiddenLabels };

inline bool ValidMediaSeed(std::string_view seed) {
  if (seed.size() != 64) return false;
  for (char c : seed)
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
  return true;
}

inline MediaIdentityMode ParseMediaMode(std::string_view mode) {
  if (mode.empty() || mode == "real") return MediaIdentityMode::kReal;
  if (mode == "isolated") return MediaIdentityMode::kIsolated;
  // A malformed explicit policy must not reveal the real hardware label.
  return MediaIdentityMode::kHiddenLabels;
}

inline std::optional<std::string> DecodeMediaLabel(std::string_view hex) {
  if (hex.size() > 1600 || hex.size() % 2) return std::nullopt;
  auto digit = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
  };
  std::string value;
  for (size_t i = 0; i < hex.size(); i += 2) {
    const int high = digit(hex[i]), low = digit(hex[i + 1]);
    if (high < 0 || low < 0) return std::nullopt;
    const unsigned byte = static_cast<unsigned>(high * 16 + low);
    if (byte < 32 || byte == 127) return std::nullopt;
    value.push_back(static_cast<char>(byte));
  }
  return value;
}

inline std::string MediaLabelSuffix(std::string_view seed, std::string_view id) {
  // Display labels only; device IDs themselves use Chromium's HMAC-SHA256.
  uint64_t state = UINT64_C(14695981039346656037);
  for (std::string_view part : {seed, std::string_view(":"), id}) {
    for (unsigned char c : part) state = (state ^ c) * UINT64_C(1099511628211);
  }
  state ^= state >> 32;
  constexpr std::string_view digits = "0123456789abcdef";
  std::string result(6, '0');
  for (size_t i = 0; i < result.size(); ++i) result[i] = digits[(state >> (i * 4)) & 15];
  return result;
}

}  // namespace blink::landu
#endif
