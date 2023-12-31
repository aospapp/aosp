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

package android.server.wm.app;

import static android.server.wm.app.Components.TestInteractiveLiveWallpaperKeys.COMPONENT;
import static android.server.wm.app.Components.TestInteractiveLiveWallpaperKeys.LAST_RECEIVED_MOTION_EVENT;

import android.server.wm.TestJournalProvider;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;

public class InteractiveLiveWallpaper extends WallpaperService {

    @Override
    public Engine onCreateEngine() {
        return new WallpaperEngine();
    }

    private class WallpaperEngine extends Engine {

        WallpaperEngine() {
            setTouchEventsEnabled(true);
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            TestJournalProvider.putExtras(getBaseContext(),
                    COMPONENT, bundle -> {
                        bundle.putParcelable(LAST_RECEIVED_MOTION_EVENT, event);
                    });
        }
    }
}
