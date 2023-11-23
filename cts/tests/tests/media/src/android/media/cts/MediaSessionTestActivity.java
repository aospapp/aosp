/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.media.cts;

import android.app.Activity;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.view.KeyEvent;

/**
 * Activity for testing foreground activity behavior with the
 * {@link android.media.session.MediaSession}.
 */
public class MediaSessionTestActivity extends Activity {
    public static final String KEY_SESSION_TOKEN = "KEY_SESSION_TOKEN";

    @Override
    protected void onResume() {
        super.onResume();
        MediaSession.Token token = getIntent().getParcelableExtra(KEY_SESSION_TOKEN);
        if (token != null) {
            MediaController controller = new MediaController(this, token);
            setMediaController(controller);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        setMediaController(null);
    }
}
