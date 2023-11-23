/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.services.telephony;

import static android.media.ToneGenerator.TONE_PROP_PROMPT;
import static android.media.ToneGenerator.TONE_SUP_BUSY;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertEquals;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.DisconnectCause;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.TelephonyTestBase;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.common.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public class DisconnectCauseUtilTest extends TelephonyTestBase {

    // constants
    public static final int PHONE_ID = 123;
    public static final String EMPTY_STRING = "";

    // dynamic
    private Context mContext;

    //Mocks
    @Mock
    private GsmCdmaPhone mMockPhone;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        // objects that call static getInstance()
        mMockPhone = Mockito.mock(GsmCdmaPhone.class);
        mContext = InstrumentationRegistry.getTargetContext();
        // set mocks
        setSinglePhone();
    }

    /**
     * Verifies that a call drop due to loss of WIFI results in a disconnect cause of error and that
     * the label, description and tone are all present.
     */
    @Test
    public void testDropDueToWifiLoss() {
        android.telecom.DisconnectCause tcCause = DisconnectCauseUtil.toTelecomDisconnectCause(
                DisconnectCause.WIFI_LOST);
        assertEquals(android.telecom.DisconnectCause.ERROR, tcCause.getCode());
        assertEquals(TONE_PROP_PROMPT, tcCause.getTone());
        assertNotNull(tcCause.getDescription());
        assertNotNull(tcCause.getReason());
    }

    /**
     *  ensure the default behavior was not changed when a disconnect cause comes in as
     *  DisconnectCause.ERROR_UNSPECIFIED
     */
    @Test
    public void testDefaultDisconnectCauseBehaviorForCauseNotInCarrierBusyToneArray() {
        android.telecom.DisconnectCause tcCause = DisconnectCauseUtil.toTelecomDisconnectCause(
                DisconnectCause.ERROR_UNSPECIFIED, EMPTY_STRING, PHONE_ID);
        // CODE
        assertEquals(android.telecom.DisconnectCause.ERROR, tcCause.getCode());
        // LABEL
        safeAssertLabel(null, tcCause);
        // TONE
        assertEquals(TONE_PROP_PROMPT, tcCause.getTone());
    }

    /**
     *  Simulate a Carrier classifying the DisconnectCause.ERROR_UNSPECIFIED as a
     *  DisconnectCause.BUSY.  The code, label, and tone should match DisconnectCause.BUSY.
     */
    @Test
    public void testCarrierSetDisconnectCauseInBusyToneArray() {
        int[] carrierBusyArr = {DisconnectCause.BUSY, DisconnectCause.ERROR_UNSPECIFIED};
        PersistableBundle config = new PersistableBundle();

        config.putIntArray(
                CarrierConfigManager.KEY_DISCONNECT_CAUSE_PLAY_BUSYTONE_INT_ARRAY,
                carrierBusyArr);

        android.telecom.DisconnectCause tcCause =
                DisconnectCauseUtil.toTelecomDisconnectCause(
                        DisconnectCause.ERROR_UNSPECIFIED, -1,
                        EMPTY_STRING, PHONE_ID, null, config);

        // CODE
        assertEquals(android.telecom.DisconnectCause.BUSY, tcCause.getCode());
        // LABEL
        safeAssertLabel(R.string.callFailed_userBusy, tcCause);
        // TONE
        assertEquals(TONE_SUP_BUSY, tcCause.getTone());
    }

    private void setSinglePhone() throws Exception {
        Phone[] mPhones = new Phone[]{mMockPhone};
        replaceInstance(PhoneFactory.class, "sPhones", null, mPhones);
    }

    private Resources getResourcesForLocale(Context context, Locale locale) {
        Configuration config = new Configuration();
        config.setToDefaults();
        config.setLocale(locale);
        Context localeContext = context.createConfigurationContext(config);
        return localeContext.getResources();
    }

    private void safeAssertLabel(Integer resourceId,
            android.telecom.DisconnectCause disconnectCause) {
        Resources r = getResourcesForLocale(mContext, Locale.US);
        if (resourceId == null || r == null) {
            return;
        }
        String label = r.getString(resourceId);
        assertEquals(label, disconnectCause.getLabel());
    }

    /**
     * Verifies that an ICC_ERROR disconnect cause generates a message which mentions there is no
     * SIM.
     */
    @Test
    public void testIccError() {
        android.telecom.DisconnectCause tcCause = DisconnectCauseUtil.toTelecomDisconnectCause(
                DisconnectCause.ICC_ERROR);
        assertEquals(android.telecom.DisconnectCause.ERROR, tcCause.getCode());
        assertNotNull(tcCause.getLabel());
        assertNotNull(tcCause.getDescription());
    }
}
