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

package com.android.adservices.service.stats;

import android.annotation.NonNull;

import java.util.Objects;

/** Class for Api Call Stats. */
public class ApiCallStats {
    private int mCode;
    private int mApiClass;
    int mApiName;
    String mAppPackageName;
    String mSdkPackageName;
    int mLatencyMillisecond;
    int mResultCode;

    public ApiCallStats() {}

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ApiCallStats)) {
            return false;
        }
        ApiCallStats apiCallStats = (ApiCallStats) obj;
        return mCode == apiCallStats.getCode()
                && mApiClass == apiCallStats.getApiClass()
                && mApiName == apiCallStats.getApiName()
                && Objects.equals(mAppPackageName, apiCallStats.getAppPackageName())
                && Objects.equals(mSdkPackageName, apiCallStats.getSdkPackageName())
                && mLatencyMillisecond == apiCallStats.getLatencyMillisecond()
                && mResultCode == apiCallStats.getResultCode();
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mCode,
                mApiClass,
                mApiName,
                mAppPackageName,
                mSdkPackageName,
                mLatencyMillisecond,
                mResultCode);
    }

    @NonNull
    public int getCode() {
        return mCode;
    }

    @NonNull
    public int getApiClass() {
        return mApiClass;
    }

    @NonNull
    public int getApiName() {
        return mApiName;
    }

    @NonNull
    public String getAppPackageName() {
        return mAppPackageName;
    }

    @NonNull
    public String getSdkPackageName() {
        return mSdkPackageName;
    }

    @NonNull
    public int getLatencyMillisecond() {
        return mLatencyMillisecond;
    }

    @NonNull
    public int getResultCode() {
        return mResultCode;
    }

    @Override
    public String toString() {
        return "ApiCallStats{"
                + "mCode="
                + mCode
                + ", mApiClass="
                + mApiClass
                + ", mApiName="
                + mApiName
                + ", mAppPackageName='"
                + mAppPackageName
                + '\''
                + ", mSdkPackageName='"
                + mSdkPackageName
                + '\''
                + ", mLatencyMillisecond="
                + mLatencyMillisecond
                + ", mResultCode="
                + mResultCode
                + '}';
    }

    /** Builder for {@link ApiCallStats}. */
    public static final class Builder {
        private final ApiCallStats mBuilding;

        public Builder() {
            mBuilding = new ApiCallStats();
        }

        /** See {@link ApiCallStats#getCode()} . */
        public @NonNull ApiCallStats.Builder setCode(int code) {
            mBuilding.mCode = code;
            return this;
        }

        /** See {@link ApiCallStats#getApiClass()} . */
        public @NonNull ApiCallStats.Builder setApiClass(int apiClass) {
            mBuilding.mApiClass = apiClass;
            return this;
        }

        /** See {@link ApiCallStats#getApiName()} . */
        public @NonNull ApiCallStats.Builder setApiName(int apiName) {
            mBuilding.mApiName = apiName;
            return this;
        }

        /** See {@link ApiCallStats#getAppPackageName()} . */
        public @NonNull ApiCallStats.Builder setAppPackageName(@NonNull String appPackageName) {
            Objects.requireNonNull(appPackageName);
            mBuilding.mAppPackageName = appPackageName;
            return this;
        }

        /** See {@link ApiCallStats#getSdkPackageName()}. */
        public @NonNull ApiCallStats.Builder setSdkPackageName(@NonNull String sdkPackageName) {
            Objects.requireNonNull(sdkPackageName);
            mBuilding.mSdkPackageName = sdkPackageName;
            return this;
        }

        /** See {@link ApiCallStats#getLatencyMillisecond()}. */
        public @NonNull ApiCallStats.Builder setLatencyMillisecond(int latencyMillisecond) {
            mBuilding.mLatencyMillisecond = latencyMillisecond;
            return this;
        }

        /** See {@link ApiCallStats#getResultCode()}. */
        public @NonNull ApiCallStats.Builder setResultCode(int resultCode) {
            mBuilding.mResultCode = resultCode;
            return this;
        }

        /** Build the {@link ApiCallStats}. */
        public @NonNull ApiCallStats build() {
            if (mBuilding.mAppPackageName == null || mBuilding.mSdkPackageName == null) {
                throw new IllegalArgumentException("appPackageName or sdkPackageName is null");
            }
            return mBuilding;
        }
    }
}
