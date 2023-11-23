// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

//#define LOG_NDEBUG 0
#define LOG_TAG "C2VDAAdaptor"

#include <C2VDAAdaptor.h>

#include <bitstream_buffer.h>
#include <native_pixmap_handle.h>
#include <v4l2_device.h>
#include <v4l2_video_decode_accelerator.h>
#include <video_pixel_format.h>

#include <utils/Log.h>

namespace android {

C2VDAAdaptor::C2VDAAdaptor() : mNumOutputBuffers(0u) {}

C2VDAAdaptor::~C2VDAAdaptor() {
    if (mVDA) {
        destroy();
    }
}

VideoDecodeAcceleratorAdaptor::Result C2VDAAdaptor::initialize(
        media::VideoCodecProfile profile, bool secureMode,
        VideoDecodeAcceleratorAdaptor::Client* client) {
    // TODO: use secureMode here, or ignore?
    if (mVDA) {
        ALOGE("Re-initialize() is not allowed");
        return ILLEGAL_STATE;
    }

    media::VideoDecodeAccelerator::Config config;
    config.profile = profile;
    config.output_mode = media::VideoDecodeAccelerator::Config::OutputMode::IMPORT;

    // TODO(johnylin): may need to implement factory to create VDA if there are multiple VDA
    // implementations in the future.
    scoped_refptr<media::V4L2Device> device = media::V4L2Device::Create();
    std::unique_ptr<media::VideoDecodeAccelerator> vda(
            new media::V4L2VideoDecodeAccelerator(device));
    if (!vda->Initialize(config, this)) {
        ALOGE("Failed to initialize VDA");
        return PLATFORM_FAILURE;
    }

    mVDA = std::move(vda);
    mClient = client;

    return SUCCESS;
}

void C2VDAAdaptor::decode(int32_t bitstreamId, int ashmemFd, off_t offset, uint32_t bytesUsed) {
    CHECK(mVDA);
    ::base::SharedMemoryHandle shmHandle(::base::FileDescriptor(ashmemFd, true), 0u,
                                         ::base::UnguessableToken::Create());
    mVDA->Decode(media::BitstreamBuffer(bitstreamId, shmHandle, bytesUsed, offset));
}

void C2VDAAdaptor::assignPictureBuffers(uint32_t numOutputBuffers, const media::Size& size) {
    CHECK(mVDA);
    std::vector<media::PictureBuffer> buffers;
    for (uint32_t id = 0; id < numOutputBuffers; ++id) {
        buffers.push_back(media::PictureBuffer(static_cast<int32_t>(id), size));
    }
    mVDA->AssignPictureBuffers(buffers);
    mNumOutputBuffers = numOutputBuffers;
}

void C2VDAAdaptor::importBufferForPicture(int32_t pictureBufferId, HalPixelFormat format,
                                          std::vector<::base::ScopedFD> dmabufFds,
                                          const std::vector<VideoFramePlane>& planes) {
    CHECK(mVDA);
    CHECK_LT(pictureBufferId, static_cast<int32_t>(mNumOutputBuffers));

    media::VideoPixelFormat pixelFormat;
    switch (format) {
        case HalPixelFormat::YV12:
            pixelFormat = media::PIXEL_FORMAT_YV12;
            break;
        case HalPixelFormat::NV12:
            pixelFormat = media::PIXEL_FORMAT_NV12;
            break;
        default:
            LOG_ALWAYS_FATAL("Unsupported format: 0x%x", format);
            return;
    }

    media::NativePixmapHandle handle;
    for (auto& fd: dmabufFds)
        handle.fds.emplace_back(::base::FileDescriptor(fd.release(), true));

    for (const auto& plane : planes) {
        handle.planes.emplace_back(plane.mStride, plane.mOffset, 0, 0);
    }
    mVDA->ImportBufferForPicture(pictureBufferId, pixelFormat, handle);
}

void C2VDAAdaptor::reusePictureBuffer(int32_t pictureBufferId) {
    CHECK(mVDA);
    CHECK_LT(pictureBufferId, static_cast<int32_t>(mNumOutputBuffers));

    mVDA->ReusePictureBuffer(pictureBufferId);
}

void C2VDAAdaptor::flush() {
    CHECK(mVDA);
    mVDA->Flush();
}

void C2VDAAdaptor::reset() {
    CHECK(mVDA);
    mVDA->Reset();
}

void C2VDAAdaptor::destroy() {
    mVDA.reset(nullptr);
    mNumOutputBuffers = 0u;
}

//static
media::VideoDecodeAccelerator::SupportedProfiles C2VDAAdaptor::GetSupportedProfiles(
        InputCodec inputCodec) {
    // TODO(johnylin): use factory function to determine whether V4L2 stream or slice API is.
    uint32_t inputFormatFourcc;
    if (inputCodec == InputCodec::H264) {
        inputFormatFourcc = V4L2_PIX_FMT_H264;
    } else if (inputCodec == InputCodec::VP8) {
        inputFormatFourcc = V4L2_PIX_FMT_VP8;
    } else {  // InputCodec::VP9
        inputFormatFourcc = V4L2_PIX_FMT_VP9;
    }

    media::VideoDecodeAccelerator::SupportedProfiles supportedProfiles;
    auto allProfiles = media::V4L2VideoDecodeAccelerator::GetSupportedProfiles();
    for (const auto& profile : allProfiles) {
        if (inputFormatFourcc ==
            media::V4L2Device::VideoCodecProfileToV4L2PixFmt(profile.profile, false)) {
            supportedProfiles.push_back(profile);
        }
    }
    return supportedProfiles;
}

void C2VDAAdaptor::ProvidePictureBuffers(uint32_t requested_num_of_buffers,
                                         media::VideoPixelFormat output_format,
                                         const media::Size& dimensions) {
    // per change ag/3262504, output_format from VDA is no longer used, component side always
    // allocate graphic buffers for flexible YUV format.
    (void)output_format;

    mClient->providePictureBuffers(requested_num_of_buffers, dimensions);
    mPictureSize = dimensions;
}

void C2VDAAdaptor::DismissPictureBuffer(int32_t picture_buffer_id) {
    mClient->dismissPictureBuffer(picture_buffer_id);
}

void C2VDAAdaptor::PictureReady(const media::Picture& picture) {
    mClient->pictureReady(picture.picture_buffer_id(), picture.bitstream_buffer_id(),
                          picture.visible_rect());
}

void C2VDAAdaptor::NotifyEndOfBitstreamBuffer(int32_t bitstream_buffer_id) {
    mClient->notifyEndOfBitstreamBuffer(bitstream_buffer_id);
}

void C2VDAAdaptor::NotifyFlushDone() {
    mClient->notifyFlushDone();
}

void C2VDAAdaptor::NotifyResetDone() {
    mClient->notifyResetDone();
}

static VideoDecodeAcceleratorAdaptor::Result convertErrorCode(
        media::VideoDecodeAccelerator::Error error) {
    switch (error) {
    case media::VideoDecodeAccelerator::ILLEGAL_STATE:
        return VideoDecodeAcceleratorAdaptor::ILLEGAL_STATE;
    case media::VideoDecodeAccelerator::INVALID_ARGUMENT:
        return VideoDecodeAcceleratorAdaptor::INVALID_ARGUMENT;
    case media::VideoDecodeAccelerator::UNREADABLE_INPUT:
        return VideoDecodeAcceleratorAdaptor::UNREADABLE_INPUT;
    case media::VideoDecodeAccelerator::PLATFORM_FAILURE:
        return VideoDecodeAcceleratorAdaptor::PLATFORM_FAILURE;
    default:
        ALOGE("Unknown error code: %d", static_cast<int>(error));
        return VideoDecodeAcceleratorAdaptor::PLATFORM_FAILURE;
    }
}

void C2VDAAdaptor::NotifyError(media::VideoDecodeAccelerator::Error error) {
    mClient->notifyError(convertErrorCode(error));
}

}  // namespace android
