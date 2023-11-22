/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.cts.backup.othersoundssettingsapp;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.android.compatibility.common.util.BackupUtils.LOCAL_TRANSPORT_TOKEN;

import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.AppModeFull;
import android.provider.Settings;

import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.BackupUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;

/**
 * Device side routines to be invoked by the host side OtherSoundsSettingsHostSideTest. These
 * are not designed to be called in any other way, as they rely on state set up by the host side
 * test.
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull
public class OtherSoundsSettingsTest {
    /** The name of the package for backup */
    private static final String SETTINGS_PACKAGE_NAME = "com.android.providers.settings";

    private ContentResolver mContentResolver;
    private BackupUtils mBackupUtils;

    @Before
    public void setUp() throws Exception {
        mContentResolver = getInstrumentation().getTargetContext().getContentResolver();
        mBackupUtils =
                new BackupUtils() {
                    @Override
                    protected InputStream executeShellCommand(String command) throws IOException {
                        ParcelFileDescriptor pfd =
                                getInstrumentation().getUiAutomation().executeShellCommand(command);
                        return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    }
                };
    }

    /**
     * Test backup and restore of Dial pad tones.
     *
     * Test logic:
     * 1. Check Dial pad tones exists.
     * 2. Backup Settings.
     * 3. Toggle Dial pad tones.
     * 4. Restore Settings.
     * 5. Check restored Dial pad tones is the same with backup value.
     */
    @Test
    public void testOtherSoundsSettings_dialPadTones() throws Exception {
        int originalValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, -1);
        assertTrue("Dial pad tones does not exist.", originalValue != -1);

        mBackupUtils.backupNowAndAssertSuccess(SETTINGS_PACKAGE_NAME);

        boolean ret =
                Settings.System.putInt(
                        mContentResolver, Settings.System.DTMF_TONE_WHEN_DIALING,
                        1 - originalValue);
        assertTrue("Toggle Dial pad tones fail.", ret);

        mBackupUtils.restoreAndAssertSuccess(LOCAL_TRANSPORT_TOKEN, SETTINGS_PACKAGE_NAME);

        int restoreValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.DTMF_TONE_WHEN_DIALING, -1);
        assertTrue("Dial pad tones restore fail.", originalValue == restoreValue);
    }

    /**
     * Test backup and restore of Touch sounds.
     *
     * Test logic:
     * 1. Check Touch sounds exists.
     * 2. Backup Settings.
     * 3. Toggle Touch sounds.
     * 4. Restore Settings.
     * 5. Check restored Touch sounds is the same with backup value.
     */
    @Test
    public void testOtherSoundsSettings_touchSounds() throws Exception {
        int originalValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.SOUND_EFFECTS_ENABLED, -1);
        assertTrue("Touch sounds does not exist.", originalValue != -1);

        mBackupUtils.backupNowAndAssertSuccess(SETTINGS_PACKAGE_NAME);

        boolean ret =
                Settings.System.putInt(
                        mContentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1 - originalValue);
        assertTrue("Toggle Touch sounds fail.", ret);

        mBackupUtils.restoreAndAssertSuccess(LOCAL_TRANSPORT_TOKEN, SETTINGS_PACKAGE_NAME);

        int restoreValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.SOUND_EFFECTS_ENABLED, -1);
        assertTrue("Touch sounds restore fail.", originalValue == restoreValue);
    }

    /**
     * Test backup and restore of Touch vibration.
     *
     * Test logic:
     * 1. Check Touch vibration exists.
     * 2. Backup Settings.
     * 3. Toggle Touch vibration.
     * 4. Restore Settings.
     * 5. Check restored Touch vibration is the same with backup value.
     */
    @Test
    public void testOtherSoundsSettings_touchVibration() throws Exception {
        int originalValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, -1);
        assertTrue("Touch vibration does not exist.", originalValue != -1);

        mBackupUtils.backupNowAndAssertSuccess(SETTINGS_PACKAGE_NAME);

        boolean ret =
                Settings.System.putInt(
                        mContentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED,
                        1 - originalValue);
        assertTrue("Toggle Touch vibration fail.", ret);

        mBackupUtils.restoreAndAssertSuccess(LOCAL_TRANSPORT_TOKEN, SETTINGS_PACKAGE_NAME);

        int restoreValue =
                Settings.System.getInt(
                        mContentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, -1);
        assertTrue("Touch vibration restore fail.", originalValue == restoreValue);
    }
}