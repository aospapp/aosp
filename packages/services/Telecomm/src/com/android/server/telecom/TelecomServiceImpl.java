/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telecom.CallState;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;


// TODO: Needed for move to system service: import com.android.internal.R;
import com.android.internal.telecom.ITelecomService;
import com.android.internal.util.IndentingPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Implementation of the ITelecom interface.
 */
public class TelecomServiceImpl extends ITelecomService.Stub {
    private static final String REGISTER_PROVIDER_OR_SUBSCRIPTION =
            "com.android.server.telecom.permission.REGISTER_PROVIDER_OR_SUBSCRIPTION";
    private static final String REGISTER_CONNECTION_MANAGER =
            "com.android.server.telecom.permission.REGISTER_CONNECTION_MANAGER";

    /** The context. */
    private Context mContext;

    /** ${inheritDoc} */
    @Override
    public IBinder asBinder() {
        return super.asBinder();
    }

 /**
     * A request object for use with {@link MainThreadHandler}. Requesters should wait() on the
     * request after sending. The main thread will notify the request when it is complete.
     */
    private static final class MainThreadRequest {
        /** The result of the request that is run on the main thread */
        public Object result;
    }

    /**
     * A handler that processes messages on the main thread in the phone process. Since many
     * of the Phone calls are not thread safe this is needed to shuttle the requests from the
     * inbound binder threads to the main thread in the phone process.
     */
    private final class MainThreadHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof MainThreadRequest) {
                MainThreadRequest request = (MainThreadRequest) msg.obj;
                Object result = null;
                switch (msg.what) {
                    case MSG_SILENCE_RINGER:
                        mCallsManager.getRinger().silence();
                        break;
                    case MSG_SHOW_CALL_SCREEN:
                        mCallsManager.getInCallController().bringToForeground(msg.arg1 == 1);
                        break;
                    case MSG_END_CALL:
                        result = endCallInternal();
                        break;
                    case MSG_ACCEPT_RINGING_CALL:
                        acceptRingingCallInternal();
                        break;
                    case MSG_CANCEL_MISSED_CALLS_NOTIFICATION:
                        mMissedCallNotifier.clearMissedCalls();
                        break;
                    case MSG_IS_TTY_SUPPORTED:
                        result = mCallsManager.isTtySupported();
                        break;
                    case MSG_GET_CURRENT_TTY_MODE:
                        result = mCallsManager.getCurrentTtyMode();
                        break;
                }

                if (result != null) {
                    request.result = result;
                    synchronized(request) {
                        request.notifyAll();
                    }
                }
            }
        }
    }

    /** Private constructor; @see init() */
    private static final String TAG = TelecomServiceImpl.class.getSimpleName();

    private static final String SERVICE_NAME = "telecom";

    private static final int MSG_SILENCE_RINGER = 1;
    private static final int MSG_SHOW_CALL_SCREEN = 2;
    private static final int MSG_END_CALL = 3;
    private static final int MSG_ACCEPT_RINGING_CALL = 4;
    private static final int MSG_CANCEL_MISSED_CALLS_NOTIFICATION = 5;
    private static final int MSG_IS_TTY_SUPPORTED = 6;
    private static final int MSG_GET_CURRENT_TTY_MODE = 7;

    /** The singleton instance. */
    private static TelecomServiceImpl sInstance;

    private final MainThreadHandler mMainThreadHandler = new MainThreadHandler();
    private final CallsManager mCallsManager;
    private final MissedCallNotifier mMissedCallNotifier;
    private final PhoneAccountRegistrar mPhoneAccountRegistrar;
    private final AppOpsManager mAppOpsManager;

    public TelecomServiceImpl(
            MissedCallNotifier missedCallNotifier, PhoneAccountRegistrar phoneAccountRegistrar,
            CallsManager callsManager, Context context) {
        mMissedCallNotifier = missedCallNotifier;
        mPhoneAccountRegistrar = phoneAccountRegistrar;
        mCallsManager = callsManager;
        mContext = context;
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
    }

    //
    // Implementation of the ITelecomService interface.
    //

    @Override
    public PhoneAccountHandle getDefaultOutgoingPhoneAccount(String uriScheme) {
        try {
            return mPhoneAccountRegistrar.getDefaultOutgoingPhoneAccount(uriScheme);
        } catch (Exception e) {
            Log.e(this, e, "getDefaultOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public PhoneAccountHandle getUserSelectedOutgoingPhoneAccount() {
        try {
            return mPhoneAccountRegistrar.getUserSelectedOutgoingPhoneAccount();
        } catch (Exception e) {
            Log.e(this, e, "getUserSelectedOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public void setUserSelectedOutgoingPhoneAccount(PhoneAccountHandle accountHandle) {
        enforceModifyPermission();

        try {
            mPhoneAccountRegistrar.setUserSelectedOutgoingPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "setUserSelectedOutgoingPhoneAccount");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getCallCapablePhoneAccounts() {
        try {
            return mPhoneAccountRegistrar.getCallCapablePhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getCallCapablePhoneAccounts");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getPhoneAccountsSupportingScheme(String uriScheme) {
        try {
            return mPhoneAccountRegistrar.getCallCapablePhoneAccounts(uriScheme);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccountsSupportingScheme");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getPhoneAccountsForPackage(String packageName) {
        try {
            return mPhoneAccountRegistrar.getPhoneAccountsForPackage(packageName);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccountsForPackage");
            throw e;
        }
    }

    @Override
    public PhoneAccount getPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            return mPhoneAccountRegistrar.getPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "getPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public int getAllPhoneAccountsCount() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccountsCount();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccountsCount");
            throw e;
        }
    }

    @Override
    public List<PhoneAccount> getAllPhoneAccounts() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccounts");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getAllPhoneAccountHandles() {
        try {
            return mPhoneAccountRegistrar.getAllPhoneAccountHandles();
        } catch (Exception e) {
            Log.e(this, e, "getAllPhoneAccounts");
            throw e;
        }
    }

    @Override
    public PhoneAccountHandle getSimCallManager() {
        try {
            return mPhoneAccountRegistrar.getSimCallManager();
        } catch (Exception e) {
            Log.e(this, e, "getSimCallManager");
            throw e;
        }
    }

    @Override
    public void setSimCallManager(PhoneAccountHandle accountHandle) {
        enforceModifyPermission();

        try {
            mPhoneAccountRegistrar.setSimCallManager(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "setSimCallManager");
            throw e;
        }
    }

    @Override
    public List<PhoneAccountHandle> getSimCallManagers() {
        try {
            return mPhoneAccountRegistrar.getConnectionManagerPhoneAccounts();
        } catch (Exception e) {
            Log.e(this, e, "getSimCallManagers");
            throw e;
        }
    }

    @Override
    public void registerPhoneAccount(PhoneAccount account) {
        try {
            enforcePhoneAccountModificationForPackage(
                    account.getAccountHandle().getComponentName().getPackageName());
            if (account.hasCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER) ||
                account.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) {
                enforceRegisterProviderOrSubscriptionPermission();
            }
            if (account.hasCapabilities(PhoneAccount.CAPABILITY_CONNECTION_MANAGER)) {
                enforceRegisterConnectionManagerPermission();
            }

            mPhoneAccountRegistrar.registerPhoneAccount(account);
        } catch (Exception e) {
            Log.e(this, e, "registerPhoneAccount %s", account);
            throw e;
        }
    }

    @Override
    public void unregisterPhoneAccount(PhoneAccountHandle accountHandle) {
        try {
            enforcePhoneAccountModificationForPackage(
                    accountHandle.getComponentName().getPackageName());
            mPhoneAccountRegistrar.unregisterPhoneAccount(accountHandle);
        } catch (Exception e) {
            Log.e(this, e, "unregisterPhoneAccount %s", accountHandle);
            throw e;
        }
    }

    @Override
    public void clearAccounts(String packageName) {
        try {
            enforcePhoneAccountModificationForPackage(packageName);
            mPhoneAccountRegistrar.clearAccounts(packageName);
        } catch (Exception e) {
            Log.e(this, e, "clearAccounts %s", packageName);
            throw e;
        }
    }

    /**
     * @see android.telecom.TelecomManager#silenceRinger
     */
    @Override
    public void silenceRinger() {
        Log.d(this, "silenceRinger");
        enforceModifyPermission();
        sendRequestAsync(MSG_SILENCE_RINGER, 0);
    }

    /**
     * @see android.telecom.TelecomManager#getDefaultPhoneApp
     */
    @Override
    public ComponentName getDefaultPhoneApp() {
        Resources resources = mContext.getResources();
        return new ComponentName(
                resources.getString(R.string.ui_default_package),
                resources.getString(R.string.dialer_default_class));
    }

    /**
     * @see android.telecom.TelecomManager#isInCall
     */
    @Override
    public boolean isInCall() {
        enforceReadPermission();
        // Do not use sendRequest() with this method since it could cause a deadlock with
        // audio service, which we call into from the main thread: AudioManager.setMode().
        final int callState = mCallsManager.getCallState();
        return callState == TelephonyManager.CALL_STATE_OFFHOOK
                || callState == TelephonyManager.CALL_STATE_RINGING;
    }

    /**
     * @see android.telecom.TelecomManager#isRinging
     */
    @Override
    public boolean isRinging() {
        enforceReadPermission();
        return mCallsManager.getCallState() == TelephonyManager.CALL_STATE_RINGING;
    }

    /**
     * @see TelecomManager#getCallState
     */
    @Override
    public int getCallState() {
        return mCallsManager.getCallState();
    }

    /**
     * @see android.telecom.TelecomManager#endCall
     */
    @Override
    public boolean endCall() {
        enforceModifyPermission();
        return (boolean) sendRequest(MSG_END_CALL);
    }

    /**
     * @see android.telecom.TelecomManager#acceptRingingCall
     */
    @Override
    public void acceptRingingCall() {
        enforceModifyPermission();
        sendRequestAsync(MSG_ACCEPT_RINGING_CALL, 0);
    }

    /**
     * @see android.telecom.TelecomManager#showInCallScreen
     */
    @Override
    public void showInCallScreen(boolean showDialpad) {
        enforceReadPermissionOrDefaultDialer();
        sendRequestAsync(MSG_SHOW_CALL_SCREEN, showDialpad ? 1 : 0);
    }

    /**
     * @see android.telecom.TelecomManager#cancelMissedCallsNotification
     */
    @Override
    public void cancelMissedCallsNotification() {
        enforceModifyPermissionOrDefaultDialer();
        sendRequestAsync(MSG_CANCEL_MISSED_CALLS_NOTIFICATION, 0);
    }

    /**
     * @see android.telecom.TelecomManager#handleMmi
     */
    @Override
    public boolean handlePinMmi(String dialString) {
        enforceModifyPermissionOrDefaultDialer();

        // Switch identity so that TelephonyManager checks Telecom's permissions instead.
        long token = Binder.clearCallingIdentity();
        boolean retval = false;
        try {
            retval = getTelephonyManager().handlePinMmi(dialString);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return retval;
    }

    /**
     * @see android.telecom.TelecomManager#isTtySupported
     */
    @Override
    public boolean isTtySupported() {
        enforceReadPermission();
        return (boolean) sendRequest(MSG_IS_TTY_SUPPORTED);
    }

    /**
     * @see android.telecom.TelecomManager#getCurrentTtyMode
     */
    @Override
    public int getCurrentTtyMode() {
        enforceReadPermission();
        return (int) sendRequest(MSG_GET_CURRENT_TTY_MODE);
    }

    /**
     * @see android.telecom.TelecomManager#addNewIncomingCall
     */
    @Override
    public void addNewIncomingCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        if (phoneAccountHandle != null && phoneAccountHandle.getComponentName() != null) {
            mAppOpsManager.checkPackage(
                    Binder.getCallingUid(), phoneAccountHandle.getComponentName().getPackageName());

            Intent intent = new Intent(TelecomManager.ACTION_INCOMING_CALL);
            intent.setPackage(mContext.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            if (extras != null) {
                intent.putExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, extras);
            }

            long token = Binder.clearCallingIdentity();
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * @see android.telecom.TelecomManager#addNewUnknownCall
     */
    @Override
    public void addNewUnknownCall(PhoneAccountHandle phoneAccountHandle, Bundle extras) {
        if (phoneAccountHandle != null && phoneAccountHandle.getComponentName() != null &&
                TelephonyUtil.isPstnComponentName(phoneAccountHandle.getComponentName())) {
            mAppOpsManager.checkPackage(
                    Binder.getCallingUid(), phoneAccountHandle.getComponentName().getPackageName());

            Intent intent = new Intent(TelecomManager.ACTION_NEW_UNKNOWN_CALL);
            intent.setClass(mContext, CallReceiver.class);
            intent.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            intent.putExtras(extras);
            intent.putExtra(CallReceiver.KEY_IS_UNKNOWN_CALL, true);
            intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle);
            mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
        } else {
            Log.i(this, "Null phoneAccountHandle or not initiated by Telephony. Ignoring request"
                    + " to add new unknown call.");
        }
    }

    //
    // Supporting methods for the ITelecomService interface implementation.
    //

    private void acceptRingingCallInternal() {
        Call call = mCallsManager.getFirstCallWithState(CallState.RINGING);
        if (call != null) {
            call.answer(call.getVideoState());
        }
    }

    private boolean endCallInternal() {
        // Always operate on the foreground call if one exists, otherwise get the first call in
        // priority order by call-state.
        Call call = mCallsManager.getForegroundCall();
        if (call == null) {
            call = mCallsManager.getFirstCallWithState(
                    CallState.ACTIVE,
                    CallState.DIALING,
                    CallState.RINGING,
                    CallState.ON_HOLD);
        }

        if (call != null) {
            if (call.getState() == CallState.RINGING) {
                call.reject(false /* rejectWithMessage */, null);
            } else {
                call.disconnect();
            }
            return true;
        }

        return false;
    }

    private void enforcePhoneAccountModificationForPackage(String packageName) {
        // TODO: Use a new telecomm permission for this instead of reusing modify.

        int result = mContext.checkCallingOrSelfPermission(Manifest.permission.MODIFY_PHONE_STATE);

        // Callers with MODIFY_PHONE_STATE can use the PhoneAccount mechanism to implement
        // built-in behavior even when PhoneAccounts are not exposed as a third-part API. They
        // may also modify PhoneAccounts on behalf of any 'packageName'.

        if (result != PackageManager.PERMISSION_GRANTED) {
            // Other callers are only allowed to modify PhoneAccounts if the relevant system
            // feature is enabled ...
            enforceConnectionServiceFeature();
            // ... and the PhoneAccounts they refer to are for their own package.
            enforceCallingPackage(packageName);
        }
    }

    private void enforceReadPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceReadPermission();
        }
    }

    private void enforceModifyPermissionOrDefaultDialer() {
        if (!isDefaultDialerCalling()) {
            enforceModifyPermission();
        }
    }

    private void enforceCallingPackage(String packageName) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
    }

    private void enforceConnectionServiceFeature() {
        enforceFeature(PackageManager.FEATURE_CONNECTION_SERVICE);
    }

    private void enforceRegisterProviderOrSubscriptionPermission() {
        enforcePermission(REGISTER_PROVIDER_OR_SUBSCRIPTION);
    }

    private void enforceRegisterConnectionManagerPermission() {
        enforcePermission(REGISTER_CONNECTION_MANAGER);
    }

    private void enforceReadPermission() {
        enforcePermission(Manifest.permission.READ_PHONE_STATE);
    }

    private void enforceModifyPermission() {
        enforcePermission(Manifest.permission.MODIFY_PHONE_STATE);
    }

    private void enforcePermission(String permission) {
        mContext.enforceCallingOrSelfPermission(permission, null);
    }

    private void enforceFeature(String feature) {
        PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(feature)) {
            throw new UnsupportedOperationException(
                    "System does not support feature " + feature);
        }
    }

    private boolean isDefaultDialerCalling() {
        ComponentName defaultDialerComponent = getDefaultPhoneApp();
        if (defaultDialerComponent != null) {
            try {
                mAppOpsManager.checkPackage(
                        Binder.getCallingUid(), defaultDialerComponent.getPackageName());
                return true;
            } catch (SecurityException e) {
                Log.e(TAG, e, "Could not get default dialer.");
            }
        }
        return false;
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager)mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private MainThreadRequest sendRequestAsync(int command, int arg1) {
        MainThreadRequest request = new MainThreadRequest();
        mMainThreadHandler.obtainMessage(command, arg1, 0, request).sendToTarget();
        return request;
    }

    /**
     * Posts the specified command to be executed on the main thread, waits for the request to
     * complete, and returns the result.
     */
    private Object sendRequest(int command) {
        if (Looper.myLooper() == mMainThreadHandler.getLooper()) {
            MainThreadRequest request = new MainThreadRequest();
            mMainThreadHandler.handleMessage(mMainThreadHandler.obtainMessage(command, request));
            return request.result;
        } else {
            MainThreadRequest request = sendRequestAsync(command, 0);

            // Wait for the request to complete
            synchronized (request) {
                while (request.result == null) {
                    try {
                        request.wait();
                    } catch (InterruptedException e) {
                        // Do nothing, go back and wait until the request is complete
                    }
                }
            }
            return request.result;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mCallsManager != null) {
            pw.println("mCallsManager: ");
            pw.increaseIndent();
            mCallsManager.dump(pw);
            pw.decreaseIndent();
        }
    }
}
