/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.server.connectivity.mdns.MdnsRecord.MAX_LABEL_LENGTH;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * MdnsAdvertiser manages advertising services per {@link com.android.server.NsdService} requests.
 *
 * All methods except the constructor must be called on the looper thread.
 */
public class MdnsAdvertiser {
    private static final String TAG = MdnsAdvertiser.class.getSimpleName();
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    // Top-level domain for link-local queries, as per RFC6762 3.
    private static final String LOCAL_TLD = "local";


    private final Looper mLooper;
    private final AdvertiserCallback mCb;

    // Max-sized buffers to be used as temporary buffer to read/build packets. May be used by
    // multiple components, but only for self-contained operations in the looper thread, so not
    // concurrently.
    // TODO: set according to MTU. 1300 should fit for ethernet MTU 1500 with some overhead.
    private final byte[] mPacketCreationBuffer = new byte[1300];

    private final MdnsSocketProvider mSocketProvider;
    private final ArrayMap<Network, InterfaceAdvertiserRequest> mAdvertiserRequests =
            new ArrayMap<>();
    private final ArrayMap<MdnsInterfaceSocket, MdnsInterfaceAdvertiser> mAllAdvertisers =
            new ArrayMap<>();
    private final SparseArray<Registration> mRegistrations = new SparseArray<>();
    private final Dependencies mDeps;

    private String[] mDeviceHostName;
    @NonNull private final SharedLog mSharedLog;

    /**
     * Dependencies for {@link MdnsAdvertiser}, useful for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * @see MdnsInterfaceAdvertiser
         */
        public MdnsInterfaceAdvertiser makeAdvertiser(@NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> initialAddresses,
                @NonNull Looper looper, @NonNull byte[] packetCreationBuffer,
                @NonNull MdnsInterfaceAdvertiser.Callback cb,
                @NonNull String[] deviceHostName,
                @NonNull SharedLog sharedLog) {
            // Note NetworkInterface is final and not mockable
            return new MdnsInterfaceAdvertiser(socket, initialAddresses, looper,
                    packetCreationBuffer, cb, deviceHostName, sharedLog);
        }

        /**
         * Generates a unique hostname to be used by the device.
         */
        @NonNull
        public String[] generateHostname() {
            // Generate a very-probably-unique hostname. This allows minimizing possible conflicts
            // to the point that probing for it is no longer necessary (as per RFC6762 8.1 last
            // paragraph), and does not leak more information than what could already be obtained by
            // looking at the mDNS packets source address.
            // This differs from historical behavior that just used "Android.local" for many
            // devices, creating a lot of conflicts.
            // Having a different hostname per interface is an acceptable option as per RFC6762 14.
            // This hostname will change every time the interface is reconnected, so this does not
            // allow tracking the device.
            // TODO: consider deriving a hostname from other sources, such as the IPv6 addresses
            // (reusing the same privacy-protecting mechanics).
            return new String[] {
                    "Android_" + UUID.randomUUID().toString().replace("-", ""), LOCAL_TLD };
        }
    }

    private final MdnsInterfaceAdvertiser.Callback mInterfaceAdvertiserCb =
            new MdnsInterfaceAdvertiser.Callback() {
        @Override
        public void onRegisterServiceSucceeded(
                @NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            // Wait for all current interfaces to be done probing before notifying of success.
            if (any(mAllAdvertisers, (k, a) -> a.isProbing(serviceId))) return;
            // The service may still be unregistered/renamed if a conflict is found on a later added
            // interface, or if a conflicting announcement/reply is detected (RFC6762 9.)

            final Registration registration = mRegistrations.get(serviceId);
            if (registration == null) {
                Log.wtf(TAG, "Register succeeded for unknown registration");
                return;
            }
            if (!registration.mNotifiedRegistrationSuccess) {
                mCb.onRegisterServiceSucceeded(serviceId, registration.getServiceInfo());
                registration.mNotifiedRegistrationSuccess = true;
            }
        }

        @Override
        public void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId) {
            mSharedLog.i("Found conflict, restarted probing for service " + serviceId);

            final Registration registration = mRegistrations.get(serviceId);
            if (registration == null) return;
            if (registration.mNotifiedRegistrationSuccess) {
                // TODO: consider notifying clients that the service is no longer registered with
                // the old name (back to probing). The legacy implementation did not send any
                // callback though; it only sent onServiceRegistered after re-probing finishes
                // (with the old, conflicting, actually not used name as argument... The new
                // implementation will send callbacks with the new name).
                registration.mNotifiedRegistrationSuccess = false;

                // The service was done probing, just reset it to probing state (RFC6762 9.)
                forAllAdvertisers(a -> a.restartProbingForConflict(serviceId));
                return;
            }

            // Conflict was found during probing; rename once to find a name that has no conflict
            registration.updateForConflict(
                    registration.makeNewServiceInfoForConflict(1 /* renameCount */),
                    1 /* renameCount */);

            // Keep renaming if the new name conflicts in local registrations
            updateRegistrationUntilNoConflict((net, adv) -> adv.hasRegistration(registration),
                    registration);

            // Update advertisers to use the new name
            forAllAdvertisers(a -> a.renameServiceForConflict(
                    serviceId, registration.getServiceInfo()));
        }

        @Override
        public void onDestroyed(@NonNull MdnsInterfaceSocket socket) {
            for (int i = mAdvertiserRequests.size() - 1; i >= 0; i--) {
                if (mAdvertiserRequests.valueAt(i).onAdvertiserDestroyed(socket)) {
                    mAdvertiserRequests.removeAt(i);
                }
            }
            mAllAdvertisers.remove(socket);
        }
    };

    private boolean hasAnyConflict(
            @NonNull BiPredicate<Network, InterfaceAdvertiserRequest> applicableAdvertiserFilter,
            @NonNull NsdServiceInfo newInfo) {
        return any(mAdvertiserRequests, (network, adv) ->
                applicableAdvertiserFilter.test(network, adv) && adv.hasConflict(newInfo));
    }

    private void updateRegistrationUntilNoConflict(
            @NonNull BiPredicate<Network, InterfaceAdvertiserRequest> applicableAdvertiserFilter,
            @NonNull Registration registration) {
        int renameCount = 0;
        NsdServiceInfo newInfo = registration.getServiceInfo();
        while (hasAnyConflict(applicableAdvertiserFilter, newInfo)) {
            renameCount++;
            newInfo = registration.makeNewServiceInfoForConflict(renameCount);
        }
        registration.updateForConflict(newInfo, renameCount);
    }

    /**
     * A request for a {@link MdnsInterfaceAdvertiser}.
     *
     * This class tracks services to be advertised on all sockets provided via a registered
     * {@link MdnsSocketProvider.SocketCallback}.
     */
    private class InterfaceAdvertiserRequest implements MdnsSocketProvider.SocketCallback {
        /** Registrations to add to newer MdnsInterfaceAdvertisers when sockets are created. */
        @NonNull
        private final SparseArray<Registration> mPendingRegistrations = new SparseArray<>();
        @NonNull
        private final ArrayMap<MdnsInterfaceSocket, MdnsInterfaceAdvertiser> mAdvertisers =
                new ArrayMap<>();

        InterfaceAdvertiserRequest(@Nullable Network requestedNetwork) {
            mSocketProvider.requestSocket(requestedNetwork, this);
        }

        /**
         * Called when an advertiser was destroyed, after all services were unregistered and it sent
         * exit announcements, or the interface is gone.
         *
         * @return true if this {@link InterfaceAdvertiserRequest} should now be deleted.
         */
        boolean onAdvertiserDestroyed(@NonNull MdnsInterfaceSocket socket) {
            mAdvertisers.remove(socket);
            if (mAdvertisers.size() == 0 && mPendingRegistrations.size() == 0) {
                // No advertiser is using sockets from this request anymore (in particular for exit
                // announcements), and there is no registration so newer sockets will not be
                // necessary, so the request can be unregistered.
                mSocketProvider.unrequestSocket(this);
                return true;
            }
            return false;
        }

        /**
         * Return whether this {@link InterfaceAdvertiserRequest} has the given registration.
         */
        boolean hasRegistration(@NonNull Registration registration) {
            return mPendingRegistrations.indexOfValue(registration) >= 0;
        }

        /**
         * Return whether using the proposed new {@link NsdServiceInfo} to add a registration would
         * cause a conflict in this {@link InterfaceAdvertiserRequest}.
         */
        boolean hasConflict(@NonNull NsdServiceInfo newInfo) {
            return getConflictingService(newInfo) >= 0;
        }

        /**
         * Get the ID of a conflicting service, or -1 if none.
         */
        int getConflictingService(@NonNull NsdServiceInfo info) {
            for (int i = 0; i < mPendingRegistrations.size(); i++) {
                final NsdServiceInfo other = mPendingRegistrations.valueAt(i).getServiceInfo();
                if (info.getServiceName().equals(other.getServiceName())
                        && info.getServiceType().equals(other.getServiceType())) {
                    return mPendingRegistrations.keyAt(i);
                }
            }
            return -1;
        }

        /**
         * Add a service.
         *
         * Conflicts must be checked via {@link #getConflictingService} before attempting to add.
         */
        void addService(int id, Registration registration) {
            mPendingRegistrations.put(id, registration);
            for (int i = 0; i < mAdvertisers.size(); i++) {
                try {
                    mAdvertisers.valueAt(i).addService(
                            id, registration.getServiceInfo(), registration.getSubtype());
                } catch (NameConflictException e) {
                    Log.wtf(TAG, "Name conflict adding services that should have unique names", e);
                }
            }
        }

        void removeService(int id) {
            mPendingRegistrations.remove(id);
            for (int i = 0; i < mAdvertisers.size(); i++) {
                mAdvertisers.valueAt(i).removeService(id);
            }
        }

        @Override
        public void onSocketCreated(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket,
                @NonNull List<LinkAddress> addresses) {
            MdnsInterfaceAdvertiser advertiser = mAllAdvertisers.get(socket);
            if (advertiser == null) {
                advertiser = mDeps.makeAdvertiser(socket, addresses, mLooper, mPacketCreationBuffer,
                        mInterfaceAdvertiserCb, mDeviceHostName,
                        mSharedLog.forSubComponent(socket.getInterface().getName()));
                mAllAdvertisers.put(socket, advertiser);
                advertiser.start();
            }
            mAdvertisers.put(socket, advertiser);
            for (int i = 0; i < mPendingRegistrations.size(); i++) {
                final Registration registration = mPendingRegistrations.valueAt(i);
                try {
                    advertiser.addService(mPendingRegistrations.keyAt(i),
                            registration.getServiceInfo(), registration.getSubtype());
                } catch (NameConflictException e) {
                    Log.wtf(TAG, "Name conflict adding services that should have unique names", e);
                }
            }
        }

        @Override
        public void onInterfaceDestroyed(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket) {
            final MdnsInterfaceAdvertiser advertiser = mAdvertisers.get(socket);
            if (advertiser != null) advertiser.destroyNow();
        }

        @Override
        public void onAddressesChanged(@NonNull Network network,
                @NonNull MdnsInterfaceSocket socket, @NonNull List<LinkAddress> addresses) {
            final MdnsInterfaceAdvertiser advertiser = mAdvertisers.get(socket);
            if (advertiser != null) advertiser.updateAddresses(addresses);
        }
    }

    private static class Registration {
        @NonNull
        final String mOriginalName;
        boolean mNotifiedRegistrationSuccess;
        private int mConflictCount;
        @NonNull
        private NsdServiceInfo mServiceInfo;
        @Nullable
        private final String mSubtype;

        private Registration(@NonNull NsdServiceInfo serviceInfo, @Nullable String subtype) {
            this.mOriginalName = serviceInfo.getServiceName();
            this.mServiceInfo = serviceInfo;
            this.mSubtype = subtype;
        }

        /**
         * Update the registration to use a different service name, after a conflict was found.
         *
         * @param newInfo New service info to use.
         * @param renameCount How many renames were done before reaching the current name.
         */
        private void updateForConflict(@NonNull NsdServiceInfo newInfo, int renameCount) {
            mConflictCount += renameCount;
            mServiceInfo = newInfo;
        }

        /**
         * Make a new service name for the registration, after a conflict was found.
         *
         * If a name conflict was found during probing or because different advertising requests
         * used the same name, the registration is attempted again with a new name (here using
         * a number suffix, (1), (2) etc). Registration success is notified once probing succeeds
         * with a new name. This matches legacy behavior based on mdnsresponder, and appendix D of
         * RFC6763.
         *
         * @param renameCount How much to increase the number suffix for this conflict.
         */
        @NonNull
        public NsdServiceInfo makeNewServiceInfoForConflict(int renameCount) {
            // In case of conflict choose a different service name. After the first conflict use
            // "Name (2)", then "Name (3)" etc.
            // TODO: use a hidden method in NsdServiceInfo once MdnsAdvertiser is moved to service-t
            final NsdServiceInfo newInfo = new NsdServiceInfo();
            newInfo.setServiceName(getUpdatedServiceName(renameCount));
            newInfo.setServiceType(mServiceInfo.getServiceType());
            for (Map.Entry<String, byte[]> attr : mServiceInfo.getAttributes().entrySet()) {
                newInfo.setAttribute(attr.getKey(),
                        attr.getValue() == null ? null : new String(attr.getValue()));
            }
            newInfo.setHost(mServiceInfo.getHost());
            newInfo.setPort(mServiceInfo.getPort());
            newInfo.setNetwork(mServiceInfo.getNetwork());
            // interfaceIndex is not set when registering
            return newInfo;
        }

        private String getUpdatedServiceName(int renameCount) {
            final String suffix = " (" + (mConflictCount + renameCount + 1) + ")";
            final String truncatedServiceName = MdnsUtils.truncateServiceName(mOriginalName,
                    MAX_LABEL_LENGTH - suffix.length());
            return truncatedServiceName + suffix;
        }

        @NonNull
        public NsdServiceInfo getServiceInfo() {
            return mServiceInfo;
        }

        @Nullable
        public String getSubtype() {
            return mSubtype;
        }
    }

    /**
     * Callbacks for advertising services.
     *
     * Every method is called on the MdnsAdvertiser looper thread.
     */
    public interface AdvertiserCallback {
        /**
         * Called when a service was successfully registered, after probing.
         *
         * @param serviceId ID of the service provided when registering.
         * @param registeredInfo Registered info, which may be different from the requested info,
         *                       after probing and possibly choosing alternative service names.
         */
        void onRegisterServiceSucceeded(int serviceId, NsdServiceInfo registeredInfo);

        /**
         * Called when service registration failed.
         *
         * @param serviceId ID of the service provided when registering.
         * @param errorCode One of {@code NsdManager.FAILURE_*}
         */
        void onRegisterServiceFailed(int serviceId, int errorCode);

        // Unregistration is notified immediately as success in NsdService so no callback is needed
        // here.
    }

    public MdnsAdvertiser(@NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
            @NonNull AdvertiserCallback cb, @NonNull SharedLog sharedLog) {
        this(looper, socketProvider, cb, new Dependencies(), sharedLog);
    }

    @VisibleForTesting
    MdnsAdvertiser(@NonNull Looper looper, @NonNull MdnsSocketProvider socketProvider,
            @NonNull AdvertiserCallback cb, @NonNull Dependencies deps,
            @NonNull SharedLog sharedLog) {
        mLooper = looper;
        mCb = cb;
        mSocketProvider = socketProvider;
        mDeps = deps;
        mDeviceHostName = deps.generateHostname();
        mSharedLog = sharedLog;
    }

    private void checkThread() {
        if (Thread.currentThread() != mLooper.getThread()) {
            throw new IllegalStateException("This must be called on the looper thread");
        }
    }

    /**
     * Add a service to advertise.
     * @param id A unique ID for the service.
     * @param service The service info to advertise.
     * @param subtype An optional subtype to advertise the service with.
     */
    public void addService(int id, NsdServiceInfo service, @Nullable String subtype) {
        checkThread();
        if (mRegistrations.get(id) != null) {
            Log.e(TAG, "Adding duplicate registration for " + service);
            // TODO (b/264986328): add a more specific error code
            mCb.onRegisterServiceFailed(id, NsdManager.FAILURE_INTERNAL_ERROR);
            return;
        }

        mSharedLog.i("Adding service " + service + " with ID " + id + " and subtype " + subtype);

        final Network network = service.getNetwork();
        final Registration registration = new Registration(service, subtype);
        final BiPredicate<Network, InterfaceAdvertiserRequest> checkConflictFilter;
        if (network == null) {
            // If registering on all networks, no advertiser must have conflicts
            checkConflictFilter = (net, adv) -> true;
        } else {
            // If registering on one network, the matching network advertiser and the one for all
            // networks must not have conflicts
            checkConflictFilter = (net, adv) -> net == null || network.equals(net);
        }

        updateRegistrationUntilNoConflict(checkConflictFilter, registration);

        InterfaceAdvertiserRequest advertiser = mAdvertiserRequests.get(network);
        if (advertiser == null) {
            advertiser = new InterfaceAdvertiserRequest(network);
            mAdvertiserRequests.put(network, advertiser);
        }
        advertiser.addService(id, registration);
        mRegistrations.put(id, registration);
    }

    /**
     * Remove a previously added service.
     * @param id ID used when registering.
     */
    public void removeService(int id) {
        checkThread();
        if (!mRegistrations.contains(id)) return;
        mSharedLog.i("Removing service with ID " + id);
        for (int i = mAdvertiserRequests.size() - 1; i >= 0; i--) {
            final InterfaceAdvertiserRequest advertiser = mAdvertiserRequests.valueAt(i);
            advertiser.removeService(id);
        }
        mRegistrations.remove(id);
        // Regenerates host name when registrations removed.
        if (mRegistrations.size() == 0) {
            mDeviceHostName = mDeps.generateHostname();
        }
    }

    private static <K, V> boolean any(@NonNull ArrayMap<K, V> map,
            @NonNull BiPredicate<K, V> predicate) {
        for (int i = 0; i < map.size(); i++) {
            if (predicate.test(map.keyAt(i), map.valueAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void forAllAdvertisers(@NonNull Consumer<MdnsInterfaceAdvertiser> consumer) {
        any(mAllAdvertisers, (socket, advertiser) -> {
            consumer.accept(advertiser);
            return false;
        });
    }
}
