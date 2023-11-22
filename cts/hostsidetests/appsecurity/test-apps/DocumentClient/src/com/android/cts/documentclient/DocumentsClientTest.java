/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.cts.documentclient;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject;
import android.support.test.uiautomator.UiSelector;
import android.test.InstrumentationTestCase;
import android.test.MoreAsserts;
import android.text.format.DateUtils;

import com.android.cts.documentclient.MyActivity.Result;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Tests for {@link DocumentsProvider} and interaction with platform intents
 * like {@link Intent#ACTION_OPEN_DOCUMENT}.
 */
public class DocumentsClientTest extends InstrumentationTestCase {
    private UiDevice mDevice;
    private MyActivity mActivity;

    private static final long TIMEOUT = 10 * DateUtils.SECOND_IN_MILLIS;

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mDevice = UiDevice.getInstance(getInstrumentation());
        mActivity = launchActivity(getInstrumentation().getTargetContext().getPackageName(),
                MyActivity.class, null);
        mDevice.waitForIdle();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        mActivity.finish();
    }

    public void testOpenSimple() throws Exception {
        if (!supportedHardware()) return;

        try {
            // Opening without permission should fail
            readFully(Uri.parse("content://com.android.cts.documentprovider/document/doc:file1"));
            fail("Able to read data before opened!");
        } catch (SecurityException expected) {
        }

        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        mActivity.startActivityForResult(intent, 42);

        // Ensure that we see both of our roots
        mDevice.waitForIdle();
        assertTrue("CtsLocal root", new UiObject(new UiSelector().text("CtsLocal")).waitForExists(TIMEOUT));
        assertTrue("CtsCreate root", new UiObject(new UiSelector().text("CtsCreate")).exists());
        assertFalse("CtsGetContent", new UiObject(new UiSelector().text("CtsGetContent")).exists());

        // Pick a specific file from our test provider
        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("CtsLocal")).click();

        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("FILE1")).click();

        final Result result = mActivity.getResult();
        final Uri uri = result.data.getData();

        // We should now have permission to read/write
        MoreAsserts.assertEquals("fileone".getBytes(), readFully(uri));

        writeFully(uri, "replaced!".getBytes());
        SystemClock.sleep(500);
        MoreAsserts.assertEquals("replaced!".getBytes(), readFully(uri));
    }

    public void testCreateNew() throws Exception {
        if (!supportedHardware()) return;

        final String DISPLAY_NAME = "My New Awesome Document Title";
        final String MIME_TYPE = "image/png";

        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, DISPLAY_NAME);
        intent.setType(MIME_TYPE);
        mActivity.startActivityForResult(intent, 42);

        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("CtsCreate")).click();
        mDevice.waitForIdle();
        new UiObject(new UiSelector().resourceId("com.android.documentsui:id/container_save")
                .childSelector(new UiSelector().resourceId("android:id/button1"))).click();

        final Result result = mActivity.getResult();
        final Uri uri = result.data.getData();

        writeFully(uri, "meow!".getBytes());

        assertEquals(DISPLAY_NAME, getColumn(uri, Document.COLUMN_DISPLAY_NAME));
        assertEquals(MIME_TYPE, getColumn(uri, Document.COLUMN_MIME_TYPE));
    }

    public void testCreateExisting() throws Exception {
        if (!supportedHardware()) return;

        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "NEVERUSED");
        intent.setType("mime2/file2");
        mActivity.startActivityForResult(intent, 42);

        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("CtsCreate")).click();

        // Pick file2, which should be selected since MIME matches, then try
        // picking a non-matching MIME, which should leave file2 selected.
        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("FILE2")).click();
        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("FILE1")).click();

        mDevice.waitForIdle();
        new UiObject(new UiSelector().resourceId("com.android.documentsui:id/container_save")
                .childSelector(new UiSelector().resourceId("android:id/button1"))).click();

        final Result result = mActivity.getResult();
        final Uri uri = result.data.getData();

        MoreAsserts.assertEquals("filetwo".getBytes(), readFully(uri));
    }

    public void testTree() throws Exception {
        if (!supportedHardware()) return;

        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        mActivity.startActivityForResult(intent, 42);

        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("CtsCreate")).click();
        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("DIR2")).click();
        mDevice.waitForIdle();
        new UiObject(new UiSelector().resourceId("com.android.documentsui:id/container_save")
                .childSelector(new UiSelector().resourceId("android:id/button1"))).click();

        final Result result = mActivity.getResult();
        final Uri uri = result.data.getData();

        // We should have selected DIR2
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri));
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri));

        assertEquals("DIR2", getColumn(doc, Document.COLUMN_DISPLAY_NAME));

        // Look around and make sure we can see children
        final ContentResolver resolver = getInstrumentation().getContext().getContentResolver();
        Cursor cursor = resolver.query(children, new String[] {
                Document.COLUMN_DISPLAY_NAME }, null, null, null);
        try {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("FILE4", cursor.getString(0));
        } finally {
            cursor.close();
        }

        // Create some documents
        Uri pic = DocumentsContract.createDocument(resolver, doc, "image/png", "pic.png");
        Uri dir = DocumentsContract.createDocument(resolver, doc, Document.MIME_TYPE_DIR, "my dir");
        Uri dirPic = DocumentsContract.createDocument(resolver, dir, "image/png", "pic2.png");

        writeFully(pic, "pic".getBytes());
        writeFully(dirPic, "dirPic".getBytes());

        // Read then delete existing doc
        final Uri file4 = DocumentsContract.buildDocumentUriUsingTree(uri, "doc:file4");
        MoreAsserts.assertEquals("filefour".getBytes(), readFully(file4));
        assertTrue("delete", DocumentsContract.deleteDocument(resolver, file4));
        try {
            MoreAsserts.assertEquals("filefour".getBytes(), readFully(file4));
            fail("Expected file to be gone");
        } catch (FileNotFoundException expected) {
        }

        // And rename something
        dirPic = DocumentsContract.renameDocument(resolver, dirPic, "wow");
        assertNotNull("rename", dirPic);

        // We should only see single child
        assertEquals("wow", getColumn(dirPic, Document.COLUMN_DISPLAY_NAME));
        MoreAsserts.assertEquals("dirPic".getBytes(), readFully(dirPic));

        try {
            // Make sure we can't see files outside selected dir
            getColumn(DocumentsContract.buildDocumentUriUsingTree(uri, "doc:file1"),
                    Document.COLUMN_DISPLAY_NAME);
            fail("Somehow read document outside tree!");
        } catch (SecurityException expected) {
        }
    }

    public void testGetContent() throws Exception {
        if (!supportedHardware()) return;

        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        mActivity.startActivityForResult(intent, 42);

        // Look around, we should be able to see both DocumentsProviders and
        // other GET_CONTENT sources.
        mDevice.waitForIdle();
        assertTrue("CtsLocal root", new UiObject(new UiSelector().text("CtsLocal")).waitForExists(TIMEOUT));
        assertTrue("CtsCreate root", new UiObject(new UiSelector().text("CtsCreate")).exists());
        assertTrue("CtsGetContent", new UiObject(new UiSelector().text("CtsGetContent")).exists());

        mDevice.waitForIdle();
        new UiObject(new UiSelector().text("CtsGetContent")).click();

        final Result result = mActivity.getResult();
        assertEquals("ReSuLt", result.data.getAction());
    }

    private String getColumn(Uri uri, String column) {
        final ContentResolver resolver = getInstrumentation().getContext().getContentResolver();
        final Cursor cursor = resolver.query(uri, new String[] { column }, null, null, null);
        try {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        } finally {
            cursor.close();
        }
    }

    private byte[] readFully(Uri uri) throws IOException {
        InputStream in = getInstrumentation().getContext().getContentResolver()
                .openInputStream(uri);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } finally {
            in.close();
        }
    }

    private void writeFully(Uri uri, byte[] data) throws IOException {
        OutputStream out = getInstrumentation().getContext().getContentResolver()
                .openOutputStream(uri);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private boolean supportedHardware() {
        final PackageManager pm = getInstrumentation().getContext().getPackageManager();
        if (pm.hasSystemFeature("android.hardware.type.television")
                || pm.hasSystemFeature("android.hardware.type.watch")) {
            return false;
        }
        return true;
    }
}
