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
 * limitations under the License
 */

package com.android.server.connectivity.mdns;

import static com.android.testutils.DevSdkIgnoreRuleKt.SC_V2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.InetAddresses;
import android.net.Network;
import android.text.TextUtils;

import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.MdnsServiceInfo.TextEntry;
import com.android.server.connectivity.mdns.MdnsServiceTypeClient.QueryTaskConfig;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Tests for {@link MdnsServiceTypeClient}. */
@RunWith(DevSdkIgnoreRunner.class)
@DevSdkIgnoreRule.IgnoreUpTo(SC_V2)
public class MdnsServiceTypeClientTests {
    private static final int INTERFACE_INDEX = 999;
    private static final String SERVICE_TYPE = "_googlecast._tcp.local";
    private static final String[] SERVICE_TYPE_LABELS = TextUtils.split(SERVICE_TYPE, "\\.");
    private static final InetSocketAddress IPV4_ADDRESS = new InetSocketAddress(
            MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);
    private static final InetSocketAddress IPV6_ADDRESS = new InetSocketAddress(
            MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);

    private static final long TEST_TTL = 120000L;
    private static final long TEST_ELAPSED_REALTIME = 123L;
    private static final long TEST_TIMEOUT_MS = 10_000L;

    @Mock
    private MdnsServiceBrowserListener mockListenerOne;
    @Mock
    private MdnsServiceBrowserListener mockListenerTwo;
    @Mock
    private MdnsPacketWriter mockPacketWriter;
    @Mock
    private MdnsMultinetworkSocketClient mockSocketClient;
    @Mock
    private Network mockNetwork;
    @Mock
    private MdnsResponseDecoder.Clock mockDecoderClock;
    @Mock
    private SharedLog mockSharedLog;
    @Captor
    private ArgumentCaptor<MdnsServiceInfo> serviceInfoCaptor;

    private final byte[] buf = new byte[10];

    private DatagramPacket[] expectedIPv4Packets;
    private DatagramPacket[] expectedIPv6Packets;
    private ScheduledFuture<?>[] expectedSendFutures;
    private FakeExecutor currentThreadExecutor = new FakeExecutor();

    private MdnsServiceTypeClient client;

    @Before
    @SuppressWarnings("DoNotMock")
    public void setUp() throws IOException {
        MockitoAnnotations.initMocks(this);
        doReturn(TEST_ELAPSED_REALTIME).when(mockDecoderClock).elapsedRealtime();

        expectedIPv4Packets = new DatagramPacket[16];
        expectedIPv6Packets = new DatagramPacket[16];
        expectedSendFutures = new ScheduledFuture<?>[16];

        for (int i = 0; i < expectedSendFutures.length; ++i) {
            expectedIPv4Packets[i] = new DatagramPacket(buf, 0 /* offset */, 5 /* length */,
                    MdnsConstants.getMdnsIPv4Address(), MdnsConstants.MDNS_PORT);
            expectedIPv6Packets[i] = new DatagramPacket(buf, 0 /* offset */, 5 /* length */,
                    MdnsConstants.getMdnsIPv6Address(), MdnsConstants.MDNS_PORT);
            expectedSendFutures[i] = Mockito.mock(ScheduledFuture.class);
        }
        when(mockPacketWriter.getPacket(IPV4_ADDRESS))
                .thenReturn(expectedIPv4Packets[0])
                .thenReturn(expectedIPv4Packets[1])
                .thenReturn(expectedIPv4Packets[2])
                .thenReturn(expectedIPv4Packets[3])
                .thenReturn(expectedIPv4Packets[4])
                .thenReturn(expectedIPv4Packets[5])
                .thenReturn(expectedIPv4Packets[6])
                .thenReturn(expectedIPv4Packets[7])
                .thenReturn(expectedIPv4Packets[8])
                .thenReturn(expectedIPv4Packets[9])
                .thenReturn(expectedIPv4Packets[10])
                .thenReturn(expectedIPv4Packets[11])
                .thenReturn(expectedIPv4Packets[12])
                .thenReturn(expectedIPv4Packets[13])
                .thenReturn(expectedIPv4Packets[14])
                .thenReturn(expectedIPv4Packets[15]);

        when(mockPacketWriter.getPacket(IPV6_ADDRESS))
                .thenReturn(expectedIPv6Packets[0])
                .thenReturn(expectedIPv6Packets[1])
                .thenReturn(expectedIPv6Packets[2])
                .thenReturn(expectedIPv6Packets[3])
                .thenReturn(expectedIPv6Packets[4])
                .thenReturn(expectedIPv6Packets[5])
                .thenReturn(expectedIPv6Packets[6])
                .thenReturn(expectedIPv6Packets[7])
                .thenReturn(expectedIPv6Packets[8])
                .thenReturn(expectedIPv6Packets[9])
                .thenReturn(expectedIPv6Packets[10])
                .thenReturn(expectedIPv6Packets[11])
                .thenReturn(expectedIPv6Packets[12])
                .thenReturn(expectedIPv6Packets[13])
                .thenReturn(expectedIPv6Packets[14])
                .thenReturn(expectedIPv6Packets[15]);

        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor,
                        mockDecoderClock, mockNetwork, mockSharedLog) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
    }

    @Test
    public void sendQueries_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, 3 queries.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after initialTimeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(
                3, MdnsConfigs.initialTimeBetweenBurstsMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                4, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                5, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Third burst will be sent after initialTimeBetweenBurstsMs * 2, 3 queries.
        verifyAndSendQuery(
                6, MdnsConfigs.initialTimeBetweenBurstsMs() * 2, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                7, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                8, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Forth burst will be sent after initialTimeBetweenBurstsMs * 4, 3 queries.
        verifyAndSendQuery(
                9, MdnsConfigs.initialTimeBetweenBurstsMs() * 4, /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                10, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                11, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Fifth burst will be sent after timeBetweenBurstsMs, 3 queries.
        verifyAndSendQuery(12, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        verifyAndSendQuery(
                13, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                14, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[15]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_activeScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(false)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // The previous scheduled task should be canceled.
        verify(expectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, 3 query.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                1, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        // Second burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(3, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);
        // Third burst will be sent after timeBetweenBurstsMs, 1 query.
        verifyAndSendQuery(4, MdnsConfigs.timeBetweenBurstsMs(), /* expectsUnicastResponse= */
                false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    public void sendQueries_reentry_passiveScanMode() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // First burst, first query is sent.
        verifyAndSendQuery(0, 0, /* expectsUnicastResponse= */ true);

        // After the first query is sent, change the subtypes, and restart.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // The previous scheduled task should be canceled.
        verify(expectedSendFutures[1]).cancel(true);

        // Queries should continue to be sent.
        verifyAndSendQuery(1, 0, /* expectsUnicastResponse= */ true);
        verifyAndSendQuery(
                2, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);
        verifyAndSendQuery(
                3, MdnsConfigs.timeBetweenQueriesInBurstMs(), /* expectsUnicastResponse= */ false);

        // Stop sending packets.
        client.stopSendAndReceive(mockListenerOne);
        verify(expectedSendFutures[5]).cancel(true);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testQueryTaskConfig_alwaysAskForUnicastResponse() {
        //MdnsConfigsFlagsImpl.alwaysAskForUnicastResponseInEachBurst.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config = new QueryTaskConfig(
                searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1, mockNetwork);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    public void testQueryTaskConfig_askForUnicastInFirstQuery() {
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(false).build();
        QueryTaskConfig config = new QueryTaskConfig(
                searchOptions.getSubtypes(), searchOptions.isPassiveMode(), 1, mockNetwork);

        // This is the first query. We will ask for unicast response.
        assertTrue(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, 1);

        // For the rest of queries in this burst, we will NOT ask for unicast response.
        for (int i = 1; i < MdnsConfigs.queriesPerBurst(); i++) {
            int oldTransactionId = config.transactionId;
            config = config.getConfigForNextRun();
            assertFalse(config.expectUnicastResponse);
            assertEquals(config.subtypes, searchOptions.getSubtypes());
            assertEquals(config.transactionId, oldTransactionId + 1);
        }

        // This is the first query of a new burst. We will NOT ask for unicast response.
        int oldTransactionId = config.transactionId;
        config = config.getConfigForNextRun();
        assertFalse(config.expectUnicastResponse);
        assertEquals(config.subtypes, searchOptions.getSubtypes());
        assertEquals(config.transactionId, oldTransactionId + 1);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenNewSessionStarts() {
        //MdnsConfigsFlagsImpl.useSessionIdToScheduleMdnsTask.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Change the sutypes and start a new session.
        searchOptions =
                MdnsSearchOptions.newBuilder()
                        .addSubtype("12345")
                        .addSubtype("abcde")
                        .setIsPassiveMode(true)
                        .build();
        client.startSendAndReceive(mockListenerOne, searchOptions);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        firstMdnsTask.run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(currentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void testIfPreviousTaskIsCanceledWhenSessionStops() {
        //MdnsConfigsFlagsImpl.shouldCancelScanTaskWhenFutureIsNull.override(true);
        MdnsSearchOptions searchOptions =
                MdnsSearchOptions.newBuilder().addSubtype("12345").setIsPassiveMode(true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        // Change the sutypes and start a new session.
        client.stopSendAndReceive(mockListenerOne);
        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the first mdns task is not successful canceled and it gets
        // executed anyway.
        currentThreadExecutor.getAndClearSubmittedRunnable().run();

        // Although it gets executes, no more task gets scheduled.
        assertNull(currentThreadExecutor.getAndClearLastScheduledRunnable());
    }

    @Test
    public void testQueryScheduledWhenAnsweredFromCache() {
        final MdnsSearchOptions searchOptions = MdnsSearchOptions.getDefaultOptions();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        assertNotNull(currentThreadExecutor.getAndClearSubmittedRunnable());

        client.processResponse(createResponse(
                "service-instance-1", "192.0.2.123", 5353,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), TEST_TTL), /* interfaceIndex= */ 20, mockNetwork);

        verify(mockListenerOne).onServiceNameDiscovered(any());
        verify(mockListenerOne).onServiceFound(any());

        // File another identical query
        client.startSendAndReceive(mockListenerTwo, searchOptions);

        verify(mockListenerTwo).onServiceNameDiscovered(any());
        verify(mockListenerTwo).onServiceFound(any());

        // This time no query is submitted, only scheduled
        assertNull(currentThreadExecutor.getAndClearSubmittedRunnable());
        assertNotNull(currentThreadExecutor.getAndClearLastScheduledRunnable());
        // This just skips the first query of the first burst
        assertEquals(MdnsConfigs.timeBetweenQueriesInBurstMs(),
                currentThreadExecutor.getAndClearLastScheduledDelayInMs());
    }

    private static void verifyServiceInfo(MdnsServiceInfo serviceInfo, String serviceName,
            String[] serviceType, List<String> ipv4Addresses, List<String> ipv6Addresses, int port,
            List<String> subTypes, Map<String, String> attributes, int interfaceIndex,
            Network network) {
        assertEquals(serviceName, serviceInfo.getServiceInstanceName());
        assertArrayEquals(serviceType, serviceInfo.getServiceType());
        assertEquals(ipv4Addresses, serviceInfo.getIpv4Addresses());
        assertEquals(ipv6Addresses, serviceInfo.getIpv6Addresses());
        assertEquals(port, serviceInfo.getPort());
        assertEquals(subTypes, serviceInfo.getSubtypes());
        for (String key : attributes.keySet()) {
            assertTrue(attributes.containsKey(key));
            assertEquals(attributes.get(key), serviceInfo.getAttributeByKey(key));
        }
        assertEquals(interfaceIndex, serviceInfo.getInterfaceIndex());
        assertEquals(network, serviceInfo.getNetwork());
    }

    @Test
    public void processResponse_incompleteResponse() {
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        client.processResponse(createResponse(
                "service-instance-1", null /* host */, 0 /* port */,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                /* ipv4Addresses= */ List.of(),
                /* ipv6Addresses= */ List.of(),
                /* port= */ 0,
                /* subTypes= */ List.of(),
                Collections.emptyMap(),
                INTERFACE_INDEX,
                mockNetwork);

        verify(mockListenerOne, never()).onServiceFound(any(MdnsServiceInfo.class));
        verify(mockListenerOne, never()).onServiceUpdated(any(MdnsServiceInfo.class));
    }

    @Test
    public void processIPv4Response_completeResponseForNewServiceInstance() throws Exception {
        final String ipV4Address = "192.168.1.1";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        client.processResponse(createResponse(
                "service-instance-1", ipV4Address, 5353,
                /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), /* interfaceIndex= */ 20, mockNetwork);

        // Process a second response with a different port and updated text attributes.
        client.processResponse(createResponse(
                "service-instance-1", ipV4Address, 5354,
                /* subtype= */ "ABCDE",
                Collections.singletonMap("key", "value"), TEST_TTL),
                /* interfaceIndex= */ 20, mockNetwork);

        // Verify onServiceNameDiscovered was called once for the initial response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address) /* ipv4Address */,
                List.of() /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                20 /* interfaceIndex */,
                mockNetwork);

        // Verify onServiceFound was called once for the initial response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));
        assertEquals(initialServiceInfo.getInterfaceIndex(), 20);
        assertEquals(mockNetwork, initialServiceInfo.getNetwork());

        // Verify onServiceUpdated was called once for the second response.
        verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = serviceInfoCaptor.getAllValues().get(2);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv4Address(), ipV4Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
        assertEquals(updatedServiceInfo.getInterfaceIndex(), 20);
        assertEquals(mockNetwork, updatedServiceInfo.getNetwork());
    }

    @Test
    public void processIPv6Response_getCorrectServiceInfo() throws Exception {
        final String ipV6Address = "2000:3333::da6c:63ff:fe7c:7483";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Process the initial response.
        client.processResponse(createResponse(
                "service-instance-1", ipV6Address, 5353,
                /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), /* interfaceIndex= */ 20, mockNetwork);

        // Process a second response with a different port and updated text attributes.
        client.processResponse(createResponse(
                "service-instance-1", ipV6Address, 5354,
                /* subtype= */ "ABCDE",
                Collections.singletonMap("key", "value"), TEST_TTL),
                /* interfaceIndex= */ 20, mockNetwork);

        // Verify onServiceNameDiscovered was called once for the initial response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                List.of() /* ipv4Address */,
                List.of(ipV6Address) /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                20 /* interfaceIndex */,
                mockNetwork);

        // Verify onServiceFound was called once for the initial response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo initialServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(initialServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(initialServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(initialServiceInfo.getPort(), 5353);
        assertEquals(initialServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(initialServiceInfo.getAttributeByKey("key"));
        assertEquals(initialServiceInfo.getInterfaceIndex(), 20);
        assertEquals(mockNetwork, initialServiceInfo.getNetwork());

        // Verify onServiceUpdated was called once for the second response.
        verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        MdnsServiceInfo updatedServiceInfo = serviceInfoCaptor.getAllValues().get(2);
        assertEquals(updatedServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(updatedServiceInfo.getIpv6Address(), ipV6Address);
        assertEquals(updatedServiceInfo.getPort(), 5354);
        assertTrue(updatedServiceInfo.hasSubtypes());
        assertEquals(updatedServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertEquals(updatedServiceInfo.getAttributeByKey("key"), "value");
        assertEquals(updatedServiceInfo.getInterfaceIndex(), 20);
        assertEquals(mockNetwork, updatedServiceInfo.getNetwork());
    }

    private void verifyServiceRemovedNoCallback(MdnsServiceBrowserListener listener) {
        verify(listener, never()).onServiceRemoved(any());
        verify(listener, never()).onServiceNameRemoved(any());
    }

    private void verifyServiceRemovedCallback(MdnsServiceBrowserListener listener,
            String serviceName, String[] serviceType, int interfaceIndex, Network network) {
        verify(listener).onServiceRemoved(argThat(
                info -> serviceName.equals(info.getServiceInstanceName())
                        && Arrays.equals(serviceType, info.getServiceType())
                        && info.getInterfaceIndex() == interfaceIndex
                        && network.equals(info.getNetwork())));
        verify(listener).onServiceNameRemoved(argThat(
                info -> serviceName.equals(info.getServiceInstanceName())
                        && Arrays.equals(serviceType, info.getServiceType())
                        && info.getInterfaceIndex() == interfaceIndex
                        && network.equals(info.getNetwork())));
    }

    @Test
    public void processResponse_goodBye() throws Exception {
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        final String serviceName = "service-instance-1";
        final String ipV6Address = "2000:3333::da6c:63ff:fe7c:7483";
        // Process the initial response.
        client.processResponse(createResponse(
                serviceName, ipV6Address, 5353,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        client.processResponse(createResponse(
                "goodbye-service", ipV6Address, 5353,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), /* ptrTtlMillis= */ 0L), INTERFACE_INDEX, mockNetwork);

        // Verify removed callback won't be called if the service is not existed.
        verifyServiceRemovedNoCallback(mockListenerOne);
        verifyServiceRemovedNoCallback(mockListenerTwo);

        // Verify removed callback would be called.
        client.processResponse(createResponse(
                serviceName, ipV6Address, 5353,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), 0L), INTERFACE_INDEX, mockNetwork);
        verifyServiceRemovedCallback(
                mockListenerOne, serviceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX, mockNetwork);
        verifyServiceRemovedCallback(
                mockListenerTwo, serviceName, SERVICE_TYPE_LABELS, INTERFACE_INDEX, mockNetwork);
    }

    @Test
    public void reportExistingServiceToNewlyRegisteredListeners() throws Exception {
        // Process the initial response.
        client.processResponse(createResponse(
                "service-instance-1", "192.168.1.1", 5353,
                /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceNameDiscovered was called once for the existing response.
        verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                "service-instance-1",
                SERVICE_TYPE_LABELS,
                List.of("192.168.1.1") /* ipv4Address */,
                List.of() /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);

        // Verify onServiceFound was called once for the existing response.
        verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        MdnsServiceInfo existingServiceInfo = serviceInfoCaptor.getAllValues().get(1);
        assertEquals(existingServiceInfo.getServiceInstanceName(), "service-instance-1");
        assertEquals(existingServiceInfo.getIpv4Address(), "192.168.1.1");
        assertEquals(existingServiceInfo.getPort(), 5353);
        assertEquals(existingServiceInfo.getSubtypes(), Collections.singletonList("ABCDE"));
        assertNull(existingServiceInfo.getAttributeByKey("key"));

        // Process a goodbye message for the existing response.
        client.processResponse(createResponse(
                "service-instance-1", "192.168.1.1", 5353,
                SERVICE_TYPE_LABELS,
                Collections.emptyMap(), /* ptrTtlMillis= */ 0L), INTERFACE_INDEX, mockNetwork);

        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        // Verify onServiceFound was not called on the newly registered listener after the existing
        // response is gone.
        verify(mockListenerTwo, never()).onServiceNameDiscovered(any(MdnsServiceInfo.class));
        verify(mockListenerTwo, never()).onServiceFound(any(MdnsServiceInfo.class));
    }

    @Test
    public void processResponse_searchOptionsEnableServiceRemoval_shouldRemove()
            throws Exception {
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor,
                        mockDecoderClock, mockNetwork, mockSharedLog) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        MdnsSearchOptions searchOptions = MdnsSearchOptions.newBuilder().setRemoveExpiredService(
                true).build();
        client.startSendAndReceive(mockListenerOne, searchOptions);
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        client.processResponse(createResponse(
                serviceInstanceName, "192.168.1.1", 5353, /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is under TTL.
        doReturn(TEST_ELAPSED_REALTIME + TEST_TTL - 1L).when(mockDecoderClock).elapsedRealtime();
        firstMdnsTask.run();

        // Verify removed callback was not called.
        verifyServiceRemovedNoCallback(mockListenerOne);

        // Simulate the case where the response is after TTL.
        doReturn(TEST_ELAPSED_REALTIME + TEST_TTL + 1L).when(mockDecoderClock).elapsedRealtime();
        firstMdnsTask.run();

        // Verify removed callback was called.
        verifyServiceRemovedCallback(mockListenerOne, serviceInstanceName, SERVICE_TYPE_LABELS,
                INTERFACE_INDEX, mockNetwork);
    }

    @Test
    public void processResponse_searchOptionsNotEnableServiceRemoval_shouldNotRemove()
            throws Exception {
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor,
                        mockDecoderClock, mockNetwork, mockSharedLog) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        client.processResponse(createResponse(
                serviceInstanceName, "192.168.1.1", 5353, /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        doReturn(TEST_ELAPSED_REALTIME + TEST_TTL + 1L).when(mockDecoderClock).elapsedRealtime();
        firstMdnsTask.run();

        // Verify removed callback was not called.
        verifyServiceRemovedNoCallback(mockListenerOne);
    }

    @Test
    @Ignore("MdnsConfigs is not configurable currently.")
    public void processResponse_removeServiceAfterTtlExpiresEnabled_shouldRemove()
            throws Exception {
        //MdnsConfigsFlagsImpl.removeServiceAfterTtlExpires.override(true);
        final String serviceInstanceName = "service-instance-1";
        client =
                new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor,
                        mockDecoderClock, mockNetwork, mockSharedLog) {
                    @Override
                    MdnsPacketWriter createMdnsPacketWriter() {
                        return mockPacketWriter;
                    }
                };
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        Runnable firstMdnsTask = currentThreadExecutor.getAndClearSubmittedRunnable();

        // Process the initial response.
        client.processResponse(createResponse(
                serviceInstanceName, "192.168.1.1", 5353, /* subtype= */ "ABCDE",
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Clear the scheduled runnable.
        currentThreadExecutor.getAndClearLastScheduledRunnable();

        // Simulate the case where the response is after TTL.
        doReturn(TEST_ELAPSED_REALTIME + TEST_TTL + 1L).when(mockDecoderClock).elapsedRealtime();
        firstMdnsTask.run();

        // Verify removed callback was called.
        verifyServiceRemovedCallback(mockListenerOne, serviceInstanceName, SERVICE_TYPE_LABELS,
                INTERFACE_INDEX, mockNetwork);
    }

    @Test
    public void testProcessResponse_InOrder() throws Exception {
        final String serviceName = "service-instance";
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";
        client.startSendAndReceive(mockListenerOne, MdnsSearchOptions.getDefaultOptions());
        InOrder inOrder = inOrder(mockListenerOne);

        // Process the initial response which is incomplete.
        final String subtype = "ABCDE";
        client.processResponse(createResponse(
                serviceName, null, 5353, subtype,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Process a second response which has ip address to make response become complete.
        client.processResponse(createResponse(
                serviceName, ipV4Address, 5353, subtype,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Process a third response with a different ip address, port and updated text attributes.
        client.processResponse(createResponse(
                serviceName, ipV6Address, 5354, subtype,
                Collections.singletonMap("key", "value"), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Process the last response which is goodbye message (with the main type, not subtype).
        client.processResponse(createResponse(
                serviceName, ipV6Address, 5354, SERVICE_TYPE_LABELS,
                Collections.singletonMap("key", "value"), /* ptrTtlMillis= */ 0L),
                INTERFACE_INDEX, mockNetwork);

        // Verify onServiceNameDiscovered was first called for the initial response.
        inOrder.verify(mockListenerOne).onServiceNameDiscovered(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(0),
                serviceName,
                SERVICE_TYPE_LABELS,
                List.of() /* ipv4Address */,
                List.of() /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList(subtype) /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);

        // Verify onServiceFound was second called for the second response.
        inOrder.verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(1),
                serviceName,
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address) /* ipv4Address */,
                List.of() /* ipv6Address */,
                5353 /* port */,
                Collections.singletonList(subtype) /* subTypes */,
                Collections.singletonMap("key", null) /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);

        // Verify onServiceUpdated was third called for the third response.
        inOrder.verify(mockListenerOne).onServiceUpdated(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(2),
                serviceName,
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address) /* ipv4Address */,
                List.of(ipV6Address) /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList(subtype) /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);

        // Verify onServiceRemoved was called for the last response.
        inOrder.verify(mockListenerOne).onServiceRemoved(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(3),
                serviceName,
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address) /* ipv4Address */,
                List.of(ipV6Address) /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);

        // Verify onServiceNameRemoved was called for the last response.
        inOrder.verify(mockListenerOne).onServiceNameRemoved(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getAllValues().get(4),
                serviceName,
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address) /* ipv4Address */,
                List.of(ipV6Address) /* ipv6Address */,
                5354 /* port */,
                Collections.singletonList("ABCDE") /* subTypes */,
                Collections.singletonMap("key", "value") /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);
    }

    @Test
    public void testProcessResponse_Resolve() throws Exception {
        client = new MdnsServiceTypeClient(
                SERVICE_TYPE, mockSocketClient, currentThreadExecutor, mockNetwork, mockSharedLog);

        final String instanceName = "service-instance";
        final String[] hostname = new String[] { "testhost "};
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";

        final MdnsSearchOptions resolveOptions = MdnsSearchOptions.newBuilder()
                .setResolveInstanceName(instanceName).build();

        client.startSendAndReceive(mockListenerOne, resolveOptions);
        InOrder inOrder = inOrder(mockListenerOne, mockSocketClient);

        // Verify a query for SRV/TXT was sent, but no PTR query
        final ArgumentCaptor<DatagramPacket> srvTxtQueryCaptor =
                ArgumentCaptor.forClass(DatagramPacket.class);
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        // Send twice for IPv4 and IPv6
        inOrder.verify(mockSocketClient, times(2)).sendUnicastPacket(srvTxtQueryCaptor.capture(),
                eq(mockNetwork));

        final MdnsPacket srvTxtQueryPacket = MdnsPacket.parse(
                new MdnsPacketReader(srvTxtQueryCaptor.getValue()));

        final String[] serviceName = getTestServiceName(instanceName);
        assertFalse(hasQuestion(srvTxtQueryPacket, MdnsRecord.TYPE_PTR));
        assertTrue(hasQuestion(srvTxtQueryPacket, MdnsRecord.TYPE_SRV, serviceName));
        assertTrue(hasQuestion(srvTxtQueryPacket, MdnsRecord.TYPE_TXT, serviceName));

        // Process a response with SRV+TXT
        final MdnsPacket srvTxtResponse = new MdnsPacket(
                0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(
                        new MdnsServiceRecord(serviceName, 0L /* receiptTimeMillis */,
                                true /* cacheFlush */, TEST_TTL, 0 /* servicePriority */,
                                0 /* serviceWeight */, 1234 /* servicePort */, hostname),
                        new MdnsTextRecord(serviceName, 0L /* receiptTimeMillis */,
                                true /* cacheFlush */, TEST_TTL,
                                Collections.emptyList() /* entries */)),
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);

        client.processResponse(srvTxtResponse, INTERFACE_INDEX, mockNetwork);

        // Expect a query for A/AAAA
        final ArgumentCaptor<DatagramPacket> addressQueryCaptor =
                ArgumentCaptor.forClass(DatagramPacket.class);
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        inOrder.verify(mockSocketClient, times(2)).sendMulticastPacket(addressQueryCaptor.capture(),
                eq(mockNetwork));

        final MdnsPacket addressQueryPacket = MdnsPacket.parse(
                new MdnsPacketReader(addressQueryCaptor.getValue()));
        assertTrue(hasQuestion(addressQueryPacket, MdnsRecord.TYPE_A, hostname));
        assertTrue(hasQuestion(addressQueryPacket, MdnsRecord.TYPE_AAAA, hostname));

        // Process a response with address records
        final MdnsPacket addressResponse = new MdnsPacket(
                0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(
                        new MdnsInetAddressRecord(hostname, 0L /* receiptTimeMillis */,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV4Address)),
                        new MdnsInetAddressRecord(hostname, 0L /* receiptTimeMillis */,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV6Address))),
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);

        inOrder.verify(mockListenerOne, never()).onServiceNameDiscovered(any());
        client.processResponse(addressResponse, INTERFACE_INDEX, mockNetwork);

        inOrder.verify(mockListenerOne).onServiceFound(serviceInfoCaptor.capture());
        verifyServiceInfo(serviceInfoCaptor.getValue(),
                instanceName,
                SERVICE_TYPE_LABELS,
                List.of(ipV4Address),
                List.of(ipV6Address),
                1234 /* port */,
                Collections.emptyList() /* subTypes */,
                Collections.emptyMap() /* attributes */,
                INTERFACE_INDEX,
                mockNetwork);
    }

    @Test
    public void testRenewTxtSrvInResolve() throws Exception {
        client = new MdnsServiceTypeClient(SERVICE_TYPE, mockSocketClient, currentThreadExecutor,
                mockDecoderClock, mockNetwork, mockSharedLog);

        final String instanceName = "service-instance";
        final String[] hostname = new String[] { "testhost "};
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";

        final MdnsSearchOptions resolveOptions = MdnsSearchOptions.newBuilder()
                .setResolveInstanceName(instanceName).build();

        client.startSendAndReceive(mockListenerOne, resolveOptions);
        InOrder inOrder = inOrder(mockListenerOne, mockSocketClient);

        // Get the query for SRV/TXT
        final ArgumentCaptor<DatagramPacket> srvTxtQueryCaptor =
                ArgumentCaptor.forClass(DatagramPacket.class);
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        // Send twice for IPv4 and IPv6
        inOrder.verify(mockSocketClient, times(2)).sendUnicastPacket(srvTxtQueryCaptor.capture(),
                eq(mockNetwork));

        final MdnsPacket srvTxtQueryPacket = MdnsPacket.parse(
                new MdnsPacketReader(srvTxtQueryCaptor.getValue()));

        final String[] serviceName = getTestServiceName(instanceName);
        assertTrue(hasQuestion(srvTxtQueryPacket, MdnsRecord.TYPE_SRV, serviceName));
        assertTrue(hasQuestion(srvTxtQueryPacket, MdnsRecord.TYPE_TXT, serviceName));

        // Process a response with all records
        final MdnsPacket srvTxtResponse = new MdnsPacket(
                0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(
                        new MdnsServiceRecord(serviceName, TEST_ELAPSED_REALTIME,
                                true /* cacheFlush */, TEST_TTL, 0 /* servicePriority */,
                                0 /* serviceWeight */, 1234 /* servicePort */, hostname),
                        new MdnsTextRecord(serviceName, TEST_ELAPSED_REALTIME,
                                true /* cacheFlush */, TEST_TTL,
                                Collections.emptyList() /* entries */),
                        new MdnsInetAddressRecord(hostname, TEST_ELAPSED_REALTIME,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV4Address)),
                        new MdnsInetAddressRecord(hostname, TEST_ELAPSED_REALTIME,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV6Address))),
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);
        client.processResponse(srvTxtResponse, INTERFACE_INDEX, mockNetwork);
        inOrder.verify(mockListenerOne).onServiceNameDiscovered(any());
        inOrder.verify(mockListenerOne).onServiceFound(any());

        // Expect no query on the next run
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        inOrder.verifyNoMoreInteractions();

        // Advance time so 75% of TTL passes and re-execute
        doReturn(TEST_ELAPSED_REALTIME + (long) (TEST_TTL * 0.75))
                .when(mockDecoderClock).elapsedRealtime();
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();

        // Expect a renewal query
        final ArgumentCaptor<DatagramPacket> renewalQueryCaptor =
                ArgumentCaptor.forClass(DatagramPacket.class);
        // Second and later sends are sent as "expect multicast response" queries
        inOrder.verify(mockSocketClient, times(2)).sendMulticastPacket(renewalQueryCaptor.capture(),
                eq(mockNetwork));
        inOrder.verify(mockListenerOne).onDiscoveryQuerySent(any(), anyInt());
        final MdnsPacket renewalPacket = MdnsPacket.parse(
                new MdnsPacketReader(renewalQueryCaptor.getValue()));
        assertTrue(hasQuestion(renewalPacket, MdnsRecord.TYPE_SRV, serviceName));
        assertTrue(hasQuestion(renewalPacket, MdnsRecord.TYPE_TXT, serviceName));
        inOrder.verifyNoMoreInteractions();

        long updatedReceiptTime =  TEST_ELAPSED_REALTIME + TEST_TTL;
        final MdnsPacket refreshedSrvTxtResponse = new MdnsPacket(
                0 /* flags */,
                Collections.emptyList() /* questions */,
                List.of(
                        new MdnsServiceRecord(serviceName, updatedReceiptTime,
                                true /* cacheFlush */, TEST_TTL, 0 /* servicePriority */,
                                0 /* serviceWeight */, 1234 /* servicePort */, hostname),
                        new MdnsTextRecord(serviceName, updatedReceiptTime,
                                true /* cacheFlush */, TEST_TTL,
                                Collections.emptyList() /* entries */),
                        new MdnsInetAddressRecord(hostname, updatedReceiptTime,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV4Address)),
                        new MdnsInetAddressRecord(hostname, updatedReceiptTime,
                                true /* cacheFlush */, TEST_TTL,
                                InetAddresses.parseNumericAddress(ipV6Address))),
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */);
        client.processResponse(refreshedSrvTxtResponse, INTERFACE_INDEX, mockNetwork);

        // Advance time to updatedReceiptTime + 1, expected no refresh query because the cache
        // should contain the record that have update last receipt time.
        doReturn(updatedReceiptTime + 1).when(mockDecoderClock).elapsedRealtime();
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void testProcessResponse_ResolveExcludesOtherServices() {
        client = new MdnsServiceTypeClient(
                SERVICE_TYPE, mockSocketClient, currentThreadExecutor, mockNetwork, mockSharedLog);

        final String requestedInstance = "instance1";
        final String otherInstance = "instance2";
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";

        final MdnsSearchOptions resolveOptions = MdnsSearchOptions.newBuilder()
                // Use different case in the options
                .setResolveInstanceName("Instance1").build();

        client.startSendAndReceive(mockListenerOne, resolveOptions);
        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        // Complete response from instanceName
        client.processResponse(createResponse(
                requestedInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                        Collections.emptyMap() /* textAttributes */, TEST_TTL),
                INTERFACE_INDEX, mockNetwork);

        // Complete response from otherInstanceName
        client.processResponse(createResponse(
                otherInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                        Collections.emptyMap() /* textAttributes */, TEST_TTL),
                INTERFACE_INDEX, mockNetwork);

        // Address update from otherInstanceName
        client.processResponse(createResponse(
                otherInstance, ipV6Address, 5353, SERVICE_TYPE_LABELS,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Goodbye from otherInstanceName
        client.processResponse(createResponse(
                otherInstance, ipV6Address, 5353, SERVICE_TYPE_LABELS,
                Collections.emptyMap(), 0L /* ttl */), INTERFACE_INDEX, mockNetwork);

        // mockListenerOne gets notified for the requested instance
        verify(mockListenerOne).onServiceNameDiscovered(matchServiceName(requestedInstance));
        verify(mockListenerOne).onServiceFound(matchServiceName(requestedInstance));

        // ...but does not get any callback for the other instance
        verify(mockListenerOne, never()).onServiceFound(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceNameDiscovered(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceUpdated(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceRemoved(matchServiceName(otherInstance));

        // mockListenerTwo gets notified for both though
        final InOrder inOrder = inOrder(mockListenerTwo);
        inOrder.verify(mockListenerTwo).onServiceNameDiscovered(
                matchServiceName(requestedInstance));
        inOrder.verify(mockListenerTwo).onServiceFound(matchServiceName(requestedInstance));

        inOrder.verify(mockListenerTwo).onServiceNameDiscovered(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceFound(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceUpdated(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceRemoved(matchServiceName(otherInstance));
    }

    @Test
    public void testProcessResponse_SubtypeDiscoveryLimitedToSubtype() {
        client = new MdnsServiceTypeClient(
                SERVICE_TYPE, mockSocketClient, currentThreadExecutor, mockNetwork, mockSharedLog);

        final String matchingInstance = "instance1";
        final String subtype = "_subtype";
        final String otherInstance = "instance2";
        final String ipV4Address = "192.0.2.0";
        final String ipV6Address = "2001:db8::";

        final MdnsSearchOptions options = MdnsSearchOptions.newBuilder()
                // Search with different case. Note MdnsSearchOptions subtype doesn't start with "_"
                .addSubtype("Subtype").build();

        client.startSendAndReceive(mockListenerOne, options);
        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        // Complete response from instanceName
        final MdnsPacket packetWithoutSubtype = createResponse(
                matchingInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                Collections.emptyMap() /* textAttributes */, TEST_TTL);
        final MdnsPointerRecord originalPtr = (MdnsPointerRecord) CollectionUtils.findFirst(
                packetWithoutSubtype.answers, r -> r instanceof MdnsPointerRecord);

        // Add a subtype PTR record
        final ArrayList<MdnsRecord> newAnswers = new ArrayList<>(packetWithoutSubtype.answers);
        newAnswers.add(new MdnsPointerRecord(
                // PTR should be _subtype._sub._type._tcp.local -> instance1._type._tcp.local
                Stream.concat(Stream.of(subtype, "_sub"), Arrays.stream(SERVICE_TYPE_LABELS))
                        .toArray(String[]::new),
                originalPtr.getReceiptTime(), originalPtr.getCacheFlush(), originalPtr.getTtl(),
                originalPtr.getPointer()));
        final MdnsPacket packetWithSubtype = new MdnsPacket(
                packetWithoutSubtype.flags,
                packetWithoutSubtype.questions,
                newAnswers,
                packetWithoutSubtype.authorityRecords,
                packetWithoutSubtype.additionalRecords);
        client.processResponse(packetWithSubtype, INTERFACE_INDEX, mockNetwork);

        // Complete response from otherInstanceName, without subtype
        client.processResponse(createResponse(
                        otherInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                        Collections.emptyMap() /* textAttributes */, TEST_TTL),
                INTERFACE_INDEX, mockNetwork);

        // Address update from otherInstanceName
        client.processResponse(createResponse(
                otherInstance, ipV6Address, 5353, SERVICE_TYPE_LABELS,
                Collections.emptyMap(), TEST_TTL), INTERFACE_INDEX, mockNetwork);

        // Goodbye from otherInstanceName
        client.processResponse(createResponse(
                otherInstance, ipV6Address, 5353, SERVICE_TYPE_LABELS,
                Collections.emptyMap(), 0L /* ttl */), INTERFACE_INDEX, mockNetwork);

        // mockListenerOne gets notified for the requested instance
        final ArgumentMatcher<MdnsServiceInfo> subtypeInstanceMatcher = info ->
                info.getServiceInstanceName().equals(matchingInstance)
                        && info.getSubtypes().equals(Collections.singletonList(subtype));
        verify(mockListenerOne).onServiceNameDiscovered(argThat(subtypeInstanceMatcher));
        verify(mockListenerOne).onServiceFound(argThat(subtypeInstanceMatcher));

        // ...but does not get any callback for the other instance
        verify(mockListenerOne, never()).onServiceFound(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceNameDiscovered(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceUpdated(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceRemoved(matchServiceName(otherInstance));

        // mockListenerTwo gets notified for both though
        final InOrder inOrder = inOrder(mockListenerTwo);
        inOrder.verify(mockListenerTwo).onServiceNameDiscovered(argThat(subtypeInstanceMatcher));
        inOrder.verify(mockListenerTwo).onServiceFound(argThat(subtypeInstanceMatcher));

        inOrder.verify(mockListenerTwo).onServiceNameDiscovered(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceFound(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceUpdated(matchServiceName(otherInstance));
        inOrder.verify(mockListenerTwo).onServiceRemoved(matchServiceName(otherInstance));
    }

    @Test
    public void testNotifySocketDestroyed() throws Exception {
        client = new MdnsServiceTypeClient(
                SERVICE_TYPE, mockSocketClient, currentThreadExecutor, mockNetwork, mockSharedLog);

        final String requestedInstance = "instance1";
        final String otherInstance = "instance2";
        final String ipV4Address = "192.0.2.0";

        final MdnsSearchOptions resolveOptions = MdnsSearchOptions.newBuilder()
                // Use different case in the options
                .setResolveInstanceName("Instance1").build();

        client.startSendAndReceive(mockListenerOne, resolveOptions);
        // Ensure the first task is executed so it schedules a future task
        currentThreadExecutor.getAndClearSubmittedFuture().get(
                TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.startSendAndReceive(mockListenerTwo, MdnsSearchOptions.getDefaultOptions());

        // Filing the second request cancels the first future
        verify(expectedSendFutures[0]).cancel(true);

        // Ensure it gets executed too
        currentThreadExecutor.getAndClearSubmittedFuture().get(
                TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Complete response from instanceName
        client.processResponse(createResponse(
                        requestedInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                        Collections.emptyMap() /* textAttributes */, TEST_TTL),
                INTERFACE_INDEX, mockNetwork);

        // Complete response from otherInstanceName
        client.processResponse(createResponse(
                        otherInstance, ipV4Address, 5353, SERVICE_TYPE_LABELS,
                        Collections.emptyMap() /* textAttributes */, TEST_TTL),
                INTERFACE_INDEX, mockNetwork);

        verify(expectedSendFutures[1], never()).cancel(true);
        client.notifySocketDestroyed();
        verify(expectedSendFutures[1]).cancel(true);

        // mockListenerOne gets notified for the requested instance
        final InOrder inOrder1 = inOrder(mockListenerOne);
        inOrder1.verify(mockListenerOne).onServiceNameDiscovered(
                matchServiceName(requestedInstance));
        inOrder1.verify(mockListenerOne).onServiceFound(matchServiceName(requestedInstance));
        inOrder1.verify(mockListenerOne).onServiceRemoved(matchServiceName(requestedInstance));
        inOrder1.verify(mockListenerOne).onServiceNameRemoved(matchServiceName(requestedInstance));
        verify(mockListenerOne, never()).onServiceFound(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceNameDiscovered(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceRemoved(matchServiceName(otherInstance));
        verify(mockListenerOne, never()).onServiceNameRemoved(matchServiceName(otherInstance));

        // mockListenerTwo gets notified for both though
        final InOrder inOrder2 = inOrder(mockListenerTwo);
        inOrder2.verify(mockListenerTwo).onServiceNameDiscovered(
                matchServiceName(requestedInstance));
        inOrder2.verify(mockListenerTwo).onServiceFound(matchServiceName(requestedInstance));
        inOrder2.verify(mockListenerTwo).onServiceNameDiscovered(matchServiceName(otherInstance));
        inOrder2.verify(mockListenerTwo).onServiceFound(matchServiceName(otherInstance));
        inOrder2.verify(mockListenerTwo).onServiceRemoved(matchServiceName(otherInstance));
        inOrder2.verify(mockListenerTwo).onServiceNameRemoved(matchServiceName(otherInstance));
        inOrder2.verify(mockListenerTwo).onServiceRemoved(matchServiceName(requestedInstance));
        inOrder2.verify(mockListenerTwo).onServiceNameRemoved(matchServiceName(requestedInstance));
    }

    private static MdnsServiceInfo matchServiceName(String name) {
        return argThat(info -> info.getServiceInstanceName().equals(name));
    }

    // verifies that the right query was enqueued with the right delay, and send query by executing
    // the runnable.
    private void verifyAndSendQuery(int index, long timeInMs, boolean expectsUnicastResponse) {
        verifyAndSendQuery(
                index, timeInMs, expectsUnicastResponse, true /* multipleSocketDiscovery */);
    }

    private void verifyAndSendQuery(int index, long timeInMs, boolean expectsUnicastResponse,
            boolean multipleSocketDiscovery) {
        assertEquals(currentThreadExecutor.getAndClearLastScheduledDelayInMs(), timeInMs);
        currentThreadExecutor.getAndClearLastScheduledRunnable().run();
        if (expectsUnicastResponse) {
            verify(mockSocketClient).sendUnicastPacket(
                    expectedIPv4Packets[index], mockNetwork);
            if (multipleSocketDiscovery) {
                verify(mockSocketClient).sendUnicastPacket(
                        expectedIPv6Packets[index], mockNetwork);
            }
        } else {
            verify(mockSocketClient).sendMulticastPacket(
                    expectedIPv4Packets[index], mockNetwork);
            if (multipleSocketDiscovery) {
                verify(mockSocketClient).sendMulticastPacket(
                        expectedIPv6Packets[index], mockNetwork);
            }
        }
    }

    private static String[] getTestServiceName(String instanceName) {
        return Stream.concat(Stream.of(instanceName),
                Arrays.stream(SERVICE_TYPE_LABELS)).toArray(String[]::new);
    }

    private static boolean hasQuestion(MdnsPacket packet, int type) {
        return hasQuestion(packet, type, null);
    }

    private static boolean hasQuestion(MdnsPacket packet, int type, @Nullable String[] name) {
        return packet.questions.stream().anyMatch(q -> q.getType() == type
                && (name == null || Arrays.equals(q.name, name)));
    }

    // A fake ScheduledExecutorService that keeps tracking the last scheduled Runnable and its delay
    // time.
    private class FakeExecutor extends ScheduledThreadPoolExecutor {
        private long lastScheduledDelayInMs;
        private Runnable lastScheduledRunnable;
        private Runnable lastSubmittedRunnable;
        private Future<?> lastSubmittedFuture;
        private int futureIndex;

        FakeExecutor() {
            super(1);
            lastScheduledDelayInMs = -1;
        }

        @Override
        public Future<?> submit(Runnable command) {
            Future<?> future = super.submit(command);
            lastSubmittedRunnable = command;
            lastSubmittedFuture = future;
            return future;
        }

        // Don't call through the real implementation, just track the scheduled Runnable, and
        // returns a ScheduledFuture.
        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            lastScheduledDelayInMs = delay;
            lastScheduledRunnable = command;
            return expectedSendFutures[futureIndex++];
        }

        // Returns the delay of the last scheduled task, and clear it.
        long getAndClearLastScheduledDelayInMs() {
            long val = lastScheduledDelayInMs;
            lastScheduledDelayInMs = -1;
            return val;
        }

        // Returns the last scheduled task, and clear it.
        Runnable getAndClearLastScheduledRunnable() {
            Runnable val = lastScheduledRunnable;
            lastScheduledRunnable = null;
            return val;
        }

        Runnable getAndClearSubmittedRunnable() {
            Runnable val = lastSubmittedRunnable;
            lastSubmittedRunnable = null;
            return val;
        }

        Future<?> getAndClearSubmittedFuture() {
            Future<?> val = lastSubmittedFuture;
            lastSubmittedFuture = null;
            return val;
        }
    }

    private MdnsPacket createResponse(
            @NonNull String serviceInstanceName,
            @Nullable String host,
            int port,
            @NonNull String subtype,
            @NonNull Map<String, String> textAttributes,
            long ptrTtlMillis)
            throws Exception {
        final ArrayList<String> type = new ArrayList<>();
        type.add(subtype);
        type.add(MdnsConstants.SUBTYPE_LABEL);
        type.addAll(Arrays.asList(SERVICE_TYPE_LABELS));
        return createResponse(serviceInstanceName, host, port, type.toArray(new String[0]),
                textAttributes, ptrTtlMillis);
    }

    // Creates a mDNS response.
    private MdnsPacket createResponse(
            @NonNull String serviceInstanceName,
            @Nullable String host,
            int port,
            @NonNull String[] type,
            @NonNull Map<String, String> textAttributes,
            long ptrTtlMillis) {

        final ArrayList<MdnsRecord> answerRecords = new ArrayList<>();

        // Set PTR record
        final ArrayList<String> serviceNameList = new ArrayList<>();
        serviceNameList.add(serviceInstanceName);
        serviceNameList.addAll(Arrays.asList(type));
        final String[] serviceName = serviceNameList.toArray(new String[0]);
        final MdnsPointerRecord pointerRecord = new MdnsPointerRecord(
                type,
                TEST_ELAPSED_REALTIME /* receiptTimeMillis */,
                false /* cacheFlush */,
                ptrTtlMillis,
                serviceName);
        answerRecords.add(pointerRecord);

        // Set SRV record.
        final MdnsServiceRecord serviceRecord = new MdnsServiceRecord(
                serviceName,
                TEST_ELAPSED_REALTIME /* receiptTimeMillis */,
                false /* cacheFlush */,
                TEST_TTL,
                0 /* servicePriority */,
                0 /* serviceWeight */,
                port,
                new String[]{"hostname"});
        answerRecords.add(serviceRecord);

        // Set A/AAAA record.
        if (host != null) {
            final InetAddress addr = InetAddresses.parseNumericAddress(host);
            final MdnsInetAddressRecord inetAddressRecord = new MdnsInetAddressRecord(
                    new String[] {"hostname"} /* name */,
                    TEST_ELAPSED_REALTIME /* receiptTimeMillis */,
                    false /* cacheFlush */,
                    TEST_TTL,
                    addr);
            answerRecords.add(inetAddressRecord);
        }

        // Set TXT record.
        final List<TextEntry> textEntries = new ArrayList<>();
        for (Map.Entry<String, String> kv : textAttributes.entrySet()) {
            textEntries.add(new TextEntry(kv.getKey(), kv.getValue().getBytes(UTF_8)));
        }
        final MdnsTextRecord textRecord = new MdnsTextRecord(
                serviceName,
                TEST_ELAPSED_REALTIME /* receiptTimeMillis */,
                false /* cacheFlush */,
                TEST_TTL,
                textEntries);
        answerRecords.add(textRecord);
        return new MdnsPacket(
                0 /* flags */,
                Collections.emptyList() /* questions */,
                answerRecords,
                Collections.emptyList() /* authorityRecords */,
                Collections.emptyList() /* additionalRecords */
        );
    }
}