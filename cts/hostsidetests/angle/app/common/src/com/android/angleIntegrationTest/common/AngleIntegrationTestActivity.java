/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.angleIntegrationTest.common;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.lang.Override;

public class AngleIntegrationTestActivity extends Activity {

    private final String TAG = this.getClass().getSimpleName();

    private GlesView mGlesView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mGlesView = new GlesView(this);
        setContentView(mGlesView);

        Log.i(TAG, "ANGLE Manifest activity complete");
    }

    public GlesView getGlesView() {
        return mGlesView;
    }
}
