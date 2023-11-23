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

package com.android.bedstead.nene.devicepolicy;

import static com.android.bedstead.nene.permissions.Permissions.MANAGE_PROFILE_AND_DEVICE_OWNERS;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.os.Build;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.exceptions.AdbException;
import com.android.bedstead.nene.exceptions.NeneException;
import com.android.bedstead.nene.packages.Package;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.nene.utils.ShellCommand;
import com.android.bedstead.nene.utils.ShellCommandUtils;
import com.android.bedstead.nene.utils.Versions;

import java.util.Objects;

/**
 * A reference to a Device Owner.
 */
public final class DeviceOwner extends DevicePolicyController {
    DeviceOwner(UserReference user,
            Package pkg,
            ComponentName componentName) {
        super(user, pkg, componentName);
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("DeviceOwner{");
        stringBuilder.append("user=").append(user());
        stringBuilder.append(", package=").append(pkg());
        stringBuilder.append(", componentName=").append(componentName());
        stringBuilder.append("}");

        return stringBuilder.toString();
    }

    @Override
    public void remove() {
        if (!Versions.meetsMinimumSdkVersionRequirement(Build.VERSION_CODES.S)) {
            removePreS();
            return;
        }

        DevicePolicyManager devicePolicyManager =
                TestApis.context().androidContextAsUser(mUser).getSystemService(
                        DevicePolicyManager.class);

        try (PermissionContext p =
                     TestApis.permissions().withPermission(MANAGE_PROFILE_AND_DEVICE_OWNERS)) {
            devicePolicyManager.forceRemoveActiveAdmin(mComponentName, mUser.id());
        }
    }

    private void removePreS() {
        try {
            ShellCommand.builderForUser(mUser, "dpm remove-active-admin")
                    .addOperand(componentName().flattenToShortString())
                    .validate(ShellCommandUtils::startsWithSuccess)
                    .execute();
        } catch (AdbException e) {
            throw new NeneException("Error removing device owner " + this, e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DeviceOwner)) {
            return false;
        }

        DeviceOwner other = (DeviceOwner) obj;

        return Objects.equals(other.mUser, mUser)
                && Objects.equals(other.mPackage, mPackage)
                && Objects.equals(other.mComponentName, mComponentName);
    }
}
