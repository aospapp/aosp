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

#ifndef ANDROID_C2_VDA_BQ_BLOCK_POOL_H_
#define ANDROID_C2_VDA_BQ_BLOCK_POOL_H_

#include <functional>
#include <map>

#include <media/stagefright/bqhelper/WGraphicBufferProducer.h>

#include <C2BqBufferPriv.h>
#include <C2Buffer.h>

/**
 * The BufferQueue-backed block pool design which supports to request arbitrary count of graphic
 * buffers from IGBP, and use this buffer set among codec component and client.
 *
 * The block pool should restore the mapping table between slot indices and GraphicBuffer (or
 * C2GraphicAllocation). When component requests a new buffer, the block pool calls dequeueBuffer
 * to IGBP to obtain a valid slot index, and returns the corresponding buffer from map.
 *
 * Buffers in the map should be canceled to IGBP on block pool destruction, or on resolution change
 * request.
 */
class C2VdaBqBlockPool : public C2BufferQueueBlockPool {
public:
    C2VdaBqBlockPool(const std::shared_ptr<C2Allocator>& allocator, const local_id_t localId);

    ~C2VdaBqBlockPool() override;

    C2Allocator::id_t getAllocatorId() const override { return mAllocator->getId(); };

    local_id_t getLocalId() const override { return mLocalId; };

    /**
     * Tries to dequeue a buffer from producer. If the dequeued slot is not in |mSlotBuffers| and
     * BUFFER_NEEDS_REALLOCATION is returned, allocates new buffer from producer by requestBuffer
     * and records the buffer and its slot index into |mSlotBuffers|.
     *
     * When the size of |mSlotBuffers| reaches the requested buffer count, set disallow allocation
     * to producer. After that only slots with allocated buffer could be dequeued.
     */
    c2_status_t fetchGraphicBlock(uint32_t width, uint32_t height, uint32_t format,
                                  C2MemoryUsage usage,
                                  std::shared_ptr<C2GraphicBlock>* block /* nonnull */) override;

    void configureProducer(const android::sp<android::HGraphicBufferProducer>& producer) override;

    /**
     * Sends the request of arbitrary number of graphic buffers allocation. If producer is given,
     * it will set maxDequeuedBufferCount as the requested buffer count to producer.
     *
     * \note C2VdaBqBlockPool-specific function
     *
     * \param bufferCount  the number of requested buffers
     */
    c2_status_t requestNewBufferSet(int32_t bufferCount);

private:
    c2_status_t cancelAllBuffers();

    const std::shared_ptr<C2Allocator> mAllocator;
    const local_id_t mLocalId;

    android::sp<android::HGraphicBufferProducer> mProducer;
    uint64_t mProducerId;

    // Function mutex to lock at the start of each API function call for protecting the
    // synchronization of all member variables.
    std::mutex mMutex;

    std::map<int32_t, std::shared_ptr<C2GraphicAllocation>> mSlotAllocations;
    size_t mMaxDequeuedBuffers;
};

#endif  // ANDROID_C2_VDA_BQ_BLOCK_POOL_H_
