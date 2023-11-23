/*
 * Copyright (C) 2020 The Android Open Source Project
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
package android.car.userlib;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.os.UserManager;

/**
 * Provides utility methods for generic user-related functionalities that don't require a manager.
 */
public final class UserHelper {

    private UserHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }

    /**
     * Gets a PII-safe representation of the name.
     */
    @Nullable
    public static String safeName(@Nullable String name) {
        return name == null ? name : name.length() + "_chars";
    }

    /**
     * Checks whether the given user is both {@code SYSTEM} and headless.
     */
    public static boolean isHeadlessSystemUser(@UserIdInt int userId) {
        return userId == UserHandle.USER_SYSTEM && UserManager.isHeadlessSystemUserMode();
    }
}
