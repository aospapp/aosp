// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_FRAME_POOL_H
#define ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_FRAME_POOL_H

#include <memory>
#include <queue>

#include <C2Buffer.h>
#include <base/callback.h>
#include <base/memory/weak_ptr.h>
#include <base/sequenced_task_runner.h>
#include <base/threading/thread.h>

#include <size.h>
#include <v4l2_codec2/components/VideoFrame.h>
#include <v4l2_codec2/components/VideoTypes.h>

namespace android {

// Fetch C2GraphicBlock from C2BlockPool and wrap to VideoFrame.
// Provide asynchronous call which avoid the caller busy-polling while
// C2BlockPool::fetchGraphicBlock() times out.
class VideoFramePool {
public:
    using GetVideoFrameCB = base::OnceCallback<void(std::unique_ptr<VideoFrame>)>;

    static std::unique_ptr<VideoFramePool> Create(
            std::shared_ptr<C2BlockPool> blockPool, const media::Size& size,
            HalPixelFormat pixelFormat, bool isSecure,
            scoped_refptr<::base::SequencedTaskRunner> taskRunner);
    ~VideoFramePool();

    // Get a VideoFrame instance, which will be passed via |cb|.
    // If any error occurs, then pass nullptr.
    void getVideoFrame(GetVideoFrameCB cb);

    // Return true if any callback of getting VideoFrame instance is pending.
    bool hasPendingRequests() const;

private:
    // |blockPool| is the C2BlockPool that we fetch graphic blocks from.
    // |size| is the resolution size of the required graphic blocks.
    // |pixelFormat| is the pixel format of the required graphic blocks.
    // |isSecure| indicates the video stream is encrypted or not.
    // All public methods and the callbacks should be run on |taskRunner|.
    VideoFramePool(std::shared_ptr<C2BlockPool> blockPool, const media::Size& size,
                   HalPixelFormat pixelFormat, bool isSecure,
                   scoped_refptr<::base::SequencedTaskRunner> taskRunner);
    bool initialize();
    void destroyTask();

    void getVideoFrameTask(GetVideoFrameCB cb);
    void onVideoFrameReady(GetVideoFrameCB cb, std::unique_ptr<VideoFrame> frame);

    std::shared_ptr<C2BlockPool> mBlockPool;
    const media::Size mSize;
    const HalPixelFormat mPixelFormat;
    const C2MemoryUsage mMemoryUsage;

    size_t mNumPendingRequests = 0;

    scoped_refptr<::base::SequencedTaskRunner> mClientTaskRunner;
    ::base::Thread mFetchThread{"VideoFramePoolFetchThread"};
    scoped_refptr<::base::SequencedTaskRunner> mFetchTaskRunner;

    ::base::WeakPtr<VideoFramePool> mClientWeakThis;
    ::base::WeakPtr<VideoFramePool> mFetchWeakThis;
    ::base::WeakPtrFactory<VideoFramePool> mClientWeakThisFactory{this};
    ::base::WeakPtrFactory<VideoFramePool> mFetchWeakThisFactory{this};
};

}  // namespace android

#endif  // ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_FRAME_POOL_H
