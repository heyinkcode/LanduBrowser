#include "../../native/landu_geolocation_policy.h"

#include <cstdlib>
#include <iostream>
#include <limits>

int main() {
  using permissions::landu::GeolocationMode;
  using permissions::landu::GeolocationPolicy;
  int checks = 0;
  auto check = [&checks](bool condition) {
    ++checks;
    if (!condition) std::abort();
  };
  GeolocationPolicy point{GeolocationMode::kCustom, 37.5, -122.25, 75};
  check(point.IsValidCustom());
  auto precise = point.ForAccuracy(true);
  check(precise.latitude == 37.5 && precise.longitude == -122.25 && precise.accuracy == 75);
  auto approximate = point.ForAccuracy(false);
  check(approximate.accuracy >= 10000);
  check(approximate.latitude != point.latitude || approximate.longitude != point.longitude);
  check(std::abs(approximate.latitude - point.latitude) <= 0.051);
  check(std::abs(approximate.longitude - point.longitude) <= 0.051);
  check(point.latitude == 37.5 && point.accuracy == 75);
  for (double latitude : {-90.0, -89.99, 0.0, 89.99, 90.0}) {
    for (double longitude : {-180.0, -179.99, 0.0, 179.99, 180.0}) {
      auto edge = GeolocationPolicy{GeolocationMode::kCustom, latitude, longitude, 15000}.ForAccuracy(false);
      check(edge.IsValidCustom());
      check(edge.accuracy == 15000);
      check(std::abs(edge.latitude - latitude) <= 0.051 && std::abs(edge.longitude - longitude) <= 0.051);
    }
  }
  check(!GeolocationPolicy{}.IsValidCustom());
  for (double invalid : {std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()}) {
    check(!GeolocationPolicy{GeolocationMode::kCustom, invalid, 0, 100}.IsValidCustom());
    check(!GeolocationPolicy{GeolocationMode::kCustom, 0, invalid, 100}.IsValidCustom());
    check(!GeolocationPolicy{GeolocationMode::kCustom, 0, 0, invalid}.IsValidCustom());
  }
  std::cout << "{\"suite\":\"native-geolocation-policy\",\"assertions\":" << checks
            << ",\"passed\":true,\"deviceTested\":false}\n";
}
