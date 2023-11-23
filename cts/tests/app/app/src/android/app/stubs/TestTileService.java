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
 * limitations under the License.
 */

package android.app.stubs;

import android.content.ComponentName;
import android.os.Debug;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

public class TestTileService extends TileService {

    public static final String TAG = "TestTileService";
    public static final String PKG = "android.app.stubs";
    public static final int ICON_ID = R.drawable.robot;

    private static TestTileService sTestTileService = null;
    AtomicBoolean isConnected = new AtomicBoolean(false);
    AtomicBoolean isListening = new AtomicBoolean(false);
    AtomicBoolean hasBeenClicked = new AtomicBoolean(false);

    public static String getId() {
        return String.format("%s/%s", TestTileService.class.getPackage().getName(),
                TestTileService.class.getName());
    }

    public static ComponentName getComponentName() {
        return new ComponentName(TestTileService.class.getPackage().getName(),
                TestTileService.class.getName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    public static TileService getInstance() {
        return sTestTileService;
    }

    public static boolean isConnected() {
        return sTestTileService != null && sTestTileService.isConnected.get();
    }

    public static boolean isListening() {
        return sTestTileService.isListening.get();
    }

    public static boolean hasBeenClicked() {
        return sTestTileService.hasBeenClicked.get();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        isListening.set(true);
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        isListening.set(false);
    }

    @Override
    public void onClick() {
        super.onClick();
        hasBeenClicked.set(true);
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        sTestTileService = this;
        isConnected.set(true);
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        sTestTileService = null;
        isConnected.set(false);
        isListening.set(false);
        hasBeenClicked.set(false);
    }
}
