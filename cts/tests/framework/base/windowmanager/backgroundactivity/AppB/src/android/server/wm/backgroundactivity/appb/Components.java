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

package android.server.wm.backgroundactivity.appb;

import android.content.ComponentName;
import android.content.Context;
import android.server.wm.component.ComponentsBase;

import java.util.HashMap;

public class Components extends ComponentsBase {
    public static final String JAVA_PACKAGE_NAME = getPackageName(Components.class);
    private static final HashMap<String, Components> sPackageNameToComponents = new HashMap<>();

    public static Components get(Context context) {
        return get(context.getPackageName());
    }

    public static Components get(String packageName) {
        synchronized (sPackageNameToComponents) {
            return sPackageNameToComponents.computeIfAbsent(packageName, Components::new);
        }
    }
    /** Action constants for {@link #FOREGROUND_ACTIVITY}. */
    public static class ForegroundActivityAction {
        public final String LAUNCH_BACKGROUND_ACTIVITIES;
        public final String FINISH_ACTIVITY;

        public ForegroundActivityAction(String packageName) {
            LAUNCH_BACKGROUND_ACTIVITIES = packageName + ".ACTION_LAUNCH_BACKGROUND_ACTIVITIES";
            FINISH_ACTIVITY = packageName + ".ACTION_FINISH_ACTIVITY";
        }
    }

    /** Extra key constants for {@link #FOREGROUND_ACTIVITY}. */
    public static class ForegroundActivityExtra {
        // Keep in sync with  android.server.wm.backgroundactivity.common.CommonComponents
        // .CommonForegroundActivityExtras
        public final String ACTIVITY_ID = "ACTIVITY_ID_EXTRA";
        public final String LAUNCH_INTENTS = "LAUNCH_INTENTS_EXTRA";
        public final String FINISH_FIRST = "FINISH_FIRST_EXTRA";
    }

    /** Extra key constants for {@link #START_PENDING_INTENT_ACTIVITY} */
    public static class StartPendingIntentActivityExtra {
        /**
         * If present starts the pending intent with a bundle created from ActivityOptions where
         * setBackgroundActivityLaunchAllowed() was set to this value.
         */
        public final String ALLOW_BAL = "ALLOW_BAL_EXTRA";

        /** Start the pending intent with a `null` bundle if no options are set. */
        public final String USE_NULL_BUNDLE = "USE_NULL_BUNDLE";
    }

    /** Extra key constants for {@link #START_PENDING_INTENT_RECEIVER} */
    public static class StartPendingIntentReceiverExtra {
        public final String PENDING_INTENT = "PENDING_INTENT_EXTRA";
    }

    // TODO(b/263368846) rename to camelCase
    public final String APP_PACKAGE_NAME;
    public final ComponentName FOREGROUND_ACTIVITY;
    public final ComponentName START_PENDING_INTENT_ACTIVITY;
    public final ComponentName START_PENDING_INTENT_RECEIVER;

    public final ForegroundActivityAction FOREGROUND_ACTIVITY_ACTIONS;
    public final ForegroundActivityExtra FOREGROUND_ACTIVITY_EXTRA = new ForegroundActivityExtra();

    public final StartPendingIntentActivityExtra START_PENDING_INTENT_ACTIVITY_EXTRA =
            new StartPendingIntentActivityExtra();
    public final StartPendingIntentReceiverExtra START_PENDING_INTENT_RECEIVER_EXTRA =
            new StartPendingIntentReceiverExtra();

    public Components(String appPackageName) {
        APP_PACKAGE_NAME = appPackageName;

        FOREGROUND_ACTIVITY =
                component(APP_PACKAGE_NAME, "ForegroundActivity");
        START_PENDING_INTENT_ACTIVITY =
                component(APP_PACKAGE_NAME, "StartPendingIntentActivity");
        START_PENDING_INTENT_RECEIVER =
                component(APP_PACKAGE_NAME, "StartPendingIntentReceiver");

        FOREGROUND_ACTIVITY_ACTIONS = new ForegroundActivityAction(APP_PACKAGE_NAME);
    }

    private ComponentName component(String packageName, String className) {
        String fullClassName = JAVA_PACKAGE_NAME + "." + className;
        return new ComponentName(packageName, fullClassName);
    }
}
