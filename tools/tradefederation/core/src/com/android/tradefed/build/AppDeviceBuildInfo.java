/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.tradefed.build;

import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** A {@link IDeviceBuildInfo} that also contains a {@link IAppBuildInfo}. */
public class AppDeviceBuildInfo extends DeviceBuildInfo implements IAppBuildInfo {

    private static final long serialVersionUID = BuildSerializedVersion.VERSION;

    /**
     * @see DeviceBuildInfo#DeviceBuildInfo(String, String)
     */
    public AppDeviceBuildInfo(String buildId, String buildName) {
        super(buildId, buildName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addAppPackageFile(File appPackageFile, String version) {
        setFile(BuildInfoFileKey.PACKAGE_FILES, appPackageFile, version);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<VersionedFile> getAppPackageFiles() {
        List<VersionedFile> origList = getVersionedFiles(BuildInfoFileKey.PACKAGE_FILES);
        List<VersionedFile> listCopy = new ArrayList<VersionedFile>();
        if (origList != null) {
            listCopy.addAll(origList);
        }
        return listCopy;
    }

    /** Copy all the files from the {@link IAppBuildInfo}. */
    public void setAppBuild(IAppBuildInfo appBuild) {
        copyAllFileFrom((BuildInfo) appBuild);
    }

    /** Copy all the files from the {@link IDeviceBuildInfo}. */
    public void setDeviceBuild(IDeviceBuildInfo deviceBuild) {
        copyAllFileFrom((BuildInfo) deviceBuild);
    }
}
