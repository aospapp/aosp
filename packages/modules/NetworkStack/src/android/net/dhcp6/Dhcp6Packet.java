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

package android.net.dhcp6;

import static com.android.net.module.util.NetworkStackConstants.DHCP_MAX_OPTION_LEN;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.Struct;
import com.android.net.module.util.structs.IaPrefixOption;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Defines basic data and operations needed to build and use packets for the
 * DHCPv6 protocol. Subclasses create the specific packets used at each
 * stage of the negotiation.
 *
 * @hide
 */
public class Dhcp6Packet {

    /**
     * DHCPv6 Message Type.
     */
    public static final byte DHCP6_MESSAGE_TYPE_SOLICIT = 1;
    public static final byte DHCP6_MESSAGE_TYPE_ADVERTISE = 2;
    public static final byte DHCP6_MESSAGE_TYPE_REQUEST = 3;
    public static final byte DHCP6_MESSAGE_TYPE_CONFIRM = 4;
    public static final byte DHCP6_MESSAGE_TYPE_RENEW = 5;
    public static final byte DHCP6_MESSAGE_TYPE_REBIND = 6;
    public static final byte DHCP6_MESSAGE_TYPE_REPLY = 7;
    public static final byte DHCP6_MESSAGE_TYPE_RELEASE = 8;
    public static final byte DHCP6_MESSAGE_TYPE_DECLINE = 9;
    public static final byte DHCP6_MESSAGE_TYPE_RECONFIGURE = 10;
    public static final byte DHCP6_MESSAGE_TYPE_INFORMATION_REQUEST = 11;
    public static final byte DHCP6_MESSAGE_TYPE_RELAY_FORW = 12;
    public static final byte DHCP6_MESSAGE_TYPE_RELAY_REPL = 13;

    /**
     * DHCPv6 Optional Type: Client Identifier.
     * DHCPv6 message from client must have this option.
     */
    public static final byte DHCP6_CLIENT_IDENTIFIER = 1;
    @NonNull
    protected final byte[] mClientDuid;

    /**
     * DHCPv6 Optional Type: Server Identifier.
     */
    public static final byte DHCP6_SERVER_IDENTIFIER = 2;
    protected final byte[] mServerDuid;

    /**
     * DHCPv6 Optional Type: Elapsed time.
     */
    public static final byte DHCP6_ELAPSED_TIME = 8;
    protected final short mSecs;

    /**
     * DHCPv6 Optional Type: Status Code.
     */
    public static final byte DHCP6_STATUS_CODE = 13;
    protected short mStatusCode;
    protected String mStatusMsg;

    public static final short STATUS_SUCCESS           = 0;
    public static final short STATUS_UNSPEC_FAIL       = 1;
    public static final short STATUS_NO_ADDR_AVAI      = 2;
    public static final short STATUS_NO_BINDING        = 3;
    public static final short STATUS_PREFIX_NOT_ONLINK = 4;
    public static final short STATUS_USE_MULTICAST     = 5;
    public static final short STATUS_NO_PREFIX_AVAI    = 6;

    /**
     * DHCPv6 Optional Type: IA_PD.
     */
    public static final byte DHCP6_IA_PD = 25;
    @NonNull
    protected final byte[] mIaPd;
    @NonNull
    protected PrefixDelegation mPrefixDelegation;

    /**
     * The transaction identifier used in this particular DHCPv6 negotiation
     */
    protected final int mTransId;

    /**
     * The unique identifier for IA_NA, IA_TA, IA_PD used in this particular DHCPv6 negotiation
     */
    protected int mIaId;

    Dhcp6Packet(int transId, short secs, @NonNull final byte[] clientDuid, final byte[] serverDuid,
            @NonNull final byte[] iapd) {
        mTransId = transId;
        mSecs = secs;
        mClientDuid = clientDuid;
        mServerDuid = serverDuid;
        mIaPd = iapd;
    }

    /**
     * Returns the transaction ID.
     */
    public int getTransactionId() {
        return mTransId;
    }

    /**
     * Returns IA_ID associated to IA_PD.
     */
    public int getIaId() {
        return mIaId;
    }

    /**
     * Returns the client's DUID.
     */
    @NonNull
    public byte[] getClientDuid() {
        return mClientDuid;
    }

    /**
     * Returns the server's DUID.
     */
    public byte[] getServerDuid() {
        return mServerDuid;
    }

    /**
     * A class to take DHCPv6 IA_PD option allocated from server.
     * https://www.rfc-editor.org/rfc/rfc8415.html#section-21.21
     */
    public static class PrefixDelegation {
        public int iaid;
        public int t1;
        public int t2;
        public final IaPrefixOption ipo;

        PrefixDelegation(int iaid, int t1, int t2, final IaPrefixOption ipo) {
            this.iaid = iaid;
            this.t1 = t1;
            this.t2 = t2;
            this.ipo = ipo;
        }

        @Override
        public String toString() {
            return "Prefix Delegation: iaid " + iaid + ", t1 " + t1 + ", t2 " + t2
                    + ", prefix " + ipo;
        }
    }

    /**
     * DHCPv6 packet parsing exception.
     */
    public static class ParseException extends Exception {
        ParseException(String msg) {
            super(msg);
        }
    }

    private static void skipOption(@NonNull final ByteBuffer packet, int optionLen)
            throws BufferUnderflowException {
        for (int i = 0; i < optionLen; i++) {
            packet.get();
        }
    }

    /**
     * Reads a string of specified length from the buffer.
     *
     * TODO: move to a common place which can be shared with DhcpClient.
     */
    private static String readAsciiString(@NonNull final ByteBuffer buf, int byteCount,
            boolean isNullOk) {
        final byte[] bytes = new byte[byteCount];
        buf.get(bytes);
        return readAsciiString(bytes, isNullOk);
    }

    private static String readAsciiString(@NonNull final byte[] payload, boolean isNullOk) {
        final byte[] bytes = payload;
        int length = bytes.length;
        if (!isNullOk) {
            // Stop at the first null byte. This is because some DHCP options (e.g., the domain
            // name) are passed to netd via FrameworkListener, which refuses arguments containing
            // null bytes. We don't do this by default because vendorInfo is an opaque string which
            // could in theory contain null bytes.
            for (length = 0; length < bytes.length; length++) {
                if (bytes[length] == 0) {
                    break;
                }
            }
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    /**
     * Creates a concrete Dhcp6Packet from the supplied ByteBuffer.
     *
     * The buffer only starts with a UDP encapsulation (i.e. DHCPv6 message). A subset of the
     * optional parameters are parsed and are stored in object fields. Client/Server message
     * format:
     *
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |    msg-type   |               transaction-id                  |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                                                               |
     * .                            options                            .
     * .                 (variable number and length)                  .
     * |                                                               |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     */
    @VisibleForTesting
    static Dhcp6Packet decodePacket(@NonNull final ByteBuffer packet) throws ParseException {
        short secs = 0;
        byte[] iapd = null;
        byte[] serverDuid = null;
        byte[] clientDuid = null;
        short statusCode = STATUS_SUCCESS;
        String statusMsg = null;

        packet.order(ByteOrder.BIG_ENDIAN);

        // DHCPv6 message contents.
        final int msgTypeAndTransId = packet.getInt();
        final byte messageType = (byte) (msgTypeAndTransId >> 24);
        final int transId = msgTypeAndTransId & 0x0FFF;

        /**
         * Parse DHCPv6 options, option format:
         *
         * 0                   1                   2                   3
         * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * |          option-code          |           option-len          |
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         * |                          option-data                          |
         * |                      (option-len octets)                      |
         * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
         */
        while (packet.hasRemaining()) {
            try {
                final short optionType = packet.getShort();
                final int optionLen = packet.getShort() & 0xFFFF;
                int expectedLen = 0;

                switch(optionType) {
                    case DHCP6_SERVER_IDENTIFIER:
                        expectedLen = optionLen;
                        final byte[] sduid = new byte[expectedLen];
                        packet.get(sduid, 0 /* offset */, expectedLen);
                        serverDuid = sduid;
                        break;
                    case DHCP6_CLIENT_IDENTIFIER:
                        expectedLen = optionLen;
                        final byte[] cduid = new byte[expectedLen];
                        packet.get(cduid, 0 /* offset */, expectedLen);
                        clientDuid = cduid;
                        break;
                    case DHCP6_IA_PD:
                        expectedLen = optionLen;
                        final byte[] bytes = new byte[expectedLen];
                        packet.get(bytes, 0 /* offset */, expectedLen);
                        iapd = bytes;
                        break;
                    case DHCP6_ELAPSED_TIME:
                        expectedLen = 2;
                        secs = packet.getShort();
                        break;
                    case DHCP6_STATUS_CODE:
                        expectedLen = optionLen;
                        statusCode = packet.getShort();
                        statusMsg = readAsciiString(packet, expectedLen - 2, false /* isNullOk */);
                        break;
                    default:
                        expectedLen = optionLen;
                        // BufferUnderflowException will be thrown if option is truncated.
                        skipOption(packet, optionLen);
                        break;
                }
                if (expectedLen != optionLen) {
                    throw new ParseException(
                            "Invalid length " + optionLen + " for option " + optionType
                                    + ", expected " + expectedLen);
                }
            } catch (BufferUnderflowException e) {
                throw new ParseException(e.getMessage());
            }
        }

        Dhcp6Packet newPacket;

        switch(messageType) {
            case DHCP6_MESSAGE_TYPE_SOLICIT:
                newPacket = new Dhcp6SolicitPacket(transId, secs, clientDuid, iapd);
                break;
            case DHCP6_MESSAGE_TYPE_ADVERTISE:
                newPacket = new Dhcp6AdvertisePacket(transId, clientDuid, serverDuid, iapd);
                break;
            case DHCP6_MESSAGE_TYPE_REQUEST:
                newPacket = new Dhcp6RequestPacket(transId, secs, clientDuid, serverDuid, iapd);
                break;
            case DHCP6_MESSAGE_TYPE_REPLY:
                newPacket = new Dhcp6ReplyPacket(transId, clientDuid, serverDuid, iapd);
                break;
            case DHCP6_MESSAGE_TYPE_RENEW:
                newPacket = new Dhcp6RenewPacket(transId, secs, clientDuid, serverDuid, iapd);
                break;
            case DHCP6_MESSAGE_TYPE_REBIND:
                newPacket = new Dhcp6RebindPacket(transId, secs, clientDuid, iapd);
                break;
            default:
                throw new ParseException("Unimplemented DHCP6 message type %d" + messageType);
        }

        if (iapd != null) {
            final ByteBuffer buffer = ByteBuffer.wrap(iapd);
            final int iaid = buffer.getInt();
            final int t1 = buffer.getInt();
            final int t2 = buffer.getInt();
            final IaPrefixOption ipo = Struct.parse(IaPrefixOption.class, buffer);
            newPacket.mPrefixDelegation = new PrefixDelegation(iaid, t1, t2, ipo);
            newPacket.mIaId = iaid;
        }
        newPacket.mStatusCode = statusCode;
        newPacket.mStatusMsg = statusMsg;

        return newPacket;
    }

    /**
     * Parse a packet from an array of bytes, stopping at the given length.
     */
    public static Dhcp6Packet decodePacket(@NonNull final byte[] packet, int length)
            throws ParseException {
        final ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN);
        return decodePacket(buffer);
    }

    /**
     * Adds an optional parameter containing an array of bytes.
     */
    protected static void addTlv(ByteBuffer buf, short type, @NonNull byte[] payload) {
        if (payload.length > DHCP_MAX_OPTION_LEN) {
            throw new IllegalArgumentException("DHCP option too long: "
                    + payload.length + " vs. " + DHCP_MAX_OPTION_LEN);
        }
        buf.putShort(type);
        buf.putShort((short) payload.length);
        buf.put(payload);
    }

    /**
     * Adds an optional parameter containing a short integer.
     */
    protected static void addTlv(ByteBuffer buf, short type, short value) {
        buf.putShort(type);
        buf.putShort((short) 2);
        buf.putShort(value);
    }

    /**
     * Builds a DHCPv6 SOLICIT packet from the required specified parameters.
     */
    public static ByteBuffer buildSolicitPacket(int transId, short secs, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid) {
        final Dhcp6SolicitPacket pkt = new Dhcp6SolicitPacket(transId, secs, clientDuid, iapd);
        return pkt.buildPacket();
    }

    /**
     * Builds a DHCPv6 ADVERTISE packet from the required specified parameters.
     */
    public static ByteBuffer buildAdvertisePacket(int transId, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid, @NonNull final byte[] serverDuid) {
        final Dhcp6AdvertisePacket pkt =
                new Dhcp6AdvertisePacket(transId, clientDuid, serverDuid, iapd);
        return pkt.buildPacket();
    }

    /**
     * Builds a DHCPv6 REPLY packet from the required specified parameters.
     */
    public static ByteBuffer buildReplyPacket(int transId, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid, @NonNull final byte[] serverDuid) {
        final Dhcp6ReplyPacket pkt = new Dhcp6ReplyPacket(transId, clientDuid, serverDuid, iapd);
        return pkt.buildPacket();
    }

    /**
     * Builds a DHCPv6 REQUEST packet from the required specified parameters.
     */
    public static ByteBuffer buildRequestPacket(int transId, short secs, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid, @NonNull final byte[] serverDuid) {
        final Dhcp6RequestPacket pkt =
                new Dhcp6RequestPacket(transId, secs, clientDuid, serverDuid, iapd);
        return pkt.buildPacket();
    }

    /**
     * Builds a DHCPv6 RENEW packet from the required specified parameters.
     */
    public static ByteBuffer buildRenewPacket(int transId, short secs, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid, @NonNull final byte[] serverDuid) {
        final Dhcp6RenewPacket pkt =
                new Dhcp6RenewPacket(transId, secs, clientDuid, serverDuid, iapd);
        return pkt.buildPacket();
    }

    /**
     * Builds a DHCPv6 REBIND packet from the required specified parameters.
     */
    public static ByteBuffer buildRebindPacket(int transId, short secs, @NonNull final byte[] iapd,
            @NonNull final byte[] clientDuid) {
        final Dhcp6RebindPacket pkt = new Dhcp6RebindPacket(transId, secs, clientDuid, iapd);
        return pkt.buildPacket();
    }
}
