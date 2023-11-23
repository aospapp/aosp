// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

//#define LOG_NDEBUG 0
#define LOG_TAG "C2VdaBqBlockPool"

#include <v4l2_codec2/plugin_store/C2VdaBqBlockPool.h>

#include <errno.h>

#include <chrono>
#include <mutex>

#include <C2AllocatorGralloc.h>
#include <C2BlockInternal.h>
#include <log/log.h>
#include <system/window.h>
#include <types.h>
#include <ui/BufferQueueDefs.h>

#include <v4l2_codec2/plugin_store/V4L2AllocatorId.h>

using ::android::C2AndroidMemoryUsage;
using ::android::Fence;
using ::android::GraphicBuffer;
using ::android::sp;
using ::android::status_t;
using ::android::BufferQueueDefs::BUFFER_NEEDS_REALLOCATION;
using ::android::BufferQueueDefs::NUM_BUFFER_SLOTS;
using ::android::hardware::hidl_handle;
using ::android::hardware::Return;

using HBuffer = ::android::hardware::graphics::common::V1_2::HardwareBuffer;
using HStatus = ::android::hardware::graphics::bufferqueue::V2_0::Status;
using ::android::hardware::graphics::bufferqueue::V2_0::utils::b2h;
using ::android::hardware::graphics::bufferqueue::V2_0::utils::h2b;
using ::android::hardware::graphics::bufferqueue::V2_0::utils::HFenceWrapper;

namespace {

// The wait time for acquire fence in milliseconds.
constexpr int kFenceWaitTimeMs = 10;
// The timeout delay range for dequeuing spare buffer delay time in microseconds.
constexpr int kDequeueSpareMinDelayUs = 500;
constexpr int kDequeueSpareMaxDelayUs = 16 * 1000;
// The timeout limit of acquiring lock of timed_mutex in milliseconds.
constexpr std::chrono::milliseconds kTimedMutexTimeoutMs = std::chrono::milliseconds(500);
// The max retry times for fetchSpareBufferSlot timeout.
constexpr int32_t kFetchSpareBufferMaxRetries = 10;

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
    }
    return C2_CORRUPTED;
}

/**
 * BlockPoolData implementation for C2VdaBqBlockPool. The life cycle of this object should be as
 * long as its accompanied C2GraphicBlock.
 *
 * When C2VdaBqBlockPoolData is created, |mShared| is false, and the owner of the accompanied
 * C2GraphicBlock is the component that called fetchGraphicBlock(). If this is released before
 * sharing, the destructor will call detachBuffer() to BufferQueue to free the slot.
 *
 * When the accompanied C2GraphicBlock is going to share to client from component, component should
 * call MarkBlockPoolDataAsShared() to set |mShared| to true, and then this will be released after
 * the transition of C2GraphicBlock across HIDL interface. At this time, the destructor will not
 * call detachBuffer().
 */
struct C2VdaBqBlockPoolData : public _C2BlockPoolData {
    // This type should be a different value than what _C2BlockPoolData::type_t has defined.
    static constexpr int kTypeVdaBufferQueue = TYPE_BUFFERQUEUE + 256;

    C2VdaBqBlockPoolData(uint64_t producerId, int32_t slotId,
                         const std::shared_ptr<C2VdaBqBlockPool::Impl>& pool);
    C2VdaBqBlockPoolData() = delete;

    // If |mShared| is false, call detach buffer to BufferQueue via |mPool|
    virtual ~C2VdaBqBlockPoolData() override;

    type_t getType() const override { return static_cast<type_t>(kTypeVdaBufferQueue); }

    bool mShared = false;  // whether is shared from component to client.
    const uint64_t mProducerId;
    const int32_t mSlotId;
    const std::shared_ptr<C2VdaBqBlockPool::Impl> mPool;
};

c2_status_t MarkBlockPoolDataAsShared(const C2ConstGraphicBlock& sharedBlock) {
    std::shared_ptr<_C2BlockPoolData> data = _C2BlockFactory::GetGraphicBlockPoolData(sharedBlock);
    if (!data || data->getType() != C2VdaBqBlockPoolData::kTypeVdaBufferQueue) {
        // Skip this functtion if |sharedBlock| is not fetched from C2VdaBqBlockPool.
        return C2_OMITTED;
    }
    const std::shared_ptr<C2VdaBqBlockPoolData> poolData =
            std::static_pointer_cast<C2VdaBqBlockPoolData>(data);
    if (poolData->mShared) {
        ALOGE("C2VdaBqBlockPoolData(id=%" PRIu64 ", slot=%d) is already marked as shared...",
              poolData->mProducerId, poolData->mSlotId);
        return C2_BAD_STATE;
    }
    poolData->mShared = true;
    return C2_OK;
}

// static
c2_status_t C2VdaBqBlockPool::getPoolIdFromGraphicBlock(
        const std::shared_ptr<C2GraphicBlock>& block, uint32_t* poolId) {
    uint32_t width, height, format, stride, igbp_slot, generation;
    uint64_t usage, igbp_id;
    android::_UnwrapNativeCodec2GrallocMetadata(block->handle(), &width, &height, &format, &usage,
                                                &stride, &generation, &igbp_id, &igbp_slot);
    ALOGV("Unwrap Metadata: igbp[%" PRIu64 ", %u] (%u*%u, fmt %#x, usage %" PRIx64 ", stride %u)",
          igbp_id, igbp_slot, width, height, format, usage, stride);
    *poolId = igbp_slot;
    return C2_OK;
}

class C2VdaBqBlockPool::Impl : public std::enable_shared_from_this<C2VdaBqBlockPool::Impl> {
public:
    using HGraphicBufferProducer = C2VdaBqBlockPool::HGraphicBufferProducer;

    explicit Impl(const std::shared_ptr<C2Allocator>& allocator);
    // TODO: should we detach buffers on producer if any on destructor?
    ~Impl() = default;

    c2_status_t fetchGraphicBlock(uint32_t width, uint32_t height, uint32_t format,
                                  C2MemoryUsage usage,
                                  std::shared_ptr<C2GraphicBlock>* block /* nonnull */);
    void setRenderCallback(const C2BufferQueueBlockPool::OnRenderCallback& renderCallback);
    void configureProducer(const sp<HGraphicBufferProducer>& producer);
    c2_status_t requestNewBufferSet(int32_t bufferCount);
    c2_status_t updateGraphicBlock(bool willCancel, uint32_t oldSlot, uint32_t* newSlot,
                                   std::shared_ptr<C2GraphicBlock>* block /* nonnull */);
    c2_status_t getMinBuffersForDisplay(size_t* bufferCount);

private:
    friend struct C2VdaBqBlockPoolData;

    // The exponential rate control calculator with factor of 2. Per increase() call will double the
    // value until it reaches maximum. reset() will set value to the minimum.
    class ExpRateControlCalculator {
    public:
        ExpRateControlCalculator(int min, int max) : kMinValue(min), kMaxValue(max), mValue(min) {}
        ExpRateControlCalculator() = delete;

        void reset() { mValue = kMinValue; }
        void increase() { mValue = std::min(kMaxValue, mValue << 1); }
        int value() const { return mValue; }

    private:
        const int kMinValue;
        const int kMaxValue;
        int mValue;
    };

    // Requested buffer formats.
    struct BufferFormat {
        BufferFormat(uint32_t width, uint32_t height, uint32_t pixelFormat,
                     C2AndroidMemoryUsage androidUsage)
              : mWidth(width), mHeight(height), mPixelFormat(pixelFormat), mUsage(androidUsage) {}
        BufferFormat() = default;

        uint32_t mWidth = 0;
        uint32_t mHeight = 0;
        uint32_t mPixelFormat = 0;
        C2AndroidMemoryUsage mUsage = C2MemoryUsage(0);
    };

    // For C2VdaBqBlockPoolData to detach corresponding slot buffer from BufferQueue.
    void detachBuffer(uint64_t producerId, int32_t slotId);

    // Fetches a spare slot index by dequeueing and requesting one extra buffer from producer. The
    // spare buffer slot guarantees at least one buffer to be dequeued in producer, so as to prevent
    // the invalid operation for producer of the attempt to dequeue buffers exceeded the maximal
    // dequeued buffer count.
    // This function should be called after the last requested buffer is fetched in
    // fetchGraphicBlock(), or in the beginning of switchProducer(). Block pool should store the
    // slot index into |mSpareSlot| and cancel the buffer immediately.
    // The generation number and usage of the spare buffer will be recorded in |generation| and
    // |usage|, which will be useful later in switchProducer().
    c2_status_t fetchSpareBufferSlot(HGraphicBufferProducer* const producer, uint32_t width,
                                     uint32_t height, uint32_t pixelFormat,
                                     C2AndroidMemoryUsage androidUsage, uint32_t* generation,
                                     uint64_t* usage);

    // Helper function to call dequeue buffer to producer.
    c2_status_t dequeueBuffer(HGraphicBufferProducer* const producer, uint32_t width,
                              uint32_t height, uint32_t pixelFormat,
                              C2AndroidMemoryUsage androidUsage, int32_t& status, int32_t& slot,
                              sp<Fence>& fence);

    // Switches producer and transfers allocated buffers from old producer to the new one.
    bool switchProducer(HGraphicBufferProducer* const newProducer, uint64_t newProducerId);

    const std::shared_ptr<C2Allocator> mAllocator;

    sp<HGraphicBufferProducer> mProducer;
    uint64_t mProducerId;
    C2BufferQueueBlockPool::OnRenderCallback mRenderCallback;

    // Function mutex to lock at the start of each API function call for protecting the
    // synchronization of all member variables.
    std::mutex mMutex;
    // The mutex of excluding the procedures of configuring producer and allocating buffers. They
    // should be blocked mutually. Set the timeout for acquiring lock in case of any deadlock.
    // Configuring producer: configureProducer() called by CCodec.
    // Allocating buffers: requestNewBufferSet(), then a loop of fetchGraphicBlock() called by
    //                     compoenent until |mSlotAllocations|.size() equals |mBuffersRequested|.
    std::timed_mutex mConfigureProducerAndAllocateBuffersMutex;
    // The unique lock of the procedure of allocating buffers. It should be locked in the beginning
    // of requestNewBufferSet() and unlock in the end of the loop of fetchGraphicBlock(). Note that
    // all calls should be in the same thread.
    std::unique_lock<std::timed_mutex> mAllocateBuffersLock;

    // The map restored C2GraphicAllocation from corresponding slot index.
    std::map<int32_t, std::shared_ptr<C2GraphicAllocation>> mSlotAllocations;
    // Number of buffers requested on requestNewBufferSet() call.
    size_t mBuffersRequested;
    // The slot index of spare buffer.
    int32_t mSpareSlot;
    // Currently requested buffer formats.
    BufferFormat mBufferFormat;
    // The map recorded the slot indices from old producer to new producer.
    std::map<int32_t, int32_t> mProducerChangeSlotMap;
    // The rate control calculator for the delay of dequeueing spare buffer.
    ExpRateControlCalculator mSpareDequeueDelayUs;
    // The counter for representing the buffer count in client. Only used in producer switching
    // case. It will be reset in switchProducer(), and accumulated in updateGraphicBlock() routine.
    uint32_t mBuffersInClient = 0u;
    // The indicator to record if producer has been switched. Set to true when producer is switched.
    // Toggle off when requestNewBufferSet() is called. We forcedly detach all slots to make sure
    // all slots are available, except the ones owned by client.
    bool mProducerSwitched = false;
};

C2VdaBqBlockPool::Impl::Impl(const std::shared_ptr<C2Allocator>& allocator)
      : mAllocator(allocator),
        mAllocateBuffersLock(mConfigureProducerAndAllocateBuffersMutex, std::defer_lock),
        mBuffersRequested(0u),
        mSpareSlot(-1),
        mSpareDequeueDelayUs(kDequeueSpareMinDelayUs, kDequeueSpareMaxDelayUs) {}

c2_status_t C2VdaBqBlockPool::Impl::fetchGraphicBlock(
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

    // The existence of |mProducerChangeSlotMap| indicates producer is just switched. Use return
    // code C2_BAD_STATE to inform the component to handle the procedure of producer change.
    // TODO(johnylin): consider to inform producer change to component in an active way.
    if (!mProducerChangeSlotMap.empty()) {
        return C2_BAD_STATE;
    }

    sp<Fence> fence = new Fence();
    C2AndroidMemoryUsage androidUsage = usage;
    int32_t status;
    uint32_t pixelFormat = format;
    int32_t slot;

    c2_status_t err = dequeueBuffer(mProducer.get(), width, height, pixelFormat, androidUsage,
                                    status, slot, fence);
    if (err != C2_OK) {
        return err;
    }

    // Wait for acquire fence if we get one.
    HFenceWrapper hFenceWrapper{};
    if (!b2h(fence, &hFenceWrapper)) {
        ALOGE("Invalid fence received from dequeueBuffer.");
        return C2_BAD_VALUE;
    }
    if (fence) {
        status_t fenceStatus = fence->wait(kFenceWaitTimeMs);
        if (fenceStatus != android::NO_ERROR) {
            Return<HStatus> cancelTransStatus =
                    mProducer->cancelBuffer(slot, hFenceWrapper.getHandle());
            if (!cancelTransStatus.isOk()) {
                ALOGE("cancelBuffer transaction error: %s",
                      cancelTransStatus.description().c_str());
                return C2_CORRUPTED;
            }
            if (fenceStatus == -ETIME) {  // fence wait timed out
                ALOGV("buffer fence wait timed out, wait for retry...");
                return C2_TIMED_OUT;
            }
            ALOGE("buffer fence wait error: %d", fenceStatus);
            return asC2Error(fenceStatus);
        }
        if (mRenderCallback) {
            nsecs_t signalTime = fence->getSignalTime();
            if (signalTime >= 0 && signalTime < INT64_MAX) {
                mRenderCallback(mProducerId, slot, signalTime);
            } else {
                ALOGV("got fence signal time of %" PRId64 " nsec", signalTime);
            }
        }
    }

    auto iter = mSlotAllocations.find(slot);
    if (iter == mSlotAllocations.end()) {
        if (slot == mSpareSlot) {
            // The dequeued slot is the spare buffer, we don't use this buffer for decoding and must
            // cancel it after the delay time. Other working buffers may be available and pushed to
            // free buffer queue in producer during the delay.
            ALOGV("dequeued spare slot, cancel it after a wait time delay (%d)...",
                  mSpareDequeueDelayUs.value());
            ::usleep(mSpareDequeueDelayUs.value());  // wait for retry
            // Double the delay time if spare buffer still be dequeued the next time. This could
            // prevent block pool keeps aggressively dequeueing spare buffer while other buffers are
            // not available yet.
            mSpareDequeueDelayUs.increase();
            Return<HStatus> cancelTransStatus =
                    mProducer->cancelBuffer(slot, hFenceWrapper.getHandle());
            if (!cancelTransStatus.isOk()) {
                ALOGE("cancelBuffer transaction error: %s",
                      cancelTransStatus.description().c_str());
                return C2_CORRUPTED;
            }
            return C2_TIMED_OUT;
        }
        if (mSlotAllocations.size() >= mBuffersRequested) {
            // The dequeued slot has a pre-allocated buffer whose size and format is as same as
            // currently requested (but was not dequeued during allocation cycle). Just detach it to
            // free this slot. And try dequeueBuffer again.
            ALOGD("dequeued a new slot index but already allocated enough buffers. Detach it.");
            Return<HStatus> detachTransStatus = mProducer->detachBuffer(slot);
            if (!detachTransStatus.isOk()) {
                ALOGE("detachBuffer transaction error: %s",
                      detachTransStatus.description().c_str());
                return C2_CORRUPTED;
            }
            return C2_TIMED_OUT;
        }
        if (status != BUFFER_NEEDS_REALLOCATION) {
            // The dequeued slot has a pre-allocated buffer whose size and format is as same as
            // currently requested, so there is no BUFFER_NEEDS_REALLOCATION flag. However since the
            // buffer reference is already dropped, still call requestBuffer to re-allocate then.
            // Add a debug note here for tracking.
            ALOGD("dequeued a new slot index without BUFFER_NEEDS_REALLOCATION flag.");
        }

        // Call requestBuffer to allocate buffer for the slot and obtain the reference.
        sp<GraphicBuffer> slotBuffer = new GraphicBuffer();
        uint32_t generation;
        Return<void> transStatus = mProducer->requestBuffer(
                slot, [&status, &slotBuffer, &generation](HStatus hStatus, HBuffer const& hBuffer,
                                                          uint32_t generationNumber) {
                    if (h2b(hStatus, &status) && h2b(hBuffer, &slotBuffer) && slotBuffer) {
                        generation = generationNumber;
                        slotBuffer->setGenerationNumber(generationNumber);
                    } else {
                        status = android::BAD_VALUE;
                    }
                });

        // Check requestBuffer transaction status
        if (!transStatus.isOk()) {
            ALOGE("requestBuffer transaction error: %s", transStatus.description().c_str());
            return C2_CORRUPTED;
        }
        // Check requestBuffer return flag
        if (status != android::NO_ERROR) {
            ALOGE("requestBuffer failed: %d", status);
            Return<HStatus> cancelTransStatus =
                    mProducer->cancelBuffer(slot, hFenceWrapper.getHandle());
            if (!cancelTransStatus.isOk()) {
                ALOGE("cancelBuffer transaction error: %s",
                      cancelTransStatus.description().c_str());
                return C2_CORRUPTED;
            }
            return asC2Error(status);
        }

        // Convert GraphicBuffer to C2GraphicAllocation and wrap producer id and slot index
        ALOGV("buffer wraps { producer id: %" PRIu64 ", slot: %d }", mProducerId, slot);
        C2Handle* c2Handle = android::WrapNativeCodec2GrallocHandle(
                slotBuffer->handle, slotBuffer->width, slotBuffer->height, slotBuffer->format,
                slotBuffer->usage, slotBuffer->stride, slotBuffer->getGenerationNumber(),
                mProducerId, slot);
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
        if (mSlotAllocations.size() == mBuffersRequested) {
            // Allocate one spare buffer after allocating enough buffers requested by client.
            uint32_t generation;
            uint64_t usage;

            err = C2_TIMED_OUT;
            for (int32_t retriesLeft = kFetchSpareBufferMaxRetries;
                 err == C2_TIMED_OUT && retriesLeft >= 0; retriesLeft--) {
                err = fetchSpareBufferSlot(mProducer.get(), width, height, pixelFormat,
                                           androidUsage, &generation, &usage);
            }
            if (err != C2_OK) {
                ALOGE("fetchSpareBufferSlot failed after %d retries: %d",
                      kFetchSpareBufferMaxRetries, err);
                return err;
            }

            // Already allocated enough buffers, set allowAllocation to false to restrict the
            // eligible slots to allocated ones for future dequeue.
            Return<HStatus> transStatus = mProducer->allowAllocation(false);
            if (!transStatus.isOk()) {
                ALOGE("allowAllocation(false) transaction error: %s",
                      transStatus.description().c_str());
                return C2_CORRUPTED;
            }
            if (!h2b(static_cast<HStatus>(transStatus), &status)) {
                status = android::BAD_VALUE;
            }
            if (status != android::NO_ERROR) {
                ALOGE("allowAllocation(false) failed");
                return asC2Error(status);
            }
            // Store buffer formats for future usage.
            mBufferFormat = BufferFormat(width, height, pixelFormat, androidUsage);
            ALOG_ASSERT(mAllocateBuffersLock.owns_lock());
            mAllocateBuffersLock.unlock();
        }
    } else if (mSlotAllocations.size() < mBuffersRequested) {
        ALOGE("failed to allocate enough buffers");
        return C2_NO_MEMORY;
    }

    // Reset spare dequeue delay time once we have dequeued a working buffer.
    mSpareDequeueDelayUs.reset();

    auto poolData = std::make_shared<C2VdaBqBlockPoolData>(mProducerId, slot, shared_from_this());
    *block = _C2BlockFactory::CreateGraphicBlock(mSlotAllocations[slot], std::move(poolData));
    return C2_OK;
}

c2_status_t C2VdaBqBlockPool::Impl::fetchSpareBufferSlot(HGraphicBufferProducer* const producer,
                                                         uint32_t width, uint32_t height,
                                                         uint32_t pixelFormat,
                                                         C2AndroidMemoryUsage androidUsage,
                                                         uint32_t* generation, uint64_t* usage) {
    ALOGV("fetchSpareBufferSlot");
    sp<Fence> fence = new Fence();
    int32_t status;
    int32_t slot;

    c2_status_t err =
            dequeueBuffer(producer, width, height, pixelFormat, androidUsage, status, slot, fence);
    if (err != C2_OK) {
        return err;
    }

    // Wait for acquire fence if we get one.
    HFenceWrapper hFenceWrapper{};
    if (!b2h(fence, &hFenceWrapper)) {
        ALOGE("Invalid fence received from dequeueBuffer.");
        return C2_BAD_VALUE;
    }
    if (fence) {
        status_t fenceStatus = fence->wait(kFenceWaitTimeMs);
        if (fenceStatus != android::NO_ERROR) {
            Return<HStatus> cancelTransStatus =
                    producer->cancelBuffer(slot, hFenceWrapper.getHandle());
            if (!cancelTransStatus.isOk()) {
                ALOGE("cancelBuffer transaction error: %s",
                      cancelTransStatus.description().c_str());
                return C2_CORRUPTED;
            }
            if (fenceStatus == -ETIME) {  // fence wait timed out
                ALOGV("buffer fence wait timed out, wait for retry...");
                return C2_TIMED_OUT;
            }
            ALOGE("buffer fence wait error: %d", fenceStatus);
            return asC2Error(fenceStatus);
        }
    }

    if (status != BUFFER_NEEDS_REALLOCATION) {
        ALOGD("dequeued a new slot index without BUFFER_NEEDS_REALLOCATION flag.");
    }

    // Call requestBuffer to allocate buffer for the slot and obtain the reference.
    // Get generation number here.
    sp<GraphicBuffer> slotBuffer = new GraphicBuffer();
    Return<void> transStatus = producer->requestBuffer(
            slot, [&status, &slotBuffer, &generation](HStatus hStatus, HBuffer const& hBuffer,
                                                      uint32_t generationNumber) {
                if (h2b(hStatus, &status) && h2b(hBuffer, &slotBuffer) && slotBuffer) {
                    *generation = generationNumber;
                    slotBuffer->setGenerationNumber(generationNumber);
                } else {
                    status = android::BAD_VALUE;
                }
            });

    // Check requestBuffer transaction status.
    if (!transStatus.isOk()) {
        ALOGE("requestBuffer transaction error: %s", transStatus.description().c_str());
        return C2_CORRUPTED;
    }

    // Get generation number and usage from the slot buffer.
    *usage = slotBuffer->getUsage();
    ALOGV("Obtained from spare buffer: generation = %u, usage = %" PRIu64 "", *generation, *usage);

    // Cancel this buffer anyway.
    Return<HStatus> cancelTransStatus = producer->cancelBuffer(slot, hFenceWrapper.getHandle());
    if (!cancelTransStatus.isOk()) {
        ALOGE("cancelBuffer transaction error: %s", cancelTransStatus.description().c_str());
        return C2_CORRUPTED;
    }

    // Check requestBuffer return flag.
    if (status != android::NO_ERROR) {
        ALOGE("requestBuffer failed: %d", status);
        return asC2Error(status);
    }

    mSpareSlot = slot;
    mSpareDequeueDelayUs.reset();
    ALOGV("Spare slot index = %d", mSpareSlot);
    return C2_OK;
}

c2_status_t C2VdaBqBlockPool::Impl::dequeueBuffer(HGraphicBufferProducer* const producer,
                                                  uint32_t width, uint32_t height,
                                                  uint32_t pixelFormat,
                                                  C2AndroidMemoryUsage androidUsage,
                                                  int32_t& status, int32_t& slot,
                                                  sp<Fence>& fence) {
    using Input = HGraphicBufferProducer::DequeueBufferInput;
    using Output = HGraphicBufferProducer::DequeueBufferOutput;
    bool needRealloc = false;
    Return<void> transStatus = producer->dequeueBuffer(
            Input{width, height, pixelFormat, androidUsage.asGrallocUsage()},
            [&status, &slot, &needRealloc, &fence](HStatus hStatus, int32_t hSlot,
                                                   Output const& hOutput) {
                slot = hSlot;
                if (!h2b(hStatus, &status) || !h2b(hOutput.fence, &fence)) {
                    status = android::BAD_VALUE;
                } else {
                    needRealloc = hOutput.bufferNeedsReallocation;
                    if (needRealloc) {
                        status = BUFFER_NEEDS_REALLOCATION;
                    }
                }
            });

    // Check dequeueBuffer transaction status
    if (!transStatus.isOk()) {
        ALOGE("dequeueBuffer transaction error: %s", transStatus.description().c_str());
        return C2_CORRUPTED;
    }
    // Check dequeueBuffer return flag
    if (status != android::NO_ERROR && status != BUFFER_NEEDS_REALLOCATION) {
        ALOGE("dequeueBuffer failed: %d", status);
        return asC2Error(status);
    }
    return C2_OK;
}

void C2VdaBqBlockPool::Impl::setRenderCallback(
        const C2BufferQueueBlockPool::OnRenderCallback& renderCallback) {
    ALOGV("setRenderCallback");
    std::lock_guard<std::mutex> lock(mMutex);
    mRenderCallback = renderCallback;
}

c2_status_t C2VdaBqBlockPool::Impl::requestNewBufferSet(int32_t bufferCount) {
    if (bufferCount <= 0) {
        ALOGE("Invalid requested buffer count = %d", bufferCount);
        return C2_BAD_VALUE;
    }

    if (!mAllocateBuffersLock.try_lock_for(kTimedMutexTimeoutMs)) {
        ALOGE("Cannot acquire allocate buffers / configure producer lock over %" PRId64 " ms...",
              static_cast<int64_t>(kTimedMutexTimeoutMs.count()));
        return C2_BLOCKING;
    }

    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProducer) {
        ALOGD("No HGraphicBufferProducer is configured...");
        return C2_NO_INIT;
    }

    if (mProducerSwitched) {
        // Some slots can be occupied by buffers transferred from the old producer. They will not
        // used in the current producer. Free the slots of the buffers here. But we cannot find a
        // slot is associated with the staled buffer. We free all slots whose associated buffers
        // are not owned by client.
        ALOGI("requestNewBufferSet: detachBuffer all slots forcedly");
        for (int32_t slot = 0; slot < static_cast<int32_t>(NUM_BUFFER_SLOTS); ++slot) {
            if (mSlotAllocations.find(slot) != mSlotAllocations.end()) {
                // Skip detaching the buffer which is owned by client now.
                continue;
            }
            Return<HStatus> transStatus = mProducer->detachBuffer(slot);
            if (!transStatus.isOk()) {
                ALOGE("detachBuffer trans error: %s", transStatus.description().c_str());
                return C2_CORRUPTED;
            }
            int32_t status;
            if (!h2b(static_cast<HStatus>(transStatus), &status)) {
                status = android::BAD_VALUE;
            }
            if (status == android::NO_INIT) {
                // No more active buffer slot. Break the loop now.
                break;
            }
        }
        mProducerSwitched = false;
    }

    ALOGV("Requested new buffer count: %d, still dequeued buffer count: %zu", bufferCount,
          mSlotAllocations.size());

    // The remained slot indices in |mSlotAllocations| now are still dequeued (un-available).
    // maxDequeuedBufferCount should be set to "new requested buffer count" + "still dequeued buffer
    // count" to make sure it has enough available slots to request buffer from.
    // Moreover, one extra buffer count is added for fetching spare buffer slot index.
    Return<HStatus> transStatus =
            mProducer->setMaxDequeuedBufferCount(bufferCount + mSlotAllocations.size() + 1);
    if (!transStatus.isOk()) {
        ALOGE("setMaxDequeuedBufferCount trans error: %s", transStatus.description().c_str());
        return C2_CORRUPTED;
    }
    int32_t status;
    if (!h2b(static_cast<HStatus>(transStatus), &status)) {
        status = android::BAD_VALUE;
    }
    if (status != android::NO_ERROR) {
        ALOGE("setMaxDequeuedBufferCount failed");
        return asC2Error(status);
    }

    // Release all remained slot buffer references here. CCodec should either cancel or queue its
    // owned buffers from this set before the next resolution change.
    mSlotAllocations.clear();
    mProducerChangeSlotMap.clear();
    mBuffersRequested = static_cast<size_t>(bufferCount);
    mSpareSlot = -1;

    Return<HStatus> transStatus2 = mProducer->allowAllocation(true);
    if (!transStatus2.isOk()) {
        ALOGE("allowAllocation(true) transaction error: %s", transStatus2.description().c_str());
        return C2_CORRUPTED;
    }
    if (!h2b(static_cast<HStatus>(transStatus2), &status)) {
        status = android::BAD_VALUE;
    }
    if (status != android::NO_ERROR) {
        ALOGE("allowAllocation(true) failed");
        return asC2Error(status);
    }
    return C2_OK;
}

void C2VdaBqBlockPool::Impl::configureProducer(const sp<HGraphicBufferProducer>& producer) {
    ALOGV("configureProducer");
    if (producer == nullptr) {
        ALOGE("input producer is nullptr...");
        return;
    }

    std::unique_lock<std::timed_mutex> configureProducerLock(
            mConfigureProducerAndAllocateBuffersMutex, std::defer_lock);
    if (!configureProducerLock.try_lock_for(kTimedMutexTimeoutMs)) {
        ALOGE("Cannot acquire configure producer / allocate buffers lock over %" PRId64 " ms...",
              static_cast<int64_t>(kTimedMutexTimeoutMs.count()));
        return;
    }

    std::lock_guard<std::mutex> lock(mMutex);
    uint64_t producerId;
    Return<uint64_t> transStatus = producer->getUniqueId();
    if (!transStatus.isOk()) {
        ALOGE("getUniqueId transaction error: %s", transStatus.description().c_str());
        return;
    }
    producerId = static_cast<uint64_t>(transStatus);

    if (mProducer && mProducerId != producerId) {
        ALOGI("Producer (Surface) is going to switch... ( %" PRIu64 " -> %" PRIu64 " )",
              mProducerId, producerId);
        if (!switchProducer(producer.get(), producerId)) {
            mProducerChangeSlotMap.clear();
            return;
        }
    } else {
        mSlotAllocations.clear();
    }

    // HGraphicBufferProducer could (and should) be replaced if the client has set a new generation
    // number to producer. The old HGraphicBufferProducer will be disconnected and deprecated then.
    mProducer = producer;
    mProducerId = producerId;
}

bool C2VdaBqBlockPool::Impl::switchProducer(HGraphicBufferProducer* const newProducer,
                                            uint64_t newProducerId) {
    if (mAllocator->getId() == android::V4L2AllocatorId::SECURE_GRAPHIC) {
        // TODO(johnylin): support this when we meet the use case in the future.
        ALOGE("Switch producer for secure buffer is not supported...");
        return false;
    }

    // Set maxDequeuedBufferCount to new producer.
    // Just like requestNewBufferSet(), maxDequeuedBufferCount should be set to "requested buffer
    // count" + "buffer count in client" + 1 (spare buffer) to make sure it has enough available
    // slots to request buffer from.
    // "Requested buffer count" could be obtained by the size of |mSlotAllocations|. However, it is
    // not able to know "buffer count in client" in blockpool's aspect. The alternative solution is
    // to set the worse case first, which is equal to the size of |mSlotAllocations|. And in the end
    // of updateGraphicBlock() routine, we could get the arbitrary "buffer count in client" by
    // counting the calls of updateGraphicBlock(willCancel=true). Then we set maxDequeuedBufferCount
    // again to the correct value.
    Return<HStatus> transStatus =
            newProducer->setMaxDequeuedBufferCount(mSlotAllocations.size() * 2 + 1);
    if (!transStatus.isOk()) {
        ALOGE("setMaxDequeuedBufferCount trans error: %s", transStatus.description().c_str());
        return false;
    }
    int32_t status;
    if (!h2b(static_cast<HStatus>(transStatus), &status)) {
        status = android::BAD_VALUE;
    }
    if (status != android::NO_ERROR) {
        ALOGE("setMaxDequeuedBufferCount failed");
        return false;
    }

    // Reset "buffer count in client". It will be accumulated in updateGraphicBlock() routine.
    mBuffersInClient = 0;

    // Set allowAllocation to new producer.
    Return<HStatus> transStatus2 = newProducer->allowAllocation(true);
    if (!transStatus2.isOk()) {
        ALOGE("allowAllocation(true) transaction error: %s", transStatus2.description().c_str());
        return false;
    }
    if (!h2b(static_cast<HStatus>(transStatus2), &status)) {
        status = android::BAD_VALUE;
    }
    if (status != android::NO_ERROR) {
        ALOGE("allowAllocation(true) failed");
        return false;
    }

    // Fetch spare buffer slot from new producer first, this step also allows us to obtain the
    // generation number and usage of new producer. While attaching buffers, generation number and
    // usage must be aligned to the producer.
    uint32_t newGeneration;
    uint64_t newUsage;
    c2_status_t err = fetchSpareBufferSlot(newProducer, mBufferFormat.mWidth, mBufferFormat.mHeight,
                                           mBufferFormat.mPixelFormat, mBufferFormat.mUsage,
                                           &newGeneration, &newUsage);
    if (err != C2_OK) {
        ALOGE("fetchSpareBufferSlot failed: %d", err);
        return false;
    }

    // Attach all buffers to new producer.
    mProducerChangeSlotMap.clear();
    int32_t slot;
    std::map<int32_t, std::shared_ptr<C2GraphicAllocation>> newSlotAllocations;
    for (auto iter = mSlotAllocations.begin(); iter != mSlotAllocations.end(); ++iter) {
        // Convert C2GraphicAllocation to GraphicBuffer.
        uint32_t width, height, format, stride, igbp_slot, generation;
        uint64_t usage, igbp_id;
        android::_UnwrapNativeCodec2GrallocMetadata(iter->second->handle(), &width, &height,
                                                    &format, &usage, &stride, &generation, &igbp_id,
                                                    &igbp_slot);
        native_handle_t* grallocHandle =
                android::UnwrapNativeCodec2GrallocHandle(iter->second->handle());

        // Update generation number and usage from newly-allocated spare buffer.
        sp<GraphicBuffer> graphicBuffer =
                new GraphicBuffer(grallocHandle, GraphicBuffer::CLONE_HANDLE, width, height, format,
                                  1, newUsage, stride);
        if (graphicBuffer->initCheck() != android::NO_ERROR) {
            ALOGE("Failed to create GraphicBuffer: %d", graphicBuffer->initCheck());
            return false;
        }
        graphicBuffer->setGenerationNumber(newGeneration);
        native_handle_delete(grallocHandle);

        // Convert GraphicBuffer into HBuffer.
        HBuffer hBuffer{};
        uint32_t hGenerationNumber{};
        if (!b2h(graphicBuffer, &hBuffer, &hGenerationNumber)) {
            ALOGE("Failed to convert GraphicBuffer to HBuffer");
            return false;
        }

        // Attach HBuffer to new producer and get the attached slot index.
        bool converted{};
        Return<void> transStatus = newProducer->attachBuffer(
                hBuffer, hGenerationNumber,
                [&converted, &status, &slot](HStatus hStatus, int32_t hSlot, bool releaseAll) {
                    converted = h2b(hStatus, &status);
                    if (!converted) {
                        status = android::BAD_VALUE;
                    }
                    slot = hSlot;
                    if (converted && releaseAll && status == android::OK) {
                        status = android::INVALID_OPERATION;
                    }
                });
        if (!transStatus.isOk()) {
            ALOGE("attachBuffer trans error: %s", transStatus.description().c_str());
            return false;
        }
        if (status != android::NO_ERROR) {
            ALOGE("attachBuffer failed: %d", status);
            return false;
        }

        // Convert back to C2GraphicAllocation wrapping new producer id, generation number, usage
        // and slot index.
        ALOGV("buffer wraps { producer id: %" PRIu64 ", slot: %d }", newProducerId, slot);
        C2Handle* c2Handle = android::WrapNativeCodec2GrallocHandle(
                graphicBuffer->handle, width, height, format, newUsage, stride, newGeneration,
                newProducerId, slot);
        if (!c2Handle) {
            ALOGE("WrapNativeCodec2GrallocHandle failed");
            return false;
        }
        std::shared_ptr<C2GraphicAllocation> alloc;
        c2_status_t err = mAllocator->priorGraphicAllocation(c2Handle, &alloc);
        if (err != C2_OK) {
            ALOGE("priorGraphicAllocation failed: %d", err);
            return false;
        }

        // Store to |newSlotAllocations| and also store old-to-new producer slot map.
        ALOGV("Transfered buffer from old producer to new, slot prev: %d -> new %d", iter->first,
              slot);
        newSlotAllocations[slot] = std::move(alloc);
        mProducerChangeSlotMap[iter->first] = slot;
    }

    // Set allowAllocation to false so producer could not allocate new buffers.
    Return<HStatus> transStatus4 = newProducer->allowAllocation(false);
    if (!transStatus4.isOk()) {
        ALOGE("allowAllocation(false) transaction error: %s", transStatus4.description().c_str());
        return false;
    }
    if (!h2b(static_cast<HStatus>(transStatus4), &status)) {
        status = android::BAD_VALUE;
    }
    if (status != android::NO_ERROR) {
        ALOGE("allowAllocation(false) failed");
        return false;
    }

    // Try to detach all buffers from old producer.
    for (const auto& slotAllocation : mSlotAllocations) {
        Return<HStatus> transStatus = mProducer->detachBuffer(slotAllocation.first);
        if (!transStatus.isOk()) {
            ALOGE("detachBuffer trans error: %s", transStatus.description().c_str());
            return false;
        }
        if (!h2b(static_cast<HStatus>(transStatus), &status)) {
            status = android::BAD_VALUE;
        }
        if (status != android::NO_ERROR) {
            ALOGW("detachBuffer slot=%d from old producer failed: %d", slotAllocation.first,
                  status);
        }
    }

    mSlotAllocations = std::move(newSlotAllocations);
    return true;
}

c2_status_t C2VdaBqBlockPool::Impl::updateGraphicBlock(
        bool willCancel, uint32_t oldSlot, uint32_t* newSlot,
        std::shared_ptr<C2GraphicBlock>* block /* nonnull */) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mProducerChangeSlotMap.empty()) {
        ALOGD("A new buffer set is requested right after producer change, no more update needed.");
        return C2_CANCELED;
    }

    auto it = mProducerChangeSlotMap.find(static_cast<int32_t>(oldSlot));
    if (it == mProducerChangeSlotMap.end()) {
        ALOGE("Cannot find old slot = %u in map...", oldSlot);
        return C2_NOT_FOUND;
    }

    int32_t slot = it->second;
    *newSlot = static_cast<uint32_t>(slot);
    mProducerChangeSlotMap.erase(it);

    if (willCancel) {
        // The old C2GraphicBlock might be owned by client. Cancel this slot.
        Return<HStatus> transStatus = mProducer->cancelBuffer(slot, hidl_handle{});
        if (!transStatus.isOk()) {
            ALOGE("cancelBuffer transaction error: %s", transStatus.description().c_str());
            return C2_CORRUPTED;
        }
        // Client might try to attach the old buffer to the current producer on client's end,
        // although it is useless for us anymore. However it will still occupy an available slot.
        mBuffersInClient++;
    } else {
        // The old C2GraphicBlock is still owned by component, replace by the new one and keep this
        // slot dequeued.
        auto poolData =
                std::make_shared<C2VdaBqBlockPoolData>(mProducerId, slot, shared_from_this());
        *block = _C2BlockFactory::CreateGraphicBlock(mSlotAllocations[slot], std::move(poolData));
    }

    if (mProducerChangeSlotMap.empty()) {
        // The updateGraphicBlock() routine is about to finish.
        // Set the correct maxDequeuedBufferCount to producer, which is "requested buffer count" +
        // "buffer count in client" + 1 (spare buffer).
        ALOGV("Requested buffer count: %zu, buffer count in client: %u", mSlotAllocations.size(),
              mBuffersInClient);
        Return<HStatus> transStatus = mProducer->setMaxDequeuedBufferCount(mSlotAllocations.size() +
                                                                           mBuffersInClient + 1);
        if (!transStatus.isOk()) {
            ALOGE("setMaxDequeuedBufferCount trans error: %s", transStatus.description().c_str());
            return C2_CORRUPTED;
        }
        int32_t status;
        if (!h2b(static_cast<HStatus>(transStatus), &status)) {
            status = android::BAD_VALUE;
        }
        if (status != android::NO_ERROR) {
            ALOGE("setMaxDequeuedBufferCount failed: %d", status);
            return C2_CORRUPTED;
        }
        mProducerSwitched = true;
    }

    return C2_OK;
}

c2_status_t C2VdaBqBlockPool::Impl::getMinBuffersForDisplay(size_t* bufferCount) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (!mProducer) {
        ALOGD("No HGraphicBufferProducer is configured...");
        return C2_NO_INIT;
    }

    int32_t status, value;
    Return<void> transStatus = mProducer->query(NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS,
                                                [&status, &value](int32_t tStatus, int32_t tValue) {
                                                    status = tStatus;
                                                    value = tValue;
                                                });
    if (!transStatus.isOk()) {
        ALOGE("query(NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS) trans error: %s",
              transStatus.description().c_str());
        return C2_CORRUPTED;
    }
    if (status != android::NO_ERROR) {
        ALOGE("query(NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS) failed: %d", status);
        return asC2Error(status);
    }
    if (value <= 0) {
        ALOGE("Illegal value of NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS = %d", value);
        return C2_BAD_VALUE;
    }
    *bufferCount = static_cast<size_t>(value);
    return C2_OK;
}

void C2VdaBqBlockPool::Impl::detachBuffer(uint64_t producerId, int32_t slotId) {
    ALOGV("detachBuffer: producer id = %" PRIu64 ", slot = %d", producerId, slotId);
    std::lock_guard<std::mutex> lock(mMutex);
    if (producerId == mProducerId && mProducer) {
        Return<HStatus> transStatus = mProducer->detachBuffer(slotId);
        if (!transStatus.isOk()) {
            ALOGE("detachBuffer trans error: %s", transStatus.description().c_str());
            return;
        }
        int32_t status;
        if (!h2b(static_cast<HStatus>(transStatus), &status)) {
            status = android::BAD_VALUE;
        }
        if (status != android::NO_ERROR) {
            ALOGD("detachBuffer failed: %d", status);
            return;
        }

        auto it = mSlotAllocations.find(slotId);
        // It may happen that the slot is not included in |mSlotAllocations|, which means it is
        // released after resolution change.
        if (it != mSlotAllocations.end()) {
            mSlotAllocations.erase(it);
        }
    }
}

C2VdaBqBlockPool::C2VdaBqBlockPool(const std::shared_ptr<C2Allocator>& allocator,
                                   const local_id_t localId)
      : C2BufferQueueBlockPool(allocator, localId), mLocalId(localId), mImpl(new Impl(allocator)) {}

c2_status_t C2VdaBqBlockPool::fetchGraphicBlock(
        uint32_t width, uint32_t height, uint32_t format, C2MemoryUsage usage,
        std::shared_ptr<C2GraphicBlock>* block /* nonnull */) {
    if (mImpl) {
        return mImpl->fetchGraphicBlock(width, height, format, usage, block);
    }
    return C2_NO_INIT;
}

void C2VdaBqBlockPool::setRenderCallback(
        const C2BufferQueueBlockPool::OnRenderCallback& renderCallback) {
    if (mImpl) {
        mImpl->setRenderCallback(renderCallback);
    }
}

c2_status_t C2VdaBqBlockPool::requestNewBufferSet(int32_t bufferCount) {
    if (mImpl) {
        return mImpl->requestNewBufferSet(bufferCount);
    }
    return C2_NO_INIT;
}

void C2VdaBqBlockPool::configureProducer(const sp<HGraphicBufferProducer>& producer) {
    if (mImpl) {
        mImpl->configureProducer(producer);
    }
}

c2_status_t C2VdaBqBlockPool::updateGraphicBlock(
        bool willCancel, uint32_t oldSlot, uint32_t* newSlot,
        std::shared_ptr<C2GraphicBlock>* block /* nonnull */) {
    if (mImpl) {
        return mImpl->updateGraphicBlock(willCancel, oldSlot, newSlot, block);
    }
    return C2_NO_INIT;
}

c2_status_t C2VdaBqBlockPool::getMinBuffersForDisplay(size_t* bufferCount) {
    if (mImpl) {
        return mImpl->getMinBuffersForDisplay(bufferCount);
    }
    return C2_NO_INIT;
}

C2VdaBqBlockPoolData::C2VdaBqBlockPoolData(uint64_t producerId, int32_t slotId,
                                           const std::shared_ptr<C2VdaBqBlockPool::Impl>& pool)
      : mProducerId(producerId), mSlotId(slotId), mPool(pool) {}

C2VdaBqBlockPoolData::~C2VdaBqBlockPoolData() {
    if (mShared || !mPool) {
        return;
    }
    mPool->detachBuffer(mProducerId, mSlotId);
}
