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

package com.android.server.uwb;

import android.content.Context;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.uwb.resources.R;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    private static final String LOG_TAG = DeviceConfigFacade.class.getSimpleName();

    /**
     */
    private static final int MAX_FOV = 180;

    public static final int DEFAULT_RANGING_RESULT_LOG_INTERVAL_MS = 5_000;
    private static final int MS_IN_HOUR = 60 * 60 * 1000;
    public static final int DEFAULT_BUG_REPORT_MIN_INTERVAL_MS = 24 * MS_IN_HOUR;
    private static final String TAG = "DeviceConfigFacadeUwb";

    public enum PoseSourceType {
        NONE,
        ROTATION_VECTOR,
        GYRO,
        SIXDOF,
        DOUBLE_INTEGRATE,
    }

    private final Context mContext;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private int mRangingResultLogIntervalMs;
    private boolean mDeviceErrorBugreportEnabled;
    private boolean mSessionInitErrorBugreportEnabled;
    private int mBugReportMinIntervalMs;
    private boolean mEnableFilters;
    private int mFilterDistanceInliersPercent;
    private int mFilterDistanceWindow;
    private int mFilterAngleInliersPercent;
    private int mFilterAngleWindow;
    private PoseSourceType mPoseSourceType;
    private boolean mEnablePrimerEstElevation;
    private boolean mEnablePrimerAoA;
    private boolean mEnablePrimerFov;
    private int mPrimerFovDegree;
    private boolean mEnableBackAzimuth;
    private boolean mEnableBackAzimuthMasking;
    private int mBackAzimuthWindow;
    private float mFrontAzimuthRadiansPerSecond;
    private float mBackAzimuthRadiansPerSecond;
    private float mMirrorScoreStdRadians;
    private float mBackNoiseInfluenceCoeff;
    private int mPredictionTimeoutSeconds;

    // Config parameters related to Advertising Profile.
    private int mAdvertiseAoaCriteriaAngle;
    private int mAdvertiseTimeThresholdMillis;
    private int mAdvertiseArraySizeToCheck;
    private int mAdvertiseArrayStartIndexToCalVariance;
    private int mAdvertiseArrayEndIndexToCalVariance;
    private int mAdvertiseTrustedVarianceValue;

    // Config parameters related to Rx/Tx data packets.
    private int mRxDataMaxPacketsToStore;
    // Flag to enable unlimited background ranging.
    private boolean mBackgroundRangingEnabled;
    // Flag to disable error streak timer when a session is ongoing.
    private boolean mRangingErrorStreakTimerEnabled;

    public DeviceConfigFacade(Handler handler, Context context) {
        mContext = context;

        updateDeviceConfigFlags();
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_UWB,
                command -> handler.post(command),
                properties -> {
                    updateDeviceConfigFlags();
                });
    }

    private void updateDeviceConfigFlags() {
        String poseSourceName;
        mRangingResultLogIntervalMs = DeviceConfig.getInt(DeviceConfig.NAMESPACE_UWB,
                "ranging_result_log_interval_ms", DEFAULT_RANGING_RESULT_LOG_INTERVAL_MS);
        mDeviceErrorBugreportEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_UWB,
                "device_error_bugreport_enabled", false);
        mSessionInitErrorBugreportEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_UWB,
                "session_init_error_bugreport_enabled", false);
        mBugReportMinIntervalMs = DeviceConfig.getInt(DeviceConfig.NAMESPACE_UWB,
                "bug_report_min_interval_ms", DEFAULT_BUG_REPORT_MIN_INTERVAL_MS);

        // Default values come from the overlay file (config.xml).
        mEnableFilters = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "enable_filters",
                mContext.getResources().getBoolean(R.bool.enable_filters)
        );
        mFilterDistanceInliersPercent = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "filter_distance_inliers_percent",
                mContext.getResources().getInteger(R.integer.filter_distance_inliers_percent)
        );
        mFilterDistanceWindow = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "filter_distance_window",
                mContext.getResources().getInteger(R.integer.filter_distance_window)
        );
        mFilterAngleInliersPercent = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "filter_angle_inliers_percent",
                mContext.getResources().getInteger(R.integer.filter_angle_inliers_percent)
        );
        mFilterAngleWindow = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "filter_angle_window",
                mContext.getResources().getInteger(R.integer.filter_angle_window)
        );
        poseSourceName = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_UWB,
                "pose_source_type",
                mContext.getResources().getString(R.string.pose_source_type)
        );
        mEnablePrimerEstElevation = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "enable_primer_est_elevation",
                mContext.getResources().getBoolean(R.bool.enable_primer_est_elevation)
        );
        mEnablePrimerAoA = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "enable_primer_aoa",
                mContext.getResources().getBoolean(R.bool.enable_primer_aoa)
        );
        mPrimerFovDegree = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "primer_fov_degrees",
                mContext.getResources().getInteger(R.integer.primer_fov_degrees)
        );
        mPredictionTimeoutSeconds = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "prediction_timeout_seconds",
                mContext.getResources().getInteger(R.integer.prediction_timeout_seconds)
        );
        mEnableBackAzimuth = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "enable_azimuth_mirroring",
                mContext.getResources().getBoolean(R.bool.enable_azimuth_mirroring)
        );
        mEnableBackAzimuthMasking = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "predict_rear_azimuths",
                mContext.getResources().getBoolean(R.bool.predict_rear_azimuths)
        );
        mBackAzimuthWindow = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "mirror_detection_window",
                mContext.getResources().getInteger(R.integer.mirror_detection_window)
        );
        int frontAzimuthDegreesPerSecond = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "front_mirror_dps",
                mContext.getResources().getInteger(R.integer.front_mirror_dps)
        );
        int backAzimuthDegreesPerSecond = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "back_mirror_dps",
                mContext.getResources().getInteger(R.integer.back_mirror_dps)
        );
        int mirrorScoreStdDegrees = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "mirror_score_std_degrees",
                mContext.getResources().getInteger(R.integer.mirror_score_std_degrees)
        );
        int backNoiseInfluencePercent = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "back_noise_influence_percent",
                mContext.getResources().getInteger(R.integer.back_noise_influence_percent)
        );

        // Read the Advertising profile config parameters.
        mAdvertiseAoaCriteriaAngle = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_aoa_criteria_angle",
                mContext.getResources().getInteger(R.integer.advertise_aoa_criteria_angle)
        );
        mAdvertiseTimeThresholdMillis = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_time_threshold_millis",
                mContext.getResources().getInteger(R.integer.advertise_time_threshold_millis)
        );
        mAdvertiseArraySizeToCheck = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_array_size_to_check",
                mContext.getResources().getInteger(R.integer.advertise_array_size_to_check)
        );
        mAdvertiseArrayStartIndexToCalVariance = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_array_start_index_to_cal_variance",
                mContext.getResources().getInteger(
                        R.integer.advertise_array_start_index_to_cal_variance)
        );
        mAdvertiseArrayEndIndexToCalVariance = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_array_end_index_to_cal_variance",
                mContext.getResources().getInteger(
                        R.integer.advertise_array_end_index_to_cal_variance)
        );
        mAdvertiseTrustedVarianceValue = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "advertise_trusted_variance_value",
                mContext.getResources().getInteger(R.integer.advertise_trusted_variance_value)
        );

        // Rx data packets.
        mRxDataMaxPacketsToStore = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_UWB,
                "rx_data_max_packets_to_store",
                mContext.getResources().getInteger(R.integer.rx_data_max_packets_to_store)
        );

        mBackgroundRangingEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "background_ranging_enabled",
                mContext.getResources().getBoolean(R.bool.background_ranging_enabled)
        );

        mRangingErrorStreakTimerEnabled = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_UWB,
                "ranging_error_streak_timer_enabled",
                mContext.getResources().getBoolean(R.bool.ranging_error_streak_timer_enabled)
        );

        // A little parsing and cleanup:
        mFrontAzimuthRadiansPerSecond = (float) Math.toRadians(frontAzimuthDegreesPerSecond);
        mBackAzimuthRadiansPerSecond = (float) Math.toRadians(backAzimuthDegreesPerSecond);
        mMirrorScoreStdRadians = (float) Math.toRadians(mirrorScoreStdDegrees);
        mBackNoiseInfluenceCoeff = backNoiseInfluencePercent / 100F;
        try {
            mPoseSourceType = PoseSourceType.valueOf(poseSourceName);
        } catch (IllegalArgumentException e) {
            mPoseSourceType = PoseSourceType.ROTATION_VECTOR;
            Log.e(LOG_TAG, "UWB pose source '" + poseSourceName + "' defined in flags or"
                    + "overlay file is invalid. Defaulting to " + mPoseSourceType.name());
        }
        mEnablePrimerFov = mPrimerFovDegree > 0 && mPrimerFovDegree < MAX_FOV;
    }

    /**
     * Gets ranging result logging interval in ms
     */
    public int getRangingResultLogIntervalMs() {
        return mRangingResultLogIntervalMs;
    }

    /**
     * Gets the feature flag for reporting device error
     */
    public boolean isDeviceErrorBugreportEnabled() {
        return mDeviceErrorBugreportEnabled;
    }

    /**
     * Gets the feature flag for reporting session init error
     */
    public boolean isSessionInitErrorBugreportEnabled() {
        return mSessionInitErrorBugreportEnabled;
    }

    /**
     * Gets minimum wait time between two bug report captures
     */
    public int getBugReportMinIntervalMs() {
        return mBugReportMinIntervalMs;
    }

    /**
     * Gets the flag for enabling UWB filtering.
     */
    public boolean isEnableFilters() {
        return mEnableFilters;
    }

    /**
     * Gets the percentage (0-100) of inliers to be used in the distance filter cut.
     */
    public int getFilterDistanceInliersPercent() {
        return mFilterDistanceInliersPercent;
    }

    /**
     * Gets the size of the distance filter moving window.
     */
    public int getFilterDistanceWindow() {
        return mFilterDistanceWindow;
    }

    /**
     * Gets the percentage (0-100) of inliers to be used inthe angle filter cut.
     */
    public int getFilterAngleInliersPercent() {
        return mFilterAngleInliersPercent;
    }

    /**
     * Gets the size of the angle filter moving window.
     */
    public int getFilterAngleWindow() {
        return mFilterAngleWindow;
    }

    /**
     * Gets the type of pose source that should be used by default.
     */
    public PoseSourceType getPoseSourceType() {
        return mPoseSourceType;
    }

    /**
     * Gets the flag that enables the elevation estimation primer.
     */
    public boolean isEnablePrimerEstElevation() {
        return mEnablePrimerEstElevation;
    }

    /**
     * Gets the flag that enables the primer that converts AoA to spherical coordinates.
     */
    public boolean isEnablePrimerAoA() {
        return mEnablePrimerAoA;
    }

    /**
     * Gets a value indicating if the FOV primer should be enabled.
     */
    public boolean isEnablePrimerFov() {
        return mEnablePrimerFov;
    }

    /**
     * Gets the configured field of view.
     */
    public int getPrimerFovDegree() {
        return mPrimerFovDegree;
    }

    /**
     * Gets how long to replace reports with an error status with predicted reports in seconds.
     */
    public int getPredictionTimeoutSeconds() {
        return mPredictionTimeoutSeconds;
    }

    /**
     * Gets the flag that enables back-azimuth detection.
     */
    public boolean isEnableBackAzimuth() {
        return mEnableBackAzimuth;
    }

    /**
     * Gets the flag that causes rear azimuth values to be replaced with predictions.
     */
    public boolean isEnableBackAzimuthMasking() {
        return mEnableBackAzimuthMasking;
    }

    /**
     * Gets the size of the back-azimuth detection window.
     */
    public int getBackAzimuthWindow() {
        return mBackAzimuthWindow;
    }

    /**
     * Gets minimum correlation rate required to assume azimuth readings are coming from the front.
     */
    public float getFrontAzimuthRadiansPerSecond() {
        return mFrontAzimuthRadiansPerSecond;
    }

    /**
     * Gets minimum correlation rate required to assume azimuth readings are coming from the back.
     */
    public float getBackAzimuthRadiansPerSecond() {
        return mBackAzimuthRadiansPerSecond;
    }

    /**
     * Gets the standard deviation of the mirror detection bell curve.
     */
    public float getMirrorScoreStdRadians() {
        return mMirrorScoreStdRadians;
    }

    /**
     * Gets a coefficient of how much noise adds to the back-facing mirroring score.
     */
    public float getBackNoiseInfluenceCoeff() {
        return mBackNoiseInfluenceCoeff;
    }

    /**
     * Gets the Advertising Profile AoA Criteria Angle.
     */
    public int getAdvertiseAoaCriteriaAngle() {
        return mAdvertiseAoaCriteriaAngle;
    }

    /**
     * Gets the Advertising profile time threshold (for the received Owr Aoa Measurements).
     */
    public int getAdvertiseTimeThresholdMillis() {
        return mAdvertiseTimeThresholdMillis;
    }

    /**
     * Gets the Advertising profile Array Size (of the stored values from Owr Aoa Measurements).
     */
    public int getAdvertiseArraySizeToCheck() {
        return mAdvertiseArraySizeToCheck;
    }

    /**
     * Gets the Advertising profile Array Start Index (of the stored values from Owr Aoa
     * Measurements), which we will use to calculate Variance.
     */
    public int getAdvertiseArrayStartIndexToCalVariance() {
        return mAdvertiseArrayStartIndexToCalVariance;
    }

    /**
     * Gets the Advertising profile Array End Index (of the stored values from Owr Aoa
     * Measurements), which we will use to calculate Variance.
     */
    public int getAdvertiseArrayEndIndexToCalVariance() {
        return mAdvertiseArrayEndIndexToCalVariance;
    }

    /**
     * Gets the Advertising profile Trusted Variance Value (the threshold within which computed
     * variance from the Owr Aoa Measurements is acceptable).
     */
    public int getAdvertiseTrustedVarianceValue() {
        return mAdvertiseTrustedVarianceValue;
    }

    /**
     * Gets the max number of Rx data packets (received on a UWB session from the remote device),
     * to be stored in the UWB framework.
     */
    public int getRxDataMaxPacketsToStore() {
        return mRxDataMaxPacketsToStore;
    }

    /**
     * Returns whether background ranging is enabled or not.
     * If enabled:
     *  * Background 3p apps are allowed to open new ranging sessions
     *  * When previously foreground 3p apps moves to background, sessions are not terminated
     */
    public boolean isBackgroundRangingEnabled() {
        return mBackgroundRangingEnabled;
    }

    /**
     * Returns whether ranging error streak timer is enabled or not.
     * If disabled, session would not be automatically stopped if there is no peer available.
     */
    public boolean isRangingErrorStreakTimerEnabled() {
        return mRangingErrorStreakTimerEnabled;
    }
}
