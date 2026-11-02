#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_AUDIO_NOISE_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_AUDIO_NOISE_H_

#include <bit>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>

namespace blink::landu {

inline uint64_t MixAudioValue(uint64_t value) {
  value ^= value >> 30;
  value *= UINT64_C(0xbf58476d1ce4e5b9);
  value ^= value >> 27;
  value *= UINT64_C(0x94d049bb133111eb);
  return value ^ (value >> 31);
}

inline std::optional<uint64_t> AudioNoiseSeed(std::string_view text) {
  if (text.size() != 64)
    return std::nullopt;
  uint64_t seed = UINT64_C(0x6c616e6475617564);
  for (char character : text) {
    unsigned digit;
    if (character >= '0' && character <= '9')
      digit = character - '0';
    else if (character >= 'a' && character <= 'f')
      digit = character - 'a' + 10;
    else
      return std::nullopt;
    seed = MixAudioValue(seed ^ digit);
  }
  return seed;
}

enum class AudioReadback : uint64_t {
  kOffline = 0x6f66666c696e65,
  kFrequency = 0x6672657175656e63,
  kTimeDomain = 0x74696d65,
  kWorklet = 0x776f726b6c6574,
};

inline uint64_t AudioSampleKey(uint64_t seed, AudioReadback kind,
                               uint64_t channel, size_t index) {
  return MixAudioValue(seed ^ static_cast<uint64_t>(kind) ^
                       MixAudioValue(channel + UINT64_C(0x9e3779b97f4a7c15)) ^
                       MixAudioValue(index));
}

// Set one mantissa bit, rather than adding new noise on every access. Normal
// finite samples change by at most one ULP. Preserve silence (including -0),
// subnormals, infinities and NaNs bit-for-bit. Reapplying is idempotent.
inline void NoiseAudioValues(std::span<float> values, uint64_t seed,
                             AudioReadback kind, uint64_t channel = 0) {
  for (size_t i = 0; i < values.size(); ++i) {
    const uint32_t bits = std::bit_cast<uint32_t>(values[i]);
    const uint32_t exponent = bits & UINT32_C(0x7f800000);
    if (exponent == 0 || exponent == UINT32_C(0x7f800000))
      continue;
    const uint32_t pair = bits & ~UINT32_C(1);
    const uint32_t bit = static_cast<uint32_t>(
        MixAudioValue(AudioSampleKey(seed, kind, channel, i) ^ pair) & 1);
    values[i] = std::bit_cast<float>(pair | bit);
  }
}

// Byte analysers have 256 levels; limit the change to one level. A flat silent
// time-domain signal is 128, whereas silent frequency data is 0.
inline void NoiseAudioValues(std::span<uint8_t> values, uint64_t seed,
                             AudioReadback kind, uint64_t channel = 0) {
  for (size_t i = 0; i < values.size(); ++i) {
    const uint8_t value = values[i];
    if (value == 0 || value == 255 ||
        (kind == AudioReadback::kTimeDomain && (value == 128 || value == 129)))
      continue;
    const unsigned pair = value & ~1u;
    values[i] = static_cast<uint8_t>(pair |
        (MixAudioValue(AudioSampleKey(seed, kind, channel, i) ^ pair) & 1));
  }
}

}  // namespace blink::landu
#endif
