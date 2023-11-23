/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.providerui.cts;

import static android.Manifest.permission.ACCESS_BACKGROUND_LOCATION;
import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.ACCESS_MEDIA_LOCATION;
import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.RECORD_AUDIO;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.MediaStore;
import android.providerui.cts.GetResultActivity.Result;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.BySelector;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.UiSelector;
import android.support.test.uiautomator.Until;
import android.system.Os;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.content.FileProvider;
import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
public class MediaStoreUiTest {
    private static final String TAG = "MediaStoreUiTest";

    private static final int REQUEST_CODE = 42;

    private Instrumentation mInstrumentation;
    private Context mContext;
    private UiDevice mDevice;
    private GetResultActivity mActivity;

    private File mFile;
    private Uri mMediaStoreUri;
    private String mTargetPackageName;

    @Parameter(0)
    public String mVolumeName;

    @Parameters
    public static Iterable<? extends Object> data() {
        return MediaStore.getExternalVolumeNames(InstrumentationRegistry.getTargetContext());
    }

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = InstrumentationRegistry.getTargetContext();
        mDevice = UiDevice.getInstance(mInstrumentation);

        final Intent intent = new Intent(mContext, GetResultActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mActivity = (GetResultActivity) mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        mActivity.clearResult();
    }

    @After
    public void tearDown() throws Exception {
        if (mFile != null) {
            mFile.delete();
        }

        final ContentResolver resolver = mActivity.getContentResolver();
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            mActivity.revokeUriPermission(
                    permission.getUri(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }

        mActivity.finish();
    }

    @Test
    public void testGetDocumentUri() throws Exception {
        if (!supportsHardware()) return;

        prepareFile();

        final Uri treeUri = acquireAccess(mFile, Environment.DIRECTORY_DOCUMENTS);
        assertNotNull(treeUri);

        final Uri docUri = MediaStore.getDocumentUri(mActivity, mMediaStoreUri);
        assertNotNull(docUri);

        final ContentResolver resolver = mActivity.getContentResolver();

        // Test reading
        final byte[] expected = "TEST".getBytes();
        final byte[] actual = new byte[4];
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(docUri, "r")) {
            Os.read(fd.getFileDescriptor(), actual, 0, actual.length);
            assertArrayEquals(expected, actual);
        }

        // Test writing
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(docUri, "wt")) {
            Os.write(fd.getFileDescriptor(), expected, 0, expected.length);
        }
    }

    @Test
    public void testGetDocumentUri_ThrowsWithoutPermission() throws Exception {
        if (!supportsHardware()) return;

        prepareFile();

        try {
            MediaStore.getDocumentUri(mActivity, mMediaStoreUri);
            fail("Expecting SecurityException.");
        } catch (SecurityException e) {
            // Expected
        }
    }

    @Test
    public void testGetDocumentUri_Symmetry() throws Exception {
        if (!supportsHardware()) return;

        prepareFile();

        final Uri treeUri = acquireAccess(mFile, Environment.DIRECTORY_DOCUMENTS);
        Log.v(TAG, "Tree " + treeUri);
        assertNotNull(treeUri);

        final Uri docUri = MediaStore.getDocumentUri(mActivity, mMediaStoreUri);
        Log.v(TAG, "Document " + docUri);
        assertNotNull(docUri);

        final Uri mediaUri = MediaStore.getMediaUri(mActivity, docUri);
        Log.v(TAG, "Media " + mediaUri);
        assertNotNull(mediaUri);

        assertEquals(mMediaStoreUri, mediaUri);
    }

    private void maybeClick(UiSelector sel) {
        try { mDevice.findObject(sel).click(); } catch (Throwable ignored) { }
    }

    private void maybeClick(BySelector sel) {
        try { mDevice.findObject(sel).click(); } catch (Throwable ignored) { }
    }

    private void maybeGrantRuntimePermission(String pkg, Set<String> requested, String permission)
            throws NameNotFoundException {
        // We only need to grant dangerous permissions
        if ((mContext.getPackageManager().getPermissionInfo(permission, 0).getProtection()
                & PermissionInfo.PROTECTION_DANGEROUS) == 0) {
            return;
        }

        if (requested.contains(permission)) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(pkg, permission);
        }
    }

    /**
     * Verify that whoever handles {@link MediaStore#ACTION_IMAGE_CAPTURE} can
     * correctly write the contents into a passed {@code content://} Uri.
     */
    @Test
    public void testImageCaptureWithInadequeteLocationPermissions() throws Exception {
        Set<String> perms = new HashSet<>();
        perms.add(ACCESS_COARSE_LOCATION);
        perms.add(ACCESS_BACKGROUND_LOCATION);
        perms.add(ACCESS_MEDIA_LOCATION);
        testImageCaptureWithoutLocation(perms);
    }
     /**
     * Helper function to verify that whoever handles {@link MediaStore#ACTION_IMAGE_CAPTURE} can
     * correctly write the contents into a passed {@code content://} Uri, without location
     * information, necessarily, when ACCESS_FINE_LOCATION permissions aren't given.
     */
    private void testImageCaptureWithoutLocation(Set<String> locationPermissions)
            throws Exception {
        assertFalse("testImageCaptureWithoutLocation should not be passed ACCESS_FINE_LOCATION",
                locationPermissions.contains(ACCESS_FINE_LOCATION));
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            Log.d(TAG, "Skipping due to lack of camera");
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        final File targetDir = new File(mContext.getFilesDir(), "debug");
        final File target = new File(targetDir, timeStamp  + "capture.jpg");

        targetDir.mkdirs();
        assertFalse(target.exists());

        final Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(MediaStore.EXTRA_OUTPUT,
                FileProvider.getUriForFile(mContext, "android.providerui.cts.fileprovider", target));

        // Figure out who is going to answer the phone
        final ResolveInfo ri = mContext.getPackageManager().resolveActivity(intent, 0);
        final String answeringPkg = ri.activityInfo.packageName;
        Log.d(TAG, "We're probably launching " + ri);

        final PackageInfo pi = mContext.getPackageManager().getPackageInfo(answeringPkg,
                PackageManager.GET_PERMISSIONS);
        final Set<String> answeringReq = new HashSet<>();
        answeringReq.addAll(Arrays.asList(pi.requestedPermissions));
        // Grant the 'answering' app all the permissions they might want.
        maybeGrantRuntimePermission(answeringPkg, answeringReq, CAMERA);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, ACCESS_FINE_LOCATION);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, ACCESS_COARSE_LOCATION);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, ACCESS_BACKGROUND_LOCATION);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, RECORD_AUDIO);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, READ_EXTERNAL_STORAGE);
        maybeGrantRuntimePermission(answeringPkg, answeringReq, WRITE_EXTERNAL_STORAGE);
        SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);

        grantSelfRequisitePermissions(locationPermissions);

        Result result = getImageCaptureIntentResult(intent, answeringPkg);

        assertTrue("exists", target.exists());
        assertTrue("has data", target.length() > 65536);

        // At the very least we expect photos generated by the device to have
        // sane baseline EXIF data
        final ExifInterface exif = new ExifInterface(new FileInputStream(target));
        assertAttribute(exif, ExifInterface.TAG_MAKE);
        assertAttribute(exif, ExifInterface.TAG_MODEL);
        assertAttribute(exif, ExifInterface.TAG_DATETIME);
        float[] latLong = new float[2];
        Boolean hasLocation = exif.getLatLong(latLong);
        assertTrue("Should not contain location information latitude: " + latLong[0] +
                " longitude: " + latLong[1], !hasLocation);
    }

    private void grantSelfRequisitePermissions(Set<String> locationPermissions)
            throws Exception {
        String selfPkg = mContext.getPackageName();
        for (String perm : locationPermissions) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(selfPkg, perm);
            assertTrue("Permission " + perm + "could not be granted",
                    mContext.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED);
        }
    }

    private Result getImageCaptureIntentResult(Intent intent, String answeringPkg)
            throws Exception {

        mActivity.startActivityForResult(intent, REQUEST_CODE);
        mDevice.waitForIdle();

        // To ensure camera app is launched
        SystemClock.sleep(5 * DateUtils.SECOND_IN_MILLIS);

        // Try a couple different strategies for taking a photo / capturing a video: first capture
        // and confirm using hardware keys.
        mDevice.pressKeyCode(KeyEvent.KEYCODE_CAMERA);
        mDevice.waitForIdle();
        SystemClock.sleep(5 * DateUtils.SECOND_IN_MILLIS);
        // We're done.
        mDevice.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER);
        mDevice.waitForIdle();

        // Maybe that gave us a result?
        Result result = mActivity.getResult(15, TimeUnit.SECONDS);
        Log.d(TAG, "First pass result was " + result);

        // Hrm, that didn't work; let's try an alternative approach of digging
        // around for a shutter button
        if (result == null) {
            maybeClick(new UiSelector().resourceId(answeringPkg + ":id/shutter_button"));
            mDevice.waitForIdle();
            SystemClock.sleep(5 * DateUtils.SECOND_IN_MILLIS);
            maybeClick(new UiSelector().resourceId(answeringPkg + ":id/shutter_button"));
            mDevice.waitForIdle();
            maybeClick(new UiSelector().resourceId(answeringPkg + ":id/done_button"));
            mDevice.waitForIdle();

            result = mActivity.getResult(15, TimeUnit.SECONDS);
            Log.d(TAG, "Second pass result was " + result);
        }

        // Grr, let's try hunting around even more
        if (result == null) {
            maybeClick(By.pkg(answeringPkg).descContains("Capture"));
            mDevice.waitForIdle();
            SystemClock.sleep(5 * DateUtils.SECOND_IN_MILLIS);
            maybeClick(By.pkg(answeringPkg).descContains("Done"));
            mDevice.waitForIdle();

            result = mActivity.getResult(15, TimeUnit.SECONDS);
            Log.d(TAG, "Third pass result was " + result);
        }

        assertNotNull("Expected to get a IMAGE_CAPTURE result; your camera app should "
                + "respond to the CAMERA and DPAD_CENTER keycodes", result);
        return result;
    }

    private static void assertAttribute(ExifInterface exif, String tag) {
        final String res = exif.getAttribute(tag);
        if (res == null || res.length() == 0) {
            Log.d(TAG, "Expected valid EXIF tag for tag " + tag);
        }
    }

    private boolean supportsHardware() {
        final PackageManager pm = mContext.getPackageManager();
        return !pm.hasSystemFeature("android.hardware.type.television")
                && !pm.hasSystemFeature("android.hardware.type.watch");
    }

    private void prepareFile() throws Exception {
        final File dir = new File(MediaStore.getVolumePath(mVolumeName),
                Environment.DIRECTORY_DOCUMENTS);
        final File file = new File(dir, "cts" + System.nanoTime() + ".txt");

        mFile = stageFile(R.raw.text, file);
        mMediaStoreUri = MediaStore.scanFile(mContext, mFile);

        Log.v(TAG, "Staged " + mFile + " as " + mMediaStoreUri);
    }

    private Uri acquireAccess(File file, String directoryName) {
        StorageManager storageManager =
                (StorageManager) mActivity.getSystemService(Context.STORAGE_SERVICE);

        // Request access from DocumentsUI
        final StorageVolume volume = storageManager.getStorageVolume(file);
        final Intent intent = volume.createOpenDocumentTreeIntent();
        mActivity.startActivityForResult(intent, REQUEST_CODE);

        if (mTargetPackageName == null) {
            mTargetPackageName = getTargetPackageName(mActivity);
        }

        // Granting the access
        BySelector buttonPanelSelector = By.pkg(mTargetPackageName)
                .res(mTargetPackageName + ":id/container_save");
        mDevice.wait(Until.hasObject(buttonPanelSelector), 30 * DateUtils.SECOND_IN_MILLIS);
        final UiObject2 buttonPanel = mDevice.findObject(buttonPanelSelector);
        final UiObject2 allowButton = buttonPanel.findObject(By.res("android:id/button1"));
        allowButton.click();
        mDevice.waitForIdle();

        // Granting the access by click "allow" in confirm dialog
        final BySelector dialogButtonPanelSelector = By.pkg(mTargetPackageName)
                .res(mTargetPackageName + ":id/buttonPanel");
        mDevice.wait(Until.hasObject(dialogButtonPanelSelector), 30 * DateUtils.SECOND_IN_MILLIS);
        final UiObject2 positiveButton = mDevice.findObject(dialogButtonPanelSelector)
                .findObject(By.res("android:id/button1"));
        positiveButton.click();
        mDevice.waitForIdle();

        // Check granting result and take persistent permission
        final Result result = mActivity.getResult();
        assertEquals(Activity.RESULT_OK, result.resultCode);

        final Intent resultIntent = result.data;
        final Uri resultUri = resultIntent.getData();
        final int flags = resultIntent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        mActivity.getContentResolver().takePersistableUriPermission(resultUri, flags);
        return resultUri;
    }

    private static String getTargetPackageName(Context context) {
        final PackageManager pm = context.getPackageManager();

        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        final ResolveInfo ri = pm.resolveActivity(intent, 0);
        return ri.activityInfo.packageName;
    }

    // TODO: replace with ProviderTestUtils
    static String executeShellCommand(String command) throws IOException {
        return executeShellCommand(command,
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
    }

    // TODO: replace with ProviderTestUtils
    static String executeShellCommand(String command, UiAutomation uiAutomation)
            throws IOException {
        Log.v(TAG, "$ " + command);
        ParcelFileDescriptor pfd = uiAutomation.executeShellCommand(command.toString());
        BufferedReader br = null;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor());) {
            br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String str = null;
            StringBuilder out = new StringBuilder();
            while ((str = br.readLine()) != null) {
                Log.v(TAG, "> " + str);
                out.append(str);
            }
            return out.toString();
        } finally {
            if (br != null) {
                br.close();
            }
        }
    }

    // TODO: replace with ProviderTestUtils
    static File stageFile(int resId, File file) throws IOException {
        // The caller may be trying to stage into a location only available to
        // the shell user, so we need to perform the entire copy as the shell
        if (FileUtils.contains(Environment.getStorageDirectory(), file)) {
            executeShellCommand("mkdir -p " + file.getParent());

            final Context context = InstrumentationRegistry.getTargetContext();
            try (AssetFileDescriptor afd = context.getResources().openRawResourceFd(resId)) {
                final File source = ParcelFileDescriptor.getFile(afd.getFileDescriptor());
                final long skip = afd.getStartOffset();
                final long count = afd.getLength();

                executeShellCommand(String.format("dd bs=1 if=%s skip=%d count=%d of=%s",
                        source.getAbsolutePath(), skip, count, file.getAbsolutePath()));

                // Force sync to try updating other views
                executeShellCommand("sync");
            }
        } else {
            final File dir = file.getParentFile();
            dir.mkdirs();
            if (!dir.exists()) {
                throw new FileNotFoundException("Failed to create parent for " + file);
            }
            final Context context = InstrumentationRegistry.getTargetContext();
            try (InputStream source = context.getResources().openRawResource(resId);
                    OutputStream target = new FileOutputStream(file)) {
                FileUtils.copy(source, target);
            }
        }
        return file;
    }
}
