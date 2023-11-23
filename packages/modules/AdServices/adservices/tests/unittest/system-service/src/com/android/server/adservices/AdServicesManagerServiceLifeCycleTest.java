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
package com.android.server.adservices;

import static com.android.adservices.mockito.ExtendedMockitoExpectations.mockGetLocalManager;
import static com.android.adservices.mockito.ExtendedMockitoExpectations.mockGetLocalManagerNotFound;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalManagerRegistry;
import com.android.server.sdksandbox.SdkSandboxManagerLocal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

public final class AdServicesManagerServiceLifeCycleTest {

    @Mock private Context mContext;
    @Mock private AdServicesManagerService mService;
    @Mock private SdkSandboxManagerLocal mSdkSandboxManagerLocal;

    // Need to use a spy to mock publishBinderService()
    private AdServicesManagerService.Lifecycle mSpyLifecycle;
    private StaticMockitoSession mMockSession;

    // TODO(b/281577492): use ExtendedMockitoRule and remove these 2 session methods
    private void startMockitoSession() {
        mMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(LocalManagerRegistry.class)
                        .strictness(Strictness.LENIENT)
                        .initMocks(this)
                        .startMocking();
    }

    @After
    public void finishMockitoSession() {
        mMockSession.finishMocking();
    }

    @Before
    public void setUp() {
        startMockitoSession();
        mSpyLifecycle = spy(new AdServicesManagerService.Lifecycle(mContext, mService));
        doNothing().when(mSpyLifecycle).publishBinderService();
        mockGetLocalManager(SdkSandboxManagerLocal.class, mSdkSandboxManagerLocal);
    }

    @Test
    public void testOnStart_noSdkSandboxManagerLocal() {
        mockGetLocalManagerNotFound(SdkSandboxManagerLocal.class);

        assertThrows(IllegalStateException.class, () -> mSpyLifecycle.onStart());
    }

    @Test
    public void testOnStart_binderRegistrationFails() {
        doThrow(new RuntimeException("D'OH!")).when(mSpyLifecycle).publishBinderService();

        mSpyLifecycle.onStart();

        verifyBinderPublished();
        verifyAdServiceRegisteredOnSdkManager(/* published= */ false);
    }

    @Test
    public void testOnStart() {
        mSpyLifecycle.onStart();

        verifyBinderPublished();
        verifyAdServiceRegisteredOnSdkManager(/* published= */ true);
    }

    private void verifyAdServiceRegisteredOnSdkManager(boolean published) {
        verify(mSdkSandboxManagerLocal).registerAdServicesManagerService(mService, published);
    }

    private void verifyBinderPublished() {
        verify(mSpyLifecycle).publishBinderService();
    }
}
