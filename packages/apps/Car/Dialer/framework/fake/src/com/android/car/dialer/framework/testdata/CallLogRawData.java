/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.dialer.framework.testdata;

/**
 * A class represents the call log raw data.
 */
public class CallLogRawData {

    private String mNumber;
    private Integer mNumberType = 1;
    /**
     * This is the time interval from when the call occurs to the current time.
     */
    private Integer mInterval;

    private Integer mCallType = 1;

    private boolean mIsRead = true;

    /**
     * Sets the number;
     */
    public void setNumber(String number) {
        mNumber = number;
    }

    /**
     * Sets the number type;
     */
    public void setNumberType(Integer numberType) {
        mNumberType = numberType;
    }

    /**
     * Sets the time interval;
     */
    public void setInterval(Integer interval) {
        mInterval = interval;
    }

    /** Sets the call type. */
    public void setCallType(Integer callType) {
        mCallType = callType;
    }

    /** Sets the read state. */
    public void setRead(boolean isRead) {
        mIsRead = isRead;
    }

    /**
     * Gets the phone number;
     */
    public String getNumber() {
        return mNumber;
    }

    /**
     * Gets the phone number type;
     */
    public Integer getNumberType() {
        return mNumberType;
    }

    /**
     * Gets the time interval for the phone call;
     */
    public Integer getInterval() {
        return mInterval;
    }

    /** Gets the call type. */
    public Integer getCallType() {
        return mCallType;
    }

    /** Gets the read state. */
    public Integer getRead() {
        return mIsRead ? 1 : 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("[number: ");
        builder.append(mNumber);
        builder.append(", number type: ");
        builder.append(mNumberType);
        builder.append(", time interval: ");
        builder.append(mInterval);
        builder.append(", call type: ");
        builder.append(mCallType);
        builder.append(", isRead: ");
        builder.append(mIsRead);
        builder.append("]");
        return builder.toString();
    }
}
