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

package com.android.adservices.service.measurement.noising;

import com.android.adservices.service.measurement.ReportSpec;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

/**
 * Util class for generating impression noise
 */
public final class ImpressionNoiseUtil {

    /**
     * This is used in the scenario where both app and web destinations are available with the
     * {@link com.android.adservices.service.measurement.Source} and the source is of {@link
     * com.android.adservices.service.measurement.Source.SourceType#EVENT} type and post-install
     * detection is enabled (cooldown window being available). It's a special case because in this
     * condition an extra early window is added only if install detection is enabled (install
     * cool-down window is available) and only app conversions are relevant in that window.
     *
     * <p>Reading guide - The outermost array signifies different states of reporting - one of them
     * will be picked at a time. The middle array holds reports in 1st and 2nd window, so there will
     * be either 0 elements (no conversions in either window), 1 element (a conversion report in one
     * of the windows) and 2 elements (conversions in both windows). The innermost array represents
     * a single report. 3 elements in the innermost array are trigger metadata (0 - trigger1 or 1 -
     * trigger2), window index (0 - window1, 1 - window2) and destination type (0 - app or 1 - web).
     *
     * <p>E.g. The element at index 5 is {{0, 1, 0}, {0, 1, 0}}, it means 2 conversions with trigger
     * metadata as 0 in window2 (1) of app destination type(0).
     */
    public static final int[][][] DUAL_DESTINATION_POST_INSTALL_FAKE_REPORT_CONFIG =
            new int[][][] {
                // window1 - no conversion, window 2 - no conversion
                {},
                // window1 - no conversion, window 2 - 1 conversion with metadata 0
                {{0, 1, 0}},
                {{0, 1, 1}},
                // window1 - no conversion, window 2 - 1 conversion with metadata 1
                {{1, 1, 0}},
                {{1, 1, 1}},
                // window1 - no conversion, window 2 - 2 conversions with metadata 0 and 0
                {{0, 1, 0}, {0, 1, 0}},
                {{0, 1, 0}, {0, 1, 1}},
                // window1 - no conversion, window 2 - 2 conversions with metadata 0 and 1
                {{0, 1, 0}, {1, 1, 0}},
                {{0, 1, 0}, {1, 1, 1}},
                {{0, 1, 1}, {1, 1, 0}},
                // window1 - no conversion, window 2 - 2 conversions with metadata 1 and 1
                {{1, 1, 0}, {1, 1, 0}},
                {{1, 1, 0}, {1, 1, 1}},
                // window1 - 1 app conversion with metadata 0, window 2 - no conversion
                {{0, 0, 0}},
                // window1 - 1 app conversion with metadata 1, window 2 - no conversion
                {{1, 0, 0}},
                // window1 - 2 conversions with metadata 0 and 0, window 2 - no conversion
                {{0, 0, 0}, {0, 0, 0}},
                // window1 - 2 app conversions with metadata 0 and 1, window 2 - no conversion
                {{0, 0, 0}, {1, 0, 0}},
                // window1 - 2 app conversions with metadata 1 and 1, window 2 - no conversion
                {{1, 0, 0}, {1, 0, 0}},
                // window1 - 1 app conversion with metadata 0, window 2 - 1 conversion with
                // metadata 0
                {{0, 0, 0}, {0, 1, 0}},
                {{0, 0, 0}, {0, 1, 1}},
                // window1 - 1 app conversion with metadata 0, window 2 - 1 conversion with
                // metadata 1
                {{0, 0, 0}, {1, 1, 0}},
                {{0, 0, 0}, {1, 1, 1}},
                // window1 - 1 app conversion with metadata 1, window 2 - 1 conversions with
                // metadata 0
                {{1, 0, 0}, {0, 1, 0}},
                {{1, 0, 0}, {0, 1, 1}},
                // window1 - 1 app conversion with metadata 1, window 2 - 1 conversions with
                // metadata 1
                {{1, 0, 0}, {1, 1, 0}},
                {{1, 0, 0}, {1, 1, 1}}
            };

    private ImpressionNoiseUtil() {}

    /**
     * Randomly generate report configs based on noise params
     *
     * @param noiseParams Noise parameters to use for state generation
     * @param rand random number generator
     * @return list of reporting configs
     */
    public static List<int[]> selectRandomStateAndGenerateReportConfigs(
            ImpressionNoiseParams noiseParams, Random rand) {
        // Get total possible combinations
        int numCombinations =
                Combinatorics.getNumberOfStarsAndBarsSequences(
                        /*numStars=*/ noiseParams.getReportCount(),
                        /*numBars=*/ noiseParams.getTriggerDataCardinality()
                                * noiseParams.getReportingWindowCount()
                                * noiseParams.getDestinationTypeMultiplier());
        // Choose a sequence index
        int sequenceIndex = rand.nextInt(numCombinations);
        return getReportConfigsForSequenceIndex(noiseParams, sequenceIndex);
    }

    @VisibleForTesting
    static List<int[]> getReportConfigsForSequenceIndex(
            ImpressionNoiseParams noiseParams, int sequenceIndex) {
        List<int[]> reportConfigs = new ArrayList<>();
        // Get the configuration for the sequenceIndex
        int[] starIndices = Combinatorics.getStarIndices(
                /*numStars=*/noiseParams.getReportCount(),
                /*sequenceIndex=*/sequenceIndex);
        int[] barsPrecedingEachStar = Combinatorics.getBarsPrecedingEachStar(starIndices);
        // Generate fake reports
        // Stars: number of reports
        // Bars: (Number of windows) * (Trigger data cardinality) * (Destination multiplier)
        for (int numBars : barsPrecedingEachStar) {
            if (numBars == 0) {
                continue;
            }

            // Extract bits for trigger data, destination type and windowIndex from encoded numBars
            int[] reportConfig = createReportingConfig(numBars, noiseParams);
            reportConfigs.add(reportConfig);
        }
        return reportConfigs;
    }

    /**
     * Extract bits for trigger data, destination type and windowIndex from encoded numBars.
     *
     * @param numBars data encoding triggerData, destinationType and window index
     * @param noiseParams noise params
     * @return array having triggerData, destinationType and windowIndex
     */
    private static int[] createReportingConfig(int numBars, ImpressionNoiseParams noiseParams) {
        int triggerData = (numBars - 1) % noiseParams.getTriggerDataCardinality();
        int remainingData = (numBars - 1) / noiseParams.getTriggerDataCardinality();

        int reportingWindowIndex = remainingData % noiseParams.getReportingWindowCount();
        int destinationTypeIndex = remainingData / noiseParams.getReportingWindowCount();
        return new int[] {triggerData, reportingWindowIndex, destinationTypeIndex};
    }

    /**
     * Randomly generate report configs based on noise params
     *
     * @param reportSpec Report spec to use for state generation
     * @param destinationMultiplier destination multiplier
     * @param rand random number generator
     * @return list of reporting configs
     */
    public static List<int[]> selectFlexEventReportRandomStateAndGenerateReportConfigs(
            ReportSpec reportSpec, int destinationMultiplier, Random rand) {

        int[][] params = reportSpec.getPrivacyParamsForComputation();
        for (int i = 0; i < params[1].length; i++) {
            params[1][i] *= destinationMultiplier;
        }
        int numStates = Combinatorics.getNumStatesFlexAPI(params[0][0], params[1], params[2]);
        int sequenceIndex = rand.nextInt(numStates);
        List<Combinatorics.AtomReportState> rawFakeReports =
                Combinatorics.getReportSetBasedOnRank(
                        params[0][0], params[1], params[2], sequenceIndex, new HashMap<>());
        List<int[]> fakeReportConfigs = new ArrayList<>();
        for (Combinatorics.AtomReportState rawFakeReport : rawFakeReports) {
            int[] fakeReportConfig = new int[3];
            fakeReportConfig[0] = rawFakeReport.getTriggerDataType();
            fakeReportConfig[1] = (rawFakeReport.getWindowIndex()) / destinationMultiplier;
            fakeReportConfig[2] = (rawFakeReport.getWindowIndex()) % destinationMultiplier;
            fakeReportConfigs.add(fakeReportConfig);
        }
        return fakeReportConfigs;
    }
}
