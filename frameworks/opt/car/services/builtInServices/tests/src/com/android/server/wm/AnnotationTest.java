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

package com.android.server.wm;

import static android.car.test.util.AnnotationHelper.checkForAnnotation;

import androidx.test.core.app.ApplicationProvider;

import com.android.annotation.AddedIn;
import com.android.internal.car.R;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;

public class AnnotationTest {
    @Test
    public void testCarHelperServiceAPIAddedInAnnotation() throws Exception {
        checkForAnnotation(readFile(R.raw.CSHS_classes), AddedIn.class);
    }


    private String[] readFile(int resourceId) throws IOException {
        try (InputStream configurationStream = ApplicationProvider.getApplicationContext()
                .getResources().openRawResource(resourceId)) {
            return new String(configurationStream.readAllBytes()).split("\n");
        }
    }
}

