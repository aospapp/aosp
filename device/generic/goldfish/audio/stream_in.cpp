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
#include "stream_in.h"
#include "deleters.h"
#include "talsa.h"
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

namespace {

struct ReadThread : public IOThread {
    typedef MessageQueue<IStreamIn::ReadParameters, kSynchronizedReadWrite> CommandMQ;
    typedef MessageQueue<IStreamIn::ReadStatus, kSynchronizedReadWrite> StatusMQ;
    typedef MessageQueue<uint8_t, kSynchronizedReadWrite> DataMQ;

    ReadThread(StreamIn *stream,
               const unsigned nChannels,
               const size_t sampleRateHz,
               const size_t frameCount,
               const size_t bufferSize)
            : mStream(stream)
            , mNChannels(nChannels)
            , mSampleRateHz(sampleRateHz)
            , mFrameCount(frameCount)
            , mCommandMQ(1)
            , mStatusMQ(1)
            , mDataMQ(bufferSize, true /* EventFlag */) {
        if (!mCommandMQ.isValid()) {
            ALOGE("ReadThread::%s:%d: mCommandMQ is invalid", __func__, __LINE__);
            return;
        }
        if (!mDataMQ.isValid()) {
            ALOGE("ReadThread::%s:%d: mDataMQ is invalid", __func__, __LINE__);
            return;
        }
        if (!mStatusMQ.isValid()) {
            ALOGE("ReadThread::%s:%d: mStatusMQ is invalid", __func__, __LINE__);
            return;
        }

        status_t status;

        EventFlag* rawEfGroup = nullptr;
        status = EventFlag::createEventFlag(mDataMQ.getEventFlagWord(), &rawEfGroup);
        if (status != OK || !rawEfGroup) {
            ALOGE("ReadThread::%s:%d: rawEfGroup is invalid", __func__, __LINE__);
            return;
        } else {
            mEfGroup.reset(rawEfGroup);
        }

        mThread = std::thread(&ReadThread::threadLoop, this);
    }

    ~ReadThread() {
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
            mEfGroup->wait(MessageQueueFlagBits::NOT_FULL | EXIT_REQUEST | STAND_BY_REQUEST,
                           &efState);
            if (efState & EXIT_REQUEST) {
                return;
            }

            if (efState & STAND_BY_REQUEST) {
                mPcm.reset();
                mBuffer.reset();
            }

            if (efState & (MessageQueueFlagBits::NOT_FULL | 0)) {
                if (!mPcm) {
                    mBuffer.reset(new uint8_t[mDataMQ.getQuantumCount()]);
                    LOG_ALWAYS_FATAL_IF(!mBuffer);

                    mPcm = talsa::pcmOpen(
                        talsa::kPcmCard, talsa::kPcmDevice,
                        mNChannels, mSampleRateHz, mFrameCount,
                        false /* isOut */);
                    LOG_ALWAYS_FATAL_IF(!mPcm);

                    mPos.reset();
                }

                processCommand();
            }
        }
    }

    void processCommand() {
        IStreamIn::ReadParameters rParameters;

        if (!mCommandMQ.read(&rParameters)) {
            return;  // Nothing to do.
        }

        IStreamIn::ReadStatus rStatus;
        switch (rParameters.command) {
            case IStreamIn::ReadCommand::READ:
                rStatus = doRead(rParameters);
                break;

            case IStreamIn::ReadCommand::GET_CAPTURE_POSITION:
                rStatus = doGetCapturePosition();
                break;

            default:
                ALOGE("ReadThread::%s:%d: Unknown read thread command code %d",
                      __func__, __LINE__, rParameters.command);
                rStatus.retval = Result::NOT_SUPPORTED;
                break;
        }

        rStatus.replyTo = rParameters.command;

        if (!mStatusMQ.write(&rStatus)) {
            ALOGE("ReadThread::%s:%d: status message queue write failed", __func__, __LINE__);
        }

        mEfGroup->wake(MessageQueueFlagBits::NOT_EMPTY | 0);
    }

    IStreamIn::ReadStatus doRead(const IStreamIn::ReadParameters &rParameters) {
        const size_t bytesToRead = std::min(mDataMQ.availableToWrite(),
                                            static_cast<size_t>(rParameters.params.read));

        IStreamIn::ReadStatus status;
        size_t read = 0;
        status.retval = doReadImpl(&mBuffer[0], bytesToRead, read);
        if (status.retval == Result::OK) {
            if (!mDataMQ.write(&mBuffer[0], read)) {
                ALOGE("ReadThread::%s:%d: mDataMQ.write failed", __func__, __LINE__);
            }

            mPos.addFrames(pcm_bytes_to_frames(mPcm.get(), read));
            status.reply.read = read;
        }

        return status;
    }

    Result doReadImpl(uint8_t *const data, const size_t toRead, size_t &read) {
        const int res = pcm_read(mPcm.get(), data, toRead);
        if (res < 0) {
            memset(data, 0, toRead);
            read = toRead;

            ALOGE("ReadThread::%s:%d pcm_read failed with %s",
                  __func__, __LINE__, strerror(-res));
        } else if (res == 0) {
            read = toRead;
        } else {
            read = res;
        }

        return Result::OK;
    }

    IStreamIn::ReadStatus doGetCapturePosition() {
        IStreamIn::ReadStatus status;

        status.retval = Result::OK;
        nsecs_t t = 0;
        mPos.now(mSampleRateHz, status.reply.capturePosition.frames, t);
        status.reply.capturePosition.time = t;

        return status;
    }

    StreamIn *const mStream;
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

StreamIn::StreamIn(sp<IDevice> dev,
                   void (*unrefDevice)(IDevice*),
                   int32_t ioHandle,
                   const DeviceAddress& device,
                   const AudioConfig& config,
                   hidl_bitfield<AudioInputFlag> flags,
                   const SinkMetadata& sinkMetadata)
        : mDev(std::move(dev))
        , mUnrefDevice(unrefDevice)
        , mCommon(ioHandle, device, config, flags)
        , mSinkMetadata(sinkMetadata) {
}

StreamIn::~StreamIn() {
    close();
}

Return<uint64_t> StreamIn::getFrameSize() {
    return mCommon.getFrameSize();
}

Return<uint64_t> StreamIn::getFrameCount() {
    return mCommon.getFrameCount();
}

Return<uint64_t> StreamIn::getBufferSize() {
    return mCommon.getBufferSize();
}

Return<uint32_t> StreamIn::getSampleRate() {
    return mCommon.getSampleRate();
}

Return<void> StreamIn::getSupportedSampleRates(AudioFormat format,
                                               getSupportedSampleRates_cb _hidl_cb) {
    mCommon.getSupportedSampleRates(format, _hidl_cb);
    return Void();
}

Return<Result> StreamIn::setSampleRate(uint32_t sampleRateHz) {
    return mCommon.setSampleRate(sampleRateHz);
}

Return<hidl_bitfield<AudioChannelMask>> StreamIn::getChannelMask() {
    return mCommon.getChannelMask();
}

Return<void> StreamIn::getSupportedChannelMasks(AudioFormat format,
                                                IStream::getSupportedChannelMasks_cb _hidl_cb) {
    mCommon.getSupportedChannelMasks(format, _hidl_cb);
    return Void();
}

Return<Result> StreamIn::setChannelMask(hidl_bitfield<AudioChannelMask> mask) {
    return mCommon.setChannelMask(mask);
}

Return<AudioFormat> StreamIn::getFormat() {
    return mCommon.getFormat();
}

Return<void> StreamIn::getSupportedFormats(getSupportedFormats_cb _hidl_cb) {
    mCommon.getSupportedFormats(_hidl_cb);
    return Void();
}

Return<Result> StreamIn::setFormat(AudioFormat format) {
    return mCommon.setFormat(format);
}

Return<void> StreamIn::getAudioProperties(getAudioProperties_cb _hidl_cb) {
    mCommon.getAudioProperties(_hidl_cb);
    return Void();
}

Return<Result> StreamIn::addEffect(uint64_t effectId) {
    (void)effectId;
    return Result::INVALID_ARGUMENTS;
}

Return<Result> StreamIn::removeEffect(uint64_t effectId) {
    (void)effectId;
    return Result::INVALID_ARGUMENTS;
}

Return<Result> StreamIn::standby() {
    if (mReadThread) {
        LOG_ALWAYS_FATAL_IF(!mReadThread->standby());
    }

    return Result::OK;
}

Return<void> StreamIn::getDevices(getDevices_cb _hidl_cb) {
    mCommon.getDevices(_hidl_cb);
    return Void();
}

Return<Result> StreamIn::setDevices(const hidl_vec<DeviceAddress>& devices) {
    return mCommon.setDevices(devices);
}

Return<void> StreamIn::getParameters(const hidl_vec<ParameterValue>& context,
                                     const hidl_vec<hidl_string>& keys,
                                     getParameters_cb _hidl_cb) {
    (void)context;
    _hidl_cb((keys.size() > 0) ? Result::NOT_SUPPORTED : Result::OK, {});
    return Void();
}

Return<Result> StreamIn::setParameters(const hidl_vec<ParameterValue>& context,
                                       const hidl_vec<ParameterValue>& parameters) {
    (void)context;
    (void)parameters;
    return Result::OK;
}

Return<Result> StreamIn::setHwAvSync(uint32_t hwAvSync) {
    (void)hwAvSync;
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamIn::start() {
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamIn::stop() {
    return Result::NOT_SUPPORTED;
}

Return<void> StreamIn::createMmapBuffer(int32_t minSizeFrames,
                                        createMmapBuffer_cb _hidl_cb) {
    (void)minSizeFrames;
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<void> StreamIn::getMmapPosition(getMmapPosition_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<Result> StreamIn::close() {
    if (mDev) {
        mReadThread.reset();
        mUnrefDevice(mDev.get());
        mDev = nullptr;
        return Result::OK;
    } else {
        return Result::INVALID_STATE;
    }
}

Return<void> StreamIn::getAudioSource(getAudioSource_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, {});
    return Void();
}

Return<Result> StreamIn::setGain(float gain) {
    (void)gain;
    return Result::NOT_SUPPORTED;
}

Return<void> StreamIn::updateSinkMetadata(const SinkMetadata& sinkMetadata) {
    (void)sinkMetadata;
    return Void();
}

Return<void> StreamIn::prepareForReading(uint32_t frameSize,
                                         uint32_t framesCount,
                                         prepareForReading_cb _hidl_cb) {
    if (!frameSize || !framesCount || frameSize > 256 || framesCount > (1u << 20)) {
        _hidl_cb(Result::INVALID_ARGUMENTS, {}, {}, {}, {});
        return Void();
    }

    if (mReadThread) {  // INVALID_STATE if the method was already called.
        _hidl_cb(Result::INVALID_STATE, {}, {}, {}, {});
        return Void();
    }

    auto t = std::make_unique<ReadThread>(this,
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

        mReadThread = std::move(t);
    } else {
        _hidl_cb(Result::INVALID_ARGUMENTS, {}, {}, {}, {});
    }

    return Void();
}

Return<uint32_t> StreamIn::getInputFramesLost() {
    return 0;
}

Return<void> StreamIn::getCapturePosition(getCapturePosition_cb _hidl_cb) {
    _hidl_cb(Result::NOT_SUPPORTED, 0, 0);  // see ReadThread::doGetCapturePosition
    return Void();
}

Return<void> StreamIn::getActiveMicrophones(getActiveMicrophones_cb _hidl_cb) {
    _hidl_cb(Result::OK, {util::getMicrophoneInfo()});
    return Void();
}

Return<Result> StreamIn::setMicrophoneDirection(MicrophoneDirection direction) {
    (void)direction;
    return Result::NOT_SUPPORTED;
}

Return<Result> StreamIn::setMicrophoneFieldDimension(float zoom) {
    (void)zoom;
    return Result::NOT_SUPPORTED;
}

}  // namespace implementation
}  // namespace V6_0
}  // namespace audio
}  // namespace hardware
}  // namespace android
