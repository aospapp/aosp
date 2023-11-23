/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.uirendering.cts.testclasses;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;

import androidx.annotation.ColorLong;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class GainmapTests {

    private static final ColorSpace BT2020_HLG = ColorSpace.get(ColorSpace.Named.BT2020_HLG);
    private static final ColorSpace BT2020_PQ = ColorSpace.get(ColorSpace.Named.BT2020_PQ);

    // A 10x6 base image with a 5x3 (so 1/2 res) gainmap that boosts the center 3 pixels
    // by 0x40, 0x80, and 0xff respectively
    private static final Bitmap sTestImage;
    static {
        Bitmap base = Bitmap.createBitmap(10, 6, Bitmap.Config.ARGB_8888);
        base.eraseColor(Color.WHITE);

        Bitmap gainmapImage = Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888);
        gainmapImage.eraseColor(0);
        gainmapImage.setPixel(1, 1, 0xFF404040);
        gainmapImage.setPixel(2, 1, 0xFF808080);
        gainmapImage.setPixel(3, 1, 0xFFFFFFFF);

        Gainmap gainmap = new Gainmap(gainmapImage);
        base.setGainmap(gainmap);
        sTestImage = base;
    }

    private static final Picture sTestPicture;
    static {
        sTestPicture = new Picture();
        Canvas canvas = sTestPicture.beginRecording(sTestImage.getWidth(), sTestImage.getHeight());
        canvas.drawBitmap(sTestImage, 0, 0, null);
        sTestPicture.endRecording();
    }

    private static void assertChannels(Color result, @ColorLong long expected, float delta) {
        ColorSpace.Connector connector = ColorSpace.connect(Color.colorSpace(expected),
                result.getColorSpace());
        float[] mapped = connector.transform(Color.red(expected), Color.green(expected),
                Color.blue(expected));
        Assert.assertEquals(result.red(), mapped[0], delta, "red channel mismatch");
        Assert.assertEquals(result.green(), mapped[1], delta, "green channel mismatch");
        Assert.assertEquals(result.blue(), mapped[2], delta, "blue channel mismatch");
    }

    private static @ColorLong long mapWhiteWithGain(Gainmap gainmap, double gain) {
        double logRatioMin = Math.log(gainmap.getRatioMin()[0]);
        double logRatioMax = Math.log(gainmap.getRatioMax()[0]);
        double epsilonSdr = gainmap.getEpsilonSdr()[0];
        double epsilonHdr = gainmap.getEpsilonHdr()[0];
        double L = (logRatioMin * (1 - gain)) + (logRatioMax * gain);
        float D = (float) ((1.0 + epsilonSdr) * Math.exp(L) - epsilonHdr);
        return Color.pack(D, D, D, 1.f, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB));
    }

    private static @ColorLong long mapWhiteWithGain(double gain) {
        return mapWhiteWithGain(sTestImage.getGainmap(), gain);
    }

    private static void assertTestImageResult(Bitmap result) {
        // 8888 precision ain't so great
        final float delta = result.getConfig() == Bitmap.Config.ARGB_8888 ? 0.02f : 0.002f;
        assertChannels(result.getColor(0, 0), Color.pack(Color.WHITE), delta);
        assertChannels(result.getColor(2, 2), mapWhiteWithGain(0x40 / 255.f), delta);
        assertChannels(result.getColor(4, 2), mapWhiteWithGain(0x80 / 255.f), delta);
        assertChannels(result.getColor(6, 2), mapWhiteWithGain(0xFF / 255.f), delta);
    }

    private static Bitmap renderTestImageWithHardware(ColorSpace dest) {
        return renderTestImageWithHardware(dest, false);
    }

    private static Bitmap renderTestImageWithHardware(ColorSpace dest, boolean usePicture) {
        HardwareBuffer buffer = HardwareBuffer.create(sTestImage.getWidth(), sTestImage.getHeight(),
                HardwareBuffer.RGBA_8888,
                1, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareBufferRenderer renderer = new HardwareBufferRenderer(buffer);
        RenderNode content = new RenderNode("gainmap");
        content.setPosition(0, 0, sTestImage.getWidth(), sTestImage.getHeight());
        RecordingCanvas canvas = content.beginRecording();
        if (usePicture) {
            canvas.drawPicture(sTestPicture);
        } else {
            canvas.drawBitmap(sTestImage, 0, 0, null);
        }
        content.endRecording();
        renderer.setContentRoot(content);
        CountDownLatch latch = new CountDownLatch(1);
        renderer.obtainRenderRequest().setColorSpace(dest).draw(Runnable::run, result -> {
            result.getFence().awaitForever();
            latch.countDown();
        });
        try {
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Assert.fail(ex.getMessage());
        }
        return Bitmap.wrapHardwareBuffer(buffer, dest).copy(Bitmap.Config.ARGB_8888, false);
    }

    @Test
    public void gainmapToHlgSoftware() {
        Bitmap result = Bitmap.createBitmap(10, 6, Bitmap.Config.RGBA_F16, false, BT2020_HLG);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(sTestImage, 0f, 0f, null);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToPqSoftware() {
        Bitmap result = Bitmap.createBitmap(10, 6, Bitmap.Config.RGBA_F16, false, BT2020_PQ);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(sTestImage, 0f, 0f, null);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToHlgPictureSoftware() {
        Bitmap result = Bitmap.createBitmap(10, 6, Bitmap.Config.RGBA_F16, false, BT2020_HLG);
        Canvas canvas = new Canvas(result);
        canvas.drawPicture(sTestPicture);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToPqPictureSoftware() {
        Bitmap result = Bitmap.createBitmap(10, 6, Bitmap.Config.RGBA_F16, false, BT2020_HLG);
        Canvas canvas = new Canvas(result);
        canvas.drawPicture(sTestPicture);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToHlgHardware() throws Exception {
        Bitmap result = renderTestImageWithHardware(BT2020_HLG);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToPqHardware() {
        Bitmap result = renderTestImageWithHardware(BT2020_PQ);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToHlgPictureHardware() throws Exception {
        Bitmap result = renderTestImageWithHardware(BT2020_HLG, true);
        assertTestImageResult(result);
    }

    @Test
    public void gainmapToPqPictureHardware() {
        Bitmap result = renderTestImageWithHardware(BT2020_PQ, true);
        assertTestImageResult(result);
    }

    @Test
    public void createScaledBitmap() {
        Bitmap result = Bitmap.createScaledBitmap(sTestImage, 20, 12, false);
        assertEquals(result.getWidth(), 20);
        assertEquals(result.getHeight(), 12);
        assertTrue(result.hasGainmap());
        Bitmap gainmapContents = result.getGainmap().getGainmapContents();
        assertEquals(gainmapContents.getWidth(), 10);
        assertEquals(gainmapContents.getHeight(), 6);

        assertChannels(gainmapContents.getColor(0, 0), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(1, 1), Color.pack(Color.BLACK), 0f);

        assertChannels(gainmapContents.getColor(2, 2), Color.pack(0xFF404040), 0f);
        assertChannels(gainmapContents.getColor(3, 3), Color.pack(0xFF404040), 0f);

        assertChannels(gainmapContents.getColor(4, 2), Color.pack(0xFF808080), 0f);
        assertChannels(gainmapContents.getColor(5, 3), Color.pack(0xFF808080), 0f);

        assertChannels(gainmapContents.getColor(6, 2), Color.pack(0xFFFFFFFF), 0f);
        assertChannels(gainmapContents.getColor(7, 3), Color.pack(0xFFFFFFFF), 0f);

        assertChannels(gainmapContents.getColor(8, 4), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(9, 5), Color.pack(Color.BLACK), 0f);
    }

    @Test
    public void applyRotation180Matrix() {
        Matrix m = new Matrix();
        m.setRotate(180.0f, 5.f, 3.f);
        Bitmap result = Bitmap.createBitmap(sTestImage, 0, 0, 10, 6, m, false);
        assertEquals(result.getWidth(), 10);
        assertEquals(result.getHeight(), 6);
        assertTrue(result.hasGainmap());
        Bitmap gainmapContents = result.getGainmap().getGainmapContents();
        assertEquals(gainmapContents.getWidth(), 5);
        assertEquals(gainmapContents.getHeight(), 3);
        assertChannels(gainmapContents.getColor(0, 0), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(0, 1), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(1, 1), Color.pack(0xFFFFFFFF), 0f);
        assertChannels(gainmapContents.getColor(2, 1), Color.pack(0xFF808080), 0f);
        assertChannels(gainmapContents.getColor(3, 1), Color.pack(0xFF404040), 0f);
        assertChannels(gainmapContents.getColor(4, 1), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(4, 2), Color.pack(Color.BLACK), 0f);
    }

    @Test
    public void applyRotation90Matrix() {
        Matrix m = new Matrix();
        m.setRotate(90.0f, 5.f, 3.f);
        Bitmap result = Bitmap.createBitmap(sTestImage, 0, 0, 10, 6, m, false);
        assertEquals(result.getWidth(), 6);
        assertEquals(result.getHeight(), 10);
        assertTrue(result.hasGainmap());
        Bitmap gainmapContents = result.getGainmap().getGainmapContents();
        assertEquals(gainmapContents.getWidth(), 3);
        assertEquals(gainmapContents.getHeight(), 5);
        assertChannels(gainmapContents.getColor(0, 0), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(1, 0), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(1, 1), Color.pack(0xFF404040), 0f);
        assertChannels(gainmapContents.getColor(1, 2), Color.pack(0xFF808080), 0f);
        assertChannels(gainmapContents.getColor(1, 3), Color.pack(0xFFFFFFFF), 0f);
        assertChannels(gainmapContents.getColor(1, 4), Color.pack(Color.BLACK), 0f);
        assertChannels(gainmapContents.getColor(2, 4), Color.pack(Color.BLACK), 0f);
    }
}
