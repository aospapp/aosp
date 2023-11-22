/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.telecom.tests;

import android.content.Context;
import android.telecom.DisconnectCause;
import android.telecom.ParcelableCallAnalytics;
import android.telecom.TelecomAnalytics;
import android.telecom.TelecomManager;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.IndentingPrintWriter;
import com.android.server.telecom.Analytics;
import com.android.server.telecom.Log;

import java.io.StringWriter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnalyticsTests extends TelecomSystemTest {
    @MediumTest
    public void testAnalyticsSingleCall() throws Exception {
        IdPair testCall = startAndMakeActiveIncomingCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);
        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();

        assertTrue(analyticsMap.containsKey(testCall.mCallId));

        Analytics.CallInfoImpl callAnalytics = analyticsMap.get(testCall.mCallId);
        assertTrue(callAnalytics.startTime > 0);
        assertEquals(0, callAnalytics.endTime);
        assertEquals(Analytics.INCOMING_DIRECTION, callAnalytics.callDirection);
        assertFalse(callAnalytics.isInterrupted);
        assertNull(callAnalytics.callTerminationReason);
        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics.connectionService);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall.mConnectionId, DisconnectCause.ERROR);

        analyticsMap = Analytics.cloneData();
        callAnalytics = analyticsMap.get(testCall.mCallId);
        assertTrue(callAnalytics.endTime > 0);
        assertNotNull(callAnalytics.callTerminationReason);
        assertEquals(DisconnectCause.ERROR, callAnalytics.callTerminationReason.getCode());

        StringWriter sr = new StringWriter();
        IndentingPrintWriter ip = new IndentingPrintWriter(sr, "    ");
        Analytics.dump(ip);
        String dumpResult = sr.toString();
        String[] expectedFields = {"startTime", "endTime", "direction", "isAdditionalCall",
                "isInterrupted", "callTechnologies", "callTerminationReason", "connectionService"};
        for (String field : expectedFields) {
            assertTrue(dumpResult.contains(field));
        }
    }

    @MediumTest
    public void testAnalyticsDumping() throws Exception {
        Analytics.reset();
        IdPair testCall = startAndMakeActiveIncomingCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall.mConnectionId, DisconnectCause.ERROR);
        Analytics.CallInfoImpl expectedAnalytics = Analytics.cloneData().get(testCall.mCallId);

        TelecomManager tm = (TelecomManager) mSpyContext.getSystemService(Context.TELECOM_SERVICE);
        List<ParcelableCallAnalytics> analyticsList = tm.dumpAnalytics().getCallAnalytics();

        assertEquals(1, analyticsList.size());
        ParcelableCallAnalytics pCA = analyticsList.get(0);

        assertTrue(Math.abs(expectedAnalytics.startTime - pCA.getStartTimeMillis()) <
                ParcelableCallAnalytics.MILLIS_IN_5_MINUTES);
        assertEquals(0, pCA.getStartTimeMillis() % ParcelableCallAnalytics.MILLIS_IN_5_MINUTES);
        assertTrue(Math.abs((expectedAnalytics.endTime - expectedAnalytics.startTime) -
                pCA.getCallDurationMillis()) < ParcelableCallAnalytics.MILLIS_IN_1_SECOND);
        assertEquals(0, pCA.getCallDurationMillis() % ParcelableCallAnalytics.MILLIS_IN_1_SECOND);

        assertEquals(expectedAnalytics.callDirection, pCA.getCallType());
        assertEquals(expectedAnalytics.isAdditionalCall, pCA.isAdditionalCall());
        assertEquals(expectedAnalytics.isInterrupted, pCA.isInterrupted());
        assertEquals(expectedAnalytics.callTechnologies, pCA.getCallTechnologies());
        assertEquals(expectedAnalytics.callTerminationReason.getCode(),
                pCA.getCallTerminationCode());
        assertEquals(expectedAnalytics.connectionService, pCA.getConnectionService());
        List<ParcelableCallAnalytics.AnalyticsEvent> analyticsEvents = pCA.analyticsEvents();
        Set<Integer> capturedEvents = new HashSet<>();
        for (ParcelableCallAnalytics.AnalyticsEvent e : analyticsEvents) {
            capturedEvents.add(e.getEventName());
        }
        assertTrue(capturedEvents.contains(ParcelableCallAnalytics.AnalyticsEvent.SET_ACTIVE));
        assertTrue(capturedEvents.contains(
                ParcelableCallAnalytics.AnalyticsEvent.FILTERING_INITIATED));
    }

    @MediumTest
    public void testAnalyticsTwoCalls() throws Exception {
        IdPair testCall1 = startAndMakeActiveIncomingCall(
                "650-555-1212",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);
        IdPair testCall2 = startAndMakeActiveOutgoingCall(
                "650-555-1213",
                mPhoneAccountA0.getAccountHandle(),
                mConnectionServiceFixtureA);

        Map<String, Analytics.CallInfoImpl> analyticsMap = Analytics.cloneData();
        assertTrue(analyticsMap.containsKey(testCall1.mCallId));
        assertTrue(analyticsMap.containsKey(testCall2.mCallId));

        Analytics.CallInfoImpl callAnalytics1 = analyticsMap.get(testCall1.mCallId);
        Analytics.CallInfoImpl callAnalytics2 = analyticsMap.get(testCall2.mCallId);
        assertTrue(callAnalytics1.startTime > 0);
        assertTrue(callAnalytics2.startTime > 0);
        assertEquals(0, callAnalytics1.endTime);
        assertEquals(0, callAnalytics2.endTime);

        assertEquals(Analytics.INCOMING_DIRECTION, callAnalytics1.callDirection);
        assertEquals(Analytics.OUTGOING_DIRECTION, callAnalytics2.callDirection);

        assertTrue(callAnalytics1.isInterrupted);
        assertTrue(callAnalytics2.isAdditionalCall);

        assertNull(callAnalytics1.callTerminationReason);
        assertNull(callAnalytics2.callTerminationReason);

        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics1.connectionService);
        assertEquals(mConnectionServiceComponentNameA.flattenToShortString(),
                callAnalytics1.connectionService);

        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall2.mConnectionId, DisconnectCause.REMOTE);
        mConnectionServiceFixtureA.
                sendSetDisconnected(testCall1.mConnectionId, DisconnectCause.ERROR);

        analyticsMap = Analytics.cloneData();
        callAnalytics1 = analyticsMap.get(testCall1.mCallId);
        callAnalytics2 = analyticsMap.get(testCall2.mCallId);
        assertTrue(callAnalytics1.endTime > 0);
        assertTrue(callAnalytics2.endTime > 0);
        assertNotNull(callAnalytics1.callTerminationReason);
        assertNotNull(callAnalytics2.callTerminationReason);
        assertEquals(DisconnectCause.ERROR, callAnalytics1.callTerminationReason.getCode());
        assertEquals(DisconnectCause.REMOTE, callAnalytics2.callTerminationReason.getCode());
    }

    @SmallTest
    public void testAnalyticsRounding() {
        long[] testVals = {0, -1, -10, -100, -57836, 1, 10, 100, 1000, 458457};
        long[] expected = {0, -1, -10, -100, -60000, 1, 10, 100, 1000, 500000};
        for (int i = 0; i < testVals.length; i++) {
            assertEquals(expected[i], Analytics.roundToOneSigFig(testVals[i]));
        }
    }

    @SmallTest
    public void testAnalyticsLogSessionTiming() throws Exception {
        long minTime = 50;
        Log.startSession(Log.Sessions.CSW_ADD_CONFERENCE_CALL);
        Thread.sleep(minTime);
        Log.endSession();
        TelecomManager tm = (TelecomManager) mSpyContext.getSystemService(Context.TELECOM_SERVICE);
        List<TelecomAnalytics.SessionTiming> sessions = tm.dumpAnalytics().getSessionTimings();
        sessions.stream()
                .filter(s -> Log.Sessions.CSW_ADD_CONFERENCE_CALL.equals(
                        Analytics.sSessionIdToLogSession.get(s.getKey())))
                .forEach(s -> assertTrue(s.getTime() > minTime));
    }
}
