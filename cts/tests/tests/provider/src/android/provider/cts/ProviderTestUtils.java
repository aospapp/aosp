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
 * limitations under the License
 */

package android.provider.cts;

import static android.provider.cts.MediaStoreTest.TAG;

import static org.junit.Assert.fail;

import android.app.UiAutomation;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.provider.cts.MediaStoreUtils.PendingParams;
import android.provider.cts.MediaStoreUtils.PendingSession;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods for provider cts tests.
 */
public class ProviderTestUtils {

    private static final int BACKUP_TIMEOUT_MILLIS = 4000;
    private static final Pattern BMGR_ENABLED_PATTERN = Pattern.compile(
            "^Backup Manager currently (enabled|disabled)$");

    private static final Pattern PATTERN_STORAGE_PATH = Pattern.compile(
            "(?i)^/storage/[^/]+/(?:[0-9]+/)?");

    static Iterable<String> getSharedVolumeNames() {
        // We test both new and legacy volume names
        final HashSet<String> testVolumes = new HashSet<>();
        testVolumes.addAll(
                MediaStore.getExternalVolumeNames(InstrumentationRegistry.getTargetContext()));
        testVolumes.add(MediaStore.VOLUME_EXTERNAL);
        return testVolumes;
    }

    static String resolveVolumeName(String volumeName) {
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            return MediaStore.VOLUME_EXTERNAL_PRIMARY;
        } else {
            return volumeName;
        }
    }

    static void setDefaultSmsApp(boolean setToSmsApp, String packageName, UiAutomation uiAutomation)
            throws Exception {
        String mode = setToSmsApp ? "allow" : "default";
        String cmd = "appops set %s %s %s";
        executeShellCommand(String.format(cmd, packageName, "WRITE_SMS", mode), uiAutomation);
        executeShellCommand(String.format(cmd, packageName, "READ_SMS", mode), uiAutomation);
    }

    static String executeShellCommand(String command) throws IOException {
        return executeShellCommand(command,
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
    }

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

    static String setBackupTransport(String transport, UiAutomation uiAutomation) throws Exception {
        String output = executeShellCommand("bmgr transport " + transport, uiAutomation);
        Pattern pattern = Pattern.compile("\\(formerly (.*)\\)$");
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            throw new Exception("non-parsable output setting bmgr transport: " + output);
        }
    }

    static boolean setBackupEnabled(boolean enable, UiAutomation uiAutomation) throws Exception {
        // Check to see the previous state of the backup service
        boolean previouslyEnabled = false;
        String output = executeShellCommand("bmgr enabled", uiAutomation);
        Matcher matcher = BMGR_ENABLED_PATTERN.matcher(output.trim());
        if (matcher.find()) {
            previouslyEnabled = "enabled".equals(matcher.group(1));
        } else {
            throw new RuntimeException("Backup output format changed.  No longer matches"
                    + " expected regex: " + BMGR_ENABLED_PATTERN + "\nactual: '" + output + "'");
        }

        executeShellCommand("bmgr enable " + enable, uiAutomation);
        return previouslyEnabled;
    }

    static boolean hasBackupTransport(String transport, UiAutomation uiAutomation)
            throws Exception {
        String output = executeShellCommand("bmgr list transports", uiAutomation);
        for (String t : output.split(" ")) {
            if ("*".equals(t)) {
                // skip the current selection marker.
                continue;
            } else if (Objects.equals(transport, t)) {
                return true;
            }
        }
        return false;
    }

    static void runBackup(String packageName, UiAutomation uiAutomation) throws Exception {
        executeShellCommand("bmgr backupnow " + packageName, uiAutomation);
        Thread.sleep(BACKUP_TIMEOUT_MILLIS);
    }

    static void runRestore(String packageName, UiAutomation uiAutomation) throws Exception {
        executeShellCommand("bmgr restore 1 " + packageName, uiAutomation);
        Thread.sleep(BACKUP_TIMEOUT_MILLIS);
    }

    static void wipeBackup(String backupTransport, String packageName, UiAutomation uiAutomation)
            throws Exception {
        executeShellCommand("bmgr wipe " + backupTransport + " " + packageName, uiAutomation);
    }

    static File stageDir(String volumeName) throws IOException {
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }
        return Environment.buildPath(MediaStore.getVolumePath(volumeName), "Android", "media",
                "android.provider.cts");
    }

    static File stageDownloadDir(String volumeName) throws IOException {
        if (MediaStore.VOLUME_EXTERNAL.equals(volumeName)) {
            volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY;
        }
        return Environment.buildPath(MediaStore.getVolumePath(volumeName),
                Environment.DIRECTORY_DOWNLOADS, "android.provider.cts");
    }

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

    static Uri stageMedia(int resId, Uri collectionUri) throws IOException {
        return stageMedia(resId, collectionUri, "image/png");
    }

    static Uri stageMedia(int resId, Uri collectionUri, String mimeType) throws IOException {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String displayName = "cts" + System.nanoTime();
        final PendingParams params = new PendingParams(collectionUri, displayName, mimeType);
        final Uri pendingUri = MediaStoreUtils.createPending(context, params);
        try (PendingSession session = MediaStoreUtils.openPending(context, pendingUri)) {
            try (InputStream source = context.getResources().openRawResource(resId);
                    OutputStream target = session.openOutputStream()) {
                FileUtils.copy(source, target);
            }
            return session.publish();
        }
    }

    static Uri scanFile(File file) throws Exception {
        return MediaStore.scanFile(InstrumentationRegistry.getTargetContext(), file);
    }

    static Uri scanFileFromShell(File file) throws Exception {
        return MediaStore.scanFileFromShell(InstrumentationRegistry.getTargetContext(), file);
    }

    static void scanVolume(File file) throws Exception {
        MediaStore.scanVolume(InstrumentationRegistry.getTargetContext(), file);
    }

    public static byte[] hash(InputStream in) throws Exception {
        try (DigestInputStream digestIn = new DigestInputStream(in,
                MessageDigest.getInstance("SHA-1"));
                OutputStream out = new FileOutputStream(new File("/dev/null"))) {
            FileUtils.copy(digestIn, out);
            return digestIn.getMessageDigest().digest();
        }
    }

    public static void assertExists(String path) throws IOException {
        assertExists(null, path);
    }

    public static void assertExists(File file) throws IOException {
        assertExists(null, file.getAbsolutePath());
    }

    public static void assertExists(String msg, String path) throws IOException {
        if (!access(path)) {
            fail(msg);
        }
    }

    public static void assertNotExists(String path) throws IOException {
        assertNotExists(null, path);
    }

    public static void assertNotExists(File file) throws IOException {
        assertNotExists(null, file.getAbsolutePath());
    }

    public static void assertNotExists(String msg, String path) throws IOException {
        if (access(path)) {
            fail(msg);
        }
    }

    private static boolean access(String path) throws IOException {
        // The caller may be trying to stage into a location only available to
        // the shell user, so we need to perform the entire copy as the shell
        if (FileUtils.contains(Environment.getStorageDirectory(), new File(path))) {
            return executeShellCommand("ls -la " + path).contains(path);
        } else {
            try {
                Os.access(path, OsConstants.F_OK);
                return true;
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) {
                    return false;
                } else {
                    throw new IOException(e.getMessage());
                }
            }
        }
    }

    public static boolean containsId(Uri uri, long id) {
        try (Cursor c = InstrumentationRegistry.getTargetContext().getContentResolver().query(uri,
                new String[] { MediaColumns._ID }, null, null)) {
            while (c.moveToNext()) {
                if (c.getLong(0) == id) return true;
            }
        }
        return false;
    }

    public static File getRawFile(Uri uri) throws Exception {
        final String res = ProviderTestUtils.executeShellCommand(
                "content query --uri " + uri + " --projection _data",
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
        final int i = res.indexOf("_data=");
        if (i >= 0) {
            return new File(res.substring(i + 6));
        } else {
            throw new FileNotFoundException("Failed to find _data for " + uri + "; found " + res);
        }
    }

    public static String getRawFileHash(File file) throws Exception {
        final String res = ProviderTestUtils.executeShellCommand(
                "sha1sum " + file.getAbsolutePath(),
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
        if (Pattern.matches("[0-9a-fA-F]{40}.+", res)) {
            return res.substring(0, 40);
        } else {
            throw new FileNotFoundException("Failed to find hash for " + file + "; found " + res);
        }
    }

    public static File getRelativeFile(Uri uri) throws Exception {
        final String path = getRawFile(uri).getAbsolutePath();
        final Matcher matcher = PATTERN_STORAGE_PATH.matcher(path);
        if (matcher.find()) {
            return new File(path.substring(matcher.end()));
        } else {
            throw new IllegalArgumentException();
        }
    }
}
