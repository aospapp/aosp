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

package android.car.view.inputmethod.cts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.SparseArray;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ShellUtils;

import org.junit.Before;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO(b/267678351): Mark this test with RequireCheckerRule
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
public final class InputMethodManagerServiceProxyTest {

    public static final String NULL_AUTOFILL_SUGGESTIONS_CONTROLLER =
            "com.android.server.inputmethod.NullAutofillSuggestionsController";

    private static final Pattern CREATE_USER_OUTPUT_REGEX =
            Pattern.compile("Success: created user id (\\d+)");

    private static final long START_USER_PROPAGATION_TIMEOUT_MILLIS = 5000L;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private UserManager mUserManager;
    private Resources mResources;

    @Before
    public void setUp() {
        mUserManager = mContext.getSystemService(UserManager.class);
        assumeTrue(mUserManager.isVisibleBackgroundUsersSupported());

        mResources = mContext.getResources();
    }

    @Test
    public void testIsVisibleBackgroundUsersSupportedIsInSyncWithIMMSProxy() {
        String immsProxyClass = mResources.getString(mResources.getIdentifier(
                "config_deviceSpecificInputMethodManagerService", "string", "android"));
        assertThat(immsProxyClass).isNotEmpty();
    }

    @Test
    public void testCarImmsLifecycle() {
        int userId = UserHandle.USER_NULL;
        try {
            userId = createRandomUser();
            assertThat(containsCarImmsForUser(userId)).isFalse();
            assertWithMessage("User started").that(
                    ShellUtils.runShellCommand("am start-user -w %d",
                            userId)).contains("Success: user started");
            assertThat(containsCarImmsForUser(userId)).isTrue();
        } finally {
            stopAndRemoveUser(userId);
            assertThat(containsCarImmsForUser(userId)).isFalse();
        }
    }

    private static int createRandomUser() {
        // `pm create-user` is expected to return a string like `Success: created user id NN`
        // where NN represents a two digits user id.
        String shellOut = ShellUtils.runShellCommand("pm create-user testUser");
        Matcher matcher = CREATE_USER_OUTPUT_REGEX.matcher(shellOut);
        assertThat(matcher.matches()).isTrue();
        assertThat(matcher.groupCount()).isEqualTo(1);
        return Integer.parseInt(matcher.group(1));
    }

    private static void stopAndRemoveUser(int userId) {
        ShellUtils.runShellCommand("am stop-user -w -f %d", userId);
        assertWithMessage("User removed").that(
                ShellUtils.runShellCommand("pm remove-user -w %d",
                        userId)).contains("Success: removed user");
    }

    private static boolean containsCarImmsForUser(int userId) {
        SparseArray<String> carImms = parseServiceConfigDump();
        return carImms.contains(userId);
    }

    @Test
    public void testAutofillIsDisabledForSystemUser() {
        SparseArray<String> configs = parseServiceConfigDump();
        assertWithMessage("A dedicated IMMS must be initialized for USER_SYSTEM")
                .that(configs.contains(UserHandle.USER_SYSTEM)).isTrue();
        assertWithMessage("USER_SYSTEM's IMMS must be set with null autofill")
                .that(configs.get(UserHandle.USER_SYSTEM)).contains(
                NULL_AUTOFILL_SUGGESTIONS_CONTROLLER);
    }

    private static SparseArray<String> parseServiceConfigDump() {
        String dump = ShellUtils.runShellCommand("dumpsys input_method --brief");
        Pattern dumpPattern = Pattern.compile("\\*\\*mServicesForUser\\*\\*(.+?)\\*\\*",
                Pattern.DOTALL);
        Matcher dumpMatcher = dumpPattern.matcher(dump);
        SparseArray<String> configs = new SparseArray<>();
        if (!dumpMatcher.find()) {
            return configs;
        }
        Pattern immsPattern = Pattern.compile("userId=(.*?) imms=(.*?) \\{autofill=(.*?)\\}");
        String immsMatch = dumpMatcher.group(1);
        Matcher immsMatcher = immsPattern.matcher(immsMatch);
        while (immsMatcher.find()) {
            int userId = Integer.parseInt(immsMatcher.group(1));
            String autofillClass = immsMatcher.group(3);
            configs.put(userId, autofillClass);
        }
        return configs;
    }
}
