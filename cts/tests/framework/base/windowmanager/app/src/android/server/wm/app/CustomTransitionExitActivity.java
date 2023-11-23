/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.server.wm.app;

import static android.server.wm.app.Components.BackgroundActivityTransition.TRANSITION_REQUESTED;
import static android.server.wm.app.Components.CUSTOM_TRANSITION_EXIT_ACTIVITY;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.server.wm.TestJournalProvider;

/**
 * Activity to test that show background for activity transitions works
 */
public class CustomTransitionExitActivity extends Activity {

    String mTransitionType;
    int mBackgroundColor = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.background_image);

        // Ensure the activity is edge-to-edge
        // In tests we rely on the activity's content filling the entire window
        getWindow().setDecorFitsSystemWindows(false);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        mTransitionType = bundle.getString("transitionType");
        mBackgroundColor = bundle.getInt("backgroundColorOverride", 0);

        // Delay the starting the activity so we don't skip the transition.
        startActivityDelayed();
    }

    private void startActivityDelayed() {
        Runnable r = () -> {
            // Notify the test journal that we are starting the activity transition
            TestJournalProvider.putExtras(
                    getBaseContext(), CUSTOM_TRANSITION_EXIT_ACTIVITY, bundle -> {
                        bundle.putBoolean(TRANSITION_REQUESTED,
                                true);
                    });
            final Intent i = new Intent(
                    CustomTransitionExitActivity.this,
                    CustomTransitionEnterActivity.class);
            i.putExtra("transitionType", mTransitionType);
            i.putExtra("backgroundColorOverride", mBackgroundColor);
            startActivity(i);
        };

        Handler h = new Handler();
        h.postDelayed(r, 1000);
    }
}
