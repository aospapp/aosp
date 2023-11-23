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

package com.android.ondevicepersonalization.services.download.mdd;

import static android.content.pm.PackageManager.GET_META_DATA;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.vendor.OnDevicePersonalizationVendorDataDao;
import com.android.ondevicepersonalization.services.manifest.AppManifestConfigHelper;
import com.android.ondevicepersonalization.services.util.PackageUtils;

import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.GetFileGroupsByFilterRequest;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.RemoveFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.ClientConfigProto;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FileGroupPopulator to add FileGroups for ODP onboarded packages
 */
public class OnDevicePersonalizationFileGroupPopulator implements FileGroupPopulator {
    private static final String TAG = "OnDevicePersonalizationFileGroupPopulator";

    private final Context mContext;

    public OnDevicePersonalizationFileGroupPopulator(Context context) {
        this.mContext = context;
    }

    /**
     * A helper function to create a DataFilegroup.
     */
    public static DataFileGroup createDataFileGroup(
            String groupName,
            String ownerPackage,
            String[] fileId,
            int[] byteSize,
            String[] checksum,
            ChecksumType[] checksumType,
            String[] url,
            DeviceNetworkPolicy deviceNetworkPolicy) {
        if (fileId.length != byteSize.length
                || fileId.length != checksum.length
                || fileId.length != url.length
                || checksumType.length != fileId.length) {
            throw new IllegalArgumentException();
        }

        DataFileGroup.Builder dataFileGroupBuilder =
                DataFileGroup.newBuilder()
                        .setGroupName(groupName)
                        .setOwnerPackage(ownerPackage)
                        .setDownloadConditions(
                                DownloadConditions.newBuilder().setDeviceNetworkPolicy(
                                        deviceNetworkPolicy));

        for (int i = 0; i < fileId.length; ++i) {
            DataFile file =
                    DataFile.newBuilder()
                            .setFileId(fileId[i])
                            .setByteSize(byteSize[i])
                            .setChecksum(checksum[i])
                            .setChecksumType(checksumType[i])
                            .setUrlToDownload(url[i])
                            .build();
            dataFileGroupBuilder.addFile(file);
        }

        return dataFileGroupBuilder.build();
    }

    /**
     * Creates the fileGroup name based off the package's name and cert.
     *
     * @param packageName Name of the package owning the fileGroup
     * @param context     Context of the calling service/application
     * @return The created fileGroup name.
     */
    public static String createPackageFileGroupName(String packageName, Context context) throws
            PackageManager.NameNotFoundException {
        return packageName + "_" + PackageUtils.getCertDigest(context, packageName);
    }

    /**
     * Creates the MDD download URL for the given package
     *
     * @param packageName PackageName of the package owning the fileGroup
     * @param context     Context of the calling service/application
     * @return The created MDD URL for the package.
     */
    @VisibleForTesting
    public static String createDownloadUrl(String packageName, Context context) throws
            PackageManager.NameNotFoundException {
        String baseURL = AppManifestConfigHelper.getDownloadUrlFromOdpSettings(
                context, packageName);
        if (baseURL == null) {
            throw new IllegalArgumentException("Failed to retrieve base download URL");
        }
        Uri uri = Uri.parse(baseURL);

        // Enforce URI scheme
        if (OnDevicePersonalizationLocalFileDownloader.isLocalOdpUri(uri)) {
            if (!PackageUtils.isPackageDebuggable(context, packageName)) {
                throw new IllegalArgumentException("Local urls are only valid "
                        + "for debuggable packages: " + baseURL);
            }
        } else if (!baseURL.startsWith("https")) {
            throw new IllegalArgumentException("File url is not secure: " + baseURL);
        }

        return addDownloadUrlQueryParameters(uri, packageName, context);
    }

    /**
     * Adds query parameters to the download URL.
     */
    private static String addDownloadUrlQueryParameters(Uri uri, String packageName,
            Context context)
            throws PackageManager.NameNotFoundException {
        long syncToken = OnDevicePersonalizationVendorDataDao.getInstance(context, packageName,
                PackageUtils.getCertDigest(context, packageName)).getSyncToken();
        if (syncToken != -1) {
            uri = uri.buildUpon().appendQueryParameter("syncToken",
                    String.valueOf(syncToken)).build();
        }
        // TODO(b/267177135) Add user data query parameters here
        return uri.toString();
    }

    @Override
    public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
        GetFileGroupsByFilterRequest request =
                GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build();
        return FluentFuture.from(mobileDataDownload.getFileGroupsByFilter(request))
                .transformAsync(fileGroupList -> {
                    Set<String> fileGroupsToRemove = new HashSet<>();
                    for (ClientConfigProto.ClientFileGroup fileGroup : fileGroupList) {
                        fileGroupsToRemove.add(fileGroup.getGroupName());
                    }
                    List<ListenableFuture<Boolean>> mFutures = new ArrayList<>();
                    for (PackageInfo packageInfo : mContext.getPackageManager()
                            .getInstalledPackages(
                                    PackageManager.PackageInfoFlags.of(GET_META_DATA))) {
                        if (AppManifestConfigHelper.manifestContainsOdpSettings(
                                mContext, packageInfo.packageName)) {
                            try {
                                String groupName = createPackageFileGroupName(
                                        packageInfo.packageName,
                                        mContext);
                                fileGroupsToRemove.remove(groupName);
                                String ownerPackage = mContext.getPackageName();
                                String fileId = groupName;
                                int byteSize = 0;
                                String checksum = "";
                                ChecksumType checksumType = ChecksumType.NONE;
                                String downloadUrl = createDownloadUrl(packageInfo.packageName,
                                        mContext);
                                DeviceNetworkPolicy deviceNetworkPolicy =
                                        DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI;
                                DataFileGroup dataFileGroup = createDataFileGroup(
                                        groupName,
                                        ownerPackage,
                                        new String[]{fileId},
                                        new int[]{byteSize},
                                        new String[]{checksum},
                                        new ChecksumType[]{checksumType},
                                        new String[]{downloadUrl},
                                        deviceNetworkPolicy);
                                mFutures.add(mobileDataDownload.addFileGroup(
                                        AddFileGroupRequest.newBuilder().setDataFileGroup(
                                                dataFileGroup).build()));
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to create file group for "
                                        + packageInfo.packageName, e);
                            }
                        }
                    }

                    for (String group : fileGroupsToRemove) {
                        mFutures.add(mobileDataDownload.removeFileGroup(
                                RemoveFileGroupRequest.newBuilder().setGroupName(group).build()));
                    }

                    return PropagatedFutures.transform(
                            Futures.successfulAsList(mFutures),
                            result -> {
                                if (result.contains(null)) {
                                    Log.d(TAG, "Failed to add or remove a file group");
                                } else {
                                    Log.d(TAG, "Successfully updated all file groups");
                                }
                                return null;
                            },
                            OnDevicePersonalizationExecutors.getBackgroundExecutor()
                    );
                }, OnDevicePersonalizationExecutors.getBackgroundExecutor());
    }
}
