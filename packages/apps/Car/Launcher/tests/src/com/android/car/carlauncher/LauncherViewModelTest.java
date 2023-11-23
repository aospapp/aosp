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

package com.android.car.carlauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@RunWith(AndroidJUnit4.class)
public final class LauncherViewModelTest extends AbstractExtendedMockitoTestCase {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();
    private LauncherViewModel mLauncherModel;
    @Mock
    private AppLauncherUtils.LauncherAppsInfo mLauncherAppsInfo;
    private Drawable mDrawable;
    private Consumer mConsumer;
    private List<LauncherItem> mCustomizedApps;
    private List<LauncherItem> mAlphabetizedApps;
    private List<AppMetaData> mApps;

    @Before
    public void setUp() throws Exception {
        mLauncherModel = new LauncherViewModel(
                new File("/data/user/10/com.android.car.carlauncher/files"));
        mCustomizedApps = new ArrayList<>();
        mAlphabetizedApps = new ArrayList<>();
        mLauncherAppsInfo = mock(AppLauncherUtils.LauncherAppsInfo.class);
        mDrawable = mock(Drawable.class);
        mConsumer = mock(Consumer.class);
        mApps = new ArrayList<>();
        AppMetaData app1 = new AppMetaData(
                "App1",
                new ComponentName("A", "A"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        AppMetaData app2 = new AppMetaData(
                "App2",
                new ComponentName("B", "B"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        AppMetaData app3 = new AppMetaData(
                "App3",
                new ComponentName("C", "C"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        mApps.add(app1);
        mApps.add(app2);
        mApps.add(app3);
        LauncherItem launcherItem1 = new AppItem(
                app1.getPackageName(),
                app1.getClassName(),
                app1.getDisplayName(),
                app1);
        LauncherItem launcherItem2 = new AppItem(
                app2.getPackageName(),
                app2.getClassName(),
                app2.getDisplayName(),
                app2);
        LauncherItem launcherItem3 = new AppItem(
                app3.getPackageName(),
                app3.getClassName(),
                app3.getDisplayName(),
                app3);
        mAlphabetizedApps.add(launcherItem1);
        mAlphabetizedApps.add(launcherItem2);
        mAlphabetizedApps.add(launcherItem3);
        mCustomizedApps.add(launcherItem2);
        mCustomizedApps.add(launcherItem3);
        mCustomizedApps.add(launcherItem1);
        when(mLauncherAppsInfo.getLaunchableComponentsList()).thenReturn(mApps);
    }

    @Test
    public void testConcurrentGenerateWithAlphabetizedApps() throws IOException {
        LauncherItemHelper helper = mock(LauncherItemHelper.class);
        mLauncherModel.setLauncherItemHelper(helper);

        for (int i = 0; i < 100; i++) {
            ExecutorService fetchOrderExecutorService = Executors.newSingleThreadExecutor();
            fetchOrderExecutorService.execute(() -> {
                mLauncherModel.updateAppsOrder();
                fetchOrderExecutorService.shutdown();
            });

            ExecutorService alphabetizeExecutorService = Executors.newSingleThreadExecutor();
            alphabetizeExecutorService.execute(() -> {
                mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
                alphabetizeExecutorService.shutdown();
            });

        }

        mLauncherModel.getCurrentLauncher().observeForever(launcherItems -> {
            assertEquals(3, launcherItems.size());
            assertEquals("A", launcherItems.get(0).getPackageName());
            assertEquals("B", launcherItems.get(1).getPackageName());
            assertEquals("C", launcherItems.get(2).getPackageName());
        });
    }

    @Test
    public void testGenerateWithAlphabetizedApps() throws IOException {
        LauncherItemHelper helper = mock(LauncherItemHelper.class);
        mLauncherModel.setLauncherItemHelper(helper);
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.getCurrentLauncher().observeForever(launcherItems -> {
            assertEquals(3, launcherItems.size());
            assertEquals("A", launcherItems.get(0).getPackageName());
            assertEquals("B", launcherItems.get(1).getPackageName());
            assertEquals("C", launcherItems.get(2).getPackageName());
        });
    }

    @Test
    public void testUpdateAppsOrderWithExistingOrder() {
        LauncherItemHelper helper = new LauncherItemHelper();
        mLauncherModel.setLauncherItemHelper(helper);
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        mLauncherModel.setInputStream(inputStream);
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.movePackage(0, mApps.get(2));
        ByteArrayOutputStream testOutputStream = new ByteArrayOutputStream();
        mLauncherModel.setOutputStream(testOutputStream);
        mLauncherModel.maybeSaveAppsOrder();
        InputStream newInputStream = new ByteArrayInputStream(
                testOutputStream.toByteArray());
        mLauncherModel.setInputStream(newInputStream);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals("C", mApps.get(2).getPackageName());
            assertTrue(mLauncherModel.isCustomized());
            assertEquals(3, it.size());
            assertEquals("C", it.get(0).getPackageName());
        });
    }

    @Test
    public void testUpdateAppsOrderWithNoExistingOrder() {
        LauncherItemHelper helper = new LauncherItemHelper();
        mLauncherModel.setLauncherItemHelper(helper);
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals(3, it.size());
            assertEquals("A", it.get(0).getPackageName());
        });
    }

    @Test
    public void testSaveAppsOrder() {
        mLauncherModel.setOutputStream(new ByteArrayOutputStream());
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            mLauncherModel.maybeSaveAppsOrder();
            assertNotNull(mLauncherModel.getOutputStream());
            assertTrue(
                    mLauncherModel.getOutputStream().toString().getBytes().length == 0);
        });
    }

    @Test
    public void testMovePackage() {
        mLauncherModel.setOutputStream(new ByteArrayOutputStream());
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.movePackage(0, mApps.get(2));
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals("C", mApps.get(2).getPackageName());
            assertEquals(3, it.size());
            assertEquals("C", it.get(0).getPackageName());
        });
    }

    @Test
    public void testAddPackageWithAlphabetizedApps() {
        mLauncherModel.setOutputStream(new ByteArrayOutputStream());
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        AppMetaData app4 = new AppMetaData(
                "App12",
                new ComponentName("D", "D"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        mLauncherModel.addPackage(app4);
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals(4, it.size());
            assertEquals("A", it.get(0).getPackageName());
            assertEquals("D", it.get(1).getPackageName());
            assertEquals("B", it.get(2).getPackageName());
            assertEquals("C", it.get(3).getPackageName());
        });
    }

    @Test
    public void testAddPackageWithCustomizedApps() {
        LauncherItemHelper helper = new LauncherItemHelper();
        mLauncherModel.setLauncherItemHelper(helper);
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.movePackage(0, mApps.get(2));
        ByteArrayOutputStream testOutputStream = new ByteArrayOutputStream();
        mLauncherModel.setOutputStream(testOutputStream);
        mLauncherModel.maybeSaveAppsOrder();
        InputStream newInputStream = new ByteArrayInputStream(
                testOutputStream.toByteArray());
        mLauncherModel.setInputStream(newInputStream);
        mLauncherModel.updateAppsOrder();
        AppMetaData app4 = new AppMetaData(
                "App12",
                new ComponentName("A1", "D"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        mLauncherModel.addPackage(app4);
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals(4, it.size());
            assertEquals("C", it.get(0).getPackageName());
            assertEquals("A", it.get(1).getPackageName());
            assertEquals("B", it.get(2).getPackageName());
            assertEquals("A1", it.get(3).getPackageName());
        });
    }

    @Test
    public void testRemovePackageWithAlphabetizedApps() {
        mLauncherModel.setOutputStream(new ByteArrayOutputStream());
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        AppMetaData app4 = new AppMetaData("App12",
                new ComponentName("A1", "D"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        mLauncherModel.addPackage(app4);
        mLauncherModel.removePackage(app4);
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals(3, it.size());
            assertEquals("A", it.get(0).getPackageName());
            assertEquals("B", it.get(1).getPackageName());
            assertEquals("C", it.get(2).getPackageName());
        });
    }

    @Test
    public void testRemovePackageWithCustomizedApps() {
        LauncherItemHelper helper = new LauncherItemHelper();
        mLauncherModel.setLauncherItemHelper(helper);
        mLauncherModel.generateAlphabetizedAppOrder(mLauncherAppsInfo);
        mLauncherModel.updateAppsOrder();
        mLauncherModel.movePackage(0, mApps.get(2));
        ByteArrayOutputStream testOutputStream = new ByteArrayOutputStream();
        mLauncherModel.setOutputStream(testOutputStream);
        mLauncherModel.maybeSaveAppsOrder();
        InputStream newInputStream = new ByteArrayInputStream(
                testOutputStream.toByteArray());
        mLauncherModel.setInputStream(newInputStream);
        mLauncherModel.updateAppsOrder();
        AppMetaData app4 = new AppMetaData(
                "App12",
                new ComponentName("A1", "D"),
                mDrawable,
                true,
                false,
                mConsumer,
                mConsumer);
        mLauncherModel.addPackage(app4);
        mLauncherModel.removePackage(app4);
        mLauncherModel.getCurrentLauncher().observeForever(it -> {
            assertEquals(3, it.size());
            assertEquals("C", it.get(0).getPackageName());
            assertEquals("A", it.get(1).getPackageName());
            assertEquals("B", it.get(2).getPackageName());
        });
    }
}
