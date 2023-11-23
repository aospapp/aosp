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

package com.android.server.nearby.util.encryption;

import androidx.annotation.Nullable;

/**
 * A Cryptor that returns the original data without actual encryption
 */
public class CryptorImpFake extends Cryptor {
    // Lazily instantiated when {@link #getInstance()} is called.
    @Nullable
    private static CryptorImpFake sCryptor;

    /** Returns an instance of CryptorImpFake. */
    public static CryptorImpFake getInstance() {
        if (sCryptor == null) {
            sCryptor = new CryptorImpFake();
        }
        return sCryptor;
    }

    private CryptorImpFake() {
    }
}
