/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include <log/log.h>
#include <fmq/EventFlag.h>
#include <fmq/MessageQueue.h>
#include <hidl/MQDescriptor.h>
#include <hidl/Status.h>
#include <math.h>
#include "stream_out.h"
#include "talsa.h"
#include "deleters.h"
#include "util.h"
#include <sys/resource.h>
#include <pthread.h>
#include <cutils/sched_policy.h>
#include <utils/ThreadDefs.h>
#include <future>
#include <thread>

namespace android {
namespace hardware {
namespace audio {
namespace V6_0 {
namespace implementation {

using ::android::hardware::Void;
using namespace ::android::hardware::audio::common::V6_0;
using namespace ::android::hardware::audio::V6_0;

namespace {

struct WriteThread : public IOThread {
    typedef MessageQueue<IStreamOut::WriteCommand, kSynchronizedReadWrite> CommandMQ;
    typedef MessageQueue<IStreamOut::WriteStatus, kSynchronizedReadWrite> StatusMQ;
    typedef MessageQueue<uint8_t, kSynchronizedReadWrite> DataMQ;

    WriteThread(StreamOut *stream,
                const unsigned nChannels,
                const size_t sampleRateHz,
                const size_t frameCount,
                const size_t mqBufferSize)
            : mStream(stream)
            , mNChannels(nChannels)
            , mSampleRateHz(sampleRateHz)
            , mFrameCount(frameCount)
            , mCommandMQ(1)
            , mStatusMQ(1)
            , mDataMQ(mqBufferSize, true /* EventFlag */) {
        if (!mCommandMQ.isValid()) {
            ALOGE("WriteThread::%s:%d: mCommandMQ is invalid", __func__, __LINE__);
            return;
        }
        if (!mDataMQ.isValid()) {
            ALOGE("WriteThread::%s:%d: mDataMQ is invalid", __func__, __LINE__);
            return;
        }
        if (!mStatusMQ.isValid()) {
            ALOGE("WriteThread::%s:%d: mStatusMQ is invalid", __func__, __LINE__);
            return;
        }

        status_t status;

        EventFlag* rawEfGroup = nullptr;
        status = EventFlag::createEventFlag(mDataMQ.getEventFlagWord(), &rawEfGroup);
        if (status != OK || !rawEfGroup) {
            ALOGE("WriteThread::%s:%d: rawEfGroup is invalid", __func__, __LINE__);
            return;
        } else {
            mEfGroup.reset(rawEfGroup);
        }

        mThread = std::thread(&WriteThread::threadLoop, this);
    }

    ~WriteThread() {
        if (mThread.joinable()) {
            requestExit();
            mThread.join();
        }
    }

    EventFlag *getEventFlag() override {
        return mEfGroup.get();
    }

    bool isRunning() const {
        return mThread.joinable();
    }

    std::future<pthread_t> getTid() {
        return mTid.get_future();
    }

    void threadLoop() {
        setpriority(PRIO_PROCESS, 0, PRIORITY_URGENT_AUDIO);
        set_sched_policy(0, SP_FOREGROUND);
        mTid.set_value(pthread_self());

        while (true) {
            uint32_t efState = 0;
            mEfGroup->wait(MessageQueueFlagBits::NOT_EMPTY | STAND_BY_REQUEST | EXIT_REQUEST,
                           &efState);
            if (efState & EXIT_REQUEST) {
                return;
            }

            if (efState & STAND_BY_REQUEST) {
                mPcm.reset();
                mBuffer.reset();
            }

            if (efState & (MessageQueueFlagBits::NOT_EMPTY | 0)) {
                if (!mPcm) {
                    mBuffer.reset(new uint8_t[mDataMQ.getQuantumCount()]);
                    LOG_ALWAYS_FATAL_IF(!mBuffer);

                    mPcm = talsa::pcmOpen(
                        talsa::kPcmCard, talsa::kPcmDevice,
                        mNChannels, mSampleRateHz, mFrameCount,
                        true /* isOut */);
                    LOG_ALWAYS_FATAL_IF(!mPcm);

                    mPos.reset();
                }

                processCommand();
            }
        }
    }

    void processCommand() {
        IStreamOut::WriteCommand wCommand;

        if (!mCommandMQ.read(&wCommand)) {
            return;  // Nothing to do.
        }

        IStreamOut::WriteStatus wStatus;
        switch (wCommand) {
            case IStreamOut::WriteCommand::WRITE:
                wStatus = doWrite();
                break;

            case IStreamOut::WriteCommand::GET_PRESENTATION_POSITION:
                wStatus = doGetPresentationPosition();
                break;

            case IStreamOut::WriteCommand::GET_LATENCY:
                wStatus = doGetLatency();
                break;

            default:
                ALOGE("WriteThread::%s:%d: Unknown write thread command code %d",
                      __func__, __LINE__, wCommand);
                wStatus.retval = Result::NOT_SUPPORTED;
                break;
        }

        wStatus.replyTo = wCommand;

        if (!mStatusMQ.write(&wStatus)) {
            ALOGE("status message queue write failed");
        }

        mEfGroup->wake(MessageQueueFlagBits::NOT_FULL | 0);
    }

    IStreamOut::WriteStatus doWrite() {
        IStreamOut::WriteStatus status;

        const size_t availToRead = mDataMQ.availableToRead();
        size_t written = 0;
        if (mDataMQ.read(&mBuffer[0], availToRead)) {
            status.retval = doWriteImpl(&mBuffer[0], availToRead, written);

            mPos.addFrames(pcm_bytes_to_frames(mPcm.get(), written));
            status.reply.written = written;
        } else {
            ALOGE("WriteThread::%s:%d: mDataMQ.read failed", __func__, __LINE__);
            status.retval = Result::OK;
        }

        return status;
    }

    IStreamOut::WriteStatus doGetPresentationPosition() {
        IStreamOut::WriteStatus status;

        status.retval = doGetPresentationPositionImpl(
            status.reply.presentationPosition.frames,
            status.reply.presentationPosition.timeStamp);

        return status;
    }

    IStreamOut::WriteStatus doGetLatency() {
        IStreamOut::WriteStatus status;

        status.retval = Result::OK;
        status.reply.latencyMs = mStream->getLatency();

        return status;
    }

    Result doWriteImpl(const uint8_t *const data,
                       const size_t toWrite,
                       size_t &written) {
        const int res = pcm_write(mPcm.get(), data, toWrite);
        if (res) {
            ALOGE("WriteThread::%s:%d: pcm_write failed with %s",
                  __func__, __LINE__, strerror(-res));
        }

        written = toWrite;
        return Result::OK;
    }

    Result doGetPresentationPositionImpl(uint64_t &frames, TimeSpec &ts) {
        nsecs_t t = 0;
        mPos.now(mSampleRateHz, frames, t);
        ts.tvSec = ns2s(t);
        ts.tvNSec = t - s2ns(ts.tvSec);
        return Result::OK;
    }

    StreamOut *const mStream;
    const unsigned mNChannels;
    const size_t mSampleRateHz;
    const size_t mFrameCount;
    CommandMQ mCommandMQ;
    StatusMQ mStatusMQ;
    DataMQ mDataMQ;
    std::unique_ptr<EventFlag, deleters::forEventFlag> mEfGroup;
    std::unique_ptr<uint8_t[]> mBuffer;
    talsa::PcmPtr mPcm;
    util::StreamPosition mPos;
    std::thread mThread;
    std::promise<pthread_t> mTid;
};

} // namespace

StreamOut::StreamOut(sp<IDevice> dev,
                     void (*unrefDevice)(IDevice*),
                     int32_t ioHandle,
                     const DeviceAddress& device,
                     const AudioConfig& config,
                     hidl_bitfield<AudioOutputFlag> flags,
                     const SourceMetadata& sourceMetadata)
        : mDev(std::move(dev))
        , mUnrefDevice(unrefDevice)
        , mCommon(ioHandle, device, config, flags)
        , mSourceMetadata(sourceMetadata) {
}

StreamOut::~StreamOut() {
    close();
}

Return<uint64_t> StreamOut::getFrameSize() {
    return mCommon.getFrameSize();
}

Return<uint64_t> StreamOut::getFrameCount() {
    return mCommon.getFrameCount();
}

Return<uint64_t> StreamOut::getBufferSize() {
    return mCommon.getBufferSize();
}

Return<uint32_t> StreamOut::getSampleRate() {
    return mCommon.getSampleRate();
}

Return<void> StreamOut::getSupportedSampleRates(AudioFormat format,
                                                getSupportedSampleRates_cb _hidl_cb) {
    mCommon.getSupportedSampleRates(format, _hidl_cb);
    return Void();
}

Return<Result> StreamOut::setSampleRate(uint32_t sampleRateHz) {
    return mCommon.setSampleRate(sampleRateHz);
}

Return<hidl_bitfield<AudioChannelMask>> StreamOut::getChannelMask() {
    return mCommon.getChannelMask();
}

Return<void> StreamOut::getSupportedChannelMasks(AudioFormat format,
                                                 IStream::getSupportedChannelMasks_cb _hidl_cb) {
    mCommon.getSupportedChannelMasks(format, _hidl_cb);
    return Void();
}

Return<Result> StreamOut::setChannelMask(hidl_bitfield<AudioChannelMask> mask) {
    return mCommon.setChannelMask(mask);
}

Return<AudioFormat> StreamOut::getFormat() {
    return mCommon.getFormat();
}

Return<void> StreamOut::getSupportedFormats(getSupportedFormats_cb _hidl_cb) {
    mCommon.getSupportedFormats(_hidl_cb);
    return Void();
}

Return<Result> StreamOut::setFormat(AudioFormat format) {
    return mCommon.setFormat(format);
}

Return<void> StreamOut::getAudioProperties(getAudioProperties_cb _hidl_cb) {
    mCommon.getAudioProperties(_hidl_cb);
    return Void();
}

Return<Result> StreamOut::addEffect(uint64_t effectId) {
    (void)effectId;
    return Result::INVALID_ARGUMENTS;
}

Return<Result> StreamOut::removeEffect(uint64_t effectId) {
    (void)effectId;
    return Result::INVALID_ARGUMENTS;
}

Return<Result> StreamOut::standby() {
    if (mWriteThread) {
        LOG_ALWAYS_FATAL_IF(!mWriteThread->standby());
    }

    return Result::OK;
}

Return<void> StreamOut::getDevices(getDevices_cb _hidl_cb) {
    mCommon.getDevices(_hidl_cb);
    return Void();
}

Return<Result> StreamOut::setDevices(const hidl_vec<DeviceAddress>& devices) {
    return mCommon.setDevices(devices);
}

Return<void> StreamOut::getParameters(const hidl_vec<ParameterValue>& context,
                                      const hidl_vec<hidl_string>& keys,
                                      getParameters_cb _hidl_cb) {
    (void)context;
    _hidl_cb((keys.size() > 0) ? Result::NOT_SUPPORTED : Result::OK, {});
    return Void();
}

Return<Result> StreamOut::setParameters(const hidl_vec<ParameterValue>& context,
                                        const hidl_vec<ParameterValue>& parameters) {
    (void)context;
    (void)parameters;
    return Result::OK;
}

Return<Result> StreamOut::setHwAvSync(uint32_t hwAvSync) {
    (void)hwAvSync;
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::close() {
    if (mDev) {
        mWriteThread.reset();
        mUnrefDevice(mDev.get());
        mDev = nullptr;
        return Result::OK;
    } else {
        return Result::INVALID_STATE;
    }
}

Return<Result> StreamOut::start() {
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::stop() {
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::createMmapBuffer(int32_t minSizeFrames,
                                         createMmapBuffer_cb _hidl_cb) {
    (void)minSizeFrames;
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<void> StreamOut::getMmapPosition(getMmapPosition_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<uint32_t> StreamOut::getLatency() {
    return mCommon.getFrameCount() * 1000 / mCommon.getSampleRate();
}

Return<Result> StreamOut::setVolume(float left, float right) {
    (void)left;
    (void)right;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::updateSourceMetadata(const SourceMetadata& sourceMetadata) {
    (void)sourceMetadata;
    return Void();
}

Return<void> StreamOut::prepareForWriting(uint32_t frameSize,
                                          uint32_t framesCount,
                                          prepareForWriting_cb _hidl_cb) {
    if (!frameSize || !framesCount || frameSize > 256 || framesCount > (1u << 20)) {
        _hidl_cb(Result::INVALID_ARGUMENTS, {}, {}, {}, {});
        return Void();
    }

    if (mWriteThread) {  // INVALID_STATE if the method was already called.
        _hidl_cb(Result::INVALID_STATE, {}, {}, {}, {});
        return Void();
    }

    auto t = std::make_unique<WriteThread>(this,
                                           util::countChannels(mCommon.getChannelMask()),
                                           mCommon.getSampleRate(),
                                           mCommon.getFrameCount(),
                                           frameSize * framesCount);

    if (t->isRunning()) {
        _hidl_cb(Result::OK,
                 *(t->mCommandMQ.getDesc()),
                 *(t->mDataMQ.getDesc()),
                 *(t->mStatusMQ.getDesc()),
                 {.pid = getpid(), .tid = t->getTid().get()});

        mWriteThread = std::move(t);
    } else {
        _hidl_cb(Result::INVALID_ARGUMENTS, {}, {}, {}, {});
    }

    return Void();
}

Return<void> StreamOut::getRenderPosition(getRenderPosition_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, 0);
    return Void();
}

Return<void> StreamOut::getNextWriteTimestamp(getNextWriteTimestamp_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, 0);
    return Void();
}

Return<Result> StreamOut::setCallback(const sp<IStreamOutCallback>& callback) {
    (void)callback;
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::clearCallback() {
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::setEventCallback(const sp<IStreamOutEventCallback>& callback) {
    (void)callback;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::supportsPauseAndResume(supportsPauseAndResume_cb _hidl_cb) {
    _hidl_cb(false, false);
    return Void();
}

Return<Result> StreamOut::pause() {
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::resume() {
    return Result::NOT_SUPPORTED;
}

Return<bool> StreamOut::supportsDrain() {
    return false;
}

Return<Result> StreamOut::drain(AudioDrain type) {
    (void)type;
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamOut::flush() {
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::getPresentationPosition(getPresentationPosition_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {}, {});    // see WriteThread::doGetPresentationPosition
    return Void();
}

Return<Result> StreamOut::selectPresentation(int32_t presentationId,
                                             int32_t programId) {
    (void)presentationId;
    (void)programId;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::getDualMonoMode(getDualMonoMode_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<Result> StreamOut::setDualMonoMode(DualMonoMode mode) {
    (void)mode;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::getAudioDescriptionMixLevel(getAudioDescriptionMixLevel_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, 0);
    return Void();
}

Return<Result> StreamOut::setAudioDescriptionMixLevel(float leveldB) {
    (void)leveldB;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamOut::getPlaybackRateParameters(getPlaybackRateParameters_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<Result> StreamOut::setPlaybackRateParameters(const PlaybackRate &playbackRate) {
    (void)playbackRate;
    return Result::NOT_SUPPORTED;
}

}  // namespace implementation
}  // namespace V6_0
}  // namespace audio
}  // namespace hardware
}  // namespace android
