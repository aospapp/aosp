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

package com.android.server.connectivity.mdns

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.HandlerThread
import com.android.server.connectivity.mdns.MdnsAnnouncer.AnnouncementInfo
import com.android.server.connectivity.mdns.MdnsRecordRepository.Dependencies
import com.android.server.connectivity.mdns.MdnsRecordRepository.getReverseDnsAddress
import com.android.server.connectivity.mdns.MdnsServiceInfo.TextEntry
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_SERVICE_ID_1 = 42
private const val TEST_SERVICE_ID_2 = 43
private const val TEST_PORT = 12345
private const val TEST_SUBTYPE = "_subtype"
private val TEST_HOSTNAME = arrayOf("Android_000102030405060708090A0B0C0D0E0F", "local")
private val TEST_ADDRESSES = listOf(
        LinkAddress(parseNumericAddress("192.0.2.111"), 24),
        LinkAddress(parseNumericAddress("2001:db8::111"), 64),
        LinkAddress(parseNumericAddress("2001:db8::222"), 64))

private val TEST_SERVICE_1 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyTestService"
    port = TEST_PORT
}

private val TEST_SERVICE_2 = NsdServiceInfo().apply {
    serviceType = "_testservice._tcp"
    serviceName = "MyOtherTestService"
    port = TEST_PORT
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsRecordRepositoryTest {
    private val thread = HandlerThread(MdnsRecordRepositoryTest::class.simpleName)
    private val deps = object : Dependencies() {
        override fun getInterfaceInetAddresses(iface: NetworkInterface) =
                Collections.enumeration(TEST_ADDRESSES.map { it.address })
    }

    @Before
    fun setUp() {
        thread.start()
    }

    @After
    fun tearDown() {
        thread.quitSafely()
        thread.join()
    }

    @Test
    fun testAddServiceAndProbe() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        assertEquals(0, repository.servicesCount)
        assertEquals(-1, repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1,
                null /* subtype */))
        assertEquals(1, repository.servicesCount)

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        assertNotNull(probingInfo)
        assertTrue(repository.isProbing(TEST_SERVICE_ID_1))

        assertEquals(TEST_SERVICE_ID_1, probingInfo.serviceId)
        val packet = probingInfo.getPacket(0)

        assertEquals(MdnsConstants.FLAGS_QUERY, packet.flags)
        assertEquals(0, packet.answers.size)
        assertEquals(0, packet.additionalRecords.size)

        assertEquals(1, packet.questions.size)
        val expectedName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        assertEquals(MdnsAnyRecord(expectedName, false /* unicast */), packet.questions[0])

        assertEquals(1, packet.authorityRecords.size)
        assertEquals(MdnsServiceRecord(expectedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                120_000L /* ttlMillis */,
                0 /* servicePriority */, 0 /* serviceWeight */,
                TEST_PORT, TEST_HOSTNAME), packet.authorityRecords[0])

        assertContentEquals(intArrayOf(TEST_SERVICE_ID_1), repository.clearServices())
    }

    @Test
    fun testAddAndConflicts() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1, null /* subtype */)
        assertFailsWith(NameConflictException::class) {
            repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1, null /* subtype */)
        }
    }

    @Test
    fun testInvalidReuseOfServiceId() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1, null /* subtype */)
        assertFailsWith(IllegalArgumentException::class) {
            repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_2, null /* subtype */)
        }
    }

    @Test
    fun testHasActiveService() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        assertFalse(repository.hasActiveService(TEST_SERVICE_ID_1))

        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1, null /* subtype */)
        assertTrue(repository.hasActiveService(TEST_SERVICE_ID_1))

        val probingInfo = repository.setServiceProbing(TEST_SERVICE_ID_1)
        repository.onProbingSucceeded(probingInfo)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1)
        assertTrue(repository.hasActiveService(TEST_SERVICE_ID_1))

        repository.exitService(TEST_SERVICE_ID_1)
        assertFalse(repository.hasActiveService(TEST_SERVICE_ID_1))
    }

    @Test
    fun testExitAnnouncements() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1)

        val exitAnnouncement = repository.exitService(TEST_SERVICE_ID_1)
        assertNotNull(exitAnnouncement)
        assertEquals(1, repository.servicesCount)
        val packet = exitAnnouncement.getPacket(0)

        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)
        assertEquals(0, packet.additionalRecords.size)

        assertContentEquals(listOf(
                MdnsPointerRecord(
                        arrayOf("_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local"))
        ), packet.answers)

        repository.removeService(TEST_SERVICE_ID_1)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testExitAnnouncements_WithSubtype() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1, TEST_SUBTYPE)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1)

        val exitAnnouncement = repository.exitService(TEST_SERVICE_ID_1)
        assertNotNull(exitAnnouncement)
        assertEquals(1, repository.servicesCount)
        val packet = exitAnnouncement.getPacket(0)

        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)
        assertEquals(0, packet.additionalRecords.size)

        assertContentEquals(listOf(
                MdnsPointerRecord(
                        arrayOf("_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local")),
                MdnsPointerRecord(
                        arrayOf("_subtype", "_sub", "_testservice", "_tcp", "local"),
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        0L /* ttlMillis */,
                        arrayOf("MyTestService", "_testservice", "_tcp", "local")),
        ), packet.answers)

        repository.removeService(TEST_SERVICE_ID_1)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testExitingServiceReAdded() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1)
        repository.exitService(TEST_SERVICE_ID_1)

        assertEquals(TEST_SERVICE_ID_1,
                repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_1, null /* subtype */))
        assertEquals(1, repository.servicesCount)

        repository.removeService(TEST_SERVICE_ID_2)
        assertEquals(0, repository.servicesCount)
    }

    @Test
    fun testOnProbingSucceeded() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        val announcementInfo = repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1,
                TEST_SUBTYPE)
        repository.onAdvertisementSent(TEST_SERVICE_ID_1)
        val packet = announcementInfo.getPacket(0)

        assertEquals(0x8400 /* response, authoritative */, packet.flags)
        assertEquals(0, packet.questions.size)
        assertEquals(0, packet.authorityRecords.size)

        val serviceType = arrayOf("_testservice", "_tcp", "local")
        val serviceSubtype = arrayOf(TEST_SUBTYPE, "_sub", "_testservice", "_tcp", "local")
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")
        val v4AddrRev = getReverseDnsAddress(TEST_ADDRESSES[0].address)
        val v6Addr1Rev = getReverseDnsAddress(TEST_ADDRESSES[1].address)
        val v6Addr2Rev = getReverseDnsAddress(TEST_ADDRESSES[2].address)

        assertContentEquals(listOf(
                // Reverse address and address records for the hostname
                MdnsPointerRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[0].address),
                MdnsPointerRecord(v6Addr1Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[1].address),
                MdnsPointerRecord(v6Addr2Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME),
                MdnsInetAddressRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_ADDRESSES[2].address),
                // Service registration records (RFC6763)
                MdnsPointerRecord(
                        serviceType,
                        0L /* receiptTimeMillis */,
                        // Not a unique name owned by the announcer, so cacheFlush=false
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName),
                MdnsPointerRecord(
                        serviceSubtype,
                        0L /* receiptTimeMillis */,
                        // Not a unique name owned by the announcer, so cacheFlush=false
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName),
                MdnsServiceRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        0 /* servicePriority */,
                        0 /* serviceWeight */,
                        TEST_PORT /* servicePort */,
                        TEST_HOSTNAME),
                MdnsTextRecord(
                        serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        emptyList() /* entries */),
                // Service type enumeration record (RFC6763 9.)
                MdnsPointerRecord(
                        arrayOf("_services", "_dns-sd", "_udp", "local"),
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceType)
        ), packet.answers)

        assertContentEquals(listOf(
                MdnsNsecRecord(v4AddrRev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v4AddrRev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(TEST_HOSTNAME,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        TEST_HOSTNAME,
                        intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)),
                MdnsNsecRecord(v6Addr1Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v6Addr1Rev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(v6Addr2Rev,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        120000L /* ttlMillis */,
                        v6Addr2Rev,
                        intArrayOf(MdnsRecord.TYPE_PTR)),
                MdnsNsecRecord(serviceName,
                        0L /* receiptTimeMillis */,
                        true /* cacheFlush */,
                        4500000L /* ttlMillis */,
                        serviceName,
                        intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV))
        ), packet.additionalRecords)
    }

    @Test
    fun testGetReverseDnsAddress() {
        val expectedV6 = "1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.B.D.0.1.0.0.2.ip6.arpa"
                .split(".").toTypedArray()
        assertContentEquals(expectedV6, getReverseDnsAddress(parseNumericAddress("2001:db8::1")))
        val expectedV4 = "123.2.0.192.in-addr.arpa".split(".").toTypedArray()
        assertContentEquals(expectedV4, getReverseDnsAddress(parseNumericAddress("192.0.2.123")))
    }

    @Test
    fun testGetReply() {
        doGetReplyTest(subtype = null)
    }

    @Test
    fun testGetReply_WithSubtype() {
        doGetReplyTest(TEST_SUBTYPE)
    }

    private fun doGetReplyTest(subtype: String?) {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.initWithService(TEST_SERVICE_ID_1, TEST_SERVICE_1, subtype)
        val queriedName = if (subtype == null) arrayOf("_testservice", "_tcp", "local")
        else arrayOf(subtype, "_sub", "_testservice", "_tcp", "local")

        val questions = listOf(MdnsPointerRecord(queriedName,
                0L /* receiptTimeMillis */,
                false /* cacheFlush */,
                // TTL and data is empty for a question
                0L /* ttlMillis */,
                null /* pointer */))
        val query = MdnsPacket(0 /* flags */, questions, listOf() /* answers */,
                listOf() /* authorityRecords */, listOf() /* additionalRecords */)
        val src = InetSocketAddress(parseNumericAddress("192.0.2.123"), 5353)
        val reply = repository.getReply(query, src)

        assertNotNull(reply)
        // Source address is IPv4
        assertEquals(MdnsConstants.getMdnsIPv4Address(), reply.destination.address)
        assertEquals(MdnsConstants.MDNS_PORT, reply.destination.port)

        // TTLs as per RFC6762 10.
        val longTtl = 4_500_000L
        val shortTtl = 120_000L
        val serviceName = arrayOf("MyTestService", "_testservice", "_tcp", "local")

        assertEquals(listOf(
                MdnsPointerRecord(
                        queriedName,
                        0L /* receiptTimeMillis */,
                        false /* cacheFlush */,
                        longTtl,
                        serviceName),
        ), reply.answers)

        assertEquals(listOf(
            MdnsTextRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    longTtl,
                    listOf() /* entries */),
            MdnsServiceRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    shortTtl,
                    0 /* servicePriority */,
                    0 /* serviceWeight */,
                    TEST_PORT,
                    TEST_HOSTNAME),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    shortTtl,
                    TEST_ADDRESSES[0].address),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    shortTtl,
                    TEST_ADDRESSES[1].address),
            MdnsInetAddressRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    shortTtl,
                    TEST_ADDRESSES[2].address),
            MdnsNsecRecord(
                    serviceName,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    longTtl,
                    serviceName /* nextDomain */,
                    intArrayOf(MdnsRecord.TYPE_TXT, MdnsRecord.TYPE_SRV)),
            MdnsNsecRecord(
                    TEST_HOSTNAME,
                    0L /* receiptTimeMillis */,
                    true /* cacheFlush */,
                    shortTtl,
                    TEST_HOSTNAME /* nextDomain */,
                    intArrayOf(MdnsRecord.TYPE_A, MdnsRecord.TYPE_AAAA)),
        ), reply.additionalAnswers)
    }

    @Test
    fun testGetConflictingServices() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1, null /* subtype */)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2, null /* subtype */)

        val packet = MdnsPacket(
                0 /* flags */,
                emptyList() /* questions */,
                listOf(
                    MdnsServiceRecord(
                            arrayOf("MyTestService", "_testservice", "_tcp", "local"),
                            0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                            0 /* servicePriority */, 0 /* serviceWeight */,
                            TEST_SERVICE_1.port + 1,
                            TEST_HOSTNAME),
                    MdnsTextRecord(
                            arrayOf("MyOtherTestService", "_testservice", "_tcp", "local"),
                            0L /* receiptTimeMillis */, true /* cacheFlush */, 0L /* ttlMillis */,
                            listOf(TextEntry.fromString("somedifferent=entry"))),
                ) /* answers */,
                emptyList() /* authorityRecords */,
                emptyList() /* additionalRecords */)

        assertEquals(setOf(TEST_SERVICE_ID_1, TEST_SERVICE_ID_2),
                repository.getConflictingServices(packet))
    }

    @Test
    fun testGetConflictingServices_IdenticalService() {
        val repository = MdnsRecordRepository(thread.looper, deps, TEST_HOSTNAME)
        repository.addService(TEST_SERVICE_ID_1, TEST_SERVICE_1, null /* subtype */)
        repository.addService(TEST_SERVICE_ID_2, TEST_SERVICE_2, null /* subtype */)

        val otherTtlMillis = 1234L
        val packet = MdnsPacket(
                0 /* flags */,
                emptyList() /* questions */,
                listOf(
                        MdnsServiceRecord(
                                arrayOf("MyTestService", "_testservice", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, 0 /* servicePriority */, 0 /* serviceWeight */,
                                TEST_SERVICE_1.port,
                                TEST_HOSTNAME),
                        MdnsTextRecord(
                                arrayOf("MyOtherTestService", "_testservice", "_tcp", "local"),
                                0L /* receiptTimeMillis */, true /* cacheFlush */,
                                otherTtlMillis, emptyList()),
                ) /* answers */,
                emptyList() /* authorityRecords */,
                emptyList() /* additionalRecords */)

        // Above records are identical to the actual registrations: no conflict
        assertEquals(emptySet(), repository.getConflictingServices(packet))
    }
}

private fun MdnsRecordRepository.initWithService(
    serviceId: Int,
    serviceInfo: NsdServiceInfo,
    subtype: String? = null
): AnnouncementInfo {
    updateAddresses(TEST_ADDRESSES)
    addService(serviceId, serviceInfo, subtype)
    val probingInfo = setServiceProbing(serviceId)
    assertNotNull(probingInfo)
    return onProbingSucceeded(probingInfo)
}
