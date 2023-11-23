/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.connecteddevice.ble;

import static com.android.car.connecteddevice.BleStreamProtos.BleDeviceMessageProto.BleDeviceMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Holds the needed data from a {@link BleDeviceMessage}. */
public class DeviceMessage {

    private static final String TAG = "DeviceMessage";

    private final UUID mRecipient;

    private final boolean mIsMessageEncrypted;

    private byte[] mMessage;

    public DeviceMessage(@Nullable UUID recipient, boolean isMessageEncrypted,
            @NonNull byte[] message) {
        mRecipient = recipient;
        mIsMessageEncrypted = isMessageEncrypted;
        mMessage = message;
    }

    /** Returns the recipient for this message. {@code null} if no recipient set. */
    @Nullable
    public UUID getRecipient() {
        return mRecipient;
    }

    /** Returns whether this message is encrypted. */
    public boolean isMessageEncrypted() {
        return mIsMessageEncrypted;
    }

    /** Returns the message payload. */
    @Nullable
    public byte[] getMessage() {
        return mMessage;
    }

    /** Set the message payload. */
    public void setMessage(@NonNull byte[] message) {
        mMessage = message;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof DeviceMessage)) {
            return false;
        }
        DeviceMessage deviceMessage = (DeviceMessage) obj;
        return Objects.equals(mRecipient, deviceMessage.mRecipient)
                && mIsMessageEncrypted == deviceMessage.mIsMessageEncrypted
                && Arrays.equals(mMessage, deviceMessage.mMessage);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(mRecipient, mIsMessageEncrypted)
                + Arrays.hashCode(mMessage);
    }
}
