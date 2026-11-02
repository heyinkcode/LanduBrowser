#ifndef COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_SETTINGS_H_
#define COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_SETTINGS_H_

#include "base/command_line.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_split.h"
#include "components/permissions/landu_geolocation_policy.h"

namespace permissions::landu {

inline GeolocationPolicy ReadGeolocationPolicy() {
  const auto* command = base::CommandLine::ForCurrentProcess();
  if (!command->HasSwitch("landu-geolocation-mode") &&
      !command->HasSwitch("landu-geolocation-position")) {
    return {};
  }
  GeolocationPolicy result;
  result.mode = GeolocationMode::kBlocked;
  if (command->GetSwitchValueASCII("landu-geolocation-mode") != "custom") {
    return result;
  }
  const std::string position =
      command->GetSwitchValueASCII("landu-geolocation-position");
  if (position.size() > 128) {
    return result;
  }
  auto parts = base::SplitStringPiece(position, ",", base::KEEP_WHITESPACE,
                                      base::SPLIT_WANT_ALL);
  if (parts.size() != 3 ||
      !base::StringToDouble(parts[0], &result.latitude) ||
      !base::StringToDouble(parts[1], &result.longitude) ||
      !base::StringToDouble(parts[2], &result.accuracy)) {
    return result;
  }
  result.mode = GeolocationMode::kCustom;
  if (!result.IsValidCustom()) {
    result.mode = GeolocationMode::kBlocked;
  }
  return result;
}

}  // namespace permissions::landu

#endif  // COMPONENTS_PERMISSIONS_LANDU_GEOLOCATION_SETTINGS_H_
