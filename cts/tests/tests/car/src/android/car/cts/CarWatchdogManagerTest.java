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

package android.car.cts;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.car.Car;
import android.car.test.ApiCheckerRule.Builder;
import android.car.watchdog.CarWatchdogManager;
import android.car.watchdog.IoOveruseStats;
import android.car.watchdog.PerStateBytes;
import android.car.watchdog.ResourceOveruseStats;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.AppModeFull;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiLevelUtil;
import com.android.compatibility.common.util.PollingCheck;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;

@AppModeFull(reason = "Instant Apps cannot get car related permissions")
public final class CarWatchdogManagerTest extends AbstractCarTestCase {
    private static final String TAG = CarWatchdogManagerTest.class.getSimpleName();
    // System event performance data collections are extended for at least 30 seconds after
    // receiving the corresponding system event completion notification. During these periods
    // (on <= Android T releases), a custom collection cannot be started. Thus, retry starting
    // custom collection for at least twice this duration.
    private static final long START_CUSTOM_COLLECTION_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);
    private static final String START_CUSTOM_PERF_COLLECTION_CMD =
            "dumpsys android.automotive.watchdog.ICarWatchdog/default --start_perf --max_duration"
                    + " 600 --interval 1";
    private static final String STOP_CUSTOM_PERF_COLLECTION_CMD =
            "dumpsys android.automotive.watchdog.ICarWatchdog/default --stop_perf";
    private static final String RESET_RESOURCE_OVERUSE_CMD = String.format(
            "dumpsys android.automotive.watchdog.ICarWatchdog/default "
                    + "--reset_resource_overuse_stats %s",
            InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
    private static final String START_CUSTOM_COLLECTION_SUCCESS_MSG =
            "Successfully started custom perf collection";
    private static final long FIVE_HUNDRED_KILOBYTES = 1024 * 500;
    // Wait time to sync I/O stats from proc fs -> watchdog daemon -> CarService.
    private static final int STATS_SYNC_WAIT_MS = 5000;

    private final ResourceOveruseStatsPollingCheckCondition
            mResourceOveruseStatsPollingCheckCondition =
            new ResourceOveruseStatsPollingCheckCondition();

    private Context mContext;
    private CarWatchdogManager mCarWatchdogManager;
    private File mFile;

    // TODO(b/242350638): add missing annotations, remove (on child bug of 242350638)
    @Override
    protected void configApiCheckerRule(Builder builder) {
        Log.w(TAG, "Disabling API requirements check");
        builder.disableAnnotationsCheck();
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mFile = mContext.getFilesDir();
        mCarWatchdogManager = (CarWatchdogManager) getCar().getCarManager(Car.CAR_WATCHDOG_SERVICE);
    }

    @Test
    public void testGetResourceOveruseStats() throws Exception {
        runShellCommand(RESET_RESOURCE_OVERUSE_CMD);

        startCustomCollection();

        long writtenBytes = writeToDisk(mFile, FIVE_HUNDRED_KILOBYTES);

        assertWithMessage("Failed to write data to dir '" + mFile.getAbsolutePath() + "'").that(
                writtenBytes).isGreaterThan(0L);

        mResourceOveruseStatsPollingCheckCondition.setWrittenBytes(writtenBytes);

        PollingCheck.waitFor(STATS_SYNC_WAIT_MS, mResourceOveruseStatsPollingCheckCondition);

        // Stop the custom performance collection. This resets watchdog's I/O stat collection to
        // the default interval.
        runShellCommand(STOP_CUSTOM_PERF_COLLECTION_CMD);

        ResourceOveruseStats resourceOveruseStats =
                mResourceOveruseStatsPollingCheckCondition.getResourceOveruseStats();
        IoOveruseStats ioOveruseStats = resourceOveruseStats.getIoOveruseStats();
        PerStateBytes remainingWriteBytes = ioOveruseStats.getRemainingWriteBytes();
        assertWithMessage("Package name").that(resourceOveruseStats.getPackageName())
                .isEqualTo(mContext.getPackageName());
        assertWithMessage("Total bytes written to disk").that(
                ioOveruseStats.getTotalBytesWritten()).isAtLeast(writtenBytes);
        assertWithMessage("Remaining write bytes").that(remainingWriteBytes).isNotNull();
        assertWithMessage("Remaining foreground write bytes").that(
                remainingWriteBytes.getForegroundModeBytes()).isGreaterThan(0);
        assertWithMessage("Remaining background write bytes").that(
                remainingWriteBytes.getBackgroundModeBytes()).isGreaterThan(0);
        assertWithMessage("Remaining garage mode write bytes").that(
                remainingWriteBytes.getGarageModeBytes()).isGreaterThan(0);
        assertWithMessage("Duration in seconds").that(
                ioOveruseStats.getDurationInSeconds()).isGreaterThan(0);
        assertWithMessage("Start time").that(ioOveruseStats.getStartTime()).isGreaterThan(0);
        assertWithMessage("Total overuse").that(ioOveruseStats.getTotalOveruses()).isEqualTo(0);
        assertWithMessage("Total times killed").that(
                ioOveruseStats.getTotalTimesKilled()).isEqualTo(0);
        assertWithMessage("Killable on overuse").that(
                ioOveruseStats.isKillableOnOveruse()).isTrue();
        assertWithMessage("User handle").that(resourceOveruseStats.getUserHandle()).isEqualTo(
                UserHandle.getUserHandleForUid(Process.myUid()));
    }

    /**
     * Test that no exception is thrown when calling the addResourceOveruseListener and
     * removeResourceOveruseListener client APIs.
     *
     * <p>The actual notification handling and killing will
     * be tested with host side tests.
     */
    @Test
    public void testListenIoOveruse() {
        CarWatchdogManager.ResourceOveruseListener listener = resourceOveruseStats -> {
            // Do nothing
        };

        mCarWatchdogManager.addResourceOveruseListener(
                mContext.getMainExecutor(), CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO, listener);
        mCarWatchdogManager.removeResourceOveruseListener(listener);
    }

    @Test
    public void testThrowsExceptionOnNullResourceOveruseListener() {
        assertThrows(NullPointerException.class,
                () -> mCarWatchdogManager.addResourceOveruseListener(
                        mContext.getMainExecutor(), CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                        null));
    }

    /**
     * Starts a custom performance collection with a 1-second interval.
     *
     * <p>This enables watchdog daemon to read proc stats more frequently and reduces the test wait
     * time.
     */
    private static void startCustomCollection() throws Exception {
        if (ApiLevelUtil.isAfter(Build.VERSION_CODES.TIRAMISU)) {
            String result = runShellCommand(START_CUSTOM_PERF_COLLECTION_CMD);
            assertWithMessage("Custom collection start message").that(result)
                    .contains(START_CUSTOM_COLLECTION_SUCCESS_MSG);
            return;
        }
        // TODO(b/261869056): Remove the polling check once it is safe to remove.
        PollingCheck.check("Failed to start custom collect performance data collection",
                START_CUSTOM_COLLECTION_TIMEOUT_MS,
                () -> {
                    String result = runShellCommand(START_CUSTOM_PERF_COLLECTION_CMD);
                    return result.contains(START_CUSTOM_COLLECTION_SUCCESS_MSG) || result.isEmpty();
                });
    }

    private static long writeToDisk(File dir, long size) throws Exception {
        if (!dir.exists()) {
            throw new FileNotFoundException(
                    "directory '" + dir.getAbsolutePath() + "' doesn't exist");
        }
        File uniqueFile = new File(dir, Long.toString(System.nanoTime()));
        try (FileOutputStream fos = new FileOutputStream(uniqueFile)) {
            Log.d(TAG, "Attempting to write " + size + " bytes");
            writeToFos(fos, size);
            fos.getFD().sync();
        }
        return size;
    }

    private static void writeToFos(FileOutputStream fos, long maxSize) throws IOException {
        while (maxSize != 0) {
            int writeSize = (int) Math.min(Integer.MAX_VALUE,
                    Math.min(Runtime.getRuntime().freeMemory(), maxSize));
            Log.i(TAG, "writeSize:" + writeSize);
            try {
                fos.write(new byte[writeSize]);
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
                continue;
            }
            maxSize -= writeSize;
        }
    }

    private final class ResourceOveruseStatsPollingCheckCondition
            implements PollingCheck.PollingCheckCondition {
        private ResourceOveruseStats mResourceOveruseStats;
        private long mWrittenBytes;

        @Override
        public boolean canProceed() {
            mResourceOveruseStats = mCarWatchdogManager.getResourceOveruseStats(
                    CarWatchdogManager.FLAG_RESOURCE_OVERUSE_IO,
                    CarWatchdogManager.STATS_PERIOD_CURRENT_DAY);
            // Flash memory usage stats are polled once every one second. The syncing of stats
            // from proc fs -> watchdog daemon -> CarService can happen across multiple polling,
            // so wait until the reported stats cover the entire write size.
            IoOveruseStats ioOveruseStats = mResourceOveruseStats.getIoOveruseStats();
            return ioOveruseStats != null
                    && ioOveruseStats.getTotalBytesWritten() >= mWrittenBytes;
        }

        public ResourceOveruseStats getResourceOveruseStats() {
            return mResourceOveruseStats;
        }

        public void setWrittenBytes(long writtenBytes) {
            mWrittenBytes = writtenBytes;
        }
    };
}
