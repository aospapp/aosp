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

import static com.android.server.connectivity.mdns.util.MdnsUtils.ensureRunningOnHandlerThread;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.server.connectivity.mdns.MdnsRecordRepository.ReplyInfo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Collections;

/**
 * A class that handles sending mDNS replies to a {@link MulticastSocket}, possibly queueing them
 * to be sent after some delay.
 *
 * TODO: implement sending after a delay, combining queued replies and duplicate answer suppression
 */
public class MdnsReplySender {
    private static final boolean DBG = MdnsAdvertiser.DBG;
    private static final int MSG_SEND = 1;

    private final String mLogTag;
    @NonNull
    private final MdnsInterfaceSocket mSocket;
    @NonNull
    private final Handler mHandler;
    @NonNull
    private final byte[] mPacketCreationBuffer;

    public MdnsReplySender(@NonNull String interfaceTag, @NonNull Looper looper,
            @NonNull MdnsInterfaceSocket socket, @NonNull byte[] packetCreationBuffer) {
        mHandler = new SendHandler(looper);
        mLogTag = MdnsReplySender.class.getSimpleName() + "/" +  interfaceTag;
        mSocket = socket;
        mPacketCreationBuffer = packetCreationBuffer;
    }

    /**
     * Queue a reply to be sent when its send delay expires.
     */
    public void queueReply(@NonNull ReplyInfo reply) {
        ensureRunningOnHandlerThread(mHandler);
        // TODO: implement response aggregation (RFC 6762 6.4)
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SEND, reply), reply.sendDelayMs);

        if (DBG) {
            Log.v(mLogTag, "Scheduling " + reply);
        }
    }

    /**
     * Send a packet immediately.
     *
     * Must be called on the looper thread used by the {@link MdnsReplySender}.
     */
    public void sendNow(@NonNull MdnsPacket packet, @NonNull InetSocketAddress destination)
            throws IOException {
        ensureRunningOnHandlerThread(mHandler);
        if (!((destination.getAddress() instanceof Inet6Address && mSocket.hasJoinedIpv6())
                || (destination.getAddress() instanceof Inet4Address && mSocket.hasJoinedIpv4()))) {
            // Skip sending if the socket has not joined the v4/v6 group (there was no address)
            return;
        }

        // TODO: support packets over size (send in multiple packets with TC bit set)
        final MdnsPacketWriter writer = new MdnsPacketWriter(mPacketCreationBuffer);

        writer.writeUInt16(0); // Transaction ID (advertisement: 0)
        writer.writeUInt16(packet.flags); // Response, authoritative (rfc6762 18.4)
        writer.writeUInt16(packet.questions.size()); // questions count
        writer.writeUInt16(packet.answers.size()); // answers count
        writer.writeUInt16(packet.authorityRecords.size()); // authority entries count
        writer.writeUInt16(packet.additionalRecords.size()); // additional records count

        for (MdnsRecord record : packet.questions) {
            // Questions do not have TTL or data
            record.writeHeaderFields(writer);
        }
        for (MdnsRecord record : packet.answers) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.authorityRecords) {
            record.write(writer, 0L);
        }
        for (MdnsRecord record : packet.additionalRecords) {
            record.write(writer, 0L);
        }

        final int len = writer.getWritePosition();
        final byte[] outBuffer = new byte[len];
        System.arraycopy(mPacketCreationBuffer, 0, outBuffer, 0, len);

        mSocket.send(new DatagramPacket(outBuffer, 0, len, destination));
    }

    /**
     * Cancel all pending sends.
     */
    public void cancelAll() {
        ensureRunningOnHandlerThread(mHandler);
        mHandler.removeMessages(MSG_SEND);
    }

    private class SendHandler extends Handler {
        SendHandler(@NonNull Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            final ReplyInfo replyInfo = (ReplyInfo) msg.obj;
            if (DBG) Log.v(mLogTag, "Sending " + replyInfo);

            final int flags = 0x8400; // Response, authoritative (rfc6762 18.4)
            final MdnsPacket packet = new MdnsPacket(flags,
                    Collections.emptyList() /* questions */,
                    replyInfo.answers,
                    Collections.emptyList() /* authorityRecords */,
                    replyInfo.additionalAnswers);

            try {
                sendNow(packet, replyInfo.destination);
            } catch (IOException e) {
                Log.e(mLogTag, "Error sending MDNS response", e);
            }
        }
    }
}
