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

package android.net;

import static android.net.InetAddresses.parseNumericAddress;
import static android.system.OsConstants.IPPROTO_ICMP;
import static android.system.OsConstants.IPPROTO_ICMPV6;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.net.module.util.DnsPacket.ANSECTION;
import static com.android.net.module.util.DnsPacket.ARSECTION;
import static com.android.net.module.util.DnsPacket.NSSECTION;
import static com.android.net.module.util.DnsPacket.QDSECTION;
import static com.android.net.module.util.HexDump.dumpHexString;
import static com.android.net.module.util.NetworkStackConstants.ARP_REPLY;
import static com.android.net.module.util.NetworkStackConstants.ARP_REQUEST;
import static com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN;
import static com.android.net.module.util.NetworkStackConstants.ETHER_BROADCAST;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4;
import static com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_PIO;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_SLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ND_OPTION_TLLA;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_NEIGHBOR_SOLICITATION;
import static com.android.net.module.util.NetworkStackConstants.ICMPV6_ROUTER_ADVERTISEMENT;
import static com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_ALL_NODES_MULTICAST;
import static com.android.net.module.util.NetworkStackConstants.NEIGHBOR_ADVERTISEMENT_FLAG_OVERRIDE;
import static com.android.net.module.util.NetworkStackConstants.NEIGHBOR_ADVERTISEMENT_FLAG_SOLICITED;
import static com.android.net.module.util.NetworkStackConstants.TCPHDR_SYN;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.net.module.util.DnsPacket;
import com.android.net.module.util.Ipv6Utils;
import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.EthernetHeader;
import com.android.net.module.util.structs.Icmpv4Header;
import com.android.net.module.util.structs.Icmpv6Header;
import com.android.net.module.util.structs.Ipv4Header;
import com.android.net.module.util.structs.Ipv6Header;
import com.android.net.module.util.structs.LlaOption;
import com.android.net.module.util.structs.NsHeader;
import com.android.net.module.util.structs.PrefixInformationOption;
import com.android.net.module.util.structs.RaHeader;
import com.android.net.module.util.structs.TcpHeader;
import com.android.net.module.util.structs.UdpHeader;
import com.android.networkstack.arp.ArpPacket;
import com.android.testutils.TapPacketReader;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * A class simulate tethered client. When caller create TetheringTester, it would connect to
 * tethering module that do the dhcp and slaac to obtain ipv4 and ipv6 address. Then caller can
 * send/receive packets by this class.
 */
public final class TetheringTester {
    private static final String TAG = TetheringTester.class.getSimpleName();
    private static final int PACKET_READ_TIMEOUT_MS = 500;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final int READ_RA_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final InetAddress LINK_LOCAL = parseNumericAddress("fe80::1");

    public static final String DHCP_HOSTNAME = "testhostname";

    private final ArrayMap<MacAddress, TetheredDevice> mTetheredDevices;
    private final TapPacketReader mDownstreamReader;
    private final TapPacketReader mUpstreamReader;

    public TetheringTester(TapPacketReader downstream) {
        this(downstream, null);
    }

    public TetheringTester(TapPacketReader downstream, TapPacketReader upstream) {
        if (downstream == null) fail("Downstream reader could not be NULL");

        mDownstreamReader = downstream;
        mUpstreamReader = upstream;
        mTetheredDevices = new ArrayMap<>();
    }

    public TetheredDevice createTetheredDevice(MacAddress macAddr, boolean hasIpv6)
            throws Exception {
        if (mTetheredDevices.get(macAddr) != null) {
            fail("Tethered device already created");
        }

        TetheredDevice tethered = new TetheredDevice(macAddr, hasIpv6);
        mTetheredDevices.put(macAddr, tethered);

        return tethered;
    }

    public class TetheredDevice {
        public final MacAddress macAddr;
        public final MacAddress routerMacAddr;
        public final Inet4Address ipv4Addr;
        public final Inet4Address ipv4Gatway;
        public final Inet6Address ipv6Addr;

        private TetheredDevice(MacAddress mac, boolean hasIpv6) throws Exception {
            macAddr = mac;
            DhcpResults dhcpResults = runDhcp(macAddr.toByteArray());
            ipv4Addr = (Inet4Address) dhcpResults.ipAddress.getAddress();
            ipv4Gatway = (Inet4Address) dhcpResults.gateway;
            routerMacAddr = getRouterMacAddressFromArp(ipv4Addr, macAddr,
                    dhcpResults.serverAddress);
            ipv6Addr = hasIpv6 ? runSlaac(macAddr, routerMacAddr) : null;
        }
    }

    /** Simulate dhcp client to obtain ipv4 address. */
    public DhcpResults runDhcp(byte[] clientMacAddr)
            throws Exception {
        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(clientMacAddr);
            offerPacket = getNextDhcpPacket();
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        if (!(offerPacket instanceof DhcpOfferPacket)) {
            throw new TimeoutException("No DHCPOFFER received on interface within timeout");
        }

        sendDhcpRequest(offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket();
        if (!(ackPacket instanceof DhcpAckPacket)) {
            throw new TimeoutException("No DHCPACK received on interface within timeout");
        }

        return ackPacket.toDhcpResults();
    }

    private void sendDhcpDiscover(byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        mDownstreamReader.sendResponse(packet);
    }

    private void sendDhcpRequest(DhcpPacket offerPacket, byte[] macAddress)
            throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        mDownstreamReader.sendResponse(packet);
    }

    private DhcpPacket getNextDhcpPacket() throws Exception {
        final byte[] packet = getDownloadPacket((p) -> {
            // Test whether this is DHCP packet.
            try {
                DhcpPacket.decodeFullPacket(p, p.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet.
                return false;
            }

            return true;
        });

        return packet == null ? null :
                DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
    }

    @Nullable
    private ArpPacket parseArpPacket(final byte[] packet) {
        try {
            return ArpPacket.parseArpPacket(packet, packet.length);
        } catch (ArpPacket.ParseException e) {
            return null;
        }
    }

    private void maybeReplyArp(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);

        final ArpPacket arpPacket = parseArpPacket(packet);
        if (arpPacket == null || arpPacket.opCode != ARP_REQUEST) return;

        for (int i = 0; i < mTetheredDevices.size(); i++) {
            TetheredDevice tethered = mTetheredDevices.valueAt(i);
            if (!arpPacket.targetIp.equals(tethered.ipv4Addr)) continue;

            final ByteBuffer arpReply = ArpPacket.buildArpPacket(
                    arpPacket.senderHwAddress.toByteArray() /* dst */,
                    tethered.macAddr.toByteArray() /* srcMac */,
                    arpPacket.senderIp.getAddress() /* target IP */,
                    arpPacket.senderHwAddress.toByteArray() /* target HW address */,
                    tethered.ipv4Addr.getAddress() /* sender IP */,
                    (short) ARP_REPLY);
            try {
                sendUploadPacket(arpReply);
            } catch (Exception e) {
                fail("Failed to reply ARP for " + tethered.ipv4Addr);
            }
            return;
        }
    }

    private MacAddress getRouterMacAddressFromArp(final Inet4Address tetherIp,
            final MacAddress tetherMac, final Inet4Address routerIp) throws Exception {
        final ByteBuffer arpProbe = ArpPacket.buildArpPacket(ETHER_BROADCAST /* dst */,
                tetherMac.toByteArray() /* srcMac */, routerIp.getAddress() /* target IP */,
                new byte[ETHER_ADDR_LEN] /* target HW address */,
                tetherIp.getAddress() /* sender IP */, (short) ARP_REQUEST);
        sendUploadPacket(arpProbe);

        final byte[] packet = getDownloadPacket((p) -> {
            final ArpPacket arpPacket = parseArpPacket(p);
            if (arpPacket == null || arpPacket.opCode != ARP_REPLY) return false;
            return arpPacket.targetIp.equals(tetherIp);
        });

        if (packet != null) {
            Log.d(TAG, "Get Mac address from ARP");
            final ArpPacket arpReply = ArpPacket.parseArpPacket(packet, packet.length);
            return arpReply.senderHwAddress;
        }

        fail("Could not get ARP packet");
        return null;
    }

    private List<PrefixInformationOption> getRaPrefixOptions(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        if (!isExpectedIcmpPacket(buf, true /* hasEth */, false /* isIpv4 */,
                ICMPV6_ROUTER_ADVERTISEMENT)) {
            fail("Parsing RA packet fail");
        }

        Struct.parse(RaHeader.class, buf);
        final ArrayList<PrefixInformationOption> pioList = new ArrayList<>();
        while (buf.position() < packet.length) {
            final int currentPos = buf.position();
            final int type = Byte.toUnsignedInt(buf.get());
            final int length = Byte.toUnsignedInt(buf.get());
            if (type == ICMPV6_ND_OPTION_PIO) {
                final ByteBuffer pioBuf = ByteBuffer.wrap(buf.array(), currentPos,
                        Struct.getSize(PrefixInformationOption.class));
                final PrefixInformationOption pio =
                        Struct.parse(PrefixInformationOption.class, pioBuf);
                pioList.add(pio);

                // Move ByteBuffer position to the next option.
                buf.position(currentPos + Struct.getSize(PrefixInformationOption.class));
            } else {
                buf.position(currentPos + (length * 8));
            }
        }
        return pioList;
    }

    private Inet6Address runSlaac(MacAddress srcMac, MacAddress dstMac) throws Exception {
        sendRsPacket(srcMac, dstMac);

        final byte[] raPacket = verifyPacketNotNull("Receive RA fail", getDownloadPacket(p -> {
            return isExpectedIcmpPacket(p, true /* hasEth */, false /* isIpv4 */,
                    ICMPV6_ROUTER_ADVERTISEMENT);
        }));

        final List<PrefixInformationOption> options = getRaPrefixOptions(raPacket);

        for (PrefixInformationOption pio : options) {
            if (pio.validLifetime > 0) {
                final byte[] addressBytes = pio.prefix;
                // Random the last two bytes as suffix.
                // TODO: Currently do not implmement DAD in the test. Rely the gateway ipv6 address
                // genetrated by tethering module always has random the last byte.
                addressBytes[addressBytes.length - 1] = (byte) (new Random()).nextInt();
                addressBytes[addressBytes.length - 2] = (byte) (new Random()).nextInt();

                return (Inet6Address) InetAddress.getByAddress(addressBytes);
            }
        }

        fail("No available ipv6 prefix");
        return null;
    }

    private void sendRsPacket(MacAddress srcMac, MacAddress dstMac) throws Exception {
        Log.d(TAG, "Sending RS");
        ByteBuffer slla = LlaOption.build((byte) ICMPV6_ND_OPTION_SLLA, srcMac);
        ByteBuffer rs = Ipv6Utils.buildRsPacket(srcMac, dstMac, (Inet6Address) LINK_LOCAL,
                IPV6_ADDR_ALL_NODES_MULTICAST, slla);

        sendUploadPacket(rs);
    }

    private void maybeReplyNa(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        final EthernetHeader ethHdr = Struct.parse(EthernetHeader.class, buf);
        if (ethHdr.etherType != ETHER_TYPE_IPV6) return;

        final Ipv6Header ipv6Hdr = Struct.parse(Ipv6Header.class, buf);
        if (ipv6Hdr.nextHeader != (byte) IPPROTO_ICMPV6) return;

        final Icmpv6Header icmpv6Hdr = Struct.parse(Icmpv6Header.class, buf);
        if (icmpv6Hdr.type != (short) ICMPV6_NEIGHBOR_SOLICITATION) return;

        final NsHeader nsHdr = Struct.parse(NsHeader.class, buf);
        for (int i = 0; i < mTetheredDevices.size(); i++) {
            TetheredDevice tethered = mTetheredDevices.valueAt(i);
            if (!nsHdr.target.equals(tethered.ipv6Addr)) continue;

            final ByteBuffer tlla = LlaOption.build((byte) ICMPV6_ND_OPTION_TLLA, tethered.macAddr);
            int flags = NEIGHBOR_ADVERTISEMENT_FLAG_SOLICITED
                    | NEIGHBOR_ADVERTISEMENT_FLAG_OVERRIDE;
            ByteBuffer ns = Ipv6Utils.buildNaPacket(tethered.macAddr, tethered.routerMacAddr,
                    nsHdr.target, ipv6Hdr.srcIp, flags, nsHdr.target, tlla);
            try {
                sendUploadPacket(ns);
            } catch (Exception e) {
                fail("Failed to reply NA for " + tethered.ipv6Addr);
            }

            return;
        }
    }

    public static boolean isExpectedIcmpPacket(byte[] packet, boolean hasEth, boolean isIpv4,
            int type) {
        final ByteBuffer buf = ByteBuffer.wrap(packet);
        return isExpectedIcmpPacket(buf, hasEth, isIpv4, type);
    }

    private static boolean isExpectedIcmpPacket(ByteBuffer buf, boolean hasEth, boolean isIpv4,
            int type) {
        try {
            if (hasEth && !hasExpectedEtherHeader(buf, isIpv4)) return false;

            final int ipProto = isIpv4 ? IPPROTO_ICMP : IPPROTO_ICMPV6;
            if (!hasExpectedIpHeader(buf, isIpv4, ipProto)) return false;

            if (isIpv4) {
                return Struct.parse(Icmpv4Header.class, buf).type == (short) type;
            } else {
                return Struct.parse(Icmpv6Header.class, buf).type == (short) type;
            }
        } catch (Exception e) {
            // Parsing packet fail means it is not icmp packet.
        }

        return false;
    }

    private static boolean hasExpectedEtherHeader(@NonNull final ByteBuffer buf, boolean isIpv4)
            throws Exception {
        final int expected = isIpv4 ? ETHER_TYPE_IPV4 : ETHER_TYPE_IPV6;

        return Struct.parse(EthernetHeader.class, buf).etherType == expected;
    }

    private static boolean hasExpectedIpHeader(@NonNull final ByteBuffer buf, boolean isIpv4,
            int ipProto) throws Exception {
        if (isIpv4) {
            return Struct.parse(Ipv4Header.class, buf).protocol == (byte) ipProto;
        } else {
            return Struct.parse(Ipv6Header.class, buf).nextHeader == (byte) ipProto;
        }
    }

    private static boolean isExpectedUdpPacket(@NonNull final byte[] rawPacket, boolean hasEth,
            boolean isIpv4, Predicate<ByteBuffer> payloadVerifier) {
        final ByteBuffer buf = ByteBuffer.wrap(rawPacket);
        try {
            if (hasEth && !hasExpectedEtherHeader(buf, isIpv4)) return false;

            if (!hasExpectedIpHeader(buf, isIpv4, IPPROTO_UDP)) return false;

            if (Struct.parse(UdpHeader.class, buf) == null) return false;

            if (!payloadVerifier.test(buf)) return false;
        } catch (Exception e) {
            // Parsing packet fail means it is not udp packet.
            return false;
        }
        return true;
    }

    // Returns remaining bytes in the ByteBuffer in a new byte array of the right size. The
    // ByteBuffer will be empty upon return. Used to avoid lint warning.
    // See https://errorprone.info/bugpattern/ByteBufferBackingArray
    private static byte[] getRemaining(final ByteBuffer buf) {
        final byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        Log.d(TAG, "Get remaining bytes: " + dumpHexString(bytes));
        return bytes;
    }

    // |expectedPayload| is copied as read-only because the caller may reuse it.
    public static boolean isExpectedUdpPacket(@NonNull final byte[] rawPacket, boolean hasEth,
            boolean isIpv4, @NonNull final ByteBuffer expectedPayload) {
        return isExpectedUdpPacket(rawPacket, hasEth, isIpv4, p -> {
            if (p.remaining() != expectedPayload.limit()) return false;

            return Arrays.equals(getRemaining(p), getRemaining(
                    expectedPayload.asReadOnlyBuffer()));
        });
    }

    // |expectedPayload| is copied as read-only because the caller may reuse it.
    // See hasExpectedDnsMessage.
    public static boolean isExpectedUdpDnsPacket(@NonNull final byte[] rawPacket, boolean hasEth,
            boolean isIpv4, @NonNull final ByteBuffer expectedPayload) {
        return isExpectedUdpPacket(rawPacket, hasEth, isIpv4, p -> {
            return hasExpectedDnsMessage(p, expectedPayload);
        });
    }

    public static class TestDnsPacket extends DnsPacket {
        TestDnsPacket(byte[] data) throws DnsPacket.ParseException {
            super(data);
        }

        @Nullable
        public static TestDnsPacket getTestDnsPacket(final ByteBuffer buf) {
            try {
                // The ByteBuffer will be empty upon return.
                return new TestDnsPacket(getRemaining(buf));
            } catch (DnsPacket.ParseException e) {
                return null;
            }
        }

        public DnsHeader getHeader() {
            return mHeader;
        }

        public List<DnsRecord> getRecordList(int secType) {
            return mRecords[secType];
        }

        public int getANCount() {
            return mHeader.getRecordCount(ANSECTION);
        }

        public int getQDCount() {
            return mHeader.getRecordCount(QDSECTION);
        }

        public int getNSCount() {
            return mHeader.getRecordCount(NSSECTION);
        }

        public int getARCount() {
            return mHeader.getRecordCount(ARSECTION);
        }

        private boolean isRecordsEquals(int type, @NonNull final TestDnsPacket other) {
            List<DnsRecord> records = getRecordList(type);
            List<DnsRecord> otherRecords = other.getRecordList(type);

            if (records.size() != otherRecords.size()) return false;

            // Expect that two compared resource records are in the same order. For current tests
            // in EthernetTetheringTest, it is okay because dnsmasq doesn't reorder the forwarded
            // resource records.
            // TODO: consider allowing that compare records out of order.
            for (int i = 0; i < records.size(); i++) {
                // TODO: use DnsRecord.equals once aosp/1387135 is merged.
                if (!TextUtils.equals(records.get(i).dName, otherRecords.get(i).dName)
                        || records.get(i).nsType != otherRecords.get(i).nsType
                        || records.get(i).nsClass != otherRecords.get(i).nsClass
                        || records.get(i).ttl != otherRecords.get(i).ttl
                        || !Arrays.equals(records.get(i).getRR(), otherRecords.get(i).getRR())) {
                    return false;
                }
            }
            return true;
        }

        public boolean isQDRecordsEquals(@NonNull final TestDnsPacket other) {
            return isRecordsEquals(QDSECTION, other);
        }

        public boolean isANRecordsEquals(@NonNull final TestDnsPacket other) {
            return isRecordsEquals(ANSECTION, other);
        }
    }

    // The ByteBuffer |actual| will be empty upon return. The ByteBuffer |excepted| will be copied
    // as read-only because the caller may reuse it.
    private static boolean hasExpectedDnsMessage(@NonNull final ByteBuffer actual,
            @NonNull final ByteBuffer excepted) {
        // Forwarded DNS message is extracted from remaining received packet buffer which has
        // already parsed ethernet header, if any, IP header and UDP header.
        final TestDnsPacket forwardedDns = TestDnsPacket.getTestDnsPacket(actual);
        if (forwardedDns == null) return false;

        // Original DNS message is the payload of the sending test UDP packet. It is used to check
        // that the forwarded DNS query and reply have corresponding contents.
        final TestDnsPacket originalDns = TestDnsPacket.getTestDnsPacket(
                excepted.asReadOnlyBuffer());
        assertNotNull(originalDns);

        // Compare original DNS message which is sent to dnsmasq and forwarded DNS message which
        // is forwarded by dnsmasq. The original message and forwarded message may be not identical
        // because dnsmasq may change the header flags or even recreate the DNS query message and
        // so on. We only simple check on forwarded packet and monitor if test will be broken by
        // vendor dnsmasq customization. See forward_query() in external/dnsmasq/src/forward.c.
        //
        // DNS message format. See rfc1035 section 4.1.
        // +---------------------+
        // |        Header       |
        // +---------------------+
        // |       Question      | the question for the name server
        // +---------------------+
        // |        Answer       | RRs answering the question
        // +---------------------+
        // |      Authority      | RRs pointing toward an authority
        // +---------------------+
        // |      Additional     | RRs holding additional information
        // +---------------------+

        // [1] Header section. See rfc1035 section 4.1.1.
        // Verify QR flag bit, QDCOUNT, ANCOUNT, NSCOUNT, ARCOUNT.
        if (originalDns.getHeader().isResponse() != forwardedDns.getHeader().isResponse()) {
            return false;
        }
        if (originalDns.getQDCount() != forwardedDns.getQDCount()) return false;
        if (originalDns.getANCount() != forwardedDns.getANCount()) return false;
        if (originalDns.getNSCount() != forwardedDns.getNSCount()) return false;
        if (originalDns.getARCount() != forwardedDns.getARCount()) return false;

        // [2] Question section. See rfc1035 section 4.1.2.
        // Question section has at least one entry either DNS query or DNS reply.
        if (forwardedDns.getRecordList(QDSECTION).isEmpty()) return false;
        // Expect that original and forwarded message have the same question records (usually 1).
        if (!originalDns.isQDRecordsEquals(forwardedDns)) return false;

        // [3] Answer section. See rfc1035 section 4.1.3.
        if (forwardedDns.getHeader().isResponse()) {
            // DNS reply has at least have one answer in our tests.
            // See EthernetTetheringTest#testTetherUdpV4Dns.
            if (forwardedDns.getRecordList(ANSECTION).isEmpty()) return false;
            // Expect that original and forwarded message have the same answer records.
            if (!originalDns.isANRecordsEquals(forwardedDns)) return false;
        }

        // Ignore checking {Authority, Additional} sections because they are not tested
        // in EthernetTetheringTest.
        return true;
    }


    private static boolean isTcpSynPacket(@NonNull final TcpHeader tcpHeader) {
        return (tcpHeader.dataOffsetAndControlBits & TCPHDR_SYN) != 0;
    }

    public static boolean isExpectedTcpPacket(@NonNull final byte[] rawPacket, boolean hasEth,
            boolean isIpv4, int seq, @NonNull final ByteBuffer payload) {
        final ByteBuffer buf = ByteBuffer.wrap(rawPacket);
        try {
            if (hasEth && !hasExpectedEtherHeader(buf, isIpv4)) return false;

            if (!hasExpectedIpHeader(buf, isIpv4, IPPROTO_TCP)) return false;

            final TcpHeader tcpHeader = Struct.parse(TcpHeader.class, buf);
            if (tcpHeader.seq != seq) return false;

            // Don't try to parse the payload if it is a TCP SYN segment because additional TCP
            // option MSS may be added in the SYN segment. Currently, TetherController uses
            // iptables to limit downstream MSS for IPv4. The additional TCP options will be
            // misunderstood as payload because parsing TCP options are not supported by class
            // TcpHeader for now. See TetherController::setupIptablesHooks.
            // TODO: remove once TcpHeader supports parsing TCP options.
            if (isTcpSynPacket(tcpHeader)) {
                Log.d(TAG, "Found SYN segment. Ignore parsing the remaining part of packet.");
                return true;
            }

            if (payload.limit() != buf.remaining()) return false;
            return Arrays.equals(getRemaining(buf), getRemaining(payload.asReadOnlyBuffer()));
        } catch (Exception e) {
            // Parsing packet fail means it is not tcp packet.
        }

        return false;
    }

    private void sendUploadPacket(ByteBuffer packet) throws Exception {
        mDownstreamReader.sendResponse(packet);
    }

    private void sendDownloadPacket(ByteBuffer packet) throws Exception {
        assertNotNull("Can't deal with upstream interface in local only mode", mUpstreamReader);

        mUpstreamReader.sendResponse(packet);
    }

    private byte[] getDownloadPacket(Predicate<byte[]> filter) {
        byte[] packet;
        while ((packet = mDownstreamReader.poll(PACKET_READ_TIMEOUT_MS)) != null) {
            if (filter.test(packet)) return packet;

            maybeReplyArp(packet);
            maybeReplyNa(packet);
        }

        return null;
    }

    private byte[] getUploadPacket(Predicate<byte[]> filter) {
        assertNotNull("Can't deal with upstream interface in local only mode", mUpstreamReader);

        return mUpstreamReader.poll(PACKET_READ_TIMEOUT_MS, filter);
    }

    private @NonNull byte[] verifyPacketNotNull(String message, @Nullable byte[] packet) {
        assertNotNull(message, packet);

        return packet;
    }

    public byte[] testUpload(final ByteBuffer packet, final Predicate<byte[]> filter)
            throws Exception {
        sendUploadPacket(packet);

        return getUploadPacket(filter);
    }

    public byte[] verifyUpload(final ByteBuffer packet, final Predicate<byte[]> filter)
            throws Exception {
        return verifyPacketNotNull("Upload fail", testUpload(packet, filter));
    }

    public byte[] verifyDownload(final ByteBuffer packet, final Predicate<byte[]> filter)
            throws Exception {
        sendDownloadPacket(packet);

        return verifyPacketNotNull("Download fail", getDownloadPacket(filter));
    }
}
