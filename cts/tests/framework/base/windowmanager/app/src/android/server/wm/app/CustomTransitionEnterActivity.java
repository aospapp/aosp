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

import static android.server.wm.app.Components.CustomTransitionAnimations.BACKGROUND_COLOR;
import static android.server.wm.app.Components.CustomTransitionAnimations.BOTTOM_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.LEFT_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.RIGHT_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.TOP_EDGE_EXTENSION;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Activity to test that show background for activity transitions works
 */
public class CustomTransitionEnterActivity extends Activity {

    String mTransitionType;
    @ColorInt int mBackgroundColor = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.background_image);

        // Ensure the activity is edge-to-edge
        // In tests we rely on the activity's content filling the entire window
        getWindow().setDecorFitsSystemWindows(false);

        Intent intent = getIntent();
        Bundle bundle = intent.getExtras();
        mTransitionType = bundle.getString("transitionType");
        mBackgroundColor = bundle.getInt("backgroundColorOverride");
    }

    @Override
    protected void onResume() {
        super.onResume();
        switch (mTransitionType) {
            case BACKGROUND_COLOR:
                overridePendingTransition(R.anim.show_backdrop_hide_window_animation,
                        R.anim.show_backdrop_hide_window_animation, mBackgroundColor);
                break;
            case LEFT_EDGE_EXTENSION:
                overridePendingTransition(R.anim.edge_extension_left_window_animation,
                        R.anim.edge_extension_left_window_animation, mBackgroundColor);
                break;
            case TOP_EDGE_EXTENSION:
                overridePendingTransition(R.anim.edge_extension_top_window_animation,
                        R.anim.edge_extension_top_window_animation, mBackgroundColor);
                break;
            case RIGHT_EDGE_EXTENSION:
                overridePendingTransition(R.anim.edge_extension_right_window_animation,
                        R.anim.edge_extension_right_window_animation, mBackgroundColor);
                break;
            case BOTTOM_EDGE_EXTENSION:
                overridePendingTransition(R.anim.edge_extension_bottom_window_animation,
                        R.anim.edge_extension_bottom_window_animation, mBackgroundColor);
                break;
        }
    }
}
