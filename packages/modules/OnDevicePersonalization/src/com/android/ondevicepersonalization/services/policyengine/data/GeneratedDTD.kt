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

package com.android.ondevicepersonalization.services.policyengine.data

import com.android.libraries.pcc.chronicle.api.DataTypeDescriptor
import com.android.libraries.pcc.chronicle.api.FieldType
import com.android.libraries.pcc.chronicle.api.dataTypeDescriptor

/** User Data DTD. */
public val USER_DATA_GENERATED_DTD: DataTypeDescriptor = dataTypeDescriptor(name =
    "chronicle_dtd.UserData", cls = UserData::class) {
      "timeSec" to FieldType.Long
      "timezone" to FieldType.Integer
      "orientation" to FieldType.Integer
      "availableBytesMB" to FieldType.Integer
      "batteryPct" to FieldType.Integer
      "country" to FieldType.Integer
      "language" to FieldType.Integer
      "carrier" to FieldType.Integer
      "osVersions" to dataTypeDescriptor(name = "chronicle_dtd.OSVersion", cls = OSVersion::class) {
        "major" to FieldType.Integer
        "minor" to FieldType.Integer
        "micro" to FieldType.Integer
      }
      "connectionType" to FieldType.Integer
      "connectionSpeedKbps" to FieldType.Integer
      "networkMetered" to FieldType.Boolean
      "deviceMetrics" to dataTypeDescriptor(name = "chronicle_dtd.DeviceMetrics", cls =
          DeviceMetrics::class) {
        "make" to FieldType.Integer
        "model" to FieldType.Integer
        "screenHeightDp" to FieldType.Integer
        "screenWidthDp" to FieldType.Integer
        "xdpi" to FieldType.Float
        "ydpi" to FieldType.Float
        "pxRatio" to FieldType.Float
      }
      "appInstalledHistory" to FieldType.List(dataTypeDescriptor(name =
          "chronicle_dtd.AppInstallStatus", cls = AppInstallStatus::class) {
        "packageName" to FieldType.String
        "installed" to FieldType.Boolean
      })
      "appUsageHistory" to FieldType.List(dataTypeDescriptor(name = "chronicle_dtd.AppUsageStatus",
          cls = AppUsageStatus::class) {
        "packageName" to FieldType.String
        "totalTimeUsedMillis" to FieldType.Long
      })
      "currentLocation" to dataTypeDescriptor(name = "chronicle_dtd.Location", cls =
          Location::class) {
        "timeSec" to FieldType.Long
        "latitude" to FieldType.Double
        "longitude" to FieldType.Double
        "locationProvider" to FieldType.Integer
        "preciseLocation" to FieldType.Boolean
      }
      "locationHistory" to FieldType.List(dataTypeDescriptor(name = "chronicle_dtd.LocationStatus",
          cls = LocationStatus::class) {
        "latitude" to FieldType.Double
        "longitude" to FieldType.Double
        "durationMillis" to FieldType.Long
      })
    }
