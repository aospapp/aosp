/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.cts;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.UiAutomation;
import android.app.stubs.TestTileService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.Until;
import android.test.AndroidTestCase;

import androidx.test.filters.FlakyTest;
import androidx.test.InstrumentationRegistry;

import junit.framework.Assert;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class TileServiceTest extends AndroidTestCase {
    final String TAG = TileServiceTest.class.getSimpleName();

    // Time between checks for state we expect.
    private static final long CHECK_DELAY = 250;
    // Number of times to check before failing. This is set so the maximum wait time is about 4s,
    // as some tests were observed to take around 3s.
    private static final long CHECK_RETRIES = 15;
    // Timeout to wait for launcher
    private static final long TIMEOUT = 8000;

    private TileService mTileService;
    private Intent homeIntent;
    private String mLauncherPackage;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);

        mLauncherPackage = mContext.getPackageManager().resolveActivity(homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;

        // Wait for home
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        device.wait(Until.hasObject(By.pkg(mLauncherPackage).depth(0)), TIMEOUT);
    }

    @Override
    protected void tearDown() throws Exception {
        expandSettings(false);
        toggleServiceAccess(TestTileService.getComponentName().flattenToString(), false);
        waitForConnected(false);
        assertNull(TestTileService.getInstance());
    }

    public void testCreateTileService() {
        final TileService tileService = new TileService();
    }

    public void testListening() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
    }

    public void testListening_stopped() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
        expandSettings(false);
        waitForListening(false);
    }

    public void testLocked_deviceNotLocked() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
        assertFalse(mTileService.isLocked());
    }

    public void testSecure_deviceNotSecure() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
        assertFalse(mTileService.isSecure());
    }
    
    public void testTile_hasCorrectIcon() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
        Tile tile = mTileService.getQsTile();
        assertEquals(TestTileService.ICON_ID, tile.getIcon().getResId());
    }

    public void testTile_hasCorrectSubtitle() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);

        Tile tile = mTileService.getQsTile();
        tile.setSubtitle("test_subtitle");
        tile.updateTile();
        assertEquals("test_subtitle", tile.getSubtitle());
    }

    public void testShowDialog() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        Looper.prepare();
        Dialog dialog = new AlertDialog.Builder(mContext).create();
        startTileService();
        expandSettings(true);
        waitForListening(true);
        clickTile(TestTileService.getComponentName().flattenToString());
        waitForClick();

        mTileService.showDialog(dialog);

        assertTrue(dialog.isShowing());
        dialog.dismiss();
    }

    public void testUnlockAndRun_phoneIsUnlockedActivityIsRun() throws Exception {
        if (!TileService.isQuickSettingsSupported()) return;
        startTileService();
        expandSettings(true);
        waitForListening(true);
        assertFalse(mTileService.isLocked());

        TestRunnable testRunnable = new TestRunnable();

        mTileService.unlockAndRun(testRunnable);
        Thread.sleep(100); // wait for activity to run
        waitForRun(testRunnable);
    }

    private void startTileService() throws Exception {
        toggleServiceAccess(TestTileService.getComponentName().flattenToString(), true);
        waitForConnected(true); // wait for service to be bound
        mTileService = TestTileService.getInstance();
        assertNotNull(mTileService);
    }

    private void toggleServiceAccess(String componentName, boolean on) throws Exception {

        String command = " cmd statusbar " + (on ? "add-tile " : "remove-tile ")
                + componentName;

        runCommand(command);
    }

    private void clickTile(String componentName) throws Exception {
        runCommand(" cmd statusbar click-tile " + componentName);
    }

    private void runCommand(String command) throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        // Execute command
        try (ParcelFileDescriptor fd = uiAutomation.executeShellCommand(command)) {
            Assert.assertNotNull("Failed to execute shell command: " + command, fd);
            // Wait for the command to finish by reading until EOF
            try (InputStream in = new FileInputStream(fd.getFileDescriptor())) {
                byte[] buffer = new byte[4096];
                while (in.read(buffer) > 0) {}
            } catch (IOException e) {
                throw new IOException("Could not read stdout of command: " + command, e);
            }
        } finally {
            uiAutomation.destroy();
        }
    }

    /**
     * Waits for the TileService to receive the clicked event. If it times out it fails the test.
     * @throws InterruptedException
     */
    private void waitForClick() throws InterruptedException {
        int ct = 0;
        while (!TestTileService.hasBeenClicked() && (ct++ < CHECK_RETRIES)) {
            Thread.sleep(CHECK_DELAY);
        }
        assertTrue(TestTileService.hasBeenClicked());
    }

    /**
     * Waits for the runnable to be run. If it times out it fails the test.
     * @throws InterruptedException
     */
    private void waitForRun(TestRunnable t) throws InterruptedException {
        int ct = 0;
        while (!t.hasRan && (ct++ < CHECK_RETRIES)) {
            Thread.sleep(CHECK_DELAY);
        }
        assertTrue(t.hasRan);
    }

    /**
     * Waits for the TileService to be in the expected listening state. If it times out, it fails
     * the test
     * @param state desired listening state
     * @throws InterruptedException
     */
    private void waitForListening(boolean state) throws InterruptedException {
        int ct = 0;
        while (TestTileService.isListening() != state && (ct++ < CHECK_RETRIES)) {
            Thread.sleep(CHECK_DELAY);
        }
        assertEquals(state, TestTileService.isListening());
    }

    /**
     * Waits for the TileService to be in the expected connected state. If it times out, it fails
     * the test
     * @param state desired connected state
     * @throws InterruptedException
     */
    private void waitForConnected(boolean state) throws InterruptedException {
        int ct = 0;
        while (TestTileService.isConnected() != state && (ct++ < CHECK_RETRIES)) {
            Thread.sleep(CHECK_DELAY);
        }
        assertEquals(state, TestTileService.isConnected());
    }

    private void expandSettings(boolean expand) throws Exception {
        runCommand(" cmd statusbar " + (expand ? "expand-settings" : "collapse"));
        Thread.sleep(200); // wait for animation
    }

    class TestRunnable implements Runnable {
        boolean hasRan = false;

        @Override
        public void run() {
            hasRan = true;
        }
    }
}
