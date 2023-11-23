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

package com.android.adservices.adselection;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.download.MddJobService;
import com.android.adservices.service.Flags;
import com.android.adservices.service.MaintenanceJobService;
import com.android.adservices.service.adselection.AdSelectionServiceImpl;
import com.android.adservices.service.common.PackageChangedReceiver;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;

/** Unit test for {@link AdSelectionService} */
public class AdSelectionServiceTest {

    private final Flags mFlagsWithAdSelectionSwitchOnGaUxDisabled =
            new FlagsWithKillSwitchOnGaUxDisabled();
    private final Flags mFlagsWithAdSelectionSwitchOffGaUxDisabled =
            new FlagsWithKillSwitchOffGaUxDisabled();
    private final Flags mFlagsWithAdSelectionSwitchOnGaUxEnabled =
            new FlagsWithKillSwitchOnGaUxEnabled();
    private final Flags mFlagsWithAdSelectionSwitchOffGaUxEnabled =
            new FlagsWithKillSwitchOffGaUxEnabled();

    @Mock private AdSelectionServiceImpl mMockAdSelectionServiceImpl;
    @Mock private ConsentManager mConsentManagerMock;
    @Mock private PackageManager mPackageManagerMock;

    private MockitoSession mStaticMockSession;

    @Before
    public void setup() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(ConsentManager.class)
                        .spyStatic(AdSelectionServiceImpl.class)
                        .spyStatic(PackageChangedReceiver.class)
                        .mockStatic(MddJobService.class)
                        .mockStatic(MaintenanceJobService.class)
                        .initMocks(this)
                        .startMocking();
    }

    @After
    public void teardown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testBindableAdSelectionServiceKillSwitchOnGaUxDisabled() {
        AdSelectionService adSelectionService =
                new AdSelectionService(mFlagsWithAdSelectionSwitchOnGaUxDisabled);
        adSelectionService.onCreate();
        IBinder binder = adSelectionService.onBind(getIntentForAdSelectionService());
        assertNull(binder);

        verify(mConsentManagerMock, never()).getConsent();
        verify(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()), never());
    }

    @Test
    public void testBindableAdSelectionServiceKillSwitchOffGaUxDisabled() {
        doReturn(mMockAdSelectionServiceImpl)
                .when(() -> AdSelectionServiceImpl.create(any(Context.class)));
        doReturn(mConsentManagerMock).when(() -> ConsentManager.getInstance(any(Context.class)));
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        ExtendedMockito.doReturn(true)
                .when(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
        doReturn(true).when(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()));
        doReturn(true).when(() -> MaintenanceJobService.scheduleIfNeeded(any(), anyBoolean()));

        AdSelectionService adSelectionServiceSpy =
                new AdSelectionService(mFlagsWithAdSelectionSwitchOffGaUxDisabled);

        spyOn(adSelectionServiceSpy);
        doReturn(mPackageManagerMock).when(adSelectionServiceSpy).getPackageManager();

        adSelectionServiceSpy.onCreate();
        IBinder binder = adSelectionServiceSpy.onBind(getIntentForAdSelectionService());
        assertNotNull(binder);

        verify(mConsentManagerMock).getConsent();
        verify(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
        verify(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()));
        verify(() -> MaintenanceJobService.scheduleIfNeeded(any(), anyBoolean()));
    }

    /**
     * Test whether the service is not bindable when the kill switch is on with the GA UX flag on.
     */
    @Test
    public void testBindableAdSelectionServiceKillSwitchOnGaUxEnabled() {
        AdSelectionService adSelectionService =
                new AdSelectionService(mFlagsWithAdSelectionSwitchOnGaUxEnabled);
        adSelectionService.onCreate();
        IBinder binder = adSelectionService.onBind(getIntentForAdSelectionService());
        assertNull(binder);

        verify(mConsentManagerMock, never()).getConsent();
        verify(mConsentManagerMock, never()).getConsent(any());
        verify(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()), never());
    }

    /**
     * Test whether the service is bindable and works properly when the kill switch is off with the
     * GA UX flag on.
     */
    @Test
    public void testBindableAdSelectionServiceKillSwitchOffGaUxEnabled() {
        doReturn(mMockAdSelectionServiceImpl)
                .when(() -> AdSelectionServiceImpl.create(any(Context.class)));
        doReturn(mConsentManagerMock).when(() -> ConsentManager.getInstance(any(Context.class)));
        doReturn(AdServicesApiConsent.GIVEN)
                .when(mConsentManagerMock)
                .getConsent(eq(AdServicesApiType.FLEDGE));
        ExtendedMockito.doReturn(true)
                .when(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
        doReturn(true).when(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()));
        doReturn(true).when(() -> MaintenanceJobService.scheduleIfNeeded(any(), anyBoolean()));

        AdSelectionService adSelectionServiceSpy =
                new AdSelectionService(mFlagsWithAdSelectionSwitchOffGaUxEnabled);

        spyOn(adSelectionServiceSpy);
        doReturn(mPackageManagerMock).when(adSelectionServiceSpy).getPackageManager();

        adSelectionServiceSpy.onCreate();
        IBinder binder = adSelectionServiceSpy.onBind(getIntentForAdSelectionService());
        assertNotNull(binder);

        verify(mConsentManagerMock, never()).getConsent();
        verify(mConsentManagerMock).getConsent(eq(AdServicesApiType.FLEDGE));
        verify(() -> PackageChangedReceiver.enableReceiver(any(Context.class), any()));
        verify(() -> MddJobService.scheduleIfNeeded(any(), anyBoolean()));
        verify(() -> MaintenanceJobService.scheduleIfNeeded(any(), anyBoolean()));
    }

    private Intent getIntentForAdSelectionService() {
        return new Intent(ApplicationProvider.getApplicationContext(), AdSelectionService.class);
    }

    private static class FlagsWithKillSwitchOnGaUxDisabled implements Flags {
        @Override
        public boolean getFledgeSelectAdsKillSwitch() {
            return true;
        }

        @Override
        public boolean getGaUxFeatureEnabled() {
            return false;
        }
    }

    private static class FlagsWithKillSwitchOffGaUxDisabled implements Flags {
        @Override
        public boolean getFledgeSelectAdsKillSwitch() {
            return false;
        }

        @Override
        public boolean getGaUxFeatureEnabled() {
            return false;
        }
    }

    private static class FlagsWithKillSwitchOnGaUxEnabled implements Flags {
        @Override
        public boolean getFledgeSelectAdsKillSwitch() {
            return true;
        }

        @Override
        public boolean getGaUxFeatureEnabled() {
            return true;
        }
    }

    private static class FlagsWithKillSwitchOffGaUxEnabled implements Flags {
        @Override
        public boolean getFledgeSelectAdsKillSwitch() {
            return false;
        }

        @Override
        public boolean getGaUxFeatureEnabled() {
            return true;
        }
    }
}
