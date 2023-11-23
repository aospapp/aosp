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
package android.security.cts;

import android.app.ActivityManager;
import android.os.IBinder;
import android.platform.test.annotations.SecurityTest;
import android.util.Log;

import junit.framework.TestCase;

import java.lang.reflect.InvocationTargetException;

@SecurityTest
public class ActivityManagerTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @SecurityTest(minPatchLevel = "2015-03")
    public void testActivityManager_injectInputEvents() throws ClassNotFoundException {
        try {
            /*
             * Should throw NoSuchMethodException. getEnclosingActivityContainer() has been
             * removed/renamed.
             * Patch:  https://android.googlesource.com/platform/frameworks/base/+/aa7e3ed%5E!/
             */
            Class.forName("android.app.ActivityManagerNative").getMethod(
                    "getEnclosingActivityContainer", IBinder.class);
            fail("ActivityManagerNative.getEnclosingActivityContainer() API should not be" +
                    "available in patched devices: Device is vulnerable to CVE-2015-1533");
        } catch (NoSuchMethodException e) {
            // Patched devices should throw this exception
        }
    }

    // b/144285917
    @SecurityTest(minPatchLevel = "2020-05")
    public void testActivityManager_attachNullApplication() {
        SecurityException securityException = null;
        Exception unexpectedException = null;
        try {
            final Object iam = ActivityManager.class.getDeclaredMethod("getService").invoke(null);
            Class.forName("android.app.IActivityManager").getDeclaredMethod("attachApplication",
                    Class.forName("android.app.IApplicationThread"), long.class)
                    .invoke(iam, null /* thread */, 0 /* startSeq */);
        } catch (SecurityException e) {
            securityException = e;
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SecurityException) {
                securityException = (SecurityException) e.getCause();
            } else {
                unexpectedException = e;
            }
        } catch (Exception e) {
            unexpectedException = e;
        }
        if (unexpectedException != null) {
            Log.w("ActivityManagerTest", "Unexpected exception", unexpectedException);
        }

        assertNotNull("Expect SecurityException by attaching null application", securityException);
    }
}
