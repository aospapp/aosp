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

import android.content.pm.VersionedPackage;

/**
 * Collection of dummy apps used in tests.
 */
public class TestApp {
    public static final String A = "com.android.cts.rollback.lib.testapp.A";
    public static final String Apex = "com.android.apex.cts.shim";

    public static final TestApp A1 = new TestApp("A1", A, 1, /*isApex*/false,
            "RollbackManagerTestAppA1.apk");
    public static final TestApp A2 = new TestApp("A2", A, 2, /*isApex*/false,
            "RollbackManagerTestAppA2.apk");
    public static final TestApp Apex2 = new TestApp("Apex2", Apex, 2, /*isApex*/true,
            "com.android.apex.cts.shim.v2.apex");
    public static final TestApp Apex3 = new TestApp("Apex3", Apex, 3, /*isApex*/true,
            "com.android.apex.cts.shim.v3.apex");

    private final String mName;
    private final String mPackageName;
    private final long mVersionCode;
    private final String[] mResourceNames;
    private final boolean mIsApex;

    public TestApp(String name, String packageName, long versionCode, boolean isApex,
            String... resourceNames) {
        mName = name;
        mPackageName = packageName;
        mVersionCode = versionCode;
        mResourceNames = resourceNames;
        mIsApex = isApex;
    }

    String getPackageName() {
        return mPackageName;
    }

    long getVersionCode() {
        return mVersionCode;
    }

    String[] getResourceNames() {
        return mResourceNames;
    }

    VersionedPackage getVersionedPackage() {
        return new VersionedPackage(mPackageName, mVersionCode);
    }

    boolean isApex() {
        return mIsApex;
    }

    @Override
    public String toString() {
        return mName;
    }
}
