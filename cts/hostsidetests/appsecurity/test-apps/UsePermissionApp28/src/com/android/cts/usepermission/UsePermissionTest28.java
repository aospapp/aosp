/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.cts.usepermission;

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static junit.framework.Assert.assertEquals;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import org.junit.Test;

/**
 * Runtime permission behavior tests for apps targeting API 28
 */
public class UsePermissionTest28 extends BasePermissionsTest {
    private static final int REQUEST_CODE_PERMISSIONS = 42;

    @Test
    public void testLocationPermissionWasSplit() throws Exception {
        Context context = getInstrumentation().getTargetContext();

        assertEquals(PERMISSION_DENIED, context.checkSelfPermission(ACCESS_FINE_LOCATION));
        assertEquals(PERMISSION_DENIED, context.checkSelfPermission(ACCESS_BACKGROUND_LOCATION));

        String[] permissions = {ACCESS_FINE_LOCATION};

        // request only foreground permission. This should automatically also add the background
        // permission
        BasePermissionActivity.Result result = requestPermissions(permissions,
                REQUEST_CODE_PERMISSIONS,
                BasePermissionActivity.class,
                () -> {
                    try {
                        clickAllowAlwaysButton();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        assertPermissionRequestResult(result, REQUEST_CODE_PERMISSIONS, permissions,
                new boolean[]{true});

        assertEquals(PERMISSION_GRANTED, context.checkSelfPermission(ACCESS_FINE_LOCATION));
        assertEquals(PERMISSION_GRANTED, context.checkSelfPermission(ACCESS_BACKGROUND_LOCATION));
    }
}
