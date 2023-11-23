/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.devicepolicy.cts;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.os.UserManager;
import android.util.Log;
import android.widget.TextView;

/**
 * An activity that displays the serial number of the user that it is running into.
 */
public final class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView mTextView;
    private long mSerialNumber;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        setContentView(R.layout.main);
        mTextView = findViewById(R.id.user_textview);

        UserManager userManager = getSystemService(UserManager.class);
        mSerialNumber = userManager.getSerialNumberForUser(Process.myUserHandle());

        Log.v(TAG, "onCreate(): serial number is " + mSerialNumber);
    }

    @Override
    public void onStart() {
        super.onStart();

        Log.v(TAG, "updateState(): displaying serial number as " + mSerialNumber);
        mTextView.setText(Long.toString(mSerialNumber));
    }
}