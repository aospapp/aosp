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
package android.cts.statsd.subscriber;

import com.android.compatibility.common.util.CpuFeatures;
import com.android.internal.os.StatsdConfigProto;
import com.android.os.AtomsProto;
import com.android.os.ShellConfig;
import com.android.os.statsd.ShellDataProto;
import com.android.tradefed.device.CollectingByteOutputReceiver;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.DeviceTestCase;
import com.google.common.io.Files;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Statsd shell data subscription test.
 */
public class ShellSubscriberTest extends DeviceTestCase {
    private int sizetBytes;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sizetBytes = getSizetBytes();
    }

    private int getSizetBytes() {
        try {
            ITestDevice device = getDevice();
            if (CpuFeatures.isArm64(device)) {
                return 8;
            }
            if (CpuFeatures.isArm32(device)) {
                return 4;
            }
            return -1;
        } catch (DeviceNotAvailableException e) {
            return -1;
        }
    }

    // Tests that anomaly detection for count works.
    // Also tests that anomaly detection works when spanning multiple buckets.
    public void testShellSubscription() {
        if (sizetBytes < 0) {
            return;
        }
        // choose a pulled atom that is likely to be supported on all devices (SYSTEM_UPTIME).
        // Testing pushed atom is a little trickier, because the executeShellCommand() is blocking
        // and we cannot push a breadcrumb event at the same time when the shell subscription is
        // running. So test pulled atom instead.
        ShellConfig.ShellSubscription config = ShellConfig.ShellSubscription.newBuilder()
                .addPulled(ShellConfig.PulledAtomSubscription.newBuilder().setMatcher(
                        StatsdConfigProto.SimpleAtomMatcher.newBuilder()
                                .setAtomId(AtomsProto.Atom.SYSTEM_UPTIME_FIELD_NUMBER).build())
                        .setFreqMillis(2000).build()).build();
        CollectingByteOutputReceiver receiver = new CollectingByteOutputReceiver();
        startSubscription(config, receiver, 10);
        byte[] output = receiver.getOutput();
        // There should be at lease some data returned.
        assertTrue(output.length > sizetBytes);

        int atomCount = 0;
        int i = 0;
        while (output.length > i + sizetBytes) {
            int len = 0;
            for (int j = 0; j < sizetBytes; j++) {
                len += ((int) output[i + j] & 0xffL) << (sizetBytes * j);
            }
            LogUtil.CLog.d("received : " + output.length + " bytes, size : " + len);

            if (output.length < i + sizetBytes + len) {
                fail("Bad data received.");
            }

            try {
                ShellDataProto.ShellData data =
                        ShellDataProto.ShellData.parseFrom(
                                Arrays.copyOfRange(output, i + sizetBytes, i + sizetBytes + len));
                assertTrue(data.getAtomCount() > 0);
                assertTrue(data.getAtom(0).hasSystemUptime());
                atomCount++;
                LogUtil.CLog.d("Received " + data.toString());
            } catch (InvalidProtocolBufferException e) {
                fail("Failed to parse proto");
            }
            i += (sizetBytes + len);
        }

        assertTrue(atomCount > 0);
    }

    private void startSubscription(ShellConfig.ShellSubscription config,
                                   CollectingByteOutputReceiver receiver, int waitTimeSec) {
        LogUtil.CLog.d("Uploading the following config:\n" + config.toString());
        try {
            File configFile = File.createTempFile("shellconfig", ".config");
            configFile.deleteOnExit();
            int length = config.toByteArray().length;
            byte[] combined = new byte[sizetBytes + config.toByteArray().length];

            System.arraycopy(IntToByteArrayLittleEndian(length), 0, combined, 0, sizetBytes);
            System.arraycopy(config.toByteArray(), 0, combined, sizetBytes, length);

            Files.write(combined, configFile);
            String remotePath = "/data/local/tmp/" + configFile.getName();
            getDevice().pushFile(configFile, remotePath);
            LogUtil.CLog.d("waiting....................");

            getDevice().executeShellCommand(
                    String.join(" ", "cat", remotePath, "|", "cmd stats data-subscribe ",
                            String.valueOf(waitTimeSec)), receiver);
            getDevice().executeShellCommand("rm " + remotePath);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    byte[] IntToByteArrayLittleEndian(int length) {
        ByteBuffer b = ByteBuffer.allocate(sizetBytes);
        b.order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(length);
        return b.array();
    }
}
