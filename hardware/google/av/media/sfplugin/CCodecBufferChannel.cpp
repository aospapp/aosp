/*
 * Copyright 2017, The Android Open Source Project
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
#define LOG_TAG "CCodecBufferChannel"
#include <utils/Log.h>

#include <numeric>
#include <thread>

#include <C2AllocatorGralloc.h>
#include <C2PlatformSupport.h>
#include <C2BlockInternal.h>
#include <C2Config.h>
#include <C2Debug.h>

#include <android/hardware/cas/native/1.0/IDescrambler.h>
#include <binder/MemoryDealer.h>
#include <gui/Surface.h>
#include <media/openmax/OMX_Core.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ALookup.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AUtils.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/MediaCodecConstants.h>
#include <media/MediaCodecBuffer.h>
#include <system/window.h>

#include "CCodecBufferChannel.h"
#include "Codec2Buffer.h"
#include "SkipCutBuffer.h"

namespace android {

using hardware::hidl_handle;
using hardware::hidl_string;
using hardware::hidl_vec;
using namespace hardware::cas::V1_0;
using namespace hardware::cas::native::V1_0;

using CasStatus = hardware::cas::V1_0::Status;

/**
 * Base class for representation of buffers at one port.
 */
class CCodecBufferChannel::Buffers {
public:
    Buffers() = default;
    virtual ~Buffers() = default;

    /**
     * Set format for MediaCodec-facing buffers.
     */
    void setFormat(const sp<AMessage> &format) {
        CHECK(format != nullptr);
        mFormat = format;
    }

    /**
     * Return a copy of current format.
     */
    sp<AMessage> dupFormat() {
        return mFormat != nullptr ? mFormat->dup() : nullptr;
    }

    /**
     * Returns true if the buffers are operating under array mode.
     */
    virtual bool isArrayMode() const { return false; }

    /**
     * Fills the vector with MediaCodecBuffer's if in array mode; otherwise,
     * no-op.
     */
    virtual void getArray(Vector<sp<MediaCodecBuffer>> *) const {}

protected:
    // Format to be used for creating MediaCodec-facing buffers.
    sp<AMessage> mFormat;

private:
    DISALLOW_EVIL_CONSTRUCTORS(Buffers);
};

class CCodecBufferChannel::InputBuffers : public CCodecBufferChannel::Buffers {
public:
    InputBuffers() = default;
    virtual ~InputBuffers() = default;

    /**
     * Set a block pool to obtain input memory blocks.
     */
    void setPool(const std::shared_ptr<C2BlockPool> &pool) { mPool = pool; }

    /**
     * Get a new MediaCodecBuffer for input and its corresponding index.
     * Returns false if no new buffer can be obtained at the moment.
     */
    virtual bool requestNewBuffer(size_t *index, sp<MediaCodecBuffer> *buffer) = 0;

    /**
     * Release the buffer obtained from requestNewBuffer() and get the
     * associated C2Buffer object back. Returns true if the buffer was on file
     * and released successfully.
     */
    virtual bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) = 0;

    /**
     * Flush internal state. After this call, no index or buffer previously
     * returned from requestNewBuffer() is valid.
     */
    virtual void flush() = 0;

    /**
     * Return array-backed version of input buffers. The returned object
     * shall retain the internal state so that it will honor index and
     * buffer from previous calls of requestNewBuffer().
     */
    virtual std::unique_ptr<InputBuffers> toArrayMode() = 0;

protected:
    // Pool to obtain blocks for input buffers.
    std::shared_ptr<C2BlockPool> mPool;

private:
    DISALLOW_EVIL_CONSTRUCTORS(InputBuffers);
};

class CCodecBufferChannel::OutputBuffers : public CCodecBufferChannel::Buffers {
public:
    OutputBuffers() = default;
    virtual ~OutputBuffers() = default;

    /**
     * Register output C2Buffer from the component and obtain corresponding
     * index and MediaCodecBuffer object. Returns false if registration
     * fails.
     */
    virtual bool registerBuffer(
            const std::shared_ptr<C2Buffer> &buffer,
            size_t *index,
            sp<MediaCodecBuffer> *clientBuffer) = 0;

    /**
     * Register codec specific data as a buffer to be consistent with
     * MediaCodec behavior.
     */
    virtual bool registerCsd(
            const C2StreamCsdInfo::output * /* csd */,
            size_t * /* index */,
            sp<MediaCodecBuffer> * /* clientBuffer */) = 0;

    /**
     * Release the buffer obtained from registerBuffer() and get the
     * associated C2Buffer object back. Returns true if the buffer was on file
     * and released successfully.
     */
    virtual bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) = 0;

    /**
     * Flush internal state. After this call, no index or buffer previously
     * returned from registerBuffer() is valid.
     */
    virtual void flush(const std::list<std::unique_ptr<C2Work>> &flushedWork) = 0;

    /**
     * Return array-backed version of output buffers. The returned object
     * shall retain the internal state so that it will honor index and
     * buffer from previous calls of registerBuffer().
     */
    virtual std::unique_ptr<OutputBuffers> toArrayMode() = 0;

    /**
     * Initialize SkipCutBuffer object.
     */
    void initSkipCutBuffer(
            int32_t delay, int32_t padding, int32_t sampleRate, int32_t channelCount) {
        CHECK(mSkipCutBuffer == nullptr);
        mDelay = delay;
        mPadding = padding;
        mSampleRate = sampleRate;
        setSkipCutBuffer(delay, padding, channelCount);
    }

    /**
     * Update the SkipCutBuffer object. No-op if it's never initialized.
     */
    void updateSkipCutBuffer(int32_t sampleRate, int32_t channelCount) {
        if (mSkipCutBuffer == nullptr) {
            return;
        }
        int32_t delay = mDelay;
        int32_t padding = mPadding;
        if (sampleRate != mSampleRate) {
            delay = ((int64_t)delay * sampleRate) / mSampleRate;
            padding = ((int64_t)padding * sampleRate) / mSampleRate;
        }
        setSkipCutBuffer(delay, padding, channelCount);
    }

    /**
     * Submit buffer to SkipCutBuffer object, if initialized.
     */
    void submit(const sp<MediaCodecBuffer> &buffer) {
        if (mSkipCutBuffer != nullptr) {
            mSkipCutBuffer->submit(buffer);
        }
    }

    /**
     * Transfer SkipCutBuffer object to the other Buffers object.
     */
    void transferSkipCutBuffer(const sp<SkipCutBuffer> &scb) {
        mSkipCutBuffer = scb;
    }

protected:
    sp<SkipCutBuffer> mSkipCutBuffer;

private:
    int32_t mDelay;
    int32_t mPadding;
    int32_t mSampleRate;

    void setSkipCutBuffer(int32_t skip, int32_t cut, int32_t channelCount) {
        if (mSkipCutBuffer != nullptr) {
            size_t prevSize = mSkipCutBuffer->size();
            if (prevSize != 0u) {
                ALOGD("Replacing SkipCutBuffer holding %zu bytes", prevSize);
            }
        }
        mSkipCutBuffer = new SkipCutBuffer(skip, cut, channelCount);
    }

    DISALLOW_EVIL_CONSTRUCTORS(OutputBuffers);
};

namespace {

// TODO: get this info from component
const static size_t kMinInputBufferArraySize = 8;
const static size_t kMinOutputBufferArraySize = 16;
const static size_t kLinearBufferSize = 1048576;

/**
 * Simple local buffer pool backed by std::vector.
 */
class LocalBufferPool : public std::enable_shared_from_this<LocalBufferPool> {
public:
    /**
     * Create a new LocalBufferPool object.
     *
     * \param poolCapacity  max total size of buffers managed by this pool.
     *
     * \return  a newly created pool object.
     */
    static std::shared_ptr<LocalBufferPool> Create(size_t poolCapacity) {
        return std::shared_ptr<LocalBufferPool>(new LocalBufferPool(poolCapacity));
    }

    /**
     * Return an ABuffer object whose size is at least |capacity|.
     *
     * \param   capacity  requested capacity
     * \return  nullptr if the pool capacity is reached
     *          an ABuffer object otherwise.
     */
    sp<ABuffer> newBuffer(size_t capacity) {
        Mutex::Autolock lock(mMutex);
        auto it = std::find_if(
                mPool.begin(), mPool.end(),
                [capacity](const std::vector<uint8_t> &vec) {
                    return vec.capacity() >= capacity;
                });
        if (it != mPool.end()) {
            sp<ABuffer> buffer = new VectorBuffer(std::move(*it), shared_from_this());
            mPool.erase(it);
            return buffer;
        }
        if (mUsedSize + capacity > mPoolCapacity) {
            while (!mPool.empty()) {
                mUsedSize -= mPool.back().capacity();
                mPool.pop_back();
            }
            if (mUsedSize + capacity > mPoolCapacity) {
                ALOGD("mUsedSize = %zu, capacity = %zu, mPoolCapacity = %zu",
                        mUsedSize, capacity, mPoolCapacity);
                return nullptr;
            }
        }
        std::vector<uint8_t> vec(capacity);
        mUsedSize += vec.capacity();
        return new VectorBuffer(std::move(vec), shared_from_this());
    }

private:
    /**
     * ABuffer backed by std::vector.
     */
    class VectorBuffer : public ::android::ABuffer {
    public:
        /**
         * Construct a VectorBuffer by taking the ownership of supplied vector.
         *
         * \param vec   backing vector of the buffer. this object takes
         *              ownership at construction.
         * \param pool  a LocalBufferPool object to return the vector at
         *              destruction.
         */
        VectorBuffer(std::vector<uint8_t> &&vec, const std::shared_ptr<LocalBufferPool> &pool)
            : ABuffer(vec.data(), vec.capacity()),
              mVec(std::move(vec)),
              mPool(pool) {
        }

        ~VectorBuffer() override {
            std::shared_ptr<LocalBufferPool> pool = mPool.lock();
            if (pool) {
                // If pool is alive, return the vector back to the pool so that
                // it can be recycled.
                pool->returnVector(std::move(mVec));
            }
        }

    private:
        std::vector<uint8_t> mVec;
        std::weak_ptr<LocalBufferPool> mPool;
    };

    Mutex mMutex;
    size_t mPoolCapacity;
    size_t mUsedSize;
    std::list<std::vector<uint8_t>> mPool;

    /**
     * Private constructor to prevent constructing non-managed LocalBufferPool.
     */
    explicit LocalBufferPool(size_t poolCapacity)
        : mPoolCapacity(poolCapacity), mUsedSize(0) {
    }

    /**
     * Take back the ownership of vec from the destructed VectorBuffer and put
     * it in front of the pool.
     */
    void returnVector(std::vector<uint8_t> &&vec) {
        Mutex::Autolock lock(mMutex);
        mPool.push_front(std::move(vec));
    }

    DISALLOW_EVIL_CONSTRUCTORS(LocalBufferPool);
};

sp<GraphicBlockBuffer> AllocateGraphicBuffer(
        const std::shared_ptr<C2BlockPool> &pool,
        const sp<AMessage> &format,
        uint32_t pixelFormat,
        const C2MemoryUsage &usage,
        const std::shared_ptr<LocalBufferPool> &localBufferPool) {
    int32_t width, height;
    if (!format->findInt32("width", &width) || !format->findInt32("height", &height)) {
        ALOGD("format lacks width or height");
        return nullptr;
    }

    std::shared_ptr<C2GraphicBlock> block;
    c2_status_t err = pool->fetchGraphicBlock(
            width, height, pixelFormat, usage, &block);
    if (err != C2_OK) {
        ALOGD("fetch graphic block failed: %d", err);
        return nullptr;
    }

    return GraphicBlockBuffer::Allocate(
            format,
            block,
            [localBufferPool](size_t capacity) {
                return localBufferPool->newBuffer(capacity);
            });
}

class BuffersArrayImpl;

/**
 * Flexible buffer slots implementation.
 */
class FlexBuffersImpl {
public:
    FlexBuffersImpl() = default;

    /**
     * Assign an empty slot for a buffer and return the index. If there's no
     * empty slot, just add one at the end and return it.
     *
     * \param buffer[in]  a new buffer to assign a slot.
     * \return            index of the assigned slot.
     */
    size_t assignSlot(const sp<Codec2Buffer> &buffer) {
        for (size_t i = 0; i < mBuffers.size(); ++i) {
            if (mBuffers[i].clientBuffer == nullptr
                    && mBuffers[i].compBuffer.expired()) {
                mBuffers[i].clientBuffer = buffer;
                return i;
            }
        }
        mBuffers.push_back({ buffer, std::weak_ptr<C2Buffer>() });
        return mBuffers.size() - 1;
    }

    /**
     * Release the slot from the client, and get the C2Buffer object back from
     * the previously assigned buffer. Note that the slot is not completely free
     * until the returned C2Buffer object is freed.
     *
     * \param   buffer[in]        the buffer previously assigned a slot.
     * \param   c2buffer[in,out]  pointer to C2Buffer to be populated. Ignored
     *                            if null.
     * \return  true  if the buffer is successfully released from a slot
     *          false otherwise
     */
    bool releaseSlot(const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) {
        sp<Codec2Buffer> clientBuffer;
        size_t index = mBuffers.size();
        for (size_t i = 0; i < mBuffers.size(); ++i) {
            if (mBuffers[i].clientBuffer == buffer) {
                clientBuffer = mBuffers[i].clientBuffer;
                mBuffers[i].clientBuffer.clear();
                index = i;
                break;
            }
        }
        if (clientBuffer == nullptr) {
            ALOGV("%s: No matching buffer found", __func__);
            return false;
        }
        std::shared_ptr<C2Buffer> result = clientBuffer->asC2Buffer();
        mBuffers[index].compBuffer = result;
        if (c2buffer) {
            *c2buffer = result;
        }
        return true;
    }

private:
    friend class BuffersArrayImpl;

    struct Entry {
        sp<Codec2Buffer> clientBuffer;
        std::weak_ptr<C2Buffer> compBuffer;
    };
    std::vector<Entry> mBuffers;
};

/**
 * Static buffer slots implementation based on a fixed-size array.
 */
class BuffersArrayImpl {
public:
    /**
     * Initialize buffer array from the original |impl|. The buffers known by
     * the client is preserved, and the empty slots are populated so that the
     * array size is at least |minSize|.
     *
     * \param impl[in]      FlexBuffersImpl object used so far.
     * \param minSize[in]   minimum size of the buffer array.
     * \param allocate[in]  function to allocate a client buffer for an empty slot.
     */
    void initialize(
            const FlexBuffersImpl &impl,
            size_t minSize,
            std::function<sp<Codec2Buffer>()> allocate) {
        for (size_t i = 0; i < impl.mBuffers.size(); ++i) {
            sp<Codec2Buffer> clientBuffer = impl.mBuffers[i].clientBuffer;
            bool ownedByClient = (clientBuffer != nullptr);
            if (!ownedByClient) {
                clientBuffer = allocate();
            }
            mBuffers.push_back({ clientBuffer, impl.mBuffers[i].compBuffer, ownedByClient });
        }
        for (size_t i = impl.mBuffers.size(); i < minSize; ++i) {
            mBuffers.push_back({ allocate(), std::weak_ptr<C2Buffer>(), false });
        }
    }

    /**
     * Grab a buffer from the underlying array which matches the criteria.
     *
     * \param index[out]    index of the slot.
     * \param buffer[out]   the matching buffer.
     * \param match[in]     a function to test whether the buffer matches the
     *                      criteria or not.
     * \return OK           if successful,
     *         NO_MEMORY    if there's no available slot meets the criteria.
     */
    status_t grabBuffer(
            size_t *index,
            sp<Codec2Buffer> *buffer,
            std::function<bool(const sp<Codec2Buffer> &)> match =
                [](const sp<Codec2Buffer> &) { return true; }) {
        for (size_t i = 0; i < mBuffers.size(); ++i) {
            if (!mBuffers[i].ownedByClient && mBuffers[i].compBuffer.expired()
                    && match(mBuffers[i].clientBuffer)) {
                mBuffers[i].ownedByClient = true;
                *buffer = mBuffers[i].clientBuffer;
                (*buffer)->meta()->clear();
                (*buffer)->setRange(0, (*buffer)->capacity());
                *index = i;
                return OK;
            }
        }
        return NO_MEMORY;
    }

    /**
     * Return the buffer from the client, and get the C2Buffer object back from
     * the buffer. Note that the slot is not completely free until the returned
     * C2Buffer object is freed.
     *
     * \param   buffer[in]        the buffer previously grabbed.
     * \param   c2buffer[in,out]  pointer to C2Buffer to be populated. Ignored
     *                            if null.
     * \return  true  if the buffer is successfully returned
     *          false otherwise
     */
    bool returnBuffer(const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) {
        sp<Codec2Buffer> clientBuffer;
        size_t index = mBuffers.size();
        for (size_t i = 0; i < mBuffers.size(); ++i) {
            if (mBuffers[i].clientBuffer == buffer) {
                if (!mBuffers[i].ownedByClient) {
                    ALOGD("Client returned a buffer it does not own according to our record: %zu", i);
                }
                clientBuffer = mBuffers[i].clientBuffer;
                mBuffers[i].ownedByClient = false;
                index = i;
                break;
            }
        }
        if (clientBuffer == nullptr) {
            ALOGV("%s: No matching buffer found", __func__);
            return false;
        }
        ALOGV("%s: matching buffer found (index=%zu)", __func__, index);
        std::shared_ptr<C2Buffer> result = clientBuffer->asC2Buffer();
        mBuffers[index].compBuffer = result;
        if (c2buffer) {
            *c2buffer = result;
        }
        return true;
    }

    /**
     * Populate |array| with the underlying buffer array.
     *
     * \param array[out]  an array to be filled with the underlying buffer array.
     */
    void getArray(Vector<sp<MediaCodecBuffer>> *array) const {
        array->clear();
        for (const Entry &entry : mBuffers) {
            array->push(entry.clientBuffer);
        }
    }

    /**
     * The client abandoned all known buffers, so reclaim the ownership.
     */
    void flush() {
        for (Entry &entry : mBuffers) {
            entry.ownedByClient = false;
        }
    }

private:
    struct Entry {
        const sp<Codec2Buffer> clientBuffer;
        std::weak_ptr<C2Buffer> compBuffer;
        bool ownedByClient;
    };
    std::vector<Entry> mBuffers;
};

class InputBuffersArray : public CCodecBufferChannel::InputBuffers {
public:
    InputBuffersArray() = default;
    ~InputBuffersArray() override = default;

    void initialize(
            const FlexBuffersImpl &impl,
            size_t minSize,
            std::function<sp<Codec2Buffer>()> allocate) {
        mImpl.initialize(impl, minSize, allocate);
    }

    bool isArrayMode() const final { return true; }

    std::unique_ptr<CCodecBufferChannel::InputBuffers> toArrayMode() final {
        return nullptr;
    }

    void getArray(Vector<sp<MediaCodecBuffer>> *array) const final {
        mImpl.getArray(array);
    }

    bool requestNewBuffer(size_t *index, sp<MediaCodecBuffer> *buffer) override {
        sp<Codec2Buffer> c2Buffer;
        status_t err = mImpl.grabBuffer(index, &c2Buffer);
        if (err == OK) {
            c2Buffer->setFormat(mFormat);
            *buffer = c2Buffer;
            return true;
        }
        return false;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.returnBuffer(buffer, c2buffer);
    }

    void flush() override {
        mImpl.flush();
    }

private:
    BuffersArrayImpl mImpl;
};

class LinearInputBuffers : public CCodecBufferChannel::InputBuffers {
public:
    using CCodecBufferChannel::InputBuffers::InputBuffers;

    bool requestNewBuffer(size_t *index, sp<MediaCodecBuffer> *buffer) override {
        int32_t capacity = kLinearBufferSize;
        (void)mFormat->findInt32(KEY_MAX_INPUT_SIZE, &capacity);
        // TODO: proper max input size
        // TODO: read usage from intf
        sp<Codec2Buffer> newBuffer = alloc((size_t)capacity);
        if (newBuffer == nullptr) {
            return false;
        }
        *index = mImpl.assignSlot(newBuffer);
        *buffer = newBuffer;
        return true;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.releaseSlot(buffer, c2buffer);
    }

    void flush() override {
        // This is no-op by default unless we're in array mode where we need to keep
        // track of the flushed work.
    }

    std::unique_ptr<CCodecBufferChannel::InputBuffers> toArrayMode() final {
        int32_t capacity = kLinearBufferSize;
        (void)mFormat->findInt32(C2_NAME_STREAM_MAX_BUFFER_SIZE_SETTING, &capacity);

        std::unique_ptr<InputBuffersArray> array(new InputBuffersArray);
        array->setPool(mPool);
        array->setFormat(mFormat);
        array->initialize(
                mImpl,
                kMinInputBufferArraySize,
                [this, capacity] () -> sp<Codec2Buffer> { return alloc(capacity); });
        return std::move(array);
    }

    virtual sp<Codec2Buffer> alloc(size_t size) const {
        C2MemoryUsage usage = { C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE };
        std::shared_ptr<C2LinearBlock> block;

        c2_status_t err = mPool->fetchLinearBlock(size, usage, &block);
        if (err != C2_OK) {
            return nullptr;
        }

        return LinearBlockBuffer::Allocate(mFormat, block);
    }

private:
    FlexBuffersImpl mImpl;
};

class EncryptedLinearInputBuffers : public LinearInputBuffers {
public:
    EncryptedLinearInputBuffers(
            bool secure,
            const sp<MemoryDealer> &dealer,
            const sp<ICrypto> &crypto,
            int32_t heapSeqNum)
        : mUsage({0, 0}),
          mDealer(dealer),
          mCrypto(crypto),
          mHeapSeqNum(heapSeqNum) {
        if (secure) {
            mUsage = { C2MemoryUsage::READ_PROTECTED, 0 };
        } else {
            mUsage = { C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE };
        }
        for (size_t i = 0; i < kMinInputBufferArraySize; ++i) {
            sp<IMemory> memory = mDealer->allocate(kLinearBufferSize);
            if (memory == nullptr) {
                ALOGD("Failed to allocate memory from dealer: only %zu slots allocated", i);
                break;
            }
            mMemoryVector.push_back({std::weak_ptr<C2LinearBlock>(), memory});
        }
    }

    ~EncryptedLinearInputBuffers() override {
    }

    sp<Codec2Buffer> alloc(size_t size) const override {
        sp<IMemory> memory;
        for (const Entry &entry : mMemoryVector) {
            if (entry.block.expired()) {
                memory = entry.memory;
                break;
            }
        }
        if (memory == nullptr) {
            return nullptr;
        }

        std::shared_ptr<C2LinearBlock> block;
        c2_status_t err = mPool->fetchLinearBlock(size, mUsage, &block);
        if (err != C2_OK) {
            return nullptr;
        }

        return new EncryptedLinearBlockBuffer(mFormat, block, memory, mHeapSeqNum);
    }

private:
    C2MemoryUsage mUsage;
    sp<MemoryDealer> mDealer;
    sp<ICrypto> mCrypto;
    int32_t mHeapSeqNum;
    struct Entry {
        std::weak_ptr<C2LinearBlock> block;
        sp<IMemory> memory;
    };
    std::vector<Entry> mMemoryVector;
};

class GraphicMetadataInputBuffers : public CCodecBufferChannel::InputBuffers {
public:
    GraphicMetadataInputBuffers() : mStore(GetCodec2PlatformAllocatorStore()) {}
    ~GraphicMetadataInputBuffers() override = default;

    bool requestNewBuffer(size_t *index, sp<MediaCodecBuffer> *buffer) override {
        std::shared_ptr<C2Allocator> alloc;
        c2_status_t err = mStore->fetchAllocator(mPool->getAllocatorId(), &alloc);
        if (err != C2_OK) {
            return false;
        }
        sp<GraphicMetadataBuffer> newBuffer = new GraphicMetadataBuffer(mFormat, alloc);
        if (newBuffer == nullptr) {
            return false;
        }
        *index = mImpl.assignSlot(newBuffer);
        *buffer = newBuffer;
        return true;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.releaseSlot(buffer, c2buffer);
    }

    void flush() override {
        // This is no-op by default unless we're in array mode where we need to keep
        // track of the flushed work.
    }

    std::unique_ptr<CCodecBufferChannel::InputBuffers> toArrayMode() final {
        std::shared_ptr<C2Allocator> alloc;
        c2_status_t err = mStore->fetchAllocator(mPool->getAllocatorId(), &alloc);
        if (err != C2_OK) {
            return nullptr;
        }
        std::unique_ptr<InputBuffersArray> array(new InputBuffersArray);
        array->setPool(mPool);
        array->setFormat(mFormat);
        array->initialize(
                mImpl,
                kMinInputBufferArraySize,
                [format = mFormat, alloc]() -> sp<Codec2Buffer> {
                    return new GraphicMetadataBuffer(format, alloc);
                });
        return std::move(array);
    }

private:
    FlexBuffersImpl mImpl;
    std::shared_ptr<C2AllocatorStore> mStore;
};

class GraphicInputBuffers : public CCodecBufferChannel::InputBuffers {
public:
    GraphicInputBuffers()
        : mLocalBufferPool(LocalBufferPool::Create(1920 * 1080 * 4 * 16)) {
    }
    ~GraphicInputBuffers() override = default;

    bool requestNewBuffer(size_t *index, sp<MediaCodecBuffer> *buffer) override {
        // TODO: proper max input size
        // TODO: read usage from intf
        C2MemoryUsage usage = { C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE };
        sp<GraphicBlockBuffer> newBuffer = AllocateGraphicBuffer(
                mPool, mFormat, HAL_PIXEL_FORMAT_YV12, usage, mLocalBufferPool);
        if (newBuffer == nullptr) {
            return false;
        }
        *index = mImpl.assignSlot(newBuffer);
        *buffer = newBuffer;
        return true;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.releaseSlot(buffer, c2buffer);
    }

    void flush() override {
        // This is no-op by default unless we're in array mode where we need to keep
        // track of the flushed work.
    }

    std::unique_ptr<CCodecBufferChannel::InputBuffers> toArrayMode() final {
        std::unique_ptr<InputBuffersArray> array(new InputBuffersArray);
        array->setPool(mPool);
        array->setFormat(mFormat);
        array->initialize(
                mImpl,
                kMinInputBufferArraySize,
                [pool = mPool, format = mFormat, lbp = mLocalBufferPool]() -> sp<Codec2Buffer> {
                    C2MemoryUsage usage = { C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE };
                    return AllocateGraphicBuffer(
                            pool, format, HAL_PIXEL_FORMAT_YV12, usage, lbp);
                });
        return std::move(array);
    }

private:
    FlexBuffersImpl mImpl;
    std::shared_ptr<LocalBufferPool> mLocalBufferPool;
};

class DummyInputBuffers : public CCodecBufferChannel::InputBuffers {
public:
    DummyInputBuffers() = default;

    bool requestNewBuffer(size_t *, sp<MediaCodecBuffer> *) override {
        return false;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &, std::shared_ptr<C2Buffer> *) override {
        return false;
    }

    void flush() override {
    }

    std::unique_ptr<CCodecBufferChannel::InputBuffers> toArrayMode() final {
        return nullptr;
    }

    bool isArrayMode() const final { return true; }

    void getArray(Vector<sp<MediaCodecBuffer>> *array) const final {
        array->clear();
    }
};

class OutputBuffersArray : public CCodecBufferChannel::OutputBuffers {
public:
    OutputBuffersArray() = default;
    ~OutputBuffersArray() override = default;

    void initialize(
            const FlexBuffersImpl &impl,
            size_t minSize,
            std::function<sp<Codec2Buffer>()> allocate) {
        mImpl.initialize(impl, minSize, allocate);
    }

    bool isArrayMode() const final { return true; }

    std::unique_ptr<CCodecBufferChannel::OutputBuffers> toArrayMode() final {
        return nullptr;
    }

    bool registerBuffer(
            const std::shared_ptr<C2Buffer> &buffer,
            size_t *index,
            sp<MediaCodecBuffer> *clientBuffer) final {
        sp<Codec2Buffer> c2Buffer;
        status_t err = mImpl.grabBuffer(
                index,
                &c2Buffer,
                [buffer](const sp<Codec2Buffer> &clientBuffer) {
                    return clientBuffer->canCopy(buffer);
                });
        if (err != OK) {
            ALOGD("grabBuffer failed: %d", err);
            return false;
        }
        c2Buffer->setFormat(mFormat);
        if (!c2Buffer->copy(buffer)) {
            ALOGD("copy buffer failed");
            return false;
        }
        submit(c2Buffer);
        *clientBuffer = c2Buffer;
        return true;
    }

    bool registerCsd(
            const C2StreamCsdInfo::output *csd,
            size_t *index,
            sp<MediaCodecBuffer> *clientBuffer) final {
        sp<Codec2Buffer> c2Buffer;
        status_t err = mImpl.grabBuffer(
                index,
                &c2Buffer,
                [csd](const sp<Codec2Buffer> &clientBuffer) {
                    return clientBuffer->base() != nullptr
                            && clientBuffer->capacity() >= csd->flexCount();
                });
        if (err != OK) {
            return false;
        }
        memcpy(c2Buffer->base(), csd->m.value, csd->flexCount());
        c2Buffer->setRange(0, csd->flexCount());
        c2Buffer->setFormat(mFormat);
        *clientBuffer = c2Buffer;
        return true;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.returnBuffer(buffer, c2buffer);
    }

    void flush(const std::list<std::unique_ptr<C2Work>> &flushedWork) override {
        (void)flushedWork;
        mImpl.flush();
        if (mSkipCutBuffer != nullptr) {
            mSkipCutBuffer->clear();
        }
    }

    void getArray(Vector<sp<MediaCodecBuffer>> *array) const final {
        mImpl.getArray(array);
    }

private:
    BuffersArrayImpl mImpl;
};

class FlexOutputBuffers : public CCodecBufferChannel::OutputBuffers {
public:
    using CCodecBufferChannel::OutputBuffers::OutputBuffers;

    bool registerBuffer(
            const std::shared_ptr<C2Buffer> &buffer,
            size_t *index,
            sp<MediaCodecBuffer> *clientBuffer) override {
        sp<Codec2Buffer> newBuffer = wrap(buffer);
        newBuffer->setFormat(mFormat);
        *index = mImpl.assignSlot(newBuffer);
        *clientBuffer = newBuffer;
        return true;
    }

    bool registerCsd(
            const C2StreamCsdInfo::output *csd,
            size_t *index,
            sp<MediaCodecBuffer> *clientBuffer) final {
        sp<Codec2Buffer> newBuffer = new LocalLinearBuffer(
                mFormat, ABuffer::CreateAsCopy(csd->m.value, csd->flexCount()));
        *index = mImpl.assignSlot(newBuffer);
        *clientBuffer = newBuffer;
        return true;
    }

    bool releaseBuffer(
            const sp<MediaCodecBuffer> &buffer, std::shared_ptr<C2Buffer> *c2buffer) override {
        return mImpl.releaseSlot(buffer, c2buffer);
    }

    void flush(
            const std::list<std::unique_ptr<C2Work>> &flushedWork) override {
        (void) flushedWork;
        // This is no-op by default unless we're in array mode where we need to keep
        // track of the flushed work.
    }

    std::unique_ptr<CCodecBufferChannel::OutputBuffers> toArrayMode() override {
        std::unique_ptr<OutputBuffersArray> array(new OutputBuffersArray);
        array->setFormat(mFormat);
        array->transferSkipCutBuffer(mSkipCutBuffer);
        array->initialize(
                mImpl,
                kMinOutputBufferArraySize,
                [this]() { return allocateArrayBuffer(); });
        return std::move(array);
    }

    /**
     * Return an appropriate Codec2Buffer object for the type of buffers.
     *
     * \param buffer  C2Buffer object to wrap.
     *
     * \return  appropriate Codec2Buffer object to wrap |buffer|.
     */
    virtual sp<Codec2Buffer> wrap(const std::shared_ptr<C2Buffer> &buffer) = 0;

    /**
     * Return an appropriate Codec2Buffer object for the type of buffers, to be
     * used as an empty array buffer.
     *
     * \return  appropriate Codec2Buffer object which can copy() from C2Buffers.
     */
    virtual sp<Codec2Buffer> allocateArrayBuffer() = 0;

private:
    FlexBuffersImpl mImpl;
};

class LinearOutputBuffers : public FlexOutputBuffers {
public:
    using FlexOutputBuffers::FlexOutputBuffers;

    void flush(
            const std::list<std::unique_ptr<C2Work>> &flushedWork) override {
        if (mSkipCutBuffer != nullptr) {
            mSkipCutBuffer->clear();
        }
        FlexOutputBuffers::flush(flushedWork);
    }

    sp<Codec2Buffer> wrap(const std::shared_ptr<C2Buffer> &buffer) override {
        if (buffer == nullptr) {
            return new LocalLinearBuffer(mFormat, new ABuffer(0));
        }
        if (buffer->data().type() != C2BufferData::LINEAR) {
            // We expect linear output buffers from the component.
            return nullptr;
        }
        if (buffer->data().linearBlocks().size() != 1u) {
            // We expect one and only one linear block from the component.
            return nullptr;
        }
        sp<Codec2Buffer> clientBuffer = ConstLinearBlockBuffer::Allocate(mFormat, buffer);
        submit(clientBuffer);
        return clientBuffer;
    }

    sp<Codec2Buffer> allocateArrayBuffer() override {
        // TODO: proper max output size
        return new LocalLinearBuffer(mFormat, new ABuffer(kLinearBufferSize));
    }
};

class GraphicOutputBuffers : public FlexOutputBuffers {
public:
    using FlexOutputBuffers::FlexOutputBuffers;

    sp<Codec2Buffer> wrap(const std::shared_ptr<C2Buffer> &buffer) override {
        return new DummyContainerBuffer(mFormat, buffer);
    }

    sp<Codec2Buffer> allocateArrayBuffer() override {
        return new DummyContainerBuffer(mFormat);
    }
};

class RawGraphicOutputBuffers : public FlexOutputBuffers {
public:
    RawGraphicOutputBuffers()
        : mLocalBufferPool(LocalBufferPool::Create(1920 * 1080 * 4 * 16)) {
    }
    ~RawGraphicOutputBuffers() override = default;

    sp<Codec2Buffer> wrap(const std::shared_ptr<C2Buffer> &buffer) override {
        if (buffer == nullptr) {
            return ConstGraphicBlockBuffer::AllocateEmpty(
                    mFormat,
                    [lbp = mLocalBufferPool](size_t capacity) {
                        return lbp->newBuffer(capacity);
                    });
        } else {
            return ConstGraphicBlockBuffer::Allocate(
                    mFormat,
                    buffer,
                    [lbp = mLocalBufferPool](size_t capacity) {
                        return lbp->newBuffer(capacity);
                    });
        }
    }

    sp<Codec2Buffer> allocateArrayBuffer() override {
        return ConstGraphicBlockBuffer::AllocateEmpty(
                mFormat,
                [lbp = mLocalBufferPool](size_t capacity) {
                    return lbp->newBuffer(capacity);
                });
    }

private:
    std::shared_ptr<LocalBufferPool> mLocalBufferPool;
};

}  // namespace

CCodecBufferChannel::QueueGuard::QueueGuard(
        CCodecBufferChannel::QueueSync &sync) : mSync(sync) {
    std::unique_lock<std::mutex> l(mSync.mMutex);
    // At this point it's guaranteed that mSync is not under state transition,
    // as we are holding its mutex.
    if (mSync.mCount == -1) {
        mRunning = false;
    } else {
        ++mSync.mCount;
        mRunning = true;
    }
}

CCodecBufferChannel::QueueGuard::~QueueGuard() {
    if (mRunning) {
        // We are not holding mutex at this point so that QueueSync::stop() can
        // keep holding the lock until mCount reaches zero.
        --mSync.mCount;
    }
}

void CCodecBufferChannel::QueueSync::start() {
    std::unique_lock<std::mutex> l(mMutex);
    // If stopped, it goes to running state; otherwise no-op.
    int32_t expected = -1;
    (void)mCount.compare_exchange_strong(expected, 0);
}

void CCodecBufferChannel::QueueSync::stop() {
    std::unique_lock<std::mutex> l(mMutex);
    if (mCount == -1) {
        // no-op
        return;
    }
    // Holding mutex here blocks creation of additional QueueGuard objects, so
    // mCount can only decrement. In other words, threads that acquired the lock
    // are allowed to finish execution but additional threads trying to acquire
    // the lock at this point will block, and then get QueueGuard at STOPPED
    // state.
    int32_t expected = 0;
    while (!mCount.compare_exchange_weak(expected, -1)) {
        std::this_thread::yield();
    }
}

CCodecBufferChannel::CCodecBufferChannel(
        const std::shared_ptr<CCodecCallback> &callback)
    : mHeapSeqNum(-1),
      mCCodecCallback(callback),
      mFrameIndex(0u),
      mFirstValidFrameIndex(0u),
      mMetaMode(MODE_NONE),
      mPendingFeed(0) {
}

CCodecBufferChannel::~CCodecBufferChannel() {
    if (mCrypto != nullptr && mDealer != nullptr && mHeapSeqNum >= 0) {
        mCrypto->unsetHeap(mHeapSeqNum);
    }
}

void CCodecBufferChannel::setComponent(
        const std::shared_ptr<Codec2Client::Component> &component) {
    mComponent = component;
}

status_t CCodecBufferChannel::setInputSurface(
        const std::shared_ptr<InputSurfaceWrapper> &surface) {
    ALOGV("setInputSurface");
    mInputSurface = surface;
    return mInputSurface->connect(mComponent);
}

status_t CCodecBufferChannel::signalEndOfInputStream() {
    if (mInputSurface == nullptr) {
        return INVALID_OPERATION;
    }
    return mInputSurface->signalEndOfInputStream();
}

status_t CCodecBufferChannel::queueInputBufferInternal(const sp<MediaCodecBuffer> &buffer) {
    int64_t timeUs;
    CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

    int32_t flags = 0;
    int32_t tmp = 0;
    bool eos = false;
    if (buffer->meta()->findInt32("eos", &tmp) && tmp) {
        eos = true;
        ALOGV("input EOS");
    }
    if (buffer->meta()->findInt32("csd", &tmp) && tmp) {
        flags |= C2FrameData::FLAG_CODEC_CONFIG;
    }
    ALOGV("queueInputBuffer: buffer->size() = %zu", buffer->size());
    std::unique_ptr<C2Work> work(new C2Work);
    work->input.ordinal.timestamp = timeUs;
    work->input.ordinal.frameIndex = mFrameIndex++;
    work->input.buffers.clear();
    if (buffer->size() > 0u) {
        Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
        std::shared_ptr<C2Buffer> c2buffer;
        if (!(*buffers)->releaseBuffer(buffer, &c2buffer)) {
            return -ENOENT;
        }
        work->input.buffers.push_back(c2buffer);
    } else if (eos) {
        flags |= C2FrameData::FLAG_END_OF_STREAM;
    }
    work->input.flags = (C2FrameData::flags_t)flags;
    // TODO: fill info's

    work->worklets.clear();
    work->worklets.emplace_back(new C2Worklet);

    std::list<std::unique_ptr<C2Work>> items;
    items.push_back(std::move(work));
    c2_status_t err = mComponent->queue(&items);

    if (err == C2_OK && eos && buffer->size() > 0u) {
        work.reset(new C2Work);
        work->input.ordinal.timestamp = timeUs;
        work->input.ordinal.frameIndex = mFrameIndex++;
        work->input.buffers.clear();
        work->input.flags = C2FrameData::FLAG_END_OF_STREAM;

        items.clear();
        items.push_back(std::move(work));
        err = mComponent->queue(&items);
    }

    feedInputBufferIfAvailableInternal();
    return err;
}

status_t CCodecBufferChannel::queueInputBuffer(const sp<MediaCodecBuffer> &buffer) {
    QueueGuard guard(mSync);
    if (!guard.isRunning()) {
        ALOGW("No more buffers should be queued at current state.");
        return -ENOSYS;
    }
    return queueInputBufferInternal(buffer);
}

status_t CCodecBufferChannel::queueSecureInputBuffer(
        const sp<MediaCodecBuffer> &buffer, bool secure, const uint8_t *key,
        const uint8_t *iv, CryptoPlugin::Mode mode, CryptoPlugin::Pattern pattern,
        const CryptoPlugin::SubSample *subSamples, size_t numSubSamples,
        AString *errorDetailMsg) {
    QueueGuard guard(mSync);
    if (!guard.isRunning()) {
        ALOGW("No more buffers should be queued at current state.");
        return -ENOSYS;
    }

    if (!hasCryptoOrDescrambler()) {
        return -ENOSYS;
    }
    sp<EncryptedLinearBlockBuffer> encryptedBuffer((EncryptedLinearBlockBuffer *)buffer.get());

    ssize_t result = -1;
    if (mCrypto != nullptr) {
        ICrypto::DestinationBuffer destination;
        if (secure) {
            destination.mType = ICrypto::kDestinationTypeNativeHandle;
            destination.mHandle = encryptedBuffer->handle();
        } else {
            destination.mType = ICrypto::kDestinationTypeSharedMemory;
            destination.mSharedMemory = mDecryptDestination;
        }
        ICrypto::SourceBuffer source;
        encryptedBuffer->fillSourceBuffer(&source);
        result = mCrypto->decrypt(
                key, iv, mode, pattern, source, buffer->offset(),
                subSamples, numSubSamples, destination, errorDetailMsg);
        if (result < 0) {
            return result;
        }
        if (destination.mType == ICrypto::kDestinationTypeSharedMemory) {
            encryptedBuffer->copyDecryptedContent(mDecryptDestination, result);
        }
    } else {
        // Here we cast CryptoPlugin::SubSample to hardware::cas::native::V1_0::SubSample
        // directly, the structure definitions should match as checked in DescramblerImpl.cpp.
        hidl_vec<SubSample> hidlSubSamples;
        hidlSubSamples.setToExternal((SubSample *)subSamples, numSubSamples, false /*own*/);

        hardware::cas::native::V1_0::SharedBuffer srcBuffer;
        encryptedBuffer->fillSourceBuffer(&srcBuffer);

        DestinationBuffer dstBuffer;
        if (secure) {
            dstBuffer.type = BufferType::NATIVE_HANDLE;
            dstBuffer.secureMemory = hidl_handle(encryptedBuffer->handle());
        } else {
            dstBuffer.type = BufferType::SHARED_MEMORY;
            dstBuffer.nonsecureMemory = srcBuffer;
        }

        CasStatus status = CasStatus::OK;
        hidl_string detailedError;

        auto returnVoid = mDescrambler->descramble(
                key != NULL ? (ScramblingControl)key[0] : ScramblingControl::UNSCRAMBLED,
                hidlSubSamples,
                srcBuffer,
                0,
                dstBuffer,
                0,
                [&status, &result, &detailedError] (
                        CasStatus _status, uint32_t _bytesWritten,
                        const hidl_string& _detailedError) {
                    status = _status;
                    result = (ssize_t)_bytesWritten;
                    detailedError = _detailedError;
                });

        if (!returnVoid.isOk() || status != CasStatus::OK || result < 0) {
            ALOGE("descramble failed, trans=%s, status=%d, result=%zd",
                    returnVoid.description().c_str(), status, result);
            return UNKNOWN_ERROR;
        }

        ALOGV("descramble succeeded, %zd bytes", result);

        if (dstBuffer.type == BufferType::SHARED_MEMORY) {
            encryptedBuffer->copyDecryptedContentFromMemory(result);
        }
    }

    buffer->setRange(0, result);
    return queueInputBufferInternal(buffer);
}

void CCodecBufferChannel::feedInputBufferIfAvailable() {
    QueueGuard guard(mSync);
    if (!guard.isRunning()) {
        ALOGV("We're not running --- no input buffer reported");
        return;
    }
    feedInputBufferIfAvailableInternal();
}

void CCodecBufferChannel::feedInputBufferIfAvailableInternal() {
    while (mPendingFeed > 0) {
        sp<MediaCodecBuffer> inBuffer;
        size_t index;
        {
            Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
            if (!(*buffers)->requestNewBuffer(&index, &inBuffer)) {
                ALOGV("no new buffer available");
                break;
            }
        }
        ALOGV("new input index = %zu", index);
        mCallback->onInputBufferAvailable(index, inBuffer);
        ALOGV("%s: pending feed -1 from %u", __func__, mPendingFeed.load());
        --mPendingFeed;
    }
}

status_t CCodecBufferChannel::renderOutputBuffer(
        const sp<MediaCodecBuffer> &buffer, int64_t timestampNs) {
    ALOGV("renderOutputBuffer");
    ALOGV("%s: pending feed +1 from %u", __func__, mPendingFeed.load());
    ++mPendingFeed;
    feedInputBufferIfAvailable();

    std::shared_ptr<C2Buffer> c2Buffer;
    {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        if (*buffers) {
            (*buffers)->releaseBuffer(buffer, &c2Buffer);
        }
    }
    if (!c2Buffer) {
        return INVALID_OPERATION;
    }

#if 0
    const std::vector<std::shared_ptr<const C2Info>> infoParams = c2Buffer->info();
    ALOGV("queuing gfx buffer with %zu infos", infoParams.size());
    for (const std::shared_ptr<const C2Info> &info : infoParams) {
        AString res;
        for (size_t ix = 0; ix + 3 < info->size(); ix += 4) {
            if (ix) res.append(", ");
            res.append(*((int32_t*)info.get() + (ix / 4)));
        }
        ALOGV("  [%s]", res.c_str());
    }
#endif
    std::shared_ptr<const C2StreamRotationInfo::output> rotation =
        std::static_pointer_cast<const C2StreamRotationInfo::output>(
                c2Buffer->getInfo(C2StreamRotationInfo::output::PARAM_TYPE));
    bool flip = rotation && (rotation->flip & 1);
    uint32_t quarters = ((rotation ? rotation->value : 0) / 90) & 3;
    uint32_t transform = 0;
    switch (quarters) {
        case 0: // no rotation
            transform = flip ? HAL_TRANSFORM_FLIP_H : 0;
            break;
        case 1: // 90 degrees counter-clockwise
            transform = flip ? (HAL_TRANSFORM_FLIP_V | HAL_TRANSFORM_ROT_90)
                    : HAL_TRANSFORM_ROT_270;
            break;
        case 2: // 180 degrees
            transform = flip ? HAL_TRANSFORM_FLIP_V : HAL_TRANSFORM_ROT_180;
            break;
        case 3: // 90 degrees clockwise
            transform = flip ? (HAL_TRANSFORM_FLIP_H | HAL_TRANSFORM_ROT_90)
                    : HAL_TRANSFORM_ROT_90;
            break;
    }

    std::shared_ptr<const C2StreamSurfaceScalingInfo::output> surfaceScaling =
        std::static_pointer_cast<const C2StreamSurfaceScalingInfo::output>(
                c2Buffer->getInfo(C2StreamSurfaceScalingInfo::output::PARAM_TYPE));
    uint32_t videoScalingMode = NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW;
    if (surfaceScaling) {
        videoScalingMode = surfaceScaling->value;
    }

    // Use dataspace if component provides it. Otherwise, compose dataspace from color aspects
    std::shared_ptr<const C2StreamDataSpaceInfo::output> dataSpaceInfo =
        std::static_pointer_cast<const C2StreamDataSpaceInfo::output>(
                c2Buffer->getInfo(C2StreamDataSpaceInfo::output::PARAM_TYPE));
    uint32_t dataSpace = HAL_DATASPACE_UNKNOWN; // this is 0
    if (dataSpaceInfo) {
        dataSpace = dataSpaceInfo->value;
    } else {
        std::shared_ptr<const C2StreamColorAspectsInfo::output> colorAspects =
            std::static_pointer_cast<const C2StreamColorAspectsInfo::output>(
                    c2Buffer->getInfo(C2StreamColorAspectsInfo::output::PARAM_TYPE));
        C2Color::range_t range =
            colorAspects == nullptr ? C2Color::RANGE_UNSPECIFIED     : colorAspects->range;
        C2Color::primaries_t primaries =
            colorAspects == nullptr ? C2Color::PRIMARIES_UNSPECIFIED : colorAspects->primaries;
        C2Color::transfer_t transfer =
            colorAspects == nullptr ? C2Color::TRANSFER_UNSPECIFIED  : colorAspects->transfer;
        C2Color::matrix_t matrix =
            colorAspects == nullptr ? C2Color::MATRIX_UNSPECIFIED    : colorAspects->matrix;

        switch (range) {
            case C2Color::RANGE_FULL:    dataSpace |= HAL_DATASPACE_RANGE_FULL;    break;
            case C2Color::RANGE_LIMITED: dataSpace |= HAL_DATASPACE_RANGE_LIMITED; break;
            default: break;
        }

        switch (transfer) {
            case C2Color::TRANSFER_LINEAR:  dataSpace |= HAL_DATASPACE_TRANSFER_LINEAR;     break;
            case C2Color::TRANSFER_SRGB:    dataSpace |= HAL_DATASPACE_TRANSFER_SRGB;       break;
            case C2Color::TRANSFER_170M:    dataSpace |= HAL_DATASPACE_TRANSFER_SMPTE_170M; break;
            case C2Color::TRANSFER_GAMMA22: dataSpace |= HAL_DATASPACE_TRANSFER_GAMMA2_2;   break;
            case C2Color::TRANSFER_GAMMA28: dataSpace |= HAL_DATASPACE_TRANSFER_GAMMA2_8;   break;
            case C2Color::TRANSFER_ST2084:  dataSpace |= HAL_DATASPACE_TRANSFER_ST2084;     break;
            case C2Color::TRANSFER_HLG:     dataSpace |= HAL_DATASPACE_TRANSFER_HLG;        break;
            default: break;
        }

        switch (primaries) {
            case C2Color::PRIMARIES_BT601_525:
                dataSpace |= (matrix == C2Color::MATRIX_SMPTE240M
                                || matrix == C2Color::MATRIX_BT709)
                        ? HAL_DATASPACE_STANDARD_BT601_525_UNADJUSTED
                        : HAL_DATASPACE_STANDARD_BT601_525;
                break;
            case C2Color::PRIMARIES_BT601_625:
                dataSpace |= (matrix == C2Color::MATRIX_SMPTE240M
                                || matrix == C2Color::MATRIX_BT709)
                        ? HAL_DATASPACE_STANDARD_BT601_625_UNADJUSTED
                        : HAL_DATASPACE_STANDARD_BT601_625;
                break;
            case C2Color::PRIMARIES_BT2020:
                dataSpace |= (matrix == C2Color::MATRIX_BT2020CONSTANT
                        ? HAL_DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE
                        : HAL_DATASPACE_STANDARD_BT2020);
                break;
            case C2Color::PRIMARIES_BT470_M:
                dataSpace |= HAL_DATASPACE_STANDARD_BT470M;
                break;
            case C2Color::PRIMARIES_BT709:
                dataSpace |= HAL_DATASPACE_STANDARD_BT709;
                break;
            default: break;
        }
    }

    // convert legacy dataspace values to v0 values
    const static
    ALookup<android_dataspace, android_dataspace> sLegacyDataSpaceToV0 {
        {
            { HAL_DATASPACE_SRGB, HAL_DATASPACE_V0_SRGB },
            { HAL_DATASPACE_BT709, HAL_DATASPACE_V0_BT709 },
            { HAL_DATASPACE_SRGB_LINEAR, HAL_DATASPACE_V0_SRGB_LINEAR },
            { HAL_DATASPACE_BT601_525, HAL_DATASPACE_V0_BT601_525 },
            { HAL_DATASPACE_BT601_625, HAL_DATASPACE_V0_BT601_625 },
            { HAL_DATASPACE_JFIF, HAL_DATASPACE_V0_JFIF },
        }
    };
    sLegacyDataSpaceToV0.lookup((android_dataspace_t)dataSpace, (android_dataspace_t*)&dataSpace);

    // HDR static info
    std::shared_ptr<const C2StreamHdrStaticInfo::output> hdrStaticInfo =
        std::static_pointer_cast<const C2StreamHdrStaticInfo::output>(
                c2Buffer->getInfo(C2StreamHdrStaticInfo::output::PARAM_TYPE));

    {
        Mutexed<OutputSurface>::Locked output(mOutputSurface);
        if (output->surface == nullptr) {
            ALOGE("no surface");
            return OK;
        }
    }

    std::vector<C2ConstGraphicBlock> blocks = c2Buffer->data().graphicBlocks();
    if (blocks.size() != 1u) {
        ALOGE("# of graphic blocks expected to be 1, but %zu", blocks.size());
        return UNKNOWN_ERROR;
    }
    const C2ConstGraphicBlock &block = blocks.front();

    // TODO: revisit this after C2Fence implementation.
    android::IGraphicBufferProducer::QueueBufferInput qbi(
            timestampNs,
            false,
            (android_dataspace_t)dataSpace,
            Rect(blocks.front().crop().left,
                 blocks.front().crop().top,
                 blocks.front().crop().right(),
                 blocks.front().crop().bottom()),
            videoScalingMode,
            transform,
            Fence::NO_FENCE, 0);
    if (hdrStaticInfo) {
        struct android_smpte2086_metadata smpte2086_meta = {
            .displayPrimaryRed = {
                hdrStaticInfo->mastering.red.x, hdrStaticInfo->mastering.red.y
            },
            .displayPrimaryGreen = {
                hdrStaticInfo->mastering.green.x, hdrStaticInfo->mastering.green.y
            },
            .displayPrimaryBlue = {
                hdrStaticInfo->mastering.blue.x, hdrStaticInfo->mastering.blue.y
            },
            .whitePoint = {
                hdrStaticInfo->mastering.white.x, hdrStaticInfo->mastering.white.y
            },
            .maxLuminance = hdrStaticInfo->mastering.maxLuminance,
            .minLuminance = hdrStaticInfo->mastering.minLuminance,
        };

        struct android_cta861_3_metadata cta861_meta = {
            .maxContentLightLevel = hdrStaticInfo->maxCll,
            .maxFrameAverageLightLevel = hdrStaticInfo->maxFall,
        };

        HdrMetadata hdr;
        hdr.validTypes = HdrMetadata::SMPTE2086 | HdrMetadata::CTA861_3;
        hdr.smpte2086 = smpte2086_meta;
        hdr.cta8613 = cta861_meta;
        qbi.setHdrMetadata(hdr);
    }
    android::IGraphicBufferProducer::QueueBufferOutput qbo;
    status_t result = mComponent->queueToOutputSurface(block, qbi, &qbo);
    if (result != OK) {
        ALOGE("queueBuffer failed: %d", result);
        return result;
    }
    ALOGV("queue buffer successful");

    int64_t mediaTimeUs = 0;
    (void)buffer->meta()->findInt64("timeUs", &mediaTimeUs);
    mCCodecCallback->onOutputFramesRendered(mediaTimeUs, timestampNs);

    return OK;
}

status_t CCodecBufferChannel::discardBuffer(const sp<MediaCodecBuffer> &buffer) {
    ALOGV("discardBuffer: %p", buffer.get());
    bool released = false;
    {
        Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
        if (*buffers) {
            released = (*buffers)->releaseBuffer(buffer, nullptr);
        }
    }
    {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        if (*buffers && (*buffers)->releaseBuffer(buffer, nullptr)) {
            released = true;
            ALOGV("%s: pending feed +1 from %u", __func__, mPendingFeed.load());
            ++mPendingFeed;
        }
    }
    feedInputBufferIfAvailable();
    if (!released) {
        ALOGD("MediaCodec discarded an unknown buffer");
    }
    return OK;
}

void CCodecBufferChannel::getInputBufferArray(Vector<sp<MediaCodecBuffer>> *array) {
    array->clear();
    Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);

    if (!(*buffers)->isArrayMode()) {
        *buffers = (*buffers)->toArrayMode();
    }

    (*buffers)->getArray(array);
}

void CCodecBufferChannel::getOutputBufferArray(Vector<sp<MediaCodecBuffer>> *array) {
    array->clear();
    Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);

    if (!(*buffers)->isArrayMode()) {
        *buffers = (*buffers)->toArrayMode();
    }

    (*buffers)->getArray(array);
}

status_t CCodecBufferChannel::start(
        const sp<AMessage> &inputFormat, const sp<AMessage> &outputFormat) {
    C2StreamFormatConfig::input iStreamFormat(0u);
    C2StreamFormatConfig::output oStreamFormat(0u);
    c2_status_t err = mComponent->query(
            { &iStreamFormat, &oStreamFormat },
            {},
            C2_DONT_BLOCK,
            nullptr);
    if (err != C2_OK) {
        return UNKNOWN_ERROR;
    }

    // TODO: get this from input format
    bool secure = mComponent->getName().find(".secure") != std::string::npos;

    std::shared_ptr<C2AllocatorStore> allocatorStore = GetCodec2PlatformAllocatorStore();
    int poolMask = property_get_int32(
            "debug.stagefright.c2-poolmask",
            1 << C2PlatformAllocatorStore::ION |
            1 << C2PlatformAllocatorStore::BUFFERQUEUE);

    if (inputFormat != nullptr) {
        bool graphic = (iStreamFormat.value == C2FormatVideo);
        std::shared_ptr<C2BlockPool> pool;
        {
            Mutexed<BlockPools>::Locked pools(mBlockPools);

            // set default allocator ID.
            pools->inputAllocatorId = (graphic) ? C2PlatformAllocatorStore::GRALLOC
                                                : C2PlatformAllocatorStore::ION;

            // query C2PortAllocatorsTuning::input from component. If an allocator ID is obtained
            // from component, create the input block pool with given ID. Otherwise, use default IDs.
            std::vector<std::unique_ptr<C2Param>> params;
            err = mComponent->query({ },
                                    { C2PortAllocatorsTuning::input::PARAM_TYPE },
                                    C2_DONT_BLOCK,
                                    &params);
            if ((err != C2_OK && err != C2_BAD_INDEX) || params.size() != 1) {
                ALOGD("Query input allocators returned %zu params => %s (%u)",
                        params.size(), asString(err), err);
            } else if (err == C2_OK && params.size() == 1) {
                C2PortAllocatorsTuning::input *inputAllocators =
                    C2PortAllocatorsTuning::input::From(params[0].get());
                if (inputAllocators && inputAllocators->flexCount() > 0) {
                    std::shared_ptr<C2Allocator> allocator;
                    // verify allocator IDs and resolve default allocator
                    allocatorStore->fetchAllocator(inputAllocators->m.values[0], &allocator);
                    if (allocator) {
                        pools->inputAllocatorId = allocator->getId();
                    } else {
                        ALOGD("component requested invalid input allocator ID %u",
                                inputAllocators->m.values[0]);
                    }
                }
            }

            // TODO: use C2Component wrapper to associate this pool with ourselves
            if ((poolMask >> pools->inputAllocatorId) & 1) {
                err = CreateCodec2BlockPool(pools->inputAllocatorId, nullptr, &pool);
                ALOGD("Created input block pool with allocatorID %u => poolID %llu - %s (%d)",
                        pools->inputAllocatorId,
                        (unsigned long long)(pool ? pool->getLocalId() : 111000111),
                        asString(err), err);
            } else {
                err = C2_NOT_FOUND;
            }
            if (err != C2_OK) {
                C2BlockPool::local_id_t inputPoolId =
                    graphic ? C2BlockPool::BASIC_GRAPHIC : C2BlockPool::BASIC_LINEAR;
                err = GetCodec2BlockPool(inputPoolId, nullptr, &pool);
                ALOGD("Using basic input block pool with poolID %llu => got %llu - %s (%d)",
                        (unsigned long long)inputPoolId,
                        (unsigned long long)(pool ? pool->getLocalId() : 111000111),
                        asString(err), err);
                if (err != C2_OK) {
                    return NO_MEMORY;
                }
            }
            pools->inputPool = pool;
        }

        Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
        if (graphic) {
            if (mInputSurface) {
                buffers->reset(new DummyInputBuffers);
            } else if (mMetaMode == MODE_ANW) {
                buffers->reset(new GraphicMetadataInputBuffers);
            } else {
                buffers->reset(new GraphicInputBuffers);
            }
        } else {
            if (hasCryptoOrDescrambler()) {
                if (mDealer == nullptr) {
                    mDealer = new MemoryDealer(
                            align(kLinearBufferSize, MemoryDealer::getAllocationAlignment())
                                * (kMinInputBufferArraySize + 1),
                            "EncryptedLinearInputBuffers");
                    mDecryptDestination = mDealer->allocate(kLinearBufferSize);
                }
                if (mCrypto != nullptr && mHeapSeqNum < 0) {
                    mHeapSeqNum = mCrypto->setHeap(mDealer->getMemoryHeap());
                } else {
                    mHeapSeqNum = -1;
                }
                buffers->reset(new EncryptedLinearInputBuffers(
                        secure, mDealer, mCrypto, mHeapSeqNum));
            } else {
                buffers->reset(new LinearInputBuffers);
            }
        }
        (*buffers)->setFormat(inputFormat);

        if (err == C2_OK) {
            (*buffers)->setPool(pool);
        } else {
            // TODO: error
        }
    }

    if (outputFormat != nullptr) {
        sp<IGraphicBufferProducer> outputSurface;
        uint32_t outputGeneration;
        {
            Mutexed<OutputSurface>::Locked output(mOutputSurface);
            outputSurface = output->surface ?
                    output->surface->getIGraphicBufferProducer() : nullptr;
            outputGeneration = output->generation;
        }

        bool graphic = (oStreamFormat.value == C2FormatVideo);
        C2BlockPool::local_id_t outputPoolId_;

        {
            Mutexed<BlockPools>::Locked pools(mBlockPools);

            // set default allocator ID.
            pools->outputAllocatorId = (graphic) ? C2PlatformAllocatorStore::GRALLOC
                                                 : C2PlatformAllocatorStore::ION;

            // query C2PortAllocatorsTuning::output from component, or use default allocator if
            // unsuccessful.
            std::vector<std::unique_ptr<C2Param>> params;
            err = mComponent->query({ },
                                    { C2PortAllocatorsTuning::output::PARAM_TYPE },
                                    C2_DONT_BLOCK,
                                    &params);
            if ((err != C2_OK && err != C2_BAD_INDEX) || params.size() != 1) {
                ALOGD("Query input allocators returned %zu params => %s (%u)",
                        params.size(), asString(err), err);
            } else if (err == C2_OK && params.size() == 1) {
                C2PortAllocatorsTuning::output *outputAllocators =
                    C2PortAllocatorsTuning::output::From(params[0].get());
                if (outputAllocators && outputAllocators->flexCount() > 0) {
                    std::shared_ptr<C2Allocator> allocator;
                    // verify allocator IDs and resolve default allocator
                    allocatorStore->fetchAllocator(outputAllocators->m.values[0], &allocator);
                    if (allocator) {
                        pools->outputAllocatorId = allocator->getId();
                    } else {
                        ALOGD("component requested invalid output allocator ID %u",
                                outputAllocators->m.values[0]);
                    }
                }
            }

            // use bufferqueue if outputting to a surface
            if (pools->outputAllocatorId == C2PlatformAllocatorStore::GRALLOC
                    && outputSurface
                    && ((poolMask >> C2PlatformAllocatorStore::BUFFERQUEUE) & 1)) {
                pools->outputAllocatorId = C2PlatformAllocatorStore::BUFFERQUEUE;
            }

            if ((poolMask >> pools->outputAllocatorId) & 1) {
                err = mComponent->createBlockPool(
                        pools->outputAllocatorId, &pools->outputPoolId, &pools->outputPoolIntf);
                ALOGI("Created output block pool with allocatorID %u => poolID %llu - %s",
                        pools->outputAllocatorId,
                        (unsigned long long)pools->outputPoolId,
                        asString(err));
            } else {
                err = C2_NOT_FOUND;
            }
            if (err != C2_OK) {
                // use basic pool instead
                pools->outputPoolId =
                    graphic ? C2BlockPool::BASIC_GRAPHIC : C2BlockPool::BASIC_LINEAR;
            }

            // Configure output block pool ID as parameter C2PortBlockPoolsTuning::output to
            // component.
            std::unique_ptr<C2PortBlockPoolsTuning::output> poolIdsTuning =
                    C2PortBlockPoolsTuning::output::AllocUnique({ pools->outputPoolId });

            std::vector<std::unique_ptr<C2SettingResult>> failures;
            err = mComponent->config({ poolIdsTuning.get() }, C2_MAY_BLOCK, &failures);
            ALOGD("Configured output block pool ids %llu => %s",
                    (unsigned long long)poolIdsTuning->m.values[0], asString(err));
            outputPoolId_ = pools->outputPoolId;
        }

        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);

        if (graphic) {
            if (outputSurface) {
                buffers->reset(new GraphicOutputBuffers);
            } else {
                buffers->reset(new RawGraphicOutputBuffers);
            }
        } else {
            buffers->reset(new LinearOutputBuffers);
        }
        (*buffers)->setFormat(outputFormat->dup());


        // Try to set output surface to created block pool if given.
        if (outputSurface) {
            mComponent->setOutputSurface(
                    outputPoolId_,
                    outputSurface,
                    outputGeneration);
        }

        if (oStreamFormat.value == C2FormatAudio) {
            int32_t channelCount;
            int32_t sampleRate;
            if (outputFormat->findInt32(KEY_CHANNEL_COUNT, &channelCount)
                    && outputFormat->findInt32(KEY_SAMPLE_RATE, &sampleRate)) {
                int32_t delay = 0;
                int32_t padding = 0;;
                if (!outputFormat->findInt32("encoder-delay", &delay)) {
                    delay = 0;
                }
                if (!outputFormat->findInt32("encoder-padding", &padding)) {
                    padding = 0;
                }
                if (delay || padding) {
                    // We need write access to the buffers..
                    (*buffers) = (*buffers)->toArrayMode();
                    (*buffers)->initSkipCutBuffer(delay, padding, sampleRate, channelCount);
                }
            }
        }
    }

    mPendingFeed = 0;
    mSync.start();
    if (mInputSurface == nullptr) {
        // TODO: use proper buffer depth instead of this random value
        for (size_t i = 0; i < kMinInputBufferArraySize; ++i) {
            size_t index;
            sp<MediaCodecBuffer> buffer;
            {
                Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
                if (!(*buffers)->requestNewBuffer(&index, &buffer)) {
                    if (i == 0) {
                        ALOGE("start: cannot allocate memory at all");
                        return NO_MEMORY;
                    } else {
                        ALOGV("start: cannot allocate memory, only %zu buffers allocated", i);
                    }
                    break;
                }
            }
            if (buffer) {
                mCallback->onInputBufferAvailable(index, buffer);
            }
        }
    }
    return OK;
}

void CCodecBufferChannel::stop() {
    mSync.stop();
    mFirstValidFrameIndex = mFrameIndex.load();
    if (mInputSurface != nullptr) {
        mInputSurface->disconnect();
        mInputSurface.reset();
    }
}

void CCodecBufferChannel::flush(const std::list<std::unique_ptr<C2Work>> &flushedWork) {
    ALOGV("flush");
    {
        Mutexed<std::unique_ptr<InputBuffers>>::Locked buffers(mInputBuffers);
        (*buffers)->flush();
    }
    {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        (*buffers)->flush(flushedWork);
    }
}

void CCodecBufferChannel::onWorkDone(
        std::unique_ptr<C2Work> work, const sp<AMessage> &outputFormat,
        const C2StreamInitDataInfo::output *initData) {
    if (handleWork(std::move(work), outputFormat, initData)) {
        ALOGV("%s: pending feed +1 from %u", __func__, mPendingFeed.load());
        ++mPendingFeed;
    }
    feedInputBufferIfAvailable();
}

bool CCodecBufferChannel::handleWork(
        std::unique_ptr<C2Work> work,
        const sp<AMessage> &outputFormat,
        const C2StreamInitDataInfo::output *initData) {
    if (work->result != C2_OK) {
        if (work->result == C2_NOT_FOUND) {
            // TODO: Define what flushed work's result is.
            ALOGD("flushed work; ignored.");
            return true;
        }
        ALOGD("work failed to complete: %d", work->result);
        mCCodecCallback->onError(work->result, ACTION_CODE_FATAL);
        return false;
    }

    // NOTE: MediaCodec usage supposedly have only one worklet
    if (work->worklets.size() != 1u) {
        ALOGE("onWorkDone: incorrect number of worklets: %zu",
                work->worklets.size());
        mCCodecCallback->onError(UNKNOWN_ERROR, ACTION_CODE_FATAL);
        return false;
    }

    const std::unique_ptr<C2Worklet> &worklet = work->worklets.front();
    if ((worklet->output.ordinal.frameIndex - mFirstValidFrameIndex.load()).peek() < 0) {
        // Discard frames from previous generation.
        ALOGD("Discard frames from previous generation.");
        return true;
    }
    std::shared_ptr<C2Buffer> buffer;
    // NOTE: MediaCodec usage supposedly have only one output stream.
    if (worklet->output.buffers.size() > 1u) {
        ALOGE("onWorkDone: incorrect number of output buffers: %zu",
                worklet->output.buffers.size());
        mCCodecCallback->onError(UNKNOWN_ERROR, ACTION_CODE_FATAL);
        return false;
    } else if (worklet->output.buffers.size() == 1u) {
        buffer = worklet->output.buffers[0];
        if (!buffer) {
            ALOGW("onWorkDone: nullptr found in buffers; ignored.");
        }
    }

    if (outputFormat != nullptr) {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        ALOGD("onWorkDone: output format changed to %s",
                outputFormat->debugString().c_str());
        (*buffers)->setFormat(outputFormat);

        AString mediaType;
        if (outputFormat->findString(KEY_MIME, &mediaType)
                && mediaType == MIMETYPE_AUDIO_RAW) {
            int32_t channelCount;
            int32_t sampleRate;
            if (outputFormat->findInt32(KEY_CHANNEL_COUNT, &channelCount)
                    && outputFormat->findInt32(KEY_SAMPLE_RATE, &sampleRate)) {
                (*buffers)->updateSkipCutBuffer(sampleRate, channelCount);
            }
        }
    }

    int32_t flags = 0;
    if (worklet->output.flags & C2FrameData::FLAG_END_OF_STREAM) {
        flags |= MediaCodec::BUFFER_FLAG_EOS;
        ALOGV("onWorkDone: output EOS");
    }

    bool feedNeeded = true;
    sp<MediaCodecBuffer> outBuffer;
    size_t index;
    if (initData != nullptr) {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        if ((*buffers)->registerCsd(initData, &index, &outBuffer)) {
            outBuffer->meta()->setInt64("timeUs", worklet->output.ordinal.timestamp.peek());
            outBuffer->meta()->setInt32("flags", MediaCodec::BUFFER_FLAG_CODECCONFIG);
            ALOGV("onWorkDone: csd index = %zu", index);

            buffers.unlock();
            mCallback->onOutputBufferAvailable(index, outBuffer);
            buffers.lock();
            feedNeeded = false;
        } else {
            ALOGE("onWorkDone: unable to register csd");
            buffers.unlock();
            mCCodecCallback->onError(UNKNOWN_ERROR, ACTION_CODE_FATAL);
            buffers.lock();
            return false;
        }
    }

    if (!buffer && !flags) {
        ALOGV("onWorkDone: Not reporting output buffer (%lld)",
              work->input.ordinal.frameIndex.peekull());
        return feedNeeded;
    }

    if (buffer) {
        for (const std::shared_ptr<const C2Info> &info : buffer->info()) {
            // TODO: properly translate these to metadata
            switch (info->coreIndex().coreIndex()) {
                case C2StreamPictureTypeMaskInfo::CORE_INDEX:
                    if (((C2StreamPictureTypeMaskInfo *)info.get())->value & C2PictureTypeKeyFrame) {
                        flags |= MediaCodec::BUFFER_FLAG_SYNCFRAME;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    {
        Mutexed<std::unique_ptr<OutputBuffers>>::Locked buffers(mOutputBuffers);
        if (!(*buffers)->registerBuffer(buffer, &index, &outBuffer)) {
            ALOGE("onWorkDone: unable to register output buffer");
            // TODO
            // buffers.unlock();
            // mCCodecCallback->onError(UNKNOWN_ERROR, ACTION_CODE_FATAL);
            // buffers.lock();
            return false;
        }
    }

    outBuffer->meta()->setInt64("timeUs", worklet->output.ordinal.timestamp.peek());
    outBuffer->meta()->setInt32("flags", flags);
    ALOGV("onWorkDone: out buffer index = %zu size = %zu", index, outBuffer->size());
    mCallback->onOutputBufferAvailable(index, outBuffer);
    return false;
}

status_t CCodecBufferChannel::setSurface(const sp<Surface> &newSurface) {
    if (newSurface != nullptr) {
        newSurface->setScalingMode(NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW);
        newSurface->setMaxDequeuedBufferCount(kMinOutputBufferArraySize);
    }

//    if (newSurface == nullptr) {
//        if (*surface != nullptr) {
//            ALOGW("cannot unset a surface");
//            return INVALID_OPERATION;
//        }
//        return OK;
//    }
//
//    if (*surface == nullptr) {
//        ALOGW("component was not configured with a surface");
//        return INVALID_OPERATION;
//    }

    uint32_t generation;

    ANativeWindowBuffer *buf;
    ANativeWindow *window = newSurface.get();
    int fenceFd;
    window->dequeueBuffer(window, &buf, &fenceFd);
    sp<GraphicBuffer> gbuf = GraphicBuffer::from(buf);
    generation = gbuf->getGenerationNumber();
    window->cancelBuffer(window, buf, fenceFd);

    std::shared_ptr<Codec2Client::Configurable> outputPoolIntf;
    C2BlockPool::local_id_t outputPoolId;
    {
        Mutexed<BlockPools>::Locked pools(mBlockPools);
        outputPoolId = pools->outputPoolId;
        outputPoolIntf = pools->outputPoolIntf;
    }

    if (outputPoolIntf) {
        if (mComponent->setOutputSurface(
                outputPoolId,
                newSurface->getIGraphicBufferProducer(),
                generation) != C2_OK) {
            ALOGW("setSurface -- setOutputSurface() failed to configure "
                    "new surface to the component's output block pool.");
            return INVALID_OPERATION;
        }
    }

    {
        Mutexed<OutputSurface>::Locked output(mOutputSurface);
        output->surface = newSurface;
        output->generation = generation = gbuf->getGenerationNumber();
    }

    return OK;
}

void CCodecBufferChannel::setMetaMode(MetaMode mode) {
    mMetaMode = mode;
}

}  // namespace android
