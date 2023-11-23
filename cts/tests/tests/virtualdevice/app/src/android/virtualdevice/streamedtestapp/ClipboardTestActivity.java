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

package android.virtualdevice.streamedtestapp;

import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_GET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_SET_AND_GET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_SET_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.ACTION_WAIT_FOR_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_FINISH_AFTER_SENDING_RESULT;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_GET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_HAS_CLIP;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_NOT_FOCUSABLE;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_RESULT_RECEIVER;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_SET_CLIP_DATA;
import static android.virtualdevice.cts.common.ClipboardTestConstants.EXTRA_WAIT_FOR_FOCUS;
import static android.virtualdevice.cts.common.ClipboardTestConstants.RESULT_CODE_CLIP_LISTENER_READY;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.view.WindowManager;

import androidx.annotation.Nullable;

public class ClipboardTestActivity extends Activity {

    private static final String TAG = "ClipboardTestActivity";

    ClipboardManager mClipboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mClipboard = getSystemService(ClipboardManager.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = getIntent();
        if (intent.getBooleanExtra(EXTRA_NOT_FOCUSABLE, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        boolean waitForFocus = intent.getBooleanExtra(EXTRA_WAIT_FOR_FOCUS, true);
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
            doGet();
        } else if (ACTION_SET_CLIP.equals(action)) {
            doSet();
        } else if (ACTION_SET_AND_GET_CLIP.equals(action)) {
            doSetAndGet();
        } else if (ACTION_WAIT_FOR_CLIP.equals(action)) {
            doWaitForClipboardWrite();
        }
    }

    void doGet() {
        Bundle result = new Bundle();
        result.putBoolean(EXTRA_HAS_CLIP, mClipboard.hasPrimaryClip());
        result.putParcelable(EXTRA_GET_CLIP_DATA, mClipboard.getPrimaryClip());
        sendResultAndMaybeFinish(result);
    }

    void doSet() {
        Intent intent = getIntent();
        ClipData clip = intent.getParcelableExtra(EXTRA_SET_CLIP_DATA, ClipData.class);
        mClipboard.setPrimaryClip(clip);
        sendResultAndMaybeFinish(null);
    }

    void doSetAndGet() {
        Intent intent = getIntent();
        ClipData clip = intent.getParcelableExtra(EXTRA_SET_CLIP_DATA, ClipData.class);
        mClipboard.setPrimaryClip(clip);

        Bundle result = new Bundle();
        result.putBoolean(EXTRA_HAS_CLIP, mClipboard.hasPrimaryClip());
        result.putParcelable(EXTRA_GET_CLIP_DATA, mClipboard.getPrimaryClip());
        sendResultAndMaybeFinish(result);
    }

    void doWaitForClipboardWrite() {
        mClipboard.addPrimaryClipChangedListener(() -> {
            if (isFinishing()) {
                // Avoid calling sendResultAndMaybeFinish again if this listener is called a
                // second time before we've completely torn down the activity.
                return;
            }
            Bundle result = new Bundle();
            result.putBoolean(EXTRA_HAS_CLIP, mClipboard.hasPrimaryClip());
            result.putParcelable(EXTRA_GET_CLIP_DATA, mClipboard.getPrimaryClip());
            sendResultAndMaybeFinish(result);
        });
        ResultReceiver resultReceiver =
                getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        if (resultReceiver != null) {
            resultReceiver.send(RESULT_CODE_CLIP_LISTENER_READY, null);
        }
    }
    void sendResultAndMaybeFinish(Bundle result) {
        ResultReceiver resultReceiver =
                getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        if (resultReceiver != null) {
            resultReceiver.send(Activity.RESULT_OK, result);
        }
        if (getIntent().getBooleanExtra(EXTRA_FINISH_AFTER_SENDING_RESULT, true)) {
            finish();
        }
    }
}
