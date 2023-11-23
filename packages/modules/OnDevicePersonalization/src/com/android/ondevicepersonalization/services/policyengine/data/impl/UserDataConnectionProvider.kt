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

package com.android.ondevicepersonalization.services.policyengine.data.impl

import android.ondevicepersonalization.UserData
import android.ondevicepersonalization.OSVersion
import android.ondevicepersonalization.DeviceMetrics
import android.ondevicepersonalization.Location
import android.ondevicepersonalization.AppInstallStatus
import android.ondevicepersonalization.AppUsageStatus
import android.ondevicepersonalization.LocationStatus

import com.android.ondevicepersonalization.services.data.user.UserDataDao
import com.android.ondevicepersonalization.services.data.user.RawUserData
import com.android.ondevicepersonalization.services.data.user.UserDataCollector
import com.android.libraries.pcc.chronicle.api.Connection
import com.android.libraries.pcc.chronicle.api.ConnectionProvider
import com.android.libraries.pcc.chronicle.api.ConnectionRequest
import com.android.libraries.pcc.chronicle.api.DataType
import com.android.libraries.pcc.chronicle.api.ManagedDataType
import com.android.libraries.pcc.chronicle.api.ManagementStrategy
import com.android.libraries.pcc.chronicle.api.StorageMedia

import com.android.ondevicepersonalization.services.policyengine.data.USER_DATA_GENERATED_DTD
import com.android.ondevicepersonalization.services.policyengine.data.UserDataReader

import java.time.Duration

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** [ConnectionProvider] implementation for ODA use data. */
class UserDataConnectionProvider() : ConnectionProvider {
    override val dataType: DataType =
        ManagedDataType(
            USER_DATA_GENERATED_DTD,
            ManagementStrategy.Stored(false, StorageMedia.MEMORY, Duration.ofDays(30)),
            setOf(UserDataReader::class.java)
        )

    override fun getConnection(connectionRequest: ConnectionRequest<out Connection>): Connection {
        return UserDataReaderImpl()
    }

    class UserDataReaderImpl : UserDataReader {
        override fun readUserData(): UserData? {
            val rawUserData: RawUserData? = RawUserData.getInstance();
            if (rawUserData == null) {
                return null;
            }

            // TODO(b/267013762): more privacy-preserving processing may be needed
            return UserData.Builder()
                    .setTimeSec(rawUserData.timeMillis / 1000)
                    .setTimezone(rawUserData.utcOffset)
                    .setOrientation(rawUserData.orientation)
                    .setAvailableBytesMB(rawUserData.availableBytesMB)
                    .setBatteryPct(rawUserData.batteryPct)
                    .setCountry(rawUserData.country.ordinal)
                    .setLanguage(rawUserData.language.ordinal)
                    .setCarrier(rawUserData.carrier.ordinal)
                    .setOsVersions(OSVersion.Builder()
                            .setMajor(rawUserData.osVersions.major)
                            .setMinor(rawUserData.osVersions.minor)
                            .setMicro(rawUserData.osVersions.micro)
                            .build())
                    .setConnectionType(rawUserData.connectionType.ordinal)
                    .setConnectionSpeedKbps(rawUserData.connectionSpeedKbps)
                    .setNetworkMetered(rawUserData.networkMeteredStatus)
                    .setDeviceMetrics(DeviceMetrics.Builder()
                            .setMake(rawUserData.deviceMetrics.make.ordinal)
                            .setModel(rawUserData.deviceMetrics.model.ordinal)
                            .setScreenHeights(rawUserData.deviceMetrics.screenHeight)
                            .setScreenWidth(rawUserData.deviceMetrics.screenWidth)
                            .setXdpi(rawUserData.deviceMetrics.xdpi)
                            .setYdpi(rawUserData.deviceMetrics.ydpi)
                            .setPxRatio(rawUserData.deviceMetrics.pxRatio)
                            .build())
                    .setCurrentLocation(Location.Builder()
                            .setTimeSec(rawUserData.currentLocation.timeMillis / 1000)
                            .setLatitude(rawUserData.currentLocation.latitude)
                            .setLongitude(rawUserData.currentLocation.longitude)
                            .setLocationProvider(rawUserData.currentLocation.provider.ordinal)
                            .setPreciseLocation(rawUserData.currentLocation.isPreciseLocation)
                            .build())
                    .setAppInstalledHistory(getAppInstalledHistory(rawUserData))
                    .setAppUsageHistory(getAppUsageHistory(rawUserData))
                    .setLocationHistory(getLocationHistory(rawUserData))
                    .build()
        }

        private fun getAppInstalledHistory(rawUserData: RawUserData): List<AppInstallStatus> {
            var res = ArrayList<AppInstallStatus>()
            for (appInfo in rawUserData.appsInfo) {
                res.add(AppInstallStatus.Builder()
                        .setPackageName(appInfo.packageName)
                        .setInstalled(appInfo.installed)
                        .build())
            }
            return res
        }

        private fun getAppUsageHistory(rawUserData: RawUserData): List<AppUsageStatus> {
            var res = ArrayList<AppUsageStatus>()
            rawUserData.appUsageHistory.forEach {
                (key, value) -> res.add(AppUsageStatus.Builder()
                        .setPackageName(key)
                        .setTotalTimeUsedInMillis(value)
                        .build())
            }
            return res.sortedWith(compareBy({ it.getTotalTimeUsedInMillis() }))
        }

        private fun getLocationHistory(rawUserData: RawUserData): List<LocationStatus> {
            var res = ArrayList<LocationStatus>()
            rawUserData.locationHistory.forEach {
                (key, value) -> res.add(LocationStatus.Builder()
                        .setLatitude(key.latitude)
                        .setLongitude(key.longitude)
                        .setDurationMillis(value)
                        .build())
            }
            return res.sortedWith(compareBy({ it.getDurationMillis() }))
        }
    }
}
