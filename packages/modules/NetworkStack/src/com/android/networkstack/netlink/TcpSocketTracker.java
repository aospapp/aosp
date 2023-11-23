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

import static android.net.util.DataStallUtils.CONFIG_MIN_PACKETS_THRESHOLD;
import static android.net.util.DataStallUtils.CONFIG_TCP_PACKETS_FAIL_PERCENTAGE;
import static android.net.util.DataStallUtils.DEFAULT_DATA_STALL_MIN_PACKETS_THRESHOLD;
import static android.net.util.DataStallUtils.DEFAULT_TCP_PACKETS_FAIL_PERCENTAGE;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;
import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import static com.android.net.module.util.netlink.NetlinkConstants.NLMSG_DONE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCKDIAG_MSG_HEADER_SIZE;
import static com.android.net.module.util.netlink.NetlinkConstants.SOCK_DIAG_BY_FAMILY;
import static com.android.net.module.util.netlink.NetlinkUtils.DEFAULT_RECV_BUFSIZE;
import static com.android.net.module.util.netlink.NetlinkUtils.IO_TIMEOUT_MS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.INetd;
import android.net.MarkMaskParcel;
import android.net.Network;
import android.os.AsyncTask;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.SocketUtils;
import com.android.net.module.util.netlink.InetDiagMessage;
import com.android.net.module.util.netlink.NetlinkConstants;
import com.android.net.module.util.netlink.NetlinkUtils;
import com.android.net.module.util.netlink.StructInetDiagMsg;
import com.android.net.module.util.netlink.StructNlMsgHdr;
import com.android.networkstack.apishim.NetworkShimImpl;
import com.android.networkstack.apishim.common.ShimUtils;
import com.android.networkstack.apishim.common.UnsupportedApiLevelException;

import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Class for NetworkStack to send a SockDiag request and parse the returned tcp info.
 *
 * This is not thread-safe. This should be only accessed from one thread.
 */
public class TcpSocketTracker {
    private static final String TAG = TcpSocketTracker.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int[] ADDRESS_FAMILIES = new int[] {AF_INET6, AF_INET};

    /** Cookie offset of an InetMagMessage header. */
    private static final int IDIAG_COOKIE_OFFSET = 44;
    private static final int END_OF_PARSING = -1;
    /**
     *  Gather the socket info.
     *
     *    Key: The idiag_cookie value of the socket. See struct inet_diag_sockid in
     *         &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
     *  Value: See {@Code SocketInfo}
     */
    private final LongSparseArray<SocketInfo> mSocketInfos = new LongSparseArray<>();
    // Number of packets sent since the last received packet
    private int mSentSinceLastRecv;
    // The latest fail rate calculated by the latest tcp info.
    private int mLatestPacketFailPercentage;
    // Number of packets received in the latest polling cycle.
    private int mLatestReceivedCount;
    /**
     * Request to send to kernel to request tcp info.
     *
     *   Key: Ip family type.
     * Value: Bytes array represent the {@Code inetDiagReqV2}.
     */
    private final SparseArray<byte[]> mSockDiagMsg = new SparseArray<>();
    private final Dependencies mDependencies;
    private final INetd mNetd;
    private final Network mNetwork;
    // The fwmark value of {@code mNetwork}.
    private final int mNetworkMark;
    // The network id mask of fwmark.
    private final int mNetworkMask;
    private int mMinPacketsThreshold = DEFAULT_DATA_STALL_MIN_PACKETS_THRESHOLD;
    private int mTcpPacketsFailRateThreshold = DEFAULT_TCP_PACKETS_FAIL_PERCENTAGE;

    private final Object mDozeModeLock = new Object();
    @GuardedBy("mDozeModeLock")
    private boolean mInDozeMode = false;
    @VisibleForTesting
    protected final DeviceConfig.OnPropertiesChangedListener mConfigListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    mMinPacketsThreshold = mDependencies.getDeviceConfigPropertyInt(
                            NAMESPACE_CONNECTIVITY,
                            CONFIG_MIN_PACKETS_THRESHOLD,
                            DEFAULT_DATA_STALL_MIN_PACKETS_THRESHOLD);
                    mTcpPacketsFailRateThreshold = mDependencies.getDeviceConfigPropertyInt(
                            NAMESPACE_CONNECTIVITY,
                            CONFIG_TCP_PACKETS_FAIL_PERCENTAGE,
                            DEFAULT_TCP_PACKETS_FAIL_PERCENTAGE);
                }
            };

    final BroadcastReceiver mDeviceIdleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(intent.getAction())) {
                final PowerManager powerManager = context.getSystemService(PowerManager.class);
                final boolean deviceIdle = powerManager.isDeviceIdleMode();
                setDozeMode(deviceIdle);
            }
        }
    };

    public TcpSocketTracker(@NonNull final Dependencies dps, @NonNull final Network network) {
        mDependencies = dps;
        mNetwork = network;
        mNetd = mDependencies.getNetd();

        // If the parcel is null, nothing should be matched which is achieved by the combination of
        // {@code NetlinkUtils#NULL_MASK} and {@code NetlinkUtils#UNKNOWN_MARK}.
        final MarkMaskParcel parcel = getNetworkMarkMask();
        mNetworkMark = (parcel != null) ? parcel.mark : NetlinkUtils.UNKNOWN_MARK;
        mNetworkMask = (parcel != null) ? parcel.mask : NetlinkUtils.NULL_MASK;

        // Request tcp info from NetworkStack directly needs extra SELinux permission added after Q
        // release.
        if (!mDependencies.isTcpInfoParsingSupported()) return;
        // Build SocketDiag messages.
        for (final int family : ADDRESS_FAMILIES) {
            mSockDiagMsg.put(
                    family, InetDiagMessage.buildInetDiagReqForAliveTcpSockets(family));
        }
        mDependencies.addDeviceConfigChangedListener(mConfigListener);
        mDependencies.addDeviceIdleReceiver(mDeviceIdleReceiver);
    }

    @Nullable
    private MarkMaskParcel getNetworkMarkMask() {
        try {
            final int netId = NetworkShimImpl.newInstance(mNetwork).getNetId();
            return mNetd.getFwmarkForNetwork(netId);
        } catch (UnsupportedApiLevelException e) {
            log("Get netId is not available in this API level.");
        } catch (RemoteException e) {
            Log.e(TAG, "Error getting fwmark for network, ", e);
        }
        return null;
    }

    /**
     * Request to send a SockDiag Netlink request. Receive and parse the returned message. This
     * function is not thread-safe and should only be called from only one thread.
     *
     * @Return if this polling request is sent to kernel and executes successfully or not.
     */
    public boolean pollSocketsInfo() {
        if (!mDependencies.isTcpInfoParsingSupported()) return false;
        // Traffic will be restricted in doze mode. TCP info may not reflect the correct network
        // behavior.
        // TODO: Traffic may be restricted by other reason. Get the restriction info from bpf in T+.
        synchronized (mDozeModeLock) {
            if (mInDozeMode) return false;
        }

        FileDescriptor fd = null;

        try {
            final long time = SystemClock.elapsedRealtime();
            fd = mDependencies.connectToKernel();

            final TcpStat stat = new TcpStat();
            for (final int family : ADDRESS_FAMILIES) {
                mDependencies.sendPollingRequest(fd, mSockDiagMsg.get(family));

                while (parseMessage(mDependencies.recvMessage(fd), family, stat, time)) {
                    log("Pending info exist. Attempt to read more");
                }
            }

            // Calculate mLatestReceiveCount, mSentSinceLastRecv and mLatestPacketFailPercentage.
            mSentSinceLastRecv = (stat.receivedCount == 0)
                    ? (mSentSinceLastRecv + stat.sentCount) : 0;
            mLatestReceivedCount = stat.receivedCount;
            mLatestPacketFailPercentage = ((stat.sentCount != 0)
                    ? ((stat.retransmitCount + stat.lostCount) * 100 / stat.sentCount) : 0);

            // Remove out-of-date socket info.
            cleanupSocketInfo(time);
            return true;
        } catch (ErrnoException | SocketException | InterruptedIOException e) {
            Log.e(TAG, "Fail to get TCP info via netlink.", e);
        } finally {
            SocketUtils.closeSocketQuietly(fd);
        }

        return false;
    }

    // Return true if there are more pending messages to read
    private boolean parseMessage(ByteBuffer bytes, int family, TcpStat stat, long time) {
        if (!NetlinkUtils.enoughBytesRemainForValidNlMsg(bytes)) {
            // This is unlikely to happen in real cases. Check this first for testing.
            Log.e(TAG, "Size is less than header size. Ignored.");
            return false;
        }

        // Messages are composed with the following format. Stop parsing when receiving
        // message with nlmsg_type NLMSG_DONE.
        // +------------------+---------------+--------------+--------+
        // | Netlink Header   | Family Header | Attributes   | rtattr |
        // | struct nlmsghdr  | struct rtmsg  | struct rtattr|  data  |
        // +------------------+---------------+--------------+--------+
        //               :           :               :
        // +------------------+---------------+--------------+--------+
        // | Netlink Header   | Family Header | Attributes   | rtattr |
        // | struct nlmsghdr  | struct rtmsg  | struct rtattr|  data  |
        // +------------------+---------------+--------------+--------+
        try {
            do {
                final int nlmsgLen = getLengthAndVerifyMsgHeader(bytes, family);
                if (nlmsgLen == END_OF_PARSING) return false;

                if (isValidInetDiagMsgSize(nlmsgLen)) {
                    // Get the socket cookie value. Composed by two Integers value.
                    // Corresponds to inet_diag_sockid in
                    // &lt;linux_src&gt;/include/uapi/linux/inet_diag.h
                    bytes.position(bytes.position() + IDIAG_COOKIE_OFFSET);

                    // It's stored in native with 2 int. Parse it as long for
                    // convenience.
                    final long cookie = bytes.getLong();
                    log("cookie=" + cookie);
                    // Skip the rest part of StructInetDiagMsg.
                    bytes.position(bytes.position()
                            + StructInetDiagMsg.STRUCT_SIZE - IDIAG_COOKIE_OFFSET
                            - Long.BYTES);
                    final SocketInfo info = parseSockInfo(bytes, family, nlmsgLen,
                            time);
                    // Update TcpStats based on previous and current socket info.
                    stat.accumulate(
                            calculateLatestPacketsStat(info, mSocketInfos.get(cookie)));
                    mSocketInfos.put(cookie, info);
                }
            } while (NetlinkUtils.enoughBytesRemainForValidNlMsg(bytes));
        } catch (IllegalArgumentException | BufferUnderflowException e) {
            Log.wtf(TAG, "Unexpected socket info parsing, family " + family
                    + " buffer:" + bytes + " "
                    + Base64.getEncoder().encodeToString(bytes.array()), e);
            return false;
        }

        return true;
    }

    private int getLengthAndVerifyMsgHeader(@NonNull ByteBuffer bytes, int family) {
        final StructNlMsgHdr nlmsghdr = StructNlMsgHdr.parse(bytes);
        if (nlmsghdr == null) {
            Log.e(TAG, "Badly formatted data.");
            return END_OF_PARSING;
        }

        log("pollSocketsInfo: nlmsghdr=" + nlmsghdr + ", limit=" + bytes.limit());
        // End of the message. Stop parsing.
        if (nlmsghdr.nlmsg_type == NLMSG_DONE) {
            return END_OF_PARSING;
        }

        if (nlmsghdr.nlmsg_type != SOCK_DIAG_BY_FAMILY) {
            Log.e(TAG, "Expect to get family " + family
                    + " SOCK_DIAG_BY_FAMILY message but get "
                    + nlmsghdr.nlmsg_type);
            return END_OF_PARSING;
        }

        return nlmsghdr.nlmsg_len;
    }

    private void cleanupSocketInfo(final long time) {
        final int size = mSocketInfos.size();
        final List<Long> toRemove = new ArrayList<Long>();
        for (int i = 0; i < size; i++) {
            final long key = mSocketInfos.keyAt(i);
            if (mSocketInfos.get(key).updateTime < time) {
                toRemove.add(key);
            }
        }
        for (final Long key : toRemove) {
            mSocketInfos.remove(key);
        }
    }

    /** Parse a {@code SocketInfo} from the given position of the given byte buffer. */
    @VisibleForTesting
    @NonNull
    SocketInfo parseSockInfo(@NonNull final ByteBuffer bytes, final int family,
            final int nlmsgLen, final long time) {
        final int remainingDataSize = bytes.position() + nlmsgLen - SOCKDIAG_MSG_HEADER_SIZE;
        TcpInfo tcpInfo = null;
        int mark = NetlinkUtils.INIT_MARK_VALUE;
        // Get a tcp_info.
        while (bytes.position() < remainingDataSize) {
            final RoutingAttribute rtattr =
                    new RoutingAttribute(bytes.getShort(), bytes.getShort());
            final short dataLen = rtattr.getDataLength();
            if (rtattr.rtaType == NetlinkUtils.INET_DIAG_INFO) {
                tcpInfo = TcpInfo.parse(bytes, dataLen);
            } else if (rtattr.rtaType == NetlinkUtils.INET_DIAG_MARK) {
                mark = bytes.getInt();
            } else {
                // Data provided by kernel will include both valid data and padding data. The data
                // len provided from kernel indicates the valid data size. Readers must deduce the
                // alignment by themselves.
                skipRemainingAttributesBytesAligned(bytes, dataLen);
            }
        }
        final SocketInfo info = new SocketInfo(tcpInfo, family, mark, time);
        log("parseSockInfo, " + info);
        return info;
    }

    /**
     * Return if data stall is suspected or not by checking the latest tcp connection fail rate.
     * Expect to check after polling the latest status. This function should only be called from
     * statemachine thread of NetworkMonitor.
     */
    public boolean isDataStallSuspected() {
        if (!mDependencies.isTcpInfoParsingSupported()) return false;

        // Skip checking data stall since the traffic will be restricted and it will not be real
        // network stall.
        // TODO: Traffic may be restricted by other reason. Get the restriction info from bpf in T+.
        synchronized (mDozeModeLock) {
            if (mInDozeMode) return false;
        }

        return (getLatestPacketFailPercentage() >= getTcpPacketsFailRateThreshold());
    }

    /** Calculate the change between the {@param current} and {@param previous}. */
    @Nullable
    private TcpStat calculateLatestPacketsStat(@NonNull final SocketInfo current,
            @Nullable final SocketInfo previous) {
        final TcpStat stat = new TcpStat();
        // Ignore non-target network sockets.
        if ((current.fwmark & mNetworkMask) != mNetworkMark) {
            return null;
        }

        if (current.tcpInfo == null) {
            log("Current tcpInfo is null.");
            return null;
        }

        stat.sentCount = current.tcpInfo.mSegsOut;
        stat.receivedCount = current.tcpInfo.mSegsIn;
        stat.lostCount = current.tcpInfo.mLost;
        stat.retransmitCount = current.tcpInfo.mRetransmits;

        if (previous != null && previous.tcpInfo != null) {
            stat.sentCount -= previous.tcpInfo.mSegsOut;
            stat.receivedCount -= previous.tcpInfo.mSegsIn;
            stat.lostCount -= previous.tcpInfo.mLost;
            stat.retransmitCount -= previous.tcpInfo.mRetransmits;
        }
        log("calculateLatestPacketsStat, stat:" + stat);
        return stat;
    }

    /**
     * Get tcp connection fail rate based on packet lost and retransmission count.
     *
     * @return the latest packet fail percentage. -1 denotes that there is no available data.
     */
    public int getLatestPacketFailPercentage() {
        if (!mDependencies.isTcpInfoParsingSupported()) return -1;
        // Only return fail rate if device sent enough packets.
        if (getSentSinceLastRecv() < getMinPacketsThreshold()) return -1;
        return mLatestPacketFailPercentage;
    }

    /**
     * Return the number of packets sent since last received. Note that this number is calculated
     * between each polling period, not an accurate number.
     */
    public int getSentSinceLastRecv() {
        if (!mDependencies.isTcpInfoParsingSupported()) return -1;
        return mSentSinceLastRecv;
    }

    /** Return the number of the packets received in the latest polling cycle. */
    public int getLatestReceivedCount() {
        if (!mDependencies.isTcpInfoParsingSupported()) return -1;
        return mLatestReceivedCount;
    }

    private static boolean isValidInetDiagMsgSize(final int nlMsgLen) {
        return nlMsgLen >= SOCKDIAG_MSG_HEADER_SIZE;
    }

    private int getMinPacketsThreshold() {
        return mMinPacketsThreshold;
    }

    private int getTcpPacketsFailRateThreshold() {
        return mTcpPacketsFailRateThreshold;
    }

    /**
     * Method to skip the remaining attributes bytes.
     * Corresponds to NLMSG_NEXT in bionic/libc/kernel/uapi/linux/netlink.h.
     *
     * @param buffer the target ByteBuffer
     * @param len the remaining length to skip.
     */
    private void skipRemainingAttributesBytesAligned(@NonNull final ByteBuffer buffer,
            final short len) {
        // Data in {@Code RoutingAttribute} is followed after header with size {@Code NLA_ALIGNTO}
        // bytes long for each block. Next attribute will start after the padding bytes if any.
        // If all remaining bytes after header are valid in a data block, next attr will just start
        // after valid bytes.
        //
        // E.g. With NLA_ALIGNTO(4), an attr struct with length 5 means 1 byte valid data remains
        // after header and 3(4-1) padding bytes. Next attr with length 8 will start after the
        // padding bytes and contain 4(8-4) valid bytes of data. The next attr start after the
        // valid bytes, like:
        //
        // [HEADER(L=5)][   4-Bytes DATA      ][ HEADER(L=8) ][4 bytes DATA][Next attr]
        // [ 5 valid bytes ][3 padding bytes  ][      8 valid bytes        ]   ...
        final int cur = buffer.position();
        buffer.position(cur + NetlinkConstants.alignedLengthOf(len));
    }

    private void log(final String str) {
        if (DBG) Log.d(TAG, str);
    }

    /** Stops monitoring and releases resources. */
    public void quit() {
        // Do not need to unregister receiver and listener since registration is skipped
        // in the constructor.
        if (!mDependencies.isTcpInfoParsingSupported()) return;

        mDependencies.removeDeviceConfigChangedListener(mConfigListener);
        mDependencies.removeBroadcastReceiver(mDeviceIdleReceiver);
    }

    /**
     * Corresponds to {@code struct rtattr} from bionic/libc/kernel/uapi/linux/rtnetlink.h
     *
     * struct rtattr {
     *    unsigned short rta_len;    // Length of option
     *    unsigned short rta_type;   // Type of option
     *    // Data follows
     * };
     */
    class RoutingAttribute {
        public static final int HEADER_LENGTH = 4;

        public final short rtaLen;  // The whole valid size of the struct.
        public final short rtaType;

        RoutingAttribute(final short len, final short type) {
            rtaLen = len;
            rtaType = type;
        }
        public short getDataLength() {
            return (short) (rtaLen - HEADER_LENGTH);
        }
    }

    /**
     * Data class for keeping the socket info.
     */
    @VisibleForTesting
    class SocketInfo {
        @Nullable
        public final TcpInfo tcpInfo;
        // One of {@code AF_INET6, AF_INET}.
        public final int ipFamily;
        // "fwmark" value of the socket queried from native.
        public final int fwmark;
        // Socket information updated elapsed real time.
        public final long updateTime;

        SocketInfo(@Nullable final TcpInfo info, final int family, final int mark,
                final long time) {
            tcpInfo = info;
            ipFamily = family;
            updateTime = time;
            fwmark = mark;
        }

        @Override
        public String toString() {
            return "SocketInfo {Type:" + ipTypeToString(ipFamily) + ", "
                    + tcpInfo + ", mark:" + fwmark + " updated at " + updateTime + "}";
        }

        private String ipTypeToString(final int type) {
            if (type == AF_INET) {
                return "IP";
            } else if (type == AF_INET6) {
                return "IPV6";
            } else {
                return "UNKNOWN";
            }
        }
    }

    /**
     * private data class only for storing the Tcp statistic for calculating the fail rate and sent
     * count
     * */
    private class TcpStat {
        public int sentCount;
        public int lostCount;
        public int retransmitCount;
        public int receivedCount;

        void accumulate(@Nullable final TcpStat stat) {
            if (stat == null) return;

            sentCount += stat.sentCount;
            lostCount += stat.lostCount;
            receivedCount += stat.receivedCount;
            retransmitCount += stat.retransmitCount;
        }

        @Override
        public String toString() {
            return "TcpStat {sent=" + sentCount + ", lost=" + lostCount
                    + ", retransmit=" + retransmitCount + ", received=" + receivedCount + "}";
        }
    }

    private void setDozeMode(boolean isEnabled) {
        synchronized (mDozeModeLock) {
            if (mInDozeMode == isEnabled) return;
            mInDozeMode = isEnabled;
            log("Doze mode enabled=" + mInDozeMode);
        }
    }

    /**
     * Dependencies class for testing.
     */
    @VisibleForTesting
    public static class Dependencies {
        private final Context mContext;

        public Dependencies(final Context context) {
            mContext = context;
        }

        /**
         * Connect to kernel via netlink socket.
         *
         * @return fd the fileDescriptor of the socket.
         * Throw ErrnoException, SocketException if the exception is thrown.
         */
        public FileDescriptor connectToKernel() throws ErrnoException, SocketException {
            final FileDescriptor fd = NetlinkUtils.createNetLinkInetDiagSocket();
            NetlinkUtils.connectSocketToNetlink(fd);
            Os.setsockoptTimeval(fd, SOL_SOCKET, SO_SNDTIMEO,
                    StructTimeval.fromMillis(IO_TIMEOUT_MS));
            return fd;
        }

        /**
         * Send composed message request to kernel.
         * @param fd see {@Code FileDescriptor}
         * @param msg the byte array represent the request message to write to kernel.
         *
         * Throw ErrnoException or InterruptedIOException if the exception is thrown.
         */
        public void sendPollingRequest(@NonNull final FileDescriptor fd, @NonNull final byte[] msg)
                throws ErrnoException, InterruptedIOException {
            Os.write(fd, msg, 0 /* byteOffset */, msg.length);
        }

        /**
         * Look up the value of a property in DeviceConfig.
         * @param namespace The namespace containing the property to look up.
         * @param name The name of the property to look up.
         * @param defaultValue The value to return if the property does not exist or has no non-null
         *                     value.
         * @return the corresponding value, or defaultValue if none exists.
         */
        public int getDeviceConfigPropertyInt(@NonNull final String namespace,
                @NonNull final String name, final int defaultValue) {
            return DeviceConfigUtils.getDeviceConfigPropertyInt(namespace, name, defaultValue);
        }

        /**
         * Return if request tcp info via netlink socket is supported or not.
         */
        public boolean isTcpInfoParsingSupported() {
            // Request tcp info from NetworkStack directly needs extra SELinux permission added
            // after Q release.
            return ShimUtils.isReleaseOrDevelopmentApiAbove(Build.VERSION_CODES.Q);
        }

        /**
         * Receive the request message from kernel via given fd.
         */
        public ByteBuffer recvMessage(@NonNull final FileDescriptor fd)
                throws ErrnoException, InterruptedIOException {
            return NetlinkUtils.recvMessage(fd, DEFAULT_RECV_BUFSIZE, IO_TIMEOUT_MS);
        }

        public Context getContext() {
            return mContext;
        }

        /**
         * Get an INetd connector.
         */
        public INetd getNetd() {
            return INetd.Stub.asInterface(
                    (IBinder) mContext.getSystemService(Context.NETD_SERVICE));
        }

        /** Add device config change listener */
        public void addDeviceConfigChangedListener(
                @NonNull final DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_CONNECTIVITY,
                    AsyncTask.THREAD_POOL_EXECUTOR, listener);
        }

        /** Remove device config change listener */
        public void removeDeviceConfigChangedListener(
                @NonNull final DeviceConfig.OnPropertiesChangedListener listener) {
            DeviceConfig.removeOnPropertiesChangedListener(listener);
        }

        /** Add receiver for detecting doze mode change to control TCP detection. */
        public void addDeviceIdleReceiver(@NonNull final BroadcastReceiver receiver) {
            mContext.registerReceiver(receiver,
                    new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
        }

        /** Remove broadcast receiver. */
        public void removeBroadcastReceiver(@NonNull final BroadcastReceiver receiver) {
            mContext.unregisterReceiver(receiver);
        }
    }
}
