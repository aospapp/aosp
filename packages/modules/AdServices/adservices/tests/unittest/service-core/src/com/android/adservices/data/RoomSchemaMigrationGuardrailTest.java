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

package com.android.adservices.data;

import android.annotation.NonNull;
import android.content.Context;

import androidx.room.RoomDatabase;
import androidx.room.migration.bundle.EntityBundle;
import androidx.room.migration.bundle.FieldBundle;
import androidx.room.migration.bundle.SchemaBundle;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.adservices.data.adselection.AdSelectionDatabase;
import com.android.adservices.data.adselection.AdSelectionEncryptionDatabase;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** This UT is a guardrail to schema migration managed by Room. */
public class RoomSchemaMigrationGuardrailTest {
    // Note that this is not the context of this test, but the different context whose assets folder
    // is adservices/service-core/schemas
    private static final Context TARGET_CONTEXT =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final List<Class<? extends RoomDatabase>> DATABASE_CLASSES =
            ImmutableList.of(
                    CustomAudienceDatabase.class,
                    AdSelectionDatabase.class,
                    AdSelectionEncryptionDatabase.class,
                    SharedStorageDatabase.class);
    private static final List<DatabaseWithVersion> BYPASS_DATABASE_VERSIONS_NEW_FIELD_ONLY =
            ImmutableList.of(new DatabaseWithVersion(CustomAudienceDatabase.class, 2));

    @Test
    public void validateDatabaseMigration() throws IOException {
        List<String> errors = new ArrayList<>();
        List<DatabaseWithVersion> databaseClassesWithNewestVersion =
                validateAndGetDatabaseClassesWithNewestVersionNumber(errors);
        for (DatabaseWithVersion databaseWithVersion : databaseClassesWithNewestVersion) {
            validateNewFieldOnly(databaseWithVersion, errors);
        }
        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    String.format(
                            "Finish validating room databases with error \n %s",
                            String.join("\n", errors)));
        }
    }

    private List<DatabaseWithVersion> validateAndGetDatabaseClassesWithNewestVersionNumber(
            List<String> errors) throws IOException {
        ImmutableList.Builder<DatabaseWithVersion> result = new ImmutableList.Builder<>();
        for (Class<? extends RoomDatabase> clazz : DATABASE_CLASSES) {
            try {
                final int newestDatabaseVersion = getNewestDatabaseVersion(clazz);
                result.add(new DatabaseWithVersion(clazz, newestDatabaseVersion));
            } catch (Exception e) {
                errors.add(
                        String.format(
                                "Fail to get database schema for %s, with error %s.",
                                clazz.getCanonicalName(), e.getMessage()));
            }
        }
        return result.build();
    }

    private int getNewestDatabaseVersion(Class<? extends RoomDatabase> database)
            throws IOException {
        return Arrays.stream(TARGET_CONTEXT.getAssets().list(database.getCanonicalName()))
                .map(p -> p.split("\\.")[0])
                .mapToInt(Integer::parseInt)
                .max()
                .orElseThrow();
    }

    private void validateNewFieldOnly(DatabaseWithVersion databaseWithVersion, List<String> errors)
            throws IOException {
        // Custom audience table v1 to v2 is violating the policy. Skip it.
        if (BYPASS_DATABASE_VERSIONS_NEW_FIELD_ONLY.contains(databaseWithVersion)) {
            return;
        }
        int newestDatabaseVersion = databaseWithVersion.mVersion;
        Class<? extends RoomDatabase> roomDatabaseClass = databaseWithVersion.mRoomDatabaseClass;
        if (databaseWithVersion.mVersion == 1) {
            return;
        }

        SchemaBundle oldSchemaBundle = loadSchema(roomDatabaseClass, newestDatabaseVersion - 1);
        SchemaBundle newSchemaBundle = loadSchema(roomDatabaseClass, newestDatabaseVersion);

        Map<String, EntityBundle> oldTables =
                oldSchemaBundle.getDatabase().getEntitiesByTableName();
        Map<String, EntityBundle> newTables =
                newSchemaBundle.getDatabase().getEntitiesByTableName();

        // We don't care new table in a new DB version. So iterate through the old version.
        for (Map.Entry<String, EntityBundle> e : oldTables.entrySet()) {
            String tableName = e.getKey();

            // table in old version must show in new.
            if (!newTables.containsKey(tableName)) {
                errors.add(
                        String.format(
                                "New version DB is missing table %s present in old version",
                                tableName));
                continue;
            }

            EntityBundle oldEntityBundle = e.getValue();
            EntityBundle newEntityBundle = newTables.get(tableName);

            for (FieldBundle oldFieldBundle : oldEntityBundle.getFields()) {
                if (newEntityBundle.getFields().stream().noneMatch(oldFieldBundle::isSchemaEqual)) {
                    errors.add(
                            String.format(
                                    "Table %s and field %s: Missing field in new version or"
                                            + " mismatch field in new and old version.",
                                    tableName, oldEntityBundle));
                }
            }
        }
    }

    private SchemaBundle loadSchema(Class<? extends RoomDatabase> database, int version)
            throws IOException {
        InputStream input =
                TARGET_CONTEXT
                        .getAssets()
                        .open(database.getCanonicalName() + "/" + version + ".json");
        return SchemaBundle.deserialize(input);
    }

    private static class DatabaseWithVersion {
        @NonNull private final Class<? extends RoomDatabase> mRoomDatabaseClass;
        private final int mVersion;

        DatabaseWithVersion(@NonNull Class<? extends RoomDatabase> roomDatabaseClass, int version) {
            mRoomDatabaseClass = roomDatabaseClass;
            mVersion = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DatabaseWithVersion)) return false;
            DatabaseWithVersion that = (DatabaseWithVersion) o;
            return mVersion == that.mVersion && mRoomDatabaseClass.equals(that.mRoomDatabaseClass);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mRoomDatabaseClass, mVersion);
        }

        @Override
        public String toString() {
            return "DatabaseWithVersion{"
                    + "mRoomDatabaseClass="
                    + mRoomDatabaseClass
                    + ", mVersion="
                    + mVersion
                    + '}';
        }
    }
}
