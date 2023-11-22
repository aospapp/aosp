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

import org.junit.Before;
import org.junit.Test;

/**
 * Runtime permission behavior tests for apps targeting {@link android.os.Build.VERSION_CODES#Q}.
 */
public class UsePermissionTest29 extends BasePermissionsTest {
    private static final int REQUEST_CODE_PERMISSIONS = 42;

    public interface UiInteraction {
        void run() throws Exception;
    }

    private static void assertGranted(String permission) {
        assertEquals(PERMISSION_GRANTED, getInstrumentation().getContext()
                .checkSelfPermission(permission));
    }

    private static void assertDenied(String permission) {
        assertEquals(PERMISSION_DENIED, getInstrumentation().getContext()
                .checkSelfPermission(permission));
    }

    private BasePermissionActivity.Result requestPermissions(String[] permissions,
            UiInteraction... uiInteractions) throws Exception {
        return super.requestPermissions(permissions,
                REQUEST_CODE_PERMISSIONS,
                BasePermissionActivity.class,
                () -> {
                    try {
                        for (UiInteraction uiInteraction : uiInteractions) {
                            uiInteraction.run();
                            getUiDevice().waitForIdle();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static void assertPermissionRequestResult(BasePermissionActivity.Result result,
            String[] permissions, boolean... granted) {
        BasePermissionsTest.assertPermissionRequestResult(result, REQUEST_CODE_PERMISSIONS,
                permissions, granted);
    }

    @Before
    public void assertPermissionsNotGranted() {
        assertDenied(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void locationPermissionIsNotSplit() throws Exception {
        String[] permissions = {ACCESS_FINE_LOCATION};

        BasePermissionActivity.Result result = requestPermissions(permissions,
                this::clickAllowForegroundButton);
        assertPermissionRequestResult(result, permissions, true);

        assertGranted(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void requestOnlyBackgroundNotPossible() throws Exception {
        String[] permissions = {ACCESS_BACKGROUND_LOCATION};

        BasePermissionActivity.Result result = requestPermissions(permissions);
        assertPermissionRequestResult(result, permissions, false);

        assertDenied(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void requestBoth() throws Exception {
        String[] permissions = {ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION};

        BasePermissionActivity.Result result = requestPermissions(permissions,
                this::clickAllowAlwaysButton);
        assertPermissionRequestResult(result, permissions, true, true);

        assertGranted(ACCESS_FINE_LOCATION);
        assertGranted(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void requestBothInSequence() throws Exception {
        // Step 1: request foreground only
        String[] permissions = {ACCESS_FINE_LOCATION};

        BasePermissionActivity.Result result = requestPermissions(permissions,
                this::clickAllowForegroundButton);
        assertPermissionRequestResult(result, permissions, true);

        assertGranted(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);

        // Step 2: request background only
        permissions = new String[]{ACCESS_BACKGROUND_LOCATION};

        result = requestPermissions(permissions, this::clickAllowButton);
        assertPermissionRequestResult(result, permissions, true);

        assertGranted(ACCESS_FINE_LOCATION);
        assertGranted(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void requestBothButGrantInSequence() throws Exception {
        // Step 1: grant foreground only
        String[] permissions = {ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION};
        BasePermissionActivity.Result result = requestPermissions(permissions,
                this::clickAllowForegroundButton);
        assertPermissionRequestResult(result, permissions, true, false);

        assertGranted(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);

        // Step 2: grant background
        result = requestPermissions(permissions, this::clickAllowButton);
        assertPermissionRequestResult(result, permissions, true, true);

        assertGranted(ACCESS_FINE_LOCATION);
        assertGranted(ACCESS_BACKGROUND_LOCATION);
    }

    @Test
    public void denyBackgroundWithPrejudice() throws Exception {
        // Step 1: deny the first time
        String[] permissions = {ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION};

        BasePermissionActivity.Result result = requestPermissions(permissions,
                this::clickDenyButton);
        assertPermissionRequestResult(result, permissions, false, false);

        assertDenied(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);

        // Step 2: deny with prejudice
        result = requestPermissions(permissions, this::clickDenyAndDontAskAgainButton);
        assertPermissionRequestResult(result, permissions, false, false);

        assertDenied(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);

        // Step 3: All further requests should be denied automatically
        result = requestPermissions(permissions);
        assertPermissionRequestResult(result, permissions, false, false);

        assertDenied(ACCESS_FINE_LOCATION);
        assertDenied(ACCESS_BACKGROUND_LOCATION);
    }
}
