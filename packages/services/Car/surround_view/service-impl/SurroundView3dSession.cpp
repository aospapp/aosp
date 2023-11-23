/*
 * Copyright 2020 The Android Open Source Project
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

#include "SurroundView3dSession.h"

#include <android-base/logging.h>
#include <android/hardware_buffer.h>
#include <android/hidl/memory/1.0/IMemory.h>
#include <hidlmemory/mapping.h>
#include <system/camera_metadata.h>
#include <utils/SystemClock.h>

#include <array>
#include <thread>
#include <set>

#include <android/hardware/camera/device/3.2/ICameraDevice.h>

#include "CameraUtils.h"
#include "sv_3d_params.h"

using ::android::hardware::automotive::evs::V1_0::EvsResult;
using ::android::hardware::camera::device::V3_2::Stream;
using ::android::hardware::hidl_memory;
using ::android::hidl::memory::V1_0::IMemory;

using GraphicsPixelFormat = ::android::hardware::graphics::common::V1_0::PixelFormat;

namespace android {
namespace hardware {
namespace automotive {
namespace sv {
namespace V1_0 {
namespace implementation {

typedef struct {
    int32_t id;
    int32_t width;
    int32_t height;
    int32_t format;
    int32_t direction;
    int32_t framerate;
} RawStreamConfig;

static const size_t kStreamCfgSz = sizeof(RawStreamConfig);
static const uint8_t kGrayColor = 128;
static const int kNumFrames = 4;
static const int kNumChannels = 4;

SurroundView3dSession::FramesHandler::FramesHandler(
    sp<IEvsCamera> pCamera, sp<SurroundView3dSession> pSession)
    : mCamera(pCamera),
      mSession(pSession) {}

Return<void> SurroundView3dSession::FramesHandler::deliverFrame(
    const BufferDesc_1_0& bufDesc_1_0) {
    LOG(INFO) << "Ignores a frame delivered from v1.0 EVS service.";
    mCamera->doneWithFrame(bufDesc_1_0);

    return {};
}

Return<void> SurroundView3dSession::FramesHandler::deliverFrame_1_1(
    const hidl_vec<BufferDesc_1_1>& buffers) {
    LOG(INFO) << "Received " << buffers.size() << " frames from the camera";
    mSession->mSequenceId++;

    {
        scoped_lock<mutex> lock(mSession->mAccessLock);
        if (mSession->mProcessingEvsFrames) {
            LOG(WARNING) << "EVS frames are being processed. Skip frames:"
                         << mSession->mSequenceId;
            mCamera->doneWithFrame_1_1(buffers);
            return {};
        }
    }

    if (buffers.size() != kNumFrames) {
        LOG(ERROR) << "The number of incoming frames is " << buffers.size()
                   << ", which is different from the number " << kNumFrames
                   << ", specified in config file";
        return {};
    }

    {
        scoped_lock<mutex> lock(mSession->mAccessLock);
        for (int i = 0; i < kNumFrames; i++) {
            LOG(DEBUG) << "Copying buffer No." << i
                       << " to Surround View Service";
            mSession->copyFromBufferToPointers(buffers[i],
                                               mSession->mInputPointers[i]);
        }
    }

    mCamera->doneWithFrame_1_1(buffers);

    // Notify the session that a new set of frames is ready
    {
        scoped_lock<mutex> lock(mSession->mAccessLock);
        mSession->mProcessingEvsFrames = true;
    }
    mSession->mFramesSignal.notify_all();

    return {};
}

Return<void> SurroundView3dSession::FramesHandler::notify(const EvsEventDesc& event) {
    switch(event.aType) {
        case EvsEventType::STREAM_STOPPED:
            LOG(INFO) << "Received a STREAM_STOPPED event from Evs.";

            // TODO(b/158339680): There is currently an issue in EVS reference
            // implementation that causes STREAM_STOPPED event to be delivered
            // properly. When the bug is fixed, we should deal with this event
            // properly in case the EVS stream is stopped unexpectly.
            break;

        case EvsEventType::PARAMETER_CHANGED:
            LOG(INFO) << "Camera parameter " << std::hex << event.payload[0]
                      << " is set to " << event.payload[1];
            break;

        // Below events are ignored in reference implementation.
        case EvsEventType::STREAM_STARTED:
        [[fallthrough]];
        case EvsEventType::FRAME_DROPPED:
        [[fallthrough]];
        case EvsEventType::TIMEOUT:
            LOG(INFO) << "Event " << std::hex << static_cast<unsigned>(event.aType)
                      << "is received but ignored.";
            break;
        default:
            LOG(ERROR) << "Unknown event id: " << static_cast<unsigned>(event.aType);
            break;
    }

    return {};
}

bool SurroundView3dSession::copyFromBufferToPointers(
    BufferDesc_1_1 buffer, SurroundViewInputBufferPointers pointers) {

    AHardwareBuffer_Desc* pDesc =
        reinterpret_cast<AHardwareBuffer_Desc *>(&buffer.buffer.description);

    // create a GraphicBuffer from the existing handle
    sp<GraphicBuffer> inputBuffer = new GraphicBuffer(
        buffer.buffer.nativeHandle, GraphicBuffer::CLONE_HANDLE, pDesc->width,
        pDesc->height, pDesc->format, pDesc->layers,
        GRALLOC_USAGE_HW_TEXTURE, pDesc->stride);

    if (inputBuffer == nullptr) {
        LOG(ERROR) << "Failed to allocate GraphicBuffer to wrap image handle";
        // Returning "true" in this error condition because we already released the
        // previous image (if any) and so the texture may change in unpredictable
        // ways now!
        return false;
    } else {
        LOG(INFO) << "Managed to allocate GraphicBuffer with "
                  << " width: " << pDesc->width
                  << " height: " << pDesc->height
                  << " format: " << pDesc->format
                  << " stride: " << pDesc->stride;
    }

    // Lock the input GraphicBuffer and map it to a pointer.  If we failed to
    // lock, return false.
    void* inputDataPtr;
    inputBuffer->lock(
        GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_NEVER,
        &inputDataPtr);
    if (!inputDataPtr) {
        LOG(ERROR) << "Failed to gain read access to GraphicBuffer";
        inputBuffer->unlock();
        return false;
    } else {
        LOG(INFO) << "Managed to get read access to GraphicBuffer";
    }

    int stride = pDesc->stride;

    // readPtr comes from EVS, and it is with 4 channels
    uint8_t* readPtr = static_cast<uint8_t*>(inputDataPtr);

    // writePtr is with 3 channels, since that is what SV core lib expects.
    uint8_t* writePtr = static_cast<uint8_t*>(pointers.cpu_data_pointer);

    for (int i = 0; i < pDesc->width; i++)
        for (int j = 0; j < pDesc->height; j++) {
            writePtr[(i + j * stride) * 3 + 0] =
                readPtr[(i + j * stride) * 4 + 0];
            writePtr[(i + j * stride) * 3 + 1] =
                readPtr[(i + j * stride) * 4 + 1];
            writePtr[(i + j * stride) * 3 + 2] =
                readPtr[(i + j * stride) * 4 + 2];
        }
    LOG(INFO) << "Brute force copying finished";

    return true;
}

void SurroundView3dSession::processFrames() {
    if (mSurroundView->Start3dPipeline()) {
        LOG(INFO) << "Start3dPipeline succeeded";
    } else {
        LOG(ERROR) << "Start3dPipeline failed";
        return;
    }

    while (true) {
        {
            unique_lock<mutex> lock(mAccessLock);

            if (mStreamState != RUNNING) {
                break;
            }

            mFramesSignal.wait(lock, [this]() { return mProcessingEvsFrames; });
        }

        handleFrames(mSequenceId);

        {
            // Set the boolean to false to receive the next set of frames.
            scoped_lock<mutex> lock(mAccessLock);
            mProcessingEvsFrames = false;
        }
    }

    // Notify the SV client that no new results will be delivered.
    LOG(DEBUG) << "Notify SvEvent::STREAM_STOPPED";
    mStream->notify(SvEvent::STREAM_STOPPED);

    {
        scoped_lock<mutex> lock(mAccessLock);
        mStreamState = STOPPED;
        mStream = nullptr;
        LOG(DEBUG) << "Stream marked STOPPED.";
    }
}

SurroundView3dSession::SurroundView3dSession(sp<IEvsEnumerator> pEvs,
                                             VhalHandler* vhalHandler,
                                             AnimationModule* animationModule,
                                             IOModuleConfig* pConfig) :
      mEvs(pEvs),
      mStreamState(STOPPED),
      mVhalHandler(vhalHandler),
      mAnimationModule(animationModule),
      mIOModuleConfig(pConfig) {
    mEvsCameraIds = {"0" , "1", "2", "3"};
}

SurroundView3dSession::~SurroundView3dSession() {
    // In case the client did not call stopStream properly, we should stop the
    // stream explicitly. Otherwise the process thread will take forever to
    // join.
    stopStream();

    // Waiting for the process thread to finish the buffered frames.
    mProcessThread.join();

    mEvs->closeCamera(mCamera);
}

// Methods from ::android::hardware::automotive::sv::V1_0::ISurroundViewSession.
Return<SvResult> SurroundView3dSession::startStream(
    const sp<ISurroundViewStream>& stream) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock<mutex> lock(mAccessLock);

    if (!mIsInitialized && !initialize()) {
        LOG(ERROR) << "There is an error while initializing the use case. "
                   << "Exiting";
        return SvResult::INTERNAL_ERROR;
    }

    if (mStreamState != STOPPED) {
        LOG(ERROR) << "Ignoring startVideoStream call when a stream is "
                   << "already running.";
        return SvResult::INTERNAL_ERROR;
    }

    if (mViews.empty()) {
        LOG(ERROR) << "No views have been set for current Surround View"
                   << "3d Session. Please call setViews before starting"
                   << "the stream.";
        return SvResult::VIEW_NOT_SET;
    }

    if (stream == nullptr) {
        LOG(ERROR) << "The input stream is invalid";
        return SvResult::INTERNAL_ERROR;
    }
    mStream = stream;

    mSequenceId = 0;
    startEvs();

    if (mVhalHandler != nullptr) {
        if (!mVhalHandler->startPropertiesUpdate()) {
            LOG(WARNING) << "VhalHandler cannot be started properly";
        }
    } else {
        LOG(WARNING) << "VhalHandler is null. Ignored";
    }

    // TODO(b/158131080): the STREAM_STARTED event is not implemented in EVS
    // reference implementation yet. Once implemented, this logic should be
    // moved to EVS notify callback.
    LOG(DEBUG) << "Notify SvEvent::STREAM_STARTED";
    mStream->notify(SvEvent::STREAM_STARTED);
    mProcessingEvsFrames = false;

    // Start the frame generation thread
    mStreamState = RUNNING;

    mProcessThread = thread([this]() {
        processFrames();
    });

    return SvResult::OK;
}

Return<void> SurroundView3dSession::stopStream() {
    LOG(DEBUG) << __FUNCTION__;
    unique_lock <mutex> lock(mAccessLock);

    if (mVhalHandler != nullptr) {
        mVhalHandler->stopPropertiesUpdate();
    } else {
        LOG(WARNING) << "VhalHandler is null. Ignored";
    }

    if (mStreamState == RUNNING) {
        // Tell the processFrames loop to stop processing frames
        mStreamState = STOPPING;

        // Stop the EVS stream asynchronizely
        mCamera->stopVideoStream();
    }

    return {};
}

Return<void> SurroundView3dSession::doneWithFrames(
    const SvFramesDesc& svFramesDesc){
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    mFramesRecord.inUse = false;

    (void)svFramesDesc;
    return {};
}

// Methods from ISurroundView3dSession follow.
Return<SvResult> SurroundView3dSession::setViews(
    const hidl_vec<View3d>& views) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    mViews.resize(views.size());
    for (int i=0; i<views.size(); i++) {
        mViews[i] = views[i];
    }

    return SvResult::OK;
}

Return<SvResult> SurroundView3dSession::set3dConfig(const Sv3dConfig& sv3dConfig) {
    LOG(DEBUG) << __FUNCTION__;
    scoped_lock <mutex> lock(mAccessLock);

    if (sv3dConfig.width <=0 || sv3dConfig.width > 4096) {
        LOG(WARNING) << "The width of 3d config is out of the range (0, 4096]"
                     << "Ignored!";
        return SvResult::INVALID_ARG;
    }

    if (sv3dConfig.height <=0 || sv3dConfig.height > 4096) {
        LOG(WARNING) << "The height of 3d config is out of the range (0, 4096]"
                     << "Ignored!";
        return SvResult::INVALID_ARG;
    }

    mConfig.width = sv3dConfig.width;
    mConfig.height = sv3dConfig.height;
    mConfig.carDetails = sv3dConfig.carDetails;

    if (mStream != nullptr) {
        LOG(DEBUG) << "Notify SvEvent::CONFIG_UPDATED";
        mStream->notify(SvEvent::CONFIG_UPDATED);
    }

    return SvResult::OK;
}

Return<void> SurroundView3dSession::get3dConfig(get3dConfig_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;

    _hidl_cb(mConfig);
    return {};
}

bool VerifyOverlayData(const OverlaysData& overlaysData) {
    // Check size of shared memory matches overlaysMemoryDesc.
    const int kVertexSize = 16;
    const int kIdSize = 2;
    int memDescSize = 0;
    for (auto& overlayMemDesc : overlaysData.overlaysMemoryDesc) {
        memDescSize += kIdSize + kVertexSize * overlayMemDesc.verticesCount;
    }
    if (memDescSize != overlaysData.overlaysMemory.size()) {
        LOG(ERROR) << "shared memory and overlaysMemoryDesc size mismatch.";
        return false;
    }

    // Map memory.
    sp<IMemory> pSharedMemory = mapMemory(overlaysData.overlaysMemory);
    if(pSharedMemory == nullptr) {
        LOG(ERROR) << "mapMemory failed.";
        return false;
    }

    // Get Data pointer.
    uint8_t* pData = static_cast<uint8_t*>(
        static_cast<void*>(pSharedMemory->getPointer()));
    if (pData == nullptr) {
        LOG(ERROR) << "Shared memory getPointer() failed.";
        return false;
    }

    int idOffset = 0;
    set<uint16_t> overlayIdSet;
    for (auto& overlayMemDesc : overlaysData.overlaysMemoryDesc) {

        if (overlayIdSet.find(overlayMemDesc.id) != overlayIdSet.end()) {
            LOG(ERROR) << "Duplicate id within memory descriptor.";
            return false;
        }
        overlayIdSet.insert(overlayMemDesc.id);

        if(overlayMemDesc.verticesCount < 3) {
            LOG(ERROR) << "Less than 3 vertices.";
            return false;
        }

        if (overlayMemDesc.overlayPrimitive == OverlayPrimitive::TRIANGLES &&
                overlayMemDesc.verticesCount % 3 != 0) {
            LOG(ERROR) << "Triangles primitive does not have vertices "
                       << "multiple of 3.";
            return false;
        }

        const uint16_t overlayId = *((uint16_t*)(pData + idOffset));

        if (overlayId != overlayMemDesc.id) {
            LOG(ERROR) << "Overlay id mismatch "
                       << overlayId
                       << ", "
                       << overlayMemDesc.id;
            return false;
        }

        idOffset += kIdSize + (kVertexSize * overlayMemDesc.verticesCount);
    }

    return true;
}

// TODO(b/150412555): the overlay related methods are incomplete.
Return<SvResult>  SurroundView3dSession::updateOverlays(
        const OverlaysData& overlaysData) {

    if(!VerifyOverlayData(overlaysData)) {
        LOG(ERROR) << "VerifyOverlayData failed.";
        return SvResult::INVALID_ARG;
    }

    return SvResult::OK;
}

Return<void> SurroundView3dSession::projectCameraPointsTo3dSurface(
        const hidl_vec<Point2dInt>& cameraPoints, const hidl_string& cameraId,
        projectCameraPointsTo3dSurface_cb _hidl_cb) {
    LOG(DEBUG) << __FUNCTION__;
    bool cameraIdFound = false;
    int cameraIndex = 0;
    std::vector<Point3dFloat> points3d;

    // Note: mEvsCameraIds must be in the order front, right, rear, left.
    for (auto& evsCameraId : mEvsCameraIds) {
        if (cameraId == evsCameraId) {
            cameraIdFound = true;
            LOG(DEBUG) << "Camera id found for projection: " << cameraId;
            break;
        }
        cameraIndex++;
    }

    if (!cameraIdFound) {
        LOG(ERROR) << "Camera id not found for projection: " << cameraId;
        _hidl_cb(points3d);
        return {};
    }

    for (const auto& cameraPoint : cameraPoints) {
        Point3dFloat point3d = {false, 0.0, 0.0, 0.0};

        // Verify if camera point is within the camera resolution bounds.
        point3d.isValid = (cameraPoint.x >= 0 && cameraPoint.x < mConfig.width &&
                           cameraPoint.y >= 0 && cameraPoint.y < mConfig.height);
        if (!point3d.isValid) {
            LOG(WARNING) << "Camera point (" << cameraPoint.x << ", " << cameraPoint.y
                         << ") is out of camera resolution bounds.";
            points3d.push_back(point3d);
            continue;
        }

        // Project points using mSurroundView function.
        const Coordinate2dInteger camCoord(cameraPoint.x, cameraPoint.y);
        Coordinate3dFloat projPoint3d(0.0, 0.0, 0.0);
        point3d.isValid =
                mSurroundView->GetProjectionPointFromRawCameraToSurroundView3d(camCoord,
                                                                               cameraIndex,
                                                                               &projPoint3d);
        // Convert projPoint3d in meters to point3d which is in milli-meters.
        point3d.x = projPoint3d.x * 1000.0;
        point3d.y = projPoint3d.y * 1000.0;
        point3d.z = projPoint3d.z * 1000.0;
        points3d.push_back(point3d);
    }
    _hidl_cb(points3d);
    return {};
}

bool SurroundView3dSession::handleFrames(int sequenceId) {
    LOG(INFO) << __FUNCTION__ << "Handling sequenceId " << sequenceId << ".";

    // TODO(b/157498592): Now only one sets of EVS input frames and one SV
    // output frame is supported. Implement buffer queue for both of them.
    {
        scoped_lock<mutex> lock(mAccessLock);

        if (mFramesRecord.inUse) {
            LOG(DEBUG) << "Notify SvEvent::FRAME_DROPPED";
            mStream->notify(SvEvent::FRAME_DROPPED);
            return true;
        }
    }

    // If the width/height was changed, re-allocate the data pointer.
    if (mOutputWidth != mConfig.width
        || mOutputHeight != mConfig.height) {
        LOG(DEBUG) << "Config changed. Re-allocate memory. "
                   << "Old width: "
                   << mOutputWidth
                   << ", old height: "
                   << mOutputHeight
                   << "; New width: "
                   << mConfig.width
                   << ", new height: "
                   << mConfig.height;
        delete[] static_cast<char*>(mOutputPointer.data_pointer);
        mOutputWidth = mConfig.width;
        mOutputHeight = mConfig.height;
        mOutputPointer.height = mOutputHeight;
        mOutputPointer.width = mOutputWidth;
        mOutputPointer.format = Format::RGBA;
        mOutputPointer.data_pointer =
            new char[mOutputHeight * mOutputWidth * kNumChannels];

        if (!mOutputPointer.data_pointer) {
            LOG(ERROR) << "Memory allocation failed. Exiting.";
            return false;
        }

        Size2dInteger size = Size2dInteger(mOutputWidth, mOutputHeight);
        mSurroundView->Update3dOutputResolution(size);

        mSvTexture = new GraphicBuffer(mOutputWidth,
                                       mOutputHeight,
                                       HAL_PIXEL_FORMAT_RGBA_8888,
                                       1,
                                       GRALLOC_USAGE_HW_TEXTURE,
                                       "SvTexture");
        if (mSvTexture->initCheck() == OK) {
            LOG(INFO) << "Successfully allocated Graphic Buffer";
        } else {
            LOG(ERROR) << "Failed to allocate Graphic Buffer";
            return false;
        }
    }

    // TODO(b/150412555): do not use the setViews for frames generation
    // since there is a discrepancy between the HIDL APIs and core lib APIs.
    array<array<float, 4>, 4> matrix;

    // TODO(b/150412555): use hard-coded views for now. Change view every
    // frame.
    int recViewId = sequenceId % 16;
    for (int i=0; i<4; i++)
        for (int j=0; j<4; j++) {
            matrix[i][j] = kRecViews[recViewId][i*4+j];
    }

    // Get the latest VHal property values
    if (mVhalHandler != nullptr) {
        if (!mVhalHandler->getPropertyValues(&mPropertyValues)) {
            LOG(ERROR) << "Failed to get property values";
        }
    } else {
        LOG(WARNING) << "VhalHandler is null. Ignored";
    }

    vector<AnimationParam> params;
    if (mAnimationModule != nullptr) {
        params = mAnimationModule->getUpdatedAnimationParams(mPropertyValues);
    } else {
        LOG(WARNING) << "AnimationModule is null. Ignored";
    }

    if (!params.empty()) {
        mSurroundView->SetAnimations(params);
    } else {
        LOG(INFO) << "AnimationParams is empty. Ignored";
    }

    if (mSurroundView->Get3dSurroundView(
        mInputPointers, matrix, &mOutputPointer)) {
        LOG(INFO) << "Get3dSurroundView succeeded";
    } else {
        LOG(ERROR) << "Get3dSurroundView failed. "
                   << "Using memset to initialize to gray.";
        memset(mOutputPointer.data_pointer, kGrayColor,
               mOutputHeight * mOutputWidth * kNumChannels);
    }

    void* textureDataPtr = nullptr;
    mSvTexture->lock(GRALLOC_USAGE_SW_WRITE_OFTEN
                    | GRALLOC_USAGE_SW_READ_NEVER,
                    &textureDataPtr);
    if (!textureDataPtr) {
        LOG(ERROR) << "Failed to gain write access to GraphicBuffer!";
        return false;
    }

    // Note: there is a chance that the stride of the texture is not the
    // same as the width. For example, when the input frame is 1920 * 1080,
    // the width is 1080, but the stride is 2048. So we'd better copy the
    // data line by line, instead of single memcpy.
    uint8_t* writePtr = static_cast<uint8_t*>(textureDataPtr);
    uint8_t* readPtr = static_cast<uint8_t*>(mOutputPointer.data_pointer);
    const int readStride = mOutputWidth * kNumChannels;
    const int writeStride = mSvTexture->getStride() * kNumChannels;
    if (readStride == writeStride) {
        memcpy(writePtr, readPtr, readStride * mSvTexture->getHeight());
    } else {
        for (int i=0; i<mSvTexture->getHeight(); i++) {
            memcpy(writePtr, readPtr, readStride);
            writePtr = writePtr + writeStride;
            readPtr = readPtr + readStride;
        }
    }
    LOG(INFO) << "memcpy finished!";
    mSvTexture->unlock();

    ANativeWindowBuffer* buffer = mSvTexture->getNativeBuffer();
    LOG(DEBUG) << "ANativeWindowBuffer->handle: " << buffer->handle;

    {
        scoped_lock<mutex> lock(mAccessLock);

        mFramesRecord.frames.svBuffers.resize(1);
        SvBuffer& svBuffer = mFramesRecord.frames.svBuffers[0];
        svBuffer.viewId = 0;
        svBuffer.hardwareBuffer.nativeHandle = buffer->handle;
        AHardwareBuffer_Desc* pDesc =
            reinterpret_cast<AHardwareBuffer_Desc *>(
                &svBuffer.hardwareBuffer.description);
        pDesc->width = mOutputWidth;
        pDesc->height = mOutputHeight;
        pDesc->layers = 1;
        pDesc->usage = GRALLOC_USAGE_HW_TEXTURE;
        pDesc->stride = mSvTexture->getStride();
        pDesc->format = HAL_PIXEL_FORMAT_RGBA_8888;
        mFramesRecord.frames.timestampNs = elapsedRealtimeNano();
        mFramesRecord.frames.sequenceId = sequenceId;

        mFramesRecord.inUse = true;
        mStream->receiveFrames(mFramesRecord.frames);
    }

    return true;
}

bool SurroundView3dSession::initialize() {
    lock_guard<mutex> lock(mAccessLock, adopt_lock);

    if (!setupEvs()) {
        LOG(ERROR) << "Failed to setup EVS components for 3d session";
        return false;
    }

    // TODO(b/150412555): ask core-lib team to add API description for "create"
    // method in the .h file.
    // The create method will never return a null pointer based the API
    // description.
    mSurroundView = unique_ptr<SurroundView>(Create());

    SurroundViewStaticDataParams params =
            SurroundViewStaticDataParams(
                    mCameraParams,
                    mIOModuleConfig->sv2dConfig.sv2dParams,
                    mIOModuleConfig->sv3dConfig.sv3dParams,
                    GetUndistortionScales(),
                    mIOModuleConfig->sv2dConfig.carBoundingBox,
                    mIOModuleConfig->carModelConfig.carModel.texturesMap,
                    mIOModuleConfig->carModelConfig.carModel.partsMap);
    mSurroundView->SetStaticData(params);

    mInputPointers.resize(kNumFrames);
    for (int i = 0; i < kNumFrames; i++) {
        mInputPointers[i].width = mCameraParams[i].size.width;
        mInputPointers[i].height = mCameraParams[i].size.height;
        mInputPointers[i].format = Format::RGB;
        mInputPointers[i].cpu_data_pointer =
                (void*)new uint8_t[mInputPointers[i].width *
                                   mInputPointers[i].height *
                                   kNumChannels];
    }
    LOG(INFO) << "Allocated " << kNumFrames << " input pointers";

    mOutputWidth = mIOModuleConfig->sv3dConfig.sv3dParams.resolution.width;
    mOutputHeight = mIOModuleConfig->sv3dConfig.sv3dParams.resolution.height;

    mConfig.width = mOutputWidth;
    mConfig.height = mOutputHeight;
    mConfig.carDetails = SvQuality::HIGH;

    mOutputPointer.height = mOutputHeight;
    mOutputPointer.width = mOutputWidth;
    mOutputPointer.format = Format::RGBA;
    mOutputPointer.data_pointer = new char[
        mOutputHeight * mOutputWidth * kNumChannels];

    if (!mOutputPointer.data_pointer) {
        LOG(ERROR) << "Memory allocation failed. Exiting.";
        return false;
    }

    mSvTexture = new GraphicBuffer(mOutputWidth,
                                   mOutputHeight,
                                   HAL_PIXEL_FORMAT_RGBA_8888,
                                   1,
                                   GRALLOC_USAGE_HW_TEXTURE,
                                   "SvTexture");

    if (mSvTexture->initCheck() == OK) {
        LOG(INFO) << "Successfully allocated Graphic Buffer";
    } else {
        LOG(ERROR) << "Failed to allocate Graphic Buffer";
        return false;
    }


    mIsInitialized = true;
    return true;
}

bool SurroundView3dSession::setupEvs() {
    // Reads the camera related information from the config object
    const string evsGroupId = mIOModuleConfig->cameraConfig.evsGroupId;

    // Setup for EVS
    LOG(INFO) << "Requesting camera list";
    mEvs->getCameraList_1_1(
            [this, evsGroupId] (hidl_vec<CameraDesc> cameraList) {
        LOG(INFO) << "Camera list callback received " << cameraList.size();
        for (auto&& cam : cameraList) {
            LOG(INFO) << "Found camera " << cam.v1.cameraId;
            if (cam.v1.cameraId == evsGroupId) {
                mCameraDesc = cam;
            }
        }
    });

    bool foundCfg = false;
    std::unique_ptr<Stream> targetCfg(new Stream());

    // This logic picks the configuration with the largest area that supports
    // RGBA8888 format
    int32_t maxArea = 0;
    camera_metadata_entry_t streamCfgs;
    if (!find_camera_metadata_entry(
             reinterpret_cast<camera_metadata_t *>(mCameraDesc.metadata.data()),
             ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS,
             &streamCfgs)) {
        // Stream configurations are found in metadata
        RawStreamConfig *ptr = reinterpret_cast<RawStreamConfig *>(
            streamCfgs.data.i32);
        for (unsigned idx = 0; idx < streamCfgs.count; idx += kStreamCfgSz) {
            if (ptr->direction ==
                ANDROID_SCALER_AVAILABLE_STREAM_CONFIGURATIONS_OUTPUT &&
                ptr->format == HAL_PIXEL_FORMAT_RGBA_8888) {

                if (ptr->width * ptr->height > maxArea) {
                    targetCfg->id = ptr->id;
                    targetCfg->width = ptr->width;
                    targetCfg->height = ptr->height;

                    // This client always wants below input data format
                    targetCfg->format =
                        static_cast<GraphicsPixelFormat>(
                            HAL_PIXEL_FORMAT_RGBA_8888);

                    maxArea = ptr->width * ptr->height;

                    foundCfg = true;
                }
            }
            ++ptr;
        }
    } else {
        LOG(WARNING) << "No stream configuration data is found; "
                     << "default parameters will be used.";
    }

    if (!foundCfg) {
        LOG(INFO) << "No config was found";
        targetCfg = nullptr;
        return false;
    }

    string camId = mCameraDesc.v1.cameraId.c_str();
    mCamera = mEvs->openCamera_1_1(camId.c_str(), *targetCfg);
    if (mCamera == nullptr) {
        LOG(ERROR) << "Failed to allocate EVS Camera interface for " << camId;
        return false;
    } else {
        LOG(INFO) << "Camera " << camId << " is opened successfully";
    }

    map<string, AndroidCameraParams> cameraIdToAndroidParameters;
    for (const auto& id : mIOModuleConfig->cameraConfig.evsCameraIds) {
        AndroidCameraParams params;
        if (getAndroidCameraParams(mCamera, id, params)) {
            cameraIdToAndroidParameters.emplace(id, params);
            LOG(INFO) << "Camera parameters are fetched successfully for "
                      << "physical camera: " << id;
        } else {
            LOG(ERROR) << "Failed to get camera parameters for "
                       << "physical camera: " << id;
            return false;
        }
    }

    mCameraParams =
            convertToSurroundViewCameraParams(cameraIdToAndroidParameters);

    for (auto& camera : mCameraParams) {
        camera.size.width = targetCfg->width;
        camera.size.height = targetCfg->height;
        camera.circular_fov = 179;
    }

    return true;
}

bool SurroundView3dSession::startEvs() {
    mFramesHandler = new FramesHandler(mCamera, this);
    Return<EvsResult> result = mCamera->startVideoStream(mFramesHandler);
    if (result != EvsResult::OK) {
        LOG(ERROR) << "Failed to start video stream";
        return false;
    } else {
        LOG(INFO) << "Video stream was started successfully";
    }

    return true;
}

}  // namespace implementation
}  // namespace V1_0
}  // namespace sv
}  // namespace automotive
}  // namespace hardware
}  // namespace android
