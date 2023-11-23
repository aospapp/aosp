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

package android.server.wm.backgroundactivity.appa;

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

    /** Action constants for {@link #FOREGROUND_EMBEDDING_ACTIVITY}. */
    public static class ForegroundEmbeddedActivityAction {
        public final String LAUNCH_EMBEDDED_ACTIVITY;
        public final String FINISH_ACTIVITY;

        public ForegroundEmbeddedActivityAction(String packageName) {
            LAUNCH_EMBEDDED_ACTIVITY = packageName + ".ACTION_LAUNCH_EMBEDDED_ACTIVITY";
            FINISH_ACTIVITY = packageName + ".ACTION_FINISH_ACTIVITY";
        }
    }

    /** Action constants for {@link #LAUNCH_INTO_PIP_ACTIVITY}. */
    public static class LaunchIntoPipActivityAction {
        public final String LAUNCH_INTO_PIP;

        public LaunchIntoPipActivityAction(String packageName) {
            LAUNCH_INTO_PIP = packageName + ".ACTION_LAUNCH_INTO_PIP";
        }
    }

    /** Extra key constants for {@link #FOREGROUND_ACTIVITY}. */
    public static class ForegroundActivityExtra {
        public final String LAUNCH_BACKGROUND_ACTIVITY =
                "LAUNCH_BACKGROUND_ACTIVITY_EXTRA";
        public final String LAUNCH_SECOND_BACKGROUND_ACTIVITY =
                "LAUNCH_SECOND_BACKGROUND_ACTIVITY_EXTRA";
        public final String RELAUNCH_FOREGROUND_ACTIVITY_EXTRA =
                "RELAUNCH_FOREGROUND_ACTIVITY_EXTRA";
        public final String START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS =
                "START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS_EXTRA";
        public final String START_ACTIVITY_FROM_FG_ACTIVITY_NEW_TASK =
                "START_ACTIVITY_FROM_FG_ACTIVITY_NEW_TASK_EXTRA";

        // Keep in sync with  android.server.wm.backgroundactivity.common.CommonComponents
        // .CommonForegroundActivityExtras
        public final String ACTIVITY_ID = "ACTIVITY_ID_EXTRA";
        public final String LAUNCH_INTENTS = "LAUNCH_INTENTS_EXTRA";
        public final String FINISH_FIRST = "FINISH_FIRST_EXTRA";
    }

    /** Extra key constants for {@link #SEND_PENDING_INTENT_RECEIVER} */
    public static class SendPendingIntentReceiverExtra {
        public final String IS_BROADCAST = "IS_BROADCAST_EXTRA";
        public final String APP_B_PACKAGE = "APP_B_PACKAGE_EXTRA";

        /**
         * Create the intent with BAL set to the explicit value.
         *
         * <p>This should not have any effect as the ActivityOptions on the Intent are not used when
         * starting the PendingIntent.
         */
        public final String ALLOW_BAL_EXTRA_ON_PENDING_INTENT =
                "ALLOW_BAL_EXTRA_ON_PENDING_INTENT";
    }

    /** Extra key constants for {@link #START_ACTIVITY_RECEIVER} */
    public static class StartActivityReceiverExtra {
        public final String START_ACTIVITY_DELAY_MS =
                "START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS_EXTRA";
    }

    // TODO(b/263368846) rename to camelCase
    public final String APP_PACKAGE_NAME;
    public final ComponentName BACKGROUND_ACTIVITY;
    public final ComponentName SECOND_BACKGROUND_ACTIVITY;
    public final ComponentName FOREGROUND_ACTIVITY;
    public final ComponentName FOREGROUND_EMBEDDING_ACTIVITY;
    public final ComponentName SEND_PENDING_INTENT_RECEIVER;
    public final ComponentName START_ACTIVITY_RECEIVER;
    public final ComponentName SIMPLE_ADMIN_RECEIVER;
    public final ComponentName BACKGROUND_ACTIVITY_TEST_SERVICE;
    public final ComponentName ACTIVITY_START_SERVICE;
    public final ComponentName PIP_ACTIVITY;
    public final ComponentName LAUNCH_INTO_PIP_ACTIVITY;
    public final ComponentName RELAUNCHING_ACTIVITY;
    public final ComponentName VIRTUAL_DISPLAY_ACTIVITY;
    public final ComponentName WIDGET_CONFIG_TEST_ACTIVITY;
    public final ComponentName WIDGET_PROVIDER;

    public final ForegroundActivityAction FOREGROUND_ACTIVITY_ACTIONS;
    public final ForegroundActivityExtra FOREGROUND_ACTIVITY_EXTRA = new ForegroundActivityExtra();

    public final ForegroundEmbeddedActivityAction FOREGROUND_EMBEDDING_ACTIVITY_ACTIONS;

    public final LaunchIntoPipActivityAction LAUNCH_INTO_PIP_ACTIONS;

    public final SendPendingIntentReceiverExtra SEND_PENDING_INTENT_RECEIVER_EXTRA =
            new SendPendingIntentReceiverExtra();
    public final StartActivityReceiverExtra START_ACTIVITY_RECEIVER_EXTRA =
            new StartActivityReceiverExtra();

    public Components(String appPackageName) {
        APP_PACKAGE_NAME = appPackageName;

        BACKGROUND_ACTIVITY =
                component(APP_PACKAGE_NAME, "BackgroundActivity");
        SECOND_BACKGROUND_ACTIVITY =
                component(APP_PACKAGE_NAME, "SecondBackgroundActivity");
        FOREGROUND_ACTIVITY =
                component(APP_PACKAGE_NAME, "ForegroundActivity");
        FOREGROUND_EMBEDDING_ACTIVITY =
                component(APP_PACKAGE_NAME, "ForegroundEmbeddingActivity");
        SEND_PENDING_INTENT_RECEIVER =
                component(APP_PACKAGE_NAME, "SendPendingIntentReceiver");
        START_ACTIVITY_RECEIVER =
                component(APP_PACKAGE_NAME, "StartBackgroundActivityReceiver");
        SIMPLE_ADMIN_RECEIVER =
                component(APP_PACKAGE_NAME, "SimpleAdminReceiver");
        BACKGROUND_ACTIVITY_TEST_SERVICE =
                component(APP_PACKAGE_NAME, "BackgroundActivityTestService");
        PIP_ACTIVITY =
                component(APP_PACKAGE_NAME, "PipActivity");
        LAUNCH_INTO_PIP_ACTIVITY =
                component(APP_PACKAGE_NAME, "LaunchIntoPipActivity");
        RELAUNCHING_ACTIVITY =
                component(APP_PACKAGE_NAME, "RelaunchingActivity");
        VIRTUAL_DISPLAY_ACTIVITY =
                component(APP_PACKAGE_NAME, "VirtualDisplayActivity");
        WIDGET_CONFIG_TEST_ACTIVITY =
                component(APP_PACKAGE_NAME, "WidgetConfigTestActivity");
        WIDGET_PROVIDER =
                component(APP_PACKAGE_NAME, "WidgetProvider");
        ACTIVITY_START_SERVICE =
                component(APP_PACKAGE_NAME, "ActivityStarterService");

        FOREGROUND_ACTIVITY_ACTIONS = new ForegroundActivityAction(APP_PACKAGE_NAME);
        FOREGROUND_EMBEDDING_ACTIVITY_ACTIONS =
                new ForegroundEmbeddedActivityAction(APP_PACKAGE_NAME);
        LAUNCH_INTO_PIP_ACTIONS = new LaunchIntoPipActivityAction(APP_PACKAGE_NAME);
    }

    private ComponentName component(String packageName, String className) {
        String fullClassName = JAVA_PACKAGE_NAME + "." + className;
        return new ComponentName(packageName, fullClassName);
    }
}
