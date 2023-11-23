/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server;

import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.nsd.NsdManager.MDNS_DISCOVERY_MANAGER_EVENT;
import static android.net.nsd.NsdManager.MDNS_SERVICE_EVENT;
import static android.net.nsd.NsdManager.RESOLVE_SERVICE_SUCCEEDED;
import static android.provider.DeviceConfig.NAMESPACE_TETHERING;

import static com.android.modules.utils.build.SdkLevel.isAtLeastU;
import static com.android.server.connectivity.mdns.MdnsRecord.MAX_LABEL_LENGTH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.INetd;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.mdns.aidl.DiscoveryInfo;
import android.net.mdns.aidl.GetAddressInfo;
import android.net.mdns.aidl.IMDnsEventListener;
import android.net.mdns.aidl.RegistrationInfo;
import android.net.mdns.aidl.ResolutionInfo;
import android.net.nsd.INsdManager;
import android.net.nsd.INsdManagerCallback;
import android.net.nsd.INsdServiceConnector;
import android.net.nsd.MDnsManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.InetAddressUtils;
import com.android.net.module.util.PermissionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.ExecutorProvider;
import com.android.server.connectivity.mdns.MdnsAdvertiser;
import com.android.server.connectivity.mdns.MdnsDiscoveryManager;
import com.android.server.connectivity.mdns.MdnsMultinetworkSocketClient;
import com.android.server.connectivity.mdns.MdnsSearchOptions;
import com.android.server.connectivity.mdns.MdnsServiceBrowserListener;
import com.android.server.connectivity.mdns.MdnsServiceInfo;
import com.android.server.connectivity.mdns.MdnsSocketProvider;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";
    /**
     * Enable discovery using the Java DiscoveryManager, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_DISCOVERY_MANAGER_VERSION = "mdns_discovery_manager_version";
    private static final String LOCAL_DOMAIN_NAME = "local";

    /**
     * Enable advertising using the Java MdnsAdvertiser, instead of the legacy mdnsresponder
     * implementation.
     */
    private static final String MDNS_ADVERTISER_VERSION = "mdns_advertiser_version";

    /**
     * Comma-separated list of type:flag mappings indicating the flags to use to allowlist
     * discovery/advertising using MdnsDiscoveryManager / MdnsAdvertiser for a given type.
     *
     * For example _mytype._tcp.local and _othertype._tcp.local would be configured with:
     * _mytype._tcp:mytype,_othertype._tcp.local:othertype
     *
     * In which case the flags:
     * "mdns_discovery_manager_allowlist_mytype_version",
     * "mdns_advertiser_allowlist_mytype_version",
     * "mdns_discovery_manager_allowlist_othertype_version",
     * "mdns_advertiser_allowlist_othertype_version"
     * would be used to toggle MdnsDiscoveryManager / MdnsAdvertiser for each type. The flags will
     * be read with
     * {@link DeviceConfigUtils#isFeatureEnabled(Context, String, String, String, boolean)}.
     *
     * @see #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX
     * @see #MDNS_ALLOWLIST_FLAG_SUFFIX
     */
    private static final String MDNS_TYPE_ALLOWLIST_FLAGS = "mdns_type_allowlist_flags";

    private static final String MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX =
            "mdns_discovery_manager_allowlist_";
    private static final String MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX =
            "mdns_advertiser_allowlist_";
    private static final String MDNS_ALLOWLIST_FLAG_SUFFIX = "_version";

    public static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long CLEANUP_DELAY_MS = 10000;
    private static final int IFACE_IDX_ANY = 0;
    private static final SharedLog LOGGER = new SharedLog("serviceDiscovery");

    private final Context mContext;
    private final NsdStateMachine mNsdStateMachine;
    private final MDnsManager mMDnsManager;
    private final MDnsEventCallback mMDnsEventCallback;
    @NonNull
    private final Dependencies mDeps;
    @NonNull
    private final MdnsMultinetworkSocketClient mMdnsSocketClient;
    @NonNull
    private final MdnsDiscoveryManager mMdnsDiscoveryManager;
    @NonNull
    private final MdnsSocketProvider mMdnsSocketProvider;
    @NonNull
    private final MdnsAdvertiser mAdvertiser;
    private final SharedLog mServiceLogs = LOGGER.forSubComponent(TAG);
    // WARNING : Accessing these values in any thread is not safe, it must only be changed in the
    // state machine thread. If change this outside state machine, it will need to introduce
    // synchronization.
    private boolean mIsDaemonStarted = false;
    private boolean mIsMonitoringSocketsStarted = false;

    /**
     * Clients receiving asynchronous messages
     */
    private final HashMap<NsdServiceConnector, ClientInfo> mClients = new HashMap<>();

    /* A map from unique id to client info */
    private final SparseArray<ClientInfo> mIdToClientInfoMap= new SparseArray<>();

    private final long mCleanupDelayMs;

    private static final int INVALID_ID = 0;
    private int mUniqueId = 1;
    // The count of the connected legacy clients.
    private int mLegacyClientCount = 0;
    // The number of client that ever connected.
    private int mClientNumberId = 1;

    private static class MdnsListener implements MdnsServiceBrowserListener {
        protected final int mClientId;
        protected final int mTransactionId;
        @NonNull
        protected final NsdServiceInfo mReqServiceInfo;
        @NonNull
        protected final String mListenedServiceType;

        MdnsListener(int clientId, int transactionId, @NonNull NsdServiceInfo reqServiceInfo,
                @NonNull String listenedServiceType) {
            mClientId = clientId;
            mTransactionId = transactionId;
            mReqServiceInfo = reqServiceInfo;
            mListenedServiceType = listenedServiceType;
        }

        @NonNull
        public String getListenedServiceType() {
            return mListenedServiceType;
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo) { }

        @Override
        public void onSearchStoppedWithError(int error) { }

        @Override
        public void onSearchFailedToStart() { }

        @Override
        public void onDiscoveryQuerySent(@NonNull List<String> subtypes, int transactionId) { }

        @Override
        public void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) { }
    }

    private class DiscoveryListener extends MdnsListener {

        DiscoveryListener(int clientId, int transactionId, @NonNull NsdServiceInfo reqServiceInfo,
                @NonNull String listenServiceType) {
            super(clientId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceNameDiscovered(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_FOUND,
                    new MdnsEvent(mClientId, serviceInfo));
        }

        @Override
        public void onServiceNameRemoved(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_LOST,
                    new MdnsEvent(mClientId, serviceInfo));
        }
    }

    private class ResolutionListener extends MdnsListener {

        ResolutionListener(int clientId, int transactionId, @NonNull NsdServiceInfo reqServiceInfo,
                @NonNull String listenServiceType) {
            super(clientId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceFound(MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.RESOLVE_SERVICE_SUCCEEDED,
                    new MdnsEvent(mClientId, serviceInfo));
        }
    }

    private class ServiceInfoListener extends MdnsListener {

        ServiceInfoListener(int clientId, int transactionId, @NonNull NsdServiceInfo reqServiceInfo,
                @NonNull String listenServiceType) {
            super(clientId, transactionId, reqServiceInfo, listenServiceType);
        }

        @Override
        public void onServiceFound(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientId, serviceInfo));
        }

        @Override
        public void onServiceUpdated(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED,
                    new MdnsEvent(mClientId, serviceInfo));
        }

        @Override
        public void onServiceRemoved(@NonNull MdnsServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(MDNS_DISCOVERY_MANAGER_EVENT, mTransactionId,
                    NsdManager.SERVICE_UPDATED_LOST,
                    new MdnsEvent(mClientId, serviceInfo));
        }
    }

    /**
     * Data class of mdns service callback information.
     */
    private static class MdnsEvent {
        final int mClientId;
        @NonNull
        final MdnsServiceInfo mMdnsServiceInfo;

        MdnsEvent(int clientId, @NonNull MdnsServiceInfo mdnsServiceInfo) {
            mClientId = clientId;
            mMdnsServiceInfo = mdnsServiceInfo;
        }
    }

    private class NsdStateMachine extends StateMachine {

        private final DefaultState mDefaultState = new DefaultState();
        private final EnabledState mEnabledState = new EnabledState();

        @Override
        protected String getWhatToString(int what) {
            return NsdManager.nameOf(what);
        }

        private void maybeStartDaemon() {
            if (mIsDaemonStarted) {
                if (DBG) Log.d(TAG, "Daemon is already started.");
                return;
            }
            mMDnsManager.registerEventListener(mMDnsEventCallback);
            mMDnsManager.startDaemon();
            mIsDaemonStarted = true;
            maybeScheduleStop();
            mServiceLogs.log("Start mdns_responder daemon");
        }

        private void maybeStopDaemon() {
            if (!mIsDaemonStarted) {
                if (DBG) Log.d(TAG, "Daemon has not been started.");
                return;
            }
            mMDnsManager.unregisterEventListener(mMDnsEventCallback);
            mMDnsManager.stopDaemon();
            mIsDaemonStarted = false;
            mServiceLogs.log("Stop mdns_responder daemon");
        }

        private boolean isAnyRequestActive() {
            return mIdToClientInfoMap.size() != 0;
        }

        private void scheduleStop() {
            sendMessageDelayed(NsdManager.DAEMON_CLEANUP, mCleanupDelayMs);
        }
        private void maybeScheduleStop() {
            // The native daemon should stay alive and can't be cleanup
            // if any legacy client connected.
            if (!isAnyRequestActive() && mLegacyClientCount == 0) {
                scheduleStop();
            }
        }

        private void cancelStop() {
            this.removeMessages(NsdManager.DAEMON_CLEANUP);
        }

        private void maybeStartMonitoringSockets() {
            if (mIsMonitoringSocketsStarted) {
                if (DBG) Log.d(TAG, "Socket monitoring is already started.");
                return;
            }

            mMdnsSocketProvider.startMonitoringSockets();
            mIsMonitoringSocketsStarted = true;
        }

        private void maybeStopMonitoringSocketsIfNoActiveRequest() {
            if (!mIsMonitoringSocketsStarted) return;
            if (isAnyRequestActive()) return;

            mMdnsSocketProvider.requestStopWhenInactive();
            mIsMonitoringSocketsStarted = false;
        }

        NsdStateMachine(String name, Handler handler) {
            super(name, handler);
            addState(mDefaultState);
                addState(mEnabledState, mDefaultState);
            State initialState = mEnabledState;
            setInitialState(initialState);
            setLogRecSize(25);
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo cInfo;
                final int clientId = msg.arg2;
                switch (msg.what) {
                    case NsdManager.REGISTER_CLIENT:
                        final ConnectorArgs arg = (ConnectorArgs) msg.obj;
                        final INsdManagerCallback cb = arg.callback;
                        try {
                            cb.asBinder().linkToDeath(arg.connector, 0);
                            final String tag = "Client" + arg.uid + "-" + mClientNumberId++;
                            cInfo = new ClientInfo(cb, arg.useJavaBackend,
                                    mServiceLogs.forSubComponent(tag));
                            mClients.put(arg.connector, cInfo);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Client " + clientId + " has already died");
                        }
                        break;
                    case NsdManager.UNREGISTER_CLIENT:
                        final NsdServiceConnector connector = (NsdServiceConnector) msg.obj;
                        cInfo = mClients.remove(connector);
                        if (cInfo != null) {
                            cInfo.expungeAllRequests();
                            if (cInfo.isPreSClient()) {
                                mLegacyClientCount -= 1;
                            }
                        }
                        maybeStopMonitoringSocketsIfNoActiveRequest();
                        maybeScheduleStop();
                        break;
                    case NsdManager.DISCOVER_SERVICES:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onDiscoverServicesFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                       break;
                    case NsdManager.STOP_DISCOVERY:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onStopDiscoveryFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onRegisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.UNREGISTER_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onUnregisterServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        break;
                    case NsdManager.STOP_RESOLUTION:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onStopResolutionFailed(
                                    clientId, NsdManager.FAILURE_OPERATION_NOT_RUNNING);
                        }
                        break;
                    case NsdManager.REGISTER_SERVICE_CALLBACK:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cInfo.onServiceInfoCallbackRegistrationFailed(
                                    clientId, NsdManager.FAILURE_BAD_PARAMETERS);
                        }
                        break;
                    case NsdManager.DAEMON_CLEANUP:
                        maybeStopDaemon();
                        break;
                    // This event should be only sent by the legacy (target SDK < S) clients.
                    // Mark the sending client as legacy.
                    case NsdManager.DAEMON_STARTUP:
                        cInfo = getClientInfoForReply(msg);
                        if (cInfo != null) {
                            cancelStop();
                            cInfo.setPreSClient();
                            mLegacyClientCount += 1;
                            maybeStartDaemon();
                        }
                        break;
                    default:
                        Log.e(TAG, "Unhandled " + msg);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private ClientInfo getClientInfoForReply(Message msg) {
                final ListenerArgs args = (ListenerArgs) msg.obj;
                return mClients.get(args.connector);
            }
        }

        class EnabledState extends State {
            @Override
            public void enter() {
                sendNsdStateChangeBroadcast(true);
            }

            @Override
            public void exit() {
                // TODO: it is incorrect to stop the daemon without expunging all requests
                // and sending error callbacks to clients.
                scheduleStop();
            }

            private boolean requestLimitReached(ClientInfo clientInfo) {
                if (clientInfo.mClientRequests.size() >= ClientInfo.MAX_LIMIT) {
                    if (DBG) Log.d(TAG, "Exceeded max outstanding requests " + clientInfo);
                    return true;
                }
                return false;
            }

            private void storeLegacyRequestMap(int clientId, int globalId, ClientInfo clientInfo,
                    int what) {
                clientInfo.mClientRequests.put(clientId, new LegacyClientRequest(globalId, what));
                mIdToClientInfoMap.put(globalId, clientInfo);
                // Remove the cleanup event because here comes a new request.
                cancelStop();
            }

            private void storeAdvertiserRequestMap(int clientId, int globalId,
                    ClientInfo clientInfo) {
                clientInfo.mClientRequests.put(clientId, new AdvertiserClientRequest(globalId));
                mIdToClientInfoMap.put(globalId, clientInfo);
            }

            private void removeRequestMap(int clientId, int globalId, ClientInfo clientInfo) {
                final ClientRequest existing = clientInfo.mClientRequests.get(clientId);
                if (existing == null) return;
                clientInfo.mClientRequests.remove(clientId);
                mIdToClientInfoMap.remove(globalId);

                if (existing instanceof LegacyClientRequest) {
                    maybeScheduleStop();
                } else {
                    maybeStopMonitoringSocketsIfNoActiveRequest();
                }
            }

            private void storeDiscoveryManagerRequestMap(int clientId, int globalId,
                    MdnsListener listener, ClientInfo clientInfo) {
                clientInfo.mClientRequests.put(clientId,
                        new DiscoveryManagerRequest(globalId, listener));
                mIdToClientInfoMap.put(globalId, clientInfo);
            }

            /**
             * Truncate a service name to up to 63 UTF-8 bytes.
             *
             * See RFC6763 4.1.1: service instance names are UTF-8 and up to 63 bytes. Truncating
             * names used in registerService follows historical behavior (see mdnsresponder
             * handle_regservice_request).
             */
            @NonNull
            private String truncateServiceName(@NonNull String originalName) {
                return MdnsUtils.truncateServiceName(originalName, MAX_LABEL_LENGTH);
            }

            private void stopDiscoveryManagerRequest(ClientRequest request, int clientId, int id,
                    ClientInfo clientInfo) {
                clientInfo.unregisterMdnsListenerFromRequest(request);
                removeRequestMap(clientId, id, clientInfo);
            }

            @Override
            public boolean processMessage(Message msg) {
                final ClientInfo clientInfo;
                final int id;
                final int clientId = msg.arg2;
                final ListenerArgs args;
                switch (msg.what) {
                    case NsdManager.DISCOVER_SERVICES: {
                        if (DBG) Log.d(TAG, "Discover services");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in discovery");
                            break;
                        }

                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onDiscoverServicesFailed(
                                    clientId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        id = getUniqueId();
                        final Pair<String, String> typeAndSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeAndSubtype == null
                                ? null : typeAndSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                || mDeps.isMdnsDiscoveryManagerEnabled(mContext)
                                || useDiscoveryManagerForType(serviceType)) {
                            if (serviceType == null) {
                                clientInfo.onDiscoverServicesFailed(clientId,
                                        NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }

                            final String listenServiceType = serviceType + ".local";
                            maybeStartMonitoringSockets();
                            final MdnsListener listener =
                                    new DiscoveryListener(clientId, id, info, listenServiceType);
                            final MdnsSearchOptions.Builder optionsBuilder =
                                    MdnsSearchOptions.newBuilder()
                                            .setNetwork(info.getNetwork())
                                            .setRemoveExpiredService(true)
                                            .setIsPassiveMode(true);
                            if (typeAndSubtype.second != null) {
                                // The parsing ensures subtype starts with an underscore.
                                // MdnsSearchOptions expects the underscore to not be present.
                                optionsBuilder.addSubtype(typeAndSubtype.second.substring(1));
                            }
                            mMdnsDiscoveryManager.registerListener(
                                    listenServiceType, listener, optionsBuilder.build());
                            storeDiscoveryManagerRequestMap(clientId, id, listener, clientInfo);
                            clientInfo.onDiscoverServicesStarted(clientId, info);
                            clientInfo.log("Register a DiscoveryListener " + id
                                    + " for service type:" + listenServiceType);
                        } else {
                            maybeStartDaemon();
                            if (discoverServices(id, info)) {
                                if (DBG) {
                                    Log.d(TAG, "Discover " + msg.arg2 + " " + id
                                            + info.getServiceType());
                                }
                                storeLegacyRequestMap(clientId, id, clientInfo, msg.what);
                                clientInfo.onDiscoverServicesStarted(clientId, info);
                            } else {
                                stopServiceDiscovery(id);
                                clientInfo.onDiscoverServicesFailed(clientId,
                                        NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.STOP_DISCOVERY: {
                        if (DBG) Log.d(TAG, "Stop service discovery");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in stop discovery");
                            break;
                        }

                        final ClientRequest request = clientInfo.mClientRequests.get(clientId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in STOP_DISCOVERY");
                            break;
                        }
                        id = request.mGlobalId;
                        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
                        // point, so this needs to check the type of the original request to
                        // unregister instead of looking at the flag value.
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(request, clientId, id, clientInfo);
                            clientInfo.onStopDiscoverySucceeded(clientId);
                            clientInfo.log("Unregister the DiscoveryListener " + id);
                        } else {
                            removeRequestMap(clientId, id, clientInfo);
                            if (stopServiceDiscovery(id)) {
                                clientInfo.onStopDiscoverySucceeded(clientId);
                            } else {
                                clientInfo.onStopDiscoveryFailed(
                                        clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.REGISTER_SERVICE: {
                        if (DBG) Log.d(TAG, "Register service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in registration");
                            break;
                        }

                        if (requestLimitReached(clientInfo)) {
                            clientInfo.onRegisterServiceFailed(
                                    clientId, NsdManager.FAILURE_MAX_LIMIT);
                            break;
                        }

                        id = getUniqueId();
                        final NsdServiceInfo serviceInfo = args.serviceInfo;
                        final String serviceType = serviceInfo.getServiceType();
                        final Pair<String, String> typeSubtype = parseTypeAndSubtype(serviceType);
                        final String registerServiceType = typeSubtype == null
                                ? null : typeSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                || mDeps.isMdnsAdvertiserEnabled(mContext)
                                || useAdvertiserForType(registerServiceType)) {
                            if (registerServiceType == null) {
                                Log.e(TAG, "Invalid service type: " + serviceType);
                                clientInfo.onRegisterServiceFailed(clientId,
                                        NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }
                            serviceInfo.setServiceType(registerServiceType);
                            serviceInfo.setServiceName(truncateServiceName(
                                    serviceInfo.getServiceName()));

                            maybeStartMonitoringSockets();
                            // TODO: pass in the subtype as well. Including the subtype in the
                            // service type would generate service instance names like
                            // Name._subtype._sub._type._tcp, which is incorrect
                            // (it should be Name._type._tcp).
                            mAdvertiser.addService(id, serviceInfo, typeSubtype.second);
                            storeAdvertiserRequestMap(clientId, id, clientInfo);
                        } else {
                            maybeStartDaemon();
                            if (registerService(id, serviceInfo)) {
                                if (DBG) Log.d(TAG, "Register " + clientId + " " + id);
                                storeLegacyRequestMap(clientId, id, clientInfo, msg.what);
                                // Return success after mDns reports success
                            } else {
                                unregisterService(id);
                                clientInfo.onRegisterServiceFailed(
                                        clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }

                        }
                        break;
                    }
                    case NsdManager.UNREGISTER_SERVICE: {
                        if (DBG) Log.d(TAG, "unregister service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in unregistration");
                            break;
                        }
                        final ClientRequest request = clientInfo.mClientRequests.get(clientId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE");
                            break;
                        }
                        id = request.mGlobalId;
                        removeRequestMap(clientId, id, clientInfo);

                        // Note isMdnsAdvertiserEnabled may have changed to false at this point,
                        // so this needs to check the type of the original request to unregister
                        // instead of looking at the flag value.
                        if (request instanceof AdvertiserClientRequest) {
                            mAdvertiser.removeService(id);
                            clientInfo.onUnregisterServiceSucceeded(clientId);
                        } else {
                            if (unregisterService(id)) {
                                clientInfo.onUnregisterServiceSucceeded(clientId);
                            } else {
                                clientInfo.onUnregisterServiceFailed(
                                        clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.RESOLVE_SERVICE: {
                        if (DBG) Log.d(TAG, "Resolve service");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in resolution");
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        id = getUniqueId();
                        final Pair<String, String> typeSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeSubtype == null
                                ? null : typeSubtype.first;
                        if (clientInfo.mUseJavaBackend
                                ||  mDeps.isMdnsDiscoveryManagerEnabled(mContext)
                                || useDiscoveryManagerForType(serviceType)) {
                            if (serviceType == null) {
                                clientInfo.onResolveServiceFailed(clientId,
                                        NsdManager.FAILURE_INTERNAL_ERROR);
                                break;
                            }
                            final String resolveServiceType = serviceType + ".local";

                            maybeStartMonitoringSockets();
                            final MdnsListener listener =
                                    new ResolutionListener(clientId, id, info, resolveServiceType);
                            final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                                    .setNetwork(info.getNetwork())
                                    .setIsPassiveMode(true)
                                    .setResolveInstanceName(info.getServiceName())
                                    .setRemoveExpiredService(true)
                                    .build();
                            mMdnsDiscoveryManager.registerListener(
                                    resolveServiceType, listener, options);
                            storeDiscoveryManagerRequestMap(clientId, id, listener, clientInfo);
                            clientInfo.log("Register a ResolutionListener " + id
                                    + " for service type:" + resolveServiceType);
                        } else {
                            if (clientInfo.mResolvedService != null) {
                                clientInfo.onResolveServiceFailed(
                                        clientId, NsdManager.FAILURE_ALREADY_ACTIVE);
                                break;
                            }

                            maybeStartDaemon();
                            if (resolveService(id, info)) {
                                clientInfo.mResolvedService = new NsdServiceInfo();
                                storeLegacyRequestMap(clientId, id, clientInfo, msg.what);
                            } else {
                                clientInfo.onResolveServiceFailed(
                                        clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            }
                        }
                        break;
                    }
                    case NsdManager.STOP_RESOLUTION: {
                        if (DBG) Log.d(TAG, "Stop service resolution");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in stop resolution");
                            break;
                        }

                        final ClientRequest request = clientInfo.mClientRequests.get(clientId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in STOP_RESOLUTION");
                            break;
                        }
                        id = request.mGlobalId;
                        // Note isMdnsDiscoveryManagerEnabled may have changed to false at this
                        // point, so this needs to check the type of the original request to
                        // unregister instead of looking at the flag value.
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(request, clientId, id, clientInfo);
                            clientInfo.onStopResolutionSucceeded(clientId);
                            clientInfo.log("Unregister the ResolutionListener " + id);
                        } else {
                            removeRequestMap(clientId, id, clientInfo);
                            if (stopResolveService(id)) {
                                clientInfo.onStopResolutionSucceeded(clientId);
                            } else {
                                clientInfo.onStopResolutionFailed(
                                        clientId, NsdManager.FAILURE_OPERATION_NOT_RUNNING);
                            }
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    }
                    case NsdManager.REGISTER_SERVICE_CALLBACK: {
                        if (DBG) Log.d(TAG, "Register a service callback");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in callback registration");
                            break;
                        }

                        final NsdServiceInfo info = args.serviceInfo;
                        id = getUniqueId();
                        final Pair<String, String> typeAndSubtype =
                                parseTypeAndSubtype(info.getServiceType());
                        final String serviceType = typeAndSubtype == null
                                ? null : typeAndSubtype.first;
                        if (serviceType == null) {
                            clientInfo.onServiceInfoCallbackRegistrationFailed(clientId,
                                    NsdManager.FAILURE_BAD_PARAMETERS);
                            break;
                        }
                        final String resolveServiceType = serviceType + ".local";

                        maybeStartMonitoringSockets();
                        final MdnsListener listener =
                                new ServiceInfoListener(clientId, id, info, resolveServiceType);
                        final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                                .setNetwork(info.getNetwork())
                                .setIsPassiveMode(true)
                                .setResolveInstanceName(info.getServiceName())
                                .setRemoveExpiredService(true)
                                .build();
                        mMdnsDiscoveryManager.registerListener(
                                resolveServiceType, listener, options);
                        storeDiscoveryManagerRequestMap(clientId, id, listener, clientInfo);
                        clientInfo.log("Register a ServiceInfoListener " + id
                                + " for service type:" + resolveServiceType);
                        break;
                    }
                    case NsdManager.UNREGISTER_SERVICE_CALLBACK: {
                        if (DBG) Log.d(TAG, "Unregister a service callback");
                        args = (ListenerArgs) msg.obj;
                        clientInfo = mClients.get(args.connector);
                        // If the binder death notification for a INsdManagerCallback was received
                        // before any calls are received by NsdService, the clientInfo would be
                        // cleared and cause NPE. Add a null check here to prevent this corner case.
                        if (clientInfo == null) {
                            Log.e(TAG, "Unknown connector in callback unregistration");
                            break;
                        }

                        final ClientRequest request = clientInfo.mClientRequests.get(clientId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in UNREGISTER_SERVICE_CALLBACK");
                            break;
                        }
                        id = request.mGlobalId;
                        if (request instanceof DiscoveryManagerRequest) {
                            stopDiscoveryManagerRequest(request, clientId, id, clientInfo);
                            clientInfo.onServiceInfoCallbackUnregistered(clientId);
                            clientInfo.log("Unregister the ServiceInfoListener " + id);
                        } else {
                            loge("Unregister failed with non-DiscoveryManagerRequest.");
                        }
                        break;
                    }
                    case MDNS_SERVICE_EVENT:
                        if (!handleMDnsServiceEvent(msg.arg1, msg.arg2, msg.obj)) {
                            return NOT_HANDLED;
                        }
                        break;
                    case MDNS_DISCOVERY_MANAGER_EVENT:
                        if (!handleMdnsDiscoveryManagerEvent(msg.arg1, msg.arg2, msg.obj)) {
                            return NOT_HANDLED;
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            private boolean handleMDnsServiceEvent(int code, int id, Object obj) {
                NsdServiceInfo servInfo;
                ClientInfo clientInfo = mIdToClientInfoMap.get(id);
                if (clientInfo == null) {
                    Log.e(TAG, String.format("id %d for %d has no client mapping", id, code));
                    return false;
                }

                /* This goes in response as msg.arg2 */
                int clientId = clientInfo.getClientId(id);
                if (clientId < 0) {
                    // This can happen because of race conditions. For example,
                    // SERVICE_FOUND may race with STOP_SERVICE_DISCOVERY,
                    // and we may get in this situation.
                    Log.d(TAG, String.format("%d for listener id %d that is no longer active",
                            code, id));
                    return false;
                }
                if (DBG) {
                    Log.d(TAG, String.format("MDns service event code:%d id=%d", code, id));
                }
                switch (code) {
                    case IMDnsEventListener.SERVICE_FOUND: {
                        final DiscoveryInfo info = (DiscoveryInfo) obj;
                        final String name = info.serviceName;
                        final String type = info.registrationType;
                        servInfo = new NsdServiceInfo(name, type);
                        final int foundNetId = info.netId;
                        if (foundNetId == 0L) {
                            // Ignore services that do not have a Network: they are not usable
                            // by apps, as they would need privileged permissions to use
                            // interfaces that do not have an associated Network.
                            break;
                        }
                        if (foundNetId == INetd.DUMMY_NET_ID) {
                            // Ignore services on the dummy0 interface: they are only seen when
                            // discovering locally advertised services, and are not reachable
                            // through that interface.
                            break;
                        }
                        setServiceNetworkForCallback(servInfo, info.netId, info.interfaceIdx);
                        clientInfo.onServiceFound(clientId, servInfo);
                        break;
                    }
                    case IMDnsEventListener.SERVICE_LOST: {
                        final DiscoveryInfo info = (DiscoveryInfo) obj;
                        final String name = info.serviceName;
                        final String type = info.registrationType;
                        final int lostNetId = info.netId;
                        servInfo = new NsdServiceInfo(name, type);
                        // The network could be set to null (netId 0) if it was torn down when the
                        // service is lost
                        // TODO: avoid returning null in that case, possibly by remembering
                        // found services on the same interface index and their network at the time
                        setServiceNetworkForCallback(servInfo, lostNetId, info.interfaceIdx);
                        clientInfo.onServiceLost(clientId, servInfo);
                        break;
                    }
                    case IMDnsEventListener.SERVICE_DISCOVERY_FAILED:
                        clientInfo.onDiscoverServicesFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case IMDnsEventListener.SERVICE_REGISTERED: {
                        final RegistrationInfo info = (RegistrationInfo) obj;
                        final String name = info.serviceName;
                        servInfo = new NsdServiceInfo(name, null /* serviceType */);
                        clientInfo.onRegisterServiceSucceeded(clientId, servInfo);
                        break;
                    }
                    case IMDnsEventListener.SERVICE_REGISTRATION_FAILED:
                        clientInfo.onRegisterServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        break;
                    case IMDnsEventListener.SERVICE_RESOLVED: {
                        final ResolutionInfo info = (ResolutionInfo) obj;
                        int index = 0;
                        final String fullName = info.serviceFullName;
                        while (index < fullName.length() && fullName.charAt(index) != '.') {
                            if (fullName.charAt(index) == '\\') {
                                ++index;
                            }
                            ++index;
                        }
                        if (index >= fullName.length()) {
                            Log.e(TAG, "Invalid service found " + fullName);
                            break;
                        }

                        String name = unescape(fullName.substring(0, index));
                        String rest = fullName.substring(index);
                        String type = rest.replace(".local.", "");

                        final NsdServiceInfo serviceInfo = clientInfo.mResolvedService;
                        serviceInfo.setServiceName(name);
                        serviceInfo.setServiceType(type);
                        serviceInfo.setPort(info.port);
                        serviceInfo.setTxtRecords(info.txtRecord);
                        // Network will be added after SERVICE_GET_ADDR_SUCCESS

                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);

                        final int id2 = getUniqueId();
                        if (getAddrInfo(id2, info.hostname, info.interfaceIdx)) {
                            storeLegacyRequestMap(clientId, id2, clientInfo,
                                    NsdManager.RESOLVE_SERVICE);
                        } else {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                            clientInfo.mResolvedService = null;
                        }
                        break;
                    }
                    case IMDnsEventListener.SERVICE_RESOLUTION_FAILED:
                        /* NNN resolveId errorCode */
                        stopResolveService(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.onResolveServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        clientInfo.mResolvedService = null;
                        break;
                    case IMDnsEventListener.SERVICE_GET_ADDR_FAILED:
                        /* NNN resolveId errorCode */
                        stopGetAddrInfo(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.onResolveServiceFailed(
                                clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        clientInfo.mResolvedService = null;
                        break;
                    case IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS: {
                        /* NNN resolveId hostname ttl addr interfaceIdx netId */
                        final GetAddressInfo info = (GetAddressInfo) obj;
                        final String address = info.address;
                        final int netId = info.netId;
                        InetAddress serviceHost = null;
                        try {
                            serviceHost = InetAddress.getByName(address);
                        } catch (UnknownHostException e) {
                            Log.wtf(TAG, "Invalid host in GET_ADDR_SUCCESS", e);
                        }

                        // If the resolved service is on an interface without a network, consider it
                        // as a failure: it would not be usable by apps as they would need
                        // privileged permissions.
                        if (netId != NETID_UNSET && serviceHost != null) {
                            clientInfo.mResolvedService.setHost(serviceHost);
                            setServiceNetworkForCallback(clientInfo.mResolvedService,
                                    netId, info.interfaceIdx);
                            clientInfo.onResolveServiceSucceeded(
                                    clientId, clientInfo.mResolvedService);
                        } else {
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }
                        stopGetAddrInfo(id);
                        removeRequestMap(clientId, id, clientInfo);
                        clientInfo.mResolvedService = null;
                        break;
                    }
                    default:
                        return false;
                }
                return true;
            }

            @Nullable
            private NsdServiceInfo buildNsdServiceInfoFromMdnsEvent(
                    final MdnsEvent event, int code) {
                final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                final String[] typeArray = serviceInfo.getServiceType();
                final String joinedType;
                if (typeArray.length == 0
                        || !typeArray[typeArray.length - 1].equals(LOCAL_DOMAIN_NAME)) {
                    Log.wtf(TAG, "MdnsServiceInfo type does not end in .local: "
                            + Arrays.toString(typeArray));
                    return null;
                } else {
                    joinedType = TextUtils.join(".",
                            Arrays.copyOfRange(typeArray, 0, typeArray.length - 1));
                }
                final String serviceType;
                switch (code) {
                    case NsdManager.SERVICE_FOUND:
                    case NsdManager.SERVICE_LOST:
                        // For consistency with historical behavior, discovered service types have
                        // a dot at the end.
                        serviceType = joinedType + ".";
                        break;
                    case RESOLVE_SERVICE_SUCCEEDED:
                        // For consistency with historical behavior, resolved service types have
                        // a dot at the beginning.
                        serviceType = "." + joinedType;
                        break;
                    default:
                        serviceType = joinedType;
                        break;
                }
                final String serviceName = serviceInfo.getServiceInstanceName();
                final NsdServiceInfo servInfo = new NsdServiceInfo(serviceName, serviceType);
                final Network network = serviceInfo.getNetwork();
                // In MdnsDiscoveryManagerEvent, the Network can be null which means it is a
                // network for Tethering interface. In other words, the network == null means the
                // network has netId = INetd.LOCAL_NET_ID.
                setServiceNetworkForCallback(
                        servInfo,
                        network == null ? INetd.LOCAL_NET_ID : network.netId,
                        serviceInfo.getInterfaceIndex());
                return servInfo;
            }

            private boolean handleMdnsDiscoveryManagerEvent(
                    int transactionId, int code, Object obj) {
                final ClientInfo clientInfo = mIdToClientInfoMap.get(transactionId);
                if (clientInfo == null) {
                    Log.e(TAG, String.format(
                            "id %d for %d has no client mapping", transactionId, code));
                    return false;
                }

                final MdnsEvent event = (MdnsEvent) obj;
                final int clientId = event.mClientId;
                final NsdServiceInfo info = buildNsdServiceInfoFromMdnsEvent(event, code);
                // Errors are already logged if null
                if (info == null) return false;
                if (DBG) {
                    Log.d(TAG, String.format("MdnsDiscoveryManager event code=%s transactionId=%d",
                            NsdManager.nameOf(code), transactionId));
                }
                switch (code) {
                    case NsdManager.SERVICE_FOUND:
                        clientInfo.onServiceFound(clientId, info);
                        break;
                    case NsdManager.SERVICE_LOST:
                        clientInfo.onServiceLost(clientId, info);
                        break;
                    case NsdManager.RESOLVE_SERVICE_SUCCEEDED: {
                        final ClientRequest request = clientInfo.mClientRequests.get(clientId);
                        if (request == null) {
                            Log.e(TAG, "Unknown client request in RESOLVE_SERVICE_SUCCEEDED");
                            break;
                        }
                        final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                        info.setPort(serviceInfo.getPort());

                        Map<String, String> attrs = serviceInfo.getAttributes();
                        for (Map.Entry<String, String> kv : attrs.entrySet()) {
                            final String key = kv.getKey();
                            try {
                                info.setAttribute(key, serviceInfo.getAttributeAsBytes(key));
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "Invalid attribute", e);
                            }
                        }
                        final List<InetAddress> addresses = getInetAddresses(serviceInfo);
                        if (addresses.size() != 0) {
                            info.setHostAddresses(addresses);
                            clientInfo.onResolveServiceSucceeded(clientId, info);
                        } else {
                            // No address. Notify resolution failure.
                            clientInfo.onResolveServiceFailed(
                                    clientId, NsdManager.FAILURE_INTERNAL_ERROR);
                        }

                        // Unregister the listener immediately like IMDnsEventListener design
                        if (!(request instanceof DiscoveryManagerRequest)) {
                            Log.wtf(TAG, "non-DiscoveryManager request in DiscoveryManager event");
                            break;
                        }
                        stopDiscoveryManagerRequest(request, clientId, transactionId, clientInfo);
                        break;
                    }
                    case NsdManager.SERVICE_UPDATED: {
                        final MdnsServiceInfo serviceInfo = event.mMdnsServiceInfo;
                        info.setPort(serviceInfo.getPort());

                        Map<String, String> attrs = serviceInfo.getAttributes();
                        for (Map.Entry<String, String> kv : attrs.entrySet()) {
                            final String key = kv.getKey();
                            try {
                                info.setAttribute(key, serviceInfo.getAttributeAsBytes(key));
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "Invalid attribute", e);
                            }
                        }

                        final List<InetAddress> addresses = getInetAddresses(serviceInfo);
                        info.setHostAddresses(addresses);
                        clientInfo.onServiceUpdated(clientId, info);
                        break;
                    }
                    case NsdManager.SERVICE_UPDATED_LOST:
                        clientInfo.onServiceUpdatedLost(clientId);
                        break;
                    default:
                        return false;
                }
                return true;
            }
       }
    }

    @NonNull
    private static List<InetAddress> getInetAddresses(@NonNull MdnsServiceInfo serviceInfo) {
        final List<String> v4Addrs = serviceInfo.getIpv4Addresses();
        final List<String> v6Addrs = serviceInfo.getIpv6Addresses();
        final List<InetAddress> addresses = new ArrayList<>(v4Addrs.size() + v6Addrs.size());
        for (String ipv4Address : v4Addrs) {
            try {
                addresses.add(InetAddresses.parseNumericAddress(ipv4Address));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv4 address", e);
            }
        }
        for (String ipv6Address : v6Addrs) {
            try {
                final Inet6Address addr = (Inet6Address) InetAddresses.parseNumericAddress(
                        ipv6Address);
                addresses.add(InetAddressUtils.withScopeId(addr, serviceInfo.getInterfaceIndex()));
            } catch (IllegalArgumentException e) {
                Log.wtf(TAG, "Invalid ipv6 address", e);
            }
        }
        return addresses;
    }

    private static void setServiceNetworkForCallback(NsdServiceInfo info, int netId, int ifaceIdx) {
        switch (netId) {
            case NETID_UNSET:
                info.setNetwork(null);
                break;
            case INetd.LOCAL_NET_ID:
                // Special case for LOCAL_NET_ID: Networks on netId 99 are not generally
                // visible / usable for apps, so do not return it. Store the interface
                // index instead, so at least if the client tries to resolve the service
                // with that NsdServiceInfo, it will be done on the same interface.
                // If they recreate the NsdServiceInfo themselves, resolution would be
                // done on all interfaces as before T, which should also work.
                info.setNetwork(null);
                info.setInterfaceIndex(ifaceIdx);
                break;
            default:
                info.setNetwork(new Network(netId));
        }
    }

    // The full service name is escaped from standard DNS rules on mdnsresponder, making it suitable
    // for passing to standard system DNS APIs such as res_query() . Thus, make the service name
    // unescape for getting right service address. See "Notes on DNS Name Escaping" on
    // external/mdnsresponder/mDNSShared/dns_sd.h for more details.
    private String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (++i >= s.length()) {
                    Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                    break;
                }
                c = s.charAt(i);
                if (c != '.' && c != '\\') {
                    if (i + 2 >= s.length()) {
                        Log.e(TAG, "Unexpected end of escape sequence in: " + s);
                        break;
                    }
                    c = (char) ((c - '0') * 100 + (s.charAt(i + 1) - '0') * 10
                            + (s.charAt(i + 2) - '0'));
                    i += 2;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Check the given service type is valid and construct it to a service type
     * which can use for discovery / resolution service.
     *
     * <p>The valid service type should be 2 labels, or 3 labels if the query is for a
     * subtype (see RFC6763 7.1). Each label is up to 63 characters and must start with an
     * underscore; they are alphanumerical characters or dashes or underscore, except the
     * last one that is just alphanumerical. The last label must be _tcp or _udp.
     *
     * <p>The subtype may also be specified with a comma after the service type, for example
     * _type._tcp,_subtype.
     *
     * @param serviceType the request service type for discovery / resolution service
     * @return constructed service type or null if the given service type is invalid.
     */
    @Nullable
    public static Pair<String, String> parseTypeAndSubtype(String serviceType) {
        if (TextUtils.isEmpty(serviceType)) return null;

        final String typeOrSubtypePattern = "_[a-zA-Z0-9-_]{1,61}[a-zA-Z0-9]";
        final Pattern serviceTypePattern = Pattern.compile(
                // Optional leading subtype (_subtype._type._tcp)
                // (?: xxx) is a non-capturing parenthesis, don't capture the dot
                "^(?:(" + typeOrSubtypePattern + ")\\.)?"
                        // Actual type (_type._tcp.local)
                        + "(" + typeOrSubtypePattern + "\\._(?:tcp|udp))"
                        // Drop '.' at the end of service type that is compatible with old backend.
                        // e.g. allow "_type._tcp.local."
                        + "\\.?"
                        // Optional subtype after comma, for "_type._tcp,_subtype" format
                        + "(?:,(" + typeOrSubtypePattern + "))?"
                        + "$");
        final Matcher matcher = serviceTypePattern.matcher(serviceType);
        if (!matcher.matches()) return null;
        // Use the subtype either at the beginning or after the comma
        final String subtype = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
        return new Pair<>(matcher.group(2), subtype);
    }

    @VisibleForTesting
    NsdService(Context ctx, Handler handler, long cleanupDelayMs) {
        this(ctx, handler, cleanupDelayMs, new Dependencies());
    }

    @VisibleForTesting
    NsdService(Context ctx, Handler handler, long cleanupDelayMs, Dependencies deps) {
        mCleanupDelayMs = cleanupDelayMs;
        mContext = ctx;
        mNsdStateMachine = new NsdStateMachine(TAG, handler);
        mNsdStateMachine.start();
        mMDnsManager = ctx.getSystemService(MDnsManager.class);
        mMDnsEventCallback = new MDnsEventCallback(mNsdStateMachine);
        mDeps = deps;

        mMdnsSocketProvider = deps.makeMdnsSocketProvider(ctx, handler.getLooper(),
                LOGGER.forSubComponent("MdnsSocketProvider"));
        // Netlink monitor starts on boot, and intentionally never stopped, to ensure that all
        // address events are received.
        handler.post(mMdnsSocketProvider::startNetLinkMonitor);
        mMdnsSocketClient =
                new MdnsMultinetworkSocketClient(handler.getLooper(), mMdnsSocketProvider);
        mMdnsDiscoveryManager = deps.makeMdnsDiscoveryManager(new ExecutorProvider(),
                mMdnsSocketClient, LOGGER.forSubComponent("MdnsDiscoveryManager"));
        handler.post(() -> mMdnsSocketClient.setCallback(mMdnsDiscoveryManager));
        mAdvertiser = deps.makeMdnsAdvertiser(handler.getLooper(), mMdnsSocketProvider,
                new AdvertiserCallback(), LOGGER.forSubComponent("MdnsAdvertiser"));
    }

    /**
     * Dependencies of NsdService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Check whether the MdnsDiscoveryManager feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsDiscoveryManager feature is enabled.
         */
        public boolean isMdnsDiscoveryManagerEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isFeatureEnabled(context, NAMESPACE_TETHERING,
                    MDNS_DISCOVERY_MANAGER_VERSION, DeviceConfigUtils.TETHERING_MODULE_NAME,
                    false /* defaultEnabled */);
        }

        /**
         * Check whether the MdnsAdvertiser feature is enabled.
         *
         * @param context The global context information about an app environment.
         * @return true if the MdnsAdvertiser feature is enabled.
         */
        public boolean isMdnsAdvertiserEnabled(Context context) {
            return isAtLeastU() || DeviceConfigUtils.isFeatureEnabled(context, NAMESPACE_TETHERING,
                    MDNS_ADVERTISER_VERSION, DeviceConfigUtils.TETHERING_MODULE_NAME,
                    false /* defaultEnabled */);
        }

        /**
         * Get the type allowlist flag value.
         * @see #MDNS_TYPE_ALLOWLIST_FLAGS
         */
        @Nullable
        public String getTypeAllowlistFlags() {
            return DeviceConfigUtils.getDeviceConfigProperty(NAMESPACE_TETHERING,
                    MDNS_TYPE_ALLOWLIST_FLAGS, null);
        }

        /**
         * @see DeviceConfigUtils#isFeatureEnabled(Context, String, String, String, boolean)
         */
        public boolean isFeatureEnabled(Context context, String feature) {
            return DeviceConfigUtils.isFeatureEnabled(context, NAMESPACE_TETHERING,
                    feature, DeviceConfigUtils.TETHERING_MODULE_NAME, false /* defaultEnabled */);
        }

        /**
         * @see MdnsDiscoveryManager
         */
        public MdnsDiscoveryManager makeMdnsDiscoveryManager(
                @NonNull ExecutorProvider executorProvider,
                @NonNull MdnsMultinetworkSocketClient socketClient, @NonNull SharedLog sharedLog) {
            return new MdnsDiscoveryManager(executorProvider, socketClient, sharedLog);
        }

        /**
         * @see MdnsAdvertiser
         */
        public MdnsAdvertiser makeMdnsAdvertiser(
                @NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
                @NonNull MdnsAdvertiser.AdvertiserCallback cb, @NonNull SharedLog sharedLog) {
            return new MdnsAdvertiser(looper, socketProvider, cb, sharedLog);
        }

        /**
         * @see MdnsSocketProvider
         */
        public MdnsSocketProvider makeMdnsSocketProvider(@NonNull Context context,
                @NonNull Looper looper, @NonNull SharedLog sharedLog) {
            return new MdnsSocketProvider(context, looper, sharedLog);
        }
    }

    /**
     * Return whether a type is allowlisted to use the Java backend.
     * @param type The service type
     * @param flagPrefix One of {@link #MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX} or
     *                   {@link #MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX}.
     */
    private boolean isTypeAllowlistedForJavaBackend(@Nullable String type,
            @NonNull String flagPrefix) {
        if (type == null) return false;
        final String typesConfig = mDeps.getTypeAllowlistFlags();
        if (TextUtils.isEmpty(typesConfig)) return false;

        final String mappingPrefix = type + ":";
        String mappedFlag = null;
        for (String mapping : TextUtils.split(typesConfig, ",")) {
            if (mapping.startsWith(mappingPrefix)) {
                mappedFlag = mapping.substring(mappingPrefix.length());
                break;
            }
        }

        if (mappedFlag == null) return false;

        return mDeps.isFeatureEnabled(mContext,
                flagPrefix + mappedFlag + MDNS_ALLOWLIST_FLAG_SUFFIX);
    }

    private boolean useDiscoveryManagerForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_DISCOVERY_MANAGER_ALLOWLIST_FLAG_PREFIX);
    }

    private boolean useAdvertiserForType(@Nullable String type) {
        return isTypeAllowlistedForJavaBackend(type, MDNS_ADVERTISER_ALLOWLIST_FLAG_PREFIX);
    }

    public static NsdService create(Context context) {
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        NsdService service = new NsdService(context, handler, CLEANUP_DELAY_MS);
        return service;
    }

    private static class MDnsEventCallback extends IMDnsEventListener.Stub {
        private final StateMachine mStateMachine;

        MDnsEventCallback(StateMachine sm) {
            mStateMachine = sm;
        }

        @Override
        public void onServiceRegistrationStatus(final RegistrationInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceDiscoveryStatus(final DiscoveryInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onServiceResolutionStatus(final ResolutionInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public void onGettingServiceAddressStatus(final GetAddressInfo status) {
            mStateMachine.sendMessage(
                    MDNS_SERVICE_EVENT, status.result, status.id, status);
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() throws RemoteException {
            return this.HASH;
        }
    }

    private class AdvertiserCallback implements MdnsAdvertiser.AdvertiserCallback {
        @Override
        public void onRegisterServiceSucceeded(int serviceId, NsdServiceInfo registeredInfo) {
            final ClientInfo clientInfo = getClientInfoOrLog(serviceId);
            if (clientInfo == null) return;

            final int clientId = getClientIdOrLog(clientInfo, serviceId);
            if (clientId < 0) return;

            // onRegisterServiceSucceeded only has the service name in its info. This aligns with
            // historical behavior.
            final NsdServiceInfo cbInfo = new NsdServiceInfo(registeredInfo.getServiceName(), null);
            clientInfo.onRegisterServiceSucceeded(clientId, cbInfo);
        }

        @Override
        public void onRegisterServiceFailed(int serviceId, int errorCode) {
            final ClientInfo clientInfo = getClientInfoOrLog(serviceId);
            if (clientInfo == null) return;

            final int clientId = getClientIdOrLog(clientInfo, serviceId);
            if (clientId < 0) return;

            clientInfo.onRegisterServiceFailed(clientId, errorCode);
        }

        private ClientInfo getClientInfoOrLog(int serviceId) {
            final ClientInfo clientInfo = mIdToClientInfoMap.get(serviceId);
            if (clientInfo == null) {
                Log.e(TAG, String.format("Callback for service %d has no client", serviceId));
            }
            return clientInfo;
        }

        private int getClientIdOrLog(@NonNull ClientInfo info, int serviceId) {
            final int clientId = info.getClientId(serviceId);
            if (clientId < 0) {
                Log.e(TAG, String.format("Client ID not found for service %d", serviceId));
            }
            return clientId;
        }
    }

    private static class ConnectorArgs {
        @NonNull public final NsdServiceConnector connector;
        @NonNull public final INsdManagerCallback callback;
        public final boolean useJavaBackend;
        public final int uid;

        ConnectorArgs(@NonNull NsdServiceConnector connector, @NonNull INsdManagerCallback callback,
                boolean useJavaBackend, int uid) {
            this.connector = connector;
            this.callback = callback;
            this.useJavaBackend = useJavaBackend;
            this.uid = uid;
        }
    }

    @Override
    public INsdServiceConnector connect(INsdManagerCallback cb, boolean useJavaBackend) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.INTERNET, "NsdService");
        if (DBG) Log.d(TAG, "New client connect. useJavaBackend=" + useJavaBackend);
        final INsdServiceConnector connector = new NsdServiceConnector();
        mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(NsdManager.REGISTER_CLIENT,
                new ConnectorArgs((NsdServiceConnector) connector, cb, useJavaBackend,
                        Binder.getCallingUid())));
        return connector;
    }

    private static class ListenerArgs {
        public final NsdServiceConnector connector;
        public final NsdServiceInfo serviceInfo;
        ListenerArgs(NsdServiceConnector connector, NsdServiceInfo serviceInfo) {
            this.connector = connector;
            this.serviceInfo = serviceInfo;
        }
    }

    private class NsdServiceConnector extends INsdServiceConnector.Stub
            implements IBinder.DeathRecipient  {
        @Override
        public void registerService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.REGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void unregisterService(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.UNREGISTER_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, null)));
        }

        @Override
        public void discoverServices(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DISCOVER_SERVICES, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void stopDiscovery(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.STOP_DISCOVERY, 0, listenerKey, new ListenerArgs(this, null)));
        }

        @Override
        public void resolveService(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.RESOLVE_SERVICE, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void stopResolution(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.STOP_RESOLUTION, 0, listenerKey, new ListenerArgs(this, null)));
        }

        @Override
        public void registerServiceInfoCallback(int listenerKey, NsdServiceInfo serviceInfo) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.REGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, serviceInfo)));
        }

        @Override
        public void unregisterServiceInfoCallback(int listenerKey) {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.UNREGISTER_SERVICE_CALLBACK, 0, listenerKey,
                    new ListenerArgs(this, null)));
        }

        @Override
        public void startDaemon() {
            mNsdStateMachine.sendMessage(mNsdStateMachine.obtainMessage(
                    NsdManager.DAEMON_STARTUP, new ListenerArgs(this, null)));
        }

        @Override
        public void binderDied() {
            mNsdStateMachine.sendMessage(
                    mNsdStateMachine.obtainMessage(NsdManager.UNREGISTER_CLIENT, this));
        }
    }

    private void sendNsdStateChangeBroadcast(boolean isEnabled) {
        final Intent intent = new Intent(NsdManager.ACTION_NSD_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        int nsdState = isEnabled ? NsdManager.NSD_STATE_ENABLED : NsdManager.NSD_STATE_DISABLED;
        intent.putExtra(NsdManager.EXTRA_NSD_STATE, nsdState);
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    private boolean registerService(int regId, NsdServiceInfo service) {
        if (DBG) {
            Log.d(TAG, "registerService: " + regId + " " + service);
        }
        String name = service.getServiceName();
        String type = service.getServiceType();
        int port = service.getPort();
        byte[] textRecord = service.getTxtRecord();
        final int registerInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && registerInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to register service on not found");
            return false;
        }
        return mMDnsManager.registerService(regId, name, type, port, textRecord, registerInterface);
    }

    private boolean unregisterService(int regId) {
        return mMDnsManager.stopOperation(regId);
    }

    private boolean discoverServices(int discoveryId, NsdServiceInfo serviceInfo) {
        final String type = serviceInfo.getServiceType();
        final int discoverInterface = getNetworkInterfaceIndex(serviceInfo);
        if (serviceInfo.getNetwork() != null && discoverInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to discover service on not found");
            return false;
        }
        return mMDnsManager.discover(discoveryId, type, discoverInterface);
    }

    private boolean stopServiceDiscovery(int discoveryId) {
        return mMDnsManager.stopOperation(discoveryId);
    }

    private boolean resolveService(int resolveId, NsdServiceInfo service) {
        final String name = service.getServiceName();
        final String type = service.getServiceType();
        final int resolveInterface = getNetworkInterfaceIndex(service);
        if (service.getNetwork() != null && resolveInterface == IFACE_IDX_ANY) {
            Log.e(TAG, "Interface to resolve service on not found");
            return false;
        }
        return mMDnsManager.resolve(resolveId, name, type, "local.", resolveInterface);
    }

    /**
     * Guess the interface to use to resolve or discover a service on a specific network.
     *
     * This is an imperfect guess, as for example the network may be gone or not yet fully
     * registered. This is fine as failing is correct if the network is gone, and a client
     * attempting to resolve/discover on a network not yet setup would have a bad time anyway; also
     * this is to support the legacy mdnsresponder implementation, which historically resolved
     * services on an unspecified network.
     */
    private int getNetworkInterfaceIndex(NsdServiceInfo serviceInfo) {
        final Network network = serviceInfo.getNetwork();
        if (network == null) {
            // Fallback to getInterfaceIndex if present (typically if the NsdServiceInfo was
            // provided by NsdService from discovery results, and the service was found on an
            // interface that has no app-usable Network).
            if (serviceInfo.getInterfaceIndex() != 0) {
                return serviceInfo.getInterfaceIndex();
            }
            return IFACE_IDX_ANY;
        }

        final ConnectivityManager cm = mContext.getSystemService(ConnectivityManager.class);
        if (cm == null) {
            Log.wtf(TAG, "No ConnectivityManager for resolveService");
            return IFACE_IDX_ANY;
        }
        final LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) return IFACE_IDX_ANY;

        // Only resolve on non-stacked interfaces
        final NetworkInterface iface;
        try {
            iface = NetworkInterface.getByName(lp.getInterfaceName());
        } catch (SocketException e) {
            Log.e(TAG, "Error querying interface", e);
            return IFACE_IDX_ANY;
        }

        if (iface == null) {
            Log.e(TAG, "Interface not found: " + lp.getInterfaceName());
            return IFACE_IDX_ANY;
        }

        return iface.getIndex();
    }

    private boolean stopResolveService(int resolveId) {
        return mMDnsManager.stopOperation(resolveId);
    }

    private boolean getAddrInfo(int resolveId, String hostname, int interfaceIdx) {
        return mMDnsManager.getServiceAddress(resolveId, hostname, interfaceIdx);
    }

    private boolean stopGetAddrInfo(int resolveId) {
        return mMDnsManager.stopOperation(resolveId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!PermissionUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        // Dump state machine logs
        mNsdStateMachine.dump(fd, pw, args);

        // Dump service and clients logs
        pw.println();
        pw.println("Logs:");
        pw.increaseIndent();
        mServiceLogs.reverseDump(pw);
        pw.decreaseIndent();
    }

    private abstract static class ClientRequest {
        private final int mGlobalId;

        private ClientRequest(int globalId) {
            mGlobalId = globalId;
        }
    }

    private static class LegacyClientRequest extends ClientRequest {
        private final int mRequestCode;

        private LegacyClientRequest(int globalId, int requestCode) {
            super(globalId);
            mRequestCode = requestCode;
        }
    }

    private static class AdvertiserClientRequest extends ClientRequest {
        private AdvertiserClientRequest(int globalId) {
            super(globalId);
        }
    }

    private static class DiscoveryManagerRequest extends ClientRequest {
        @NonNull
        private final MdnsListener mListener;

        private DiscoveryManagerRequest(int globalId, @NonNull MdnsListener listener) {
            super(globalId);
            mListener = listener;
        }
    }

    /* Information tracked per client */
    private class ClientInfo {

        private static final int MAX_LIMIT = 10;
        private final INsdManagerCallback mCb;
        /* Remembers a resolved service until getaddrinfo completes */
        private NsdServiceInfo mResolvedService;

        /* A map from client-side ID (listenerKey) to the request */
        private final SparseArray<ClientRequest> mClientRequests = new SparseArray<>();

        // The target SDK of this client < Build.VERSION_CODES.S
        private boolean mIsPreSClient = false;
        // The flag of using java backend if the client's target SDK >= U
        private final boolean mUseJavaBackend;
        // Store client logs
        private final SharedLog mClientLogs;

        private ClientInfo(INsdManagerCallback cb, boolean useJavaBackend, SharedLog sharedLog) {
            mCb = cb;
            mUseJavaBackend = useJavaBackend;
            mClientLogs = sharedLog;
            mClientLogs.log("New client. useJavaBackend=" + useJavaBackend);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("mResolvedService ").append(mResolvedService).append("\n");
            sb.append("mIsLegacy ").append(mIsPreSClient).append("\n");
            for (int i = 0; i < mClientRequests.size(); i++) {
                int clientID = mClientRequests.keyAt(i);
                sb.append("clientId ")
                        .append(clientID)
                        .append(" mDnsId ").append(mClientRequests.valueAt(i).mGlobalId)
                        .append(" type ").append(
                                mClientRequests.valueAt(i).getClass().getSimpleName())
                        .append("\n");
            }
            return sb.toString();
        }

        private boolean isPreSClient() {
            return mIsPreSClient;
        }

        private void setPreSClient() {
            mIsPreSClient = true;
        }

        private void unregisterMdnsListenerFromRequest(ClientRequest request) {
            final MdnsListener listener =
                    ((DiscoveryManagerRequest) request).mListener;
            mMdnsDiscoveryManager.unregisterListener(
                    listener.getListenedServiceType(), listener);
        }

        // Remove any pending requests from the global map when we get rid of a client,
        // and send cancellations to the daemon.
        private void expungeAllRequests() {
            mClientLogs.log("Client unregistered. expungeAllRequests!");
            // TODO: to keep handler responsive, do not clean all requests for that client at once.
            for (int i = 0; i < mClientRequests.size(); i++) {
                final int clientId = mClientRequests.keyAt(i);
                final ClientRequest request = mClientRequests.valueAt(i);
                final int globalId = request.mGlobalId;
                mIdToClientInfoMap.remove(globalId);
                if (DBG) {
                    Log.d(TAG, "Terminating client-ID " + clientId
                            + " global-ID " + globalId + " type " + mClientRequests.get(clientId));
                }

                if (request instanceof DiscoveryManagerRequest) {
                    unregisterMdnsListenerFromRequest(request);
                    continue;
                }

                if (request instanceof AdvertiserClientRequest) {
                    mAdvertiser.removeService(globalId);
                    continue;
                }

                if (!(request instanceof LegacyClientRequest)) {
                    throw new IllegalStateException("Unknown request type: " + request.getClass());
                }

                switch (((LegacyClientRequest) request).mRequestCode) {
                    case NsdManager.DISCOVER_SERVICES:
                        stopServiceDiscovery(globalId);
                        break;
                    case NsdManager.RESOLVE_SERVICE:
                        stopResolveService(globalId);
                        break;
                    case NsdManager.REGISTER_SERVICE:
                        unregisterService(globalId);
                        break;
                    default:
                        break;
                }
            }
            mClientRequests.clear();
        }

        // mClientRequests is a sparse array of listener id -> ClientRequest.  For a given
        // mDnsClient id, return the corresponding listener id.  mDnsClient id is also called a
        // global id.
        private int getClientId(final int globalId) {
            for (int i = 0; i < mClientRequests.size(); i++) {
                if (mClientRequests.valueAt(i).mGlobalId == globalId) {
                    return mClientRequests.keyAt(i);
                }
            }
            return -1;
        }

        private void log(String message) {
            mClientLogs.log(message);
        }

        void onDiscoverServicesStarted(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onDiscoverServicesStarted(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesStarted", e);
            }
        }

        void onDiscoverServicesFailed(int listenerKey, int error) {
            try {
                mCb.onDiscoverServicesFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onDiscoverServicesFailed", e);
            }
        }

        void onServiceFound(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceFound(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceFound(", e);
            }
        }

        void onServiceLost(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceLost(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceLost(", e);
            }
        }

        void onStopDiscoveryFailed(int listenerKey, int error) {
            try {
                mCb.onStopDiscoveryFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoveryFailed", e);
            }
        }

        void onStopDiscoverySucceeded(int listenerKey) {
            try {
                mCb.onStopDiscoverySucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopDiscoverySucceeded", e);
            }
        }

        void onRegisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onRegisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceFailed", e);
            }
        }

        void onRegisterServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onRegisterServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onRegisterServiceSucceeded", e);
            }
        }

        void onUnregisterServiceFailed(int listenerKey, int error) {
            try {
                mCb.onUnregisterServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceFailed", e);
            }
        }

        void onUnregisterServiceSucceeded(int listenerKey) {
            try {
                mCb.onUnregisterServiceSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onUnregisterServiceSucceeded", e);
            }
        }

        void onResolveServiceFailed(int listenerKey, int error) {
            try {
                mCb.onResolveServiceFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceFailed", e);
            }
        }

        void onResolveServiceSucceeded(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onResolveServiceSucceeded(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onResolveServiceSucceeded", e);
            }
        }

        void onStopResolutionFailed(int listenerKey, int error) {
            try {
                mCb.onStopResolutionFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionFailed", e);
            }
        }

        void onStopResolutionSucceeded(int listenerKey) {
            try {
                mCb.onStopResolutionSucceeded(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onStopResolutionSucceeded", e);
            }
        }

        void onServiceInfoCallbackRegistrationFailed(int listenerKey, int error) {
            try {
                mCb.onServiceInfoCallbackRegistrationFailed(listenerKey, error);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackRegistrationFailed", e);
            }
        }

        void onServiceUpdated(int listenerKey, NsdServiceInfo info) {
            try {
                mCb.onServiceUpdated(listenerKey, info);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdated", e);
            }
        }

        void onServiceUpdatedLost(int listenerKey) {
            try {
                mCb.onServiceUpdatedLost(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceUpdatedLost", e);
            }
        }

        void onServiceInfoCallbackUnregistered(int listenerKey) {
            try {
                mCb.onServiceInfoCallbackUnregistered(listenerKey);
            } catch (RemoteException e) {
                Log.e(TAG, "Error calling onServiceInfoCallbackUnregistered", e);
            }
        }
    }
}
