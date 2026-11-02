#ifndef THIRD_PARTY_BLINK_RENDERER_PLATFORM_GRAPHICS_LANDU_GPU_METADATA_H_
#define THIRD_PARTY_BLINK_RENDERER_PLATFORM_GRAPHICS_LANDU_GPU_METADATA_H_

#include "base/check.h"
#include "base/command_line.h"
#include "base/no_destructor.h"
#include "third_party/blink/public/common/landu/landu_gpu_metadata_settings.h"

namespace blink {

inline const ::landu::GpuMetadata& GetLanduGpuMetadata() {
  static const base::NoDestructor<::landu::GpuMetadata> metadata([] {
    const auto* command = base::CommandLine::ForCurrentProcess();
    std::array<bool, 6> present;
    std::array<std::string, 6> values;
    for (size_t i = 0; i < ::landu::kGpuMetadataSwitches.size(); ++i) {
      present[i] = command->HasSwitch(::landu::kGpuMetadataSwitches[i]);
      values[i] = command->GetSwitchValueASCII(::landu::kGpuMetadataSwitches[i]);
    }
    auto parsed = ::landu::ParseGpuMetadata(present, std::move(values));
    CHECK(parsed.has_value());
    return *parsed;
  }());
  return *metadata;
}

}  // namespace blink
#endif  // THIRD_PARTY_BLINK_RENDERER_PLATFORM_GRAPHICS_LANDU_GPU_METADATA_H_
