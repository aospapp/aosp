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

package com.android.bedstead.harrier.annotations.enterprise;

import static com.android.bedstead.harrier.annotations.AnnotationRunPrecedence.MIDDLE;
import static com.android.bedstead.nene.packages.CommonPackages.FEATURE_DEVICE_ADMIN;

import com.android.bedstead.harrier.annotations.AnnotationRunPrecedence;
import com.android.bedstead.harrier.annotations.FailureMode;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.RequireNotInstantApp;
import com.android.bedstead.harrier.annotations.RequireNotWatch;
import com.android.bedstead.nene.devicepolicy.DeviceOwnerType;
import com.android.queryable.annotations.Query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark that a test requires that a device owner is available on the device.
 *
 * <p>Your test configuration may be configured so that this test is only run on a device which has
 * a device owner. Otherwise, you can use {@code Devicestate} to ensure that the device enters
 * the correct state for the method. If using {@code Devicestate}, you can use
 * {@code Devicestate#deviceOwner()} to interact with the device owner.
 *
 * <p>When running on a device with a headless system user, enforcing this with {@code Devicestate}
 * will also result in the profile owner of the current user being set to the same device policy
 * controller.
 *
 * <p>If {@code Devicestate} is required to set the device owner (because there isn't one already)
 * then all users and accounts may be removed from the device.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@RequireFeature(FEATURE_DEVICE_ADMIN)
// TODO(b/206441366): Add instant app support
@RequireNotInstantApp(reason = "Instant Apps cannot run Enterprise Tests")
@RequireNotWatch(reason = "b/270121483 Watches get marked as paired which means we can't change Device Owner")
public @interface EnsureHasDeviceOwner {

    /** See {@link EnsureHasDeviceOwner#headlessDeviceOwnerType }. */
    enum HeadlessDeviceOwnerType {
        /**
         * When used - the Device Owner will be set but no profile owners will be set.
         */
        NONE,

        /**
         * When used - when setting the device owner on a headless system user mode device, a profile
         * owner will also be set on the initial user. This matches the behaviour when setting up
         * a new HSUM device.
         *
         * <p>Note that when this is set - a default affiliation ID will be added to the Device
         * Owner and to the Profile Owner set on any other users.
         */
        AFFILIATED;
    }

    int DO_PO_WEIGHT = MIDDLE;

    String DEFAULT_KEY = "deviceOwner";

    /**
     * The key used to identify this DPC.
     *
     * <p>This can be used with {@link AdditionalQueryParameters} to modify the requirements for
     * the DPC. */
    String key() default DEFAULT_KEY;

    /** Behaviour if the device owner cannot be set. */
    FailureMode failureMode() default FailureMode.FAIL;

    /**
     * Requirements for the DPC.
     *
     * <p>Defaults to the default version of RemoteDPC.
     */
    Query dpc() default @Query();

    /**
     * Whether this DPC should be returned by calls to {@code Devicestate#dpc()}.
     *
     * <p>Only one policy manager per test should be marked as primary.
     */
    boolean isPrimary() default false;

    /**
     * Affiliation ids to be set for the device owner.
     */
    String[] affiliationIds() default {};

    /**
     * Weight sets the order that annotations will be resolved.
     *
     * <p>Annotations with a lower weight will be resolved before annotations with a higher weight.
     *
     * <p>If there is an order requirement between annotations, ensure that the weight of the
     * annotation which must be resolved first is lower than the one which must be resolved later.
     *
     * <p>Weight can be set to a {@link AnnotationRunPrecedence} constant, or to any {@link int}.
     */
    int weight() default DO_PO_WEIGHT;

    /**
     * The type of device owner that is managing the device which can be {@link
     * DeviceOwnerType#DEFAULT} or {@link DeviceOwnerType#FINANCED}.
     */
    int type() default DeviceOwnerType.DEFAULT;

    /**
     * The behaviour when running tests on a HSUM device.
     */
    HeadlessDeviceOwnerType headlessDeviceOwnerType() default HeadlessDeviceOwnerType.AFFILIATED;
}
