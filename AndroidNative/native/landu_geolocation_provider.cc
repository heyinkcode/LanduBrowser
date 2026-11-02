#include "chrome/browser/landu_geolocation_provider.h"

#include <utility>

#include "base/location.h"
#include "base/time/time.h"

namespace landu {

GeolocationProvider::GeolocationProvider(permissions::landu::GeolocationPolicy policy)
    : policy_(policy) {}

GeolocationProvider::~GeolocationProvider() = default;

void GeolocationProvider::FillDiagnostics(device::mojom::GeolocationDiagnostics& diagnostics) {
  using State = device::mojom::GeolocationDiagnostics::ProviderState;
  diagnostics.provider_state = !running_ ? State::kStopped
                               : high_accuracy_ ? State::kHighAccuracy
                                                : State::kLowAccuracy;
}

void GeolocationProvider::SetUpdateCallback(const LocationProviderUpdateCallback& callback) {
  callback_ = callback;
}

void GeolocationProvider::StartProvider(bool high_accuracy) {
  running_ = true;
  high_accuracy_ = high_accuracy;
  StartIfAllowed();
}

void GeolocationProvider::StopProvider() {
  running_ = false;
  timer_.Stop();
  result_.reset();
}

const device::mojom::GeopositionResult* GeolocationProvider::GetPosition() {
  return result_.get();
}

void GeolocationProvider::OnPermissionGranted() {
  permission_granted_ = true;
  StartIfAllowed();
}

void GeolocationProvider::OnPermissionManagerShuttingDown() {
  permission_granted_ = false;
  StopProvider();
}

void GeolocationProvider::StartIfAllowed() {
  if (!running_ || !permission_granted_) {
    return;
  }
  timer_.Start(FROM_HERE, base::Seconds(1), this, &GeolocationProvider::Publish);
  // Publish last: the consumer may stop or destroy this provider inline.
  Publish();
}

void GeolocationProvider::Publish() {
  if (!running_ || !permission_granted_ || callback_.is_null()) {
    return;
  }
  if (!policy_.IsValidCustom()) {
    result_ = device::mojom::GeopositionResult::NewError(
        device::mojom::GeopositionError::New(
            device::mojom::GeopositionErrorCode::kPermissionDenied,
            "Geolocation is blocked for this environment", ""));
  } else {
    const auto point = policy_.ForAccuracy(high_accuracy_);
    auto position = device::mojom::Geoposition::New();
    position->latitude = point.latitude;
    position->longitude = point.longitude;
    position->accuracy = point.accuracy;
    position->timestamp = base::Time::Now();
    position->is_precise = high_accuracy_;
    result_ = device::mojom::GeopositionResult::NewPosition(std::move(position));
  }
  callback_.Run(this, result_.Clone());
}

}  // namespace landu
