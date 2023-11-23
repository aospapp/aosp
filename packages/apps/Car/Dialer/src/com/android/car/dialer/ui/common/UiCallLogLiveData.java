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

package com.android.car.dialer.ui.common;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import com.android.car.dialer.R;
import com.android.car.dialer.livedata.CallHistoryLiveData;
import com.android.car.dialer.livedata.HeartBeatLiveData;
import com.android.car.dialer.log.L;
import com.android.car.telephony.common.TelecomUtils;
import com.android.car.dialer.ui.common.entity.UiCallLog;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.InMemoryPhoneBook;
import com.android.car.telephony.common.PhoneCallLog;
import com.android.car.telephony.common.PhoneNumber;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a list of call logs for UI representation. This live data get data source from both
 * call log and contact list. It also refresh itself on the relative time in the body text.
 */
public class UiCallLogLiveData extends MediatorLiveData<List<UiCallLog>> {
    private static final String TAG = "CD.UiCallLogLiveData";

    private static final String TYPE_AND_RELATIVE_TIME_JOINER = ", ";
    private Context mContext;

    public UiCallLogLiveData(Context context,
            HeartBeatLiveData heartBeatLiveData,
            CallHistoryLiveData callHistoryLiveData,
            LiveData<List<Contact>> contactListLiveData) {
        mContext = context;
        addSource(callHistoryLiveData, this::onCallHistoryChanged);
        addSource(contactListLiveData,
                (contacts) -> onCallHistoryChanged(callHistoryLiveData.getValue()));
        addSource(heartBeatLiveData, (trigger) -> updateRelativeTime());
    }

    private void onCallHistoryChanged(List<PhoneCallLog> callLogs) {
        setValue(convert(callLogs));
    }

    private void updateRelativeTime() {
        boolean hasChanged = false;
        List<UiCallLog> uiCallLogs = getValue();
        if (uiCallLogs == null) {
            return;
        }
        for (UiCallLog uiCallLog : uiCallLogs) {
            String secondaryText = uiCallLog.getText();
            List<String> splittedSecondaryText = Splitter.on(
                    TYPE_AND_RELATIVE_TIME_JOINER).splitToList(secondaryText);

            String oldRelativeTime;
            String type = "";
            if (splittedSecondaryText.size() == 1) {
                oldRelativeTime = splittedSecondaryText.get(0);
            } else if (splittedSecondaryText.size() == 2) {
                type = splittedSecondaryText.get(0);
                oldRelativeTime = splittedSecondaryText.get(1);
            } else {
                L.w(TAG, "secondary text format is incorrect: %s", secondaryText);
                return;
            }

            String newRelativeTime = getRelativeTime(uiCallLog.getMostRecentCallEndTimestamp());
            if (!oldRelativeTime.equals(newRelativeTime)) {
                String newSecondaryText = getSecondaryText(type, newRelativeTime);
                uiCallLog.setText(newSecondaryText);
                hasChanged = true;
            }
        }

        if (hasChanged) {
            setValue(getValue());
        }
    }

    private List<UiCallLog> convert(List<PhoneCallLog> phoneCallLogs) {
        if (phoneCallLogs == null) {
            return Collections.emptyList();
        }
        List<UiCallLog> uiCallLogs = new ArrayList<>();

        InMemoryPhoneBook inMemoryPhoneBook = InMemoryPhoneBook.get();
        for (PhoneCallLog phoneCallLog : phoneCallLogs) {
            String number = phoneCallLog.getPhoneNumberString();
            String relativeTime = getRelativeTime(phoneCallLog.getLastCallEndTimestamp());
            if (TelecomUtils.isVoicemailNumber(mContext, number)) {
                String title = mContext.getString(R.string.voicemail);
                UiCallLog uiCallLog = new UiCallLog(title,
                        relativeTime, number, null, phoneCallLog.getAllCallRecords());
                uiCallLogs.add(uiCallLog);
                continue;
            }

            Contact contact = inMemoryPhoneBook.lookupContactEntry(number);
            String title;
            if (contact != null && contact.getDisplayName() != null) {
                title = contact.getDisplayName();
            } else if (!TextUtils.isEmpty(number)) {
                title = TelecomUtils.getFormattedNumber(mContext, number);
            } else {
                title = mContext.getString(R.string.unknown);
            }
            PhoneNumber phoneNumber = contact != null ? contact.getPhoneNumber(number) : null;

            UiCallLog uiCallLog = new UiCallLog(
                    title,
                    getSecondaryText(getType(phoneNumber), relativeTime),
                    number,
                    contact != null ? contact.getAvatarUri() : null,
                    phoneCallLog.getAllCallRecords());

            uiCallLogs.add(uiCallLog);
        }
        return uiCallLogs;
    }

    private String getRelativeTime(long millis) {
        boolean validTimestamp = millis > 0;

        return validTimestamp ? DateUtils.getRelativeTimeSpanString(
                millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE).toString() : "";
    }

    private String getSecondaryText(@Nullable CharSequence type, String relativeTime) {
        if (!TextUtils.isEmpty(type)) {
            return Joiner.on(TYPE_AND_RELATIVE_TIME_JOINER).join(type, relativeTime);
        } else {
            return relativeTime;
        }
    }

    private CharSequence getType(@Nullable PhoneNumber phoneNumber) {
        return phoneNumber != null ? phoneNumber.getReadableLabel(mContext.getResources()) : "";
    }
}
