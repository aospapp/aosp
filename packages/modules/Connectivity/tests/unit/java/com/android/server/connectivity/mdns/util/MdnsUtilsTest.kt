/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.server.connectivity.mdns.util

import android.os.Build
import com.android.server.connectivity.mdns.util.MdnsUtils.equalsIgnoreDnsCase
import com.android.server.connectivity.mdns.util.MdnsUtils.toDnsLowerCase
import com.android.server.connectivity.mdns.util.MdnsUtils.truncateServiceName
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
class MdnsUtilsTest {
    @Test
    fun testToDnsLowerCase() {
        assertEquals("test", toDnsLowerCase("TEST"))
        assertEquals("test", toDnsLowerCase("TeSt"))
        assertEquals("test", toDnsLowerCase("test"))
        assertEquals("tÉst", toDnsLowerCase("TÉST"))
        assertEquals("ţést", toDnsLowerCase("ţést"))
        // Unicode characters 0x10000 (𐀀), 0x10001 (𐀁), 0x10041 (𐁁)
        // Note the last 2 bytes of 0x10041 are identical to 'A', but it should remain unchanged.
        assertEquals("test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- ",
                toDnsLowerCase("Test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- "))
        // Also test some characters where the first surrogate is not \ud800
        assertEquals("test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<",
                toDnsLowerCase("Test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                        "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<"))
    }

    @Test
    fun testEqualsIgnoreDnsCase() {
        assertTrue(equalsIgnoreDnsCase("TEST", "Test"))
        assertTrue(equalsIgnoreDnsCase("TEST", "test"))
        assertTrue(equalsIgnoreDnsCase("test", "TeSt"))
        assertTrue(equalsIgnoreDnsCase("Tést", "tést"))
        assertFalse(equalsIgnoreDnsCase("ŢÉST", "ţést"))
        // Unicode characters 0x10000 (𐀀), 0x10001 (𐀁), 0x10041 (𐁁)
        // Note the last 2 bytes of 0x10041 are identical to 'A', but it should remain unchanged.
        assertTrue(equalsIgnoreDnsCase("test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- ",
                "Test: -->\ud800\udc00 \ud800\udc01 \ud800\udc41<-- "))
        // Also test some characters where the first surrogate is not \ud800
        assertTrue(equalsIgnoreDnsCase("test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<",
                "Test: >\ud83c\udff4\udb40\udc67\udb40\udc62\udb40" +
                        "\udc77\udb40\udc6c\udb40\udc73\udb40\udc7f<"))
    }

    @Test
    fun testTruncateServiceName() {
        assertEquals(truncateServiceName("测试abcde", 7), "测试a")
        assertEquals(truncateServiceName("测试abcde", 100), "测试abcde")
    }
}
