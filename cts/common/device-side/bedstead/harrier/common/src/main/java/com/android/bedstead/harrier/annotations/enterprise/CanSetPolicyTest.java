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

import static com.android.bedstead.harrier.annotations.AnnotationRunPrecedence.PRECEDENCE_NOT_IMPORTANT;

import com.android.bedstead.harrier.annotations.AnnotationRunPrecedence;
import com.android.bedstead.harrier.annotations.meta.RequiresBedsteadJUnit4;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
/**
 * Mark a test as testing the states where a policy is allowed to be applied.
 *
 * <p>This will generate parameterized runs for all matching states. Tests will only be run on
 * the same user as the DPC. If you wish to test that a policy applies across all relevant states,
 * use {@link PolicyAppliesTest}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequiresBedsteadJUnit4
public @interface CanSetPolicyTest {
    /**
     * The policy being tested.
     *
     * <p>If multiple policies are specified, then they will be merged so that all valid states for
     * all specified policies are considered as valid.
     *
     * <p>This is used to calculate which states are required to be tested.
     */
    Class<?>[] policy();

    /**
     * If true, this test will only be run in a single state.
     *
     * <p>This is useful for tests of invalid inputs, where running in multiple states is unlikely
     * to add meaningful coverage.
     *
     * By default, all states where the policy can be set will be included.
     */
    boolean singleTestOnly() default false;

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
    int weight() default PRECEDENCE_NOT_IMPORTANT;
}
