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

package android.permission.cts;

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.app.Notification.EXTRA_TITLE;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.location.Criteria.ACCURACY_FINE;
import static android.provider.Settings.RESET_MODE_PACKAGE_DEFAULTS;
import static android.provider.Settings.Secure.LOCATION_ACCESS_CHECK_DELAY_MILLIS;
import static android.provider.Settings.Secure.LOCATION_ACCESS_CHECK_INTERVAL_MILLIS;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.AppModeFull;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.job.nano.JobSchedulerServiceDumpProto;
import com.android.server.job.nano.JobSchedulerServiceDumpProto.RegisteredJob;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Tests the {@code LocationAccessCheck} in permission controller.
 */
@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "Cannot set system settings as instant app. Also we never show a location "
        + "access check notification for instant apps.")
public class LocationAccessCheckTest {
    private static final String LOG_TAG = LocationAccessCheckTest.class.getSimpleName();

    private static final String TEST_APP_PKG = "android.permission.cts.appthataccesseslocation";
    private static final String TEST_APP_LABEL = "CtsLocationAccess";
    private static final String TEST_APP_SERVICE = TEST_APP_PKG + ".AccessLocationOnCommand";
    private static final String TEST_APP_LOCATION_BG_ACCESS_APK =
            "/data/local/tmp/cts/permissions/CtsAppThatAccessesLocationOnCommand.apk";
    private static final String TEST_APP_LOCATION_FG_ACCESS_APK =
            "/data/local/tmp/cts/permissions/AppThatDoesNotHaveBgLocationAccess.apk";

    /** Whether to show location access check notifications. */
    private static final String PROPERTY_LOCATION_ACCESS_CHECK_ENABLED = "location_access_check_enabled";

    private static final long UNEXPECTED_TIMEOUT_MILLIS = 10000;
    private static final long EXPECTED_TIMEOUT_MILLIS = 1000;
    private static final long LOCATION_ACCESS_TIMEOUT_MILLIS = 15000;

    // Same as in AccessLocationOnCommand
    private static final long BACKGROUND_ACCESS_SETTLE_TIME = 11000;

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private static final UiAutomation sUiAutomation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation();

    private static final String PERMISSION_CONTROLLER_PKG = sContext.getPackageManager()
            .getPermissionControllerPackageName();

    /**
     * The result of {@link #assumeCanGetFineLocation()}, so we don't have to run it over and over
     * again.
     */
    private static Boolean sCanAccessFineLocation = null;

    /**
     * Connected to {@value #TEST_APP_PKG} and make it access the location in the background
     */
    private static void accessLocation() {
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                sContext.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // ignore
            }
        };

        // Connect and disconnect to service. After the service is disconnected it causes a
        // access to the location
        Intent testAppService = new Intent();
        testAppService.setComponent(new ComponentName(TEST_APP_PKG, TEST_APP_SERVICE));
        sContext.bindService(testAppService, connection, BIND_AUTO_CREATE);
    }

    /**
     * A {@link java.util.concurrent.Callable} that can throw a {@link Throwable}
     */
    private interface ThrowingCallable<T> {
        T call() throws Throwable;
    }

    /**
     * A {@link Runnable} that can throw a {@link Throwable}
     */
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    /**
     * Make sure that a {@link ThrowingRunnable} eventually finishes without throwing a {@link
     * Exception}.
     *
     * @param r       The {@link ThrowingRunnable} to run.
     * @param timeout the maximum time to wait
     */
    public static void eventually(@NonNull ThrowingRunnable r, long timeout) throws Throwable {
        eventually(() -> {
            r.run();
            return 0;
        }, timeout);
    }

    /**
     * Make sure that a {@link ThrowingCallable} eventually finishes without throwing a {@link
     * Exception}.
     *
     * @param r       The {@link ThrowingCallable} to run.
     * @param timeout the maximum time to wait
     *
     * @return the return value from the callable
     *
     * @throws NullPointerException If the return value never becomes non-null
     */
    public static <T> T eventually(@NonNull ThrowingCallable<T> r, long timeout) throws Throwable {
        long start = System.currentTimeMillis();

        while (true) {
            try {
                T res = r.call();
                if (res == null) {
                    throw new NullPointerException("No result");
                }

                return res;
            } catch (Throwable e) {
                if (System.currentTimeMillis() - start < timeout) {
                    Log.d(LOG_TAG, "Ignoring exception", e);

                    Thread.sleep(500);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Get the state of the job scheduler
     */
    public static JobSchedulerServiceDumpProto getJobSchedulerDump() throws Exception {
        ParcelFileDescriptor pfd = sUiAutomation.executeShellCommand("dumpsys jobscheduler --proto");

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            // Copy data from 'is' into 'os'
            try (FileInputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] buffer = new byte[16384];

                while (true) {
                    int numRead = is.read(buffer);

                    if (numRead == -1) {
                        break;
                    } else {
                        os.write(buffer, 0, numRead);
                    }
                }
            }

            return JobSchedulerServiceDumpProto.parseFrom(os.toByteArray());
        }
    }

    /**
     * Clear all data of a package including permissions and files.
     *
     * @param pkg The name of the package to be cleared
     */
    private static void clearPackageData(@NonNull String pkg) {
        runShellCommand("pm clear --user -2 " + pkg);
    }

    /**
     * Force a run of the location check.
     */
    private static void runLocationCheck() {
        runShellCommand("cmd jobscheduler run -f " + PERMISSION_CONTROLLER_PKG + " 0");
    }

    /**
     * Get a notification thrown by the permission controller that is currently visible.
     *
     * @return The notification or {@code null} if there is none
     */
    private @Nullable StatusBarNotification getPermissionControllerNotification() throws Exception {
        NotificationListenerService notificationService = NotificationListener.getInstance();

        for (StatusBarNotification notification : notificationService.getActiveNotifications()) {
            if (notification.getPackageName().equals(PERMISSION_CONTROLLER_PKG)) {
                return notification;
            }
        }

        return null;
    }

    /**
     * Get a location access notification that is currently visible.
     *
     * @param cancelNotification if {@code true} the notification is canceled inside this method
     *
     * @return The notification or {@code null} if there is none
     */
    private StatusBarNotification getNotification(boolean cancelNotification) throws Throwable {
        NotificationListenerService notificationService = NotificationListener.getInstance();

        long start = System.currentTimeMillis();
        while (true) {
            runLocationCheck();

            StatusBarNotification notification = getPermissionControllerNotification();
            if (notification == null) {
                // Sometimes getting a location takes some time, hence not getting a notification
                // can be caused by not having gotten a location yet
                if (System.currentTimeMillis() - start < LOCATION_ACCESS_TIMEOUT_MILLIS
                        + BACKGROUND_ACCESS_SETTLE_TIME) {
                    Thread.sleep(200);
                    continue;
                }

                return null;
            }

            if (notification.getNotification().extras.getString(EXTRA_TITLE, "")
                    .contains(TEST_APP_LABEL)) {
                if (cancelNotification) {
                    notificationService.cancelNotification(notification.getKey());

                    // Wait for notification to get canceled
                    eventually(() -> assertFalse(
                            Arrays.asList(notificationService.getActiveNotifications()).contains(
                                    notification)), UNEXPECTED_TIMEOUT_MILLIS);
                }

                return notification;
            } else {
                notificationService.cancelNotification(notification.getKey());

                // Wait until new notification can be shown
                Thread.sleep(200);
            }
        }
    }

    /**
     * Grant a permission to the {@value #TEST_APP_PKG}.
     *
     * @param permission The permission to grant
     */
    private void grantPermissionToTestApp(@NonNull String permission) {
        sUiAutomation.grantRuntimePermission(TEST_APP_PKG, permission);
    }

    /**
     * Register {@link NotificationListener}.
     */
    @BeforeClass
    public static void allowNotificationAccess() {
        runShellCommand("cmd notification allow_listener " + (new ComponentName(sContext,
                NotificationListener.class).flattenToString()));
    }

    /**
     * Change settings so that permission controller can show location access notifications more
     * often.
     */
    @BeforeClass
    public static void reduceDelays() {
        runWithShellPermissionIdentity(() -> {
            ContentResolver cr = sContext.getContentResolver();

            // New settings will be applied in when permission controller is reset
            Settings.Secure.putLong(cr, LOCATION_ACCESS_CHECK_INTERVAL_MILLIS, 100);
            Settings.Secure.putLong(cr, LOCATION_ACCESS_CHECK_DELAY_MILLIS, 50);
        });
    }

    @BeforeClass
    public static void installBackgroundAccessApp() {
        runShellCommand("pm install -r -g " + TEST_APP_LOCATION_BG_ACCESS_APK);
    }

    @AfterClass
    public static void uninstallBackgroundAccessApp() {
        runShellCommand("pm uninstall " + TEST_APP_PKG);
    }


    private static void installForegroundAccessApp() {
        runShellCommand("pm install -r -g " + TEST_APP_LOCATION_FG_ACCESS_APK);
    }

    /**
     * Reset the permission controllers state before each test
     */
    @Before
    public void resetPermissionControllerBeforeEachTest() throws Throwable {
        resetPermissionController();
    }

    /**
     * Enable location access check
     */
    @Before
    public void enableLocationAccessCheck() {
        runWithShellPermissionIdentity(() -> DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_PRIVACY,
                PROPERTY_LOCATION_ACCESS_CHECK_ENABLED, "true", false));
    }

    /**
     * Make sure fine location can be accessed at all.
     */
    @Before
    public void assumeCanGetFineLocation() {
        if (sCanAccessFineLocation == null) {
            Criteria crit = new Criteria();
            crit.setAccuracy(ACCURACY_FINE);

            CountDownLatch locationCounter = new CountDownLatch(1);
            sContext.getSystemService(LocationManager.class).requestSingleUpdate(crit,
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            locationCounter.countDown();
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {
                        }

                        @Override
                        public void onProviderEnabled(String provider) {
                        }

                        @Override
                        public void onProviderDisabled(String provider) {
                        }
                    }, Looper.getMainLooper());


            try {
                sCanAccessFineLocation = locationCounter.await(LOCATION_ACCESS_TIMEOUT_MILLIS,
                        MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
        }

        assumeTrue(sCanAccessFineLocation);
    }

    /**
     * Reset the permission controllers state.
     */
    private static void resetPermissionController() throws Throwable {
        clearPackageData(PERMISSION_CONTROLLER_PKG);

        // Wait until jobs are cleared
        eventually(() -> {
            JobSchedulerServiceDumpProto dump = getJobSchedulerDump();
            for (RegisteredJob job : dump.registeredJobs) {
                assertNotEquals(job.dump.sourcePackageName, PERMISSION_CONTROLLER_PKG);
            }
        }, UNEXPECTED_TIMEOUT_MILLIS);

        // Setup up permission controller again (simulate a reboot)
        Intent permissionControllerSetupIntent = null;
        for (ResolveInfo ri : sContext.getPackageManager().queryBroadcastReceivers(
                new Intent(ACTION_BOOT_COMPLETED), 0)) {
            String pkg = ri.activityInfo.packageName;

            if (pkg.equals(PERMISSION_CONTROLLER_PKG)) {
                permissionControllerSetupIntent = new Intent();
                permissionControllerSetupIntent.setClassName(pkg, ri.activityInfo.name);
            }
        }

        if (permissionControllerSetupIntent != null) {
            sContext.sendBroadcast(permissionControllerSetupIntent);
        }

        // Wait until jobs are set up
        eventually(() -> {
            JobSchedulerServiceDumpProto dump = getJobSchedulerDump();
            for (RegisteredJob job : dump.registeredJobs) {
                if (job.dump.sourcePackageName.equals(PERMISSION_CONTROLLER_PKG)) {
                    return;
                }
            }

            fail("Permission controller jobs not found");
        }, UNEXPECTED_TIMEOUT_MILLIS);
    }

    /**
     * Unregister {@link NotificationListener}.
     */
    @AfterClass
    public static void disallowNotificationAccess() {
        runShellCommand("cmd notification disallow_listener " + (new ComponentName(sContext,
                        NotificationListener.class)).flattenToString());
    }

    /**
     * Reset settings so that permission controller runs normally.
     */
    @AfterClass
    public static void resetDelays() throws Throwable {
        runWithShellPermissionIdentity(() -> {
            ContentResolver cr = sContext.getContentResolver();

            Settings.Secure.resetToDefaults(cr, LOCATION_ACCESS_CHECK_INTERVAL_MILLIS);
            Settings.Secure.resetToDefaults(cr, LOCATION_ACCESS_CHECK_DELAY_MILLIS);
        });

        resetPermissionController();
    }

    /**
     * Reset location access check
     */
    @After
    public void resetPrivacyConfig() {
        runWithShellPermissionIdentity(
                () -> DeviceConfig.resetToDefaults(RESET_MODE_PACKAGE_DEFAULTS,
                        DeviceConfig.NAMESPACE_PRIVACY));
    }

    @Test
    public void notificationIsShown() throws Throwable {
        accessLocation();
        assertNotNull(getNotification(true));
    }

    @Test
    public void notificationIsShownOnlyOnce() throws Throwable {
        accessLocation();
        getNotification(true);

        assertNull(getNotification(false));
    }

    @Test
    public void notificationIsShownAgainAfterClear() throws Throwable {
        accessLocation();
        getNotification(true);

        clearPackageData(TEST_APP_PKG);

        // Wait until package is cleared and permission controller has cleared the state
        Thread.sleep(10000);

        // Clearing removed the permissions, hence grant them again
        grantPermissionToTestApp(ACCESS_FINE_LOCATION);
        grantPermissionToTestApp(ACCESS_BACKGROUND_LOCATION);

        accessLocation();
        assertNotNull(getNotification(false));
    }

    @Test
    public void notificationIsShownAgainAfterUninstallAndReinstall() throws Throwable {
        accessLocation();
        getNotification(true);

        uninstallBackgroundAccessApp();

        // Wait until package permission controller has cleared the state
        Thread.sleep(2000);

        installBackgroundAccessApp();

        eventually(() -> {
            accessLocation();
            assertNotNull(getNotification(false));
        }, UNEXPECTED_TIMEOUT_MILLIS);
    }

    @Test
    public void removeNotificationOnUninstall() throws Throwable {
        accessLocation();
        getNotification(false);

        uninstallBackgroundAccessApp();

        try {
            eventually(() -> assertNull(getNotification(false)), UNEXPECTED_TIMEOUT_MILLIS);
        } finally {
            installBackgroundAccessApp();
        }
    }

    @Test
    public void notificationIsNotShownAfterAppDoesNotRequestLocationAnymore() throws Throwable {
        accessLocation();
        getNotification(true);

        // Update to app to a version that does not request permission anymore
        installForegroundAccessApp();

        try {
            resetPermissionController();

            try {
                // We don't expect a notification, but try to trigger one anyway
                eventually(() -> assertNotNull(getNotification(false)), EXPECTED_TIMEOUT_MILLIS);
            } catch (AssertionError expected) {
                return;
            }

            fail("Location access notification was shown");
        } finally {
            installBackgroundAccessApp();
        }
    }
}
