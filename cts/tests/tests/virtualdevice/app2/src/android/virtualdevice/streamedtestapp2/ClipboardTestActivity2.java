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

package android.virtualdevice.streamedtestapp2;

import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_GET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_SET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_GET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_HAS_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_NOTIFY_WHEN_ATTACHED_TO_WINDOW;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_RESULT_RECEIVER;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_SET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_WAIT_FOR_FOCUS;
import static android.virtualdevice.cts.common.ClipboardTestConstants.RESULT_CODE_ATTACHED_TO_WINDOW;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.ResultReceiver;

import androidx.annotation.Nullable;

public class ClipboardTestActivity2 extends Activity {
    private static final String TAG = "ClipboardTestActivity2";

    ClipboardManager mClipboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mClipboard = getSystemService(ClipboardManager.class);
    }

    @Override
    public void onAttachedToWindow() {
        if (getIntent().getBooleanExtra(EXTRA_NOTIFY_WHEN_ATTACHED_TO_WINDOW, false)) {
            ResultReceiver resultReceiver =
                    getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
            if (resultReceiver != null) {
                resultReceiver.send(RESULT_CODE_ATTACHED_TO_WINDOW, null);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean waitForFocus = getIntent().getBooleanExtra(EXTRA_WAIT_FOR_FOCUS, true);
        if (!waitForFocus || hasWindowFocus()) {
            processAction();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus && !isFinishing()) {
            processAction();
        }
    }

    void processAction() {
        String action = getIntent().getAction();
        if (ACTION_GET_CLIP.equals(action)) {
            Bundle result = new Bundle();
            result.putBoolean(EXTRA_HAS_CLIP, mClipboard.hasPrimaryClip());
            result.putParcelable(EXTRA_GET_CLIP_DATA, mClipboard.getPrimaryClip());
            sendResultAndFinish(result);
        } else if (ACTION_SET_CLIP.equals(action)) {
            ClipData clip = getIntent().getParcelableExtra(EXTRA_SET_CLIP_DATA, ClipData.class);
            mClipboard.setPrimaryClip(clip);
            sendResultAndFinish(null);
        }
    }

    void sendResultAndFinish(Bundle result) {
        ResultReceiver resultReceiver =
                getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        resultReceiver.send(Activity.RESULT_OK, result);
        finish();
    }
}
