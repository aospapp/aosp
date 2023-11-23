/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.command.remote.DeviceDescriptor;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.ITestDevice.ApexInfo;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.FileUtil;

import com.android.tradefed.util.BundletoolUtil;
import java.util.ArrayList;
import java.util.List;
import org.easymock.EasyMock;
import org.mockito.Mockito;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Unit test for {@link InstallApexModuleTargetPreparer} */
@RunWith(JUnit4.class)
public class InstallApexModuleTargetPreparerTest {

    private static final String SERIAL = "serial";
    private InstallApexModuleTargetPreparer mInstallApexModuleTargetPreparer;
    private IBuildInfo mMockBuildInfo;
    private ITestDevice mMockDevice;
    private BundletoolUtil mMockBundletoolUtil;
    private File mFakeApex;
    private File mFakeApk;
    private File mFakeApkApks;
    private File mFakeApexApks;
    private File mBundletoolJar;
    private OptionSetter mSetter;
    private static final String APEX_PACKAGE_NAME = "com.android.FAKE_APEX_PACKAGE_NAME";
    private static final String APK_PACKAGE_NAME = "com.android.FAKE_APK_PACKAGE_NAME";
    private static final String SPLIT_APEX_PACKAGE_NAME =
            "com.android.SPLIT_FAKE_APEX_PACKAGE_NAME";
    private static final String SPLIT_APK_PACKAGE_NAME =
        "com.android.SPLIT_FAKE_APK_PACKAGE_NAME";
    private static final String APEX_PACKAGE_KEYWORD = "FAKE_APEX_PACKAGE_NAME";
    private static final long APEX_VERSION = 1;
    private static final String APEX_NAME = "fakeApex.apex";
    private static final String APK_NAME = "fakeApk.apk";
    private static final String SPLIT_APEX_APKS_NAME = "fakeApex.apks";
    private static final String SPLIT_APK__APKS_NAME = "fakeApk.apks";
    private static final String BUNDLETOOL_JAR_NAME = "bundletool.jar";
    private static final String APEX_DATA_DIR = "/data/apex/active/";
    private static final String STAGING_DATA_DIR = "/data/app-staging/";
    private static final String SESSION_DATA_DIR = "/data/apex/sessions/";

    @Before
    public void setUp() throws Exception {
        mFakeApex = FileUtil.createTempFile("fakeApex", ".apex");
        mFakeApk = FileUtil.createTempFile("fakeApk", ".apk");
        mMockDevice = EasyMock.createMock(ITestDevice.class);
        mMockBuildInfo = EasyMock.createMock(IBuildInfo.class);
        mMockBundletoolUtil = Mockito.mock(BundletoolUtil.class);
        EasyMock.expect(mMockDevice.getSerialNumber()).andStubReturn(SERIAL);
        EasyMock.expect(mMockDevice.getDeviceDescriptor()).andStubReturn(null);

        mInstallApexModuleTargetPreparer =
                new InstallApexModuleTargetPreparer() {
                    @Override
                    protected String getModuleKeywordFromApexPackageName(String packageName) {
                        return APEX_PACKAGE_KEYWORD;
                    }

                    @Override
                    protected String getBundletoolFileName() {
                        return BUNDLETOOL_JAR_NAME;
                    }

                    @Override
                    protected BundletoolUtil getBundletoolUtil() {
                        return mMockBundletoolUtil;
                    }

                    @Override
                    protected File getLocalPathForFilename(
                            IBuildInfo buildInfo, String appFileName, ITestDevice device)
                            throws TargetSetupError {
                        if (appFileName.endsWith(".apex")) {
                            return mFakeApex;
                        }
                        if (appFileName.endsWith(".apk")) {
                            return mFakeApk;
                        }
                        if (appFileName.endsWith(".apks")) {
                            if (appFileName.contains("Apex")) {
                                return mFakeApexApks;
                            }
                            if (appFileName.contains("Apk")) {
                                return mFakeApkApks;
                            }
                        }
                        if (appFileName.endsWith(".jar")) {
                            return mBundletoolJar;
                        }
                        return null;
                    }

                    @Override
                    protected String parsePackageName(
                            File testAppFile, DeviceDescriptor deviceDescriptor) {
                        if (testAppFile.getName().endsWith(".apex")) {
                            return APEX_PACKAGE_NAME;
                        }
                        if (testAppFile.getName().endsWith(".apk") &&
                            !testAppFile.getName().contains("Split")) {
                            return APK_PACKAGE_NAME;
                        }
                        if (testAppFile.getName().endsWith(".apk") &&
                            testAppFile.getName().contains("Split")) {
                            return SPLIT_APK_PACKAGE_NAME;
                        }
                        return null;
                    }

                    @Override
                    protected ApexInfo retrieveApexInfo(
                            File apex, DeviceDescriptor deviceDescriptor) {
                        ApexInfo apexInfo;
                        if (apex.getName().contains("Split")) {
                            apexInfo = new ApexInfo(SPLIT_APEX_PACKAGE_NAME, APEX_VERSION);
                        } else {
                            apexInfo = new ApexInfo(APEX_PACKAGE_NAME, APEX_VERSION);
                        }
                        return apexInfo;
                    }
                };

        mSetter = new OptionSetter(mInstallApexModuleTargetPreparer);
        mSetter.setOptionValue("cleanup-apks", "true");
    }

    @After
    public void tearDown() throws Exception {
        FileUtil.deleteFile(mFakeApex);
        FileUtil.deleteFile(mFakeApk);
    }

    @Test
    public void testSetupSuccess_removeExistingStagedApexSuccess() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);

        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testSetupSuccess_noDataUnderApexDataDirs() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        CommandResult res = new CommandResult();
        res.setStdout("");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mockSuccessfulInstallPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);
        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testSetupSuccess_getActivatedPackageSuccess() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);

        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testSetupFail_getActivatedPackageFail() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallPackageAndReboot();
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(new HashSet<ApexInfo>());

        try {
            EasyMock.replay(mMockBuildInfo, mMockDevice);
            mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
            fail("Should have thrown a TargetSetupError.");
        } catch (TargetSetupError expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("Failed to retrieve activated apex on device serial."));
        } finally {
            EasyMock.verify(mMockBuildInfo, mMockDevice);
        }
    }

    @Test
    public void testSetupFail_apexActivationFail() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME_TO_FAIL", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);

        try {
            EasyMock.replay(mMockBuildInfo, mMockDevice);
            mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
            fail("Should have thrown a TargetSetupError.");
        } catch (TargetSetupError expected) {
            String failureMsg =
                    String.format(
                            "packageName: %s, versionCode: %d", APEX_PACKAGE_NAME, APEX_VERSION);
            assertTrue(expected.getMessage().contains(failureMsg));
        } finally {
            EasyMock.verify(mMockBuildInfo, mMockDevice);
        }
    }

    @Test
    public void testSetupAndTearDown_SingleApk() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APK_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(1);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        EasyMock.expect(mMockDevice.installPackage((File) EasyMock.anyObject(), EasyMock.eq(true)))
                .andReturn(null)
                .once();
        EasyMock.expect(mMockDevice.uninstallPackage(APK_PACKAGE_NAME)).andReturn(null).once();

        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        mInstallApexModuleTargetPreparer.tearDown(mMockDevice, mMockBuildInfo, null);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testSetupAndTearDown_ApkAndApks() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APK_NAME);
        mInstallApexModuleTargetPreparer.addTestFileName(SPLIT_APK__APKS_NAME);;
        mFakeApkApks = File.createTempFile("fakeApk", ".apks");

        File fakeSplitApkApks = File.createTempFile("ApkSplits", "");
        fakeSplitApkApks.delete();
        fakeSplitApkApks.mkdir();
        File splitApk1 = File.createTempFile("fakeSplitApk1", ".apk", fakeSplitApkApks);
        mBundletoolJar = File.createTempFile("bundletool", ".jar");
        File splitApk2 = File.createTempFile("fakeSplitApk2", ".apk", fakeSplitApkApks);
        try {
            mMockDevice.deleteFile(APEX_DATA_DIR + "*");
            EasyMock.expectLastCall().times(1);
            mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
            EasyMock.expectLastCall().times(1);
            mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
            EasyMock.expectLastCall().times(1);
            CommandResult res = new CommandResult();
            res.setStdout("test.apex");
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR))
                .andReturn(res);
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR))
                .andReturn(res);
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR))
                .andReturn(res);
            mMockDevice.reboot();
            EasyMock.expectLastCall();
            when(mMockBundletoolUtil.generateDeviceSpecFile(Mockito.any(ITestDevice.class)))
                .thenReturn("serial.json");

            assertTrue(fakeSplitApkApks != null);
            assertTrue(mFakeApkApks != null);
            assertEquals(2, fakeSplitApkApks.listFiles().length);

            when(mMockBundletoolUtil.extractSplitsFromApks(
                Mockito.eq(mFakeApkApks),
                Mockito.anyString(),
                Mockito.any(ITestDevice.class),
                Mockito.any(IBuildInfo.class)))
                .thenReturn(fakeSplitApkApks);

            mMockDevice.waitForDeviceAvailable();

            List<String> trainInstallCmd = new ArrayList<>();
            trainInstallCmd.add("install-multi-package");
            trainInstallCmd.add(mFakeApk.getAbsolutePath());
            String cmd = "";
            for (File f : fakeSplitApkApks.listFiles()) {
                if (!cmd.isEmpty()) {
                    cmd += ":" + f.getParentFile().getAbsolutePath() + "/" + f.getName();
                } else {
                    cmd += f.getParentFile().getAbsolutePath() + "/" + f.getName();
                }
            }
            trainInstallCmd.add(cmd);
            EasyMock.expect(mMockDevice.executeAdbCommand(trainInstallCmd.toArray(new String[0])))
                .andReturn("Success")
                .once();

            EasyMock.expect(mMockDevice.uninstallPackage(APK_PACKAGE_NAME)).andReturn(null).once();
            EasyMock.expect(mMockDevice.uninstallPackage(SPLIT_APK_PACKAGE_NAME))
                .andReturn(null)
                .once();

            EasyMock.replay(mMockBuildInfo, mMockDevice);
            mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
            mInstallApexModuleTargetPreparer.tearDown(mMockDevice, mMockBuildInfo, null);
            Mockito.verify(mMockBundletoolUtil, times(1))
                .generateDeviceSpecFile(Mockito.any(ITestDevice.class));
            Mockito.verify(mMockBundletoolUtil, times(1))
                .extractSplitsFromApks(
                    Mockito.eq(mFakeApkApks),
                    Mockito.anyString(),
                    Mockito.any(ITestDevice.class),
                    Mockito.any(IBuildInfo.class));
            EasyMock.verify(mMockBuildInfo, mMockDevice);
            assertTrue(!mInstallApexModuleTargetPreparer.getApkInstalled().isEmpty());
        } finally {
            FileUtil.deleteFile(mFakeApexApks);
            FileUtil.deleteFile(mFakeApkApks);
            FileUtil.recursiveDelete(fakeSplitApkApks);
            FileUtil.deleteFile(fakeSplitApkApks);
            FileUtil.deleteFile(mBundletoolJar);
        }
    }

    @Test
    public void testSetupAndTearDown() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);
        mMockDevice.reboot();
        EasyMock.expectLastCall();

        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        mInstallApexModuleTargetPreparer.tearDown(mMockDevice, mMockBuildInfo, null);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testGetModuleKeyword() {
        mInstallApexModuleTargetPreparer = new InstallApexModuleTargetPreparer();
        final String testApex1PackageName = "com.android.foo";
        final String testApex2PackageName = "com.android.bar_test";
        assertEquals(
                "foo",
                mInstallApexModuleTargetPreparer.getModuleKeywordFromApexPackageName(
                        testApex1PackageName));
        assertEquals(
                "bar_test",
                mInstallApexModuleTargetPreparer.getModuleKeywordFromApexPackageName(
                        testApex2PackageName));
    }

    @Test
    public void testSetupAndTearDown_MultiInstall() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(APEX_NAME);
        mInstallApexModuleTargetPreparer.addTestFileName(APK_NAME);
        mMockDevice.deleteFile(APEX_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
        EasyMock.expectLastCall().times(2);
        CommandResult res = new CommandResult();
        res.setStdout("test.apex");
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR)).andReturn(res);
        EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR)).andReturn(res);
        mMockDevice.reboot();
        EasyMock.expectLastCall();
        mockSuccessfulInstallMultiPackageAndReboot();
        Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
        activatedApex.add(new ApexInfo("com.android.FAKE_APEX_PACKAGE_NAME", 1));
        EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);
        EasyMock.expect(mMockDevice.uninstallPackage(APK_PACKAGE_NAME)).andReturn(null).once();
        mMockDevice.reboot();
        EasyMock.expectLastCall();

        EasyMock.replay(mMockBuildInfo, mMockDevice);
        mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
        mInstallApexModuleTargetPreparer.tearDown(mMockDevice, mMockBuildInfo, null);
        EasyMock.verify(mMockBuildInfo, mMockDevice);
    }

    @Test
    public void testInstallUsingBundletool() throws Exception {
        mInstallApexModuleTargetPreparer.addTestFileName(SPLIT_APEX_APKS_NAME);
        mInstallApexModuleTargetPreparer.addTestFileName(SPLIT_APK__APKS_NAME);
        mFakeApexApks = File.createTempFile("fakeApex", ".apks");
        mFakeApkApks = File.createTempFile("fakeApk", ".apks");

        File fakeSplitApexApks = File.createTempFile("ApexSplits", "");
        fakeSplitApexApks.delete();
        fakeSplitApexApks.mkdir();
        File splitApex = File.createTempFile("fakeSplitApex", ".apex", fakeSplitApexApks);

        File fakeSplitApkApks = File.createTempFile("ApkSplits", "");
        fakeSplitApkApks.delete();
        fakeSplitApkApks.mkdir();
        File splitApk1 = File.createTempFile("fakeSplitApk1", ".apk", fakeSplitApkApks);
        mBundletoolJar = File.createTempFile("bundletool", ".jar");
        File splitApk2 = File.createTempFile("fakeSplitApk2", ".apk", fakeSplitApkApks);
        try {
            mMockDevice.deleteFile(APEX_DATA_DIR + "*");
            EasyMock.expectLastCall().times(2);
            mMockDevice.deleteFile(SESSION_DATA_DIR + "*");
            EasyMock.expectLastCall().times(2);
            mMockDevice.deleteFile(STAGING_DATA_DIR + "*");
            EasyMock.expectLastCall().times(2);
            CommandResult res = new CommandResult();
            res.setStdout("test.apex");
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + APEX_DATA_DIR))
                    .andReturn(res);
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + SESSION_DATA_DIR))
                    .andReturn(res);
            EasyMock.expect(mMockDevice.executeShellV2Command("ls " + STAGING_DATA_DIR))
                    .andReturn(res);
            mMockDevice.reboot();
            EasyMock.expectLastCall();
            when(mMockBundletoolUtil.generateDeviceSpecFile(Mockito.any(ITestDevice.class)))
                    .thenReturn("serial.json");

            assertTrue(fakeSplitApexApks != null);
            assertTrue(fakeSplitApkApks != null);
            assertTrue(mFakeApexApks != null);
            assertTrue(mFakeApkApks != null);
            assertEquals(1, fakeSplitApexApks.listFiles().length);
            assertEquals(2, fakeSplitApkApks.listFiles().length);

            when(mMockBundletoolUtil.extractSplitsFromApks(
                            Mockito.eq(mFakeApexApks),
                            Mockito.anyString(),
                            Mockito.any(ITestDevice.class),
                            Mockito.any(IBuildInfo.class)))
                    .thenReturn(fakeSplitApexApks);

            when(mMockBundletoolUtil.extractSplitsFromApks(
                            Mockito.eq(mFakeApkApks),
                            Mockito.anyString(),
                            Mockito.any(ITestDevice.class),
                            Mockito.any(IBuildInfo.class)))
                    .thenReturn(fakeSplitApkApks);

            mMockDevice.waitForDeviceAvailable();

            List<String> trainInstallCmd = new ArrayList<>();
            trainInstallCmd.add("install-multi-package");
            trainInstallCmd.add(splitApex.getAbsolutePath());
            String cmd = "";
            for (File f : fakeSplitApkApks.listFiles()) {
                if (!cmd.isEmpty()) {
                    cmd += ":" + f.getParentFile().getAbsolutePath() + "/" + f.getName();
                } else {
                    cmd += f.getParentFile().getAbsolutePath() + "/" + f.getName();
                }
            }
            trainInstallCmd.add(cmd);
            EasyMock.expect(mMockDevice.executeAdbCommand(trainInstallCmd.toArray(new String[0])))
                    .andReturn("Success")
                    .once();
            mMockDevice.reboot();
            Set<ApexInfo> activatedApex = new HashSet<ApexInfo>();
            activatedApex.add(new ApexInfo(SPLIT_APEX_PACKAGE_NAME, 1));
            EasyMock.expect(mMockDevice.getActiveApexes()).andReturn(activatedApex);
            EasyMock.expect(mMockDevice.uninstallPackage(SPLIT_APK_PACKAGE_NAME))
                .andReturn(null)
                .once();
            mMockDevice.reboot();
            EasyMock.expectLastCall();

            EasyMock.replay(mMockBuildInfo, mMockDevice);
            mInstallApexModuleTargetPreparer.setUp(mMockDevice, mMockBuildInfo);
            mInstallApexModuleTargetPreparer.tearDown(mMockDevice, mMockBuildInfo, null);
            Mockito.verify(mMockBundletoolUtil, times(1))
                    .generateDeviceSpecFile(Mockito.any(ITestDevice.class));
            Mockito.verify(mMockBundletoolUtil, times(1))
                    .extractSplitsFromApks(
                            Mockito.eq(mFakeApexApks),
                            Mockito.anyString(),
                            Mockito.any(ITestDevice.class),
                            Mockito.any(IBuildInfo.class));
            Mockito.verify(mMockBundletoolUtil, times(1))
                    .extractSplitsFromApks(
                            Mockito.eq(mFakeApkApks),
                            Mockito.anyString(),
                            Mockito.any(ITestDevice.class),
                            Mockito.any(IBuildInfo.class));
            EasyMock.verify(mMockBuildInfo, mMockDevice);
        } finally {
            FileUtil.deleteFile(mFakeApexApks);
            FileUtil.deleteFile(mFakeApkApks);
            FileUtil.recursiveDelete(fakeSplitApexApks);
            FileUtil.deleteFile(fakeSplitApexApks);
            FileUtil.recursiveDelete(fakeSplitApkApks);
            FileUtil.deleteFile(fakeSplitApkApks);
            FileUtil.deleteFile(mBundletoolJar);
        }
    }

    private void mockSuccessfulInstallPackageAndReboot() throws Exception {
        EasyMock.expect(mMockDevice.installPackage((File) EasyMock.anyObject(), EasyMock.eq(true)))
                .andReturn(null)
                .once();
        mMockDevice.reboot();
        EasyMock.expectLastCall().once();
    }

    private void mockSuccessfulInstallMultiPackageAndReboot() throws Exception {
        List<String> trainInstallCmd = new ArrayList<>();
        trainInstallCmd.add("install-multi-package");
        trainInstallCmd.add(mFakeApex.getAbsolutePath());
        trainInstallCmd.add(mFakeApk.getAbsolutePath());
        EasyMock.expect(mMockDevice.executeAdbCommand(trainInstallCmd.toArray(new String[0])))
                .andReturn("Success")
                .once();
        mMockDevice.reboot();
        EasyMock.expectLastCall().once();
    }
}
