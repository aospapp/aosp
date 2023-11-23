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
package com.android.adservices.service.ui.data;

import android.annotation.NonNull;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;

import java.util.Map;

/**
 * Manager that deals with all UX related states. All other UX code should use this class to read
 * from/write to data stores when needed. Specifically, this class:
 * <li>Reads sessionized UX flags from {@code Flags}, and provide these flags through the getFlags
 *     API.
 * <li>Reads and writes UX persistent states to its own {@code BooleanFileDatastore} when the source
 *     involves PP_API.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class UxStatesManager {

    private static final Object LOCK = new Object();
    private static volatile UxStatesManager sUxStatesManager;
    private final Map<String, Boolean> mUxFlags;

    UxStatesManager(@NonNull Flags flags) {
        mUxFlags = flags.getUxFlags();
    }

    /** Returns an instansce of the UxStatesManager. */
    @NonNull
    public static UxStatesManager getInstance() {

        if (sUxStatesManager == null) {
            synchronized (LOCK) {
                if (sUxStatesManager == null) {
                    sUxStatesManager = new UxStatesManager(FlagsFactory.getFlags());
                }
            }
        }

        return sUxStatesManager;
    }

    /** Return the sessionized UX flags. */
    public boolean getFlag(String uxFlagKey) {
        Boolean value = mUxFlags.get(uxFlagKey);
        return value != null ? value : false;
    }
}
