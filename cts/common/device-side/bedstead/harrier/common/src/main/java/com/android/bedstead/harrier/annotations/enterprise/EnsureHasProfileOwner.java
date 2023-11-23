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

import static com.android.bedstead.harrier.UserType.INSTRUMENTED_USER;
import static com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner.DO_PO_WEIGHT;
import static com.android.bedstead.nene.packages.CommonPackages.FEATURE_DEVICE_ADMIN;

import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.AnnotationRunPrecedence;
import com.android.bedstead.harrier.annotations.RequireFeature;
import com.android.bedstead.harrier.annotations.RequireNotInstantApp;
import com.android.queryable.annotations.Query;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark that a test requires that a profile owner is set.
 *
 * <p>You can use {@code Devicestate} to ensure that the device enters
 * the correct state for the method. If using {@code Devicestate}, you can use
 * {@code Devicestate#profileOwner()} to interact with the profile owner.
 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@RequireFeature(FEATURE_DEVICE_ADMIN)
// TODO(b/206441366): Add instant app support
@RequireNotInstantApp(reason = "Instant Apps cannot run Enterprise Tests")
public @interface EnsureHasProfileOwner {
    /** Which user type the profile owner should be installed on. */
    UserType onUser() default INSTRUMENTED_USER;

    String DEFAULT_KEY = "profileOwner";

    /**
     * The key used to identify this DPC.
     *
     * <p>This can be used with {@link AdditionalQueryParameters} to modify the requirements for
     * the DPC. */
    String key() default DEFAULT_KEY;

    /**
     * Requirements for the DPC
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
     * If true, uses the {@code DevicePolicyManager#getParentProfileInstance(ComponentName)}
     * instance of the dpc when calling to .dpc()
     *
     * <p>Only used if {@link #isPrimary()} is true.
     */
    boolean useParentInstance() default false;

    /**
     * Affiliation ids to be set for the profile owner.
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
}
