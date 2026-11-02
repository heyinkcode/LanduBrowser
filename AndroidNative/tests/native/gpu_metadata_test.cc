#include "../../native/landu_gpu_metadata_settings.h"

#include <cstdlib>
#include <iostream>

namespace {
int checks = 0;
void Check(bool value) { if (!value) std::abort(); ++checks; }
}

int main() {
  const std::array<std::string, 6> values = {
      "Google Inc. (Qualcomm)", "ANGLE (Qualcomm, Adreno (TM) 740, OpenGL ES 3.2)",
      "qualcomm", "adreno-7xx", "0x1234", "Adreno (TM) 740"};
  const std::array<bool, 6> all = {true, true, true, true, true, true};
  auto real = landu::ParseGpuMetadata({}, {});
  Check(real.has_value() && !real->enabled);
  auto custom = landu::ParseGpuMetadata(all, values);
  Check(custom.has_value() && custom->enabled && custom->fields == values);
  // Every partial combination must fail, including explicit-empty optional flags.
  for (unsigned mask = 1; mask < 63; ++mask) {
    std::array<bool, 6> present;
    for (size_t i = 0; i < present.size(); ++i) present[i] = (mask & (1u << i)) != 0;
    Check(!landu::ParseGpuMetadata(present, values).has_value());
  }
  Check(!landu::ParseGpuMetadata({}, values).has_value());
  for (size_t i = 0; i < values.size(); ++i) {
    for (const auto& bad : {std::string("x\n"), std::string("x\t"), std::string("x\0", 2),
                            std::string("x\x7f"), std::string("x\xc3\xa9"), std::string(" x"), std::string("x "),
                            std::string(landu::kGpuMetadataLimits[i] + 1, 'x')}) {
      auto altered = values; altered[i] = bad;
      Check(!landu::ParseGpuMetadata(all, altered).has_value());
    }
    auto maximum = values; maximum[i] = std::string(landu::kGpuMetadataLimits[i], 'x');
    Check(landu::ParseGpuMetadata(all, maximum).has_value());
    auto empty = values; empty[i].clear();
    Check(landu::ParseGpuMetadata(all, empty).has_value() == (i >= 3));
  }
  auto hidden = values; hidden[3].clear(); hidden[4].clear(); hidden[5].clear();
  custom = landu::ParseGpuMetadata(all, hidden);
  Check(custom.has_value() && custom->enabled && custom->fields == hidden);
  std::cout << "{\"suite\":\"native-gpu-metadata\",\"assertions\":" << checks
            << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
