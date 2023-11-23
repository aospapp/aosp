/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.healthconnect.permission;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.content.AttributionSource;
import android.content.Context;
import android.health.connect.HealthPermissions;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.RecordMapper;
import android.health.connect.internal.datatypes.utils.RecordTypePermissionCategoryMapper;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.server.healthconnect.storage.datatypehelpers.RecordHelper;
import com.android.server.healthconnect.storage.utils.RecordHelperProvider;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class to force caller of data apis to hold api required permissions.
 *
 * @hide
 */
public class DataPermissionEnforcer {
    private final PermissionManager mPermissionManager;
    private final Context mContext;

    public DataPermissionEnforcer(PermissionManager permissionManager, Context context) {
        mPermissionManager = permissionManager;
        mContext = context;
    }

    /** Enforces default write permissions for given recordTypeIds */
    public void enforceRecordIdsWritePermissions(
            List<Integer> recordTypeIds, AttributionSource attributionSource) {
        enforceRecordIdWritePermissionInternal(recordTypeIds, attributionSource);
    }

    /** Enforces default read permissions for given recordTypeIds */
    public void enforceRecordIdsReadPermissions(
            List<Integer> recordTypeIds, AttributionSource attributionSource) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthReadPermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));
            enforceRecordPermission(
                    permissionName, attributionSource, recordTypeId, /* isReadPermission= */ true);
        }
    }

    /**
     * Enforces that caller has either read or write permissions for given recordTypeId. Returns
     * flag which indicates that caller is allowed to read only records written by itself.
     */
    public boolean enforceReadAccessAndGetEnforceSelfRead(
            int recordTypeId, AttributionSource attributionSource) {
        boolean enforceSelfRead = false;
        try {
            enforceRecordIdsReadPermissions(
                    Collections.singletonList(recordTypeId), attributionSource);
        } catch (SecurityException readSecurityException) {
            try {
                enforceRecordIdsWritePermissions(
                        Collections.singletonList(recordTypeId), attributionSource);
                // Apps are always allowed to read self data if they have insert
                // permission.
                enforceSelfRead = true;
            } catch (SecurityException writeSecurityException) {
                throw readSecurityException;
            }
        }
        return enforceSelfRead;
    }

    /**
     * Enforces that caller has all write permissions to write given records. Includes permissions
     * for writing optional extra data if it's present in given records.
     */
    public void enforceRecordsWritePermissions(
            List<RecordInternal<?>> recordInternals, AttributionSource attributionSource) {
        Map<Integer, Set<String>> recordTypeIdToExtraPerms = new ArrayMap<>();

        for (RecordInternal<?> recordInternal : recordInternals) {
            int recordTypeId = recordInternal.getRecordType();
            RecordHelper<?> recordHelper =
                    RecordHelperProvider.getInstance().getRecordHelper(recordTypeId);

            if (!recordTypeIdToExtraPerms.containsKey(recordTypeId)) {
                recordTypeIdToExtraPerms.put(recordTypeId, new ArraySet<>());
            }

            recordHelper.checkRecordOperationsAreEnabled(recordInternal);
            recordTypeIdToExtraPerms
                    .get(recordTypeId)
                    .addAll(recordHelper.getRequiredExtraWritePermissions(recordInternal));
        }

        // Check main write permissions for given recordIds
        enforceRecordIdWritePermissionInternal(
                recordTypeIdToExtraPerms.keySet().stream().toList(), attributionSource);

        // Check extra write permissions for given records
        for (Integer recordTypeId : recordTypeIdToExtraPerms.keySet()) {
            for (String permissionName : recordTypeIdToExtraPerms.get(recordTypeId)) {
                enforceRecordPermission(
                        permissionName,
                        attributionSource,
                        recordTypeId,
                        /* isReadPermission= */ false);
            }
        }
    }

    /** Enforces that caller has any of given permissions. */
    public void enforceAnyOfPermissions(@NonNull String... permissions) {
        for (var permission : permissions) {
            if (mContext.checkCallingPermission(permission) == PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException(
                "Caller requires one of the following permissions: "
                        + String.join(", ", permissions));
    }

    /**
     * Collects extra read permissions to its grant state. Used to not expose extra data if caller
     * doesn't have corresponding permission.
     */
    public Map<String, Boolean> collectExtraReadPermissionToStateMapping(
            int recordTypeId, AttributionSource attributionSource) {
        RecordHelper<?> recordHelper =
                RecordHelperProvider.getInstance().getRecordHelper(recordTypeId);
        if (recordHelper.getExtraReadPermissions().isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Boolean> mapping = new ArrayMap<>();
        for (String permissionName : recordHelper.getExtraReadPermissions()) {
            mapping.put(permissionName, isPermissionGranted(permissionName, attributionSource));
        }
        return mapping;
    }

    public Map<String, Boolean> collectExtraWritePermissionStateMapping(
            List<RecordInternal<?>> recordInternals, AttributionSource attributionSource) {
        Map<String, Boolean> mapping = new ArrayMap<>();
        for (RecordInternal<?> recordInternal : recordInternals) {
            int recordTypeId = recordInternal.getRecordType();
            RecordHelper<?> recordHelper =
                    RecordHelperProvider.getInstance().getRecordHelper(recordTypeId);

            for (String permName : recordHelper.getExtraWritePermissions()) {
                mapping.put(permName, isPermissionGranted(permName, attributionSource));
            }
        }
        return mapping;
    }

    private void enforceRecordIdWritePermissionInternal(
            List<Integer> recordTypeIds, AttributionSource attributionSource) {
        for (Integer recordTypeId : recordTypeIds) {
            String permissionName =
                    HealthPermissions.getHealthWritePermission(
                            RecordTypePermissionCategoryMapper
                                    .getHealthPermissionCategoryForRecordType(recordTypeId));
            enforceRecordPermission(
                    permissionName, attributionSource, recordTypeId, /* isReadPermission= */ false);
        }
    }

    private void enforceRecordPermission(
            String permissionName,
            AttributionSource attributionSource,
            int recordTypeId,
            boolean isReadPermission) {
        if (!isPermissionGranted(permissionName, attributionSource)) {
            String prohibitedAction =
                    isReadPermission ? "to read to record type" : " to write to record type ";
            throw new SecurityException(
                    "Caller doesn't have "
                            + permissionName
                            + prohibitedAction
                            + RecordMapper.getInstance()
                                    .getRecordIdToExternalRecordClassMap()
                                    .get(recordTypeId));
        }
    }

    private boolean isPermissionGranted(
            String permissionName, AttributionSource attributionSource) {
        return mPermissionManager.checkPermissionForStartDataDelivery(
                        permissionName, attributionSource, null)
                == PERMISSION_GRANTED;
    }
}
