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
 * limitations under the License
 */

package android.app.stubs;

import android.app.Activity;
import android.content.res.Configuration;

public class PipActivity extends Activity {

    private int mMultiWindowChangedCount;
    private int mPictureInPictureModeChangedCount;
    private boolean mLastReporterMultiWindowMode;
    private boolean mLastReporterPictureInPictureMode;

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        mLastReporterMultiWindowMode = isInMultiWindowMode;
        mMultiWindowChangedCount++;
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        mLastReporterPictureInPictureMode = isInPictureInPictureMode;
        mPictureInPictureModeChangedCount++;
    }

    public boolean getLastReportedMultiWindowMode() {
        return mLastReporterMultiWindowMode;
    }

    public boolean getLastReporterPictureInPictureMode() {
        return mLastReporterPictureInPictureMode;
    }

    public int getMultiWindowChangedCount() {
        return mMultiWindowChangedCount;
    }

    public int getPictureInPictureModeChangedCount() {
        return mPictureInPictureModeChangedCount;
    }
}
