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

import android.annotation.UserIdInt;
import android.os.UserHandle;

public class UserHandleCompat {
    public UserHandle mHandle;

    public UserHandleCompat(UserHandle handle) {
        this.mHandle = handle;
    }

    public UserHandleCompat(int userId) {
        this.mHandle = new UserHandle(userId);
    }

    public static boolean isSameApp(int uid1, int uid2) {
        return UserHandle.isSameApp(uid1, uid2);
    }

    public static @UserIdInt
    int getUserId(int uid) {
        return UserHandle.getUserId(uid);
    }

    public static final @UserIdInt
    int USER_OWNER = UserHandle.USER_OWNER;

    public static final @UserIdInt
    int USER_NULL = UserHandle.USER_NULL;

    public static final @UserIdInt
    int USER_SYSTEM = UserHandle.USER_SYSTEM;
}
