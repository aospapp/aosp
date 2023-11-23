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

package android.videocodec.cts;

import static android.media.MediaFormat.PICTURE_TYPE_I;
import static android.media.MediaFormat.PICTURE_TYPE_UNKNOWN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.mediav2.common.cts.BitStreamUtils;
import android.mediav2.common.cts.CodecEncoderTestBase;
import android.mediav2.common.cts.DecodeStreamToYuv;
import android.mediav2.common.cts.EncoderConfigParams;
import android.mediav2.common.cts.RawResource;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.compatibility.common.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Wrapper class for handling and testing video encoder components.
 */
public class VideoEncoderValidationTestBase extends CodecEncoderTestBase {
    private static final String LOG_TAG = VideoEncoderValidationTestBase.class.getSimpleName();
    private static final String MEDIA_DIR = WorkDir.getMediaDirString();

    protected static final boolean ENABLE_LOGS = false;
    protected static final StringBuilder DIAGNOSTICS = new StringBuilder();

    protected final CompressedResource mCRes;
    protected BitStreamUtils.ParserBase mParser;

    final TreeMap<Long, Integer> mPtsPicTypeMap = new TreeMap<>();

    RandomAccessFile mFileInp;
    long mFileReadOffset;
    long mFileLength;

    public static class CompressedResource {
        final String mMediaType;
        final String mResFile;

        CompressedResource(String mediaType, String resFile) {
            mMediaType = mediaType;
            mResFile = resFile;
        }

        @NonNull
        @Override
        public String toString() {
            return "CompressedResource{" + "res file ='" + mResFile + '\'' + '}';
        }

        public String uniqueLabel() {
            return mMediaType + mResFile;
        }
    }

    public static final CompressedResource BIRTHDAY_FULLHD_LANDSCAPE =
            new CompressedResource(MediaFormat.MIMETYPE_VIDEO_AVC, MEDIA_DIR
                    + "AVICON-MOBILE-BirthdayHalfway-SI17-CRUW03-L-420-8bit-SDR-1080p-30fps.mp4");
    public static final CompressedResource SELFIEGROUP_FULLHD_PORTRAIT =
            new CompressedResource(MediaFormat.MIMETYPE_VIDEO_AVC, MEDIA_DIR
                    + "AVICON-MOBILE-SelfieGroupGarden-SF15-CF01-P-420-8bit-SDR-1080p-30fps.mp4");

    static void logAllFilesInCacheDir(boolean isStartOfTest) {
        if (isStartOfTest) DIAGNOSTICS.setLength(0);
        String cacheDir = CONTEXT.getCacheDir().toString();
        DIAGNOSTICS.append(String.format("\nThe state of cache dir : %s, %s is :", cacheDir,
                isStartOfTest ? "at start" : "now"));
        File dir = new File(cacheDir);
        if (dir.exists()) {
            for (File f : Objects.requireNonNull(dir.listFiles())) {
                DIAGNOSTICS.append(f.getName()).append("\n");
            }
        } else {
            DIAGNOSTICS.append(" directory not present");
        }
    }

    static void decodeStreamsToYuv(ArrayList<CompressedResource> resources,
            HashMap<String, RawResource> streamYuvMap, String prefix) {
        decodeStreamsToYuv(resources, streamYuvMap, Integer.MAX_VALUE, prefix);
    }

    static void decodeStreamsToYuv(ArrayList<CompressedResource> resources,
            HashMap<String, RawResource> streamYuvMap, int frameLimit, String prefix) {
        logAllFilesInCacheDir(true);
        for (CompressedResource res : resources) {
            if (!(streamYuvMap.containsKey(res.uniqueLabel()))) {
                try {
                    DecodeStreamToYuv yuv = new DecodeStreamToYuv(res.mMediaType, res.mResFile,
                            frameLimit, prefix);
                    streamYuvMap.put(res.uniqueLabel(), yuv.getDecodedYuv());
                } catch (Exception e) {
                    streamYuvMap.put(res.uniqueLabel(), null);
                    DIAGNOSTICS.append(String.format("\nWhile decoding the resource : %s,"
                            + " encountered exception :  %s was thrown", res, e));
                    logAllFilesInCacheDir(false);
                }
            }
        }
    }

    VideoEncoderValidationTestBase(String encoder, String mediaType,
            EncoderConfigParams encCfgParams, CompressedResource res, String allTestParams) {
        super(encoder, mediaType, new EncoderConfigParams[]{encCfgParams}, allTestParams);
        mCRes = res;
    }

    protected void setUpSource(String inpPath) throws IOException {
        Preconditions.assertTestFileExists(inpPath);
        mFileInp = new RandomAccessFile(inpPath, "r");
        mInputData = null;
        mFileReadOffset = 0L;
        mFileLength = mFileInp.length();
    }

    protected void resetContext(boolean isAsync, boolean signalEOSWithLastFrame) {
        super.resetContext(isAsync, signalEOSWithLastFrame);
        mPtsPicTypeMap.clear();
    }

    protected void enqueueInput(int bufferIndex) {
        int frmSize = 3 * mActiveRawRes.mBytesPerSample * mActiveRawRes.mWidth
                * mActiveRawRes.mHeight / 2;
        if (mInputData == null || mInputData.length != frmSize) {
            mInputData = new byte[frmSize];
        }
        int bytesRead = 0;
        try {
            bytesRead = mFileInp.read(mInputData);
            if (mIsLoopBack && mInputCount < mLoopBackFrameLimit && bytesRead == -1) {
                mFileInp.seek(0);
                bytesRead = mFileInp.read(mInputData);
            }
        } catch (IOException e) {
            fail("encountered exception during file read." + e + "\n" + mTestConfig + mTestEnv);
        }
        if (bytesRead != -1 && bytesRead != frmSize) {
            fail("received partial frame to encode \n" + mTestConfig + mTestEnv);
        }
        if (bytesRead == -1) {
            assertEquals("mFileReadOffset, mFileLength and EOS state are not in sync \n"
                    + mTestConfig + mTestEnv, mFileReadOffset, mFileLength);
            enqueueEOS(bufferIndex);
        } else {
            int size = mActiveRawRes.mBytesPerSample * mActiveEncCfg.mWidth
                    * mActiveEncCfg.mHeight * 3 / 2;
            int flags = 0;
            long pts = mInputOffsetPts + mInputCount * 1000000L / mActiveEncCfg.mFrameRate;

            Image img = mCodec.getInputImage(bufferIndex);
            assertNotNull("CPU-read via ImageReader API is not available \n" + mTestConfig
                    + mTestEnv, img);
            fillImage(img);
            if (mSignalEOSWithLastFrame) {
                if (mIsLoopBack ? (mInputCount + 1 >= mLoopBackFrameLimit) :
                        (mFileReadOffset + frmSize >= mFileLength)) {
                    flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                    mSawInputEOS = true;
                }
            }
            mNumBytesSubmitted += size;
            mFileReadOffset += frmSize;
            mCodec.queueInputBuffer(bufferIndex, 0, size, pts, flags);
            if (ENABLE_LOGS) {
                Log.v(LOG_TAG, "input: id: " + bufferIndex + " size: " + size + " pts: " + pts
                        + " flags: " + flags);
            }
            mOutputBuff.saveInPTS(pts);
            mInputCount++;
        }
    }

    protected void dequeueOutput(int bufferIndex, MediaCodec.BufferInfo info) {
        if (info.size > 0 && ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)) {
            int picType = PICTURE_TYPE_UNKNOWN;

            if ((info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                picType = PICTURE_TYPE_I;
            }
            if (picType == PICTURE_TYPE_UNKNOWN) {
                MediaFormat format = mCodec.getOutputFormat(bufferIndex);
                picType = format.getInteger(MediaFormat.KEY_PICTURE_TYPE, PICTURE_TYPE_UNKNOWN);
            }
            if (picType == PICTURE_TYPE_UNKNOWN && mParser != null) {
                ByteBuffer buf = mCodec.getOutputBuffer(bufferIndex);
                picType = BitStreamUtils.getFrameTypeFromBitStream(buf, info, mParser);
            }
            mPtsPicTypeMap.put(info.presentationTimeUs, picType);
        }
        super.dequeueOutput(bufferIndex, info);
    }
}
