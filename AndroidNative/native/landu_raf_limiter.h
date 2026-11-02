// Copyright 2026 Landu Browser contributors. BSD-style license; see LICENSE.
#ifndef THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_RAF_LIMITER_H_
#define THIRD_PARTY_BLINK_PUBLIC_COMMON_LANDU_RAF_LIMITER_H_

#include <cmath>

namespace blink::landu {

// Gates actual display callbacks without modifying their timestamps. Each
// execution context owns its phase, so independent documents/workers cannot
// consume one another's frame budget. No catch-up burst after idle/background.
class RafLimiter {
 public:
  bool ShouldDispatch(double timestamp_ms, int max_fps) {
    if (max_fps < 1 || max_fps > 240 || !std::isfinite(timestamp_ms)) return true;
    const double period = 1000.0 / max_fps;
    if (!initialized_ || max_fps != max_fps_ || timestamp_ms < last_frame_ms_ ||
        timestamp_ms - last_frame_ms_ > 2 * period) {
      initialized_ = true; max_fps_ = max_fps;
      next_frame_ms_ = timestamp_ms + period;
      last_frame_ms_ = timestamp_ms;
      return true;
    }
    last_frame_ms_ = timestamp_ms;
    // Account for the browser's timestamp precision; keep the original value.
    if (timestamp_ms + 0.05 < next_frame_ms_) return false;
    do { next_frame_ms_ += period; } while (next_frame_ms_ <= timestamp_ms + 0.05);
    return true;
  }

 private:
  bool initialized_ = false;
  int max_fps_ = 0;
  double last_frame_ms_ = 0;
  double next_frame_ms_ = 0;
};

}  // namespace landu
#endif
