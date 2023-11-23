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

package com.android.server.healthconnect.storage.request;

import android.annotation.NonNull;
import android.health.connect.aidl.ReadRecordsRequestParcel;

import com.android.server.healthconnect.storage.datatypehelpers.RecordHelper;
import com.android.server.healthconnect.storage.utils.RecordHelperProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Refines a request from what the user sent to a format that makes the most sense for the
 * TransactionManager.
 *
 * <p>Notes, This class refines the queries the records stored in the DB
 *
 * @hide
 */
public class ReadTransactionRequest {
    public static final String TYPE_NOT_PRESENT_PACKAGE_NAME = "package_name";
    private final List<ReadTableRequest> mReadTableRequests;

    public ReadTransactionRequest(
            String packageName,
            ReadRecordsRequestParcel request,
            long startDateAccess,
            boolean enforceSelfRead,
            Map<String, Boolean> extraReadPermsMapping) {
        RecordHelper<?> recordHelper =
                RecordHelperProvider.getInstance().getRecordHelper(request.getRecordType());
        mReadTableRequests =
                Collections.singletonList(
                        recordHelper.getReadTableRequest(
                                request,
                                packageName,
                                enforceSelfRead,
                                startDateAccess,
                                extraReadPermsMapping));
    }

    public ReadTransactionRequest(
            Map<Integer, List<UUID>> recordTypeToUuids, long startDateAccess) {
        mReadTableRequests = new ArrayList<>();
        recordTypeToUuids.forEach(
                (recordType, uuids) ->
                        mReadTableRequests.add(
                                RecordHelperProvider.getInstance()
                                        .getRecordHelper(recordType)
                                        .getReadTableRequest(uuids, startDateAccess)));
    }

    @NonNull
    public List<ReadTableRequest> getReadRequests() {
        return mReadTableRequests;
    }
}
