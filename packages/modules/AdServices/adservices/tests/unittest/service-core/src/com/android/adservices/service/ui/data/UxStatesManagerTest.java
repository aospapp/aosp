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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;

import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class UxStatesManagerTest {

    private UxStatesManager mUxStatesManager;
    private MockitoSession mStaticMockSession;

    @Mock private Flags mMockFlags;

    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);

        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(UxStatesManager.class)
                        .spyStatic(FlagsFactory.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        // Set up the test map before calling the UxStatesManager c-tor.
        setUpTestMap();

        mUxStatesManager = new UxStatesManager(mMockFlags);
    }

    @After
    public void teardown() throws IOException {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    private void setUpTestMap() {
        Map<String, Boolean> testMap = new HashMap<>();
        testMap.put("TRUE_FLAG_KEY", true);
        testMap.put("FALSE_FLAG_KEY", false);
        doReturn(testMap).when(mMockFlags).getUxFlags();
    }

    @Test
    public void getFlagTest_emptyFlagKey() {
        assertThat(mUxStatesManager.getFlag("")).isFalse();
    }

    @Test
    public void getFlagTest_invalidFlagKey() {
        assertThat(mUxStatesManager.getFlag("INVALID_FLAG_KEY")).isFalse();
    }

    @Test
    public void getFlagTest_trueFlagKey() {
        assertThat(mUxStatesManager.getFlag("TRUE_FLAG_KEY")).isTrue();
    }

    @Test
    public void getFlagTest_falseFlagKey() {
        assertThat(mUxStatesManager.getFlag("FALSE_FLAG_KEY")).isFalse();
    }
}
