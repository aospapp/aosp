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
package android.uirendering.cts.testclasses;

import com.android.cts.uirendering.R;

import android.test.suitebuilder.annotation.SmallTest;
import android.uirendering.cts.bitmapcomparers.BitmapComparer;
import android.uirendering.cts.bitmapcomparers.ExactComparer;
import android.uirendering.cts.testinfrastructure.ActivityTestBase;


/**
 * Created to see how custom views made with XML and programatic code will work.
 */
public class LayoutTests extends ActivityTestBase {
    private BitmapComparer mBitmapComparer;

    public LayoutTests() {
        mBitmapComparer = new ExactComparer();
    }

    @SmallTest
    public void testSimpleRedLayout() {
        createTest().addLayout(R.layout.simple_red_layout, null).runWithComparer(mBitmapComparer);
    }

    @SmallTest
    public void testSimpleRectLayout() {
        createTest().addLayout(R.layout.simple_rect_layout, null).runWithComparer(mBitmapComparer);
    }
}

