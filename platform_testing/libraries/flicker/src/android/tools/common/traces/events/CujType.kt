/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.tools.common.traces.events

import kotlin.js.JsExport

/**
 * From com.android.internal.jank.InteractionJankMonitor.
 *
 * NOTE: Make sure order is the same as in {@see com.android.internal.jank.InteractionJankMonitor}.
 */
@JsExport
enum class CujType {
    CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE,
    CUJ_NOTIFICATION_SHADE_EXPAND_COLLAPSE_LOCK,
    CUJ_NOTIFICATION_SHADE_SCROLL_FLING,
    CUJ_NOTIFICATION_SHADE_ROW_EXPAND,
    CUJ_NOTIFICATION_SHADE_ROW_SWIPE,
    CUJ_NOTIFICATION_SHADE_QS_EXPAND_COLLAPSE,
    CUJ_NOTIFICATION_SHADE_QS_SCROLL_SWIPE,
    CUJ_LAUNCHER_APP_LAUNCH_FROM_RECENTS,
    CUJ_LAUNCHER_APP_LAUNCH_FROM_ICON,
    CUJ_LAUNCHER_APP_CLOSE_TO_HOME,
    CUJ_LAUNCHER_APP_CLOSE_TO_PIP,
    CUJ_LAUNCHER_QUICK_SWITCH,
    CUJ_NOTIFICATION_HEADS_UP_APPEAR,
    CUJ_NOTIFICATION_HEADS_UP_DISAPPEAR,
    CUJ_NOTIFICATION_ADD,
    CUJ_NOTIFICATION_REMOVE,
    CUJ_NOTIFICATION_APP_START,
    CUJ_LOCKSCREEN_PASSWORD_APPEAR,
    CUJ_LOCKSCREEN_PATTERN_APPEAR,
    CUJ_LOCKSCREEN_PIN_APPEAR,
    CUJ_LOCKSCREEN_PASSWORD_DISAPPEAR,
    CUJ_LOCKSCREEN_PATTERN_DISAPPEAR,
    CUJ_LOCKSCREEN_PIN_DISAPPEAR,
    CUJ_LOCKSCREEN_TRANSITION_FROM_AOD,
    CUJ_LOCKSCREEN_TRANSITION_TO_AOD,
    CUJ_LAUNCHER_OPEN_ALL_APPS,
    CUJ_LAUNCHER_ALL_APPS_SCROLL,
    CUJ_LAUNCHER_APP_LAUNCH_FROM_WIDGET,
    CUJ_SETTINGS_PAGE_SCROLL,
    CUJ_LOCKSCREEN_UNLOCK_ANIMATION,
    CUJ_SHADE_APP_LAUNCH_FROM_HISTORY_BUTTON,
    CUJ_SHADE_APP_LAUNCH_FROM_MEDIA_PLAYER,
    CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE,
    CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON,
    CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
    CUJ_PIP_TRANSITION,
    CUJ_WALLPAPER_TRANSITION,
    CUJ_USER_SWITCH,
    CUJ_SPLASHSCREEN_AVD,
    CUJ_SPLASHSCREEN_EXIT_ANIM,
    CUJ_SCREEN_OFF,
    CUJ_SCREEN_OFF_SHOW_AOD,
    CUJ_ONE_HANDED_ENTER_TRANSITION,
    CUJ_ONE_HANDED_EXIT_TRANSITION,
    CUJ_UNFOLD_ANIM,
    CUJ_SUW_LOADING_TO_SHOW_INFO_WITH_ACTIONS,
    CUJ_SUW_SHOW_FUNCTION_SCREEN_WITH_ACTIONS,
    CUJ_SUW_LOADING_TO_NEXT_FLOW,
    CUJ_SUW_LOADING_SCREEN_FOR_STATUS,
    CUJ_SPLIT_SCREEN_ENTER, // Setup to enter splitscreen in launcher layer
    CUJ_SPLIT_SCREEN_EXIT,
    CUJ_LOCKSCREEN_LAUNCH_CAMERA,
    CUJ_SPLIT_SCREEN_RESIZE,
    CUJ_SETTINGS_SLIDER,
    CUJ_TAKE_SCREENSHOT,
    CUJ_VOLUME_CONTROL,
    CUJ_BIOMETRIC_PROMPT_TRANSITION,
    CUJ_SETTINGS_TOGGLE,
    CUJ_SHADE_DIALOG_OPEN,
    CUJ_USER_DIALOG_OPEN,
    CUJ_TASKBAR_EXPAND,
    CUJ_TASKBAR_COLLAPSE,
    CUJ_SHADE_CLEAR_ALL,
    CUJ_LAUNCHER_UNLOCK_ENTRANCE_ANIMATION,
    CUJ_LOCKSCREEN_OCCLUSION,
    CUJ_RECENTS_SCROLLING,
    CUJ_LAUNCHER_APP_SWIPE_TO_RECENTS,
    CUJ_LAUNCHER_CLOSE_ALL_APPS_SWIPE,
    CUJ_LAUNCHER_CLOSE_ALL_APPS_TO_HOME,
    CUJ_IME_INSETS_ANIMATION,

    // KEEP AS LAST TYPE
    // used to handle new types that haven't been added here yet but might be dumped by the platform
    UNKNOWN;

    companion object {
        fun from(eventId: Int): CujType {
            // -1 to account for unknown event type
            if (eventId >= values().size - 1) {
                return UNKNOWN
            }
            return values()[eventId]
        }
    }
}
