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

import com.android.mms.service.exception.ApnException;
import com.android.mms.service.exception.MmsHttpException;
import com.android.mms.service.exception.MmsNetworkException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for MMS requests. This has the common logic of sending/downloading MMS.
 */
public abstract class MmsRequest {
    private static final int RETRY_TIMES = 3;

    protected static final String EXTRA_MESSAGE_REF = "messageref";

    /**
     * Interface for certain functionalities from MmsService
     */
    public static interface RequestManager {
        /**
         * Add a request to pending queue when it is executed by carrier app
         *
         * @param key The message ref key from carrier app
         * @param request The request in pending
         */
        public void addPending(int key, MmsRequest request);

        /**
         * Enqueue an MMS request for running
         *
         * @param request the request to enqueue
         */
        public void addRunning(MmsRequest request);

        /*
         * @return Whether to auto persist received MMS
         */
        public boolean getAutoPersistingPref();

        /**
         * Read pdu (up to maxSize bytes) from supplied content uri
         * @param contentUri content uri from which to read
         * @param maxSize maximum number of bytes to read
         * @return read pdu (else null in case of error or too big)
         */
        public byte[] readPduFromContentUri(final Uri contentUri, final int maxSize);

        /**
         * Write pdu to supplied content uri
         * @param contentUri content uri to which bytes should be written
         * @param pdu pdu bytes to write
         * @return true in case of success (else false)
         */
        public boolean writePduToContentUri(final Uri contentUri, final byte[] pdu);
    }

    // The URI of persisted message
    protected Uri mMessageUri;
    // The reference to the pending requests manager (i.e. the MmsService)
    protected RequestManager mRequestManager;
    // The SIM id
    protected long mSubId;
    // The creator app
    protected String mCreator;
    // MMS config
    protected MmsConfig.Overridden mMmsConfig;
    // MMS config overrides
    protected Bundle mMmsConfigOverrides;

    // Intent result receiver for carrier app
    protected final BroadcastReceiver mCarrierAppResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Telephony.Mms.Intents.MMS_SEND_ACTION) ||
                    action.equals(Telephony.Mms.Intents.MMS_DOWNLOAD_ACTION)) {
                Log.d(MmsService.TAG, "Carrier app result for " + action);
                final int rc = getResultCode();
                if (rc == Activity.RESULT_OK) {
                    // Handled by carrier app, waiting for result
                    Log.d(MmsService.TAG, "Sending/downloading MMS by IP pending.");
                    final Bundle resultExtras = getResultExtras(false);
                    if (resultExtras != null && resultExtras.containsKey(EXTRA_MESSAGE_REF)) {
                        final int ref = resultExtras.getInt(EXTRA_MESSAGE_REF);
                        Log.d(MmsService.TAG, "messageref = " + ref);
                        mRequestManager.addPending(ref, MmsRequest.this);
                    } else {
                        // Bad, no message ref provided
                        Log.e(MmsService.TAG, "Can't find messageref in result extras.");
                    }
                } else {
                    // No carrier app present, sending normally
                    Log.d(MmsService.TAG, "Sending/downloading MMS by IP failed.");
                    mRequestManager.addRunning(MmsRequest.this);
                }
            } else {
                Log.e(MmsService.TAG, "unexpected BroadcastReceiver action: " + action);
            }

        }
    };

    public MmsRequest(RequestManager requestManager, Uri messageUri, long subId,
            String creator, Bundle configOverrides) {
        mRequestManager = requestManager;
        mMessageUri = messageUri;
        mSubId = subId;
        mCreator = creator;
        mMmsConfigOverrides = configOverrides;
        mMmsConfig = null;
    }

    private boolean ensureMmsConfigLoaded() {
        if (mMmsConfig == null) {
            // Not yet retrieved from mms config manager. Try getting it.
            final MmsConfig config = MmsConfigManager.getInstance().getMmsConfigBySubId(mSubId);
            if (config != null) {
                mMmsConfig = new MmsConfig.Overridden(config, mMmsConfigOverrides);
            }
        }
        return mMmsConfig != null;
    }

    /**
     * Execute the request
     *
     * @param context The context
     * @param networkManager The network manager to use
     */
    public void execute(Context context, MmsNetworkManager networkManager) {
        int result = SmsManager.MMS_ERROR_UNSPECIFIED;
        byte[] response = null;
        if (!ensureMmsConfigLoaded()) { // Check mms config
            Log.e(MmsService.TAG, "MmsRequest: mms config is not loaded yet");
            result = SmsManager.MMS_ERROR_CONFIGURATION_ERROR;
        } else if (!prepareForHttpRequest()) { // Prepare request, like reading pdu data from user
            Log.e(MmsService.TAG, "MmsRequest: failed to prepare for request");
            result = SmsManager.MMS_ERROR_IO_ERROR;
        } else { // Execute
            long retryDelaySecs = 2;
            // Try multiple times of MMS HTTP request
            for (int i = 0; i < RETRY_TIMES; i++) {
                try {
                    networkManager.acquireNetwork();
                    try {
                        final ApnSettings apn = ApnSettings.load(context, null/*apnName*/, mSubId);
                        response = doHttp(context, networkManager, apn);
                        result = Activity.RESULT_OK;
                        // Success
                        break;
                    } finally {
                        networkManager.releaseNetwork();
                    }
                } catch (ApnException e) {
                    Log.e(MmsService.TAG, "MmsRequest: APN failure", e);
                    result = SmsManager.MMS_ERROR_INVALID_APN;
                    break;
                } catch (MmsNetworkException e) {
                    Log.e(MmsService.TAG, "MmsRequest: MMS network acquiring failure", e);
                    result = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS;
                    // Retry
                } catch (MmsHttpException e) {
                    Log.e(MmsService.TAG, "MmsRequest: HTTP or network I/O failure", e);
                    result = SmsManager.MMS_ERROR_HTTP_FAILURE;
                    // Retry
                } catch (Exception e) {
                    Log.e(MmsService.TAG, "MmsRequest: unexpected failure", e);
                    result = SmsManager.MMS_ERROR_UNSPECIFIED;
                    break;
                }
                try {
                    Thread.sleep(retryDelaySecs * 1000, 0/*nano*/);
                } catch (InterruptedException e) {}
                retryDelaySecs <<= 1;
            }
        }
        processResult(context, result, response);
    }

    /**
     * Try running MMS HTTP request for all the addresses that we can resolve to
     *
     * @param context The context
     * @param netMgr The {@link com.android.mms.service.MmsNetworkManager}
     * @param url The HTTP URL
     * @param pdu The PDU to send
     * @param method The HTTP method to use
     * @param apn The APN setting to use
     * @return The response data
     * @throws MmsHttpException If there is any HTTP/network failure
     */
    protected byte[] doHttpForResolvedAddresses(Context context, MmsNetworkManager netMgr,
            String url, byte[] pdu, int method, ApnSettings apn) throws MmsHttpException {
        MmsHttpException lastException = null;
        final ConnectivityManager connMgr =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Do HTTP on all the addresses we can resolve to
        for (final InetAddress address : resolveDestination(connMgr, netMgr, url, apn)) {
            try {
                // TODO: we have to use a deprecated API here because with the new
                // ConnectivityManager APIs in LMP, we need to either use a bound process
                // or a bound socket. The former can not be used since we share the
                // phone process with others. The latter is not supported by any HTTP
                // library yet. We have to rely on this API to get things work. Once
                // a multinet aware HTTP lib is ready, we should switch to that and
                // remove all the unnecessary code.
                if (!connMgr.requestRouteToHostAddress(
                        ConnectivityManager.TYPE_MOBILE_MMS, address)) {
                    throw new MmsHttpException("MmsRequest: can not request a route for host "
                            + address);
                }
                return HttpUtils.httpConnection(
                        context,
                        url,
                        pdu,
                        method,
                        apn.isProxySet(),
                        apn.getProxyAddress(),
                        apn.getProxyPort(),
                        netMgr,
                        address instanceof Inet6Address,
                        mMmsConfig);
            } catch (MmsHttpException e) {
                lastException = e;
                Log.e(MmsService.TAG, "MmsRequest: failure in trying address " + address, e);
            }
        }
        if (lastException != null) {
            throw lastException;
        } else {
            // Should not reach here
            throw new MmsHttpException("MmsRequest: unknown failure");
        }
    }

    /**
     * Resolve the name of the host we are about to connect to, which can be the URL host or
     * the proxy host. We only resolve to the supported address types (IPv4 or IPv6 or both)
     * based on the MMS network interface's address type, i.e. we only need addresses that
     * match the link address type.
     *
     * @param connMgr The connectivity manager
     * @param netMgr The current {@link MmsNetworkManager}
     * @param url The HTTP URL
     * @param apn The APN setting to use
     * @return A list of matching resolved addresses
     * @throws MmsHttpException For any network failure
     */
    private static List<InetAddress> resolveDestination(ConnectivityManager connMgr,
            MmsNetworkManager netMgr, String url, ApnSettings apn) throws MmsHttpException {
        Log.d(MmsService.TAG, "MmsRequest: resolve url " + url);
        // Find the real host to connect to
        String host = null;
        if (apn.isProxySet()) {
            host = apn.getProxyAddress();
        } else {
            final Uri uri = Uri.parse(url);
            host = uri.getHost();
        }
        // Find out the link address types: ipv4 or ipv6 or both
        final int addressTypes = getMmsLinkAddressTypes(connMgr, netMgr.getNetwork());
        Log.d(MmsService.TAG, "MmsRequest: addressTypes=" + addressTypes);
        // Resolve the host to a list of addresses based on supported address types
        return resolveHostName(netMgr, host, addressTypes);
    }

    // Address type masks
    private static final int ADDRESS_TYPE_IPV4 = 1;
    private static final int ADDRESS_TYPE_IPV6 = 1 << 1;

    /**
     * Try to find out if we should use IPv6 or IPv4 for MMS. Basically we check if the MMS
     * network interface has IPv6 address or not. If so, we will use IPv6. Otherwise, use
     * IPv4.
     *
     * @param connMgr The connectivity manager
     * @return A bit mask indicating what address types we have
     */
    private static int getMmsLinkAddressTypes(ConnectivityManager connMgr, Network network) {
        int result = 0;
        // Return none if network is not available
        if (network == null) {
            return result;
        }
        final LinkProperties linkProperties = connMgr.getLinkProperties(network);
        if (linkProperties != null) {
            for (InetAddress addr : linkProperties.getAddresses()) {
                if (addr instanceof Inet4Address) {
                    result |= ADDRESS_TYPE_IPV4;
                } else if (addr instanceof Inet6Address) {
                    result |= ADDRESS_TYPE_IPV6;
                }
            }
        }
        return result;
    }

    /**
     * Resolve host name to address by specified address types.
     *
     * @param netMgr The current {@link MmsNetworkManager}
     * @param host The host name
     * @param addressTypes The required address type in a bit mask
     *  (0x01: IPv4, 0x10: IPv6, 0x11: both)
     * @return
     * @throws MmsHttpException
     */
    private static List<InetAddress> resolveHostName(MmsNetworkManager netMgr, String host,
            int addressTypes) throws MmsHttpException {
        final List<InetAddress> resolved = new ArrayList<InetAddress>();
        try {
            if (addressTypes != 0) {
                for (final InetAddress addr : netMgr.getAllByName(host)) {
                    if ((addressTypes & ADDRESS_TYPE_IPV6) != 0
                            && addr instanceof Inet6Address) {
                        // Should use IPv6 and this is IPv6 address, add it
                        resolved.add(addr);
                    } else if ((addressTypes & ADDRESS_TYPE_IPV4) != 0
                            && addr instanceof Inet4Address) {
                        // Should use IPv4 and this is IPv4 address, add it
                        resolved.add(addr);
                    }
                }
            }
            if (resolved.size() < 1) {
                throw new MmsHttpException("Failed to resolve " + host
                        + " for allowed address types: " + addressTypes);
            }
            return resolved;
        } catch (final UnknownHostException e) {
            throw new MmsHttpException("Failed to resolve " + host, e);
        }
    }

    /**
     * Process the result of the completed request, including updating the message status
     * in database and sending back the result via pending intents.
     *
     * @param context The context
     * @param result The result code of execution
     * @param response The response body
     */
    public void processResult(Context context, int result, byte[] response) {
        updateStatus(context, result, response);

        // Return MMS HTTP request result via PendingIntent
        final PendingIntent pendingIntent = getPendingIntent();
        if (pendingIntent != null) {
            boolean succeeded = true;
            // Extra information to send back with the pending intent
            Intent fillIn = new Intent();
            if (response != null) {
                succeeded = transferResponse(fillIn, response);
            }
            if (mMessageUri != null) {
                fillIn.putExtra("uri", mMessageUri.toString());
            }
            try {
                if (!succeeded) {
                    result = SmsManager.MMS_ERROR_IO_ERROR;
                }
                pendingIntent.send(context, result, fillIn);
            } catch (PendingIntent.CanceledException e) {
                Log.e(MmsService.TAG, "MmsRequest: sending pending intent canceled", e);
            }
        }

        revokeUriPermission(context);
    }

    /**
     * Making the HTTP request to MMSC
     *
     * @param context The context
     * @param netMgr The current {@link MmsNetworkManager}
     * @param apn The APN setting
     * @return The HTTP response data
     * @throws MmsHttpException If any network error happens
     */
    protected abstract byte[] doHttp(Context context, MmsNetworkManager netMgr, ApnSettings apn)
            throws MmsHttpException;

    /**
     * @return The PendingIntent associate with the MMS sending invocation
     */
    protected abstract PendingIntent getPendingIntent();

    /**
     * @return The running queue should be used by this request
     */
    protected abstract int getRunningQueue();

    /**
     * Update database status of the message represented by this request
     *
     * @param context The context
     * @param result The result code of execution
     * @param response The response body
     */
    protected abstract void updateStatus(Context context, int result, byte[] response);

    /**
     * Prepare to make the HTTP request - will download message for sending
     * @return true if preparation succeeds (and request can proceed) else false
     */
    protected abstract boolean prepareForHttpRequest();

    /**
     * Transfer the received response to the caller
     *
     * @param fillIn the intent that will be returned to the caller
     * @param response the pdu to transfer
     * @return true if response transfer succeeds else false
     */
    protected abstract boolean transferResponse(Intent fillIn, byte[] response);

    /**
     * Revoke the content URI permission granted by the MMS app to the phone package.
     *
     * @param context The context
     */
    protected abstract void revokeUriPermission(Context context);
}
