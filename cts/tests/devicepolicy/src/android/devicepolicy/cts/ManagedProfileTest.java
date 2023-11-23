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

package android.devicepolicy.cts;

import static com.android.eventlib.truth.EventLogsSubject.assertThat;
import static com.android.queryable.queries.ActivityQuery.activity;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasNoDpc;
import com.android.bedstead.remotedpc.RemoteDpc;
import com.android.bedstead.testapp.TestAppActivityReference;
import com.android.bedstead.testapp.TestAppInstance;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public final class ManagedProfileTest {

    @ClassRule @Rule public static final DeviceState sDeviceState = new DeviceState();

    @Test
    @EnsureHasNoDpc
    public void startActivityInManagedProfile_activityStarts() {
        // We want a fresh - properly created work profile
        try (RemoteDpc dpc = RemoteDpc.createWorkProfile();
             TestAppInstance testApp = sDeviceState.testApps().query()
                     .whereActivities().contains(activity().where().exported().isTrue())
                     .get().install()) {

            TestAppActivityReference activity =
                    testApp.activities().query().whereActivity().exported().isTrue().get();
            activity.start();

            assertThat(activity.events().activityCreated()).eventOccurred();
        }
    }
}
