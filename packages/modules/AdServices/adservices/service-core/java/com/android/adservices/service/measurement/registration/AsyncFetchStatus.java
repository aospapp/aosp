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

package com.android.adservices.service.measurement.registration;

import java.util.Optional;

import javax.annotation.Nullable;

/** POJO for storing source and trigger fetcher status */
public class AsyncFetchStatus {
    public enum ResponseStatus {
        UNKNOWN,
        SUCCESS,
        SERVER_UNAVAILABLE,
        NETWORK_ERROR,
        INVALID_URL
    }

    public enum EntityStatus {
        UNKNOWN,
        SUCCESS,
        HEADER_MISSING,
        HEADER_ERROR,
        PARSING_ERROR,
        VALIDATION_ERROR,
        INVALID_ENROLLMENT,
        STORAGE_ERROR
    }

    private ResponseStatus mResponseStatus;

    private EntityStatus mEntityStatus;

    @Nullable private Long mResponseSize;

    @Nullable private Long mRegistrationDelay;

    private boolean mIsRedirectError;

    public AsyncFetchStatus() {
        mResponseStatus = ResponseStatus.UNKNOWN;
        mEntityStatus = EntityStatus.UNKNOWN;
        mIsRedirectError = false;
    }

    /** Get the status of a communication with an Ad Tech server. */
    public ResponseStatus getResponseStatus() {
        return mResponseStatus;
    }

    /**
     * Set the status of a communication with an Ad Tech server.
     *
     * @param status a {@link ResponseStatus} that is used up the call stack to determine errors in
     *     the Ad tech server during source and trigger fetching.
     */
    public void setResponseStatus(ResponseStatus status) {
        mResponseStatus = status;
    }

    /** Get entity status */
    public EntityStatus getEntityStatus() {
        return mEntityStatus;
    }

    /** Set entity status */
    public void setEntityStatus(EntityStatus entityStatus) {
        mEntityStatus = entityStatus;
    }

    /** Get response header size. */
    public Optional<Long> getResponseSize() {
        return Optional.ofNullable(mResponseSize);
    }

    /** Set response header size. */
    public void setResponseSize(Long responseSize) {
        mResponseSize = responseSize;
    }

    /** Get registration delay. */
    public Optional<Long> getRegistrationDelay() {
        return Optional.ofNullable(mRegistrationDelay);
    }

    /** Set registration delay. */
    public void setRegistrationDelay(Long registrationDelay) {
        mRegistrationDelay = registrationDelay;
    }

    /** Get redirect error status. */
    public boolean isRedirectError() {
        return mIsRedirectError;
    }

    /** Set redirect error status. */
    public void setRedirectError(boolean isRedirectError) {
        mIsRedirectError = isRedirectError;
    }

    /** Returns true if request is successful. */
    public boolean isRequestSuccess() {
        return mResponseStatus == ResponseStatus.SUCCESS;
    }

    /** Returns true if request can be retried. */
    public boolean canRetry() {
        return mResponseStatus == ResponseStatus.NETWORK_ERROR
                || mResponseStatus == ResponseStatus.SERVER_UNAVAILABLE;
    }
}
