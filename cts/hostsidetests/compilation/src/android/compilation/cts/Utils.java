/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.compilation.cts;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.Pair;

import com.google.common.io.ByteStreams;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class Utils {
    private final TestInformation mTestInfo;

    public Utils(TestInformation testInfo) throws Exception {
        assertThat(testInfo.getDevice()).isNotNull();
        mTestInfo = testInfo;
    }

    public String assertCommandSucceeds(String... command) throws Exception {
        CommandResult result =
                mTestInfo.getDevice().executeShellV2Command(String.join(" ", command));
        assertWithMessage(result.toString()).that(result.getExitCode()).isEqualTo(0);
        // Remove trailing \n's.
        return result.getStdout().trim();
    }

    /**
     * Installs a package from resources.
     *
     * @param apkDmResources For each pair, the first item is the APK resource name, and the second
     *         item is the DM resource name or null.
     */
    public void installFromResources(IAbi abi, List<Pair<String, String>> apkDmResources)
            throws Exception {
        // We cannot use `ITestDevice.installPackage` or `SuiteApkInstaller` here because they don't
        // support DM files.
        List<String> cmd = new ArrayList<>(List.of("install-multiple", "--abi", abi.getName()));

        for (Pair<String, String> pair : apkDmResources) {
            String apkResource = pair.first;
            File apkFile = copyResourceToFile(apkResource, File.createTempFile("temp", ".apk"));
            apkFile.deleteOnExit();
            cmd.add(apkFile.getAbsolutePath());

            String dmResource = pair.second;
            if (dmResource != null) {
                File dmFile = copyResourceToFile(
                        dmResource, new File(getDmPath(apkFile.getAbsolutePath())));
                dmFile.deleteOnExit();
                cmd.add(dmFile.getAbsolutePath());
            }
        }

        String result = mTestInfo.getDevice().executeAdbCommand(cmd.toArray(String[] ::new));
        assertWithMessage("Failed to install").that(result).isNotNull();
    }

    public void installFromResources(IAbi abi, String apkResource, String dmResource)
            throws Exception {
        installFromResources(abi, List.of(Pair.create(apkResource, dmResource)));
    }

    public void installFromResources(IAbi abi, String apkResource) throws Exception {
        installFromResources(abi, apkResource, null);
    }

    public void pushFromResource(String resource, String remotePath) throws Exception {
        File tempFile = copyResourceToFile(resource, File.createTempFile("temp", ".tmp"));
        tempFile.deleteOnExit();
        mTestInfo.getDevice().pushFile(tempFile, remotePath);
    }

    public File copyResourceToFile(String resourceName, File file) throws Exception {
        try (OutputStream outputStream = new FileOutputStream(file);
                InputStream inputStream = getClass().getResourceAsStream(resourceName)) {
            assertThat(ByteStreams.copy(inputStream, outputStream)).isGreaterThan(0);
        }
        return file;
    }

    private String getDmPath(String apkPath) throws Exception {
        return apkPath.replaceAll("\\.apk$", ".dm");
    }
}
