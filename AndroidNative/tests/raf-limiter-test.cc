#include "../native/landu_raf_limiter.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

int main() {
  int checks = 0;
  for (int hardware : {30, 60, 90, 120, 144, 240}) {
    for (int requested : {0, 15, 30, 60, 90, 120, 240}) {
      blink::landu::RafLimiter limiter;
      int dispatched = 0;
      for (int frame = 0; frame < hardware * 10; ++frame) {
        const double real_timestamp = frame * 1000.0 / hardware;
        if (limiter.ShouldDispatch(real_timestamp, requested)) ++dispatched;
      }
      const int expected = requested == 0 ? hardware * 10 : std::min(hardware, requested) * 10;
      assert(std::abs(dispatched - expected) <= 1);
      ++checks;
    }
  }
  blink::landu::RafLimiter limiter, other_document;
  assert(limiter.ShouldDispatch(100, 30));
  assert(!limiter.ShouldDispatch(116.67, 30));
  assert(other_document.ShouldDispatch(116.67, 30));
  assert(limiter.ShouldDispatch(133.33, 30));
  assert(limiter.ShouldDispatch(5000, 30));
  assert(!limiter.ShouldDispatch(5000, 30));
  assert(limiter.ShouldDispatch(0, 30));
  assert(limiter.ShouldDispatch(1, 60));
  assert(limiter.ShouldDispatch(2, 0));
  assert(limiter.ShouldDispatch(std::numeric_limits<double>::quiet_NaN(), 60));
  checks += 10;
  std::cout << "Native RAF limiter: " << checks << " scenarios passed\n";
}
