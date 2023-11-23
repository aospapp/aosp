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

package com.android.server.connectivity.mdns;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.connectivity.mdns.util.MdnsLogger;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A {@link Callable} that builds and enqueues a mDNS query to send over the multicast socket. If a
 * query is built and enqueued successfully, then call to {@link #call()} returns the transaction ID
 * and the list of the subtypes in the query as a {@link Pair}. If a query is failed to build, or if
 * it can not be enqueued, then call to {@link #call()} returns {@code null}.
 */
public class EnqueueMdnsQueryCallable implements Callable<Pair<Integer, List<String>>> {

    private static final String TAG = "MdnsQueryCallable";
    private static final MdnsLogger LOGGER = new MdnsLogger(TAG);
    private static final List<Integer> castShellEmulatorMdnsPorts;

    static {
        castShellEmulatorMdnsPorts = new ArrayList<>();
        String[] stringPorts = MdnsConfigs.castShellEmulatorMdnsPorts();

        for (String port : stringPorts) {
            try {
                castShellEmulatorMdnsPorts.add(Integer.parseInt(port));
            } catch (NumberFormatException e) {
                // Ignore.
            }
        }
    }

    @NonNull
    private final WeakReference<MdnsSocketClientBase> weakRequestSender;
    @NonNull
    private final MdnsPacketWriter packetWriter;
    @NonNull
    private final String[] serviceTypeLabels;
    @NonNull
    private final List<String> subtypes;
    private final boolean expectUnicastResponse;
    private final int transactionId;
    @Nullable
    private final Network network;
    private final boolean sendDiscoveryQueries;
    @NonNull
    private final List<MdnsResponse> servicesToResolve;
    @NonNull
    private final MdnsResponseDecoder.Clock clock;

    EnqueueMdnsQueryCallable(
            @NonNull MdnsSocketClientBase requestSender,
            @NonNull MdnsPacketWriter packetWriter,
            @NonNull String serviceType,
            @NonNull Collection<String> subtypes,
            boolean expectUnicastResponse,
            int transactionId,
            @Nullable Network network,
            boolean sendDiscoveryQueries,
            @NonNull Collection<MdnsResponse> servicesToResolve,
            @NonNull MdnsResponseDecoder.Clock clock) {
        weakRequestSender = new WeakReference<>(requestSender);
        this.packetWriter = packetWriter;
        serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.subtypes = new ArrayList<>(subtypes);
        this.expectUnicastResponse = expectUnicastResponse;
        this.transactionId = transactionId;
        this.network = network;
        this.sendDiscoveryQueries = sendDiscoveryQueries;
        this.servicesToResolve = new ArrayList<>(servicesToResolve);
        this.clock = clock;
    }

    // Incompatible return type for override of Callable#call().
    @SuppressWarnings("nullness:override.return.invalid")
    @Override
    @Nullable
    public Pair<Integer, List<String>> call() {
        try {
            MdnsSocketClientBase requestSender = weakRequestSender.get();
            if (requestSender == null) {
                return null;
            }

            int numQuestions = 0;

            if (sendDiscoveryQueries) {
                numQuestions++; // Base service type
                if (!subtypes.isEmpty()) {
                    numQuestions += subtypes.size();
                }
            }

            // List of (name, type) to query
            final ArrayList<Pair<String[], Integer>> missingKnownAnswerRecords = new ArrayList<>();
            final long now = clock.elapsedRealtime();
            for (MdnsResponse response : servicesToResolve) {
                final String[] serviceName = response.getServiceName();
                if (serviceName == null) continue;
                if (!response.hasTextRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getTextRecord(), now)) {
                    missingKnownAnswerRecords.add(new Pair<>(serviceName, MdnsRecord.TYPE_TXT));
                }
                if (!response.hasServiceRecord() || MdnsUtils.isRecordRenewalNeeded(
                        response.getServiceRecord(), now)) {
                    missingKnownAnswerRecords.add(new Pair<>(serviceName, MdnsRecord.TYPE_SRV));
                    // The hostname is not yet known, so queries for address records will be sent
                    // the next time the EnqueueMdnsQueryCallable is enqueued if the reply does not
                    // contain them. In practice, advertisers should include the address records
                    // when queried for SRV, although it's not a MUST requirement (RFC6763 12.2).
                    // TODO: Figure out how to renew the A/AAAA record. Usually A/AAAA record will
                    //  be included in the response to the SRV record so in high chances there is
                    //  no need to renew them individually.
                } else if (!response.hasInet4AddressRecord() && !response.hasInet6AddressRecord()) {
                    final String[] host = response.getServiceRecord().getServiceHost();
                    missingKnownAnswerRecords.add(new Pair<>(host, MdnsRecord.TYPE_A));
                    missingKnownAnswerRecords.add(new Pair<>(host, MdnsRecord.TYPE_AAAA));
                }
            }
            numQuestions += missingKnownAnswerRecords.size();

            if (numQuestions == 0) {
                // No query to send
                return null;
            }

            // Header.
            packetWriter.writeUInt16(transactionId); // transaction ID
            packetWriter.writeUInt16(MdnsConstants.FLAGS_QUERY); // flags
            packetWriter.writeUInt16(numQuestions); // number of questions
            packetWriter.writeUInt16(0); // number of answers (not yet known; will be written later)
            packetWriter.writeUInt16(0); // number of authority entries
            packetWriter.writeUInt16(0); // number of additional records

            // Question(s) for missing records on known answers
            for (Pair<String[], Integer> question : missingKnownAnswerRecords) {
                writeQuestion(question.first, question.second);
            }

            // Question(s) for discovering other services with the type. There will be one question
            // for each (fqdn+subtype, recordType) combination, as well as one for each (fqdn,
            // recordType) combination.
            if (sendDiscoveryQueries) {
                for (String subtype : subtypes) {
                    String[] labels = new String[serviceTypeLabels.length + 2];
                    labels[0] = MdnsConstants.SUBTYPE_PREFIX + subtype;
                    labels[1] = MdnsConstants.SUBTYPE_LABEL;
                    System.arraycopy(serviceTypeLabels, 0, labels, 2, serviceTypeLabels.length);

                    writeQuestion(labels, MdnsRecord.TYPE_PTR);
                }
                writeQuestion(serviceTypeLabels, MdnsRecord.TYPE_PTR);
            }

            if (requestSender instanceof MdnsMultinetworkSocketClient) {
                sendPacketToIpv4AndIpv6(requestSender, MdnsConstants.MDNS_PORT, network);
                for (Integer emulatorPort : castShellEmulatorMdnsPorts) {
                    sendPacketToIpv4AndIpv6(requestSender, emulatorPort, network);
                }
            } else if (requestSender instanceof MdnsSocketClient) {
                final MdnsSocketClient client = (MdnsSocketClient) requestSender;
                InetAddress mdnsAddress = MdnsConstants.getMdnsIPv4Address();
                if (client.isOnIPv6OnlyNetwork()) {
                    mdnsAddress = MdnsConstants.getMdnsIPv6Address();
                }

                sendPacketTo(client, new InetSocketAddress(mdnsAddress, MdnsConstants.MDNS_PORT));
                for (Integer emulatorPort : castShellEmulatorMdnsPorts) {
                    sendPacketTo(client, new InetSocketAddress(mdnsAddress, emulatorPort));
                }
            } else {
                throw new IOException("Unknown socket client type: " + requestSender.getClass());
            }
            return Pair.create(transactionId, subtypes);
        } catch (IOException e) {
            LOGGER.e(String.format("Failed to create mDNS packet for subtype: %s.",
                    TextUtils.join(",", subtypes)), e);
            return null;
        }
    }

    private void writeQuestion(String[] labels, int type) throws IOException {
        packetWriter.writeLabels(labels);
        packetWriter.writeUInt16(type);
        packetWriter.writeUInt16(
                MdnsConstants.QCLASS_INTERNET
                        | (expectUnicastResponse ? MdnsConstants.QCLASS_UNICAST : 0));
    }

    private void sendPacketTo(MdnsSocketClient requestSender, InetSocketAddress address)
            throws IOException {
        DatagramPacket packet = packetWriter.getPacket(address);
        if (expectUnicastResponse) {
            requestSender.sendUnicastPacket(packet);
        } else {
            requestSender.sendMulticastPacket(packet);
        }
    }

    private void sendPacketFromNetwork(MdnsSocketClientBase requestSender,
            InetSocketAddress address, Network network)
            throws IOException {
        DatagramPacket packet = packetWriter.getPacket(address);
        if (expectUnicastResponse) {
            requestSender.sendUnicastPacket(packet, network);
        } else {
            requestSender.sendMulticastPacket(packet, network);
        }
    }

    private void sendPacketToIpv4AndIpv6(MdnsSocketClientBase requestSender, int port,
            Network network) {
        try {
            sendPacketFromNetwork(requestSender,
                    new InetSocketAddress(MdnsConstants.getMdnsIPv4Address(), port), network);
        } catch (IOException e) {
            Log.i(TAG, "Can't send packet to IPv4", e);
        }
        try {
            sendPacketFromNetwork(requestSender,
                    new InetSocketAddress(MdnsConstants.getMdnsIPv6Address(), port), network);
        } catch (IOException e) {
            Log.i(TAG, "Can't send packet to IPv6", e);
        }
    }
}