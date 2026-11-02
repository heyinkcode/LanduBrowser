#ifndef COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_POLICY_H_
#define COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_POLICY_H_

#include <algorithm>
#include <cmath>

namespace permissions::landu {

enum class GeolocationMode { kSystem, kCustom, kBlocked };

struct GeolocationPolicy {
  GeolocationMode mode = GeolocationMode::kSystem;
  double latitude = 0;
  double longitude = 0;
  double accuracy = 0;

  bool IsValidCustom() const {
    return mode == GeolocationMode::kCustom && std::isfinite(latitude) &&
           std::isfinite(longitude) && std::isfinite(accuracy) &&
           latitude >= -90 && latitude <= 90 && longitude >= -180 &&
           longitude <= 180 && accuracy >= 1 && accuracy <= 1000000;
  }

  GeolocationPolicy ForAccuracy(bool precise) const {
    if (!IsValidCustom() || precise) {
      return *this;
    }
    GeolocationPolicy result = *this;
    // A low-accuracy subscriber must not receive the precise configured point.
    // Cell centers are at most ~8 km away; a 10 km accuracy radius covers it.
    result.latitude = std::clamp(
        std::floor((latitude + 90) * 10) / 10 - 90 + 0.05, -89.95, 89.95);
    result.longitude = std::clamp(
        std::floor((longitude + 180) * 10) / 10 - 180 + 0.05, -179.95, 179.95);
    result.accuracy = std::max(10000.0, accuracy);
    return result;
  }
};

}  // namespace permissions::landu

#endif  // COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_POLICY_H_
