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

package com.android.server.wifi.hal;

import static junit.framework.Assert.assertEquals;

import static org.mockito.Mockito.when;

import java.util.function.Supplier;

public class HalTestUtils {
    /**
     * Check that we get the expected return value when the specified method is called.
     *
     * @param calledMethod Method to call on mDut.
     * @param mockedMethod Method called by mDut to retrieve the value.
     * @param value Value that the mockedMethod should return.
     */
    public static <T> void verifyReturnValue(Supplier<T> calledMethod, T mockedMethod, T value) {
        when(mockedMethod).thenReturn(value);
        T retrievedValue = calledMethod.get();
        assertEquals(value, retrievedValue);
    }
}
