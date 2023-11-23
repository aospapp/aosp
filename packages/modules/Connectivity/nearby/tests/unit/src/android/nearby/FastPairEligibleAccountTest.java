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

package android.nearby;

import static com.google.common.truth.Truth.assertThat;

import android.accounts.Account;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FastPairEligibleAccountTest {

    private static final Account ACCOUNT = new Account("abc@google.com", "type1");
    private static final Account ACCOUNT_NULL = null;

    private static final boolean OPT_IN_TRUE = true;

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetGetFastPairEligibleAccountNotNull() {
        FastPairEligibleAccount eligibleAccount =
                genFastPairEligibleAccount(ACCOUNT, OPT_IN_TRUE);

        assertThat(eligibleAccount.getAccount()).isEqualTo(ACCOUNT);
        assertThat(eligibleAccount.isOptIn()).isEqualTo(OPT_IN_TRUE);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testSetGetFastPairEligibleAccountNull() {
        FastPairEligibleAccount eligibleAccount =
                genFastPairEligibleAccount(ACCOUNT_NULL, OPT_IN_TRUE);

        assertThat(eligibleAccount.getAccount()).isEqualTo(ACCOUNT_NULL);
        assertThat(eligibleAccount.isOptIn()).isEqualTo(OPT_IN_TRUE);
    }

    /* Generates FastPairEligibleAccount. */
    private static FastPairEligibleAccount genFastPairEligibleAccount(
            Account account, boolean optIn) {
        FastPairEligibleAccount.Builder builder = new FastPairEligibleAccount.Builder();
        builder.setAccount(account);
        builder.setOptIn(optIn);

        return builder.build();
    }
}
