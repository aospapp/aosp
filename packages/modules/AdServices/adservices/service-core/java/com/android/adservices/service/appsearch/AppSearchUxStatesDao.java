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

package com.android.adservices.service.appsearch;

import android.annotation.NonNull;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.appsearch.annotation.Document;
import androidx.appsearch.app.AppSearchSchema.StringPropertyConfig;
import androidx.appsearch.app.GlobalSearchSession;

import com.android.adservices.LogUtil;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Objects;
import java.util.concurrent.Executor;

/** This class represents the data access object for the UX states written to AppSearch. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
@Document
class AppSearchUxStatesDao extends AppSearchDao {
    public static final String NAMESPACE = "uxstates";

    // Column name used for preparing the query string, are not part of the @Document.
    private static final String USER_ID_COLNAME = "userId";

    /**
     * Identifier of the Consent Document; must be unique within the Document's `namespace`. This is
     * the row ID for consent data. It is a combination of user ID and api type.
     */
    @Document.Id private final String mId;

    @Document.StringProperty(indexingType = StringPropertyConfig.INDEXING_TYPE_EXACT_TERMS)
    private final String mUserId;

    /** Namespace of the Consent Document. Used to group documents during querying or deletion. */
    @Document.Namespace private final String mNamespace;

    @Document.BooleanProperty private boolean mIsEntryPointEnabled;
    @Document.BooleanProperty private boolean mIsU18Account;
    @Document.BooleanProperty private boolean mIsAdultAccount;
    @Document.BooleanProperty private boolean mIsAdIdEnabled;
    @Document.BooleanProperty private boolean mWasU18NotificationDisplayed;

    AppSearchUxStatesDao(
            @NonNull String id,
            @NonNull String userId,
            @NonNull String namespace,
            @NonNull boolean isEntryPointEnabled,
            @NonNull boolean isU18Account,
            @NonNull boolean isAdultAccount,
            @NonNull boolean isAdIdEnabled,
            @NonNull boolean wasU18NotificationDisplayed) {
        this.mId = id;
        this.mUserId = userId;
        this.mNamespace = namespace;
        this.mIsEntryPointEnabled = isEntryPointEnabled;
        this.mIsU18Account = isU18Account;
        this.mIsAdultAccount = isAdultAccount;
        this.mIsAdIdEnabled = isAdIdEnabled;
        this.mWasU18NotificationDisplayed = wasU18NotificationDisplayed;
    }

    /** Returns the row ID that should be unique for the namespace. */
    public static String getRowId(@NonNull String uid) {
        return uid;
    }

    /**
     * Read the UX states from AppSearch.
     *
     * @param searchSession we use GlobalSearchSession here to allow AdServices to read.
     * @param executor the Executor to use.
     * @param userId the user ID for the query.
     * @return whether the row is consented for this user ID and apiType.
     */
    public static AppSearchUxStatesDao readData(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        Objects.requireNonNull(searchSession);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(userId);

        String query = getQuery(userId);
        AppSearchUxStatesDao dao =
                AppSearchDao.readConsentData(
                        AppSearchUxStatesDao.class, searchSession, executor, NAMESPACE, query);
        LogUtil.d("AppSearch UX states read: " + dao + " [ query: " + query + "]");
        return dao;
    }

    // Get the search query for AppSearch. Format specified at http://shortn/_RwVKmB74f3.
    // Note: AND as an operator is not supported by AppSearch on S or T.
    @VisibleForTesting
    static String getQuery(String userId) {
        return USER_ID_COLNAME + ":" + userId;
    }

    /**
     * Get the row ID for this row.
     *
     * @return ID
     */
    public String getId() {
        return mId;
    }

    /**
     * Get the user ID for this row.
     *
     * @return user ID
     */
    public String getUserId() {
        return mUserId;
    }

    /**
     * Get the namespace for this row.
     *
     * @return nameespace
     */
    public String getNamespace() {
        return mNamespace;
    }

    @NonNull
    public boolean isEntryPointEnabled() {
        return mIsEntryPointEnabled;
    }

    @NonNull
    public void setEntryPointEnabled(boolean isEntryPointEnabled) {
        mIsEntryPointEnabled = isEntryPointEnabled;
    }

    @NonNull
    public boolean isU18Account() {
        return mIsU18Account;
    }

    @NonNull
    public void setU18Account(boolean isU18Account) {
        mIsU18Account = isU18Account;
    }

    @NonNull
    public boolean isAdultAccount() {
        return mIsAdultAccount;
    }

    @NonNull
    public void setAdultAccount(boolean isAdultAccount) {
        mIsAdultAccount = isAdultAccount;
    }

    @NonNull
    public boolean isAdIdEnabled() {
        return mIsAdIdEnabled;
    }

    @NonNull
    public void setAdIdEnabled(boolean isAdIdEnabled) {
        mIsAdIdEnabled = isAdIdEnabled;
    }

    @NonNull
    public boolean wasU18NotificationDisplayed() {
        return mWasU18NotificationDisplayed;
    }

    @NonNull
    public void setU18NotificationDisplayed(boolean wasU18NotificationDisplayed) {
        mWasU18NotificationDisplayed = wasU18NotificationDisplayed;
    }

    public String toString() {
        return "id="
                + mId
                + "; userId="
                + mUserId
                + "; namespace="
                + mNamespace
                + "; isEntryPointEnabled="
                + mIsEntryPointEnabled
                + "; isU18Account="
                + mIsU18Account
                + "; isAdultAccount="
                + mIsAdultAccount
                + "; isAdIdEnabled="
                + mIsAdIdEnabled
                + "; wasU18NotificationDisplayed="
                + mWasU18NotificationDisplayed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mId, mUserId, mNamespace, mIsEntryPointEnabled, mIsU18Account, mIsAdultAccount, mIsAdIdEnabled, mWasU18NotificationDisplayed);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppSearchUxStatesDao)) return false;
        AppSearchUxStatesDao obj = (AppSearchUxStatesDao) o;
        return (Objects.equals(this.mId, obj.mId))
                && (Objects.equals(this.mUserId, obj.mUserId))
                && (Objects.equals(this.mNamespace, obj.mNamespace))
                && this.mIsEntryPointEnabled == obj.mIsEntryPointEnabled
                && this.mIsU18Account == obj.mIsU18Account
                && this.mIsAdultAccount == obj.mIsAdultAccount
                && this.mIsAdIdEnabled == obj.mIsAdIdEnabled
                && this.mWasU18NotificationDisplayed == obj.mWasU18NotificationDisplayed;
    }

    /** Read the isAdIdEnabled bit from AppSearch. */
    public static boolean readIsAdIdEnabled(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        AppSearchUxStatesDao dao = readData(searchSession, executor, userId);
        if (dao == null) {
            return false;
        }
        return dao.isAdIdEnabled();
    }

    /** Read the isU18Account bit from AppSearch. */
    public static boolean readIsU18Account(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        AppSearchUxStatesDao dao = readData(searchSession, executor, userId);
        if (dao == null) {
            return false;
        }
        return dao.isU18Account();
    }

    /** Read the isEntryPointEnabled bit from AppSearch. */
    public static boolean readIsEntryPointEnabled(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        AppSearchUxStatesDao dao = readData(searchSession, executor, userId);
        if (dao == null) {
            return false;
        }
        return dao.isEntryPointEnabled();
    }

    /** Read the isAdultAccount bit from AppSearch. */
    public static boolean readIsAdultAccount(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        AppSearchUxStatesDao dao = readData(searchSession, executor, userId);
        if (dao == null) {
            return false;
        }
        return dao.isAdultAccount();
    }

    /** Read the wasU18NotificationDisplayed bit from AppSearch. */
    public static boolean readIsU18NotificationDisplayed(
            @NonNull ListenableFuture<GlobalSearchSession> searchSession,
            @NonNull Executor executor,
            @NonNull String userId) {
        AppSearchUxStatesDao dao = readData(searchSession, executor, userId);
        if (dao == null) {
            return false;
        }
        return dao.wasU18NotificationDisplayed();
    }
}
