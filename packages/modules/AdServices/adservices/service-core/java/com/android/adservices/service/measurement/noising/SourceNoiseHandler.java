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

package com.android.adservices.service.measurement.noising;

import android.annotation.NonNull;
import android.net.Uri;

import com.android.adservices.service.Flags;
import com.android.adservices.service.measurement.PrivacyParams;
import com.android.adservices.service.measurement.ReportSpec;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.reporting.EventReportWindowCalcDelegate;
import com.android.adservices.service.measurement.util.UnsignedLong;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

/** Generates noised reports for the provided source. */
public class SourceNoiseHandler {
    private static final int PROBABILITY_DECIMAL_POINTS_LIMIT = 7;

    private final Flags mFlags;
    private final EventReportWindowCalcDelegate mEventReportWindowCalcDelegate;

    public SourceNoiseHandler(@NonNull Flags flags) {
        mFlags = flags;
        mEventReportWindowCalcDelegate = new EventReportWindowCalcDelegate(flags);
    }

    @VisibleForTesting
    SourceNoiseHandler(
            @NonNull Flags flags,
            @NonNull EventReportWindowCalcDelegate eventReportWindowCalcDelegate) {
        mFlags = flags;
        mEventReportWindowCalcDelegate = eventReportWindowCalcDelegate;
    }

    /** Multiplier is 1, when only one destination needs to be considered. */
    public static final int SINGLE_DESTINATION_IMPRESSION_NOISE_MULTIPLIER = 1;

    /**
     * Double-folds the number of states in order to allocate half to app destination and half to
     * web destination for fake reports generation.
     */
    public static final int DUAL_DESTINATION_IMPRESSION_NOISE_MULTIPLIER = 2;

    /**
     * Assign attribution mode based on random rate and generate fake reports if needed. Should only
     * be called for a new Source.
     *
     * @return fake reports to be stored in the datastore.
     */
    public List<Source.FakeReport> assignAttributionModeAndGenerateFakeReports(
            @NonNull Source source) {
        Random rand = new Random();
        double value = rand.nextDouble();
        if (value > getRandomAttributionProbability(source)) {
            source.setAttributionMode(Source.AttributionMode.TRUTHFULLY);
            return Collections.emptyList();
        }

        List<Source.FakeReport> fakeReports;
        ReportSpec flexEventReportSpec = source.getFlexEventReportSpec();
        if (flexEventReportSpec == null) {
            if (isVtcDualDestinationModeWithPostInstallEnabled(source)) {
                // Source is 'EVENT' type, both app and web destination are set and install
                // exclusivity
                // window is provided. Pick one of the static reporting states randomly.
                fakeReports = generateVtcDualDestinationPostInstallFakeReports(source);
            } else {
                // There will at least be one (app or web) destination available
                ImpressionNoiseParams noiseParams = getImpressionNoiseParams(source);
                fakeReports =
                        ImpressionNoiseUtil.selectRandomStateAndGenerateReportConfigs(
                                        noiseParams, rand)
                                .stream()
                                .map(
                                        reportConfig ->
                                                new Source.FakeReport(
                                                        new UnsignedLong(
                                                                Long.valueOf(reportConfig[0])),
                                                        mEventReportWindowCalcDelegate
                                                                .getReportingTimeForNoising(
                                                                        source,
                                                                        reportConfig[1],
                                                                        isInstallDetectionEnabled(
                                                                                source)),
                                                        resolveFakeReportDestinations(
                                                                source, reportConfig[2])))
                                .collect(Collectors.toList());
            }
        } else {
            int destinationTypeMultiplier = getDestinationTypeMultiplier(source);
            List<int[]> fakeReportConfigs =
                    ImpressionNoiseUtil.selectFlexEventReportRandomStateAndGenerateReportConfigs(
                            flexEventReportSpec, destinationTypeMultiplier, rand);
            fakeReports =
                    fakeReportConfigs.stream()
                            .map(
                                    reportConfig ->
                                            new Source.FakeReport(
                                                    new UnsignedLong(
                                                            Long.valueOf(
                                                                    flexEventReportSpec
                                                                            .getTriggerDataValue(
                                                                                    reportConfig[
                                                                                            0]))),
                                                    mEventReportWindowCalcDelegate
                                                            .getReportingTimeForNoisingFlexEventAPI(
                                                                    reportConfig[1],
                                                                    reportConfig[0],
                                                                    flexEventReportSpec),
                                                    resolveFakeReportDestinations(
                                                            source, reportConfig[2])))
                            .collect(Collectors.toList());
        }
        @Source.AttributionMode
        int attributionMode =
                fakeReports.isEmpty()
                        ? Source.AttributionMode.NEVER
                        : Source.AttributionMode.FALSELY;
        source.setAttributionMode(attributionMode);
        return fakeReports;
    }

    /** @return Probability of selecting random state for attribution */
    public double getRandomAttributionProbability(@NonNull Source source) {
        if (mFlags.getMeasurementEnableConfigurableEventReportingWindows()
                || mFlags.getMeasurementEnableVtcConfigurableMaxEventReports()) {
            return calculateNoiseDynamically(source);
        }

        // Both destinations are set and install attribution is supported
        if (!shouldReportCoarseDestinations(source)
                && source.hasWebDestinations()
                && isInstallDetectionEnabled(source)) {
            return source.getSourceType() == Source.SourceType.EVENT
                    ? PrivacyParams.INSTALL_ATTR_DUAL_DESTINATION_EVENT_NOISE_PROBABILITY
                    : PrivacyParams.INSTALL_ATTR_DUAL_DESTINATION_NAVIGATION_NOISE_PROBABILITY;
        }

        // Both destinations are set but install attribution isn't supported
        if (!shouldReportCoarseDestinations(source)
                && source.hasAppDestinations()
                && source.hasWebDestinations()) {
            return source.getSourceType() == Source.SourceType.EVENT
                    ? PrivacyParams.DUAL_DESTINATION_EVENT_NOISE_PROBABILITY
                    : PrivacyParams.DUAL_DESTINATION_NAVIGATION_NOISE_PROBABILITY;
        }

        // App destination is set and install attribution is supported
        if (isInstallDetectionEnabled(source)) {
            return source.getSourceType() == Source.SourceType.EVENT
                    ? PrivacyParams.INSTALL_ATTR_EVENT_NOISE_PROBABILITY
                    : PrivacyParams.INSTALL_ATTR_NAVIGATION_NOISE_PROBABILITY;
        }

        // One of the destinations is available without install attribution support
        return source.getSourceType() == Source.SourceType.EVENT
                ? PrivacyParams.EVENT_NOISE_PROBABILITY
                : PrivacyParams.NAVIGATION_NOISE_PROBABILITY;
    }

    private double calculateNoiseDynamically(Source source) {
        int triggerDataCardinality = source.getTriggerDataCardinality();
        int reportingWindowCountForNoising =
                mEventReportWindowCalcDelegate.getReportingWindowCountForNoising(
                        source, isInstallDetectionEnabled(source));
        int maxReportCount =
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        source, isInstallDetectionEnabled(source));
        int destinationMultiplier = getDestinationTypeMultiplier(source);
        int numberOfStates =
                Combinatorics.getNumberOfStarsAndBarsSequences(
                        /*numStars=*/ maxReportCount,
                        /*numBars=*/ triggerDataCardinality
                                * reportingWindowCountForNoising
                                * destinationMultiplier);
        double absoluteProbability = Combinatorics.getFlipProbability(numberOfStates);
        return BigDecimal.valueOf(absoluteProbability)
                .setScale(PROBABILITY_DECIMAL_POINTS_LIMIT, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private boolean isVtcDualDestinationModeWithPostInstallEnabled(Source source) {
        return !shouldReportCoarseDestinations(source)
                && source.getSourceType() == Source.SourceType.EVENT
                && source.hasWebDestinations()
                && isInstallDetectionEnabled(source);
    }

    /**
     * Get the destination type multiplier,
     *
     * @return number of the destination type
     */
    private int getDestinationTypeMultiplier(Source source) {
        return !shouldReportCoarseDestinations(source)
                        && source.hasAppDestinations()
                        && source.hasWebDestinations()
                ? DUAL_DESTINATION_IMPRESSION_NOISE_MULTIPLIER
                : SINGLE_DESTINATION_IMPRESSION_NOISE_MULTIPLIER;
    }

    /**
     * Either both app and web destinations can be available or one of them will be available. When
     * both destinations are available, we double the number of states at noise generation to be
     * able to randomly choose one of them for fake report creation. We don't add the multiplier
     * when only one of them is available. In that case, choose the one that's non-null.
     *
     * @param destinationIdentifier destination identifier, can be 0 (app) or 1 (web)
     * @return app or web destination {@link Uri}
     */
    private List<Uri> resolveFakeReportDestinations(Source source, int destinationIdentifier) {
        if (shouldReportCoarseDestinations(source)) {
            ImmutableList.Builder<Uri> destinations = new ImmutableList.Builder<>();
            Optional.ofNullable(source.getAppDestinations()).ifPresent(destinations::addAll);
            Optional.ofNullable(source.getWebDestinations()).ifPresent(destinations::addAll);
            return destinations.build();
        }

        if (source.hasAppDestinations() && source.hasWebDestinations()) {
            return destinationIdentifier % DUAL_DESTINATION_IMPRESSION_NOISE_MULTIPLIER == 0
                    ? source.getAppDestinations()
                    : source.getWebDestinations();
        }

        return source.hasAppDestinations()
                ? source.getAppDestinations()
                : source.getWebDestinations();
    }

    private boolean isInstallDetectionEnabled(@NonNull Source source) {
        return source.getInstallCooldownWindow() > 0 && source.hasAppDestinations();
    }

    private boolean shouldReportCoarseDestinations(Source source) {
        return mFlags.getMeasurementEnableCoarseEventReportDestinations()
                && source.getCoarseEventReportDestinations();
    }

    private List<Source.FakeReport> generateVtcDualDestinationPostInstallFakeReports(
            Source source) {
        int[][][] fakeReportsConfig =
                ImpressionNoiseUtil.DUAL_DESTINATION_POST_INSTALL_FAKE_REPORT_CONFIG;
        int randomIndex = new Random().nextInt(fakeReportsConfig.length);
        int[][] reportsConfig = fakeReportsConfig[randomIndex];
        return Arrays.stream(reportsConfig)
                .map(
                        reportConfig ->
                                new Source.FakeReport(
                                        new UnsignedLong(Long.valueOf(reportConfig[0])),
                                        mEventReportWindowCalcDelegate.getReportingTimeForNoising(
                                                source,
                                                /* window index */ reportConfig[1],
                                                isInstallDetectionEnabled(source)),
                                        resolveFakeReportDestinations(source, reportConfig[2])))
                .collect(Collectors.toList());
    }

    @VisibleForTesting
    ImpressionNoiseParams getImpressionNoiseParams(Source source) {
        int destinationTypeMultiplier = getDestinationTypeMultiplier(source);
        return new ImpressionNoiseParams(
                mEventReportWindowCalcDelegate.getMaxReportCount(
                        source, isInstallDetectionEnabled(source)),
                source.getTriggerDataCardinality(),
                mEventReportWindowCalcDelegate.getReportingWindowCountForNoising(
                        source, isInstallDetectionEnabled(source)),
                destinationTypeMultiplier);
    }
}
