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
import android.annotation.TargetApi;
import android.net.LinkAddress;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.HexDump;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * A repository of records advertised through {@link MdnsInterfaceAdvertiser}.
 *
 * Must be used on a consistent looper thread.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU) // Allow calling T+ APIs; this is only loaded on T+
public class MdnsRecordRepository {
    // RFC6762 p.15
    private static final long MIN_MULTICAST_REPLY_INTERVAL_MS = 1_000L;

    // TTLs as per RFC6762 10.
    // TTL for records with a host name as the resource record's name (e.g., A, AAAA, HINFO) or a
    // host name contained within the resource record's rdata (e.g., SRV, reverse mapping PTR
    // record)
    private static final long NAME_RECORDS_TTL_MILLIS = TimeUnit.SECONDS.toMillis(120);
    // TTL for other records
    private static final long NON_NAME_RECORDS_TTL_MILLIS = TimeUnit.MINUTES.toMillis(75);

    // Top-level domain for link-local queries, as per RFC6762 3.
    private static final String LOCAL_TLD = "local";
    // Subtype separator as per RFC6763 7.1 (_printer._sub._http._tcp.local)
    private static final String SUBTYPE_SEPARATOR = "_sub";

    // Service type for service enumeration (RFC6763 9.)
    private static final String[] DNS_SD_SERVICE_TYPE =
            new String[] { "_services", "_dns-sd", "_udp", LOCAL_TLD };

    public static final InetSocketAddress IPV6_ADDR = new InetSocketAddress(
            MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);
    public static final InetSocketAddress IPV4_ADDR = new InetSocketAddress(
            MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);

    @NonNull
    private final Random mDelayGenerator = new Random();
    // Map of service unique ID -> records for service
    @NonNull
    private final SparseArray<ServiceRegistration> mServices = new SparseArray<>();
    @NonNull
    private final List<RecordInfo<?>> mGeneralRecords = new ArrayList<>();
    @NonNull
    private final Looper mLooper;
    @NonNull
    private final String[] mDeviceHostname;

    public MdnsRecordRepository(@NonNull Looper looper, @NonNull String[] deviceHostname) {
        this(looper, new Dependencies(), deviceHostname);
    }

    @VisibleForTesting
    public MdnsRecordRepository(@NonNull Looper looper, @NonNull Dependencies deps,
            @NonNull String[] deviceHostname) {
        mDeviceHostname = deviceHostname;
        mLooper = looper;
    }

    /**
     * Dependencies to use with {@link MdnsRecordRepository}, useful for testing.
     */
    @VisibleForTesting
    public static class Dependencies {

        /**
         * @see NetworkInterface#getInetAddresses().
         */
        @NonNull
        public Enumeration<InetAddress> getInterfaceInetAddresses(@NonNull NetworkInterface iface) {
            return iface.getInetAddresses();
        }
    }

    private static class RecordInfo<T extends MdnsRecord> {
        public final T record;
        public final NsdServiceInfo serviceInfo;

        /**
         * Whether the name of this record is expected to be fully owned by the service or may be
         * advertised by other hosts as well (shared).
         */
        public final boolean isSharedName;

        /**
         * Whether probing is still in progress for the record.
         */
        public boolean isProbing;

        /**
         * Last time (as per SystemClock.elapsedRealtime) when advertised via multicast, 0 if never
         */
        public long lastAdvertisedTimeMs;

        /**
         * Last time (as per SystemClock.elapsedRealtime) when sent via unicast or multicast,
         * 0 if never
         */
        public long lastSentTimeMs;

        RecordInfo(NsdServiceInfo serviceInfo, T record, boolean sharedName,
                 boolean probing) {
            this.serviceInfo = serviceInfo;
            this.record = record;
            this.isSharedName = sharedName;
            this.isProbing = probing;
        }
    }

    private static class ServiceRegistration {
        @NonNull
        public final List<RecordInfo<?>> allRecords;
        @NonNull
        public final List<RecordInfo<MdnsPointerRecord>> ptrRecords;
        @NonNull
        public final RecordInfo<MdnsServiceRecord> srvRecord;
        @NonNull
        public final RecordInfo<MdnsTextRecord> txtRecord;
        @NonNull
        public final NsdServiceInfo serviceInfo;
        @Nullable
        public final String subtype;

        /**
         * Whether the service is sending exit announcements and will be destroyed soon.
         */
        public boolean exiting = false;

        /**
         * Create a ServiceRegistration for dns-sd service registration (RFC6763).
         *
         * @param deviceHostname Hostname of the device (for the interface used)
         * @param serviceInfo Service to advertise
         */
        ServiceRegistration(@NonNull String[] deviceHostname, @NonNull NsdServiceInfo serviceInfo,
                @Nullable String subtype) {
            this.serviceInfo = serviceInfo;
            this.subtype = subtype;

            final String[] serviceType = splitServiceType(serviceInfo);
            final String[] serviceName = splitFullyQualifiedName(serviceInfo, serviceType);

            // Service PTR record
            final RecordInfo<MdnsPointerRecord> ptrRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            serviceType,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceName),
                    true /* sharedName */, true /* probing */);

            if (subtype == null) {
                this.ptrRecords = Collections.singletonList(ptrRecord);
            } else {
                final String[] subtypeName = new String[serviceType.length + 2];
                System.arraycopy(serviceType, 0, subtypeName, 2, serviceType.length);
                subtypeName[0] = subtype;
                subtypeName[1] = SUBTYPE_SEPARATOR;
                final RecordInfo<MdnsPointerRecord> subtypeRecord = new RecordInfo<>(
                        serviceInfo,
                        new MdnsPointerRecord(
                                subtypeName,
                                0L /* receiptTimeMillis */,
                                false /* cacheFlush */,
                                NON_NAME_RECORDS_TTL_MILLIS,
                                serviceName),
                        true /* sharedName */, true /* probing */);

                this.ptrRecords = List.of(ptrRecord, subtypeRecord);
            }

            srvRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsServiceRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS, 0 /* servicePriority */, 0 /* serviceWeight */,
                            serviceInfo.getPort(),
                            deviceHostname),
                    false /* sharedName */, true /* probing */);

            txtRecord = new RecordInfo<>(
                    serviceInfo,
                    new MdnsTextRecord(serviceName,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */, // Service name is verified unique after probing
                            NON_NAME_RECORDS_TTL_MILLIS,
                            attrsToTextEntries(serviceInfo.getAttributes())),
                    false /* sharedName */, true /* probing */);

            final ArrayList<RecordInfo<?>> allRecords = new ArrayList<>(5);
            allRecords.addAll(ptrRecords);
            allRecords.add(srvRecord);
            allRecords.add(txtRecord);
            // Service type enumeration record (RFC6763 9.)
            allRecords.add(new RecordInfo<>(
                    serviceInfo,
                    new MdnsPointerRecord(
                            DNS_SD_SERVICE_TYPE,
                            0L /* receiptTimeMillis */,
                            false /* cacheFlush */,
                            NON_NAME_RECORDS_TTL_MILLIS,
                            serviceType),
                    true /* sharedName */, true /* probing */));

            this.allRecords = Collections.unmodifiableList(allRecords);
        }

        void setProbing(boolean probing) {
            for (RecordInfo<?> info : allRecords) {
                info.isProbing = probing;
            }
        }
    }

    /**
     * Inform the repository of the latest interface addresses.
     */
    public void updateAddresses(@NonNull List<LinkAddress> newAddresses) {
        mGeneralRecords.clear();
        for (LinkAddress addr : newAddresses) {
            final String[] revDnsAddr = getReverseDnsAddress(addr.getAddress());
            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsPointerRecord(
                            revDnsAddr,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS,
                            mDeviceHostname),
                    false /* sharedName */, false /* probing */));

            mGeneralRecords.add(new RecordInfo<>(
                    null /* serviceInfo */,
                    new MdnsInetAddressRecord(
                            mDeviceHostname,
                            0L /* receiptTimeMillis */,
                            true /* cacheFlush */,
                            NAME_RECORDS_TTL_MILLIS,
                            addr.getAddress()),
                    false /* sharedName */, false /* probing */));
        }
    }

    /**
     * Add a service to the repository.
     *
     * This may remove/replace any existing service that used the name added but is exiting.
     * @param serviceId A unique service ID.
     * @param serviceInfo Service info to add.
     * @return If the added service replaced another with a matching name (which was exiting), the
     *         ID of the replaced service.
     * @throws NameConflictException There is already a (non-exiting) service using the name.
     */
    public int addService(int serviceId, NsdServiceInfo serviceInfo, @Nullable String subtype)
            throws NameConflictException {
        if (mServices.contains(serviceId)) {
            throw new IllegalArgumentException(
                    "Service ID must not be reused across registrations: " + serviceId);
        }

        final int existing = getServiceByName(serviceInfo.getServiceName());
        // It's OK to re-add a service that is exiting
        if (existing >= 0 && !mServices.get(existing).exiting) {
            throw new NameConflictException(existing);
        }

        final ServiceRegistration registration = new ServiceRegistration(
                mDeviceHostname, serviceInfo, subtype);
        mServices.put(serviceId, registration);

        // Remove existing exiting service
        mServices.remove(existing);
        return existing;
    }

    /**
     * @return The ID of the service identified by its name, or -1 if none.
     */
    private int getServiceByName(@NonNull String serviceName) {
        for (int i = 0; i < mServices.size(); i++) {
            final ServiceRegistration registration = mServices.valueAt(i);
            if (serviceName.equals(registration.serviceInfo.getServiceName())) {
                return mServices.keyAt(i);
            }
        }
        return -1;
    }

    private MdnsProber.ProbingInfo makeProbingInfo(int serviceId,
            @NonNull MdnsServiceRecord srvRecord) {
        final List<MdnsRecord> probingRecords = new ArrayList<>();
        // Probe with cacheFlush cleared; it is set when announcing, as it was verified unique:
        // RFC6762 10.2
        probingRecords.add(new MdnsServiceRecord(srvRecord.getName(),
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                srvRecord.getTtl(),
                srvRecord.getServicePriority(), srvRecord.getServiceWeight(),
                srvRecord.getServicePort(),
                srvRecord.getServiceHost()));

        return new MdnsProber.ProbingInfo(serviceId, probingRecords);
    }

    private static List<MdnsServiceInfo.TextEntry> attrsToTextEntries(Map<String, byte[]> attrs) {
        final List<MdnsServiceInfo.TextEntry> out = new ArrayList<>(attrs.size());
        for (Map.Entry<String, byte[]> attr : attrs.entrySet()) {
            out.add(new MdnsServiceInfo.TextEntry(attr.getKey(), attr.getValue()));
        }
        return out;
    }

    /**
     * Mark a service in the repository as exiting.
     * @param id ID of the service, used at registration time.
     * @return The exit announcement to indicate the service was removed, or null if not necessary.
     */
    @Nullable
    public MdnsAnnouncer.ExitAnnouncementInfo exitService(int id) {
        final ServiceRegistration registration = mServices.get(id);
        if (registration == null) return null;
        if (registration.exiting) return null;

        // Send exit (TTL 0) for the PTR records, if at least one was sent (in particular don't send
        // if still probing)
        if (CollectionUtils.all(registration.ptrRecords, r -> r.lastSentTimeMs == 0L)) {
            return null;
        }

        registration.exiting = true;
        final List<MdnsRecord> expiredRecords = CollectionUtils.map(registration.ptrRecords,
                r -> new MdnsPointerRecord(
                        r.record.getName(),
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        0L /* ttlMillis */,
                        r.record.getPointer()));

        // Exit should be skipped if the record is still advertised by another service, but that
        // would be a conflict (2 service registrations with the same service name), so it would
        // not have been allowed by the repository.
        return new MdnsAnnouncer.ExitAnnouncementInfo(id, expiredRecords);
    }

    public void removeService(int id) {
        mServices.remove(id);
    }

    /**
     * @return The number of services currently held in the repository, including exiting services.
     */
    public int getServicesCount() {
        return mServices.size();
    }

    /**
     * Remove all services from the repository
     * @return IDs of the removed services
     */
    @NonNull
    public int[] clearServices() {
        final int[] ret = new int[mServices.size()];
        for (int i = 0; i < mServices.size(); i++) {
            ret[i] = mServices.keyAt(i);
        }
        mServices.clear();
        return ret;
    }

    /**
     * Info about a reply to be sent.
     */
    public static class ReplyInfo {
        @NonNull
        public final List<MdnsRecord> answers;
        @NonNull
        public final List<MdnsRecord> additionalAnswers;
        public final long sendDelayMs;
        @NonNull
        public final InetSocketAddress destination;

        public ReplyInfo(
                @NonNull List<MdnsRecord> answers,
                @NonNull List<MdnsRecord> additionalAnswers,
                long sendDelayMs,
                @NonNull InetSocketAddress destination) {
            this.answers = answers;
            this.additionalAnswers = additionalAnswers;
            this.sendDelayMs = sendDelayMs;
            this.destination = destination;
        }

        @Override
        public String toString() {
            return "{ReplyInfo to " + destination + ", answers: " + answers.size()
                    + ", additionalAnswers: " + additionalAnswers.size()
                    + ", sendDelayMs " + sendDelayMs + "}";
        }
    }

    /**
     * Get the reply to send to an incoming packet.
     *
     * @param packet The incoming packet.
     * @param src The source address of the incoming packet.
     */
    @Nullable
    public ReplyInfo getReply(MdnsPacket packet, InetSocketAddress src) {
        final long now = SystemClock.elapsedRealtime();
        final boolean replyUnicast = (packet.flags & MdnsConstants.QCLASS_UNICAST) != 0;
        final ArrayList<MdnsRecord> additionalAnswerRecords = new ArrayList<>();
        final ArrayList<RecordInfo<?>> answerInfo = new ArrayList<>();
        for (MdnsRecord question : packet.questions) {
            // Add answers from general records
            addReplyFromService(question, mGeneralRecords, null /* servicePtrRecord */,
                    null /* serviceSrvRecord */, null /* serviceTxtRecord */, replyUnicast, now,
                    answerInfo, additionalAnswerRecords);

            // Add answers from each service
            for (int i = 0; i < mServices.size(); i++) {
                final ServiceRegistration registration = mServices.valueAt(i);
                if (registration.exiting) continue;
                addReplyFromService(question, registration.allRecords, registration.ptrRecords,
                        registration.srvRecord, registration.txtRecord, replyUnicast, now,
                        answerInfo, additionalAnswerRecords);
            }
        }

        if (answerInfo.size() == 0 && additionalAnswerRecords.size() == 0) {
            return null;
        }

        // Determine the send delay
        final long delayMs;
        if ((packet.flags & MdnsConstants.FLAG_TRUNCATED) != 0) {
            // RFC 6762 6.: 400-500ms delay if TC bit is set
            delayMs = 400L + mDelayGenerator.nextInt(100);
        } else if (packet.questions.size() > 1
                || CollectionUtils.any(answerInfo, a -> a.isSharedName)) {
            // 20-120ms if there may be responses from other hosts (not a fully owned
            // name) (RFC 6762 6.), or if there are multiple questions (6.3).
            // TODO: this should be 0 if this is a probe query ("can be distinguished from a
            // normal query by the fact that a probe query contains a proposed record in the
            // Authority Section that answers the question" in 6.), and the reply is for a fully
            // owned record.
            delayMs = 20L + mDelayGenerator.nextInt(100);
        } else {
            delayMs = 0L;
        }

        // Determine the send destination
        final InetSocketAddress dest;
        if (replyUnicast) {
            dest = src;
        } else if (src.getAddress() instanceof Inet4Address) {
            dest = IPV4_ADDR;
        } else {
            dest = IPV6_ADDR;
        }

        // Build the list of answer records from their RecordInfo
        final ArrayList<MdnsRecord> answerRecords = new ArrayList<>(answerInfo.size());
        for (RecordInfo<?> info : answerInfo) {
            // TODO: consider actual packet send delay after response aggregation
            info.lastSentTimeMs = now + delayMs;
            if (!replyUnicast) {
                info.lastAdvertisedTimeMs = info.lastSentTimeMs;
            }
            answerRecords.add(info.record);
        }

        return new ReplyInfo(answerRecords, additionalAnswerRecords, delayMs, dest);
    }

    /**
     * Add answers and additional answers for a question, from a ServiceRegistration.
     */
    private void addReplyFromService(@NonNull MdnsRecord question,
            @NonNull List<RecordInfo<?>> serviceRecords,
            @Nullable List<RecordInfo<MdnsPointerRecord>> servicePtrRecords,
            @Nullable RecordInfo<MdnsServiceRecord> serviceSrvRecord,
            @Nullable RecordInfo<MdnsTextRecord> serviceTxtRecord,
            boolean replyUnicast, long now, @NonNull List<RecordInfo<?>> answerInfo,
            @NonNull List<MdnsRecord> additionalAnswerRecords) {
        boolean hasDnsSdPtrRecordAnswer = false;
        boolean hasDnsSdSrvRecordAnswer = false;
        boolean hasFullyOwnedNameMatch = false;
        boolean hasKnownAnswer = false;

        final int answersStartIndex = answerInfo.size();
        for (RecordInfo<?> info : serviceRecords) {
            if (info.isProbing) continue;

             /* RFC6762 6.: the record name must match the question name, the record rrtype
             must match the question qtype unless the qtype is "ANY" (255) or the rrtype is
             "CNAME" (5), and the record rrclass must match the question qclass unless the
             qclass is "ANY" (255) */
            if (!Arrays.equals(info.record.getName(), question.getName())) continue;
            hasFullyOwnedNameMatch |= !info.isSharedName;

            // The repository does not store CNAME records
            if (question.getType() != MdnsRecord.TYPE_ANY
                    && question.getType() != info.record.getType()) {
                continue;
            }
            if (question.getRecordClass() != MdnsRecord.CLASS_ANY
                    && question.getRecordClass() != info.record.getRecordClass()) {
                continue;
            }

            hasKnownAnswer = true;
            hasDnsSdPtrRecordAnswer |= (servicePtrRecords != null
                    && CollectionUtils.any(servicePtrRecords, r -> info == r));
            hasDnsSdSrvRecordAnswer |= (info == serviceSrvRecord);

            // TODO: responses to probe queries should bypass this check and only ensure the
            // reply is sent 250ms after the last sent time (RFC 6762 p.15)
            if (!replyUnicast && info.lastAdvertisedTimeMs > 0L
                    && now - info.lastAdvertisedTimeMs < MIN_MULTICAST_REPLY_INTERVAL_MS) {
                continue;
            }

            // TODO: Don't reply if in known answers of the querier (7.1) if TTL is > half

            answerInfo.add(info);
        }

        // RFC6762 6.1:
        // "Any time a responder receives a query for a name for which it has verified exclusive
        // ownership, for a type for which that name has no records, the responder MUST [...]
        // respond asserting the nonexistence of that record"
        if (hasFullyOwnedNameMatch && !hasKnownAnswer) {
            additionalAnswerRecords.add(new MdnsNsecRecord(
                    question.getName(),
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    // TODO: RFC6762 6.1: "In general, the TTL given for an NSEC record SHOULD
                    // be the same as the TTL that the record would have had, had it existed."
                    NAME_RECORDS_TTL_MILLIS,
                    question.getName(),
                    new int[] { question.getType() }));
        }

        // No more records to add if no answer
        if (answerInfo.size() == answersStartIndex) return;

        final List<RecordInfo<?>> additionalAnswerInfo = new ArrayList<>();
        // RFC6763 12.1: if including PTR record, include the SRV and TXT records it names
        if (hasDnsSdPtrRecordAnswer) {
            if (serviceTxtRecord != null) {
                additionalAnswerInfo.add(serviceTxtRecord);
            }
            if (serviceSrvRecord != null) {
                additionalAnswerInfo.add(serviceSrvRecord);
            }
        }

        // RFC6763 12.1&.2: if including PTR or SRV record, include the address records it names
        if (hasDnsSdPtrRecordAnswer || hasDnsSdSrvRecordAnswer) {
            for (RecordInfo<?> record : mGeneralRecords) {
                if (record.record instanceof MdnsInetAddressRecord) {
                    additionalAnswerInfo.add(record);
                }
            }
        }

        for (RecordInfo<?> info : additionalAnswerInfo) {
            additionalAnswerRecords.add(info.record);
        }

        // RFC6762 6.1: negative responses
        addNsecRecordsForUniqueNames(additionalAnswerRecords,
                answerInfo.listIterator(answersStartIndex),
                additionalAnswerInfo.listIterator());
    }

    /**
     * Add NSEC records indicating that the response records are unique.
     *
     * Following RFC6762 6.1:
     * "On receipt of a question for a particular name, rrtype, and rrclass, for which a responder
     * does have one or more unique answers, the responder MAY also include an NSEC record in the
     * Additional Record Section indicating the nonexistence of other rrtypes for that name and
     * rrclass."
     * @param destinationList List to add the NSEC records to.
     * @param answerRecords Lists of answered records based on which to add NSEC records (typically
     *                      answer and additionalAnswer sections)
     */
    @SafeVarargs
    private static void addNsecRecordsForUniqueNames(
            List<MdnsRecord> destinationList,
            Iterator<RecordInfo<?>>... answerRecords) {
        // Group unique records by name. Use a TreeMap with comparator as arrays don't implement
        // equals / hashCode.
        final Map<String[], List<MdnsRecord>> nsecByName = new TreeMap<>(Arrays::compare);
        // But keep the list of names in added order, otherwise records would be sorted in
        // alphabetical order instead of the order of the original records, which would look like
        // inconsistent behavior depending on service name.
        final List<String[]> namesInAddedOrder = new ArrayList<>();
        for (Iterator<RecordInfo<?>> answers : answerRecords) {
            addNonSharedRecordsToMap(answers, nsecByName, namesInAddedOrder);
        }

        for (String[] nsecName : namesInAddedOrder) {
            final List<MdnsRecord> entryRecords = nsecByName.get(nsecName);
            long minTtl = Long.MAX_VALUE;
            final Set<Integer> types = new ArraySet<>(entryRecords.size());
            for (MdnsRecord record : entryRecords) {
                if (minTtl > record.getTtl()) minTtl = record.getTtl();
                types.add(record.getType());
            }

            destinationList.add(new MdnsNsecRecord(
                    nsecName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    minTtl,
                    nsecName,
                    CollectionUtils.toIntArray(types)));
        }
    }

    /**
     * Add non-shared records to a map listing them by record name, and to a list of names that
     * remembers the adding order.
     *
     * In the destination map records are grouped by name; so the map has one key per record name,
     * and the values are the lists of different records that share the same name.
     * @param records Records to scan.
     * @param dest Map to add the records to.
     * @param namesInAddedOrder List of names to add the names in order, keeping the first
     *                          occurrence of each name.
     */
    private static void addNonSharedRecordsToMap(
            Iterator<RecordInfo<?>> records,
            Map<String[], List<MdnsRecord>> dest,
            List<String[]> namesInAddedOrder) {
        while (records.hasNext()) {
            final RecordInfo<?> record = records.next();
            if (record.isSharedName) continue;
            final List<MdnsRecord> recordsForName = dest.computeIfAbsent(record.record.name,
                    key -> {
                        namesInAddedOrder.add(key);
                        return new ArrayList<>();
                    });
            recordsForName.add(record.record);
        }
    }

    /**
     * Called to indicate that probing succeeded for a service.
     * @param probeSuccessInfo The successful probing info.
     * @return The {@link MdnsAnnouncer.AnnouncementInfo} to send, now that probing has succeeded.
     */
    public MdnsAnnouncer.AnnouncementInfo onProbingSucceeded(
            MdnsProber.ProbingInfo probeSuccessInfo)
            throws IOException {

        final ServiceRegistration registration = mServices.get(probeSuccessInfo.getServiceId());
        if (registration == null) throw new IOException(
                "Service is not registered: " + probeSuccessInfo.getServiceId());
        registration.setProbing(false);

        final ArrayList<MdnsRecord> answers = new ArrayList<>();
        final ArrayList<MdnsRecord> additionalAnswers = new ArrayList<>();

        // Interface address records in general records
        for (RecordInfo<?> record : mGeneralRecords) {
            answers.add(record.record);
        }

        // All service records
        for (RecordInfo<?> info : registration.allRecords) {
            answers.add(info.record);
        }

        addNsecRecordsForUniqueNames(additionalAnswers,
                mGeneralRecords.iterator(), registration.allRecords.iterator());

        return new MdnsAnnouncer.AnnouncementInfo(probeSuccessInfo.getServiceId(),
                answers, additionalAnswers);
    }

    /**
     * Get the service IDs of services conflicting with a received packet.
     */
    public Set<Integer> getConflictingServices(MdnsPacket packet) {
        // Avoid allocating a new set for each incoming packet: use an empty set by default.
        Set<Integer> conflicting = Collections.emptySet();
        for (MdnsRecord record : packet.answers) {
            for (int i = 0; i < mServices.size(); i++) {
                final ServiceRegistration registration = mServices.valueAt(i);
                if (registration.exiting) continue;

                // Only look for conflicts in service name, as a different service name can be used
                // if there is a conflict, but there is nothing actionable if any other conflict
                // happens. In fact probing is only done for the service name in the SRV record.
                // This means only SRV and TXT records need to be checked.
                final RecordInfo<MdnsServiceRecord> srvRecord = registration.srvRecord;
                if (!Arrays.equals(record.getName(), srvRecord.record.getName())) continue;

                // As per RFC6762 9., it's fine if the "conflict" is an identical record with same
                // data.
                if (record instanceof MdnsServiceRecord) {
                    final MdnsServiceRecord local = srvRecord.record;
                    final MdnsServiceRecord other = (MdnsServiceRecord) record;
                    // Note "equals" does not consider TTL or receipt time, as intended here
                    if (Objects.equals(local, other)) {
                        continue;
                    }
                }

                if (record instanceof MdnsTextRecord) {
                    final MdnsTextRecord local = registration.txtRecord.record;
                    final MdnsTextRecord other = (MdnsTextRecord) record;
                    if (Objects.equals(local, other)) {
                        continue;
                    }
                }

                if (conflicting.size() == 0) {
                    // Conflict was found: use a mutable set
                    conflicting = new ArraySet<>();
                }
                final int serviceId = mServices.keyAt(i);
                conflicting.add(serviceId);
            }
        }

        return conflicting;
    }

    /**
     * (Re)set a service to the probing state.
     * @return The {@link MdnsProber.ProbingInfo} to send for probing.
     */
    @Nullable
    public MdnsProber.ProbingInfo setServiceProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return null;

        registration.setProbing(true);
        return makeProbingInfo(serviceId, registration.srvRecord.record);
    }

    /**
     * Indicates whether a given service is in probing state.
     */
    public boolean isProbing(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return false;

        return registration.srvRecord.isProbing;
    }

    /**
     * Return whether the repository has an active (non-exiting) service for the given ID.
     */
    public boolean hasActiveService(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return false;

        return !registration.exiting;
    }

    /**
     * Rename a service to the newly provided info, following a conflict.
     *
     * If the specified service does not exist, this returns null.
     */
    @Nullable
    public MdnsProber.ProbingInfo renameServiceForConflict(int serviceId, NsdServiceInfo newInfo) {
        final ServiceRegistration existing = mServices.get(serviceId);
        if (existing == null) return null;

        final ServiceRegistration newService = new ServiceRegistration(
                mDeviceHostname, newInfo, existing.subtype);
        mServices.put(serviceId, newService);
        return makeProbingInfo(serviceId, newService.srvRecord.record);
    }

    /**
     * Called when {@link MdnsAdvertiser} sent an advertisement for the given service.
     */
    public void onAdvertisementSent(int serviceId) {
        final ServiceRegistration registration = mServices.get(serviceId);
        if (registration == null) return;

        final long now = SystemClock.elapsedRealtime();
        for (RecordInfo<?> record : registration.allRecords) {
            record.lastSentTimeMs = now;
            record.lastAdvertisedTimeMs = now;
        }
    }

    /**
     * Compute:
     * 2001:db8::1 --> 1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.0.1.0.0.2.ip6.arpa
     *
     * Or:
     * 192.0.2.123 --> 123.2.0.192.in-addr.arpa
     */
    @VisibleForTesting
    public static String[] getReverseDnsAddress(@NonNull InetAddress addr) {
        // xxx.xxx.xxx.xxx.in-addr.arpa (up to 28 characters)
        // or 32 hex characters separated by dots + .ip6.arpa
        final byte[] addrBytes = addr.getAddress();
        final List<String> out = new ArrayList<>();
        if (addr instanceof Inet4Address) {
            for (int i = addrBytes.length - 1; i >= 0; i--) {
                out.add(String.valueOf(Byte.toUnsignedInt(addrBytes[i])));
            }
            out.add("in-addr");
        } else {
            final String hexAddr = HexDump.toHexString(addrBytes);

            for (int i = hexAddr.length() - 1; i >= 0; i--) {
                out.add(String.valueOf(hexAddr.charAt(i)));
            }
            out.add("ip6");
        }
        out.add("arpa");

        return out.toArray(new String[0]);
    }

    private static String[] splitFullyQualifiedName(
            @NonNull NsdServiceInfo info, @NonNull String[] serviceType) {
        final String[] split = new String[serviceType.length + 1];
        split[0] = info.getServiceName();
        System.arraycopy(serviceType, 0, split, 1, serviceType.length);

        return split;
    }

    private static String[] splitServiceType(@NonNull NsdServiceInfo info) {
        // String.split(pattern, 0) removes trailing empty strings, which would appear when
        // splitting "domain.name." (with a dot a the end), so this is what is needed here.
        final String[] split = info.getServiceType().split("\\.", 0);
        final String[] type = new String[split.length + 1];
        System.arraycopy(split, 0, type, 0, split.length);
        type[split.length] = LOCAL_TLD;

        return type;
    }
}
