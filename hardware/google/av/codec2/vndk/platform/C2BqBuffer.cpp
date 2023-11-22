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
#define LOG_TAG "C2BqBuffer"
#include <utils/Log.h>

#include <gui/BufferQueueDefs.h>
#include <list>
#include <map>
#include <mutex>

#include <C2AllocatorGralloc.h>
#include <C2BqBufferPriv.h>
#include <C2BlockInternal.h>

using ::android::AnwBuffer;
using ::android::BufferQueueDefs::NUM_BUFFER_SLOTS;
using ::android::C2AllocatorGralloc;
using ::android::C2AndroidMemoryUsage;
using ::android::Fence;
using ::android::GraphicBuffer;
using ::android::HGraphicBufferProducer;
using ::android::IGraphicBufferProducer;
using ::android::hardware::graphics::common::V1_0::PixelFormat;
using ::android::hidl_handle;
using ::android::sp;
using ::android::status_t;
using ::android::wp;

struct C2BufferQueueBlockPoolData : public _C2BlockPoolData {

    bool held;
    bool local;
    uint64_t bqId;
    int32_t bqSlot;
    sp<HGraphicBufferProducer> igbp;
    std::shared_ptr<C2BufferQueueBlockPool::Impl> localPool;

    virtual type_t getType() const override {
        return TYPE_BUFFERQUEUE;
    }

    // Create a remote BlockPoolData.
    C2BufferQueueBlockPoolData(
            uint64_t bqId, int32_t bqSlot,
            const sp<HGraphicBufferProducer>& producer = nullptr);

    // Create a local BlockPoolData.
    C2BufferQueueBlockPoolData(
            uint64_t bqId, int32_t bqSlot,
            const std::shared_ptr<C2BufferQueueBlockPool::Impl>& pool);

    virtual ~C2BufferQueueBlockPoolData() override;

};

bool _C2BlockFactory::GetBufferQueueData(
        const std::shared_ptr<_C2BlockPoolData>& data,
        uint64_t* bqId, int32_t* bqSlot) {
    if (data && data->getType() == _C2BlockPoolData::TYPE_BUFFERQUEUE) {
        if (bqId) {
            const std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
                    std::static_pointer_cast<C2BufferQueueBlockPoolData>(data);
            *bqId = poolData->bqId;
            if (bqSlot) {
                *bqSlot = poolData->bqSlot;
            }
        }
        return true;
    }
    return false;
}

bool _C2BlockFactory::AssignBlockToBufferQueue(
        const std::shared_ptr<_C2BlockPoolData>& data,
        const sp<HGraphicBufferProducer>& igbp,
        uint64_t bqId,
        int32_t bqSlot,
        bool held) {
    if (data && data->getType() == _C2BlockPoolData::TYPE_BUFFERQUEUE) {
        const std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
                std::static_pointer_cast<C2BufferQueueBlockPoolData>(data);
        poolData->igbp = igbp;
        poolData->bqId = bqId;
        poolData->bqSlot = bqSlot;
        poolData->held = held;
        return true;
    }
    return false;
}

bool _C2BlockFactory::HoldBlockFromBufferQueue(
        const std::shared_ptr<_C2BlockPoolData>& data,
        const sp<HGraphicBufferProducer>& igbp) {
    const std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
            std::static_pointer_cast<C2BufferQueueBlockPoolData>(data);
    if (!poolData->local) {
        poolData->igbp = igbp;
    }
    if (poolData->held) {
        poolData->held = true;
        return false;
    }
    poolData->held = true;
    return true;
}

bool _C2BlockFactory::YieldBlockToBufferQueue(
        const std::shared_ptr<_C2BlockPoolData>& data) {
    const std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
            std::static_pointer_cast<C2BufferQueueBlockPoolData>(data);
    if (!poolData->held) {
        poolData->held = false;
        return false;
    }
    poolData->held = false;
    return true;
}

std::shared_ptr<C2GraphicBlock> _C2BlockFactory::CreateGraphicBlock(
        const C2Handle *handle) {
    // TODO: get proper allocator? and mutex?
    static std::unique_ptr<C2AllocatorGralloc> sAllocator = std::make_unique<C2AllocatorGralloc>(0);

    std::shared_ptr<C2GraphicAllocation> alloc;
    if (C2AllocatorGralloc::isValid(handle)) {
        uint32_t width;
        uint32_t height;
        uint32_t format;
        uint64_t usage;
        uint32_t stride;
        uint64_t igbp_id;
        uint32_t igbp_slot;
        android::_UnwrapNativeCodec2GrallocMetadata(
                handle, &width, &height, &format, &usage, &stride, &igbp_id, &igbp_slot);
        c2_status_t err = sAllocator->priorGraphicAllocation(handle, &alloc);
        if (err == C2_OK) {
            std::shared_ptr<C2GraphicBlock> block;
            if (igbp_id || igbp_slot) {
                // BQBBP
                std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
                        std::make_shared<C2BufferQueueBlockPoolData>(igbp_id, (int32_t)igbp_slot);
                block = _C2BlockFactory::CreateGraphicBlock(alloc, poolData);
            } else {
                block = _C2BlockFactory::CreateGraphicBlock(alloc);
            }
            return block;
        }
    }
    return nullptr;
}

class C2BufferQueueBlockPool::Impl
        : public std::enable_shared_from_this<C2BufferQueueBlockPool::Impl> {
private:
    c2_status_t fetchFromIgbp_l(
            uint32_t width,
            uint32_t height,
            uint32_t format,
            C2MemoryUsage usage,
            std::shared_ptr<C2GraphicBlock> *block /* nonnull */) {
        // We have an IGBP now.
        sp<Fence> fence = new Fence();
        C2AndroidMemoryUsage androidUsage = usage;
        status_t status;
        PixelFormat pixelFormat = static_cast<PixelFormat>(format);
        int slot;
        ALOGV("tries to dequeue buffer");
        mProducer->dequeueBuffer(
                width, height, pixelFormat, androidUsage.asGrallocUsage(), true,
                [&status, &slot, &fence](
                        int32_t tStatus, int32_t tSlot, hidl_handle const& tFence,
                        HGraphicBufferProducer::FrameEventHistoryDelta const& tTs) {
                    status = tStatus;
                    slot = tSlot;
                    if (!android::conversion::convertTo(fence.get(), tFence) &&
                            status == android::NO_ERROR) {
                        status = android::BAD_VALUE;
                    }
                    (void) tTs;
                });
        // dequeueBuffer returns flag.
        if (status < android::OK) {
            ALOGD("cannot dequeue buffer %d", status);
            if (status == android::INVALID_OPERATION) {
              // Too many buffer dequeued. retrying after some time is required.
              return C2_TIMED_OUT;
            } else {
              return C2_BAD_VALUE;
            }
        }
        ALOGV("dequeued a buffer successfully");
        native_handle_t* nh = nullptr;
        hidl_handle fenceHandle;
        if (fence) {
            android::conversion::wrapAs(&fenceHandle, &nh, *fence);
        }
        if (fence) {
            static constexpr int kFenceWaitTimeMs = 10;

            status_t status = fence->wait(kFenceWaitTimeMs);
            if (status == -ETIME) {
                // fence is not signalled yet.
                mProducer->cancelBuffer(slot, fenceHandle);
                return C2_TIMED_OUT;
            }
            if (status != android::NO_ERROR) {
                ALOGD("buffer fence wait error %d", status);
                mProducer->cancelBuffer(slot, fenceHandle);
                return C2_BAD_VALUE;
            } else if (mRenderCallback) {
                nsecs_t signalTime = fence->getSignalTime();
                if (signalTime >= 0 && signalTime < INT64_MAX) {
                    mRenderCallback(mProducerId, slot, signalTime);
                } else {
                    ALOGV("got fence signal time of %lld", (long long)signalTime);
                }
            }
        }

        sp<GraphicBuffer> &slotBuffer = mBuffers[slot];
        if (status & IGraphicBufferProducer::BUFFER_NEEDS_REALLOCATION || !slotBuffer) {
            if (!slotBuffer) {
                slotBuffer = new GraphicBuffer();
            }
            // N.B. This assumes requestBuffer# returns an existing allocation
            // instead of a new allocation.
            mProducer->requestBuffer(
                    slot,
                    [&status, &slotBuffer](int32_t tStatus, AnwBuffer const& tBuffer){
                        status = tStatus;
                        if (!android::conversion::convertTo(slotBuffer.get(), tBuffer) &&
                                status == android::NO_ERROR) {
                            status = android::BAD_VALUE;
                        }
                    });

            if (status != android::NO_ERROR) {
                mBuffers[slot].clear();
                mProducer->cancelBuffer(slot, fenceHandle);
                return C2_BAD_VALUE;
            }
        }
        if (mBuffers[slot]) {
            native_handle_t *grallocHandle = native_handle_clone(mBuffers[slot]->handle);

            if (grallocHandle) {
                ALOGV("buffer wraps %llu %d", (unsigned long long)mProducerId, slot);
                C2Handle *c2Handle = android::WrapNativeCodec2GrallocHandle(
                        grallocHandle,
                        mBuffers[slot]->width,
                        mBuffers[slot]->height,
                        mBuffers[slot]->format,
                        mBuffers[slot]->usage,
                        mBuffers[slot]->stride,
                        mProducerId, slot);
                if (c2Handle) {
                    // Moved everything to c2Handle.
                    native_handle_delete(grallocHandle);
                    std::shared_ptr<C2GraphicAllocation> alloc;
                    c2_status_t err = mAllocator->priorGraphicAllocation(c2Handle, &alloc);
                    if (err != C2_OK) {
                        return err;
                    }
                    std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
                            std::make_shared<C2BufferQueueBlockPoolData>(
                                    mProducerId, slot, shared_from_this());
                    *block = _C2BlockFactory::CreateGraphicBlock(alloc, poolData);
                    return C2_OK;
                }
                native_handle_close(grallocHandle);
                native_handle_delete(grallocHandle);
            }
            // Block was not created. call requestBuffer# again next time.
            mBuffers[slot].clear();
            mProducer->cancelBuffer(slot, fenceHandle);
        }
        return C2_BAD_VALUE;
    }

public:
    Impl(const std::shared_ptr<C2Allocator> &allocator)
        : mInit(C2_OK), mProducerId(0), mAllocator(allocator) {
    }

    ~Impl() {
        std::lock_guard<std::mutex> lock(mMutex);
        bool noInit = false;
        for (int i = 0; i < NUM_BUFFER_SLOTS; ++i) {
            if (!noInit && mProducer) {
                noInit = mProducer->detachBuffer(i) == android::NO_INIT;
            }
            mBuffers[i].clear();
        }
    }

    c2_status_t fetchGraphicBlock(
            uint32_t width,
            uint32_t height,
            uint32_t format,
            C2MemoryUsage usage,
            std::shared_ptr<C2GraphicBlock> *block /* nonnull */) {
        block->reset();
        if (mInit != C2_OK) {
            return mInit;
        }

        static int kMaxIgbpRetry = 20; // TODO: small number can cause crash in releasing.
        static int kMaxIgbpRetryDelayUs = 10000;

        int curTry = 0;

        while (curTry++ < kMaxIgbpRetry) {
            std::unique_lock<std::mutex> lock(mMutex);
            // TODO: return C2_NO_INIT
            if (mProducerId == 0) {
                std::shared_ptr<C2GraphicAllocation> alloc;
                c2_status_t err = mAllocator->newGraphicAllocation(
                        width, height, format, usage, &alloc);
                if (err != C2_OK) {
                    return err;
                }
                std::shared_ptr<C2BufferQueueBlockPoolData> poolData =
                        std::make_shared<C2BufferQueueBlockPoolData>(
                                (uint64_t)0, ~0, shared_from_this());
                // TODO: config?
                *block = _C2BlockFactory::CreateGraphicBlock(alloc, poolData);
                ALOGV("allocated a buffer successfully");

                return C2_OK;
            }
            c2_status_t status = fetchFromIgbp_l(width, height, format, usage, block);
            if (status == C2_TIMED_OUT) {
                lock.unlock();
                ::usleep(kMaxIgbpRetryDelayUs);
                continue;
            }
            return status;
        }
        return C2_TIMED_OUT;
    }

    void setRenderCallback(const OnRenderCallback &renderCallback) {
        std::lock_guard<std::mutex> lock(mMutex);
        mRenderCallback = renderCallback;
    }

    void configureProducer(const sp<HGraphicBufferProducer> &producer) {
        int32_t status = android::OK;
        uint64_t producerId = 0;
        if (producer) {
            producer->getUniqueId(
                    [&status, &producerId](int32_t tStatus, int64_t tProducerId) {
                status = tStatus;
                producerId = tProducerId;
                    });
        }
        {
            std::lock_guard<std::mutex> lock(mMutex);
            if (status == android::OK && producerId == mProducerId) {
                // producer is not changed.
                return;
            }
            bool noInit = false;
            for (int i = 0; i < NUM_BUFFER_SLOTS; ++i) {
                if (!noInit && mProducer) {
                    noInit = mProducer->detachBuffer(i) == android::NO_INIT;
                }
                mBuffers[i].clear();
            }
            if (producer && status == android::OK) {
                mProducer = producer;
                mProducerId = producerId;
            } else {
                mProducer = nullptr;
                mProducerId = 0;
            }
        }
    }

private:
    friend struct C2BufferQueueBlockPoolData;

    void cancel(uint64_t igbp_id, int32_t igbp_slot) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (igbp_id == mProducerId && mProducer) {
            mProducer->cancelBuffer(igbp_slot, nullptr);
        }
    }

    c2_status_t mInit;
    uint64_t mProducerId;
    OnRenderCallback mRenderCallback;

    const std::shared_ptr<C2Allocator> mAllocator;

    std::mutex mMutex;
    sp<HGraphicBufferProducer> mProducer;

    sp<GraphicBuffer> mBuffers[NUM_BUFFER_SLOTS];
};

C2BufferQueueBlockPoolData::C2BufferQueueBlockPoolData(
        uint64_t bqId, int32_t bqSlot,
        const sp<HGraphicBufferProducer>& producer) :
        held(producer && bqId != 0), local(false),
        bqId(bqId), bqSlot(bqSlot),
        igbp(producer),
        localPool() {
}

C2BufferQueueBlockPoolData::C2BufferQueueBlockPoolData(
        uint64_t bqId, int32_t bqSlot,
        const std::shared_ptr<C2BufferQueueBlockPool::Impl>& pool) :
        held(true), local(true),
        bqId(bqId), bqSlot(bqSlot),
        igbp(pool ? pool->mProducer : nullptr),
        localPool(pool) {
}

C2BufferQueueBlockPoolData::~C2BufferQueueBlockPoolData() {
    if (!held || bqId == 0) {
        return;
    }
    if (local && localPool) {
        localPool->cancel(bqId, bqSlot);
    } else if (igbp) {
        igbp->cancelBuffer(bqSlot, nullptr);
    }
}

C2BufferQueueBlockPool::C2BufferQueueBlockPool(
        const std::shared_ptr<C2Allocator> &allocator, const local_id_t localId)
        : mAllocator(allocator), mLocalId(localId), mImpl(new Impl(allocator)) {}

C2BufferQueueBlockPool::~C2BufferQueueBlockPool() {}

c2_status_t C2BufferQueueBlockPool::fetchGraphicBlock(
        uint32_t width,
        uint32_t height,
        uint32_t format,
        C2MemoryUsage usage,
        std::shared_ptr<C2GraphicBlock> *block /* nonnull */) {
    if (mImpl) {
        return mImpl->fetchGraphicBlock(width, height, format, usage, block);
    }
    return C2_CORRUPTED;
}

void C2BufferQueueBlockPool::configureProducer(const sp<HGraphicBufferProducer> &producer) {
    if (mImpl) {
        mImpl->configureProducer(producer);
    }
}

void C2BufferQueueBlockPool::setRenderCallback(const OnRenderCallback &renderCallback) {
    if (mImpl) {
        mImpl->setRenderCallback(renderCallback);
    }
}
