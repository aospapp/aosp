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

package com.android.systemui.car.displayarea;

import static android.car.settings.CarSettings.Secure.KEY_SETUP_WIZARD_IN_PROGRESS;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;

import javax.inject.Inject;

/**
 * Dagger Subcomponent for DisplayAreas within SysUI.
 */
@SysUISingleton
public class DisplayAreaComponent extends CoreStartable {
    public static final String TAG = "DisplayAreaComponent";
    // action name for the intent when to update the foreground DA visibility
    public static final String DISPLAY_AREA_VISIBILITY_CHANGED =
            "DISPLAY_AREA_VISIBILITY_CHANGED";
    // key name for the intent's extra that tells the DA's visibility status
    public static final String INTENT_EXTRA_IS_DISPLAY_AREA_VISIBLE =
            "EXTRA_IS_DISPLAY_AREA_VISIBLE";

    private final CarDisplayAreaController mCarDisplayAreaController;

    private final Context mContext;
    private int mCurrentUser;
    private boolean mIsDefaultTdaFullScreen;

    @Inject
    public DisplayAreaComponent(Context context,
            CarDisplayAreaController carDisplayAreaController) {
        super(context);
        mContext = context;
        mCarDisplayAreaController = carDisplayAreaController;
    }

    @Override
    public void start() {
        logIfDebuggable("start:");
        if (CarDisplayAreaUtils.isCustomDisplayPolicyDefined(mContext)) {
            // Register the DA's
            mCarDisplayAreaController.register();

            // add a receiver to listen to ACTION_USER_SWITCHED when user is switching. We would
            // make the default foreground DA full screen when SUW is being presented to the user.
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_SWITCHED);
            // When SUW is in progress, make foregroundDA fullscreen.
            final Handler handler = new Handler();
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // ignore user 0 -> USER_SYSTEM and USER_ALL for suw
                        if (mCurrentUser != UserHandle.USER_ALL
                                && mCurrentUser != UserHandle.USER_SYSTEM) {
                            int res = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                                    KEY_SETUP_WIZARD_IN_PROGRESS, mCurrentUser);
                            logIfDebuggable("SUW in progress: " + (res == 1));
                            // res == 1 -> SUW in progress
                            if (res == 1 && !mIsDefaultTdaFullScreen) {
                                if (!mCarDisplayAreaController.isForegroundDaVisible()) {
                                    mCarDisplayAreaController.hideTitleBar();
                                    mCarDisplayAreaController.makeForegroundDaVisible(true);
                                }
                                mCarDisplayAreaController.makeForegroundDAFullScreen();
                                mIsDefaultTdaFullScreen = true;
                            } else if (res == 0 && mIsDefaultTdaFullScreen) {
                                // reset
                                mCarDisplayAreaController.makeForegroundDaVisible(false);
                                mCarDisplayAreaController.setDefaultBounds();
                                mCarDisplayAreaController.showTitleBar();
                                mIsDefaultTdaFullScreen = false;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, " error finding SETUP_WIZARD_IN_PROGRESS ", e);
                    } finally {
                        if (mIsDefaultTdaFullScreen) {
                            // only poll this when default TDA is full screen. We want to check
                            // the progress of suw every second until the user exits the suw.
                            handler.postDelayed(this, 1000);
                        }
                    }
                }
            };

            mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mCurrentUser = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_ALL);
                    logIfDebuggable("ACTION_USER_SWITCHED received current user: " + mCurrentUser);
                    handler.post(runnable);
                }
            }, filter, null, null);
        }
    }

    /**
     * enum to define the state of display area possible.
     * CONTROL_BAR state is when only control bar is visible.
     * FULL state is when display area hosting default apps  cover the screen fully.
     * DEFAULT state where maps are shown above DA for default apps.
     */
    public enum FOREGROUND_DA_STATE {
        CONTROL_BAR, DEFAULT, FULL
    }

    private static void logIfDebuggable(String message) {
        if (Build.IS_DEBUGGABLE) {
            Log.d(TAG, message);
        }
    }
}
