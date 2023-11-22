package com.android.server.telecom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.DisconnectCause;
import android.telephony.PhoneNumberUtils;

/**
 * Single point of entry for all outgoing and incoming calls. {@link CallActivity} serves as a
 * trampoline activity that captures call intents for individual users and forwards it to
 * the {@link CallReceiver} which interacts with the rest of Telecom, both of which run only as
 * the primary user.
 */
public class CallReceiver extends BroadcastReceiver {
    private static final String TAG = CallReceiver.class.getName();

    static final String KEY_IS_UNKNOWN_CALL = "is_unknown_call";
    static final String KEY_IS_INCOMING_CALL = "is_incoming_call";
    static final String KEY_IS_DEFAULT_DIALER =
            "is_default_dialer";

    @Override
    public void onReceive(Context context, Intent intent) {
        final boolean isUnknownCall = intent.getBooleanExtra(KEY_IS_UNKNOWN_CALL, false);
        final boolean isIncomingCall = intent.getBooleanExtra(KEY_IS_INCOMING_CALL, false);
        Log.i(this, "onReceive - isIncomingCall: %s isUnknownCall: %s", isIncomingCall,
                isUnknownCall);

        if (isUnknownCall) {
            processUnknownCallIntent(intent);
        } else if (isIncomingCall) {
            processIncomingCallIntent(intent);
        } else {
            processOutgoingCallIntent(context, intent);
        }
    }

    /**
     * Processes CALL, CALL_PRIVILEGED, and CALL_EMERGENCY intents.
     *
     * @param intent Call intent containing data about the handle to call.
     */
    static void processOutgoingCallIntent(Context context, Intent intent) {
        Uri handle = intent.getData();
        String scheme = handle.getScheme();
        String uriString = handle.getSchemeSpecificPart();

        if (!PhoneAccount.SCHEME_VOICEMAIL.equals(scheme)) {
            handle = Uri.fromParts(PhoneNumberUtils.isUriNumber(uriString) ?
                    PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL, uriString, null);
        }

        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = Bundle.EMPTY;
        }

        final boolean isDefaultDialer = intent.getBooleanExtra(KEY_IS_DEFAULT_DIALER, false);

        // Send to CallsManager to ensure the InCallUI gets kicked off before the broadcast returns
        Call call = getCallsManager().startOutgoingCall(handle, phoneAccountHandle, clientExtras);

        if (call != null) {
            // Asynchronous calls should not usually be made inside a BroadcastReceiver because once
            // onReceive is complete, the BroadcastReceiver's process runs the risk of getting
            // killed if memory is scarce. However, this is OK here because the entire Telecom
            // process will be running throughout the duration of the phone call and should never
            // be killed.
            NewOutgoingCallIntentBroadcaster broadcaster = new NewOutgoingCallIntentBroadcaster(
                    context, getCallsManager(), call, intent, isDefaultDialer);
            final int result = broadcaster.processIntent();
            final boolean success = result == DisconnectCause.NOT_DISCONNECTED;

            if (!success && call != null) {
                disconnectCallAndShowErrorDialog(context, call, result);
            }
        }
    }

    static void processIncomingCallIntent(Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(TAG, "Rejecting incoming call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(TAG, "Rejecting incoming call due to null component name");
            return;
        }

        Bundle clientExtras = null;
        if (intent.hasExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)) {
            clientExtras = intent.getBundleExtra(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS);
        }
        if (clientExtras == null) {
            clientExtras = Bundle.EMPTY;
        }

        Log.d(TAG, "Processing incoming call from connection service [%s]",
                phoneAccountHandle.getComponentName());
        getCallsManager().processIncomingCallIntent(phoneAccountHandle, clientExtras);
    }

    private void processUnknownCallIntent(Intent intent) {
        PhoneAccountHandle phoneAccountHandle = intent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);

        if (phoneAccountHandle == null) {
            Log.w(this, "Rejecting unknown call due to null phone account");
            return;
        }
        if (phoneAccountHandle.getComponentName() == null) {
            Log.w(this, "Rejecting unknown call due to null component name");
            return;
        }

        getCallsManager().addNewUnknownCall(phoneAccountHandle, intent.getExtras());
    }

    static CallsManager getCallsManager() {
        return CallsManager.getInstance();
    }

    private static void disconnectCallAndShowErrorDialog(
            Context context, Call call, int errorCode) {
        call.disconnect();
        final Intent errorIntent = new Intent(context, ErrorDialogActivity.class);
        int errorMessageId = -1;
        switch (errorCode) {
            case DisconnectCause.INVALID_NUMBER:
            case DisconnectCause.NO_PHONE_NUMBER_SUPPLIED:
                errorMessageId = R.string.outgoing_call_error_no_phone_number_supplied;
                break;
        }
        if (errorMessageId != -1) {
            errorIntent.putExtra(ErrorDialogActivity.ERROR_MESSAGE_ID_EXTRA, errorMessageId);
        }
        errorIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivityAsUser(errorIntent, UserHandle.CURRENT);
    }
}
