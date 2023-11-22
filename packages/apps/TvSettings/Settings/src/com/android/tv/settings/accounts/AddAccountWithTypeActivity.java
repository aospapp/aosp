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

package com.android.tv.settings.accounts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import java.io.IOException;
import android.util.Log;

public class AddAccountWithTypeActivity extends Activity {

    private static final String TAG = "AddAccountWithTypeActivity";

    private static final int REQUEST_CHOOSE_ACCOUNT_TYPE = 0;
    private static final int REQUEST_ADD_ACCOUNT = 1;
    private static final String CHOOSE_ACCOUNT_TYPE_ACTION =
            "com.google.android.gms.common.account.CHOOSE_ACCOUNT_TYPE";

    private boolean mLaunchAccountTypePicker;

    private final AccountManagerCallback<Bundle> mCallback = new AccountManagerCallback<Bundle>() {
        @Override
        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Intent addAccountIntent = future.getResult()
                        .getParcelable(AccountManager.KEY_INTENT);
                if (addAccountIntent == null) {
                    Log.e(TAG, "Failed to retrieve add account intent from authenticator");
                    handleAddAccountError();
                } else {
                    startActivityForResult(addAccountIntent, REQUEST_ADD_ACCOUNT);
                }
            } catch (OperationCanceledException e) {
                Log.e(TAG, "Failed to get add account intent: " + e);
                handleAddAccountError();
            } catch (IOException e) {
                Log.e(TAG, "Failed to get add account intent: " + e);
                handleAddAccountError();
            } catch (AuthenticatorException e) {
                Log.e(TAG, "Failed to get add account intent: " + e);
                handleAddAccountError();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String accountType = getIntent().getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);
        if (accountType != null) {
            startAddAccount(accountType);
        } else {
            startAccountTypePicker();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (Activity.RESULT_CANCELED == resultCode) {
            setResult(resultCode);
            finish();
            return;
        }

        switch (requestCode) {
            case REQUEST_ADD_ACCOUNT:
                if (resultCode == Activity.RESULT_OK) {
                    setResult(resultCode);
                    finish();
                } else {
                    handleAddAccountError(resultCode);
                }
                break;
            case REQUEST_CHOOSE_ACCOUNT_TYPE:
                if (resultCode == Activity.RESULT_OK) {
                    String accountType = data.getExtras()
                            .getString(AccountManager.KEY_ACCOUNT_TYPE);
                    startAddAccount(accountType);
                } else {
                    setResult(resultCode);
                    finish();
                }
                break;
        }
    }

    private void startAccountTypePicker() {
        mLaunchAccountTypePicker = true;
        Intent i = new Intent(CHOOSE_ACCOUNT_TYPE_ACTION);
        startActivityForResult(i, REQUEST_CHOOSE_ACCOUNT_TYPE);
    }

    private void startAddAccount(String accountType) {
        mLaunchAccountTypePicker = false;
        AccountManager.get(this).addAccount(
                accountType,
                null, /* authTokenType */
                null, /* requiredFeatures */
                null, /* accountOptions */
                null, mCallback, null);
    }

    private void handleAddAccountError() {
        handleAddAccountError(Activity.RESULT_CANCELED);
    }

    private void handleAddAccountError(int resultCode) {
        if (mLaunchAccountTypePicker) {
            Log.e(TAG, "request add account failed to add account");
            // try again
            startAccountTypePicker();
        } else {
            setResult(resultCode);
            finish();
        }
    }
}
