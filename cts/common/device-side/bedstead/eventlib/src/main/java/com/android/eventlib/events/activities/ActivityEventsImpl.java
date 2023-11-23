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

package com.android.eventlib.events.activities;

import com.android.bedstead.nene.activities.NeneActivity;

/** Default implementation of {@link ActivityEvents}. */
public final class ActivityEventsImpl implements ActivityEvents {
    private final NeneActivity mActivity;

    ActivityEventsImpl(NeneActivity activity) {
        mActivity = activity;
    }

    @Override
    public ActivityCreatedEvent.ActivityCreatedEventQuery activityCreated() {
        return ActivityCreatedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityDestroyedEvent.ActivityDestroyedEventQuery activityDestroyed() {
        return ActivityDestroyedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityPausedEvent.ActivityPausedEventQuery activityPaused() {
        return ActivityPausedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityRestartedEvent.ActivityRestartedEventQuery activityRestarted() {
        return ActivityRestartedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityResumedEvent.ActivityResumedEventQuery activityResumed() {
        return ActivityResumedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityStartedEvent.ActivityStartedEventQuery activityStarted() {
        return ActivityStartedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

    @Override
    public ActivityStoppedEvent.ActivityStoppedEventQuery activityStopped() {
        return ActivityStoppedEvent.queryPackage(
                mActivity.getComponentName().getPackageName())
                .whereActivity().activityClass().className().isEqualTo(
                        mActivity.getComponentName().getClassName())
                .onUser(mActivity.getUser());
    }

}
