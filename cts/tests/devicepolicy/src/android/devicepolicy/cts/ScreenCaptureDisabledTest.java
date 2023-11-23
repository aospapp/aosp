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

package android.devicepolicy.cts;


import static com.android.bedstead.metricsrecorder.truth.MetricQueryBuilderSubject.assertThat;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.app.admin.DevicePolicyManager;
import android.app.admin.RemoteDevicePolicyManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.stats.devicepolicy.EventId;

import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ApplicationProvider;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.SlowApiTest;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.NegativePolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.PositivePolicyTest;
import com.android.bedstead.harrier.policies.ScreenCaptureDisabled;
import com.android.bedstead.metricsrecorder.EnterpriseMetricsRecorder;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.testapp.TestApp;
import com.android.bedstead.testapp.TestAppInstance;
import com.android.bedstead.testapp.TestAppProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;


@RunWith(BedsteadJUnit4.class)
public final class ScreenCaptureDisabledTest {

    @ClassRule
    @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final TestAppProvider sTestAppProvider = new TestAppProvider();
    private static final TestApp sTestApp =
            sTestAppProvider.query().whereActivities().isNotEmpty().get();
    private RemoteDevicePolicyManager mDevicePolicyManager;
    private DevicePolicyManager mLocalDevicePolicyManager;
    private ComponentName mAdmin;
    private UiAutomation mUiAutomation;

    @Before
    public void setUp() {
        mAdmin = sDeviceState.dpc().componentName();
        mDevicePolicyManager = sDeviceState.dpc().devicePolicyManager();
        //TODO(b/198593716) : Use TestApi to take screnshot instead of UiAutomation.
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mLocalDevicePolicyManager = ApplicationProvider.getApplicationContext().getSystemService(
                DevicePolicyManager.class);
    }

    @After
    public void tearDown() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, false);
    }

    @Test
    @PositivePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_false_works() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, false);

        assertThat(mLocalDevicePolicyManager.getScreenCaptureDisabled(/* admin= */ null)).isFalse();
    }

    @Test
    @CanSetPolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_false_checkWithDPC_works() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, false);

        assertThat(mDevicePolicyManager.getScreenCaptureDisabled(mAdmin)).isFalse();
    }

    @Test
    @PositivePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_true_works() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

        assertThat(mLocalDevicePolicyManager.getScreenCaptureDisabled(/* admin= */ null)).isTrue();
    }

    @Test
    @CanSetPolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_true_checkWithDPC_works() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

        assertThat(mDevicePolicyManager.getScreenCaptureDisabled(mAdmin)).isTrue();
    }

    @Test
    @NegativePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_true_doesNotApply() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

        assertThat(mLocalDevicePolicyManager.getScreenCaptureDisabled(/* admin= */ null)).isFalse();
    }

    @Test
    @NegativePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_true_screenCaptureWorks() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

        assertThat(takeScreenshotExpectingSuccess()).isNotNull();
    }

    @Test
    @PositivePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    @SlowApiTest("Screenshot policy can take minutes to propagate")
    public void setScreenCaptureDisabled_true_screenCaptureFails() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

        assertThat(takeScreenshotExpectingFailure()).isNull();
    }

    @Test
    @PositivePolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_false_screenCaptureWorks() {
        mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, false);

        assertThat(takeScreenshotExpectingSuccess()).isNotNull();
    }

    @Test
    @CanSetPolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_true_metricsLogged() {
        try (EnterpriseMetricsRecorder metrics = EnterpriseMetricsRecorder.create()) {
            mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, true);

            assertThat(metrics.query()
                    .whereType().isEqualTo(EventId.SET_SCREEN_CAPTURE_DISABLED_VALUE)
                    .whereAdminPackageName().isEqualTo(mAdmin.getPackageName())
                    .whereBoolean().isTrue()).wasLogged();
        }
    }

    @Test
    @CanSetPolicyTest(policy = ScreenCaptureDisabled.class)
    @Postsubmit(reason = "new test")
    public void setScreenCaptureDisabled_false_metricsLogged() {
        try (EnterpriseMetricsRecorder metrics = EnterpriseMetricsRecorder.create()) {
            mDevicePolicyManager.setScreenCaptureDisabled(mAdmin, false);

            assertThat(metrics.query()
                    .whereType().isEqualTo(EventId.SET_SCREEN_CAPTURE_DISABLED_VALUE)
                    .whereAdminPackageName().isEqualTo(mAdmin.getPackageName())
                    .whereBoolean().isFalse()).wasLogged();
        }
    }

    private Bitmap takeScreenshotExpectingFailure() {
        try (TestAppInstance testApp = sTestApp.install()) {
            testApp.activities().any().start();
            return Poll.forValue(mUiAutomation::takeScreenshot)
                    .timeout(Duration.ofMinutes(5))
                    .toBeNull()
                    .await();
        }
    }

    private Bitmap takeScreenshotExpectingSuccess() {
        try (TestAppInstance testApp = sTestApp.install()) {
            testApp.activities().any().start();
            return Poll.forValue(mUiAutomation::takeScreenshot)
                    .toNotBeNull()
                    .await();
        }
    }
}
