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

package com.android.adservices.service.measurement;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.Uri;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.android.adservices.service.measurement.noising.SourceNoiseHandler;
import com.android.adservices.service.measurement.reporting.EventReportWindowCalcDelegate;
import com.android.adservices.service.measurement.util.Debug;
import com.android.adservices.service.measurement.util.UnsignedLong;

import com.google.common.collect.ImmutableMultiset;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * POJO for EventReport.
 */
public class EventReport {

    private String mId;
    private UnsignedLong mSourceEventId;
    private long mReportTime;
    private long mTriggerTime;
    private long mTriggerPriority;
    private List<Uri> mAttributionDestinations;
    private String mEnrollmentId;
    private UnsignedLong mTriggerData;
    private UnsignedLong mTriggerDedupKey;
    private double mRandomizedTriggerRate;
    private @Status int mStatus;
    private @DebugReportStatus int mDebugReportStatus;
    private Source.SourceType mSourceType;
    @Nullable private UnsignedLong mSourceDebugKey;
    @Nullable private UnsignedLong mTriggerDebugKey;
    private String mSourceId;
    private String mTriggerId;
    private Uri mRegistrationOrigin;

    @IntDef(value = {Status.PENDING, Status.DELIVERED, Status.MARKED_TO_DELETE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Status {
        int PENDING = 0;
        int DELIVERED = 1;
        int MARKED_TO_DELETE = 2;
    }

    @IntDef(
            value = {
                DebugReportStatus.NONE,
                DebugReportStatus.PENDING,
                DebugReportStatus.DELIVERED,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DebugReportStatus {
        int NONE = 0;
        int PENDING = 1;
        int DELIVERED = 2;
    }

    private EventReport() {
        mTriggerDedupKey = null;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof EventReport)) {
            return false;
        }
        EventReport eventReport = (EventReport) obj;
        return mStatus == eventReport.mStatus
                && mDebugReportStatus == eventReport.mDebugReportStatus
                && mReportTime == eventReport.mReportTime
                && Objects.equals(mAttributionDestinations, eventReport.mAttributionDestinations)
                && ImmutableMultiset.copyOf(mAttributionDestinations)
                        .equals(ImmutableMultiset.copyOf(eventReport.mAttributionDestinations))
                && Objects.equals(mEnrollmentId, eventReport.mEnrollmentId)
                && mTriggerTime == eventReport.mTriggerTime
                && Objects.equals(mTriggerData, eventReport.mTriggerData)
                && Objects.equals(mSourceEventId, eventReport.mSourceEventId)
                && mTriggerPriority == eventReport.mTriggerPriority
                && Objects.equals(mTriggerDedupKey, eventReport.mTriggerDedupKey)
                && mSourceType == eventReport.mSourceType
                && mRandomizedTriggerRate == eventReport.mRandomizedTriggerRate
                && Objects.equals(mSourceDebugKey, eventReport.mSourceDebugKey)
                && Objects.equals(mTriggerDebugKey, eventReport.mTriggerDebugKey)
                && Objects.equals(mSourceId, eventReport.mSourceId)
                && Objects.equals(mTriggerId, eventReport.mTriggerId)
                && Objects.equals(mRegistrationOrigin, eventReport.mRegistrationOrigin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mStatus,
                mDebugReportStatus,
                mReportTime,
                mAttributionDestinations,
                mEnrollmentId,
                mTriggerTime,
                mTriggerData,
                mSourceEventId,
                mTriggerPriority,
                mTriggerDedupKey,
                mSourceType,
                mRandomizedTriggerRate,
                mSourceDebugKey,
                mTriggerDebugKey,
                mSourceId,
                mTriggerId,
                mRegistrationOrigin);
    }

    /** Unique identifier for the report. */
    public String getId() {
        return mId;
    }

    /** Identifier of the associated {@link Source} event. */
    public UnsignedLong getSourceEventId() {
        return mSourceEventId;
    }

    /**
     * Scheduled time for the report to be sent.
     */
    public long getReportTime() {
        return mReportTime;
    }

    /**
     * TriggerTime of the associated {@link Trigger}.
     */
    public long getTriggerTime() {
        return mTriggerTime;
    }

    /**
     * Priority of the associated {@link Trigger}.
     */
    public long getTriggerPriority() {
        return mTriggerPriority;
    }

    /**
     * AttributionDestinations of the {@link Source} and {@link Trigger}.
     */
    public List<Uri> getAttributionDestinations() {
        return mAttributionDestinations;
    }

    /**
     * Ad Tech enrollment ID.
     */
    public String getEnrollmentId() {
        return mEnrollmentId;
    }

    /**
     * Metadata for the report.
     */
    public UnsignedLong getTriggerData() {
        return mTriggerData;
    }

    /**
     * Deduplication key of the associated {@link Trigger}
     */
    public UnsignedLong getTriggerDedupKey() {
        return mTriggerDedupKey;
    }

    /**
     * Current {@link Status} of the report.
     */
    public @Status int getStatus() {
        return mStatus;
    }

    /** Current {@link DebugReportStatus} of the report. */
    public @DebugReportStatus int getDebugReportStatus() {
        return mDebugReportStatus;
    }

    /**
     * SourceType of the event's source.
     */
    public Source.SourceType getSourceType() {
        return mSourceType;
    }

    /**
     * Randomized trigger rate for noising
     */
    public double getRandomizedTriggerRate() {
        return mRandomizedTriggerRate;
    }

    /** Source Debug Key */
    @Nullable
    public UnsignedLong getSourceDebugKey() {
        return mSourceDebugKey;
    }

    /** Trigger Debug Key */
    @Nullable
    public UnsignedLong getTriggerDebugKey() {
        return mTriggerDebugKey;
    }

    /** Source ID */
    public String getSourceId() {
        return mSourceId;
    }

    /** Trigger ID */
    public String getTriggerId() {
        return mTriggerId;
    }

    /** Returns registration origin used to register the source and trigger */
    public Uri getRegistrationOrigin() {
        return mRegistrationOrigin;
    }

    /** Builder for {@link EventReport} */
    public static final class Builder {

        private final EventReport mBuilding;

        public Builder() {
            mBuilding = new EventReport();
        }

        /**
         * See {@link EventReport#getId()}
         */
        public Builder setId(String id) {
            mBuilding.mId = id;
            return this;
        }

        /** See {@link EventReport#getSourceEventId()} */
        public Builder setSourceEventId(UnsignedLong sourceEventId) {
            mBuilding.mSourceEventId = sourceEventId;
            return this;
        }

        /** See {@link EventReport#getEnrollmentId()} */
        public Builder setEnrollmentId(String enrollmentId) {
            mBuilding.mEnrollmentId = enrollmentId;
            return this;
        }

        /**
         * See {@link EventReport#getAttributionDestination()}
         */
        public Builder setAttributionDestinations(List<Uri> attributionDestinations) {
            mBuilding.mAttributionDestinations = attributionDestinations;
            return this;
        }

        /**
         * See {@link EventReport#getTriggerTime()}
         */
        public Builder setTriggerTime(long triggerTime) {
            mBuilding.mTriggerTime = triggerTime;
            return this;
        }

        /**
         * See {@link EventReport#getTriggerData()}
         */
        public Builder setTriggerData(UnsignedLong triggerData) {
            mBuilding.mTriggerData = triggerData;
            return this;
        }

        /**
         * See {@link EventReport#getTriggerPriority()}
         */
        public Builder setTriggerPriority(long triggerPriority) {
            mBuilding.mTriggerPriority = triggerPriority;
            return this;
        }

        /**
         * See {@link EventReport#getTriggerDedupKey()}
         */
        public Builder setTriggerDedupKey(UnsignedLong triggerDedupKey) {
            mBuilding.mTriggerDedupKey = triggerDedupKey;
            return this;
        }

        /**
         * See {@link EventReport#getReportTime()}
         */
        public Builder setReportTime(long reportTime) {
            mBuilding.mReportTime = reportTime;
            return this;
        }

        /**
         * See {@link EventReport#getStatus()}
         */
        public Builder setStatus(@Status int status) {
            mBuilding.mStatus = status;
            return this;
        }

        /** See {@link EventReport#getDebugReportStatus()}} */
        public Builder setDebugReportStatus(@DebugReportStatus int debugReportStatus) {
            mBuilding.mDebugReportStatus = debugReportStatus;
            return this;
        }

        /**
         * See {@link EventReport#getSourceType()}
         */
        public Builder setSourceType(Source.SourceType sourceType) {
            mBuilding.mSourceType = sourceType;
            return this;
        }

        /** See {@link EventReport#getRandomizedTriggerRate()}} */
        public Builder setRandomizedTriggerRate(double randomizedTriggerRate) {
            mBuilding.mRandomizedTriggerRate = randomizedTriggerRate;
            return this;
        }

        /** See {@link EventReport#getSourceDebugKey()}} */
        public Builder setSourceDebugKey(UnsignedLong sourceDebugKey) {
            mBuilding.mSourceDebugKey = sourceDebugKey;
            return this;
        }

        /** See {@link EventReport#getTriggerDebugKey()}} */
        public Builder setTriggerDebugKey(UnsignedLong triggerDebugKey) {
            mBuilding.mTriggerDebugKey = triggerDebugKey;
            return this;
        }

        /** See {@link EventReport#getSourceId()} */
        public Builder setSourceId(String sourceId) {
            mBuilding.mSourceId = sourceId;
            return this;
        }

        /** See {@link EventReport#getTriggerId()} */
        public Builder setTriggerId(String triggerId) {
            mBuilding.mTriggerId = triggerId;
            return this;
        }

        /** See {@link Source#getRegistrationOrigin()} ()} */
        @NonNull
        public Builder setRegistrationOrigin(Uri registrationOrigin) {
            mBuilding.mRegistrationOrigin = registrationOrigin;
            return this;
        }

        // TODO (b/285607306): cleanup since this doesn't just do "populateFromSourceAndTrigger"
        /** Populates fields using {@link Source}, {@link Trigger} and {@link EventTrigger}. */
        public Builder populateFromSourceAndTrigger(
                @NonNull Source source,
                @NonNull Trigger trigger,
                @NonNull EventTrigger eventTrigger,
                @Nullable Pair<UnsignedLong, UnsignedLong> debugKeyPair,
                @NonNull EventReportWindowCalcDelegate eventReportWindowCalcDelegate,
                @NonNull SourceNoiseHandler sourceNoiseHandler,
                List<Uri> eventReportDestinations) {
            mBuilding.mTriggerPriority = eventTrigger.getTriggerPriority();
            mBuilding.mTriggerDedupKey = eventTrigger.getDedupKey();
            // truncate trigger data to 3-bit or 1-bit based on {@link Source.SourceType}
            mBuilding.mTriggerData = getTruncatedTriggerData(source, eventTrigger);
            mBuilding.mTriggerTime = trigger.getTriggerTime();
            mBuilding.mSourceEventId = source.getEventId();
            mBuilding.mEnrollmentId = source.getEnrollmentId();
            mBuilding.mStatus = Status.PENDING;
            mBuilding.mAttributionDestinations = eventReportDestinations;
            mBuilding.mReportTime =
                    eventReportWindowCalcDelegate.getReportingTime(
                            source, trigger.getTriggerTime(), trigger.getDestinationType());
            mBuilding.mSourceType = source.getSourceType();
            mBuilding.mRandomizedTriggerRate =
                    sourceNoiseHandler.getRandomAttributionProbability(source);
            mBuilding.mSourceDebugKey = debugKeyPair.first;
            mBuilding.mTriggerDebugKey = debugKeyPair.second;
            mBuilding.mDebugReportStatus = DebugReportStatus.NONE;
            if (Debug.isAttributionDebugReportPermitted(source, trigger,
                    mBuilding.mSourceDebugKey, mBuilding.mTriggerDebugKey)) {
                mBuilding.mDebugReportStatus = DebugReportStatus.PENDING;
            }
            mBuilding.mSourceId = source.getId();
            mBuilding.mTriggerId = trigger.getId();
            mBuilding.mRegistrationOrigin = trigger.getRegistrationOrigin();
            return this;
        }

        private UnsignedLong getTruncatedTriggerData(Source source, EventTrigger eventTrigger) {
            UnsignedLong triggerData = eventTrigger.getTriggerData();
            return triggerData.mod(source.getTriggerDataCardinality());
        }

        /**
         * Build the {@link EventReport}.
         */
        public EventReport build() {
            return mBuilding;
        }
    }
}
