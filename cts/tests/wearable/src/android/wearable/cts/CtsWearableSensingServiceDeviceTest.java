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
package android.wearable.cts;

import static android.wearable.cts.CtsWearableSensingService.whenCallbackTriggeredRespondWithServiceStatus;
import static android.wearable.cts.CtsWearableSensingService.whenCallbackTriggeredRespondWithStatus;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;

import android.app.ambientcontext.AmbientContextManager;
import android.app.wearable.WearableSensingManager;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;
import android.platform.test.annotations.AppModeFull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.DeviceConfigStateChangerRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;

/**
 * This suite of test ensures that WearableSensingManagerService behaves correctly when properly
 * bound to a WearableSensingService implementation.
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull(
        reason = "PM will not recognize CtsWearableSensingService in instantMode.")
public class CtsWearableSensingServiceDeviceTest {
    private static final String NAMESPACE_wearable_sensing = "wearable_sensing";
    private static final String NAMESPACE_ambient_context = "ambient_context";

    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String FAKE_APP_PACKAGE = "foo.bar.baz";
    private static final String CTS_PACKAGE_NAME =
            CtsWearableSensingService.class.getPackage().getName();
    private static final String CTS_SERVICE_NAME = CTS_PACKAGE_NAME + "/."
            + CtsWearableSensingService.class.getSimpleName();
    private static final int USER_ID = UserHandle.myUserId();
    private static final int TEMPORARY_SERVICE_DURATION = 5000;

    private static final String KEY_FOR_PROVIDE_DATA = "foo.bar.baz_key_for_provide_data";
    private static final int VALUE_TO_SEND = 1000;
    private static final String VALUE_TO_WRITE = "wearable_sensing";

    @Rule
    public final DeviceConfigStateChangerRule mLookAllTheseRules =
            new DeviceConfigStateChangerRule(getInstrumentation().getTargetContext(),
                    NAMESPACE_wearable_sensing,
                    KEY_SERVICE_ENABLED,
                    "true");

    @Rule
    public final DeviceConfigStateChangerRule mAmbientContextRules =
            new DeviceConfigStateChangerRule(getInstrumentation().getTargetContext(),
                    NAMESPACE_ambient_context,
                    KEY_SERVICE_ENABLED,
                    "true");

    @Before
    public void setUp() throws Exception {
        assumeTrue("VERSION.SDK_INT=" + VERSION.SDK_INT,
                VERSION.SDK_INT >= VERSION_CODES.UPSIDE_DOWN_CAKE);

        CtsWearableSensingService.reset();
        clearTestableWearableSensingService();
        destroyDataStream();
        bindToTestableWearableSensingService();
        createDataStream();
    }

    @Test
    public void testProvideDataStream_success() {
        CtsWearableSensingService.whenCallbackTriggeredRespondWithStatus(
                WearableSensingManager.STATUS_SUCCESS);

        provideDataStream();
        CtsWearableSensingService.awaitResult();

        assertThat(getLastStatusCode()).isEqualTo(WearableSensingManager.STATUS_SUCCESS);
    }

    @Test
    public void testProvideDataStream_failure() {
        whenCallbackTriggeredRespondWithStatus(WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);

        provideDataStream();
        CtsWearableSensingService.awaitResult();

        assertThat(getLastStatusCode()).isEqualTo(
                WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
    }

    @Test
    public void testProvideDataStream_writeData_reads() throws Exception {
        whenCallbackTriggeredRespondWithStatus(WearableSensingManager.STATUS_SUCCESS);

        provideDataStream();
        writeDataToStream();
        CtsWearableSensingService.awaitResult();

        assertThat(readDataStream()).isEqualTo(VALUE_TO_WRITE);
    }

    @Test
    public void testProvideData_success() {
        whenCallbackTriggeredRespondWithStatus(WearableSensingManager.STATUS_SUCCESS);

        provideData();
        CtsWearableSensingService.awaitResult();

        assertThat(getLastStatusCode()).isEqualTo(WearableSensingManager.STATUS_SUCCESS);
    }

    @Test
    public void testProvideData_failure() {
        whenCallbackTriggeredRespondWithStatus(WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);

        provideData();
        CtsWearableSensingService.awaitResult();

        assertThat(getLastStatusCode()).isEqualTo(
                WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
    }

    @Test
    public void testProvideData_getsRightData() {
        whenCallbackTriggeredRespondWithStatus(WearableSensingManager.STATUS_SUCCESS);

        provideData();
        CtsWearableSensingService.awaitResult();

        assertThat(CtsWearableSensingService.getData().getInt(KEY_FOR_PROVIDE_DATA))
                .isEqualTo(VALUE_TO_SEND);
    }

    @Test
    public void startDetection_wearable_getsCall() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        whenCallbackTriggeredRespondWithServiceStatus(AmbientContextManager.STATUS_SUCCESS);

        callStartDetection();
        CtsWearableSensingService.awaitResultAmbientContextDetectionService();

        assertThat(getLastStatusCodeAmbientDetectionService())
                .isEqualTo(AmbientContextManager.STATUS_SUCCESS);
    }

    // Requesting for a detection start with mixed events will not be
    @Test
    public void startDetection_mixed_doesNotGetCall() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);

        callStartDetectionMixed();

        CtsWearableSensingService.expectTimeOut();
    }

    @Test
    public void stopDetection_wearable_getsCall() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        whenCallbackTriggeredRespondWithServiceStatus(AmbientContextManager.STATUS_SUCCESS);
        callStartDetection();
        CtsWearableSensingService.awaitResultAmbientContextDetectionService();

        callStopDetection();
        CtsWearableSensingService.awaitResultAmbientContextDetectionService();

        assertThat(CtsWearableSensingService.getLastCallingPackage()).isEqualTo(FAKE_APP_PACKAGE);
    }

    @Test
    public void stopDetection_notWearable_doesNotGetCall() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        callStartDetectionDefault();
        CtsWearableSensingService.expectTimeOut();

        callStopDetection();
        CtsWearableSensingService.expectTimeOut();

        assertThat(CtsWearableSensingService.getLastCallingPackage()).isNull();
    }

    @Test
    public void queryServiceStatus_available() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        whenCallbackTriggeredRespondWithServiceStatus(AmbientContextManager.STATUS_SUCCESS);

        callQueryWearableServiceStatus();
        CtsWearableSensingService.awaitResultAmbientContextDetectionService();

        assertThat(getLastStatusCodeAmbientDetectionService())
                .isEqualTo(AmbientContextManager.STATUS_SUCCESS);
    }

    @Test
    public void queryDefaultServiceStatus_notCalled() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        whenCallbackTriggeredRespondWithServiceStatus(AmbientContextManager.STATUS_SUCCESS);

        callQueryServiceStatus();

        CtsWearableSensingService.expectTimeOut();
    }

    @Test
    public void queryMixedServiceStatus_notCalled() {
        setTestableAmbientContextDetectionService(CTS_SERVICE_NAME);
        whenCallbackTriggeredRespondWithServiceStatus(AmbientContextManager.STATUS_SUCCESS);

        callQueryMixedServiceStatus();

        CtsWearableSensingService.expectTimeOut();
    }

    @After
    public void tearDown() {
        clearTestableWearableSensingService();
        clearTestableAmbientContextDetectionService();
        destroyDataStream();
    }

    private void bindToTestableWearableSensingService() {
        // On Manager, bind to test service
        assertThat(getWearableSensingServiceComponent()).isNotEqualTo(CTS_SERVICE_NAME);
        setTestableWearableSensingService(CTS_SERVICE_NAME);
        assertThat(CTS_SERVICE_NAME).contains(getWearableSensingServiceComponent());
    }

    private String getWearableSensingServiceComponent() {
        return runShellCommand("cmd wearable_sensing get-bound-package %d", USER_ID);
    }

    private void setTestableWearableSensingService(String service) {
        runShellCommand("cmd wearable_sensing set-temporary-service %d %s %d",
                USER_ID, service, TEMPORARY_SERVICE_DURATION);
    }

    private void clearTestableWearableSensingService() {
        runShellCommand("cmd wearable_sensing set-temporary-service %d", USER_ID);
    }

    private void callStartDetection() {
        runShellCommand("cmd ambient_context start-detection-wearable %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void callStartDetectionMixed() {
        runShellCommand("cmd ambient_context start-detection-mixed %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void callStartDetectionDefault() {
        runShellCommand("cmd ambient_context start-detection %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void createDataStream() {
        runShellCommand("cmd wearable_sensing create-data-stream");
    }

    private void destroyDataStream() {
        runShellCommand("cmd wearable_sensing destroy-data-stream");
    }

    private void callStopDetection() {
        runShellCommand("cmd ambient_context stop-detection %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void provideDataStream() {
        runShellCommand("cmd wearable_sensing provide-data-stream %d", USER_ID);
    }

    private void provideData() {
        runShellCommand("cmd wearable_sensing provide-data %d %s %d",
                USER_ID, KEY_FOR_PROVIDE_DATA, VALUE_TO_SEND);
    }

    private void writeDataToStream() {
        runShellCommand("cmd wearable_sensing write-to-data-stream %s", VALUE_TO_WRITE);
    }

    private String readDataStream() throws Exception {
        ParcelFileDescriptor pipe = CtsWearableSensingService.getParcelFileDescriptor();
        InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pipe);
        byte[] bytes = new byte[VALUE_TO_WRITE.getBytes().length];
        is.read(bytes, 0, bytes.length);
        return new String(bytes);
    }

    private int getLastStatusCode() {
        return Integer.parseInt(runShellCommand(
                "cmd wearable_sensing get-last-status-code"));
    }

    private int getLastStatusCodeAmbientDetectionService() {
        return Integer.parseInt(runShellCommand(
                "cmd ambient_context get-last-status-code"));
    }

    private void setTestableAmbientContextDetectionService(String service) {
        runShellCommand("cmd ambient_context set-temporary-services %d %s %s %d",
                USER_ID, service, service, TEMPORARY_SERVICE_DURATION);
    }

    private void callQueryServiceStatus() {
        runShellCommand("cmd ambient_context query-service-status %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void callQueryWearableServiceStatus() {
        runShellCommand("cmd ambient_context query-wearable-service-status %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void callQueryMixedServiceStatus() {
        runShellCommand("cmd ambient_context query-mixed-service-status %d %s",
                USER_ID, FAKE_APP_PACKAGE);
    }

    private void clearTestableAmbientContextDetectionService() {
        runShellCommand("cmd ambient_context set-temporary-service %d", USER_ID);
    }
}
