/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.ColorInt;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.MediumTest;
import android.uirendering.cts.bitmapverifiers.ColorVerifier;
import android.uirendering.cts.testinfrastructure.ActivityTestBase;
import android.uirendering.cts.testinfrastructure.ViewInitializer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.uirendering.cts.R;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class LayerTests extends ActivityTestBase {
    @Test
    public void testLayerPaintAlpha() {
        // red channel full strength, other channels 75% strength
        // (since 25% alpha red subtracts from them)
        @ColorInt
        final int expectedColor = Color.rgb(255, 191, 191);
        createTest()
                .addLayout(R.layout.simple_red_layout, (ViewInitializer) view -> {
                    // reduce alpha by 50%
                    Paint paint = new Paint();
                    paint.setAlpha(128);
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, paint);

                    // reduce alpha by another 50% (ensuring two alphas combine correctly)
                    view.setAlpha(0.5f);
                })
                .runWithVerifier(new ColorVerifier(expectedColor));
    }

    @Test
    public void testLayerPaintColorFilter() {
        // Red, fully desaturated. Note that it's not 255/3 in each channel.
        // See ColorMatrix#setSaturation()
        @ColorInt
        final int expectedColor = Color.rgb(54, 54, 54);
        createTest()
                .addLayout(R.layout.simple_red_layout, (ViewInitializer) view -> {
                    Paint paint = new Paint();
                    ColorMatrix desatMatrix = new ColorMatrix();
                    desatMatrix.setSaturation(0.0f);
                    paint.setColorFilter(new ColorMatrixColorFilter(desatMatrix));
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
                })
                .runWithVerifier(new ColorVerifier(expectedColor));
    }

    @Test
    public void testLayerPaintBlend() {
        // Red, drawn underneath opaque white, so output should be white.
        // TODO: consider doing more interesting blending test here
        @ColorInt
        final int expectedColor = Color.WHITE;
        createTest()
                .addLayout(R.layout.simple_red_layout, (ViewInitializer) view -> {
                    Paint paint = new Paint();
                    /* Note that when drawing in SW, we're blending within an otherwise empty
                     * SW layer, as opposed to in the frame buffer (which has a white
                     * background).
                     *
                     * For this reason we use just use DST, which just throws out the SRC
                     * content, regardless of the DST alpha channel.
                     */
                    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST));
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
                })
                .runWithVerifier(new ColorVerifier(expectedColor));
    }

    @Test
    public void testLayerInitialSizeZero() {
        createTest()
                .addLayout(R.layout.frame_layout, view -> {
                    FrameLayout root = (FrameLayout) view.findViewById(R.id.frame_layout);
                    // disable clipChildren, to ensure children aren't rejected by bounds
                    root.setClipChildren(false);
                    for (int i = 0; i < 2; i++) {
                        View child = new View(view.getContext());
                        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        // add rendering content, so View isn't skipped at render time
                        child.setBackgroundColor(Color.RED);

                        // add one with width=0, one with height=0
                        root.addView(child, new FrameLayout.LayoutParams(
                                i == 0 ? 0 : 90,
                                i == 0 ? 90 : 0,
                                Gravity.TOP | Gravity.LEFT));
                    }
                }, true)
                .runWithVerifier(new ColorVerifier(Color.WHITE, 0 /* zero tolerance */));
    }

    @Test
    public void testLayerResizeZero() {
        createTest()
                .addLayout(R.layout.frame_layout, view -> {
                    FrameLayout root = (FrameLayout) view.findViewById(R.id.frame_layout);
                    // disable clipChildren, to ensure child isn't rejected by bounds
                    root.setClipChildren(false);
                    for (int i = 0; i < 2; i++) {
                        View child = new View(view.getContext());
                        child.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                        // add rendering content, so View isn't skipped at render time
                        child.setBackgroundColor(Color.BLUE);
                        root.addView(child, new FrameLayout.LayoutParams(90, 90,
                                Gravity.TOP | Gravity.LEFT));
                    }

                    // post invalid dimensions a few frames in, so initial layer allocation succeeds
                    // NOTE: this must execute before capture, or verification will fail
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                        int mDrawCount = 0;
                        @Override
                        public boolean onPreDraw() {
                            if (mDrawCount++ == 5) {
                                root.getChildAt(0).getLayoutParams().width = 0;
                                root.getChildAt(0).requestLayout();
                                root.getChildAt(1).getLayoutParams().height = 0;
                                root.getChildAt(1).requestLayout();
                            }
                            return true;
                        }
                    });
                }, true)
                .runWithVerifier(new ColorVerifier(Color.WHITE, 0 /* zero tolerance */));
    }
}
