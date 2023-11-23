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

package com.android.permissioncontroller.permission.ui.auto.dashboard

import android.content.Context
import android.content.Intent
import androidx.preference.Preference.OnPreferenceClickListener
import com.android.car.ui.preference.CarUiPreference
import com.android.permissioncontroller.R
import com.android.permissioncontroller.permission.ui.model.v31.PermissionUsageDetailsViewModel

/**
 * Preference that displays a permission usage for an app.
 */
class AutoPermissionHistoryPreference(
    context: Context,
    historyPreferenceData: PermissionUsageDetailsViewModel.HistoryPreferenceData
) : CarUiPreference(context) {

    init {
        title = historyPreferenceData.preferenceTitle
        summary = if (historyPreferenceData.summaryText != null) {
            context.getString(R.string.auto_permission_usage_timeline_summary,
                historyPreferenceData.accessTime, historyPreferenceData.summaryText)
        } else {
            historyPreferenceData.accessTime
        }
        if (historyPreferenceData.appIcon != null) {
            icon = historyPreferenceData.appIcon
        }

        onPreferenceClickListener = OnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_MANAGE_APP_PERMISSIONS).apply {
                putExtra(Intent.EXTRA_USER, historyPreferenceData.userHandle)
                putExtra(Intent.EXTRA_PACKAGE_NAME, historyPreferenceData.pkgName)
            }
            context.startActivity(intent)
            true
        }
    }
}
