#ifndef LOCATION_LBS_CONTEXTHUB_NANOAPPS_NEARBY_FILTER_EXTENSION_H_
#define LOCATION_LBS_CONTEXTHUB_NANOAPPS_NEARBY_FILTER_EXTENSION_H_
#include <chre.h>

#include <utility>

#include "location/lbs/contexthub/nanoapps/nearby/adv_report_cache.h"
#include "location/lbs/contexthub/nanoapps/nearby/byte_array.h"
#include "location/lbs/contexthub/nanoapps/nearby/nearby_extension.h"
#include "location/lbs/contexthub/nanoapps/nearby/proto/nearby_extension.nanopb.h"
#include "third_party/contexthub/chre/util/include/chre/util/dynamic_vector.h"
#include "third_party/contexthub/chre/util/include/chre/util/time.h"

namespace nearby {

struct FilterExtensionResult {
  // Default value for filter extension result to expire.
  static constexpr uint64_t kFilterExtensionReportExpireTimeMilliSec =
      5 * chre::kOneSecondInMilliseconds;

  const uint16_t end_point;
  AdvReportCache reports;

  explicit FilterExtensionResult(uint16_t end_point) : end_point(end_point) {
    reports.SetCacheTimeout(kFilterExtensionReportExpireTimeMilliSec);
  }

  FilterExtensionResult(FilterExtensionResult &&src)
      : end_point(src.end_point) {
    this->reports = std::move(src.reports);
  }

  // Deconstructs FilterExtensionResult and releases all resources.
  ~FilterExtensionResult() {
    Clear();
  }

  // Releases all resources {cache element, heap memory}.
  void Clear() {
    reports.Clear();
  }

  // Returns advertise reports in cache.
  chre::DynamicVector<chreBleAdvertisingReport> &GetAdvReports() {
    return reports.GetAdvReports();
  }

  // Logic operator to compare host end point.
  friend bool operator==(const FilterExtensionResult &c1,
                         const FilterExtensionResult &c2) {
    return c1.end_point == c2.end_point;
  }

  // Logic operator to compare host end point.
  friend bool operator!=(const FilterExtensionResult &c1,
                         const FilterExtensionResult &c2) {
    return c1.end_point != c2.end_point;
  }
};

class FilterExtension {
 public:
  // Updates extended filters (passed in the event) for each end host.
  // Returns generic_filters, which can be used to restart BLE scan.
  void Update(const chreHostEndpointInfo &host_info,
              const chreMessageFromHostData &event,
              chre::DynamicVector<chreBleGenericFilter> *generic_filters,
              nearby_extension_FilterConfigResult *config_result);

  // Matches BLE advertisements. Returns matched advertisements in
  // filter_results. If the results is only delivered when screen is on,
  // returned in screen_on_filter_results.
  void Match(
      const chre::DynamicVector<chreBleAdvertisingReport> &ble_adv_list,
      chre::DynamicVector<FilterExtensionResult> *filter_results,
      chre::DynamicVector<FilterExtensionResult> *screen_on_filter_results);

  // Serializes config_result into data_buf. The encoded size is filled in
  // encoded_size. Returns true for successful encoding.
  static bool EncodeConfigResult(
      const nearby_extension_FilterConfigResult &config_result,
      ByteArray data_buf, size_t *encoded_size);

  // Encodes reports into data_buf. The reports are converted to
  // nearby_extension_FilterResult before the serialization.
  static bool Encode(
      const chre::DynamicVector<chreBleAdvertisingReport> &reports,
      ByteArray data_buf, size_t *encoded_size);

  // Whether host list is empty. The host which doesn't have filter
  // configuration or was disconnected should be removed in the host list.
  bool IsEmpty() const {
    return host_list_.empty();
  }

  // Returns the index of the host if exists or could create.
  // Otherwise, returns -1.
  int32_t FindOrCreateHostIndex(const chreHostEndpointInfo &host_info);

 private:
  chre::DynamicVector<chreHostEndpointInfo> host_list_;
};

}  // namespace nearby

#endif  // LOCATION_LBS_CONTEXTHUB_NANOAPPS_NEARBY_FILTER_EXTENSION_H_
