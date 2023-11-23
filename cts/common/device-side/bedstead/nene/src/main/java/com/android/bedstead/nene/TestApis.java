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

package com.android.bedstead.nene;

import com.android.bedstead.nene.activities.Activities;
import com.android.bedstead.nene.annotations.Experimental;
import com.android.bedstead.nene.context.Context;
import com.android.bedstead.nene.devicepolicy.DevicePolicy;
import com.android.bedstead.nene.notifications.Notifications;
import com.android.bedstead.nene.packages.Packages;
import com.android.bedstead.nene.permissions.Permissions;
import com.android.bedstead.nene.settings.Settings;
import com.android.bedstead.nene.systemproperties.SystemProperties;
import com.android.bedstead.nene.users.Users;

/**
 * Entry point to Nene Test APIs.
 */
public final class TestApis {
    /** Access Test APIs related to Users. */
    public static Users users() {
        return Users.sInstance;
    }

    /** Access Test APIs related to Packages. */
    public static Packages packages() {
        return Packages.sInstance;
    }

    /** Access Test APIs related to device policy. */
    public static DevicePolicy devicePolicy() {
        return DevicePolicy.sInstance;
    }

    /** Access Test APIs related to permissions. */
    public static Permissions permissions() {
        return Permissions.sInstance;
    }

    /** Access Test APIs related to context. */
    public static Context context() {
        return Context.sInstance;
    }

    /** Access Test APIs relating to Settings. */
    public static Settings settings() {
        return Settings.sInstance;
    }

    /** Access Test APIs related to System Properties. */
    public static SystemProperties systemProperties() {
        return SystemProperties.sInstance;
    }

    /** Access Test APIs related to activities. */
    @Experimental
    public static Activities activities() {
        return Activities.sInstance;
    }

    /** Access Test APIs related to notifications. */
    public static Notifications notifications() {
        return Notifications.sInstance;
    }

    /** @deprecated Use statically */
    @Deprecated()
    public TestApis() {

    }
}
