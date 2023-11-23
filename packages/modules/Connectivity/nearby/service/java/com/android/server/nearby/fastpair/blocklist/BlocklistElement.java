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

import com.android.server.nearby.fastpair.blocklist.Blocklist.BlocklistState;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetBlocklist;

/** Element in the {@link FastPairHalfSheetBlocklist} */
public class BlocklistElement {
    private final long mTimeStamp;
    private final BlocklistState mState;

    public BlocklistElement(BlocklistState state, long timeStamp) {
        this.mState = state;
        this.mTimeStamp = timeStamp;
    }

    public Long getTimeStamp() {
        return mTimeStamp;
    }

    public BlocklistState getState() {
        return mState;
    }
}
