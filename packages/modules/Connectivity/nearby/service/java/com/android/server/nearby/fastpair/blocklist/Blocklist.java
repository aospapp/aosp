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

package com.android.server.nearby.fastpair.blocklist;


/**
 * Skeletal implementation of Blocklist
 *
 * <p>Controls the frequency to show the available device to users.
 */
public interface Blocklist {

    /** Checks certain item is blocked within durationSeconds. */
    boolean isBlocklisted(int id, int durationSeconds);

    /** Updates the HalfSheet blocklist state for a given id. */
    boolean updateState(int id, BlocklistState state);

    /** Removes the HalfSheet blocklist. */
    boolean removeBlocklist(int id);

    /** Resets certain device ban state to active. */
    void resetBlockState(int id);

    /**
     * Used for indicate what state is the blocklist item.
     *
     * <p>The different states have differing priorities and higher priority states will override
     * lower one.
     * More details and state transition diagram，
     * see: https://docs.google.com/document/d/1wzE5CHXTkzKJY-2AltSrxOVteom2Nebc1sbjw1Tt7BQ/edit?usp=sharing&resourcekey=0-L-wUz3Hw5gZPThm5VPwHOQ
     */
    enum BlocklistState {
        UNKNOWN(0),
        ACTIVE(1),
        DISMISSED(2),
        PAIRING(3),
        PAIRED(4),
        DO_NOT_SHOW_AGAIN(5),
        DO_NOT_SHOW_AGAIN_LONG(6);

        private final int mValue;

        BlocklistState(final int value) {
            this.mValue = value;
        }

        public boolean hasHigherPriorityThan(BlocklistState otherState) {
            return this.mValue > otherState.mValue;
        }
    }
}
