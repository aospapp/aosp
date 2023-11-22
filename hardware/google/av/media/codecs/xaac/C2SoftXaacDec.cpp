/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define LOG_NDEBUG 0
#define LOG_TAG "C2SoftXaacDec"
#include <log/log.h>

#include <inttypes.h>

#include <cutils/properties.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/MediaDefs.h>
#include <media/stagefright/foundation/hexdump.h>

#include <C2PlatformSupport.h>
#include <SimpleC2Interface.h>

#include "C2SoftXaacDec.h"

#define DRC_DEFAULT_MOBILE_REF_LEVEL 64   /* 64*-0.25dB = -16 dB below full scale for mobile conf */
#define DRC_DEFAULT_MOBILE_DRC_CUT   127  /* maximum compression of dynamic range for mobile conf */
#define DRC_DEFAULT_MOBILE_DRC_BOOST 127  /* maximum compression of dynamic range for mobile conf */
#define DRC_DEFAULT_MOBILE_DRC_HEAVY 1    /* switch for heavy compression for mobile conf */
#define DRC_DEFAULT_MOBILE_ENC_LEVEL (-1) /* encoder target level; -1 => the value is unknown, otherwise dB step value (e.g. 64 for -16 dB) */

#define PROP_DRC_OVERRIDE_REF_LEVEL  "aac_drc_reference_level"
#define PROP_DRC_OVERRIDE_CUT        "aac_drc_cut"
#define PROP_DRC_OVERRIDE_BOOST      "aac_drc_boost"
#define PROP_DRC_OVERRIDE_HEAVY      "aac_drc_heavy"
#define PROP_DRC_OVERRIDE_ENC_LEVEL  "aac_drc_enc_target_level"

#define RETURN_IF_NE(returned, expected, retval, str)                  \
    if (returned != expected) {                                        \
        ALOGE("Error in %s: Returned: %d Expected: %d", str, returned, \
              expected);                                               \
        return retval;                                                 \
    }

namespace android {

constexpr char COMPONENT_NAME[] = "c2.android.xaac.decoder";

class C2SoftXaacDec::IntfImpl : public C2InterfaceHelper {
public:
    explicit IntfImpl(const std::shared_ptr<C2ReflectorHelper> &helper)
        : C2InterfaceHelper(helper) {

        setDerivedInstance(this);

        addParameter(
                DefineParam(mInputFormat, C2_NAME_INPUT_STREAM_FORMAT_SETTING)
                .withConstValue(new C2StreamFormatConfig::input(0u, C2FormatCompressed))
                .build());

        addParameter(
                DefineParam(mOutputFormat, C2_NAME_OUTPUT_STREAM_FORMAT_SETTING)
                .withConstValue(new C2StreamFormatConfig::output(0u, C2FormatAudio))
                .build());

        addParameter(
                DefineParam(mInputMediaType, C2_NAME_INPUT_PORT_MIME_SETTING)
                .withConstValue(AllocSharedString<C2PortMimeConfig::input>(
                        MEDIA_MIMETYPE_AUDIO_AAC))
                .build());

        addParameter(
                DefineParam(mOutputMediaType, C2_NAME_OUTPUT_PORT_MIME_SETTING)
                .withConstValue(AllocSharedString<C2PortMimeConfig::output>(
                        MEDIA_MIMETYPE_AUDIO_RAW))
                .build());

        addParameter(
                DefineParam(mSampleRate, C2_NAME_STREAM_SAMPLE_RATE_SETTING)
                .withDefault(new C2StreamSampleRateInfo::output(0u, 44100))
                .withFields({C2F(mSampleRate, value).oneOf({
                    7350, 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000
                })})
                .withSetter((Setter<decltype(*mSampleRate)>::StrictValueWithNoDeps))
                .build());

        addParameter(
                DefineParam(mChannelCount, C2_NAME_STREAM_CHANNEL_COUNT_SETTING)
                .withDefault(new C2StreamChannelCountInfo::output(0u, 1))
                .withFields({C2F(mChannelCount, value).inRange(1, 8)})
                .withSetter(Setter<decltype(*mChannelCount)>::StrictValueWithNoDeps)
                .build());

        addParameter(
                DefineParam(mBitrate, C2_NAME_STREAM_BITRATE_SETTING)
                .withDefault(new C2BitrateTuning::input(0u, 64000))
                .withFields({C2F(mBitrate, value).inRange(8000, 960000)})
                .withSetter(Setter<decltype(*mBitrate)>::NonStrictValueWithNoDeps)
                .build());

        addParameter(
                DefineParam(mAacFormat, C2_NAME_STREAM_AAC_FORMAT_SETTING)
                .withDefault(new C2StreamAacFormatInfo::input(0u, C2AacStreamFormatRaw))
                .withFields({C2F(mAacFormat, value).oneOf({
                    C2AacStreamFormatRaw, C2AacStreamFormatAdts
                })})
                .withSetter(Setter<decltype(*mAacFormat)>::StrictValueWithNoDeps)
                .build());
    }

    bool isAdts() const { return mAacFormat->value == C2AacStreamFormatAdts; }
    uint32_t getBitrate() const { return mBitrate->value; }

private:
    std::shared_ptr<C2StreamFormatConfig::input> mInputFormat;
    std::shared_ptr<C2StreamFormatConfig::output> mOutputFormat;
    std::shared_ptr<C2PortMimeConfig::input> mInputMediaType;
    std::shared_ptr<C2PortMimeConfig::output> mOutputMediaType;
    std::shared_ptr<C2StreamSampleRateInfo::output> mSampleRate;
    std::shared_ptr<C2StreamChannelCountInfo::output> mChannelCount;
    std::shared_ptr<C2BitrateTuning::input> mBitrate;

    std::shared_ptr<C2StreamAacFormatInfo::input> mAacFormat;
};

C2SoftXaacDec::C2SoftXaacDec(
        const char* name,
        c2_node_id_t id,
        const std::shared_ptr<IntfImpl> &intfImpl)
    : SimpleC2Component(std::make_shared<SimpleInterface<IntfImpl>>(name, id, intfImpl)),
        mIntf(intfImpl),
        mXheaacCodecHandle(nullptr),
        mMemoryArray{nullptr},
        mOutputDrainBuffer(nullptr) {
}

C2SoftXaacDec::~C2SoftXaacDec() {
    onRelease();
}

c2_status_t C2SoftXaacDec::onInit() {
    mInputBuffer = nullptr;
    mOutputBuffer = nullptr;
    mSampFreq = 0;
    mNumChannels = 0;
    mPcmWdSz = 0;
    mChannelMask = 0;
    mNumOutBytes = 0;
    mCurFrameIndex = 0;
    mCurTimestamp = 0;
    mIsCodecInitialized = false;
    mIsCodecConfigFlushRequired = false;
    mSignalledOutputEos = false;
    mSignalledError = false;
    mOutputDrainBufferWritePos = 0;

    status_t err = initDecoder();
    return err == OK ? C2_OK : C2_CORRUPTED;
}

c2_status_t C2SoftXaacDec::onStop() {
    drainDecoder();
    // reset the "configured" state
    mInputBuffer = nullptr;
    mOutputBuffer = nullptr;
    mSampFreq = 0;
    mNumChannels = 0;
    mPcmWdSz = 0;
    mChannelMask = 0;
    mNumOutBytes = 0;
    mCurFrameIndex = 0;
    mCurTimestamp = 0;
    mIsCodecInitialized = false;
    mIsCodecConfigFlushRequired = false;
    for (int i = 0; i < MAX_MEM_ALLOCS; i++) mMemoryArray[i] = nullptr;
    mSignalledOutputEos = false;
    mSignalledError = false;
    mOutputDrainBufferWritePos = 0;

    return C2_OK;
}

void C2SoftXaacDec::onReset() {
    (void)onStop();
}

void C2SoftXaacDec::onRelease() {
    int errCode = deInitXAACDecoder();
    if (0 != errCode) ALOGE("deInitXAACDecoder() failed %d", errCode);

    if (mOutputDrainBuffer) {
        delete[] mOutputDrainBuffer;
        mOutputDrainBuffer = nullptr;
    }
}

status_t C2SoftXaacDec::initDecoder() {
    ALOGV("initDecoder()");
    status_t status = UNKNOWN_ERROR;
    IA_ERRORCODE err_code = IA_NO_ERROR;
    int loop = 0;

    err_code = initXAACDecoder();
    if (err_code != IA_NO_ERROR) {
        if (NULL == mXheaacCodecHandle) {
            ALOGE("AAC decoder handle is null");
        }

        int temp_loop = 0;
        for (loop = 0; loop < mMallocCount; loop++) {
            if (mMemoryArray[loop]) {
                free(mMemoryArray[loop]);
            } else if (!temp_loop) {
                temp_loop = loop;
            }
        }
        if (temp_loop > 0) {
            ALOGE(" memory allocation error %d\n", temp_loop);
        }
        ALOGE("initXAACDecoder Failed");

        mMallocCount = 0;
        return status;
    } else {
        status = OK;
    }

    mOutputDrainBuffer = new short[kOutputDrainBufferSize];

    initXAACDrc();

    return status;
}

static void fillEmptyWork(const std::unique_ptr<C2Work>& work) {
    uint32_t flags = 0;
    if (work->input.flags & C2FrameData::FLAG_END_OF_STREAM) {
        flags |= C2FrameData::FLAG_END_OF_STREAM;
        ALOGV("signalling eos");
    }
    work->worklets.front()->output.flags = (C2FrameData::flags_t)flags;
    work->worklets.front()->output.buffers.clear();
    work->worklets.front()->output.ordinal = work->input.ordinal;
    work->workletsProcessed = 1u;
}

void C2SoftXaacDec::finishWork(const std::unique_ptr<C2Work>& work,
                            const std::shared_ptr<C2BlockPool>& pool) {
    ALOGV("mCurFrameIndex = %" PRIu64, mCurFrameIndex);

    std::shared_ptr<C2LinearBlock> block;
    C2MemoryUsage usage = {C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE};
    // TODO: error handling, proper usage, etc.
    c2_status_t err =
        pool->fetchLinearBlock(mOutputDrainBufferWritePos, usage, &block);
    if (err != C2_OK) ALOGE("err = %d", err);
    C2WriteView wView = block->map().get();
    int16_t* outBuffer = reinterpret_cast<int16_t*>(wView.data());
    memcpy(outBuffer, mOutputDrainBuffer, mOutputDrainBufferWritePos);
    mOutputDrainBufferWritePos = 0;

    auto fillWork = [buffer = createLinearBuffer(block)](
        const std::unique_ptr<C2Work>& work) {
        uint32_t flags = 0;
        if (work->input.flags & C2FrameData::FLAG_END_OF_STREAM) {
            flags |= C2FrameData::FLAG_END_OF_STREAM;
            ALOGV("signalling eos");
        }
        work->worklets.front()->output.flags = (C2FrameData::flags_t)flags;
        work->worklets.front()->output.buffers.clear();
        work->worklets.front()->output.buffers.push_back(buffer);
        work->worklets.front()->output.ordinal = work->input.ordinal;
        work->workletsProcessed = 1u;
    };
    if (work && work->input.ordinal.frameIndex == c2_cntr64_t(mCurFrameIndex)) {
        fillWork(work);
    } else {
        finish(mCurFrameIndex, fillWork);
    }

    ALOGV("out timestamp %" PRIu64 " / %u", mCurTimestamp, block->capacity());
}

void C2SoftXaacDec::process(const std::unique_ptr<C2Work>& work,
                         const std::shared_ptr<C2BlockPool>& pool) {
    work->workletsProcessed = 0u;
    work->result = C2_OK;
    work->worklets.front()->output.configUpdate.clear();
    if (mSignalledError || mSignalledOutputEos) {
        work->result = C2_BAD_VALUE;
        return;
    }
    uint8_t* inBuffer = nullptr;
    uint32_t inBufferLength = 0;
    C2ReadView view = mDummyReadView;
    size_t offset = 0u;
    size_t size = 0u;
    if (!work->input.buffers.empty()) {
        view = work->input.buffers[0]->data().linearBlocks().front().map().get();
        size = view.capacity();
    }
    if (size && view.error()) {
        ALOGE("read view map failed %d", view.error());
        work->result = view.error();
        return;
    }

    bool eos = (work->input.flags & C2FrameData::FLAG_END_OF_STREAM) != 0;
    bool codecConfig =
        (work->input.flags & C2FrameData::FLAG_CODEC_CONFIG) != 0;

    if (codecConfig) {
        if (size == 0u) {
            ALOGE("empty codec config");
            mSignalledError = true;
            work->result = C2_CORRUPTED;
            return;
        }
        // const_cast because of libAACdec method signature.
        inBuffer = const_cast<uint8_t*>(view.data() + offset);
        inBufferLength = size;

        /* GA header configuration sent to Decoder! */
        int err_code = configXAACDecoder(inBuffer, inBufferLength);
        if (err_code) {
            ALOGE("configXAACDecoder err_code = %d", err_code);
            mSignalledError = true;
            work->result = C2_CORRUPTED;
            return;
        }
        work->worklets.front()->output.ordinal = work->input.ordinal;
        work->worklets.front()->output.buffers.clear();

        return;
    }

    mCurFrameIndex = work->input.ordinal.frameIndex.peeku();
    mCurTimestamp = work->input.ordinal.timestamp.peeku();
    mOutputDrainBufferWritePos = 0;
    while (size > 0u) {
        if ((kOutputDrainBufferSize * sizeof(int16_t) -
             mOutputDrainBufferWritePos) <
            (mOutputFrameLength * sizeof(int16_t) * mNumChannels)) {
            ALOGV("skipping decode: not enough space left in DrainBuffer");
            break;
        }

        ALOGV("inAttribute size = %zu", size);
        if (mIntf->isAdts()) {
            ALOGV("ADTS");
            size_t adtsHeaderSize = 0;
            // skip 30 bits, aac_frame_length follows.
            // ssssssss ssssiiip ppffffPc ccohCCll llllllll lll?????

            const uint8_t* adtsHeader = view.data() + offset;
            bool signalError = false;
            if (size < 7) {
                ALOGE("Audio data too short to contain even the ADTS header. "
                      "Got %zu bytes.", size);
                hexdump(adtsHeader, size);
                signalError = true;
            } else {
                bool protectionAbsent = (adtsHeader[1] & 1);
                unsigned aac_frame_length = ((adtsHeader[3] & 3) << 11) |
                                            (adtsHeader[4] << 3) |
                                            (adtsHeader[5] >> 5);

                if (size < aac_frame_length) {
                    ALOGE("Not enough audio data for the complete frame. "
                          "Got %zu bytes, frame size according to the ADTS "
                          "header is %u bytes.", size, aac_frame_length);
                    hexdump(adtsHeader, size);
                    signalError = true;
                } else {
                    adtsHeaderSize = (protectionAbsent ? 7 : 9);
                    if (aac_frame_length < adtsHeaderSize) {
                        signalError = true;
                    } else {
                        // const_cast because of libAACdec method signature.
                        inBuffer =
                            const_cast<uint8_t*>(adtsHeader + adtsHeaderSize);
                        inBufferLength = aac_frame_length - adtsHeaderSize;

                        offset += adtsHeaderSize;
                        size -= adtsHeaderSize;
                    }
                }
            }

            if (signalError) {
                mSignalledError = true;
                work->result = C2_CORRUPTED;
                return;
            }
        } else {
            ALOGV("Non ADTS");
            // const_cast because of libAACdec method signature.
            inBuffer = const_cast<uint8_t*>(view.data() + offset);
            inBufferLength = size;
        }

        signed int prevSampleRate = mSampFreq;
        signed int prevNumChannels = mNumChannels;

        /* XAAC decoder expects first frame to be fed via configXAACDecoder API
         * which should initialize the codec. Once this state is reached, call the
         * decodeXAACStream API with same frame to decode! */
        if (!mIsCodecInitialized) {
            int err_code = configXAACDecoder(inBuffer, inBufferLength);
            if (err_code) {
                ALOGE("configXAACDecoder Failed 2 err_code = %d", err_code);
                mSignalledError = true;
                work->result = C2_CORRUPTED;
                return;
            }
            mIsCodecConfigFlushRequired = true;

            if ((mSampFreq != prevSampleRate) ||
                (mNumChannels != prevNumChannels)) {
                ALOGI("Reconfiguring decoder: %d->%d Hz, %d->%d channels",
                      prevSampleRate, mSampFreq, prevNumChannels, mNumChannels);

                C2StreamSampleRateInfo::output sampleRateInfo(0u, mSampFreq);
                C2StreamChannelCountInfo::output channelCountInfo(0u, mNumChannels);
                std::vector<std::unique_ptr<C2SettingResult>> failures;
                c2_status_t err = mIntf->config(
                        { &sampleRateInfo, &channelCountInfo },
                        C2_MAY_BLOCK,
                        &failures);
                if (err == OK) {
                    work->worklets.front()->output.configUpdate.push_back(
                        C2Param::Copy(sampleRateInfo));
                    work->worklets.front()->output.configUpdate.push_back(
                        C2Param::Copy(channelCountInfo));
                } else {
                    ALOGE("Cannot set width and height");
                    mSignalledError = true;
                    work->result = C2_CORRUPTED;
                    return;
                }
            }
        }

        signed int bytesConsumed = 0;
        int errorCode = 0;
        if (mIsCodecInitialized) {
            errorCode = decodeXAACStream(inBuffer, inBufferLength,
                                         &bytesConsumed, &mNumOutBytes);
        } else {
            ALOGE("Assumption that first frame after header initializes decoder Failed!");
        }
        size -= bytesConsumed;
        offset += bytesConsumed;

        if (inBufferLength != (uint32_t)bytesConsumed)
            ALOGE("All data not consumed");

        /* In case of error, decoder would have given out empty buffer */
        if ((0 != errorCode) && (0 == mNumOutBytes) && mIsCodecInitialized)
            mNumOutBytes = mOutputFrameLength * (mPcmWdSz / 8) * mNumChannels;

        if (!bytesConsumed) {
            ALOGE("bytesConsumed = 0 should never happen");
            mSignalledError = true;
            work->result = C2_CORRUPTED;
            return;
        }

        if ((uint32_t)mNumOutBytes >
            mOutputFrameLength * sizeof(int16_t) * mNumChannels) {
            ALOGE("mNumOutBytes > mOutputFrameLength * sizeof(int16_t) * mNumChannels, should never happen");
            mSignalledError = true;
            work->result = C2_CORRUPTED;
            return;
        }

        if (errorCode) {
            // TODO: check for overflow, ASAN
            memset(mOutputBuffer, 0, mNumOutBytes);

            // Discard input buffer.
            size = 0;

            // fall through
        }
        memcpy(mOutputDrainBuffer, mOutputBuffer, mNumOutBytes);
        mOutputDrainBufferWritePos += mNumOutBytes;
    }

    if (mOutputDrainBufferWritePos) {
        finishWork(work, pool);
    } else {
        fillEmptyWork(work);
    }
    if (eos) mSignalledOutputEos = true;
}

c2_status_t C2SoftXaacDec::drain(uint32_t drainMode,
                              const std::shared_ptr<C2BlockPool>& pool) {
    (void)pool;
    if (drainMode == NO_DRAIN) {
        ALOGW("drain with NO_DRAIN: no-op");
        return C2_OK;
    }
    if (drainMode == DRAIN_CHAIN) {
        ALOGW("DRAIN_CHAIN not supported");
        return C2_OMITTED;
    }

    return C2_OK;
}

void C2SoftXaacDec::configflushDecode() {
    IA_ERRORCODE err_code;
    uint32_t ui_init_done;
    uint32_t inBufferLength = 8203;

    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_FLUSH_MEM,
                                NULL);

    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_INPUT_BYTES,
                                0,
                                &inBufferLength);

    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_FLUSH_MEM,
                                NULL);

    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_INIT_DONE_QUERY,
                                &ui_init_done);
    if (ui_init_done) {
        err_code = getXAACStreamInfo();
        ALOGV("Found Codec with below config---\nsampFreq %d\nnumChannels %d\npcmWdSz %d\nchannelMask %d\noutputFrameLength %d",
               mSampFreq, mNumChannels, mPcmWdSz, mChannelMask, mOutputFrameLength);
        if(mNumChannels > MAX_CHANNEL_COUNT) {
            ALOGE(" No of channels are more than max channels\n");
            mIsCodecInitialized = false;
        } else
            mIsCodecInitialized = true;
    }
}

c2_status_t C2SoftXaacDec::onFlush_sm() {
    if (mIsCodecInitialized) configflushDecode();
    drainDecoder();

    return C2_OK;
}

int C2SoftXaacDec::drainDecoder() {
    /* Output delay compensation logic should sit here. */
    /* Nothing to be done as XAAC decoder does not introduce output buffer delay */

    return 0;
}

int C2SoftXaacDec::initXAACDecoder() {
    /* First part                                        */
    /* Error Handler Init                                */
    /* Get Library Name, Library Version and API Version */
    /* Initialize API structure + Default config set     */
    /* Set config params from user                       */
    /* Initialize memory tables                          */
    /* Get memory information and allocate memory        */

    mInputBufferSize = 0;
    mInputBuffer = nullptr;
    mOutputBuffer = nullptr;
    mMallocCount = 0;
    /* Process struct initing end */

    /* ******************************************************************/
    /* Initialize API structure and set config params to default        */
    /* ******************************************************************/
    /* API size */
    uint32_t pui_api_size;
    /* Get the API size */
    IA_ERRORCODE err_code = ixheaacd_dec_api(NULL,
                                             IA_API_CMD_GET_API_SIZE,
                                             0,
                                             &pui_api_size);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_API_SIZE");

    /* Allocate memory for API */
    if (!mMemoryArray[mMallocCount]) {
        mMemoryArray[mMallocCount] = memalign(4, pui_api_size);
        if (!mMemoryArray[mMallocCount]) {
            ALOGE("malloc for pui_api_size + 4 >> %d Failed", pui_api_size + 4);
            return IA_FATAL_ERROR;
        }
    }

    /* Set API object with the memory allocated */
    mXheaacCodecHandle = (pVOID)((WORD8*)mMemoryArray[mMallocCount]);
    mMallocCount++;

    /* Set the config params to default values */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_INIT_API_PRE_CONFIG_PARAMS,
                                NULL);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_INIT_API_PRE_CONFIG_PARAMS");

    /* ******************************************************************/
    /* Set config parameters                                            */
    /* ******************************************************************/
    uint32_t ui_mp4_flag = 1;
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_ISMP4,
                                &ui_mp4_flag);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_ISMP4");

    /* ******************************************************************/
    /* Initialize Memory info tables                                    */
    /* ******************************************************************/
    uint32_t ui_proc_mem_tabs_size;
    /* Get memory info tables size */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_MEMTABS_SIZE,
                                0,
                                &ui_proc_mem_tabs_size);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_MEMTABS_SIZE");

    if (!mMemoryArray[mMallocCount]) {
        mMemoryArray[mMallocCount] = memalign(4, ui_proc_mem_tabs_size);
        if (!mMemoryArray[mMallocCount]) {
            ALOGE("Malloc for size (ui_proc_mem_tabs_size + 4) = %d failed!", ui_proc_mem_tabs_size + 4);
            return IA_FATAL_ERROR;
        }
    }

    /* Set pointer for process memory tables    */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_MEMTABS_PTR,
                                0,
                                (pVOID)((WORD8*)mMemoryArray[mMallocCount]));
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_SET_MEMTABS_PTR");
    mMallocCount++;

    /* initialize the API, post config, fill memory tables  */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_INIT_API_POST_CONFIG_PARAMS,
                                NULL);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_INIT_API_POST_CONFIG_PARAMS");

    /* ******************************************************************/
    /* Allocate Memory with info from library                           */
    /* ******************************************************************/
    /* There are four different types of memories, that needs to be allocated */
    /* persistent,scratch,input and output */
    for (int i = 0; i < 4; i++) {
        int ui_size = 0, ui_alignment = 0, ui_type = 0;

        /* Get memory size */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_GET_MEM_INFO_SIZE,
                                    i,
                                    &ui_size);
        RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_MEM_INFO_SIZE");

        /* Get memory alignment */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_GET_MEM_INFO_ALIGNMENT,
                                    i,
                                    &ui_alignment);
        RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_MEM_INFO_ALIGNMENT");

        /* Get memory type */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_GET_MEM_INFO_TYPE,
                                    i,
                                    &ui_type);
        RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_MEM_INFO_TYPE");

        if (!mMemoryArray[mMallocCount]) {
            mMemoryArray[mMallocCount] = memalign(ui_alignment, ui_size);
            if (!mMemoryArray[mMallocCount]) {
                ALOGE("Malloc for size (ui_size + ui_alignment) = %d failed!",
                       ui_size + ui_alignment);
                return IA_FATAL_ERROR;
            }
        }
        pVOID pv_alloc_ptr = (pVOID)((WORD8*)mMemoryArray[mMallocCount]);
        mMallocCount++;

        /* Set the buffer pointer */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_SET_MEM_PTR,
                                    i,
                                    pv_alloc_ptr);
        RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_SET_MEM_PTR");
        if (ui_type == IA_MEMTYPE_INPUT) {
            mInputBuffer = (pWORD8)pv_alloc_ptr;
            mInputBufferSize = ui_size;
        }
        if (ui_type == IA_MEMTYPE_OUTPUT)
            mOutputBuffer = (pWORD8)pv_alloc_ptr;
    }
    /* End first part */

    return IA_NO_ERROR;
}

status_t C2SoftXaacDec::initXAACDrc() {
    unsigned int ui_drc_val;
    IA_ERRORCODE err_code = IA_NO_ERROR;
    char value[PROPERTY_VALUE_MAX];
    if (property_get(PROP_DRC_OVERRIDE_REF_LEVEL, value, NULL)) {
        ui_drc_val = atoi(value);
        ALOGV("AAC decoder using desired DRC target reference level of %d instead of %d",
               ui_drc_val, DRC_DEFAULT_MOBILE_REF_LEVEL);
    } else {
        ui_drc_val = DRC_DEFAULT_MOBILE_REF_LEVEL;
    }
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_TARGET_LEVEL,
                                &ui_drc_val);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_TARGET_LEVEL");

    if (property_get(PROP_DRC_OVERRIDE_CUT, value, NULL)) {
        ui_drc_val = atoi(value);
        ALOGV("AAC decoder using desired DRC attenuation factor of %d instead of %d",
                ui_drc_val, DRC_DEFAULT_MOBILE_DRC_CUT);
    } else {
        ui_drc_val = DRC_DEFAULT_MOBILE_DRC_CUT;
    }
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_CUT,
                                &ui_drc_val);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_CUT");

    if (property_get(PROP_DRC_OVERRIDE_BOOST, value, NULL)) {
        ui_drc_val = atoi(value);
        ALOGV("AAC decoder using desired DRC boost factor of %d instead of %d",
              ui_drc_val, DRC_DEFAULT_MOBILE_DRC_BOOST);
    } else {
        ui_drc_val = DRC_DEFAULT_MOBILE_DRC_BOOST;
    }
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_BOOST,
                                &ui_drc_val);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_BOOST");

    if (property_get(PROP_DRC_OVERRIDE_BOOST, value, NULL)) {
        ui_drc_val = atoi(value);
        ALOGV("AAC decoder using desired DRC boost factor of %d instead of %d",
               ui_drc_val, DRC_DEFAULT_MOBILE_DRC_HEAVY);
    } else {
        ui_drc_val = DRC_DEFAULT_MOBILE_DRC_HEAVY;
    }

    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_SET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_HEAVY_COMP,
                                &ui_drc_val);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_DRC_HEAVY_COMP");

    return IA_NO_ERROR;
}

int C2SoftXaacDec::deInitXAACDecoder() {
    ALOGV("deInitXAACDecoder");

    /* Error code */
    IA_ERRORCODE err_code = IA_NO_ERROR;

    /* Tell that the input is over in this buffer */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INPUT_OVER,
                                0,
                                NULL);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_INPUT_OVER");

    for (int i = 0; i < mMallocCount; i++) {
        if (mMemoryArray[i]) {
            free(mMemoryArray[i]);
            mMemoryArray[i] = nullptr;
        }
    }
    mMallocCount = 0;

    return err_code;
}

int C2SoftXaacDec::configXAACDecoder(uint8_t* inBuffer, uint32_t inBufferLength) {
    if (mInputBufferSize < inBufferLength) {
        ALOGE("Cannot config AAC, input buffer size %d < inBufferLength %d", mInputBufferSize, inBufferLength);
        return false;
    }
    /* Copy the buffer passed by Android plugin to codec input buffer */
    memcpy(mInputBuffer, inBuffer, inBufferLength);

    /* Set number of bytes to be processed */
    IA_ERRORCODE err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                             IA_API_CMD_SET_INPUT_BYTES,
                                             0,
                                             &inBufferLength);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_SET_INPUT_BYTES");

    if (mIsCodecConfigFlushRequired) {
        /* If codec is already initialized, then GA header is passed again */
        /* Need to call the Flush API instead of INIT_PROCESS */
        mIsCodecInitialized = false; /* Codec needs to be Reinitialized after flush */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_INIT,
                                    IA_CMD_TYPE_GA_HDR,
                                    NULL);
        RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_GA_HDR");
    } else {
        /* Initialize the process */
        err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                    IA_API_CMD_INIT,
                                    IA_CMD_TYPE_INIT_PROCESS,
                                    NULL);
    }

    uint32_t ui_init_done;
    /* Checking for end of initialization */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_INIT,
                                IA_CMD_TYPE_INIT_DONE_QUERY,
                                &ui_init_done);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_INIT_DONE_QUERY");

    /* How much buffer is used in input buffers */
    int32_t i_bytes_consumed;
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CURIDX_INPUT_BUF,
                                0,
                                &i_bytes_consumed);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_CURIDX_INPUT_BUF");

    if (ui_init_done) {
        err_code = getXAACStreamInfo();
        ALOGI("Found Codec with below config---\nsampFreq %d\nnumChannels %d\npcmWdSz %d\nchannelMask %d\noutputFrameLength %d",
               mSampFreq, mNumChannels, mPcmWdSz, mChannelMask, mOutputFrameLength);
        mIsCodecInitialized = true;
    }

    return err_code;
}

int C2SoftXaacDec::decodeXAACStream(uint8_t* inBuffer,
                                 uint32_t inBufferLength,
                                 int32_t* bytesConsumed,
                                 int32_t* outBytes) {
    if (mInputBufferSize < inBufferLength) {
        ALOGE("Cannot config AAC, input buffer size %d < inBufferLength %d", mInputBufferSize, inBufferLength);
        return -1;
    }
    /* Copy the buffer passed by Android plugin to codec input buffer */
    memcpy(mInputBuffer, inBuffer, inBufferLength);

    /* Set number of bytes to be processed */
    IA_ERRORCODE err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                             IA_API_CMD_SET_INPUT_BYTES,
                                             0,
                                             &inBufferLength);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_SET_INPUT_BYTES");

    /* Execute process */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_EXECUTE,
                                IA_CMD_TYPE_DO_EXECUTE,
                                NULL);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_DO_EXECUTE");

    /* Checking for end of processing */
    uint32_t ui_exec_done;
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_EXECUTE,
                                IA_CMD_TYPE_DONE_QUERY,
                                &ui_exec_done);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_CMD_TYPE_DONE_QUERY");

    /* How much buffer is used in input buffers */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CURIDX_INPUT_BUF,
                                0,
                                bytesConsumed);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_CURIDX_INPUT_BUF");

    /* Get the output bytes */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_OUTPUT_BYTES,
                                0,
                                outBytes);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_API_CMD_GET_OUTPUT_BYTES");

    return err_code;
}

IA_ERRORCODE C2SoftXaacDec::getXAACStreamInfo() {
    IA_ERRORCODE err_code = IA_NO_ERROR;

    /* Sampling frequency */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_SAMP_FREQ,
                                &mSampFreq);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_SAMP_FREQ");

    /* Total Number of Channels */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_NUM_CHANNELS,
                                &mNumChannels);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_NUM_CHANNELS");

    /* PCM word size */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_PCM_WDSZ,
                                &mPcmWdSz);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_PCM_WDSZ");

    /* channel mask to tell the arrangement of channels in bit stream */
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_CHANNEL_MASK,
                                &mChannelMask);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_CHANNEL_MASK");

    /* Channel mode to tell MONO/STEREO/DUAL-MONO/NONE_OF_THESE */
    uint32_t ui_channel_mode;
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_CHANNEL_MODE,
                                &ui_channel_mode);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_CHANNEL_MODE");
    if (ui_channel_mode == 0)
        ALOGV("Channel Mode: MONO_OR_PS\n");
    else if (ui_channel_mode == 1)
        ALOGV("Channel Mode: STEREO\n");
    else if (ui_channel_mode == 2)
        ALOGV("Channel Mode: DUAL-MONO\n");
    else
        ALOGV("Channel Mode: NONE_OF_THESE or MULTICHANNEL\n");

    /* Channel mode to tell SBR PRESENT/NOT_PRESENT */
    uint32_t ui_sbr_mode;
    err_code = ixheaacd_dec_api(mXheaacCodecHandle,
                                IA_API_CMD_GET_CONFIG_PARAM,
                                IA_ENHAACPLUS_DEC_CONFIG_PARAM_SBR_MODE,
                                &ui_sbr_mode);
    RETURN_IF_NE(err_code, IA_NO_ERROR, err_code, "IA_ENHAACPLUS_DEC_CONFIG_PARAM_SBR_MODE");
    if (ui_sbr_mode == 0)
        ALOGV("SBR Mode: NOT_PRESENT\n");
    else if (ui_sbr_mode == 1)
        ALOGV("SBR Mode: PRESENT\n");
    else
        ALOGV("SBR Mode: ILLEGAL\n");

    /* mOutputFrameLength = 1024 * (1 + SBR_MODE) for AAC */
    /* For USAC it could be 1024 * 3 , support to query  */
    /* not yet added in codec                            */
    mOutputFrameLength = 1024 * (1 + ui_sbr_mode);
    ALOGI("mOutputFrameLength %d ui_sbr_mode %d", mOutputFrameLength, ui_sbr_mode);

    return IA_NO_ERROR;
}

class C2SoftXaacDecFactory : public C2ComponentFactory {
public:
    C2SoftXaacDecFactory() : mHelper(std::static_pointer_cast<C2ReflectorHelper>(
            GetCodec2PlatformComponentStore()->getParamReflector())) {
    }

    virtual c2_status_t createComponent(
            c2_node_id_t id,
            std::shared_ptr<C2Component>* const component,
            std::function<void(C2Component*)> deleter) override {
        *component = std::shared_ptr<C2Component>(
                new C2SoftXaacDec(COMPONENT_NAME,
                               id,
                               std::make_shared<C2SoftXaacDec::IntfImpl>(mHelper)),
                deleter);
        return C2_OK;
    }

    virtual c2_status_t createInterface(
            c2_node_id_t id,
            std::shared_ptr<C2ComponentInterface>* const interface,
            std::function<void(C2ComponentInterface*)> deleter) override {
        *interface = std::shared_ptr<C2ComponentInterface>(
                new SimpleInterface<C2SoftXaacDec::IntfImpl>(
                        COMPONENT_NAME, id, std::make_shared<C2SoftXaacDec::IntfImpl>(mHelper)),
                deleter);
        return C2_OK;
    }

    virtual ~C2SoftXaacDecFactory() override = default;

private:
    std::shared_ptr<C2ReflectorHelper> mHelper;
};

}  // namespace android

extern "C" ::C2ComponentFactory* CreateCodec2Factory() {
    ALOGV("in %s", __func__);
    return new ::android::C2SoftXaacDecFactory();
}

extern "C" void DestroyCodec2Factory(::C2ComponentFactory* factory) {
    ALOGV("in %s", __func__);
    delete factory;
}
