#ifndef THIRD_PARTY_BLINK_RENDERER_MODULES_WEBAUDIO_LANDU_AUDIO_READBACK_H_
#define THIRD_PARTY_BLINK_RENDERER_MODULES_WEBAUDIO_LANDU_AUDIO_READBACK_H_

#include <algorithm>
#include <span>

#include "base/command_line.h"
#include "third_party/blink/public/common/landu/landu_audio_noise.h"

namespace blink {

inline std::optional<uint64_t> LanduAudioNoiseSeed() {
  if (!base::CommandLine::InitializedForCurrentProcess())
    return std::nullopt;
  return landu::AudioNoiseSeed(
      base::CommandLine::ForCurrentProcess()->GetSwitchValueASCII(
          "landu-audio-seed"));
}

template <typename Array>
inline void NoiseLanduAudioArray(Array* array, size_t written_count,
                                landu::AudioReadback kind,
                                uint64_t channel = 0) {
  const auto seed = LanduAudioNoiseSeed();
  if (!seed || !array)
    return;
  auto values = std::span(array->AsSpan());
  landu::NoiseAudioValues(values.first(std::min(written_count, values.size())),
                          *seed, kind, channel);
}

}  // namespace blink
#endif
