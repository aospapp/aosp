// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

//#define LOG_NDEBUG 0
#define LOG_TAG "VideoFramePool"

#include <v4l2_codec2/components/VideoFramePool.h>

#include <stdint.h>
#include <memory>

#include <android/hardware/graphics/common/1.0/types.h>
#include <base/bind.h>
#include <base/memory/ptr_util.h>
#include <base/time/time.h>
#include <log/log.h>

#include <v4l2_codec2/components/VideoTypes.h>

using android::hardware::graphics::common::V1_0::BufferUsage;

namespace android {
namespace {
// The number of times and timeout used between subsequent calls when fetching graphic blocks.
constexpr size_t kAllocateBufferMaxRetries = 32;
constexpr size_t kFetchRetryDelayUs = 1000;
}  // namespace

// static
std::unique_ptr<VideoFramePool> VideoFramePool::Create(
        std::shared_ptr<C2BlockPool> blockPool, const media::Size& size, HalPixelFormat pixelFormat,
        bool isSecure, scoped_refptr<::base::SequencedTaskRunner> taskRunner) {
    std::unique_ptr<VideoFramePool> pool = ::base::WrapUnique(new VideoFramePool(
            std::move(blockPool), size, pixelFormat, isSecure, std::move(taskRunner)));
    if (!pool->initialize()) return nullptr;
    return pool;
}

VideoFramePool::VideoFramePool(std::shared_ptr<C2BlockPool> blockPool, const media::Size& size,
                               HalPixelFormat pixelFormat, bool isSecure,
                               scoped_refptr<::base::SequencedTaskRunner> taskRunner)
      : mBlockPool(std::move(blockPool)),
        mSize(size),
        mPixelFormat(pixelFormat),
        mMemoryUsage(isSecure ? C2MemoryUsage::READ_PROTECTED : C2MemoryUsage::CPU_READ,
                     static_cast<uint64_t>(BufferUsage::VIDEO_DECODER)),
        mClientTaskRunner(std::move(taskRunner)) {
    ALOGV("%s(size=%dx%d)", __func__, size.width(), size.height());
    ALOG_ASSERT(mClientTaskRunner->RunsTasksInCurrentSequence());
    DCHECK(mBlockPool);
    DCHECK(mClientTaskRunner);
}

bool VideoFramePool::initialize() {
    if (!mFetchThread.Start()) {
        ALOGE("Fetch thread failed to start.");
        return false;
    }
    mFetchTaskRunner = mFetchThread.task_runner();

    mClientWeakThis = mClientWeakThisFactory.GetWeakPtr();
    mFetchWeakThis = mFetchWeakThisFactory.GetWeakPtr();

    return true;
}

VideoFramePool::~VideoFramePool() {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mClientTaskRunner->RunsTasksInCurrentSequence());

    mClientWeakThisFactory.InvalidateWeakPtrs();

    if (mFetchThread.IsRunning()) {
        mFetchTaskRunner->PostTask(FROM_HERE,
                                   ::base::BindOnce(&VideoFramePool::destroyTask, mFetchWeakThis));
        mFetchThread.Stop();
    }
}

void VideoFramePool::destroyTask() {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mFetchTaskRunner->RunsTasksInCurrentSequence());

    mFetchWeakThisFactory.InvalidateWeakPtrs();
}

void VideoFramePool::getVideoFrame(GetVideoFrameCB cb) {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mClientTaskRunner->RunsTasksInCurrentSequence());

    ++mNumPendingRequests;
    mFetchTaskRunner->PostTask(FROM_HERE, ::base::BindOnce(&VideoFramePool::getVideoFrameTask,
                                                           mFetchWeakThis, std::move(cb)));
}

bool VideoFramePool::hasPendingRequests() const {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mClientTaskRunner->RunsTasksInCurrentSequence());

    return mNumPendingRequests > 0;
}

void VideoFramePool::getVideoFrameTask(GetVideoFrameCB cb) {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mFetchTaskRunner->RunsTasksInCurrentSequence());

    std::unique_ptr<VideoFrame> frame = nullptr;

    size_t numRetries = 0;
    while (numRetries < kAllocateBufferMaxRetries) {
        std::shared_ptr<C2GraphicBlock> block;
        c2_status_t err = mBlockPool->fetchGraphicBlock(mSize.width(), mSize.height(),
                                                        static_cast<uint32_t>(mPixelFormat),
                                                        mMemoryUsage, &block);

        if (err == C2_OK) {
            frame = VideoFrame::Create(std::move(block));
            break;
        } else if (err != C2_TIMED_OUT && err != C2_BLOCKING) {
            ALOGE("Failed to fetch block, err=%d, retry %zu times", err, numRetries);
            break;
        } else {
            ++numRetries;
            ALOGD("fetchGraphicBlock() timeout. retry %zu times", numRetries);
            usleep(kFetchRetryDelayUs);
        }
    }
    if (numRetries == kAllocateBufferMaxRetries) {
        ALOGE("Timeout to fetch block, retry %zu times", numRetries);
    }

    mClientTaskRunner->PostTask(
            FROM_HERE, ::base::BindOnce(&VideoFramePool::onVideoFrameReady, mClientWeakThis,
                                        std::move(cb), std::move(frame)));
}

void VideoFramePool::onVideoFrameReady(GetVideoFrameCB cb, std::unique_ptr<VideoFrame> frame) {
    ALOGV("%s()", __func__);
    ALOG_ASSERT(mClientTaskRunner->RunsTasksInCurrentSequence());

    --mNumPendingRequests;

    if (!frame) {
        ALOGE("Failed to get GraphicBlock, abandoning all pending requests.");
        mClientWeakThisFactory.InvalidateWeakPtrs();
        mClientWeakThis = mClientWeakThisFactory.GetWeakPtr();

        mNumPendingRequests = 0;
    }

    std::move(cb).Run(std::move(frame));
}

}  // namespace android
