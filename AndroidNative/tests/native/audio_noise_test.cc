#include "../../native/landu_audio_noise.h"

#include <array>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using blink::landu::AudioReadback;
using blink::landu::NoiseAudioValues;

int main() {
  unsigned assertions = 0;
  auto check = [&](bool condition) {
    ++assertions;
    if (!condition) throw std::runtime_error("Audio readback invariant failed");
  };
  const auto seed = blink::landu::AudioNoiseSeed(std::string(64, 'a'));
  const auto other = blink::landu::AudioNoiseSeed(std::string(64, 'b'));
  check(seed && other && seed != other);
  for (const auto& invalid : {std::string(), std::string(63, 'a'),
       std::string(65, 'a'), std::string(64, 'A'), std::string(64, 'g'),
       std::string(63, 'a') + "\n"})
    check(!blink::landu::AudioNoiseSeed(invalid));
  auto bits = [](float value) { return std::bit_cast<uint32_t>(value); };
  const std::array<uint32_t, 10> special = {0, 0x80000000, 1, 0x80000001,
      0x007fffff, 0x807fffff, 0x7f800000, 0xff800000, 0x7fc12345, 0xffc12345};
  for (auto kind : {AudioReadback::kOffline, AudioReadback::kFrequency, AudioReadback::kTimeDomain, AudioReadback::kWorklet}) {
    std::vector<float> values;
    for (auto value : special) values.push_back(std::bit_cast<float>(value));
    NoiseAudioValues(values, *seed, kind);
    for (size_t i = 0; i < special.size(); ++i) check(bits(values[i]) == special[i]);
    std::vector<float> signal(8192);
    for (size_t i = 0; i < signal.size(); ++i)
      signal[i] = kind == AudioReadback::kFrequency ? -30.f - i * .01f : std::sin(i * .13) * .7;
    auto a = signal, b = signal, c = signal, again = signal;
    NoiseAudioValues(a, *seed, kind);
    NoiseAudioValues(b, *other, kind);
    NoiseAudioValues(c, *seed, kind, 1);
    NoiseAudioValues(again, *seed, kind);
    check(a != signal && a != b && a != c && a == again);
    for (size_t i = 0; i < signal.size(); ++i) {
      check((bits(a[i]) & ~1u) == (bits(signal[i]) & ~1u));
      check(std::signbit(a[i]) == std::signbit(signal[i]));
    }
    for (int read = 0; read < 10; ++read) NoiseAudioValues(again, *seed, kind);
    check(a == again);
    // Short destinations must be prefixes of the full read, without indexing
    // noise by destination length. Do not alter caller-owned tail/canary data.
    for (size_t count : {0u, 1u, 17u, 256u, 8192u}) {
      auto short_read = signal;
      NoiseAudioValues(std::span(short_read).first(count), *seed, kind);
      for (size_t i = 0; i < signal.size(); ++i)
        check(bits(short_read[i]) == bits(i < count ? a[i] : signal[i]));
    }
  }
  // Worklet input processing must leave the source bus intact; the copied
  // channel remains bounded even when passed through repeated graph stages.
  const std::vector<float> source(128, 0.1234f);
  auto input_copy = source;
  NoiseAudioValues(input_copy, *seed, AudioReadback::kWorklet, UINT64_C(1) << 32);
  check(input_copy != source);
  auto next_input = input_copy;
  NoiseAudioValues(next_input, *seed, AudioReadback::kWorklet, (UINT64_C(2) << 32) | 1);
  for (size_t i = 0; i < source.size(); ++i) {
    check(bits(source[i]) == bits(0.1234f));
    check((bits(next_input[i]) & ~1u) == (bits(source[i]) & ~1u));
  }
  for (auto kind : {AudioReadback::kTimeDomain, AudioReadback::kFrequency}) {
    std::vector<uint8_t> original(4096);
    for (size_t i = 0; i < original.size(); ++i) original[i] = i % 256;
    auto a = original, b = original;
    NoiseAudioValues(a, *seed, kind);
    NoiseAudioValues(b, *other, kind);
    check(a != original && a != b);
    for (size_t i = 0; i < original.size(); ++i) {
      check(std::abs(int(a[i]) - int(original[i])) <= 1);
      if (original[i] == 0 || original[i] == 255 ||
          (kind == AudioReadback::kTimeDomain && original[i] == 128))
        check(a[i] == original[i]);
    }
    b = a;
    for (int read = 0; read < 10; ++read) NoiseAudioValues(b, *seed, kind);
    check(a == b);
    std::vector<uint8_t> silence(4096, kind == AudioReadback::kTimeDomain ? 128 : 0);
    const auto silent_copy = silence;
    NoiseAudioValues(silence, *seed, kind);
    check(silence == silent_copy);
  }
  std::cout << "{\"suite\":\"native-audio-noise\",\"assertions\":" << assertions
            << ",\"passed\":true,\"browserTested\":false,\"deviceTested\":false}\n";
}
