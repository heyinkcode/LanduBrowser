#include "../../native/landu_android_identity.h"

#include <cstdlib>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

namespace {
unsigned checks = 0;
void Check(bool condition) {
  ++checks;
  if (!condition)
    std::abort();
}
}  // namespace

int main() {
  using embedder_support::landu::ParseAndroidIdentity;
  auto system = ParseAndroidIdentity(false, false, "", "");
  Check(system.has_value() && !system->enabled);
  Check(!ParseAndroidIdentity(true, false, "15.0.0", ""));
  Check(!ParseAndroidIdentity(false, true, "", "Pixel 8"));
  Check(!ParseAndroidIdentity(false, false, "15.0.0", ""));
  for (const auto& [version, ua] : std::vector<std::pair<std::string, std::string>>{
           {"10.0.0", "10"}, {"15.1.0", "15.1"}, {"16.2.3", "16.2.3"},
           {"99.999.999", "99.999.999"}}) {
    auto identity = ParseAndroidIdentity(true, true, version, "Pixel 8");
    Check(identity.has_value() && identity->enabled);
    Check(identity->platform_version == version && identity->ua_version == ua);
    Check(identity->model == "Pixel 8");
  }
  auto hidden = ParseAndroidIdentity(true, true, "15.0.0", "");
  Check(hidden.has_value() && hidden->enabled && hidden->model.empty());
  for (std::string version : {"", "15", "15.0", "9.0.0", "100.0.0", "015.0.0",
                              "15.00.0", "15.0.00", "15.-1.0", "15.0.1000",
                              "15.0.0.0", "15..0", "15.0.0 ", "15.0.0\r\n"}) {
    Check(!ParseAndroidIdentity(true, true, version, "Pixel 8"));
  }
  for (std::string model : std::vector<std::string>{" Pixel 8", "Pixel 8 ", "x;y", "x/y", "x\"y", "x\ty", "x\ny",
                            std::string(81, 'x'), std::string("x\0y", 3), "\xe5\xb0\x8f"}) {
    Check(!ParseAndroidIdentity(true, true, "15.0.0", model));
  }
  for (unsigned c = 0; c < 128; ++c) {
    char value = static_cast<char>(c);
    bool allowed = (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') ||
                   (value >= '0' && value <= '9') || value == ' ' || value == '.' || value == '_' || value == '-';
    Check(ParseAndroidIdentity(true, true, "16.0.0", std::string("x") + value + "y").has_value() == allowed);
  }
  std::cout << "{\"suite\":\"native-android-identity-parser\",\"assertions\":" << checks
            << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
