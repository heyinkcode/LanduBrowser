#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_GPU_METADATA_SETTINGS_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_GPU_METADATA_SETTINGS_H_

#include <array>
#include <optional>
#include <string>
#include <utility>

namespace landu {

inline constexpr std::array<const char*, 6> kGpuMetadataSwitches = {
    "landu-webgl-vendor", "landu-webgl-renderer", "landu-webgpu-vendor",
    "landu-webgpu-architecture", "landu-webgpu-device", "landu-webgpu-description"};
inline constexpr std::array<size_t, 6> kGpuMetadataLimits = {128, 512, 64, 128, 64, 512};

struct GpuMetadata {
  bool enabled = false;
  std::array<std::string, 6> fields;
};

inline std::optional<GpuMetadata> ParseGpuMetadata(
    const std::array<bool, 6>& present,
    std::array<std::string, 6> values) {
  size_t count = 0;
  for (bool value : present) count += value ? 1 : 0;
  if (count == 0) {
    for (const auto& value : values) {
      if (!value.empty()) return std::nullopt;
    }
    return GpuMetadata{};
  }
  if (count != present.size()) return std::nullopt;
  for (size_t i = 0; i < values.size(); ++i) {
    const auto& value = values[i];
    if (value.size() > kGpuMetadataLimits[i] || (i < 3 && value.empty()))
      return std::nullopt;
    if (!value.empty() && (value.front() == ' ' || value.back() == ' '))
      return std::nullopt;
    for (unsigned char character : value) {
      if (character < 32 || character > 126) return std::nullopt;
    }
  }
  return GpuMetadata{true, std::move(values)};
}

}  // namespace landu
#endif  // THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_GPU_METADATA_SETTINGS_H_
