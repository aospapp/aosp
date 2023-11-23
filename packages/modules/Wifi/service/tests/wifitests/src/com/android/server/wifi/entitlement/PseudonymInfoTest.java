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

package com.android.server.wifi.entitlement;

import static com.android.server.wifi.entitlement.PseudonymInfo.MINIMUM_REFRESH_INTERVAL_IN_MILLIS;
import static com.android.server.wifi.entitlement.PseudonymInfo.REFRESH_AHEAD_TIME_IN_MILLIS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * Unit tests for {@link PseudonymInfo}.
 */
@SmallTest
public class PseudonymInfoTest {
    private static final String PSEUDONYM = "pseudonym";
    private static final String IMSI = "imsi";
    private static final long TTL_IN_MILLIS = Duration.ofDays(1).toMillis();
    private static final long TWENTY_MINUTES_IN_MILLIS = Duration.ofMinutes(20).toMillis();

    @Test
    public void pseudonymInfoWithoutTtl() {
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI);
        assertEquals(PSEUDONYM, pseudonymInfo.getPseudonym());
        assertEquals(IMSI, pseudonymInfo.getImsi());
        assertEquals(PseudonymInfo.DEFAULT_PSEUDONYM_TTL_IN_MILLIS, pseudonymInfo.getTtlInMillis());
    }

    @Test
    public void pseudonymInfoWithTtl() {
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, TTL_IN_MILLIS);
        assertEquals(PSEUDONYM, pseudonymInfo.getPseudonym());
        assertEquals(TTL_IN_MILLIS, pseudonymInfo.getTtlInMillis());
        assertEquals(TTL_IN_MILLIS - REFRESH_AHEAD_TIME_IN_MILLIS,
                pseudonymInfo.getAtrInMillis());
        assertFalse(pseudonymInfo.hasExpired());
        assertFalse(pseudonymInfo.shouldBeRefreshed());
        assertFalse(pseudonymInfo.isOldEnoughToRefresh());
    }

    @Test
    public void pseudonymInfoWithTtlTimestamp() {
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, TTL_IN_MILLIS,
                Instant.now().toEpochMilli() - MINIMUM_REFRESH_INTERVAL_IN_MILLIS);
        assertFalse(pseudonymInfo.hasExpired());
        assertFalse(pseudonymInfo.shouldBeRefreshed());
        assertTrue(pseudonymInfo.isOldEnoughToRefresh());

        pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, TTL_IN_MILLIS,
                Instant.now().toEpochMilli() - TTL_IN_MILLIS + REFRESH_AHEAD_TIME_IN_MILLIS);
        assertFalse(pseudonymInfo.hasExpired());
        assertTrue(pseudonymInfo.shouldBeRefreshed());
        assertTrue(pseudonymInfo.isOldEnoughToRefresh());

        pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, TTL_IN_MILLIS,
                Instant.now().toEpochMilli() - TTL_IN_MILLIS);
        assertTrue(pseudonymInfo.hasExpired());
        assertTrue(pseudonymInfo.shouldBeRefreshed());
        assertTrue(pseudonymInfo.isOldEnoughToRefresh());
    }

    @Test
    public void pseudonymInfoWithSmallTtl() {
        PseudonymInfo pseudonymInfo =
                new PseudonymInfo(PSEUDONYM, IMSI, TWENTY_MINUTES_IN_MILLIS);
        assertTrue(pseudonymInfo.getAtrInMillis() >= TWENTY_MINUTES_IN_MILLIS / 2);
    }
}
