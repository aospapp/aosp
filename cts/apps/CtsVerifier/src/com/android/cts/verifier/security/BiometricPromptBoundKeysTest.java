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
 * limitations under the License
 */

package com.android.cts.verifier.security;

import android.content.DialogInterface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;

import com.android.cts.verifier.R;

import java.util.concurrent.Executor;

/**
 * Test for {@link BiometricPrompt}. This test runs twice, once with confirmation required,
 * once without. Both tests use crypto objects.
 */
public class BiometricPromptBoundKeysTest extends FingerprintBoundKeysTest {

    private static final int STATE_TEST_REQUIRE_CONFIRMATION = 1; // confirmation required
    private static final int STATE_TEST_NO_CONFIRMATION = 2; // no confirmation required

    private DialogCallback mDialogCallback;
    private BiometricPrompt mBiometricPrompt;
    private CancellationSignal mCancellationSignal;
    private BiometricManager mBiometricManager;
    private int mState = STATE_TEST_REQUIRE_CONFIRMATION;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Executor mExecutor = (runnable) -> {
        mHandler.post(runnable);
    };

    private final Runnable mNegativeButtonRunnable = () -> {
        showToast("Authentication canceled by user");
    };

    private class DialogCallback extends
            BiometricPrompt.AuthenticationCallback {
        @Override
        public void onAuthenticationError(int errMsgId, CharSequence errString) {
            showToast(errString.toString());
        }

        @Override
        public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
            if (tryEncrypt()) {
                if (mState == STATE_TEST_REQUIRE_CONFIRMATION) {
                    mState = STATE_TEST_NO_CONFIRMATION;
                    showToast("First test passed, run again to start the second test");
                    Button startButton = findViewById(R.id.sec_start_test_button);
                    startButton.setText("Run second test");
                } else if (mState == STATE_TEST_NO_CONFIRMATION) {
                    showToast("Test passed.");
                    getPassButton().setEnabled(true);
                }
            } else {
                showToast("Test failed. Key not accessible after auth");
            }
        }
    }

    @Override
    protected void onPermissionsGranted() {
        mBiometricManager = getSystemService(BiometricManager.class);
        final int result = mBiometricManager.canAuthenticate();

        if (result == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            showToast("No biometric features, test passed.");
            getPassButton().setEnabled(true);
        } else if (result == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
            showToast("Biometric unavailable, something is wrong with your device");
        } else if (result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            showToast("Error: " + result + " Please ensure you have a biometric enrolled");
            Button startTestButton = findViewById(R.id.sec_start_test_button);
            startTestButton.setEnabled(false);
        }
    }

    @Override
    protected void showAuthenticationScreen() {
        mCancellationSignal = new CancellationSignal();
        mDialogCallback = new DialogCallback();
        final boolean requireConfirmation = mState == STATE_TEST_REQUIRE_CONFIRMATION
                ? true : false;
        mBiometricPrompt = new BiometricPrompt.Builder(getApplicationContext())
                .setTitle("Authenticate with biometric")
                .setNegativeButton("Cancel", mExecutor,
                        (DialogInterface dialogInterface, int which) -> {
                            if (which == DialogInterface.BUTTON_NEGATIVE) {
                                mHandler.post(mNegativeButtonRunnable);
                            }
                        })
                .setConfirmationRequired(requireConfirmation)
                .build();
        mBiometricPrompt.authenticate(
                new BiometricPrompt
                .CryptoObject(getCipher()),
                mCancellationSignal, mExecutor, mDialogCallback);
    }

    @Override
    protected int getTitleRes() {
        return R.string.sec_biometric_prompt_bound_key_test;
    }

    @Override
    protected int getDescriptionRes() {
        return R.string.sec_biometric_prompt_bound_key_test_info;
    }
}
