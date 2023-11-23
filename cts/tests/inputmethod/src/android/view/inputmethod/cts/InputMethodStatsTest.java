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

package android.view.inputmethod.cts;

import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
import static android.view.inputmethod.cts.util.InputMethodVisibilityVerifier.expectImeInvisible;
import static android.view.inputmethod.cts.util.InputMethodVisibilityVerifier.expectImeVisible;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.view.WindowInsets;
import android.view.WindowInsetsController.OnControllableInsetsChangedListener;
import android.view.inputmethod.cts.util.EndToEndImeTestBase;
import android.view.inputmethod.cts.util.MetricsRecorder;
import android.view.inputmethod.cts.util.TestActivity;
import android.view.inputmethod.cts.util.TestUtils;
import android.view.inputmethod.nano.ImeProtoEnums;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.PollingCheck;
import com.android.cts.mockime.ImeSettings;
import com.android.cts.mockime.MockImeSession;
import com.android.os.nano.AtomsProto;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test suite to ensure IME stats get tracked and logged correctly.
 */
@RunWith(AndroidJUnit4.class)
public class InputMethodStatsTest extends EndToEndImeTestBase {

    private static final String TAG = "InputMethodStatsTest";

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(20);

    private Instrumentation mInstrumentation;

    /** The test app package name from which atoms will be logged. */
    private String mPkgName;

    @Before
    public void setUp() throws Exception {
        MetricsRecorder.removeConfig();
        MetricsRecorder.clearReports();

        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mPkgName = mInstrumentation.getContext().getPackageName();
    }

    @After
    public void tearDown() throws Exception {
        MetricsRecorder.removeConfig();
        MetricsRecorder.clearReports();

        mInstrumentation = null;
        mPkgName = "";
    }

    private TestActivity createTestActivity(final int windowFlags) {
        return TestActivity.startSync(activity -> createLayout(windowFlags, activity));
    }

    private LinearLayout createLayout(final int windowFlags, final Activity activity) {
        final var layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        final var editText = new EditText(activity);
        editText.setText("Editable");
        layout.addView(editText);
        editText.requestFocus();
        activity.getWindow().setSoftInputMode(windowFlags);
        return layout;
    }

    /**
     * Waits for the given inset type to be controllable on the given activity's
     * {@link android.view.WindowInsetsController}.
     *
     * @implNote
     * This is used to avoid the case where {@link android.view.InsetsController#show(int)}
     * is called before IME insets control is available, starting a more complex flow which is
     * currently harder to track with the {@link com.android.server.inputmethod.ImeTrackerService}
     * system.
     *
     * TODO(b/263069667): Remove this method when the ImeInsetsSourceConsumer show flow is fixed.
     *
     * @param type the inset type waiting to be controllable.
     * @param activity the activity whose Window Insets Controller to wait on.
     */
    private void awaitControl(final int type, final Activity activity) {
        final var latch = new CountDownLatch(1);
        final OnControllableInsetsChangedListener listener = (controller, typeMask) -> {
            if ((typeMask & type) != 0) {
                latch.countDown();
            }
        };
        TestUtils.runOnMainSync(() -> activity.getWindow()
                .getDecorView()
                .getWindowInsetsController()
                .addOnControllableInsetsChangedListener(listener));

        try {
            if (!latch.await(TIMEOUT, TimeUnit.SECONDS)) {
                fail("IME insets controls not available");
            }
        } catch (InterruptedException e) {
            fail("Waiting for IMe insets controls to be available failed");
        } finally {
            TestUtils.runOnMainSync(() -> activity.getWindow()
                    .getDecorView()
                    .getWindowInsetsController()
                    .removeOnControllableInsetsChangedListener(listener));
        }
    }

    /**
     * Test the logging for a client show IME request.
     */
    @Test
    public void testClientShowImeRequestFinished() throws Throwable {
        // Create mockImeSession to decouple from real IMEs,
        // and enable calling expectImeVisible.
        try (var imeSession = MockImeSession.create(
                mInstrumentation.getContext(),
                mInstrumentation.getUiAutomation(),
                new ImeSettings.Builder())) {
            // Wait for any outstanding IME requests to finish, to not interfere with test.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Setup Failed: There should be no pending IME requests present when the "
                            + "test starts.");

            MetricsRecorder.uploadConfigForPushedAtomWithUid(mPkgName,
                    AtomsProto.Atom.IME_REQUEST_FINISHED_FIELD_NUMBER,
                    false /* useUidAttributionChain */);

            final var activity = createTestActivity(SOFT_INPUT_STATE_UNCHANGED);
            awaitControl(WindowInsets.Type.ime(), activity);
            expectImeInvisible(TIMEOUT);

            TestUtils.runOnMainSync(() -> activity.getWindow()
                    .getDecorView()
                    .getWindowInsetsController()
                    .show(WindowInsets.Type.ime()));

            expectImeVisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            final var data = MetricsRecorder.getEventMetricDataList();
            assertWithMessage("Number of atoms logged")
                    .that(data.size())
                    .isAtLeast(1);

            try {
                int successfulAtoms = 0;
                for (int i = 0; i < data.size(); i++) {
                    final var atom = data.get(i).atom;
                    assertThat(atom).isNotNull();

                    final var imeRequestFinished = atom.getImeRequestFinished();
                    assertThat(imeRequestFinished).isNotNull();

                    // Skip cancelled requests.
                    if (imeRequestFinished.status == ImeProtoEnums.STATUS_CANCEL) continue;

                    successfulAtoms++;

                    assertWithMessage("Ime Request type")
                            .that(imeRequestFinished.type)
                            .isEqualTo(ImeProtoEnums.TYPE_SHOW);
                    assertWithMessage("Ime Request status")
                            .that(imeRequestFinished.status)
                            .isEqualTo(ImeProtoEnums.STATUS_SUCCESS);
                    assertWithMessage("Ime Request origin")
                            .that(imeRequestFinished.origin)
                            .isEqualTo(ImeProtoEnums.ORIGIN_CLIENT_SHOW_SOFT_INPUT);
                }

                assertWithMessage("Number of successful atoms logged")
                        .that(successfulAtoms)
                        .isAtLeast(1);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "\natoms data:\n" + data, e);
            }
        }
    }

    /**
     * Test the logging for a client hide IME request.
     */
    @Test
    public void testClientHideImeRequestFinished() throws Exception {
        // Create mockImeSession to decouple from real IMEs,
        // and enable calling expectImeVisible and expectImeInvisible.
        try (var imeSession = MockImeSession.create(
                mInstrumentation.getContext(),
                mInstrumentation.getUiAutomation(),
                new ImeSettings.Builder())) {
            // Wait for any outstanding IME requests to finish, to not interfere with test.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Setup Failed: There should be no pending IME requests present when the "
                            + "test starts.");

            MetricsRecorder.uploadConfigForPushedAtomWithUid(mPkgName,
                    AtomsProto.Atom.IME_REQUEST_FINISHED_FIELD_NUMBER,
                    false /* useUidAttributionChain */);

            final var activity = createTestActivity(SOFT_INPUT_STATE_UNCHANGED);
            awaitControl(WindowInsets.Type.ime(), activity);
            expectImeInvisible(TIMEOUT);

            TestUtils.runOnMainSync(() -> activity.getWindow()
                    .getDecorView()
                    .getWindowInsetsController()
                    .show(WindowInsets.Type.ime()));

            expectImeVisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            // Remove logs for the show requests.
            MetricsRecorder.clearReports();

            TestUtils.runOnMainSync(() -> activity.getWindow()
                    .getDecorView()
                    .getWindowInsetsController()
                    .hide(WindowInsets.Type.ime()));

            expectImeInvisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            final var data = MetricsRecorder.getEventMetricDataList();
            assertWithMessage("Number of atoms logged")
                    .that(data.size())
                    .isAtLeast(1);

            try {
                int successfulAtoms = 0;
                for (int i = 0; i < data.size(); i++) {
                    final var atom = data.get(i).atom;
                    assertThat(atom).isNotNull();

                    final var imeRequestFinished = atom.getImeRequestFinished();
                    assertThat(imeRequestFinished).isNotNull();

                    // Skip cancelled requests.
                    if (imeRequestFinished.status == ImeProtoEnums.STATUS_CANCEL) continue;

                    successfulAtoms++;

                    assertWithMessage("Ime Request type")
                            .that(imeRequestFinished.type)
                            .isEqualTo(ImeProtoEnums.TYPE_HIDE);
                    assertWithMessage("Ime Request status")
                            .that(imeRequestFinished.status)
                            .isEqualTo(ImeProtoEnums.STATUS_SUCCESS);
                    assertWithMessage("Ime Request origin")
                            .that(imeRequestFinished.origin)
                            .isEqualTo(ImeProtoEnums.ORIGIN_CLIENT_HIDE_SOFT_INPUT);
                }

                assertWithMessage("Number of successful atoms logged")
                        .that(successfulAtoms)
                        .isAtLeast(1);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "\natoms data:\n" + data, e);
            }
        }
    }

    /**
     * Test the logging for a server show IME request.
     */
    @Test
    public void testServerShowImeRequestFinished() throws Exception {
        // Create mockImeSession to decouple from real IMEs,
        // and enable calling expectImeVisible and expectImeInvisible.
        try (var imeSession = MockImeSession.create(
                mInstrumentation.getContext(),
                mInstrumentation.getUiAutomation(),
                new ImeSettings.Builder())) {
            // Wait for any outstanding IME requests to finish, to not interfere with test.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Setup Failed: There should be no pending IME requests present when the "
                            + "test starts.");

            MetricsRecorder.uploadConfigForPushedAtomWithUid(mPkgName,
                    AtomsProto.Atom.IME_REQUEST_FINISHED_FIELD_NUMBER,
                    false /* useUidAttributionChain */);

            createTestActivity(SOFT_INPUT_STATE_ALWAYS_VISIBLE);

            expectImeVisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            final var data = MetricsRecorder.getEventMetricDataList();
            assertWithMessage("Number of atoms logged")
                    .that(data.size())
                    .isAtLeast(1);

            try {
                int successfulAtoms = 0;
                for (int i = 0; i < data.size(); i++) {
                    final var atom = data.get(i).atom;
                    assertThat(atom).isNotNull();

                    final var imeRequestFinished = atom.getImeRequestFinished();
                    assertThat(imeRequestFinished).isNotNull();

                    // Skip cancelled requests.
                    if (imeRequestFinished.status == ImeProtoEnums.STATUS_CANCEL) continue;

                    successfulAtoms++;

                    assertWithMessage("Ime Request type")
                            .that(imeRequestFinished.type)
                            .isEqualTo(ImeProtoEnums.TYPE_SHOW);
                    assertWithMessage("Ime Request status")
                            .that(imeRequestFinished.status)
                            .isEqualTo(ImeProtoEnums.STATUS_SUCCESS);
                    assertWithMessage("Ime Request origin")
                            .that(imeRequestFinished.origin)
                            .isEqualTo(ImeProtoEnums.ORIGIN_SERVER_START_INPUT);
                }

                assertWithMessage("Number of successful atoms logged")
                        .that(successfulAtoms)
                        .isAtLeast(1);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "\natoms data:\n" + data, e);
            }
        }
    }

    /**
     * Test the logging for a server hide IME request.
     */
    @Test
    public void testServerHideImeRequestFinished() throws Exception {
        // Create mockImeSession to decouple from real IMEs,
        // and enable calling expectImeVisible and expectImeInvisible.
        try (var imeSession = MockImeSession.create(
                mInstrumentation.getContext(),
                mInstrumentation.getUiAutomation(),
                new ImeSettings.Builder())) {
            // Wait for any outstanding IME requests to finish, to not interfere with test.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Setup Failed: There should be no pending IME requests present when the "
                            + "test starts.");

            MetricsRecorder.uploadConfigForPushedAtomWithUid(mPkgName,
                    AtomsProto.Atom.IME_REQUEST_FINISHED_FIELD_NUMBER,
                    false /* useUidAttributionChain */);

            createTestActivity(SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            expectImeVisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            // Remove logs for the show requests.
            MetricsRecorder.clearReports();

            // TODO: this is not actually an IME hide request from the server,
            //  but in the current configuration it is tracked like one.
            //  Will likely change in the future.
            imeSession.callRequestHideSelf(0 /* flags */);

            expectImeInvisible(TIMEOUT);
            // Wait for any outstanding IME requests to finish, to capture all atoms successfully.
            PollingCheck.waitFor(() -> !imeSession.hasPendingImeVisibilityRequests(),
                    "Test Error: Pending IME requests took too long, likely timing out.");

            final var data = MetricsRecorder.getEventMetricDataList();
            assertWithMessage("Number of atoms logged")
                    .that(data.size())
                    .isAtLeast(1);

            try {
                int successfulAtoms = 0;
                for (int i = 0; i < data.size(); i++) {
                    final var atom = data.get(i).atom;
                    assertThat(atom).isNotNull();

                    final var imeRequestFinished = atom.getImeRequestFinished();
                    assertThat(imeRequestFinished).isNotNull();

                    // Skip cancelled requests.
                    if (imeRequestFinished.status == ImeProtoEnums.STATUS_CANCEL) continue;

                    successfulAtoms++;

                    assertWithMessage("Ime Request type")
                            .that(imeRequestFinished.type)
                            .isEqualTo(ImeProtoEnums.TYPE_HIDE);
                    assertWithMessage("Ime Request status")
                            .that(imeRequestFinished.status)
                            .isEqualTo(ImeProtoEnums.STATUS_SUCCESS);
                    assertWithMessage("Ime Request origin")
                            .that(imeRequestFinished.origin)
                            .isEqualTo(ImeProtoEnums.ORIGIN_SERVER_HIDE_INPUT);
                }

                assertWithMessage("Number of successful atoms logged")
                        .that(successfulAtoms)
                        .isAtLeast(1);
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + "\natoms data:\n" + data, e);
            }
        }
    }
}
