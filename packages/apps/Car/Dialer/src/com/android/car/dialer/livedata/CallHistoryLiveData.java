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

package com.android.car.dialer.livedata;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.text.TextUtils;

import androidx.annotation.IntDef;

import com.android.car.dialer.R;
import com.android.car.telephony.common.AsyncQueryLiveData;
import com.android.car.telephony.common.PhoneCallLog;
import com.android.car.telephony.common.QueryParam;

import java.util.ArrayList;
import java.util.List;

/**
 * Live data which loads call history.
 */
public class CallHistoryLiveData extends AsyncQueryLiveData<List<PhoneCallLog>> {
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    @IntDef({
            CallType.CALL_TYPE_ALL,
            CallType.INCOMING_TYPE,
            CallType.OUTGOING_TYPE,
            CallType.MISSED_TYPE,
    })
    public @interface CallType {
        int CALL_TYPE_ALL = -1;
        int INCOMING_TYPE = CallLog.Calls.INCOMING_TYPE;
        int OUTGOING_TYPE = CallLog.Calls.OUTGOING_TYPE;
        int MISSED_TYPE = CallLog.Calls.MISSED_TYPE;
        int VOICEMAIL_TYPE = CallLog.Calls.VOICEMAIL_TYPE;
    }

    /**
     * Creates a new instance of call history live data which loads all types of call history
     * with a limit of 100 logs.
     */
    public static CallHistoryLiveData newInstance(Context context, String accountName) {
        return newInstance(
                context,
                CallType.CALL_TYPE_ALL,
                context.getResources().getInteger(R.integer.config_phone_call_log_limit),
                accountName);
    }

    private static CallHistoryLiveData newInstance(Context context, int callType, int limit,
            String accountName) {
        StringBuilder where = new StringBuilder();
        List<String> selectionArgs = new ArrayList<>();
        limit = Math.max(limit, 0);

        if (callType != CallType.CALL_TYPE_ALL) {
            // add a filter for call type
            where.append(CallLog.Calls.TYPE + " = ?");
            selectionArgs.add(Integer.toString(callType));
            where.append(" AND ");
        }

        if (TextUtils.isEmpty(accountName)) {
            where.append(CallLog.Calls.PHONE_ACCOUNT_ID + " IS NULL");
        } else {
            where.append(CallLog.Calls.PHONE_ACCOUNT_ID + " = ?");
            selectionArgs.add(accountName);
        }

        Uri uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY,
                        Integer.toString(limit))
                .build();
        QueryParam queryParam = new QueryParam(
                uri,
                null,
                where.toString(),
                selectionArgs.toArray(EMPTY_STRING_ARRAY),
                CallLog.Calls.DEFAULT_SORT_ORDER,
                Manifest.permission.READ_CALL_LOG);
        return new CallHistoryLiveData(context, queryParam);
    }

    private final Context mContext;

    private CallHistoryLiveData(Context context, QueryParam queryParam) {
        super(context, QueryParam.of(queryParam));
        mContext = context;
    }

    @Override
    protected List<PhoneCallLog> convertToEntity(Cursor cursor) {
        List<PhoneCallLog> resultList = new ArrayList<>();

        PhoneCallLog lastPhoneCallLog = null;
        while (cursor.moveToNext()) {
            PhoneCallLog phoneCallLog = PhoneCallLog.fromCursor(mContext, cursor);

            if (lastPhoneCallLog == null
                    || !lastPhoneCallLog.merge(phoneCallLog, /*checkTimeRange=*/true)) {
                resultList.add(phoneCallLog);
                lastPhoneCallLog = phoneCallLog;
            }
        }
        return resultList;
    }
}
