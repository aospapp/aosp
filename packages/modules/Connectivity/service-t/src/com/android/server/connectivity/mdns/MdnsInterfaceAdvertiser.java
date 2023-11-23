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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkAddress;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.HexDump;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsAnnouncer.BaseAnnouncementInfo;
import com.android.server.connectivity.mdns.MdnsPacketRepeater.PacketRepeaterCallback;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * A class that handles advertising services on a {@link MdnsInterfaceSocket} tied to an interface.
 */
public class MdnsInterfaceAdvertiser implements MulticastPacketReader.PacketHandler {
    private static final boolean DBG = MdnsAdvertiser.DBG;
    @VisibleForTesting
    public static final long EXIT_ANNOUNCEMENT_DELAY_MS = 100L;
    @NonNull
    private final String mTag;
    @NonNull
    private final ProbingCallback mProbingCallback = new ProbingCallback();
    @NonNull
    private final AnnouncingCallback mAnnouncingCallback = new AnnouncingCallback();
    @NonNull
    private final MdnsRecordRepository mRecordRepository;
    @NonNull
    private final Callback mCb;
    // Callbacks are on the same looper thread, but posted to the next handler loop
    @NonNull
    private final Handler mCbHandler;
    @NonNull
    private final MdnsInterfaceSocket mSocket;
    @NonNull
    private final MdnsAnnouncer mAnnouncer;
    @NonNull
    private final MdnsProber mProber;
    @NonNull
    private final MdnsReplySender mReplySender;

    @NonNull
    private final SharedLog mSharedLog;

    /**
     * Callbacks called by {@link MdnsInterfaceAdvertiser} to report status updates.
     */
    interface Callback {
        /**
         * Called by the advertiser after it successfully registered a service, after probing.
         */
        void onRegisterServiceSucceeded(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);

        /**
         * Called by the advertiser when a conflict was found, during or after probing.
         *
         * If a conflict is found during probing, the {@link #renameServiceForConflict} must be
         * called to restart probing and attempt registration with a different name.
         */
        void onServiceConflict(@NonNull MdnsInterfaceAdvertiser advertiser, int serviceId);

        /**
         * Called by the advertiser when it destroyed itself.
         *
         * This can happen after a call to {@link #destroyNow()}, or after all services were
         * unregistered and the advertiser finished sending exit announcements.
         */
        void onDestroyed(@NonNull MdnsInterfaceSocket socket);
    }

    /**
     * Callbacks from {@link MdnsProber}.
     */
    private class ProbingCallback implements
            PacketRepeaterCallback<MdnsProber.ProbingInfo> {
        @Override
        public void onFinished(MdnsProber.ProbingInfo info) {
            final MdnsAnnouncer.AnnouncementInfo announcementInfo;
            mSharedLog.i("Probing finished for service " + info.getServiceId());
            mCbHandler.post(() -> mCb.onRegisterServiceSucceeded(
                    MdnsInterfaceAdvertiser.this, info.getServiceId()));
            try {
                announcementInfo = mRecordRepository.onProbingSucceeded(info);
            } catch (IOException e) {
                mSharedLog.e("Error building announcements", e);
                return;
            }

            mAnnouncer.startSending(info.getServiceId(), announcementInfo,
                    0L /* initialDelayMs */);
        }
    }

    /**
     * Callbacks from {@link MdnsAnnouncer}.
     */
    private class AnnouncingCallback implements PacketRepeaterCallback<BaseAnnouncementInfo> {
        @Override
        public void onSent(int index, @NonNull BaseAnnouncementInfo info) {
            mRecordRepository.onAdvertisementSent(info.getServiceId());
        }

        @Override
        public void onFinished(@NonNull BaseAnnouncementInfo info) {
            if (info instanceof MdnsAnnouncer.ExitAnnouncementInfo) {
                mRecordRepository.removeService(info.getServiceId());

                if (mRecordRepository.getServicesCount() == 0) {
                    destroyNow();
                }
            }
        }
    }

    /**
     * Dependencies for {@link MdnsInterfaceAdvertiser}, useful for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        /** @see MdnsRecordRepository */
        @NonNull
        public MdnsRecordRepository makeRecordRepository(@NonNull Looper looper,
                @NonNull String[] deviceHostName) {
            return new MdnsRecordRepository(looper, deviceHostName);
        }

        /** @see MdnsReplySender */
        @NonNull
        public MdnsReplySender makeReplySender(@NonNull String interfaceTag, @NonNull Looper looper,
                @NonNull MdnsInterfaceSocket socket, @NonNull byte[] packetCreationBuffer) {
            return new MdnsReplySender(interfaceTag, looper, socket, packetCreationBuffer);
        }

        /** @see MdnsAnnouncer */
        public MdnsAnnouncer makeMdnsAnnouncer(@NonNull String interfaceTag, @NonNull Looper looper,
                @NonNull MdnsReplySender replySender,
                @Nullable PacketRepeaterCallback<MdnsAnnouncer.BaseAnnouncementInfo> cb) {
            return new MdnsAnnouncer(interfaceTag, looper, replySender, cb);
        }

        /** @see MdnsProber */
        public MdnsProber makeMdnsProber(@NonNull String interfaceTag, @NonNull Looper looper,
                @NonNull MdnsReplySender replySender,
                @NonNull PacketRepeaterCallback<MdnsProber.ProbingInfo> cb) {
            return new MdnsProber(interfaceTag, looper, replySender, cb);
        }
    }

    public MdnsInterfaceAdvertiser(@NonNull MdnsInterfaceSocket socket,
            @NonNull List<LinkAddress> initialAddresses, @NonNull Looper looper,
            @NonNull byte[] packetCreationBuffer, @NonNull Callback cb,
            @NonNull String[] deviceHostName, @NonNull SharedLog sharedLog) {
        this(socket, initialAddresses, looper, packetCreationBuffer, cb,
                new Dependencies(), deviceHostName, sharedLog);
    }

    public MdnsInterfaceAdvertiser(@NonNull MdnsInterfaceSocket socket,
            @NonNull List<LinkAddress> initialAddresses, @NonNull Looper looper,
            @NonNull byte[] packetCreationBuffer, @NonNull Callback cb, @NonNull Dependencies deps,
            @NonNull String[] deviceHostName, @NonNull SharedLog sharedLog) {
        mTag = MdnsInterfaceAdvertiser.class.getSimpleName() + "/" + sharedLog.getTag();
        mRecordRepository = deps.makeRecordRepository(looper, deviceHostName);
        mRecordRepository.updateAddresses(initialAddresses);
        mSocket = socket;
        mCb = cb;
        mCbHandler = new Handler(looper);
        mReplySender = deps.makeReplySender(sharedLog.getTag(), looper, socket,
                packetCreationBuffer);
        mAnnouncer = deps.makeMdnsAnnouncer(sharedLog.getTag(), looper, mReplySender,
                mAnnouncingCallback);
        mProber = deps.makeMdnsProber(sharedLog.getTag(), looper, mReplySender, mProbingCallback);
        mSharedLog = sharedLog;
    }

    /**
     * Start the advertiser.
     *
     * The advertiser will stop itself when all services are removed and exit announcements sent,
     * notifying via {@link Callback#onDestroyed}. This can also be triggered manually via
     * {@link #destroyNow()}.
     */
    public void start() {
        mSocket.addPacketHandler(this);
    }

    /**
     * Start advertising a service.
     *
     * @throws NameConflictException There is already a service being advertised with that name.
     */
    public void addService(int id, NsdServiceInfo service, @Nullable String subtype)
            throws NameConflictException {
        final int replacedExitingService = mRecordRepository.addService(id, service, subtype);
        // Cancel announcements for the existing service. This only happens for exiting services
        // (so cancelling exiting announcements), as per RecordRepository.addService.
        if (replacedExitingService >= 0) {
            mSharedLog.i("Service " + replacedExitingService
                    + " getting re-added, cancelling exit announcements");
            mAnnouncer.stop(replacedExitingService);
        }
        mProber.startProbing(mRecordRepository.setServiceProbing(id));
    }

    /**
     * Stop advertising a service.
     *
     * This will trigger exit announcements for the service.
     */
    public void removeService(int id) {
        if (!mRecordRepository.hasActiveService(id)) return;
        mProber.stop(id);
        mAnnouncer.stop(id);
        final MdnsAnnouncer.ExitAnnouncementInfo exitInfo = mRecordRepository.exitService(id);
        if (exitInfo != null) {
            // This effectively schedules destroyNow(), as it is to be called when the exit
            // announcement finishes if there is no service left.
            // A non-zero exit announcement delay follows legacy mdnsresponder behavior, and is
            // also useful to ensure that when a host receives the exit announcement, the service
            // has been unregistered on all interfaces; so an announcement sent from interface A
            // that was already in-flight while unregistering won't be received after the exit on
            // interface B.
            mAnnouncer.startSending(id, exitInfo, EXIT_ANNOUNCEMENT_DELAY_MS);
        } else {
            // No exit announcement necessary: remove the service immediately.
            mRecordRepository.removeService(id);
            if (mRecordRepository.getServicesCount() == 0) {
                destroyNow();
            }
        }
    }

    /**
     * Update interface addresses used to advertise.
     *
     * This causes new address records to be announced.
     */
    public void updateAddresses(@NonNull List<LinkAddress> newAddresses) {
        mRecordRepository.updateAddresses(newAddresses);
        // TODO: restart advertising, but figure out what exit messages need to be sent for the
        // previous addresses
    }

    /**
     * Destroy the advertiser immediately, not sending any exit announcement.
     *
     * <p>Useful when the underlying network went away. This will trigger an onDestroyed callback.
     */
    public void destroyNow() {
        for (int serviceId : mRecordRepository.clearServices()) {
            mProber.stop(serviceId);
            mAnnouncer.stop(serviceId);
        }
        mReplySender.cancelAll();
        mSocket.removePacketHandler(this);
        mCbHandler.post(() -> mCb.onDestroyed(mSocket));
    }

    /**
     * Reset a service to the probing state due to a conflict found on the network.
     */
    public void restartProbingForConflict(int serviceId) {
        final MdnsProber.ProbingInfo probingInfo = mRecordRepository.setServiceProbing(serviceId);
        if (probingInfo == null) return;

        mProber.restartForConflict(probingInfo);
    }

    /**
     * Rename a service following a conflict found on the network, and restart probing.
     *
     * If the service was not registered on this {@link MdnsInterfaceAdvertiser}, this is a no-op.
     */
    public void renameServiceForConflict(int serviceId, NsdServiceInfo newInfo) {
        final MdnsProber.ProbingInfo probingInfo = mRecordRepository.renameServiceForConflict(
                serviceId, newInfo);
        if (probingInfo == null) return;

        mProber.restartForConflict(probingInfo);
    }

    /**
     * Indicates whether probing is in progress for the given service on this interface.
     *
     * Also returns false if the specified service is not registered.
     */
    public boolean isProbing(int serviceId) {
        return mRecordRepository.isProbing(serviceId);
    }

    @Override
    public void handlePacket(byte[] recvbuf, int length, InetSocketAddress src) {
        final MdnsPacket packet;
        try {
            packet = MdnsPacket.parse(new MdnsPacketReader(recvbuf, length));
        } catch (MdnsPacket.ParseException e) {
            Log.e(mTag, "Error parsing mDNS packet", e);
            if (DBG) {
                Log.v(
                        mTag, "Packet: " + HexDump.toHexString(recvbuf, 0, length));
            }
            return;
        }

        if (DBG) {
            Log.v(mTag,
                    "Parsed packet with " + packet.questions.size() + " questions, "
                            + packet.answers.size() + " answers, "
                            + packet.authorityRecords.size() + " authority, "
                            + packet.additionalRecords.size() + " additional from " + src);
        }

        for (int conflictServiceId : mRecordRepository.getConflictingServices(packet)) {
            mCbHandler.post(() -> mCb.onServiceConflict(this, conflictServiceId));
        }

        // Even in case of conflict, add replies for other services. But in general conflicts would
        // happen when the incoming packet has answer records (not a question), so there will be no
        // answer. One exception is simultaneous probe tiebreaking (rfc6762 8.2), in which case the
        // conflicting service is still probing and won't reply either.
        final MdnsRecordRepository.ReplyInfo answers = mRecordRepository.getReply(packet, src);

        if (answers == null) return;
        mReplySender.queueReply(answers);
    }
}
