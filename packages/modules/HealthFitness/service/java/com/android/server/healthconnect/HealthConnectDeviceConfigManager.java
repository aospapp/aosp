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

package com.android.server.healthconnect;

import android.annotation.NonNull;
import android.content.Context;
import android.health.connect.ratelimiter.RateLimiter;
import android.health.connect.ratelimiter.RateLimiter.QuotaBucket;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Singleton class to provide values and listen changes of settings flags.
 *
 * @hide
 */
public class HealthConnectDeviceConfigManager implements DeviceConfig.OnPropertiesChangedListener {
    private static Set<String> sFlagsToTrack = new ArraySet<>();
    private static final String EXERCISE_ROUTE_FEATURE_FLAG = "exercise_routes_enable";
    public static final String ENABLE_RATE_LIMITER_FLAG = "enable_rate_limiter";
    private static final String MAX_READ_REQUESTS_PER_24H_FOREGROUND_FLAG =
            "max_read_requests_per_24h_foreground";
    private static final String MAX_READ_REQUESTS_PER_24H_BACKGROUND_FLAG =
            "max_read_requests_per_24h_background";
    private static final String MAX_READ_REQUESTS_PER_15M_FOREGROUND_FLAG =
            "max_read_requests_per_15m_foreground";
    private static final String MAX_READ_REQUESTS_PER_15M_BACKGROUND_FLAG =
            "max_read_requests_per_15m_background";
    private static final String MAX_WRITE_REQUESTS_PER_24H_FOREGROUND_FLAG =
            "max_write_requests_per_24h_foreground";
    private static final String MAX_WRITE_REQUESTS_PER_24H_BACKGROUND_FLAG =
            "max_write_requests_per_24h_background";
    private static final String MAX_WRITE_REQUESTS_PER_15M_FOREGROUND_FLAG =
            "max_write_requests_per_15m_foreground";
    private static final String MAX_WRITE_REQUESTS_PER_15M_BACKGROUND_FLAG =
            "max_write_requests_per_15m_background";
    private static final String MAX_WRITE_CHUNK_SIZE_FLAG = "max_write_chunk_size";
    private static final String MAX_WRITE_SINGLE_RECORD_SIZE_FLAG = "max_write_single_record_size";

    // Flag to enable/disable sleep and exercise sessions.
    private static final String SESSION_DATATYPE_FEATURE_FLAG = "session_types_enable";

    @VisibleForTesting
    public static final String COUNT_MIGRATION_STATE_IN_PROGRESS_FLAG =
            "count_migration_state_in_progress";

    @VisibleForTesting
    public static final String COUNT_MIGRATION_STATE_ALLOWED_FLAG = "count_migration_state_allowed";

    @VisibleForTesting
    public static final String MAX_START_MIGRATION_CALLS_ALLOWED_FLAG =
            "max_start_migration_calls_allowed";

    @VisibleForTesting
    public static final String IDLE_STATE_TIMEOUT_DAYS_FLAG = "idle_state_timeout_days";

    @VisibleForTesting
    public static final String NON_IDLE_STATE_TIMEOUT_DAYS_FLAG = "non_idle_state_timeout_days";

    @VisibleForTesting
    public static final String IN_PROGRESS_STATE_TIMEOUT_HOURS_FLAG =
            "in_progress_state_timeout_hours";

    @VisibleForTesting
    public static final String EXECUTION_TIME_BUFFER_MINUTES_FLAG = "execution_time_buffer_minutes";

    @VisibleForTesting
    public static final String MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_FLAG =
            "migration_completion_job_run_interval_days";

    @VisibleForTesting
    public static final String MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_FLAG =
            "migration_pause_job_run_interval_hours";

    @VisibleForTesting
    public static final String ENABLE_PAUSE_STATE_CHANGE_JOBS_FLAG =
            "enable_pause_state_change_jobs";

    @VisibleForTesting
    public static final String ENABLE_COMPLETE_STATE_CHANGE_JOBS_FLAG =
            "enable_complete_state_change_jobs";

    @VisibleForTesting
    public static final String ENABLE_MIGRATION_NOTIFICATIONS_FLAG =
            "enable_migration_notifications";

    private static final boolean SESSION_DATATYPE_DEFAULT_FLAG_VALUE = true;
    private static final boolean EXERCISE_ROUTE_DEFAULT_FLAG_VALUE = true;
    public static final boolean ENABLE_RATE_LIMITER_DEFAULT_FLAG_VALUE = true;
    public static final int QUOTA_BUCKET_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE = 1000;
    public static final int QUOTA_BUCKET_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE = 8000;
    public static final int QUOTA_BUCKET_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE = 1000;
    public static final int QUOTA_BUCKET_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE = 8000;
    public static final int CHUNK_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE = 5000000;
    public static final int RECORD_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE = 1000000;

    @VisibleForTesting
    public static final int MIGRATION_STATE_IN_PROGRESS_COUNT_DEFAULT_FLAG_VALUE = 5;

    @VisibleForTesting public static final int MIGRATION_STATE_ALLOWED_COUNT_DEFAULT_FLAG_VALUE = 5;
    @VisibleForTesting public static final int MAX_START_MIGRATION_CALLS_DEFAULT_FLAG_VALUE = 6;
    @VisibleForTesting public static final int IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE = 120;
    @VisibleForTesting public static final int NON_IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE = 15;

    @VisibleForTesting
    public static final int IN_PROGRESS_STATE_TIMEOUT_HOURS_DEFAULT_FLAG_VALUE = 12;

    @VisibleForTesting
    public static final int EXECUTION_TIME_BUFFER_MINUTES_DEFAULT_FLAG_VALUE = 30;

    @VisibleForTesting
    public static final int MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_DEFAULT_FLAG_VALUE = 1;

    @VisibleForTesting
    public static final int MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_DEFAULT_FLAG_VALUE = 4;

    @VisibleForTesting
    public static final boolean ENABLE_PAUSE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE = true;

    @VisibleForTesting
    public static final boolean ENABLE_COMPLETE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE = false;

    @VisibleForTesting
    public static final boolean ENABLE_MIGRATION_NOTIFICATIONS_DEFAULT_FLAG_VALUE = true;

    private static HealthConnectDeviceConfigManager sDeviceConfigManager;
    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
    private static final String HEALTH_FITNESS_NAMESPACE = DeviceConfig.NAMESPACE_HEALTH_FITNESS;

    @GuardedBy("mLock")
    private boolean mExerciseRouteEnabled =
            DeviceConfig.getBoolean(
                    HEALTH_FITNESS_NAMESPACE,
                    EXERCISE_ROUTE_FEATURE_FLAG,
                    EXERCISE_ROUTE_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private boolean mSessionDatatypeEnabled =
            DeviceConfig.getBoolean(
                    HEALTH_FITNESS_NAMESPACE,
                    SESSION_DATATYPE_FEATURE_FLAG,
                    SESSION_DATATYPE_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mMigrationStateInProgressCount =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    COUNT_MIGRATION_STATE_IN_PROGRESS_FLAG,
                    MIGRATION_STATE_IN_PROGRESS_COUNT_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mMigrationStateAllowedCount =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    COUNT_MIGRATION_STATE_ALLOWED_FLAG,
                    MIGRATION_STATE_ALLOWED_COUNT_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mMaxStartMigrationCalls =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    MAX_START_MIGRATION_CALLS_ALLOWED_FLAG,
                    MAX_START_MIGRATION_CALLS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mIdleStateTimeoutPeriod =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    IDLE_STATE_TIMEOUT_DAYS_FLAG,
                    IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mNonIdleStateTimeoutPeriod =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    NON_IDLE_STATE_TIMEOUT_DAYS_FLAG,
                    NON_IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mInProgressStateTimeoutPeriod =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    IN_PROGRESS_STATE_TIMEOUT_HOURS_FLAG,
                    IN_PROGRESS_STATE_TIMEOUT_HOURS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mExecutionTimeBuffer =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    EXECUTION_TIME_BUFFER_MINUTES_FLAG,
                    EXECUTION_TIME_BUFFER_MINUTES_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mMigrationCompletionJobRunInterval =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_FLAG,
                    MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private int mMigrationPauseJobRunInterval =
            DeviceConfig.getInt(
                    HEALTH_FITNESS_NAMESPACE,
                    MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_FLAG,
                    MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private boolean mEnablePauseStateChangeJob =
            DeviceConfig.getBoolean(
                    HEALTH_FITNESS_NAMESPACE,
                    ENABLE_PAUSE_STATE_CHANGE_JOBS_FLAG,
                    ENABLE_PAUSE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private boolean mEnableCompleteStateChangeJob =
            DeviceConfig.getBoolean(
                    HEALTH_FITNESS_NAMESPACE,
                    ENABLE_COMPLETE_STATE_CHANGE_JOBS_FLAG,
                    ENABLE_COMPLETE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE);

    @GuardedBy("mLock")
    private boolean mEnableMigrationNotifications =
            DeviceConfig.getBoolean(
                    HEALTH_FITNESS_NAMESPACE,
                    ENABLE_MIGRATION_NOTIFICATIONS_FLAG,
                    ENABLE_MIGRATION_NOTIFICATIONS_DEFAULT_FLAG_VALUE);

    @NonNull
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static void initializeInstance(Context context) {
        if (sDeviceConfigManager == null) {
            sDeviceConfigManager = new HealthConnectDeviceConfigManager();
            DeviceConfig.addOnPropertiesChangedListener(
                    HEALTH_FITNESS_NAMESPACE, context.getMainExecutor(), sDeviceConfigManager);
            addFlagsToTrack();
        }
    }

    /** Returns initialised instance of this class. */
    @NonNull
    public static HealthConnectDeviceConfigManager getInitialisedInstance() {
        Objects.requireNonNull(sDeviceConfigManager);

        return sDeviceConfigManager;
    }

    /** Adds flags that need to be updated if their values are changed on the server. */
    private static void addFlagsToTrack() {
        sFlagsToTrack.add(EXERCISE_ROUTE_FEATURE_FLAG);
        sFlagsToTrack.add(SESSION_DATATYPE_FEATURE_FLAG);
        sFlagsToTrack.add(ENABLE_RATE_LIMITER_FLAG);
        sFlagsToTrack.add(COUNT_MIGRATION_STATE_IN_PROGRESS_FLAG);
        sFlagsToTrack.add(COUNT_MIGRATION_STATE_ALLOWED_FLAG);
        sFlagsToTrack.add(MAX_START_MIGRATION_CALLS_ALLOWED_FLAG);
        sFlagsToTrack.add(IDLE_STATE_TIMEOUT_DAYS_FLAG);
        sFlagsToTrack.add(NON_IDLE_STATE_TIMEOUT_DAYS_FLAG);
        sFlagsToTrack.add(IN_PROGRESS_STATE_TIMEOUT_HOURS_FLAG);
        sFlagsToTrack.add(EXECUTION_TIME_BUFFER_MINUTES_FLAG);
        sFlagsToTrack.add(MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_FLAG);
        sFlagsToTrack.add(MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_FLAG);
        sFlagsToTrack.add(ENABLE_PAUSE_STATE_CHANGE_JOBS_FLAG);
        sFlagsToTrack.add(ENABLE_COMPLETE_STATE_CHANGE_JOBS_FLAG);
        sFlagsToTrack.add(ENABLE_MIGRATION_NOTIFICATIONS_FLAG);
    }

    /** Returns if operations with exercise route are enabled. */
    public boolean isExerciseRouteFeatureEnabled() {
        mLock.readLock().lock();
        try {
            return mExerciseRouteEnabled;
        } finally {
            mLock.readLock().unlock();
        }
    }

    @GuardedBy("mLock")
    private boolean mRateLimiterEnabled =
            DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                    ENABLE_RATE_LIMITER_FLAG,
                    ENABLE_RATE_LIMITER_DEFAULT_FLAG_VALUE);

    /** Returns if operations with sessions datatypes are enabled. */
    public boolean isSessionDatatypeFeatureEnabled() {
        mLock.readLock().lock();
        try {
            return mSessionDatatypeEnabled;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * Returns the required count for {@link
     * android.health.connect.HealthConnectDataState.MIGRATION_STATE_IN_PROGRESS}.
     */
    public int getMigrationStateInProgressCount() {
        mLock.readLock().lock();
        try {
            return mMigrationStateInProgressCount;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * Returns the required count for {@link
     * android.health.connect.HealthConnectDataState.MIGRATION_STATE_ALLOWED}.
     */
    public int getMigrationStateAllowedCount() {
        mLock.readLock().lock();
        try {
            return mMigrationStateAllowedCount;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns the maximum number of start migration calls allowed. */
    public int getMaxStartMigrationCalls() {
        mLock.readLock().lock();
        try {
            return mMaxStartMigrationCalls;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * Returns the timeout period of {@link
     * android.health.connect.HealthConnectDataState.MIGRATION_STATE_IDLE}.
     */
    public Duration getIdleStateTimeoutPeriod() {
        mLock.readLock().lock();
        try {
            return Duration.ofDays(mIdleStateTimeoutPeriod);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns the timeout period of non-idle migration states. */
    public Duration getNonIdleStateTimeoutPeriod() {
        mLock.readLock().lock();
        try {
            return Duration.ofDays(mNonIdleStateTimeoutPeriod);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /**
     * Returns the timeout period of {@link
     * android.health.connect.HealthConnectDataState.MIGRATION_STATE_IN_PROGRESS}.
     */
    public Duration getInProgressStateTimeoutPeriod() {
        mLock.readLock().lock();
        try {
            return Duration.ofHours(mInProgressStateTimeoutPeriod);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns the time buffer kept to ensure that job execution is not skipped. */
    public long getExecutionTimeBuffer() {
        mLock.readLock().lock();
        try {
            return TimeUnit.MINUTES.toMillis(mExecutionTimeBuffer);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns the time interval at which the migration completion job will run periodically. */
    public long getMigrationCompletionJobRunInterval() {
        mLock.readLock().lock();
        try {
            return TimeUnit.DAYS.toMillis(mMigrationCompletionJobRunInterval);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns the time interval at which the migration pause job will run periodically. */
    public long getMigrationPauseJobRunInterval() {
        mLock.readLock().lock();
        try {
            return TimeUnit.HOURS.toMillis(mMigrationPauseJobRunInterval);
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns if migration pause change jobs are enabled. */
    public boolean isPauseStateChangeJobEnabled() {
        mLock.readLock().lock();
        try {
            return mEnablePauseStateChangeJob;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns if migration completion jobs are enabled. */
    public boolean isCompleteStateChangeJobEnabled() {
        mLock.readLock().lock();
        try {
            return mEnableCompleteStateChangeJob;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Returns if migration notifications are enabled. */
    public boolean areMigrationNotificationsEnabled() {
        mLock.readLock().lock();
        try {
            return mEnableMigrationNotifications;
        } finally {
            mLock.readLock().unlock();
        }
    }

    /** Updates rate limiting quota values. */
    public void updateRateLimiterValues() {
        Map<Integer, Integer> quotaBucketToMaxApiCallQuotaMap = new HashMap<>();
        Map<String, Integer> quotaBucketToMaxMemoryQuotaMap = new HashMap<>();
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_24H_FOREGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_READ_REQUESTS_PER_24H_FOREGROUND_FLAG,
                        QUOTA_BUCKET_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_24H_BACKGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_READ_REQUESTS_PER_24H_BACKGROUND_FLAG,
                        QUOTA_BUCKET_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_15M_FOREGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_READ_REQUESTS_PER_15M_FOREGROUND_FLAG,
                        QUOTA_BUCKET_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_READS_PER_15M_BACKGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_READ_REQUESTS_PER_15M_BACKGROUND_FLAG,
                        QUOTA_BUCKET_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_FOREGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_REQUESTS_PER_24H_FOREGROUND_FLAG,
                        QUOTA_BUCKET_PER_24H_FOREGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_24H_BACKGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_REQUESTS_PER_24H_BACKGROUND_FLAG,
                        QUOTA_BUCKET_PER_24H_BACKGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_FOREGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_REQUESTS_PER_15M_FOREGROUND_FLAG,
                        QUOTA_BUCKET_PER_15M_FOREGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxApiCallQuotaMap.put(
                QuotaBucket.QUOTA_BUCKET_WRITES_PER_15M_BACKGROUND,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_REQUESTS_PER_15M_BACKGROUND_FLAG,
                        QUOTA_BUCKET_PER_15M_BACKGROUND_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxMemoryQuotaMap.put(
                RateLimiter.CHUNK_SIZE_LIMIT_IN_BYTES,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_CHUNK_SIZE_FLAG,
                        CHUNK_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE));
        quotaBucketToMaxMemoryQuotaMap.put(
                RateLimiter.RECORD_SIZE_LIMIT_IN_BYTES,
                DeviceConfig.getInt(
                        DeviceConfig.NAMESPACE_HEALTH_FITNESS,
                        MAX_WRITE_SINGLE_RECORD_SIZE_FLAG,
                        RECORD_SIZE_LIMIT_IN_BYTES_DEFAULT_FLAG_VALUE));
        RateLimiter.updateApiCallQuotaMap(quotaBucketToMaxApiCallQuotaMap);
        RateLimiter.updateMemoryQuotaMap(quotaBucketToMaxMemoryQuotaMap);
        mLock.readLock().lock();
        try {
            RateLimiter.updateEnableRateLimiterFlag(mRateLimiterEnabled);
        } finally {
            mLock.readLock().unlock();
        }
    }

    @Override
    public void onPropertiesChanged(DeviceConfig.Properties properties) {
        if (!properties.getNamespace().equals(HEALTH_FITNESS_NAMESPACE)) {
            return;
        }

        Set<String> changedFlags = new ArraySet<>(properties.getKeyset());
        changedFlags.retainAll(sFlagsToTrack);

        for (String name : changedFlags) {
            if (name.equals(EXERCISE_ROUTE_FEATURE_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mExerciseRouteEnabled =
                            properties.getBoolean(
                                    EXERCISE_ROUTE_FEATURE_FLAG, EXERCISE_ROUTE_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(SESSION_DATATYPE_FEATURE_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mSessionDatatypeEnabled =
                            properties.getBoolean(
                                    SESSION_DATATYPE_FEATURE_FLAG,
                                    SESSION_DATATYPE_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(ENABLE_RATE_LIMITER_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mRateLimiterEnabled =
                            properties.getBoolean(
                                    ENABLE_RATE_LIMITER_FLAG,
                                    ENABLE_RATE_LIMITER_DEFAULT_FLAG_VALUE);
                    RateLimiter.updateEnableRateLimiterFlag(mRateLimiterEnabled);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(COUNT_MIGRATION_STATE_IN_PROGRESS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mMigrationStateInProgressCount =
                            properties.getInt(
                                    COUNT_MIGRATION_STATE_IN_PROGRESS_FLAG,
                                    MIGRATION_STATE_IN_PROGRESS_COUNT_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(COUNT_MIGRATION_STATE_ALLOWED_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mMigrationStateAllowedCount =
                            properties.getInt(
                                    COUNT_MIGRATION_STATE_ALLOWED_FLAG,
                                    MIGRATION_STATE_ALLOWED_COUNT_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(MAX_START_MIGRATION_CALLS_ALLOWED_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mMaxStartMigrationCalls =
                            properties.getInt(
                                    MAX_START_MIGRATION_CALLS_ALLOWED_FLAG,
                                    MAX_START_MIGRATION_CALLS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(IDLE_STATE_TIMEOUT_DAYS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mIdleStateTimeoutPeriod =
                            properties.getInt(
                                    IDLE_STATE_TIMEOUT_DAYS_FLAG,
                                    IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(NON_IDLE_STATE_TIMEOUT_DAYS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mNonIdleStateTimeoutPeriod =
                            properties.getInt(
                                    NON_IDLE_STATE_TIMEOUT_DAYS_FLAG,
                                    NON_IDLE_STATE_TIMEOUT_DAYS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(IN_PROGRESS_STATE_TIMEOUT_HOURS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mInProgressStateTimeoutPeriod =
                            properties.getInt(
                                    IN_PROGRESS_STATE_TIMEOUT_HOURS_FLAG,
                                    IN_PROGRESS_STATE_TIMEOUT_HOURS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(EXECUTION_TIME_BUFFER_MINUTES_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mExecutionTimeBuffer =
                            properties.getInt(
                                    EXECUTION_TIME_BUFFER_MINUTES_FLAG,
                                    EXECUTION_TIME_BUFFER_MINUTES_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mMigrationCompletionJobRunInterval =
                            properties.getInt(
                                    MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_FLAG,
                                    MIGRATION_COMPLETION_JOB_RUN_INTERVAL_DAYS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mMigrationPauseJobRunInterval =
                            properties.getInt(
                                    MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_FLAG,
                                    MIGRATION_PAUSE_JOB_RUN_INTERVAL_HOURS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(ENABLE_PAUSE_STATE_CHANGE_JOBS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mEnablePauseStateChangeJob =
                            properties.getBoolean(
                                    ENABLE_PAUSE_STATE_CHANGE_JOBS_FLAG,
                                    ENABLE_PAUSE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(ENABLE_COMPLETE_STATE_CHANGE_JOBS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mEnableCompleteStateChangeJob =
                            properties.getBoolean(
                                    ENABLE_COMPLETE_STATE_CHANGE_JOBS_FLAG,
                                    ENABLE_COMPLETE_STATE_CHANGE_JOB_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            } else if (name.equals(ENABLE_MIGRATION_NOTIFICATIONS_FLAG)) {
                mLock.writeLock().lock();
                try {
                    mEnableMigrationNotifications =
                            properties.getBoolean(
                                    ENABLE_MIGRATION_NOTIFICATIONS_FLAG,
                                    ENABLE_MIGRATION_NOTIFICATIONS_DEFAULT_FLAG_VALUE);
                } finally {
                    mLock.writeLock().unlock();
                }
            }
        }
    }
}
