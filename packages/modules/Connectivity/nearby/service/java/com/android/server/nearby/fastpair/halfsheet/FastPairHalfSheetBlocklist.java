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


import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.ACTIVE;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DISMISSED;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN;
import static com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState.DO_NOT_SHOW_AGAIN_LONG;

import android.util.Log;
import android.util.LruCache;

import androidx.annotation.VisibleForTesting;

import com.android.server.nearby.fastpair.blocklist.Blocklist;
import com.android.server.nearby.fastpair.blocklist.BlocklistElement;
import com.android.server.nearby.util.Clock;
import com.android.server.nearby.util.DefaultClock;


/**
 * Maintains a list of half sheet id to tell whether the half sheet should be suppressed or not.
 *
 * <p>When user cancel half sheet, the ble address related half sheet should be in block list and
 * after certain duration of time half sheet can show again.
 */
public class FastPairHalfSheetBlocklist extends LruCache<Integer, BlocklistElement>
        implements Blocklist {
    private static final String TAG = "HalfSheetBlocklist";
    // Number of entries in the FastPair blocklist
    private static final int FAST_PAIR_BLOCKLIST_CACHE_SIZE = 16;
    // Duration between first half sheet dismiss and second half sheet shows: 2 seconds
    private static final int FAST_PAIR_HALF_SHEET_DISMISS_COOL_DOWN_MILLIS = 2000;
    // The timeout to ban half sheet after user trigger the ban logic even number of time : 1 day
    private static final int DURATION_RESURFACE_HALFSHEET_EVEN_NUMBER_BAN_MILLI_SECONDS = 86400000;
    // Timeout for DISMISSED entries in the blocklist to expire : 1 min
    private static final int FAST_PAIR_BLOCKLIST_DISMISSED_HALF_SHEET_TIMEOUT_MILLIS = 60000;
    // The timeout for entries in the blocklist to expire : 1 day
    private static final int STATE_EXPIRATION_MILLI_SECONDS = 86400000;
    private long mEndTimeBanAllItems;
    private final Clock mClock;


    public FastPairHalfSheetBlocklist() {
        // Reuses the size limit from notification cache.
        // Number of entries in the FastPair blocklist
        super(FAST_PAIR_BLOCKLIST_CACHE_SIZE);
        mClock = new DefaultClock();
    }

    @VisibleForTesting
    FastPairHalfSheetBlocklist(int size, Clock clock) {
        super(size);
        mClock = clock;
    }

    /**
     * Checks whether need to show HalfSheet or not.
     *
     * <p> When the HalfSheet {@link BlocklistState} is DISMISS, there is a little cool down period
     * to allow half sheet to reshow.
     * If the HalfSheet {@link BlocklistState} is DO_NOT_SHOW_AGAIN, within durationMilliSeconds
     * from banned start time, the function will return true
     * otherwise it will return false if the status is expired
     * If the HalfSheet {@link BlocklistState} is DO_NOT_SHOW_AGAIN_LONG, the half sheet will be
     * baned for a longer duration.
     *
     * @param id {@link com.android.nearby.halfsheet.HalfSheetActivity} id
     * @param durationMilliSeconds the time duration from item is banned to now
     * @return whether the HalfSheet is blocked to show
     */
    @Override
    public boolean isBlocklisted(int id, int durationMilliSeconds) {
        if (shouldBanAllItem()) {
            return true;
        }
        BlocklistElement entry = get(id);
        if (entry == null) {
            return false;
        }
        if (entry.getState().equals(DO_NOT_SHOW_AGAIN)) {
            Log.d(TAG, "BlocklistState: DO_NOT_SHOW_AGAIN");
            return mClock.elapsedRealtime() < entry.getTimeStamp() + durationMilliSeconds;
        }
        if (entry.getState().equals(DO_NOT_SHOW_AGAIN_LONG)) {
            Log.d(TAG, "BlocklistState: DO_NOT_SHOW_AGAIN_LONG ");
            return mClock.elapsedRealtime()
                    < entry.getTimeStamp()
                    + DURATION_RESURFACE_HALFSHEET_EVEN_NUMBER_BAN_MILLI_SECONDS;
        }

        if (entry.getState().equals(ACTIVE)) {
            Log.d(TAG, "BlocklistState: ACTIVE");
            return false;
        }
        // Get some cool down period for dismiss state
        if (entry.getState().equals(DISMISSED)) {
            Log.d(TAG, "BlocklistState: DISMISSED");
            return mClock.elapsedRealtime()
                    < entry.getTimeStamp() + FAST_PAIR_HALF_SHEET_DISMISS_COOL_DOWN_MILLIS;
        }
        if (dismissStateHasExpired(entry)) {
            Log.d(TAG, "stateHasExpired: True");
            return false;
        }
        return true;
    }

    @Override
    public boolean removeBlocklist(int id) {
        BlocklistElement oldValue = remove(id);
        return oldValue != null;
    }

    /**
     * Updates the HalfSheet blocklist state
     *
     * <p>When the new {@link BlocklistState} has higher priority then old {@link BlocklistState} or
     * the old {@link BlocklistState} status is expired,the function will update the status.
     *
     * @param id HalfSheet id
     * @param state Blocklist state
     * @return update status successful or not
     */
    @Override
    public boolean updateState(int id, BlocklistState state) {
        BlocklistElement entry = get(id);
        if (entry == null || state.hasHigherPriorityThan(entry.getState())
                || dismissStateHasExpired(entry)) {
            Log.d(TAG, "updateState: " + state);
            put(id, new BlocklistElement(state, mClock.elapsedRealtime()));
            return true;
        }
        return false;
    }

    /** Enables lower state to override the higher value state. */
    public void forceUpdateState(int id, BlocklistState state) {
        put(id, new BlocklistElement(state, mClock.elapsedRealtime()));
    }

    /** Resets certain device ban state to active. */
    @Override
    public void resetBlockState(int id) {
        BlocklistElement entry = get(id);
        if (entry != null) {
            put(id, new BlocklistElement(ACTIVE, mClock.elapsedRealtime()));
        }
    }

    /** Checks whether certain device state has expired. */
    public boolean isStateExpired(int id) {
        BlocklistElement entry = get(id);
        if (entry != null) {
            return mClock.elapsedRealtime() > entry.getTimeStamp() + STATE_EXPIRATION_MILLI_SECONDS;
        }
        return false;
    }

    private boolean dismissStateHasExpired(BlocklistElement entry) {
        return mClock.elapsedRealtime()
                > entry.getTimeStamp() + FAST_PAIR_BLOCKLIST_DISMISSED_HALF_SHEET_TIMEOUT_MILLIS;
    }

    /**
     * Updates the end time that all half sheet will be banned.
     */
    void banAllItem(long banDurationTimeMillis) {
        long endTime = mClock.elapsedRealtime() + banDurationTimeMillis;
        if (endTime > mEndTimeBanAllItems) {
            mEndTimeBanAllItems = endTime;
        }
    }

    private boolean shouldBanAllItem() {
        return mClock.elapsedRealtime() < mEndTimeBanAllItems;
    }
}
