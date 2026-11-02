#include "../../native/landu_client_rects_noise.h"

#include <array>
#include <bit>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>

using blink::landu::ClientRectMeasurement;
using blink::landu::ClientRectsBounds;
using blink::landu::NoiseClientRect;

int main() {
  unsigned assertions = 0;
  auto check = [&](bool condition) {
    ++assertions;
    if (!condition) throw std::runtime_error("ClientRects invariant failed at " + std::to_string(assertions));
  };
  auto same = [](ClientRectMeasurement a, ClientRectMeasurement b) {
    return std::bit_cast<uint64_t>(a.x) == std::bit_cast<uint64_t>(b.x) &&
           std::bit_cast<uint64_t>(a.y) == std::bit_cast<uint64_t>(b.y) &&
           std::bit_cast<uint64_t>(a.width) == std::bit_cast<uint64_t>(b.width) &&
           std::bit_cast<uint64_t>(a.height) == std::bit_cast<uint64_t>(b.height);
  };
  const auto seed = blink::landu::ClientRectsSeed(std::string(64, 'a'));
  const auto other = blink::landu::ClientRectsSeed(std::string(64, 'b'));
  check(seed && other && seed != other);
  for (const auto& invalid : {std::string(), std::string(63, 'a'), std::string(65, 'a'),
       std::string(64, 'A'), std::string(64, 'g'), std::string(63, 'a') + "\n"})
    check(!blink::landu::ClientRectsSeed(invalid));
  check(same(NoiseClientRect({}, *seed), {}));
  check(same(NoiseClientRect({-0., -0., -0., -0.}, *seed), {-0., -0., -0., -0.}));
  for (double special : {std::numeric_limits<double>::quiet_NaN(),
       std::numeric_limits<double>::infinity(), -std::numeric_limits<double>::infinity()}) {
    for (auto rect : {ClientRectMeasurement{special, 1, 2, 3}, {1, special, 2, 3},
         {1, 2, special, 3}, {1, 2, 3, special}}) {
      check(same(NoiseClientRect(rect, *seed), rect));
      ClientRectsBounds bounds; bounds.Add(rect); check(!bounds.Get());
    }
  }
  const double maximum = std::numeric_limits<double>::max();
  check(same(NoiseClientRect({maximum, 0, maximum, 1}, *seed), {maximum, 0, maximum, 1}));
  ClientRectsBounds empty;
  check(empty.Get() && same(*empty.Get(), {}));
  ClientRectsBounds collapsed;
  collapsed.Add({20, 30, 0, 5}); collapsed.Add({100, 100, 0, 0});
  check(collapsed.Get() && same(*collapsed.Get(), {20, 30, 0, 5}));
  ClientRectsBounds union_box;
  union_box.Add({-999, -999, 0, 1});
  union_box.Add({10, 20, 30, 10}); union_box.Add({15, 40, 50, 10});
  check(union_box.Get() && same(*union_box.Get(), {10, 20, 55, 30}));
  ClientRectsBounds negative;
  negative.Add({20, 20, -5, -10});
  check(negative.Get() && same(*negative.Get(), {15, 10, 5, 10}));
  ClientRectsBounds overflow;
  overflow.Add({-maximum, 0, 1, 1}); overflow.Add({maximum, 0, 1, 1});
  check(!overflow.Get());

  unsigned differing = 0;
  for (int i = -4000; i <= 4000; ++i) {
    ClientRectMeasurement original{i / 16., i / 32., 50.25, 19.75};
    auto measured = NoiseClientRect(original, *seed);
    check(same(measured, NoiseClientRect(original, *seed)));
    differing += !same(measured, NoiseClientRect(original, *other));
    check(measured.IsFinite() && measured.width > 0 && measured.height > 0);
    constexpr double tolerance = 1. / 16384. + 1e-12;
    check(std::abs(measured.x - original.x) <= tolerance &&
          std::abs(measured.y - original.y) <= tolerance &&
          std::abs((measured.x + measured.width) - (original.x + original.width)) <= tolerance &&
          std::abs((measured.y + measured.height) - (original.y + original.height)) <= tolerance);
    // Adjacent fragments must share the same edge and retain their ordering.
    auto adjacent = NoiseClientRect({original.x + original.width, original.y, 50.25, 19.75}, *seed);
    check(std::abs(measured.x + measured.width - adjacent.x) < 1e-12);
    ClientRectsBounds measured_bounds;
    measured_bounds.Add(measured); measured_bounds.Add(adjacent);
    auto bounds = measured_bounds.Get();
    check(bounds && bounds->x == measured.x && bounds->y == measured.y &&
          std::abs(bounds->width - (adjacent.x + adjacent.width - measured.x)) < 1e-12);
    auto zero_width = NoiseClientRect({original.x, original.y, -0., 10}, *seed);
    check(std::signbit(zero_width.width) && zero_width.width == 0);
  }
  check(differing > 7900);
  for (double coordinate : {-1e30, -1e12, 0., 1e12, 1e30}) {
    for (double size : {1e-30, 1e-12, 1., 1e6}) {
      auto rect = NoiseClientRect({coordinate, coordinate, size, -size}, *seed);
      check(rect.IsFinite() && rect.width > 0 && rect.height < 0);
      check(std::abs(rect.width - size) <= size / 1000. &&
            std::abs(rect.height + size) <= size / 1000.);
    }
  }
  std::cout << "{\"suite\":\"client-rects-noise\",\"assertions\":" << assertions
            << ",\"passed\":true,\"browserRuntimeTested\":false}\n";
}
