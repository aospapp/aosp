// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_DECODER_H
#define ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_DECODER_H

#include <stdint.h>
#include <memory>

#include <base/callback.h>
#include <base/files/scoped_file.h>

#include <v4l2_codec2/components/VideoFrame.h>
#include <v4l2_codec2/components/VideoFramePool.h>
#include <v4l2_codec2/components/VideoTypes.h>

namespace android {

class VideoDecoder {
public:
    enum class DecodeStatus {
        kOk = 0,   // Everything went as planned.
        kAborted,  // Read aborted due to Flush() during pending read.
        kError,    // Decoder returned decode error.
    };
    static const char* DecodeStatusToString(DecodeStatus status);

    struct BitstreamBuffer {
        BitstreamBuffer(const int32_t id, base::ScopedFD dmabuf_fd, const size_t offset,
                        const size_t size)
              : id(id), dmabuf_fd(std::move(dmabuf_fd)), offset(offset), size(size) {}
        ~BitstreamBuffer() = default;

        const int32_t id;
        base::ScopedFD dmabuf_fd;
        const size_t offset;
        const size_t size;
    };

    using GetPoolCB =
            base::RepeatingCallback<void(std::unique_ptr<VideoFramePool>*, const media::Size& size,
                                         HalPixelFormat pixelFormat, size_t numOutputBuffers)>;
    using DecodeCB = base::OnceCallback<void(DecodeStatus)>;
    using OutputCB = base::RepeatingCallback<void(std::unique_ptr<VideoFrame>)>;
    using ErrorCB = base::RepeatingCallback<void()>;

    virtual ~VideoDecoder();

    virtual void decode(std::unique_ptr<BitstreamBuffer> buffer, DecodeCB decodeCb) = 0;
    virtual void drain(DecodeCB drainCb) = 0;
    virtual void flush() = 0;
};

}  // namespace android

#endif  // ANDROID_V4L2_CODEC2_COMPONENTS_VIDEO_DECODER_H
