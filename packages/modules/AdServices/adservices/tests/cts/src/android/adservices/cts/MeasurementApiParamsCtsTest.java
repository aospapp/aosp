/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.adservices.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.WebSourceParams;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.net.Uri;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.KeyEvent;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MeasurementApiParamsCtsTest {

    @Test
    public void testDeletionRequest() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);

        DeletionRequest deletionRequest =
                new DeletionRequest.Builder()
                        .setDeletionMode(DeletionRequest.DELETION_MODE_ALL)
                        .setMatchBehavior(DeletionRequest.MATCH_BEHAVIOR_DELETE)
                        .setStart(start)
                        .setEnd(end)
                        .setDomainUris(
                                List.of(
                                        Uri.parse("https://d-foo.com"),
                                        Uri.parse("https://d-bar.com")))
                        .setOriginUris(
                                List.of(
                                        Uri.parse("https://o-foo.com"),
                                        Uri.parse("https://o-bar.com")))
                        .build();

        assertEquals(DeletionRequest.DELETION_MODE_ALL, deletionRequest.getDeletionMode());
        assertEquals(DeletionRequest.MATCH_BEHAVIOR_DELETE, deletionRequest.getMatchBehavior());
        assertEquals(start, deletionRequest.getStart());
        assertEquals(end, deletionRequest.getEnd());
        assertEquals("https://d-foo.com", deletionRequest.getDomainUris().get(0).toString());
        assertEquals("https://d-bar.com", deletionRequest.getDomainUris().get(1).toString());
        assertEquals("https://o-foo.com", deletionRequest.getOriginUris().get(0).toString());
        assertEquals("https://o-bar.com", deletionRequest.getOriginUris().get(1).toString());
    }

    private WebSourceParams createWebSourceParamsExample() {
        return new WebSourceParams.Builder(Uri.parse("https://registration-uri"))
                .setDebugKeyAllowed(true)
                .build();
    }

    @Test
    public void testWebSourceParams() {
        WebSourceParams webSourceParams = createWebSourceParamsExample();
        assertEquals("https://registration-uri", webSourceParams.getRegistrationUri().toString());
        assertTrue(webSourceParams.isDebugKeyAllowed());
        assertEquals(0, webSourceParams.describeContents());
    }

    @Test
    public void testWebSourceParamsParceling() {
        Parcel p = Parcel.obtain();
        WebSourceParams exampleParams = createWebSourceParamsExample();
        exampleParams.writeToParcel(p, 0);
        p.setDataPosition(0);

        WebSourceParams webSourceParams = WebSourceParams.CREATOR.createFromParcel(p);
        assertEquals(
                exampleParams.getRegistrationUri().toString(),
                webSourceParams.getRegistrationUri().toString());
        assertEquals(exampleParams.isDebugKeyAllowed(), webSourceParams.isDebugKeyAllowed());
        p.recycle();
    }

    private WebSourceRegistrationRequest createWebSourceRegistrationRequestExample() {
        return new WebSourceRegistrationRequest.Builder(
                        List.of(
                                new WebSourceParams.Builder(Uri.parse("https://registration-uri"))
                                        .setDebugKeyAllowed(true)
                                        .build()),
                        Uri.parse("https://top-origin"))
                .setInputEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1))
                .setVerifiedDestination(Uri.parse("https://verified-destination"))
                .setAppDestination(Uri.parse("android-app://app-destination"))
                .setWebDestination(Uri.parse("https://web-destination"))
                .build();
    }

    @Test
    public void testWebSourceRegistrationRequest() {
        WebSourceRegistrationRequest request = createWebSourceRegistrationRequestExample();

        assertEquals(0, request.describeContents());
        assertEquals(
                "https://registration-uri",
                request.getSourceParams().get(0).getRegistrationUri().toString());
        assertTrue(request.getSourceParams().get(0).isDebugKeyAllowed());
        assertEquals("https://top-origin", request.getTopOriginUri().toString());
        assertEquals(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_1).getAction(),
                ((KeyEvent) request.getInputEvent()).getAction());
        assertEquals("https://verified-destination", request.getVerifiedDestination().toString());
        assertEquals("android-app://app-destination", request.getAppDestination().toString());
        assertEquals("https://web-destination", request.getWebDestination().toString());
    }

    @Test
    public void testWebSourceRegistrationRequestParceling() {
        Parcel p = Parcel.obtain();
        WebSourceRegistrationRequest exampleRequest = createWebSourceRegistrationRequestExample();
        exampleRequest.writeToParcel(p, 0);
        p.setDataPosition(0);
        WebSourceRegistrationRequest request =
                WebSourceRegistrationRequest.CREATOR.createFromParcel(p);

        assertEquals(exampleRequest.describeContents(), request.describeContents());
        assertEquals(
                exampleRequest.getSourceParams().get(0).getRegistrationUri().toString(),
                request.getSourceParams().get(0).getRegistrationUri().toString());
        assertEquals(
                exampleRequest.getSourceParams().get(0).isDebugKeyAllowed(),
                request.getSourceParams().get(0).isDebugKeyAllowed());
        assertEquals(
                exampleRequest.getTopOriginUri().toString(), request.getTopOriginUri().toString());
        assertEquals(
                ((KeyEvent) exampleRequest.getInputEvent()).getAction(),
                ((KeyEvent) request.getInputEvent()).getAction());
        assertEquals(
                exampleRequest.getVerifiedDestination().toString(),
                request.getVerifiedDestination().toString());
        assertEquals(
                exampleRequest.getAppDestination().toString(),
                request.getAppDestination().toString());
        assertEquals(
                exampleRequest.getWebDestination().toString(),
                request.getWebDestination().toString());
        p.recycle();
    }

    private WebTriggerParams createWebTriggerParamsExample() {
        return new WebTriggerParams.Builder(Uri.parse("https://registration-uri"))
                .setDebugKeyAllowed(true)
                .build();
    }

    @Test
    public void testWebTriggerParams() {
        WebTriggerParams webTriggerParams = createWebTriggerParamsExample();

        assertEquals(0, webTriggerParams.describeContents());
        assertEquals("https://registration-uri", webTriggerParams.getRegistrationUri().toString());
        assertTrue(webTriggerParams.isDebugKeyAllowed());
    }

    @Test
    public void testWebTriggerParamsParceling() {
        Parcel p = Parcel.obtain();
        WebTriggerParams exampleParams = createWebTriggerParamsExample();
        exampleParams.writeToParcel(p, 0);
        p.setDataPosition(0);
        WebTriggerParams params = WebTriggerParams.CREATOR.createFromParcel(p);

        assertEquals(exampleParams.describeContents(), params.describeContents());
        assertEquals(
                exampleParams.getRegistrationUri().toString(),
                params.getRegistrationUri().toString());
        assertEquals(exampleParams.isDebugKeyAllowed(), params.isDebugKeyAllowed());
        p.recycle();
    }

    private WebTriggerRegistrationRequest createWebTriggerRegistrationRequestExample() {
        return new WebTriggerRegistrationRequest.Builder(
                        List.of(
                                new WebTriggerParams.Builder(Uri.parse("https://registration-uri"))
                                        .setDebugKeyAllowed(true)
                                        .build()),
                        Uri.parse("https://destination"))
                .build();
    }

    @Test
    public void testWebTriggerRegistrationRequest() {
        WebTriggerRegistrationRequest request = createWebTriggerRegistrationRequestExample();

        assertEquals(0, request.describeContents());
        assertEquals(
                "https://registration-uri",
                request.getTriggerParams().get(0).getRegistrationUri().toString());
        assertTrue(request.getTriggerParams().get(0).isDebugKeyAllowed());
        assertEquals("https://destination", request.getDestination().toString());
    }

    @Test
    public void testWebTriggerRegistrationRequestParceling() {
        Parcel p = Parcel.obtain();
        WebTriggerRegistrationRequest exampleRequest = createWebTriggerRegistrationRequestExample();
        exampleRequest.writeToParcel(p, 0);
        p.setDataPosition(0);
        WebTriggerRegistrationRequest request =
                WebTriggerRegistrationRequest.CREATOR.createFromParcel(p);

        assertEquals(exampleRequest.describeContents(), request.describeContents());
        assertEquals(
                exampleRequest.getTriggerParams().get(0).getRegistrationUri().toString(),
                request.getTriggerParams().get(0).getRegistrationUri().toString());
        assertEquals(
                exampleRequest.getTriggerParams().get(0).isDebugKeyAllowed(),
                request.getTriggerParams().get(0).isDebugKeyAllowed());
        assertEquals(
                exampleRequest.getDestination().toString(), request.getDestination().toString());
        p.recycle();
    }
}
