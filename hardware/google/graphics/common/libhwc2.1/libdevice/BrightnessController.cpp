/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define ATRACE_TAG (ATRACE_TAG_GRAPHICS | ATRACE_TAG_HAL)

#include <cutils/properties.h>
#include <poll.h>

#include "BrightnessController.h"
#include "ExynosHWCModule.h"

BrightnessController::BrightnessController(int32_t panelIndex, std::function<void(void)> refresh,
                                           std::function<void(void)> updateDcLhbm)
      : mPanelIndex(panelIndex),
        mEnhanceHbmReq(false),
        mLhbmReq(false),
        mBrightnessFloatReq(-1),
        mBrightnessLevel(0),
        mGhbm(HbmMode::OFF),
        mDimming(false),
        mLhbm(false),
        mSdrDim(false),
        mPrevSdrDim(false),
        mFrameRefresh(refresh),
        mHdrLayerState(HdrLayerState::kHdrNone),
        mUpdateDcLhbm(updateDcLhbm) {
    initBrightnessSysfs();
}

BrightnessController::~BrightnessController() {
    if (mDimmingLooper) {
        mDimmingLooper->removeMessages(mDimmingHandler);
    }
    if (mDimmingThreadRunning) {
        mDimmingLooper->sendMessage(mDimmingHandler, DimmingMsgHandler::MSG_QUIT);
        mDimmingThread.join();
    }
}

int BrightnessController::initDrm(const DrmDevice& drmDevice,
                                  const DrmConnector& connector) {
    initBrightnessTable(drmDevice, connector);

    initDimmingUsage();

    mLhbmSupported = connector.lhbm_on().id() != 0;
    mGhbmSupported = connector.hbm_mode().id() != 0;
    return NO_ERROR;
}

void BrightnessController::initDimmingUsage() {
    mBrightnessDimmingUsage = static_cast<BrightnessDimmingUsage>(
            property_get_int32("vendor.display.brightness.dimming.usage", 0));
    mHbmDimmingTimeUs =
            property_get_int32("vendor.display.brightness.dimming.hbm_time", kHbmDimmingTimeUs);

    if (mBrightnessDimmingUsage == BrightnessDimmingUsage::NORMAL) {
        mDimming.store(true);
    }

    if (mBrightnessDimmingUsage == BrightnessDimmingUsage::HBM) {
        mDimmingHandler = new DimmingMsgHandler(this);
        mDimmingThread = std::thread(&BrightnessController::dimmingThread, this);
    }
}

void BrightnessController::initBrightnessSysfs() {
    String8 nodeName;
    nodeName.appendFormat(BRIGHTNESS_SYSFS_NODE, mPanelIndex);
    mBrightnessOfs.open(nodeName.string(), std::ofstream::out);
    if (mBrightnessOfs.fail()) {
        ALOGE("%s %s fail to open", __func__, nodeName.string());
        mBrightnessOfs.close();
        return;
    }

    nodeName.clear();
    nodeName.appendFormat(MAX_BRIGHTNESS_SYSFS_NODE, mPanelIndex);

    std::ifstream ifsMaxBrightness(nodeName.string());
    if (ifsMaxBrightness.fail()) {
        ALOGE("%s fail to open %s", __func__, nodeName.string());
        return;
    }

    ifsMaxBrightness >> mMaxBrightness;
    ifsMaxBrightness.close();
}

void BrightnessController::initBrightnessTable(const DrmDevice& drmDevice,
                                               const DrmConnector& connector) {
    if (connector.brightness_cap().id() == 0) {
        ALOGD("the brightness_cap is not supported");
        return;
    }

    const auto [ret, blobId] = connector.brightness_cap().value();
    if (ret) {
        ALOGE("Fail to get brightness_cap (ret = %d)", ret);
        return;
    }

    if (blobId == 0) {
        ALOGE("the brightness_cap is supported but blob is not valid");
        return;
    }

    drmModePropertyBlobPtr blob = drmModeGetPropertyBlob(drmDevice.fd(), blobId);
    if (blob == nullptr) {
        ALOGE("Fail to get brightness_cap blob");
        return;
    }

    const struct brightness_capability *cap =
            reinterpret_cast<struct brightness_capability *>(blob->data);
    mBrightnessTable[toUnderlying(BrightnessRange::NORMAL)] = BrightnessTable(cap->normal);
    mBrightnessTable[toUnderlying(BrightnessRange::HBM)] = BrightnessTable(cap->hbm);

    parseHbmModeEnums(connector.hbm_mode());

    // init to min before SF sets the brightness
    mDisplayWhitePointNits = cap->normal.nits.min;
    mBrightnessIntfSupported = true;

    drmModeFreePropertyBlob(blob);
}

int BrightnessController::processEnhancedHbm(bool on) {
    if (!mGhbmSupported) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    mEnhanceHbmReq.store(on);
    if (mEnhanceHbmReq.is_dirty()) {
        updateStates();
    }
    return NO_ERROR;
}

void BrightnessController::processDimmingOff() {
    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    if (mHbmDimming) {
        mHbmDimming = false;
        updateStates();
        mFrameRefresh();
    }
}

int BrightnessController::processDisplayBrightness(float brightness, const nsecs_t vsyncNs,
                                                   bool waitPresent) {
    uint32_t level;
    bool ghbm;

    if (brightness < -1.0f || brightness > 1.0f) {
        return HWC2_ERROR_BAD_PARAMETER;
    }

    ATRACE_CALL();
    if (!mBrightnessIntfSupported) {
        level = brightness < 0 ? 0 : static_cast<uint32_t>(brightness * mMaxBrightness + 0.5f);
        return applyBrightnessViaSysfs(level);
    }

    {
        std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
        mBrightnessFloatReq.store(brightness);
        if (!mBrightnessFloatReq.is_dirty()) {
            return NO_ERROR;
        }

        // check if it will go drm path for below cases.
        // case 1: hbm state will change
        // case 2: for hwc3, brightness command could apply at next present if possible
        if (mGhbmSupported || waitPresent) {
            // ghbm on/off always go drm path
            if (mGhbmSupported) {
                if (queryBrightness(brightness, &ghbm, &level)) {
                    ALOGE("%s failed to convert brightness %f", __func__, brightness);
                    return -EINVAL;
                }
                // check if this will cause a hbm transition
                if ((mGhbm.get() != HbmMode::OFF) != ghbm) {
                    // this brightness change will go drm path
                    updateStates();
                    mFrameRefresh(); // force next frame to update brightness
                    return NO_ERROR;
                }
            }
            // there will be a Present to apply this brightness change
            if (waitPresent) {
                // this brightness change will go drm path
                updateStates();
                return NO_ERROR;
            }
        } else {
            level = brightness < 0 ? 0 : static_cast<uint32_t>(brightness * mMaxBrightness + 0.5f);
        }
        // go sysfs path
    }

    // Sysfs path is faster than drm path. If there is an unchecked drm path change, the sysfs
    // path should check the sysfs content.
    if (mUncheckedGbhmRequest) {
        ATRACE_NAME("check_ghbm_mode");
        checkSysfsStatus(kGlobalHbmModeFileNode,
                         std::to_string(toUnderlying(mPendingGhbmStatus.load())), vsyncNs * 5);
        mUncheckedGbhmRequest = false;
    }

    if (mUncheckedLhbmRequest) {
        ATRACE_NAME("check_lhbm_mode");
        checkSysfsStatus(kLocalHbmModeFileNode, std::to_string(mPendingLhbmStatus), vsyncNs * 5);
        mUncheckedLhbmRequest = false;
    }

    return applyBrightnessViaSysfs(level);
}

// In HWC3, brightness change could be applied via drm commit or sysfs path.
// If a brightness change command does not come with a frame update, this
// function wil be called to apply the brghtness change via sysfs path.
int BrightnessController::applyPendingChangeViaSysfs(const nsecs_t vsyncNs) {
    ATRACE_CALL();
    uint32_t level;
    {
        std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);

        if (!mBrightnessLevel.is_dirty()) {
            return NO_ERROR;
        }

        // there will be a drm commit to apply this brightness change if a GHBM change is pending.
        if (mGhbm.is_dirty()) {
            ALOGI("%s standalone brightness change will be handled by next frame update for GHBM",
                  __func__);
            return NO_ERROR;
        }

        // there will be a drm commit to apply this brightness change if a LHBM change is pending.
        if (mLhbm.is_dirty()) {
            ALOGI("%s standalone brightness change will be handled by next frame update for LHBM",
                  __func__);
            return NO_ERROR;
        }

        level = mBrightnessLevel.get();
    }

    if (mUncheckedBlRequest) {
        ATRACE_NAME("check_bl_value");
        checkSysfsStatus(BRIGHTNESS_SYSFS_NODE, std::to_string(mPendingBl), vsyncNs * 5);
        mUncheckedBlRequest = false;
    }

    return applyBrightnessViaSysfs(level);
}

int BrightnessController::processLocalHbm(bool on) {
    if (!mLhbmSupported) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    mLhbmReq.store(on);
    if (mLhbmReq.is_dirty()) {
        updateStates();
    }

    return NO_ERROR;
}

void BrightnessController::updateFrameStates(HdrLayerState hdrState, bool sdrDim) {
    mHdrLayerState.store(hdrState);
    if (!mGhbmSupported) {
        return;
    }

    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    mPrevSdrDim.store(mSdrDim.get());
    mSdrDim.store(sdrDim);
    if (mSdrDim.is_dirty() || mPrevSdrDim.is_dirty()) {
        updateStates();
    }
}

int BrightnessController::processInstantHbm(bool on) {
    if (!mGhbmSupported) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    mInstantHbmReq.store(on);
    if (mInstantHbmReq.is_dirty()) {
        updateStates();
    }
    return NO_ERROR;
}

float BrightnessController::getSdrDimRatioForInstantHbm() {
    if (!mBrightnessIntfSupported || !mGhbmSupported) {
        return 1.0f;
    }

    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    if (!mInstantHbmReq.get()) {
        return 1.0f;
    }

    float sdr = 0;
    if (queryBrightness(mBrightnessFloatReq.get(), nullptr, nullptr, &sdr) != NO_ERROR) {
        return 1.0f;
    }

    float peak = mBrightnessTable[toUnderlying(BrightnessRange::MAX) - 1].mNitsEnd;
    if (sdr == 0 || peak == 0) {
        ALOGW("%s error luminance value sdr %f peak %f", __func__, sdr, peak);
        return 1.0f;
    }

    float ratio = sdr / peak;
    if (ratio < kGhbmMinDimRatio) {
        ALOGW("%s sdr dim ratio %f too small", __func__, ratio);
        ratio = kGhbmMinDimRatio;
    }

    return ratio;
}

void BrightnessController::onClearDisplay() {
    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    mEnhanceHbmReq.reset(false);
    mLhbmReq.reset(false);
    mBrightnessFloatReq.reset(-1);
    mInstantHbmReq.reset(false);

    mBrightnessLevel.reset(0);
    mGhbm.reset(HbmMode::OFF);
    mDimming.reset(false);
    mHbmDimming = false;
    if (mBrightnessDimmingUsage == BrightnessDimmingUsage::NORMAL) {
        mDimming.store(true);
    }
    mLhbm.reset(false);

    mLhbmBrightnessAdj = false;
}

int BrightnessController::prepareFrameCommit(ExynosDisplay& display,
                              const DrmConnector& connector,
                              ExynosDisplayDrmInterface::DrmModeAtomicReq& drmReq,
                              bool& ghbmSync, bool& lhbmSync, bool& blSync) {
    int ret;

    ghbmSync = false;
    lhbmSync = false;
    blSync = false;

    ATRACE_CALL();
    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);

    if (mDimming.is_dirty()) {
        if ((ret = drmReq.atomicAddProperty(connector.id(), connector.dimming_on(),
                                            mDimming.get())) < 0) {
            ALOGE("%s: Fail to set dimming_on property", __func__);
        }
        mDimming.clear_dirty();
    }

    if (mLhbm.is_dirty() && mLhbmSupported) {
        if ((ret = drmReq.atomicAddProperty(connector.id(), connector.lhbm_on(),
                                            mLhbm.get())) < 0) {
            ALOGE("%s: Fail to set lhbm_on property", __func__);
        } else {
            lhbmSync = true;
        }

        auto dbv = mBrightnessLevel.get();
        auto old_dbv = dbv;
        if (mLhbm.get()) {
            mUpdateDcLhbm();
            uint32_t dbv_adj = 0;
            if (display.getColorAdjustedDbv(dbv_adj)) {
                ALOGW("failed to get adjusted dbv");
            } else if (dbv_adj != dbv && dbv_adj != 0) {
                dbv_adj = std::clamp(dbv_adj,
                        mBrightnessTable[toUnderlying(BrightnessRange::NORMAL)].mBklStart,
                        mBrightnessTable[toUnderlying(BrightnessRange::NORMAL)].mBklEnd);

                ALOGI("lhbm: adjust dbv from %d to %d", dbv, dbv_adj);
                dbv = dbv_adj;
                mLhbmBrightnessAdj = (dbv != old_dbv);
            }
        }

        if (mLhbmBrightnessAdj) {
            // case 1: lhbm on and dbv is changed, use the new dbv
            // case 2: lhbm off and dbv was changed at lhbm on, use current dbv
            if ((ret = drmReq.atomicAddProperty(connector.id(),
                                               connector.brightness_level(), dbv)) < 0) {
                ALOGE("%s: Fail to set brightness_level property", __func__);
            } else {
                blSync = true;
                mUncheckedBlRequest = true;
                mPendingBl = dbv;
            }
        }

        // mLhbmBrightnessAdj will last from LHBM on to off
        if (!mLhbm.get() && mLhbmBrightnessAdj) {
            mLhbmBrightnessAdj = false;
        }

        mLhbm.clear_dirty();
    }

    if (mBrightnessLevel.is_dirty()) {
        // skip if lhbm has updated bl
        if (!blSync) {
            if ((ret = drmReq.atomicAddProperty(connector.id(),
                                                connector.brightness_level(),
                                                mBrightnessLevel.get())) < 0) {
                ALOGE("%s: Fail to set brightness_level property", __func__);
            } else {
                mUncheckedBlRequest = true;
                mPendingBl = mBrightnessLevel.get();
            }
        }
        mBrightnessLevel.clear_dirty();
    }

    if (mGhbm.is_dirty() && mGhbmSupported) {
        HbmMode hbmMode = mGhbm.get();
        auto [hbmEnum, ret] = DrmEnumParser::halToDrmEnum(static_cast<int32_t>(hbmMode),
                                                          mHbmModeEnums);
        if (ret < 0) {
            ALOGE("Fail to convert hbm mode(%d)", hbmMode);
            return ret;
        }

        if ((ret = drmReq.atomicAddProperty(connector.id(), connector.hbm_mode(),
                                            hbmEnum)) < 0) {
            ALOGE("%s: Fail to set hbm_mode property", __func__);
        } else {
            ghbmSync = true;
        }
        mGhbm.clear_dirty();
    }

    mHdrLayerState.clear_dirty();
    return NO_ERROR;
}

void BrightnessController::DimmingMsgHandler::handleMessage(const ::android::Message& message) {
    ALOGI("%s %d", __func__, message.what);

    switch (message.what) {
        case MSG_DIMMING_OFF:
            mBrightnessController->processDimmingOff();
            break;

        case MSG_QUIT:
            mBrightnessController->mDimmingThreadRunning = false;
            break;
    }
}

void BrightnessController::dimmingThread() {
    mDimmingLooper = new Looper(false);
    Looper::setForThread(mDimmingLooper);
    mDimmingThreadRunning = true;
    while (mDimmingThreadRunning.load(std::memory_order_relaxed)) {
        mDimmingLooper->pollOnce(-1);
    }
}

// Process all requests to update states for next commit
int BrightnessController::updateStates() {
    bool ghbm;
    uint32_t level;
    float brightness = mInstantHbmReq.get() ? 1.0f : mBrightnessFloatReq.get();
    if (queryBrightness(brightness, &ghbm, &level, &mDisplayWhitePointNits)) {
        ALOGW("%s failed to convert brightness %f", __func__, mBrightnessFloatReq.get());
        return HWC2_ERROR_UNSUPPORTED;
    }

    mBrightnessLevel.store(level);
    mLhbm.store(mLhbmReq.get());

    // turn off irc for sun light visibility
    bool irc = !mEnhanceHbmReq.get();
    if (ghbm) {
        mGhbm.store(irc ? HbmMode::ON_IRC_ON : HbmMode::ON_IRC_OFF);
    } else {
        mGhbm.store(HbmMode::OFF);
    }

    if (mLhbm.is_dirty()) {
        // Next sysfs path should verify this change has been applied.
        mUncheckedLhbmRequest = true;
        mPendingLhbmStatus = mLhbm.get();
    }
    if (mGhbm.is_dirty()) {
        // Next sysfs path should verify this change has been applied.
        mUncheckedGbhmRequest = true;
        mPendingGhbmStatus = mGhbm.get();
    }

    // no dimming for instant hbm
    // no dimming if current or previous frame is mixed composition
    //  - frame N-1: no HDR, HBM off, no sdr dim
    //  - frame N: HDR visible HBM on, sdr dim is enabled
    //  - frame N+1, HDR gone, HBM off, no sdr dim.
    //  We don't need panel dimming for HBM on at frame N and HBM off at frame N+1
    bool dimming = !mInstantHbmReq.get() && !mSdrDim.get() && !mPrevSdrDim.get();
    switch (mBrightnessDimmingUsage) {
        case BrightnessDimmingUsage::HBM:
            // turn on dimming at HBM on/off
            // turn off dimming after mHbmDimmingTimeUs or there is an instant hbm on/off
            if (mGhbm.is_dirty() && dimming) {
                mHbmDimming = true;
                if (mDimmingLooper) {
                    mDimmingLooper->removeMessages(mDimmingHandler,
                                                   DimmingMsgHandler::MSG_DIMMING_OFF);
                    mDimmingLooper->sendMessageDelayed(us2ns(mHbmDimmingTimeUs), mDimmingHandler,
                                                       DimmingMsgHandler::MSG_DIMMING_OFF);
                }
            }

            dimming = dimming && (mHbmDimming);
            break;

        case BrightnessDimmingUsage::NONE:
            dimming = false;
            break;

        default:
            break;
    }
    mDimming.store(dimming);

    mEnhanceHbmReq.clear_dirty();
    mLhbmReq.clear_dirty();
    mBrightnessFloatReq.clear_dirty();
    mInstantHbmReq.clear_dirty();
    mSdrDim.clear_dirty();
    mPrevSdrDim.clear_dirty();

    if (mBrightnessLevel.is_dirty() || mDimming.is_dirty() || mGhbm.is_dirty() ||
        mLhbm.is_dirty()) {
        ALOGI("level=%d, DimmingOn=%d, Hbm=%d, LhbmOn=%d.", mBrightnessLevel.get(), mDimming.get(),
              mGhbm.get(), mLhbm.get());
    }
    return NO_ERROR;
}

int BrightnessController::queryBrightness(float brightness, bool *ghbm, uint32_t *level,
                                               float *nits) {
    if (!mBrightnessIntfSupported) {
        return HWC2_ERROR_UNSUPPORTED;
    }

    if (brightness < 0) {
        // screen off
        if (ghbm) {
            *ghbm = false;
        }
        if (level) {
            *level = 0;
        }
        if (nits) {
            *nits = 0;
        }
        return NO_ERROR;
    }

    for (uint32_t i = 0; i < toUnderlying(BrightnessRange::MAX); ++i) {
        if (brightness <= mBrightnessTable[i].mBriEnd) {
            if (ghbm) {
                *ghbm = (i == toUnderlying(BrightnessRange::HBM));
            }

            if (level || nits) {
                auto fSpan = mBrightnessTable[i].mBriEnd - mBrightnessTable[i].mBriStart;
                auto norm = fSpan == 0 ? 1 : (brightness - mBrightnessTable[i].mBriStart) / fSpan;

                if (level) {
                    auto iSpan = mBrightnessTable[i].mBklEnd - mBrightnessTable[i].mBklStart;
                    auto bl = norm * iSpan + mBrightnessTable[i].mBklStart;
                    *level = static_cast<uint32_t>(bl + 0.5);
                }

                if (nits) {
                    auto nSpan = mBrightnessTable[i].mNitsEnd - mBrightnessTable[i].mNitsStart;
                    *nits = norm * nSpan + mBrightnessTable[i].mNitsStart;
                }
            }

            return NO_ERROR;
        }
    }

    return -EINVAL;
}

// Return immediately if it's already in the status. Otherwise poll the status
int BrightnessController::checkSysfsStatus(const char* file, const std::string& expectedValue,
                                           const nsecs_t timeoutNs) {
    ATRACE_CALL();
    char buf[16];
    String8 nodeName;
    nodeName.appendFormat(file, mPanelIndex);
    UniqueFd fd = open(nodeName.string(), O_RDONLY);

    int size = read(fd.get(), buf, sizeof(buf));
    if (size <= 0) {
        ALOGE("%s failed to read from %s", __func__, kLocalHbmModeFileNode);
        return false;
    }

    // '- 1' to remove trailing '\n'
    if (std::string_view(buf, size - 1) == expectedValue) {
        return true;
    }

    struct pollfd pfd;
    int ret = EINVAL;

    auto startTime = systemTime(SYSTEM_TIME_MONOTONIC);
    pfd.fd = fd.get();
    pfd.events = POLLPRI;
    while (true) {
        auto currentTime = systemTime(SYSTEM_TIME_MONOTONIC);
        // int64_t for nsecs_t
        auto remainTimeNs = timeoutNs - (currentTime - startTime);
        if (remainTimeNs <= 0) {
            remainTimeNs = ms2ns(1);
        }
        int pollRet = poll(&pfd, 1, ns2ms(remainTimeNs));
        if (pollRet == 0) {
            ALOGW("%s poll timeout", __func__);
            // time out
            ret = ETIMEDOUT;
            break;
        } else if (pollRet > 0) {
            if (!(pfd.revents & POLLPRI)) {
                continue;
            }

            lseek(fd.get(), 0, SEEK_SET);
            size = read(fd.get(), buf, sizeof(buf));
            if (size > 0) {
                if (std::string_view(buf, size - 1) == expectedValue) {
                    ret = 0;
                } else {
                    buf[size - 1] = 0;
                    ALOGE("%s read %s expected %s after notified", __func__, buf,
                          expectedValue.c_str());
                    ret = EINVAL;
                }
            } else {
                ret = EIO;
                ALOGE("%s failed to read after notified %d", __func__, errno);
            }
            break;
        } else {
            if (errno == EAGAIN || errno == EINTR) {
                continue;
            }

            ALOGE("%s poll failed %d", __func__, errno);
            ret = errno;
            break;
        }
    };

    return ret == NO_ERROR;
}

int BrightnessController::applyBrightnessViaSysfs(uint32_t level) {
    if (mBrightnessOfs.is_open()) {
        ATRACE_NAME("write_bl_sysfs");
        mBrightnessOfs.seekp(std::ios_base::beg);
        mBrightnessOfs << std::to_string(level);
        mBrightnessOfs.flush();
        if (mBrightnessOfs.fail()) {
            ALOGE("%s fail to write brightness %d", __func__, level);
            mBrightnessOfs.clear();
            return HWC2_ERROR_NO_RESOURCES;
        }

        {
            std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
            mBrightnessLevel.reset(level);
            ALOGI("level=%d, DimmingOn=%d, Hbm=%d, LhbmOn=%d", level,
                  mDimming.get(), mGhbm.get(), mLhbm.get());
        }

        return NO_ERROR;
    }

    return HWC2_ERROR_UNSUPPORTED;
}

// brightness is normalized to current display brightness
bool BrightnessController::validateLayerBrightness(float brightness) {
    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);
    if (!std::isfinite(brightness)) {
        ALOGW("%s layer brightness %f is not a valid floating value", __func__, brightness);
        return false;
    }

    if (brightness > 1.f || brightness < 0.f) {
        ALOGW("%s Brightness is out of [0, 1] range: %f", __func__, brightness);
        return false;
    }

    return true;
}

void BrightnessController::parseHbmModeEnums(const DrmProperty& property) {
    const std::vector<std::pair<uint32_t, const char *>> modeEnums = {
            {static_cast<uint32_t>(HbmMode::OFF), "Off"},
            {static_cast<uint32_t>(HbmMode::ON_IRC_ON), "On IRC On"},
            {static_cast<uint32_t>(HbmMode::ON_IRC_OFF), "On IRC Off"},
    };

    DrmEnumParser::parseEnums(property, modeEnums, mHbmModeEnums);
    for (auto &e : mHbmModeEnums) {
        ALOGD("hbm mode [hal: %d, drm: %" PRId64 ", %s]", e.first, e.second,
              modeEnums[e.first].second);
    }
}

void BrightnessController::dump(String8& result) {
    std::lock_guard<std::recursive_mutex> lock(mBrightnessMutex);

    result.appendFormat("BrightnessController:\n");
    result.appendFormat("\tsysfs support %d, max %d, valid brightness table %d, "
                        "lhbm supported %d, ghbm supported %d\n", mBrightnessOfs.is_open(),
                        mMaxBrightness, mBrightnessIntfSupported, mLhbmSupported, mGhbmSupported);
    result.appendFormat("\trequests: enhance hbm %d, lhbm %d, "
                        "brightness %f, instant hbm %d\n",
                        mEnhanceHbmReq.get(), mLhbmReq.get(), mBrightnessFloatReq.get(),
                        mInstantHbmReq.get());
    result.appendFormat("\tstates: brighntess level %d, ghbm %d, dimming %d, lhbm %d\n",
                        mBrightnessLevel.get(), mGhbm.get(), mDimming.get(), mLhbm.get());
    result.appendFormat("\thdr layer state %d, unchecked lhbm request %d(%d), "
                        "unchecked ghbm request %d(%d)\n",
                        mHdrLayerState.get(), mUncheckedLhbmRequest.load(),
                        mPendingLhbmStatus.load(), mUncheckedGbhmRequest.load(),
                        mPendingGhbmStatus.load());
    result.appendFormat("\tdimming usage %d, hbm dimming %d, time us %d\n", mBrightnessDimmingUsage,
                        mHbmDimming, mHbmDimmingTimeUs);
    result.appendFormat("\twhite point nits %f\n", mDisplayWhitePointNits);
    result.appendFormat("\n");
}
