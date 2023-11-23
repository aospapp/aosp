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

package com.android.server.nearby.fastpair.halfsheet;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import com.android.server.nearby.fastpair.blocklist.Blocklist;
import com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState;
import com.android.server.nearby.fastpair.blocklist.BlocklistElement;
import com.android.server.nearby.util.DefaultClock;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class FastPairHalfSheetBlocklistTest {

    @Mock
    private DefaultClock mClock;
    private FastPairHalfSheetBlocklist mFastPairHalfSheetBlocklist;
    private static final int SIZE_OF_BLOCKLIST = 2;
    private static final long CURRENT_TIME = 1000000L;
    private static final long BLOCKLIST_CANCEL_TIMEOUT_MILLIS = 30000L;
    private static final long SUPPRESS_ALL_DURATION_MILLIS = 60000L;
    private static final long DURATION_RESURFACE_DISMISS_HALF_SHEET_MILLISECOND = 86400000;
    private static final long STATE_EXPIRATION_MILLISECOND = 86400000;
    private static final int HALFSHEET_ID = 1;
    private static final long DURATION_MILLI_SECONDS_LONG = 86400000;
    private static final int DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS = 1;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME);
        mFastPairHalfSheetBlocklist = new FastPairHalfSheetBlocklist(SIZE_OF_BLOCKLIST, mClock);
    }

    @Test
    public void testUpdateState() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.ACTIVE, CURRENT_TIME));

        boolean initiallyBlocklisted =
                mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                        DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS);

        mFastPairHalfSheetBlocklist.updateState(HALFSHEET_ID, Blocklist.BlocklistState.ACTIVE);
        boolean isBlockListedWhenActive =
                mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                        DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS);

        mFastPairHalfSheetBlocklist.updateState(HALFSHEET_ID, Blocklist.BlocklistState.DISMISSED);
        boolean isBlockListedAfterDismissed =
                mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                        DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS);

        mFastPairHalfSheetBlocklist.updateState(HALFSHEET_ID,
                Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN);
        boolean isBlockListedAfterDoNotShowAgain =
                mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                        DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS);

        mFastPairHalfSheetBlocklist.updateState(HALFSHEET_ID,
                Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN_LONG);
        boolean isBlockListedAfterDoNotShowAgainLong =
                mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                        DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS);

        assertThat(initiallyBlocklisted).isFalse();
        assertThat(isBlockListedWhenActive).isFalse();
        assertThat(isBlockListedAfterDismissed).isTrue();
        assertThat(isBlockListedAfterDoNotShowAgain).isTrue();
        assertThat(isBlockListedAfterDoNotShowAgainLong).isTrue();
    }

    @Test
    public void testBlocklist_overflow() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DISMISSED, CURRENT_TIME));
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID + 1,
                new BlocklistElement(Blocklist.BlocklistState.UNKNOWN, CURRENT_TIME));
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID + 2,
                new BlocklistElement(Blocklist.BlocklistState.UNKNOWN, CURRENT_TIME));

        // blocklist should have evicted HALFSHEET_ID making it no longer blocklisted, this is
        // because for the test we initialize the size of the blocklist cache to be max = 2
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void removeHalfSheetDismissState() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DISMISSED, CURRENT_TIME));
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();

        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID + 1)).isFalse();
    }

    @Test
    public void removeHalfSheetBanState() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID + 1)).isFalse();
    }

    @Test
    public void testHalfSheetTimeOutReleaseBan() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        when(mClock.elapsedRealtime())
            .thenReturn(CURRENT_TIME + BLOCKLIST_CANCEL_TIMEOUT_MILLIS + 1);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void testHalfSheetDoNotShowAgainLong() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(
                        Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN_LONG, CURRENT_TIME));
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID)).isTrue();
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
        assertThat(mFastPairHalfSheetBlocklist.removeBlocklist(HALFSHEET_ID + 1)).isFalse();
    }

    @Test
    public void testHalfSheetDoNotShowAgainLongTimeout() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME + DURATION_MILLI_SECONDS_LONG + 1);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void banAllItem_blockHalfSheet() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.ACTIVE, CURRENT_TIME));

        mFastPairHalfSheetBlocklist.banAllItem(SUPPRESS_ALL_DURATION_MILLIS);
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME + SUPPRESS_ALL_DURATION_MILLIS - 1);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
    }

    @Test
    public void banAllItem_invokeAgainWithShorterDurationTime_blockHalfSheet() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.ACTIVE, CURRENT_TIME));

        mFastPairHalfSheetBlocklist.banAllItem(SUPPRESS_ALL_DURATION_MILLIS);
        // The 2nd invocation time is shorter than the original one so it's ignored.
        mFastPairHalfSheetBlocklist.banAllItem(SUPPRESS_ALL_DURATION_MILLIS - 1);
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME + SUPPRESS_ALL_DURATION_MILLIS - 1);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
    }

    @Test
    public void banAllItem_releaseHalfSheet() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.ACTIVE, CURRENT_TIME));

        mFastPairHalfSheetBlocklist.banAllItem(SUPPRESS_ALL_DURATION_MILLIS);
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME + SUPPRESS_ALL_DURATION_MILLIS);

        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void banAllItem_extendEndTime_blockHalfSheet() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.ACTIVE, CURRENT_TIME));

        mFastPairHalfSheetBlocklist.banAllItem(SUPPRESS_ALL_DURATION_MILLIS);
        when(mClock.elapsedRealtime()).thenReturn(CURRENT_TIME + SUPPRESS_ALL_DURATION_MILLIS);
        // Another banAllItem comes so the end time is extended.
        mFastPairHalfSheetBlocklist.banAllItem(/* banDurationTimeMillis= */ 1);

        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
    }

    @Test
    public void testHalfSheetTimeOutFirstDismissWithInDuration() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        when(mClock.elapsedRealtime())
            .thenReturn(CURRENT_TIME + DURATION_RESURFACE_DISMISS_HALF_SHEET_MILLISECOND - 1);

        assertThat(
                mFastPairHalfSheetBlocklist.isBlocklisted(
                        HALFSHEET_ID, (int) DURATION_RESURFACE_DISMISS_HALF_SHEET_MILLISECOND))
                .isTrue();
    }

    @Test
    public void testHalfSheetTimeOutFirstDismissOutOfDuration() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        when(mClock.elapsedRealtime())
            .thenReturn(CURRENT_TIME + DURATION_RESURFACE_DISMISS_HALF_SHEET_MILLISECOND + 1);

        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void testHalfSheetReset() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        mFastPairHalfSheetBlocklist.resetBlockState(HALFSHEET_ID);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
                DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }

    @Test
    public void testIsStateExpired() {
        mFastPairHalfSheetBlocklist.put(
                HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        when(mClock.elapsedRealtime())
                .thenReturn(CURRENT_TIME + 1);
        assertThat(mFastPairHalfSheetBlocklist.isStateExpired(HALFSHEET_ID)).isFalse();
        when(mClock.elapsedRealtime())
                .thenReturn(CURRENT_TIME +  STATE_EXPIRATION_MILLISECOND + 1);
        assertThat(mFastPairHalfSheetBlocklist.isStateExpired(HALFSHEET_ID)).isTrue();
    }

    @Test
    public void testForceUpdateState() {
        mFastPairHalfSheetBlocklist.put(HALFSHEET_ID,
                new BlocklistElement(Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN, CURRENT_TIME));
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
            DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isTrue();
        mFastPairHalfSheetBlocklist.forceUpdateState(HALFSHEET_ID, BlocklistState.ACTIVE);
        assertThat(mFastPairHalfSheetBlocklist.isBlocklisted(HALFSHEET_ID,
            DURATION_RESURFACE_HALFSHEET_FIRST_DISMISS_MILLI_SECONDS)).isFalse();
    }
}
