#include "location/lbs/contexthub/nanoapps/nearby/nearby_extension.h"

#include "third_party/contexthub/chre/util/include/chre/util/nanoapp/log.h"

#define LOG_TAG "[NEARBY][FILTER_EXTENSION]"

/**
 * Example advertisement data format.
 *
 * 0x02,  // byte length of flag
 * 0x01,  // type of ad data (flag)
 * 0x02,  // ad data (flag)
 * 0x05,  // byte length of manufacturer specific data
 * 0xff,  // type of ad data (manufacturer specific data)
 * 0xe0,  // ad data (manufacturer id[0])
 * 0x00,  // ad data (manufacturer id[1])
 * 0x78,  // ad data (manufacturer data for data filter)
 * 0x02,  // ad data (manufacturer data for delivery mode)
 */

static const uint16_t EXT_ADV_DATA_LEN = 9;
static const uint16_t EXT_ADV_DATA_FILTER_INDEX = 7;
static const uint16_t EXT_ADV_DELIVERY_MODE_INDEX = 8;
static const uint16_t EXT_FILTER_CONFIG_DATA_INDEX = 0;
static const uint16_t EXT_FILTER_CONFIG_DATA_MASK_INDEX = 1;
static uint8_t EXT_FILTER_DATA = 0;
static uint8_t EXT_FILTER_DATA_MASK = 0;

const char kHostPackageName[] = "com.google.android.nearby.offload.reference";

// TODO(b/284151838): investigate to pass hardware filter.
uint32_t chrexNearbySetExtendedFilterConfig(
    const chreHostEndpointInfo *host_info,
    const struct chrexNearbyExtendedFilterConfig *config,
    uint32_t *vendorStatusCode) {
  if (host_info->isNameValid &&
      strcmp(host_info->packageName, kHostPackageName) == 0) {
    EXT_FILTER_DATA = config->data[EXT_FILTER_CONFIG_DATA_INDEX];
    EXT_FILTER_DATA_MASK = config->data[EXT_FILTER_CONFIG_DATA_MASK_INDEX];
  }
  *vendorStatusCode = 0;
  LOGD("Set EXT_FILTER_DATA 0x%02X", EXT_FILTER_DATA);
  LOGD("Set EXT_FILTER_DATA_MASK 0x%02X", EXT_FILTER_DATA_MASK);
  return CHREX_NEARBY_RESULT_OK;
}

uint32_t chrexNearbyMatchExtendedFilter(
    const chreHostEndpointInfo *host_info,
    const struct chreBleAdvertisingReport *report) {
  if (!host_info->isNameValid ||
      strcmp(host_info->packageName, kHostPackageName) != 0 ||
      report->dataLength == 0) {
    return CHREX_NEARBY_FILTER_ACTION_IGNORE;
  }
  if (report->dataLength < EXT_ADV_DATA_LEN) {
    LOGD("data length %d is less than expected", report->dataLength);
    return CHREX_NEARBY_FILTER_ACTION_IGNORE;
  }
  uint8_t extData = report->data[EXT_ADV_DATA_FILTER_INDEX];
  int8_t deliveryMode =
      static_cast<int8_t>(report->data[EXT_ADV_DELIVERY_MODE_INDEX]);
  if ((extData & EXT_FILTER_DATA_MASK) !=
      (EXT_FILTER_DATA & EXT_FILTER_DATA_MASK)) {
    return CHREX_NEARBY_FILTER_ACTION_IGNORE;
  }
  switch (deliveryMode) {
    case CHREX_NEARBY_FILTER_ACTION_DELIVER_ON_WAKE:
      return CHREX_NEARBY_FILTER_ACTION_DELIVER_ON_WAKE;
    case CHREX_NEARBY_FILTER_ACTION_DELIVER_IMMEDIATELY:
      return CHREX_NEARBY_FILTER_ACTION_DELIVER_IMMEDIATELY;
    default:
      return CHREX_NEARBY_FILTER_ACTION_IGNORE;
  }
}
