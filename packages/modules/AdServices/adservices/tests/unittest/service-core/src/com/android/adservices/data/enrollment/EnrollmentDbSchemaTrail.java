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

package com.android.adservices.data.enrollment;

import static com.android.adservices.data.enrollment.EnrollmentTables.EnrollmentDataContract;

import com.google.common.collect.ImmutableMap;

import java.util.Collection;
import java.util.Map;

/**
 * Has scripts to create database at any version. To introduce migration to a new version x, this
 * class should have one entry of {@link #CREATE_TABLES_STATEMENTS_BY_VERSION} for version x. These
 * entries will cause creation of method {@code getCreateStatementByTableVx}, where the previous
 * version's (x-1) scripts will be revised to create scripts for version x.
 */
public class EnrollmentDbSchemaTrail {
    private static final String CREATE_TABLE_ENROLLMENT_V1 =
            "CREATE TABLE "
                    + EnrollmentTables.EnrollmentDataContract.TABLE
                    + " ("
                    + EnrollmentTables.EnrollmentDataContract.ENROLLMENT_ID
                    + " TEXT PRIMARY KEY NOT NULL, "
                    + EnrollmentTables.EnrollmentDataContract.COMPANY_ID
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract.SDK_NAMES
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_SOURCE_REGISTRATION_URL
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_TRIGGER_REGISTRATION_URL
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_REPORTING_URL
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract
                            .REMARKETING_RESPONSE_BASED_REGISTRATION_URL
                    + " TEXT, "
                    + EnrollmentTables.EnrollmentDataContract.ENCRYPTION_KEY_URL
                    + " TEXT "
                    + ")";

    private static final Map<String, String> CREATE_STATEMENT_BY_TABLE_V1 =
            ImmutableMap.of(EnrollmentDataContract.TABLE, CREATE_TABLE_ENROLLMENT_V1);

    private static final Map<Integer, Collection<String>> CREATE_TABLES_STATEMENTS_BY_VERSION =
            new ImmutableMap.Builder<Integer, Collection<String>>()
                    .put(1, CREATE_STATEMENT_BY_TABLE_V1.values())
                    .build();

    /**
     * Returns a map of table to the respective create statement at the provided version.
     *
     * @param version version for which create statements are requested
     * @return map of table to their create statement
     */
    public static Collection<String> getCreateTableStatementsByVersion(int version) {
        if (version < 1) {
            throw new IllegalArgumentException("Unsupported version " + version);
        }

        return CREATE_TABLES_STATEMENTS_BY_VERSION.get(version);
    }
}
