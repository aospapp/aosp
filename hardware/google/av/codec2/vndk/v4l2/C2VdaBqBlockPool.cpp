/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "C2VdaBqBlockPool"

#include <errno.h>

#include <mutex>

#include <gui/BufferQueueDefs.h>
#include <utils/Log.h>

#include <C2AllocatorGralloc.h>
#include <C2BlockInternal.h>
#include <v4l2/C2VdaBqBlockPool.h>

using ::android::AnwBuffer;
using ::android::C2AndroidMemoryUsage;
using ::android::Fence;
using ::android::GraphicBuffer;
using ::android::HGraphicBufferProducer;
using ::android::hidl_handle;
using ::android::IGraphicBufferProducer;
using ::android::sp;
using ::android::status_t;
using ::android::hardware::graphics::common::V1_0::PixelFormat;

namespace {

// The wait time for acquire fence in milliseconds.
const int kFenceWaitTimeMs = 10;
// The timeout delay for dequeuing buffer from producer in nanoseconds.
const int64_t kDequeueTimeoutNs = 10 * 1000 * 1000;

}  // namespace

static c2_status_t asC2Error(int32_t err) {
    switch (err) {
    case android::NO_ERROR:
        return C2_OK;
    case android::NO_INIT:
        return C2_NO_INIT;
    case android::BAD_VALUE:
        return C2_BAD_VALUE;
    case android::TIMED_OUT:
        return C2_TIMED_OUT;
    case android::WOULD_BLOCK:
        return C2_BLOCKING;
    case android::NO_MEMORY:
        return C2_NO_MEMORY;
    case -ETIME:
        return C2_TIMED_OUT;  // for fence wait
    }
    return C2_CORRUPTED;
}

C2VdaBqBlockPool::C2VdaBqBlockPool(const std::shared_ptr<C2Allocator>& allocator,
                                   const local_id_t localId)
      : C2BufferQueueBlockPool(allocator, localId),
        mAllocator(allocator),
        mLocalId(localId),
        mMaxDequeuedBuffers(0u) {}

C2VdaBqBlockPool::~C2VdaBqBlockPool() {
    std::lock_guard<std::mutex> lock(mMutex);
    cancelAllBuffers();
}

c2_status_t C2VdaBqBlockPool::fetchGraphicBlock(
        uint32_t width, uint32_t height, uint32_t format, C2MemoryUsage usage,
        std::shared_ptr<C2GraphicBlock>* block /* nonnull */) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (!mProducer) {
        // Producer will not be configured in byte-buffer mode. Allocate buffers from allocator
        // directly as a basic graphic block pool.
        std::shared_ptr<C2GraphicAllocation> alloc;
        c2_status_t err = mAllocator->newGraphicAllocation(width, height, format, usage, &alloc);
        if (err != C2_OK) {
            return err;
        }
        *block = _C2BlockFactory::CreateGraphicBlock(alloc);
        return C2_OK;
    }

    sp<Fence> fence = new Fence();
    C2AndroidMemoryUsage androidUsage = usage;
    int32_t status;
    PixelFormat pixelFormat = static_cast<PixelFormat>(format);
    int32_t slot;

    mProducer->dequeueBuffer(
            width, height, pixelFormat, androidUsage.asGrallocUsage(), true,
            [&status, &slot, &fence](int32_t tStatus, int32_t tSlot, hidl_handle const& tFence,
                                     HGraphicBufferProducer::FrameEventHistoryDelta const& tTs) {
                status = tStatus;
                slot = tSlot;
                if (!android::conversion::convertTo(fence.get(), tFence) &&
                    status == android::NO_ERROR) {
                    status = android::BAD_VALUE;
                }
                (void)tTs;
            });

    // check dequeueBuffer return flag
    if (status != android::NO_ERROR &&
        status != IGraphicBufferProducer::BUFFER_NEEDS_REALLOCATION) {
        if (status == android::TIMED_OUT) {
            // no buffer is available now, wait for another retry.
            ALOGV("dequeueBuffer timed out, wait for retry...");
            return C2_TIMED_OUT;
        }
        ALOGE("dequeueBuffer failed: %d", status);
        return asC2Error(status);
    }

    // wait for acquire fence if we get one.
    native_handle_t* nh = nullptr;
    hidl_handle fenceHandle;
    if (fence) {
        android::conversion::wrapAs(&fenceHandle, &nh, *fence);
        status_t fenceStatus = fence->wait(kFenceWaitTimeMs);
        if (fenceStatus != android::NO_ERROR) {
            ALOGE("buffer fence wait error: %d", fenceStatus);
            mProducer->cancelBuffer(slot, fenceHandle);
            native_handle_delete(nh);
            return asC2Error(fenceStatus);
        }
    }

    auto iter = mSlotAllocations.find(slot);
    if (iter == mSlotAllocations.end()) {
        // it's a new slot index, request for a new buffer.
        if (mSlotAllocations.size() >= mMaxDequeuedBuffers) {
            ALOGE("still get a new slot index but already allocated enough buffers.");
            mProducer->cancelBuffer(slot, fenceHandle);
            native_handle_delete(nh);
            return C2_CORRUPTED;
        }
        if (status != IGraphicBufferProducer::BUFFER_NEEDS_REALLOCATION) {
            ALOGE("expect BUFFER_NEEDS_REALLOCATION flag but didn't get one.");
            mProducer->cancelBuffer(slot, fenceHandle);
            native_handle_delete(nh);
            return C2_CORRUPTED;
        }
        sp<GraphicBuffer> slotBuffer = new GraphicBuffer();
        mProducer->requestBuffer(
                slot, [&status, &slotBuffer](int32_t tStatus, AnwBuffer const& tBuffer) {
                    status = tStatus;
                    if (!android::conversion::convertTo(slotBuffer.get(), tBuffer) &&
                        status == android::NO_ERROR) {
                        status = android::BAD_VALUE;
                    }
                });

        // check requestBuffer return flag
        if (status != android::NO_ERROR) {
            ALOGE("requestBuffer failed: %d", status);
            mProducer->cancelBuffer(slot, fenceHandle);
            native_handle_delete(nh);
            return asC2Error(status);
        }
        native_handle_delete(nh);

        // convert GraphicBuffer to C2GraphicAllocation and wrap producer id and slot index
        native_handle_t* grallocHandle = native_handle_clone(slotBuffer->handle);
        ALOGV("buffer wraps { producer id: %" PRIu64 ", slot: %d }", mProducerId, slot);
        C2Handle* c2Handle = android::WrapNativeCodec2GrallocHandle(
                grallocHandle, slotBuffer->width, slotBuffer->height, slotBuffer->format,
                slotBuffer->usage, slotBuffer->stride, mProducerId, slot);
        native_handle_delete(grallocHandle);
        if (!c2Handle) {
            ALOGE("WrapNativeCodec2GrallocHandle failed");
            return C2_NO_MEMORY;
        }

        std::shared_ptr<C2GraphicAllocation> alloc;
        c2_status_t err = mAllocator->priorGraphicAllocation(c2Handle, &alloc);
        if (err != C2_OK) {
            ALOGE("priorGraphicAllocation failed: %d", err);
            return err;
        }

        mSlotAllocations[slot] = std::move(alloc);
        if (mSlotAllocations.size() == mMaxDequeuedBuffers) {
            // already allocated enough buffers, set allowAllocation to false to restrict the
            // eligible slots to allocated ones for future dequeue.
            status = mProducer->allowAllocation(false);
            if (status != android::NO_ERROR) {
                ALOGE("allowAllocation(false) failed");
                return asC2Error(status);
            }
        }
    } else if (mSlotAllocations.size() < mMaxDequeuedBuffers) {
        ALOGE("failed to allocate enough buffers");
        return C2_BAD_STATE;
    }

    *block = _C2BlockFactory::CreateGraphicBlock(mSlotAllocations[slot]);
    return C2_OK;
}

c2_status_t C2VdaBqBlockPool::requestNewBufferSet(int32_t bufferCount) {
    if (bufferCount <= 0) {
        ALOGE("Invalid requested buffer count = %d", bufferCount);
        return C2_BAD_VALUE;
    }

    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProducer) {
        ALOGD("No HGraphicBufferProducer is configured...");
        return C2_NO_INIT;
    }

    // For dynamic resolution change, cancel all mapping buffers and discard references.
    // Client needs to make sure all buffers are dequeued and owned by client before calling.
    auto cancelStatus = cancelAllBuffers();
    if (cancelStatus != C2_OK) {
        ALOGE("cancelBuffer failed while requesting new buffer set...");
        return C2_CORRUPTED;
    }

    // TODO: should we query(NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS) and add it on?
    int32_t status = mProducer->setMaxDequeuedBufferCount(bufferCount);
    if (status != android::NO_ERROR) {
        ALOGE("setMaxDequeuedBufferCount failed");
        return asC2Error(status);
    }
    mMaxDequeuedBuffers = static_cast<size_t>(bufferCount);

    status = mProducer->allowAllocation(true);
    if (status != android::NO_ERROR) {
        ALOGE("allowAllocation(true) failed");
        return asC2Error(status);
    }
    return C2_OK;
}

void C2VdaBqBlockPool::configureProducer(const sp<HGraphicBufferProducer>& producer) {
    ALOGV("configureProducer");
    std::lock_guard<std::mutex> lock(mMutex);
    // TODO: handle producer change request (client changes surface) while codec is running.
    if (mProducer) {
        ALOGE("Not allowed to reset HGraphicBufferProducer");
        return;
    }

    mProducer = producer;
    if (producer) {
        int32_t status;
        producer->getUniqueId(
                [&status, &producerId = mProducerId](int32_t tStatus, int64_t tProducerId) {
                    status = tStatus;
                    producerId = tProducerId;
                });
        if (status != android::NO_ERROR) {
            ALOGE("getUniqueId failed");
            mProducer = nullptr;
            mProducerId = 0;
            return;
        }
        status = producer->setDequeueTimeout(kDequeueTimeoutNs);
        if (status != android::NO_ERROR) {
            ALOGE("setDequeueTimeout failed");
            mProducer = nullptr;
            mProducerId = 0;
            return;
        }
    } else {
        mProducerId = 0;
    }
    mSlotAllocations.clear();
}

c2_status_t C2VdaBqBlockPool::cancelAllBuffers() {
    int32_t lastError = android::NO_ERROR;
    for (const auto& elem : mSlotAllocations) {
        int32_t slot = elem.first;
        sp<Fence> fence(Fence::NO_FENCE);
        native_handle_t* nh = nullptr;
        hidl_handle fenceHandle;
        android::conversion::wrapAs(&fenceHandle, &nh, *fence);
        int32_t status = mProducer->cancelBuffer(slot, fenceHandle);
        native_handle_delete(nh);
        if (status == android::NO_INIT) {
            // Producer may be disconnected during the loop of cancelBuffer (especially in the
            // destruction state). Break the loop and return immediately.
            mSlotAllocations.clear();
            return C2_NO_INIT;
        }
        if (status != android::NO_ERROR) {
            ALOGE("cancelBuffer failed at slot = %d, err: %d", slot, status);
            lastError = status;
        }
    }
    mSlotAllocations.clear();
    return asC2Error(lastError);
}
