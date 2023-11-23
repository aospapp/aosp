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

package com.android.adservices.service.measurement.aggregation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import com.android.adservices.service.measurement.FilterMap;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.TriggerFixture;
import com.android.adservices.service.measurement.XNetworkData;
import com.android.adservices.service.measurement.util.UnsignedLong;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Unit tests for {@link AggregatePayloadGenerator} */
@SmallTest
public final class AggregatePayloadGeneratorTest {

    @Test
    public void testGenerateAttributionReport_twoContributions_filterSetMatches()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // First filter map does not match, second does.
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("unmatched"));
        triggerDataFilter1.put("ctid", Collections.singletonList("unmatched"));
        FilterMap filterMap1 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataFilter1)
                        .build();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter2 = new HashMap<>();
        triggerDataFilter2.put("product", Collections.singletonList("1234"));
        triggerDataFilter2.put("ctid", Collections.singletonList("id"));
        FilterMap filterMap2 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataFilter2)
                        .build();
        // First not-filter map matches, second does not.
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("product", Collections.singletonList("matches_when_negated"));
        FilterMap notFilterMap1 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataNotFilter1)
                        .build();
        Map<String, List<String>> triggerDataNotFilter2 = new HashMap<>();
        triggerDataNotFilter2.put("product", Collections.singletonList("234"));
        FilterMap notFilterMap2 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataNotFilter2)
                        .build();
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(filterMap1, filterMap2))
                        .setNotFilterSet(List.of(notFilterMap1, notFilterMap2))
                        .build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(1369L)).setValue(32768).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(2693L)).setValue(1664).build()));
    }

    @Test
    public void testGenerateAttributionReport_twoContributions_filterSetDoesNotMatch()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Filter maps do not match
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("unmatched"));
        triggerDataFilter1.put("ctid", Collections.singletonList("unmatched"));
        FilterMap filterMap1 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataFilter1)
                        .build();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter2 = new HashMap<>();
        triggerDataFilter2.put("conversion_subdomain", Collections.singletonList("unmatched"));
        FilterMap filterMap2 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataFilter2)
                        .build();
        // Not-filter maps do not match when negated
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("ctid", Collections.singletonList("id"));
        FilterMap notFilterMap1 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataNotFilter1)
                        .build();
        Map<String, List<String>> triggerDataNotFilter2 = new HashMap<>();
        triggerDataNotFilter2.put("product", Collections.singletonList("234"));
        FilterMap notFilterMap2 =
                new FilterMap.Builder()
                        .setAttributionFilterMap(triggerDataNotFilter2)
                        .build();
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(filterMap1, filterMap2))
                        .setNotFilterSet(List.of(notFilterMap1, notFilterMap2))
                        .build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(345L)).setValue(32768).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(2693L)).setValue(1664).build()));
    }

    @Test
    public void testGenerateAttributionReport_twoContributions_success() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("product", Collections.singletonList("100"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build()))
                        .setNotFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataNotFilter1).build())).build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(1369L)).setValue(32768).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(2693L)).setValue(1664).build()));
    }

    @Test
    public void testGenerateAttributionReport_ordersByAggregationKeyIds() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("product", Collections.singletonList("100"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build()))
                        .setNotFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataNotFilter1).build())).build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertEquals(BigInteger.valueOf(1369L), contributions.get(0).getKey());
        assertEquals(32768, contributions.get(0).getValue());
        assertEquals(BigInteger.valueOf(2693L), contributions.get(1).getKey());
        assertEquals(1664, contributions.get(1).getValue());
    }

    @Test
    public void testGenerateAttributionReport_matchingKeyOnlyInTriggerValues()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        aggregatableSource.put("thirdSource", BigInteger.valueOf(101L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();
        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("product", Collections.singletonList("100"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build()))
                        .setNotFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataNotFilter1).build()))
                        .build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        // "thirdSource" is matched although it appears in values but not in trigger data.
        values.put("thirdSource", 100);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 3);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(1369L)).setValue(32768).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(2693L)).setValue(1664).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(101L)).setValue(100).build()));
    }

    @Test
    public void testGenerateAttributionReportMoreTriggerDataSuccessfully() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(345L));
        aggregatableSource.put("geoValue", BigInteger.valueOf(5L));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        sourceFilterMap.put("ctid", Collections.singletonList("id"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();
        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        Map<String, List<String>> triggerDataNotFilter1 = new HashMap<>();
        triggerDataNotFilter1.put("product", Collections.singletonList("100"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(1024L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build()))
                        .setNotFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataNotFilter1).build()))
                        .build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2688L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("geoValue", "nonMatch")))
                        .build());
        // Apply this key_piece to "geoValue".
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(768L))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("geoValue")))
                        .build());
        // Don't apply this key_piece.
        Map<String, List<String>> triggerDataFilter2 = new HashMap<>();
        triggerDataFilter2.put("product", Collections.singletonList("0"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(200L))
                        .setSourceKeys(new HashSet<>(Arrays.asList("campaignCounts", "geoValue")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter2).build()))
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("geoValue", 1664);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(1369L)).setValue(32768).build()));
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(BigInteger.valueOf(2949L)).setValue(1664).build()));
    }

    @Test
    public void testGenerateAttributionReportWithHighBits() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(4L).shiftLeft(63));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build())).build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(new BigInteger("30000000000000000", 16)).setValue(32768).build()));
    }

    @Test
    public void testGenerateAttributionReportBinaryOrsKeys() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put("campaignCounts", BigInteger.valueOf(2L)
                .shiftLeft(63).add(BigInteger.valueOf(2L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put("conversion_subdomain",
                Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =  new FilterMap.Builder()
                .setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter).build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(List.of(new FilterMap.Builder()
                                .setAttributionFilterMap(triggerDataFilter1).build())).build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values).build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        .build();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(contributions.contains(
                new AggregateHistogramContribution.Builder()
                        .setKey(new BigInteger("10000000000000006", 16)).setValue(32768).build()));
    }

    @Test
    public void generateAttributionReport_xnaBitMap_binaryOrsKeys() throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put(
                "campaignCounts", BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(2L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put(
                "conversion_subdomain", Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =
                new FilterMap.Builder().setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter)
                        .build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(
                                new XNetworkData.Builder()
                                        .setKeyOffset(new UnsignedLong(12L))
                                        .build())
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values)
                        .build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        // Derived XNA source
                        .setParentId(UUID.randomUUID().toString())
                        .build();

        String adtechBitMapString =
                new JSONObject(Map.of(source.getEnrollmentId(), "0x2")).toString();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .setAdtechBitMapping(adtechBitMapString)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("10000000000002006", 16))
                                .setValue(32768)
                                .build()));
    }

    @Test
    public void generateAttributionReport_xnaBitMapPresentForSomeKeys_binaryOrsKeys()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put(
                "campaignCounts", BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(2L)));
        aggregatableSource.put(
                "key2", BigInteger.valueOf(2L).shiftLeft(55).add(BigInteger.valueOf(4L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put(
                "conversion_subdomain", Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =
                new FilterMap.Builder().setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter)
                        .build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(
                                new XNetworkData.Builder()
                                        .setKeyOffset(new UnsignedLong(12L))
                                        .build())
                        .build());
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(55).add(BigInteger.valueOf(8L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("key2")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(null)
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        values.put("key2", 16384);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values)
                        .build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        // Derived XNA source
                        .setParentId(UUID.randomUUID().toString())
                        .build();

        String adtechBitMapString =
                new JSONObject(Map.of(source.getEnrollmentId(), "0x2")).toString();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .setAdtechBitMapping(adtechBitMapString)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 2);
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("10000000000002006", 16))
                                .setValue(32768)
                                .build()));
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("10000000000000E", 16))
                                .setValue(16384)
                                .build()));
    }

    @Test
    public void generateAttributionReport_xnaBitMapWithoutBitmapping_doesNonXnaCalculation()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put(
                "campaignCounts", BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(2L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put(
                "conversion_subdomain", Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =
                new FilterMap.Builder().setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter)
                        .build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(
                                new XNetworkData.Builder()
                                        .setKeyOffset(new UnsignedLong(12L))
                                        .build())
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values)
                        .build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        // Derived XNA source
                        .setParentId(UUID.randomUUID().toString())
                        .build();

        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .setAdtechBitMapping(null)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("10000000000000006", 16))
                                .setValue(32768)
                                .build()));
    }

    @Test
    public void generateAttributionReport_xnaBitMapWithoutAdtechNetwork_doesNonXnaCalculation()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put(
                "campaignCounts", BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(2L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put(
                "conversion_subdomain", Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =
                new FilterMap.Builder().setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter)
                        .build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(null)
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values)
                        .build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        // Derived XNA source
                        .setParentId(UUID.randomUUID().toString())
                        .build();

        String adtechBitMapString =
                new JSONObject(Map.of(source.getEnrollmentId(), "0x2")).toString();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .setAdtechBitMapping(adtechBitMapString)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("10000000000000006", 16))
                                .setValue(32768)
                                .build()));
    }

    @Test
    public void generateAttributionReport_xnaBitMapWithoutOffset_fallbackToZeroOffset()
            throws JSONException {
        // Build AggregatableAttributionSource.
        TreeMap<String, BigInteger> aggregatableSource = new TreeMap<>();
        aggregatableSource.put(
                "campaignCounts", BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(2L)));
        Map<String, List<String>> sourceFilterMap = new HashMap<>();
        sourceFilterMap.put(
                "conversion_subdomain", Collections.singletonList("electronics.megastore"));
        sourceFilterMap.put("product", Arrays.asList("1234", "234"));
        FilterMap sourceFilter =
                new FilterMap.Builder().setAttributionFilterMap(sourceFilterMap).build();
        AggregatableAttributionSource attributionSource =
                new AggregatableAttributionSource.Builder()
                        .setAggregatableSource(aggregatableSource)
                        .setFilterMap(sourceFilter)
                        .build();

        // Build AggregatableAttributionTrigger.
        List<AggregateTriggerData> triggerDataList = new ArrayList<>();
        // Apply this key_piece to "campaignCounts".
        Map<String, List<String>> triggerDataFilter1 = new HashMap<>();
        triggerDataFilter1.put("product", Collections.singletonList("1234"));
        triggerDataFilter1.put("ctid", Collections.singletonList("id"));
        triggerDataList.add(
                new AggregateTriggerData.Builder()
                        .setKey(BigInteger.valueOf(2L).shiftLeft(63).add(BigInteger.valueOf(4L)))
                        .setSourceKeys(new HashSet<>(Collections.singletonList("campaignCounts")))
                        .setFilterSet(
                                List.of(
                                        new FilterMap.Builder()
                                                .setAttributionFilterMap(triggerDataFilter1)
                                                .build()))
                        .setXNetworkData(new XNetworkData.Builder().setKeyOffset(null).build())
                        .build());

        Map<String, Integer> values = new HashMap<>();
        values.put("campaignCounts", 32768);
        AggregatableAttributionTrigger attributionTrigger =
                new AggregatableAttributionTrigger.Builder()
                        .setTriggerData(triggerDataList)
                        .setValues(values)
                        .build();

        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setAggregatableAttributionSource(attributionSource)
                        // Derived XNA source
                        .setParentId(UUID.randomUUID().toString())
                        .build();

        String adtechBitMapString =
                new JSONObject(Map.of(source.getEnrollmentId(), "0x8")).toString();
        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setAggregatableAttributionTrigger(attributionTrigger)
                        .setAdtechBitMapping(adtechBitMapString)
                        .build();
        Optional<List<AggregateHistogramContribution>> aggregateHistogramContributions =
                AggregatePayloadGenerator.generateAttributionReport(source, trigger);
        assertTrue(aggregateHistogramContributions.isPresent());
        List<AggregateHistogramContribution> contributions = aggregateHistogramContributions.get();

        assertEquals(contributions.size(), 1);
        assertTrue(
                contributions.contains(
                        new AggregateHistogramContribution.Builder()
                                .setKey(new BigInteger("1000000000000000E", 16))
                                .setValue(32768)
                                .build()));
    }
}
