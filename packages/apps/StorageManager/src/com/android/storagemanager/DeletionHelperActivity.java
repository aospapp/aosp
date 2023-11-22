/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import com.android.storagemanager.deletionhelper.DeletionHelperSettings;

/**
 * The DeletionHelperActivity is an activity for deleting apps, photos, and downloaded files which
 * have not been recently used.
 */
public class DeletionHelperActivity extends Activity implements ButtonBarProvider {
    private ViewGroup mButtonBar;
    private Button mNextButton, mSkipButton;
    private Fragment f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_main_prefs);

        // If we are not returning from an existing activity, create a new fragment.
        if (savedInstanceState == null) {
            FragmentManager manager = getFragmentManager();
            f = DeletionHelperSettings.newInstance();
            FragmentTransaction transaction = manager.beginTransaction();
            transaction.replace(R.id.main_content, f);
            transaction.commit();
        }

        mButtonBar = (ViewGroup) findViewById(R.id.button_bar);
        mNextButton = (Button) findViewById(R.id.next_button);
        mSkipButton = (Button) findViewById(R.id.skip_button);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[],
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        f.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public ViewGroup getButtonBar() {
        return mButtonBar;
    }

    @Override
    public Button getNextButton() {
        return mNextButton;
    }

    @Override
    public Button getSkipButton() {
        return mSkipButton;
    }
}