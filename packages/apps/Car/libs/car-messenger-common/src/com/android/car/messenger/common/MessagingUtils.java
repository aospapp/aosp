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

package com.android.car.messenger.common;

import static com.android.car.assist.CarVoiceInteractionSession.KEY_DEVICE_ADDRESS;
import static com.android.car.assist.CarVoiceInteractionSession.KEY_PHONE_NUMBER;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * Utility class for text messaging related functions.
 */
public final class MessagingUtils {
    private static final String TAG = "CMC.MessagingUtils";

    /** Action used to direct send to a specified phone number */
    public static final String ACTION_DIRECT_SEND = "ACTION_DIRECT_SEND";

    private MessagingUtils() {}

    /**
     * Sends a reply, meant to be used from a caller originating from voice input.
     *
     * @param intent Originates from the client requesting to direct send a SMS, and may have been
     *               modified by the system voice assistant.
     */
    public static void directSend(Context context, Intent intent) {
        final CharSequence phoneNumber = intent.getCharSequenceExtra(KEY_PHONE_NUMBER);
        final String iccId = intent.getStringExtra(KEY_DEVICE_ADDRESS);
        final CharSequence message = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (iccId == null || phoneNumber == null || TextUtils.isEmpty(message)) {
            Log.e(TAG, "Dropping voice reply. Received no icc id, number and/or empty message!");
            return;
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Direct Send: id:" + iccId + ", pn:" + phoneNumber + ", msg:" + message);
            Log.d(TAG, "Sending a message to specified phone number");
        }

        SubscriptionManager subManager = context.getSystemService(SubscriptionManager.class);
        List<SubscriptionInfo> infos = subManager.getActiveSubscriptionInfoList();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "active subscriptions: " + infos);
        }
        if (infos == null) {
            Log.w(TAG, "Dropping voice reply. No active subscriptions");
            return;
        }

        SubscriptionInfo subInfo = infos.stream().filter(
                info -> info.getIccId().equals(iccId)).findFirst().orElse(null);
        if (subInfo == null) {
            Log.w(TAG, "Dropping voice reply. No iccId matched");
            return;
        }

        SmsManager smsManager;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            smsManager = context.getSystemService(SmsManager.class)
                    .createForSubscriptionId(subInfo.getSubscriptionId());
        } else {
            smsManager = SmsManager.getSmsManagerForSubscriptionId(subInfo.getSubscriptionId());
        }
        smsManager.sendTextMessage(
                phoneNumber.toString(),
                /* scAddress= */ null,
                message.toString(),
                /* sentIntent= */ null,
                /* deliveryIntent= */ null);
    }
}
