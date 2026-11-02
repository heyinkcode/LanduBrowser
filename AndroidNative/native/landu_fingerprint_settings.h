#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_FINGERPRINT_SETTINGS_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_FINGERPRINT_SETTINGS_H_

#include <array>
#include <cstddef>
#include <optional>
#include <string>
#include <string_view>

namespace blink::landu {

template <std::size_t N>
inline std::optional<unsigned> ParseChoice(
    std::string_view text,
    const std::array<unsigned, N>& allowed) {
  if (text.empty() || text.size() > 2 || text.front() == '0')
    return std::nullopt;
  unsigned value = 0;
  for (char digit : text) {
    if (digit < '0' || digit > '9')
      return std::nullopt;
    value = value * 10 + static_cast<unsigned>(digit - '0');
  }
  for (unsigned choice : allowed) {
    if (value == choice)
      return value;
  }
  return std::nullopt;
}

inline std::optional<unsigned> HardwareConcurrency(std::string_view text) {
  return ParseChoice(text, std::array<unsigned, 5>{2, 4, 8, 12, 16});
}

inline std::optional<float> DeviceMemory(std::string_view text) {
  auto value = ParseChoice(text, std::array<unsigned, 4>{1, 2, 4, 8});
  if (!value)
    return std::nullopt;
  return static_cast<float>(*value);
}

// Restrict named local fonts only. Downloaded web fonts and character fallback
// remain available so that Chinese text can still be displayed. This is not a
// promise of identical glyph rendering across devices or complete font hiding.
inline bool IsRegionalLocalFont(std::string_view family) {
  if (family.empty())
    return false;
  // Only prefixes are inspected. Bound the buffer without letting long names
  // or padding bypass the filter.
  std::string normalized;
  normalized.reserve(64);
  for (char character : family) {
    if (character == ' ' || character == '\t' || character == '-' ||
        character == '_' || character == '@')
      continue;
    normalized.push_back(character >= 'A' && character <= 'Z'
                             ? character + ('a' - 'A')
                             : character);
    if (normalized.size() == 64)
      break;
  }
  // Include family, localized, full-face and PostScript name variants. Prefix
  // matching includes face suffixes such as Regular, Bold, SC and UI.
  constexpr std::string_view kFamilies[] = {
      "microsoftyahei", "microsoftjhenghei", "simsun", "nsimsun", "simhei",
      "fangsong", "kaiti", "pingfang", "heiti", "songti", "stheiti",
      "stsong", "stkaiti", "stfangsong", "stxihei", "stzhongsong",
      "notosanscjk", "notoserifcjk", "notosanssc", "notosanstc",
      "notosanshk", "notoserifsc", "notoseriftc", "notoserifhk",
      "sourcehansans", "sourcehanserif", "sourcehanmono", "droidsansfallback",
      "misans", "mitype", "mipro", "milanting", "harmonyossans",
      "opposans", "vivosans", "honorsans", "hmos",
      "微软雅黑", "微軟雅黑", "微软正黑体", "微軟正黑體", "宋体", "宋體",
      "新宋体", "新宋體", "黑体", "黑體", "仿宋", "楷体", "楷體",
      "苹方", "蘋方", "华文", "華文", "思源黑体", "思源黑體",
      "思源宋体", "思源宋體", "思源等宽", "思源等寬", "小米兰亭", "小米蘭亭"};
  for (std::string_view prefix : kFamilies) {
    if (normalized.starts_with(prefix))
      return true;
  }
  return false;
}

}  // namespace blink::landu

#endif
