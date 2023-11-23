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

package android.adservices.adselection;

import static android.adservices.adselection.ReportInteractionRequest.FLAG_REPORTING_DESTINATION_BUYER;
import static android.adservices.adselection.ReportInteractionRequest.FLAG_REPORTING_DESTINATION_SELLER;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ReportInteractionRequestTest {
    private static final long AD_SELECTION_ID = 1234L;
    private static final String INTERACTION_KEY = "click";
    private String mInteractionData;
    private static final int DESTINATIONS =
            FLAG_REPORTING_DESTINATION_SELLER | FLAG_REPORTING_DESTINATION_BUYER;

    @Before
    public void setup() throws Exception {
        JSONObject obj = new JSONObject().put("key", "value");
        mInteractionData = obj.toString();
    }

    @Test
    public void testBuildReportInteractionRequestSuccess() throws Exception {
        ReportInteractionRequest request =
                new ReportInteractionRequest(
                        AD_SELECTION_ID, INTERACTION_KEY, mInteractionData, DESTINATIONS);

        assertEquals(AD_SELECTION_ID, request.getAdSelectionId());
        assertEquals(INTERACTION_KEY, request.getInteractionKey());
        assertEquals(mInteractionData, request.getInteractionData());
        assertEquals(DESTINATIONS, request.getReportingDestinations());
    }

    @Test
    public void testFailsToBuildWithUnsetAdSelectionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ReportInteractionRequest(
                            0, INTERACTION_KEY, mInteractionData, DESTINATIONS);
                });
    }

    @Test
    public void testFailsToBuildWithUnsetInteractionKey() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ReportInteractionRequest(
                            AD_SELECTION_ID, null, mInteractionData, DESTINATIONS);
                });
    }

    @Test
    public void testFailsToBuildWithUnsetInteractionData() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ReportInteractionRequest(
                            AD_SELECTION_ID, INTERACTION_KEY, null, DESTINATIONS);
                });
    }

    @Test
    public void testFailsToBuildWithUnsetDestinations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new ReportInteractionRequest(
                            AD_SELECTION_ID, INTERACTION_KEY, mInteractionData, 0);
                });
    }
}
