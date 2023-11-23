/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.cts;

import static android.app.AppOpsManager.OP_MANAGE_IPSEC_TUNNELS;
import static android.net.IpSecManager.UdpEncapsulationSocket;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.cts.PacketUtils.AES_CBC_BLK_SIZE;
import static android.net.cts.PacketUtils.AES_CBC_IV_LEN;
import static android.net.cts.PacketUtils.BytePayload;
import static android.net.cts.PacketUtils.EspHeader;
import static android.net.cts.PacketUtils.IP4_HDRLEN;
import static android.net.cts.PacketUtils.IP6_HDRLEN;
import static android.net.cts.PacketUtils.Ip4Header;
import static android.net.cts.PacketUtils.Ip6Header;
import static android.net.cts.PacketUtils.IpHeader;
import static android.net.cts.PacketUtils.UDP_HDRLEN;
import static android.net.cts.PacketUtils.UdpHeader;
import static android.system.OsConstants.AF_INET;
import static android.system.OsConstants.AF_INET6;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.LinkAddress;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.TestNetworkInterface;
import android.net.TestNetworkManager;
import android.net.cts.PacketUtils.Payload;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemProperties;
import android.platform.test.annotations.AppModeFull;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "MANAGE_TEST_NETWORKS permission can't be granted to instant apps")
public class IpSecManagerTunnelTest extends IpSecBaseTest {
    private static final String TAG = IpSecManagerTunnelTest.class.getSimpleName();

    private static final InetAddress LOCAL_OUTER_4 = InetAddress.parseNumericAddress("192.0.2.1");
    private static final InetAddress REMOTE_OUTER_4 = InetAddress.parseNumericAddress("192.0.2.2");
    private static final InetAddress LOCAL_OUTER_6 =
            InetAddress.parseNumericAddress("2001:db8:1::1");
    private static final InetAddress REMOTE_OUTER_6 =
            InetAddress.parseNumericAddress("2001:db8:1::2");

    private static final InetAddress LOCAL_INNER_4 =
            InetAddress.parseNumericAddress("198.51.100.1");
    private static final InetAddress REMOTE_INNER_4 =
            InetAddress.parseNumericAddress("198.51.100.2");
    private static final InetAddress LOCAL_INNER_6 =
            InetAddress.parseNumericAddress("2001:db8:2::1");
    private static final InetAddress REMOTE_INNER_6 =
            InetAddress.parseNumericAddress("2001:db8:2::2");

    private static final int IP4_PREFIX_LEN = 32;
    private static final int IP6_PREFIX_LEN = 128;

    private static final int TIMEOUT_MS = 500;

    // Static state to reduce setup/teardown
    private static ConnectivityManager sCM;
    private static TestNetworkManager sTNM;
    private static ParcelFileDescriptor sTunFd;
    private static TestNetworkCallback sTunNetworkCallback;
    private static Network sTunNetwork;
    private static TunUtils sTunUtils;

    private static Context sContext = InstrumentationRegistry.getContext();
    private static IBinder sBinder = new Binder();

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .adoptShellPermissionIdentity();
        sCM = (ConnectivityManager) sContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        sTNM = (TestNetworkManager) sContext.getSystemService(Context.TEST_NETWORK_SERVICE);

        // Under normal circumstances, the MANAGE_IPSEC_TUNNELS appop would be auto-granted, and
        // a standard permission is insufficient. So we shell out the appop, to give us the
        // right appop permissions.
        setAppop(OP_MANAGE_IPSEC_TUNNELS, true);

        TestNetworkInterface testIface =
                sTNM.createTunInterface(
                        new LinkAddress[] {
                            new LinkAddress(LOCAL_OUTER_4, IP4_PREFIX_LEN),
                            new LinkAddress(LOCAL_OUTER_6, IP6_PREFIX_LEN)
                        });

        sTunFd = testIface.getFileDescriptor();
        sTunNetworkCallback = setupAndGetTestNetwork(testIface.getInterfaceName());
        sTunNetwork = sTunNetworkCallback.getNetworkBlocking();

        sTunUtils = new TunUtils(sTunFd);
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // Set to true before every run; some tests flip this.
        setAppop(OP_MANAGE_IPSEC_TUNNELS, true);

        // Clear sTunUtils state
        sTunUtils.reset();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        setAppop(OP_MANAGE_IPSEC_TUNNELS, false);

        sCM.unregisterNetworkCallback(sTunNetworkCallback);

        sTNM.teardownTestNetwork(sTunNetwork);
        sTunFd.close();

        InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .dropShellPermissionIdentity();
    }

    private static boolean hasTunnelsFeature() {
        return sContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
                || SystemProperties.getInt("ro.product.first_api_level", 0)
                        >= Build.VERSION_CODES.Q;
    }

    private static void setAppop(int appop, boolean allow) {
        String opName = AppOpsManager.opToName(appop);
        for (String pkg : new String[] {"com.android.shell", sContext.getPackageName()}) {
            String cmd =
                    String.format(
                            "appops set %s %s %s",
                            pkg, // Package name
                            opName, // Appop
                            (allow ? "allow" : "deny")); // Action
            SystemUtil.runShellCommand(cmd);
        }
    }

    private static TestNetworkCallback setupAndGetTestNetwork(String ifname) throws Exception {
        // Build a network request
        NetworkRequest nr =
                new NetworkRequest.Builder()
                        .addTransportType(TRANSPORT_TEST)
                        .removeCapability(NET_CAPABILITY_TRUSTED)
                        .removeCapability(NET_CAPABILITY_NOT_VPN)
                        .setNetworkSpecifier(ifname)
                        .build();

        TestNetworkCallback cb = new TestNetworkCallback();
        sCM.requestNetwork(nr, cb);

        // Setup the test network after network request is filed to prevent Network from being
        // reaped due to no requests matching it.
        sTNM.setupTestNetwork(ifname, sBinder);

        return cb;
    }

    @Test
    public void testSecurityExceptionCreateTunnelInterfaceWithoutAppop() throws Exception {
        if (!hasTunnelsFeature()) return;

        // Ensure we don't have the appop. Permission is not requested in the Manifest
        setAppop(OP_MANAGE_IPSEC_TUNNELS, false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try {
            mISM.createIpSecTunnelInterface(LOCAL_INNER_6, REMOTE_INNER_6, sTunNetwork);
            fail("Did not throw SecurityException for Tunnel creation without appop");
        } catch (SecurityException expected) {
        }
    }

    @Test
    public void testSecurityExceptionBuildTunnelTransformWithoutAppop() throws Exception {
        if (!hasTunnelsFeature()) return;

        // Ensure we don't have the appop. Permission is not requested in the Manifest
        setAppop(OP_MANAGE_IPSEC_TUNNELS, false);

        // Security exceptions are thrown regardless of IPv4/IPv6. Just test one
        try (IpSecManager.SecurityParameterIndex spi =
                        mISM.allocateSecurityParameterIndex(LOCAL_INNER_4);
                IpSecTransform transform =
                        new IpSecTransform.Builder(sContext)
                                .buildTunnelModeTransform(REMOTE_INNER_4, spi)) {
            fail("Did not throw SecurityException for Transform creation without appop");
        } catch (SecurityException expected) {
        }
    }

    /* Test runnables for callbacks after IPsec tunnels are set up. */
    private abstract class IpSecTunnelTestRunnable {
        /**
         * Runs the test code, and returns the inner socket port, if any.
         *
         * @param ipsecNetwork The IPsec Interface based Network for binding sockets on
         * @return the integer port of the inner socket if outbound, or 0 if inbound
         *     IpSecTunnelTestRunnable
         * @throws Exception if any part of the test failed.
         */
        public abstract int run(Network ipsecNetwork) throws Exception;
    }

    private static class TestNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final CompletableFuture<Network> futureNetwork = new CompletableFuture<>();

        @Override
        public void onAvailable(Network network) {
            futureNetwork.complete(network);
        }

        public Network getNetworkBlocking() throws Exception {
            return futureNetwork.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private int getPacketSize(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode) {
        int expectedPacketSize = TEST_DATA.length + UDP_HDRLEN;

        // Inner Transport mode packet size
        if (transportInTunnelMode) {
            expectedPacketSize =
                    PacketUtils.calculateEspPacketSize(
                            expectedPacketSize,
                            AES_CBC_IV_LEN,
                            AES_CBC_BLK_SIZE,
                            AUTH_KEY.length * 4);
        }

        // Inner IP Header
        expectedPacketSize += innerFamily == AF_INET ? IP4_HDRLEN : IP6_HDRLEN;

        // Tunnel mode transform size
        expectedPacketSize =
                PacketUtils.calculateEspPacketSize(
                        expectedPacketSize, AES_CBC_IV_LEN, AES_CBC_BLK_SIZE, AUTH_KEY.length * 4);

        // UDP encap size
        expectedPacketSize += useEncap ? UDP_HDRLEN : 0;

        // Outer IP Header
        expectedPacketSize += outerFamily == AF_INET ? IP4_HDRLEN : IP6_HDRLEN;

        return expectedPacketSize;
    }

    private interface IpSecTunnelTestRunnableFactory {
        IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                InetAddress localOuter,
                InetAddress remoteOuter,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int innerSocketPort,
                int expectedPacketSize)
                throws Exception;
    }

    private class OutputIpSecTunnelTestRunnableFactory implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                InetAddress localOuter,
                InetAddress remoteOuter,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int unusedInnerSocketPort,
                int expectedPacketSize) {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(Network ipsecNetwork) throws Exception {
                    // Build a socket and send traffic
                    JavaUdpSocket socket = new JavaUdpSocket(localInner);
                    ipsecNetwork.bindSocket(socket.mSocket);
                    int innerSocketPort = socket.getPort();

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_IN, inTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_OUT, outTransportTransform);
                    }

                    socket.sendTo(TEST_DATA, remoteInner, socket.getPort());

                    // Verify that an encrypted packet is sent. As of right now, checking encrypted
                    // body is not possible, due to the test not knowing some of the fields of the
                    // inner IP header (flow label, flags, etc)
                    sTunUtils.awaitEspPacketNoPlaintext(
                            spi, TEST_DATA, encapPort != 0, expectedPacketSize);

                    socket.close();

                    return innerSocketPort;
                }
            };
        }
    }

    private class InputReflectedIpSecTunnelTestRunnableFactory
            implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                InetAddress localOuter,
                InetAddress remoteOuter,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int innerSocketPort,
                int expectedPacketSize)
                throws Exception {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(Network ipsecNetwork) throws Exception {
                    // Build a socket and receive traffic
                    JavaUdpSocket socket = new JavaUdpSocket(localInner, innerSocketPort);
                    ipsecNetwork.bindSocket(socket.mSocket);

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_IN, outTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_OUT, inTransportTransform);
                    }

                    sTunUtils.reflectPackets();

                    // Receive packet from socket, and validate that the payload is correct
                    receiveAndValidatePacket(socket);

                    socket.close();

                    return 0;
                }
            };
        }
    }

    private class InputPacketGeneratorIpSecTunnelTestRunnableFactory
            implements IpSecTunnelTestRunnableFactory {
        public IpSecTunnelTestRunnable getIpSecTunnelTestRunnable(
                boolean transportInTunnelMode,
                int spi,
                InetAddress localInner,
                InetAddress remoteInner,
                InetAddress localOuter,
                InetAddress remoteOuter,
                IpSecTransform inTransportTransform,
                IpSecTransform outTransportTransform,
                int encapPort,
                int innerSocketPort,
                int expectedPacketSize)
                throws Exception {
            return new IpSecTunnelTestRunnable() {
                @Override
                public int run(Network ipsecNetwork) throws Exception {
                    // Build a socket and receive traffic
                    JavaUdpSocket socket = new JavaUdpSocket(localInner);
                    ipsecNetwork.bindSocket(socket.mSocket);

                    // For Transport-In-Tunnel mode, apply transform to socket
                    if (transportInTunnelMode) {
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_IN, outTransportTransform);
                        mISM.applyTransportModeTransform(
                                socket.mSocket, IpSecManager.DIRECTION_OUT, inTransportTransform);
                    }

                    byte[] pkt;
                    if (transportInTunnelMode) {
                        pkt =
                                getTransportInTunnelModePacket(
                                        spi,
                                        spi,
                                        remoteInner,
                                        localInner,
                                        remoteOuter,
                                        localOuter,
                                        socket.getPort(),
                                        encapPort);
                    } else {
                        pkt =
                                getTunnelModePacket(
                                        spi,
                                        remoteInner,
                                        localInner,
                                        remoteOuter,
                                        localOuter,
                                        socket.getPort(),
                                        encapPort);
                    }
                    sTunUtils.injectPacket(pkt);

                    // Receive packet from socket, and validate
                    receiveAndValidatePacket(socket);

                    socket.close();

                    return 0;
                }
            };
        }
    }

    private void checkTunnelOutput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new OutputIpSecTunnelTestRunnableFactory());
    }

    private void checkTunnelInput(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        checkTunnel(
                innerFamily,
                outerFamily,
                useEncap,
                transportInTunnelMode,
                new InputPacketGeneratorIpSecTunnelTestRunnableFactory());
    }

    /**
     * Validates that the kernel can talk to itself.
     *
     * <p>This test takes an outbound IPsec packet, reflects it (by flipping IP src/dst), and
     * injects it back into the TUN. This test then verifies that a packet with the correct payload
     * is found on the specified socket/port.
     */
    public void checkTunnelReflected(
            int innerFamily, int outerFamily, boolean useEncap, boolean transportInTunnelMode)
            throws Exception {
        if (!hasTunnelsFeature()) return;

        InetAddress localInner = innerFamily == AF_INET ? LOCAL_INNER_4 : LOCAL_INNER_6;
        InetAddress remoteInner = innerFamily == AF_INET ? REMOTE_INNER_4 : REMOTE_INNER_6;

        InetAddress localOuter = outerFamily == AF_INET ? LOCAL_OUTER_4 : LOCAL_OUTER_6;
        InetAddress remoteOuter = outerFamily == AF_INET ? REMOTE_OUTER_4 : REMOTE_OUTER_6;

        // Preselect both SPI and encap port, to be used for both inbound and outbound tunnels.
        int spi = getRandomSpi(localOuter, remoteOuter);
        int expectedPacketSize =
                getPacketSize(innerFamily, outerFamily, useEncap, transportInTunnelMode);

        try (IpSecManager.SecurityParameterIndex inTransportSpi =
                        mISM.allocateSecurityParameterIndex(localInner, spi);
                IpSecManager.SecurityParameterIndex outTransportSpi =
                        mISM.allocateSecurityParameterIndex(remoteInner, spi);
                IpSecTransform inTransportTransform =
                        buildIpSecTransform(sContext, inTransportSpi, null, remoteInner);
                IpSecTransform outTransportTransform =
                        buildIpSecTransform(sContext, outTransportSpi, null, localInner);
                UdpEncapsulationSocket encapSocket = mISM.openUdpEncapsulationSocket()) {

            // Run output direction tests
            IpSecTunnelTestRunnable outputIpSecTunnelTestRunnable =
                    new OutputIpSecTunnelTestRunnableFactory()
                            .getIpSecTunnelTestRunnable(
                                    transportInTunnelMode,
                                    spi,
                                    localInner,
                                    remoteInner,
                                    localOuter,
                                    remoteOuter,
                                    inTransportTransform,
                                    outTransportTransform,
                                    useEncap ? encapSocket.getPort() : 0,
                                    0,
                                    expectedPacketSize);
            int innerSocketPort =
                    buildTunnelNetworkAndRunTests(
                    localInner,
                    remoteInner,
                    localOuter,
                    remoteOuter,
                    spi,
                    useEncap ? encapSocket : null,
                    outputIpSecTunnelTestRunnable);

            // Input direction tests, with matching inner socket ports.
            IpSecTunnelTestRunnable inputIpSecTunnelTestRunnable =
                    new InputReflectedIpSecTunnelTestRunnableFactory()
                            .getIpSecTunnelTestRunnable(
                                    transportInTunnelMode,
                                    spi,
                                    remoteInner,
                                    localInner,
                                    localOuter,
                                    remoteOuter,
                                    inTransportTransform,
                                    outTransportTransform,
                                    useEncap ? encapSocket.getPort() : 0,
                                    innerSocketPort,
                                    expectedPacketSize);
            buildTunnelNetworkAndRunTests(
                    remoteInner,
                    localInner,
                    localOuter,
                    remoteOuter,
                    spi,
                    useEncap ? encapSocket : null,
                    inputIpSecTunnelTestRunnable);
        }
    }

    public void checkTunnel(
            int innerFamily,
            int outerFamily,
            boolean useEncap,
            boolean transportInTunnelMode,
            IpSecTunnelTestRunnableFactory factory)
            throws Exception {
        if (!hasTunnelsFeature()) return;

        InetAddress localInner = innerFamily == AF_INET ? LOCAL_INNER_4 : LOCAL_INNER_6;
        InetAddress remoteInner = innerFamily == AF_INET ? REMOTE_INNER_4 : REMOTE_INNER_6;

        InetAddress localOuter = outerFamily == AF_INET ? LOCAL_OUTER_4 : LOCAL_OUTER_6;
        InetAddress remoteOuter = outerFamily == AF_INET ? REMOTE_OUTER_4 : REMOTE_OUTER_6;

        // Preselect both SPI and encap port, to be used for both inbound and outbound tunnels.
        // Re-uses the same SPI to ensure that even in cases of symmetric SPIs shared across tunnel
        // and transport mode, packets are encrypted/decrypted properly based on the src/dst.
        int spi = getRandomSpi(localOuter, remoteOuter);
        int expectedPacketSize =
                getPacketSize(innerFamily, outerFamily, useEncap, transportInTunnelMode);

        try (IpSecManager.SecurityParameterIndex inTransportSpi =
                        mISM.allocateSecurityParameterIndex(localInner, spi);
                IpSecManager.SecurityParameterIndex outTransportSpi =
                        mISM.allocateSecurityParameterIndex(remoteInner, spi);
                IpSecTransform inTransportTransform =
                        buildIpSecTransform(sContext, inTransportSpi, null, remoteInner);
                IpSecTransform outTransportTransform =
                        buildIpSecTransform(sContext, outTransportSpi, null, localInner);
                UdpEncapsulationSocket encapSocket = mISM.openUdpEncapsulationSocket()) {

            buildTunnelNetworkAndRunTests(
                    localInner,
                    remoteInner,
                    localOuter,
                    remoteOuter,
                    spi,
                    useEncap ? encapSocket : null,
                    factory.getIpSecTunnelTestRunnable(
                            transportInTunnelMode,
                            spi,
                            localInner,
                            remoteInner,
                            localOuter,
                            remoteOuter,
                            inTransportTransform,
                            outTransportTransform,
                            useEncap ? encapSocket.getPort() : 0,
                            0,
                            expectedPacketSize));
        }
    }

    private int buildTunnelNetworkAndRunTests(
            InetAddress localInner,
            InetAddress remoteInner,
            InetAddress localOuter,
            InetAddress remoteOuter,
            int spi,
            UdpEncapsulationSocket encapSocket,
            IpSecTunnelTestRunnable test)
            throws Exception {
        int innerPrefixLen = localInner instanceof Inet6Address ? IP6_PREFIX_LEN : IP4_PREFIX_LEN;
        TestNetworkCallback testNetworkCb = null;
        int innerSocketPort;

        try (IpSecManager.SecurityParameterIndex inSpi =
                        mISM.allocateSecurityParameterIndex(localOuter, spi);
                IpSecManager.SecurityParameterIndex outSpi =
                        mISM.allocateSecurityParameterIndex(remoteOuter, spi);
                IpSecManager.IpSecTunnelInterface tunnelIface =
                        mISM.createIpSecTunnelInterface(localOuter, remoteOuter, sTunNetwork)) {
            // Build the test network
            tunnelIface.addAddress(localInner, innerPrefixLen);
            testNetworkCb = setupAndGetTestNetwork(tunnelIface.getInterfaceName());
            Network testNetwork = testNetworkCb.getNetworkBlocking();

            // Check interface was created
            assertNotNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));

            // Verify address was added
            final NetworkInterface netIface = NetworkInterface.getByInetAddress(localInner);
            assertNotNull(netIface);
            assertEquals(tunnelIface.getInterfaceName(), netIface.getDisplayName());

            // Configure Transform parameters
            IpSecTransform.Builder transformBuilder = new IpSecTransform.Builder(sContext);
            transformBuilder.setEncryption(
                    new IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, CRYPT_KEY));
            transformBuilder.setAuthentication(
                    new IpSecAlgorithm(
                            IpSecAlgorithm.AUTH_HMAC_SHA256, AUTH_KEY, AUTH_KEY.length * 4));

            if (encapSocket != null) {
                transformBuilder.setIpv4Encapsulation(encapSocket, encapSocket.getPort());
            }

            // Apply transform and check that traffic is properly encrypted
            try (IpSecTransform inTransform =
                            transformBuilder.buildTunnelModeTransform(remoteOuter, inSpi);
                    IpSecTransform outTransform =
                            transformBuilder.buildTunnelModeTransform(localOuter, outSpi)) {
                mISM.applyTunnelModeTransform(tunnelIface, IpSecManager.DIRECTION_IN, inTransform);
                mISM.applyTunnelModeTransform(
                        tunnelIface, IpSecManager.DIRECTION_OUT, outTransform);

                innerSocketPort = test.run(testNetwork);
            }

            // Teardown the test network
            sTNM.teardownTestNetwork(testNetwork);

            // Remove addresses and check that interface is still present, but fails lookup-by-addr
            tunnelIface.removeAddress(localInner, innerPrefixLen);
            assertNotNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));
            assertNull(NetworkInterface.getByInetAddress(localInner));

            // Check interface was cleaned up
            tunnelIface.close();
            assertNull(NetworkInterface.getByName(tunnelIface.getInterfaceName()));
        } finally {
            if (testNetworkCb != null) {
                sCM.unregisterNetworkCallback(testNetworkCb);
            }
        }

        return innerSocketPort;
    }

    private static void receiveAndValidatePacket(JavaUdpSocket socket) throws Exception {
        byte[] socketResponseBytes = socket.receive();
        assertArrayEquals(TEST_DATA, socketResponseBytes);
    }

    private int getRandomSpi(InetAddress localOuter, InetAddress remoteOuter) throws Exception {
        // Try to allocate both in and out SPIs using the same requested SPI value.
        try (IpSecManager.SecurityParameterIndex inSpi =
                        mISM.allocateSecurityParameterIndex(localOuter);
                IpSecManager.SecurityParameterIndex outSpi =
                        mISM.allocateSecurityParameterIndex(remoteOuter, inSpi.getSpi()); ) {
            return inSpi.getSpi();
        }
    }

    private IpHeader getIpHeader(int protocol, InetAddress src, InetAddress dst, Payload payload) {
        if ((src instanceof Inet6Address) != (dst instanceof Inet6Address)) {
            throw new IllegalArgumentException("Invalid src/dst address combination");
        }

        if (src instanceof Inet6Address) {
            return new Ip6Header(protocol, (Inet6Address) src, (Inet6Address) dst, payload);
        } else {
            return new Ip4Header(protocol, (Inet4Address) src, (Inet4Address) dst, payload);
        }
    }

    private EspHeader buildTransportModeEspPacket(
            int spi, InetAddress src, InetAddress dst, int port, Payload payload) throws Exception {
        IpHeader preEspIpHeader = getIpHeader(payload.getProtocolId(), src, dst, payload);

        return new EspHeader(
                payload.getProtocolId(),
                spi,
                1, // sequence number
                CRYPT_KEY, // Same key for auth and crypt
                payload.getPacketBytes(preEspIpHeader));
    }

    private EspHeader buildTunnelModeEspPacket(
            int spi,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort,
            Payload payload)
            throws Exception {
        IpHeader innerIp = getIpHeader(payload.getProtocolId(), srcInner, dstInner, payload);
        return new EspHeader(
                innerIp.getProtocolId(),
                spi,
                1, // sequence number
                CRYPT_KEY, // Same key for auth and crypt
                innerIp.getPacketBytes());
    }

    private IpHeader maybeEncapPacket(
            InetAddress src, InetAddress dst, int encapPort, EspHeader espPayload)
            throws Exception {

        Payload payload = espPayload;
        if (encapPort != 0) {
            payload = new UdpHeader(encapPort, encapPort, espPayload);
        }

        return getIpHeader(payload.getProtocolId(), src, dst, payload);
    }

    private byte[] getTunnelModePacket(
            int spi,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort)
            throws Exception {
        UdpHeader udp = new UdpHeader(port, port, new BytePayload(TEST_DATA));

        EspHeader espPayload =
                buildTunnelModeEspPacket(
                        spi, srcInner, dstInner, srcOuter, dstOuter, port, encapPort, udp);
        return maybeEncapPacket(srcOuter, dstOuter, encapPort, espPayload).getPacketBytes();
    }

    private byte[] getTransportInTunnelModePacket(
            int spiInner,
            int spiOuter,
            InetAddress srcInner,
            InetAddress dstInner,
            InetAddress srcOuter,
            InetAddress dstOuter,
            int port,
            int encapPort)
            throws Exception {
        UdpHeader udp = new UdpHeader(port, port, new BytePayload(TEST_DATA));

        EspHeader espPayload = buildTransportModeEspPacket(spiInner, srcInner, dstInner, port, udp);
        espPayload =
                buildTunnelModeEspPacket(
                        spiOuter,
                        srcInner,
                        dstInner,
                        srcOuter,
                        dstOuter,
                        port,
                        encapPort,
                        espPayload);
        return maybeEncapPacket(srcOuter, dstOuter, encapPort, espPayload).getPacketBytes();
    }

    // Transport-in-Tunnel mode tests
    @Test
    public void testTransportInTunnelModeV4InV4() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET, false, true);
        checkTunnelInput(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4UdpEncap() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET, true, true);
        checkTunnelInput(AF_INET, AF_INET, true, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV4UdpEncapReflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV6() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET6, false, true);
        checkTunnelInput(AF_INET, AF_INET6, false, true);
    }

    @Test
    public void testTransportInTunnelModeV4InV6Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4() throws Exception {
        checkTunnelOutput(AF_INET6, AF_INET, false, true);
        checkTunnelInput(AF_INET6, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4UdpEncap() throws Exception {
        checkTunnelOutput(AF_INET6, AF_INET, true, true);
        checkTunnelInput(AF_INET6, AF_INET, true, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV4UdpEncapReflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV6() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET6, false, true);
        checkTunnelInput(AF_INET, AF_INET6, false, true);
    }

    @Test
    public void testTransportInTunnelModeV6InV6Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, true);
    }

    // Tunnel mode tests
    @Test
    public void testTunnelV4InV4() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET, false, false);
        checkTunnelInput(AF_INET, AF_INET, false, false);
    }

    @Test
    public void testTunnelV4InV4Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, false, false);
    }

    @Test
    public void testTunnelV4InV4UdpEncap() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET, true, false);
        checkTunnelInput(AF_INET, AF_INET, true, false);
    }

    @Test
    public void testTunnelV4InV4UdpEncapReflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET, true, false);
    }

    @Test
    public void testTunnelV4InV6() throws Exception {
        checkTunnelOutput(AF_INET, AF_INET6, false, false);
        checkTunnelInput(AF_INET, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV4InV6Reflected() throws Exception {
        checkTunnelReflected(AF_INET, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV6InV4() throws Exception {
        checkTunnelOutput(AF_INET6, AF_INET, false, false);
        checkTunnelInput(AF_INET6, AF_INET, false, false);
    }

    @Test
    public void testTunnelV6InV4Reflected() throws Exception {
        checkTunnelReflected(AF_INET6, AF_INET, false, false);
    }

    @Test
    public void testTunnelV6InV4UdpEncap() throws Exception {
        checkTunnelOutput(AF_INET6, AF_INET, true, false);
        checkTunnelInput(AF_INET6, AF_INET, true, false);
    }

    @Test
    public void testTunnelV6InV4UdpEncapReflected() throws Exception {
        checkTunnelReflected(AF_INET6, AF_INET, true, false);
    }

    @Test
    public void testTunnelV6InV6() throws Exception {
        checkTunnelOutput(AF_INET6, AF_INET6, false, false);
        checkTunnelInput(AF_INET6, AF_INET6, false, false);
    }

    @Test
    public void testTunnelV6InV6Reflected() throws Exception {
        checkTunnelReflected(AF_INET6, AF_INET6, false, false);
    }
}
