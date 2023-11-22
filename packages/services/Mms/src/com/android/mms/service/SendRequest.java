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

package com.android.mms.service;

import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.SendConf;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import com.android.mms.service.exception.MmsHttpException;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * Request to send an MMS
 */
public class SendRequest extends MmsRequest {
    private final Uri mPduUri;
    private byte[] mPduData;
    private final String mLocationUrl;
    private final PendingIntent mSentIntent;

    public SendRequest(RequestManager manager, long subId, Uri contentUri, Uri messageUri,
            String locationUrl, PendingIntent sentIntent, String creator,
            Bundle configOverrides) {
        super(manager, messageUri, subId, creator, configOverrides);
        mPduUri = contentUri;
        mPduData = null;
        mLocationUrl = locationUrl;
        mSentIntent = sentIntent;
    }

    @Override
    protected byte[] doHttp(Context context, MmsNetworkManager netMgr, ApnSettings apn)
            throws MmsHttpException {
        return doHttpForResolvedAddresses(context,
                netMgr,
                mLocationUrl != null ? mLocationUrl : apn.getMmscUrl(),
                mPduData,
                HttpUtils.HTTP_POST_METHOD,
                apn);
    }

    @Override
    protected PendingIntent getPendingIntent() {
        return mSentIntent;
    }

    @Override
    protected int getRunningQueue() {
        return MmsService.QUEUE_INDEX_SEND;
    }

    public void storeInOutbox(Context context) {
        final long identity = Binder.clearCallingIdentity();
        try {
            // Read message using phone process identity
            if (!readPduFromContentUri()) {
                Log.e(MmsService.TAG, "SendRequest.storeInOutbox: empty PDU");
                return;
            }
            if (mMessageUri == null) {
                // This is a new message to send
                final GenericPdu pdu = (new PduParser(mPduData)).parse();
                if (pdu == null) {
                    Log.e(MmsService.TAG, "SendRequest.storeInOutbox: can't parse input PDU");
                    return;
                }
                if (!(pdu instanceof SendReq)) {
                    Log.d(MmsService.TAG, "SendRequest.storeInOutbox: not SendReq");
                    return;
                }
                final PduPersister persister = PduPersister.getPduPersister(context);
                mMessageUri = persister.persist(
                        pdu,
                        Telephony.Mms.Outbox.CONTENT_URI,
                        true/*createThreadId*/,
                        true/*groupMmsEnabled*/,
                        null/*preOpenedFiles*/);
                if (mMessageUri == null) {
                    Log.e(MmsService.TAG, "SendRequest.storeInOutbox: can not persist message");
                    return;
                }
                final ContentValues values = new ContentValues(5);
                values.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
                values.put(Telephony.Mms.READ, 1);
                values.put(Telephony.Mms.SEEN, 1);
                if (!TextUtils.isEmpty(mCreator)) {
                    values.put(Telephony.Mms.CREATOR, mCreator);
                }
                values.put(Telephony.Mms.SUB_ID, mSubId);
                if (SqliteWrapper.update(context, context.getContentResolver(), mMessageUri, values,
                        null/*where*/, null/*selectionArg*/) != 1) {
                    Log.e(MmsService.TAG, "SendRequest.storeInOutbox: failed to update message");
                }
            } else {
                // This is a stored message, either in FAILED or DRAFT
                // Move this to OUTBOX for sending
                final ContentValues values = new ContentValues(3);
                // Reset the timestamp
                values.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
                values.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX);
                values.put(Telephony.Mms.SUB_ID, mSubId);
                if (SqliteWrapper.update(context, context.getContentResolver(), mMessageUri, values,
                        null/*where*/, null/*selectionArg*/) != 1) {
                    Log.e(MmsService.TAG, "SendRequest.storeInOutbox: failed to update message");
                }
            }
        } catch (MmsException e) {
            Log.e(MmsService.TAG, "SendRequest.storeInOutbox: can not persist/update message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "SendRequest.storeInOutbox: unexpected parsing failure", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Read the pdu from the file descriptor and cache pdu bytes in request
     * @return true if pdu read successfully
     */
    private boolean readPduFromContentUri() {
        if (mPduData != null) {
            return true;
        }
        final int bytesTobeRead = mMmsConfig.getMaxMessageSize();
        mPduData = mRequestManager.readPduFromContentUri(mPduUri, bytesTobeRead);
        return (mPduData != null);
    }

    @Override
    protected void updateStatus(Context context, int result, byte[] response) {
        if (mMessageUri == null) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final int messageStatus = result == Activity.RESULT_OK ?
                    Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED;
            SendConf sendConf = null;
            if (response != null && response.length > 0) {
                final GenericPdu pdu = (new PduParser(response)).parse();
                if (pdu != null && pdu instanceof SendConf) {
                    sendConf = (SendConf) pdu;
                }
            }
            final ContentValues values = new ContentValues(3);
            values.put(Telephony.Mms.MESSAGE_BOX, messageStatus);
            if (sendConf != null) {
                values.put(Telephony.Mms.RESPONSE_STATUS, sendConf.getResponseStatus());
                values.put(Telephony.Mms.MESSAGE_ID,
                        PduPersister.toIsoString(sendConf.getMessageId()));
            }
            SqliteWrapper.update(context, context.getContentResolver(), mMessageUri, values,
                    null/*where*/, null/*selectionArg*/);
        } catch (SQLiteException e) {
            Log.e(MmsService.TAG, "SendRequest.updateStatus: can not update message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "SendRequest.updateStatus: can not parse response", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Transfer the received response to the caller (for send requests the pdu is small and can
     *  just include bytes as extra in the "returned" intent).
     *
     * @param fillIn the intent that will be returned to the caller
     * @param response the pdu to transfer
     */
    @Override
    protected boolean transferResponse(Intent fillIn, byte[] response) {
        // SendConf pdus are always small and can be included in the intent
        if (response != null) {
            fillIn.putExtra(SmsManager.EXTRA_MMS_DATA, response);
        }
        return true;
    }

    /**
     * Read the data from the file descriptor if not yet done
     * @return whether data successfully read
     */
    protected boolean prepareForHttpRequest() {
        return readPduFromContentUri();
    }

    /**
     * Try sending via the carrier app by sending an intent
     *
     * @param context The context
     */
    public void trySendingByCarrierApp(Context context) {
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        Intent intent = new Intent(Telephony.Mms.Intents.MMS_SEND_ACTION);
        List<String> carrierPackages = telephonyManager.getCarrierPackageNamesForIntent(
                intent);

        if (carrierPackages == null || carrierPackages.size() != 1) {
            mRequestManager.addRunning(this);
        } else {
            intent.setPackage(carrierPackages.get(0));
            intent.putExtra(Telephony.Mms.Intents.EXTRA_MMS_CONTENT_URI, mPduUri);
            intent.putExtra(Telephony.Mms.Intents.EXTRA_MMS_LOCATION_URL, mLocationUrl);
            intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
            context.sendOrderedBroadcastAsUser(
                    intent,
                    UserHandle.OWNER,
                    android.Manifest.permission.RECEIVE_MMS,
                    AppOpsManager.OP_RECEIVE_MMS,
                    mCarrierAppResultReceiver,
                    null/*scheduler*/,
                    Activity.RESULT_CANCELED,
                    null/*initialData*/,
                    null/*initialExtras*/);
        }
    }

    @Override
    protected void revokeUriPermission(Context context) {
        context.revokeUriPermission(mPduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}
