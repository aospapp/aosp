/*
 * Copyright (C) 2018 The Android Open Source Project
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
#define LOG_TAG "Codec2-InputSurfaceConnection"
#include <log/log.h>

#include <codec2/hidl/1.0/InputSurfaceConnection.h>

#include <media/stagefright/bqhelper/ComponentWrapper.h>

#include <C2BlockInternal.h>
#include <C2PlatformSupport.h>
#include <C2AllocatorGralloc.h>

#include <C2Debug.h>
#include <C2Config.h>
#include <C2Component.h>
#include <C2Work.h>
#include <C2Buffer.h>
#include <C2.h>

#include <ui/GraphicBuffer.h>
#include <system/graphics.h>
#include <utils/Errors.h>

#include <memory>
#include <list>
#include <mutex>
#include <atomic>

namespace /* unnamed */ {

class Buffer2D : public C2Buffer {
public:
    explicit Buffer2D(C2ConstGraphicBlock block) : C2Buffer({ block }) {
    }
};

constexpr int32_t kBufferCount = 16;

} // unnamed namespace

namespace hardware {
namespace google {
namespace media {
namespace c2 {
namespace V1_0 {
namespace utils {

using namespace ::android;

struct InputSurfaceConnection::Impl : public ComponentWrapper {
    Impl(
            const sp<GraphicBufferSource>& source,
            const std::shared_ptr<C2Component>& comp) :
        mSource(source), mComp(comp), mFrameIndex(0) {
    }

    virtual ~Impl() = default;

    bool init() {
        sp<GraphicBufferSource> source = mSource.promote();
        if (source == nullptr) {
            return false;
        }
        status_t err = source->initCheck();
        if (err != OK) {
            ALOGE("Impl::init -- GBS init failed: %d", err);
            return false;
        }

        // Query necessary information for GraphicBufferSource::configure() from
        // the component interface.
        std::shared_ptr<C2Component> comp = mComp.lock();
        if (!comp) {
            ALOGE("Impl::init -- component died.");
            return false;
        }
        std::shared_ptr<C2ComponentInterface> intf = comp->intf();
        if (!intf) {
            ALOGE("Impl::init -- null component interface.");
            return false;
        }

        // TODO: read settings properly from the interface
        C2VideoSizeStreamTuning::input inputSize;
        C2StreamUsageTuning::input usage;
        c2_status_t c2Status = intf->query_vb(
                { &inputSize, &usage },
                {},
                C2_MAY_BLOCK,
                nullptr);
        if (c2Status != C2_OK) {
            ALOGD("Impl::init -- cannot query information from "
                    "the component interface: %s.", asString(c2Status));
            return false;
        }

        // TODO: proper color aspect & dataspace
        android_dataspace dataSpace = HAL_DATASPACE_BT709;

        // TODO: use the usage read from intf
        // uint32_t grallocUsage =
        //         C2AndroidMemoryUsage(C2MemoryUsage(usage.value)).
        //         asGrallocUsage();
        uint32_t grallocUsage =
                strncmp(intf->getName().c_str(), "c2.android.", 11) == 0 ?
                GRALLOC_USAGE_SW_READ_OFTEN :
                GRALLOC_USAGE_HW_VIDEO_ENCODER;

        err = source->configure(
                this, dataSpace, kBufferCount,
                inputSize.width, inputSize.height,
                grallocUsage);
        if (err != OK) {
            ALOGE("Impl::init -- GBS configure failed: %d", err);
            return false;
        }
        for (int32_t i = 0; i < kBufferCount; ++i) {
            if (!source->onInputBufferAdded(i).isOk()) {
                ALOGE("Impl::init: populating GBS slots failed");
                return false;
            }
        }
        if (!source->start().isOk()) {
            ALOGE("Impl::init -- GBS start failed");
            return false;
        }
        mAllocatorMutex.lock();
        c2_status_t c2err = GetCodec2PlatformAllocatorStore()->fetchAllocator(
                C2AllocatorStore::PLATFORM_START + 1,  // GRALLOC
                &mAllocator);
        mAllocatorMutex.unlock();
        if (c2err != OK) {
            ALOGE("Impl::init -- failed to fetch gralloc allocator: %d", c2err);
            return false;
        }
        return true;
    }

    // From ComponentWrapper
    virtual status_t submitBuffer(
            int32_t bufferId,
            const sp<GraphicBuffer>& buffer,
            int64_t timestamp,
            int fenceFd) override {
        ALOGV("Impl::submitBuffer -- bufferId = %d", bufferId);
        // TODO: Use fd to construct fence
        (void)fenceFd;

        std::shared_ptr<C2Component> comp = mComp.lock();
        if (!comp) {
            ALOGW("Impl::submitBuffer -- component died.");
            return NO_INIT;
        }

        std::shared_ptr<C2GraphicAllocation> alloc;
        C2Handle* handle = WrapNativeCodec2GrallocHandle(
                native_handle_clone(buffer->handle),
                buffer->width, buffer->height,
                buffer->format, buffer->usage, buffer->stride);
        mAllocatorMutex.lock();
        c2_status_t err = mAllocator->priorGraphicAllocation(handle, &alloc);
        mAllocatorMutex.unlock();
        if (err != OK) {
            return UNKNOWN_ERROR;
        }
        std::shared_ptr<C2GraphicBlock> block =
                _C2BlockFactory::CreateGraphicBlock(alloc);

        std::unique_ptr<C2Work> work(new C2Work);
        work->input.flags = (C2FrameData::flags_t)0;
        work->input.ordinal.timestamp = timestamp;
        work->input.ordinal.frameIndex = mFrameIndex.fetch_add(
                1, std::memory_order_relaxed);
        work->input.buffers.clear();
        std::shared_ptr<C2Buffer> c2Buffer(
                // TODO: fence
                new Buffer2D(block->share(
                        C2Rect(block->width(), block->height()), ::C2Fence())),
                [bufferId, src = mSource](C2Buffer* ptr) {
                    delete ptr;
                    sp<GraphicBufferSource> source = src.promote();
                    if (source != nullptr) {
                        // TODO: fence
                        (void)source->onInputBufferEmptied(bufferId, -1);
                    }
                });
        work->input.buffers.push_back(c2Buffer);
        work->worklets.clear();
        work->worklets.emplace_back(new C2Worklet);
        std::list<std::unique_ptr<C2Work>> items;
        items.push_back(std::move(work));

        err = comp->queue_nb(&items);
        return (err == C2_OK) ? OK : UNKNOWN_ERROR;
    }

    virtual status_t submitEos(int32_t /* bufferId */) override {
        ALOGV("Impl::submitEos");
        std::shared_ptr<C2Component> comp = mComp.lock();
        if (!comp) {
            ALOGW("Impl::submitEos -- component died.");
            return NO_INIT;
        }

        std::unique_ptr<C2Work> work(new C2Work);
        work->input.flags = (C2FrameData::flags_t)0;
        work->input.ordinal.frameIndex = mFrameIndex.fetch_add(
                1, std::memory_order_relaxed);
        work->input.buffers.clear();
        work->worklets.clear();
        work->worklets.emplace_back(new C2Worklet);
        std::list<std::unique_ptr<C2Work>> items;
        items.push_back(std::move(work));

        c2_status_t err = comp->queue_nb(&items);
        return (err == C2_OK) ? OK : UNKNOWN_ERROR;
    }

    void dispatchDataSpaceChanged(
            int32_t dataSpace, int32_t aspects, int32_t pixelFormat) override {
        // TODO
        (void)dataSpace;
        (void)aspects;
        (void)pixelFormat;
    }

private:
    wp<GraphicBufferSource> mSource;
    std::weak_ptr<C2Component> mComp;

    // Needed for ComponentWrapper implementation
    std::mutex mAllocatorMutex;
    std::shared_ptr<C2Allocator> mAllocator;
    std::atomic_uint64_t mFrameIndex;
};

InputSurfaceConnection::InputSurfaceConnection(
        const sp<GraphicBufferSource>& source,
        const std::shared_ptr<C2Component>& comp) :
    mSource(source),
    mImpl(new Impl(source, comp)) {
}

InputSurfaceConnection::~InputSurfaceConnection() {
    if (mSource) {
        (void)mSource->stop();
        (void)mSource->release();
        mSource.clear();
    }
    mImpl.clear();
}

bool InputSurfaceConnection::init() {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mImpl) {
        return false;
    }
    return mImpl->init();
}

Return<Status> InputSurfaceConnection::disconnect() {
    ALOGV("disconnect");
    mMutex.lock();
    if (mSource) {
        (void)mSource->stop();
        (void)mSource->release();
        mSource.clear();
    }
    mImpl.clear();
    mMutex.unlock();
    ALOGV("disconnected");
    return Status::OK;
}

}  // namespace utils
}  // namespace V1_0
}  // namespace c2
}  // namespace media
}  // namespace google
}  // namespace hardware

