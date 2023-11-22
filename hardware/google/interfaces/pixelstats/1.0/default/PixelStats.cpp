#include "PixelStats.h"

#define LOG_TAG "pixelstats-system"
#include <log/log.h>
#include <metricslogger/metrics_logger.h>

namespace hardware {
namespace google {
namespace pixelstats {
namespace V1_0 {
namespace implementation {

using namespace android::metricslogger;

PixelStats::PixelStats()
    :limiter_(kDailyRatelimit) {}

void loggerAddFields(ComplexEventLogger* logger) {
    logger->Record();
}

template<typename... Args>
void loggerAddFields(ComplexEventLogger* logger, int32_t field, int32_t value, Args... args) {
    logger->AddTaggedData(LOGBUILDER_TYPE, TYPE_ACTION);
    logger->AddTaggedData(field, value);
    loggerAddFields(logger, args...);
}

template<typename... Args>
void logIntAction(int32_t category, Args... args) {
    ComplexEventLogger logger(category);
    logger.AddTaggedData(LOGBUILDER_TYPE, TYPE_ACTION);
    loggerAddFields(&logger, args...);
}

// Methods from ::hardware::google::pixelstats::V1_0::IPixelStats follow.
Return<void> PixelStats::reportUsbConnectorConnected() {
    // Ratelimit to max 20 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_USB_CONNECTOR_CONNECTED, 20))
        return Void();
    logIntAction(android::metricslogger::ACTION_USB_CONNECTOR_CONNECTED);
    return Void();
}

Return<void> PixelStats::reportUsbConnectorDisconnected(int32_t durationMillis) {
    // Ratelimit to max 20 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_USB_CONNECTOR_DISCONNECTED, 20))
        return Void();
    logIntAction(android::metricslogger::ACTION_USB_CONNECTOR_DISCONNECTED,
                 android::metricslogger::FIELD_DURATION_MILLIS, durationMillis);
    return Void();
}

Return<void> PixelStats::reportUsbAudioConnected(int32_t vid, int32_t pid) {
    // Ratelimit to max 20 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_USB_AUDIO_CONNECTED, 20))
        return Void();
    logIntAction(android::metricslogger::ACTION_USB_AUDIO_CONNECTED,
                 android::metricslogger::FIELD_USB_AUDIO_VIDPID, (vid << 16) | pid);
    return Void();
}

Return<void> PixelStats::reportUsbAudioDisconnected(int32_t vid, int32_t pid,
                                                    int32_t durationMillis) {
    // Ratelimit to max 20 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_USB_AUDIO_DISCONNECTED, 20))
        return Void();
    logIntAction(android::metricslogger::ACTION_USB_AUDIO_DISCONNECTED, FIELD_USB_AUDIO_VIDPID,
                    (vid << 16) | pid, android::metricslogger::FIELD_DURATION_MILLIS,
                    durationMillis);
    return Void();
}

Return<void> PixelStats::reportSpeakerImpedance(int32_t speakerLocation, int32_t milliOhms) {
    // Ratelimit to max 2 / 24hrs (expected 1/24hrs)
    if (rateLimit(android::metricslogger::ACTION_SPEAKER_IMPEDANCE, 2))
        return Void();

    logIntAction(android::metricslogger::ACTION_SPEAKER_IMPEDANCE, FIELD_SPEAKER_LOCATION,
                    speakerLocation, FIELD_SPEAKER_IMPEDANCE_MILLIOHMS, milliOhms);
    return Void();
}

static android::metricslogger::HardwareType toMetricsLoggerHardwareType(
    IPixelStats::HardwareType pixelstatsType) {
    switch (pixelstatsType) {
        case IPixelStats::HardwareType::MICROPHONE:
            return android::metricslogger::HardwareType::HARDWARE_MICROPHONE;
        case IPixelStats::HardwareType::CODEC:
            return android::metricslogger::HardwareType::HARDWARE_CODEC;
        case IPixelStats::HardwareType::SPEAKER:
            return android::metricslogger::HardwareType::HARDWARE_SPEAKER;
        case IPixelStats::HardwareType::FINGERPRINT:
            return android::metricslogger::HardwareType::HARDWARE_FINGERPRINT;
        case IPixelStats::HardwareType::UNKNOWN:
        default:
            return android::metricslogger::HardwareType::HARDWARE_UNKNOWN;

    }
}

static android::metricslogger::HardwareFailureCode toMetricsLoggerHardwareFailure(
    IPixelStats::HardwareErrorCode pixelstatsError) {
    switch (pixelstatsError) {
        case IPixelStats::HardwareErrorCode::COMPLETE:
            return HARDWARE_FAILURE_COMPLETE;
        case IPixelStats::HardwareErrorCode::SPEAKER_HIGH_Z:
            return HARDWARE_FAILURE_SPEAKER_HIGH_Z;
        case IPixelStats::HardwareErrorCode::SPEAKER_SHORT:
            return HARDWARE_FAILURE_SPEAKER_SHORT;
        case IPixelStats::HardwareErrorCode::FINGERPRINT_SENSOR_BROKEN:
            return HARDWARE_FAILURE_FINGERPRINT_SENSOR_BROKEN;
        case IPixelStats::HardwareErrorCode::FINGERPRINT_TOO_MANY_DEAD_PIXELS:
            return HARDWARE_FAILURE_FINGERPRINT_TOO_MANY_DEAD_PIXELS;
        case IPixelStats::HardwareErrorCode::UNKNOWN:
        default:
            return HARDWARE_FAILURE_UNKNOWN;
    }
}

Return<void> PixelStats::reportHardwareFailed(HardwareType hardwareType, int32_t hardwareLocation,
                                              HardwareErrorCode errorCode) {
    // Ratelimit to max 15 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_HARDWARE_FAILED, 15))
        return Void();

    logIntAction(ACTION_HARDWARE_FAILED,
                 FIELD_HARDWARE_TYPE, toMetricsLoggerHardwareType(hardwareType),
                 FIELD_HARDWARE_LOCATION, hardwareLocation,
                 FIELD_HARDWARE_FAILURE_CODE, toMetricsLoggerHardwareFailure(errorCode));
    return Void();
}

Return<void> PixelStats::reportPhysicalDropDetected(int32_t confidencePctg, int32_t accelPeak,
                                                    int32_t freefallDurationMs) {
    // Ratelimit to max 10 / 24hrs (expected 0/24hrs)
    if (rateLimit(android::metricslogger::ACTION_PHYSICAL_DROP, 10))
        return Void();

    logIntAction(ACTION_PHYSICAL_DROP, FIELD_CONFIDENCE_PERCENT, confidencePctg,
                 FIELD_ACCEL_MILLI_G, accelPeak,
                 FIELD_DURATION_MILLIS, freefallDurationMs);
    return Void();
}

Return<void> PixelStats::reportChargeCycles(const hidl_string& buckets) {
    // Ratelimit to max 2 / 24hrs (expected 1/24hrs)
    if (rateLimit(android::metricslogger::ACTION_BATTERY_CHARGE_CYCLES, 2))
        return Void();
    LogMultiAction(ACTION_BATTERY_CHARGE_CYCLES, FIELD_BATTERY_CHARGE_CYCLES, buckets);
    return Void();
}

static android::metricslogger::IoOperation toMetricsLoggerIoOperation(IPixelStats::IoOperation op) {
    switch (op) {
        default:
        case IPixelStats::IoOperation::UNKNOWN:
            return android::metricslogger::IoOperation::IOOP_UNKNOWN;
        case IPixelStats::IoOperation::READ:
            return android::metricslogger::IoOperation::IOOP_READ;
        case IPixelStats::IoOperation::WRITE:
            return android::metricslogger::IoOperation::IOOP_WRITE;
        case IPixelStats::IoOperation::UNMAP:
            return android::metricslogger::IoOperation::IOOP_UNMAP;
        case IPixelStats::IoOperation::SYNC:
            return android::metricslogger::IoOperation::IOOP_SYNC;
    }
}

Return<void> PixelStats::reportSlowIo(IoOperation operation, int32_t count) {
    // Ratelimit to max 2 per 24hrs
    if (rateLimit(android::metricslogger::ACTION_SLOW_IO, 2))
        return Void();
    logIntAction(ACTION_SLOW_IO, FIELD_IO_OPERATION_TYPE, toMetricsLoggerIoOperation(operation),
                    FIELD_IO_OPERATION_COUNT, count);
    return Void();
}

Return<void> PixelStats::reportBatteryHealthSnapshot(const BatteryHealthSnapshotArgs& args) {
    // Ratelimit to max 2 per 24hrs
    if (rateLimit(android::metricslogger::ACTION_BATTERY_HEALTH, 2))
        return Void();
    logIntAction(ACTION_BATTERY_HEALTH,
                 FIELD_BATTERY_HEALTH_SNAPSHOT_TYPE, (int32_t)args.type,
                 FIELD_BATTERY_TEMPERATURE_DECI_C, args.temperatureDeciC,
                 FIELD_BATTERY_VOLTAGE_UV, args.voltageMicroV,
                 FIELD_BATTERY_CURRENT_UA, args.currentMicroA,
                 FIELD_BATTERY_OPEN_CIRCUIT_VOLTAGE_UV, args.openCircuitVoltageMicroV,
                 FIELD_BATTERY_RESISTANCE_UOHMS, args.resistanceMicroOhm,
                 FIELD_END_BATTERY_PERCENT, args.levelPercent);
    return Void();
}

Return<void> PixelStats::reportBatteryCausedShutdown(int32_t voltageMicroV) {
    // Ratelimit to max 5 per 24hrs
    if (rateLimit(android::metricslogger::ACTION_BATTERY_CAUSED_SHUTDOWN, 5))
        return Void();
    logIntAction(ACTION_BATTERY_CAUSED_SHUTDOWN, FIELD_BATTERY_VOLTAGE_UV, voltageMicroV);
    return Void();
}

bool PixelStats::rateLimit(int action, int limit) {
    if (limiter_.RateLimit(action, limit)) {
        ALOGE("Rate limited action %d\n", action);
        return true;
    }
    return false;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace pixelstats
}  // namespace google
}  // namespace hardware
