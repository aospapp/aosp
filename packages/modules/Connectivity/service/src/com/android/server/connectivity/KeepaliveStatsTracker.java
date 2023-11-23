/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.connectivity;

import static android.telephony.SubscriptionManager.OnSubscriptionsChangedListener;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkSpecifier;
import android.net.TelephonyNetworkSpecifier;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.metrics.DailykeepaliveInfoReported;
import com.android.metrics.DurationForNumOfKeepalive;
import com.android.metrics.DurationPerNumOfKeepalive;
import com.android.metrics.KeepaliveLifetimeForCarrier;
import com.android.metrics.KeepaliveLifetimePerCarrier;
import com.android.modules.utils.BackgroundThread;
import com.android.net.module.util.CollectionUtils;
import com.android.server.ConnectivityStatsLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tracks carrier and duration metrics of automatic on/off keepalives.
 *
 * <p>This class follows AutomaticOnOffKeepaliveTracker closely and its on*Keepalive methods needs
 * to be called in a timely manner to keep the metrics accurate. It is also not thread-safe and all
 * public methods must be called by the same thread, namely the ConnectivityService handler thread.
 */
public class KeepaliveStatsTracker {
    private static final String TAG = KeepaliveStatsTracker.class.getSimpleName();

    @NonNull private final Handler mConnectivityServiceHandler;
    @NonNull private final Dependencies mDependencies;

    // Mapping of subId to carrierId. Updates are received from OnSubscriptionsChangedListener
    private final SparseIntArray mCachedCarrierIdPerSubId = new SparseIntArray();
    // The default subscription id obtained from SubscriptionManager.getDefaultSubscriptionId.
    // Updates are received from the ACTION_DEFAULT_SUBSCRIPTION_CHANGED broadcast.
    private int mCachedDefaultSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    // Class to store network information, lifetime durations and active state of a keepalive.
    private static final class KeepaliveStats {
        // The carrier ID for a keepalive, or TelephonyManager.UNKNOWN_CARRIER_ID(-1) if not set.
        public final int carrierId;
        // The transport types of the underlying network for each keepalive. A network may include
        // multiple transport types. Each transport type is represented by a different bit, defined
        // in NetworkCapabilities
        public final int transportTypes;
        // The keepalive interval in millis.
        public final int intervalMs;
        // The uid of the app that requested the keepalive.
        public final int appUid;
        // Indicates if the keepalive is an automatic keepalive.
        public final boolean isAutoKeepalive;

        // Snapshot of the lifetime stats
        public static class LifetimeStats {
            public final int lifetimeMs;
            public final int activeLifetimeMs;

            LifetimeStats(int lifetimeMs, int activeLifetimeMs) {
                this.lifetimeMs = lifetimeMs;
                this.activeLifetimeMs = activeLifetimeMs;
            }
        }

        // The total time since the keepalive is started until it is stopped.
        private int mLifetimeMs = 0;
        // The total time the keepalive is active (not suspended).
        private int mActiveLifetimeMs = 0;

        // A timestamp of the most recent time the lifetime metrics was updated.
        private long mLastUpdateLifetimeTimestamp;

        // A flag to indicate if the keepalive is active.
        private boolean mKeepaliveActive = true;

        /**
         * Gets the lifetime stats for the keepalive, updated to timeNow, and then resets it.
         *
         * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
         */
        public LifetimeStats getAndResetLifetimeStats(long timeNow) {
            updateLifetimeStatsAndSetActive(timeNow, mKeepaliveActive);
            // Get a snapshot of the stats
            final LifetimeStats lifetimeStats = new LifetimeStats(mLifetimeMs, mActiveLifetimeMs);
            // Reset the stats
            resetLifetimeStats(timeNow);

            return lifetimeStats;
        }

        public boolean isKeepaliveActive() {
            return mKeepaliveActive;
        }

        KeepaliveStats(
                int carrierId,
                int transportTypes,
                int intervalSeconds,
                int appUid,
                boolean isAutoKeepalive,
                long timeNow) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMs = intervalSeconds * 1000;
            this.appUid = appUid;
            this.isAutoKeepalive = isAutoKeepalive;
            mLastUpdateLifetimeTimestamp = timeNow;
        }

        /**
         * Updates the lifetime metrics to the given time and sets the active state. This should be
         * called whenever the active state of the keepalive changes.
         *
         * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
         */
        public void updateLifetimeStatsAndSetActive(long timeNow, boolean keepaliveActive) {
            final int durationIncrease = (int) (timeNow - mLastUpdateLifetimeTimestamp);
            mLifetimeMs += durationIncrease;
            if (mKeepaliveActive) mActiveLifetimeMs += durationIncrease;

            mLastUpdateLifetimeTimestamp = timeNow;
            mKeepaliveActive = keepaliveActive;
        }

        /**
         * Resets the lifetime metrics but does not reset the active/stopped state of the keepalive.
         * This also updates the time to timeNow, ensuring stats will start from this time.
         *
         * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
         */
        public void resetLifetimeStats(long timeNow) {
            mLifetimeMs = 0;
            mActiveLifetimeMs = 0;
            mLastUpdateLifetimeTimestamp = timeNow;
        }
    }

    // List of duration stats metric where the index is the number of concurrent keepalives.
    // Each DurationForNumOfKeepalive message stores a registered duration and an active duration.
    // Registered duration is the total time spent with mNumRegisteredKeepalive == index.
    // Active duration is the total time spent with mNumActiveKeepalive == index.
    private final List<DurationForNumOfKeepalive.Builder> mDurationPerNumOfKeepalive =
            new ArrayList<>();

    // Map of keepalives identified by the id from getKeepaliveId to their stats information.
    private final SparseArray<KeepaliveStats> mKeepaliveStatsPerId = new SparseArray<>();

    // Generate a unique integer using a given network's netId and the slot number.
    // This is possible because netId is a 16 bit integer, so an integer with the first 16 bits as
    // the netId and the last 16 bits as the slot number can be created. This allows slot numbers to
    // be up to 2^16.
    private int getKeepaliveId(@NonNull Network network, int slot) {
        final int netId = network.getNetId();
        if (netId < 0 || netId >= (1 << 16)) {
            throw new IllegalArgumentException("Unexpected netId value: " + netId);
        }
        if (slot < 0 || slot >= (1 << 16)) {
            throw new IllegalArgumentException("Unexpected slot value: " + slot);
        }

        return (netId << 16) + slot;
    }

    // Class to act as the key to aggregate the KeepaliveLifetimeForCarrier stats.
    private static final class LifetimeKey {
        public final int carrierId;
        public final int transportTypes;
        public final int intervalMs;

        LifetimeKey(int carrierId, int transportTypes, int intervalMs) {
            this.carrierId = carrierId;
            this.transportTypes = transportTypes;
            this.intervalMs = intervalMs;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            final LifetimeKey that = (LifetimeKey) o;

            return carrierId == that.carrierId && transportTypes == that.transportTypes
                    && intervalMs == that.intervalMs;
        }

        @Override
        public int hashCode() {
            return carrierId + 3 * transportTypes + 5 * intervalMs;
        }
    }

    // Map to aggregate the KeepaliveLifetimeForCarrier stats using LifetimeKey as the key.
    final Map<LifetimeKey, KeepaliveLifetimeForCarrier.Builder> mAggregateKeepaliveLifetime =
            new HashMap<>();

    private final Set<Integer> mAppUids = new HashSet<Integer>();
    private int mNumKeepaliveRequests = 0;
    private int mNumAutomaticKeepaliveRequests = 0;

    private int mNumRegisteredKeepalive = 0;
    private int mNumActiveKeepalive = 0;

    // A timestamp of the most recent time the duration metrics was updated.
    private long mLastUpdateDurationsTimestamp;

    /** Dependency class */
    @VisibleForTesting
    public static class Dependencies {
        // Returns a timestamp with the time base of SystemClock.elapsedRealtime to keep durations
        // relative to start time and avoid timezone change, including time spent in deep sleep.
        public long getElapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }
    }

    public KeepaliveStatsTracker(@NonNull Context context, @NonNull Handler handler) {
        this(context, handler, new Dependencies());
    }

    @VisibleForTesting
    public KeepaliveStatsTracker(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull Dependencies dependencies) {
        Objects.requireNonNull(context);
        mDependencies = Objects.requireNonNull(dependencies);
        mConnectivityServiceHandler = Objects.requireNonNull(handler);

        final SubscriptionManager subscriptionManager =
                Objects.requireNonNull(context.getSystemService(SubscriptionManager.class));

        mLastUpdateDurationsTimestamp = mDependencies.getElapsedRealtime();
        context.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        mCachedDefaultSubscriptionId =
                                intent.getIntExtra(
                                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                    }
                },
                new IntentFilter(SubscriptionManager.ACTION_DEFAULT_SUBSCRIPTION_CHANGED),
                /* broadcastPermission= */ null,
                mConnectivityServiceHandler);

        // The default constructor for OnSubscriptionsChangedListener will always implicitly grab
        // the looper of the current thread. In the case the current thread does not have a looper,
        // this will throw. Therefore, post a runnable that creates it there.
        // When the callback is called on the BackgroundThread, post a message on the CS handler
        // thread to update the caches, which can only be touched there.
        BackgroundThread.getHandler().post(() ->
                subscriptionManager.addOnSubscriptionsChangedListener(
                        r -> r.run(), new OnSubscriptionsChangedListener() {
                            @Override
                            public void onSubscriptionsChanged() {
                                final List<SubscriptionInfo> activeSubInfoList =
                                        subscriptionManager.getActiveSubscriptionInfoList();
                                // A null subInfo list here indicates the current state is unknown
                                // but not necessarily empty, simply ignore it. Another call to the
                                // listener will be invoked in the future.
                                if (activeSubInfoList == null) return;
                                mConnectivityServiceHandler.post(() -> {
                                    mCachedCarrierIdPerSubId.clear();

                                    for (final SubscriptionInfo subInfo : activeSubInfoList) {
                                        mCachedCarrierIdPerSubId.put(subInfo.getSubscriptionId(),
                                                subInfo.getCarrierId());
                                    }
                                });
                            }
                        }));
    }

    /** Ensures the list of duration metrics is large enough for number of registered keepalives. */
    private void ensureDurationPerNumOfKeepaliveSize() {
        if (mNumActiveKeepalive < 0 || mNumRegisteredKeepalive < 0) {
            throw new IllegalStateException(
                    "Number of active or registered keepalives is negative");
        }
        if (mNumActiveKeepalive > mNumRegisteredKeepalive) {
            throw new IllegalStateException(
                    "Number of active keepalives greater than registered keepalives");
        }

        while (mDurationPerNumOfKeepalive.size() <= mNumRegisteredKeepalive) {
            final DurationForNumOfKeepalive.Builder durationForNumOfKeepalive =
                    DurationForNumOfKeepalive.newBuilder();
            durationForNumOfKeepalive.setNumOfKeepalive(mDurationPerNumOfKeepalive.size());
            durationForNumOfKeepalive.setKeepaliveRegisteredDurationsMsec(0);
            durationForNumOfKeepalive.setKeepaliveActiveDurationsMsec(0);

            mDurationPerNumOfKeepalive.add(durationForNumOfKeepalive);
        }
    }

    /**
     * Updates the durations metrics to the given time. This should always be called before making a
     * change to mNumRegisteredKeepalive or mNumActiveKeepalive to keep the duration metrics
     * correct.
     *
     * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
     */
    private void updateDurationsPerNumOfKeepalive(long timeNow) {
        if (mDurationPerNumOfKeepalive.size() < mNumRegisteredKeepalive) {
            Log.e(TAG, "Unexpected jump in number of registered keepalive");
        }
        ensureDurationPerNumOfKeepaliveSize();

        final int durationIncrease = (int) (timeNow - mLastUpdateDurationsTimestamp);
        final DurationForNumOfKeepalive.Builder durationForNumOfRegisteredKeepalive =
                mDurationPerNumOfKeepalive.get(mNumRegisteredKeepalive);

        durationForNumOfRegisteredKeepalive.setKeepaliveRegisteredDurationsMsec(
                durationForNumOfRegisteredKeepalive.getKeepaliveRegisteredDurationsMsec()
                        + durationIncrease);

        final DurationForNumOfKeepalive.Builder durationForNumOfActiveKeepalive =
                mDurationPerNumOfKeepalive.get(mNumActiveKeepalive);

        durationForNumOfActiveKeepalive.setKeepaliveActiveDurationsMsec(
                durationForNumOfActiveKeepalive.getKeepaliveActiveDurationsMsec()
                        + durationIncrease);

        mLastUpdateDurationsTimestamp = timeNow;
    }

    // TODO: Move this function to frameworks/libs/net/.../NetworkCapabilitiesUtils.java
    private static int getSubId(@NonNull NetworkCapabilities nc, int defaultSubId) {
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            final NetworkSpecifier networkSpecifier = nc.getNetworkSpecifier();
            if (networkSpecifier instanceof TelephonyNetworkSpecifier) {
                return ((TelephonyNetworkSpecifier) networkSpecifier).getSubscriptionId();
            }
            // Use the default subscriptionId.
            return defaultSubId;
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            final TransportInfo info = nc.getTransportInfo();
            if (info instanceof WifiInfo) {
                return ((WifiInfo) info).getSubscriptionId();
            }
        }

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private int getCarrierId(@NonNull NetworkCapabilities networkCapabilities) {
        // Try to get the correct subscription id.
        final int subId = getSubId(networkCapabilities, mCachedDefaultSubscriptionId);
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return TelephonyManager.UNKNOWN_CARRIER_ID;
        }
        return mCachedCarrierIdPerSubId.get(subId, TelephonyManager.UNKNOWN_CARRIER_ID);
    }

    private int getTransportTypes(@NonNull NetworkCapabilities networkCapabilities) {
        // Transport types are internally packed as bits starting from bit 0. Casting to int works
        // fine since for now and the foreseeable future, there will be less than 32 transports.
        return (int) networkCapabilities.getTransportTypesInternal();
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just started and is active. */
    public void onStartKeepalive(
            @NonNull Network network,
            int slot,
            @NonNull NetworkCapabilities nc,
            int intervalSeconds,
            int appUid,
            boolean isAutoKeepalive) {
        ensureRunningOnHandlerThread();
        final int keepaliveId = getKeepaliveId(network, slot);
        if (mKeepaliveStatsPerId.contains(keepaliveId)) {
            throw new IllegalArgumentException(
                    "Attempt to start keepalive stats on a known network, slot pair");
        }

        mNumKeepaliveRequests++;
        if (isAutoKeepalive) mNumAutomaticKeepaliveRequests++;
        mAppUids.add(appUid);

        final long timeNow = mDependencies.getElapsedRealtime();
        updateDurationsPerNumOfKeepalive(timeNow);

        mNumRegisteredKeepalive++;
        mNumActiveKeepalive++;

        final KeepaliveStats newKeepaliveStats =
                new KeepaliveStats(
                        getCarrierId(nc),
                        getTransportTypes(nc),
                        intervalSeconds,
                        appUid,
                        isAutoKeepalive,
                        timeNow);

        mKeepaliveStatsPerId.put(keepaliveId, newKeepaliveStats);
    }

    /**
     * Inform the KeepaliveStatsTracker that the keepalive with the given network, slot pair has
     * updated its active state to keepaliveActive.
     *
     * @return the KeepaliveStats associated with the network, slot pair or null if it is unknown.
     */
    private @NonNull KeepaliveStats onKeepaliveActive(
            @NonNull Network network, int slot, boolean keepaliveActive) {
        final long timeNow = mDependencies.getElapsedRealtime();
        return onKeepaliveActive(network, slot, keepaliveActive, timeNow);
    }

    /**
     * Inform the KeepaliveStatsTracker that the keepalive with the given network, slot pair has
     * updated its active state to keepaliveActive.
     *
     * @param network the network of the keepalive
     * @param slot the slot number of the keepalive
     * @param keepaliveActive the new active state of the keepalive
     * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
     * @return the KeepaliveStats associated with the network, slot pair or null if it is unknown.
     */
    private @NonNull KeepaliveStats onKeepaliveActive(
            @NonNull Network network, int slot, boolean keepaliveActive, long timeNow) {
        ensureRunningOnHandlerThread();

        final int keepaliveId = getKeepaliveId(network, slot);
        if (!mKeepaliveStatsPerId.contains(keepaliveId)) {
            throw new IllegalArgumentException(
                    "Attempt to set active keepalive on an unknown network, slot pair");
        }
        updateDurationsPerNumOfKeepalive(timeNow);

        final KeepaliveStats keepaliveStats = mKeepaliveStatsPerId.get(keepaliveId);
        if (keepaliveActive != keepaliveStats.isKeepaliveActive()) {
            mNumActiveKeepalive += keepaliveActive ? 1 : -1;
        }

        keepaliveStats.updateLifetimeStatsAndSetActive(timeNow, keepaliveActive);
        return keepaliveStats;
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been paused. */
    public void onPauseKeepalive(@NonNull Network network, int slot) {
        onKeepaliveActive(network, slot, /* keepaliveActive= */ false);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been resumed. */
    public void onResumeKeepalive(@NonNull Network network, int slot) {
        onKeepaliveActive(network, slot, /* keepaliveActive= */ true);
    }

    /** Inform the KeepaliveStatsTracker a keepalive has just been stopped. */
    public void onStopKeepalive(@NonNull Network network, int slot) {
        final int keepaliveId = getKeepaliveId(network, slot);
        final long timeNow = mDependencies.getElapsedRealtime();

        final KeepaliveStats keepaliveStats =
                onKeepaliveActive(network, slot, /* keepaliveActive= */ false, timeNow);

        mNumRegisteredKeepalive--;

        // add to the aggregate since it will be removed.
        addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
        // free up the slot.
        mKeepaliveStatsPerId.remove(keepaliveId);
    }

    /**
     * Updates and adds the lifetime metric of keepaliveStats to the aggregate.
     *
     * @param keepaliveStats the stats to add to the aggregate
     * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
     */
    private void addToAggregateKeepaliveLifetime(
            @NonNull KeepaliveStats keepaliveStats, long timeNow) {

        final KeepaliveStats.LifetimeStats lifetimeStats =
                keepaliveStats.getAndResetLifetimeStats(timeNow);

        final LifetimeKey key =
                new LifetimeKey(
                        keepaliveStats.carrierId,
                        keepaliveStats.transportTypes,
                        keepaliveStats.intervalMs);

        KeepaliveLifetimeForCarrier.Builder keepaliveLifetimeForCarrier =
                mAggregateKeepaliveLifetime.get(key);

        if (keepaliveLifetimeForCarrier == null) {
            keepaliveLifetimeForCarrier =
                    KeepaliveLifetimeForCarrier.newBuilder()
                            .setCarrierId(keepaliveStats.carrierId)
                            .setTransportTypes(keepaliveStats.transportTypes)
                            .setIntervalsMsec(keepaliveStats.intervalMs);
            mAggregateKeepaliveLifetime.put(key, keepaliveLifetimeForCarrier);
        }

        keepaliveLifetimeForCarrier.setLifetimeMsec(
                keepaliveLifetimeForCarrier.getLifetimeMsec() + lifetimeStats.lifetimeMs);
        keepaliveLifetimeForCarrier.setActiveLifetimeMsec(
                keepaliveLifetimeForCarrier.getActiveLifetimeMsec()
                        + lifetimeStats.activeLifetimeMs);
    }

    /**
     * Builds and returns DailykeepaliveInfoReported proto.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    @VisibleForTesting
    public @NonNull DailykeepaliveInfoReported buildKeepaliveMetrics() {
        ensureRunningOnHandlerThread();
        final long timeNow = mDependencies.getElapsedRealtime();
        return buildKeepaliveMetrics(timeNow);
    }

    /**
     * Updates the metrics to timeNow and builds and returns DailykeepaliveInfoReported proto.
     *
     * @param timeNow a timestamp obtained using Dependencies.getElapsedRealtime
     */
    private @NonNull DailykeepaliveInfoReported buildKeepaliveMetrics(long timeNow) {
        updateDurationsPerNumOfKeepalive(timeNow);

        final DurationPerNumOfKeepalive.Builder durationPerNumOfKeepalive =
                DurationPerNumOfKeepalive.newBuilder();

        mDurationPerNumOfKeepalive.forEach(
                durationForNumOfKeepalive ->
                        durationPerNumOfKeepalive.addDurationForNumOfKeepalive(
                                durationForNumOfKeepalive));

        final KeepaliveLifetimePerCarrier.Builder keepaliveLifetimePerCarrier =
                KeepaliveLifetimePerCarrier.newBuilder();

        for (int i = 0; i < mKeepaliveStatsPerId.size(); i++) {
            final KeepaliveStats keepaliveStats = mKeepaliveStatsPerId.valueAt(i);
            addToAggregateKeepaliveLifetime(keepaliveStats, timeNow);
        }

        // Fill keepalive carrier stats to the proto
        mAggregateKeepaliveLifetime
                .values()
                .forEach(
                        keepaliveLifetimeForCarrier ->
                                keepaliveLifetimePerCarrier.addKeepaliveLifetimeForCarrier(
                                        keepaliveLifetimeForCarrier));

        final DailykeepaliveInfoReported.Builder dailyKeepaliveInfoReported =
                DailykeepaliveInfoReported.newBuilder();

        dailyKeepaliveInfoReported.setDurationPerNumOfKeepalive(durationPerNumOfKeepalive);
        dailyKeepaliveInfoReported.setKeepaliveLifetimePerCarrier(keepaliveLifetimePerCarrier);
        dailyKeepaliveInfoReported.setKeepaliveRequests(mNumKeepaliveRequests);
        dailyKeepaliveInfoReported.setAutomaticKeepaliveRequests(mNumAutomaticKeepaliveRequests);
        dailyKeepaliveInfoReported.setDistinctUserCount(mAppUids.size());
        dailyKeepaliveInfoReported.addAllUid(mAppUids);

        return dailyKeepaliveInfoReported.build();
    }

    /**
     * Builds and resets the stored metrics. Similar to buildKeepaliveMetrics but also resets the
     * metrics while maintaining the state of the keepalives.
     *
     * @return the DailykeepaliveInfoReported proto that was built.
     */
    @VisibleForTesting
    public @NonNull DailykeepaliveInfoReported buildAndResetMetrics() {
        ensureRunningOnHandlerThread();
        final long timeNow = mDependencies.getElapsedRealtime();

        final DailykeepaliveInfoReported metrics = buildKeepaliveMetrics(timeNow);

        mDurationPerNumOfKeepalive.clear();
        mAggregateKeepaliveLifetime.clear();
        mAppUids.clear();
        mNumKeepaliveRequests = 0;
        mNumAutomaticKeepaliveRequests = 0;

        // Update the metrics with the existing keepalives.
        ensureDurationPerNumOfKeepaliveSize();

        mAggregateKeepaliveLifetime.clear();
        // Reset the stats for existing keepalives
        for (int i = 0; i < mKeepaliveStatsPerId.size(); i++) {
            final KeepaliveStats keepaliveStats = mKeepaliveStatsPerId.valueAt(i);
            keepaliveStats.resetLifetimeStats(timeNow);
            mAppUids.add(keepaliveStats.appUid);
            mNumKeepaliveRequests++;
            if (keepaliveStats.isAutoKeepalive) mNumAutomaticKeepaliveRequests++;
        }

        return metrics;
    }

    /** Writes the stored metrics to ConnectivityStatsLog and resets.  */
    public void writeAndResetMetrics() {
        ensureRunningOnHandlerThread();
        final DailykeepaliveInfoReported dailyKeepaliveInfoReported = buildAndResetMetrics();
        ConnectivityStatsLog.write(
                ConnectivityStatsLog.DAILY_KEEPALIVE_INFO_REPORTED,
                dailyKeepaliveInfoReported.getDurationPerNumOfKeepalive().toByteArray(),
                dailyKeepaliveInfoReported.getKeepaliveLifetimePerCarrier().toByteArray(),
                dailyKeepaliveInfoReported.getKeepaliveRequests(),
                dailyKeepaliveInfoReported.getAutomaticKeepaliveRequests(),
                dailyKeepaliveInfoReported.getDistinctUserCount(),
                CollectionUtils.toIntArray(dailyKeepaliveInfoReported.getUidList()));
    }

    private void ensureRunningOnHandlerThread() {
        if (mConnectivityServiceHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on handler thread: " + Thread.currentThread().getName());
        }
    }
}
