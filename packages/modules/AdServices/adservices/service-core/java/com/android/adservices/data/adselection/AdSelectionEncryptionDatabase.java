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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.android.adservices.data.common.FledgeRoomConverters;
import com.android.internal.annotations.GuardedBy;

import java.util.Objects;

/** Room based database for ad selection encryption keys. */
@Database(
        entities = {DBEncryptionKey.class},
        version = AdSelectionEncryptionDatabase.ENCRYPTION_DATABASE_VERSION)
@TypeConverters({FledgeRoomConverters.class})
public abstract class AdSelectionEncryptionDatabase extends RoomDatabase {
    public static final int ENCRYPTION_DATABASE_VERSION = 1;
    public static final String ENCRYPTION_DATABASE_NAME = "adselectionencryption.db";

    private static final Object SINGLETON_LOCK = new Object();

    @GuardedBy("SINGLETON_LOCK")
    private static AdSelectionEncryptionDatabase sSingleton = null;

    /** Returns an instance of the AdSelectionEncryptionDatabase given a context. */
    public static AdSelectionEncryptionDatabase getInstance(@NonNull Context context) {
        Objects.requireNonNull(context, "Context must be present.");
        synchronized (SINGLETON_LOCK) {
            if (Objects.isNull(sSingleton)) {
                sSingleton =
                        Room.databaseBuilder(
                                        context,
                                        AdSelectionEncryptionDatabase.class,
                                        ENCRYPTION_DATABASE_NAME)
                                .fallbackToDestructiveMigration()
                                .build();
            }
            return sSingleton;
        }
    }

    /** @return a Dao to access entities in EncryptionKey database. */
    public abstract EncryptionKeyDao encryptionKeyDao();
}
