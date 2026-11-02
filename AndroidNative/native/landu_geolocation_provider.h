#ifndef CHROME_BROWSER_LANDU_GEOLOCATION_PROVIDER_H_
#define CHROME_BROWSER_LANDU_GEOLOCATION_PROVIDER_H_

#include "base/timer/timer.h"
#include "components/permissions/landu_geolocation_policy.h"
#include "services/device/public/cpp/geolocation/location_provider.h"

namespace landu {

// A non-null embedder provider is exclusive: no Android/network fallback.
class GeolocationProvider final : public device::LocationProvider {
 public:
  explicit GeolocationProvider(permissions::landu::GeolocationPolicy policy);
  ~GeolocationProvider() override;
  void FillDiagnostics(device::mojom::GeolocationDiagnostics& diagnostics) override;
  void SetUpdateCallback(const LocationProviderUpdateCallback& callback) override;
  void StartProvider(bool high_accuracy) override;
  void StopProvider() override;
  const device::mojom::GeopositionResult* GetPosition() override;
  void OnPermissionGranted() override;
  void OnPermissionManagerShuttingDown() override;

 private:
  void StartIfAllowed();
  void Publish();

  const permissions::landu::GeolocationPolicy policy_;
  bool running_ = false;
  bool permission_granted_ = false;
  bool high_accuracy_ = false;
  LocationProviderUpdateCallback callback_;
  device::mojom::GeopositionResultPtr result_;
  base::RepeatingTimer timer_;
};

}  // namespace landu

#endif  // CHROME_BROWSER_LANDU_GEOLOCATION_PROVIDER_H_
