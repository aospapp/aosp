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

package android.car.cts.builtin.view;

import static com.google.common.truth.Truth.assertThat;

import android.car.builtin.view.SurfaceControlHelper;
import android.graphics.PixelFormat;
import android.view.SurfaceControl;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SurfaceControlHelperTest {

    @Test
    public void testMirrorSurface() {
        int width = 16;
        int hegiht = 8;
        SurfaceControl source = new SurfaceControl.Builder().setBufferSize(width, hegiht)
                .setFormat(PixelFormat.RGBA_8888)
                .setName("Test Surface")
                .setHidden(false)
                .build();
        SurfaceControl mirror = SurfaceControlHelper.mirrorSurface(source);
        // Just checks SurfaceControlHelper.mirrorSurface() returns the valid SurfaceControl.
        assertThat(mirror).isNotNull();
        assertThat(mirror.isValid()).isTrue();
    }
}
