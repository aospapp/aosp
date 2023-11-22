/*
 * Copyright (c) 2015, Motorola Mobility LLC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     - Neither the name of Motorola Mobility nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL MOTOROLA MOBILITY LLC BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

package com.android.service.ims.presence;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.ContactsContract;
import com.android.ims.internal.Logger;

public class AccountUtil {
    private static Logger logger = Logger.getLogger("AccountUtil");

    public static final String ACCOUNT_SETTING = "accountSetting";
    public static final String VTCALL_SETTING = "vtcall_enable"; // "vtCallSetting";
    public static final String PRESENCE_SETTING = "presence_enable";// "presenceSetting";
    public static final String ACCOUNT_TYPE = "com.android.rcs.eab.account";
    public static final String AUTHTOKEN_TYPE = "com.android.rcs.eab.account";
    public static final String ACCOUNT_NAME = " ";

    public static Account addRcsAccount(Context context) {
        AccountManager mAccountManager = null;
        mAccountManager = AccountManager.get(context);
        Account[] vCallingAccounts = mAccountManager
                .getAccountsByType(ACCOUNT_TYPE);

        if (vCallingAccounts.length == 0) {
            logger.debug("Creating Video Calling Account");

            Bundle bundle = new Bundle();
            bundle.putString("pretty_name",
                    context.getString(R.string.account_pretty_name));

            Account vCallingAccount = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
            if (!mAccountManager.addAccountExplicitly(vCallingAccount, null,
                    null)) {
                logger.error("addRcsAccount() failed!");
                return null;
            }

            ContentResolver.setIsSyncable(vCallingAccount,
                    ContactsContract.AUTHORITY, 0);
            ContentResolver.setSyncAutomatically(vCallingAccount,
                    ContactsContract.AUTHORITY, false);

            logger.debug("Video Calling account created succesfuly");
            return vCallingAccount;
        } else {
            Account vCallingAccount = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
            ContentResolver.setIsSyncable(vCallingAccount,
                    ContactsContract.AUTHORITY, 0);

            ContentResolver.setSyncAutomatically(vCallingAccount,
                    ContactsContract.AUTHORITY, false);
            logger.debug("Video Calling Account already exists");
            return vCallingAccount;
        }
    }

    public static boolean removeRcsAccount(Context context) {
        logger.debug("removeRcsAccount()");
        AccountManager mAccountManager = null;
        boolean result = false;
        mAccountManager = AccountManager.get(context);
        Account[] vCallingAccounts = mAccountManager
                .getAccountsByType(ACCOUNT_TYPE);

        if (vCallingAccounts.length == 0) {
            logger.debug("Video Calling Account is not present. Do nothing.");
        } else {
            Account vCallingAccount = new Account(ACCOUNT_NAME, ACCOUNT_TYPE);
            mAccountManager.removeAccount(vCallingAccount, null, null);
            logger.debug("Video Calling Account removed.");
        }
        return result;
    }
}
