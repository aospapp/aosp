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

package com.android.adservices.service.topics.fixture;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import android.os.SystemProperties;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.modules.utils.testing.StaticMockFixture;

/** This Fixture class is to force {@link SystemProperties} to return its default value. */
public final class SysPropForceDefaultValueFixture implements StaticMockFixture {
    @Override
    public StaticMockitoSessionBuilder setUpMockedClasses(
            StaticMockitoSessionBuilder sessionBuilder) {
        sessionBuilder.spyStatic(SystemProperties.class);
        return sessionBuilder;
    }

    @Override
    public void setUpMockBehaviors() {
        // Mock all getters in SystemProperties to return its default values. The signature of these
        // getters is always (key, defaultVal).
        final int defaultValueArgumentIndex = 1;
        ExtendedMockito.doAnswer(invocation -> invocation.getArgument(defaultValueArgumentIndex))
                .when(() -> SystemProperties.getInt(anyString(), anyInt()));
        ExtendedMockito.doAnswer(invocation -> invocation.getArgument(defaultValueArgumentIndex))
                .when(() -> SystemProperties.getLong(anyString(), anyLong()));
        ExtendedMockito.doAnswer(invocation -> invocation.getArgument(defaultValueArgumentIndex))
                .when(() -> SystemProperties.getBoolean(anyString(), anyBoolean()));
        ExtendedMockito.doAnswer(invocation -> invocation.getArgument(defaultValueArgumentIndex))
                .when(() -> SystemProperties.get(anyString(), anyString()));

        // When using the method SystemProperties.get(key), the default value is not passed in.
        // Return an empty string to bypass SystemProperties.get(key). Another option is to pass in
        // a string with invalid number format like "1-1", but it'll log an error which may abuse
        // the logcat. The implementation is based on the usage of SystemProperties.get(key).
        ExtendedMockito.doReturn("").when(() -> SystemProperties.get(anyString()));
    }

    @Override
    public void tearDown() {}
}
