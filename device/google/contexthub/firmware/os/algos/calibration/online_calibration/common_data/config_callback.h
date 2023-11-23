#ifndef LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_CONFIG_CALLBACK_H_
#define LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_CONFIG_CALLBACK_H_

#include <cstdint>

namespace online_calibration {

// Context-dependent configuration change types.
enum class ConfigChangeType : uint8_t {
  kNoConfigChange = 0,
  kMagGyroActiveMode,
  kMagGyroPassiveMode,
  kGyroActiveMode,
  kGyroPassiveMode,
  kNumConfigChangeTypes,
};

// Callback interface for changing an algorithm specific configuration (e.g.,
// sensor subscription properties, etc.).
class ConfigCallback {
 protected:
  // Protected destructor. The implementation can destroy itself, it can't be
  // destroyed through this interface.
  virtual ~ConfigCallback() = default;

 public:
  /*
   * Override this method to allow calibration objects to trigger changes in
   * sensor configurations or operational states (e.g., upon device motion the
   * MagGyroCal may switch to higher-rate gyroscope and magnetometer sampling
   * rates to produce a new calibration result).
   *
   * config_type: This enumerator indicates what configuration change must be
   *              made, the owner of the calibration object will make the
   *              appropriate platform dependent changes.
   *
   * sensor_index: The calibration algorithm will provide a sensor index to help
   *               uniquely identify the sensor it calibrates. This can be used
   *               to disambiguate what platform specific configuration response
   *               should be taken.
   */
  virtual void UpdateConfiguration(ConfigChangeType config_type,
                                   uint8_t sensor_index) = 0;
};

}  // namespace online_calibration

#endif  // LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_CONFIG_CALLBACK_H_
