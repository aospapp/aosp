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

package com.android.tradefed.targetprep;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.Log;
import com.android.tradefed.build.BuildInfoKey.BuildInfoFileKey;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IInvocationContextReceiver;
import com.android.tradefed.testtype.suite.ModuleDefinition;
import com.android.tradefed.util.AbiUtils;
import com.android.tradefed.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link ITargetPreparer} that attempts to push any number of files from any host path to any
 * device path.
 *
 * <p>Should be performed *after* a new build is flashed, and *after* DeviceSetup is run (if
 * enabled)
 */
@OptionClass(alias = "push-file")
public class PushFilePreparer extends BaseTargetPreparer
        implements ITargetCleaner, IAbiReceiver, IInvocationContextReceiver {
    private static final String LOG_TAG = "PushFilePreparer";
    private static final String MEDIA_SCAN_INTENT =
            "am broadcast -a android.intent.action.MEDIA_MOUNTED -d file://%s "
                    + "--receiver-include-background";

    private IAbi mAbi;

    @Option(
        name = "push",
        description =
                "A push-spec, formatted as "
                        + "'/localpath/to/srcfile.txt->/devicepath/to/destfile.txt' "
                        + "or '/localpath/to/srcfile.txt->/devicepath/to/destdir/'. "
                        + "May be repeated. The local path may be relative to the test cases "
                        + "build out directories "
                        + "($ANDROID_HOST_OUT_TESTCASES / $ANDROID_TARGET_OUT_TESTCASES)."
    )
    private Collection<String> mPushSpecs = new ArrayList<>();

    @Option(
        name = "push-file",
        description =
                "A push-spec, specifying the local file to the path where it should be pushed on "
                        + "device. May be repeated."
    )
    private Map<File, String> mPushFileSpecs = new HashMap<>();

    @Option(name="post-push", description=
            "A command to run on the device (with `adb shell (yourcommand)`) after all pushes " +
            "have been attempted.  Will not be run if a push fails with abort-on-push-failure " +
            "enabled.  May be repeated.")
    private Collection<String> mPostPushCommands = new ArrayList<>();

    @Option(name="abort-on-push-failure", description=
            "If false, continue if pushes fail.  If true, abort the Invocation on any failure.")
    private boolean mAbortOnFailure = true;

    @Option(name="trigger-media-scan", description=
            "After pushing files, trigger a media scan of external storage on device.")
    private boolean mTriggerMediaScan = false;

    @Option(name="cleanup", description = "Whether files pushed onto device should be cleaned up "
            + "after test. Note that the preparer does not verify that files/directories have "
            + "been deleted.")
    private boolean mCleanup = false;

    @Option(name="remount-system", description="Remounts system partition to be writable "
            + "so that files could be pushed there too")
    private boolean mRemount = false;

    private Set<String> mFilesPushed = null;
    /** If the preparer is part of a module, we can use the test module name as a search criteria */
    private String mModuleName = null;

    /**
     * Helper method to only throw if mAbortOnFailure is enabled.  Callers should behave as if this
     * method may return.
     */
    private void fail(String message, ITestDevice device) throws TargetSetupError {
        if (mAbortOnFailure) {
            throw new TargetSetupError(message, device.getDeviceDescriptor());
        } else {
            // Log the error and return
            Log.w(LOG_TAG, message);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    /** {@inheritDoc} */
    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    /** {@inheritDoc} */
    @Override
    public void setInvocationContext(IInvocationContext invocationContext) {
        if (invocationContext.getAttributes().get(ModuleDefinition.MODULE_NAME) != null) {
            // Only keep the module name
            mModuleName =
                    invocationContext.getAttributes().get(ModuleDefinition.MODULE_NAME).get(0);
        }
    }

    /**
     * Resolve relative file path via {@link IBuildInfo} and test cases directories.
     *
     * @param buildInfo the build artifact information
     * @param fileName relative file path to be resolved
     * @return the file from the build info or test cases directories
     */
    public File resolveRelativeFilePath(IBuildInfo buildInfo, String fileName) {
        File src = null;
        if (buildInfo != null) {
            src = buildInfo.getFile(fileName);
            if (src != null && src.exists()) {
                return src;
            }
        }
        if (buildInfo instanceof IDeviceBuildInfo) {
            IDeviceBuildInfo deviceBuild = (IDeviceBuildInfo) buildInfo;
            File testDir = deviceBuild.getTestsDir();
            List<File> scanDirs = new ArrayList<>();
            // If it exists, always look first in the ANDROID_TARGET_OUT_TESTCASES
            File targetTestCases = deviceBuild.getFile(BuildInfoFileKey.TARGET_LINKED_DIR);
            if (targetTestCases != null) {
                scanDirs.add(targetTestCases);
            }
            if (testDir != null) {
                scanDirs.add(testDir);
            }

            if (mModuleName != null) {
                // Use module name as a discriminant to find some files
                if (testDir != null) {
                    try {
                        File moduleDir =
                                FileUtil.findDirectory(
                                        mModuleName, scanDirs.toArray(new File[] {}));
                        if (moduleDir != null) {
                            // If the spec is pushing the module itself
                            if (mModuleName.equals(fileName)) {
                                // If that's the main binary generated by the target, we push the
                                // full directory
                                return moduleDir;
                            }
                            // Search the module directory if it exists use it in priority
                            src = FileUtil.findFile(fileName, null, moduleDir);
                            if (src != null) {
                                return src;
                            }
                        } else {
                            CLog.e("Did not find any module directory for '%s'", mModuleName);
                        }
                    } catch (IOException e) {
                        CLog.w(
                                "Something went wrong while searching for the module '%s' "
                                        + "directory.",
                                mModuleName);
                    }
                }
            }
            // Search top-level matches
            for (File searchDir : scanDirs) {
                try {
                    Set<File> allMatch = FileUtil.findFilesObject(searchDir, fileName);
                    if (allMatch.size() > 1) {
                        CLog.d(
                                "Several match for filename '%s', searching for top-level match.",
                                fileName);
                        for (File f : allMatch) {
                            // Bias toward direct child / top level nodes
                            if (f.getParent().equals(searchDir.getAbsolutePath())) {
                                return f;
                            }
                        }
                    } else if (allMatch.size() == 1) {
                        return allMatch.iterator().next();
                    }
                } catch (IOException e) {
                    CLog.w("Failed to find test files from directory.");
                }
            }
            // Fall-back to searching everything
            try {
                // Search the full tests dir if no target dir is available.
                src = FileUtil.findFile(fileName, null, scanDirs.toArray(new File[] {}));
            } catch (IOException e) {
                CLog.w("Failed to find test files from directory.");
                src = null;
            }
        }
        return src;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp(ITestDevice device, IBuildInfo buildInfo) throws TargetSetupError, BuildError,
            DeviceNotAvailableException {
        mFilesPushed = new HashSet<>();
        if (mRemount) {
            device.remountSystemWritable();
        }
        for (String pushspec : mPushSpecs) {
            String[] pair = pushspec.split("->");
            if (pair.length != 2) {
                fail(String.format("Invalid pushspec: '%s'", Arrays.asList(pair)), device);
                continue;
            }
            Log.d(LOG_TAG, String.format("Trying to push local '%s' to remote '%s'", pair[0],
                    pair[1]));
            File src = new File(pair[0]);
            String remotePath = pair[1];
            evaluatePushingPair(device, buildInfo, src, remotePath);
        }
        // Push the file structure
        for (File src : mPushFileSpecs.keySet()) {
            String remotePath = mPushFileSpecs.get(src);
            Log.d(
                    LOG_TAG,
                    String.format(
                            "Trying to push local '%s' to remote '%s'", src.getPath(), remotePath));
            evaluatePushingPair(device, buildInfo, src, remotePath);
        }

        for (String command : mPostPushCommands) {
            device.executeShellCommand(command);
        }

        if (mTriggerMediaScan) {
            String mountPoint = device.getMountPoint(IDevice.MNT_EXTERNAL_STORAGE);
            device.executeShellCommand(String.format(MEDIA_SCAN_INTENT, mountPoint));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tearDown(ITestDevice device, IBuildInfo buildInfo, Throwable e)
            throws DeviceNotAvailableException {
        if (!(e instanceof DeviceNotAvailableException) && mCleanup && mFilesPushed != null) {
            if (mRemount) {
                device.remountSystemWritable();
            }
            for (String devicePath : mFilesPushed) {
                device.deleteFile(devicePath);
            }
        }
    }

    private void addPushedFile(ITestDevice device, String remotePath) throws TargetSetupError {
        if (mFilesPushed.contains(remotePath)) {
            throw new TargetSetupError(
                    String.format(
                            "We pushed two files to the %s location. Check "
                                    + "your configuration of this target_preparer",
                            remotePath),
                    device.getDeviceDescriptor());
        }
        mFilesPushed.add(remotePath);
    }

    private void evaluatePushingPair(
            ITestDevice device, IBuildInfo buildInfo, File src, String remotePath)
            throws TargetSetupError, DeviceNotAvailableException {
        String localPath = src.getPath();
        if (!src.isAbsolute()) {
            src = resolveRelativeFilePath(buildInfo, localPath);
        }
        if (src == null || !src.exists()) {
            fail(String.format("Local source file '%s' does not exist", localPath), device);
            return;
        }
        if (src.isDirectory()) {
            boolean deleteContentOnly = true;
            if (!device.doesFileExist(remotePath)) {
                device.executeShellCommand(String.format("mkdir -p \"%s\"", remotePath));
                deleteContentOnly = false;
            } else if (!device.isDirectory(remotePath)) {
                // File exists and is not a directory
                throw new TargetSetupError(
                        String.format(
                                "Attempting to push dir '%s' to an existing device file '%s'",
                                src.getAbsolutePath(), remotePath),
                        device.getDeviceDescriptor());
            }
            Set<String> filter = new HashSet<>();
            if (mAbi != null) {
                String currentArch = AbiUtils.getArchForAbi(mAbi.getName());
                filter.addAll(AbiUtils.getArchSupported());
                filter.remove(currentArch);
            }
            // TODO: Look into using syncFiles but that requires improving sync to work for unroot
            if (!device.pushDir(src, remotePath, filter)) {
                fail(
                        String.format(
                                "Failed to push local '%s' to remote '%s'", localPath, remotePath),
                        device);
                return;
            } else {
                if (deleteContentOnly) {
                    remotePath += "/*";
                }
                addPushedFile(device, remotePath);
            }
        } else {
            if (!device.pushFile(src, remotePath)) {
                fail(
                        String.format(
                                "Failed to push local '%s' to remote '%s'", localPath, remotePath),
                        device);
                return;
            } else {
                addPushedFile(device, remotePath);
            }
        }
    }
}
