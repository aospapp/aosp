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

package com.android.server.healthconnect.storage.datatypehelpers;

import static com.android.server.healthconnect.storage.request.UpsertTableRequest.TYPE_STRING;
import static com.android.server.healthconnect.storage.utils.StorageUtils.DELIMITER;
import static com.android.server.healthconnect.storage.utils.StorageUtils.INTEGER_UNIQUE;
import static com.android.server.healthconnect.storage.utils.StorageUtils.PRIMARY;
import static com.android.server.healthconnect.storage.utils.StorageUtils.TEXT_NOT_NULL;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthDataCategory;
import android.health.connect.HealthPermissions;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Slog;

import com.android.server.healthconnect.permission.HealthConnectPermissionHelper;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.request.CreateTableRequest;
import com.android.server.healthconnect.storage.request.DeleteTableRequest;
import com.android.server.healthconnect.storage.request.ReadTableRequest;
import com.android.server.healthconnect.storage.request.UpsertTableRequest;
import com.android.server.healthconnect.storage.utils.StorageUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Helper class to get priority of the apps for each {@link HealthDataCategory}
 *
 * @hide
 */
public class HealthDataCategoryPriorityHelper {
    private static final String TABLE_NAME = "health_data_category_priority_table";
    private static final String HEALTH_DATA_CATEGORY_COLUMN_NAME = "health_data_category";
    public static final List<Pair<String, Integer>> UNIQUE_COLUMN_INFO =
            Collections.singletonList(new Pair<>(HEALTH_DATA_CATEGORY_COLUMN_NAME, TYPE_STRING));
    private static final String APP_ID_PRIORITY_ORDER_COLUMN_NAME = "app_id_priority_order";
    private static final String TAG = "HealthConnectPrioHelper";
    private static final String DEFAULT_APP_RESOURCE_NAME =
            "android:string/config_defaultHealthConnectApp";
    private static volatile HealthDataCategoryPriorityHelper sHealthDataCategoryPriorityHelper;

    /**
     * map of {@link HealthDataCategory} to list of app ids from {@link AppInfoHelper}, in the order
     * of their priority
     */
    private volatile ConcurrentHashMap<Integer, List<Long>> mHealthDataCategoryToAppIdPriorityMap;

    private HealthDataCategoryPriorityHelper() {}

    // Called on DB update.
    public void onUpgrade(int oldVersion, int newVersion, @NonNull SQLiteDatabase db) {
        // empty by default
    }

    /**
     * Returns a requests representing the tables that should be created corresponding to this
     * helper
     */
    @NonNull
    public final CreateTableRequest getCreateTableRequest() {
        return new CreateTableRequest(TABLE_NAME, getColumnInfo());
    }

    public synchronized void appendToPriorityList(
            @NonNull String packageName,
            @HealthDataCategory.Type int dataCategory,
            Context context) {
        List<Long> newPriorityOrder;
        getHealthDataCategoryToAppIdPriorityMap().putIfAbsent(dataCategory, new ArrayList<>());
        long appInfoId = AppInfoHelper.getInstance().getOrInsertAppInfoId(packageName, context);
        if (getHealthDataCategoryToAppIdPriorityMap().get(dataCategory).contains(appInfoId)) {
            return;
        }
        newPriorityOrder =
                new ArrayList<>(getHealthDataCategoryToAppIdPriorityMap().get(dataCategory));

        String defaultApp =
                context.getResources()
                        .getString(
                                Resources.getSystem()
                                        .getIdentifier(DEFAULT_APP_RESOURCE_NAME, null, null));
        if (Objects.equals(packageName, defaultApp)) {
            newPriorityOrder.add(0, appInfoId);
        } else {
            newPriorityOrder.add(appInfoId);
        }
        safelyUpdateDBAndUpdateCache(
                new UpsertTableRequest(
                        TABLE_NAME,
                        getContentValuesFor(dataCategory, newPriorityOrder),
                        UNIQUE_COLUMN_INFO),
                dataCategory,
                newPriorityOrder);
    }

    public synchronized void removeFromPriorityList(
            @NonNull String packageName,
            @HealthDataCategory.Type int dataCategory,
            HealthConnectPermissionHelper permissionHelper,
            UserHandle userHandle) {

        final List<String> grantedPermissions =
                permissionHelper.getGrantedHealthPermissions(packageName, userHandle);
        for (String permission : HealthPermissions.getWriteHealthPermissionsFor(dataCategory)) {
            if (grantedPermissions.contains(permission)) {
                return;
            }
        }
        removeFromPriorityListInternal(dataCategory, packageName);
    }

    public synchronized void removeFromPriorityListIfNeeded(
            @NonNull PackageInfo packageInfo, @NonNull Context context) {
        Set<Integer> dataCategoryWithPermission = new ArraySet<>();
        for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
            String currPerm = packageInfo.requestedPermissions[i];
            if (HealthConnectManager.isHealthPermission(context, currPerm)
                    && ((packageInfo.requestedPermissionsFlags[i]
                                    & PackageInfo.REQUESTED_PERMISSION_GRANTED)
                            != 0)) {
                int dataCategory = HealthPermissions.getHealthDataCategory(currPerm);
                if (dataCategory != -1) {
                    dataCategoryWithPermission.add(dataCategory);
                }
            }
        }
        for (int category : getHealthDataCategoryToAppIdPriorityMap().keySet()) {
            if (!dataCategoryWithPermission.contains(category)) {
                removeFromPriorityListInternal(category, packageInfo.packageName);
            }
        }
    }

    /** Removes app from priorityList for all HealthData Categories if the package is uninstalled */
    public synchronized void removeAppFromPriorityList(@NonNull String packageName) {
        Objects.requireNonNull(packageName);
        for (Integer dataCategory : getHealthDataCategoryToAppIdPriorityMap().keySet()) {
            removeFromPriorityListInternal(dataCategory, packageName);
        }
    }

    /** Returns list of package names based on priority for the input {@link HealthDataCategory} */
    @NonNull
    public List<String> getPriorityOrder(@HealthDataCategory.Type int type) {
        return AppInfoHelper.getInstance().getPackageNames(getAppIdPriorityOrder(type));
    }

    /** Returns list of App ids based on priority for the input {@link HealthDataCategory} */
    @NonNull
    public List<Long> getAppIdPriorityOrder(@HealthDataCategory.Type int type) {
        List<Long> packageIds = getHealthDataCategoryToAppIdPriorityMap().get(type);
        if (packageIds == null) {
            return Collections.emptyList();
        }

        return packageIds;
    }

    public void setPriorityOrder(int dataCategory, @NonNull List<String> packagePriorityOrder) {
        List<Long> currentPriorityOrder =
                getHealthDataCategoryToAppIdPriorityMap()
                        .getOrDefault(dataCategory, Collections.emptyList());
        List<Long> newPriorityOrder =
                AppInfoHelper.getInstance().getAppInfoIds(packagePriorityOrder);

        // Remove appId from the priority order if it is not part of the current priority order,
        // this is because in the time app tried to update the order an app permission might
        // have been removed, and we only store priority order of apps with permission.
        newPriorityOrder.removeIf(priorityOrder -> !currentPriorityOrder.contains(priorityOrder));
        newPriorityOrder.addAll(currentPriorityOrder);
        // Make sure we don't remove any new entries. So append old priority in new priority and
        // remove duplicates
        newPriorityOrder = newPriorityOrder.stream().distinct().collect(Collectors.toList());

        safelyUpdateDBAndUpdateCache(
                new UpsertTableRequest(
                        TABLE_NAME,
                        getContentValuesFor(dataCategory, newPriorityOrder),
                        UNIQUE_COLUMN_INFO),
                dataCategory,
                newPriorityOrder);
    }

    /** Deletes all entries from the database and clears the cache. */
    public synchronized void clearData(@NonNull TransactionManager transactionManager) {
        transactionManager.delete(new DeleteTableRequest(TABLE_NAME));
        clearCache();
    }

    public synchronized void clearCache() {
        mHealthDataCategoryToAppIdPriorityMap = null;
    }

    private Map<Integer, List<Long>> getHealthDataCategoryToAppIdPriorityMap() {
        if (mHealthDataCategoryToAppIdPriorityMap == null) {
            populateDataCategoryToAppIdPriorityMap();
        }

        return mHealthDataCategoryToAppIdPriorityMap;
    }

    /** Returns an immutable map of data categories along with their priority order. */
    public Map<Integer, List<Long>> getHealthDataCategoryToAppIdPriorityMapImmutable() {
        return Collections.unmodifiableMap(getHealthDataCategoryToAppIdPriorityMap());
    }

    private synchronized void populateDataCategoryToAppIdPriorityMap() {
        if (mHealthDataCategoryToAppIdPriorityMap != null) {
            return;
        }

        ConcurrentHashMap<Integer, List<Long>> healthDataCategoryToAppIdPriorityMap =
                new ConcurrentHashMap<>();
        final TransactionManager transactionManager = TransactionManager.getInitialisedInstance();
        try (Cursor cursor = transactionManager.read(new ReadTableRequest(TABLE_NAME))) {
            while (cursor.moveToNext()) {
                int dataCategory =
                        cursor.getInt(cursor.getColumnIndex(HEALTH_DATA_CATEGORY_COLUMN_NAME));
                List<Long> appIdsInOrder =
                        StorageUtils.getCursorLongList(
                                cursor, APP_ID_PRIORITY_ORDER_COLUMN_NAME, DELIMITER);

                healthDataCategoryToAppIdPriorityMap.put(dataCategory, appIdsInOrder);
            }
        }

        mHealthDataCategoryToAppIdPriorityMap = healthDataCategoryToAppIdPriorityMap;
    }

    private synchronized void safelyUpdateDBAndUpdateCache(
            UpsertTableRequest request,
            @HealthDataCategory.Type int dataCategory,
            List<Long> newList) {
        try {
            TransactionManager.getInitialisedInstance().insertOrReplace(request);
            getHealthDataCategoryToAppIdPriorityMap().put(dataCategory, newList);
        } catch (Exception e) {
            Slog.e(TAG, "Priority update failed", e);
            throw e;
        }
    }

    private synchronized void safelyUpdateDBAndUpdateCache(
            DeleteTableRequest request, @HealthDataCategory.Type int dataCategory) {
        try {
            TransactionManager.getInitialisedInstance().delete(request);
            getHealthDataCategoryToAppIdPriorityMap().remove(dataCategory);
        } catch (Exception e) {
            Slog.e(TAG, "Delete from priority DB failed: ", e);
            throw e;
        }
    }

    private ContentValues getContentValuesFor(
            @HealthDataCategory.Type int dataCategory, List<Long> priorityList) {
        ContentValues contentValues = new ContentValues();
        contentValues.put(HEALTH_DATA_CATEGORY_COLUMN_NAME, dataCategory);
        contentValues.put(
                APP_ID_PRIORITY_ORDER_COLUMN_NAME, StorageUtils.flattenLongList(priorityList));

        return contentValues;
    }

    /**
     * This implementation should return the column names with which the table should be created.
     *
     * <p>NOTE: New columns can only be added via onUpgrade. Why? Consider what happens if a table
     * already exists on the device
     *
     * <p>PLEASE DON'T USE THIS METHOD TO ADD NEW COLUMNS
     */
    @NonNull
    private List<Pair<String, String>> getColumnInfo() {
        ArrayList<Pair<String, String>> columnInfo = new ArrayList<>();
        columnInfo.add(new Pair<>(RecordHelper.PRIMARY_COLUMN_NAME, PRIMARY));
        columnInfo.add(new Pair<>(HEALTH_DATA_CATEGORY_COLUMN_NAME, INTEGER_UNIQUE));
        columnInfo.add(new Pair<>(APP_ID_PRIORITY_ORDER_COLUMN_NAME, TEXT_NOT_NULL));

        return columnInfo;
    }

    @NonNull
    public static synchronized HealthDataCategoryPriorityHelper getInstance() {
        if (sHealthDataCategoryPriorityHelper == null) {
            sHealthDataCategoryPriorityHelper = new HealthDataCategoryPriorityHelper();
        }

        return sHealthDataCategoryPriorityHelper;
    }

    private synchronized void removeFromPriorityListInternal(
            int dataCategory, @NonNull String packageName) {
        List<Long> newPriorityList =
                new ArrayList<>(
                        getHealthDataCategoryToAppIdPriorityMap()
                                .getOrDefault(dataCategory, Collections.emptyList()));
        if (newPriorityList.isEmpty()) {
            return;
        }

        newPriorityList.remove(AppInfoHelper.getInstance().getAppInfoId(packageName));
        if (newPriorityList.isEmpty()) {
            safelyUpdateDBAndUpdateCache(
                    new DeleteTableRequest(TABLE_NAME)
                            .setId(HEALTH_DATA_CATEGORY_COLUMN_NAME, String.valueOf(dataCategory)),
                    dataCategory);
            return;
        }

        safelyUpdateDBAndUpdateCache(
                new UpsertTableRequest(
                        TABLE_NAME,
                        getContentValuesFor(dataCategory, newPriorityList),
                        UNIQUE_COLUMN_INFO),
                dataCategory,
                newPriorityList);
    }
}
