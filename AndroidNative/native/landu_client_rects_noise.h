#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_CLIENT_RECTS_NOISE_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_LANDU_CLIENT_RECTS_NOISE_H_

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <optional>
#include <string_view>

namespace blink::landu {

inline uint64_t MixClientRectsValue(uint64_t value) {
  value ^= value >> 30;
  value *= UINT64_C(0xbf58476d1ce4e5b9);
  value ^= value >> 27;
  value *= UINT64_C(0x94d049bb133111eb);
  return value ^ (value >> 31);
}

inline std::optional<uint64_t> ClientRectsSeed(std::string_view text) {
  if (text.size() != 64)
    return std::nullopt;
  uint64_t seed = UINT64_C(0x6c616e6475726563);
  for (char character : text) {
    unsigned digit;
    if (character >= '0' && character <= '9')
      digit = character - '0';
    else if (character >= 'a' && character <= 'f')
      digit = character - 'a' + 10;
    else
      return std::nullopt;
    seed = MixClientRectsValue(seed ^ digit);
  }
  return seed;
}

struct ClientRectMeasurement {
  double x = 0;
  double y = 0;
  double width = 0;
  double height = 0;

  bool IsFinite() const {
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(width) &&
           std::isfinite(height) && std::isfinite(x + width) &&
           std::isfinite(y + height);
  }
};

inline double ClientRectsAmplitude(uint64_t seed, uint64_t axis) {
  const uint64_t value = MixClientRectsValue(seed ^ axis);
  // At most 1/16384 CSS pixel per edge, with independent horizontal/vertical
  // amplitudes. Keep zero reserved for the disabled policy.
  const double magnitude = static_cast<double>((value & 0xffff) + 1) / 1073741824.;
  return (value & 0x10000) ? magnitude : -magnitude;
}

inline double ClientRectsUnitShift(double position) {
  return position / (1. + std::abs(position));
}

// A bounded, monotonic coordinate transform shared by all fragments, not
// independent offsets on each width. Zero and collapsed dimensions remain zero.
// Compute size as original size plus a correction to avoid cancellation of tiny
// dimensions at large coordinates. Only apply to freshly produced API snapshots.
inline ClientRectMeasurement NoiseClientRect(ClientRectMeasurement rect,
                                             uint64_t seed) {
  if (!rect.IsFinite())
    return rect;
  const double dx = ClientRectsAmplitude(seed, UINT64_C(0x78));
  const double dy = ClientRectsAmplitude(seed, UINT64_C(0x79));
  const double left = ClientRectsUnitShift(rect.x);
  const double top = ClientRectsUnitShift(rect.y);
  const double right = ClientRectsUnitShift(rect.x + rect.width);
  const double bottom = ClientRectsUnitShift(rect.y + rect.height);
  if (rect.x != 0)
    rect.x += dx * left;
  if (rect.y != 0)
    rect.y += dy * top;
  if (rect.width != 0)
    rect.width += dx * (right - left);
  if (rect.height != 0)
    rect.height += dy * (bottom - top);
  return rect;
}

// CSSOM bounding-box rules: ignore empty fragments if any non-empty fragment
// exists, use the first fragment if all are empty, and zeros for an empty list.
class ClientRectsBounds {
 public:
  void Add(ClientRectMeasurement rect) {
    valid_ = valid_ && rect.IsFinite();
    if (!has_first_) {
      first_ = rect;
      has_first_ = true;
    }
    if (rect.width == 0 || rect.height == 0)
      return;
    const double left = std::min(rect.x, rect.x + rect.width);
    const double top = std::min(rect.y, rect.y + rect.height);
    const double right = std::max(rect.x, rect.x + rect.width);
    const double bottom = std::max(rect.y, rect.y + rect.height);
    if (!has_area_) {
      left_ = left;
      top_ = top;
      right_ = right;
      bottom_ = bottom;
      has_area_ = true;
    } else {
      left_ = std::min(left_, left);
      top_ = std::min(top_, top);
      right_ = std::max(right_, right);
      bottom_ = std::max(bottom_, bottom);
    }
  }

  std::optional<ClientRectMeasurement> Get() const {
    if (!valid_)
      return std::nullopt;
    ClientRectMeasurement result = has_area_
        ? ClientRectMeasurement{left_, top_, right_ - left_, bottom_ - top_}
        : first_;
    if (!result.IsFinite())
      return std::nullopt;
    return result;
  }

 private:
  ClientRectMeasurement first_;
  bool valid_ = true;
  bool has_first_ = false;
  bool has_area_ = false;
  double left_ = 0;
  double top_ = 0;
  double right_ = 0;
  double bottom_ = 0;
};

}  // namespace blink::landu
#endif
