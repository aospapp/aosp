/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.networkstack.netlink;

import static android.net.util.DataStallUtils.CONFIG_TCP_PACKETS_FAIL_PERCENTAGE;
import static android.net.util.DataStallUtils.DEFAULT_TCP_PACKETS_FAIL_PERCENTAGE;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.system.OsConstants.AF_INET;

import static com.android.net.module.util.netlink.NetlinkConstants.SOCKDIAG_MSG_HEADER_SIZE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.INetd;
import android.net.MarkMaskParcel;
import android.net.Network;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.util.Log.TerribleFailureHandler;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructNlMsgHdr;
import com.android.networkstack.apishim.ConstantsShim;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRule.IgnoreAfter;
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo;

import libcore.util.HexEncoding;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// TODO: Add more tests for missing coverage.
@RunWith(AndroidJUnit4.class)
@SmallTest
public class TcpSocketTrackerTest {
    private static final int TEST_BUFFER_SIZE = 1024;
    private static final String DIAG_MSG_HEX =
            // struct nlmsghdr.
            "10000000" +     // length = 16
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0301" +         // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +     // seqno
            "00000000";      // pid (0 == kernel)
    private static final byte[] SOCK_DIAG_MSG_BYTES =
            HexEncoding.decode(DIAG_MSG_HEX.toCharArray(), false);
    // Hexadecimal representation of a SOCK_DIAG response with tcp info.
    private static final String SOCK_DIAG_TCP_ZERO_LOST_HEX =
            composeSockDiagTcpHex(0 /* lost */, 10 /* sent */);
    private static final byte[] SOCK_DIAG_TCP_INET_ZERO_LOST_BYTES =
            HexEncoding.decode(SOCK_DIAG_TCP_ZERO_LOST_HEX.toCharArray(), false);
    private static final TcpInfo TEST_TCPINFO =
            new TcpInfo(5 /* retransmits */, 0 /* lost */, 10 /* segsOut */, 0 /* segsIn */);
    private static final String NLMSG_DONE_HEX =
            // struct nlmsghdr
            "14000000"     // length = 20
            + "0300"         // type = NLMSG_DONE
            + "0301"         // flags = NLM_F_REQUEST | NLM_F_DUMP
            + "00000000"     // seqno
            + "00000000"     // pid (0 == kernel)
            // struct inet_diag_req_v2
            + "02"           // family = AF_INET
            + "06"           // state
            + "00"           // timer
            + "00";          // retrans
    private static final String TEST_RESPONSE_HEX = SOCK_DIAG_TCP_ZERO_LOST_HEX + NLMSG_DONE_HEX;
    private static final byte[] TEST_RESPONSE_BYTES =
            HexEncoding.decode(TEST_RESPONSE_HEX.toCharArray(), false);
    private static final int TEST_NETID1 = 0xA85;
    private static final int TEST_NETID2 = 0x1A85;
    private static final int TEST_NETID1_FWMARK = 0x0A85;
    private static final int TEST_NETID2_FWMARK = 0x1A85;
    private static final int NETID_MASK = 0xffff;
    @Mock private TcpSocketTracker.Dependencies mDependencies;
    @Mock private INetd mNetd;
    private final Network mNetwork = new Network(TEST_NETID1);
    private final Network mOtherNetwork = new Network(TEST_NETID2);
    private TerribleFailureHandler mOldWtfHandler;
    @Mock private Context mContext;
    @Mock private PowerManager mPowerManager;

    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // Override the default TerribleFailureHandler, as that handler might terminate the process
        // (if we're on an eng build).
        mOldWtfHandler =
                Log.setWtfHandler((tag, what, system) -> Log.e(tag, what.getMessage(), what));
        when(mDependencies.getNetd()).thenReturn(mNetd);
        when(mDependencies.isTcpInfoParsingSupported()).thenReturn(true);
        when(mDependencies.connectToKernel()).thenReturn(new FileDescriptor());
        when(mDependencies.getDeviceConfigPropertyInt(
                eq(NAMESPACE_CONNECTIVITY),
                eq(CONFIG_TCP_PACKETS_FAIL_PERCENTAGE),
                anyInt())).thenReturn(DEFAULT_TCP_PACKETS_FAIL_PERCENTAGE);

        when(mNetd.getFwmarkForNetwork(eq(TEST_NETID1)))
                .thenReturn(makeMarkMaskParcel(NETID_MASK, TEST_NETID1_FWMARK));
        doReturn(mPowerManager).when(mContext).getSystemService(PowerManager.class);
    }

    @After
    public void tearDown() {
        Log.setWtfHandler(mOldWtfHandler);
    }

    private MarkMaskParcel makeMarkMaskParcel(final int mask, final int mark) {
        final MarkMaskParcel parcel = new MarkMaskParcel();
        parcel.mask = mask;
        parcel.mark = mark;
        return parcel;
    }

    private ByteBuffer getByteBufferFromHexString(String hexStr) {
        final byte[] bytes = HexEncoding.decode(hexStr.toCharArray(), false);
        return getByteBuffer(bytes);
    }

    private ByteBuffer getByteBuffer(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }

    @Test
    public void testParseSockInfo() {
        final ByteBuffer buffer = getByteBuffer(SOCK_DIAG_TCP_INET_ZERO_LOST_BYTES);
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);
        buffer.position(SOCKDIAG_MSG_HEADER_SIZE);
        final TcpSocketTracker.SocketInfo parsed =
                tst.parseSockInfo(buffer, AF_INET, 276, 100L);

        assertEquals(parsed.tcpInfo, TEST_TCPINFO);
        assertEquals(parsed.fwmark, 789125);
        assertEquals(parsed.updateTime, 100);
        assertEquals(parsed.ipFamily, AF_INET);
    }

    @Test
    public void testEnoughBytesRemainForValidNlMsg() {
        final ByteBuffer buffer = ByteBuffer.allocate(TEST_BUFFER_SIZE);

        buffer.position(TEST_BUFFER_SIZE - StructNlMsgHdr.STRUCT_SIZE);
        assertTrue(NetlinkUtils.enoughBytesRemainForValidNlMsg(buffer));
        // Remaining buffer size is less than a valid StructNlMsgHdr size.
        buffer.position(TEST_BUFFER_SIZE - StructNlMsgHdr.STRUCT_SIZE + 1);
        assertFalse(NetlinkUtils.enoughBytesRemainForValidNlMsg(buffer));

        buffer.position(TEST_BUFFER_SIZE);
        assertFalse(NetlinkUtils.enoughBytesRemainForValidNlMsg(buffer));
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q) // TCP info parsing is not supported on Q
    public void testPollSocketsInfo() throws Exception {
        // This test requires shims that provide API 30 access
        assumeTrue(ConstantsShim.VERSION >= Build.VERSION_CODES.R);
        when(mDependencies.isTcpInfoParsingSupported()).thenReturn(false);
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);
        assertFalse(tst.pollSocketsInfo());

        when(mDependencies.isTcpInfoParsingSupported()).thenReturn(true);
        // No enough bytes remain for a valid NlMsg.
        final ByteBuffer invalidBuffer = ByteBuffer.allocate(1);
        invalidBuffer.order(ByteOrder.nativeOrder());
        when(mDependencies.recvMessage(any())).thenReturn(invalidBuffer);
        assertTrue(tst.pollSocketsInfo());
        assertEquals(-1, tst.getLatestPacketFailPercentage());
        assertEquals(0, tst.getSentSinceLastRecv());

        // Header only.
        final ByteBuffer headerBuffer = getByteBuffer(SOCK_DIAG_MSG_BYTES);
        when(mDependencies.recvMessage(any())).thenReturn(headerBuffer);
        assertTrue(tst.pollSocketsInfo());
        assertEquals(-1, tst.getLatestPacketFailPercentage());
        assertEquals(0, tst.getSentSinceLastRecv());

        setupNormalTestTcpInfo();
        assertTrue(tst.pollSocketsInfo());

        assertEquals(10, tst.getSentSinceLastRecv());
        assertEquals(50, tst.getLatestPacketFailPercentage());
        assertFalse(tst.isDataStallSuspected());
        // Lower the threshold.
        when(mDependencies.getDeviceConfigPropertyInt(any(), eq(CONFIG_TCP_PACKETS_FAIL_PERCENTAGE),
                anyInt())).thenReturn(40);
        // No device config change. Using cache value.
        assertFalse(tst.isDataStallSuspected());
        // Trigger a config update
        tst.mConfigListener.onPropertiesChanged(null /* properties */);
        assertTrue(tst.isDataStallSuspected());
    }

    @Test
    public void testTcpInfoParsingUnsupported() {
        doReturn(false).when(mDependencies).isTcpInfoParsingSupported();
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);
        verify(mDependencies).getNetd();

        assertFalse(tst.pollSocketsInfo());
        assertEquals(-1, tst.getLatestPacketFailPercentage());
        assertEquals(-1, tst.getLatestReceivedCount());
        assertEquals(-1, tst.getSentSinceLastRecv());
        assertFalse(tst.isDataStallSuspected());

        verify(mDependencies, atLeastOnce()).isTcpInfoParsingSupported();
        verifyNoMoreInteractions(mDependencies);

        // Verify that no un-registration for the device configuration listener and broadcast
        // receiver if TcpInfo parsing is not supported.
        tst.quit();
        verify(mDependencies, never()).removeDeviceConfigChangedListener(any());
        verify(mDependencies, never()).removeBroadcastReceiver(any());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testTcpInfoParsingWithMultipleMsgs() throws Exception {
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);

        // Case 1: A message about 5 sockets, then a message about 2 sockets,
        // then a message about 2 sockets together with DONE
        //
        // Mocking 6 return results for different IP families(3 for IPv6; 3 for Ipv4). Use the same
        // message for different IP families to reduce the complexity.
        doReturn(getByteBufferFromHexString(repeat(composeSockDiagTcpHex(0, 10), 5)),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(0, 10), 2)),
                getByteBufferFromHexString(
                        repeat(composeSockDiagTcpHex(0, 10), 2) + NLMSG_DONE_HEX),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(0, 10), 5)),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(0, 10), 2)),
                getByteBufferFromHexString(
                        repeat(composeSockDiagTcpHex(0, 10), 2) + NLMSG_DONE_HEX))
                .when(mDependencies).recvMessage(any());

        assertTrue(tst.pollSocketsInfo());
        // Verify that code reads all the messages. (3 times for IPv4, 3 times for IPv6)
        verify(mDependencies, times(6)).recvMessage(any());
        // Calculated from (retransmits + lost) / segsout.
        assertEquals(50, tst.getLatestPacketFailPercentage());
        // Lower than the 80% threshold
        assertFalse(tst.isDataStallSuspected());

        // Case 2: A message about 1 socket, then a message about 5 sockets,
        // then a message about 1 socket with DONE.
        // "Sent" increases by 5. No change for lost and retrans.
        //
        // Mocking 6 return results for different IP families(3 for IPv6; 3 for Ipv4). Use the same
        // message for different IP families to reduce the complexity.
        doReturn(getByteBufferFromHexString(composeSockDiagTcpHex(5, 15)),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(5, 15), 5)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15) + NLMSG_DONE_HEX),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15)),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(5, 15), 5)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15) + NLMSG_DONE_HEX))
                .when(mDependencies).recvMessage(any());

        assertTrue(tst.pollSocketsInfo());
        // Not reset mDependencies because it will reset other mocks.
        // Another 3 times for IPv6 and 3 times for IPv4
        verify(mDependencies, times(12)).recvMessage(any());
        // (5 lost + 0 retrans)/5 sent
        assertEquals(100, tst.getLatestPacketFailPercentage());
        assertTrue(tst.isDataStallSuspected());

        // Case 3: A message about 5 sockets, then a message about 1 socket,
        // then a message about 1 socket with DONE.
        // No change for sent, lost and retrans.
        //
        // Mocking 4 return results for different IP families(2 for IPv6; 2 for Ipv4). Use the same
        // message for different IP families to reduce the complexity.
        doReturn(getByteBufferFromHexString(repeat(composeSockDiagTcpHex(5, 15), 5)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15) + NLMSG_DONE_HEX),
                getByteBufferFromHexString(repeat(composeSockDiagTcpHex(5, 15), 5)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15)),
                getByteBufferFromHexString(composeSockDiagTcpHex(5, 15) + NLMSG_DONE_HEX))
                .when(mDependencies).recvMessage(any());

        assertTrue(tst.pollSocketsInfo());
        // Another 3 times for IPv6 and 3 times for IPv4
        verify(mDependencies, times(18)).recvMessage(any());
        // (0 lost + 0 retrans)/0 sent
        assertEquals(0, tst.getLatestPacketFailPercentage());
        // Lower than the 80% threshold
        assertFalse(tst.isDataStallSuspected());

        // Case 4: A message about 8 sockets with DONE.
        // "lost" increases by 3 and "sent" increases by 5
        //
        // Mocking 2 return results for different IP families(1 for IPv6; 1 for Ipv4). Use the same
        // message for different IP families to reduce the complexity.
        doReturn(getByteBufferFromHexString(
                        repeat(composeSockDiagTcpHex(9, 20), 8) + NLMSG_DONE_HEX),
                getByteBufferFromHexString(
                        repeat(composeSockDiagTcpHex(9, 20), 8) + NLMSG_DONE_HEX))
                .when(mDependencies).recvMessage(any());

        assertTrue(tst.pollSocketsInfo());
        // Another 1 time for IPv6 and 1 time for IPv4
        verify(mDependencies, times(20)).recvMessage(any());
        // (4 lost + 0 retrans)/5 sent
        assertEquals(80, tst.getLatestPacketFailPercentage());
        //Reach 80% threshold
        assertTrue(tst.isDataStallSuspected());

        // Case 5: A message about DONE with 2 sockets.
        // No socket information will be parsed though "lost" increases by 6 and "sent"
        // increases by 6.
        //
        // Mocking 2 return results for different IP families(1 for IPv6; 1 for Ipv4). Use the same
        // message for different IP families to reduce the complexity.
        doReturn(getByteBufferFromHexString(
                        NLMSG_DONE_HEX + repeat(composeSockDiagTcpHex(15, 26), 2)),
                getByteBufferFromHexString(
                        NLMSG_DONE_HEX + repeat(composeSockDiagTcpHex(15, 26), 2)))
                .when(mDependencies).recvMessage(any());
        assertTrue(tst.pollSocketsInfo());
        // Another 1 time for IPv6 and 1 time for IPv4
        verify(mDependencies, times(22)).recvMessage(any());
        // (0 lost + 0 retrans)/0 sent.
        // Parsing will be stopped in DONE message. No socket information will be parsed.
        assertEquals(0, tst.getLatestPacketFailPercentage());
        // Lower than the 80% threshold
        assertFalse(tst.isDataStallSuspected());
    }

    private String repeat(String orig, int times) {
        if (SdkLevel.isAtLeastT()) {
            // Only supported from Java 11
            return orig.repeat(times);
        } else {
            String repeated = "";
            for (int i = 0; i < times; i++) {
                repeated += orig;
            }
            return repeated;
        }
    }

    private static String getHexStringFromInt(int v) {
        final ByteBuffer bb = ByteBuffer.allocate(4);
        // Android is always little-endian. Refer to https://developer.android.com/ndk/guides/abis.
        bb.order(ByteOrder.nativeOrder());
        bb.putInt(v);
        String s = "";
        for (byte b : bb.array()) {
            s += String.format("%02X", b);
        }
        return s;
    }

    private static String composeSockDiagTcpHex(int lost, int sent) {
        return // struct nlmsghdr.
                "14010000" +        // length = 276
                "1400" +            // type = SOCK_DIAG_BY_FAMILY
                "0301" +            // flags = NLM_F_REQUEST | NLM_F_DUMP
                "00000000" +        // seqno
                "00000000" +        // pid (0 == kernel)
                // struct inet_diag_req_v2
                "02" +              // family = AF_INET
                "06" +              // state
                "00" +              // timer
                "00" +              // retrans
                // inet_diag_sockid
                "DEA5" +            // idiag_sport = 42462
                "71B9" +            // idiag_dport = 47473
                "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
                "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
                "00000000" +            // idiag_if
                "34ED000076270000" +    // idiag_cookie = 43387759684916
                "00000000" +            // idiag_expires
                "00000000" +            // idiag_rqueue
                "00000000" +            // idiag_wqueue
                "00000000" +            // idiag_uid
                "00000000" +            // idiag_inode
                // rtattr
                "0500" +            // len = 5
                "0800" +            // type = 8
                "00000000" +        // data
                "0800" +            // len = 8
                "0F00" +            // type = 15(INET_DIAG_MARK)
                "850A0C00" +        // data, socket mark=789125
                "AC00" +            // len = 172
                "0200" +            // type = 2(INET_DIAG_INFO)
                // tcp_info
                "01" +              // state = TCP_ESTABLISHED
                "00" +              // ca_state = TCP_CA_OPEN
                "05" +              // retransmits = 5
                "00" +              // probes = 0
                "00" +              // backoff = 0
                "07" +              // option = TCPI_OPT_WSCALE|TCPI_OPT_SACK|TCPI_OPT_TIMESTAMPS
                "88" +              // wscale = 8
                "00" +              // delivery_rate_app_limited = 0
                "4A911B00" +        // rto = 1806666
                "00000000" +        // ato = 0
                "2E050000" +        // sndMss = 1326
                "18020000" +        // rcvMss = 536
                "00000000" +        // unsacked = 0
                "00000000" +        // acked = 0
                getHexStringFromInt(lost) + // lost
                "00000000" +        // retrans = 0
                "00000000" +        // fackets = 0
                "BB000000" +        // lastDataSent = 187
                "00000000" +        // lastAckSent = 0
                "BB000000" +        // lastDataRecv = 187
                "BB000000" +        // lastDataAckRecv = 187
                "DC050000" +        // pmtu = 1500
                "30560100" +        // rcvSsthresh = 87600
                "3E2C0900" +        // rttt = 601150
                "1F960400" +        // rttvar = 300575
                "78050000" +        // sndSsthresh = 1400
                "0A000000" +        // sndCwnd = 10
                "A8050000" +        // advmss = 1448
                "03000000" +        // reordering = 3
                "00000000" +        // rcvrtt = 0
                "30560100" +        // rcvspace = 87600
                "00000000" +        // totalRetrans = 0
                "53AC000000000000" +    // pacingRate = 44115
                "FFFFFFFFFFFFFFFF" +    // maxPacingRate = 18446744073709551615
                "0100000000000000" +    // bytesAcked = 1
                "0000000000000000" +    // bytesReceived = 0
                getHexStringFromInt(sent) + // SegsOut
                "00000000" +        // SegsIn = 0
                "00000000" +        // NotSentBytes = 0
                "3E2C0900" +        // minRtt = 601150
                "00000000" +        // DataSegsIn = 0
                "00000000" +        // DataSegsOut = 0
                "0000000000000000"; // deliverRate = 0
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testTcpInfoParsingWithDozeMode() throws Exception {
        // This test requires shims that provide API 30 access
        assumeTrue(ConstantsShim.VERSION >= Build.VERSION_CODES.R);

        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);
        final ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        verify(mDependencies).addDeviceIdleReceiver(receiverCaptor.capture());
        setupNormalTestTcpInfo();
        assertTrue(tst.pollSocketsInfo());

        // Lower the threshold.
        when(mDependencies.getDeviceConfigPropertyInt(any(), eq(CONFIG_TCP_PACKETS_FAIL_PERCENTAGE),
                anyInt())).thenReturn(40);

        // Trigger a config update
        tst.mConfigListener.onPropertiesChanged(null /* properties */);
        assertEquals(10, tst.getSentSinceLastRecv());
        assertEquals(50, tst.getLatestPacketFailPercentage());
        assertTrue(tst.isDataStallSuspected());

        // Enable doze mode
        doReturn(true).when(mPowerManager).isDeviceIdleMode();
        final BroadcastReceiver receiver = receiverCaptor.getValue();
        receiver.onReceive(mContext, new Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
        assertFalse(tst.pollSocketsInfo());
        assertFalse(tst.isDataStallSuspected());
    }

    private void setupNormalTestTcpInfo() throws Exception {
        final ByteBuffer tcpBufferV6 = getByteBuffer(TEST_RESPONSE_BYTES);
        final ByteBuffer tcpBufferV4 = getByteBuffer(TEST_RESPONSE_BYTES);
        doReturn(tcpBufferV6, tcpBufferV4).when(mDependencies).recvMessage(any());
    }

    @Test @IgnoreAfter(Build.VERSION_CODES.Q)
    public void testTcpInfoParsingNotSupportedOnQ() {
        assertFalse(new TcpSocketTracker.Dependencies(mContext)
                .isTcpInfoParsingSupported());
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q)
    public void testTcpInfoParsingSupportedFromR() {
        assertTrue(new TcpSocketTracker.Dependencies(mContext)
                .isTcpInfoParsingSupported());
    }

    private static final String BAD_DIAG_MSG_HEX =
        // struct nlmsghdr.
            "00000058" +      // length = 1476395008
            "1400" +         // type = SOCK_DIAG_BY_FAMILY
            "0301" +         // flags = NLM_F_REQUEST | NLM_F_DUMP
            "00000000" +     // seqno
            "00000000" +     // pid (0 == kernel)
            // struct inet_diag_req_v2
            "02" +           // family = AF_INET
            "06" +           // state
            "00" +           // timer
            "00" +           // retrans
            // inet_diag_sockid
            "DEA5" +         // idiag_sport = 42462
            "71B9" +         // idiag_dport = 47473
            "0a006402000000000000000000000000" + // idiag_src = 10.0.100.2
            "08080808000000000000000000000000" + // idiag_dst = 8.8.8.8
            "00000000" +    // idiag_if
            "34ED000076270000" + // idiag_cookie = 43387759684916
            "00000000" +    // idiag_expires
            "00000000" +    // idiag_rqueue
            "00000000" +    // idiag_wqueue
            "00000000" +    // idiag_uid
            "00000000";    // idiag_inode
    private static final byte[] BAD_SOCK_DIAG_MSG_BYTES =
        HexEncoding.decode(BAD_DIAG_MSG_HEX.toCharArray(), false);

    @Test @IgnoreUpTo(Build.VERSION_CODES.Q) // TCP info parsing is not supported on Q
    public void testPollSocketsInfo_BadFormat() throws Exception {
        // This test requires shims that provide API 30 access
        assumeTrue(ConstantsShim.VERSION >= Build.VERSION_CODES.R);
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mNetwork);
        setupNormalTestTcpInfo();
        assertTrue(tst.pollSocketsInfo());
        assertEquals(10, tst.getSentSinceLastRecv());
        assertEquals(50, tst.getLatestPacketFailPercentage());

        final ByteBuffer badTcpBufferV6 = getByteBuffer(BAD_SOCK_DIAG_MSG_BYTES);
        final ByteBuffer badTcpBufferV4 = getByteBuffer(BAD_SOCK_DIAG_MSG_BYTES);
        doReturn(badTcpBufferV6, badTcpBufferV4).when(mDependencies).recvMessage(any());
        assertTrue(tst.pollSocketsInfo());
        // Expect no additional packets, so still 10.
        assertEquals(10, tst.getSentSinceLastRecv());
        // Expect to reset to 0.
        assertEquals(0, tst.getLatestPacketFailPercentage());
    }

    @Test
    public void testUnMatchNetwork() throws Exception {
        when(mNetd.getFwmarkForNetwork(eq(TEST_NETID2)))
                .thenReturn(makeMarkMaskParcel(NETID_MASK, TEST_NETID2_FWMARK));
        final TcpSocketTracker tst = new TcpSocketTracker(mDependencies, mOtherNetwork);
        setupNormalTestTcpInfo();
        assertTrue(tst.pollSocketsInfo());

        assertEquals(0, tst.getSentSinceLastRecv());
        assertEquals(-1, tst.getLatestPacketFailPercentage());
        assertFalse(tst.isDataStallSuspected());
    }
}
