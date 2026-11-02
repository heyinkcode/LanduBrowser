#ifndef COMPONENTS_EMBEDDER_SUPPORT_LANDU_ANDROID_IDENTITY_H_
#define COMPONENTS_EMBEDDER_SUPPORT_LANDU_ANDROID_IDENTITY_H_

#include <array>
#include <optional>
#include <string>
#include <string_view>

namespace embedder_support::landu {

struct AndroidIdentity {
  bool enabled = false;
  std::string platform_version;
  std::string ua_version;
  std::string model;
};

inline std::optional<AndroidIdentity> ParseAndroidIdentity(
    bool has_version, bool has_model, std::string_view version,
    std::string_view model) {
  if (!has_version && !has_model) {
    if (!version.empty() || !model.empty())
      return std::nullopt;
    return AndroidIdentity();
  }
  if (!has_version || !has_model || version.size() < 6 ||
      version.size() > 10 || model.size() > 80 ||
      (!model.empty() && (model.front() == ' ' || model.back() == ' '))) {
    return std::nullopt;
  }
  std::array<std::string_view, 3> parts;
  std::size_t start = 0;
  for (std::size_t i = 0; i < parts.size(); ++i) {
    std::size_t end = version.find('.', start);
    if ((i == 2) != (end == std::string_view::npos))
      return std::nullopt;
    if (end == std::string_view::npos)
      end = version.size();
    parts[i] = version.substr(start, end - start);
    if (parts[i].empty() || parts[i].size() > (i == 0 ? 2u : 3u) ||
        (parts[i].size() > 1 && parts[i].front() == '0')) {
      return std::nullopt;
    }
    unsigned value = 0;
    for (char c : parts[i]) {
      if (c < '0' || c > '9')
        return std::nullopt;
      value = value * 10 + static_cast<unsigned>(c - '0');
    }
    if (i == 0 && (value < 10 || value > 99))
      return std::nullopt;
    start = end + 1;
  }
  for (char c : model) {
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
          (c >= '0' && c <= '9') || c == ' ' || c == '.' || c == '_' ||
          c == '-')) {
      return std::nullopt;
    }
  }
  std::string ua_version(parts[0]);
  if (parts[2] != "0") {
    ua_version += "." + std::string(parts[1]) + "." + std::string(parts[2]);
  } else if (parts[1] != "0") {
    ua_version += "." + std::string(parts[1]);
  }
  return AndroidIdentity{true, std::string(version), ua_version, std::string(model)};
}

}  // namespace embedder_support::landu

#endif  // COMPONENTS_EMBEDDER_SUPPORT_LANDU_ANDROID_IDENTITY_H_
