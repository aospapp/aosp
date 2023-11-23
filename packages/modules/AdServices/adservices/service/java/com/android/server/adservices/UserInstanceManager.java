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

package com.android.server.adservices;

import android.annotation.NonNull;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.adservices.consent.AppConsentManager;
import com.android.server.adservices.consent.ConsentManager;
import com.android.server.adservices.data.topics.TopicsDao;
import com.android.server.adservices.rollback.RollbackHandlingManager;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Manager to handle User Instance. This is to ensure that each user profile is isolated.
 *
 * @hide
 */
public class UserInstanceManager {

    private final Object mLock = new Object();

    // We have 1 ConsentManager per user/user profile. This is to isolate user's data.
    @GuardedBy("mLock")
    private final Map<Integer, ConsentManager> mConsentManagerMapLocked = new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<Integer, AppConsentManager> mAppConsentManagerMapLocked = new ArrayMap<>();


    @GuardedBy("UserInstanceManager.class")
    private final Map<Integer, BlockedTopicsManager> mBlockedTopicsManagerMapLocked =
            new ArrayMap<>();

    // We have 1 RollbackManager per user/user profile, to isolate each user's data.
    @GuardedBy("mLock")
    private final Map<Integer, RollbackHandlingManager> mRollbackHandlingManagerMapLocked =
            new ArrayMap<>();

    private final String mAdServicesBaseDir;

    private final TopicsDao mTopicsDao;

    UserInstanceManager(@NonNull TopicsDao topicsDao, @NonNull String adServicesBaseDir) {
        mTopicsDao = topicsDao;
        mAdServicesBaseDir = adServicesBaseDir;
    }

    @NonNull
    ConsentManager getOrCreateUserConsentManagerInstance(int userIdentifier) throws IOException {
        synchronized (mLock) {
            ConsentManager instance = getUserConsentManagerInstance(userIdentifier);
            if (instance == null) {
                instance = ConsentManager.createConsentManager(mAdServicesBaseDir, userIdentifier);
                mConsentManagerMapLocked.put(userIdentifier, instance);
            }
            return instance;
        }
    }

    @NonNull
    AppConsentManager getOrCreateUserAppConsentManagerInstance(int userIdentifier)
            throws IOException {
        synchronized (mLock) {
            AppConsentManager instance = mAppConsentManagerMapLocked.get(userIdentifier);
            if (instance == null) {
                instance =
                        AppConsentManager.createAppConsentManager(
                                mAdServicesBaseDir, userIdentifier);
                mAppConsentManagerMapLocked.put(userIdentifier, instance);
            }
            return instance;
        }
    }

    @NonNull
    BlockedTopicsManager getOrCreateUserBlockedTopicsManagerInstance(int userIdentifier) {
        synchronized (UserInstanceManager.class) {
            BlockedTopicsManager instance = mBlockedTopicsManagerMapLocked.get(userIdentifier);
            if (instance == null) {
                instance = new BlockedTopicsManager(mTopicsDao, userIdentifier);
                mBlockedTopicsManagerMapLocked.put(userIdentifier, instance);
            }
            return instance;
        }
    }

    @NonNull
    RollbackHandlingManager getOrCreateUserRollbackHandlingManagerInstance(
            int userIdentifier, int packageVersion) throws IOException {
        synchronized (mLock) {
            RollbackHandlingManager instance =
                    mRollbackHandlingManagerMapLocked.get(userIdentifier);
            if (instance == null) {
                instance =
                        RollbackHandlingManager.createRollbackHandlingManager(
                                mAdServicesBaseDir, userIdentifier, packageVersion);
                mRollbackHandlingManagerMapLocked.put(userIdentifier, instance);
            }
            return instance;
        }
    }

    @VisibleForTesting
    ConsentManager getUserConsentManagerInstance(int userIdentifier) {
        synchronized (mLock) {
            return mConsentManagerMapLocked.get(userIdentifier);
        }
    }

    /**
     * Deletes the user instance and remove the user consent related data. This will delete the
     * directory: /data/system/adservices/user_id
     */
    void deleteUserInstance(int userIdentifier) throws Exception {
        synchronized (mLock) {
            ConsentManager instance = mConsentManagerMapLocked.get(userIdentifier);
            if (instance != null) {
                String userDirectoryPath = mAdServicesBaseDir + "/" + userIdentifier;
                final Path packageDir = Paths.get(userDirectoryPath);
                if (Files.exists(packageDir)) {
                    if (!instance.deleteUserDirectory(new File(userDirectoryPath))) {
                        LogUtil.e("Failed to delete " + userDirectoryPath);
                    }
                }
                mConsentManagerMapLocked.remove(userIdentifier);
            }

            // Delete all data in the database that belongs to this user
            mTopicsDao.clearAllBlockedTopicsOfUser(userIdentifier);
        }
    }

    void dump(PrintWriter writer, String[] args) {
        writer.println("UserInstanceManager");
        String prefix = "  ";
        writer.printf("%smAdServicesBaseDir: %s\n", prefix, mAdServicesBaseDir);
        synchronized (mLock) {
            writer.printf("%smConsentManagerMapLocked: %s\n", prefix, mConsentManagerMapLocked);
            writer.printf(
                    "%smAppConsentManagerMapLocked: %s\n", prefix, mAppConsentManagerMapLocked);
            writer.printf(
                    "%smRollbackHandlingManagerMapLocked: %s\n",
                    prefix, mRollbackHandlingManagerMapLocked);
        }
        synchronized (UserInstanceManager.class) {
            writer.printf(
                    "%smBlockedTopicsManagerMapLocked=%s\n",
                    prefix, mBlockedTopicsManagerMapLocked);
        }

        mTopicsDao.dump(writer, prefix, args);
    }

    @VisibleForTesting
    void tearDownForTesting() {
        synchronized (mLock) {
            for (ConsentManager consentManager : mConsentManagerMapLocked.values()) {
                consentManager.tearDownForTesting();
            }
            for (AppConsentManager appConsentManager : mAppConsentManagerMapLocked.values()) {
                appConsentManager.tearDownForTesting();
            }
            for (RollbackHandlingManager rollbackHandlingManager :
                    mRollbackHandlingManagerMapLocked.values()) {
                rollbackHandlingManager.tearDownForTesting();
            }
        }
    }
}
