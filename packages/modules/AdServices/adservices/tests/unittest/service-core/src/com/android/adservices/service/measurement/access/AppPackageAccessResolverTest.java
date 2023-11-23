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

package com.android.adservices.service.measurement.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AppPackageAccessResolverTest {
    private static final String ERROR_MESSAGE_FORMAT = "Package %s is not allowed to call the API.";
    private static final String ALLOW_LIST_ALL = "*";
    private static final String ALLOW_LIST_NONE = "";
    private static final String ALLOW_LIST_TWO_PACKAGES = "com.package.one,com.package.two";
    private static final String PACKAGE_1_ALLOW_LISTED = "com.package.one";
    private static final String PACKAGE_2_ALLOW_LISTED = "com.package.one";
    private static final String PACKAGE_OTHER = "com.package.other";

    @Mock private Context mContext;

    @Test
    public void isAllowed_allowListedPackages_success() {
        // Execution
        assertTrue(
                new AppPackageAccessResolver(ALLOW_LIST_TWO_PACKAGES, PACKAGE_1_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertTrue(
                new AppPackageAccessResolver(ALLOW_LIST_TWO_PACKAGES, PACKAGE_2_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertFalse(
                new AppPackageAccessResolver(ALLOW_LIST_TWO_PACKAGES, PACKAGE_OTHER)
                        .isAllowed(mContext));
    }

    @Test
    public void isAllowed_allAllowListedPackages_success() {
        // Execution
        assertTrue(
                new AppPackageAccessResolver(ALLOW_LIST_ALL, PACKAGE_1_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertTrue(
                new AppPackageAccessResolver(ALLOW_LIST_ALL, PACKAGE_2_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertTrue(new AppPackageAccessResolver(ALLOW_LIST_ALL, PACKAGE_OTHER).isAllowed(mContext));
    }

    @Test
    public void isAllowed_noneAllowListedPackages_success() {
        // Execution
        assertFalse(
                new AppPackageAccessResolver(ALLOW_LIST_NONE, PACKAGE_1_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertFalse(
                new AppPackageAccessResolver(ALLOW_LIST_NONE, PACKAGE_2_ALLOW_LISTED)
                        .isAllowed(mContext));
        assertFalse(
                new AppPackageAccessResolver(ALLOW_LIST_NONE, PACKAGE_OTHER).isAllowed(mContext));
    }

    @Test
    public void getErrorMessage() {
        // Execution
        assertEquals(
                String.format(ERROR_MESSAGE_FORMAT, PACKAGE_1_ALLOW_LISTED),
                new AppPackageAccessResolver(ALLOW_LIST_TWO_PACKAGES, PACKAGE_1_ALLOW_LISTED)
                        .getErrorMessage());
    }
}
