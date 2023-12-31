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
package android.autofillservice.cts.activities;

import android.autofillservice.cts.R;

/**
 * Same as {@link LoginActivity}, but with username not important for autofill, password important
 * for autofill
 */
public class LoginNotImportantUsernameImportantPasswordActivity extends LoginActivity {

    @Override
    protected int getContentView() {
        return R.layout.login_activity_not_important_username_important_password;
    }
}
