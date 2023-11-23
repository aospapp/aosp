/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
//#define LOG_NDEBUG 0

#define ATRACE_TAG (ATRACE_TAG_GRAPHICS | ATRACE_TAG_HAL)

#include "ExynosPrimaryDisplay.h"

#include <linux/fb.h>
#include <poll.h>

#include <chrono>
#include <fstream>

#include "BrightnessController.h"
#include "ExynosDevice.h"
#include "ExynosDisplayDrmInterface.h"
#include "ExynosDisplayDrmInterfaceModule.h"
#include "ExynosExternalDisplay.h"
#include "ExynosHWCDebug.h"
#include "ExynosHWCHelper.h"
#include "ExynosLayer.h"

extern struct exynos_hwc_control exynosHWCControl;

using namespace SOC_VERSION;
constexpr auto nsecsPerSec = std::chrono::nanoseconds(1s).count();

static const std::map<const DisplayType, const std::string> panelSysfsPath =
        {{DisplayType::DISPLAY_PRIMARY, "/sys/devices/platform/exynos-drm/primary-panel/"},
         {DisplayType::DISPLAY_SECONDARY, "/sys/devices/platform/exynos-drm/secondary-panel/"}};

static String8 getPropertyBootModeStr(const int32_t dispId) {
    String8 str;
    if (dispId == 0) {
        str.appendFormat("persist.vendor.display.primary.boot_config");
    } else {
        str.appendFormat("persist.vendor.display.%d.primary.boot_config", dispId);
    }
    return str;
}

static std::string loadPanelGammaCalibration(const std::string &file) {
    std::ifstream ifs(file);

    if (!ifs.is_open()) {
        ALOGW("Unable to open gamma calibration '%s', error = %s", file.c_str(), strerror(errno));
        return {};
    }

    std::string raw_data, gamma;
    char ch;
    while (std::getline(ifs, raw_data, '\r')) {
        gamma.append(raw_data);
        gamma.append(1, ' ');
        ifs.get(ch);
        if (ch != '\n') {
            gamma.append(1, ch);
        }
    }
    ifs.close();

    /* eliminate space character in the last byte */
    if (!gamma.empty()) {
        gamma.pop_back();
    }

    return gamma;
}

ExynosPrimaryDisplay::ExynosPrimaryDisplay(uint32_t index, ExynosDevice *device,
                                           const std::string &displayName)
      : ExynosDisplay(HWC_DISPLAY_PRIMARY, index, device, displayName),
        mMinIdleRefreshRate(0),
        mRefreshRateDelayNanos(0),
        mLastRefreshRateAppliedNanos(0),
        mAppliedActiveConfig(0),
        mDisplayIdleTimerEnabled(false),
        mDisplayNeedHandleIdleExit(false) {
    // TODO : Hard coded here
    mNumMaxPriorityAllowed = 5;

    /* Initialization */
    mFramesToReachLhbmPeakBrightness =
            property_get_int32("vendor.primarydisplay.lhbm.frames_to_reach_peak_brightness", 3);

    // Allow to enable dynamic recomposition after every power on
    // since it will always be disabled for every power off
    // TODO(b/268474771): to enable DR by default if video mode panel is detected
    if (property_get_int32("vendor.display.dynamic_recomposition", 0) & (1 << index)) {
        mDRDefault = true;
        mDREnable = true;
    }

    // Prepare multi resolution
    // Will be exynosHWCControl.multiResoultion
    mResolutionInfo.nNum = 1;
    mResolutionInfo.nResolution[0].w = 1440;
    mResolutionInfo.nResolution[0].h = 2960;
    mResolutionInfo.nDSCYSliceSize[0] = 40;
    mResolutionInfo.nDSCXSliceSize[0] = 1440 / 2;
    mResolutionInfo.nPanelType[0] = PANEL_DSC;
    mResolutionInfo.nResolution[1].w = 1080;
    mResolutionInfo.nResolution[1].h = 2220;
    mResolutionInfo.nDSCYSliceSize[1] = 30;
    mResolutionInfo.nDSCXSliceSize[1] = 1080 / 2;
    mResolutionInfo.nPanelType[1] = PANEL_DSC;
    mResolutionInfo.nResolution[2].w = 720;
    mResolutionInfo.nResolution[2].h = 1480;
    mResolutionInfo.nDSCYSliceSize[2] = 74;
    mResolutionInfo.nDSCXSliceSize[2] = 720;
    mResolutionInfo.nPanelType[2] = PANEL_LEGACY;

    char value[PROPERTY_VALUE_MAX];
    const char *earlyWakeupNodeBase = early_wakeup_node_0_base;
    if (getDisplayTypeFromIndex(mIndex) == DisplayType::DISPLAY_SECONDARY &&
        property_get("vendor.display.secondary_early_wakeup_node", value, "") > 0) {
        earlyWakeupNodeBase = value;
    }
    mEarlyWakeupDispFd = fopen(earlyWakeupNodeBase, "w");
    if (mEarlyWakeupDispFd == nullptr)
        ALOGE("open %s failed! %s", earlyWakeupNodeBase, strerror(errno));
    mBrightnessController = std::make_unique<BrightnessController>(
            mIndex, [this]() { mDevice->onRefresh(mDisplayId); },
            [this]() { updatePresentColorConversionInfo(); });

    mDisplayControl.multiThreadedPresent = true;
}

ExynosPrimaryDisplay::~ExynosPrimaryDisplay()
{
    if (mEarlyWakeupDispFd) {
        fclose(mEarlyWakeupDispFd);
        mEarlyWakeupDispFd = nullptr;
    }

    if (mDisplayNeedHandleIdleExitOfs.is_open()) {
        mDisplayNeedHandleIdleExitOfs.close();
    }
}

void ExynosPrimaryDisplay::setDDIScalerEnable(int width, int height) {

    if (exynosHWCControl.setDDIScaler == false) return;

    ALOGI("DDISCALER Info : setDDIScalerEnable(w=%d,h=%d)", width, height);
    mNewScaledWidth = width;
    mNewScaledHeight = height;
    mXres = width;
    mYres = height;
}

int ExynosPrimaryDisplay::getDDIScalerMode(int width, int height) {

    if (exynosHWCControl.setDDIScaler == false) return 1;

    // Check if panel support support resolution or not.
    for (uint32_t i=0; i < mResolutionInfo.nNum; i++) {
        if (mResolutionInfo.nResolution[i].w * mResolutionInfo.nResolution[i].h ==
                static_cast<uint32_t>(width * height))
            return i + 1;
    }

    return 1; // WQHD
}

int32_t ExynosPrimaryDisplay::doDisplayConfigInternal(hwc2_config_t config) {
    if (!mPowerModeState.has_value() || (*mPowerModeState != HWC2_POWER_MODE_ON) ||
        !isConfigSettingEnabled()) {
        mPendingConfig = config;
        mConfigRequestState = hwc_request_state_t::SET_CONFIG_STATE_DONE;
        DISPLAY_LOGI("%s:: Pending desired Config: %d", __func__, config);
        return NO_ERROR;
    }
    return ExynosDisplay::doDisplayConfigInternal(config);
}

int32_t ExynosPrimaryDisplay::getActiveConfigInternal(hwc2_config_t *outConfig) {
    if (outConfig && mPendingConfig != UINT_MAX) {
        *outConfig = mPendingConfig;
        return HWC2_ERROR_NONE;
    }
    return ExynosDisplay::getActiveConfigInternal(outConfig);
}

int32_t ExynosPrimaryDisplay::setActiveConfigInternal(hwc2_config_t config, bool force) {
    hwc2_config_t cur_config;

    getActiveConfigInternal(&cur_config);
    if (cur_config == config) {
        ALOGI("%s:: Same display config is set", __func__);
        return HWC2_ERROR_NONE;
    }
    if (!mPowerModeState.has_value() || (*mPowerModeState != HWC2_POWER_MODE_ON) ||
        !isConfigSettingEnabled()) {
        mPendingConfig = config;
        return HWC2_ERROR_NONE;
    }
    return ExynosDisplay::setActiveConfigInternal(config, force);
}

int32_t ExynosPrimaryDisplay::applyPendingConfig() {
    if (!isConfigSettingEnabled()) return HWC2_ERROR_NONE;

    hwc2_config_t config;
    if (mPendingConfig != UINT_MAX) {
        config = mPendingConfig;
        mPendingConfig = UINT_MAX;
    } else {
        getActiveConfigInternal(&config);
    }

    return ExynosDisplay::setActiveConfigInternal(config, true);
}

int32_t ExynosPrimaryDisplay::setBootDisplayConfig(int32_t config) {
    auto hwcConfig = static_cast<hwc2_config_t>(config);

    const auto &it = mDisplayConfigs.find(hwcConfig);
    if (it == mDisplayConfigs.end()) {
        DISPLAY_LOGE("%s: invalid config %d", __func__, config);
        return HWC2_ERROR_BAD_CONFIG;
    }

    const auto &mode = it->second;
    if (mode.vsyncPeriod == 0)
        return HWC2_ERROR_BAD_CONFIG;

    int refreshRate = round(nsecsPerSec / mode.vsyncPeriod * 0.1f) * 10;
    char modeStr[PROPERTY_VALUE_MAX];
    int ret = snprintf(modeStr, sizeof(modeStr), "%dx%d@%d",
             mode.width, mode.height, refreshRate);
    if (ret <= 0)
        return HWC2_ERROR_BAD_CONFIG;

    ALOGD("%s: mode=%s (%d) vsyncPeriod=%d", __func__, modeStr, config,
            mode.vsyncPeriod);
    ret = property_set(getPropertyBootModeStr(mDisplayId).string(), modeStr);

    return !ret ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}

int32_t ExynosPrimaryDisplay::clearBootDisplayConfig() {
    auto ret = property_set(getPropertyBootModeStr(mDisplayId).string(), nullptr);

    ALOGD("%s: clearing boot mode", __func__);
    return !ret ? HWC2_ERROR_NONE : HWC2_ERROR_BAD_CONFIG;
}

int32_t ExynosPrimaryDisplay::getPreferredDisplayConfigInternal(int32_t *outConfig) {
    char modeStr[PROPERTY_VALUE_MAX];
    auto ret = property_get(getPropertyBootModeStr(mDisplayId).string(), modeStr, "");

    if (ret <= 0) {
        return mDisplayInterface->getDefaultModeId(outConfig);
    }

    int width, height;
    int fps = 0;

    ret = sscanf(modeStr, "%dx%d@%d", &width, &height, &fps);
    if ((ret < 3) || !fps) {
        ALOGD("%s: unable to find boot config for mode: %s", __func__, modeStr);
        return HWC2_ERROR_BAD_CONFIG;
    }

    return lookupDisplayConfigs(width, height, fps, outConfig);
}

int32_t ExynosPrimaryDisplay::setPowerOn() {
    ATRACE_CALL();
    updateAppliedActiveConfig(0, 0);
    int ret = NO_ERROR;
    if (mDisplayId != 0 || !mFirstPowerOn) {
        if (mDevice->hasOtherDisplayOn(this)) {
            // TODO: This is useful for cmd mode, and b/282094671 tries to handles video mode
            mDisplayInterface->triggerClearDisplayPlanes();
        }
        ret = applyPendingConfig();
    }

    if (!mPowerModeState.has_value() || (*mPowerModeState == HWC2_POWER_MODE_OFF)) {
        // check the dynamic recomposition thread by following display
        mDevice->checkDynamicRecompositionThread();
        if (ret) {
            mDisplayInterface->setPowerMode(HWC2_POWER_MODE_ON);
        }
        setGeometryChanged(GEOMETRY_DISPLAY_POWER_ON);
    }

    {
        std::lock_guard<std::mutex> lock(mPowerModeMutex);
        mPowerModeState = HWC2_POWER_MODE_ON;
        if (mNotifyPowerOn) {
            mPowerOnCondition.notify_one();
            mNotifyPowerOn = false;
        }
    }

    if (mFirstPowerOn) {
        firstPowerOn();
    }

    return HWC2_ERROR_NONE;
}

int32_t ExynosPrimaryDisplay::setPowerOff() {
    ATRACE_CALL();

    clearDisplay(true);

    // check the dynamic recomposition thread by following display
    mDevice->checkDynamicRecompositionThread();

    mDisplayInterface->setPowerMode(HWC2_POWER_MODE_OFF);

    {
        std::lock_guard<std::mutex> lock(mPowerModeMutex);
        mPowerModeState = HWC2_POWER_MODE_OFF;
    }

    /* It should be called from validate() when the screen is on */
    mSkipFrame = true;
    setGeometryChanged(GEOMETRY_DISPLAY_POWER_OFF);
    if ((mRenderingState >= RENDERING_STATE_VALIDATED) &&
        (mRenderingState < RENDERING_STATE_PRESENTED))
        closeFencesForSkipFrame(RENDERING_STATE_VALIDATED);
    mRenderingState = RENDERING_STATE_NONE;

    // in the case user turns off screen when LHBM is on
    // TODO: b/236433238 considering a lock for mLhbmOn state
    mLhbmOn = false;
    return HWC2_ERROR_NONE;
}

int32_t ExynosPrimaryDisplay::setPowerDoze(hwc2_power_mode_t mode) {
    ATRACE_CALL();

    if (!mDisplayInterface->isDozeModeAvailable()) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    if (mPowerModeState.has_value() &&
        ((*mPowerModeState == HWC2_POWER_MODE_OFF) || (*mPowerModeState == HWC2_POWER_MODE_ON))) {
        if (mDisplayInterface->setLowPowerMode()) {
            ALOGI("Not support LP mode.");
            return HWC2_ERROR_UNSUPPORTED;
        }
    }

    {
        std::lock_guard<std::mutex> lock(mPowerModeMutex);
        mPowerModeState = mode;
    }

    // LHBM will be disabled in the kernel while entering AOD mode if it's
    // already enabled. Reset the state to avoid the sync problem.
    mBrightnessController->resetLhbmState();
    mLhbmOn = false;

    ExynosDisplay::updateRefreshRateHint();

    return HWC2_ERROR_NONE;
}

int32_t ExynosPrimaryDisplay::setPowerMode(int32_t mode) {
    Mutex::Autolock lock(mDisplayMutex);

    if (mode == static_cast<int32_t>(ext_hwc2_power_mode_t::PAUSE)) {
        mode = HWC2_POWER_MODE_OFF;
        mPauseDisplay = true;
    } else if (mode == static_cast<int32_t>(ext_hwc2_power_mode_t::RESUME)) {
        mode = HWC2_POWER_MODE_ON;
        mPauseDisplay = false;
    } else if (mPauseDisplay) {
        ALOGI("Skip power mode transition due to pause display.");
        return HWC2_ERROR_NONE;
    }

    if (mPowerModeState.has_value() && (mode == static_cast<int32_t>(mPowerModeState.value()))) {
        ALOGI("Skip power mode transition due to the same power state.");
        return HWC2_ERROR_NONE;
    }

    int fb_blank = (mode != HWC2_POWER_MODE_OFF) ? FB_BLANK_UNBLANK : FB_BLANK_POWERDOWN;
    ALOGD("%s:: FBIOBLANK mode(%d), blank(%d)", __func__, mode, fb_blank);

    if (fb_blank == FB_BLANK_POWERDOWN)
        mDREnable = false;
    else
        mDREnable = mDRDefault;

    if (mOperationRateManager) mOperationRateManager->onPowerMode(mode);
    switch (mode) {
        case HWC2_POWER_MODE_DOZE_SUSPEND:
        case HWC2_POWER_MODE_DOZE:
            return setPowerDoze(static_cast<hwc2_power_mode_t>(mode));
        case HWC2_POWER_MODE_OFF:
            setPowerOff();
            break;
        case HWC2_POWER_MODE_ON:
            setPowerOn();
            break;
        default:
            return HWC2_ERROR_BAD_PARAMETER;
    }

    ExynosDisplay::updateRefreshRateHint();

    return HWC2_ERROR_NONE;
}

void ExynosPrimaryDisplay::firstPowerOn() {
    SetCurrentPanelGammaSource(DisplayType::DISPLAY_PRIMARY, PanelGammaSource::GAMMA_CALIBRATION);
    mFirstPowerOn = false;
    getDisplayIdleTimerEnabled(mDisplayIdleTimerEnabled);
    initDisplayHandleIdleExit();
}

bool ExynosPrimaryDisplay::getHDRException(ExynosLayer* __unused layer)
{
    return false;
}

void ExynosPrimaryDisplay::initDisplayInterface(uint32_t interfaceType)
{
    if (interfaceType == INTERFACE_TYPE_DRM)
        mDisplayInterface = std::make_unique<ExynosPrimaryDisplayDrmInterfaceModule>((ExynosDisplay *)this);
    else
        LOG_ALWAYS_FATAL("%s::Unknown interface type(%d)",
                __func__, interfaceType);
    mDisplayInterface->init(this);

    mDpuData.init(mMaxWindowNum, mDevice->getSpecialPlaneNum(mDisplayId));
    mLastDpuData.init(mMaxWindowNum, mDevice->getSpecialPlaneNum(mDisplayId));
    ALOGI("window configs size(%zu) rcd configs zie(%zu)", mDpuData.configs.size(),
          mDpuData.rcdConfigs.size());
}

std::string ExynosPrimaryDisplay::getPanelSysfsPath(const DisplayType &type) {
    if ((type < DisplayType::DISPLAY_PRIMARY) || (type >= DisplayType::DISPLAY_MAX)) {
        ALOGE("Invalid display panel type %d", type);
        return {};
    }

    auto iter = panelSysfsPath.find(type);
    if (iter == panelSysfsPath.end()) {
        return {};
    }

    return iter->second;
}

int32_t ExynosPrimaryDisplay::SetCurrentPanelGammaSource(const DisplayType type,
                                                         const PanelGammaSource &source) {
    std::string &&panel_sysfs_path = getPanelSysfsPath(type);
    if (panel_sysfs_path.empty()) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::ifstream ifs;
    std::string &&path = panel_sysfs_path + "panel_name";
    ifs.open(path, std::ifstream::in);
    if (!ifs.is_open()) {
        ALOGW("Unable to access panel name path '%s' (%s)", path.c_str(), strerror(errno));
        return HWC2_ERROR_UNSUPPORTED;
    }
    std::string panel_name;
    std::getline(ifs, panel_name);
    ifs.close();

    path = panel_sysfs_path + "serial_number";
    ifs.open(path, std::ifstream::in);
    if (!ifs.is_open()) {
        ALOGW("Unable to access panel id path '%s' (%s)", path.c_str(), strerror(errno));
        return HWC2_ERROR_UNSUPPORTED;
    }
    std::string panel_id;
    std::getline(ifs, panel_id);
    ifs.close();

    std::string gamma_node = panel_sysfs_path + "gamma";
    if (access(gamma_node.c_str(), W_OK)) {
        ALOGW("Unable to access panel gamma calibration node '%s' (%s)", gamma_node.c_str(),
              strerror(errno));
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::string &&gamma_data = "default";
    if (source == PanelGammaSource::GAMMA_CALIBRATION) {
        std::string gamma_cal_file(kDisplayCalFilePath);
        gamma_cal_file.append(kPanelGammaCalFilePrefix)
                .append(1, '_')
                .append(panel_name)
                .append(1, '_')
                .append(panel_id)
                .append(".cal");
        if (access(gamma_cal_file.c_str(), R_OK)) {
            ALOGI("Fail to access `%s` (%s), try golden gamma calibration", gamma_cal_file.c_str(),
                  strerror(errno));
            gamma_cal_file = kDisplayCalFilePath;
            gamma_cal_file.append(kPanelGammaCalFilePrefix)
                    .append(1, '_')
                    .append(panel_name)
                    .append(".cal");
        }
        gamma_data = loadPanelGammaCalibration(gamma_cal_file);
    }

    if (gamma_data.empty()) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::ofstream ofs(gamma_node);
    if (!ofs.is_open()) {
        ALOGW("Unable to open gamma node '%s', error = %s", gamma_node.c_str(), strerror(errno));
        return HWC2_ERROR_UNSUPPORTED;
    }
    ofs.write(gamma_data.c_str(), gamma_data.size());
    ofs.close();

    currentPanelGammaSource = source;
    return HWC2_ERROR_NONE;
}

bool ExynosPrimaryDisplay::isLhbmSupported() {
    return mBrightnessController->isLhbmSupported();
}

bool ExynosPrimaryDisplay::isConfigSettingEnabled() {
    int64_t msSinceDisabled =
            (systemTime(SYSTEM_TIME_MONOTONIC) - mConfigSettingDisabledTimestamp) / 1000000;
    return !mConfigSettingDisabled || msSinceDisabled > kConfigDisablingMaxDurationMs;
}

void ExynosPrimaryDisplay::enableConfigSetting(bool en) {
    DISPLAY_ATRACE_INT("ConfigSettingDisabled", !en);

    if (!en) {
        mConfigSettingDisabled = true;
        mConfigSettingDisabledTimestamp = systemTime(SYSTEM_TIME_MONOTONIC);
        return;
    }

    mConfigSettingDisabled = false;
}

int32_t ExynosPrimaryDisplay::setLhbmDisplayConfigLocked(uint32_t peakRate) {
    auto hwConfig = mDisplayInterface->getActiveModeId();
    auto config = getConfigId(peakRate, mDisplayConfigs[hwConfig].width,
                              mDisplayConfigs[hwConfig].height);
    if (config == UINT_MAX) {
        DISPLAY_LOGE("%s: failed to get config for rate=%d", __func__, peakRate);
        return -EINVAL;
    }

    if (mPendingConfig == UINT_MAX && mActiveConfig != config) mPendingConfig = mActiveConfig;
    if (config != hwConfig) {
        if (ExynosDisplay::setActiveConfigInternal(config, true) == HWC2_ERROR_NONE) {
            DISPLAY_LOGI("%s: succeeded to set config=%d rate=%d", __func__, config, peakRate);
        } else {
            DISPLAY_LOGW("%s: failed to set config=%d rate=%d", __func__, config, peakRate);
        }
    } else {
        DISPLAY_LOGI("%s: keep config=%d rate=%d", __func__, config, peakRate);
    }
    enableConfigSetting(false);
    return OK;
}

void ExynosPrimaryDisplay::restoreLhbmDisplayConfigLocked() {
    enableConfigSetting(true);
    hwc2_config_t pendingConfig = mPendingConfig;
    auto hwConfig = mDisplayInterface->getActiveModeId();
    if (pendingConfig != UINT_MAX && pendingConfig != hwConfig) {
        if (applyPendingConfig() == HWC2_ERROR_NONE) {
            DISPLAY_LOGI("%s: succeeded to set config=%d rate=%d", __func__, pendingConfig,
                         getRefreshRate(pendingConfig));
        } else {
            DISPLAY_LOGE("%s: failed to set config=%d rate=%d", __func__, pendingConfig,
                         getRefreshRate(pendingConfig));
        }
    } else {
        mPendingConfig = UINT_MAX;
        DISPLAY_LOGI("%s: keep config=%d rate=%d", __func__, hwConfig, getRefreshRate(hwConfig));
    }
}

// This function should be called by other threads (e.g. sensor HAL).
// HWCService can call this function but it should be for test purpose only.
int32_t ExynosPrimaryDisplay::setLhbmState(bool enabled) {
    int ret = OK;
    // NOTE: mLhbmOn could be set to false at any time by setPowerOff in another
    // thread. Make sure no side effect if that happens. Or add lock if we have
    // to when new code is added.
    DISPLAY_ATRACE_CALL();
    DISPLAY_LOGI("%s: enabled=%d", __func__, enabled);
    {
        ATRACE_NAME("wait_for_power_on");
        std::unique_lock<std::mutex> lock(mPowerModeMutex);
        if (mPowerModeState != HWC2_POWER_MODE_ON) {
            mNotifyPowerOn = true;
            if (!mPowerOnCondition.wait_for(lock, std::chrono::milliseconds(2000), [this]() {
                    return (mPowerModeState == HWC2_POWER_MODE_ON);
                })) {
                DISPLAY_LOGW("%s: wait for power mode on timeout !", __func__);
                return TIMED_OUT;
            }
        }
    }

    auto lhbmSysfs = mBrightnessController->GetPanelSysfileByIndex(
            BrightnessController::kLocalHbmModeFileNode);
    ret = mBrightnessController->checkSysfsStatus(lhbmSysfs,
                                         {std::to_string(static_cast<int>(
                                                 BrightnessController::LhbmMode::DISABLED))},
                                         0);
    bool wasDisabled = ret == OK;
    if (!enabled && wasDisabled) {
        DISPLAY_LOGW("%s: lhbm is at DISABLED state, skip disabling", __func__);
        return NO_ERROR;
    } else if (enabled && !wasDisabled) {
        requestLhbm(true);
        DISPLAY_LOGI("%s: lhbm is at ENABLING or ENABLED state, re-enable to reset timeout timer",
                     __func__);
        return NO_ERROR;
    }

    std::vector<std::string> checkingValue;
    if (!enabled) {
        ATRACE_NAME("disable_lhbm");
        {
            Mutex::Autolock lock(mDisplayMutex);
            restoreLhbmDisplayConfigLocked();
        }
        requestLhbm(false);
        {
            ATRACE_NAME("wait_for_lhbm_off_cmd");
            checkingValue = {
                    std::to_string(static_cast<int>(BrightnessController::LhbmMode::DISABLED))};
            ret = mBrightnessController->checkSysfsStatus(lhbmSysfs, checkingValue,
                                                          ms2ns(kSysfsCheckTimeoutMs));
            if (ret != OK) {
                DISPLAY_LOGW("%s: failed to send lhbm-off cmd", __func__);
            }
        }
        setLHBMRefreshRateThrottle(0);
        mLhbmOn = false;
        return NO_ERROR;
    }

    ATRACE_NAME("enable_lhbm");
    int64_t lhbmWaitForRrNanos, lhbmEnablingNanos, lhbmEnablingDoneNanos;
    bool enablingStateSupported = !mFramesToReachLhbmPeakBrightness;
    uint32_t peakRate = 0;
    auto rrSysfs = mBrightnessController->GetPanelRefreshRateSysfile();
    lhbmWaitForRrNanos = systemTime(SYSTEM_TIME_MONOTONIC);
    {
        Mutex::Autolock lock(mDisplayMutex);
        peakRate = getPeakRefreshRate();
        if (peakRate < 60) {
            DISPLAY_LOGE("%s: invalid peak rate=%d", __func__, peakRate);
            return -EINVAL;
        }
        ret = setLhbmDisplayConfigLocked(peakRate);
        if (ret != OK) return ret;
    }

    if (mBrightnessController->fileExists(rrSysfs)) {
        ATRACE_NAME("wait_for_peak_rate_cmd");
        ret = mBrightnessController->checkSysfsStatus(rrSysfs, {std::to_string(peakRate)},
                                                      ms2ns(kLhbmWaitForPeakRefreshRateMs));
        if (ret != OK) {
            DISPLAY_LOGW("%s: failed to poll peak refresh rate=%d, ret=%d", __func__, peakRate,
                         ret);
        }
    } else {
        ATRACE_NAME("wait_for_peak_rate_blindly");
        DISPLAY_LOGW("%s: missing refresh rate path: %s", __func__, rrSysfs.c_str());
        // blindly wait for (3 full frames + 1 frame uncertainty) to ensure DM finishes
        // switching refresh rate
        for (int32_t i = 0; i < 4; i++) {
            if (mDisplayInterface->waitVBlank()) {
                DISPLAY_LOGE("%s: failed to blindly wait for peak refresh rate=%d, i=%d", __func__,
                             peakRate, i);
                ret = -ENODEV;
                goto enable_err;
            }
        }
    }

    setLHBMRefreshRateThrottle(kLhbmRefreshRateThrottleMs);
    checkingValue = {std::to_string(static_cast<int>(BrightnessController::LhbmMode::ENABLING)),
                     std::to_string(static_cast<int>(BrightnessController::LhbmMode::ENABLED))};
    lhbmEnablingNanos = systemTime(SYSTEM_TIME_MONOTONIC);
    requestLhbm(true);
    {
        ATRACE_NAME("wait_for_lhbm_on_cmd");
        ret = mBrightnessController->checkSysfsStatus(lhbmSysfs, checkingValue,
                                                      ms2ns(kSysfsCheckTimeoutMs));
        if (ret != OK) {
            DISPLAY_LOGE("%s: failed to enable lhbm", __func__);
            setLHBMRefreshRateThrottle(0);
            goto enable_err;
        }
    }

    lhbmEnablingDoneNanos = systemTime(SYSTEM_TIME_MONOTONIC);
    {
        ATRACE_NAME("wait_for_peak_brightness");
        if (enablingStateSupported) {
            ret = mBrightnessController->checkSysfsStatus(lhbmSysfs,
                                            {std::to_string(static_cast<int>(
                                                    BrightnessController::LhbmMode::ENABLED))},
                                            ms2ns(kSysfsCheckTimeoutMs));
            if (ret != OK) {
                DISPLAY_LOGE("%s: failed to wait for lhbm becoming effective", __func__);
                goto enable_err;
            }
        } else {
            // lhbm takes effect at next vblank
            for (int32_t i = mFramesToReachLhbmPeakBrightness + 1; i > 0; i--) {
                ret = mDisplayInterface->waitVBlank();
                if (ret) {
                    DISPLAY_LOGE("%s: failed to wait vblank for peak brightness, %d", __func__, i);
                    goto enable_err;
                }
            }
        }
    }
    DISPLAY_LOGI("%s: latency: %04d = %03d|rr@%03d + %03d|en + %03d|boost@%s", __func__,
                 getTimestampDeltaMs(0, lhbmWaitForRrNanos),
                 getTimestampDeltaMs(lhbmEnablingNanos, lhbmWaitForRrNanos), peakRate,
                 getTimestampDeltaMs(lhbmEnablingDoneNanos, lhbmEnablingNanos),
                 getTimestampDeltaMs(0, lhbmEnablingDoneNanos),
                 enablingStateSupported ? "polling" : "fixed");

    mLhbmOn = true;
    if (!mPowerModeState.has_value() || (*mPowerModeState == HWC2_POWER_MODE_OFF && mLhbmOn)) {
        mLhbmOn = false;
        DISPLAY_LOGE("%s: power off during request lhbm on", __func__);
        return -EINVAL;
    }
    return NO_ERROR;
enable_err:
    Mutex::Autolock lock(mDisplayMutex);
    restoreLhbmDisplayConfigLocked();
    return ret;
}

bool ExynosPrimaryDisplay::getLhbmState() {
    return mLhbmOn;
}

void ExynosPrimaryDisplay::setLHBMRefreshRateThrottle(const uint32_t delayMs) {
    ATRACE_CALL();

    if (delayMs) {
        // make new throttle take effect
        mLastRefreshRateAppliedNanos = systemTime(SYSTEM_TIME_MONOTONIC);
        DISPLAY_ATRACE_INT64("LastRefreshRateAppliedMs", ns2ms(mLastRefreshRateAppliedNanos));
    }

    setRefreshRateThrottleNanos(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                        std::chrono::milliseconds(delayMs))
                                        .count(),
                                VrrThrottleRequester::LHBM);
}

void ExynosPrimaryDisplay::setEarlyWakeupDisplay() {
    if (mEarlyWakeupDispFd) {
        writeFileNode(mEarlyWakeupDispFd, 1);
    }
}

void ExynosPrimaryDisplay::setExpectedPresentTime(uint64_t timestamp) {
    mExpectedPresentTime.store(timestamp);
}

uint64_t ExynosPrimaryDisplay::getPendingExpectedPresentTime() {
    if (mExpectedPresentTime.is_dirty()) {
        return mExpectedPresentTime.get();
    }

    return 0;
}

void ExynosPrimaryDisplay::applyExpectedPresentTime() {
    mExpectedPresentTime.clear_dirty();
}

int32_t ExynosPrimaryDisplay::setDisplayIdleTimer(const int32_t timeoutMs) {
    bool support = false;
    if (getDisplayIdleTimerSupport(support) || support == false) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    if (timeoutMs < 0) {
        return HWC2_ERROR_BAD_PARAMETER;
    }

    if (timeoutMs > 0) {
        setDisplayIdleDelayNanos(std::chrono::duration_cast<std::chrono::nanoseconds>(
                                         std::chrono::milliseconds(timeoutMs))
                                         .count(),
                                 DispIdleTimerRequester::SF);
    }

    bool enabled = (timeoutMs > 0);
    if (enabled != mDisplayIdleTimerEnabled) {
        if (setDisplayIdleTimerEnabled(enabled) == NO_ERROR) {
            mDisplayIdleTimerEnabled = enabled;
        }
    }

    return HWC2_ERROR_NONE;
}

int32_t ExynosPrimaryDisplay::getDisplayIdleTimerEnabled(bool &enabled) {
    bool support = false;
    if (getDisplayIdleTimerSupport(support) || support == false) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    const std::string path = getPanelSysfsPath(getDisplayTypeFromIndex(mIndex)) + "panel_idle";
    std::ifstream ifs(path);
    if (!ifs.is_open()) {
        ALOGW("%s() unable to open node '%s', error = %s", __func__, path.c_str(), strerror(errno));
        return errno;
    } else {
        std::string panel_idle;
        std::getline(ifs, panel_idle);
        ifs.close();
        enabled = (panel_idle == "1");
        ALOGI("%s() get panel_idle(%d) from the sysfs node", __func__, enabled);
    }
    return NO_ERROR;
}

int32_t ExynosPrimaryDisplay::setDisplayIdleTimerEnabled(const bool enabled) {
    const std::string path = getPanelSysfsPath(getDisplayTypeFromIndex(mIndex)) + "panel_idle";
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        ALOGW("%s() unable to open node '%s', error = %s", __func__, path.c_str(), strerror(errno));
        return errno;
    } else {
        ofs << enabled;
        ofs.close();
        ALOGI("%s() writes panel_idle(%d) to the sysfs node", __func__, enabled);
    }
    return NO_ERROR;
}

int32_t ExynosPrimaryDisplay::setDisplayIdleDelayNanos(const int32_t delayNanos,
                                                       const DispIdleTimerRequester requester) {
    std::lock_guard<std::mutex> lock(mDisplayIdleDelayMutex);

    int64_t maxDelayNanos = 0;
    mDisplayIdleTimerNanos[toUnderlying(requester)] = delayNanos;
    for (uint32_t i = 0; i < toUnderlying(DispIdleTimerRequester::MAX); i++) {
        if (mDisplayIdleTimerNanos[i] > maxDelayNanos) {
            maxDelayNanos = mDisplayIdleTimerNanos[i];
        }
    }

    if (mDisplayIdleDelayNanos == maxDelayNanos) {
        return NO_ERROR;
    }

    mDisplayIdleDelayNanos = maxDelayNanos;

    const int32_t displayIdleDelayMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                                               std::chrono::nanoseconds(mDisplayIdleDelayNanos))
                                               .count();
    const std::string path = getPanelSysfsPath(DisplayType::DISPLAY_PRIMARY) + "idle_delay_ms";
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        ALOGW("%s() unable to open node '%s', error = %s", __func__, path.c_str(), strerror(errno));
        return errno;
    } else {
        ofs << displayIdleDelayMs;
        ALOGI("%s() writes idle_delay_ms(%d) to the sysfs node (0x%x)", __func__,
              displayIdleDelayMs, ofs.rdstate());
        ofs.close();
    }
    return NO_ERROR;
}

void ExynosPrimaryDisplay::initDisplayHandleIdleExit() {
    if (bool support; getDisplayIdleTimerSupport(support) || support == false) {
        return;
    }

    const std::string path =
            getPanelSysfsPath(getDisplayTypeFromIndex(mIndex)) + "panel_need_handle_idle_exit";
    mDisplayNeedHandleIdleExitOfs.open(path, std::ofstream::out);
    if (!mDisplayNeedHandleIdleExitOfs.is_open()) {
        ALOGI("%s() '%s' doesn't exist(%s)", __func__, path.c_str(), strerror(errno));
    }

    setDisplayNeedHandleIdleExit(false, true);
}

void ExynosPrimaryDisplay::setDisplayNeedHandleIdleExit(const bool needed, const bool force) {
    if (!mDisplayNeedHandleIdleExitOfs.is_open()) {
        return;
    }

    if (needed == mDisplayNeedHandleIdleExit && !force) {
        return;
    }

    mDisplayNeedHandleIdleExitOfs << needed;
    if (mDisplayNeedHandleIdleExitOfs.fail()) {
        ALOGW("%s() failed to write panel_need_handle_idle_exit(%d) to sysfs node %s", __func__,
              needed, strerror(errno));
        return;
    }

    mDisplayNeedHandleIdleExitOfs.flush();
    if (mDisplayNeedHandleIdleExitOfs.fail()) {
        ALOGW("%s() failed to flush panel_need_handle_idle_exit(%d) to sysfs node %s", __func__,
              needed, strerror(errno));
        return;
    }

    ALOGI("%s() writes panel_need_handle_idle_exit(%d) to sysfs node", __func__, needed);
    mDisplayNeedHandleIdleExit = needed;
}

void ExynosPrimaryDisplay::handleDisplayIdleEnter(const uint32_t idleTeRefreshRate) {
    Mutex::Autolock lock(mDisplayMutex);
    uint32_t btsRefreshRate = getBtsRefreshRate();
    if (idleTeRefreshRate <= btsRefreshRate) {
        return;
    }

    bool needed = false;
    for (size_t i = 0; i < mLayers.size(); i++) {
        if (mLayers[i]->mOtfMPP && mLayers[i]->mM2mMPP == nullptr &&
            !mLayers[i]->checkBtsCap(idleTeRefreshRate)) {
            needed = true;
            break;
        }
    }

    setDisplayNeedHandleIdleExit(needed, false);
}

int ExynosPrimaryDisplay::setMinIdleRefreshRate(const int fps) {
    mMinIdleRefreshRate = fps;

    const std::string path = getPanelSysfsPath(getDisplayTypeFromIndex(mIndex)) + "min_vrefresh";
    std::ofstream ofs(path);
    if (!ofs.is_open()) {
        ALOGW("Unable to open node '%s', error = %s", path.c_str(), strerror(errno));
        return errno;
    } else {
        ofs << mMinIdleRefreshRate;
        ofs.close();
        ALOGI("ExynosPrimaryDisplay::%s() writes min_vrefresh(%d) to the sysfs node", __func__,
              fps);
    }
    return NO_ERROR;
}

int ExynosPrimaryDisplay::setRefreshRateThrottleNanos(const int64_t delayNanos,
                                                      const VrrThrottleRequester requester) {
    ATRACE_CALL();
    ALOGI("%s() requester(%u) set delay to %" PRId64 "ns", __func__, toUnderlying(requester),
          delayNanos);
    if (delayNanos < 0) {
        ALOGW("%s() set invalid delay(%" PRId64 ")", __func__, delayNanos);
        return BAD_VALUE;
    }

    std::lock_guard<std::mutex> lock(mIdleRefreshRateThrottleMutex);

    int64_t maxDelayNanos = 0;
    mVrrThrottleNanos[toUnderlying(requester)] = delayNanos;
    for (uint32_t i = 0; i < toUnderlying(VrrThrottleRequester::MAX); i++) {
        if (mVrrThrottleNanos[i] > maxDelayNanos) {
            maxDelayNanos = mVrrThrottleNanos[i];
        }
    }

    DISPLAY_ATRACE_INT64("RefreshRateDelay", ns2ms(maxDelayNanos));
    if (mRefreshRateDelayNanos == maxDelayNanos) {
        return NO_ERROR;
    }

    mRefreshRateDelayNanos = maxDelayNanos;
    return setDisplayIdleDelayNanos(mRefreshRateDelayNanos, DispIdleTimerRequester::VRR_THROTTLE);
}

void ExynosPrimaryDisplay::dump(String8 &result) {
    ExynosDisplay::dump(result);
    result.appendFormat("Display idle timer: %s\n",
                        (mDisplayIdleTimerEnabled) ? "enabled" : "disabled");
    for (uint32_t i = 0; i < toUnderlying(DispIdleTimerRequester::MAX); i++) {
        result.appendFormat("\t[%u] vote to %" PRId64 " ns\n", i, mDisplayIdleTimerNanos[i]);
    }
    result.appendFormat("Min idle refresh rate: %d\n", mMinIdleRefreshRate);
    result.appendFormat("Refresh rate delay: %" PRId64 " ns\n", mRefreshRateDelayNanos);
    for (uint32_t i = 0; i < toUnderlying(VrrThrottleRequester::MAX); i++) {
        result.appendFormat("\t[%u] vote to %" PRId64 " ns\n", i, mVrrThrottleNanos[i]);
    }
    if (mOperationRateManager) {
        result.appendFormat("Operation rate: %d\n", mOperationRateManager->getOperationRate());
    }
    result.appendFormat("\n");
}

void ExynosPrimaryDisplay::calculateTimeline(
        hwc2_config_t config, hwc_vsync_period_change_constraints_t *vsyncPeriodChangeConstraints,
        hwc_vsync_period_change_timeline_t *outTimeline) {
    ATRACE_CALL();
    int64_t desiredUpdateTime = vsyncPeriodChangeConstraints->desiredTimeNanos;
    const int64_t origDesiredUpdateTime = desiredUpdateTime;
    const int64_t threshold = mRefreshRateDelayNanos;
    int64_t lastUpdateDelta = 0;
    int64_t actualChangeTime = 0;
    bool isDelayed = false;

    /* actualChangeTime includes transient duration */
    mDisplayInterface->getVsyncAppliedTime(config, &actualChangeTime);

    outTimeline->refreshRequired = true;

    /* when refresh rate is from high to low */
    if (threshold != 0 && mLastRefreshRateAppliedNanos != 0 &&
        mDisplayConfigs[mActiveConfig].vsyncPeriod < mDisplayConfigs[config].vsyncPeriod) {
        lastUpdateDelta = desiredUpdateTime - mLastRefreshRateAppliedNanos;
        if (lastUpdateDelta < threshold) {
            /* in this case, the active config change needs to be delayed */
            isDelayed = true;
            desiredUpdateTime += threshold - lastUpdateDelta;
        }
    }
    mVsyncPeriodChangeConstraints.desiredTimeNanos = desiredUpdateTime;

    getConfigAppliedTime(mVsyncPeriodChangeConstraints.desiredTimeNanos, actualChangeTime,
                         outTimeline->newVsyncAppliedTimeNanos, outTimeline->refreshTimeNanos);

    const nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    DISPLAY_LOGD_AND_ATRACE_NAME(eDebugDisplayConfig,
                                 "requested config : %d(%d)->%d(%d), isDelay:%d,"
                                 " delta %" PRId64 ", delay %" PRId64 ", threshold %" PRId64 ", "
                                 "now:%" PRId64 ", desired %" PRId64 "->%" PRId64
                                 ", newVsyncAppliedTimeNanos : %" PRId64
                                 ", refreshTimeNanos:%" PRId64
                                 ", mLastRefreshRateAppliedNanos:%" PRId64,
                                 mActiveConfig, mDisplayConfigs[mActiveConfig].vsyncPeriod, config,
                                 mDisplayConfigs[config].vsyncPeriod, isDelayed,
                                 ns2ms(lastUpdateDelta), ns2ms(threshold - lastUpdateDelta),
                                 ns2ms(threshold), ns2ms(now), ns2ms(origDesiredUpdateTime),
                                 ns2ms(mVsyncPeriodChangeConstraints.desiredTimeNanos),
                                 ns2ms(outTimeline->newVsyncAppliedTimeNanos),
                                 ns2ms(outTimeline->refreshTimeNanos),
                                 ns2ms(mLastRefreshRateAppliedNanos));

    const int64_t diffMs = ns2ms(outTimeline->refreshTimeNanos - now);
    DISPLAY_ATRACE_INT64("TimeToChangeConfig", diffMs);
}

void ExynosPrimaryDisplay::updateAppliedActiveConfig(const hwc2_config_t newConfig,
                                                     const int64_t ts) {
    ATRACE_CALL();
    if (mAppliedActiveConfig == 0 ||
        getDisplayVsyncPeriodFromConfig(mAppliedActiveConfig) !=
                getDisplayVsyncPeriodFromConfig(newConfig)) {
        DISPLAY_LOGD(eDebugDisplayConfig,
                     "%s mAppliedActiveConfig(%d->%d), mLastRefreshRateAppliedNanos(%" PRIu64
                     " -> %" PRIu64 ")",
                     __func__, mAppliedActiveConfig, newConfig, mLastRefreshRateAppliedNanos, ts);
        mLastRefreshRateAppliedNanos = ts;
        DISPLAY_ATRACE_INT64("LastRefreshRateAppliedMs", ns2ms(mLastRefreshRateAppliedNanos));
    }

    mAppliedActiveConfig = newConfig;
}

void ExynosPrimaryDisplay::checkBtsReassignResource(const uint32_t vsyncPeriod,
                                                    const uint32_t btsVsyncPeriod) {
    ATRACE_CALL();
    uint32_t refreshRate = static_cast<uint32_t>(round(nsecsPerSec / vsyncPeriod * 0.1f) * 10);

    Mutex::Autolock lock(mDRMutex);
    if (vsyncPeriod < btsVsyncPeriod) {
        for (size_t i = 0; i < mLayers.size(); i++) {
            if (mLayers[i]->mOtfMPP && mLayers[i]->mM2mMPP == nullptr &&
                !mLayers[i]->checkBtsCap(refreshRate)) {
                mLayers[i]->setGeometryChanged(GEOMETRY_DEVICE_CONFIG_CHANGED);
                break;
            }
        }
    } else if (vsyncPeriod > btsVsyncPeriod) {
        for (size_t i = 0; i < mLayers.size(); i++) {
            if (mLayers[i]->mOtfMPP && mLayers[i]->mM2mMPP) {
                float srcWidth = mLayers[i]->mSourceCrop.right - mLayers[i]->mSourceCrop.left;
                float srcHeight = mLayers[i]->mSourceCrop.bottom - mLayers[i]->mSourceCrop.top;
                float resolution = srcWidth * srcHeight * refreshRate / 1000;
                float ratioVertical = static_cast<float>(mLayers[i]->mDisplayFrame.bottom -
                                                         mLayers[i]->mDisplayFrame.top) /
                        mYres;

                if (mLayers[i]->mOtfMPP->checkDownscaleCap(resolution, ratioVertical)) {
                    mLayers[i]->setGeometryChanged(GEOMETRY_DEVICE_CONFIG_CHANGED);
                    break;
                }
            }
        }
    }
}

bool ExynosPrimaryDisplay::isDbmSupported() {
    return mBrightnessController->isDbmSupported();
}

int32_t ExynosPrimaryDisplay::setDbmState(bool enabled) {
    mBrightnessController->processDimBrightness(enabled);
    return NO_ERROR;
}
