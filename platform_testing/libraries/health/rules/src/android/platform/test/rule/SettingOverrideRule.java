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

package android.platform.test.rule;


import android.content.ContentResolver;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.runner.Description;

/** This rule will override secure setting and revert it after the test. */
public class SettingOverrideRule extends TestWatcher {

    private final ContentResolver contentResolver = InstrumentationRegistry.getInstrumentation()
            .getContext().getContentResolver();

    private final String mSettingName;
    private final int mOverrideValue;

    private int mOriginalValue;

    public SettingOverrideRule(String name, int value) {
        mSettingName = name;
        mOverrideValue = value;
    }

    @Override
    protected void starting(Description description) {
        try {
            mOriginalValue = Settings.Secure.getInt(contentResolver, mSettingName);
        } catch (Settings.SettingNotFoundException e) {
            throw new RuntimeException(e);
        }

        Settings.Secure.putInt(contentResolver, mSettingName, mOverrideValue);
    }

    @Override
    protected void finished(Description description) {
        Settings.Secure.putInt(contentResolver, mSettingName, mOriginalValue);
    }
}
