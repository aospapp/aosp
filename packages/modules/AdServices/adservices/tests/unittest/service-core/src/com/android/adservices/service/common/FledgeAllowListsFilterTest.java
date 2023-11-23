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

package com.android.adservices.service.common;

import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CommonFixture;

import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoSession;

public class FledgeAllowListsFilterTest {
    private static final int API_NAME_LOGGING_ID = 1;

    private final AdServicesLogger mAdServicesLoggerMock =
            ExtendedMockito.mock(AdServicesLoggerImpl.class);

    private FledgeAllowListsFilter mFledgeAllowListsFilter;

    public MockitoSession mMockitoSession;

    @Before
    public void setup() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(AllowLists.class)
                        .initMocks(this)
                        .startMocking();
        mFledgeAllowListsFilter =
                new FledgeAllowListsFilter(CommonFixture.FLAGS_FOR_TEST, mAdServicesLoggerMock);
    }

    @After
    public void teardown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testIsAllowed() {
        ExtendedMockito.when(
                        AllowLists.isPackageAllowListed(
                                CommonFixture.FLAGS_FOR_TEST.getPpapiAppAllowList(),
                                CommonFixture.TEST_PACKAGE_NAME))
                .thenReturn(true);
        mFledgeAllowListsFilter.assertAppCanUsePpapi(
                CommonFixture.TEST_PACKAGE_NAME, API_NAME_LOGGING_ID);

        verifyZeroInteractions(mAdServicesLoggerMock);
    }

    @Test
    public void testNotAllowed() {
        ExtendedMockito.when(
                        AllowLists.isPackageAllowListed(
                                CommonFixture.FLAGS_FOR_TEST.getPpapiAppAllowList(),
                                CommonFixture.TEST_PACKAGE_NAME))
                .thenReturn(false);
        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mFledgeAllowListsFilter.assertAppCanUsePpapi(
                                        CommonFixture.TEST_PACKAGE_NAME, API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE,
                exception.getMessage());
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(
                        eq(API_NAME_LOGGING_ID), eq(STATUS_CALLER_NOT_ALLOWED), anyInt());

        verifyNoMoreInteractions(mAdServicesLoggerMock);
    }

    @Test
    public void nullAppPackageName() {
        assertThrows(
                NullPointerException.class,
                () -> mFledgeAllowListsFilter.assertAppCanUsePpapi(null, API_NAME_LOGGING_ID));

        verifyZeroInteractions(mAdServicesLoggerMock);
    }
}
