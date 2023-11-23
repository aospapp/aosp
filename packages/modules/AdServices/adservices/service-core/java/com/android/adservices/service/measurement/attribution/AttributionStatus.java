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

package com.android.adservices.service.measurement.attribution;

import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.Trigger;

import java.util.Optional;

import javax.annotation.Nullable;

/** POJO for storing attribution status */
public class AttributionStatus {
    /** Enums are tied to the AdservicesMeasurementAttributionStatus atom */
    public enum SourceType {
        UNKNOWN,
        NAVIGATION,
        EVENT
    }

    public enum AttributionSurface {
        UNKNOWN,
        APP_APP,
        APP_WEB,
        WEB_APP,
        WEB_WEB,
    }

    public enum AttributionResult {
        UNKNOWN,
        SUCCESS,
        FAILURE
    }

    public enum FailureType {
        UNKNOWN,
        TRIGGER_IGNORED,
        TRIGGER_ALREADY_ATTRIBUTED,
        TRIGGER_MARKED_FOR_DELETION,
        NO_MATCHING_SOURCE,
        TOP_LEVEL_FILTER_MATCH_FAILURE,
        RATE_LIMIT_EXCEEDED,
        NO_REPORTS_GENERATED
    }

    private SourceType mSourceType;
    private AttributionSurface mAttributionSurface;
    private AttributionResult mAttributionResult;
    private FailureType mFailureType;
    private boolean mIsSourceDerived;
    private boolean mIsInstallAttribution;
    @Nullable private Long mAttributionDelay;

    public AttributionStatus() {
        mSourceType = SourceType.UNKNOWN;
        mAttributionSurface = AttributionSurface.UNKNOWN;
        mAttributionResult = AttributionResult.UNKNOWN;
        mFailureType = FailureType.UNKNOWN;
        mIsSourceDerived = false;
        mIsInstallAttribution = false;
    }

    /** Get the type of the source that is getting attributed. */
    public SourceType getSourceType() {
        return mSourceType;
    }

    /** Set the type of the source that is getting attributed. */
    public void setSourceType(SourceType type) {
        mSourceType = type;
    }

    /** Set the type of the source that is getting attributed using Source.SourceType. */
    public void setSourceType(Source.SourceType type) {
        if (type == Source.SourceType.EVENT) {
            setSourceType(SourceType.EVENT);
        } else if (type == Source.SourceType.NAVIGATION) {
            setSourceType(SourceType.NAVIGATION);
        }
    }

    /** Get the surface type for the attributed source and trigger. */
    public AttributionSurface getAttributionSurface() {
        return mAttributionSurface;
    }

    /** Set the surface type for the attributed source and trigger. */
    public void setAttributionSurface(AttributionSurface attributionSurface) {
        mAttributionSurface = attributionSurface;
    }

    /** Set the surface type for the attributed source and trigger using Source and Trigger. */
    public void setSurfaceTypeFromSourceAndTrigger(Source source, Trigger trigger) {
        if (source.getPublisherType() == EventSurfaceType.APP
                && trigger.getDestinationType() == EventSurfaceType.APP) {
            setAttributionSurface(AttributionSurface.APP_APP);
        } else if (source.getPublisherType() == EventSurfaceType.APP
                && trigger.getDestinationType() == EventSurfaceType.WEB) {
            setAttributionSurface(AttributionSurface.APP_WEB);
        } else if (source.getPublisherType() == EventSurfaceType.WEB
                && trigger.getDestinationType() == EventSurfaceType.APP) {
            setAttributionSurface(AttributionSurface.WEB_APP);
        } else if (source.getPublisherType() == EventSurfaceType.WEB
                && trigger.getDestinationType() == EventSurfaceType.WEB) {
            setAttributionSurface(AttributionSurface.WEB_WEB);
        }
    }

    /** Get the result of attribution. */
    public AttributionResult getAttributionResult() {
        return mAttributionResult;
    }

    /** Set the result of attribution. */
    public void setAttributionResult(AttributionResult attributionResult) {
        mAttributionResult = attributionResult;
    }

    /** Get failure type. */
    public FailureType getFailureType() {
        return mFailureType;
    }

    /** Set failure type. */
    public void setFailureType(FailureType failureType) {
        mFailureType = failureType;
    }

    /** Set failure type using Trigger.Status. */
    public void setFailureTypeFromTriggerStatus(int triggerStatus) {
        if (triggerStatus == Trigger.Status.IGNORED) {
            setFailureType(FailureType.TRIGGER_IGNORED);
        } else if (triggerStatus == Trigger.Status.ATTRIBUTED) {
            setFailureType(FailureType.TRIGGER_ALREADY_ATTRIBUTED);
        } else if (triggerStatus == Trigger.Status.MARKED_TO_DELETE) {
            setFailureType(FailureType.TRIGGER_MARKED_FOR_DELETION);
        }
    }

    /** See if source is derived. */
    public boolean isSourceDerived() {
        return mIsSourceDerived;
    }

    /** Set source derived status */
    public void setSourceDerived(boolean isSourceDerived) {
        mIsSourceDerived = isSourceDerived;
    }

    /** See if attribution is an install attribution */
    public boolean isInstallAttribution() {
        return mIsInstallAttribution;
    }

    /** Set install attribution status */
    public void setInstallAttribution(boolean installAttribution) {
        mIsInstallAttribution = installAttribution;
    }

    /** Get attribution delay. */
    public Optional<Long> getAttributionDelay() {
        return Optional.ofNullable(mAttributionDelay);
    }

    /** Set attribution delay. */
    public void setAttributionDelay(Long attributionDelay) {
        mAttributionDelay = attributionDelay;
    }
}
