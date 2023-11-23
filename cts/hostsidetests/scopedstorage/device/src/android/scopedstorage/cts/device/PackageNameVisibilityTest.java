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

package android.scopedstorage.cts.device;

import static androidx.test.InstrumentationRegistry.getTargetContext;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.scopedstorage.cts.lib.TestUtils;

import androidx.test.filters.SdkSuppress;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
public final class PackageNameVisibilityTest {

    private static final String TAG = PackageNameVisibilityTest.class.getSimpleName();

    /**
     * The idea of this test is to create 2 media files by TEST_APP_A and TEST_APP_B and then
     * query them from all 4 different apps to verify owner_package_name filtering.
     * 1. Only owner_package_name of sMediaUriCreatedByAppB should be
     * visible to TEST_APP_WITH_APP_B_IN_QUERIES_TAG.
     * 2. Owner_package_name of both media files should be visible to
     * TEST_APP_WITH_QUERY_ALL_PACKAGES_TAG.
     * 3. Only owner_package_name of sMediaUriCreatedByAppA should be visible to TEST_APP_A.
     * 4. Only owner_package_name of sMediaUriCreatedByAppB should be visible to TEST_APP_B.
     */
    private static final TestApp TEST_APP_WITH_APP_B_IN_QUERIES_TAG = new TestApp(
            "TestAppWithQueriesTag",
            "android.scopedstorage.cts.testapp.withqueriestag", 1, false,
            "CtsTestAppWithQueriesTag.apk");

    private static final TestApp TEST_APP_WITH_QUERY_ALL_PACKAGES_TAG = new TestApp(
            "TestAppWithQueryAllPackagesPermission",
            "android.scopedstorage.cts.testapp.withqueryallpackagestag", 1, false,
            "CtsTestAppWithQueryAllPackagesPermission.apk");
    private static final TestApp TEST_APP_A = new TestApp("TestAppFileManager",
            "android.scopedstorage.cts.testapp.filemanager", 1, false,
            "CtsScopedStorageTestAppFileManager.apk");

    private static final TestApp TEST_APP_B = new TestApp("TestAppB",
            "android.scopedstorage.cts.testapp.B.noperms", 1, false,
            "CtsScopedStorageTestAppB.apk");

    private static Uri sMediaUriCreatedByAppA;
    private static Uri sMediaUriCreatedByAppB;

    @BeforeClass
    public static void setUp() throws Exception {
        TestUtils.waitForMountedAndIdleState(getTargetContext().getContentResolver());

        sMediaUriCreatedByAppA = createMediaAs(TEST_APP_A);
        sMediaUriCreatedByAppB = createMediaAs(TEST_APP_B);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        TestUtils.deleteMediaByUriAs(TEST_APP_A, sMediaUriCreatedByAppA);
        TestUtils.deleteMediaByUriAs(TEST_APP_B, sMediaUriCreatedByAppB);
    }

    @Test
    public void testNotQueryableOwnerPackageName() throws Exception {
        final String[] ownerPackageNames = TestUtils.queryForOwnerPackageNamesAs(
                TEST_APP_WITH_APP_B_IN_QUERIES_TAG, sMediaUriCreatedByAppA);

        assertEquals(0, ownerPackageNames.length);
    }

    @Test
    public void testQueryableOwnerPackageName() throws Exception {
        final String[] ownerPackageNames = TestUtils.queryForOwnerPackageNamesAs(
                TEST_APP_WITH_APP_B_IN_QUERIES_TAG, sMediaUriCreatedByAppB);

        assertEquals(Arrays.asList(TEST_APP_B.getPackageName()),
                Arrays.asList(ownerPackageNames));
    }

    @Test
    public void testQueryAllPackagesTag() throws Exception {
        final String[] ownerPackageNames = TestUtils.queryForOwnerPackageNamesAs(
                TEST_APP_WITH_QUERY_ALL_PACKAGES_TAG, sMediaUriCreatedByAppA);

        assertEquals(Arrays.asList(TEST_APP_A.getPackageName()), Arrays.asList(ownerPackageNames));
    }

    @Test
    public void testSimpleQueryWithoutPackageNameInQueryArgs() throws Exception {
        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A, sMediaUriCreatedByAppB, null);

        // Make sure that a regular simple query works and not filters the result
        assertEquals(1, resultSize);
    }

    @Test
    public void testOwnerPackageNameInSelection_filtered() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{TEST_APP_B.getPackageName()});

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A, sMediaUriCreatedByAppB, queryArgs);

        // Requested media is filtered because it's not self-owned
        assertEquals(0, resultSize);
    }

    @Test
    public void testOwnerPackageNameInSelection_selfOwned() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{TEST_APP_B.getPackageName()});

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_B, sMediaUriCreatedByAppB, queryArgs);

        // Requested media is present because it's self-owned
        assertEquals(1, resultSize);
    }

    @Test
    public void testOwnerPackageNameInGroupBy() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_GROUP_BY,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME);

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A,
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), queryArgs);

        // Only self-owned media is present, everything else is filtered
        assertEquals(1, resultSize);
    }

    @Test
    public void testOwnerPackageNameInSort() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " ASC");

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_B, sMediaUriCreatedByAppA, queryArgs);

        // Requested media is filtered because it's not self-owned
        assertEquals(0, resultSize);
    }

    @Test
    public void testOwnerPackageNameInHaving() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_GROUP_BY,
                MediaStore.MediaColumns.DISPLAY_NAME);
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_HAVING,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = '"
                        + TEST_APP_B.getPackageName() + "'");

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A, sMediaUriCreatedByAppB, queryArgs);

        // Requested media is filtered because it's not self-owned
        assertEquals(0, resultSize);
    }

    @Test
    public void testQueryArgsInLowerCase() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "owner_package_name = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{TEST_APP_B.getPackageName()});

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A, sMediaUriCreatedByAppB, queryArgs);

        // Requested media is filtered because it's not self-owned
        assertEquals(0, resultSize);
    }

    @Test
    public void testQueryArgsInUpperCase() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "OWNER_PACKAGE_NAME = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{TEST_APP_B.getPackageName()});

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_A, sMediaUriCreatedByAppB, queryArgs);

        // Requested media is filtered because it's not self-owned
        assertEquals(0, resultSize);
    }

    @Test
    public void testQueryArgsWithQueryAllPackages() throws Exception {
        final Bundle queryArgs = new Bundle();
        queryArgs.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ?");
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                new String[]{TEST_APP_B.getPackageName()});

        int resultSize = TestUtils.queryWithArgsAs(TEST_APP_WITH_QUERY_ALL_PACKAGES_TAG,
                sMediaUriCreatedByAppB, queryArgs);

        // Requested not self-owned media is present because of QUERY_ALL_PACKAGES permission
        assertEquals(1, resultSize);
    }

    private static Uri createMediaAs(TestApp testApp) {
        try {
            return TestUtils.createImageEntryForUriAs(testApp,
                    "/" + System.nanoTime() + ".jpg");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
