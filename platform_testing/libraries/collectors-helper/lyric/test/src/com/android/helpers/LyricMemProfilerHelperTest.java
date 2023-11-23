/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.helpers;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;
import android.util.Log;

import androidx.test.uiautomator.UiDevice;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Map;
import java.io.IOException;

/**
 * Android unit test for {@link LyricMemProfilerHelper}
 *
 * <p>To run: atest CollectorsHelperTest:com.android.helpers.LyricMemProfilerHelperTest
 */
@RunWith(AndroidJUnit4.class)
public class LyricMemProfilerHelperTest {
    private static final String TAG = LyricMemProfilerHelperTest.class.getSimpleName();

    private @Mock UiDevice mUiDevice;

    private int mMemInfoCmdCounter = 0;

    private int mDmabufCmdCounter = 0;

    private static final int MOCK_NATIVE_HEAP = 100;

    private static final int MOCK_TOTAL_PSS = 200;

    private static final int MOCK_DMABUF = 500;

    private String genMemInfoString(int nativeHeap, int totalPss) {
        return ".Native Heap:" + nativeHeap + " TOTAL PSS:" + totalPss + " .";
    }

    private String genDmabufString(int dmabuf, int pid) {
        String dmabufDumpString =
                " provider@2.7-se:"
                        + pid
                        + "\n"
                        + "                  Name              Rss              Pss        "
                        + " nr_procs            Inode\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           153387\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           153388\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           153389\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           153390\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           205579\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           205580\n"
                        + "         PROCESS TOTAL          1752 kB          "
                        + dmabuf
                        + " kB\n"
                        + "----------------------\n"
                        + "dmabuf total: 1752 kB kernel_rss: 0 kB userspace_rss: 1752 kB"
                        + " userspace_pss: 1752 kB";

        return dmabufDumpString;
    }

    @Before
    public void setUp() throws Throwable {
        MockitoAnnotations.initMocks(this);
        // Return a fake pid for our fake processes and an empty string otherwise.
        doAnswer(
                        (inv) -> {
                            final int pid = 1234;
                            String cmd = (String) inv.getArguments()[0];
                            if (cmd.startsWith("pgrep")) {
                                return Integer.toString(pid);
                            } else if (cmd.startsWith("dumpsys meminfo")) {
                                mMemInfoCmdCounter++;
                                if (10 == mMemInfoCmdCounter) {
                                    return genMemInfoString(
                                            MOCK_NATIVE_HEAP + 50,
                                            MOCK_TOTAL_PSS + 50); // max value
                                } else {
                                    return genMemInfoString(MOCK_NATIVE_HEAP, MOCK_TOTAL_PSS);
                                }
                            } else if (cmd.startsWith("dmabuf_dump")) {
                                mDmabufCmdCounter++;
                                if (10 == mDmabufCmdCounter) {
                                    return genDmabufString(MOCK_DMABUF + 50, pid); // max value
                                } else {
                                    return genDmabufString(MOCK_DMABUF, pid);
                                }
                            }
                            return "";
                        })
                .when(mUiDevice)
                .executeShellCommand(any());
    }

    @Test
    public void testParsePid() {
        LyricMemProfilerHelper helper = new LyricMemProfilerHelper();
        String memInfoString = helper.getMemInfoString();
        String dmabufDumpString = helper.getDmabufDumpString();
        // memInfo and dmabufDump get empty string due to mCameraProviderPid is empty
        assertThat(memInfoString).isEmpty();
        assertThat(dmabufDumpString).isEmpty();

        SystemClock.sleep(1000); // sleep 1 second to wait for camera provider initialize
        helper.startCollecting();
        memInfoString = helper.getMemInfoString();
        dmabufDumpString = helper.getDmabufDumpString();
        Map<String, Integer> metrics = helper.processOutput(memInfoString, dmabufDumpString);

        assertThat(metrics).containsKey("nativeHeap");
        assertThat(metrics).containsKey("totalPss");
        assertThat(metrics).containsKey("dmabuf");

        assertThat(metrics.get("nativeHeap")).isGreaterThan(0);
        assertThat(metrics.get("totalPss")).isGreaterThan(0);
        assertThat(metrics.get("dmabuf")).isGreaterThan(0);
    }

    @Test
    public void testProcessOutput() {
        LyricMemProfilerHelper helper = new LyricMemProfilerHelper();
        helper.startCollecting();
        String pid = helper.getCameraProviderPid();
        String memInfoString =
                "Applications Memory Usage (in Kilobytes):\n"
                        + "Uptime: 2649336 Realtime: 3041976\n"
                        + "** MEMINFO in pid 14612 [android.hardwar] **\n"
                        + "App Summary\n"
                        + "                       Pss(KB)                        Rss(KB)\n"
                        + "                        ------                         ------\n"
                        + "           Java Heap:        0                              0\n"
                        + "         Native Heap:   377584                         377584\n"
                        + "                Code:    79008                         117044\n"
                        + "               Stack:     3364                           3364\n"
                        + "            Graphics:    47672                          47672\n"
                        + "       Private Other:    37188\n"
                        + "              System:     5307\n"
                        + "             Unknown:                                   39136\n"
                        + "\n"
                        + "           TOTAL PSS:   550123            TOTAL RSS:   584800      TOTAL"
                        + " SWAP (KB):        0";

        String dmabufDumpString =
                " provider@2.7-se:"
                        + pid
                        + "\n"
                        + "                  Name              Rss              Pss        "
                        + " nr_procs            Inode\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           153387\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           153388\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           153389\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           153390\n"
                        + "             <unknown>           576 kB           576 kB               "
                        + " 1           205579\n"
                        + "             <unknown>             8 kB             8 kB               "
                        + " 1           205580\n"
                        + "         PROCESS TOTAL          1752 kB          1552 kB\n"
                        + "----------------------\n"
                        + "dmabuf total: 1752 kB kernel_rss: 0 kB userspace_rss: 1752 kB"
                        + " userspace_pss: 1752 kB";

        Map<String, Integer> metrics = helper.processOutput(memInfoString, dmabufDumpString);
        assertThat(metrics.get("nativeHeap")).isEqualTo(377584);
        assertThat(metrics.get("totalPss")).isEqualTo(550123);
        assertThat(metrics.get("dmabuf")).isEqualTo(1552);

        metrics = helper.processOutput("", "");
        assertThat(metrics).doesNotContainKey("nativeHeap");
        assertThat(metrics).doesNotContainKey("totalPss");
        assertThat(metrics).doesNotContainKey("dmabuf");
    }

    @Test
    public void testProfilePeriod() {
        LyricMemProfilerHelper helper = new TestableLyricMemProfilerHelper();
        helper.setProfilePeriodMs(100);
        helper.startCollecting();
        SystemClock.sleep(2000);
        Map<String, Integer> metrics = helper.getMetrics();
        helper.stopCollecting();
        assertThat(metrics).containsKey("nativeHeap");
        assertThat(metrics).containsKey("totalPss");
        assertThat(metrics).containsKey("dmabuf");
        assertThat(metrics).containsKey("maxNativeHeap");
        assertThat(metrics).containsKey("maxTotalPss");
        assertThat(metrics).containsKey("maxDmabuf");

        assertThat(metrics.get("nativeHeap")).isLessThan(metrics.get("maxNativeHeap"));
        assertThat(metrics.get("totalPss")).isLessThan(metrics.get("maxTotalPss"));
        assertThat(metrics.get("dmabuf")).isLessThan(metrics.get("maxDmabuf"));
    }

    @Test
    public void testProfilePeriodLessThanMin() {
        LyricMemProfilerHelper helper = new TestableLyricMemProfilerHelper();
        InOrder inOrder = inOrder(mUiDevice);
        helper.setProfilePeriodMs(50);
        helper.startCollecting();
        try {
            inOrder.verify(mUiDevice).executeShellCommand("pgrep -f -o camera.provider@");
        } catch (IOException e) {
            Log.e(TAG, "Failed to execute Shell command");
        }
        SystemClock.sleep(3000);
        verifyNoMoreInteractions(mUiDevice);

        Map<String, Integer> metrics = helper.getMetrics();
        helper.stopCollecting();
        assertThat(metrics).containsKey("nativeHeap");
        assertThat(metrics).containsKey("totalPss");
        assertThat(metrics).containsKey("dmabuf");
        assertThat(metrics).doesNotContainKey("maxNativeHeap");
        assertThat(metrics).doesNotContainKey("maxTotalPss");
        assertThat(metrics).doesNotContainKey("maxDmabuf");

        assertThat(metrics.get("nativeHeap")).isEqualTo(MOCK_NATIVE_HEAP);
        assertThat(metrics.get("totalPss")).isEqualTo(MOCK_TOTAL_PSS);
        assertThat(metrics.get("dmabuf")).isEqualTo(MOCK_DMABUF);
    }

    @Test
    public void testSetNewPidName() {
        LyricMemProfilerHelper helper = new TestableLyricMemProfilerHelper();
        InOrder inOrder = inOrder(mUiDevice);
        final String newPidName = "new.camera.name";
        helper.setProfilePidName(newPidName);
        helper.startCollecting();
        try {
            inOrder.verify(mUiDevice).executeShellCommand("pgrep -f -o " + newPidName);
        } catch (IOException e) {
            Log.e(TAG, "Failed to execute Shell command");
        }
    }

    private final class TestableLyricMemProfilerHelper extends LyricMemProfilerHelper {
        @Override
        protected UiDevice initUiDevice() {
            return mUiDevice;
        }
    }
}
