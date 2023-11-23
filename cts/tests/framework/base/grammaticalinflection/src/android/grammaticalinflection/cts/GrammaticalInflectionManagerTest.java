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

package android.grammaticalinflection.cts;

import static android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;

import static com.google.common.truth.Truth.assertThat;

import android.app.GrammaticalInflectionManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.server.wm.ActivityManagerTestBase;
import android.server.wm.TestJournalProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

//TODO(b/263201531): Add test cases that handle onConfigurationChanged in test activity
@RunWith(AndroidJUnit4.class)
public class GrammaticalInflectionManagerTest extends ActivityManagerTestBase {
    private final ComponentName TEST_APP_MAIN_ACTIVITY = new ComponentName(
            TestMainActivity.class.getPackageName(),
            TestMainActivity.class.getCanonicalName());
    private final ComponentName TEST_APP_HANDLE_CONFIG_CHANGE = new ComponentName(
            TestHandleConfigChangeActivity.class.getPackageName(),
            TestHandleConfigChangeActivity.class.getCanonicalName());

    private GrammaticalInflectionManager mGrammaticalInflectionManager;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        final Context context = InstrumentationRegistry.getContext();
        mGrammaticalInflectionManager = context.getSystemService(
                GrammaticalInflectionManager.class);
    }

    @After
    public void tearDown() throws Exception {
        mGrammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                GRAMMATICAL_GENDER_NOT_SPECIFIED);
    }

    @Test
    public void testSetApplicationGender_setFeminine_returnFeminineAfterReCreating() {
        TestJournalProvider.TestJournalContainer.start();
        launchActivity(TEST_APP_MAIN_ACTIVITY);
        mWmState.assertVisibility(TEST_APP_MAIN_ACTIVITY, /* visible*/ true);

        mGrammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_FEMININE);

        assertActivityLifecycle(TEST_APP_MAIN_ACTIVITY, true /* relaunch */);
        assertThat(mGrammaticalInflectionManager.getApplicationGrammaticalGender())
                .isEqualTo(Configuration.GRAMMATICAL_GENDER_FEMININE);
    }

    @Test
    public void testSetApplicationGender_setMasculine_returnMasculineAfterReCreating() {
        TestJournalProvider.TestJournalContainer.start();
        launchActivity(TEST_APP_MAIN_ACTIVITY);
        mWmState.assertVisibility(TEST_APP_MAIN_ACTIVITY, /* visible*/ true);

        mGrammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_MASCULINE);

        assertActivityLifecycle(TEST_APP_MAIN_ACTIVITY, true /* relaunch */);
        assertThat(mGrammaticalInflectionManager.getApplicationGrammaticalGender())
                .isEqualTo(Configuration.GRAMMATICAL_GENDER_MASCULINE);
    }

    @Test
    public void testSetApplicationGender_setMasculine_returnMasculineWithoutReCreating() {
        launchActivity(TEST_APP_HANDLE_CONFIG_CHANGE);
        TestJournalProvider.TestJournalContainer.start();
        mWmState.assertVisibility(TEST_APP_HANDLE_CONFIG_CHANGE, /* visible*/ true);

        mGrammaticalInflectionManager.setRequestedApplicationGrammaticalGender(
                Configuration.GRAMMATICAL_GENDER_MASCULINE);

        assertActivityLifecycle(TEST_APP_HANDLE_CONFIG_CHANGE, false /* relaunch */);
        assertThat(mGrammaticalInflectionManager.getApplicationGrammaticalGender())
                .isEqualTo(Configuration.GRAMMATICAL_GENDER_MASCULINE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetApplicationGender_setInvalidValue_throwException() {
        mGrammaticalInflectionManager.setRequestedApplicationGrammaticalGender(Integer.MIN_VALUE);
    }
}