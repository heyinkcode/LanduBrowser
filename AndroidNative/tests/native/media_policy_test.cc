#include "../../native/landu_media_policy.h"
#include <iostream>
#include <stdexcept>

int main() {
  using namespace blink::landu;
  unsigned assertions = 0;
  auto check = [&](bool ok) { ++assertions; if (!ok) throw std::runtime_error("Media policy check failed"); };
  const std::string seed(64, 'a'), other(64, 'b');
  check(ValidMediaSeed(seed) && ValidMediaSeed(other));
  for (auto invalid : {std::string(), std::string(63, 'a'), std::string(65, 'a'), std::string(64, 'A'), std::string(64, 'g')})
    check(!ValidMediaSeed(invalid));
  check(ParseMediaMode("") == MediaIdentityMode::kReal);
  check(ParseMediaMode("real") == MediaIdentityMode::kReal);
  check(ParseMediaMode("isolated") == MediaIdentityMode::kIsolated);
  check(ParseMediaMode("hidden-labels") == MediaIdentityMode::kHiddenLabels);
  check(ParseMediaMode("unknown") == MediaIdentityMode::kHiddenLabels);
  check(DecodeMediaLabel("4d6963") == "Mic");
  check(DecodeMediaLabel("e9baa6e5858be9a38e") == "\xe9\xba\xa6\xe5\x85\x8b\xe9\xa3\x8e");
  check(DecodeMediaLabel("") == "");
  for (auto invalid : {std::string("4"), std::string("xx"), std::string("00"), std::string("0a"), std::string("7f"), std::string(1602, 'a')})
    check(!DecodeMediaLabel(invalid));
  for (int i = 0; i < 1000; ++i) {
    const auto id = "device-" + std::to_string(i);
    const auto label = MediaLabelSuffix(seed, id);
    check(label.size() == 6 && label.find_first_not_of("0123456789abcdef") == std::string::npos);
    check(label == MediaLabelSuffix(seed, id));
    check(label != MediaLabelSuffix(other, id));
  }
  std::cout << "{\"suite\":\"media-policy\",\"assertions\":" << assertions
            << ",\"passed\":true,\"captureRuntimeTested\":false}\n";
}
