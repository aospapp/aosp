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

package android.view.inputmethod.cts;


import android.content.ClipDescription;
import android.net.Uri;
import android.os.Parcel;
import android.test.AndroidTestCase;
import android.view.inputmethod.InputContentInfo;

import java.lang.NullPointerException;
import java.security.InvalidParameterException;

public class InputContentInfoTest extends AndroidTestCase {

    public void testInputContentInfo() {
        InputContentInfo info = new InputContentInfo(
                 Uri.parse("content://com.example/path"),
                 new ClipDescription("sample content", new String[]{"image/png"}),
                 Uri.parse("https://example.com"));

        assertEquals(Uri.parse("content://com.example/path"), info.getContentUri());
        assertEquals(1, info.getDescription().getMimeTypeCount());
        assertEquals("image/png", info.getDescription().getMimeType(0));
        assertEquals("sample content", info.getDescription().getLabel());
        assertEquals(Uri.parse("https://example.com"), info.getLinkUri());
        assertEquals(0, info.describeContents());

        Parcel p = Parcel.obtain();
        info.writeToParcel(p, 0);
        p.setDataPosition(0);
        InputContentInfo targetInfo = InputContentInfo.CREATOR.createFromParcel(p);
        p.recycle();

        assertEquals(info.getContentUri(), targetInfo.getContentUri());
        assertEquals(info.getDescription().getMimeTypeCount(),
                targetInfo.getDescription().getMimeTypeCount());
        assertEquals(info.getDescription().getMimeType(0),
                targetInfo.getDescription().getMimeType(0));
        assertEquals(info.getDescription().getLabel(), targetInfo.getDescription().getLabel());
        assertEquals(info.getLinkUri(), targetInfo.getLinkUri());
        assertEquals(info.describeContents(), targetInfo.describeContents());
    }

    public void testOptionalConstructorParam() {
        InputContentInfo info = new InputContentInfo(
                Uri.parse("content://com.example/path"),
                new ClipDescription("sample content", new String[]{"image/png"}));

        assertEquals(Uri.parse("content://com.example/path"), info.getContentUri());
        assertEquals(1, info.getDescription().getMimeTypeCount());
        assertEquals("image/png", info.getDescription().getMimeType(0));
        assertEquals("sample content", info.getDescription().getLabel());
        assertNull(info.getLinkUri());
        assertEquals(0, info.describeContents());
    }

    public void testContentUri() {
        try {
            InputContentInfo info = new InputContentInfo(
                    null, new ClipDescription("sample content", new String[]{"image/png"}),
                    Uri.parse("https://example.com"));
            fail("InputContentInfo must not accept a null content URI.");
        } catch (NullPointerException e) {
            // OK.
        } catch (Exception e) {
            fail("Unexpected exception=" + e);
        }

        try {
            InputContentInfo info = new InputContentInfo(
                    Uri.parse("https://example.com"),
                    new ClipDescription("sample content", new String[]{"image/png"}),
                    Uri.parse("https://example.com"));
            fail("InputContentInfo must accept content URI only.");
        } catch (InvalidParameterException e) {
            // OK.
        } catch (Exception e) {
            fail("Unexpected exception=" + e);
        }
    }

    public void testMimeType() {
        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"), null,
                     Uri.parse("https://example.com"));
            fail("InputContentInfo must not accept a null description.");
        } catch (NullPointerException e) {
            // OK.
        } catch (Exception e) {
            fail("Unexpected exception=" + e);
        }
    }

    public void testLinkUri() {
        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"),
                     new ClipDescription("sample content", new String[]{"image/png"}),
                     null);
        } catch (Exception e) {
            fail("InputContentInfo must accept a null link Uri.");
        }

        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"),
                     new ClipDescription("sample content", new String[]{"image/png"}),
                     Uri.parse("http://example.com/path"));
        } catch (Exception e) {
            fail("InputContentInfo must accept http link Uri.");
        }

        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"),
                     new ClipDescription("sample content", new String[]{"image/png"}),
                     Uri.parse("https://example.com/path"));
        } catch (Exception e) {
            fail("InputContentInfo must accept https link Uri.");
        }

        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"),
                     new ClipDescription("sample content", new String[]{"image/png"}),
                     Uri.parse("ftp://example.com/path"));
            fail("InputContentInfo must accept http and https link Uri only.");
        } catch (InvalidParameterException e) {
            // OK.
        } catch (Exception e) {
            fail("Unexpected exception=" + e);
        }

        try {
            InputContentInfo info = new InputContentInfo(
                     Uri.parse("content://com.example/path"),
                     new ClipDescription("sample content", new String[]{"image/png"}),
                     Uri.parse("content://com.example/path"));
            fail("InputContentInfo must accept http and https link Uri only.");
        } catch (InvalidParameterException e) {
            // OK.
        } catch (Exception e) {
            fail("Unexpected exception=" + e);
        }
    }

    public void testRequestAndReleasePermission() {
        InputContentInfo info = new InputContentInfo(
                Uri.parse("content://com.example/path"),
                new ClipDescription("sample content", new String[]{"image/png"}),
                Uri.parse("https://example.com"));

        // Here we only assert that {request, release}Permission() do not crash, because ensuring
        // the entire functionality of these methods requires end-to-end IME test environment, which
        // we do not have yet in CTS.
        // Note it is actually intentional that calling these methods here has no effect.  Those
        // methods would have effect only after the object is passed from the IME process to the
        // application process.
        // TODO: Create an end-to-end CTS test for this functionality.
        info.requestPermission();
        info.releasePermission();
        info.requestPermission();
        info.releasePermission();
    }

}
