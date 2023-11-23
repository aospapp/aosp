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

package com.android.adservices.service.customaudience;


import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.adservices.common.CommonFixture;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;

import com.android.adservices.customaudience.DBCustomAudienceFixture;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.service.common.Validator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Clock;

@RunWith(MockitoJUnitRunner.class)
public class CustomAudienceImplTest {
    private static final CustomAudience VALID_CUSTOM_AUDIENCE =
            CustomAudienceFixture.getValidBuilderForBuyerFilters(CommonFixture.VALID_BUYER_1)
                    .build();

    private static final DBCustomAudience VALID_DB_CUSTOM_AUDIENCE =
            DBCustomAudienceFixture.getValidBuilderByBuyer(CommonFixture.VALID_BUYER_1).build();

    @Mock
    private CustomAudienceDao mCustomAudienceDao;
    @Mock private CustomAudienceQuantityChecker mCustomAudienceQuantityChecker;
    @Mock private Validator<CustomAudience> mCustomAudienceValidator;
    @Mock private Clock mClock;

    public CustomAudienceImpl mImpl;

    @Before
    public void setup() {
        mImpl =
                new CustomAudienceImpl(
                        mCustomAudienceDao,
                        mCustomAudienceQuantityChecker,
                        mCustomAudienceValidator,
                        mClock,
                        CommonFixture.FLAGS_FOR_TEST);
    }

    @Test
    public void testJoinCustomAudience_runNormally() {

        when(mClock.instant()).thenReturn(CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI);

        mImpl.joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);

        verify(mCustomAudienceDao)
                .insertOrOverwriteCustomAudience(
                        VALID_DB_CUSTOM_AUDIENCE,
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        verify(mClock).instant();
        verify(mCustomAudienceQuantityChecker)
                .check(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceValidator).validate(VALID_CUSTOM_AUDIENCE);
        verifyNoMoreInteractions(mClock, mCustomAudienceDao, mCustomAudienceValidator);
    }

    @Test
    public void testLeaveCustomAudience_runNormally() {
        mImpl.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME);

        verify(mCustomAudienceDao)
                .deleteAllCustomAudienceDataByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);

        verifyNoMoreInteractions(
                mClock,
                mCustomAudienceDao,
                mCustomAudienceQuantityChecker,
                mCustomAudienceValidator);
    }
}
