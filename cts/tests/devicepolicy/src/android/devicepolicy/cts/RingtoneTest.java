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

package android.devicepolicy.cts;

import static android.Manifest.permission.WRITE_SETTINGS;
import static android.provider.Settings.Secure.SYNC_PARENT_SOUNDS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.EnsureHasPermission;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.parameterized.IncludeRunOnProfileOwnerProfileWithNoDeviceOwner;
import com.android.bedstead.nene.TestApis;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
@Postsubmit(reason = "New tests")
public final class RingtoneTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final Context sContext = TestApis.context().instrumentedContext();

    private static final Uri RINGTONE_URI = Uri.parse("http://uri.does.not.matter");

    // TODO(b/194509745): Parameterize on different user types
    // TODO(b/188893663): Parameterize the different ringtone types (remove 2/3rds of this class)

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    public void getActualDefaultRingtoneUri_ringtone_matchesSettingsProviderRingtone() {
        String defaultRingtone = Settings.System.getString(
                sContext.getContentResolver(), Settings.System.RINGTONE);
        Uri expectedUri = getUriWithoutUserId(defaultRingtone);
        Uri actualRingtoneUri = getUriWithoutUserId(
                RingtoneManager.getActualDefaultRingtoneUri(
                        sContext, RingtoneManager.TYPE_RINGTONE));

        assertThat(expectedUri).isEqualTo(actualRingtoneUri);
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    public void getActualDefaultRingtoneUri_notification_matchesSettingsProviderNotificationSound() {
        String defaultRingtone = Settings.System.getString(
                sContext.getContentResolver(), Settings.System.NOTIFICATION_SOUND);
        Uri expectedUri = getUriWithoutUserId(defaultRingtone);
        Uri actualRingtoneUri = getUriWithoutUserId(
                RingtoneManager.getActualDefaultRingtoneUri(
                        sContext, RingtoneManager.TYPE_NOTIFICATION));

        assertThat(expectedUri).isEqualTo(actualRingtoneUri);
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    public void getActualDefaultRingtoneUri_alarm_matchesSettingsProviderAlarmAlert() {
        String defaultRingtone = Settings.System.getString(
                sContext.getContentResolver(), Settings.System.ALARM_ALERT);
        Uri expectedUri = getUriWithoutUserId(defaultRingtone);
        Uri actualRingtoneUri = getUriWithoutUserId(
                RingtoneManager.getActualDefaultRingtoneUri(
                        sContext, RingtoneManager.TYPE_ALARM));

        assertThat(expectedUri).isEqualTo(actualRingtoneUri);
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void setActualDefaultRingtoneUri_ringtone_setsSyncParentSoundsToFalse() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_RINGTONE);

        try {
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, RINGTONE_URI);

            assertWithMessage("SYNC_PARENT_SOUNDS is false because a ringtone"
                    + " has been set on the profile")
                    .that(TestApis.settings().secure().getInt(
                            SYNC_PARENT_SOUNDS)).isEqualTo(0);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_ringtone_syncParentSoundsIsFalse_returnsSetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_RINGTONE);
        try {
            // Calling setActualDefaultRingtoneUri will automatically switch SYNC_PARENT_SOUNDS to 0
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, RINGTONE_URI);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_ringtone_syncParentSoundsIsTrue_returnsDefaultRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_RINGTONE);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE)).isEqualTo(originalUri);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_ringtone_syncParentSoundsIsFalseAndUriWasPreviouslySet_returnsPreviouslySetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_RINGTONE);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 0);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_RINGTONE, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void setActualDefaultRingtoneUri_notification_setsSyncParentSoundsToFalse() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_NOTIFICATION);

        try {
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, RINGTONE_URI);

            assertWithMessage("SYNC_PARENT_SOUNDS is false because a ringtone has been set on the profile")
                    .that(TestApis.settings().secure().getInt(
                            SYNC_PARENT_SOUNDS)).isEqualTo(0);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_notification_syncParentSoundsIsFalse_returnsSetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_NOTIFICATION);
        try {
            // Calling setActualDefaultRingtoneUri will automatically switch SYNC_PARENT_SOUNDS to 0
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, RINGTONE_URI);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_notification_syncParentSoundsIsTrue_returnsDefaultRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_NOTIFICATION);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION)).isEqualTo(originalUri);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_notification_syncParentSoundsIsFalseAndUriWasPreviouslySet_returnsPreviouslySetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_NOTIFICATION);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 0);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_NOTIFICATION, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void setActualDefaultRingtoneUri_alarm_setsSyncParentSoundsToFalse() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_ALARM);

        try {
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, RINGTONE_URI);

            assertWithMessage(
                    "SYNC_PARENT_SOUNDS is false because a ringtone has been set on the profile")
                    .that(TestApis.settings().secure().getInt(
                            SYNC_PARENT_SOUNDS)).isEqualTo(0);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_alarm_syncParentSoundsIsFalse_returnsSetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_ALARM);
        try {
            // Calling setActualDefaultRingtoneUri will automatically switch SYNC_PARENT_SOUNDS to 0
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, RINGTONE_URI);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_alarm_syncParentSoundsIsTrue_returnsDefaultRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_ALARM);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM)).isEqualTo(originalUri);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    @Test
    @IncludeRunOnProfileOwnerProfileWithNoDeviceOwner
    @EnsureHasPermission(WRITE_SETTINGS)
    public void getActualDefaultRingtoneUri_alarm_syncParentSoundsIsFalseAndUriWasPreviouslySet_returnsPreviouslySetRingtone() {
        int originalSyncParentSounds = TestApis.settings().secure().getInt(SYNC_PARENT_SOUNDS);
        Uri originalUri = RingtoneManager.getActualDefaultRingtoneUri(
                sContext, RingtoneManager.TYPE_ALARM);
        try {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, RINGTONE_URI);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 1);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, 0);

            assertThat(RingtoneManager.getActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM)).isEqualTo(RINGTONE_URI);
        } finally {
            RingtoneManager.setActualDefaultRingtoneUri(
                    sContext, RingtoneManager.TYPE_ALARM, originalUri);
            TestApis.settings().secure().putInt(SYNC_PARENT_SOUNDS, originalSyncParentSounds);
        }
    }

    private static Uri getUriWithoutUserId(String uriString) {
        if (uriString == null) {
            return null;
        }
        return getUriWithoutUserId(Uri.parse(uriString));
    }

    /** Copied from {@link android.content.ContentProvider} */
    private static Uri getUriWithoutUserId(Uri uri) {
        if (uri == null) {
            return null;
        }
        Uri.Builder builder = uri.buildUpon();
        builder.authority(getAuthorityWithoutUserId(uri.getAuthority()));
        return builder.build();
    }

    /** Copied from {@link android.content.ContentProvider} */
    private static String getAuthorityWithoutUserId(String auth) {
        if (auth == null) {
            return null;
        }
        int end = auth.lastIndexOf('@');
        return auth.substring(end + 1);
    }
}
