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

package com.android.adservices.service.common;

import static android.adservices.common.AdServicesPermissions.ACCESS_ADSERVICES_AD_ID;
import static android.adservices.common.AdServicesPermissions.ACCESS_ADSERVICES_CUSTOM_AUDIENCE;
import static android.adservices.common.AdServicesPermissions.ACCESS_ADSERVICES_TOPICS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.adservices.common.AdServicesPermissions;
import android.content.pm.PackageManager;
import android.test.mock.MockContext;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link com.android.adservices.service.common.PermissionHelper} */
@SmallTest
public class PermissionHelperTest {
    private static final String SDK_PACKAGE_NAME = "test_package_name";
    @Mock private PackageManager mMockPackageManager;
    MockContext mMockContextGrant =
            new MockContext() {
                @Override
                public int checkCallingOrSelfPermission(String permission) {
                    return PackageManager.PERMISSION_GRANTED;
                }

                @Override
                public PackageManager getPackageManager() {
                    return mMockPackageManager;
                }
            };

    MockContext mMockContextDeny =
            new MockContext() {
                @Override
                public int checkCallingOrSelfPermission(String permission) {
                    return PackageManager.PERMISSION_DENIED;
                }

                @Override
                public PackageManager getPackageManager() {
                    return mMockPackageManager;
                }
            };

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testHasPermission_notUseSandboxCheck() {
        assertThat(
                        PermissionHelper.hasTopicsPermission(
                                mMockContextGrant, /*useSandboxCheck =*/ false, SDK_PACKAGE_NAME))
                .isTrue();
        assertThat(
                        PermissionHelper.hasAdIdPermission(
                                mMockContextGrant, /*useSandboxCheck =*/ false, SDK_PACKAGE_NAME))
                .isTrue();
        assertThat(PermissionHelper.hasAttributionPermission(mMockContextGrant)).isTrue();
        assertThat(PermissionHelper.hasCustomAudiencesPermission(mMockContextGrant)).isTrue();
    }

    @Test
    public void testNotHasPermission() {
        assertThat(
                        PermissionHelper.hasTopicsPermission(
                                mMockContextDeny, /*useSandboxCheck =*/ false, SDK_PACKAGE_NAME))
                .isFalse();
        assertThat(
                        PermissionHelper.hasAdIdPermission(
                                mMockContextDeny, /*useSandboxCheck =*/ false, SDK_PACKAGE_NAME))
                .isFalse();
        assertThat(PermissionHelper.hasAttributionPermission(mMockContextDeny)).isFalse();
        assertThat(PermissionHelper.hasCustomAudiencesPermission(mMockContextDeny)).isFalse();
    }

    @Test
    public void testSdkHasPermission() {
        when(mMockPackageManager.checkPermission(ACCESS_ADSERVICES_TOPICS, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockPackageManager.checkPermission(ACCESS_ADSERVICES_AD_ID, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockPackageManager.checkPermission(
                        AdServicesPermissions.ACCESS_ADSERVICES_ATTRIBUTION, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mMockPackageManager.checkPermission(
                        ACCESS_ADSERVICES_CUSTOM_AUDIENCE, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        assertThat(
                        PermissionHelper.hasTopicsPermission(
                                mMockContextGrant, /*useSandboxCheck =*/ true, SDK_PACKAGE_NAME))
                .isTrue();

        // TODO(b/240718367): Check Sdk permission for adid.
        // assertThat(PermissionHelper.hasAdIdPermission(mMockContextGrant, /*useSandboxCheck
        // =*/ true,

        // TODO(b/236267953): Check Sdk permission for Attribution.
        // assertThat(PermissionHelper.hasAttributionPermission(mMockContextGrant, /*useSandboxCheck
        // =*/ true,
        // SDK_PACKAGE_NAME)).isTrue();

        // TODO(b/236268316): Check Sdk permission for Custom Audiences.
        // assertThat(PermissionHelper.hasCustomAudiencesPermission(mMockContextGrant,
        // /*useSandboxCheck =*/ true,
        // SDK_PACKAGE_NAME)).isTrue();
    }

    @Test
    public void testSdkNotHasPermission() {
        when(mMockPackageManager.checkPermission(ACCESS_ADSERVICES_TOPICS, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkPermission(ACCESS_ADSERVICES_AD_ID, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkPermission(
                        AdServicesPermissions.ACCESS_ADSERVICES_ATTRIBUTION, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(mMockPackageManager.checkPermission(
                        ACCESS_ADSERVICES_CUSTOM_AUDIENCE, SDK_PACKAGE_NAME))
                .thenReturn(PackageManager.PERMISSION_DENIED);

        assertThat(
                        PermissionHelper.hasTopicsPermission(
                                mMockContextDeny, /*useSandboxCheck =*/ true, SDK_PACKAGE_NAME))
                .isFalse();

        // TODO(b/240718367): Check Sdk permission for Adid.
        // assertThat(PermissionHelper.hasAdIdPermission(mMockContextDeny, /*useSandboxCheck
        // =*/ true,
        // SDK_PACKAGE_NAME)).isFalse();

        // TODO(b/236267953): Check Sdk permission for Attribution.
        // assertThat(PermissionHelper.hasAttributionPermission(mMockContextDeny, /*useSandboxCheck
        // =*/ true,
        // SDK_PACKAGE_NAME)).isFalse();

        // TODO(b/236268316): Check Sdk permission for Custom Audiences.
        // assertThat(PermissionHelper.hasCustomAudiencesPermission(mMockContextDeny,
        // /*useSandboxCheck =*/ true,
        // SDK_PACKAGE_NAME)).isFalse();
    }
}
