/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package android.cts.backup;

import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.AppModeFull;

import com.android.compatibility.common.util.HostSideTestUtils;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

/** Tests for multi-user backup state. */
@RunWith(DeviceJUnit4ClassRunner.class)
@AppModeFull
public class MultiUserBackupStateTest extends BaseMultiUserBackupHostSideTest {
    private static final int TIMEOUT_SECONDS = 30;

    private Optional<Integer> mProfileUserId = Optional.empty();

    /** Create the profile and start it. */
    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        int profileUserId = createProfileUser(mDevice.getCurrentUser(), "MU-State");
        mProfileUserId = Optional.of(profileUserId);
        startUser(profileUserId);
    }

    /** Uninstall the test package and remove the profile. */
    @After
    @Override
    public void tearDown() throws Exception {
        if (mProfileUserId.isPresent()) {
            assertTrue(mDevice.removeUser(mProfileUserId.get()));
            mProfileUserId = Optional.empty();
        }
        super.tearDown();
    }

    /**
     * Tests that backup is deactivated for a user when the user is removed.
     *
     * <ol>
     *   <li>Activate backup for the user.
     *   <li>Check that backup is activated for the user.
     *   <li>Remove the user.
     *   <li>Assert via poll that backup is deactivated for the removed user.
     * </ol>
     */
    @Test
    public void testBackupDeactivatedWhenUserIsRemoved() throws Exception {
        int profileUserId = mProfileUserId.get();
        mBackupUtils.activateBackupForUser(true, profileUserId);

        assertTrue(mBackupUtils.isBackupActivatedForUser(profileUserId));

        assertTrue(mDevice.removeUser(profileUserId));
        mProfileUserId = Optional.empty();

        HostSideTestUtils.waitUntil("wait for backup to be deactivated for removed user",
                TIMEOUT_SECONDS, () -> !mBackupUtils.isBackupActivatedForUser(profileUserId));
    }
}
