/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.telephony.RadioAccessFamily;
import android.telephony.TelephonyManager;

import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.subscription.SubscriptionManagerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.Locale;

/**
 * Unit Test for PhoneInterfaceManager.
 */
@RunWith(AndroidJUnit4.class)
public class PhoneInterfaceManagerTest extends TelephonyTestBase {
    private PhoneInterfaceManager mPhoneInterfaceManager;
    private SharedPreferences mSharedPreferences;
    private IIntegerConsumer mIIntegerConsumer;

    @Mock
    PhoneGlobals mPhoneGlobals;
    @Mock
    Phone mPhone;

    @Mock
    private SubscriptionManagerService mSubscriptionManagerService;

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        super.setUp();
        // Note that PhoneInterfaceManager is a singleton. Calling init gives us a handle to the
        // global singleton, but the context that is passed in is unused if the phone app is already
        // alive on a test devices. You must use the spy to mock behavior. Mocks stemming from the
        // passed context will remain unused.
        mPhoneInterfaceManager = spy(PhoneInterfaceManager.init(mPhoneGlobals));
        doReturn(mSubscriptionManagerService).when(mPhoneInterfaceManager)
                .getSubscriptionManagerService();
        TelephonyManager.setupISubForTest(mSubscriptionManagerService);
        mSharedPreferences = mPhoneInterfaceManager.getSharedPreferences();
        mSharedPreferences.edit().remove(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED).commit();
        mIIntegerConsumer = mock(IIntegerConsumer.class);
    }

    @Test
    public void cleanUpAllowedNetworkTypes_validPhoneAndSubId_doSetAllowedNetwork() {
        long defaultNetworkType = RadioAccessFamily.getRafFromNetworkType(
                RILConstants.PREFERRED_NETWORK_MODE);

        mPhoneInterfaceManager.cleanUpAllowedNetworkTypes(mPhone, 1);

        verify(mPhone).loadAllowedNetworksFromSubscriptionDatabase();
        verify(mPhone).setAllowedNetworkTypes(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                defaultNetworkType, null);
    }

    @Test
    public void cleanUpAllowedNetworkTypes_validPhoneAndInvalidSubId_doNotSetAllowedNetwork() {
        long defaultNetworkType = RadioAccessFamily.getRafFromNetworkType(
                RILConstants.PREFERRED_NETWORK_MODE);

        mPhoneInterfaceManager.cleanUpAllowedNetworkTypes(mPhone, -1);

        verify(mPhone, never()).loadAllowedNetworksFromSubscriptionDatabase();
        verify(mPhone, never()).setAllowedNetworkTypes(
                TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, defaultNetworkType, null);
    }

    @Test
    public void matchLocaleFromSupportedLocaleList_inputLocaleChangeToSupportedLocale_notMatched() {
        Context context = mock(Context.class);
        when(mPhone.getContext()).thenReturn(context);
        Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getStringArray(anyInt()))
                .thenReturn(new String[]{"fi-FI", "ff-Adlm-BF", "en-US"});

        // Input empty string, then return default locale of ICU.
        String resultInputEmpty = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag(""));

        assertEquals("und", resultInputEmpty);

        // Input en, then look up the matched supported locale. No matched, so return input locale.
        String resultOnlyLanguage = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(
                mPhone,
                Locale.forLanguageTag("en"));

        assertEquals("en", resultOnlyLanguage);
    }

    @Test
    public void matchLocaleFromSupportedLocaleList_inputLocaleChangeToSupportedLocale() {
        Context context = mock(Context.class);
        when(mPhone.getContext()).thenReturn(context);
        Resources resources = mock(Resources.class);
        when(context.getResources()).thenReturn(resources);
        when(resources.getStringArray(anyInt())).thenReturn(new String[]{"zh-Hant-TW"});

        // Input zh-TW, then look up the matched supported locale, zh-Hant-TW, instead.
        String resultInputZhTw = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag("zh-TW"));

        assertEquals("zh-Hant-TW", resultInputZhTw);

        when(resources.getStringArray(anyInt())).thenReturn(
                new String[]{"fi-FI", "ff-Adlm-BF", "ff-Latn-BF"});

        // Input ff-BF, then find the matched supported locale, ff-Latn-BF, instead.
        String resultFfBf = mPhoneInterfaceManager.matchLocaleFromSupportedLocaleList(mPhone,
                Locale.forLanguageTag("ff-BF"));

        assertEquals("ff-Latn-BF", resultFfBf);
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_successfullyEnable() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED));

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);

        assertTrue(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, false));
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_successfullyDisable() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();
        assertFalse(mSharedPreferences.contains(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED));

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(false);

        assertFalse(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, true));
    }

    @Test
    public void setNullCipherAndIntegrityEnabled_lackingNecessaryHal() {
        doReturn(101).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);
        });

    }

    @Test
    public void setNullCipherAndIntegrityEnabled_lackingPermissions() {
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(true);
        });
    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled() {
        whenModemSupportsNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        mPhoneInterfaceManager.setNullCipherAndIntegrityEnabled(false);
        assertFalse(
                mSharedPreferences.getBoolean(Phone.PREF_NULL_CIPHER_AND_INTEGRITY_ENABLED, true));
    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingNecessaryHal() {
        doReturn(101).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });

    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingModemSupport() {
        whenModemDoesNotSupportNullCiphers();
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doNothing().when(mPhoneInterfaceManager).enforceModifyPermission();

        assertThrows(UnsupportedOperationException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });

    }

    @Test
    public void isNullCipherAndIntegrityPreferenceEnabled_lackingPermissions() {
        doReturn(201).when(mPhoneInterfaceManager).getHalVersion(anyInt());
        doThrow(SecurityException.class).when(mPhoneInterfaceManager).enforceReadPermission();

        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.isNullCipherAndIntegrityPreferenceEnabled();
        });
    }

    private void whenModemDoesNotSupportNullCiphers() {
        doReturn(false).when(mPhone).isNullCipherAndIntegritySupported();
        doReturn(mPhone).when(
                mPhoneInterfaceManager).getDefaultPhone();
    }

    private void whenModemSupportsNullCiphers() {
        doReturn(true).when(mPhone).isNullCipherAndIntegritySupported();
        doReturn(mPhone).when(
                mPhoneInterfaceManager).getDefaultPhone();
    }

    /**
     * Verify getCarrierRestrictionStatus throws exception for invalid caller package name.
     */
    @Test
    public void getCarrierRestrictionStatus_ReadPrivilegedException2() {
        doThrow(SecurityException.class).when(
                mPhoneInterfaceManager).enforceReadPrivilegedPermission(anyString());
        assertThrows(SecurityException.class, () -> {
            mPhoneInterfaceManager.getCarrierRestrictionStatus(mIIntegerConsumer, "");
        });
    }

    /**
     * Verify getCarrierRestrictionStatus doesn't throw any exception with valid package name
     * and with READ_PHONE_STATE permission granted.
     */
    @Test
    public void getCarrierRestrictionStatus() {
        when(mPhoneInterfaceManager.validateCallerAndGetCarrierId(anyString())).thenReturn(1);
        mPhoneInterfaceManager.getCarrierRestrictionStatus(mIIntegerConsumer,
                "com.test.package");
    }
}
