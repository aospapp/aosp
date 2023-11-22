/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.androidbvt;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Environment;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.test.suitebuilder.annotation.MediumTest;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

/*
 * Basic test for checking setting are set to default values
 */
public class SysSettingTests extends TestCase {
    private Context mContext = null;
    private AndroidBvtHelper mABvtHelper = null;
    private ContentResolver mResolver = null;

    private static enum mSettingType {
        SYSTEM, SECURE, GLOBAL
    }

    private static HashMap<String, String> mGlobalSettings = new HashMap<String, String>();
    {
        //Bluetooth is by default OFF.
        mGlobalSettings.put("bluetooth_on", "0");
        //Airplane mode is by default OFF
        mGlobalSettings.put("airplane_mode_on", "0");
        //Wifi is by default on for testing
        mGlobalSettings.put("wifi_on", "1");
        //Data roaming is by default OFF
        mGlobalSettings.put("data_roaming", "0");
        //Do not Disturb mode is by default OFF
        mGlobalSettings.put("zen_mode", "0");
    }

    private static HashMap<String, String> mSystemSettings = new HashMap<String, String>();
    {
        //Automatic Brightness mode is by default on
        mSystemSettings.put("screen_brightness_mode", "1");
        //By default 30sec before the device goes to sleep after inactivity
        mSystemSettings.put("screen_off_timeout", "30000");
        //By default Font is 1.0
        mSystemSettings.put("font_scale", "1.0");
    }

    private static HashMap<String, String> mSecureSettings = new HashMap<String, String>();
    {
        //By default screensaver is enabled
        mSecureSettings.put("screensaver_enabled", "1");
    }

    private static Map<mSettingType, HashMap<String, String>> mSettings =
            new HashMap<mSettingType, HashMap<String, String>>();
    {
        mSettings.put(mSettingType.GLOBAL, mGlobalSettings);
        mSettings.put(mSettingType.SYSTEM, mSystemSettings);
        mSettings.put(mSettingType.SECURE, mSecureSettings);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mContext = InstrumentationRegistry.getTargetContext();
        mResolver = mContext.getContentResolver();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    @MediumTest
    public void testSettingDefaultValues(){
        for(Entry<mSettingType, HashMap<String, String>> settingsByType : mSettings.entrySet()){
            mSettingType settingType = settingsByType.getKey();
            HashMap<String, String> settings = settingsByType.getValue();
            for (Entry<String, String> settingPair : settings.entrySet()) {
                assertTrue(
                        String.format("%s does not have default value: %s", settingPair.getKey(),
                                settingPair.getValue()),
                        getStringSetting(settingType, settingPair.getKey())
                                .equals(settingPair.getValue()));
            }
        }
    }

    private String getStringSetting(mSettingType type, String sName) {
        switch (type) {
            case SYSTEM:
                return Settings.System.getString(mResolver, sName);
            case GLOBAL:
                return Settings.Global.getString(mResolver, sName);
            case SECURE:
                return Settings.Secure.getString(mResolver, sName);
        }
        return null;
    }
}
