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

package android.telecom.cts;

import static android.telecom.CallAudioState.*;

import android.net.Uri;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.telecom.CallEndpoint;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.RemoteConnection;
import android.telecom.VideoProfile;
import android.telecom.cts.TestUtils.InvokeCounter;
import android.util.SparseArray;

import java.util.List;

/**
 * {@link Connection} subclass that immediately performs any state changes that are a result of
 * callbacks sent from Telecom.
 */
public class MockConnection extends Connection {
    public static final int ON_POST_DIAL_WAIT = 1;
    public static final int ON_CALL_EVENT = 2;
    public static final int ON_PULL_EXTERNAL_CALL = 3;
    public static final int ON_EXTRAS_CHANGED = 4;
    public static final int ON_START_RTT = 5;
    public static final int ON_RTT_REQUEST_RESPONSE = 6;
    public static final int ON_STOP_RTT = 7;
    public static final int ON_DEFLECT = 8;
    public static final int ON_SILENCE = 9;
    public static final int ON_ADD_CONFERENCE_PARTICIPANTS = 10;
    public static final int ON_CALL_FILTERING_COMPLETED = 11;
    public static final int ON_ANSWER_CALLED = 12;
    public static final int ON_ANSWER_VIDEO_CALLED = 13;

    private CallAudioState mCallAudioState =
            new CallAudioState(false, CallAudioState.ROUTE_EARPIECE, ROUTE_EARPIECE | ROUTE_SPEAKER);
    private boolean mEndpointIsMute = false;
    private int mState = STATE_NEW;
    public int videoState = VideoProfile.STATE_AUDIO_ONLY;
    private String mDtmfString = "";
    private MockVideoProvider mMockVideoProvider;
    private PhoneAccountHandle mPhoneAccountHandle;
    private RemoteConnection mRemoteConnection = null;
    private RttTextStream mRttTextStream;
    private boolean mAutoDestroy = true;

    private SparseArray<InvokeCounter> mInvokeCounterMap = new SparseArray<>(13);

    @Override
    public void onAnswer() {
        super.onAnswer();
        if (mInvokeCounterMap.get(ON_ANSWER_CALLED) != null) {
            mInvokeCounterMap.get(ON_ANSWER_CALLED).invoke();
        }
    }

    @Override
    public void onAnswer(int videoState) {
        super.onAnswer(videoState);
        this.videoState = videoState;
        setActive();
        if (mRemoteConnection != null) {
            mRemoteConnection.answer();
        }
        if (mInvokeCounterMap.get(ON_ANSWER_VIDEO_CALLED) != null) {
            mInvokeCounterMap.get(ON_ANSWER_VIDEO_CALLED).invoke(videoState);
        }
    }

    @Override
    public void onReject() {
        super.onReject();
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        if (mRemoteConnection != null) {
            mRemoteConnection.reject();
        }
        if (mAutoDestroy) destroy();
    }

    @Override
    public void onReject(int rejectReason) {
        super.onReject(rejectReason);
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED,
                Integer.toString(rejectReason)));
        if (mAutoDestroy) destroy();
    }

    @Override
    public void onReject(String reason) {
        super.onReject();
        setDisconnected(new DisconnectCause(DisconnectCause.REJECTED, reason));
        if (mRemoteConnection != null) {
            mRemoteConnection.reject();
        }
        if (mAutoDestroy) destroy();
    }

    @Override
    public void onHold() {
        super.onHold();
        setOnHold();
        if (mRemoteConnection != null) {
            mRemoteConnection.hold();
        }
    }

    @Override
    public void onUnhold() {
        super.onUnhold();
        setActive();
        if (mRemoteConnection != null) {
            mRemoteConnection.unhold();
        }
    }

    @Override
    public void onDisconnect() {
        super.onDisconnect();
        setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        if (mRemoteConnection != null) {
            mRemoteConnection.disconnect();
        }
        if (mAutoDestroy) destroy();
    }

    @Override
    public void onAbort() {
        super.onAbort();
        setDisconnected(new DisconnectCause(DisconnectCause.UNKNOWN));
        if (mRemoteConnection != null) {
            mRemoteConnection.abort();
        }
        if (mAutoDestroy) destroy();
    }

    @Override
    public void onPlayDtmfTone(char c) {
        super.onPlayDtmfTone(c);
        mDtmfString += c;
        if (mRemoteConnection != null) {
            mRemoteConnection.playDtmfTone(c);
        }
    }

    @Override
    public void onStopDtmfTone() {
        super.onStopDtmfTone();
        mDtmfString += ".";
        if (mRemoteConnection != null) {
            mRemoteConnection.stopDtmfTone();
        }
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        mCallAudioState = state;
        if (mRemoteConnection != null) {
            mRemoteConnection.setCallAudioState(state);
        }
    }

    @Override
    public void onCallEndpointChanged(CallEndpoint callendpoint) {
        super.onCallEndpointChanged(callendpoint);
    }

    @Override
    public void onAvailableCallEndpointsChanged(List<CallEndpoint> availableEndpoints) {
        super.onAvailableCallEndpointsChanged(availableEndpoints);
    }
    @Override
    public void onMuteStateChanged(boolean isMuted) {
        super.onMuteStateChanged(isMuted);
        mEndpointIsMute = isMuted;
    }

    @Override
    public void onStateChanged(int state) {
        super.onStateChanged(state);
        mState = state;
    }

    @Override
    public void onPostDialContinue(boolean proceed) {
        super.onPostDialContinue(proceed);
        if (mInvokeCounterMap.get(ON_POST_DIAL_WAIT) != null) {
            mInvokeCounterMap.get(ON_POST_DIAL_WAIT).invoke(proceed);
        }
    }

    @Override
    public void onCallEvent(String event, Bundle extras) {
        super.onCallEvent(event, extras);
        if (mInvokeCounterMap.get(ON_CALL_EVENT) != null) {
            mInvokeCounterMap.get(ON_CALL_EVENT).invoke(event, extras);
        }
    }

    @Override
    public void onPullExternalCall() {
        super.onPullExternalCall();
        if (mInvokeCounterMap.get(ON_PULL_EXTERNAL_CALL) != null) {
            mInvokeCounterMap.get(ON_PULL_EXTERNAL_CALL).invoke();
        }
    }

    @Override
    public void onAddConferenceParticipants(List<Uri> participants) {
        super.onAddConferenceParticipants(participants);
        if (mInvokeCounterMap.get(ON_ADD_CONFERENCE_PARTICIPANTS) != null) {
            mInvokeCounterMap.get(ON_ADD_CONFERENCE_PARTICIPANTS).invoke(participants);
        }
    }

    @Override
    public void onExtrasChanged(Bundle extras) {
        super.onExtrasChanged(extras);
        if (mInvokeCounterMap.get(ON_EXTRAS_CHANGED) != null) {
            mInvokeCounterMap.get(ON_EXTRAS_CHANGED).invoke(extras);
        }
    }

    @Override
    public void onStartRtt(RttTextStream rttTextStream) {
        super.onStartRtt(rttTextStream);
        if (mInvokeCounterMap.get(ON_START_RTT) != null) {
            mInvokeCounterMap.get(ON_START_RTT).invoke(rttTextStream);
        }
    }

    @Override
    public void handleRttUpgradeResponse(RttTextStream rttTextStream) {
        super.handleRttUpgradeResponse(rttTextStream);
        if (rttTextStream != null) {
            setRttTextStream(rttTextStream);
            setConnectionProperties(getConnectionProperties() | PROPERTY_IS_RTT);
        }

        if (mInvokeCounterMap.get(ON_RTT_REQUEST_RESPONSE) != null) {
            mInvokeCounterMap.get(ON_RTT_REQUEST_RESPONSE).invoke(rttTextStream);
        }
    }

    @Override
    public void onStopRtt() {
        super.onStopRtt();

        if (mInvokeCounterMap.get(ON_STOP_RTT) != null) {
            mInvokeCounterMap.get(ON_STOP_RTT).invoke();
        }
    }

    @Override
    public void onDeflect(Uri address) {
        if (mInvokeCounterMap.get(ON_DEFLECT) != null) {
            mInvokeCounterMap.get(ON_DEFLECT).invoke(address);
        }
    }

    @Override
    public void onSilence() {
        super.onSilence();

        if (mInvokeCounterMap.get(ON_SILENCE) != null) {
            mInvokeCounterMap.get(ON_SILENCE).invoke();
        }
    }

    @Override
    public void onCallFilteringCompleted(
            Connection.CallFilteringCompletionInfo callFilteringCompletionInfo) {
        getInvokeCounter(ON_CALL_FILTERING_COMPLETED).invoke(callFilteringCompletionInfo);

        if (mRemoteConnection != null) {
            mRemoteConnection.onCallFilteringCompleted(callFilteringCompletionInfo);
        }
    }

    /**
     * Do not destroy after setting disconnected for cases that need finer state control. If
     * disabled the caller will need to call destroy manually.
     */
    public void disableAutoDestroy() {
        mAutoDestroy = false;
    }

    public int getCurrentState()  {
        return mState;
    }

    public CallAudioState getCurrentCallAudioState() {
        return mCallAudioState;
    }

    public boolean getEndpointMuteState() {
        return mEndpointIsMute;
    }

    public String getDtmfString() {
        return mDtmfString;
    }

    public InvokeCounter getInvokeCounter(int counterIndex) {
        if (mInvokeCounterMap.get(counterIndex) == null) {
            mInvokeCounterMap.put(counterIndex,
                    new InvokeCounter(getCounterLabel(counterIndex)));
        }
        return mInvokeCounterMap.get(counterIndex);
    }

    /**
     * Creates a mock video provider for this connection.
     */
    public void createMockVideoProvider() {
        final MockVideoProvider mockVideoProvider = new MockVideoProvider(this);
        mMockVideoProvider = mockVideoProvider;
        setVideoProvider(mockVideoProvider);
    }

    public void sendMockVideoQuality(int videoQuality) {
        if (mMockVideoProvider == null) {
            return;
        }
        mMockVideoProvider.sendMockVideoQuality(videoQuality);
    }

    public void sendMockCallSessionEvent(int event) {
        if (mMockVideoProvider == null) {
            return;
        }
        mMockVideoProvider.sendMockCallSessionEvent(event);
    }

    public void sendMockPeerWidth(int width) {
        if (mMockVideoProvider == null) {
            return;
        }
        mMockVideoProvider.sendMockPeerWidth(width);
    }

    public void sendMockSessionModifyRequest(VideoProfile request) {
        if (mMockVideoProvider == null) {
            return;
        }
        mMockVideoProvider.sendMockSessionModifyRequest(request);
    }

    public MockVideoProvider getMockVideoProvider() {
        return mMockVideoProvider;
    }

    public void setMockPhoneAccountHandle(PhoneAccountHandle handle)  {
        mPhoneAccountHandle = handle;
    }

    public PhoneAccountHandle getMockPhoneAccountHandle()  {
        return mPhoneAccountHandle;
    }

    public void setRemoteConnection(RemoteConnection remoteConnection)  {
        mRemoteConnection = remoteConnection;
    }

    public RemoteConnection getRemoteConnection()  {
        return mRemoteConnection;
    }

    public void setRttTextStream(RttTextStream rttTextStream) {
        mRttTextStream = rttTextStream;
    }

    public RttTextStream getRttTextStream() {
        return mRttTextStream;
    }

    private static String getCounterLabel(int counterIndex) {
        switch (counterIndex) {
            case ON_POST_DIAL_WAIT:
                return "onPostDialWait";
            case ON_CALL_EVENT:
                return "onCallEvent";
            case ON_PULL_EXTERNAL_CALL:
                return "onPullExternalCall";
            case ON_EXTRAS_CHANGED:
                return "onExtrasChanged";
            case ON_START_RTT:
                return "onStartRtt";
            case ON_RTT_REQUEST_RESPONSE:
                return "onRttRequestResponse";
            case ON_STOP_RTT:
                return "onStopRtt";
            case ON_DEFLECT:
                return "onDeflect";
            case ON_SILENCE:
                return "onSilence";
            case ON_ADD_CONFERENCE_PARTICIPANTS:
                return "onAddConferenceParticipants";
            default:
                return "Callback";
        }
    }
}
