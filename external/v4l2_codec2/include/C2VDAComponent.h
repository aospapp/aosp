// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef ANDROID_C2_VDA_COMPONENT_H
#define ANDROID_C2_VDA_COMPONENT_H

#include <C2VDACommon.h>
#include <VideoDecodeAcceleratorAdaptor.h>

#include <rect.h>
#include <size.h>
#include <video_codecs.h>
#include <video_decode_accelerator.h>

#include <C2Component.h>
#include <C2Config.h>
#include <C2Enum.h>
#include <C2Param.h>
#include <C2ParamDef.h>
#include <SimpleC2Interface.h>
#include <util/C2InterfaceHelper.h>

#include <base/macros.h>
#include <base/memory/ref_counted.h>
#include <base/single_thread_task_runner.h>
#include <base/synchronization/waitable_event.h>
#include <base/threading/thread.h>

#include <atomic>
#include <deque>
#include <map>
#include <mutex>
#include <queue>
#include <unordered_map>

namespace android {

class C2VDAComponent : public C2Component,
                       public VideoDecodeAcceleratorAdaptor::Client,
                       public std::enable_shared_from_this<C2VDAComponent> {
public:
    class IntfImpl : public C2InterfaceHelper {
    public:
        IntfImpl(C2String name, const std::shared_ptr<C2ReflectorHelper>& helper);

        // interfaces for C2VDAComponent
        c2_status_t status() const { return mInitStatus; }
        media::VideoCodecProfile getCodecProfile() const { return mCodecProfile; }
        C2BlockPool::local_id_t getBlockPoolId() const { return mOutputBlockPoolIds->m.values[0]; }
        InputCodec getInputCodec() const { return mInputCodec; }

    private:
        // Configurable parameter setters.
        static C2R ProfileLevelSetter(bool mayBlock, C2P<C2StreamProfileLevelInfo::input>& info);

        static C2R SizeSetter(bool mayBlock, C2P<C2StreamPictureSizeInfo::output>& videoSize);

        template <typename T>
        static C2R DefaultColorAspectsSetter(bool mayBlock, C2P<T>& def);

        static C2R MergedColorAspectsSetter(bool mayBlock,
                                            C2P<C2StreamColorAspectsInfo::output>& merged,
                                            const C2P<C2StreamColorAspectsTuning::output>& def,
                                            const C2P<C2StreamColorAspectsInfo::input>& coded);

        // The input format kind; should be C2FormatCompressed.
        std::shared_ptr<C2StreamBufferTypeSetting::input> mInputFormat;
        // The output format kind; should be C2FormatVideo.
        std::shared_ptr<C2StreamBufferTypeSetting::output> mOutputFormat;
        // The MIME type of input port.
        std::shared_ptr<C2PortMediaTypeSetting::input> mInputMediaType;
        // The MIME type of output port; should be MEDIA_MIMETYPE_VIDEO_RAW.
        std::shared_ptr<C2PortMediaTypeSetting::output> mOutputMediaType;
        // The input codec profile and level. For now configuring this parameter is useless since
        // the component always uses fixed codec profile to initialize accelerator. It is only used
        // for the client to query supported profile and level values.
        // TODO: use configured profile/level to initialize accelerator.
        std::shared_ptr<C2StreamProfileLevelInfo::input> mProfileLevel;
        // Decoded video size for output.
        std::shared_ptr<C2StreamPictureSizeInfo::output> mSize;
        // Maximum size of one input buffer.
        std::shared_ptr<C2StreamMaxBufferSizeInfo::input> mMaxInputSize;
        // The suggested usage of input buffer allocator ID.
        std::shared_ptr<C2PortAllocatorsTuning::input> mInputAllocatorIds;
        // The suggested usage of output buffer allocator ID.
        std::shared_ptr<C2PortAllocatorsTuning::output> mOutputAllocatorIds;
        // The suggested usage of output buffer allocator ID with surface.
        std::shared_ptr<C2PortSurfaceAllocatorTuning::output> mOutputSurfaceAllocatorId;
        // Compnent uses this ID to fetch corresponding output block pool from platform.
        std::shared_ptr<C2PortBlockPoolsTuning::output> mOutputBlockPoolIds;
        // The color aspects parsed from input bitstream. This parameter should be configured by
        // component while decoding.
        std::shared_ptr<C2StreamColorAspectsInfo::input> mCodedColorAspects;
        // The default color aspects specified by requested output format. This parameter should be
        // configured by client.
        std::shared_ptr<C2StreamColorAspectsTuning::output> mDefaultColorAspects;
        // The combined color aspects by |mCodedColorAspects| and |mDefaultColorAspects|, and the
        // former has higher priority. This parameter is used for component to provide color aspects
        // as C2Info in decoded output buffers.
        std::shared_ptr<C2StreamColorAspectsInfo::output> mColorAspects;

        c2_status_t mInitStatus;
        media::VideoCodecProfile mCodecProfile;
        InputCodec mInputCodec;
    };

    C2VDAComponent(C2String name, c2_node_id_t id,
                   const std::shared_ptr<C2ReflectorHelper>& helper);
    virtual ~C2VDAComponent() override;

    // Implementation of C2Component interface
    virtual c2_status_t setListener_vb(const std::shared_ptr<Listener>& listener,
                                       c2_blocking_t mayBlock) override;
    virtual c2_status_t queue_nb(std::list<std::unique_ptr<C2Work>>* const items) override;
    virtual c2_status_t announce_nb(const std::vector<C2WorkOutline>& items) override;
    virtual c2_status_t flush_sm(flush_mode_t mode,
                                 std::list<std::unique_ptr<C2Work>>* const flushedWork) override;
    virtual c2_status_t drain_nb(drain_mode_t mode) override;
    virtual c2_status_t start() override;
    virtual c2_status_t stop() override;
    virtual c2_status_t reset() override;
    virtual c2_status_t release() override;
    virtual std::shared_ptr<C2ComponentInterface> intf() override;

    // Implementation of VideDecodeAcceleratorAdaptor::Client interface
    virtual void providePictureBuffers(uint32_t minNumBuffers,
                                       const media::Size& codedSize) override;
    virtual void dismissPictureBuffer(int32_t pictureBufferId) override;
    virtual void pictureReady(int32_t pictureBufferId, int32_t bitstreamId,
                              const media::Rect& cropRect) override;
    virtual void notifyEndOfBitstreamBuffer(int32_t bitstreamId) override;
    virtual void notifyFlushDone() override;
    virtual void notifyResetDone() override;
    virtual void notifyError(VideoDecodeAcceleratorAdaptor::Result error) override;

private:
    // The state machine enumeration on parent thread.
    enum class State : int32_t {
        // The initial state of component. State will change to LOADED after the component is
        // created.
        UNLOADED,
        // The component is stopped. State will change to RUNNING when start() is called by
        // framework.
        LOADED,
        // The component is running, State will change to LOADED when stop() or reset() is called by
        // framework.
        RUNNING,
        // The component is in error state.
        ERROR,
    };
    // The state machine enumeration on component thread.
    enum class ComponentState : int32_t {
        // This is the initial state until VDA initialization returns successfully.
        UNINITIALIZED,
        // VDA initialization returns successfully. VDA is ready to make progress.
        STARTED,
        // onDrain() is called. VDA is draining. Component will hold on queueing works until
        // onDrainDone().
        DRAINING,
        // onFlush() is called. VDA is flushing. State will change to STARTED after onFlushDone().
        FLUSHING,
        // onStop() is called. VDA is shutting down. State will change to UNINITIALIZED after
        // onStopDone().
        STOPPING,
        // onError() is called.
        ERROR,
    };

    // This constant is used to tell apart from drain_mode_t enumerations in C2Component.h, which
    // means no drain request.
    // Note: this value must be different than all enumerations in drain_mode_t.
    static constexpr uint32_t NO_DRAIN = ~0u;

    // Internal struct for work queue.
    struct WorkEntry {
        std::unique_ptr<C2Work> mWork;
        uint32_t mDrainMode = NO_DRAIN;
    };

    // Internal struct to keep the information of a specific graphic block.
    struct GraphicBlockInfo {
        enum class State {
            OWNED_BY_COMPONENT,    // Owned by this component.
            OWNED_BY_ACCELERATOR,  // Owned by video decode accelerator.
            OWNED_BY_CLIENT,       // Owned by client.
        };

        // The ID of this block used for accelerator.
        int32_t mBlockId = -1;
        // The ID of this block used in block pool. It indicates slot index for bufferqueue-backed
        // block pool, and buffer ID of BufferPoolData for bufferpool block pool.
        uint32_t mPoolId = 0;
        State mState = State::OWNED_BY_COMPONENT;
        // Graphic block buffer allocated from allocator. The graphic block should be owned until
        // it is passed to client.
        std::shared_ptr<C2GraphicBlock> mGraphicBlock;
        // HAL pixel format used while importing to VDA.
        HalPixelFormat mPixelFormat;
        // The handle dupped from graphic block for importing to VDA.
        ::base::ScopedFD mHandle;
        // VideoFramePlane information for importing to VDA.
        std::vector<VideoFramePlane> mPlanes;
    };

    struct VideoFormat {
        HalPixelFormat mPixelFormat = HalPixelFormat::UNKNOWN;
        uint32_t mMinNumBuffers = 0;
        media::Size mCodedSize;
        media::Rect mVisibleRect;

        VideoFormat() {}
        VideoFormat(HalPixelFormat pixelFormat, uint32_t minNumBuffers, media::Size codedSize,
                    media::Rect visibleRect);
    };

    // Internal struct for the information of output buffer returned from the accelerator.
    struct OutputBufferInfo {
        int32_t mBitstreamId;
        int32_t mBlockId;
    };

    // These tasks should be run on the component thread |mThread|.
    void onDestroy();
    void onStart(media::VideoCodecProfile profile, ::base::WaitableEvent* done);
    void onQueueWork(std::unique_ptr<C2Work> work);
    void onDequeueWork();
    void onInputBufferDone(int32_t bitstreamId);
    void onOutputBufferDone(int32_t pictureBufferId, int32_t bitstreamId);
    void onDrain(uint32_t drainMode);
    void onDrainDone();
    void onFlush();
    void onStop(::base::WaitableEvent* done);
    void onResetDone();
    void onFlushDone();
    void onStopDone();
    void onOutputFormatChanged(std::unique_ptr<VideoFormat> format);
    void onVisibleRectChanged(const media::Rect& cropRect);
    void onOutputBufferReturned(std::shared_ptr<C2GraphicBlock> block, uint32_t poolId);
    void onSurfaceChanged();

    // Send input buffer to accelerator with specified bitstream id.
    void sendInputBufferToAccelerator(const C2ConstLinearBlock& input, int32_t bitstreamId);
    // Send output buffer to accelerator. If |passToAccelerator|, change the ownership to
    // OWNED_BY_ACCELERATOR of this buffer.
    void sendOutputBufferToAccelerator(GraphicBlockInfo* info, bool passToAccelerator);
    // Set crop rectangle infomation to output format.
    void setOutputFormatCrop(const media::Rect& cropRect);
    // Helper function to get the specified GraphicBlockInfo object by its id.
    GraphicBlockInfo* getGraphicBlockById(int32_t blockId);
    // Helper function to get the specified GraphicBlockInfo object by its pool id.
    GraphicBlockInfo* getGraphicBlockByPoolId(uint32_t poolId);
    // Helper function to find the work iterator in |mPendingWorks| by bitstream id.
    std::deque<std::unique_ptr<C2Work>>::iterator findPendingWorkByBitstreamId(int32_t bitstreamId);
    // Helper function to get the specified work in |mPendingWorks| by bitstream id.
    C2Work* getPendingWorkByBitstreamId(int32_t bitstreamId);
    // Try to apply the output format change.
    void tryChangeOutputFormat();
    // Allocate output buffers (graphic blocks) from block allocator.
    c2_status_t allocateBuffersFromBlockAllocator(const media::Size& size, uint32_t pixelFormat);
    // Append allocated buffer (graphic block) to |mGraphicBlocks|.
    void appendOutputBuffer(std::shared_ptr<C2GraphicBlock> block, uint32_t poolId);
    // Append allocated buffer (graphic block) to |mGraphicBlocks| in secure mode.
    void appendSecureOutputBuffer(std::shared_ptr<C2GraphicBlock> block, uint32_t poolId);
    // Parse coded color aspects from bitstream and configs parameter if applicable.
    bool parseCodedColorAspects(const C2ConstLinearBlock& input);
    // Update color aspects for current output buffer.
    c2_status_t updateColorAspects();
    // Dequeue |mPendingBuffersToWork| to put output buffer to corresponding work and report if
    // finished as many as possible. If |dropIfUnavailable|, drop all pending existing frames
    // without blocking.
    void sendOutputBufferToWorkIfAny(bool dropIfUnavailable);
    // Update |mUndequeuedBlockIds| FIFO by pushing |blockId|.
    void updateUndequeuedBlockIds(int32_t blockId);

    // Check if the corresponding work is finished by |bitstreamId|. If yes, make onWorkDone call to
    // listener and erase the work from |mPendingWorks|.
    void reportWorkIfFinished(int32_t bitstreamId);
    // Make onWorkDone call to listener for reporting EOS work in |mPendingWorks|.
    void reportEOSWork();
    // Abandon all works in |mPendingWorks| and |mAbandonedWorks|.
    void reportAbandonedWorks();
    // Make onError call to listener for reporting errors.
    void reportError(c2_status_t error);
    // Helper function to determine if the work is finished.
    bool isWorkDone(const C2Work* work) const;

    // Start dequeue thread, return true on success. If |resetBuffersInClient|, reset the counter
    // |mBuffersInClient| on start.
    bool startDequeueThread(const media::Size& size, uint32_t pixelFormat,
                            std::shared_ptr<C2BlockPool> blockPool, bool resetBuffersInClient);
    // Stop dequeue thread.
    void stopDequeueThread();
    // The rountine task running on dequeue thread.
    void dequeueThreadLoop(const media::Size& size, uint32_t pixelFormat,
                           std::shared_ptr<C2BlockPool> blockPool);

    // The pointer of component interface implementation.
    std::shared_ptr<IntfImpl> mIntfImpl;
    // The pointer of component interface.
    const std::shared_ptr<C2ComponentInterface> mIntf;
    // The pointer of component listener.
    std::shared_ptr<Listener> mListener;

    // The main component thread.
    ::base::Thread mThread;
    // The task runner on component thread.
    scoped_refptr<::base::SingleThreadTaskRunner> mTaskRunner;

    // The dequeue buffer loop thread.
    ::base::Thread mDequeueThread;
    // The stop signal for dequeue loop which should be atomic (toggled by main thread).
    std::atomic<bool> mDequeueLoopStop;
    // The count of buffers owned by client which should be atomic.
    std::atomic<uint32_t> mBuffersInClient;

    // The following members should be utilized on component thread |mThread|.

    // The initialization result retrieved from VDA.
    VideoDecodeAcceleratorAdaptor::Result mVDAInitResult;
    // The pointer of VideoDecodeAcceleratorAdaptor.
    std::unique_ptr<VideoDecodeAcceleratorAdaptor> mVDAAdaptor;
    // The done event pointer of stop procedure. It should be restored in onStop() and signaled in
    // onStopDone().
    ::base::WaitableEvent* mStopDoneEvent;
    // The state machine on component thread.
    ComponentState mComponentState;
    // The indicator of draining with EOS. This should be always set along with component going to
    // DRAINING state, and will be unset either after reportEOSWork() (EOS is outputted), or
    // reportAbandonedWorks() (drain is cancelled and works are abandoned).
    bool mPendingOutputEOS;
    // The vector of storing allocated output graphic block information.
    std::vector<GraphicBlockInfo> mGraphicBlocks;
    // The work queue. Works are queued along with drain mode from component API queue_nb and
    // dequeued by the decode process of component.
    std::queue<WorkEntry> mQueue;
    // Store all pending works. The dequeued works are placed here until they are finished and then
    // sent out by onWorkDone call to listener.
    std::deque<std::unique_ptr<C2Work>> mPendingWorks;
    // Store all abandoned works. When component gets flushed/stopped, remaining works in queue are
    // dumped here and sent out by onWorkDone call to listener after flush/stop is finished.
    std::vector<std::unique_ptr<C2Work>> mAbandonedWorks;
    // Store the visible rect provided from VDA. If this is changed, component should issue a
    // visible size change event.
    media::Rect mRequestedVisibleRect;
    // The current output format.
    VideoFormat mOutputFormat;
    // The pending output format. We need to wait until all buffers are returned back to apply the
    // format change.
    std::unique_ptr<VideoFormat> mPendingOutputFormat;
    // The color aspects parameter for current decoded output buffers.
    std::shared_ptr<C2StreamColorAspectsInfo::output> mCurrentColorAspects;
    // The flag of pending color aspects change. This should be set once we have parsed color
    // aspects from bitstream by parseCodedColorAspects(), at the same time recorded input frame
    // index into |mPendingColorAspectsChangeFrameIndex|.
    // When this flag is true and the corresponding frame index is not less than
    // |mPendingColorAspectsChangeFrameIndex| for the output buffer in onOutputBufferDone(), update
    // |mCurrentColorAspects| from component interface and reset the flag.
    bool mPendingColorAspectsChange;
    // The record of frame index to update color aspects. Details as above.
    uint64_t mPendingColorAspectsChangeFrameIndex;
    // The record of bitstream and block ID of pending output buffers returned from accelerator.
    std::deque<OutputBufferInfo> mPendingBuffersToWork;
    // A FIFO queue to record the block IDs which are currently undequequed for display. The size
    // of this queue will be equal to the minimum number of undequeued buffers.
    std::deque<int32_t> mUndequeuedBlockIds;

    // The indicator of whether component is in secure mode.
    bool mSecureMode;

    // The following members should be utilized on parent thread.

    // The input codec profile which is configured in component interface.
    media::VideoCodecProfile mCodecProfile;
    // The state machine on parent thread which should be atomic.
    std::atomic<State> mState;
    // The mutex lock to synchronize start/stop/reset/release calls.
    std::mutex mStartStopLock;

    // The WeakPtrFactory for getting weak pointer of this.
    ::base::WeakPtrFactory<C2VDAComponent> mWeakThisFactory;

    DISALLOW_COPY_AND_ASSIGN(C2VDAComponent);
};

}  // namespace android

#endif  // ANDROID_C2_VDA_COMPONENT_H
