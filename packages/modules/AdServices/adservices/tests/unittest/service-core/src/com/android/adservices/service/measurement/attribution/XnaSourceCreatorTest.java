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

package com.android.adservices.service.measurement.attribution;

import static org.junit.Assert.assertEquals;

import android.util.Pair;

import com.android.adservices.LogUtil;
import com.android.adservices.service.measurement.AttributionConfig;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.TriggerFixture;
import com.android.adservices.service.measurement.util.Filter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class XnaSourceCreatorTest {
    @Test
    public void generateDerivedSources_withVarietyOfSources_filtersAndGeneratesSources()
            throws JSONException {
        // Setup
        String enrollment1 = "enrollment1";
        JSONArray filters =
                new JSONArray(Collections.singletonList(new JSONObject(buildMatchingFilterData())));
        AttributionConfig attributionConfig1 =
                new AttributionConfig.Builder()
                        .setSourceAdtech(enrollment1)
                        .setSourceFilters(Filter.deserializeFilterSet(filters))
                        .setSourceNotFilters(null)
                        .setFilterData(Filter.deserializeFilterSet(filters))
                        .setExpiry(50L)
                        .setPriority(50L)
                        .setPostInstallExclusivityWindow(5L)
                        .setSourcePriorityRange(new Pair<>(1L, 100L))
                        .build();
        String enrollment2 = "enrollment2";
        AttributionConfig attributionConfig2 =
                new AttributionConfig.Builder()
                        .setSourceAdtech(enrollment2)
                        .setSourceFilters(null)
                        .setSourceNotFilters(
                                Filter.deserializeFilterSet(
                                        new JSONArray(
                                                Collections.singletonList(
                                                        new JSONObject(
                                                                buildNonMatchingFilterData())))))
                        .setSourcePriorityRange(new Pair<>(101L, 200L))
                        .setSourceExpiryOverride(TimeUnit.DAYS.toMillis(10L))
                        .build();

        AttributionConfig attributionConfig1_copy =
                new AttributionConfig.Builder()
                        .setSourceAdtech(enrollment1)
                        .setSourceFilters(Filter.deserializeFilterSet(filters))
                        .setSourceNotFilters(null)
                        .setFilterData(Filter.deserializeFilterSet(filters))
                        .setExpiry(60L)
                        .setPriority(70L)
                        .setPostInstallExclusivityWindow(50L)
                        .setSourcePriorityRange(new Pair<>(1L, 100L))
                        .build();

        String attributionConfigsArray =
                new JSONArray(
                                Arrays.asList(
                                        attributionConfig1.serializeAsJson(),
                                        attributionConfig2.serializeAsJson(),
                                        // This ensures that already consumed sources aren't reused
                                        attributionConfig1_copy.serializeAsJson()))
                        .toString();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAttributionConfig(attributionConfigsArray)
                        .build();

        // Aggregate source
        JSONObject aggregatableSource = new JSONObject();
        aggregatableSource.put("key1", "0x159");
        aggregatableSource.put("key2", "0x1");
        aggregatableSource.put("key3", "0x2");

        // enrollment1 sources
        Source source1Matches =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(1L)
                        .setAggregateSource(aggregatableSource.toString())
                        .setSharedAggregationKeys(
                                new JSONArray(Arrays.asList("key2", "key3")).toString())
                        .build();

        JSONObject derivedAggregatableSource1 = new JSONObject();
        derivedAggregatableSource1.put("key2", "0x1");
        derivedAggregatableSource1.put("key3", "0x2");
        Source expectedDerivedSource1 =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(attributionConfig1.getPriority())
                        .setFilterData(
                                Filter.serializeFilterSet(attributionConfig1.getFilterData())
                                        .toString())
                        .setExpiryTime(source1Matches.getExpiryTime())
                        .setInstallCooldownWindow(
                                attributionConfig1.getPostInstallExclusivityWindow())
                        .setParentId(source1Matches.getId())
                        .setSharedAggregationKeys(
                                new JSONArray(Arrays.asList("key2", "key3")).toString())
                        .setAggregateSource(derivedAggregatableSource1.toString())
                        .build();
        Source source2Matches =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(100L)
                        .setFilterData(buildMatchingFilterData())
                        .setAggregateSource(aggregatableSource.toString())
                        .setSharedAggregationKeys(
                                new JSONArray(Collections.singletonList("key1")).toString())
                        .build();

        JSONObject derivedAggregatableSource2 = new JSONObject();
        derivedAggregatableSource2.put("key1", "0x159");
        Source expectedDerivedSource2 =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(attributionConfig1.getPriority())
                        .setFilterData(
                                Filter.serializeFilterSet(attributionConfig1.getFilterData())
                                        .toString())
                        .setExpiryTime(source2Matches.getExpiryTime())
                        .setInstallCooldownWindow(
                                attributionConfig1.getPostInstallExclusivityWindow())
                        .setParentId(source2Matches.getId())
                        .setAggregateSource(derivedAggregatableSource2.toString())
                        .setSharedAggregationKeys(
                                new JSONArray(Collections.singletonList("key1")).toString())
                        .build();
        Source source3OutOfPriorityRange =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(101L)
                        .setFilterData(buildMatchingFilterData())
                        .build();
        Source source4NonMatchingFilter =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment1)
                        .setPriority(50L)
                        .setFilterData(buildNonMatchingFilterData())
                        .build();

        // enrollment2 sources
        Source source5Matches =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment2)
                        .setPriority(120L)
                        .setInstallCooldownWindow(0L)
                        .build();
        Source expectedDerivedSource5 =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment2)
                        .setPriority(120L)
                        .setFilterData(source5Matches.getFilterDataString())
                        .setExpiryTime(source5Matches.getExpiryTime())
                        .setInstallCooldownWindow(0L)
                        .setParentId(source5Matches.getId())
                        .setAggregateSource(new JSONObject().toString())
                        .build();
        Source source6NoFiltersIssue =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment2)
                        .setPriority(130L)
                        .setFilterData(buildNonMatchingFilterData())
                        .build();
        Source source7ExpiresBeforeTriggerTime =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId(enrollment2)
                        .setPriority(120L)
                        // Set it such that
                        .setEventTime(
                                trigger.getTriggerTime()
                                        - TimeUnit.SECONDS.toMillis(
                                                attributionConfig2.getSourceExpiryOverride())
                                        - 50L /* random value so that eventTime+override is before
                                              trigger time*/)
                        .build();

        // enrollmentX source - is not considered since to attribution config is present for this
        Source source8NoAttributionConfig =
                createValidSourceBuilder()
                        .setId(UUID.randomUUID().toString())
                        .setEnrollmentId("enrollmentX")
                        .setPriority(120L)
                        .setInstallCooldownWindow(0L)
                        .build();

        List<Source> expectedDerivedSources =
                Arrays.asList(
                        expectedDerivedSource1, expectedDerivedSource2, expectedDerivedSource5);
        Comparator<Source> sorter = Comparator.comparing(Source::getParentId);
        expectedDerivedSources.sort(sorter);

        // Execution
        XnaSourceCreator xnaSourceCreator = new XnaSourceCreator();
        List<Source> parentSources =
                Arrays.asList(
                        source1Matches,
                        source2Matches,
                        source3OutOfPriorityRange,
                        source4NonMatchingFilter,
                        source5Matches,
                        source6NoFiltersIssue,
                        source7ExpiresBeforeTriggerTime,
                        source8NoAttributionConfig);
        List<Source> actualDerivedSources =
                xnaSourceCreator.generateDerivedSources(trigger, parentSources);
        actualDerivedSources.sort(sorter);

        // Assertion
        assertEquals(expectedDerivedSources, actualDerivedSources);
    }

    private Source.Builder createValidSourceBuilder() {
        return new Source.Builder()
                .setEventId(SourceFixture.ValidSourceParams.SOURCE_EVENT_ID)
                .setPublisher(SourceFixture.ValidSourceParams.PUBLISHER)
                .setAppDestinations(SourceFixture.ValidSourceParams.ATTRIBUTION_DESTINATIONS)
                .setWebDestinations(SourceFixture.ValidSourceParams.WEB_DESTINATIONS)
                .setEnrollmentId(SourceFixture.ValidSourceParams.ENROLLMENT_ID)
                .setRegistrant(SourceFixture.ValidSourceParams.REGISTRANT)
                .setEventTime(SourceFixture.ValidSourceParams.SOURCE_EVENT_TIME)
                .setExpiryTime(SourceFixture.ValidSourceParams.EXPIRY_TIME)
                .setPriority(SourceFixture.ValidSourceParams.PRIORITY)
                .setSourceType(SourceFixture.ValidSourceParams.SOURCE_TYPE)
                .setInstallAttributionWindow(
                        SourceFixture.ValidSourceParams.INSTALL_ATTRIBUTION_WINDOW)
                .setInstallCooldownWindow(SourceFixture.ValidSourceParams.INSTALL_COOLDOWN_WINDOW)
                .setAttributionMode(SourceFixture.ValidSourceParams.ATTRIBUTION_MODE)
                .setAggregateSource(SourceFixture.ValidSourceParams.buildAggregateSource())
                .setFilterData(buildMatchingFilterData())
                .setIsDebugReporting(true)
                .setRegistrationId(SourceFixture.ValidSourceParams.REGISTRATION_ID)
                .setSharedAggregationKeys(SourceFixture.ValidSourceParams.SHARED_AGGREGATE_KEYS)
                .setInstallTime(SourceFixture.ValidSourceParams.INSTALL_TIME)
                .setRegistrationOrigin(SourceFixture.ValidSourceParams.REGISTRATION_ORIGIN);
    }

    private String buildMatchingFilterData() {
        try {
            JSONObject filterMap = new JSONObject();
            filterMap.put(
                    "conversion_subdomain",
                    new JSONArray(Collections.singletonList("electronics.megastore")));
            return filterMap.toString();
        } catch (JSONException e) {
            LogUtil.e("JSONException when building aggregate filter data.");
        }
        return null;
    }

    private String buildNonMatchingFilterData() {
        try {
            JSONObject filterMap = new JSONObject();
            filterMap.put(
                    "conversion_subdomain",
                    new JSONArray(Collections.singletonList("non.matching")));
            return filterMap.toString();
        } catch (JSONException e) {
            LogUtil.e("JSONException when building aggregate filter data.");
        }
        return null;
    }
}
