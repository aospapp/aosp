/*
 * Copyright 2022 The Android Open Source Project
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

package android.ondevicepersonalization;

import static org.junit.Assert.assertEquals;

import android.os.Parcel;
import android.os.PersistableBundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit Tests of Framework API Classes.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class OnDevicePersonalizationFrameworkClassesTest {
    /**
     * Tests that the ExecuteOutput object serializes correctly.
     */
    @Test
    public void testExecuteOutput() {
        ExecuteOutput result =
                new ExecuteOutput.Builder()
                    .addSlotResults(
                        new SlotResult.Builder().setSlotKey("abc")
                            .addRenderedBidKeys("bid1")
                            .addLoggedBids(
                                new Bid.Builder()
                                    .setKey("bid1")
                                    .setMetrics(new Metrics.Builder()
                                        .setLongValues(11).build())
                                    .build())
                            .addLoggedBids(
                                new Bid.Builder()
                                    .setKey("bid2")
                                    .build())
                            .build())
                    .build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ExecuteOutput result2 = ExecuteOutput.CREATOR.createFromParcel(parcel);

        SlotResult slotResult = result2.getSlotResults().get(0);
        assertEquals("abc", slotResult.getSlotKey());
        assertEquals("bid1", slotResult.getLoggedBids().get(0).getKey());
        assertEquals("bid1", slotResult.getRenderedBidKeys().get(0));
        assertEquals(11, slotResult.getLoggedBids().get(0).getMetrics().getLongValues()[0]);
        assertEquals("bid2", slotResult.getLoggedBids().get(1).getKey());
    }

    /**
     * Tests that the SlotInfo object serializes correctly.
     */
    @Test
    public void testSlotInfo() {
        SlotInfo slotInfo = new SlotInfo.Builder().setWidth(100).setHeight(50).build();

        Parcel parcel = Parcel.obtain();
        slotInfo.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SlotInfo slotInfo2 = SlotInfo.CREATOR.createFromParcel(parcel);

        assertEquals(slotInfo, slotInfo2);
        assertEquals(100, slotInfo2.getWidth());
        assertEquals(50, slotInfo2.getHeight());
    }

    /**
     * Tests that the RenderOutput object serializes correctly.
     */
    @Test
    public void testRenderOutput() {
        RenderOutput result = new RenderOutput.Builder().setContent("abc").build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        RenderOutput result2 = RenderOutput.CREATOR.createFromParcel(parcel);

        assertEquals(result, result2);
        assertEquals("abc", result2.getContent());
    }

    /**
     * Tests that the DownloadOutput object serializes correctly.
     */
    @Test
    public void teetDownloadOutput() {
        DownloadOutput result = new DownloadOutput.Builder()
                .addKeysToRetain("abc").addKeysToRetain("def").build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        DownloadOutput result2 = DownloadOutput.CREATOR.createFromParcel(parcel);

        assertEquals(result, result2);
        assertEquals("abc", result2.getKeysToRetain().get(0));
        assertEquals("def", result2.getKeysToRetain().get(1));
    }

    /**
     * Tests that the Metrics object serializes correctly.
     */
    @Test
    public void testMetrics() {
        long[] intMetrics = {10, 20};
        double[] floatMetrics = {5.0};
        Metrics result = new Metrics.Builder()
                .setLongValues(intMetrics)
                .setDoubleValues(floatMetrics)
                .setBooleanValues(true).build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        Metrics result2 = Metrics.CREATOR.createFromParcel(parcel);

        assertEquals(result, result2);
        assertEquals(10, result2.getLongValues()[0]);
        assertEquals(20, result2.getLongValues()[1]);
        assertEquals(5.0, result2.getDoubleValues()[0], 0.0);
        assertEquals(true, result2.getBooleanValues()[0]);
    }

    /**
     * Tests that the EventInput object serializes correctly.
     */
    @Test
    public void testEventInput() {
        PersistableBundle params = new PersistableBundle();
        params.putInt("x", 3);
        EventInput result = new EventInput.Builder()
                .setEventType(6)
                .setBid(new Bid.Builder().setKey("a").build())
                .build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        EventInput result2 = EventInput.CREATOR.createFromParcel(parcel);

        assertEquals(6, result2.getEventType());
        assertEquals("a", result2.getBid().getKey());
    }

    /**
     * Tests that the Metrics object serializes correctly.
     */
    @Test
    public void testEventOutput() {
        long[] intMetrics = {10, 20};
        double[] floatMetrics = {5.0};
        EventOutput result = new EventOutput.Builder()
                .setMetrics(new Metrics.Builder().setLongValues(11).build()).build();

        Parcel parcel = Parcel.obtain();
        result.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        EventOutput result2 = EventOutput.CREATOR.createFromParcel(parcel);

        assertEquals(result, result2);
        assertEquals(11, result2.getMetrics().getLongValues()[0]);
    }
}
