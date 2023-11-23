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

package com.android.server.adservices.data.topics;

import static com.android.server.adservices.data.topics.TopicsDbTestUtil.doesTableExistAndColumnCountMatch;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** Unit test to test class {@link TopicsDbHelper} */
public class TopicsDbHelperTest {
    @Test
    public void testOnCreate() {
        SQLiteDatabase db = TopicsDbTestUtil.getDbHelperForTest().safeGetReadableDatabase();
        assertNotNull(db);
        assertTrue(doesTableExistAndColumnCountMatch(db, "blocked_topics", 4));
    }

    @Test
    public void testDump() throws Exception {
        String dump;
        String prefix = "fixed, pre is:";
        try (StringWriter sw = new StringWriter()) {
            PrintWriter pw = new PrintWriter(sw);

            TopicsDbHelper dao =
                    TopicsDbHelper.getInstance(
                            InstrumentationRegistry.getInstrumentation().getTargetContext());
            dao.dump(pw, prefix, /* args= */ null);

            pw.flush();
            dump = sw.toString();
        }

        // Content doesn't matter much, we just wanna make sure it doesn't crash (for example,
        // by using the wrong %s / %d tokens) and every line dumps the prefix
        assertDumpHasPrefix(dump, prefix);
    }

    // TODO(b/280677793): move to common code?
    static String[] assertDumpHasPrefix(String dump, String prefix) {
        assertWithMessage("content of dump()").that(dump).isNotEmpty();

        String[] lines = dump.split("\n");
        List<Integer> violatedLineNumbers = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith(prefix)) {
                violatedLineNumbers.add(i);
            }
        }
        if (!violatedLineNumbers.isEmpty()) {
            fail(
                    "Every line should start with '"
                            + prefix
                            + "', but some ("
                            + violatedLineNumbers
                            + ") did not. Full dump(): \n"
                            + dump);
        }
        return lines;
    }
}
