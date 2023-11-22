/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.managedprovisioning;

import android.app.admin.DevicePolicyManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.managedprovisioning.task.DeleteNonRequiredAppsTask;

import java.util.List;

/**
 * After a system update, this class resets the cross-profile intent filters and checks
 * if apps that have been added to the system image need to be deleted.
 */
public class PreBootListener extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int currentUserId = context.getUserId();
        if (currentUserId == UserHandle.USER_OWNER) {
            // Resetting the cross-profile intent filters for the managed profiles which have
            // this user as their parent.
            UserManager um = (UserManager) context.getSystemService(
                    Context.USER_SERVICE);
            List<UserInfo> profiles = um.getProfiles(currentUserId);
            boolean hasClearedParent = false;
            if (profiles.size() > 1) {
                PackageManager pm = context.getPackageManager();
                for (UserInfo userInfo : profiles) {
                    if (userInfo.isManagedProfile()) {
                        if (!hasClearedParent) {
                            // Removes filters from the parent to all the managed profiles.
                            pm.clearCrossProfileIntentFilters(currentUserId);
                            hasClearedParent = true;
                        }
                        pm.clearCrossProfileIntentFilters(userInfo.id);
                        CrossProfileIntentFiltersHelper.setFilters(
                                pm, currentUserId, userInfo.id);
                        deleteNonRequiredApps(context, userInfo.id);
                    }
                }
            }
        }
    }

    /**
     * Deletes non-required apps that have been added to the system image during the system
     * update.
     */
    private void deleteNonRequiredApps(Context context, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager)
                context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName profileOwner = dpm.getProfileOwnerAsUser(userId);
        if (profileOwner == null) {
            ProvisionLogger.loge("There is no profile owner on a managed profile.");
        }

        new DeleteNonRequiredAppsTask(context, profileOwner.getPackageName(), userId,
                R.array.required_apps_managed_profile, R.array.vendor_required_apps_managed_profile,
                false /* We are not creating a new profile */,
                true /* Disable INSTALL_SHORTCUT listeners */,
                new DeleteNonRequiredAppsTask.Callback() {

                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onError() {
                        ProvisionLogger.loge("Error while checking if there are new system "
                                + "apps that need to be deleted");
                    }
                }).run();
    }
}
