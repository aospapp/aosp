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

package com.android.ondevicepersonalization.services.data.events;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class QueryTest {
    @Test
    public void testBuilderAndEquals() {
        long queryId = 1;
        byte[] queryData = "data".getBytes();
        String servicePackageName = "servicePackageName";
        long timeMillis = 1;
        Query query1 = new Query.Builder()
                .setQueryId(queryId)
                .setQueryData(queryData)
                .setServicePackageName(servicePackageName)
                .setTimeMillis(timeMillis)
                .build();
        assertEquals(query1.getQueryId(), queryId);
        assertArrayEquals(query1.getQueryData(), queryData);
        assertEquals(query1.getServicePackageName(), servicePackageName);
        assertEquals(query1.getTimeMillis(), timeMillis);

        Query query2 = new Query.Builder(
                queryId, timeMillis, servicePackageName, queryData).build();
        assertEquals(query1, query2);
        assertEquals(query1.hashCode(), query2.hashCode());
    }

    @Test
    public void testBuildTwiceThrows() {
        long queryId = 1;
        byte[] queryData = "data".getBytes();
        String servicePackageName = "servicePackageName";
        long timeMillis = 1;
        Query.Builder builder = new Query.Builder()
                .setQueryId(queryId)
                .setQueryData(queryData)
                .setServicePackageName(servicePackageName)
                .setTimeMillis(timeMillis);
        builder.build();
        assertThrows(IllegalStateException.class, () -> builder.build());
    }
}
