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

import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Helper class for running mediacodec in asynchronous mode. All mediacodec callback events are
 * registered in this object so that the client can take appropriate action in time.
 * <p>
 * TODO(b/262696149): Calls to getInput(), getOutput(), getWork() return if there is a valid input
 * or output buffer available for client or when the codec is in error state. Have to add support
 * to return from these calls after a timeout. Currently the wait is indefinite.
 */
public class CodecAsyncHandler extends MediaCodec.Callback {
    private static final String LOG_TAG = CodecAsyncHandler.class.getSimpleName();
    private final Lock mLock = new ReentrantLock();
    private final Condition mCondition = mLock.newCondition();
    private final LinkedList<Pair<Integer, MediaCodec.BufferInfo>> mCbInputQueue;
    private final LinkedList<Pair<Integer, MediaCodec.BufferInfo>> mCbOutputQueue;
    private MediaFormat mOutFormat;
    private boolean mSignalledOutFormatChanged;
    private volatile boolean mSignalledError;
    private String mErrorMsg;

    public CodecAsyncHandler() {
        mCbInputQueue = new LinkedList<>();
        mCbOutputQueue = new LinkedList<>();
        mSignalledError = false;
        mSignalledOutFormatChanged = false;
        mErrorMsg = "";
    }

    public void clearQueues() {
        mLock.lock();
        mCbInputQueue.clear();
        mCbOutputQueue.clear();
        mLock.unlock();
    }

    public void resetContext() {
        clearQueues();
        mOutFormat = null;
        mSignalledOutFormatChanged = false;
        mErrorMsg = "";
        mSignalledError = false;
    }

    @Override
    public void onInputBufferAvailable(@NonNull MediaCodec codec, int bufferIndex) {
        assertTrue(bufferIndex >= 0);
        mLock.lock();
        mCbInputQueue.add(new Pair<>(bufferIndex, null));
        mCondition.signalAll();
        mLock.unlock();
    }

    @Override
    public void onOutputBufferAvailable(@NonNull MediaCodec codec, int bufferIndex,
            @NonNull MediaCodec.BufferInfo info) {
        assertTrue(bufferIndex >= 0);
        mLock.lock();
        mCbOutputQueue.add(new Pair<>(bufferIndex, info));
        mCondition.signalAll();
        mLock.unlock();
    }

    @Override
    public void onError(@NonNull MediaCodec codec, MediaCodec.CodecException e) {
        mLock.lock();
        mErrorMsg = "###################  Async Error Details  #####################\n";
        mErrorMsg += e.toString() + "\n";
        mSignalledError = true;
        mCondition.signalAll();
        mLock.unlock();
        Log.e(LOG_TAG, "received media codec error : " + e.getMessage());
    }

    @Override
    public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
        mOutFormat = format;
        mSignalledOutFormatChanged = true;
        Log.i(LOG_TAG, "Output format changed: " + format);
    }

    public void setCallBack(MediaCodec codec, boolean isCodecInAsyncMode) {
        if (isCodecInAsyncMode) {
            codec.setCallback(this);
        } else {
            codec.setCallback(null);
        }
    }

    public Pair<Integer, MediaCodec.BufferInfo> getInput() throws InterruptedException {
        Pair<Integer, MediaCodec.BufferInfo> element = null;
        mLock.lock();
        while (!mSignalledError) {
            if (mCbInputQueue.isEmpty()) {
                mCondition.await();
            } else {
                element = mCbInputQueue.remove(0);
                break;
            }
        }
        mLock.unlock();
        return element;
    }

    public Pair<Integer, MediaCodec.BufferInfo> getOutput() throws InterruptedException {
        Pair<Integer, MediaCodec.BufferInfo> element = null;
        mLock.lock();
        while (!mSignalledError) {
            if (mCbOutputQueue.isEmpty()) {
                mCondition.await();
            } else {
                element = mCbOutputQueue.remove(0);
                break;
            }
        }
        mLock.unlock();
        return element;
    }

    public Pair<Integer, MediaCodec.BufferInfo> getWork() throws InterruptedException {
        Pair<Integer, MediaCodec.BufferInfo> element = null;
        mLock.lock();
        while (!mSignalledError) {
            if (mCbInputQueue.isEmpty() && mCbOutputQueue.isEmpty()) {
                mCondition.await();
            } else {
                if (!mCbOutputQueue.isEmpty()) {
                    element = mCbOutputQueue.remove(0);
                } else {
                    element = mCbInputQueue.remove(0);
                }
                break;
            }
        }
        mLock.unlock();
        return element;
    }

    public boolean isInputQueueEmpty() {
        mLock.lock();
        boolean isEmpty = mCbInputQueue.isEmpty();
        mLock.unlock();
        return isEmpty;
    }

    public boolean hasSeenError() {
        return mSignalledError;
    }

    public String getErrMsg() {
        return mErrorMsg;
    }

    public boolean hasOutputFormatChanged() {
        return mSignalledOutFormatChanged;
    }

    public MediaFormat getOutputFormat() {
        return mOutFormat;
    }
}
