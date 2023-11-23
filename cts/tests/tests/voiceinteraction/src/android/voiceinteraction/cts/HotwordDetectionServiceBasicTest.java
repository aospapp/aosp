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

package android.voiceinteraction.cts;

import static android.Manifest.permission.CAPTURE_AUDIO_HOTWORD;
import static android.Manifest.permission.MANAGE_HOTWORD_DETECTION;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.content.pm.PackageManager.FEATURE_MICROPHONE;
import static android.voiceinteraction.common.Utils.AUDIO_EGRESS_DETECTED_RESULT;
import static android.voiceinteraction.common.Utils.AUDIO_EGRESS_DETECTED_RESULT_WRONG_COPY_BUFFER_SIZE;
import static android.voiceinteraction.common.Utils.EXTRA_HOTWORD_DETECTION_SERVICE_CAN_READ_AUDIO_DATA_IS_NOT_ZERO;
import static android.voiceinteraction.cts.testcore.Helper.CTS_SERVICE_PACKAGE;
import static android.voiceinteraction.cts.testcore.Helper.MANAGE_VOICE_KEYPHRASES;
import static android.voiceinteraction.cts.testcore.Helper.WAIT_TIMEOUT_IN_MS;
import static android.voiceinteraction.cts.testcore.Helper.createKeyphraseRecognitionExtraList;
import static android.voiceinteraction.cts.testcore.Helper.waitForFutureDoneAndAssertSuccessful;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.AppOpsManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.pm.PackageManager;
import android.hardware.soundtrigger.SoundTrigger;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.soundtrigger.SoundTriggerInstrumentation.RecognitionSession;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.platform.test.annotations.AppModeFull;
import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.HotwordDetectionService;
import android.service.voice.HotwordDetectionServiceFailure;
import android.service.voice.HotwordDetector;
import android.service.voice.HotwordRejectedResult;
import android.service.voice.SandboxedDetectionInitializer;
import android.soundtrigger.cts.instrumentation.SoundTriggerInstrumentationObserver;
import android.util.Log;
import android.voiceinteraction.common.Utils;
import android.voiceinteraction.cts.services.BaseVoiceInteractionService;
import android.voiceinteraction.cts.services.CtsBasicVoiceInteractionService;
import android.voiceinteraction.cts.testcore.Helper;
import android.voiceinteraction.cts.testcore.VoiceInteractionServiceConnectedRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.RequiresDevice;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.CddTest;
import com.android.compatibility.common.util.DisableAnimationRule;
import com.android.compatibility.common.util.RequiredFeatureRule;
import com.android.compatibility.common.util.SystemUtil;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link HotwordDetectionService}.
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "No real use case for instant mode hotword detection service")
public class HotwordDetectionServiceBasicTest {

    private static final String TAG = "HotwordDetectionServiceTest";
    // The VoiceInteractionService used by this test
    private static final String SERVICE_COMPONENT =
            "android.voiceinteraction.cts.services.CtsBasicVoiceInteractionService";

    private final CountDownLatch mLatch = new CountDownLatch(1);
    private final AppOpsManager mAppOpsManager = sInstrumentation.getContext()
            .getSystemService(AppOpsManager.class);

    private final AppOpsManager.OnOpNotedListener mOnOpNotedListener =
            (op, uid, pkgName, attributionTag, flags, result) -> {
                Log.d(TAG, "Get OnOpNotedListener callback op = " + op);
                if (AppOpsManager.OPSTR_RECORD_AUDIO.equals(op)) {
                    mLatch.countDown();
                }
            };

    private CtsBasicVoiceInteractionService mService;

    private static String sWasIndicatorEnabled;
    private static String sDefaultScreenOffTimeoutValue;
    private static String sDefaultHotwordDetectionServiceRestartPeriodValue;
    private static final Instrumentation sInstrumentation =
            InstrumentationRegistry.getInstrumentation();
    private static final PackageManager sPkgMgr = sInstrumentation.getContext().getPackageManager();

    @Rule
    public VoiceInteractionServiceConnectedRule mConnectedRule =
            new VoiceInteractionServiceConnectedRule(
                    getInstrumentation().getTargetContext(), getTestVoiceInteractionService());

    @Rule
    public DisableAnimationRule mDisableAnimationRule = new DisableAnimationRule();

    @Rule
    public RequiredFeatureRule REQUIRES_MIC_RULE = new RequiredFeatureRule(FEATURE_MICROPHONE);

    private SoundTrigger.Keyphrase[] mKeyphraseArray;
    private final SoundTriggerInstrumentationObserver mInstrumentationObserver =
            new SoundTriggerInstrumentationObserver();

    @BeforeClass
    public static void enableIndicators() {
        sWasIndicatorEnabled = Helper.getIndicatorEnabledState();
        Helper.setIndicatorEnabledState(Boolean.toString(true));
    }

    @AfterClass
    public static void resetIndicators() {
        Helper.setIndicatorEnabledState(sWasIndicatorEnabled);
    }

    @BeforeClass
    public static void extendScreenOffTimeout() throws Exception {
        // Change screen off timeout to 20 minutes.
        sDefaultScreenOffTimeoutValue = SystemUtil.runShellCommand(
                "settings get system screen_off_timeout");
        SystemUtil.runShellCommand("settings put system screen_off_timeout 1200000");
    }

    @AfterClass
    public static void restoreScreenOffTimeout() {
        SystemUtil.runShellCommand(
                "settings put system screen_off_timeout " + sDefaultScreenOffTimeoutValue);
    }

    @BeforeClass
    public static void getHotwordDetectionServiceRestartPeriodValue() {
        sDefaultHotwordDetectionServiceRestartPeriodValue =
                Helper.getHotwordDetectionServiceRestartPeriod();
    }

    @AfterClass
    public static void resetHotwordDetectionServiceRestartPeriodValue() {
        Helper.setHotwordDetectionServiceRestartPeriod(
                sDefaultHotwordDetectionServiceRestartPeriodValue);
    }

    @Before
    public void setup() {
        // VoiceInteractionServiceConnectedRule handles the service connected,
        // the test should be able to get service
        mService = (CtsBasicVoiceInteractionService) BaseVoiceInteractionService.getService();
        // Check the test can get the service
        Objects.requireNonNull(mService);

        mKeyphraseArray = Helper.createKeyphraseArray(mService);

        // Hook up SoundTriggerInjection to inject/observe STHAL operations.
        // Requires MANAGE_SOUND_TRIGGER
        runWithShellPermissionIdentity(() ->
                mInstrumentationObserver.attachInstrumentation());

        // Wait the original HotwordDetectionService finish clean up to avoid flaky
        // This also waits for mic indicator disappear
        SystemClock.sleep(5_000);
    }

    @After
    public void tearDown() {
        // Clean up any unexpected HAL state
        try {
            mInstrumentationObserver.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        mService.resetState();
        mService = null;
    }

    public String getTestVoiceInteractionService() {
        Log.d(TAG, "getTestVoiceInteractionService()");
        return CTS_SERVICE_PACKAGE + "/" + SERVICE_COMPONENT;
    }

    @Test
    public void testHotwordDetectionService_getMaxCustomInitializationStatus()
            throws Throwable {
        // TODO: not use Deprecated method
        assertThat(HotwordDetectionService.getMaxCustomInitializationStatus()).isEqualTo(2);
    }

    @Test
    public void testHotwordDetectionService_createDspDetector_sendOverMaxResult_getException()
            throws Throwable {
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt(Utils.KEY_TEST_SCENARIO,
                Utils.EXTRA_HOTWORD_DETECTION_SERVICE_SEND_OVER_MAX_INIT_STATUS);

        try {
            // Create AlwaysOnHotwordDetector and wait result
            mService.createAlwaysOnHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false, persistableBundle);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // When the HotwordDetectionService sends the initialization status that overs the
            // getMaxCustomInitializationStatus, the HotwordDetectionService will get the
            // IllegalArgumentException. In order to test this case, we send the max custom
            // initialization status when the HotwordDetectionService gets the
            // IllegalArgumentException.
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    SandboxedDetectionInitializer.getMaxCustomInitializationStatus());
        } finally {
            AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
            if (alwaysOnHotwordDetector != null) {
                alwaysOnHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_createSoftwareDetector_sendOverMaxResult_getException()
            throws Throwable {
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt(Utils.KEY_TEST_SCENARIO,
                Utils.EXTRA_HOTWORD_DETECTION_SERVICE_SEND_OVER_MAX_INIT_STATUS);

        try {
            // Create SoftwareHotwordDetector and wait result
            mService.createSoftwareHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false, persistableBundle);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // When the HotwordDetectionService sends the initialization status that overs the
            // getMaxCustomInitializationStatus, the HotwordDetectionService will get the
            // IllegalArgumentException. In order to test this case, we send the max custom
            // initialization status when the HotwordDetectionService gets the
            // IllegalArgumentException.
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    SandboxedDetectionInitializer.getMaxCustomInitializationStatus());
        } finally {
            HotwordDetector softwareHotwordDetector = mService.getSoftwareHotwordDetector();
            if (softwareHotwordDetector != null) {
                softwareHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_createDspDetector_customResult_getCustomStatus()
            throws Throwable {
        final int customStatus = SandboxedDetectionInitializer.INITIALIZATION_STATUS_SUCCESS + 1;
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt(Utils.KEY_TEST_SCENARIO,
                Utils.EXTRA_HOTWORD_DETECTION_SERVICE_SEND_CUSTOM_INIT_STATUS);
        persistableBundle.putInt(Utils.KEY_INITIALIZATION_STATUS, customStatus);

        try {
            // Create AlwaysOnHotwordDetector and wait result
            mService.createAlwaysOnHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false, persistableBundle);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // verify callback result
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    customStatus);
        } finally {
            AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
            if (alwaysOnHotwordDetector != null) {
                alwaysOnHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_createSoftwareDetector_customResult_getCustomStatus()
            throws Throwable {
        final int customStatus = SandboxedDetectionInitializer.INITIALIZATION_STATUS_SUCCESS + 1;
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt(Utils.KEY_TEST_SCENARIO,
                Utils.EXTRA_HOTWORD_DETECTION_SERVICE_SEND_CUSTOM_INIT_STATUS);
        persistableBundle.putInt(Utils.KEY_INITIALIZATION_STATUS, customStatus);

        try {
            // Create SoftwareHotwordDetector and wait result
            mService.createSoftwareHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false, persistableBundle);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // verify callback result
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    customStatus);
        } finally {
            HotwordDetector softwareHotwordDetector = mService.getSoftwareHotwordDetector();
            if (softwareHotwordDetector != null) {
                softwareHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_validHotwordDetectionComponentName_triggerSuccess()
            throws Throwable {
        // Create alwaysOnHotwordDetector and wait result
        mService.createAlwaysOnHotwordDetector();

        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // verify callback result
        // TODO: not use Deprecated variable
        assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);

        // The AlwaysOnHotwordDetector should be created correctly
        AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
        Objects.requireNonNull(alwaysOnHotwordDetector);

        alwaysOnHotwordDetector.destroy();
    }

    @Test
    @CddTest(requirements = {"9.8/H-1-9"})
    public void testVoiceInteractionService_withoutManageHotwordDetectionPermission_triggerFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector and wait result
        mService.createAlwaysOnHotwordDetectorWithoutManageHotwordDetectionPermission();

        // Wait the result and verify expected result
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify SecurityException throws
        assertThat(mService.isCreateDetectorSecurityExceptionThrow()).isTrue();
    }

    @Test
    public void testVoiceInteractionService_holdBindHotwordDetectionPermission_triggerFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector and wait result
        mService.createAlwaysOnHotwordDetectorHoldBindHotwordDetectionPermission();

        // Wait the result and verify expected result
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify SecurityException throws
        assertThat(mService.isCreateDetectorSecurityExceptionThrow()).isTrue();
    }

    @Test
    @CddTest(requirements = {"9.8/H-1-9"})
    public void testVoiceInteractionService_createSoftwareWithoutPermission_triggerFailure()
            throws Throwable {
        // Create SoftwareHotwordDetector and wait result
        mService.createSoftwareHotwordDetectorWithoutManageHotwordDetectionPermission();

        // Wait the result and verify expected result
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify SecurityException throws
        assertThat(mService.isCreateDetectorSecurityExceptionThrow()).isTrue();
    }

    @Test
    public void testVoiceInteractionService_createSoftwareBindHotwordDetectionPermission_Failure()
            throws Throwable {
        // Create SoftwareHotwordDetector and wait result
        mService.createSoftwareHotwordDetectorHoldBindHotwordDetectionPermission();

        // Wait the result and verify expected result
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify SecurityException throws
        assertThat(mService.isCreateDetectorSecurityExceptionThrow()).isTrue();
    }

    @Test
    public void testVoiceInteractionService_disallowCreateAlwaysOnHotwordDetectorTwice()
            throws Throwable {
        final boolean enableMultipleHotwordDetectors = Helper.isEnableMultipleDetectors();
        assumeTrue("Not support multiple hotword detectors", enableMultipleHotwordDetectors);

        // Create first AlwaysOnHotwordDetector, it's fine.
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);

        // Create second AlwaysOnHotwordDetector, it will get the IllegalStateException due to
        // the previous AlwaysOnHotwordDetector is not destroy.
        mService.createAlwaysOnHotwordDetector();
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify IllegalStateException throws
        assertThat(mService.isCreateDetectorIllegalStateExceptionThrow()).isTrue();

        alwaysOnHotwordDetector.destroy();
    }

    @Test
    public void testVoiceInteractionService_disallowCreateSoftwareHotwordDetectorTwice()
            throws Throwable {
        final boolean enableMultipleHotwordDetectors = Helper.isEnableMultipleDetectors();
        assumeTrue("Not support multiple hotword detectors", enableMultipleHotwordDetectors);

        // Create first SoftwareHotwordDetector and wait the HotwordDetectionService ready
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        // Create second SoftwareHotwordDetector, it will get the IllegalStateException due to
        // the previous SoftwareHotwordDetector is not destroy.
        mService.createSoftwareHotwordDetector();
        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // Verify IllegalStateException throws
        assertThat(mService.isCreateDetectorIllegalStateExceptionThrow()).isTrue();

        softwareHotwordDetector.destroy();
    }

    @Test
    public void testHotwordDetectionService_processDied_triggerOnError() throws Throwable {
        // Create first AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);

        mService.initOnErrorLatch();

        // Use AlwaysOnHotwordDetector to test process died of HotwordDetectionService
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Helper.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_CRASH);
            alwaysOnHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        mService.waitOnErrorCalled();

        // ActivityManager will schedule a timer to restart the HotwordDetectionService due to
        // we crash the service in this test case. It may impact the other test cases when
        // ActivityManager restarts the HotwordDetectionService again. Add the sleep time to wait
        // ActivityManager to restart the HotwordDetectionService, so that the service can be
        // destroyed after finishing this test case.
        Thread.sleep(5000);

        alwaysOnHotwordDetector.destroy();
    }

    @Test
    public void testHotwordDetectionService_processDied_triggerOnFailure() throws Throwable {
        // Create alwaysOnHotwordDetector with onFailure callback
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ true);

        try {
            mService.initOnFailureLatch();

            // Use AlwaysOnHotwordDetector to test process died of HotwordDetectionService
            runWithShellPermissionIdentity(() -> {
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Helper.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_CRASH);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_BINDING_DIED);

            // ActivityManager will schedule a timer to restart the HotwordDetectionService due to
            // we crash the service in this test case. It may impact the other test cases when
            // ActivityManager restarts the HotwordDetectionService again. Add the sleep time to
            // wait
            // ActivityManager to restart the HotwordDetectionService, so that the service can be
            // destroyed after finishing this test case.
            Thread.sleep(5000);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_softwareDetector_processDied_triggerOnFailure()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector =
                createSoftwareHotwordDetector(/* useOnFailure= */ true);
        try {
            // Use SoftwareHotwordDetector to test process died of HotwordDetectionService
            mService.initOnFailureLatch();
            runWithShellPermissionIdentity(() -> {
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Helper.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_CRASH);
                softwareHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);
            // wait OnFailure() called and verify the result
            mService.waitOnFailureCalled();
            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_BINDING_DIED);

            // ActivityManager will schedule a timer to restart the HotwordDetectionService due to
            // we crash the service in this test case. It may impact the other test cases when
            // ActivityManager restarts the HotwordDetectionService again. Add the sleep time to
            // wait ActivityManager to restart the HotwordDetectionService, so that the service
            // can be destroyed after finishing this test case.
            Thread.sleep(5000);
        } finally {
            // destroy detector
            softwareHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectFromDspTimeout_triggerOnFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector with onFailure callback
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ true);

        try {
            // Update HotwordDetectionService options to delay detection, to cause a timeout
            runWithShellPermissionIdentity(() -> {
                PersistableBundle options = Helper.createFakePersistableBundleData();
                options.putInt(Utils.KEY_DETECTION_DELAY_MS, 5000);
                alwaysOnHotwordDetector.updateState(options,
                        Helper.createFakeSharedMemoryData());
            });

            adoptShellPermissionIdentityForHotword();

            mService.initOnFailureLatch();

            alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                    /* status= */ 0, /* soundModelHandle= */ 100,
                    /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                    /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                    /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                    Helper.createFakeAudioFormat(), new byte[1024],
                    Helper.createFakeKeyphraseRecognitionExtraList());

            // wait onFailure() called and verify the result
            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_DETECT_TIMEOUT);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();

            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectFromDspSecurityException_onFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector with onFailure callback
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ true);

        try {
            mService.initOnFailureLatch();

            runWithShellPermissionIdentity(() -> {
                alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                        /* status= */ 0, /* soundModelHandle= */ 100,
                        /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                        /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                        /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                        Helper.createFakeAudioFormat(), new byte[1024],
                        Helper.createFakeKeyphraseRecognitionExtraList());
            });

            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectFromExternalSourceSecurityException_onFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector with onFailure callback
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ true);

        try {
            mService.initOnFailureLatch();

            runWithShellPermissionIdentity(() -> {
                alwaysOnHotwordDetector.startRecognition(Helper.createFakeAudioStream(),
                        Helper.createFakeAudioFormat(), Helper.createFakePersistableBundleData());
            });

            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_software_externalSourceSecurityException_onFailure()
            throws Throwable {
        // Create SoftwareHotwordDetector with onFailure callback
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                true);

        try {
            mService.initOnFailureLatch();

            runWithShellPermissionIdentity(() -> {
                softwareHotwordDetector.startRecognition(Helper.createFakeAudioStream(),
                        Helper.createFakeAudioFormat(), Helper.createFakePersistableBundleData());
            });

            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION);
        } finally {
            // Destroy detector
            softwareHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectFromMicSecurityException_onFailure()
            throws Throwable {
        // Create SoftwareHotwordDetector with onFailure callback
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                true);

        try {
            mService.initOnFailureLatch();

            runWithShellPermissionIdentity(() -> {
                softwareHotwordDetector.startRecognition();
            });

            // wait onFailure() called and verify the result
            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_ON_DETECTED_SECURITY_EXCEPTION);
        } finally {
            // destroy detector
            softwareHotwordDetector.destroy();
        }
    }

    @Test
    @Ignore("b/272527340")
    public void testHotwordDetectionService_onDetectFromExternalSourceAudioBroken_onFailure()
            throws Throwable {
        // Create alwaysOnHotwordDetector with onFailure callback
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ true);

        try {
            adoptShellPermissionIdentityForHotword();

            // Create the ParcelFileDescriptor to read/write audio stream
            final ParcelFileDescriptor[] parcelFileDescriptors = ParcelFileDescriptor.createPipe();

            // After the client calls the startRecognition method, the system side will start to
            // read the audio stream. When no data is read, the system side will normally end the
            // process. If the client closes the audio stream when the system is still reading the
            // audio stream, the system will get the IOException and use the onFailure callback to
            // inform the client.
            // In order to simulate the IOException case, it would be better to write 5 * 10 * 1024
            // bytes data first before calling startRecognition to avoid the timing issue that no
            // data is read from the system and make sure to close the audio stream during system
            // is still reading the audio stream.
            final CountDownLatch writeAudioStreamLatch = new CountDownLatch(5);

            Executors.newCachedThreadPool().execute(() -> {
                try (OutputStream fos = new ParcelFileDescriptor.AutoCloseOutputStream(
                        parcelFileDescriptors[1])) {
                    byte[] largeData = new byte[10 * 1024];
                    int count = 1000;
                    while (count-- > 0) {
                        Random random = new Random();
                        random.nextBytes(largeData);
                        fos.write(largeData, 0, 10 * 1024);
                        writeAudioStreamLatch.countDown();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to pipe audio data : ", e);
                }
            });

            writeAudioStreamLatch.await(WAIT_TIMEOUT_IN_MS, TimeUnit.MILLISECONDS);

            mService.initOnFailureLatch();

            alwaysOnHotwordDetector.startRecognition(parcelFileDescriptors[0],
                    Helper.createFakeAudioFormat(), Helper.createFakePersistableBundleData());

            // Close the parcelFileDescriptors to cause the IOException when reading audio
            // stream in the system side.
            parcelFileDescriptors[0].close();
            parcelFileDescriptors[1].close();

            // wait onFailure() called and verify the result
            mService.waitOnFailureCalled();

            verifyHotwordDetectionServiceFailure(mService.getHotwordDetectionServiceFailure(),
                    HotwordDetectionServiceFailure.ERROR_CODE_COPY_AUDIO_DATA_FAILURE);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();

            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @RequiresDevice
    public void testHotwordDetectionService_createDetectorTwiceQuickly_triggerSuccess()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);
        // destroy software hotword detector
        softwareHotwordDetector.destroy();

        // Create AlwaysOnHotwordDetector
        startWatchingNoted();
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();
        try {
            runWithShellPermissionIdentity(() -> {
                // Update state with test scenario HotwordDetectionService can read audio and
                // check the data is not zero
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        EXTRA_HOTWORD_DETECTION_SERVICE_CAN_READ_AUDIO_DATA_IS_NOT_ZERO);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            adoptShellPermissionIdentityForHotword();

            verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(alwaysOnHotwordDetector);

            // Verify RECORD_AUDIO noted
            verifyRecordAudioNote(/* shouldNote= */ true);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    @CddTest(requirements = {"9.8/H-1-15"})
    public void testHotwordDetectionServiceDspWithAudioEgress() throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            alwaysOnHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();

            // start recognition and trigger recognition event via recognition session
            alwaysOnHotwordDetector.startRecognition(0, new byte[]{1, 2, 3, 4, 5});
            RecognitionSession recognitionSession = waitForFutureDoneAndAssertSuccessful(
                    mInstrumentationObserver.getOnRecognitionStartedFuture());
            assertThat(recognitionSession).isNotNull();

            recognitionSession.triggerRecognitionEvent(new byte[1024],
                    createKeyphraseRecognitionExtraList());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyAudioEgressDetectedResult(detectResult, AUDIO_EGRESS_DETECTED_RESULT);

        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            disableTestModel();
        }
    }

    @Test
    @CddTest(requirements = {"9.8/H-1-15"})
    public void testHotwordDetectionService_softwareDetectorWithAudioEgress() throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            softwareHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition();

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyDetectedResult(detectResult, AUDIO_EGRESS_DETECTED_RESULT);
        } finally {
            softwareHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @CddTest(requirements = {"9.8/H-1-15"})
    public void testHotwordDetectionService_onDetectFromExternalSourceWithAudioEgress()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            alwaysOnHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            ParcelFileDescriptor audioStream = Helper.createFakeAudioStream();
            mService.initDetectRejectLatch();
            alwaysOnHotwordDetector.startRecognition(audioStream,
                    Helper.createFakeAudioFormat(),
                    Helper.createFakePersistableBundleData());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyDetectedResult(detectResult, AUDIO_EGRESS_DETECTED_RESULT);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionServiceDspWithAudioEgressWrongCopyBufferSize()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            persistableBundle.putBoolean(Utils.KEY_AUDIO_EGRESS_USE_ILLEGAL_COPY_BUFFER_SIZE, true);
            alwaysOnHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();

            // start recognition and trigger recognition event via recognition session
            alwaysOnHotwordDetector.startRecognition(0, new byte[]{1, 2, 3, 4, 5});
            RecognitionSession recognitionSession = waitForFutureDoneAndAssertSuccessful(
                    mInstrumentationObserver.getOnRecognitionStartedFuture());
            assertThat(recognitionSession).isNotNull();

            recognitionSession.triggerRecognitionEvent(new byte[1024],
                    createKeyphraseRecognitionExtraList());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyAudioEgressDetectedResult(detectResult,
                    AUDIO_EGRESS_DETECTED_RESULT_WRONG_COPY_BUFFER_SIZE);

        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            disableTestModel();
        }
    }

    @Test
    public void testHotwordDetectionService_softwareDetectorWithAudioEgressWrongCopyBufferSize()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            persistableBundle.putBoolean(Utils.KEY_AUDIO_EGRESS_USE_ILLEGAL_COPY_BUFFER_SIZE, true);
            softwareHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition();

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyAudioEgressDetectedResult(detectResult,
                    AUDIO_EGRESS_DETECTED_RESULT_WRONG_COPY_BUFFER_SIZE);
        } finally {
            softwareHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionService_externalSourceWithAudioEgressWrongCopyBufferSize()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);

        // Update HotwordDetectionService options to enable Audio egress
        runWithShellPermissionIdentity(() -> {
            PersistableBundle persistableBundle = new PersistableBundle();
            persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                    Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ENABLE_AUDIO_EGRESS);
            persistableBundle.putBoolean(Utils.KEY_AUDIO_EGRESS_USE_ILLEGAL_COPY_BUFFER_SIZE, true);
            alwaysOnHotwordDetector.updateState(
                    persistableBundle,
                    Helper.createFakeSharedMemoryData());
        }, MANAGE_HOTWORD_DETECTION);

        try {
            adoptShellPermissionIdentityForHotword();

            ParcelFileDescriptor audioStream = Helper.createFakeAudioStream();
            mService.initDetectRejectLatch();
            alwaysOnHotwordDetector.startRecognition(audioStream,
                    Helper.createFakeAudioFormat(),
                    Helper.createFakePersistableBundleData());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyAudioEgressDetectedResult(detectResult,
                    AUDIO_EGRESS_DETECTED_RESULT_WRONG_COPY_BUFFER_SIZE);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @CddTest(requirement = "9.8/H-1-2,H-1-8,H-1-14")
    public void testHotwordDetectionService_onDetectFromDsp_success() throws Throwable {
        startWatchingNoted();
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();
        try {
            adoptShellPermissionIdentityForHotword();

            verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(alwaysOnHotwordDetector);

            // Verify RECORD_AUDIO noted
            verifyRecordAudioNote(/* shouldNote= */ true);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectFromDsp_rejection() throws Throwable {
        startWatchingNoted();
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        try {
            mService.initDetectRejectLatch();
            runWithShellPermissionIdentity(() -> {
                // pass null data parameter
                alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                        /* status= */ 0, /* soundModelHandle= */ 100,
                        /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                        /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                        /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                        Helper.createFakeAudioFormat(), null,
                        Helper.createFakeKeyphraseRecognitionExtraList());
            });
            // wait onRejected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            HotwordRejectedResult rejectedResult =
                    mService.getHotwordServiceOnRejectedResult();

            assertThat(rejectedResult).isEqualTo(Helper.REJECTED_RESULT);

            // Verify RECORD_AUDIO does not note
            verifyRecordAudioNote(/* shouldNote= */ false);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            stopWatchingNoted();
        }
    }

    @Test
    @CddTest(requirement = "9.8/H-1-3")
    public void testHotwordDetectionService_onDetectFromDsp_timeout() throws Throwable {
        startWatchingNoted();
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        // Update HotwordDetectionService options to delay detection, to cause a timeout
        runWithShellPermissionIdentity(() -> {
            PersistableBundle options = Helper.createFakePersistableBundleData();
            options.putInt(Utils.KEY_DETECTION_DELAY_MS, 5000);
            alwaysOnHotwordDetector.updateState(options,
                    Helper.createFakeSharedMemoryData());
        });
        try {
            adoptShellPermissionIdentityForHotword();

            mService.initOnErrorLatch();
            alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                    /* status= */ 0, /* soundModelHandle= */ 100,
                    /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                    /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                    /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                    Helper.createFakeAudioFormat(), new byte[1024],
                    Helper.createFakeKeyphraseRecognitionExtraList());

            // wait onError() called and verify the result
            mService.waitOnErrorCalled();

            // Verify RECORD_AUDIO does not note
            verifyRecordAudioNote(/* shouldNote= */ false);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    public void testHotwordDetectionService_destroyDspDetector_activeDetectorRemoved()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        // destroy detector
        alwaysOnHotwordDetector.destroy();
        try {
            adoptShellPermissionIdentityForHotword();

            assertThrows(IllegalStateException.class, () -> {
                // Can no longer use the detector because it is in an invalid state
                alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                        /* status= */ 0, /* soundModelHandle= */ 100,
                        /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                        /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                        /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                        Helper.createFakeAudioFormat(), new byte[1024],
                        Helper.createFakeKeyphraseRecognitionExtraList());
            });
        } finally {
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @CddTest(requirement = "9.8/H-1-2,H-1-8,H-1-14")
    public void testHotwordDetectionService_onDetectFromExternalSource_success() throws Throwable {
        startWatchingNoted();
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        try {
            adoptShellPermissionIdentityForHotword();

            ParcelFileDescriptor audioStream = Helper.createFakeAudioStream();
            mService.initDetectRejectLatch();
            alwaysOnHotwordDetector.startRecognition(audioStream,
                    Helper.createFakeAudioFormat(),
                    Helper.createFakePersistableBundleData());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT);

            // Verify RECORD_AUDIO noted
            verifyRecordAudioNote(/* shouldNote= */ true);
        } finally {
            // destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    public void testHotwordDetectionService_dspDetector_onDetectFromExternalSource_rejected()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        try {
            adoptShellPermissionIdentityForHotword();

            PersistableBundle options = Helper.createFakePersistableBundleData();
            options.putBoolean(Utils.KEY_DETECTION_REJECTED, true);

            mService.initDetectRejectLatch();
            alwaysOnHotwordDetector.startRecognition(Helper.createFakeAudioStream(),
                    Helper.createFakeAudioFormat(), options);

            // Wait onRejected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            HotwordRejectedResult rejectedResult =
                    mService.getHotwordServiceOnRejectedResult();

            assertThat(rejectedResult).isEqualTo(Helper.REJECTED_RESULT);
        } finally {
            // Destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionService_softwareDetector_onDetectFromExternalSource_rejected()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);
        try {
            adoptShellPermissionIdentityForHotword();

            PersistableBundle options = Helper.createFakePersistableBundleData();
            options.putBoolean(Utils.KEY_DETECTION_REJECTED, true);

            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition(Helper.createFakeAudioStream(),
                    Helper.createFakeAudioFormat(), options);

            // Wait onRejected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            HotwordRejectedResult rejectedResult =
                    mService.getHotwordServiceOnRejectedResult();

            assertThat(rejectedResult).isEqualTo(Helper.REJECTED_RESULT);
        } finally {
            // Destroy detector
            softwareHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @CddTest(requirement = "9.8/H-1-2,H-1-8,H-1-14")
    public void testHotwordDetectionService_onDetectFromMic_success() throws Throwable {
        startWatchingNoted();
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);
        try {
            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition();

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT);

            // Verify RECORD_AUDIO noted
            verifyRecordAudioNote(/* shouldNote= */ true);
        } finally {
            softwareHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    public void testHotwordDetectionService_destroySoftwareDetector_activeDetectorRemoved()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        // Destroy SoftwareHotwordDetector
        softwareHotwordDetector.destroy();

        try {
            adoptShellPermissionIdentityForHotword();
            // Can no longer use the detector because it is in an invalid state
            assertThrows(IllegalStateException.class, softwareHotwordDetector::startRecognition);
        } finally {
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionService_onStopDetection() throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);
        try {
            adoptShellPermissionIdentityForHotword();

            // The HotwordDetectionService can't report any result after recognition is stopped. So
            // restart it after stopping; then the service can report a special result.
            softwareHotwordDetector.startRecognition();
            softwareHotwordDetector.stopRecognition();
            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition();

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();
            Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT_AFTER_STOP_DETECTION);
        } finally {
            softwareHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    @RequiresDevice
    public void testHotwordDetectionService_concurrentCapture() throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        try {
            SystemUtil.runWithShellPermissionIdentity(() -> {
                AudioRecord record =
                        new AudioRecord.Builder()
                                .setAudioAttributes(
                                        new AudioAttributes.Builder()
                                                .setInternalCapturePreset(
                                                        MediaRecorder.AudioSource.MIC).build())
                                .setAudioFormat(
                                        new AudioFormat.Builder()
                                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                .build())
                                .setBufferSizeInBytes(10240) // something large enough to not fail
                                .build();
                assertThat(record.getState()).isEqualTo(AudioRecord.STATE_INITIALIZED);

                try {
                    record.startRecording();

                    // Update state with test scenario HotwordDetectionService can read audio
                    // and check the data is not zero
                    PersistableBundle persistableBundle = new PersistableBundle();
                    persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                            EXTRA_HOTWORD_DETECTION_SERVICE_CAN_READ_AUDIO_DATA_IS_NOT_ZERO);
                    softwareHotwordDetector.updateState(
                            persistableBundle,
                            Helper.createFakeSharedMemoryData());

                    mService.initDetectRejectLatch();
                    softwareHotwordDetector.startRecognition();
                    mService.waitOnDetectOrRejectCalled();
                    AlwaysOnHotwordDetector.EventPayload detectResult =
                            mService.getHotwordServiceOnDetectedResult();
                    Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT);
                    // TODO: Test that it still works after restarting the process or killing audio
                    //  server.
                } finally {
                    record.release();
                }
            });
        } finally {
            softwareHotwordDetector.destroy();
        }
    }

    @Test
    @RequiresDevice
    public void testMultipleDetectors_onDetectFromDspAndMic_success() throws Throwable {
        assumeTrue("Not support multiple hotword detectors",
                Helper.isEnableMultipleDetectors());

        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();

        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector = createSoftwareHotwordDetector(/* useOnFailure= */
                false);

        try {
            runWithShellPermissionIdentity(() -> {
                // Update state with test scenario HotwordDetectionService can read audio and
                // check the data is not zero
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        EXTRA_HOTWORD_DETECTION_SERVICE_CAN_READ_AUDIO_DATA_IS_NOT_ZERO);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            adoptShellPermissionIdentityForHotword();
            // Test AlwaysOnHotwordDetector to be able to detect well
            verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(
                    alwaysOnHotwordDetector, /* shouldDisableTestModel= */ false);

            // Test SoftwareHotwordDetector to be able to detect well
            verifySoftwareDetectorDetectSuccess(softwareHotwordDetector);
        } finally {
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            // Destroy the always on detector
            alwaysOnHotwordDetector.destroy();

            // Destroy the software detector
            softwareHotwordDetector.destroy();
            disableTestModel();
        }
    }

    @Test
    public void testHotwordDetectionService_onHotwordDetectionServiceRestarted() throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);

        mService.initOnHotwordDetectionServiceRestartedLatch();
        // force re-start by shell command
        runShellCommand("cmd voiceinteraction restart-detection");

        // wait onHotwordDetectionServiceRestarted() called
        mService.waitOnHotwordDetectionServiceRestartedCalled();

        // Destroy the always on detector
        alwaysOnHotwordDetector.destroy();
    }

    @Test
    public void testHotwordDetectionService_dspDetector_serviceScheduleRestarted()
            throws Throwable {
        // Change the period of restarting hotword detection
        final String restartPeriod = Helper.getHotwordDetectionServiceRestartPeriod();
        Helper.setHotwordDetectionServiceRestartPeriod("8");

        try {
            mService.initOnHotwordDetectionServiceRestartedLatch();

            // Create AlwaysOnHotwordDetector and wait result
            mService.createAlwaysOnHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // Verify callback result
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);

            // Wait onHotwordDetectionServiceRestarted() called
            mService.waitOnHotwordDetectionServiceRestartedCalled();
        } finally {
            Helper.setHotwordDetectionServiceRestartPeriod(restartPeriod);
            AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
            if (alwaysOnHotwordDetector != null) {
                alwaysOnHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_softwareDetector_serviceScheduleRestarted()
            throws Throwable {
        // Change the period of restarting hotword detection
        final String restartPeriod = Helper.getHotwordDetectionServiceRestartPeriod();
        Helper.setHotwordDetectionServiceRestartPeriod("8");

        try {
            mService.initOnHotwordDetectionServiceRestartedLatch();

            // Create AlwaysOnHotwordDetector and wait result
            mService.createSoftwareHotwordDetector(/* useExecutor= */ false, /* runOnMainThread= */
                    false);

            // Wait the result and verify expected result
            mService.waitSandboxedDetectionServiceInitializedCalledOrException();

            // Verify callback result
            assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                    HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);

            // Wait onHotwordDetectionServiceRestarted() called
            mService.waitOnHotwordDetectionServiceRestartedCalled();
        } finally {
            Helper.setHotwordDetectionServiceRestartPeriod(restartPeriod);
            HotwordDetector softwareHotwordDetector = mService.getSoftwareHotwordDetector();
            if (softwareHotwordDetector != null) {
                softwareHotwordDetector.destroy();
            }
        }
    }

    @Test
    public void testHotwordDetectionService_onDetectedTwice_clientOnlyOneOnDetected()
            throws Throwable {
        // Create SoftwareHotwordDetector
        HotwordDetector softwareHotwordDetector =
                createSoftwareHotwordDetector(/*useOnFailure=*/ false);
        try {
            runWithShellPermissionIdentity(() -> {
                // Update state with test scenario unexpected onDetect callback
                // HDS will call back onDetected() twice
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_UNEXPECTED_CALLBACK);
                softwareHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            adoptShellPermissionIdentityForHotword();

            mService.initDetectRejectLatch();
            softwareHotwordDetector.startRecognition();

            // wait onDetected() called and only once (even HDS callback onDetected() many times,
            // only one onDetected() on VIS will be called)
            mService.waitOnDetectOrRejectCalled();
            // Wait for a while to make sure no 2nd onDetected() will be called
            Thread.sleep(500);
            assertThat(mService.getSoftwareOnDetectedCount()).isEqualTo(1);
        } finally {
            softwareHotwordDetector.destroy();
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testHotwordDetectionService_dspDetector_onDetectedTwice_clientOnlyOneOnDetected()
            throws Throwable {
        startWatchingNoted();
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();
        try {
            runWithShellPermissionIdentity(() -> {
                // Update state with test scenario unexpected onDetect callback
                // HDS will call back onDetected() twice
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_UNEXPECTED_CALLBACK);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            adoptShellPermissionIdentityForHotword();

            verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(alwaysOnHotwordDetector);

            // Verify RECORD_AUDIO noted
            verifyRecordAudioNote(/* shouldNote= */ true);

            // Wait for a while to make sure no 2nd onDetected() will be called
            Thread.sleep(500);
            assertThat(mService.getDspOnDetectedCount()).isEqualTo(1);
        } finally {
            // Destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            stopWatchingNoted();
        }
    }

    @Test
    public void testHotwordDetectionService_dspDetector_onRejectedTwice_clientOnlyOneOnRejected()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetector(/* useOnFailure= */ false);
        try {
            runWithShellPermissionIdentity(() -> {
                // Update state with test scenario unexpected onRejected callback
                // HDS will call back onRejected() twice
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Utils.EXTRA_HOTWORD_DETECTION_SERVICE_ON_UPDATE_STATE_UNEXPECTED_CALLBACK);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            mService.initDetectRejectLatch();
            runWithShellPermissionIdentity(() -> {
                // Pass null data parameter
                alwaysOnHotwordDetector.triggerHardwareRecognitionEventForTest(
                        /* status= */ 0, /* soundModelHandle= */ 100,
                        /* halEventReceivedMillis */ 12345, /* captureAvailable= */ true,
                        /* captureSession= */ 101, /* captureDelayMs= */ 1000,
                        /* capturePreambleMs= */ 1001, /* triggerInData= */ true,
                        Helper.createFakeAudioFormat(), null,
                        Helper.createFakeKeyphraseRecognitionExtraList());
            });
            // Wait onRejected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            HotwordRejectedResult rejectedResult =
                    mService.getHotwordServiceOnRejectedResult();

            assertThat(rejectedResult).isEqualTo(Helper.REJECTED_RESULT);

            // Wait for a while to make sure no 2nd onDetected() will be called
            Thread.sleep(500);
            assertThat(mService.getDspOnRejectedCount()).isEqualTo(1);
        } finally {
            // Destroy detector
            alwaysOnHotwordDetector.destroy();
        }
    }

    @Test
    public void testHotwordDetectionService_dspDetector_duringOnDetect_serviceRestart()
            throws Throwable {
        // Create AlwaysOnHotwordDetector
        AlwaysOnHotwordDetector alwaysOnHotwordDetector =
                createAlwaysOnHotwordDetectorWithSoundTriggerInjection();
        try {
            runWithShellPermissionIdentity(() -> {
                // Inform the HotwordDetectionService to ignore the onDetect
                PersistableBundle persistableBundle = new PersistableBundle();
                persistableBundle.putInt(Helper.KEY_TEST_SCENARIO,
                        Utils.EXTRA_HOTWORD_DETECTION_SERVICE_NO_NEED_ACTION_DURING_DETECTION);
                alwaysOnHotwordDetector.updateState(
                        persistableBundle,
                        Helper.createFakeSharedMemoryData());
            }, MANAGE_HOTWORD_DETECTION);

            adoptShellPermissionIdentityForHotword();

            alwaysOnHotwordDetector.startRecognition(0, new byte[]{1, 2, 3, 4, 5});
            RecognitionSession recognitionSession = waitForFutureDoneAndAssertSuccessful(
                    mInstrumentationObserver.getOnRecognitionStartedFuture());
            assertThat(recognitionSession).isNotNull();

            // Verify we received model load, recognition start
            recognitionSession.triggerRecognitionEvent(new byte[1024],
                    createKeyphraseRecognitionExtraList());

            // Wait for a while to make sure onDetect() will be called in the
            // MainHotwordDetectionService
            Thread.sleep(500);

            mService.initDetectRejectLatch();

            // Force re-start by shell command
            runShellCommand("cmd voiceinteraction restart-detection");

            // Wait onRejected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            HotwordRejectedResult rejectedResult =
                    mService.getHotwordServiceOnRejectedResult();

            assertThat(rejectedResult).isNotNull();
        } finally {
            // Destroy detector
            alwaysOnHotwordDetector.destroy();
            // Drop identity adopted.
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
            disableTestModel();
        }
    }

    /**
     * Before using this method, please call {@link #adoptShellPermissionIdentityForHotword()} to
     * grant permissions. And please use
     * {@link #createAlwaysOnHotwordDetectorWithSoundTriggerInjection()} to create
     * AlwaysOnHotwordDetector. This method will disable test model after verifying the result.
     * If you want to disable the test model manually, please use
     * {@link #verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(
     * AlwaysOnHotwordDetector, boolean)}
     */
    private void verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(
            AlwaysOnHotwordDetector alwaysOnHotwordDetector) throws Throwable {
        verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(
                alwaysOnHotwordDetector, /* shouldDisableTestModel= */ true);
    }

    /**
     * Before using this method, please call {@link #adoptShellPermissionIdentityForHotword()} to
     * grant permissions. And please use
     * {@link #createAlwaysOnHotwordDetectorWithSoundTriggerInjection()} to create
     * AlwaysOnHotwordDetector. If {@code shouldDisableTestModel} is true, it will disable the test
     * model. Otherwise, it doesn't disable the test model. Please call {@link #disableTestModel}
     * manually.
     */
    private void verifyOnDetectFromDspWithSoundTriggerInjectionSuccess(
            AlwaysOnHotwordDetector alwaysOnHotwordDetector,
            boolean shouldDisableTestModel) throws Throwable {
        try {
            alwaysOnHotwordDetector.startRecognition(0, new byte[]{1, 2, 3, 4, 5});
            RecognitionSession recognitionSession = waitForFutureDoneAndAssertSuccessful(
                    mInstrumentationObserver.getOnRecognitionStartedFuture());
            assertThat(recognitionSession).isNotNull();

            mService.initDetectRejectLatch();
            recognitionSession.triggerRecognitionEvent(new byte[1024],
                    createKeyphraseRecognitionExtraList());

            // wait onDetected() called and verify the result
            mService.waitOnDetectOrRejectCalled();
            AlwaysOnHotwordDetector.EventPayload detectResult =
                    mService.getHotwordServiceOnDetectedResult();

            Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT);
        } finally {
            if (shouldDisableTestModel) {
                disableTestModel();
            }
        }
    }

    private void disableTestModel() {
        runWithShellPermissionIdentity(() -> mService.disableOverrideRegisterModel(),
                MANAGE_VOICE_KEYPHRASES);
    }

    private void verifySoftwareDetectorDetectSuccess(HotwordDetector softwareHotwordDetector)
            throws Exception {
        mService.initDetectRejectLatch();
        softwareHotwordDetector.startRecognition();

        // wait onDetected() called and verify the result
        mService.waitOnDetectOrRejectCalled();
        AlwaysOnHotwordDetector.EventPayload detectResult =
                mService.getHotwordServiceOnDetectedResult();
        Helper.verifyDetectedResult(detectResult, Helper.DETECTED_RESULT);
    }

    private void verifyHotwordDetectionServiceFailure(
            HotwordDetectionServiceFailure hotwordDetectionServiceFailure, int errorCode)
            throws Throwable {
        assertThat(hotwordDetectionServiceFailure).isNotNull();
        assertThat(hotwordDetectionServiceFailure.getErrorCode()).isEqualTo(errorCode);
    }

    /**
     * Create software hotword detector and wait for ready
     */
    private HotwordDetector createSoftwareHotwordDetector(boolean useOnFailure) throws Throwable {
        // Create SoftwareHotwordDetector
        if (useOnFailure) {
            mService.createSoftwareHotwordDetectorWithOnFailureCallback(/* useExecutor= */
                    false, /* runOnMainThread= */ false);
        } else {
            mService.createSoftwareHotwordDetector();
        }

        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // verify callback result
        // TODO: not use Deprecated variable
        assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);
        HotwordDetector softwareHotwordDetector = mService.getSoftwareHotwordDetector();
        Objects.requireNonNull(softwareHotwordDetector);

        return softwareHotwordDetector;
    }

    /**
     * Create AlwaysOnHotwordDetector and wait for ready
     */
    private AlwaysOnHotwordDetector createAlwaysOnHotwordDetector(boolean useOnFailure)
            throws Throwable {
        // Create AlwaysOnHotwordDetector and wait ready.
        if (useOnFailure) {
            mService.createAlwaysOnHotwordDetectorWithOnFailureCallback(/* useExecutor= */
                    false, /* runOnMainThread= */ false);
        } else {
            mService.createAlwaysOnHotwordDetector();
        }

        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // verify callback result
        // TODO: not use Deprecated variable
        assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);
        AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
        Objects.requireNonNull(alwaysOnHotwordDetector);

        return alwaysOnHotwordDetector;
    }

    /**
     * Create AlwaysOnHotwordDetector with SoundTrigger injection. Please call
     * {@link #verifyOnDetectFromDspWithSoundTriggerInjectionSuccess} or {@link #disableTestModel()}
     * before finishing the test.
     */
    private AlwaysOnHotwordDetector createAlwaysOnHotwordDetectorWithSoundTriggerInjection()
            throws Throwable {
        final UUID uuid = new UUID(5, 7);
        final UUID vendorUuid = new UUID(7, 5);

        // Start override enrolled model with a custom model for test purposes.
        runWithShellPermissionIdentity(() -> mService.enableOverrideRegisterModel(
                new SoundTrigger.KeyphraseSoundModel(uuid, vendorUuid, /* data= */ null,
                        mKeyphraseArray)), MANAGE_VOICE_KEYPHRASES);

        // Call initAvailabilityChangeLatch for onAvailabilityChanged() callback called
        // following AlwaysOnHotwordDetector creation.
        mService.initAvailabilityChangeLatch();

        // Create AlwaysOnHotwordDetector and wait ready.
        mService.createAlwaysOnHotwordDetector();

        mService.waitSandboxedDetectionServiceInitializedCalledOrException();

        // verify initialization callback result
        // TODO: not use Deprecated variable
        assertThat(mService.getSandboxedDetectionServiceInitializedResult()).isEqualTo(
                HotwordDetectionService.INITIALIZATION_STATUS_SUCCESS);
        AlwaysOnHotwordDetector alwaysOnHotwordDetector = mService.getAlwaysOnHotwordDetector();
        Objects.requireNonNull(alwaysOnHotwordDetector);

        // verify have entered the ENROLLED state
        mService.waitAvailabilityChangedCalled();
        assertThat(mService.getHotwordDetectionServiceAvailabilityResult()).isEqualTo(
                AlwaysOnHotwordDetector.STATE_KEYPHRASE_ENROLLED);

        return alwaysOnHotwordDetector;
    }

    private void adoptShellPermissionIdentityForHotword() {
        // Drop any identity adopted earlier.
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.dropShellPermissionIdentity();
        // need to retain the identity until the callback is triggered
        uiAutomation.adoptShellPermissionIdentity(RECORD_AUDIO, CAPTURE_AUDIO_HOTWORD);
    }

    private void startWatchingNoted() {
        runWithShellPermissionIdentity(() -> {
            if (mAppOpsManager != null) {
                mAppOpsManager.startWatchingNoted(new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                        mOnOpNotedListener);
            }
        });
    }

    private void stopWatchingNoted() {
        runWithShellPermissionIdentity(() -> {
            if (mAppOpsManager != null) {
                mAppOpsManager.stopWatchingNoted(mOnOpNotedListener);
            }
        });
    }

    private void verifyRecordAudioNote(boolean shouldNote) throws Exception {
        if (sPkgMgr.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            // TODO: test TV indicator
        } else if (sPkgMgr.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            // TODO: test Auto indicator
        } else if (sPkgMgr.hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            // The privacy chips/indicators are not implemented on Wear
        } else {
            boolean isNoted = mLatch.await(Helper.CLEAR_CHIP_MS, TimeUnit.MILLISECONDS);
            assertThat(isNoted).isEqualTo(shouldNote);
        }
    }
}
