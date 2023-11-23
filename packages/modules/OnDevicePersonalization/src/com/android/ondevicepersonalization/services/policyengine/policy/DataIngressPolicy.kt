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

package com.android.ondevicepersonalization.services.policyengine.policy

import com.android.libraries.pcc.chronicle.api.policy.StorageMedium
import com.android.libraries.pcc.chronicle.api.policy.UsageType
import com.android.libraries.pcc.chronicle.api.policy.builder.policy
import com.android.ondevicepersonalization.services.policyengine.data.USER_DATA_GENERATED_DTD
import com.android.ondevicepersonalization.services.policyengine.policy.rules.UserOptsInLimitedAdsTracking
import com.android.ondevicepersonalization.services.policyengine.policy.rules.UnicornAccount
import com.android.libraries.pcc.chronicle.api.policy.contextrules.and
import com.android.libraries.pcc.chronicle.api.policy.contextrules.not

import java.time.Duration

/** This module encapsulates all data ingress policies for ODA. */
class DataIngressPolicy {
    companion object {
        // NPA (No Personalized Ads) policy for user and vendor data in ODA
        @JvmField
        val NPA_DATA_POLICY = policy(
            name = "npaPolicy",
            egressType = "None",
        ) {
            description =
            """
                Policy that grant on-device data to ad vendors if no NPA flag is set.
                """
                .trimIndent()
            target(USER_DATA_GENERATED_DTD, Duration.ofDays(30)) {
                retention(medium = StorageMedium.RAM, encryptionRequired = false)
                "timeSec" {rawUsage(UsageType.ANY)}
                "timezone" {rawUsage(UsageType.ANY)}
                "orientation" {rawUsage(UsageType.ANY)}
                "availableBytesMB" {rawUsage(UsageType.ANY)}
                "batteryPct" {rawUsage(UsageType.ANY)}
                "country" {rawUsage(UsageType.ANY)}
                "language" {rawUsage(UsageType.ANY)}
                "carrier" {rawUsage(UsageType.ANY)}
                "osVersions" {
                    "major" {rawUsage(UsageType.ANY)}
                    "minor" {rawUsage(UsageType.ANY)}
                    "micro" {rawUsage(UsageType.ANY)}
                }
                "connectionType" {rawUsage(UsageType.ANY)}
                "connectionSpeedKbps" {rawUsage(UsageType.ANY)}
                "networkMetered" {rawUsage(UsageType.ANY)}
                "deviceMetrics" {
                    "make" {rawUsage(UsageType.ANY)}
                    "model" {rawUsage(UsageType.ANY)}
                    "screenHeightDp" {rawUsage(UsageType.ANY)}
                    "screenWidthDp" {rawUsage(UsageType.ANY)}
                    "xdpi" {rawUsage(UsageType.ANY)}
                    "ydpi" {rawUsage(UsageType.ANY)}
                    "pxRatio" {rawUsage(UsageType.ANY)}
                }
                "appInstalledHistory" {
                    "packageName" {rawUsage(UsageType.ANY)}
                    "installed" {rawUsage(UsageType.ANY)}
                }
                "appUsageHistory" {
                    "packageName" {rawUsage(UsageType.ANY)}
                    "totalTimeUsedMillis" {rawUsage(UsageType.ANY)}
                }
                "currentLocation" {
                    "timeSec" {rawUsage(UsageType.ANY)}
                    "latitude" {rawUsage(UsageType.ANY)}
                    "longitude" {rawUsage(UsageType.ANY)}
                    "locationProvider" {rawUsage(UsageType.ANY)}
                    "preciseLocation" {rawUsage(UsageType.ANY)}
                }
                "locationHistory" {
                    "latitude" {rawUsage(UsageType.ANY)}
                    "longitude" {rawUsage(UsageType.ANY)}
                    "durationMillis" {rawUsage(UsageType.ANY)}
                }
            }

            allowedContext = not(UnicornAccount) and not(UserOptsInLimitedAdsTracking)
        }
    }
}
