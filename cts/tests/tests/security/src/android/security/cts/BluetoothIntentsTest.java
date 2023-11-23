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
package android.security.cts;

import static android.os.Process.BLUETOOTH_UID;

import android.content.ComponentName;
import android.content.Intent;
import android.platform.test.annotations.AsbSecurityTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.sts.common.util.StsExtraBusinessLogicTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BluetoothIntentsTest extends StsExtraBusinessLogicTestCase {
  /**
   * b/35258579
   */
  @AsbSecurityTest(cveBugId = 35258579)
  @Test
  public void testAcceptIntent() {
    genericIntentTest("ACCEPT");
  }

  /**
   * b/35258579
   */
  @AsbSecurityTest(cveBugId = 35258579)
  @Test
  public void testDeclineIntent() {
      genericIntentTest("DECLINE");
  }

  private static final String prefix = "android.btopp.intent.action.";
  private void genericIntentTest(String action) throws SecurityException {
    try {
      Intent should_be_protected_broadcast = new Intent();

      String bluetoothPackageName = getInstrumentation().getContext().getPackageManager()
          .getPackagesForUid(BLUETOOTH_UID)[0];

      ComponentName oppLauncherComponent = new ComponentName(bluetoothPackageName,
          "com.android.bluetooth.opp.BluetoothOppReceiver");

      should_be_protected_broadcast.setComponent(oppLauncherComponent);
      should_be_protected_broadcast.setAction(prefix + action);
      getInstrumentation().getContext().sendBroadcast(should_be_protected_broadcast);
    }
    catch (SecurityException e) {
      return;
    }

    throw new SecurityException("An " + prefix + action +
        " intent should not be broadcastable except by the system (declare " +
        " as protected-broadcast in manifest)");
  }
}
