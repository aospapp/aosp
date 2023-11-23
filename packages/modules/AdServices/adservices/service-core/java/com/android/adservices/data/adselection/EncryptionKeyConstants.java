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

package com.android.adservices.data.adselection;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Constants used in the EncryptionKey Datastore. */
public final class EncryptionKeyConstants {
    /** IntDef to classify different key types. */
    @IntDef(
            value = {
                EncryptionKeyType.ENCRYPTION_KEY_TYPE_INVALID,
                EncryptionKeyType.ENCRYPTION_KEY_TYPE_AUCTION,
                EncryptionKeyType.ENCRYPTION_KEY_TYPE_QUERY,
                EncryptionKeyType.ENCRYPTION_KEY_TYPE_JOIN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EncryptionKeyType {
        int ENCRYPTION_KEY_TYPE_INVALID = 0;
        int ENCRYPTION_KEY_TYPE_AUCTION = 1;
        int ENCRYPTION_KEY_TYPE_QUERY = 2;
        int ENCRYPTION_KEY_TYPE_JOIN = 3;
    }
}
