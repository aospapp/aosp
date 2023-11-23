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

package com.android.car.settings.security;

import static com.android.internal.widget.PasswordValidationError.CONTAINS_INVALID_CHARACTERS;
import static com.android.internal.widget.PasswordValidationError.CONTAINS_SEQUENCE;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_DIGITS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_LETTERS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_LOWER_CASE;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_NON_DIGITS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_NON_LETTER;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_SYMBOLS;
import static com.android.internal.widget.PasswordValidationError.NOT_ENOUGH_UPPER_CASE;
import static com.android.internal.widget.PasswordValidationError.RECENTLY_USED;
import static com.android.internal.widget.PasswordValidationError.TOO_LONG;
import static com.android.internal.widget.PasswordValidationError.TOO_SHORT;

import android.annotation.UserIdInt;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PasswordMetrics;
import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.android.car.settings.R;
import com.android.car.settings.common.Logger;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.PasswordValidationError;
import com.android.settingslib.utils.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper used by ChooseLockPinPasswordFragment
 * Much of the logic is taken from {@link com.android.settings.password.ChooseLockPassword}
 */
public class PasswordHelper {
    public static final String EXTRA_CURRENT_SCREEN_LOCK = "extra_current_screen_lock";
    public static final String EXTRA_CURRENT_PASSWORD_QUALITY = "extra_current_password_quality";

    // Error code returned from validateSetupWizard(byte[] password).
    static final int NO_ERROR = 0;
    static final int ERROR_CODE = 1;
    private static final Logger LOG = new Logger(PasswordHelper.class);

    private final Context mContext;
    private final boolean mIsPin;
    private final LockPatternUtils mLockPatternUtils;
    private final PasswordMetrics mMinMetrics;
    private List<PasswordValidationError> mValidationErrors;
    private byte[] mPasswordHistoryHashFactor;

    @UserIdInt
    private final int mUserId;

    @DevicePolicyManager.PasswordComplexity
    private final int mMinComplexity;

    public PasswordHelper(Context context, boolean isPin, @UserIdInt int userId) {
        mContext = context;
        mIsPin = isPin;
        mUserId = userId;
        mLockPatternUtils = new LockPatternUtils(context);
        mMinMetrics = mLockPatternUtils.getRequestedPasswordMetrics(
                mUserId, /* deviceWideOnly= */ false);
        mMinComplexity = mLockPatternUtils.getRequestedPasswordComplexity(
                mUserId, /* deviceWideOnly= */ false);
    }

    @VisibleForTesting
    PasswordHelper(Context context, boolean isPin, @UserIdInt int userId,
            LockPatternUtils lockPatternUtils, PasswordMetrics minMetrics,
            @DevicePolicyManager.PasswordComplexity int minComplexity) {
        mContext = context;
        mIsPin = isPin;
        mUserId = userId;
        mLockPatternUtils = lockPatternUtils;
        mMinMetrics = minMetrics;
        mMinComplexity = minComplexity;
    }

    /**
     * Validates PIN/Password and returns the validation result and updates mValidationErrors.
     *
     * @param password password the user typed in.
     * @return The error code where 0 is no error.
     */
    public int validateSetupWizard(byte[] password) {
        mValidationErrors =
                PasswordMetrics.validatePassword(mMinMetrics, mMinComplexity, mIsPin, password);

        return mValidationErrors.isEmpty() ? NO_ERROR : ERROR_CODE;
    }

    /**
     * Validates PIN/Password and returns the validation result and updates mValidationErrors
     * and checks whether the password has been reused.
     *
     * @param enteredCredential credential the user typed in.
     * @param existingCredential existing credential the user previously set.
     * @return whether password satisfies all the requirements.
     */
    public boolean validate(LockscreenCredential enteredCredential,
            LockscreenCredential existingCredential) {
        byte[] password = enteredCredential.getCredential();
        mValidationErrors =
                PasswordMetrics.validatePassword(mMinMetrics, mMinComplexity, mIsPin, password);
        if (mValidationErrors.isEmpty() && mLockPatternUtils.checkPasswordHistory(
                password, getPasswordHistoryHashFactor(existingCredential), mUserId)) {
            mValidationErrors =
                    Collections.singletonList(new PasswordValidationError(RECENTLY_USED));
        }

        return mValidationErrors.isEmpty();
    }

    /**
     * Lazily computes and returns the history hash factor of the user id of the current process
     * {@code mUserId}, used for password history check.
     */
    private byte[] getPasswordHistoryHashFactor(LockscreenCredential credential) {
        if (mPasswordHistoryHashFactor == null) {
            mPasswordHistoryHashFactor = mLockPatternUtils.getPasswordHistoryHashFactor(
                    credential, mUserId);
        }
        return mPasswordHistoryHashFactor;
    }

    /**
     * Returns an array of messages describing any errors of the last
     * {@link #validate(LockscreenCredential)} call, important messages come first.
     */
    public List<String> convertErrorCodeToMessages() {
        List<String> messages = new ArrayList<>();
        for (PasswordValidationError error : mValidationErrors) {
            switch (error.errorCode) {
                case CONTAINS_INVALID_CHARACTERS:
                    messages.add(mContext.getString(R.string.lockpassword_illegal_character));
                    break;
                case NOT_ENOUGH_UPPER_CASE:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_uppercase));
                    break;
                case NOT_ENOUGH_LOWER_CASE:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_lowercase));
                    break;
                case NOT_ENOUGH_LETTERS:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_letters));
                    break;
                case NOT_ENOUGH_DIGITS:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_numeric));
                    break;
                case NOT_ENOUGH_SYMBOLS:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_symbols));
                    break;
                case NOT_ENOUGH_NON_LETTER:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_nonletter));
                    break;
                case NOT_ENOUGH_NON_DIGITS:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            R.string.lockpassword_password_requires_nonnumerical));
                    break;
                case TOO_SHORT:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement,
                            mIsPin
                                    ? R.string.lockpassword_pin_too_short
                                    : R.string.lockpassword_password_too_short));
                    break;
                case TOO_LONG:
                    messages.add(StringUtil.getIcuPluralsString(mContext, error.requirement + 1,
                            mIsPin
                                    ? R.string.lockpassword_pin_too_long
                                    : R.string.lockpassword_password_too_long));
                    break;
                case CONTAINS_SEQUENCE:
                    messages.add(mContext.getString(
                            R.string.lockpassword_pin_no_sequential_digits));
                    break;
                case RECENTLY_USED:
                    messages.add(mContext.getString(mIsPin
                            ? R.string.lockpassword_pin_recently_used
                            : R.string.lockpassword_password_recently_used));
                    break;
                default:
                    LOG.wtf("unknown error validating password: " + error);
            }
        }
        return messages;
    }

    /**
     * Zero out credentials and force garbage collection to remove any remnants of user password
     * shards from memory. Should be used in onDestroy for any LockscreenCredential fields.
     *
     * @param credentials the credentials to zero out, can be null
     **/
    public static void zeroizeCredentials(LockscreenCredential... credentials) {
        for (LockscreenCredential credential : credentials) {
            if (credential != null) {
                credential.zeroize();
            }
        }

        System.gc();
        System.runFinalization();
        System.gc();
    }
}
