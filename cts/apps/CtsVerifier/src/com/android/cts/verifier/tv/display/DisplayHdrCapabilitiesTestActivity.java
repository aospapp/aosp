/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.cts.verifier.tv.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import androidx.annotation.StringRes;

import com.android.cts.verifier.R;
import com.android.cts.verifier.tv.TvAppVerifierActivity;
import com.android.cts.verifier.tv.TvUtil;

import com.google.common.base.Throwables;
import com.google.common.collect.Range;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Test to verify the HDR Capabilities API is correctly implemented.
 *
 * <p>This test checks if <a
 * href="https://developer.android.com/reference/android/view/Display.html#isHdr()">Display.isHdr()</a>
 * and <a
 * href="https://developer.android.com/reference/android/view/Display.html#getHdrCapabilities()">Display.getHdrCapabilities()</a>
 * return correct results when 1. HDR Display is connected, 2. non-HDR Display is connected and 3.
 * no display is connected.
 */
public class DisplayHdrCapabilitiesTestActivity extends TvAppVerifierActivity {
    private static final String LOG_TAG = "HdrCapabilitiesTest";
    private static final float MAX_EXPECTED_LUMINANCE = 10_000f;
    private static final int DISPLAY_DISCONNECT_WAIT_TIME_SECONDS = 5;

    private TestSequence mTestSequence;

    @Override
    protected void setInfoResources() {
        setInfoResources(
                R.string.tv_hdr_capabilities_test, R.string.tv_hdr_capabilities_test_info, -1);
    }

    @Override
    public String getTestDetails() {
        return mTestSequence.getFailureDetails();
    }

    @Override
    protected void createTestItems() {
        List<TestStepBase> testSteps = new ArrayList<>();
        if (TvUtil.isHdmiSourceDevice()) {
            // The device is a set-top box or a TV dongle
            testSteps.add(new NonHdrDisplayTestStep(this));
            testSteps.add(new HdrDisplayTestStep(this));
            testSteps.add(new NoDisplayTestStep(this));
        } else {
            // The device is a TV Panel
            testSteps.add(new TvPanelReportedTypesAreSupportedTestStep(this));
            testSteps.add(new TvPanelSupportedTypesAreReportedTestStep(this));
        }
        mTestSequence = new TestSequence(this, testSteps);
        mTestSequence.init();
    }

    private static class NonHdrDisplayTestStep extends SyncTestStep {

        public NonHdrDisplayTestStep(TvAppVerifierActivity context) {
            super(
                    context,
                    R.string.tv_hdr_capabilities_test_step_non_hdr_display,
                    getInstructionText(context),
                    getButtonStringId());
        }

        private static String getInstructionText(Context context) {
            return context.getString(
                    R.string.tv_hdr_connect_no_hdr_display, context.getString(getButtonStringId()));
        }

        private static @StringRes int getButtonStringId() {
            return R.string.tv_start_test;
        }

        @Override
        public void runTest() {
            DisplayManager displayManager =
                    (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            getAsserter().withMessage("Display.isHdr()").that(display.isHdr()).isFalse();
            getAsserter()
                    .withMessage("Display.getHdrCapabilities()")
                    .that(display.getHdrCapabilities().getSupportedHdrTypes())
                    .isEmpty();
        }
    }

    private static class HdrDisplayTestStep extends SyncTestStep {

        public HdrDisplayTestStep(TvAppVerifierActivity context) {
            super(
                    context,
                    R.string.tv_hdr_capabilities_test_step_hdr_display,
                    getInstructionText(context),
                    getButtonStringId());
        }

        private static String getInstructionText(Context context) {
            return context.getString(
                    R.string.tv_hdr_connect_hdr_display, context.getString(getButtonStringId()));
        }

        private static @StringRes int getButtonStringId() {
            return R.string.tv_start_test;
        }

        @Override
        public void runTest() {
            DisplayManager displayManager =
                    (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);

            getAsserter().withMessage("Display.isHdr()").that(display.isHdr()).isTrue();

            Display.HdrCapabilities hdrCapabilities = display.getHdrCapabilities();

            int[] supportedHdrTypes = hdrCapabilities.getSupportedHdrTypes();
            Arrays.sort(supportedHdrTypes);

            getAsserter()
                    .withMessage("Display.getHdrCapabilities().getSupportedTypes()")
                    .that(supportedHdrTypes)
                    .isEqualTo(
                            new int[] {
                                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                                Display.HdrCapabilities.HDR_TYPE_HDR10,
                                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
                                Display.HdrCapabilities.HDR_TYPE_HLG
                            });

            float maxLuminance = hdrCapabilities.getDesiredMaxLuminance();
            getAsserter()
                    .withMessage("Display.getHdrCapabilities().getDesiredMaxLuminance()")
                    .that(maxLuminance)
                    .isIn(Range.openClosed(0f, MAX_EXPECTED_LUMINANCE));

            float minLuminance = hdrCapabilities.getDesiredMinLuminance();
            getAsserter()
                    .withMessage("Display.getHdrCapabilities().getDesiredMinLuminance()")
                    .that(minLuminance)
                    .isIn(Range.closedOpen(0f, MAX_EXPECTED_LUMINANCE));

            getAsserter()
                    .withMessage("Display.getHdrCapabilities().getDesiredMaxAverageLuminance()")
                    .that(hdrCapabilities.getDesiredMaxAverageLuminance())
                    .isIn(Range.openClosed(minLuminance, maxLuminance));
        }
    }

    private static class NoDisplayTestStep extends AsyncTestStep {
        public NoDisplayTestStep(TvAppVerifierActivity context) {
            super(
                    context,
                    R.string.tv_hdr_capabilities_test_step_no_display,
                    getInstructionText(context),
                    getButtonStringId());
        }

        private static String getInstructionText(Context context) {
            return context.getString(
                    R.string.tv_hdr_disconnect_display,
                    context.getString(getButtonStringId()),
                    DISPLAY_DISCONNECT_WAIT_TIME_SECONDS,
                    DISPLAY_DISCONNECT_WAIT_TIME_SECONDS + 1);
        }

        private static @StringRes int getButtonStringId() {
            return R.string.tv_start_test;
        }

        @Override
        public void runTestAsync() {
            // Wait for the user to disconnect the display.
            final long delay = Duration.ofSeconds(DISPLAY_DISCONNECT_WAIT_TIME_SECONDS).toMillis();
            mContext.getPostTarget().postDelayed(this::runTest, delay);
        }

        private void runTest() {
            try {
                // Verify the display APIs do not crash when the display is disconnected
                DisplayManager displayManager =
                        (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
                Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
                display.isHdr();
                display.getHdrCapabilities();
            } catch (Exception e) {
                getAsserter().fail(Throwables.getStackTraceAsString(e));
            }
            done();
        }
    }

    private static class TvPanelReportedTypesAreSupportedTestStep extends YesNoTestStep {
        public TvPanelReportedTypesAreSupportedTestStep(TvAppVerifierActivity context) {
            super(context, getInstructionText(context));
        }

        private static String getInstructionText(Context context) {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);

            int[] hdrTypes = display.getHdrCapabilities().getSupportedHdrTypes();
            String hdrTypesString;
            if (hdrTypes.length == 0) {
                hdrTypesString = context.getString(R.string.tv_none);
            } else {
                hdrTypesString =
                        Arrays.stream(hdrTypes)
                                .mapToObj(DisplayHdrCapabilitiesTestActivity::hdrTypeToString)
                                .collect(Collectors.joining(", "));
            }

            return context.getString(
                    R.string.tv_panel_hdr_types_reported_are_supported, hdrTypesString);
        }
    }

    private static class TvPanelSupportedTypesAreReportedTestStep extends YesNoTestStep {
        public TvPanelSupportedTypesAreReportedTestStep(TvAppVerifierActivity context) {
            super(context, getInstructionText(context));
        }

        private static String getInstructionText(Context context) {
            return context.getString(R.string.tv_panel_hdr_types_supported_are_reported);
        }
    }

    private static String hdrTypeToString(@Display.HdrCapabilities.HdrType int type) {
        switch (type) {
            case Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION:
                return "DOLBY_VISION";
            case Display.HdrCapabilities.HDR_TYPE_HDR10:
                return "HDR10";
            case Display.HdrCapabilities.HDR_TYPE_HLG:
                return "HLG";
            case Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS:
                return "HDR10_PLUS";
            default:
                Log.e(LOG_TAG, "Unknown HDR type " + type);
                return "UNKNOWN";
        }
    }
}
