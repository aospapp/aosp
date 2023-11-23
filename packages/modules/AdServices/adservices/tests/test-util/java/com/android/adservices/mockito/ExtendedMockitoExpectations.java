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
package com.android.adservices.mockito;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import com.android.server.LocalManagerRegistry;

/**
 * Provides Mockito expectation for common calls.
 *
 * <p><b>NOTE: </b> most expectations require {@code spyStatic()} or {@code mockStatic()} in the
 * {@link com.android.dx.mockito.inline.extended.StaticMockitoSession session} ahead of time - this
 * helper doesn't check that such calls were made, it's up to the caller to do so.
 */
public final class ExtendedMockitoExpectations {

    /**
     * Mocks a call to {@link LocalManagerRegistry#getManager(Class)}, returning the given {@code
     * manager}.
     */
    public static <T> void mockGetLocalManager(Class<T> managerClass, T manager) {
        doReturn(manager).when(() -> LocalManagerRegistry.getManager(managerClass));
    }

    /**
     * Mocks a call to {@link LocalManagerRegistry#getManager(Class)}, returning the given {@code
     * null}.
     */
    public static void mockGetLocalManagerNotFound(Class<?> managerClass) {
        doReturn(null).when(() -> LocalManagerRegistry.getManager(managerClass));
    }

    private ExtendedMockitoExpectations() {
        throw new UnsupportedOperationException("Provides only static methods");
    }
}
