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

package com.android.cts.verifier.clipboard;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.android.cts.verifier.PassFailButtons;
import com.android.cts.verifier.R;

import java.util.concurrent.ThreadLocalRandom;


/**
 * A CTS Verifier test case for validating the user-visible clipboard preview.
 *
 * This test assumes bluetooth is turned on and the device is already paired with a second device.
 * Note: the second device need not be an Android device; it could be a laptop or desktop.
 */
public class ClipboardPreviewTestActivity extends PassFailButtons.Activity {

    /**
     * The content of the test file being transferred.
     */
    private static final String TEST_STRING = "Sample Test String";
    /**
     * The name of the test file being transferred.
     */
    private final int[] mSecretCode = new int[4];
    private final int[] mSecretGuess = new int[4];
    private final int[] mButtons = {
            R.id.clipboard_preview_test_b0,
            R.id.clipboard_preview_test_b1,
            R.id.clipboard_preview_test_b2,
            R.id.clipboard_preview_test_b3,
            R.id.clipboard_preview_test_b4,
            R.id.clipboard_preview_test_b5,
            R.id.clipboard_preview_test_b6,
            R.id.clipboard_preview_test_b7,
            R.id.clipboard_preview_test_b8,
            R.id.clipboard_preview_test_b9
    };
    private int mGuessIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup the UI.
        setContentView(R.layout.clipboard_preview);
        setPassFailButtonClickListeners();
        setInfoResources(R.string.clipboard_preview_test, R.string.clipboard_preview_test_info, -1);
        // Get the share button and attach the listener.
        Button copyButton = findViewById(R.id.clipboard_preview_test_copy);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                generateAndCopySecret();
            }
        });
        disableKeypad();
    }

    private void generateAndCopySecret() {
        String s = "";
        resetState();
        for (int i = 0; i < mSecretCode.length; ++i) {
            mSecretCode[i] = ThreadLocalRandom.current().nextInt(0, 10);
            s += mSecretCode[i];
        }
        ClipboardManager cm = this.getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Secret", s));
        enableKeypad();
    }

    private void enableKeypad() {
        for (int i = 0; i < mButtons.length; ++i) {
            Button numButton = findViewById(mButtons[i]);
            numButton.setBackgroundColor(Color.GREEN);
            int finalI = i;
            numButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    buttonClicked(finalI);
                }
            });
        }
    }

    private void disableKeypad() {
        for (int i = 0; i < mButtons.length; ++i) {
            Button numButton = findViewById(mButtons[i]);
            numButton.setOnClickListener(null);
            numButton.setBackgroundColor(Color.LTGRAY);
        }
    }

    private void resetState() {
        for (int i = 0; i < mSecretGuess.length; ++i) {
            mSecretGuess[i] = -1;
        }
        mGuessIndex = 0;
        View v = findViewById(R.id.clipboard_preview_test_pass_fail);
        findViewById(R.id.clipboard_preview_test_pass_fail).setVisibility(View.INVISIBLE);
        findViewById(R.id.fail_button).setVisibility(View.VISIBLE);
        findViewById(R.id.pass_button).setVisibility(View.VISIBLE);
    }

    private void buttonClicked(int i) {
        if (mGuessIndex < mSecretGuess.length) {
            mSecretGuess[mGuessIndex] = i;
            ++mGuessIndex;
        }
        checkSolution();
    }

    private void checkSolution() {
        boolean testPassed = true;
        if (mGuessIndex == mSecretGuess.length) {
            for (int i = 0; i < mSecretGuess.length && i < mSecretCode.length; ++i) {
                if (mSecretGuess[i] != mSecretCode[i]) {
                    testPassed = false;
                }
            }
            markPassed(testPassed);
            disableKeypad();
        }
    }

    private void markPassed(boolean passed) {
        findViewById(R.id.clipboard_preview_test_pass_fail).setVisibility(View.VISIBLE);
        if (passed) {
            findViewById(R.id.fail_button).setVisibility(View.INVISIBLE);
        } else {
            findViewById(R.id.pass_button).setVisibility(View.INVISIBLE);
        }

    }


}
