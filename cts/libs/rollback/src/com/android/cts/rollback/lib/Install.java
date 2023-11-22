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
 * limitations under the License.
 */

package com.android.cts.rollback.lib;

import android.content.Intent;
import android.content.pm.PackageInstaller;

import androidx.test.InstrumentationRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Helper class for installing test apps.
 */
public class Install {
    private final boolean mIsMultiPackage;
    private final TestApp[] mTestApps;
    private boolean mIsStaged = false;
    private boolean mEnableRollback = false;

    private Install(boolean isMultiPackage, TestApp... testApps) {
        mIsMultiPackage = isMultiPackage;
        mTestApps = testApps;
    }

    /**
     * Creates an Install builder to install a single package.
     */
    public static Install single(TestApp testApp) {
        return new Install(false, testApp);
    }

    /**
     * Creates an Install builder to install using multiPackage.
     */
    public static Install multi(TestApp... testApps) {
        return new Install(true, testApps);
    }

    /**
     * Makes the install a staged install.
     */
    public Install setStaged() {
        mIsStaged = true;
        return this;
    }

    /**
     * Enables rollback for the install.
     */
    public Install setEnableRollback() {
        mEnableRollback = true;
        return this;
    }

    private static PackageInstaller getPackageInstaller() {
        return InstrumentationRegistry.getContext().getPackageManager().getPackageInstaller();
    }

    /**
     * Creates an empty install session with appropriate install params set.
     *
     * @return the session id of the newly created session
     */
    private int createEmptyInstallSession(boolean multiPackage, boolean isApex)
            throws IOException {
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (multiPackage) {
            params.setMultiPackage();
        }
        if (isApex) {
            params.setInstallAsApex();
        }
        if (mIsStaged) {
            params.setStaged();
        }
        params.setEnableRollback(mEnableRollback);
        return getPackageInstaller().createSession(params);
    }

    /**
     * Creates an install session for the given test app.
     *
     * @return the session id of the newly created session.
     */
    private int createInstallSession(TestApp app) throws IOException {
        int sessionId = createEmptyInstallSession(/*multiPackage*/false, app.isApex());
        PackageInstaller.Session session = getPackageInstaller().openSession(sessionId);

        ClassLoader loader = TestApp.class.getClassLoader();
        for (String resourceName : app.getResourceNames()) {
            try (OutputStream os = session.openWrite(resourceName, 0, -1);
                    InputStream is = loader.getResourceAsStream(resourceName);) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) >= 0) {
                    os.write(buffer, 0, n);
                }
            }
        }
        session.close();
        return sessionId;
    }

    /**
     * Commits the install.
     */
    public void commit() throws IOException, InterruptedException {
        final int sessionId;
        final PackageInstaller.Session session;
        if (mIsMultiPackage) {
            sessionId = createEmptyInstallSession(/*multiPackage*/ true, /*isApex*/false);
            session = getPackageInstaller().openSession(sessionId);
            for (TestApp app : mTestApps) {
                session.addChildSessionId(createInstallSession(app));
            }
        } else {
            assert mTestApps.length == 1;
            sessionId = createInstallSession(mTestApps[0]);
            session = getPackageInstaller().openSession(sessionId);
        }

        session.commit(LocalIntentSender.getIntentSender());
        Intent result = LocalIntentSender.getIntentSenderResult();
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status > 0) {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message == null ? "UNKNOWN FAILURE" : message);
        }

        if (mIsStaged) {
            Utils.waitForSessionReady(sessionId);
        }
    }
}
