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
import com.google.android.mms.pdu.DeliveryInd;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduComposer;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.ReadOrigInd;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import com.android.internal.telephony.IMms;
import com.android.internal.telephony.SmsApplication;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * System service to process MMS API requests
 */
public class MmsService extends Service implements MmsRequest.RequestManager {
    public static final String TAG = "MmsService";

    public static final int QUEUE_INDEX_SEND = 0;
    public static final int QUEUE_INDEX_DOWNLOAD = 1;

    private static final String SHARED_PREFERENCES_NAME = "mmspref";
    private static final String PREF_AUTO_PERSISTING = "autopersisting";

    // Maximum time to spend waiting to read data from a content provider before failing with error.
    private static final int TASK_TIMEOUT_MS = 30 * 1000;
    // Maximum size of MMS service supports - used on occassions when MMS messages are processed
    // in a carrier independent manner (for example for imports and drafts) and the carrier
    // specific size limit should not be used (as it could be lower on some carriers).
    private static final int MAX_MMS_FILE_SIZE = 8 * 1024 * 1024;

    // Pending requests that are currently executed by carrier app
    // TODO: persist this in case MmsService crashes
    private final ConcurrentHashMap<Integer, MmsRequest> mPendingRequests =
            new ConcurrentHashMap<Integer, MmsRequest>();

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();

    @Override
    public void addPending(int key, MmsRequest request) {
        mPendingRequests.put(key, request);
    }

    /**
     * A thread-based request queue for executing the MMS requests in serial order
     */
    private class RequestQueue extends Handler {
        public RequestQueue(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final MmsRequest request = (MmsRequest) msg.obj;
            if (request != null) {
                request.execute(MmsService.this, mMmsNetworkManager);
            }
        }
    }

    private void enforceSystemUid() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only system can call this service");
        }
    }

    private IMms.Stub mStub = new IMms.Stub() {
        @Override
        public void sendMessage(long subId, String callingPkg, Uri contentUri,
                String locationUrl, Bundle configOverrides, PendingIntent sentIntent)
                        throws RemoteException {
            Log.d(TAG, "sendMessage");
            enforceSystemUid();
            final SendRequest request = new SendRequest(MmsService.this, subId, contentUri,
                    null/*messageUri*/, locationUrl, sentIntent, callingPkg, configOverrides);
            if (SmsApplication.shouldWriteMessageForPackage(callingPkg, MmsService.this)) {
                // Store the message in outbox first before sending
                request.storeInOutbox(MmsService.this);
            }
            // Try sending via carrier app
            request.trySendingByCarrierApp(MmsService.this);
        }

        @Override
        public void downloadMessage(long subId, String callingPkg, String locationUrl,
                Uri contentUri, Bundle configOverrides,
                PendingIntent downloadedIntent) throws RemoteException {
            Log.d(TAG, "downloadMessage: " + locationUrl);
            enforceSystemUid();
            final DownloadRequest request = new DownloadRequest(MmsService.this, subId,
                    locationUrl, contentUri, downloadedIntent, callingPkg, configOverrides);
            // Try downloading via carrier app
            request.tryDownloadingByCarrierApp(MmsService.this);
        }

        @Override
        public void updateMmsSendStatus(int messageRef, byte[] pdu, int status) {
            Log.d(TAG, "updateMmsSendStatus: ref=" + messageRef
                    + ", pdu=" + (pdu == null ? null : pdu.length) + ", status=" + status);
            enforceSystemUid();
            final MmsRequest request = mPendingRequests.get(messageRef);
            if (request != null) {
                if (status != SmsManager.MMS_ERROR_RETRY) {
                    // Sent completed (maybe success or fail) by carrier app, finalize the request.
                    request.processResult(MmsService.this, status, pdu);
                } else {
                    // Failed, try sending via carrier network
                    addRunning(request);
                }
            } else {
                // Really wrong here: can't find the request to update
                Log.e(TAG, "Failed to find the request to update send status");
            }
        }

        @Override
        public void updateMmsDownloadStatus(int messageRef, int status) {
            Log.d(TAG, "updateMmsDownloadStatus: ref=" + messageRef + ", status=" + status);
            enforceSystemUid();
            final MmsRequest request = mPendingRequests.get(messageRef);
            if (request != null) {
                if (status != SmsManager.MMS_ERROR_RETRY) {
                    // Downloaded completed (maybe success or fail) by carrier app, finalize the
                    // request.
                    request.processResult(MmsService.this, status, null/*response*/);
                } else {
                    // Failed, try downloading via the carrier network
                    addRunning(request);
                }
            } else {
                // Really wrong here: can't find the request to update
                Log.e(TAG, "Failed to find the request to update download status");
            }
        }

        @Override
        public Bundle getCarrierConfigValues(long subId) {
            Log.d(TAG, "getCarrierConfigValues");
            final MmsConfig mmsConfig = MmsConfigManager.getInstance().getMmsConfigBySubId(subId);
            if (mmsConfig == null) {
                return new Bundle();
            }
            return mmsConfig.getCarrierConfigValues();
        }

        @Override
        public Uri importTextMessage(String callingPkg, String address, int type, String text,
                long timestampMillis, boolean seen, boolean read) {
            Log.d(TAG, "importTextMessage");
            enforceSystemUid();
            return importSms(address, type, text, timestampMillis, seen, read, callingPkg);
        }

        @Override
        public Uri importMultimediaMessage(String callingPkg, Uri contentUri,
                String messageId, long timestampSecs, boolean seen, boolean read) {
            Log.d(TAG, "importMultimediaMessage");
            enforceSystemUid();
            return importMms(contentUri, messageId, timestampSecs, seen, read, callingPkg);
        }

        @Override
        public boolean deleteStoredMessage(String callingPkg, Uri messageUri)
                throws RemoteException {
            Log.d(TAG, "deleteStoredMessage " + messageUri);
            enforceSystemUid();
            if (!isSmsMmsContentUri(messageUri)) {
                Log.e(TAG, "deleteStoredMessage: invalid message URI: " + messageUri.toString());
                return false;
            }
            // Clear the calling identity and query the database using the phone user id
            // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
            // between the calling uid and the package uid
            final long identity = Binder.clearCallingIdentity();
            try {
                if (getContentResolver().delete(
                        messageUri, null/*where*/, null/*selectionArgs*/) != 1) {
                    Log.e(TAG, "deleteStoredMessage: failed to delete");
                    return false;
                }
            } catch (SQLiteException e) {
                Log.e(TAG, "deleteStoredMessage: failed to delete", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return true;
        }

        @Override
        public boolean deleteStoredConversation(String callingPkg, long conversationId)
                throws RemoteException {
            Log.d(TAG, "deleteStoredConversation " + conversationId);
            enforceSystemUid();
            if (conversationId == -1) {
                Log.e(TAG, "deleteStoredConversation: invalid thread id");
                return false;
            }
            final Uri uri = ContentUris.withAppendedId(
                    Telephony.Threads.CONTENT_URI, conversationId);
            // Clear the calling identity and query the database using the phone user id
            // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
            // between the calling uid and the package uid
            final long identity = Binder.clearCallingIdentity();
            try {
                if (getContentResolver().delete(uri, null, null) != 1) {
                    Log.e(TAG, "deleteStoredConversation: failed to delete");
                    return false;
                }
            } catch (SQLiteException e) {
                Log.e(TAG, "deleteStoredConversation: failed to delete", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return true;
        }

        @Override
        public boolean updateStoredMessageStatus(String callingPkg, Uri messageUri,
                ContentValues statusValues) throws RemoteException {
            Log.d(TAG, "updateStoredMessageStatus " + messageUri);
            enforceSystemUid();
            return updateMessageStatus(messageUri, statusValues);
        }

        @Override
        public boolean archiveStoredConversation(String callingPkg, long conversationId,
                boolean archived) throws RemoteException {
            Log.d(TAG, "archiveStoredConversation " + conversationId + " " + archived);
            if (conversationId == -1) {
                Log.e(TAG, "archiveStoredConversation: invalid thread id");
                return false;
            }
            return archiveConversation(conversationId, archived);
        }

        @Override
        public Uri addTextMessageDraft(String callingPkg, String address, String text)
                throws RemoteException {
            Log.d(TAG, "addTextMessageDraft");
            enforceSystemUid();
            return addSmsDraft(address, text, callingPkg);
        }

        @Override
        public Uri addMultimediaMessageDraft(String callingPkg, Uri contentUri)
                throws RemoteException {
            Log.d(TAG, "addMultimediaMessageDraft");
            enforceSystemUid();
            return addMmsDraft(contentUri, callingPkg);
        }

        @Override
        public void sendStoredMessage(long subId, String callingPkg, Uri messageUri,
                Bundle configOverrides, PendingIntent sentIntent) throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAutoPersisting(String callingPkg, boolean enabled) throws RemoteException {
            Log.d(TAG, "setAutoPersisting " + enabled);
            enforceSystemUid();
            final SharedPreferences preferences = getSharedPreferences(
                    SHARED_PREFERENCES_NAME, MODE_PRIVATE);
            final SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean(PREF_AUTO_PERSISTING, enabled);
            editor.apply();
        }

        @Override
        public boolean getAutoPersisting() throws RemoteException {
            Log.d(TAG, "getAutoPersisting");
            return getAutoPersistingPref();
        }
    };

    // Request queue threads
    // 0: send queue
    // 1: download queue
    private final RequestQueue[] mRequestQueues = new RequestQueue[2];

    // Manages MMS connectivity related stuff
    private final MmsNetworkManager mMmsNetworkManager = new MmsNetworkManager(this);

    /**
     * Lazy start the request queue threads
     *
     * @param queueIndex index of the queue to start
     */
    private void startRequestQueueIfNeeded(int queueIndex) {
        if (queueIndex < 0 || queueIndex >= mRequestQueues.length) {
            return;
        }
        synchronized (this) {
            if (mRequestQueues[queueIndex] == null) {
                final HandlerThread thread =
                        new HandlerThread("MmsService RequestQueue " + queueIndex);
                thread.start();
                mRequestQueues[queueIndex] = new RequestQueue(thread.getLooper());
            }
        }
    }

    @Override
    public void addRunning(MmsRequest request) {
        if (request == null) {
            return;
        }
        final int queue = request.getRunningQueue();
        startRequestQueueIfNeeded(queue);
        final Message message = Message.obtain();
        message.obj = request;
        mRequestQueues[queue].sendMessage(message);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mStub;
    }

    public final IBinder asBinder() {
        return mStub;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        // Load mms_config
        // TODO (ywen): make sure we start request queues after mms_config is loaded
        MmsConfigManager.getInstance().init(this);
    }

    private Uri importSms(String address, int type, String text, long timestampMillis,
            boolean seen, boolean read, String creator) {
        Uri insertUri = null;
        switch (type) {
            case SmsManager.SMS_TYPE_INCOMING:
                insertUri = Telephony.Sms.Inbox.CONTENT_URI;

                break;
            case SmsManager.SMS_TYPE_OUTGOING:
                insertUri = Telephony.Sms.Sent.CONTENT_URI;
                break;
        }
        if (insertUri == null) {
            Log.e(TAG, "importTextMessage: invalid message type for importing: " + type);
            return null;
        }
        final ContentValues values = new ContentValues(6);
        values.put(Telephony.Sms.ADDRESS, address);
        values.put(Telephony.Sms.DATE, timestampMillis);
        values.put(Telephony.Sms.SEEN, seen ? 1 : 0);
        values.put(Telephony.Sms.READ, read ? 1 : 0);
        values.put(Telephony.Sms.BODY, text);
        if (!TextUtils.isEmpty(creator)) {
            values.put(Telephony.Mms.CREATOR, creator);
        }
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            return getContentResolver().insert(insertUri, values);
        } catch (SQLiteException e) {
            Log.e(TAG, "importTextMessage: failed to persist imported text message", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private Uri importMms(Uri contentUri, String messageId, long timestampSecs,
            boolean seen, boolean read, String creator) {
        byte[] pduData = readPduFromContentUri(contentUri, MAX_MMS_FILE_SIZE);
        if (pduData == null || pduData.length < 1) {
            Log.e(TAG, "importMessage: empty PDU");
            return null;
        }
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            final GenericPdu pdu = (new PduParser(pduData)).parse();
            if (pdu == null) {
                Log.e(TAG, "importMessage: can't parse input PDU");
                return null;
            }
            Uri insertUri = null;
            if (pdu instanceof SendReq) {
                insertUri = Telephony.Mms.Sent.CONTENT_URI;
            } else if (pdu instanceof RetrieveConf ||
                    pdu instanceof NotificationInd ||
                    pdu instanceof DeliveryInd ||
                    pdu instanceof ReadOrigInd) {
                insertUri = Telephony.Mms.Inbox.CONTENT_URI;
            }
            if (insertUri == null) {
                Log.e(TAG, "importMessage; invalid MMS type: " + pdu.getClass().getCanonicalName());
                return null;
            }
            final PduPersister persister = PduPersister.getPduPersister(this);
            final Uri uri = persister.persist(
                    pdu,
                    insertUri,
                    true/*createThreadId*/,
                    true/*groupMmsEnabled*/,
                    null/*preOpenedFiles*/);
            if (uri == null) {
                Log.e(TAG, "importMessage: failed to persist message");
                return null;
            }
            final ContentValues values = new ContentValues(5);
            if (!TextUtils.isEmpty(messageId)) {
                values.put(Telephony.Mms.MESSAGE_ID, messageId);
            }
            if (timestampSecs != -1) {
                values.put(Telephony.Mms.DATE, timestampSecs);
            }
            values.put(Telephony.Mms.READ, seen ? 1 : 0);
            values.put(Telephony.Mms.SEEN, read ? 1 : 0);
            if (!TextUtils.isEmpty(creator)) {
                values.put(Telephony.Mms.CREATOR, creator);
            }
            if (SqliteWrapper.update(this, getContentResolver(), uri, values,
                    null/*where*/, null/*selectionArg*/) != 1) {
                Log.e(TAG, "importMessage: failed to update message");
            }
            return uri;
        } catch (RuntimeException e) {
            Log.e(TAG, "importMessage: failed to parse input PDU", e);
        } catch (MmsException e) {
            Log.e(TAG, "importMessage: failed to persist message", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private static boolean isSmsMmsContentUri(Uri uri) {
        final String uriString = uri.toString();
        if (!uriString.startsWith("content://sms/") && !uriString.startsWith("content://mms/")) {
            return false;
        }
        if (ContentUris.parseId(uri) == -1) {
            return false;
        }
        return true;
    }

    private boolean updateMessageStatus(Uri messageUri, ContentValues statusValues) {
        if (!isSmsMmsContentUri(messageUri)) {
            Log.e(TAG, "updateMessageStatus: invalid messageUri: " + messageUri.toString());
            return false;
        }
        if (statusValues == null) {
            Log.w(TAG, "updateMessageStatus: empty values to update");
            return false;
        }
        final ContentValues values = new ContentValues();
        if (statusValues.containsKey(SmsManager.MESSAGE_STATUS_READ)) {
            final Integer val = statusValues.getAsInteger(SmsManager.MESSAGE_STATUS_READ);
            if (val != null) {
                // MMS uses the same column name
                values.put(Telephony.Sms.READ, val);
            }
        } else if (statusValues.containsKey(SmsManager.MESSAGE_STATUS_SEEN)) {
            final Integer val = statusValues.getAsInteger(SmsManager.MESSAGE_STATUS_SEEN);
            if (val != null) {
                // MMS uses the same column name
                values.put(Telephony.Sms.SEEN, val);
            }
        }
        if (values.size() < 1) {
            Log.w(TAG, "updateMessageStatus: no value to update");
            return false;
        }
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            if (getContentResolver().update(
                    messageUri, values, null/*where*/, null/*selectionArgs*/) != 1) {
                Log.e(TAG, "updateMessageStatus: failed to update database");
                return false;
            }
            return true;
        } catch (SQLiteException e) {
            Log.e(TAG, "updateMessageStatus: failed to update database", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    private static final String ARCHIVE_CONVERSATION_SELECTION = Telephony.Threads._ID + "=?";
    private boolean archiveConversation(long conversationId, boolean archived) {
        final ContentValues values = new ContentValues(1);
        values.put(Telephony.Threads.ARCHIVED, archived ? 1 : 0);
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            if (getContentResolver().update(
                    Telephony.Threads.CONTENT_URI,
                    values,
                    ARCHIVE_CONVERSATION_SELECTION,
                    new String[] { Long.toString(conversationId)}) != 1) {
                Log.e(TAG, "archiveConversation: failed to update database");
                return false;
            }
            return true;
        } catch (SQLiteException e) {
            Log.e(TAG, "archiveConversation: failed to update database", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    private Uri addSmsDraft(String address, String text, String creator) {
        final ContentValues values = new ContentValues(5);
        values.put(Telephony.Sms.ADDRESS, address);
        values.put(Telephony.Sms.BODY, text);
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        if (!TextUtils.isEmpty(creator)) {
            values.put(Telephony.Mms.CREATOR, creator);
        }
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            return getContentResolver().insert(Telephony.Sms.Draft.CONTENT_URI, values);
        } catch (SQLiteException e) {
            Log.e(TAG, "addSmsDraft: failed to store draft message", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private Uri addMmsDraft(Uri contentUri, String creator) {
        byte[] pduData = readPduFromContentUri(contentUri, MAX_MMS_FILE_SIZE);
        if (pduData == null || pduData.length < 1) {
            Log.e(TAG, "addMmsDraft: empty PDU");
            return null;
        }
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            final GenericPdu pdu = (new PduParser(pduData)).parse();
            if (pdu == null) {
                Log.e(TAG, "addMmsDraft: can't parse input PDU");
                return null;
            }
            if (!(pdu instanceof SendReq)) {
                Log.e(TAG, "addMmsDraft; invalid MMS type: " + pdu.getClass().getCanonicalName());
                return null;
            }
            final PduPersister persister = PduPersister.getPduPersister(this);
            final Uri uri = persister.persist(
                    pdu,
                    Telephony.Mms.Draft.CONTENT_URI,
                    true/*createThreadId*/,
                    true/*groupMmsEnabled*/,
                    null/*preOpenedFiles*/);
            if (uri == null) {
                Log.e(TAG, "addMmsDraft: failed to persist message");
                return null;
            }
            final ContentValues values = new ContentValues(3);
            values.put(Telephony.Mms.READ, 1);
            values.put(Telephony.Mms.SEEN, 1);
            if (!TextUtils.isEmpty(creator)) {
                values.put(Telephony.Mms.CREATOR, creator);
            }
            if (SqliteWrapper.update(this, getContentResolver(), uri, values,
                    null/*where*/, null/*selectionArg*/) != 1) {
                Log.e(TAG, "addMmsDraft: failed to update message");
            }
            return uri;
        } catch (RuntimeException e) {
            Log.e(TAG, "addMmsDraft: failed to parse input PDU", e);
        } catch (MmsException e) {
            Log.e(TAG, "addMmsDraft: failed to persist message", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private boolean isFailedOrDraft(Uri messageUri) {
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    messageUri,
                    new String[]{ Telephony.Mms.MESSAGE_BOX },
                    null/*selection*/,
                    null/*selectionArgs*/,
                    null/*sortOrder*/);
            if (cursor != null && cursor.moveToFirst()) {
                final int box = cursor.getInt(0);
                return box == Telephony.Mms.MESSAGE_BOX_DRAFTS
                        || box == Telephony.Mms.MESSAGE_BOX_FAILED;
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "isFailedOrDraft: query message type failed", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    private byte[] loadPdu(Uri messageUri) {
        // Clear the calling identity and query the database using the phone user id
        // Otherwise the AppOps check in TelephonyProvider would complain about mismatch
        // between the calling uid and the package uid
        final long identity = Binder.clearCallingIdentity();
        try {
            final PduPersister persister = PduPersister.getPduPersister(this);
            final GenericPdu pdu = persister.load(messageUri);
            if (pdu == null) {
                Log.e(TAG, "loadPdu: failed to load PDU from " + messageUri.toString());
                return null;
            }
            final PduComposer composer = new PduComposer(this, pdu);
            return composer.make();
        } catch (MmsException e) {
            Log.e(TAG, "loadPdu: failed to load PDU from " + messageUri.toString(), e);
        } catch (RuntimeException e) {
            Log.e(TAG, "loadPdu: failed to serialize PDU", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return null;
    }

    private void returnUnspecifiedFailure(PendingIntent pi) {
        if (pi != null) {
            try {
                pi.send(SmsManager.MMS_ERROR_UNSPECIFIED);
            } catch (PendingIntent.CanceledException e) {
                // ignore
            }
        }
    }

    @Override
    public boolean getAutoPersistingPref() {
        final SharedPreferences preferences = getSharedPreferences(
                SHARED_PREFERENCES_NAME, MODE_PRIVATE);
        return preferences.getBoolean(PREF_AUTO_PERSISTING, false);
    }

    /**
     * Read pdu from content provider uri
     * @param contentUri content provider uri from which to read
     * @param maxSize maximum number of bytes to read
     * @return pdu bytes if succeeded else null
     */
    public byte[] readPduFromContentUri(final Uri contentUri, final int maxSize) {
        Callable<byte[]> copyPduToArray = new Callable<byte[]>() {
            public byte[] call() {
                ParcelFileDescriptor.AutoCloseInputStream inStream = null;
                try {
                    ContentResolver cr = MmsService.this.getContentResolver();
                    ParcelFileDescriptor pduFd = cr.openFileDescriptor(contentUri, "r");
                    inStream = new ParcelFileDescriptor.AutoCloseInputStream(pduFd);
                    // Request one extra byte to make sure file not bigger than maxSize
                    byte[] tempBody = new byte[maxSize+1];
                    int bytesRead = inStream.read(tempBody, 0, maxSize+1);
                    if (bytesRead == 0) {
                        Log.e(MmsService.TAG, "MmsService.readPduFromContentUri: empty PDU");
                        return null;
                    }
                    if (bytesRead <= maxSize) {
                        return Arrays.copyOf(tempBody, bytesRead);
                    }
                    Log.e(MmsService.TAG, "MmsService.readPduFromContentUri: PDU too large");
                    return null;
                } catch (IOException ex) {
                    Log.e(MmsService.TAG,
                            "MmsService.readPduFromContentUri: IO exception reading PDU", ex);
                    return null;
                } finally {
                    if (inStream != null) {
                        try {
                            inStream.close();
                        } catch (IOException ex) {
                        }
                    }
                }
            }
        };

        Future<byte[]> pendingResult = mExecutor.submit(copyPduToArray);
        try {
            byte[] pdu = pendingResult.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return pdu;
        } catch (Exception e) {
            // Typically a timeout occurred - cancel task
            pendingResult.cancel(true);
        }
        return null;
    }

    /**
     * Write pdu bytes to content provider uri
     * @param contentUri content provider uri to which bytes should be written
     * @param pdu Bytes to write
     * @return true if all bytes successfully written else false
     */
    public boolean writePduToContentUri(final Uri contentUri, final byte[] pdu) {
        Callable<Boolean> copyDownloadedPduToOutput = new Callable<Boolean>() {
            public Boolean call() {
                ParcelFileDescriptor.AutoCloseOutputStream outStream = null;
                try {
                    ContentResolver cr = MmsService.this.getContentResolver();
                    ParcelFileDescriptor pduFd = cr.openFileDescriptor(contentUri, "w");
                    outStream = new ParcelFileDescriptor.AutoCloseOutputStream(pduFd);
                    outStream.write(pdu);
                    return Boolean.TRUE;
                } catch (IOException ex) {
                    return Boolean.FALSE;
                } finally {
                    if (outStream != null) {
                        try {
                            outStream.close();
                        } catch (IOException ex) {
                        }
                    }
                }
            }
        };

        Future<Boolean> pendingResult = mExecutor.submit(copyDownloadedPduToOutput);
        try {
            Boolean succeeded = pendingResult.get(TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return succeeded == Boolean.TRUE;
        } catch (Exception e) {
            // Typically a timeout occurred - cancel task
            pendingResult.cancel(true);
        }
        return false;
    }
}
