#ifndef LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_RESULT_CALLBACK_INTERFACE_H_
#define LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_RESULT_CALLBACK_INTERFACE_H_

#include "calibration/online_calibration/common_data/calibration_data.h"
#include "calibration/online_calibration/common_data/sensor_data.h"

namespace online_calibration {

// Interface for a results callback implementation (useful for building
// calibration event loggers).
class ResultCallbackInterface {
 protected:
  // Protected destructor. The implementation can destroy itself, it can't be
  // destroyed through this interface.
  virtual ~ResultCallbackInterface() = default;

 public:
  // Sets a calibration event, such as a magnetometer calibration event.
  //
  // event_timestamp_nanos: Timestamp in nanoseconds of when the calibration
  //                        event was produced in the sensor timebase.
  // sensor_type: Which sensor the calibration was produced for.
  // sensor_index: Platform-dependent index that identifies the sensor (useful
  //               for devices with more than one sensor type).
  // calibration_index: Platform-dependent index that identifies the calibration
  //                    value that is being applied (distinct calibrations may
  //                    be utilized according to physical state [e.g., different
  //                    magnetometer biases may be required for the open/closed
  //                    states of a foldable device]).
  // flags: What kind of update the calibration was, e.g. offset, quality
  //        degradation (like a magnetization event), over temperature, etc.
  virtual void SetCalibrationEvent(
      uint64_t event_timestamp_nanos, SensorType sensor_type,
      uint8_t sensor_index, uint8_t calibration_index,
      CalibrationTypeFlags flags, const CalibrationDataThreeAxis &cal_data) = 0;
};

}  // namespace online_calibration

#endif  // LOCATION_LBS_CONTEXTHUB_NANOAPPS_CALIBRATION_ONLINE_CALIBRATION_COMMON_DATA_RESULT_CALLBACK_INTERFACE_H_
