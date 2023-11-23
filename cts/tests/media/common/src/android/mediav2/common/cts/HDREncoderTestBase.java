/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.mediav2.common.cts;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;

import org.junit.Assume;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

/**
 * Wrapper class for testing HDR support in video encoder components
 */
public class HDREncoderTestBase extends CodecEncoderTestBase {
    private static final String LOG_TAG = HDREncoderTestBase.class.getSimpleName();

    private ByteBuffer mHdrStaticInfo;
    private Map<Integer, String> mHdrDynamicInfo;

    public HDREncoderTestBase(String encoderName, String mediaType,
            EncoderConfigParams encCfgParams, String allTestParams) {
        super(encoderName, mediaType, new EncoderConfigParams[]{encCfgParams}, allTestParams);
    }

    protected void enqueueInput(int bufferIndex) {
        if (mHdrDynamicInfo != null && mHdrDynamicInfo.containsKey(mInputCount)) {
            insertHdrDynamicInfo(loadByteArrayFromString(mHdrDynamicInfo.get(mInputCount)));
        }
        super.enqueueInput(bufferIndex);
    }

    public void validateHDRInfo(String hdrStaticInfo, Map<Integer, String> hdrDynamicInfo)
            throws IOException, InterruptedException {
        mHdrStaticInfo = hdrStaticInfo != null
                ? ByteBuffer.wrap(loadByteArrayFromString(hdrStaticInfo)) : null;
        mHdrDynamicInfo = hdrDynamicInfo;

        MediaFormat format = mActiveEncCfg.getFormat();
        if (mHdrStaticInfo != null) {
            format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, mHdrStaticInfo);
        }
        ArrayList<MediaFormat> formats = new ArrayList<>();
        formats.add(format);
        Assume.assumeTrue(mCodecName + " does not support HDR10/HDR10+ profile "
                + mActiveEncCfg.mProfile, areFormatsSupported(mCodecName, mMediaType, formats));
        Assume.assumeTrue(mCodecName + " does not support color format COLOR_FormatYUVP010",
                hasSupportForColorFormat(mCodecName, mMediaType, mActiveEncCfg.mColorFormat));

        setUpSource(mActiveRawRes.mFileName);

        int frameLimit = 4;
        if (mHdrDynamicInfo != null) {
            Integer lastHdr10PlusFrame =
                    Collections.max(HDR_DYNAMIC_INFO.entrySet(), Map.Entry.comparingByKey())
                            .getKey();
            frameLimit = lastHdr10PlusFrame + 10;
        }
        int maxNumFrames = mInputData.length
                / (mActiveRawRes.mWidth * mActiveRawRes.mHeight * mActiveRawRes.mBytesPerSample);
        assertTrue("HDR info tests require input file with at least " + frameLimit + " frames. "
                + mActiveRawRes.mFileName + " has " + maxNumFrames + " frames. \n" + mTestConfig
                + mTestEnv, frameLimit <= maxNumFrames);

        mOutputBuff = new OutputManager();
        mMuxOutput = true;
        mCodec = MediaCodec.createByCodecName(mCodecName);
        configureCodec(format, true, true, true);
        mCodec.start();
        doWork(frameLimit);
        queueEOS();
        waitForAllOutputs();

        MediaFormat fmt = mCodec.getOutputFormat();

        mCodec.stop();
        mCodec.release();
        if (mHdrStaticInfo != null) {
            // verify if the out fmt contains HDR Static info as expected
            validateHDRInfo(fmt, MediaFormat.KEY_HDR_STATIC_INFO, mHdrStaticInfo);
        }

        // verify if the muxed file contains HDR Dynamic info as expected
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String decoder = codecList.findDecoderForFormat(format);
        assertNotNull("Device advertises support for encoding " + format + " but not decoding it \n"
                + mTestConfig + mTestEnv, decoder);

        HDRDecoderTestBase decoderTest =
                new HDRDecoderTestBase(decoder, mMediaType, mMuxedOutputFile, mAllTestParams);
        decoderTest.validateHDRInfo(hdrStaticInfo, hdrStaticInfo, mHdrDynamicInfo, mHdrDynamicInfo);
        if (HDR_INFO_IN_BITSTREAM_CODECS.contains(mMediaType)) {
            decoderTest.validateHDRInfo(hdrStaticInfo, null, mHdrDynamicInfo, null);
        }
        new File(mMuxedOutputFile).delete();
    }
}
