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

package com.android.sdksandbox;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * To be used to send latency information from sandbox to system system via callback
 *
 * @hide
 */
public final class SandboxLatencyInfo implements Parcelable {
    @IntDef(
            prefix = "SANDBOX_STATUS_",
            value = {
                SANDBOX_STATUS_SUCCESS,
                SANDBOX_STATUS_FAILED_AT_SANDBOX,
                SANDBOX_STATUS_FAILED_AT_SDK,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SandboxStatus {}

    public static final int SANDBOX_STATUS_SUCCESS = 1;
    public static final int SANDBOX_STATUS_FAILED_AT_SANDBOX = 2;
    public static final int SANDBOX_STATUS_FAILED_AT_SDK = 3;

    private final long mTimeSystemServerCalledSandbox;
    private long mTimeSandboxReceivedCallFromSystemServer = -1;
    private long mTimeSandboxCalledSdk = -1;
    private long mTimeSdkCallCompleted = -1;
    private long mTimeSandboxCalledSystemServer = -1;
    private @SandboxStatus int mSandboxStatus = SANDBOX_STATUS_SUCCESS;

    public static final @NonNull Parcelable.Creator<SandboxLatencyInfo> CREATOR =
            new Parcelable.Creator<SandboxLatencyInfo>() {
                public SandboxLatencyInfo createFromParcel(Parcel in) {
                    return new SandboxLatencyInfo(in);
                }

                public SandboxLatencyInfo[] newArray(int size) {
                    return new SandboxLatencyInfo[size];
                }
            };

    public SandboxLatencyInfo(long timeSystemServerCalledSandbox) {
        mTimeSystemServerCalledSandbox = timeSystemServerCalledSandbox;
    }

    private SandboxLatencyInfo(Parcel in) {
        mTimeSystemServerCalledSandbox = in.readLong();
        mTimeSandboxReceivedCallFromSystemServer = in.readLong();
        mTimeSandboxCalledSdk = in.readLong();
        mTimeSdkCallCompleted = in.readLong();
        mTimeSandboxCalledSystemServer = in.readLong();
        mSandboxStatus = in.readInt();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof SandboxLatencyInfo)) return false;
        SandboxLatencyInfo that = (SandboxLatencyInfo) object;
        return mTimeSystemServerCalledSandbox == that.mTimeSystemServerCalledSandbox
                && mTimeSandboxReceivedCallFromSystemServer
                        == that.mTimeSandboxReceivedCallFromSystemServer
                && mTimeSandboxCalledSdk == that.mTimeSandboxCalledSdk
                && mTimeSdkCallCompleted == that.mTimeSdkCallCompleted
                && mTimeSandboxCalledSystemServer == that.mTimeSandboxCalledSystemServer
                && mSandboxStatus == that.mSandboxStatus;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mTimeSystemServerCalledSandbox,
                mTimeSandboxReceivedCallFromSystemServer,
                mTimeSandboxCalledSdk,
                mTimeSdkCallCompleted,
                mTimeSandboxCalledSystemServer,
                mSandboxStatus);
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mTimeSystemServerCalledSandbox);
        out.writeLong(mTimeSandboxReceivedCallFromSystemServer);
        out.writeLong(mTimeSandboxCalledSdk);
        out.writeLong(mTimeSdkCallCompleted);
        out.writeLong(mTimeSandboxCalledSystemServer);
        out.writeInt(mSandboxStatus);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public long getTimeSystemServerCalledSandbox() {
        return mTimeSystemServerCalledSandbox;
    }

    public void setTimeSandboxReceivedCallFromSystemServer(
            long timeSandboxReceivedCallFromSystemServer) {
        mTimeSandboxReceivedCallFromSystemServer = timeSandboxReceivedCallFromSystemServer;
    }

    public long getTimeSandboxCalledSdk() {
        return mTimeSandboxCalledSdk;
    }

    public void setTimeSandboxCalledSdk(long timeSandboxCalledSdk) {
        mTimeSandboxCalledSdk = timeSandboxCalledSdk;
    }

    public void setTimeSdkCallCompleted(long timeSdkCallCompleted) {
        mTimeSdkCallCompleted = timeSdkCallCompleted;
    }

    public long getTimeSandboxCalledSystemServer() {
        return mTimeSandboxCalledSystemServer;
    }

    public void setTimeSandboxCalledSystemServer(long timeSandboxCalledSystemServer) {
        mTimeSandboxCalledSystemServer = timeSandboxCalledSystemServer;
    }

    public void setSandboxStatus(@SandboxStatus int sandboxStatus) {
        mSandboxStatus = sandboxStatus;
    }

    public int getSandboxLatency() {
        int latencySandbox =
                (int) (mTimeSandboxCalledSystemServer - mTimeSandboxReceivedCallFromSystemServer);
        final int latencySdk = getSdkLatency();
        if (latencySdk != -1) {
            latencySandbox -= latencySdk;
        }
        return latencySandbox;
    }

    public int getSdkLatency() {
        if (mTimeSandboxCalledSdk != -1 && mTimeSdkCallCompleted != -1) {
            return ((int) (mTimeSdkCallCompleted - mTimeSandboxCalledSdk));
        }
        return -1;
    }

    public int getLatencySystemServerToSandbox() {
        return ((int) (mTimeSandboxReceivedCallFromSystemServer - mTimeSystemServerCalledSandbox));
    }

    public boolean isSuccessfulAtSdk() {
        return mSandboxStatus != SANDBOX_STATUS_FAILED_AT_SDK;
    }

    public boolean isSuccessfulAtSandbox() {
        return mSandboxStatus != SANDBOX_STATUS_FAILED_AT_SANDBOX;
    }
}
