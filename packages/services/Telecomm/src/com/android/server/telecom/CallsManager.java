/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.telecom;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CallLog.Calls;
import android.telecom.AudioState;
import android.telecom.CallState;
import android.telecom.DisconnectCause;
import android.telecom.GatewayInfo;
import android.telecom.ParcelableConference;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.PhoneCapabilities;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import com.android.internal.util.IndentingPrintWriter;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton.
 *
 * NOTE: by design most APIs are package private, use the relevant adapter/s to allow
 * access from other packages specifically refraining from passing the CallsManager instance
 * beyond the com.android.server.telecom package boundary.
 */
public final class CallsManager extends Call.ListenerBase {

    // TODO: Consider renaming this CallsManagerPlugin.
    interface CallsManagerListener {
        void onCallAdded(Call call);
        void onCallRemoved(Call call);
        void onCallStateChanged(Call call, int oldState, int newState);
        void onConnectionServiceChanged(
                Call call,
                ConnectionServiceWrapper oldService,
                ConnectionServiceWrapper newService);
        void onIncomingCallAnswered(Call call);
        void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage);
        void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall);
        void onAudioStateChanged(AudioState oldAudioState, AudioState newAudioState);
        void onRingbackRequested(Call call, boolean ringback);
        void onIsConferencedChanged(Call call);
        void onIsVoipAudioModeChanged(Call call);
        void onVideoStateChanged(Call call);
    }

    /**
     * Singleton instance of the {@link CallsManager}, initialized from {@link TelecomService}.
     */
    private static CallsManager INSTANCE = null;

    private static final String TAG = "CallsManager";

    private static final int MAXIMUM_LIVE_CALLS = 1;
    private static final int MAXIMUM_HOLD_CALLS = 1;
    private static final int MAXIMUM_RINGING_CALLS = 1;
    private static final int MAXIMUM_OUTGOING_CALLS = 1;

    private static final int[] LIVE_CALL_STATES =
            {CallState.CONNECTING, CallState.PRE_DIAL_WAIT, CallState.DIALING, CallState.ACTIVE};

    private static final int[] OUTGOING_CALL_STATES =
            {CallState.CONNECTING, CallState.PRE_DIAL_WAIT, CallState.DIALING};

    /**
     * The main call repository. Keeps an instance of all live calls. New incoming and outgoing
     * calls are added to the map and removed when the calls move to the disconnected state.
    *
     * ConcurrentHashMap constructor params: 8 is initial table size, 0.9f is
     * load factor before resizing, 1 means we only expect a single thread to
     * access the map so make only a single shard
     */
    private final Set<Call> mCalls = Collections.newSetFromMap(
            new ConcurrentHashMap<Call, Boolean>(8, 0.9f, 1));

    private final ConnectionServiceRepository mConnectionServiceRepository;
    private final DtmfLocalTonePlayer mDtmfLocalTonePlayer;
    private final InCallController mInCallController;
    private final CallAudioManager mCallAudioManager;
    private final Ringer mRinger;
    // For this set initial table size to 16 because we add 13 listeners in
    // the CallsManager constructor.
    private final Set<CallsManagerListener> mListeners = Collections.newSetFromMap(
            new ConcurrentHashMap<CallsManagerListener, Boolean>(16, 0.9f, 1));
    private final HeadsetMediaButton mHeadsetMediaButton;
    private final WiredHeadsetManager mWiredHeadsetManager;
    private final TtyManager mTtyManager;
    private final ProximitySensorManager mProximitySensorManager;
    private final PhoneStateBroadcaster mPhoneStateBroadcaster;
    private final CallLogManager mCallLogManager;
    private final Context mContext;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final MissedCallNotifier mMissedCallNotifier;
    private final Set<Call> mLocallyDisconnectingCalls = new HashSet<>();

    /**
     * The call the user is currently interacting with. This is the call that should have audio
     * focus and be visible in the in-call UI.
     */
    private Call mForegroundCall;

    /** Singleton accessor. */
    static CallsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Sets the static singleton instance.
     *
     * @param instance The instance to set.
     */
    static void initialize(CallsManager instance) {
        INSTANCE = instance;
    }

    /**
     * Initializes the required Telecom components.
     */
     CallsManager(Context context, MissedCallNotifier missedCallNotifier,
             PhoneAccountRegistrar phoneAccountRegistrar) {
        mContext = context;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mMissedCallNotifier = missedCallNotifier;
        StatusBarNotifier statusBarNotifier = new StatusBarNotifier(context, this);
        mWiredHeadsetManager = new WiredHeadsetManager(context);
        mCallAudioManager = new CallAudioManager(context, statusBarNotifier, mWiredHeadsetManager);
        InCallTonePlayer.Factory playerFactory = new InCallTonePlayer.Factory(mCallAudioManager);
        mRinger = new Ringer(mCallAudioManager, this, playerFactory, context);
        mHeadsetMediaButton = new HeadsetMediaButton(context, this);
        mTtyManager = new TtyManager(context, mWiredHeadsetManager);
        mProximitySensorManager = new ProximitySensorManager(context);
        mPhoneStateBroadcaster = new PhoneStateBroadcaster();
        mCallLogManager = new CallLogManager(context);
        mInCallController = new InCallController(context);
        mDtmfLocalTonePlayer = new DtmfLocalTonePlayer(context);
        mConnectionServiceRepository = new ConnectionServiceRepository(mPhoneAccountRegistrar,
                context);

        mListeners.add(statusBarNotifier);
        mListeners.add(mCallLogManager);
        mListeners.add(mPhoneStateBroadcaster);
        mListeners.add(mInCallController);
        mListeners.add(mRinger);
        mListeners.add(new RingbackPlayer(this, playerFactory));
        mListeners.add(new InCallToneMonitor(playerFactory, this));
        mListeners.add(mCallAudioManager);
        mListeners.add(missedCallNotifier);
        mListeners.add(mDtmfLocalTonePlayer);
        mListeners.add(mHeadsetMediaButton);
        mListeners.add(RespondViaSmsManager.getInstance());
        mListeners.add(mProximitySensorManager);
    }

    @Override
    public void onSuccessfulOutgoingCall(Call call, int callState) {
        Log.v(this, "onSuccessfulOutgoingCall, %s", call);

        setCallState(call, callState);
        if (!mCalls.contains(call)) {
            // Call was not added previously in startOutgoingCall due to it being a potential MMI
            // code, so add it now.
            addCall(call);
        }

        // The call's ConnectionService has been updated.
        for (CallsManagerListener listener : mListeners) {
            listener.onConnectionServiceChanged(call, null, call.getConnectionService());
        }

        markCallAsDialing(call);
    }

    @Override
    public void onFailedOutgoingCall(Call call, DisconnectCause disconnectCause) {
        Log.v(this, "onFailedOutgoingCall, call: %s", call);

        markCallAsRemoved(call);
    }

    @Override
    public void onSuccessfulIncomingCall(Call incomingCall) {
        Log.d(this, "onSuccessfulIncomingCall");
        setCallState(incomingCall, CallState.RINGING);

        if (hasMaximumRingingCalls()) {
            incomingCall.reject(false, null);
            // since the call was not added to the list of calls, we have to call the missed
            // call notifier and the call logger manually.
            mMissedCallNotifier.showMissedCallNotification(incomingCall);
            mCallLogManager.logCall(incomingCall, Calls.MISSED_TYPE);
        } else {
            addCall(incomingCall);
        }
    }

    @Override
    public void onFailedIncomingCall(Call call) {
        setCallState(call, CallState.DISCONNECTED);
        call.removeListener(this);
    }

    @Override
    public void onSuccessfulUnknownCall(Call call, int callState) {
        setCallState(call, callState);
        Log.i(this, "onSuccessfulUnknownCall for call %s", call);
        addCall(call);
    }

    @Override
    public void onFailedUnknownCall(Call call) {
        Log.i(this, "onFailedUnknownCall for call %s", call);
        setCallState(call, CallState.DISCONNECTED);
        call.removeListener(this);
    }

    @Override
    public void onRingbackRequested(Call call, boolean ringback) {
        for (CallsManagerListener listener : mListeners) {
            listener.onRingbackRequested(call, ringback);
        }
    }

    @Override
    public void onPostDialWait(Call call, String remaining) {
        mInCallController.onPostDialWait(call, remaining);
    }

    @Override
    public void onParentChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateForegroundCall();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onChildrenChanged(Call call) {
        // parent-child relationship affects which call should be foreground, so do an update.
        updateForegroundCall();
        for (CallsManagerListener listener : mListeners) {
            listener.onIsConferencedChanged(call);
        }
    }

    @Override
    public void onIsVoipAudioModeChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onIsVoipAudioModeChanged(call);
        }
    }

    @Override
    public void onVideoStateChanged(Call call) {
        for (CallsManagerListener listener : mListeners) {
            listener.onVideoStateChanged(call);
        }
    }

    ImmutableCollection<Call> getCalls() {
        return ImmutableList.copyOf(mCalls);
    }

    Call getForegroundCall() {
        return mForegroundCall;
    }

    Ringer getRinger() {
        return mRinger;
    }

    InCallController getInCallController() {
        return mInCallController;
    }

    boolean hasEmergencyCall() {
        for (Call call : mCalls) {
            if (call.isEmergencyCall()) {
                return true;
            }
        }
        return false;
    }

    AudioState getAudioState() {
        return mCallAudioManager.getAudioState();
    }

    boolean isTtySupported() {
        return mTtyManager.isTtySupported();
    }

    int getCurrentTtyMode() {
        return mTtyManager.getCurrentTtyMode();
    }

    void addListener(CallsManagerListener listener) {
        mListeners.add(listener);
    }

    void removeListener(CallsManagerListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Starts the process to attach the call to a connection service.
     *
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    void processIncomingCallIntent(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Log.d(this, "processIncomingCallIntent");
        Uri handle = extras.getParcelable(TelephonyManager.EXTRA_INCOMING_NUMBER);
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                true /* isIncoming */,
                false /* isConference */);

        call.setExtras(extras);
        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    void addNewUnknownCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        Uri handle = extras.getParcelable(TelecomManager.EXTRA_UNKNOWN_CALL_HANDLE);
        Log.i(this, "addNewUnknownCall with handle: %s", Log.pii(handle));
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccountHandle,
                // Use onCreateIncomingConnection in TelephonyConnectionService, so that we attach
                // to the existing connection instead of trying to create a new one.
                true /* isIncoming */,
                false /* isConference */);
        call.setConnectTimeMillis(System.currentTimeMillis());
        call.setIsUnknown(true);
        call.setExtras(extras);
        call.addListener(this);
        call.startCreateConnection(mPhoneAccountRegistrar);
    }

    /**
     * Kicks off the first steps to creating an outgoing call so that InCallUI can launch.
     *
     * @param handle Handle to connect the call with.
     * @param phoneAccountHandle The phone account which contains the component name of the
     *        connection service to use for this call.
     * @param extras The optional extras Bundle passed with the intent used for the incoming call.
     */
    Call startOutgoingCall(Uri handle, PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        // Create a call with original handle. The handle may be changed when the call is attached
        // to a connection service, but in most cases will remain the same.
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                handle,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                null /* phoneAccountHandle */,
                false /* isIncoming */,
                false /* isConference */);

        List<PhoneAccountHandle> accounts =
                mPhoneAccountRegistrar.getCallCapablePhoneAccounts(handle.getScheme());

        // Only dial with the requested phoneAccount if it is still valid. Otherwise treat this call
        // as if a phoneAccount was not specified (does the default behavior instead).
        // Note: We will not attempt to dial with a requested phoneAccount if it is disabled.
        if (phoneAccountHandle != null) {
            if (!accounts.contains(phoneAccountHandle)) {
                phoneAccountHandle = null;
            }
        }

        if (phoneAccountHandle == null) {
            // No preset account, check if default exists that supports the URI scheme for the
            // handle.
            PhoneAccountHandle defaultAccountHandle =
                    mPhoneAccountRegistrar.getDefaultOutgoingPhoneAccount(
                            handle.getScheme());
            if (defaultAccountHandle != null) {
                phoneAccountHandle = defaultAccountHandle;
            }
        }

        call.setTargetPhoneAccount(phoneAccountHandle);

        boolean isEmergencyCall = TelephonyUtil.shouldProcessAsEmergency(mContext,
                call.getHandle());
        boolean isPotentialInCallMMICode = isPotentialInCallMMICode(handle);

        // Do not support any more live calls.  Our options are to move a call to hold, disconnect
        // a call, or cancel this call altogether.
        if (!isPotentialInCallMMICode && !makeRoomForOutgoingCall(call, isEmergencyCall)) {
            // just cancel at this point.
            return null;
        }

        if (phoneAccountHandle == null && accounts.size() > 1 && !isEmergencyCall) {
            // This is the state where the user is expected to select an account
            call.setState(CallState.PRE_DIAL_WAIT);
            extras.putParcelableList(android.telecom.Call.AVAILABLE_PHONE_ACCOUNTS, accounts);
        } else {
            call.setState(CallState.CONNECTING);
        }

        call.setExtras(extras);

        // Do not add the call if it is a potential MMI code.
        if (isPotentialMMICode(handle) || isPotentialInCallMMICode) {
            call.addListener(this);
        } else {
            addCall(call);
        }

        return call;
    }

    /**
     * Attempts to issue/connect the specified call.
     *
     * @param handle Handle to connect the call with.
     * @param gatewayInfo Optional gateway information that can be used to route the call to the
     *        actual dialed handle via a gateway provider. May be null.
     * @param speakerphoneOn Whether or not to turn the speakerphone on once the call connects.
     * @param videoState The desired video state for the outgoing call.
     */
    void placeOutgoingCall(Call call, Uri handle, GatewayInfo gatewayInfo, boolean speakerphoneOn,
            int videoState) {
        if (call == null) {
            // don't do anything if the call no longer exists
            Log.i(this, "Canceling unknown call.");
            return;
        }

        final Uri uriHandle = (gatewayInfo == null) ? handle : gatewayInfo.getGatewayAddress();

        if (gatewayInfo == null) {
            Log.i(this, "Creating a new outgoing call with handle: %s", Log.piiHandle(uriHandle));
        } else {
            Log.i(this, "Creating a new outgoing call with gateway handle: %s, original handle: %s",
                    Log.pii(uriHandle), Log.pii(handle));
        }

        call.setHandle(uriHandle);
        call.setGatewayInfo(gatewayInfo);
        call.setStartWithSpeakerphoneOn(speakerphoneOn);
        call.setVideoState(videoState);

        boolean isEmergencyCall = TelephonyUtil.shouldProcessAsEmergency(mContext,
                call.getHandle());
        if (isEmergencyCall) {
            // Emergency -- CreateConnectionProcessor will choose accounts automatically
            call.setTargetPhoneAccount(null);
        }

        if (call.getTargetPhoneAccount() != null || isEmergencyCall) {
            // If the account has been set, proceed to place the outgoing call.
            // Otherwise the connection will be initiated when the account is set by the user.
            call.startCreateConnection(mPhoneAccountRegistrar);
        }
    }

    /**
     * Attempts to start a conference call for the specified call.
     *
     * @param call The call to conference.
     * @param otherCall The other call to conference with.
     */
    void conference(Call call, Call otherCall) {
        call.conferenceWith(otherCall);
    }

    /**
     * Instructs Telecom to answer the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to answer said call.
     *
     * @param call The call to answer.
     * @param videoState The video state in which to answer the call.
     */
    void answerCall(Call call, int videoState) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to answer a non-existent call %s", call);
        } else {
            // If the foreground call is not the ringing call and it is currently isActive() or
            // STATE_DIALING, put it on hold before answering the call.
            if (mForegroundCall != null && mForegroundCall != call &&
                    (mForegroundCall.isActive() ||
                     mForegroundCall.getState() == CallState.DIALING)) {
                if (0 == (mForegroundCall.getCallCapabilities() & PhoneCapabilities.HOLD)) {
                    // This call does not support hold.  If it is from a different connection
                    // service, then disconnect it, otherwise allow the connection service to
                    // figure out the right states.
                    if (mForegroundCall.getConnectionService() != call.getConnectionService()) {
                        mForegroundCall.disconnect();
                    }
                } else {
                    Log.v(this, "Holding active/dialing call %s before answering incoming call %s.",
                            mForegroundCall, call);
                    mForegroundCall.hold();
                }
                // TODO: Wait until we get confirmation of the active call being
                // on-hold before answering the new call.
                // TODO: Import logic from CallManager.acceptCall()
            }

            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallAnswered(call);
            }

            // We do not update the UI until we get confirmation of the answer() through
            // {@link #markCallAsActive}.
            call.answer(videoState);
        }
    }

    /**
     * Instructs Telecom to reject the specified call. Intended to be invoked by the in-call
     * app through {@link InCallAdapter} after Telecom notifies it of an incoming call followed by
     * the user opting to reject said call.
     */
    void rejectCall(Call call, boolean rejectWithMessage, String textMessage) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to reject a non-existent call %s", call);
        } else {
            for (CallsManagerListener listener : mListeners) {
                listener.onIncomingCallRejected(call, rejectWithMessage, textMessage);
            }
            call.reject(rejectWithMessage, textMessage);
        }
    }

    /**
     * Instructs Telecom to play the specified DTMF tone within the specified call.
     *
     * @param digit The DTMF digit to play.
     */
    void playDtmfTone(Call call, char digit) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to play DTMF in a non-existent call %s", call);
        } else {
            call.playDtmfTone(digit);
            mDtmfLocalTonePlayer.playTone(call, digit);
        }
    }

    /**
     * Instructs Telecom to stop the currently playing DTMF tone, if any.
     */
    void stopDtmfTone(Call call) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to stop DTMF in a non-existent call %s", call);
        } else {
            call.stopDtmfTone();
            mDtmfLocalTonePlayer.stopTone(call);
        }
    }

    /**
     * Instructs Telecom to continue (or not) the current post-dial DTMF string, if any.
     */
    void postDialContinue(Call call, boolean proceed) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Request to continue post-dial string in a non-existent call %s", call);
        } else {
            call.postDialContinue(proceed);
        }
    }

    /**
     * Instructs Telecom to disconnect the specified call. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the end-call button.
     */
    void disconnectCall(Call call) {
        Log.v(this, "disconnectCall %s", call);

        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to disconnect", call);
        } else {
            mLocallyDisconnectingCalls.add(call);
            call.disconnect();
        }
    }

    /**
     * Instructs Telecom to disconnect all calls.
     */
    void disconnectAllCalls() {
        Log.v(this, "disconnectAllCalls");

        for (Call call : mCalls) {
            disconnectCall(call);
        }
    }


    /**
     * Instructs Telecom to put the specified call on hold. Intended to be invoked by the
     * in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered by
     * the user hitting the hold button during an active call.
     */
    void holdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be put on hold", call);
        } else {
            Log.d(this, "Putting call on hold: (%s)", call);
            call.hold();
        }
    }

    /**
     * Instructs Telecom to release the specified call from hold. Intended to be invoked by
     * the in-call app through {@link InCallAdapter} for an ongoing call. This is usually triggered
     * by the user hitting the hold button during a held call.
     */
    void unholdCall(Call call) {
        if (!mCalls.contains(call)) {
            Log.w(this, "Unknown call (%s) asked to be removed from hold", call);
        } else {
            Log.d(this, "unholding call: (%s)", call);
            for (Call c : mCalls) {
                if (c != null && c.isAlive() && c != call) {
                    c.hold();
                }
            }
            call.unhold();
        }
    }

    /** Called by the in-call UI to change the mute state. */
    void mute(boolean shouldMute) {
        mCallAudioManager.mute(shouldMute);
    }

    /**
      * Called by the in-call UI to change the audio route, for example to change from earpiece to
      * speaker phone.
      */
    void setAudioRoute(int route) {
        mCallAudioManager.setAudioRoute(route);
    }

    /** Called by the in-call UI to turn the proximity sensor on. */
    void turnOnProximitySensor() {
        mProximitySensorManager.turnOn();
    }

    /**
     * Called by the in-call UI to turn the proximity sensor off.
     * @param screenOnImmediately If true, the screen will be turned on immediately. Otherwise,
     *        the screen will be kept off until the proximity sensor goes negative.
     */
    void turnOffProximitySensor(boolean screenOnImmediately) {
        mProximitySensorManager.turnOff(screenOnImmediately);
    }

    void phoneAccountSelected(Call call, PhoneAccountHandle account) {
        if (!mCalls.contains(call)) {
            Log.i(this, "Attempted to add account to unknown call %s", call);
        } else {
            // TODO: There is an odd race condition here. Since NewOutgoingCallIntentBroadcaster and
            // the PRE_DIAL_WAIT sequence run in parallel, if the user selects an account before the
            // NEW_OUTGOING_CALL sequence finishes, we'll start the call immediately without
            // respecting a rewritten number or a canceled number. This is unlikely since
            // NEW_OUTGOING_CALL sequence, in practice, runs a lot faster than the user selecting
            // a phone account from the in-call UI.
            call.setTargetPhoneAccount(account);

            // Note: emergency calls never go through account selection dialog so they never
            // arrive here.
            if (makeRoomForOutgoingCall(call, false /* isEmergencyCall */)) {
                call.startCreateConnection(mPhoneAccountRegistrar);
            } else {
                call.disconnect();
            }
        }
    }

    /** Called when the audio state changes. */
    void onAudioStateChanged(AudioState oldAudioState, AudioState newAudioState) {
        Log.v(this, "onAudioStateChanged, audioState: %s -> %s", oldAudioState, newAudioState);
        for (CallsManagerListener listener : mListeners) {
            listener.onAudioStateChanged(oldAudioState, newAudioState);
        }
    }

    void markCallAsRinging(Call call) {
        setCallState(call, CallState.RINGING);
    }

    void markCallAsDialing(Call call) {
        setCallState(call, CallState.DIALING);
    }

    void markCallAsActive(Call call) {
        if (call.getConnectTimeMillis() == 0) {
            call.setConnectTimeMillis(System.currentTimeMillis());
        }
        setCallState(call, CallState.ACTIVE);

        if (call.getStartWithSpeakerphoneOn()) {
            setAudioRoute(AudioState.ROUTE_SPEAKER);
        }
    }

    void markCallAsOnHold(Call call) {
        setCallState(call, CallState.ON_HOLD);
    }

    /**
     * Marks the specified call as STATE_DISCONNECTED and notifies the in-call app. If this was the
     * last live call, then also disconnect from the in-call controller.
     *
     * @param disconnectCause The disconnect cause, see {@link android.telecomm.DisconnectCause}.
     */
    void markCallAsDisconnected(Call call, DisconnectCause disconnectCause) {
        call.setDisconnectCause(disconnectCause);
        setCallState(call, CallState.DISCONNECTED);
    }

    /**
     * Removes an existing disconnected call, and notifies the in-call app.
     */
    void markCallAsRemoved(Call call) {
        removeCall(call);
        if (mLocallyDisconnectingCalls.contains(call)) {
            mLocallyDisconnectingCalls.remove(call);
            if (mForegroundCall != null && mForegroundCall.getState() == CallState.ON_HOLD) {
                mForegroundCall.unhold();
            }
        }
    }

    /**
     * Cleans up any calls currently associated with the specified connection service when the
     * service binder disconnects unexpectedly.
     *
     * @param service The connection service that disconnected.
     */
    void handleConnectionServiceDeath(ConnectionServiceWrapper service) {
        if (service != null) {
            for (Call call : mCalls) {
                if (call.getConnectionService() == service) {
                    markCallAsDisconnected(call, new DisconnectCause(DisconnectCause.ERROR));
                }
            }
        }
    }

    boolean hasAnyCalls() {
        return !mCalls.isEmpty();
    }

    boolean hasActiveOrHoldingCall() {
        return getFirstCallWithState(CallState.ACTIVE, CallState.ON_HOLD) != null;
    }

    boolean hasRingingCall() {
        return getFirstCallWithState(CallState.RINGING) != null;
    }

    boolean onMediaButton(int type) {
        if (hasAnyCalls()) {
            if (HeadsetMediaButton.SHORT_PRESS == type) {
                Call ringingCall = getFirstCallWithState(CallState.RINGING);
                if (ringingCall == null) {
                    mCallAudioManager.toggleMute();
                    return true;
                } else {
                    ringingCall.answer(ringingCall.getVideoState());
                    return true;
                }
            } else if (HeadsetMediaButton.LONG_PRESS == type) {
                Log.d(this, "handleHeadsetHook: longpress -> hangup");
                Call callToHangup = getFirstCallWithState(
                        CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.ON_HOLD);
                if (callToHangup != null) {
                    callToHangup.disconnect();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks to see if the specified call is the only high-level call and if so, enable the
     * "Add-call" button. We allow you to add a second call but not a third or beyond.
     *
     * @param call The call to test for add-call.
     * @return Whether the add-call feature should be enabled for the call.
     */
    protected boolean isAddCallCapable(Call call) {
        if (call.getParentCall() != null) {
            // Never true for child calls.
            return false;
        }

        // Use canManageConference as a mechanism to check if the call is CDMA.
        // Disable "Add Call" for CDMA calls which are conference calls.
        boolean canManageConference = PhoneCapabilities.MANAGE_CONFERENCE
                == (call.getCallCapabilities() & PhoneCapabilities.MANAGE_CONFERENCE);
        if (call.isConference() && !canManageConference) {
            return false;
        }

        // Loop through all the other calls and there exists a top level (has no parent) call
        // that is not the specified call, return false.
        for (Call otherCall : mCalls) {
            if (call != otherCall && otherCall.getParentCall() == null) {
                return false;
            }
        }
        return true;
    }

    Call getRingingCall() {
        return getFirstCallWithState(CallState.RINGING);
    }

    Call getActiveCall() {
        return getFirstCallWithState(CallState.ACTIVE);
    }

    Call getDialingCall() {
        return getFirstCallWithState(CallState.DIALING);
    }

    Call getHeldCall() {
        return getFirstCallWithState(CallState.ON_HOLD);
    }

    int getNumHeldCalls() {
        int count = 0;
        for (Call call : mCalls) {
            if (call.getParentCall() == null && call.getState() == CallState.ON_HOLD) {
                count++;
            }
        }
        return count;
    }

    Call getFirstCallWithState(int... states) {
        return getFirstCallWithState(null, states);
    }

    /**
     * Returns the first call that it finds with the given states. The states are treated as having
     * priority order so that any call with the first state will be returned before any call with
     * states listed later in the parameter list.
     *
     * @param callToSkip Call that this method should skip while searching
     */
    Call getFirstCallWithState(Call callToSkip, int... states) {
        for (int currentState : states) {
            // check the foreground first
            if (mForegroundCall != null && mForegroundCall.getState() == currentState) {
                return mForegroundCall;
            }

            for (Call call : mCalls) {
                if (Objects.equals(callToSkip, call)) {
                    continue;
                }

                // Only operate on top-level calls
                if (call.getParentCall() != null) {
                    continue;
                }

                if (currentState == call.getState()) {
                    return call;
                }
            }
        }
        return null;
    }

    Call createConferenceCall(
            PhoneAccountHandle phoneAccount,
            ParcelableConference parcelableConference) {
        Call call = new Call(
                mContext,
                mConnectionServiceRepository,
                null /* handle */,
                null /* gatewayInfo */,
                null /* connectionManagerPhoneAccount */,
                phoneAccount,
                false /* isIncoming */,
                true /* isConference */);

        setCallState(call, Call.getStateFromConnectionState(parcelableConference.getState()));
        if (call.getState() == CallState.ACTIVE) {
            call.setConnectTimeMillis(System.currentTimeMillis());
        }
        call.setCallCapabilities(parcelableConference.getCapabilities());

        // TODO: Move this to be a part of addCall()
        call.addListener(this);
        addCall(call);
        return call;
    }

    /**
     * @return the call state currently tracked by {@link PhoneStateBroadcaster}
     */
    int getCallState() {
        return mPhoneStateBroadcaster.getCallState();
    }

    /**
     * Retrieves the {@link PhoneAccountRegistrar}.
     *
     * @return The {@link PhoneAccountRegistrar}.
     */
    PhoneAccountRegistrar getPhoneAccountRegistrar() {
        return mPhoneAccountRegistrar;
    }

    /**
     * Retrieves the {@link MissedCallNotifier}
     * @return The {@link MissedCallNotifier}.
     */
    MissedCallNotifier getMissedCallNotifier() {
        return mMissedCallNotifier;
    }

    /**
     * Adds the specified call to the main list of live calls.
     *
     * @param call The call to add.
     */
    private void addCall(Call call) {
        Log.v(this, "addCall(%s)", call);

        call.addListener(this);
        mCalls.add(call);

        // TODO: Update mForegroundCall prior to invoking
        // onCallAdded for calls which immediately take the foreground (like the first call).
        for (CallsManagerListener listener : mListeners) {
            listener.onCallAdded(call);
        }
        updateForegroundCall();
    }

    private void removeCall(Call call) {
        Log.v(this, "removeCall(%s)", call);

        call.setParentCall(null);  // need to clean up parent relationship before destroying.
        call.removeListener(this);
        call.clearConnectionService();

        boolean shouldNotify = false;
        if (mCalls.contains(call)) {
            mCalls.remove(call);
            shouldNotify = true;
        }

        // Only broadcast changes for calls that are being tracked.
        if (shouldNotify) {
            for (CallsManagerListener listener : mListeners) {
                listener.onCallRemoved(call);
            }
            updateForegroundCall();
        }

        // Now that a call has been removed, other calls may gain new call capabilities (for
        // example, if only one call is left, it is now add-call capable again). Trigger the
        // recalculation of the call's current capabilities by forcing an update. (See
        // InCallController.toParcelableCall()).
        for (Call otherCall : mCalls) {
            otherCall.setCallCapabilities(otherCall.getCallCapabilities(), true /* forceUpdate */);
        }
    }

    /**
     * Sets the specified state on the specified call.
     *
     * @param call The call.
     * @param newState The new state of the call.
     */
    private void setCallState(Call call, int newState) {
        if (call == null) {
            return;
        }
        int oldState = call.getState();
        Log.i(this, "setCallState %s -> %s, call: %s", CallState.toString(oldState),
                CallState.toString(newState), call);
        if (newState != oldState) {
            // Unfortunately, in the telephony world the radio is king. So if the call notifies
            // us that the call is in a particular state, we allow it even if it doesn't make
            // sense (e.g., STATE_ACTIVE -> STATE_RINGING).
            // TODO: Consider putting a stop to the above and turning CallState
            // into a well-defined state machine.
            // TODO: Define expected state transitions here, and log when an
            // unexpected transition occurs.
            call.setState(newState);

            // Only broadcast state change for calls that are being tracked.
            if (mCalls.contains(call)) {
                for (CallsManagerListener listener : mListeners) {
                    listener.onCallStateChanged(call, oldState, newState);
                }
                updateForegroundCall();
            }
        }
    }

    /**
     * Checks which call should be visible to the user and have audio focus.
     */
    private void updateForegroundCall() {
        Call newForegroundCall = null;
        for (Call call : mCalls) {
            // TODO: Foreground-ness needs to be explicitly set. No call, regardless
            // of its state will be foreground by default and instead the connection service should
            // be notified when its calls enter and exit foreground state. Foreground will mean that
            // the call should play audio and listen to microphone if it wants.

            // Only top-level calls can be in foreground
            if (call.getParentCall() != null) {
                continue;
            }

            // Active calls have priority.
            if (call.isActive()) {
                newForegroundCall = call;
                break;
            }

            if (call.isAlive() || call.getState() == CallState.RINGING) {
                newForegroundCall = call;
                // Don't break in case there's an active call that has priority.
            }
        }

        if (newForegroundCall != mForegroundCall) {
            Log.v(this, "Updating foreground call, %s -> %s.", mForegroundCall, newForegroundCall);
            Call oldForegroundCall = mForegroundCall;
            mForegroundCall = newForegroundCall;

            for (CallsManagerListener listener : mListeners) {
                listener.onForegroundCallChanged(oldForegroundCall, mForegroundCall);
            }
        }
    }

    private boolean isPotentialMMICode(Uri handle) {
        return (handle != null && handle.getSchemeSpecificPart() != null
                && handle.getSchemeSpecificPart().contains("#"));
    }

    /**
     * Determines if a dialed number is potentially an In-Call MMI code.  In-Call MMI codes are
     * MMI codes which can be dialed when one or more calls are in progress.
     * <P>
     * Checks for numbers formatted similar to the MMI codes defined in:
     * {@link com.android.internal.telephony.gsm.GSMPhone#handleInCallMmiCommands(String)}
     * and
     * {@link com.android.internal.telephony.imsphone.ImsPhone#handleInCallMmiCommands(String)}
     *
     * @param handle The URI to call.
     * @return {@code True} if the URI represents a number which could be an in-call MMI code.
     */
    private boolean isPotentialInCallMMICode(Uri handle) {
        if (handle != null && handle.getSchemeSpecificPart() != null &&
                handle.getScheme().equals(PhoneAccount.SCHEME_TEL)) {

            String dialedNumber = handle.getSchemeSpecificPart();
            return (dialedNumber.equals("0") ||
                    (dialedNumber.startsWith("1") && dialedNumber.length() <= 2) ||
                    (dialedNumber.startsWith("2") && dialedNumber.length() <= 2) ||
                    dialedNumber.equals("3") ||
                    dialedNumber.equals("4") ||
                    dialedNumber.equals("5"));
        }
        return false;
    }

    private int getNumCallsWithState(int... states) {
        int count = 0;
        for (int state : states) {
            for (Call call : mCalls) {
                if (call.getState() == state) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean hasMaximumLiveCalls() {
        return MAXIMUM_LIVE_CALLS <= getNumCallsWithState(LIVE_CALL_STATES);
    }

    private boolean hasMaximumHoldingCalls() {
        return MAXIMUM_HOLD_CALLS <= getNumCallsWithState(CallState.ON_HOLD);
    }

    private boolean hasMaximumRingingCalls() {
        return MAXIMUM_RINGING_CALLS <= getNumCallsWithState(CallState.RINGING);
    }

    private boolean hasMaximumOutgoingCalls() {
        return MAXIMUM_OUTGOING_CALLS <= getNumCallsWithState(OUTGOING_CALL_STATES);
    }

    private boolean makeRoomForOutgoingCall(Call call, boolean isEmergency) {
        if (hasMaximumLiveCalls()) {
            // NOTE: If the amount of live calls changes beyond 1, this logic will probably
            // have to change.
            Call liveCall = getFirstCallWithState(call, LIVE_CALL_STATES);

            if (call == liveCall) {
                // If the call is already the foreground call, then we are golden.
                // This can happen after the user selects an account in the PRE_DIAL_WAIT
                // state since the call was already populated into the list.
                return true;
            }

            if (hasMaximumOutgoingCalls()) {
                // Disconnect the current outgoing call if it's not an emergency call. If the user
                // tries to make two outgoing calls to different emergency call numbers, we will try
                // to connect the first outgoing call.
                if (isEmergency) {
                    Call outgoingCall = getFirstCallWithState(OUTGOING_CALL_STATES);
                    if (!outgoingCall.isEmergencyCall()) {
                        outgoingCall.disconnect();
                        return true;
                    }
                }
                return false;
            }

            if (hasMaximumHoldingCalls()) {
                // There is no more room for any more calls, unless it's an emergency.
                if (isEmergency) {
                    // Kill the current active call, this is easier then trying to disconnect a
                    // holding call and hold an active call.
                    liveCall.disconnect();
                    return true;
                }
                return false;  // No more room!
            }

            // We have room for at least one more holding call at this point.

            // First thing, if we are trying to make a call with the same phone account as the live
            // call, then allow it so that the connection service can make its own decision about
            // how to handle the new call relative to the current one.
            if (Objects.equals(liveCall.getTargetPhoneAccount(), call.getTargetPhoneAccount())) {
                return true;
            } else if (call.getTargetPhoneAccount() == null) {
                // Without a phone account, we can't say reliably that the call will fail.
                // If the user chooses the same phone account as the live call, then it's
                // still possible that the call can be made (like with CDMA calls not supporting
                // hold but they still support adding a call by going immediately into conference
                // mode). Return true here and we'll run this code again after user chooses an
                // account.
                return true;
            }

            // Try to hold the live call before attempting the new outgoing call.
            if (liveCall.can(PhoneCapabilities.HOLD)) {
                liveCall.hold();
                return true;
            }

            // The live call cannot be held so we're out of luck here.  There's no room.
            return false;
        }
        return true;
    }

    /**
     * Dumps the state of the {@link CallsManager}.
     *
     * @param pw The {@code IndentingPrintWriter} to write the state to.
     */
    public void dump(IndentingPrintWriter pw) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        pw.increaseIndent();
        if (mCalls != null) {
            pw.println("mCalls: ");
            pw.increaseIndent();
            for (Call call : mCalls) {
                pw.println(call);
            }
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
    }
}
