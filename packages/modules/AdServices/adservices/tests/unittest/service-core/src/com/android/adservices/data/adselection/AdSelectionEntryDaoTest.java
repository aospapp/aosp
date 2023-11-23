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

package com.android.adservices.data.adselection;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.adservices.adselection.CustomAudienceSignalsFixture;
import android.adservices.adselection.ReportInteractionRequest;
import android.adservices.common.AdDataFixture;
import android.adservices.common.CommonFixture;
import android.content.Context;
import android.net.Uri;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

public class AdSelectionEntryDaoTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    private static final String BUYER_DECISION_LOGIC_JS_1 =
            "function test() { return \"hello world 1\"; }";
    private static final String BUYER_DECISION_LOGIC_JS_2 =
            "function test() { return \"hello world 2\"; }";

    private static final Uri BIDDING_LOGIC_URI_1 = Uri.parse("http://www.domain.com/logic/1");
    private static final Uri BIDDING_LOGIC_URI_2 = Uri.parse("http://www.domain.com/logic/2");
    private static final Uri BIDDING_LOGIC_URI_3 = Uri.parse("http://www.domain.com/logic/3");

    private static final Uri RENDER_URI = Uri.parse("http://www.domain.com/advert/");

    private static final Instant ACTIVATION_TIME = CLOCK.instant().truncatedTo(ChronoUnit.MILLIS);

    private static final long AD_SELECTION_ID_1 = 1;
    private static final long AD_SELECTION_ID_2 = 2;
    private static final long AD_SELECTION_ID_3 = 3;
    private static final long AD_SELECTION_ID_4 = 4;
    private static final String CONTEXTUAL_SIGNALS = "contextual_signals";

    private static final double BID = 5;

    private static final String CALLER_PACKAGE_NAME_1 = "callerPackageName1";
    private static final String CALLER_PACKAGE_NAME_2 = "callerPackageName2";

    private static final DBBuyerDecisionLogic DB_BUYER_DECISION_LOGIC_1 =
            new DBBuyerDecisionLogic.Builder()
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_1)
                    .setBuyerDecisionLogicJs(BUYER_DECISION_LOGIC_JS_1)
                    .build();

    private static final DBBuyerDecisionLogic DB_BUYER_DECISION_LOGIC_2 =
            new DBBuyerDecisionLogic.Builder()
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_2)
                    .setBuyerDecisionLogicJs(BUYER_DECISION_LOGIC_JS_2)
                    .build();

    public static final CustomAudienceSignals CUSTOM_AUDIENCE_SIGNALS =
            CustomAudienceSignalsFixture.aCustomAudienceSignals();

    public static final DBAdSelection DB_AD_SELECTION_1 =
            new DBAdSelection.Builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setCustomAudienceSignals(CUSTOM_AUDIENCE_SIGNALS)
                    .setContextualSignals(CONTEXTUAL_SIGNALS)
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_1)
                    .setWinningAdRenderUri(RENDER_URI)
                    .setWinningAdBid(BID)
                    .setCreationTimestamp(ACTIVATION_TIME)
                    .setCallerPackageName(CALLER_PACKAGE_NAME_1)
                    .build();

    public static final DBAdSelection DB_AD_SELECTION_WITH_AD_COUNTER_KEYS =
            new DBAdSelection.Builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setCustomAudienceSignals(CUSTOM_AUDIENCE_SIGNALS)
                    .setContextualSignals(CONTEXTUAL_SIGNALS)
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_1)
                    .setWinningAdRenderUri(RENDER_URI)
                    .setWinningAdBid(BID)
                    .setCreationTimestamp(ACTIVATION_TIME)
                    .setCallerPackageName(CALLER_PACKAGE_NAME_1)
                    .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                    .build();

    public static final DBAdSelection DB_AD_SELECTION_2 =
            new DBAdSelection.Builder()
                    .setAdSelectionId(AD_SELECTION_ID_2)
                    .setCustomAudienceSignals(CUSTOM_AUDIENCE_SIGNALS)
                    .setContextualSignals(CONTEXTUAL_SIGNALS)
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_2)
                    .setWinningAdRenderUri(RENDER_URI)
                    .setWinningAdBid(BID)
                    .setCreationTimestamp(ACTIVATION_TIME)
                    .setCallerPackageName(CALLER_PACKAGE_NAME_2)
                    .build();

    public static final DBAdSelection DB_AD_CONTEXTUAL_AD_SELECTION =
            new DBAdSelection.Builder()
                    .setAdSelectionId(AD_SELECTION_ID_3)
                    .setContextualSignals(CONTEXTUAL_SIGNALS)
                    .setBiddingLogicUri(BIDDING_LOGIC_URI_3)
                    .setWinningAdRenderUri(RENDER_URI)
                    .setWinningAdBid(BID)
                    .setCreationTimestamp(ACTIVATION_TIME)
                    .setCallerPackageName(CALLER_PACKAGE_NAME_1)
                    .build();

    private static final String AD_SELECTION_CONFIG_ID_1 = "1";
    private static final String DECISION_LOGIC_JS_1 =
            "function test() { return \"hello world_1\"; }";
    private static final String TRUSTED_SCORING_SIGNALS_1 =
            "{\n"
                    + "\t\"render_uri_1\": \"signals_for_1_1\",\n"
                    + "\t\"render_uri_2\": \"signals_for_1_2\"\n"
                    + "}";
    public static final DBAdSelectionOverride DB_AD_SELECTION_OVERRIDE_1 =
            DBAdSelectionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_1)
                    .setAppPackageName(CALLER_PACKAGE_NAME_1)
                    .setDecisionLogicJS(DECISION_LOGIC_JS_1)
                    .setTrustedScoringSignals(TRUSTED_SCORING_SIGNALS_1)
                    .build();

    private static final String AD_SELECTION_CONFIG_ID_2 = "2";
    private static final String DECISION_LOGIC_JS_2 =
            "function test() { return \"hello world_2\"; }";
    private static final String TRUSTED_SCORING_SIGNALS_2 =
            "{\n"
                    + "\t\"render_uri_1\": \"signals_for_2_1\",\n"
                    + "\t\"render_uri_2\": \"signals_for_2_2\"\n"
                    + "}";
    public static final DBAdSelectionOverride DB_AD_SELECTION_OVERRIDE_2 =
            DBAdSelectionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_2)
                    .setAppPackageName(CALLER_PACKAGE_NAME_2)
                    .setDecisionLogicJS(DECISION_LOGIC_JS_2)
                    .setTrustedScoringSignals(TRUSTED_SCORING_SIGNALS_2)
                    .build();

    private static final String DECISION_LOGIC_JS_3 =
            "function test() { return \"hello world_3\"; }";
    private static final String TRUSTED_SCORING_SIGNALS_3 =
            "{\n"
                    + "\t\"render_uri_1\": \"signals_for_3_1\",\n"
                    + "\t\"render_uri_2\": \"signals_for_3_2\"\n"
                    + "}";
    public static final DBAdSelectionOverride DB_AD_SELECTION_OVERRIDE_3 =
            DBAdSelectionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_1)
                    .setAppPackageName(CALLER_PACKAGE_NAME_1)
                    .setDecisionLogicJS(DECISION_LOGIC_JS_3)
                    .setTrustedScoringSignals(TRUSTED_SCORING_SIGNALS_3)
                    .build();

    private static final String AD_SELECTION_CONFIG_ID_4 = "4";
    private static final String DECISION_LOGIC_JS_4 =
            "function test() { return \"hello world_4\"; }";
    private static final String TRUSTED_SCORING_SIGNALS_4 =
            "{\n"
                    + "\t\"render_uri_1\": \"signals_for_4_1\",\n"
                    + "\t\"render_uri_2\": \"signals_for_4_2\"\n"
                    + "}";
    public static final DBAdSelectionOverride DB_AD_SELECTION_OVERRIDE_4 =
            DBAdSelectionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_4)
                    .setAppPackageName(CALLER_PACKAGE_NAME_1)
                    .setDecisionLogicJS(DECISION_LOGIC_JS_4)
                    .setTrustedScoringSignals(TRUSTED_SCORING_SIGNALS_4)
                    .build();

    private static final DBBuyerDecisionOverride DB_BUYER_DECISION_OVERRIDE_1 =
            DBBuyerDecisionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_1)
                    .setAppPackageName(CALLER_PACKAGE_NAME_1)
                    .setBuyer(CommonFixture.VALID_BUYER_1)
                    .setDecisionLogic(BUYER_DECISION_LOGIC_JS_1)
                    .build();

    private static final DBBuyerDecisionOverride DB_BUYER_DECISION_OVERRIDE_2 =
            DBBuyerDecisionOverride.builder()
                    .setAdSelectionConfigId(AD_SELECTION_CONFIG_ID_1)
                    .setAppPackageName(CALLER_PACKAGE_NAME_1)
                    .setBuyer(CommonFixture.VALID_BUYER_2)
                    .setDecisionLogic(BUYER_DECISION_LOGIC_JS_2)
                    .build();

    private static final ImmutableList<DBBuyerDecisionOverride> DB_BUYER_DECISION_OVERRIDES =
            ImmutableList.of(DB_BUYER_DECISION_OVERRIDE_1, DB_BUYER_DECISION_OVERRIDE_2);

    // Event registering constants
    private static final int BUYER_DESTINATION =
            ReportInteractionRequest.FLAG_REPORTING_DESTINATION_BUYER;
    private static final int SELLER_DESTINATION =
            ReportInteractionRequest.FLAG_REPORTING_DESTINATION_SELLER;

    private static final String CLICK_EVENT = "click";
    private static final String HOVER_EVENT = "hover";
    private static final String HOLD_EVENT = "hold";

    private static final String SELLER_BASE_URI = "https://www.seller.com/";
    private static final String BUYER_BASE_URI = "https://www.buyer.com/";
    private static final String DIFFERENT_BASE_URI = "https://www.different.com/";

    private static final Uri SELLER_CLICK_URI = Uri.parse(SELLER_BASE_URI + CLICK_EVENT);
    private static final Uri SELLER_HOVER_URI = Uri.parse(SELLER_BASE_URI + HOVER_EVENT);
    private static final Uri BUYER_CLICK_URI = Uri.parse(BUYER_BASE_URI + CLICK_EVENT);

    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_CLICK_1 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setInteractionKey(CLICK_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_CLICK_URI)
                    .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_HOVER_1 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setInteractionKey(HOVER_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_HOVER_URI)
                    .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_HOLD_1 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setInteractionKey(HOLD_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_HOVER_URI)
                    .build();
    private static final DBRegisteredAdInteraction
            DB_REGISTERED_INTERACTION_SELLER_1_DIFFERENT_URI =
                    DBRegisteredAdInteraction.builder()
                            .setAdSelectionId(AD_SELECTION_ID_1)
                            .setInteractionKey(CLICK_EVENT)
                            .setDestination(SELLER_DESTINATION)
                            .setInteractionReportingUri(Uri.parse(DIFFERENT_BASE_URI + CLICK_EVENT))
                            .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_CLICK_2 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_2)
                    .setInteractionKey(CLICK_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_CLICK_URI)
                    .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_HOVER_2 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_2)
                    .setInteractionKey(HOVER_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_HOVER_URI)
                    .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_CLICK_3 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_3)
                    .setInteractionKey(CLICK_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_CLICK_URI)
                    .build();
    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_SELLER_HOVER_3 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_3)
                    .setInteractionKey(HOVER_EVENT)
                    .setDestination(SELLER_DESTINATION)
                    .setInteractionReportingUri(SELLER_HOVER_URI)
                    .build();

    private static final DBRegisteredAdInteraction DB_REGISTERED_INTERACTION_BUYER_1 =
            DBRegisteredAdInteraction.builder()
                    .setAdSelectionId(AD_SELECTION_ID_1)
                    .setInteractionKey(CLICK_EVENT)
                    .setDestination(BUYER_DESTINATION)
                    .setInteractionReportingUri(BUYER_CLICK_URI)
                    .build();

    private AdSelectionEntryDao mAdSelectionEntryDao;

    @Before
    public void setup() {
        mAdSelectionEntryDao =
                Room.inMemoryDatabaseBuilder(CONTEXT, AdSelectionDatabase.class)
                        .build()
                        .adSelectionEntryDao();
    }

    @Test
    public void testReturnsTrueIfAdSelectionConfigIdExists() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));

        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_2);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));
    }

    @Test
    public void testReturnsFalseIfAdSelectionConfigIdExistsDifferentPackageName() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_2));
    }

    @Test
    public void testDeletesByAdSelectionConfigId() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_2);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));

        mAdSelectionEntryDao.removeAdSelectionOverrideByIdAndPackageName(
                AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));
    }

    @Test
    public void testDoesNotDeleteWithIncorrectPackageName() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));

        mAdSelectionEntryDao.removeAdSelectionOverrideByIdAndPackageName(
                AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_2);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
    }

    @Test
    public void testDeletesAllAdSelectionOverrides() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_2);
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_4);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_4, CALLER_PACKAGE_NAME_1));

        mAdSelectionEntryDao.removeAllAdSelectionOverrides(CALLER_PACKAGE_NAME_1);

        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));
        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_4, CALLER_PACKAGE_NAME_1));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));
    }

    @Test
    public void testGetAdSelectionOverrideExists() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));

        String decisionLogicJS =
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        String trustedScoringSignals =
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertEquals(DECISION_LOGIC_JS_1, decisionLogicJS);
        assertEquals(TRUSTED_SCORING_SIGNALS_1, trustedScoringSignals);
    }

    @Test
    public void testGetAdSelectionOverrideExistsIgnoresOverridesByDifferentApp() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_2));
    }

    @Test
    public void testCorrectlyOverridesAdSelectionOverride() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));

        String decisionLogicJS_1 =
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);
        String trustedScoringSignals_1 =
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertEquals(DECISION_LOGIC_JS_1, decisionLogicJS_1);
        assertEquals(TRUSTED_SCORING_SIGNALS_1, trustedScoringSignals_1);

        // Persisting with same AdSelectionConfigId but different decisionLogicJS
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_3);

        String decisionLogicJS_3 =
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);
        String trustedScoringSignals_3 =
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertEquals(DECISION_LOGIC_JS_3, decisionLogicJS_3);
        assertEquals(TRUSTED_SCORING_SIGNALS_3, trustedScoringSignals_3);
    }

    @Test
    public void testCorrectlyGetsBothAdSelectionOverrides() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_2);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1));

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionOverrideExistForPackageName(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2));

        String decisionLogicJS_1 =
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);
        String trustedScoringSignals_1 =
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertEquals(DECISION_LOGIC_JS_1, decisionLogicJS_1);
        assertEquals(TRUSTED_SCORING_SIGNALS_1, trustedScoringSignals_1);

        String decisionLogicJS_2 =
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2);
        String trustedScoringSignals_2 =
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_2, CALLER_PACKAGE_NAME_2);

        assertEquals(DECISION_LOGIC_JS_2, decisionLogicJS_2);
        assertEquals(TRUSTED_SCORING_SIGNALS_2, trustedScoringSignals_2);
    }

    @Test
    public void testAdSelectionOverridesDoneByOtherAppsAreIgnored() {
        mAdSelectionEntryDao.persistAdSelectionOverride(DB_AD_SELECTION_OVERRIDE_1);
        assertNull(
                mAdSelectionEntryDao.getDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_2));
        assertNull(
                mAdSelectionEntryDao.getTrustedScoringSignalsOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_2));
    }

    @Test(expected = NullPointerException.class)
    public void testPersistNullAdSelectionOverride() {
        mAdSelectionEntryDao.persistAdSelectionOverride(null);
    }

    @Test
    public void testReturnsTrueIfAdSelectionIdExists() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));

        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_2);

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));
    }

    @Test
    public void testDeletesByAdSelectionId() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_2);

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));

        mAdSelectionEntryDao.removeAdSelectionEntriesByIds(List.of(AD_SELECTION_ID_1));

        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));
    }

    @Test
    public void testDeletesByAdSelectionIdNotExist() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));

        mAdSelectionEntryDao.removeAdSelectionEntriesByIds(List.of(AD_SELECTION_ID_2));

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
    }

    @Test
    public void testDeletesByMultipleAdSelectionIds() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_2);

        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertTrue(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));

        mAdSelectionEntryDao.removeAdSelectionEntriesByIds(
                List.of(AD_SELECTION_ID_1, AD_SELECTION_ID_2));

        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_1));
        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));
    }

    @Test(expected = NullPointerException.class)
    public void testPersistNullAdSelectionEntry() {
        mAdSelectionEntryDao.persistAdSelection(null);
    }

    @Test
    public void testReturnsFalseIfAdSelectionIdDoesNotExist() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);

        assertFalse(mAdSelectionEntryDao.doesAdSelectionIdExist(AD_SELECTION_ID_2));
    }

    @Test
    public void testGetsAdSelectionEntryExistsContextualAd() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_CONTEXTUAL_AD_SELECTION);

        DBAdSelectionEntry adSelectionEntry =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_3);
        DBAdSelectionEntry expected = toAdSelectionEntry(DB_AD_CONTEXTUAL_AD_SELECTION);

        assertEquals(adSelectionEntry, expected);
    }

    @Test
    public void testGetsAdSelectionEntryExistsAndDifferentBuyerDecisionLogicExists() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_CONTEXTUAL_AD_SELECTION);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_2);

        DBAdSelectionEntry adSelectionEntry =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_3);
        DBAdSelectionEntry expected = toAdSelectionEntry(DB_AD_CONTEXTUAL_AD_SELECTION);

        assertEquals(adSelectionEntry, expected);
    }

    @Test
    public void testGetsAdSelectionEntryExistsAndBuyerDecisionLogicExists() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);

        DBAdSelectionEntry adSelectionEntry =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_1);
        DBAdSelectionEntry expected =
                toAdSelectionEntry(DB_AD_SELECTION_1, DB_BUYER_DECISION_LOGIC_1);

        assertEquals(adSelectionEntry, expected);
    }

    @Test
    public void testGetsAdSelectionEntryExistsAndMultipleBuyerDecisionLogicExist() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_2);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_2);

        DBAdSelectionEntry adSelectionEntry1 =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_1);
        DBAdSelectionEntry expected1 =
                toAdSelectionEntry(DB_AD_SELECTION_1, DB_BUYER_DECISION_LOGIC_1);
        assertEquals(adSelectionEntry1, expected1);

        DBAdSelectionEntry adSelectionEntry2 =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_2);
        DBAdSelectionEntry expected2 =
                toAdSelectionEntry(DB_AD_SELECTION_2, DB_BUYER_DECISION_LOGIC_2);
        assertEquals(adSelectionEntry2, expected2);
    }

    @Test
    public void testJoinsWithCorrectBuyerDecisionLogic() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_2);

        DBAdSelectionEntry adSelectionEntry =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_1);
        DBAdSelectionEntry expected =
                toAdSelectionEntry(DB_AD_SELECTION_1, DB_BUYER_DECISION_LOGIC_1);

        assertEquals(adSelectionEntry, expected);
    }

    /**
     * Tests that if two decision logic inserts are made with the same URI, the second overwrites
     * the first.
     */
    @Test
    public void testOverwriteDecisionLogic() {
        DBBuyerDecisionLogic firstEntry = DB_BUYER_DECISION_LOGIC_1;
        DBBuyerDecisionLogic secondEntry =
                new DBBuyerDecisionLogic.Builder()
                        .setBiddingLogicUri(DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri())
                        .setBuyerDecisionLogicJs(
                                DB_BUYER_DECISION_LOGIC_2.getBuyerDecisionLogicJs())
                        .build();
        mAdSelectionEntryDao.persistBuyerDecisionLogic(firstEntry);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(secondEntry);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);

        DBAdSelectionEntry adSelectionEntry =
                mAdSelectionEntryDao.getAdSelectionEntityById(AD_SELECTION_ID_1);
        DBAdSelectionEntry expected =
                toAdSelectionEntry(DB_AD_SELECTION_1, secondEntry);

        assertEquals(adSelectionEntry, expected);
    }

    @Test
    public void testRemoveExpiredAdSelection() {
        DBAdSelection expiredDBAdSelection =
                new DBAdSelection.Builder()
                        .setAdSelectionId(AD_SELECTION_ID_4)
                        .setCustomAudienceSignals(CUSTOM_AUDIENCE_SIGNALS)
                        .setContextualSignals(CONTEXTUAL_SIGNALS)
                        .setBiddingLogicUri(BIDDING_LOGIC_URI_1)
                        .setWinningAdRenderUri(RENDER_URI)
                        .setWinningAdBid(BID)
                        .setCreationTimestamp(ACTIVATION_TIME.minusSeconds(10))
                        .setCallerPackageName(CALLER_PACKAGE_NAME_1)
                        .build();

        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistAdSelection(expiredDBAdSelection);

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionIdExist(DB_AD_SELECTION_1.getAdSelectionId()));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionIdExist(
                        expiredDBAdSelection.getAdSelectionId()));

        mAdSelectionEntryDao.removeExpiredAdSelection(ACTIVATION_TIME.minusSeconds(5));

        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionIdExist(DB_AD_SELECTION_1.getAdSelectionId()));
        assertFalse(
                mAdSelectionEntryDao.doesAdSelectionIdExist(
                        expiredDBAdSelection.getAdSelectionId()));
    }

    @Test
    public void testDoesBuyerDecisionLogicExist() {
        assertFalse(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri()));

        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);

        assertTrue(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri()));
    }

    @Test
    public void testGetAdSelectionEntitiesFilteredByCallerPackageName() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_2); // different caller package name
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_2);

        List<DBAdSelectionEntry> adSelectionEntries =
                mAdSelectionEntryDao.getAdSelectionEntities(
                        Collections.singletonList(AD_SELECTION_ID_1), CALLER_PACKAGE_NAME_1);
        DBAdSelectionEntry expected =
                toAdSelectionEntry(DB_AD_SELECTION_1, DB_BUYER_DECISION_LOGIC_1);

        assertEquals(1, adSelectionEntries.size());
        assertEquals(adSelectionEntries.get(0), expected);
    }

    @Test
    public void testRemoveExpiredBuyerDecisionLogic() {
        assertFalse(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri()));
        assertFalse(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_2.getBiddingLogicUri()));

        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_1);
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        mAdSelectionEntryDao.persistBuyerDecisionLogic(DB_BUYER_DECISION_LOGIC_2);

        assertTrue(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri()));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionIdExist(DB_AD_SELECTION_1.getAdSelectionId()));
        assertTrue(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_2.getBiddingLogicUri()));

        mAdSelectionEntryDao.removeExpiredBuyerDecisionLogic();

        assertTrue(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_1.getBiddingLogicUri()));
        assertTrue(
                mAdSelectionEntryDao.doesAdSelectionIdExist(DB_AD_SELECTION_1.getAdSelectionId()));

        // DB_BUYER_DECISION_LOGIC_2 will be removed because there is no instance of
        // DB_BUYER_DECISION_LOGIC_2.getBiddingLogicUri() in the DBAdSelection table
        assertFalse(
                mAdSelectionEntryDao.doesBuyerDecisionLogicExist(
                        DB_BUYER_DECISION_LOGIC_2.getBiddingLogicUri()));
    }

    @Test
    public void testReturnsTrueIfRegisteredEventExists() {
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, BUYER_DESTINATION));

        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(DB_REGISTERED_INTERACTION_SELLER_CLICK_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, BUYER_DESTINATION));
    }

    @Test
    public void testGetsCorrectEventUri() {
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, BUYER_DESTINATION));

        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_BUYER_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, BUYER_DESTINATION));

        // Asserts seller uri is returned
        assertEquals(
                SELLER_CLICK_URI,
                mAdSelectionEntryDao.getRegisteredAdInteractionUri(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        // Asserts buyer uri is returned
        assertEquals(
                BUYER_CLICK_URI,
                mAdSelectionEntryDao.getRegisteredAdInteractionUri(
                        AD_SELECTION_ID_1, CLICK_EVENT, BUYER_DESTINATION));
    }

    @Test
    public void testUpdatesEventUriIfPrimaryKeySame() {
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(DB_REGISTERED_INTERACTION_SELLER_CLICK_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        // Asserts seller uri is returned
        assertEquals(
                SELLER_CLICK_URI,
                mAdSelectionEntryDao.getRegisteredAdInteractionUri(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        // Overwrite primary key with another uri
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(DB_REGISTERED_INTERACTION_SELLER_1_DIFFERENT_URI));

        // Asserts different uri is returned
        assertEquals(
                Uri.parse(DIFFERENT_BASE_URI + CLICK_EVENT),
                mAdSelectionEntryDao.getRegisteredAdInteractionUri(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
    }

    @Test
    public void testClearsExpiredRegisteredEventsData() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);

        // Added registered event data with same adSelectionId as DB_AD_SELECTION_1
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        // Simulating stale registered event data by inserting data with different adSelectionIds
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_2,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_2,
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_3,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_3));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, CLICK_EVENT, SELLER_DESTINATION));
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, HOVER_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_3, CLICK_EVENT, SELLER_DESTINATION));
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_3, HOVER_EVENT, SELLER_DESTINATION));

        mAdSelectionEntryDao.removeExpiredRegisteredAdInteractions();

        // Assert that stale registered event data was cleared
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, CLICK_EVENT, SELLER_DESTINATION));
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, HOVER_EVENT, SELLER_DESTINATION));

        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_3, CLICK_EVENT, SELLER_DESTINATION));
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_3, HOVER_EVENT, SELLER_DESTINATION));

        // Assert that non-stale data was not cleared
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));
    }

    @Test
    public void testGetNumRegisteredAdInteractions() {
        // Nothing inserted yet, should return 0
        assertEquals(0, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        // Insert 2 registered ad interactions
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertEquals(2, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        // Insert 4 more registered ad interactions
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_2,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_2,
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_3,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_3));

        assertEquals(6, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        mAdSelectionEntryDao.removeExpiredRegisteredAdInteractions();

        // Everything is cleared since no ad selection ids registered
        assertEquals(0, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());
    }

    @Test
    public void testSafelyInsertRegisteredAdInteractionsDoesNotInsertWhenTableIsFull() {
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        int maxTableSize = 2;
        int maxSizePerDestination = 10;

        assertEquals(maxTableSize, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        mAdSelectionEntryDao.safelyInsertRegisteredAdInteractions(
                AD_SELECTION_ID_2,
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_2,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_2),
                maxTableSize,
                maxSizePerDestination,
                SELLER_DESTINATION);

        // Assert new interactions were not registered
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, CLICK_EVENT, SELLER_DESTINATION));

        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, HOVER_EVENT, SELLER_DESTINATION));

        // Assert old interactions are still registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        assertEquals(maxTableSize, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());
    }

    @Test
    public void testSafelyInsertRegisteredAdInteractionsOnlyInsertsTillTableIsFull() {
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        int maxTableSize = 3;
        int maxSizePerDestination = 10;

        assertEquals(2, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        mAdSelectionEntryDao.safelyInsertRegisteredAdInteractions(
                AD_SELECTION_ID_2,
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_2,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_2),
                maxTableSize,
                maxSizePerDestination,
                SELLER_DESTINATION);

        // Assert only first interaction is registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, CLICK_EVENT, SELLER_DESTINATION));

        // Assume next interaction is not registered
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_2, HOVER_EVENT, SELLER_DESTINATION));

        // Assert old events are still registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        assertEquals(maxTableSize, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());
    }

    @Test
    public void testSafelyInsertRegisteredAdInteractionsDoesNotInsertAtMaxNumPerDestination() {
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        int maxTableSize = 10;
        int maxSizePerDestination = 2;

        assertEquals(
                maxSizePerDestination, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        mAdSelectionEntryDao.safelyInsertRegisteredAdInteractions(
                AD_SELECTION_ID_1,
                ImmutableList.of(DB_REGISTERED_INTERACTION_SELLER_HOLD_1),
                maxTableSize,
                maxSizePerDestination,
                SELLER_DESTINATION);

        // Assert new interaction was not registered
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOLD_EVENT, SELLER_DESTINATION));

        // Assert old interactions are still registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        assertEquals(
                maxSizePerDestination, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());
    }

    @Test
    public void
            testSafelyInsertRegisteredAdInteractionsOnlyInsertsTillMaxPerDestinationIsReached() {
        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(DB_REGISTERED_INTERACTION_SELLER_CLICK_1));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        int maxTableSize = 10;
        int maxSizePerDestination = 2;

        assertEquals(1, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());

        mAdSelectionEntryDao.safelyInsertRegisteredAdInteractions(
                AD_SELECTION_ID_1,
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOLD_1),
                maxTableSize,
                maxSizePerDestination,
                SELLER_DESTINATION);

        // Assert only first 2 interactions are registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        // Assert next interaction was not registered
        assertFalse(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOLD_EVENT, SELLER_DESTINATION));

        // Assert old interactions are still registered
        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, CLICK_EVENT, SELLER_DESTINATION));

        assertTrue(
                mAdSelectionEntryDao.doesRegisteredAdInteractionExist(
                        AD_SELECTION_ID_1, HOVER_EVENT, SELLER_DESTINATION));

        assertEquals(
                maxSizePerDestination, mAdSelectionEntryDao.getTotalNumRegisteredAdInteractions());
    }

    @Test
    public void testGetNumRegisteredAdInteractionsPerAdSelectionAndDestination() {
        assertEquals(
                0,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_1, SELLER_DESTINATION));
        assertEquals(
                0,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_1, BUYER_DESTINATION));
        assertEquals(
                0,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_2, SELLER_DESTINATION));

        mAdSelectionEntryDao.persistDBRegisteredAdInteractions(
                ImmutableList.of(
                        DB_REGISTERED_INTERACTION_SELLER_CLICK_1,
                        DB_REGISTERED_INTERACTION_SELLER_HOVER_1));

        assertEquals(
                2,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_1, SELLER_DESTINATION));

        // Expect still to return 0 since no buyer destinations are registered
        assertEquals(
                0,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_1, BUYER_DESTINATION));

        // Expect still to return 0 since nothing with AD_SELECTION_2 was registered
        assertEquals(
                0,
                mAdSelectionEntryDao.getNumRegisteredAdInteractionsPerAdSelectionAndDestination(
                        AD_SELECTION_ID_2, SELLER_DESTINATION));
    }

    @Test
    public void testGetMissingAdSelectionHistogramInfo() {
        assertThat(
                        mAdSelectionEntryDao.getAdSelectionHistogramInfo(
                                DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getAdSelectionId(),
                                DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getCallerPackageName()))
                .isNull();
    }

    @Test
    public void testGetAdSelectionHistogramInfoWithNullAdCounterKeys() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_1);
        assertThat(
                        mAdSelectionEntryDao.doesAdSelectionIdExist(
                                DB_AD_SELECTION_1.getAdSelectionId()))
                .isTrue();

        DBAdSelectionHistogramInfo histogramInfo =
                mAdSelectionEntryDao.getAdSelectionHistogramInfo(
                        DB_AD_SELECTION_1.getAdSelectionId(),
                        DB_AD_SELECTION_1.getCallerPackageName());
        assertThat(histogramInfo).isNotNull();
        assertThat(histogramInfo.getBuyer())
                .isEqualTo(DB_AD_SELECTION_1.getCustomAudienceSignals().getBuyer());
        assertThat(histogramInfo.getAdCounterKeys()).isNull();
    }

    @Test
    public void testGetAdSelectionHistogramInfo() {
        mAdSelectionEntryDao.persistAdSelection(DB_AD_SELECTION_WITH_AD_COUNTER_KEYS);
        assertThat(
                        mAdSelectionEntryDao.doesAdSelectionIdExist(
                                DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getAdSelectionId()))
                .isTrue();

        DBAdSelectionHistogramInfo histogramInfo =
                mAdSelectionEntryDao.getAdSelectionHistogramInfo(
                        DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getAdSelectionId(),
                        DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getCallerPackageName());
        assertThat(histogramInfo).isNotNull();
        assertThat(histogramInfo.getBuyer())
                .isEqualTo(
                        DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getCustomAudienceSignals().getBuyer());
        assertThat(histogramInfo.getAdCounterKeys()).isNotNull();
        assertThat(histogramInfo.getAdCounterKeys())
                .containsExactlyElementsIn(DB_AD_SELECTION_WITH_AD_COUNTER_KEYS.getAdCounterKeys());
    }

    @Test
    public void testPersistBuyerDecisionLogicOverrides() {
        mAdSelectionEntryDao.persistBuyersDecisionLogicOverride(DB_BUYER_DECISION_OVERRIDES);

        List<DBBuyerDecisionOverride> overrides =
                mAdSelectionEntryDao.getBuyersDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertThat(overrides).containsExactlyElementsIn(DB_BUYER_DECISION_OVERRIDES);
    }

    @Test
    public void testRemoveBuyerDecisionLogicOverrides() {
        mAdSelectionEntryDao.persistBuyersDecisionLogicOverride(DB_BUYER_DECISION_OVERRIDES);

        List<DBBuyerDecisionOverride> overrides =
                mAdSelectionEntryDao.getBuyersDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertThat(overrides).containsExactlyElementsIn(DB_BUYER_DECISION_OVERRIDES);

        mAdSelectionEntryDao.removeBuyerDecisionLogicOverrideByIdAndPackageName(
                AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertThat(
                        mAdSelectionEntryDao.getBuyersDecisionLogicOverride(
                                AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1))
                .isEmpty();
    }

    @Test
    public void testRemoveAllBuyerDecisionLogicOverrides() {
        mAdSelectionEntryDao.persistBuyersDecisionLogicOverride(DB_BUYER_DECISION_OVERRIDES);

        List<DBBuyerDecisionOverride> overrides =
                mAdSelectionEntryDao.getBuyersDecisionLogicOverride(
                        AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1);

        assertThat(overrides).containsExactlyElementsIn(DB_BUYER_DECISION_OVERRIDES);

        mAdSelectionEntryDao.removeAllBuyerDecisionOverrides(CALLER_PACKAGE_NAME_1);

        assertThat(
                        mAdSelectionEntryDao.getBuyersDecisionLogicOverride(
                                AD_SELECTION_CONFIG_ID_1, CALLER_PACKAGE_NAME_1))
                .isEmpty();
    }

    /**
     * Creates expected DBAdSelectionEntry to be used for testing from DBAdSelection and
     * DBBuyerDecisionLogic. Remarketing Case
     */
    private DBAdSelectionEntry toAdSelectionEntry(
            DBAdSelection adSelection, DBBuyerDecisionLogic buyerDecisionLogic) {
        return new DBAdSelectionEntry.Builder()
                .setAdSelectionId(adSelection.getAdSelectionId())
                .setBiddingLogicUri(adSelection.getBiddingLogicUri())
                .setCustomAudienceSignals(adSelection.getCustomAudienceSignals())
                .setContextualSignals(adSelection.getContextualSignals())
                .setWinningAdRenderUri(adSelection.getWinningAdRenderUri())
                .setWinningAdBid(adSelection.getWinningAdBid())
                .setCreationTimestamp(adSelection.getCreationTimestamp())
                .setBuyerDecisionLogicJs(buyerDecisionLogic.getBuyerDecisionLogicJs())
                .build();
    }

    /**
     * Creates expected DBAdSelectionEntry to be used for testing from DBAdSelection. Contextual
     * Case
     */
    private DBAdSelectionEntry toAdSelectionEntry(DBAdSelection adSelection) {
        return new DBAdSelectionEntry.Builder()
                .setAdSelectionId(adSelection.getAdSelectionId())
                .setBiddingLogicUri(adSelection.getBiddingLogicUri())
                .setCustomAudienceSignals(adSelection.getCustomAudienceSignals())
                .setContextualSignals(adSelection.getContextualSignals())
                .setWinningAdRenderUri(adSelection.getWinningAdRenderUri())
                .setWinningAdBid(adSelection.getWinningAdBid())
                .setCreationTimestamp(adSelection.getCreationTimestamp())
                .build();
    }
}
