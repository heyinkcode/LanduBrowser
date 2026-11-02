// Copyright 2026 Landu Browser contributors. BSD-style license; see LICENSE.
#ifndef COMPONENTS_EMBEDDER_SUPPORT_LANDU_USER_AGENT_METADATA_H_
#define COMPONENTS_EMBEDDER_SUPPORT_LANDU_USER_AGENT_METADATA_H_

#include <optional>
#include <set>
#include <string>
#include <string_view>
#include <utility>

#include "base/json/json_reader.h"
#include "base/strings/string_number_conversions.h"
#include "base/values.h"
#include "base/version.h"
#include "third_party/blink/public/common/user_agent/user_agent_metadata.h"

namespace embedder_support::landu {

inline bool IsUaText(std::string_view value, size_t limit) {
  if (value.size() > limit) return false;
  for (unsigned char c : value) if (c < 32 || c > 126) return false;
  return true;
}

inline bool ReadUaText(const base::DictValue& dict, std::string_view key,
                       std::string& output, size_t limit = 512) {
  const std::string* value = dict.FindString(key);
  if (!value || !IsUaText(*value, limit)) return false;
  output = *value;
  return true;
}

inline bool ReadUaBrands(const base::ListValue* list,
                         blink::UserAgentBrandList& output,
                         bool full) {
  if (!list || list->empty() || list->size() > 8) return false;
  std::set<std::string> seen;
  for (const auto& entry : *list) {
    const auto* dict = entry.GetIfDict();
    std::string name, version;
    if (!dict || !ReadUaText(*dict, "brand", name, 64) || name.empty() ||
        !ReadUaText(*dict, "version", version, 32) || !seen.insert(name).second) return false;
    base::Version parsed(version);
    if (!parsed.IsValid() || parsed.components().size() != (full ? 4u : 1u)) return false;
    output.emplace_back();
    output.back().brand = std::move(name);
    output.back().version = std::move(version);
  }
  return true;
}

inline std::optional<blink::UserAgentMetadata> ParseUserAgentMetadata(std::string_view json) {
  if (json.empty() || json.size() > 8192) return std::nullopt;
  auto decoded = base::JSONReader::Read(json, base::JSON_PARSE_RFC);
  if (!decoded || !decoded->is_dict()) return std::nullopt;
  const auto& dict = decoded->GetDict();
  blink::UserAgentMetadata metadata;
  if (!ReadUaText(dict, "fullVersion", metadata.full_version, 32) ||
      !ReadUaText(dict, "platform", metadata.platform, 32) ||
      !ReadUaText(dict, "platformVersion", metadata.platform_version, 32) ||
      !ReadUaText(dict, "architecture", metadata.architecture, 8) ||
      !ReadUaText(dict, "bitness", metadata.bitness, 2) ||
      !ReadUaText(dict, "model", metadata.model, 80)) return std::nullopt;
  auto mobile = dict.FindBool("mobile"), wow64 = dict.FindBool("wow64");
  if (!mobile || !wow64) return std::nullopt;
  metadata.mobile = *mobile; metadata.wow64 = *wow64;
  const std::set<std::string> platforms = {"Android", "Windows", "macOS", "Linux", "Chrome OS"};
  if (!platforms.contains(metadata.platform) ||
      (metadata.architecture != "" && metadata.architecture != "arm" && metadata.architecture != "x86") ||
      (metadata.bitness != "" && metadata.bitness != "32" && metadata.bitness != "64") ||
      (metadata.platform != "Android" && !metadata.model.empty()) ||
      (metadata.wow64 && (metadata.platform != "Windows" || metadata.architecture != "x86" || metadata.bitness != "64"))) return std::nullopt;
  base::Version full(metadata.full_version), platform(metadata.platform_version);
  if (!full.IsValid() || full.components().size() != 4 || full.components()[0] < 10 ||
      full.components()[0] > 999 || !platform.IsValid() || platform.components().size() > 3) return std::nullopt;
  if (!ReadUaBrands(dict.FindList("brands"), metadata.brand_version_list, false) ||
      !ReadUaBrands(dict.FindList("fullVersionList"), metadata.brand_full_version_list, true) ||
      metadata.brand_version_list.size() != metadata.brand_full_version_list.size()) return std::nullopt;
  bool chromium = false;
  for (size_t i = 0; i < metadata.brand_version_list.size(); ++i) {
    const auto& major = metadata.brand_version_list[i];
    const auto& brand_full = metadata.brand_full_version_list[i];
    if (major.brand != brand_full.brand ||
        base::Version(major.version).components()[0] != base::Version(brand_full.version).components()[0]) return std::nullopt;
    if (major.brand == "Chromium" || major.brand == "Google Chrome") {
      if (brand_full.version != metadata.full_version) return std::nullopt;
      chromium = true;
    }
  }
  const auto* factors = dict.FindList("formFactors");
  if (!chromium || !factors || factors->empty() || factors->size() > 7) return std::nullopt;
  const std::set<std::string> allowed = {"Desktop", "Mobile", "Tablet", "Automotive", "XR", "EInk", "Watch"};
  std::set<std::string> seen;
  for (const auto& value : *factors) {
    const auto* factor = value.GetIfString();
    if (!factor || !allowed.contains(*factor) || !seen.insert(*factor).second) return std::nullopt;
    metadata.form_factors.push_back(*factor);
  }
  return metadata;
}

}  // namespace landu
#endif
