/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.bedstead.nene.roles;

import com.android.bedstead.nene.packages.Package;
import com.android.bedstead.nene.users.UserReference;

/**
 * A context that, when closed, will remove the package from the role.
 */
public final class RoleContext implements AutoCloseable {

    private final String mRole;
    private final Package mPackage;
    private final UserReference mUser;

    public RoleContext(String role, Package pkg, UserReference user) {
        mRole = role;
        mPackage = pkg;
        mUser = user;
    }

    @Override
    public void close() {
        mPackage.removeAsRoleHolder(mRole, mUser);
    }
}
