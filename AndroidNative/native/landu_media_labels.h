#ifndef THIRD_PARTY_BLINK_RENDERER_MODULES_MEDIASTREAM_LANDU_MEDIA_LABELS_H_
#define THIRD_PARTY_BLINK_RENDERER_MODULES_MEDIASTREAM_LANDU_MEDIA_LABELS_H_

#include "base/command_line.h"
#include "base/strings/string_util.h"
#include "third_party/blink/public/common/landu/landu_media_policy.h"
#include "third_party/blink/renderer/platform/wtf/text/wtf_string.h"

namespace blink {

inline String LanduMediaLabel(const String& original, const String& id,
                             const char* kind, const char* fallback) {
  // Never replace withheld, pre-permission labels with an identifying value.
  if (original.empty() || !base::CommandLine::InitializedForCurrentProcess())
    return original;
  const auto* command = base::CommandLine::ForCurrentProcess();
  const auto mode = landu::ParseMediaMode(command->GetSwitchValueASCII("landu-media-mode"));
  if (mode == landu::MediaIdentityMode::kReal) return original;
  if (mode == landu::MediaIdentityMode::kHiddenLabels) return String("");
  const std::string seed = command->GetSwitchValueASCII("landu-media-seed");
  if (!landu::ValidMediaSeed(seed)) return String("");
  const std::string name = std::string("landu-media-") + kind;
  if (command->HasSwitch(name)) {
    const auto label = landu::DecodeMediaLabel(command->GetSwitchValueASCII(name));
    return label && base::IsStringUTF8(*label) ? String::FromUtf8(*label) : String("");
  }
  return String::FromUtf8(std::string(fallback) + " " + landu::MediaLabelSuffix(seed, id.Utf8()));
}

}  // namespace blink
#endif
