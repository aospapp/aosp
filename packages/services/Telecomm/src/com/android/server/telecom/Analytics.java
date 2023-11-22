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
 * limitations under the License
 */

package com.android.server.telecom;

import android.telecom.DisconnectCause;
import android.telecom.ParcelableCallAnalytics;
import android.telecom.TelecomAnalytics;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static android.telecom.ParcelableCallAnalytics.AnalyticsEvent;
import static android.telecom.TelecomAnalytics.SessionTiming;

/**
 * A class that collects and stores data on how calls are being made, in order to
 * aggregate these into useful statistics.
 */
public class Analytics {
    public static final Map<String, Integer> sLogEventToAnalyticsEvent =
            new HashMap<String, Integer>() {{
                put(Log.Events.SET_SELECT_PHONE_ACCOUNT, AnalyticsEvent.SET_SELECT_PHONE_ACCOUNT);
                put(Log.Events.REQUEST_HOLD, AnalyticsEvent.REQUEST_HOLD);
                put(Log.Events.REQUEST_UNHOLD, AnalyticsEvent.REQUEST_UNHOLD);
                put(Log.Events.SWAP, AnalyticsEvent.SWAP);
                put(Log.Events.SKIP_RINGING, AnalyticsEvent.SKIP_RINGING);
                put(Log.Events.CONFERENCE_WITH, AnalyticsEvent.CONFERENCE_WITH);
                put(Log.Events.SPLIT_FROM_CONFERENCE, AnalyticsEvent.SPLIT_CONFERENCE);
                put(Log.Events.SET_PARENT, AnalyticsEvent.SET_PARENT);
                put(Log.Events.MUTE, AnalyticsEvent.MUTE);
                put(Log.Events.UNMUTE, AnalyticsEvent.UNMUTE);
                put(Log.Events.AUDIO_ROUTE_BT, AnalyticsEvent.AUDIO_ROUTE_BT);
                put(Log.Events.AUDIO_ROUTE_EARPIECE, AnalyticsEvent.AUDIO_ROUTE_EARPIECE);
                put(Log.Events.AUDIO_ROUTE_HEADSET, AnalyticsEvent.AUDIO_ROUTE_HEADSET);
                put(Log.Events.AUDIO_ROUTE_SPEAKER, AnalyticsEvent.AUDIO_ROUTE_SPEAKER);
                put(Log.Events.SILENCE, AnalyticsEvent.SILENCE);
                put(Log.Events.SCREENING_COMPLETED, AnalyticsEvent.SCREENING_COMPLETED);
                put(Log.Events.BLOCK_CHECK_FINISHED, AnalyticsEvent.BLOCK_CHECK_FINISHED);
                put(Log.Events.DIRECT_TO_VM_FINISHED, AnalyticsEvent.DIRECT_TO_VM_FINISHED);
                put(Log.Events.REMOTELY_HELD, AnalyticsEvent.REMOTELY_HELD);
                put(Log.Events.REMOTELY_UNHELD, AnalyticsEvent.REMOTELY_UNHELD);
                put(Log.Events.REQUEST_PULL, AnalyticsEvent.REQUEST_PULL);
                put(Log.Events.REQUEST_ACCEPT, AnalyticsEvent.REQUEST_ACCEPT);
                put(Log.Events.REQUEST_REJECT, AnalyticsEvent.REQUEST_REJECT);
                put(Log.Events.SET_ACTIVE, AnalyticsEvent.SET_ACTIVE);
                put(Log.Events.SET_DISCONNECTED, AnalyticsEvent.SET_DISCONNECTED);
                put(Log.Events.SET_HOLD, AnalyticsEvent.SET_HOLD);
                put(Log.Events.SET_DIALING, AnalyticsEvent.SET_DIALING);
                put(Log.Events.START_CONNECTION, AnalyticsEvent.START_CONNECTION);
                put(Log.Events.BIND_CS, AnalyticsEvent.BIND_CS);
                put(Log.Events.CS_BOUND, AnalyticsEvent.CS_BOUND);
                put(Log.Events.SCREENING_SENT, AnalyticsEvent.SCREENING_SENT);
                put(Log.Events.DIRECT_TO_VM_INITIATED, AnalyticsEvent.DIRECT_TO_VM_INITIATED);
                put(Log.Events.BLOCK_CHECK_INITIATED, AnalyticsEvent.BLOCK_CHECK_INITIATED);
                put(Log.Events.FILTERING_INITIATED, AnalyticsEvent.FILTERING_INITIATED);
                put(Log.Events.FILTERING_COMPLETED, AnalyticsEvent.FILTERING_COMPLETED);
                put(Log.Events.FILTERING_TIMED_OUT, AnalyticsEvent.FILTERING_TIMED_OUT);
            }};

    public static final Map<String, Integer> sLogSessionToSessionId =
            new HashMap<String, Integer> () {{
                put(Log.Sessions.ICA_ANSWER_CALL, SessionTiming.ICA_ANSWER_CALL);
                put(Log.Sessions.ICA_REJECT_CALL, SessionTiming.ICA_REJECT_CALL);
                put(Log.Sessions.ICA_DISCONNECT_CALL, SessionTiming.ICA_DISCONNECT_CALL);
                put(Log.Sessions.ICA_HOLD_CALL, SessionTiming.ICA_HOLD_CALL);
                put(Log.Sessions.ICA_UNHOLD_CALL, SessionTiming.ICA_UNHOLD_CALL);
                put(Log.Sessions.ICA_MUTE, SessionTiming.ICA_MUTE);
                put(Log.Sessions.ICA_SET_AUDIO_ROUTE, SessionTiming.ICA_SET_AUDIO_ROUTE);
                put(Log.Sessions.ICA_CONFERENCE, SessionTiming.ICA_CONFERENCE);
                put(Log.Sessions.CSW_HANDLE_CREATE_CONNECTION_COMPLETE,
                        SessionTiming.CSW_HANDLE_CREATE_CONNECTION_COMPLETE);
                put(Log.Sessions.CSW_SET_ACTIVE, SessionTiming.CSW_SET_ACTIVE);
                put(Log.Sessions.CSW_SET_RINGING, SessionTiming.CSW_SET_RINGING);
                put(Log.Sessions.CSW_SET_DIALING, SessionTiming.CSW_SET_DIALING);
                put(Log.Sessions.CSW_SET_DISCONNECTED, SessionTiming.CSW_SET_DISCONNECTED);
                put(Log.Sessions.CSW_SET_ON_HOLD, SessionTiming.CSW_SET_ON_HOLD);
                put(Log.Sessions.CSW_REMOVE_CALL, SessionTiming.CSW_REMOVE_CALL);
                put(Log.Sessions.CSW_SET_IS_CONFERENCED, SessionTiming.CSW_SET_IS_CONFERENCED);
                put(Log.Sessions.CSW_ADD_CONFERENCE_CALL, SessionTiming.CSW_ADD_CONFERENCE_CALL);

            }};

    public static final Map<String, Integer> sLogEventTimingToAnalyticsEventTiming =
            new HashMap<String, Integer>() {{
                put(Log.Events.Timings.ACCEPT_TIMING,
                        ParcelableCallAnalytics.EventTiming.ACCEPT_TIMING);
                put(Log.Events.Timings.REJECT_TIMING,
                        ParcelableCallAnalytics.EventTiming.REJECT_TIMING);
                put(Log.Events.Timings.DISCONNECT_TIMING,
                        ParcelableCallAnalytics.EventTiming.DISCONNECT_TIMING);
                put(Log.Events.Timings.HOLD_TIMING,
                        ParcelableCallAnalytics.EventTiming.HOLD_TIMING);
                put(Log.Events.Timings.UNHOLD_TIMING,
                        ParcelableCallAnalytics.EventTiming.UNHOLD_TIMING);
                put(Log.Events.Timings.OUTGOING_TIME_TO_DIALING_TIMING,
                        ParcelableCallAnalytics.EventTiming.OUTGOING_TIME_TO_DIALING_TIMING);
                put(Log.Events.Timings.BIND_CS_TIMING,
                        ParcelableCallAnalytics.EventTiming.BIND_CS_TIMING);
                put(Log.Events.Timings.SCREENING_COMPLETED_TIMING,
                        ParcelableCallAnalytics.EventTiming.SCREENING_COMPLETED_TIMING);
                put(Log.Events.Timings.DIRECT_TO_VM_FINISHED_TIMING,
                        ParcelableCallAnalytics.EventTiming.DIRECT_TO_VM_FINISHED_TIMING);
                put(Log.Events.Timings.BLOCK_CHECK_FINISHED_TIMING,
                        ParcelableCallAnalytics.EventTiming.BLOCK_CHECK_FINISHED_TIMING);
                put(Log.Events.Timings.FILTERING_COMPLETED_TIMING,
                        ParcelableCallAnalytics.EventTiming.FILTERING_COMPLETED_TIMING);
                put(Log.Events.Timings.FILTERING_TIMED_OUT_TIMING,
                        ParcelableCallAnalytics.EventTiming.FILTERING_TIMED_OUT_TIMING);
            }};

    public static final Map<Integer, String> sSessionIdToLogSession = new HashMap<>();
    static {
        for (Map.Entry<String, Integer> e : sLogSessionToSessionId.entrySet()) {
            sSessionIdToLogSession.put(e.getValue(), e.getKey());
        }
    }

    public static class CallInfo {
        public void setCallStartTime(long startTime) {
        }

        public void setCallEndTime(long endTime) {
        }

        public void setCallIsAdditional(boolean isAdditional) {
        }

        public void setCallIsInterrupted(boolean isInterrupted) {
        }

        public void setCallDisconnectCause(DisconnectCause disconnectCause) {
        }

        public void addCallTechnology(int callTechnology) {
        }

        public void setCreatedFromExistingConnection(boolean createdFromExistingConnection) {
        }

        public void setCallConnectionService(String connectionServiceName) {
        }

        public void setCallEvents(Log.CallEventRecord records) {
        }

        public void setCallIsVideo(boolean isVideo) {
        }

        public void addVideoEvent(int eventId, int videoState) {
        }
    }

    /**
     * A class that holds data associated with a call.
     */
    @VisibleForTesting
    public static class CallInfoImpl extends CallInfo {
        public String callId;
        public long startTime;  // start time in milliseconds since the epoch. 0 if not yet set.
        public long endTime;  // end time in milliseconds since the epoch. 0 if not yet set.
        public int callDirection;  // one of UNKNOWN_DIRECTION, INCOMING_DIRECTION,
                                   // or OUTGOING_DIRECTION.
        public boolean isAdditionalCall = false;  // true if the call came in while another call was
                                                  // in progress or if the user dialed this call
                                                  // while in the middle of another call.
        public boolean isInterrupted = false;  // true if the call was interrupted by an incoming
                                               // or outgoing call.
        public int callTechnologies;  // bitmask denoting which technologies a call used.

        // true if the Telecom Call object was created from an existing connection via
        // CallsManager#createCallForExistingConnection, for example, by ImsConference.
        public boolean createdFromExistingConnection = false;

        public DisconnectCause callTerminationReason;
        public String connectionService;
        public boolean isEmergency = false;

        public Log.CallEventRecord callEvents;

        public boolean isVideo = false;
        public List<ParcelableCallAnalytics.VideoEvent> videoEvents;
        private long mTimeOfLastVideoEvent = -1;

        CallInfoImpl(String callId, int callDirection) {
            this.callId = callId;
            startTime = 0;
            endTime = 0;
            this.callDirection = callDirection;
            callTechnologies = 0;
            connectionService = "";
            videoEvents = new LinkedList<>();
        }

        CallInfoImpl(CallInfoImpl other) {
            this.callId = other.callId;
            this.startTime = other.startTime;
            this.endTime = other.endTime;
            this.callDirection = other.callDirection;
            this.isAdditionalCall = other.isAdditionalCall;
            this.isInterrupted = other.isInterrupted;
            this.callTechnologies = other.callTechnologies;
            this.createdFromExistingConnection = other.createdFromExistingConnection;
            this.connectionService = other.connectionService;
            this.isEmergency = other.isEmergency;
            this.callEvents = other.callEvents;
            this.isVideo = other.isVideo;
            this.videoEvents = other.videoEvents;

            if (other.callTerminationReason != null) {
                this.callTerminationReason = new DisconnectCause(
                        other.callTerminationReason.getCode(),
                        other.callTerminationReason.getLabel(),
                        other.callTerminationReason.getDescription(),
                        other.callTerminationReason.getReason(),
                        other.callTerminationReason.getTone());
            } else {
                this.callTerminationReason = null;
            }
        }

        @Override
        public void setCallStartTime(long startTime) {
            Log.d(TAG, "setting startTime for call " + callId + " to " + startTime);
            this.startTime = startTime;
        }

        @Override
        public void setCallEndTime(long endTime) {
            Log.d(TAG, "setting endTime for call " + callId + " to " + endTime);
            this.endTime = endTime;
        }

        @Override
        public void setCallIsAdditional(boolean isAdditional) {
            Log.d(TAG, "setting isAdditional for call " + callId + " to " + isAdditional);
            this.isAdditionalCall = isAdditional;
        }

        @Override
        public void setCallIsInterrupted(boolean isInterrupted) {
            Log.d(TAG, "setting isInterrupted for call " + callId + " to " + isInterrupted);
            this.isInterrupted = isInterrupted;
        }

        @Override
        public void addCallTechnology(int callTechnology) {
            Log.d(TAG, "adding callTechnology for call " + callId + ": " + callTechnology);
            this.callTechnologies |= callTechnology;
        }

        @Override
        public void setCallDisconnectCause(DisconnectCause disconnectCause) {
            Log.d(TAG, "setting disconnectCause for call " + callId + " to " + disconnectCause);
            this.callTerminationReason = disconnectCause;
        }

        @Override
        public void setCreatedFromExistingConnection(boolean createdFromExistingConnection) {
            Log.d(TAG, "setting createdFromExistingConnection for call " + callId + " to "
                    + createdFromExistingConnection);
            this.createdFromExistingConnection = createdFromExistingConnection;
        }

        @Override
        public void setCallConnectionService(String connectionServiceName) {
            Log.d(TAG, "setting connection service for call " + callId + ": "
                    + connectionServiceName);
            this.connectionService = connectionServiceName;
        }

        @Override
        public void setCallEvents(Log.CallEventRecord records) {
            this.callEvents = records;
        }

        @Override
        public void setCallIsVideo(boolean isVideo) {
            this.isVideo = isVideo;
        }

        @Override
        public void addVideoEvent(int eventId, int videoState) {
            long timeSinceLastEvent;
            long currentTime = System.currentTimeMillis();
            if (mTimeOfLastVideoEvent < 0) {
                timeSinceLastEvent = -1;
            } else {
                timeSinceLastEvent = roundToOneSigFig(currentTime - mTimeOfLastVideoEvent);
            }
            mTimeOfLastVideoEvent = currentTime;

            videoEvents.add(new ParcelableCallAnalytics.VideoEvent(
                    eventId, timeSinceLastEvent, videoState));
        }

        @Override
        public String toString() {
            return "{\n"
                    + "    startTime: " + startTime + '\n'
                    + "    endTime: " + endTime + '\n'
                    + "    direction: " + getCallDirectionString() + '\n'
                    + "    isAdditionalCall: " + isAdditionalCall + '\n'
                    + "    isInterrupted: " + isInterrupted + '\n'
                    + "    callTechnologies: " + getCallTechnologiesAsString() + '\n'
                    + "    callTerminationReason: " + getCallDisconnectReasonString() + '\n'
                    + "    connectionService: " + connectionService + '\n'
                    + "    isVideoCall: " + isVideo + '\n'
                    + "}\n";
        }

        public ParcelableCallAnalytics toParcelableAnalytics() {
            // Rounds up to the nearest second.
            long callDuration = (endTime == 0 || startTime == 0) ? 0 : endTime - startTime;
            callDuration += (callDuration % MILLIS_IN_1_SECOND == 0) ?
                    0 : (MILLIS_IN_1_SECOND - callDuration % MILLIS_IN_1_SECOND);

            List<AnalyticsEvent> events;
            List<ParcelableCallAnalytics.EventTiming> timings;
            if (callEvents != null) {
                events = convertLogEventsToAnalyticsEvents(callEvents.getEvents());
                timings = callEvents.extractEventTimings().stream()
                        .map(Analytics::logEventTimingToAnalyticsEventTiming)
                        .collect(Collectors.toList());
            } else {
                events = Collections.emptyList();
                timings = Collections.emptyList();
            }
            ParcelableCallAnalytics result = new ParcelableCallAnalytics(
                    // rounds down to nearest 5 minute mark
                    startTime - startTime % ParcelableCallAnalytics.MILLIS_IN_5_MINUTES,
                    callDuration,
                    callDirection,
                    isAdditionalCall,
                    isInterrupted,
                    callTechnologies,
                    callTerminationReason == null ?
                            ParcelableCallAnalytics.STILL_CONNECTED :
                            callTerminationReason.getCode(),
                    isEmergency,
                    connectionService,
                    createdFromExistingConnection,
                    events,
                    timings);
            result.setIsVideoCall(isVideo);
            result.setVideoEvents(videoEvents);
            return result;
        }

        private String getCallDirectionString() {
            switch (callDirection) {
                case UNKNOWN_DIRECTION:
                    return "UNKNOWN";
                case INCOMING_DIRECTION:
                    return "INCOMING";
                case OUTGOING_DIRECTION:
                    return "OUTGOING";
                default:
                    return "UNKNOWN";
            }
        }

        private String getCallTechnologiesAsString() {
            StringBuilder s = new StringBuilder();
            s.append('[');
            if ((callTechnologies & CDMA_PHONE) != 0) s.append("CDMA ");
            if ((callTechnologies & GSM_PHONE) != 0) s.append("GSM ");
            if ((callTechnologies & SIP_PHONE) != 0) s.append("SIP ");
            if ((callTechnologies & IMS_PHONE) != 0) s.append("IMS ");
            if ((callTechnologies & THIRD_PARTY_PHONE) != 0) s.append("THIRD_PARTY ");
            s.append(']');
            return s.toString();
        }

        private String getCallDisconnectReasonString() {
            if (callTerminationReason != null) {
                return callTerminationReason.toString();
            } else {
                return "NOT SET";
            }
        }
    }
    public static final String TAG = "TelecomAnalytics";

    // Constants for call direction
    public static final int UNKNOWN_DIRECTION = ParcelableCallAnalytics.CALLTYPE_UNKNOWN;
    public static final int INCOMING_DIRECTION = ParcelableCallAnalytics.CALLTYPE_INCOMING;
    public static final int OUTGOING_DIRECTION = ParcelableCallAnalytics.CALLTYPE_OUTGOING;

    // Constants for call technology
    public static final int CDMA_PHONE = ParcelableCallAnalytics.CDMA_PHONE;
    public static final int GSM_PHONE = ParcelableCallAnalytics.GSM_PHONE;
    public static final int IMS_PHONE = ParcelableCallAnalytics.IMS_PHONE;
    public static final int SIP_PHONE = ParcelableCallAnalytics.SIP_PHONE;
    public static final int THIRD_PARTY_PHONE = ParcelableCallAnalytics.THIRD_PARTY_PHONE;

    // Constants for video events
    public static final int SEND_LOCAL_SESSION_MODIFY_REQUEST =
            ParcelableCallAnalytics.VideoEvent.SEND_LOCAL_SESSION_MODIFY_REQUEST;
    public static final int SEND_LOCAL_SESSION_MODIFY_RESPONSE =
            ParcelableCallAnalytics.VideoEvent.SEND_LOCAL_SESSION_MODIFY_RESPONSE;
    public static final int RECEIVE_REMOTE_SESSION_MODIFY_REQUEST =
            ParcelableCallAnalytics.VideoEvent.RECEIVE_REMOTE_SESSION_MODIFY_REQUEST;
    public static final int RECEIVE_REMOTE_SESSION_MODIFY_RESPONSE =
            ParcelableCallAnalytics.VideoEvent.RECEIVE_REMOTE_SESSION_MODIFY_RESPONSE;

    public static final long MILLIS_IN_1_SECOND = ParcelableCallAnalytics.MILLIS_IN_1_SECOND;

    private static final Object sLock = new Object(); // Coarse lock for all of analytics
    private static final Map<String, CallInfoImpl> sCallIdToInfo = new HashMap<>();
    private static final List<SessionTiming> sSessionTimings = new LinkedList<>();

    public static void addSessionTiming(String sessionName, long time) {
        if (sLogSessionToSessionId.containsKey(sessionName)) {
            synchronized (sLock) {
                sSessionTimings.add(new SessionTiming(sLogSessionToSessionId.get(sessionName),
                        time));
            }
        }
    }

    public static CallInfo initiateCallAnalytics(String callId, int direction) {
        Log.d(TAG, "Starting analytics for call " + callId);
        CallInfoImpl callInfo = new CallInfoImpl(callId, direction);
        synchronized (sLock) {
            sCallIdToInfo.put(callId, callInfo);
        }
        return callInfo;
    }

    public static TelecomAnalytics dumpToParcelableAnalytics() {
        List<ParcelableCallAnalytics> calls = new LinkedList<>();
        List<SessionTiming> sessionTimings = new LinkedList<>();
        synchronized (sLock) {
            calls.addAll(sCallIdToInfo.values().stream()
                    .map(CallInfoImpl::toParcelableAnalytics)
                    .collect(Collectors.toList()));
            sessionTimings.addAll(sSessionTimings);
            sCallIdToInfo.clear();
            sSessionTimings.clear();
        }
        return new TelecomAnalytics(sessionTimings, calls);
    }

    public static void dump(IndentingPrintWriter writer) {
        synchronized (sLock) {
            int prefixLength = CallsManager.TELECOM_CALL_ID_PREFIX.length();
            List<String> callIds = new ArrayList<>(sCallIdToInfo.keySet());
            // Sort the analytics in increasing order of call IDs
            try {
                Collections.sort(callIds, (id1, id2) -> {
                    int i1, i2;
                    try {
                        i1 = Integer.valueOf(id1.substring(prefixLength));
                    } catch (NumberFormatException e) {
                        i1 = Integer.MAX_VALUE;
                    }

                    try {
                        i2 = Integer.valueOf(id2.substring(prefixLength));
                    } catch (NumberFormatException e) {
                        i2 = Integer.MAX_VALUE;
                    }
                    return i1 - i2;
                });
            } catch (IllegalArgumentException e) {
                // do nothing, leave the list in a partially sorted state.
            }

            for (String callId : callIds) {
                writer.printf("Call %s: ", callId);
                writer.println(sCallIdToInfo.get(callId).toString());
            }

            Map<Integer, Double> averageTimings = SessionTiming.averageTimings(sSessionTimings);
            averageTimings.entrySet().stream()
                    .filter(e -> sSessionIdToLogSession.containsKey(e.getKey()))
                    .forEach(e -> writer.printf("%s: %.2f\n",
                            sSessionIdToLogSession.get(e.getKey()), e.getValue()));
        }
    }

    public static void reset() {
        synchronized (sLock) {
            sCallIdToInfo.clear();
        }
    }

    /**
     * Returns a copy of callIdToInfo. Use only for testing.
     */
    @VisibleForTesting
    public static Map<String, CallInfoImpl> cloneData() {
        synchronized (sLock) {
            Map<String, CallInfoImpl> result = new HashMap<>(sCallIdToInfo.size());
            for (Map.Entry<String, CallInfoImpl> entry : sCallIdToInfo.entrySet()) {
                result.put(entry.getKey(), new CallInfoImpl(entry.getValue()));
            }
            return result;
        }
    }

    private static List<AnalyticsEvent> convertLogEventsToAnalyticsEvents(
            List<Log.CallEvent> logEvents) {
        long timeOfLastEvent = -1;
        ArrayList<AnalyticsEvent> events = new ArrayList<>(logEvents.size());
        for (Log.CallEvent logEvent : logEvents) {
            if (sLogEventToAnalyticsEvent.containsKey(logEvent.eventId)) {
                int analyticsEventId = sLogEventToAnalyticsEvent.get(logEvent.eventId);
                long timeSinceLastEvent =
                        timeOfLastEvent < 0 ? -1 : logEvent.time - timeOfLastEvent;
                events.add(new AnalyticsEvent(
                        analyticsEventId,
                        roundToOneSigFig(timeSinceLastEvent)
                ));
                timeOfLastEvent = logEvent.time;
            }
        }
        return events;
    }

    private static ParcelableCallAnalytics.EventTiming logEventTimingToAnalyticsEventTiming(
            Log.CallEventRecord.EventTiming logEventTiming) {
        int analyticsEventTimingName =
                sLogEventTimingToAnalyticsEventTiming.containsKey(logEventTiming.name) ?
                        sLogEventTimingToAnalyticsEventTiming.get(logEventTiming.name) :
                        ParcelableCallAnalytics.EventTiming.INVALID;
        return new ParcelableCallAnalytics.EventTiming(analyticsEventTimingName,
                (long) logEventTiming.time);
    }

    @VisibleForTesting
    public static long roundToOneSigFig(long val)  {
        if (val == 0) {
            return val;
        }
        int logVal = (int) Math.floor(Math.log10(val < 0 ? -val : val));
        double s = Math.pow(10, logVal);
        double dec =  val / s;
        return (long) (Math.round(dec) * s);
    }
}
