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

package android.systemui.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests related MediaOutputDialog:
 *
 * atest MediaDialogTest
 */
@RunWith(AndroidJUnit4.class)
public class MediaOutputDialogTest {

    private static final int TIMEOUT = 5000;
    private static final String ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG";
    private static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String TEST_PACKAGE_NAME = "com.android.package.test";
    private static final BySelector MEDIA_DIALOG_SELECTOR = By.res(SYSTEMUI_PACKAGE_NAME,
            "media_output_dialog");

    private Context mContext;
    private UiDevice mDevice;
    private String mLauncherPackage;
    private boolean mHasTouchScreen;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        final PackageManager packageManager = mContext.getPackageManager();

        mHasTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
                || packageManager.hasSystemFeature(PackageManager.FEATURE_FAKETOUCH);

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = packageManager.resolveActivity(launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        assumeFalse("Skipping test: can't get resolve info", resolveInfo == null);
        assumeFalse("Skipping test: not supported on automotive yet",
                packageManager.hasSystemFeature(
                        PackageManager.FEATURE_AUTOMOTIVE));
        mLauncherPackage = resolveInfo.activityInfo.packageName;
    }

    @Test
    public void mediaOutputDialog_correctDialog() {
        assumeTrue(mHasTouchScreen);
        launchMediaOutputDialog();

        assertThat(mDevice.wait(Until.hasObject(MEDIA_DIALOG_SELECTOR), TIMEOUT)).isTrue();
    }

    private void launchMediaOutputDialog() {
        mDevice.pressHome();
        mDevice.wait(Until.hasObject(By.pkg(mLauncherPackage).depth(0)), TIMEOUT);

        Intent intent = new Intent();
        intent.setPackage(SYSTEMUI_PACKAGE_NAME)
                .setAction(ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG)
                .putExtra(EXTRA_PACKAGE_NAME, TEST_PACKAGE_NAME);

        mContext.sendBroadcast(intent);
    }

}
